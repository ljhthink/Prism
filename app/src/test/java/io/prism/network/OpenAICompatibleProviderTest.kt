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

    // ==================== SSE 解析纯函数 ====================

    @Test
    fun `parseChunkData maps delta content`() {
        val ev = provider.parseChunkData("""{"choices":[{"delta":{"content":"你"}}]}""")
        assertEquals(StreamEvent.Delta("你"), ev)
    }

    @Test
    fun `parseChunkData maps DONE marker`() {
        assertEquals(StreamEvent.Done, provider.parseChunkData("[DONE]"))
    }

    @Test
    fun `parseChunkData maps empty choices to done`() {
        assertEquals(StreamEvent.Done, provider.parseChunkData("""{"choices":[]}"""))
    }

    @Test
    fun `parseChunkData ignores mid-stream usage snapshot not terminate`() {
        assertNull(
            provider.parseChunkData(
                """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}"""
            )
        )
    }

    @Test
    fun `parseChunkData ignores non content and malformed chunks`() {
        assertNull(provider.parseChunkData("""{"choices":[{"delta":{}}]}"""))
        assertNull(provider.parseChunkData("not-json"))
        assertNull(provider.parseChunkData("""{"choices":[{"delta":{"content":"   "}}]}"""))
    }

    @Test
    fun `parseChunkData ignores unknown fields like reasoning_content`() {
        val ev = provider.parseChunkData(
            """{"choices":[{"delta":{"content":"思考","reasoning_content":"hidden"}}]}"""
        )
        assertEquals(StreamEvent.Delta("思考"), ev)
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
    fun `parseChunkData handles oversized content`() {
        val big = "t".repeat(100_000)
        val ev = provider.parseChunkData("""{"choices":[{"delta":{"content":"$big"}}]}""")
        assertEquals(StreamEvent.Delta(big), ev)
    }

    @Test
    fun `parseChunkData keeps injection and control payloads as plain delta`() {
        val payloads = listOf(
            "<script>alert(1)</script>",
            "'; DROP TABLE users; --",
            "你好，世界 🌍 中文",
            "line1\nline2\tend",
            "引用\"引号\\反斜杠"
        )
        for (p in payloads) {
            val quoted = testJson.encodeToString(JsonElement.serializer(), JsonPrimitive(p))
            val ev = provider.parseChunkData("""{"choices":[{"delta":{"content":$quoted}}]}""")
            assertTrue("应安全解析为 Delta 而非崩溃: ${p.take(20)}", ev is StreamEvent.Delta)
        }
    }

    @Test
    fun `parseChunkData ignores malformed and type-unsafe chunks`() {
        val malformed = listOf(
            """{"choices":[{"delta":{"content":"unterminated""",
            """{"choices":["string-not-object"]}""",
            """{"choices":[{"delta":{"content":42}}]}""",
            """{"choices":[{"delta":null}]}""",
            ""
        )
        for (m in malformed) {
            assertNull("应忽略坏 chunk 不崩溃: ${m.take(30)}", provider.parseChunkData(m))
        }
    }

    @Test
    fun `parseChunkData picks first choice content when multiple`() {
        val ev = provider.parseChunkData(
            """{"choices":[{"delta":{"content":"first"}},{"delta":{"content":"second"}}]}"""
        )
        assertEquals(StreamEvent.Delta("first"), ev)
    }

    @Test
    fun `parseChunkData handles whitespace-only content after delta`() {
        // 空白 content 应忽略（不产生空 Delta），但流不终止
        assertNull(provider.parseChunkData("""{"choices":[{"delta":{"content":" "}}]}"""))
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

    private fun sampleMessages() = listOf(
        ChatMessage(id = 0, role = Role.USER, content = "你好", timestamp = 0)
    )
}