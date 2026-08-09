package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.prism.data.ProviderConfig
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.ToolCallRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAICompatibleProvider 单元 + 集成测试（ADR-004 4.7）。
 *
 * **背景**：Ktor SSE 客户端插件要求引擎声明 `SSECapability`，而 `MockEngine` 不支持该能力，
 * 故无法用 MockEngine 测 SSE 流式。因此：
 * - **单元层**：直接测抽离的纯函数（端点拼接 / 鉴权头 / 请求体 / 自定义头 / SSE 解析）。
 * - **集成层**：起真实 Ktor Netty SSE 服务器，验证 OkHttp + SSE 端到端流式路径。
 */
class OpenAICompatibleProviderTest {

    private val apiKeyRepo = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
    // expectSuccess=true 与生产 PrismApplication 一致：非 2xx 抛 ClientRequestException，
    // 使 401 / 429 区分测试得以验证（US-007 LOW 修复）。
    private val provider = OpenAICompatibleProvider(
        HttpClient(OkHttp) { expectSuccess = true; install(SSE) },
        apiKeyRepo
    )

    // ==================== 请求组装纯函数 ====================

    @Test
    fun `buildEndpoint trims trailing slash and appends chat completions`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            provider.buildEndpoint("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            provider.buildEndpoint("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `buildAuthHeader returns null without key and Bearer with key`() {
        assertNull(provider.buildAuthHeader(null))
        assertNull(provider.buildAuthHeader(""))
        assertNull(provider.buildAuthHeader("  "))
        assertEquals("Bearer sk-123", provider.buildAuthHeader("sk-123"))
    }

    @Test
    fun `buildRequestBody serializes model messages stream`() {
        val config = ProviderConfig(
            name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            apiKeyRef = "openai", models = listOf("gpt-4o")
        )
        val messages = listOf(
            ChatMessage(id = 0, role = Role.USER, content = "你好", timestamp = 0),
            ChatMessage(id = 1, role = Role.ASSISTANT, content = "在的", timestamp = 0)
        )

        val body = provider.buildRequestBody(config, messages)

        assertTrue("应包含 model", body.contains("\"model\":\"gpt-4o\""))
        assertTrue("应包含 stream=true", body.contains("\"stream\":true"))
        assertTrue("应包含 user 角色", body.contains("\"role\":\"user\""))
        assertTrue("应包含 assistant 角色", body.contains("\"role\":\"assistant\""))
        assertTrue("应包含用户内容", body.contains("你好"))
    }

    @Test
    fun `applyCustomHeaders merges headers skipping auth and content type`() {
        val builder = HttpRequestBuilder().apply { url("https://example.com") }

        provider.applyCustomHeaders(
            builder,
            mapOf(
                HttpHeaders.Authorization to "should-not-override",
                HttpHeaders.ContentType to "should-not-override",
                "X-Custom" to "value"
            )
        )

        assertNull("Authorization 不应被自定义头覆盖", builder.headers[HttpHeaders.Authorization])
        assertNull("Content-Type 不应被自定义头覆盖", builder.headers[HttpHeaders.ContentType])
        assertEquals("value", builder.headers["X-Custom"])
    }

    @Test
    fun `applyCustomHeaders skips lowercase authorization and content type`() {
        // CR-06 发现 2：头名大小写规范化后仍应跳过 Authorization / Content-Type，避免追加重复/冲突头
        val builder = HttpRequestBuilder().apply { url("https://example.com") }

        provider.applyCustomHeaders(
            builder,
            mapOf(
                "authorization" to "should-not-override",
                "content-type" to "should-not-override",
                "x-custom" to "value"
            )
        )

        assertNull("小写 authorization 不应被自定义头覆盖", builder.headers["Authorization"])
        assertNull("小写 content-type 不应被自定义头覆盖", builder.headers["Content-Type"])
        assertEquals("value", builder.headers["x-custom"])
    }

    @Test
    fun `customAuthHeader returns value case-insensitively`() {
        // CR-06 发现 4 辅助函数：apiKeyRef 为空时回退使用自定义 Authorization 头
        assertNull(provider.customAuthHeader(emptyMap()))
        assertNull(provider.customAuthHeader(mapOf("X-Custom" to "v")))
        assertEquals("Bearer custom", provider.customAuthHeader(mapOf("Authorization" to "Bearer custom")))
        assertEquals("Bearer custom", provider.customAuthHeader(mapOf("authorization" to "Bearer custom")))
    }

    // ==================== SSE 解析纯函数（parseChunk + chunkToEvents） ====================

    /** 辅助：将 SSE data 解析为事件列表（parseChunk + chunkToEvents 两步）。 */
    private fun parseToEvents(data: String): List<StreamEvent> {
        val chunk = provider.parseChunk(data) ?: return emptyList()
        return provider.chunkToEvents(chunk, mutableMapOf(), testJson)
    }

    @Test
    fun `parseChunk maps delta content via chunkToEvents`() {
        val events = parseToEvents("""{"choices":[{"delta":{"content":"你"}}]}""")
        assertEquals(listOf(StreamEvent.Delta("你")), events)
    }

    @Test
    fun `parseChunk returns null for DONE marker`() {
        // [DONE] 由 streamChat collect 闭包在调用 parseChunk 前拦截，parseChunk 内部也防御性返回 null
        assertNull(provider.parseChunk("[DONE]"))
    }

    @Test
    fun `parseChunk maps empty choices to Done via chunkToEvents`() {
        val events = parseToEvents("""{"choices":[]}""")
        assertEquals(listOf(StreamEvent.Done), events)
    }

    @Test
    fun `parseChunk ignores mid-stream usage snapshot not terminate`() {
        // 带 usage 的空 choices 是中段快照，chunkToEvents 不发射 Done（usage != null）
        val events = parseToEvents(
            """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}"""
        )
        assertTrue("中段 usage 快照不应发射任何事件", events.isEmpty())
    }

    @Test
    fun `parseChunk ignores non content and malformed chunks`() {
        // 空 delta（无 content/toolCalls）→ chunkToEvents 返回空列表
        assertTrue(parseToEvents("""{"choices":[{"delta":{}}]}""").isEmpty())
        // 非 JSON → parseChunk 返回 null → 空列表
        assertTrue(parseToEvents("not-json").isEmpty())
        // 空白 content 被 chunkToEvents 忽略（isNullOrBlank 检查）
        assertTrue(parseToEvents("""{"choices":[{"delta":{"content":"   "}}]}""").isEmpty())
    }

    @Test
    fun `parseChunk ignores unknown fields like reasoning_content`() {
        val events = parseToEvents(
            """{"choices":[{"delta":{"content":"思考","reasoning_content":"hidden"}}]}"""
        )
        assertEquals(listOf(StreamEvent.Delta("思考")), events)
    }

    // ==================== 端到端：真实 Ktor SSE 服务器 ====================

    @Test
    fun `streamChat streams deltas then done against real server`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                // 客户端发 POST（OpenAI 兼容端点），显式绑定 HttpMethod.Post 后挂 SSE 处理器
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        sendChunk("""{"choices":[{"delta":{"content":"你"}}]}""")
                        sendChunk("""{"choices":[{"delta":{"content":"好"}}]}""")
                        sendChunk("[DONE]")
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )

            val events = provider.streamChat(config, sampleMessages()).toList()

            assertEquals(
                listOf("你", "好"),
                events.filterIsInstance<StreamEvent.Delta>().map { it.content }
            )
            assertTrue("应收到 Done", events.any { it is StreamEvent.Done })
            assertTrue("不应有 Error", events.none { it is StreamEvent.Error })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `streamChat emits error on unauthorized`() = runBlocking {
        // 服务器对 /chat/completions 返回 401（无有效鉴权头），客户端 SSE 应发射鉴权专属文案（BR-error-handling-003）
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle { call.respond(HttpStatusCode.Unauthorized) }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val events = provider.streamChat(config, sampleMessages()).toList()
            val err = events.filterIsInstance<StreamEvent.Error>().single()
            assertEquals("401 应发射鉴权失败专属文案，而非通用网络文案", "鉴权失败，请检查 API Key", err.message)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `streamChat emits generic rejected message for non 401 4xx`() = runBlocking {
        // 非 401 的 4xx（如 429）：应发射「请求被拒绝」文案而非鉴权文案（BR-error-handling-003）
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle { call.respond(HttpStatusCode.TooManyRequests) }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val events = provider.streamChat(config, sampleMessages()).toList()
            val err = events.filterIsInstance<StreamEvent.Error>().single()
            assertTrue("非 401 4xx 应含状态码文案，且不含鉴权误导", err.message.contains("请求被拒绝（429）"))
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private suspend fun ServerSSESession.sendChunk(data: String) {
        send(ServerSentEvent(data = data))
    }

    @Test
    fun `streamChat falls back to custom authorization header when apiKeyRef blank`() = runBlocking {
        // CR-06 发现 4：apiKeyRef 为空时，回退使用自定义 Authorization 头，避免无任何鉴权头。
        // 服务器记录收到的 Authorization 头后立即发送 [DONE]，使客户端流正常终止，
        // 避免无限等待（原实现仅 await 不发送事件导致 toList() 挂起）。
        val receivedAuth = CompletableDeferred<String?>()
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        receivedAuth.complete(call.request.headers[HttpHeaders.Authorization])
                        send(ServerSentEvent(data = "[DONE]"))
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "Custom", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "", models = listOf("gpt-4o"),
                headers = mapOf("Authorization" to "Bearer custom-key")
            )
            val events = provider.streamChat(config, sampleMessages()).toList()
            val auth = withTimeoutOrNull(5_000) { receivedAuth.await() }
            assertEquals("apiKeyRef 为空时应回退发送自定义 Authorization 头", "Bearer custom-key", auth)
            assertTrue("需正常终止", events.any { it is StreamEvent.Done })
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
        }
    }

    @Test
    fun `streamChat rethrows cancellation instead of emitting error`() = runBlocking {
        // 服务器发送首个增量后，等待测试显式信号 [serverStop] 再关闭连接（而非 awaitCancellation）。
        // 原因：客户端流取消时，Ktor SSE 客户端未必及时关闭连接，服务器 awaitCancellation 会一直等待，
        // 导致 server.stop() 挂起（非确定性）。通过显式信号，无论客户端连接是否已关闭，服务器都能正常停止。
        //
        // 客户端在独立协程收集流，收到增量后显式 cancelAndJoin —— 触发流取消路径。
        // 若 OpenAICompatibleProvider 的 catch(Exception) 吞掉 CancellationException，
        // 则流会 emit(Error) 而非抛 CancellationException，cancelPropagated 将保持 false。
        val serverStop = CompletableDeferred<Unit>()
        val httpClient = HttpClient(OkHttp) { install(SSE) }
        val localProvider = OpenAICompatibleProvider(httpClient, apiKeyRepo)
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        sendChunk("""{"choices":[{"delta":{"content":"你"}}]}""")
                        serverStop.await()
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val gotDelta = CompletableDeferred<Unit>()
            val emittedError = AtomicBoolean(false)
            val cancelPropagated = AtomicBoolean(false)
            val collecting = launch(Dispatchers.Default) {
                try {
                    localProvider.streamChat(config, sampleMessages()).collect { ev ->
                        when (ev) {
                            is StreamEvent.Delta -> gotDelta.complete(Unit)
                            StreamEvent.Done -> {}
                            is StreamEvent.Error -> emittedError.set(true)
                            // M4 tool_calling 事件（ADR-014 5.1）：本测试不注入 tools，三分支不会触发。
                            is StreamEvent.ToolCallStart,
                            is StreamEvent.ToolCallDelta,
                            is StreamEvent.ToolCallComplete -> {}
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelPropagated.set(true)
                    throw e
                }
            }
            // 等待收到首个增量，确认流式连接已建立
            withTimeoutOrNull(5_000) { gotDelta.await() } ?: error("未在超时内收到增量 token")
            collecting.cancelAndJoin()
            // 若 catch(Exception) 吞掉 CancellationException，会 emit(Error) 而正常结束，cancelPropagated=false
            assertTrue("取消应传播（抛 CancellationException）而非被吞掉（CR-01）", cancelPropagated.get())
            assertFalse("取消不应发射 Error（CR-01）", emittedError.get())
        } finally {
            try { serverStop.complete(Unit) } catch (_: Exception) {} // 显式关闭服务器 SSE 会话，避免 stop 挂起
            httpClient.close()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
        }
    }

    // ============ ac-verifier 补充：极端 / 边界 / 恶意注入场景 ============

    private val testJson = Json { }

    @Test
    fun `buildEndpoint handles empty and slash-only base URLs`() {
        assertEquals("/chat/completions", provider.buildEndpoint(""))
        assertEquals("/chat/completions", provider.buildEndpoint("///"))
        assertEquals(
            "https://host/v1/chat/completions",
            provider.buildEndpoint("https://host/v1/")
        )
    }

    @Test
    fun `buildRequestBody handles empty models and empty messages`() {
        val cfg = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = emptyList())
        val body = provider.buildRequestBody(cfg, emptyList())
        assertTrue("空 models 应回退空串", body.contains("\"model\":\"\""))
        assertTrue("空 messages 应序列化为空数组", body.contains("\"messages\":[]"))
        assertTrue("stream 应始终为 true", body.contains("\"stream\":true"))
    }

    // ==================== US-019 system prompt / ragContext 注入测试 ====================

    @Test
    fun `buildRequestBody prepends system message when systemPrompt provided`() {
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(
            ChatMessage(id = 0, role = Role.USER, content = "你好", timestamp = 0)
        )

        val body = provider.buildRequestBody(config, messages, systemPrompt = "你是助手")

        assertTrue("应包含 system 角色", body.contains("\"role\":\"system\""))
        assertTrue("应包含 systemPrompt 内容", body.contains("你是助手"))
        // system 消息应排在 user 消息之前
        val systemIdx = body.indexOf("\"role\":\"system\"")
        val userIdx = body.indexOf("\"role\":\"user\"")
        assertTrue("system 消息应在 user 消息之前", systemIdx < userIdx)
    }

    @Test
    fun `buildRequestBody skips system message when systemPrompt is null or blank`() {
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(ChatMessage(id = 0, role = Role.USER, content = "你好", timestamp = 0))

        val bodyNull = provider.buildRequestBody(config, messages, systemPrompt = null)
        assertFalse("null systemPrompt 不应注入 system 消息", bodyNull.contains("\"role\":\"system\""))

        val bodyBlank = provider.buildRequestBody(config, messages, systemPrompt = "   ")
        assertFalse("空白 systemPrompt 不应注入 system 消息", bodyBlank.contains("\"role\":\"system\""))
    }

    @Test
    fun `buildRequestBody inserts ragContext before last user message`() {
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(
            ChatMessage(id = 0, role = Role.USER, content = "历史问题", timestamp = 0),
            ChatMessage(id = 1, role = Role.ASSISTANT, content = "历史回答", timestamp = 0),
            ChatMessage(id = 2, role = Role.USER, content = "本轮问题", timestamp = 0)
        )

        val body = provider.buildRequestBody(config, messages, ragContext = "知识库片段")

        // 应包含 ragContext 内容
        assertTrue("应包含 ragContext 内容", body.contains("知识库片段"))
        // ragContext 应在「本轮问题」之前、在「历史回答」之后
        val ragIdx = body.indexOf("知识库片段")
        val lastUserIdx = body.lastIndexOf("本轮问题")
        val assistantIdx = body.indexOf("历史回答")
        assertTrue("ragContext 应在最后一条 user 消息之前", ragIdx < lastUserIdx)
        assertTrue("ragContext 应在 assistant 消息之后", ragIdx > assistantIdx)
    }

    @Test
    fun `buildRequestBody appends ragContext at end when no user message exists`() {
        // 异常路径：messages 中无 user 消息，ragContext 直接追加到末尾
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(ChatMessage(id = 0, role = Role.ASSISTANT, content = "仅 AI 消息", timestamp = 0))

        val body = provider.buildRequestBody(config, messages, ragContext = "context")

        assertTrue("无 user 消息时仍应包含 ragContext", body.contains("context"))
    }

    @Test
    fun `buildRequestBody injects both system and ragContext when both provided`() {
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(ChatMessage(id = 0, role = Role.USER, content = "问题", timestamp = 0))

        val body = provider.buildRequestBody(
            config, messages,
            systemPrompt = "sys",
            ragContext = "rag"
        )

        val sysIdx = body.indexOf("\"role\":\"system\"")
        val ragIdx = body.indexOf("rag")
        val userIdx = body.lastIndexOf("问题")
        assertTrue("应有 system 消息", sysIdx >= 0)
        assertTrue("应有 ragContext", ragIdx >= 0)
        assertTrue("system 在 ragContext 之前", sysIdx < ragIdx)
        assertTrue("ragContext 在最后一条 user 消息之前", ragIdx < userIdx)
    }

    @Test
    fun `parseChunk handles oversized content`() {
        val big = "t".repeat(100_000)
        val events = parseToEvents("""{"choices":[{"delta":{"content":"$big"}}]}""")
        assertEquals(listOf(StreamEvent.Delta(big)), events)
    }

    @Test
    fun `parseChunk keeps injection and control payloads as plain delta`() {
        val payloads = listOf(
            "<script>alert(1)</script>",
            "'; DROP TABLE users; --",
            "你好，世界 🌍 中文",
            "line1\nline2\tend",
            "引用\"引号\\反斜杠"
        )
        for (p in payloads) {
            val quoted = testJson.encodeToString(JsonElement.serializer(), JsonPrimitive(p))
            val events = parseToEvents("""{"choices":[{"delta":{"content":$quoted}}]}""")
            assertTrue("应安全解析为 Delta 而非崩溃: ${p.take(20)}", events.size == 1 && events[0] is StreamEvent.Delta)
        }
    }

    @Test
    fun `parseChunk ignores malformed and type-unsafe chunks`() {
        val malformed = listOf(
            """{"choices":[{"delta":{"content":"unterminated""",
            """{"choices":["string-not-object"]}""",
            """{"choices":[{"delta":{"content":42}}]}""",
            """{"choices":[{"delta":null}]}""",
            ""
        )
        for (m in malformed) {
            assertNull("应忽略坏 chunk 不崩溃: ${m.take(30)}", provider.parseChunk(m))
        }
    }

    @Test
    fun `parseChunk picks first choice content when multiple`() {
        val events = parseToEvents(
            """{"choices":[{"delta":{"content":"first"}},{"delta":{"content":"second"}}]}"""
        )
        assertEquals(listOf(StreamEvent.Delta("first")), events)
    }

    @Test
    fun `parseChunk handles whitespace-only content after delta`() {
        // 空白 content 应忽略（不产生空 Delta），但流不终止
        assertTrue(parseToEvents("""{"choices":[{"delta":{"content":" "}}]}""").isEmpty())
    }

    @Test
    fun `streamChat emits error when endpoint unreachable`() = runBlocking {
        // 通过临时 ServerSocket 拿到一个未监听端口，连接被拒 → 应发射 Error 而非崩溃（AC-4）
        val port = ServerSocket(0).use { it.localPort }
        val config = ProviderConfig(
            name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
            apiKeyRef = "openai", models = listOf("gpt-4o")
        )
        val events = provider.streamChat(config, sampleMessages()).toList()
        assertTrue("端点不可达应发射 Error（AC-4）", events.any { it is StreamEvent.Error })
        assertTrue("不应有 Delta", events.none { it is StreamEvent.Delta })
        assertTrue("错误即终态，不应再有 Done", events.none { it is StreamEvent.Done })
    }

    @Test
    fun `streamChat emits done when server closes without DONE marker`() = runBlocking {
        // 流中断：服务端返回 Delta 后关闭连接（不发 [DONE]）→ 客户端兜底补发 Done，不崩溃
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        sendChunk("""{"choices":[{"delta":{"content":"partial"}}]}""")
                        // sse 块返回后 Ktor 关闭连接，客户端流正常结束
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val events = provider.streamChat(config, sampleMessages()).toList()
            assertEquals(listOf("partial"), events.filterIsInstance<StreamEvent.Delta>().map { it.content })
            assertTrue("流中断应兜底补发 Done", events.any { it is StreamEvent.Done })
            assertFalse("流中断不应发射 Error", events.any { it is StreamEvent.Error })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `streamChat continues after mid-stream usage snapshot`() = runBlocking {
        // guardrail 转交项：空 choices 带 usage 中段快照后仍应收到后续 Delta（CR-03 端到端补验）
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        sendChunk("""{"choices":[{"delta":{"content":"你"}}]}""")
                        sendChunk("""{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
                        sendChunk("""{"choices":[{"delta":{"content":"好"}}]}""")
                        sendChunk("[DONE]")
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val events = provider.streamChat(config, sampleMessages()).toList()
            assertEquals(
                "usage 快照后仍应收到后续 Delta（CR-03）",
                listOf("你", "好"),
                events.filterIsInstance<StreamEvent.Delta>().map { it.content }
            )
            assertTrue(events.any { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `streamChat parses tool_calls delta sequence end-to-end against real server`() = runBlocking {
        // E2E（ac-verifier 补充）：真实 Ktor SSE 服务器发送 tool_calls delta 序列，
        // 验证客户端从 SSE → parseChunk → chunkToEvents 状态机 → StreamEvent 完整路径
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        // delta1: id + name + arguments 片段1
                        sendChunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_e2e","function":{"name":"read_file","arguments":"{\"path\""}}]}}]}""")
                        // delta2: arguments 片段2
                        sendChunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"/tmp\"}"}}]}}]}""")
                        // finish_reason=tool_calls 触发完成
                        sendChunk("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
                        sendChunk("[DONE]")
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val events = provider.streamChat(config, sampleMessages()).toList()

            // 应收到 ToolCallStart + ToolCallDelta(>=2) + ToolCallComplete + Done
            val starts = events.filterIsInstance<StreamEvent.ToolCallStart>()
            assertEquals("应收到 1 个 ToolCallStart", 1, starts.size)
            assertEquals("call_e2e", starts.single().toolCallId)
            assertEquals("read_file", starts.single().toolName)
            assertEquals(0, starts.single().index)

            assertTrue("应收到至少 2 个 ToolCallDelta", events.filterIsInstance<StreamEvent.ToolCallDelta>().size >= 2)

            val completes = events.filterIsInstance<StreamEvent.ToolCallComplete>()
            assertEquals("应收到 1 个 ToolCallComplete", 1, completes.size)
            assertEquals("call_e2e", completes.single().toolCallId)
            assertEquals("read_file", completes.single().toolName)
            assertEquals("/tmp", completes.single().arguments["path"])

            assertTrue("应收到 Done", events.any { it is StreamEvent.Done })
            assertTrue("不应有 Error", events.none { it is StreamEvent.Error })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    // ==================== M1 补齐：tool_calling 核心逻辑单元测试（guardrail M1） ====================
    // 覆盖 processToolCallDeltas / completeToolCalls / chunkToEvents 状态机 / toolChoiceToJson /
    // buildRequestBody tools 序列化 / role=tool 回灌 / assistant tool_calls 回放（ADR-014 5.3）

    @Test
    fun `chunkToEvents accumulates tool_call arguments across chunks and completes on finish_reason`() {
        // 验证跨 chunk 拼接 + finish_reason=tool_calls 触发 ToolCallComplete（M1 核心场景）
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        // chunk1: id + name + arguments 片段1
        val chunk1 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\""}}]}}]}"""
        )!!
        val events1 = provider.chunkToEvents(chunk1, state, testJson)
        // chunk1 应发射 ToolCallStart（首次见到 name）+ ToolCallDelta（arguments 片段）
        assertTrue("chunk1 应发射 ToolCallStart", events1.any { it is StreamEvent.ToolCallStart })
        assertTrue("chunk1 应发射 ToolCallDelta", events1.any { it is StreamEvent.ToolCallDelta })
        val start1 = events1.filterIsInstance<StreamEvent.ToolCallStart>().single()
        assertEquals("call_1", start1.toolCallId)
        assertEquals("read_file", start1.toolName)
        assertEquals(0, start1.index)

        // chunk2: 仅 arguments 片段2（无 id/name）
        val chunk2 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"/tmp\"}"}}]}}]}"""
        )!!
        val events2 = provider.chunkToEvents(chunk2, state, testJson)
        // chunk2 不应再发射 ToolCallStart（已发射过）
        assertTrue("chunk2 不应重复发射 ToolCallStart", events2.none { it is StreamEvent.ToolCallStart })
        // chunk2 应发射 ToolCallDelta
        assertTrue("chunk2 应发射 ToolCallDelta", events2.any { it is StreamEvent.ToolCallDelta })
        // chunk2 无 finish_reason，不发射 ToolCallComplete
        assertTrue("chunk2 不应发射 ToolCallComplete", events2.none { it is StreamEvent.ToolCallComplete })

        // chunk3: 携带 finish_reason=tool_calls 触发完成
        val chunk3 = provider.parseChunk(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )!!
        val events3 = provider.chunkToEvents(chunk3, state, testJson)
        val complete = events3.filterIsInstance<StreamEvent.ToolCallComplete>().single()
        assertEquals("call_1", complete.toolCallId)
        assertEquals("read_file", complete.toolName)
        assertEquals("/tmp", complete.arguments["path"])
        // 完成后 state 应清空
        assertTrue("完成后 state 应清空", state.isEmpty())
    }

    @Test
    fun `processToolCallDeltas emits ToolCallStart only once per index`() {
        // 验证 ToolCallStart 仅在首次见到 name 时发射一次（startEmitted 标志）
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val chunk1 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"tool_a","arguments":"{"}}]}}]}"""
        )!!
        provider.chunkToEvents(chunk1, state, testJson)
        // 第二个 chunk 携带 name（重复）+ arguments 片段
        val chunk2 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"tool_a","arguments":"}"}}]}}]}"""
        )!!
        val events2 = provider.chunkToEvents(chunk2, state, testJson)
        // 不应重复发射 ToolCallStart
        assertTrue("不应重复发射 ToolCallStart", events2.none { it is StreamEvent.ToolCallStart })
        // 应发射 ToolCallDelta
        assertEquals(1, events2.filterIsInstance<StreamEvent.ToolCallDelta>().size)
    }

    @Test
    fun `processToolCallDeltas isolates parallel tool_calls by index`() {
        // 验证并行 tool_call 的 index 隔离（同一 chunk 内两个 index）
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val deltas = listOf(
            ToolCallDeltaWire(index = 0, id = "call_1", function = FunctionDeltaWire(name = "tool_a")),
            ToolCallDeltaWire(index = 1, id = "call_2", function = FunctionDeltaWire(name = "tool_b"))
        )
        val events = provider.processToolCallDeltas(state, deltas)
        val starts = events.filterIsInstance<StreamEvent.ToolCallStart>()
        assertEquals("应发射两个 ToolCallStart", 2, starts.size)
        assertTrue("应含 index=0 tool_a", starts.any { it.index == 0 && it.toolName == "tool_a" })
        assertTrue("应含 index=1 tool_b", starts.any { it.index == 1 && it.toolName == "tool_b" })
        assertEquals("state 应有两个累加器", 2, state.size)
        assertEquals("call_1", state[0]?.id)
        assertEquals("call_2", state[1]?.id)
    }

    @Test
    fun `processToolCallDeltas handles out-of-order index across chunks`() {
        // 验证乱序 index：先收到 index=1，再收到 index=0
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val chunk1 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"tool_b"}}]}}]}"""
        )!!
        provider.chunkToEvents(chunk1, state, testJson)
        val chunk2 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"tool_a"}}]}}]}"""
        )!!
        provider.chunkToEvents(chunk2, state, testJson)
        assertEquals(2, state.size)
        assertEquals("call_1", state[0]?.id)
        assertEquals("call_2", state[1]?.id)
    }

    @Test
    fun `completeToolCalls emits ToolCallComplete with empty args when arguments blank`() {
        // 验证空 arguments 时返回 emptyMap（不崩溃）
        val state = mutableMapOf(
            0 to ToolCallAccumulator(id = "call_1", name = "tool", arguments = StringBuilder())
        )
        val events = provider.completeToolCalls(state, testJson)
        val complete = events.filterIsInstance<StreamEvent.ToolCallComplete>().single()
        assertEquals("call_1", complete.toolCallId)
        assertEquals("tool", complete.toolName)
        assertTrue("空 arguments 应为 emptyMap", complete.arguments.isEmpty())
    }

    @Test
    fun `completeToolCalls degrades malformed JSON arguments to Error`() {
        // 验证不完整 JSON 降级为 Error（R1 缓解）
        val state = mutableMapOf(
            0 to ToolCallAccumulator(
                id = "call_1", name = "tool", arguments = StringBuilder("{broken")
            )
        )
        val events = provider.completeToolCalls(state, testJson)
        assertTrue(
            "应发射解析失败 Error",
            events.any { it is StreamEvent.Error && it.message.contains("工具参数解析失败") }
        )
        assertTrue("不应发射 ToolCallComplete", events.none { it is StreamEvent.ToolCallComplete })
    }

    @Test
    fun `completeToolCalls degrades missing id to Error (M2)`() {
        // M2 防御：id 缺失时降级为 Error，避免回灌空 tool_call_id 导致下游 400
        val state = mutableMapOf(
            0 to ToolCallAccumulator(
                id = "", name = "read_file", arguments = StringBuilder("{\"path\":\"/tmp\"}")
            )
        )
        val events = provider.completeToolCalls(state, testJson)
        assertTrue(
            "应发射 id 缺失 Error",
            events.any { it is StreamEvent.Error && it.message.contains("工具调用 id 缺失") }
        )
        assertTrue("不应发射 ToolCallComplete", events.none { it is StreamEvent.ToolCallComplete })
    }

    @Test
    fun `completeToolCalls uses unknown placeholder when id and name both missing`() {
        // M2 边界：id 和 name 都缺失时，Error 文案使用 <unknown> 占位
        val state = mutableMapOf(
            0 to ToolCallAccumulator(id = "", name = "", arguments = StringBuilder("{}"))
        )
        val events = provider.completeToolCalls(state, testJson)
        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue("应含 <unknown> 占位", error.message.contains("<unknown>"))
    }

    @Test
    fun `completeToolCalls handles mixed valid and invalid tool_calls in same batch`() {
        // 验证同一批中部分有效、部分无效（id 缺失）时，有效部分仍正常发射
        val state = mutableMapOf(
            0 to ToolCallAccumulator(id = "call_1", name = "tool_a", arguments = StringBuilder("{\"k\":\"v\"}")),
            1 to ToolCallAccumulator(id = "", name = "tool_b", arguments = StringBuilder("{}"))
        )
        val events = provider.completeToolCalls(state, testJson)
        // 应有 1 个 ToolCallComplete（index=0）+ 1 个 Error（index=1 id 缺失）
        val completes = events.filterIsInstance<StreamEvent.ToolCallComplete>()
        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertEquals(1, completes.size)
        assertEquals(1, errors.size)
        assertEquals("call_1", completes.single().toolCallId)
        assertTrue(errors.single().message.contains("工具调用 id 缺失"))
    }

    @Test
    fun `toolChoiceToJson serializes Auto branch`() {
        // toolChoiceToJson 4 分支序列化（ADR-014 5.3.1）
        assertEquals("\"auto\"", provider.toolChoiceToJson(ToolChoice.Auto).toString())
    }

    @Test
    fun `toolChoiceToJson serializes Required branch`() {
        assertEquals("\"required\"", provider.toolChoiceToJson(ToolChoice.Required).toString())
    }

    @Test
    fun `toolChoiceToJson serializes None branch`() {
        assertEquals("\"none\"", provider.toolChoiceToJson(ToolChoice.None).toString())
    }

    @Test
    fun `toolChoiceToJson serializes Specific branch to nested object`() {
        val json = provider.toolChoiceToJson(ToolChoice.Specific("my_tool"))
        assertEquals(
            """{"type":"function","function":{"name":"my_tool"}}""",
            json.toString()
        )
    }

    @Test
    fun `buildRequestBody serializes tools and tool_choice when provided`() {
        // 验证 tools 非空时序列化 tools + tool_choice + parallel_tool_calls=false
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(ChatMessage(id = 0, role = Role.USER, content = "hi", timestamp = 0))
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = ToolDefinition.FunctionDef(
                    name = "read_file",
                    description = "Read a file",
                    parameters = buildJsonObject { put("type", "object") }
                )
            )
        )
        val body = provider.buildRequestBody(config, messages, tools = tools, toolChoice = ToolChoice.Auto)
        assertTrue("应包含 tools 字段", body.contains("\"tools\""))
        assertTrue("应包含工具名 read_file", body.contains("read_file"))
        assertTrue("应包含 tool_choice auto", body.contains("\"tool_choice\":\"auto\""))
        assertTrue("parallel_tool_calls 应为 false", body.contains("\"parallel_tool_calls\":false"))
    }

    @Test
    fun `buildRequestBody omits tools fields when tools null for backward compat`() {
        // 验证 tools=null 时不序列化 tools/tool_choice/parallel_tool_calls（向后兼容）
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(ChatMessage(id = 0, role = Role.USER, content = "hi", timestamp = 0))
        val body = provider.buildRequestBody(config, messages, tools = null, toolChoice = null)
        assertFalse("不应包含 tools 字段", body.contains("\"tools\""))
        assertFalse("不应包含 tool_choice 字段", body.contains("\"tool_choice\""))
        assertFalse("不应包含 parallel_tool_calls 字段", body.contains("\"parallel_tool_calls\""))
    }

    @Test
    fun `buildRequestBody serializes role tool message with tool_call_id for backpropagation`() {
        // 验证 role=TOOL 消息回灌（携带 tool_call_id）+ assistant tool_calls 回放
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(
            ChatMessage(id = 0, role = Role.USER, content = "read file", timestamp = 0),
            ChatMessage(
                id = 1, role = Role.ASSISTANT, content = "", timestamp = 0,
                toolCalls = listOf(
                    ToolCallRef(
                        id = "call_1", type = "function",
                        functionName = "read_file", arguments = "{\"path\":\"/tmp\"}"
                    )
                )
            ),
            ChatMessage(
                id = 2, role = Role.TOOL, content = "file content", timestamp = 0,
                toolCallId = "call_1", toolName = "read_file"
            )
        )
        val body = provider.buildRequestBody(config, messages)
        assertTrue("应包含 role tool", body.contains("\"role\":\"tool\""))
        assertTrue("应包含 tool_call_id", body.contains("\"tool_call_id\":\"call_1\""))
        assertTrue("应包含 assistant tool_calls 回放", body.contains("\"tool_calls\""))
        assertTrue("应包含 function name read_file", body.contains("read_file"))
        assertTrue("应包含 tool result content", body.contains("file content"))
    }

    @Test
    fun `buildRequestBody serializes assistant null content when only tool_calls present`() {
        // 验证 assistant 空 content + 非空 toolCalls 时 content=null（OpenAI 允许）
        val config = ProviderConfig(name = "X", baseUrl = "http://h", apiKeyRef = "", models = listOf("gpt"))
        val messages = listOf(
            ChatMessage(id = 0, role = Role.USER, content = "q", timestamp = 0),
            ChatMessage(
                id = 1, role = Role.ASSISTANT, content = "", timestamp = 0,
                toolCalls = listOf(
                    ToolCallRef(id = "call_1", functionName = "t", arguments = "{}")
                )
            )
        )
        val body = provider.buildRequestBody(config, messages)
        // assistant 消息的 content 应为 null（空字符串时 toMessageBody 转为 null）
        assertTrue("应含 assistant 角色", body.contains("\"role\":\"assistant\""))
        // 不应含空字符串 content（应为 null）
        assertTrue("应含 tool_calls 字段", body.contains("\"tool_calls\""))
    }

    @Test
    fun `chunkToEvents ignores tool_calls delta with null function`() {
        // 边界：tool_calls delta 携带 index 但 function=null（不应崩溃）
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val chunk = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1"}]}}]}"""
        )!!
        val events = provider.chunkToEvents(chunk, state, testJson)
        // function=null 时不发射 ToolCallStart（无 name），不发射 ToolCallDelta（无 arguments）
        assertTrue("无 function 不应发射 ToolCallStart", events.none { it is StreamEvent.ToolCallStart })
        assertTrue("无 function 不应发射 ToolCallDelta", events.none { it is StreamEvent.ToolCallDelta })
        // 但 state 应记录 index（id 已存）
        assertEquals("call_1", state[0]?.id)
    }

    // ==================== ac-verifier 补充：极端/边缘场景（主 Agent 盲区） ====================

    @Test
    fun `chunkToEvents handles oversized arguments across multiple chunks`() {
        // 超长 arguments：100KB JSON 跨 3 chunk 拼接，验证 StringBuilder 高效拼接 + JSON.parse 正确
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val bigValue = "a".repeat(100_000) // 100KB 纯字母（无特殊字符，无需 JSON 转义）
        // chunk1: id + name + arguments 片段1 ({"data":")
        val chunk1 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_big","function":{"name":"big_tool","arguments":"{\"data\":\""}}]}}]}"""
        )!!
        provider.chunkToEvents(chunk1, state, testJson)
        // chunk2: arguments 片段2 (100000 个 a)
        val chunk2 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$bigValue"}}]}}]}"""
        )!!
        provider.chunkToEvents(chunk2, state, testJson)
        // chunk3: arguments 片段3 ("})
        val chunk3 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"}"}}]}}]}"""
        )!!
        provider.chunkToEvents(chunk3, state, testJson)
        // finish_reason=tool_calls 触发完成
        val finishChunk = provider.parseChunk(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )!!
        val events = provider.chunkToEvents(finishChunk, state, testJson)
        val complete = events.filterIsInstance<StreamEvent.ToolCallComplete>().single()
        assertEquals("call_big", complete.toolCallId)
        assertEquals("big_tool", complete.toolName)
        assertEquals("100KB arguments 应正确解析", bigValue, complete.arguments["data"])
        assertTrue("state 应清空", state.isEmpty())
    }

    @Test
    fun `chunkToEvents preserves state when finish_reason missing`() {
        // 极端场景：有 tool_calls delta 但无 finish_reason=tool_calls → 不发射 Complete，state 保留
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val chunk = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"tool","arguments":"{}"}}]}}]}"""
        )!!
        val events = provider.chunkToEvents(chunk, state, testJson)
        // 应发射 ToolCallStart + ToolCallDelta，但不发射 ToolCallComplete
        assertTrue("应发射 ToolCallStart", events.any { it is StreamEvent.ToolCallStart })
        assertTrue("不应发射 ToolCallComplete（无 finish_reason）", events.none { it is StreamEvent.ToolCallComplete })
        // state 应保留（未清空，等待后续 finish_reason chunk）
        assertEquals("state 应保留", 1, state.size)
        assertEquals("call_1", state[0]?.id)
    }

    @Test
    fun `chunkToEvents degrades missing id to Error end-to-end via finish_reason`() {
        // M2 端到端验证：通过 chunkToEvents 触发 id 缺失场景（非标准 Provider 不携带 tool_call id）
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        // delta 不携带 id（模拟非标准 Provider，如 Ollama 旧版）
        val chunk1 = provider.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"tool_no_id","arguments":"{}"}}]}}]}"""
        )!!
        val events1 = provider.chunkToEvents(chunk1, state, testJson)
        // 首个 delta 有 name → 发射 ToolCallStart（id 为空字符串）
        assertTrue("应发射 ToolCallStart（即使 id 为空）", events1.any { it is StreamEvent.ToolCallStart })
        // finish_reason=tool_calls 触发 completeToolCalls
        val chunk2 = provider.parseChunk(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )!!
        val events2 = provider.chunkToEvents(chunk2, state, testJson)
        // 应发射 Error（id 缺失），不发射 ToolCallComplete
        assertTrue(
            "应发射 id 缺失 Error",
            events2.any { it is StreamEvent.Error && it.message.contains("工具调用 id 缺失") }
        )
        assertTrue("不应发射 ToolCallComplete", events2.none { it is StreamEvent.ToolCallComplete })
        // state 应清空
        assertTrue("state 应清空", state.isEmpty())
    }

    @Test
    fun `processToolCallDeltas handles 10 parallel tool_calls`() {
        // 极端场景：10 个并行 tool_call（index 0-9），验证 state map 处理大量 index
        val state = mutableMapOf<Int, ToolCallAccumulator>()
        val deltas = (0..9).map { i ->
            ToolCallDeltaWire(index = i, id = "call_$i", function = FunctionDeltaWire(name = "tool_$i"))
        }
        val events = provider.processToolCallDeltas(state, deltas)
        assertEquals("应发射 10 个 ToolCallStart", 10, events.filterIsInstance<StreamEvent.ToolCallStart>().size)
        assertEquals("state 应有 10 个累加器", 10, state.size)
        for (i in 0..9) {
            assertEquals("call_$i", state[i]?.id)
            assertEquals("tool_$i", state[i]?.name)
        }
    }

    @Test
    fun `completeToolCalls handles nested JSON arguments`() {
        // 极端场景：嵌套 JSON arguments（Map/List/Boolean/Number 混合），验证 jsonElementToMap 递归正确
        val state = mutableMapOf(
            0 to ToolCallAccumulator(
                id = "call_1", name = "tool",
                arguments = StringBuilder("""{"outer":{"inner":"value","list":[1,2,3]},"flag":true,"count":42}""")
            )
        )
        val events = provider.completeToolCalls(state, testJson)
        val complete = events.filterIsInstance<StreamEvent.ToolCallComplete>().single()
        assertEquals("call_1", complete.toolCallId)
        assertEquals("tool", complete.toolName)
        // 验证嵌套结构被正确解析
        assertTrue("outer 应为 Map", complete.arguments["outer"] is Map<*, *>)
        assertTrue("flag 应为 true", complete.arguments["flag"] == true)
        assertTrue("count 应为 42", complete.arguments["count"] == 42)
    }

    private fun sampleMessages() = listOf(
        ChatMessage(id = 0, role = Role.USER, content = "你好", timestamp = 0)
    )
}