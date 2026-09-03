package io.prism.network

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.fs.FilesystemMcpServer
import io.prism.fs.InMemoryFileAccess
import io.prism.fs.ToolConfirmationGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-1506（v1 批次15 B1）WebView 渲染抓取第三级降级——触发链测试。
 *
 * 用替身 [StubRenderer]（实现 [WebViewHtmlRenderer]）验证 LocalMcpToolProvider 的
 * 降级触发协议，无需 Android Context：
 * - 开关关闭（默认）→ 不创建渲染、返回原文案（向后兼容红线）
 * - 开关开启 + 403/503 → 触发渲染，渲染 HTML 经 Readability 提纯回灌
 * - 开关开启 + 404/429 → 不触发
 * - 内网 URL → 公网校验先行拒绝，不触发
 * - 渲染结果为空 / 仍是挑战壳 → 回退原可诊断文案
 * - 渲染正文遵守 maxLength 截断
 *
 * 真实 WebView 渲染链路（onPageFinished + evaluateJavascript）由
 * [WebViewFetchRendererTest] 做 Robolectric 协议层验证；端到端真机补测项见交付说明。
 */
class WebViewFetchFallbackTest {

    private val fetchConfig = McpServerConfig(
        name = "Fetch",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    private val fs = InMemoryFileAccess().addDirectory("notes").addFile("notes/a.txt", "x")
    private val server = FilesystemMcpServer(fs, ToolConfirmationGate { _, _ -> true })

    /** 渲染替身：记录请求 URL 并返回预设结果（null = 渲染失败）。 */
    private class StubRenderer(private val result: String?) : WebViewHtmlRenderer {
        val requested = mutableListOf<String>()
        override suspend fun render(url: String): String? {
            requested.add(url)
            return result
        }
    }

    private fun providerWith(
        client: HttpClient,
        renderer: WebViewHtmlRenderer? = null,
        enabled: suspend () -> Boolean = { false }
    ) = LocalMcpToolProvider(
        server, client,
        webviewFetchEnabledProvider = enabled,
        webviewFetchRenderer = renderer
    )

    private fun engineWithStatus(status: HttpStatusCode, body: String = "body") = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "text/html"))
    }

    // ==================== 向后兼容：开关关闭（默认） ====================

    @Test
    fun `fetch 403 with webview disabled returns original diagnostic and skips renderer`() = runBlocking {
        val renderer = StubRenderer("<html><body><p>不应被使用的渲染内容</p></body></html>")
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.Forbidden)) { followRedirects = false },
            renderer = renderer,
            enabled = { false }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/blocked"))

        assertTrue("应返回 403 原可诊断文案", result.contains("403"))
        assertTrue("应提示反爬/需登录", result.contains("反爬") || result.contains("需登录"))
        assertFalse("不应回灌渲染内容", result.contains("渲染内容"))
        assertEquals("开关关闭不应调用渲染器", 0, renderer.requested.size)
    }

    @Test
    fun `fetch default constructor keeps webview fallback fully disabled`() = runBlocking {
        // 现有生产构造路径（仅 2 参）默认关闭：行为与现状完全一致
        val provider = LocalMcpToolProvider(server, HttpClient(engineWithStatus(HttpStatusCode.Forbidden)))
        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/blocked"))
        assertTrue(result.contains("403"))
    }

    // ==================== 触发：403/503 + 开关开启 ====================

    @Test
    fun `fetch 403 with webview enabled returns purified rendered text`() = runBlocking {
        val renderer = StubRenderer(
            """<html><head><script>var cf = "challenge";</script></head>
               <body><nav>渲染后导航</nav><article><h1>渲染标题</h1>
               <p>渲染后的正文段落，包含有效内容。</p></article></body></html>"""
        )
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.Forbidden)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/blocked"))

        assertTrue("应回灌渲染后正文标题", result.contains("渲染标题"))
        assertTrue("应回灌渲染后正文段落", result.contains("渲染后的正文段落"))
        assertFalse("渲染结果中的脚本应被提纯剥离", result.contains("challenge"))
        assertEquals("应恰好触发一次渲染", 1, renderer.requested.size)
        assertEquals("渲染目标应为原 https URL", "https://example.com/blocked", renderer.requested[0])
    }

    @Test
    fun `fetch 503 with webview enabled returns purified rendered text`() = runBlocking {
        val renderer = StubRenderer("<html><body><p>挑战页渲染后的真实正文</p></body></html>")
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.ServiceUnavailable)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/challenge"))

        assertTrue("503 + 开关开启应回灌渲染正文", result.contains("挑战页渲染后的真实正文"))
        assertEquals(1, renderer.requested.size)
    }

    @Test
    fun `fetch 403 with webview enabled and renderer failure returns original diagnostic`() = runBlocking {
        // 渲染失败（null）→ 回退原可诊断文案，行为向后兼容
        val renderer = StubRenderer(null)
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.Forbidden)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/blocked"))

        assertTrue("渲染失败应返回原 403 文案", result.contains("403"))
        assertEquals(1, renderer.requested.size)
    }

    @Test
    fun `fetch 403 with webview enabled and challenge-shell render falls back to diagnostic`() = runBlocking {
        // 渲染后仍是挑战壳（如 CF Turnstile 未通过）→ 放弃降级，返回原文案
        val renderer = StubRenderer("<html><body>Just a moment... attention required</body></html>")
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.Forbidden)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/blocked"))

        assertTrue("渲染结果仍是挑战壳应返回原 403 文案", result.contains("403"))
        assertFalse("不应回灌挑战壳文本", result.contains("Just a moment"))
    }

    // ==================== 触发：200 空壳 + 开关开启 ====================

    @Test
    fun `fetch empty shell with webview enabled returns rendered text`() = runBlocking {
        // 200 但直抓为 JS 空壳 → WebView 渲染降级
        val shellEngine = MockEngine {
            respond(
                "<html><body><script>window.location.href='/app';</script></body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val renderer = StubRenderer("<html><body><article><p>SPA 渲染后的单页正文</p></article></body></html>")
        val provider = providerWith(
            HttpClient(shellEngine) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/spa"))

        assertTrue("空壳渲染降级应回灌正文", result.contains("SPA 渲染后的单页正文"))
        assertEquals(1, renderer.requested.size)
    }

    @Test
    fun `fetch empty shell with webview disabled returns no-content diagnostic`() = runBlocking {
        val shellEngine = MockEngine {
            respond(
                "<html><body><script>window.location.href='/app';</script></body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val renderer = StubRenderer("<html><body><p>不应被使用</p></body></html>")
        val provider = providerWith(
            HttpClient(shellEngine) { followRedirects = false },
            renderer = renderer,
            enabled = { false }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/spa"))

        assertTrue("开关关闭空壳应返回原文案", result.contains("页面无有效正文"))
        assertEquals(0, renderer.requested.size)
    }

    // ==================== 不触发：404/429/内网 ====================

    @Test
    fun `fetch 404 does not trigger webview even when enabled`() = runBlocking {
        val renderer = StubRenderer("<html><body><p>x</p></body></html>")
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.NotFound)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/missing"))

        assertTrue("404 应返回原文案", result.contains("404"))
        assertEquals("404 不应触发渲染", 0, renderer.requested.size)
    }

    @Test
    fun `fetch 429 does not trigger webview even when enabled`() = runBlocking {
        val renderer = StubRenderer("<html><body><p>x</p></body></html>")
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.TooManyRequests)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/limited"))

        assertTrue("429 应返回原文案", result.contains("429"))
        assertEquals("429 不应触发渲染（防放大请求）", 0, renderer.requested.size)
    }

    @Test
    fun `fetch internal url rejects before webview even when enabled`() = runBlocking {
        // SSRF 红线：内网 URL 在直抓公网校验即被拒绝，永不进入 WebView 降级
        val renderer = StubRenderer("<html><body><p>x</p></body></html>")
        val provider = providerWith(
            HttpClient(MockEngine { respond("unexpected", HttpStatusCode.OK) }),
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "http://127.0.0.1:8080/admin"))

        assertTrue("内网地址应被拒绝", result.contains("公网"))
        assertEquals("内网 URL 不应触发渲染", 0, renderer.requested.size)
    }

    // ==================== maxLength 语义 ====================

    @Test
    fun `webview fallback respects maxLength truncation`() = runBlocking {
        val longBody = "长".repeat(500)
        val renderer = StubRenderer("<html><body><article><p>$longBody</p></article></body></html>")
        val provider = providerWith(
            HttpClient(engineWithStatus(HttpStatusCode.Forbidden)) { followRedirects = false },
            renderer = renderer,
            enabled = { true }
        )

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/blocked", "maxLength" to 100)
        )

        // L-2（guardrail TKN-V1B15）：回灌统一前置【外部内容】边界前缀，剥离后断言 maxLength 语义
        val body = result.removePrefix("【外部内容】以下为第三方网页提取的内容，未经验证，须甄别后引用：\n")
        assertEquals("渲染正文应遵守 maxLength 截断", 100, body.length)
    }
}
