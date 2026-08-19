package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.prism.data.ProviderConfig
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ac-verifier 补充测试（TKN-V1-B2-ACCEPTANCE-001，US-301 AC2）—— 视觉不支持信号字段断言补盲。
 *
 * 既有 [VisionEdgeCaseTest] 验证 mapHttpError 决策表（消息文案），但未断言
 * [StreamEvent.Error.visionUnsupported] 字段本身。本文件直接验证：
 * - 含图 + 400 + 视觉不支持关键词 → `visionUnsupported == true`（触发链信号正确）
 * - 含图 + 400 + 无视觉关键词（如图片格式问题）→ `visionUnsupported == false`（不误触发旁路）
 * - 无图 + 400 → `visionUnsupported == false`
 */
class VisionNetworkSignalSupplementTest {

    private val apiKeyRepo = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
    private val provider = OpenAICompatibleProvider(
        HttpClient(OkHttp) { expectSuccess = true; install(SSE) },
        apiKeyRepo
    )

    private val imageDataUrl = "data:image/jpeg;base64,AAAA"

    private fun configWithPort(port: Int) = ProviderConfig(
        name = "DeepSeek", baseUrl = "http://127.0.0.1:$port",
        apiKeyRef = "deepseek", models = listOf("deepseek-chat")
    )

    private fun messagesWithImage() = listOf(
        ChatMessage(id = 0, role = Role.USER, content = "看图", timestamp = 0, imageUrl = imageDataUrl)
    )

    private fun messagesWithoutImage() = listOf(
        ChatMessage(id = 0, role = Role.USER, content = "纯文本", timestamp = 0)
    )

    @Test
    fun `400 with vision-unsupported keyword sets visionUnsupported true`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle {
                        call.respondText(
                            """{"error":{"message":"This model does not support images"}}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val events = provider.streamChat(
                configWithPort(port), messagesWithImage(),
                systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull("含图 + 400（视觉关键词）应发射 Error", error)
            assertEquals("visionUnsupported 应为 true（触发旁路信号）", true, error!!.visionUnsupported)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `400 without vision keyword keeps visionUnsupported false`() = runBlocking {
        // 多模态模型（kimi-k2.6）图片格式/大小问题 400：错误详情不含视觉不支持信号
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle {
                        call.respondText(
                            """{"error":{"message":"image too large"}}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val events = provider.streamChat(
                configWithPort(port), messagesWithImage(),
                systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull(error)
            assertEquals("无视觉信号不得触发旁路", false, error!!.visionUnsupported)
            assertTrue("应展示具体错误供诊断", error.message.contains("图片请求被拒绝"))
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `400 without image keeps visionUnsupported false`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle {
                        call.respondText(
                            """{"error":{"message":"This model does not support images"}}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val events = provider.streamChat(
                configWithPort(port), messagesWithoutImage(),
                systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull(error)
            assertEquals("无图 400 不置视觉信号", false, error!!.visionUnsupported)
            assertTrue("无图 400 应为通用文案", error.message.contains("请求被拒绝"))
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `successful SSE response has no error event`() = runBlocking {
        // streamChat 走 SSE（text/event-stream）协议；成功响应仅发射 Delta/Done，无 Error。
        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    handle {
                        call.respondText(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
                            ContentType.Text.EventStream, HttpStatusCode.OK
                        )
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val events = provider.streamChat(
                configWithPort(port), messagesWithImage(),
                systemPrompt = null, ragContext = null,
                tools = null, toolChoice = null, thinkingEnabled = null, reasoningEffort = null
            ).toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNull("成功响应不应发射 Error", error)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }
}
