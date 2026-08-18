package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.prism.data.ProviderConfig
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.ToolCallRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * UXR8 N3 视觉功能补充边缘测试（ADR-030）—— ac-verifier TKN-UXR8-B3-ACCEPTANCE-001。
 *
 * 补盲区：
 * - **N-LOW-C（guardrail TKN-UXR8-B3-GUARDRAIL-002）**：工具回路多轮中图片信号保持 ——
 *   原始用户含图消息后追加 assistant(tool_calls) + TOOL 结果（模拟工具回路第 2 轮请求），
 *   `lastOrNull { role == USER }` 仍指向含图消息 → requestHasImage=true → 含图 400 仍映射
 *   视觉降级文案（召回率正确性）。
 * - **mapHttpError 决策表补充**：含图 + 401 → 鉴权失败优先（when 分支顺序）；含图 + 404 →
 *   通用 4xx 文案（不误报视觉降级）。
 * - **N-LOW-A（guardrail N-LOW-A）**：`ConversationScreen.computeInSampleSize`（private 纯函数）
 *   边界值直接单测（反射调用），覆盖 1x1 / 等比 / 超宽 / targetEdge 边界。
 */
class VisionEdgeCaseTest {

    private val apiKeyRepo = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
    private val provider = OpenAICompatibleProvider(
        HttpClient(OkHttp) { expectSuccess = true; install(SSE) },
        apiKeyRepo
    )

    private val imageDataUrl = "data:image/jpeg;base64,AAAA"

    private suspend fun startServer(status: HttpStatusCode): Int {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle { call.respond(status) }
                }
            }
        }
        server.start(wait = false)
        return server.engine.resolvedConnectors().first().port
    }

    // ==================== N-LOW-C：工具回路多轮图片信号保持（召回率） ====================

    @Test
    fun `tool loop round keeps image signal from original user message on 400`() = runBlocking {
        // 模拟工具回路第 2 轮请求：原始用户含图消息（仍是最后一条 USER）+ assistant(tool_calls)
        // + TOOL 结果追加在其后。lastOrNull { role == USER } 必须仍指向含图消息 →
        // requestHasImage=true。R2（ADR-032）：仅当服务端错误详情含「不支持图片」信号时才
        // 映射视觉降级文案——此处返回含图片不支持信号的 body，验证召回率 + 新逻辑双成立。
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle {
                        call.respondText(
                            """{"error":{"message":"This model does not support images"}}""",
                            io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(
                name = "DeepSeek", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "deepseek", models = listOf("deepseek-chat")
            )
            val messages = listOf(
                ChatMessage(id = 0, role = Role.USER, content = "看图并总结", timestamp = 0, imageUrl = imageDataUrl),
                ChatMessage(
                    id = 1, role = Role.ASSISTANT, content = "", timestamp = 1,
                    toolCalls = listOf(ToolCallRef(id = "call_1", functionName = "skill__t", arguments = "{}"))
                ),
                ChatMessage(id = 2, role = Role.TOOL, content = "tool result", timestamp = 2, toolCallId = "call_1")
            )
            val events = provider.streamChat(
                config, messages, systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull("含图工具回路 + 400 应发射 Error", error)
            val e = error ?: return@runBlocking
            assertTrue(
                "工具回路多轮中图片信号应保持（lastOrNull 指向原始含图 USER）",
                e.message.contains("不支持图片")
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    // ==================== mapHttpError 决策表补充 ====================

    @Test
    fun `image request with 401 reports auth error not vision degradation`() = runBlocking {
        // when 分支顺序：401 优先于「含图 + 400」——鉴权失败不应误报为不支持图片
        val port = startServer(HttpStatusCode.Unauthorized)
        try {
            val config = ProviderConfig(
                name = "DeepSeek", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "deepseek", models = listOf("deepseek-chat")
            )
            val messages = listOf(
                ChatMessage(id = 0, role = Role.USER, content = "看图", timestamp = 0, imageUrl = imageDataUrl)
            )
            val events = provider.streamChat(
                config, messages, systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull(error)
            val e = error ?: return@runBlocking
            assertTrue("含图 + 401 应报鉴权失败", e.message.contains("鉴权失败"))
            assertFalse("含图 + 401 不应误报视觉降级", e.message.contains("不支持图片"))
        } finally {
        }
    }

    @Test
    fun `image request with 404 keeps generic 4xx message`() = runBlocking {
        // 其他 4xx（404）即使含图也走通用 4xx 文案（DEF-002：携带状态码），不误报视觉降级
        val port = startServer(HttpStatusCode.NotFound)
        try {
            val config = ProviderConfig(
                name = "DeepSeek", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "deepseek", models = listOf("deepseek-chat")
            )
            val messages = listOf(
                ChatMessage(id = 0, role = Role.USER, content = "看图", timestamp = 0, imageUrl = imageDataUrl)
            )
            val events = provider.streamChat(
                config, messages, systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull(error)
            val e = error ?: return@runBlocking
            assertTrue("含图 + 404 应为通用 4xx 文案", e.message.contains("请求被拒绝"))
            assertFalse("含图 + 404 不应误报视觉降级", e.message.contains("不支持图片"))
        } finally {
        }
    }

    // ==================== N-LOW-A：computeInSampleSize 边界（反射，private 纯函数） ====================

    /** 通过 Java 反射调用 private top-level 纯函数（不修改生产代码）。 */
    private fun computeInSampleSize(origWidth: Int, origHeight: Int, targetEdge: Int): Int {
        return try {
            val cls = Class.forName("io.prism.ui.chat.ConversationScreenKt")
            val method = cls.getDeclaredMethod(
                "computeInSampleSize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(null, origWidth, origHeight, targetEdge) as Int
        } catch (e: Exception) {
            fail("反射调用 computeInSampleSize 失败：${e::class.simpleName} — ${e.message}")
            0
        }
    }

    @Test
    fun `computeInSampleSize 1x1 image returns 1`() {
        assertEquals(1, computeInSampleSize(1, 1, 1024))
    }

    @Test
    fun `computeInSampleSize image at target edge returns 1`() {
        assertEquals(1, computeInSampleSize(1024, 1024, 1024))
    }

    @Test
    fun `computeInSampleSize image just under double target edge returns 1`() {
        // 1500 < 2048（2×target）→ sample=1
        assertEquals(1, computeInSampleSize(1500, 1500, 1024))
    }

    @Test
    fun `computeInSampleSize image at exactly double target edge returns 2`() {
        assertEquals(2, computeInSampleSize(2048, 2048, 1024))
    }

    @Test
    fun `computeInSampleSize huge 8000px photo returns 4`() {
        assertEquals(4, computeInSampleSize(8000, 6000, 1024))
    }

    @Test
    fun `computeInSampleSize extreme ultra wide returns sample from max edge`() {
        // 超宽（长边主导）：4095/4=1023 < 1024 → sample=2；4096/4=1024 ≥ 1024 → sample=4
        assertEquals(2, computeInSampleSize(4095, 100, 1024))
        assertEquals(4, computeInSampleSize(4096, 100, 1024))
    }

    @Test
    fun `computeInSampleSize portrait orientation uses max edge`() {
        // 竖图：短边在 width，长边在 height → 仍按 maxOf 计算
        assertEquals(2, computeInSampleSize(100, 2048, 1024))
    }

    @Test
    fun `computeInSampleSize invalid zero dimensions returns 1 without crash`() {
        // 调用方（encodeImageToDataUrl）已先做 outWidth/outHeight <= 0 → return null 防除零，
        // 此处验证纯函数对非法输入安全返回 1（不崩溃、不溢出）
        assertEquals(1, computeInSampleSize(0, 0, 1024))
    }

    @Test
    fun `computeInSampleSize tiny target edge accumulates powers of two`() {
        // targetEdge=1 极值：1024px 图将一路倍增采样直至 1024（不溢出）
        assertEquals(1024, computeInSampleSize(1024, 1, 1))
    }
}
