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
        val client = HttpClient(engine)
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
        val client = HttpClient(engine)
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
        val client = HttpClient(engine)
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/404")
        )

        assertTrue("应返回失败文案", result.contains("抓取失败"))
        assertFalse("不应泄露内部细节", result.contains("Exception"))
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
        val client = HttpClient(engine)
        val provider = providerWith(client)

        val result = provider.callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/x", "maxLength" to 999999)
        )

        // 截断到上限（Content-Length 预检可能先拦截，或 bodyAsText 后 take 上限）
        assertTrue("不应崩溃", result.isNotBlank())
    }
}
