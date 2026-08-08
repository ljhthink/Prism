package io.prism.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.prism.data.ProviderConfig
import io.prism.security.ApiKeyRepository
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI 兼容 Provider 的流式请求实现（ADR-004 4.3）。
 *
 * 负责将 [ProviderConfig] 组装为 `/v1/chat/completions` 的 SSE 请求并消费流式响应，
 * 将解析结果以 [StreamEvent] 流暴露给调用方。
 *
 * **组装规则**：
 * - 端点：`baseUrl.trimEnd('/') + "/chat/completions"`
 * - 鉴权：`Authorization: Bearer <apiKeyRef 明文>`（无 key 时跳过）
 * - 自定义头：合并 [ProviderConfig.headers]，不覆盖 Authorization / Content-Type
 * - 请求体：`model` / `messages` / `stream=true`
 *
 * **错误处理**：网络异常、鉴权失败、流中断统一发射 [StreamEvent.Error]，不外抛。
 * 结束 chunk 空 `choices[]` 视为流结束，发射 [StreamEvent.Done]，不崩溃。
 *
 * **可测性**（ADR-004 4.7）：Ktor 的 SSE 客户端插件要求引擎声明 `SSECapability`，
 * 而 `MockEngine` 不支持该能力（且因 `internal constructor` 无法在 kapt 下子类化）。
 * 因此将请求组装与 SSE 解析抽离为 `internal` 纯函数（[buildEndpoint] / [buildAuthHeader] /
 * [buildRequestBody] / [applyCustomHeaders] / [parseChunkData]），单元测试直接覆盖核心逻辑；
 * 端到端流式路径由真实 Ktor SSE 服务器集成测试验证。
 */
class OpenAICompatibleProvider(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository
) : ChatStreamProvider {

    /** 编译时 JSON，忽略未知字段（如 `reasoning_content`），空安全。 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 发起流式对话请求。
     *
     * **US-019 扩展**（ADR-012 5.4）：新增 [systemPrompt] / [ragContext] 可选参数。
     * - [systemPrompt] 非空时，在 messages 列表最前插入 `MessageBody("system", systemPrompt)`
     * - [ragContext] 非空时，作为独立 user 消息插在最后一条 user 消息前（RAG context 必须在用户问题之前，
     *   让模型把 context 与 question 关联）
     *
     * **M4 Phase A 接口对齐**（ADR-014 5.2）：override 签名新增 [tools] / [toolChoice] 参数以满足
     * [ChatStreamProvider] 契约。**Phase A 仅对齐签名，不序列化 tools 到请求体**——tool_calling 的
     * 请求序列化与流式 delta 状态机解析属 Phase C（US-024）。当前所有调用方均传 null（默认），
     * 故行为与 US-019 完全一致（向后兼容）。非 null tools 在 Phase A 会被忽略，Phase C 将完整实现。
     *
     * @param config 目标 Provider 配置（含 baseUrl / apiKeyRef / headers / models）
     * @param messages 对话历史（[ChatMessage] 转换为请求体消息）
     * @param systemPrompt system 消息内容（可选，RAG grounding rules）；null 时不注入
     * @param ragContext RAG context 文本（可选，知识库片段拼接）；null 时不注入
     * @param tools 工具定义列表（M4，Phase A 接受但暂不序列化；Phase C US-024 实现）
     * @param toolChoice 工具选择策略（M4，Phase A 接受但暂不序列化；Phase C US-024 实现）
     * @return [StreamEvent] 流：增量 [StreamEvent.Delta] → 结束 [StreamEvent.Done] / 错误 [StreamEvent.Error]
     */
    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>?,
        toolChoice: ToolChoice?
    ): Flow<StreamEvent> = flow {
        // G-01 修复（guardrail TKN-M4-PHASEA-GUARDRAIL-001）：Phase A 接受 tools/toolChoice 但不序列化，
        // 非 null 时发 Log.w 使中间态可观测，避免「静默降级」（Phase C US-024 实现完整 tool_calling）。
        if (tools != null) {
            Log.w(TAG, "tools 非空但 Phase A 未实现 tool_calling 序列化，已忽略（Phase C US-024 实现）")
        }
        val endpoint = buildEndpoint(config.baseUrl)
        val apiKey = if (config.apiKeyRef.isNotBlank()) {
            apiKeyRepository.readApiKeyOnce(config.apiKeyRef)
        } else {
            null
        }

        var terminated = false
        try {
            httpClient.sse(endpoint, {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                // 鉴权头优先级：apiKeyRef 明文 > 自定义 Authorization 头。
                // apiKeyRef 为空时回退使用自定义 Authorization 头，避免无任何鉴权头（CR-06 残留，发现 4）
                buildAuthHeader(apiKey)?.let { header(HttpHeaders.Authorization, it) }
                    ?: customAuthHeader(config.headers)?.let { header(HttpHeaders.Authorization, it) }
                // 合并自定义头，Authorization / Content-Type 以本请求为准
                applyCustomHeaders(this, config.headers)
                setBody(buildRequestBody(config, messages, systemPrompt, ragContext))
            }) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    val parsed = parseChunkData(data) ?: return@collect
                    if (parsed is StreamEvent.Done) terminated = true
                    emit(parsed)
                }
            }
        } catch (e: CancellationException) {
            // 协程取消必须重新抛出，不得吞掉（结构化并发，CR-01）
            throw e
        } catch (e: SSEClientException) {
            // SSE 插件对非 200 响应一律抛 SSEClientException（无论 expectSuccess 值），
            // 从 response 读取状态码区分 401 鉴权失败与其他 4xx（US-007 LOW 修复）。
            emit(mapHttpError(e.response?.status?.value ?: -1))
            return@flow
        } catch (e: ClientRequestException) {
            // 兜底：非 SSE 路径（或未来非流式请求）的 4xx 仍按状态码映射。
            emit(mapHttpError(e.response.status.value))
            return@flow
        } catch (e: Exception) {
            // 网络/协议错误映射为通用文案，避免内部路径/异常细节泄露（CR-05）
            emit(StreamEvent.Error("网络请求失败，请检查网络连接或 Provider 配置"))
            return@flow
        }
        // SSE 流正常结束但未收到 Done（如服务端关闭连接）—— 兜底补发 Done
        if (!terminated) emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)

    // ==================== 可测试纯函数（ADR-004 4.7） ====================

    /** 拼接 chat/completions 端点，去除尾部斜杠。 */
    internal fun buildEndpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/chat/completions"

    /** 组装 Authorization 头值；空白或无 key 返回 null（省略鉴权头）。 */
    internal fun buildAuthHeader(apiKey: String?): String? =
        apiKey?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    /**
     * 序列化请求体（model / messages / stream=true）。取首个模型，空则回退空串交服务端校验。
     *
     * **US-019 注入规则**（ADR-012 5.4）：
     * - [systemPrompt] 非空：在 messages 最前插入 `MessageBody("system", systemPrompt)`
     * - [ragContext] 非空：作为独立 user 消息插在**最后一条 user 消息之前**。
     *   选择「最后一条 user 消息之前」而非「messages 末尾」的原因：messages 末尾一定是用户本轮问题
     *   （ConversationViewModel.sendMessage 已保证），RAG context 须紧贴用户问题之前，让模型
     *   把 context 与 question 关联（OpenAI Chat Completions 不持久化状态，每轮重发完整序列）。
     * - 两者为空时维持原行为（向后兼容，既有调用零改动）。
     *
     * **测试覆盖**：[OpenAICompatibleProviderTest] 含 system 注入 / ragContext 注入 / 两者均注入 /
     * 均不注入四个分支单元测试。
     */
    internal fun buildRequestBody(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        ragContext: String? = null
    ): String {
        val requestMessages = buildList {
            // 1. system 消息前置（若有）
            if (!systemPrompt.isNullOrBlank()) {
                add(MessageBody(SYSTEM_ROLE, systemPrompt))
            }
            // 2. 转换对话历史
            messages.forEach { add(MessageBody(it.role.toRequestRole(), it.content)) }
            // 3. ragContext 插在最后一条 user 消息之前（若有）
            if (!ragContext.isNullOrBlank()) {
                val lastUserIndex = indexOfLast { it.role == USER_ROLE }
                if (lastUserIndex == -1) {
                    // 无 user 消息（异常路径）：直接追加 context 到末尾，由服务端处理
                    add(MessageBody(USER_ROLE, ragContext))
                } else {
                    // 在最后一条 user 消息前插入 context user 消息
                    add(lastUserIndex, MessageBody(USER_ROLE, ragContext))
                }
            }
        }
        val body = ChatCompletionRequest(
            model = config.models.firstOrNull() ?: "",
            messages = requestMessages,
            stream = true
        )
        return json.encodeToString(ChatCompletionRequest.serializer(), body)
    }

    /**
     * 合并自定义头到请求构建器；Authorization / Content-Type 以本请求为准，跳过自定义覆盖。
     *
     * 头名比较做大小写规范化（lowercase），避免用户配置小写 `authorization` / `content-type`
     * 时不命中跳过逻辑、追加重复/冲突头（CR-06，发现 2）。
     */
    internal fun applyCustomHeaders(builder: HttpRequestBuilder, headers: Map<String, String>) {
        headers.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower != HttpHeaders.Authorization.lowercase() && lower != HttpHeaders.ContentType.lowercase()) {
                builder.header(k, v)
            }
        }
    }

    /** 从自定义头中取 Authorization 头值（大小写不敏感）；无则返回 null。 */
    internal fun customAuthHeader(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value

    /**
     * 将 HTTP 状态码映射为面向用户的错误事件：
     * - 401 → 鉴权失败专属文案（BR-error-handling-003）
     * - 其他 4xx → 携带状态码的「请求被拒绝」文案
     * - 其余 → 通用网络错误文案
     */
    private fun mapHttpError(status: Int): StreamEvent.Error = when {
        status == HttpStatusCode.Unauthorized.value -> StreamEvent.Error("鉴权失败，请检查 API Key")
        status in 400..499 -> StreamEvent.Error("请求被拒绝（$status），请检查 Provider 配置")
        else -> StreamEvent.Error("网络请求失败，请检查网络连接或 Provider 配置")
    }

    /**
     * 解析单个 SSE `data` 载荷为 [StreamEvent]；无法解析的 chunk 返回 null（忽略，不中断流）。
     *
     * - `[DONE]` → [StreamEvent.Done]
     * - 含非空 content delta → [StreamEvent.Delta]
     * - 空 `choices[]` 且无 usage（真正的结束 chunk）→ [StreamEvent.Done]
     * - 空 `choices[]` 但带 usage（中段 usage 快照）→ null（忽略，避免提前终止流，CR-03）
     * - 其余（无 content / 非 JSON / 空白 content）→ null
     */
    internal fun parseChunkData(data: String): StreamEvent? {
        if (data == DONE_MARKER) return StreamEvent.Done
        val chunk = try {
            json.decodeFromString(ChatCompletionChunk.serializer(), data)
        } catch (e: Exception) {
            // 解析失败的非终止 chunk：忽略，避免单个坏 chunk 中断流
            return null
        }
        val delta = chunk.choices.firstOrNull()?.delta?.content
        if (delta != null && delta.isNotBlank()) return StreamEvent.Delta(delta)
        // 空 choices[]：无 usage 视为流结束；带 usage 为中段快照，忽略
        if (chunk.choices.isEmpty() && chunk.usage == null) return StreamEvent.Done
        return null
    }

    private companion object {
        const val TAG = "OpenAICompatibleProvider"
        const val DONE_MARKER = "[DONE]"
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"
    }
}

/** 请求体消息（role + content）。role 由 [Role.toRequestRole] 或 system/ragContext 注入产生。 */
@Serializable
private data class MessageBody(val role: String, val content: String)

/** chat/completions 请求体（stream=true）。stream 无默认值，确保始终随请求序列化。 */
@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<MessageBody>,
    val stream: Boolean
)

/** 流式响应 chunk。usage 用于区分中段快照与真正结束 chunk（CR-03）。 */
@Serializable
private data class ChatCompletionChunk(
    val choices: List<Choice> = emptyList(),
    val usage: JsonElement? = null
)

/** 单个 choice。 */
@Serializable
private data class Choice(val delta: Delta = Delta())

/** 增量 token。 */
@Serializable
private data class Delta(val content: String? = null)

/**
 * 将 UI 层 [Role] 映射为 OpenAI 请求角色。
 *
 * **M4 Phase A 边界**（ADR-014 5.6）：[Role.TOOL] 暂不支持——OpenAI tool 结果消息需
 * `role="tool"` + `tool_call_id` 字段，而当前 [MessageBody] 仅有 role+content，无法携带
 * `tool_call_id`。完整 TOOL 序列化属 Phase C/D（US-024/US-026 重构 buildRequestBody）。
 *
 * Phase A 不产生 TOOL 消息（Phase D 才回灌工具结果），此处对 TOOL **Fail Fast** 抛出
 * [IllegalStateException]，避免静默映射为 "assistant" 导致请求语义错误（Karpathy: 显式暴露假设）。
 */
private fun Role.toRequestRole(): String = when (this) {
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.TOOL -> throw IllegalStateException(
        "Role.TOOL 序列化未在 Phase A 实现，将在 Phase C/D (US-024/US-026) 支持"
    )
}