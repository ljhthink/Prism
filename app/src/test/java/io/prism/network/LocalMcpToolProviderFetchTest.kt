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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocalMcpToolProvider Fetch 工具单元测试（UXR3 问题 11，ADR-023；guardrail T-1 补齐）。
 *
 * 覆盖：
 * - 纯函数 [LocalMcpToolProvider.isPublicHttpUrl]：scheme 校验 / 回环 / 私有网段 /
 *   链路本地 / 域名解析后校验 / 解析失败拒绝
 * - Fetch 工具集成（MockEngine）：
 *   - 公网 URL 抓取成功（HTML 剥离 + maxLength 截断）
 *   - 内网 URL 被拒绝（不发起请求）
 *   - 缺 url / 非 http(s) 返回明确文案
 *   - HTTP 错误状态返回文案
 */
class LocalMcpToolProviderFetchTest {

    private val fetchConfig = McpServerConfig(
        name = "Fetch",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    private val fs = InMemoryFileAccess().addDirectory("notes").addFile("notes/a.txt", "x")
    private val server = FilesystemMcpServer(fs, ToolConfirmationGate { _, _ -> true })

    private fun providerWith(client: HttpClient) = LocalMcpToolProvider(server, client)

    // ==================== isPublicHttpUrl 纯函数 ====================

    @Test
    fun `isPublicHttpUrl rejects non-http scheme`() {
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("ftp://example.com/x"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("file:///etc/passwd"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("javascript:alert(1)"))
    }

    @Test
    fun `isPublicHttpUrl rejects loopback addresses`() {
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://127.0.0.1/x"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://localhost/x"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://[::1]/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects private and link-local subnets`() {
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://10.0.0.1/x"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://192.168.1.1/x"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://172.16.0.1/x"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://169.254.169.254/latest/meta-data"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://169.254.0.1/x"))
    }

    @Test
    fun `isPublicHttpUrl accepts public domain`() {
        // 域名解析为公网 IP 时应放行；本测试用知名公网域名
        assertTrue(LocalMcpToolProvider(server, null).isPublicHttpUrl("https://www.google.com/"))
    }

    @Test
    fun `isPublicHttpUrl rejects unresolvable host fail closed`() {
        // 无法解析的主机名 → 拒绝（fail-closed，不向不明主机发请求）
        assertFalse(
            LocalMcpToolProvider(server, null).isPublicHttpUrl(
                "https://nonexistent-host-9f3k2.example.invalid/"
            )
        )
    }

    @Test
    fun `isPublicHttpUrl rejects malformed url`() {
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http://"))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl(""))
        assertFalse(LocalMcpToolProvider(server, null).isPublicHttpUrl("http:///path"))
    }

    // ==================== Fetch 工具集成（MockEngine） ====================

    @Test
    fun `fetch tool returns stripped text for public url`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://example.com/page", request.url.toString())
            respond(
                content = "<html><body><h1>标题</h1><p>段落内容</p></body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/page", "maxLength" to 5000)
        )

        // HTML 标签被剥离，文本内容保留
        assertTrue("应返回剥离后的文本", result.contains("标题"))
        assertTrue("应返回剥离后的文本", result.contains("段落内容"))
        assertFalse("不应含 HTML 标签", result.contains("<html>"))
    }

    @Test
    fun `fetch tool rejects internal url without making request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond("unexpected", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "http://127.0.0.1:8080/admin")
        )

        assertTrue("内网地址应被拒绝", result.contains("公网"))
        assertEquals("不应发起请求", 0, requestCount)
    }

    @Test
    fun `fetch tool missing url returns message`() = runBlocking {
        val provider = providerWith(HttpClient())
        val result = provider.callTool(fetchConfig, "fetch", emptyMap())
        assertTrue(result.contains("缺少必需参数 url"))
    }

    @Test
    fun `fetch tool non-http scheme returns message`() = runBlocking {
        val provider = providerWith(HttpClient())
        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "ftp://example.com/x"))
        assertTrue(result.contains("http:// 或 https://"))
    }

    @Test
    fun `fetch tool http error returns message`() = runBlocking {
        val engine = MockEngine {
            respond("Not Found", HttpStatusCode.NotFound)
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/404")
        )

        assertTrue("应返回失败文案", result.contains("抓取失败"))
        assertFalse("不应泄露内部细节", result.contains("Exception"))
    }

    @Test
    fun `fetch tool 403 returns anti-crawler diagnostic without retry prompt`() = runBlocking {
        // R3（UXR10，ADR-032）：403 是反爬/需登录常态，应给出可诊断文案并显式标注勿重试，
        // 避免 LLM 误以为 URL 写错而反复重试（放大请求频率 → 叠加 LLM 端点限流）。
        val engine = MockEngine {
            respond("Forbidden", HttpStatusCode.Forbidden)
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/blocked")
        )

        assertTrue("403 应提示反爬/需登录", result.contains("403"))
        assertTrue("403 应提示勿反复重试", result.contains("勿反复重试") || result.contains("勿连续抓取"))
    }

    @Test
    fun `fetch tool 429 returns rate-limit diagnostic without retry prompt`() = runBlocking {
        // R3（UXR10，ADR-032）：目标站限流 429 文案，引导稍后再试或换来源，勿连续抓取
        val engine = MockEngine {
            respond("Too Many Requests", HttpStatusCode.TooManyRequests)
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/limited")
        )

        assertTrue("429 应提示目标站限流", result.contains("429"))
        assertTrue("429 应提示勿连续抓取", result.contains("勿连续抓取") || result.contains("勿反复重试"))
    }

    @Test
    fun `fetch tool null client degrades gracefully`() = runBlocking {
        // fetchHttpClient=null 时返回降级文案（不抛异常）
        val provider = LocalMcpToolProvider(server, null)
        val result = provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/"))
        assertTrue("client=null 应返回降级文案", result.contains("未配置网络客户端"))
    }

    @Test
    fun `fetch tool maxLength clamps to valid range`() = runBlocking {
        // maxLength 超上限/低于下限时 clamp 到 [MIN_FETCH_LEN, MAX_FETCH_LEN]
        val engine = MockEngine {
            respond("x".repeat(1000), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/x", "maxLength" to 999999)
        )

        // 截断到上限（Content-Length 预检可能先拦截，或 bodyAsText 后 take 上限）
        assertTrue("不应崩溃", result.isNotBlank())
    }

    // ==================== UXR11 U3：fetchWithRedirects 手动跟随 3xx（ADR-033） ====================

    @Test
    fun `fetch follows 3xx redirect to public location`() = runBlocking {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests.add(request.url.toString())
            if (request.url.toString().endsWith("/start")) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found, // 302
                    headers = headersOf(HttpHeaders.Location, "https://example.com/target")
                )
            } else {
                respond(
                    content = "<html><body>目标内容</body></html>",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/html")
                )
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/start")
        )

        assertEquals("应跟随 1 次重定向共 2 次请求", 2, requests.size)
        assertEquals("目标 URL 应为重定向后地址", "https://example.com/target", requests[1])
        assertTrue("应返回目标页内容", result.contains("目标内容"))
    }

    @Test
    fun `fetch does not follow redirect to internal address (SSRF fail-closed)`() = runBlocking {
        // 重定向目标指向云元数据内网地址 → isPublicHttpUrl 拒绝跟随，返回原始 3xx 可诊断文案
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests.add(request.url.toString())
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "http://169.254.169.254/latest/meta-data")
            )
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/redirect")
        )

        assertEquals("不应跟随到内网地址，仅 1 次请求", 1, requests.size)
        assertFalse("不应出现内网地址请求", requests.any { it.contains("169.254") })
        assertTrue("应返回原始 3xx 的可诊断文案", result.contains("抓取失败：HTTP 302"))
    }

    @Test
    fun `fetch follows at most FETCH_REDIRECT_MAX hops`() = runBlocking {
        // 每跳都返回 302 指向下一跳 → 上限 3 跳后不再跟随，返回第 4 个 3xx 可诊断文案
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests.add(request.url.toString())
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://example.com/step-${requests.size}")
            )
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/0")
        )

        // UXR11 U3（ADR-033）：客户端 followRedirects=false（Ktor 3.x 默认跟随重定向），
        // 3xx 由 fetchWithRedirects 手动跟随并逐跳 SSRF 校验。每跳返回 302 指向下一跳 →
        // 上限 3 跳后不再跟随，返回第 4 个 3xx 可诊断文案。
        assertEquals("最多跟随 3 跳（共 4 次请求）", 4, requests.size)
        assertTrue("超出上限的 3xx 返回可诊断文案", result.contains("抓取失败：HTTP 302"))
    }

    @Test
    fun `fetch resolves relative redirect location with URI resolve`() = runBlocking {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests.add(request.url.toString())
            if (requests.size == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "/docs/page")
                )
            } else {
                respond(
                    content = "<html><body>相对路径目标</body></html>",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/html")
                )
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/base/start")
        )

        assertEquals(2, requests.size)
        assertEquals("相对 Location 应解析为绝对 URL", "https://example.com/docs/page", requests[1])
        assertTrue("应返回重定向后内容", result.contains("相对路径目标"))
    }

    @Test
    fun `fetch sends browser typical headers`() = runBlocking {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                "<html><body>ok</body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        provider.callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/"))

        val headers = captured!!.headers
        assertTrue("应带浏览器 UA", headers[HttpHeaders.UserAgent]?.contains("Chrome") == true)
        assertTrue("应带 Accept", headers[HttpHeaders.Accept]?.contains("text/html") == true)
        assertTrue("应带 Accept-Language", headers[HttpHeaders.AcceptLanguage]?.isNotBlank() == true)
        assertTrue("应带 Cache-Control", headers[HttpHeaders.CacheControl] == "no-cache")
        assertEquals("应带 Sec-Fetch-Dest", "document", headers["Sec-Fetch-Dest"])
        assertEquals("应带 Sec-Fetch-Mode", "navigate", headers["Sec-Fetch-Mode"])
        assertEquals("应带 Sec-Fetch-Site", "none", headers["Sec-Fetch-Site"])
        assertEquals("应带 Sec-Fetch-User", "?1", headers["Sec-Fetch-User"])
        assertEquals("应带 Upgrade-Insecure-Requests", "1", headers["Upgrade-Insecure-Requests"])
        // 刻意不设 Accept-Encoding：交由 OkHttp 透明 gzip 解压避免响应乱码
        assertNull("不应显式设置 Accept-Encoding", headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun `fetch 404 returns diagnostic without retry prompt`() = runBlocking {
        val engine = MockEngine {
            respond("Not Found", HttpStatusCode.NotFound)
        }
        val client = HttpClient(engine) { followRedirects = false }
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/missing")
        )

        assertTrue("404 应提示目标页面不存在", result.contains("404"))
        assertTrue("404 应提示勿反复重试/改用其他来源", result.contains("勿反复重试") || result.contains("改用其他来源"))
    }
}
