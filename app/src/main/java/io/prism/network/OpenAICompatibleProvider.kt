package io.prism.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI 兼容 Provider 的流式请求实现（ADR-004 4.3 / ADR-014 5.3）。
 *
 * 负责将 [ProviderConfig] 组装为 `/v1/chat/completions` 的 SSE 请求并消费流式响应，
 * 将解析结果以 [StreamEvent] 流暴露给调用方。
 *
 * **组装规则**：
 * - 端点：`baseUrl.trimEnd('/') + "/chat/completions"`
 * - 鉴权：`Authorization: Bearer <apiKeyRef 明文>`（无 key 时跳过）
 * - 自定义头：合并 [ProviderConfig.headers]，不覆盖 Authorization / Content-Type
 * - 请求体：`model` / `messages` / `stream=true`，M4 新增 `tools` / `tool_choice` / `parallel_tool_calls`
 *
 * **M4 Phase C tool_calling**（ADR-014 5.3）：
 * - [buildRequestBody] 扩展：注入 tools + tool result 回灌（role=tool 携带 tool_call_id）+
 *   assistant tool_calls 回放
 * - delta 状态机：[parseChunk] 纯解析 → [chunkToEvents] 纯状态机处理（text delta +
 *   pendingToolCalls 跨 chunk 拼接 + finish_reason=tool_calls 触发 [StreamEvent.ToolCallComplete]）
 * - JSON 解析失败降级为 [StreamEvent.Error] 不崩溃；CancellationException 重抛（BR-error-handling-007）
 *
 * **可测性**（ADR-004 4.7 / BR-testing-004）：SSE 客户端要求引擎声明 SSECapability，
 * MockEngine 不支持。故将请求组装与 SSE 解析抽离为 `internal` 纯函数
 * （[buildEndpoint] / [buildAuthHeader] / [buildRequestBody] / [applyCustomHeaders] /
 * [parseChunk] / [chunkToEvents] / [processToolCallDeltas] / [completeToolCalls]），
 * 单元测试直接覆盖核心逻辑；端到端流式路径由真实 Ktor SSE 服务器集成测试验证。
 *
 * **错误处理**：网络异常、鉴权失败、流中断统一发射 [StreamEvent.Error]，不外抛。
 * 结束 chunk 空 `choices[]` 视为流结束，发射 [StreamEvent.Done]，不崩溃。
 */
class OpenAICompatibleProvider(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository
) : ChatStreamProvider, ChatCompletionProvider {

    /** 编译时 JSON，忽略未知字段（如 `reasoning_content`），空安全。 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 发起流式对话请求。
     *
     * **US-019 扩展**（ADR-012 5.4）：新增 [systemPrompt] / [ragContext] 可选参数。
     * - [systemPrompt] 非空时，在 messages 列表最前插入 `MessageBody("system", systemPrompt)`
     * - [ragContext] 非空时，作为独立 user 消息插在最后一条 user 消息前（RAG context 必须在用户问题之前）
     *
     * **M4 Phase C tool_calling**（ADR-014 5.3）：[tools] / [toolChoice] 非空时序列化到请求体，
     * 流式响应经 [chunkToEvents] 状态机解析 tool_calls delta，发射 [StreamEvent.ToolCallStart] /
     * [StreamEvent.ToolCallDelta] / [StreamEvent.ToolCallComplete]。null 时行为与 US-019 一致（向后兼容）。
     *
     * @param config 目标 Provider 配置（含 baseUrl / apiKeyRef / headers / models）
     * @param messages 对话历史（[ChatMessage] 转换为请求体消息，含 role=tool 结果回灌）
     * @param systemPrompt system 消息内容（可选，RAG grounding rules）；null 时不注入
     * @param ragContext RAG context 文本（可选，知识库片段拼接）；null 时不注入
     * @param tools 工具定义列表（M4，null 时不序列化 tools 字段，向后兼容）
     * @param toolChoice 工具选择策略（M4，null 时不序列化 tool_choice 字段）
     * @return [StreamEvent] 流：增量 [StreamEvent.Delta] / 工具调用事件 / 结束 [StreamEvent.Done] / 错误 [StreamEvent.Error]
     */
    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>?,
        toolChoice: ToolChoice?
    ): Flow<StreamEvent> = flow {
        val endpoint = buildEndpoint(config.baseUrl)
        val apiKey = if (config.apiKeyRef.isNotBlank()) {
            apiKeyRepository.readApiKeyOnce(config.apiKeyRef)
        } else {
            null
        }

        // tool_calling 跨 chunk 状态：index → 累加器。flow{} 作用域内单协程访问（flowOn IO），无需同步。
        val pendingToolCalls = mutableMapOf<Int, ToolCallAccumulator>()
        var terminated = false
        try {
            httpClient.sse(endpoint, {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                // 鉴权头优先级：apiKeyRef 明文 > 自定义 Authorization 头。
                buildAuthHeader(apiKey)?.let { header(HttpHeaders.Authorization, it) }
                    ?: customAuthHeader(config.headers)?.let { header(HttpHeaders.Authorization, it) }
                applyCustomHeaders(this, config.headers)
                setBody(buildRequestBody(config, messages, systemPrompt, ragContext, tools, toolChoice))
            }) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    // [DONE] 终止标记（非 JSON），直接发射 Done
                    if (data == DONE_MARKER) {
                        terminated = true
                        emit(StreamEvent.Done)
                        return@collect
                    }
                    val chunk = parseChunk(data) ?: return@collect
                    val events = chunkToEvents(chunk, pendingToolCalls, json)
                    for (e in events) {
                        if (e is StreamEvent.Done) terminated = true
                        emit(e)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消必须重新抛出，不得吞掉（结构化并发，CR-01 / BR-error-handling-007）
            throw e
        } catch (e: SSEClientException) {
            // SSE 插件对非 200 响应一律抛 SSEClientException，从 response 读取状态码区分 401 与其他 4xx。
            emit(mapHttpError(e.response?.status?.value ?: -1))
            return@flow
        } catch (e: ClientRequestException) {
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

    // ==================== 非流式请求（ADR-015 5.3 / H-1 阻塞项解除） ====================

    /**
     * 发起非流式对话请求（stream=false），返回完整的 assistant 回复内容。
     *
     * **M5 Phase B**（ADR-015 5.3）：供 [io.prism.memory.ConversationSummarizer] 和
     * [io.prism.memory.UserProfileManager] 使用，这些后台任务需要完整单次结果而非流式增量。
     *
     * **请求**：POST `/chat/completions`，body 含 `stream=false`，复用 [buildRequestBody]
     * 的消息组装逻辑（systemPrompt 前置 + messages 转换 + ragContext 插入）。
     * 不携带 tools/toolChoice（后台任务不需要工具调用）。
     *
     * **响应**：解析 `choices[0].message.content` 为 [String]。
     *
     * **错误处理**（BR-error-handling-007 / BR-error-handling-004）：
     * - CancellationException 重抛（不吞协程取消）
     * - 其他异常返回 null（调用方降级处理，如摘要失败降级为截断）
     * - 不向调用方泄露内部路径/堆栈（CWE-209 纵深防御）
     *
     * @param config 目标 Provider 配置
     * @param messages 对话历史（不含 system 消息）
     * @param systemPrompt system 消息内容（可选，摘要/抽取 prompt）
     * @param ragContext RAG context 文本（可选，后台任务通常 null）
     * @return assistant 回复内容；失败时返回 null
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    override suspend fun chatCompletion(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?
    ): String? {
        val endpoint = buildEndpoint(config.baseUrl)
        val apiKey = if (config.apiKeyRef.isNotBlank()) {
            apiKeyRepository.readApiKeyOnce(config.apiKeyRef)
        } else {
            null
        }

        return try {
            val response: HttpResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                // 鉴权头优先级：apiKeyRef 明文 > 自定义 Authorization 头（与 streamChat 一致）
                buildAuthHeader(apiKey)?.let { header(HttpHeaders.Authorization, it) }
                    ?: customAuthHeader(config.headers)?.let { header(HttpHeaders.Authorization, it) }
                applyCustomHeaders(this, config.headers)
                setBody(buildRequestBody(config, messages, systemPrompt, ragContext, stream = false))
            }
            val responseBody = response.bodyAsText()
            parseCompletionResponse(responseBody)
        } catch (e: CancellationException) {
            // 协程取消必须重抛，不得吞掉（BR-error-handling-007）
            throw e
        } catch (e: ClientRequestException) {
            // HTTP 4xx/5xx（如 401 鉴权失败、429 限流）—— 返回 null 让调用方降级
            null
        } catch (e: Exception) {
            // 网络/协议错误 —— 返回 null 让调用方降级，不泄露内部细节
            null
        }
    }

    /**
     * 解析非流式 chat completion 响应体为 assistant 回复内容（纯函数，可测）。
     *
     * **解析规则**：
     * - 合法 JSON 且 `choices` 非空 → `choices[0].message.content`（空白返回 null）
     * - 合法 JSON 但 `choices` 为空 → null（异常响应，降级处理）
     * - 非法 JSON / 解析失败 → null（降级处理，不崩溃）
     *
     * @param responseBody HTTP 响应体原文（JSON string）
     * @return assistant 回复内容；解析失败或无内容时返回 null
     */
    internal fun parseCompletionResponse(responseBody: String): String? {
        return try {
            val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
            parsed.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 解析失败降级为 null，不崩溃
            null
        }
    }

    // ==================== 可测试纯函数（ADR-004 4.7 / BR-testing-004） ====================

    /** 拼接 chat/completions 端点，去除尾部斜杠。 */
    internal fun buildEndpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/chat/completions"

    /** 组装 Authorization 头值；空白或无 key 返回 null（省略鉴权头）。 */
    internal fun buildAuthHeader(apiKey: String?): String? =
        apiKey?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    /**
     * 序列化请求体（model / messages / stream=true + M4 tools / tool_choice / parallel_tool_calls）。
     *
     * **US-019 注入规则**（ADR-012 5.4）：
     * - [systemPrompt] 非空：在 messages 最前插入 `MessageBody("system", systemPrompt)`
     * - [ragContext] 非空：作为独立 user 消息插在**最后一条 user 消息之前**
     *
     * **M4 Phase C tool_calling 注入**（ADR-014 5.3）：
     * - [tools] 非空：序列化 `tools` 字段 + `tool_choice`（[toolChoiceToJson]）+ `parallel_tool_calls=false`
     * - role=tool 消息：[MessageBody] 携带 `tool_call_id`（关联 LLM 返回的 tool_call）
     * - role=assistant 且 toolCalls 非空：[MessageBody] 携带 `tool_calls` 数组（回放 OpenAI 结构）
     * - assistant 空 content + 非空 toolCalls：content 序列化为 null（OpenAI 允许 assistant 消息 null content + tool_calls）
     *
     * @param tools 工具定义（null 时不序列化 tools 字段，向后兼容）
     * @param toolChoice 工具选择策略（null 时不序列化 tool_choice 字段）
     * @param stream 是否流式请求（默认 true，向后兼容；M5 Phase B 非流式路径传 false）
     */
    internal fun buildRequestBody(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        ragContext: String? = null,
        tools: List<ToolDefinition>? = null,
        toolChoice: ToolChoice? = null,
        stream: Boolean = true
    ): String {
        val requestMessages = buildList {
            // 1. system 消息前置（若有）
            if (!systemPrompt.isNullOrBlank()) {
                add(MessageBody(role = SYSTEM_ROLE, content = systemPrompt))
            }
            // 2. 转换对话历史（含 role=tool 结果回灌 + assistant tool_calls 回放）
            messages.forEach { add(it.toMessageBody()) }
            // 3. ragContext 插在最后一条 user 消息之前（若有）
            if (!ragContext.isNullOrBlank()) {
                val lastUserIndex = indexOfLast { it.role == USER_ROLE }
                if (lastUserIndex == -1) {
                    add(MessageBody(role = USER_ROLE, content = ragContext))
                } else {
                    add(lastUserIndex, MessageBody(role = USER_ROLE, content = ragContext))
                }
            }
        }
        val body = ChatCompletionRequest(
            model = config.models.firstOrNull() ?: "",
            messages = requestMessages,
            stream = stream,
            tools = tools,
            toolChoice = toolChoice?.let { toolChoiceToJson(it) },
            // strict mode 需 parallel_tool_calls=false（OpenAI 限制），且减少并行幻觉
            parallelToolCalls = if (tools != null) false else null
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
     * 解析单个 SSE `data` 载荷为 [ChatCompletionChunk]；无法解析的 chunk 返回 null（忽略，不中断流）。
     *
     * **注意**：本函数只做纯 JSON 解码，不映射 [StreamEvent]。`[DONE]` 终止标记由调用方
     * （[streamChat] collect 闭包）在调用本函数前拦截处理。事件映射由 [chunkToEvents] 完成。
     *
     * - 合法 JSON chunk → [ChatCompletionChunk]（含 choices / usage / finish_reason / tool_calls）
     * - `[DONE]` → null（调用方应先拦截，不应传入本函数）
     * - 非 JSON / 解析失败 → null（忽略坏 chunk 不中断流）
     */
    internal fun parseChunk(data: String): ChatCompletionChunk? {
        if (data == DONE_MARKER) return null
        return try {
            json.decodeFromString(ChatCompletionChunk.serializer(), data)
        } catch (e: Exception) {
            // 解析失败的非终止 chunk：忽略，避免单个坏 chunk 中断流
            null
        }
    }

    /**
     * 将单个 [ChatCompletionChunk] 映射为 0..N 个 [StreamEvent]（纯函数，可测）。
     *
     * **处理顺序**：
     * 1. text delta：`choices[0].delta.content` 非空且非空白 → [StreamEvent.Delta]
     * 2. tool_calls delta 累加：`choices[0].delta.toolCalls` 经 [processToolCallDeltas] 累加到 [state]，
     *    发射 [StreamEvent.ToolCallStart] / [StreamEvent.ToolCallDelta]
     * 3. finish_reason == "tool_calls"：[completeToolCalls] 解析累加的 arguments，
     *    发射 [StreamEvent.ToolCallComplete]（JSON 解析失败降级为 [StreamEvent.Error]）
     * 4. 空 `choices[]`：无 usage 视为流结束 → [StreamEvent.Done]；带 usage 为中段快照 → 忽略（CR-03）
     *
     * @param state 跨 chunk 的 tool_call 累加状态（调用方持有，本函数原地修改）
     * @param json 用于 arguments JSON 解析的 Json 实例
     */
    internal fun chunkToEvents(
        chunk: ChatCompletionChunk,
        state: MutableMap<Int, ToolCallAccumulator>,
        json: Json
    ): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val choice = chunk.choices.firstOrNull()
        // 1. text delta
        val content = choice?.delta?.content
        if (!content.isNullOrBlank()) events.add(StreamEvent.Delta(content))
        // 2. tool_calls delta 累加
        val toolCallDeltas = choice?.delta?.toolCalls
        if (!toolCallDeltas.isNullOrEmpty()) {
            events.addAll(processToolCallDeltas(state, toolCallDeltas))
        }
        // 3. finish_reason == "tool_calls" 触发完成
        if (choice?.finishReason == FINISH_TOOL_CALLS) {
            events.addAll(completeToolCalls(state, json))
        }
        // 4. 空 choices：无 usage = 流结束；带 usage = 中段快照忽略
        if (chunk.choices.isEmpty() && chunk.usage == null) {
            events.add(StreamEvent.Done)
        }
        return events
    }

    /**
     * 处理 tool_calls delta 分片，累加到 [state] 并发射 [StreamEvent.ToolCallStart] / [ToolCallDelta]。
     *
     * OpenAI tool_calls delta 按 `index` 区分并行 tool_call，`function.name` 仅首个 delta 携带，
     * `function.arguments` 是 JSON string 增量片段需跨 chunk 拼接。
     *
     * @param state index → 累加器（原地修改）
     * @param deltas 单个 chunk 内的 tool_calls delta 列表
     */
    internal fun processToolCallDeltas(
        state: MutableMap<Int, ToolCallAccumulator>,
        deltas: List<ToolCallDeltaWire>
    ): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        for (tc in deltas) {
            val acc = state.getOrPut(tc.index) { ToolCallAccumulator() }
            if (!tc.id.isNullOrEmpty()) acc.id = tc.id
            tc.function?.name?.let { acc.name = it }
            tc.function?.arguments?.let { acc.arguments.append(it) }
            // 首次见到 name 时发射 ToolCallStart（UI 可立即展示"正在调用工具"）
            if (acc.name.isNotEmpty() && !acc.startEmitted) {
                events.add(StreamEvent.ToolCallStart(acc.id, acc.name, tc.index))
                acc.startEmitted = true
            }
            // 每个 arguments 片段发射 ToolCallDelta（UI 可实时展示参数构建，可选）
            tc.function?.arguments?.let {
                events.add(StreamEvent.ToolCallDelta(acc.id, it))
            }
        }
        return events
    }

    /**
     * finish_reason == "tool_calls" 时，解析所有累加的 tool_call 并发射 [StreamEvent.ToolCallComplete]。
     *
     * **降级策略**（ADR-014 5.7 / guardrail M2 缓解）：
     * - **id 缺失**：部分非标准 OpenAI 兼容端点（如 Ollama 旧版）可能不在 delta 携带 id。
     *   OpenAI API 要求回灌 tool result 时 `tool_call_id` 非空，缺失会导致 400 拒绝。
     *   此处检测 id 为空 → 发射 [StreamEvent.Error] 并跳过该 tool_call（不进入回灌回路）。
     * - **arguments JSON 解析失败**：不完整 JSON 降级为 [StreamEvent.Error]（不崩溃，R1 缓解）。
     *
     * 完成后清空 [state]（同一批 tool_call 不重复发射）。
     *
     * @param state index → 累加器（完成后清空）
     * @param json 用于 arguments JSON 解析
     */
    internal fun completeToolCalls(
        state: MutableMap<Int, ToolCallAccumulator>,
        json: Json
    ): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        state.forEach { (_, acc) ->
            // M2 防御：id 缺失时降级为 Error，避免回灌空 tool_call_id 导致下游 400
            if (acc.id.isEmpty()) {
                events.add(StreamEvent.Error("工具调用 id 缺失: ${acc.name.ifEmpty { "<unknown>" }}"))
                return@forEach
            }
            val args: Map<String, Any?> = if (acc.arguments.isEmpty()) {
                emptyMap()
            } else {
                try {
                    val element = json.parseToJsonElement(acc.arguments.toString())
                    // tool arguments 顶层必为 JSON object；jsonElementToMap 返回 Any? 需类型收敛
                    @Suppress("UNCHECKED_CAST")
                    jsonElementToMap(element) as? Map<String, Any?>
                        ?: emptyMap()
                } catch (e: Exception) {
                    // 不完整 JSON 降级为 Error，不崩溃（ADR-014 5.7 / R1 缓解）
                    events.add(StreamEvent.Error("工具参数解析失败: ${acc.name}"))
                    return@forEach
                }
            }
            events.add(StreamEvent.ToolCallComplete(acc.id, acc.name, args))
        }
        state.clear()
        return events
    }

    /**
     * 将 [ToolChoice] 转为 OpenAI `tool_choice` 字段的 JSON 表示（ADR-014 5.3.1）。
     *
     * - [ToolChoice.Auto] → `"auto"`
     * - [ToolChoice.Required] → `"required"`
     * - [ToolChoice.None] → `"none"`
     * - [ToolChoice.Specific] → `{"type":"function","function":{"name":"..."}}`
     */
    internal fun toolChoiceToJson(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.Required -> JsonPrimitive("required")
        ToolChoice.None -> JsonPrimitive("none")
        is ToolChoice.Specific -> buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", choice.name) }
        }
    }

    /**
     * 将 [ChatMessage] 转换为请求体 [MessageBody]（含 role=tool 结果 + assistant tool_calls 回放）。
     *
     * - USER → `MessageBody(role="user", content=msg.content)`
     * - ASSISTANT → `MessageBody(role="assistant", content=非空?content:null, toolCalls=回放)`；
     *   空 content + 非空 toolCalls 时 content=null（OpenAI 允许 assistant null content + tool_calls）
     * - TOOL → `MessageBody(role="tool", content=msg.content, toolCallId=msg.toolCallId)`
     */
    private fun ChatMessage.toMessageBody(): MessageBody = when (role) {
        Role.USER -> MessageBody(role = USER_ROLE, content = content)
        Role.ASSISTANT -> {
            val replay = toolCalls.takeIf { it.isNotEmpty() }?.map { ToolCallWire.fromRef(it) }
            MessageBody(
                role = ASSISTANT_ROLE,
                content = content.takeIf { it.isNotEmpty() },
                toolCalls = replay
            )
        }
        Role.TOOL -> MessageBody(
            role = TOOL_ROLE,
            content = content,
            toolCallId = toolCallId
        )
    }

    private companion object {
        const val TAG = "OpenAICompatibleProvider"
        const val DONE_MARKER = "[DONE]"
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"
        const val ASSISTANT_ROLE = "assistant"
        const val TOOL_ROLE = "tool"
        const val FINISH_TOOL_CALLS = "tool_calls"
    }
}

// ==================== OpenAI 线缆数据类（private，仅序列化用） ====================

/**
 * 请求体消息。M4 Phase C 扩展：[content] 可空（assistant tool_call 消息可为 null），
 * [toolCallId] role=tool 时必填，[toolCalls] role=assistant 回放 tool_calls 结构。
 */
@Serializable
private data class MessageBody(
    val role: String,
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallWire>? = null
)

/**
 * chat/completions 请求体（stream=true）。M4 Phase C 扩展 tools/tool_choice/parallel_tool_calls。
 *
 * `stream` 无默认值，确保始终随请求序列化。tools/toolChoice/parallelToolCalls 默认 null，
 * null 时 kotlinx.serialization 默认不序列化该字段（向后兼容，既有调用零改动）。
 */
@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<MessageBody>,
    val stream: Boolean,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null
)

/**
 * 流式响应 chunk。usage 用于区分中段快照与真正结束 chunk（CR-03）。
 * M4 Phase C：choices 含 finish_reason 与 tool_calls delta。
 *
 * **可见性**：[internal] 因 [parseChunk] / [chunkToEvents] 是 internal 纯函数，
 * 需在单元测试中直接构造与断言（BR-testing-004）。
 */
@Serializable
internal data class ChatCompletionChunk(
    val choices: List<Choice> = emptyList(),
    val usage: JsonElement? = null
)

/** 单个 choice。M4 Phase C 扩展 [finishReason]（tool_calls/stop/length）。 */
@Serializable
internal data class Choice(
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

/**
 * 增量 token。M4 Phase C 扩展 [role]（首个 delta 携带 assistant 角色）与 [toolCalls]。
 */
@Serializable
internal data class Delta(
    val content: String? = null,
    val role: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDeltaWire>? = null
)

/**
 * tool_call delta 分片（OpenAI 流式协议）。按 [index] 区分并行 tool_call。
 *
 * @property index 并行 tool_call 索引（跨 chunk 同 index 累加）
 * @property id 工具调用 id（仅首个 delta 携带，如 `call_xxx`）
 * @property type 固定 `"function"`
 * @property function 函数名与 arguments 分片
 */
@Serializable
internal data class ToolCallDeltaWire(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDeltaWire? = null
)

/** tool_call delta 的函数名与 arguments 分片。arguments 是 JSON string 增量片段。 */
@Serializable
internal data class FunctionDeltaWire(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * assistant 消息回放的 tool_call 完整结构（非 delta）。用于构建下次请求时回放上一轮 tool_calls。
 *
 * @see io.prism.ui.model.ToolCallRef UI 层引用类型（构造器 [fromRef] 转换）
 */
@Serializable
private data class ToolCallWire(
    val id: String,
    val type: String = "function",
    val function: FunctionCallWire
) {
    companion object {
        /** 从 UI 层 [ToolCallRef] 转换为线缆结构。 */
        fun fromRef(ref: io.prism.ui.model.ToolCallRef): ToolCallWire = ToolCallWire(
            id = ref.id,
            type = ref.type,
            function = FunctionCallWire(name = ref.functionName, arguments = ref.arguments)
        )
    }
}

/** tool_call 的函数名与完整 arguments（JSON string，回放时原样传递）。 */
@Serializable
private data class FunctionCallWire(
    val name: String,
    val arguments: String
)

// ==================== 非流式响应数据类（M5 Phase B，ADR-015 5.3） ====================

/**
 * 非流式 chat completion 响应体（stream=false）。
 *
 * 与流式 [ChatCompletionChunk] 的区别：
 * - `choices[].message`（完整消息）vs `choices[].delta`（增量分片）
 * - 无 `usage` 为 null 的中段快照概念，usage 直接在顶层
 *
 * **可见性**：[internal] 因 [parseCompletionResponse] 是 internal 纯函数，需在单元测试中
 * 直接构造与断言（BR-testing-004）。
 *
 * @see ChatCompletionChunk 流式响应对照
 */
@Serializable
internal data class ChatCompletionResponse(
    val choices: List<CompletionChoice> = emptyList(),
    val usage: UsageWire? = null
)

/** 非流式响应的单个 choice（含完整 message）。 */
@Serializable
internal data class CompletionChoice(
    val message: CompletionMessage = CompletionMessage(),
    @SerialName("finish_reason") val finishReason: String? = null
)

/** 非流式响应的完整 assistant 消息。 */
@Serializable
internal data class CompletionMessage(
    val role: String? = null,
    val content: String? = null
)

/** token 用量信息（流式与非流式共用结构）。 */
@Serializable
internal data class UsageWire(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

// ==================== tool_call 状态机累加器（internal，可测） ====================

/**
 * tool_call 跨 chunk 累加器（ADR-014 5.3.2）。
 *
 * 每个 `index` 对应一个累加器，跨 chunk 累加 id/name/arguments。
 * `arguments` 是 JSON string 增量片段拼接（finish_reason=tool_calls 时才 JSON.parse）。
 *
 * @property id 工具调用 id（首个 delta 携带）
 * @property name 工具名（首个 delta 携带）
 * @property arguments arguments JSON string 增量累加（StringBuilder 高效拼接）
 * @property startEmitted 是否已发射 [StreamEvent.ToolCallStart]（避免重复发射）
 */
internal data class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
    var startEmitted: Boolean = false
)

// ==================== JSON 工具函数（internal，可测） ====================

/**
 * 将 [JsonElement] 递归转换为 Kotlin 原生 [Map]（用于 tool_call arguments 解析）。
 *
 * - [JsonObject] → `Map<String, Any?>`（递归）
 * - [JsonArray] → `List<Any?>`（递归）
 * - [JsonPrimitive] → String/Number/Boolean/null（按 isString 区分）
 * - [JsonNull] → null
 */
internal fun jsonElementToMap(element: JsonElement): Any? = when (element) {
    is JsonObject -> element.entries.associate { (k, v) -> k to jsonElementToMap(v) }
    is JsonArray -> element.map { jsonElementToMap(it) }
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "null" -> null
        element.content == "true" -> true
        element.content == "false" -> false
        element.content.toIntOrNull() != null -> element.content.toInt()
        element.content.toLongOrNull() != null -> element.content.toLong()
        element.content.toDoubleOrNull() != null -> element.content.toDouble()
        else -> element.content
    }
    JsonNull -> null
}

/**
 * 将 UI 层 [Role] 映射为 OpenAI 请求角色。
 *
 * **M4 Phase C**（ADR-014 5.6）：[Role.TOOL] → `"tool"`，配合 [MessageBody.toolCallId]
 * 携带 `tool_call_id`，实现工具结果回灌。Phase A 的 Fail Fast 占位已替换为完整映射。
 */
private fun Role.toRequestRole(): String = when (this) {
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.TOOL -> "tool"
}
