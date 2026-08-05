package io.prism.network

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
     * @param config 目标 Provider 配置（含 baseUrl / apiKeyRef / headers / models）
     * @param messages 对话历史（[ChatMessage] 转换为请求体消息）
     * @return [StreamEvent] 流：增量 [StreamEvent.Delta] → 结束 [StreamEvent.Done] / 错误 [StreamEvent.Error]
     */
    override fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent> = flow {
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
                setBody(buildRequestBody(config, messages))
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

    /** 序列化请求体（model / messages / stream=true）。取首个模型，空则回退空串交服务端校验。 */
    internal fun buildRequestBody(config: ProviderConfig, messages: List<ChatMessage>): String {
        val requestMessages = messages.map { MessageBody(it.role.toRequestRole(), it.content) }
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
        const val DONE_MARKER = "[DONE]"
    }
}

/** 请求体消息（role + content）。 */
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

/** 将 UI 层 [Role] 映射为 OpenAI 请求角色。 */
private fun Role.toRequestRole(): String = if (this == Role.USER) "user" else "assistant"