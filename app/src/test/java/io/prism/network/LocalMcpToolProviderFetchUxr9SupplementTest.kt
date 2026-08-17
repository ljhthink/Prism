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
 * UXR9 US-905 补充测试（ac-verifier，TKN-UXR9-ACCEPTANCE-001）。
 *
 * 主 Agent 的 [LocalMcpToolProviderFetchTest] / [LocalMcpToolProviderFetchSupplementTest]
 * 覆盖了 SSRF 绕过向量（userinfo/IPv6/混合大小写 scheme），但 **未覆盖 PRD AC-2 明确要求的
 * 「含中文/非 ASCII URL 校验」**（`java.net.URI` 对中文路径抛 URISyntaxException 误拒是
 * Bug5 根因之一）。本文件补充验证：
 *
 * - AC-2：含中文路径/非 ASCII 的 URL 不再被 `isPublicHttpUrl` 误拒（正则提取 host）
 * - AC-2：Fetch 工具对中文 URL 实际发起请求并返回内容（消除测试-生产行为漂移）
 * - AC-3：生产路径（非 2xx 按状态码处理而非抛异常）已由主 Agent 测试覆盖，此处补
 *   Content-Length 预检与 404 的联合断言（expectSuccess=false 语义）
 */
class LocalMcpToolProviderFetchUxr9SupplementTest {

    private val fetchConfig = McpServerConfig(
        name = "Fetch",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    private val fs = InMemoryFileAccess().addDirectory("notes").addFile("notes/a.txt", "x")
    private val server = FilesystemMcpServer(fs, ToolConfirmationGate { _, _ -> true })

    private fun providerWith(client: HttpClient? = null) = LocalMcpToolProvider(server, client)

    // ==================== AC-2：含中文/非 ASCII URL 校验 ====================

    @Test
    fun `isPublicHttpUrl accepts chinese path with ascii public host`() {
        // Bug5 根因回归：旧实现 `java.net.URI("https://baike.baidu.com/item/昔涟")`
        // 对含中文路径抛 URISyntaxException → 误拒。新实现正则提取 host（ascii 公网主机），
        // 中文仅存在于 path，不影响 host 校验 → 应放行。
        assertTrue("中文路径 URL 应被接受", providerWith().isPublicHttpUrl("https://baike.baidu.com/item/昔涟"))
        assertTrue("多段中文路径 URL 应被接受", providerWith().isPublicHttpUrl("https://example.com/中文/路径/测试"))
        assertTrue("中文 query 参数 URL 应被接受", providerWith().isPublicHttpUrl("https://example.com/search?q=昔涟"))
    }

    @Test
    fun `isPublicHttpUrl rejects private host even with chinese path`() {
        // 中文路径不降低安全校验：host 为回环/元数据地址时仍拒绝（fail-closed 保持）
        assertFalse(providerWith().isPublicHttpUrl("http://127.0.0.1/中文路径"))
        assertFalse(providerWith().isPublicHttpUrl("http://169.254.169.254/latest/meta-data/中文"))
    }

    @Test
    fun `fetch tool makes request for chinese path url and returns content`() = runBlocking {
        // AC-2 集成：中文 URL 通过 isPublicHttpUrl 后实际发起请求，返回剥离后的内容
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = "百度百科 昔涟 词条内容",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
            )
        }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/item/昔涟")
        )
        assertTrue("应返回抓取内容", result.contains("百度百科"))
        assertTrue("应发起请求", !capturedUrl.isNullOrBlank())
    }

    // ==================== AC-3：生产路径（expectSuccess=false 语义） ====================

    @Test
    fun `fetch tool handles 404 without throwing and returns status message`() = runBlocking {
        // expectSuccess=false：非 2xx 不抛异常，按状态码处理返回文案（不泄露内部细节）
        val engine = MockEngine {
            respond("Not Found", HttpStatusCode.NotFound)
        }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/missing")
        )
        assertTrue("404 应返回失败文案", result.contains("抓取失败"))
        assertTrue("应含状态码信息", result.contains("404"))
        assertFalse("不应泄露异常细节", result.contains("Exception"))
    }

    @Test
    fun `fetch tool 500 also returns status message not exception`() = runBlocking {
        val engine = MockEngine {
            respond("Server Error", HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/error")
        )
        assertTrue("500 应返回失败文案", result.contains("抓取失败"))
        assertTrue("应含状态码 500", result.contains("500"))
    }

    // ==================== Q-LOW-2：IPv6 字面量 + userinfo 组合 ====================

    @Test
    fun `isPublicHttpUrl rejects ipv6 literal combined with userinfo`() {
        // Q-LOW-2（guardrail TKN-UXR9-GUARDRAIL-002）：先剥离 userinfo 再判 IPv6 字面量。
        // `user:pass@[::1]:8080` 剥 userinfo 后剩余 `[::1]:8080` → host=::1 → 回环拒绝。
        // 旧实现先判 `[` 后剥 userinfo，会残留 `[::1` 走 IDN 失败 → fail-closed 也拒绝，
        // 本测试锁定新实现的主机提取路径仍保持拒绝（且提取正确）。
        assertFalse("userinfo+IPv6 回环应拒绝", providerWith().isPublicHttpUrl("http://user:pass@[::1]:8080/"))
        assertFalse("userinfo+IPv6 链路本地应拒绝", providerWith().isPublicHttpUrl("http://user:pass@[fe80::1]/x"))
    }

    @Test
    fun `isPublicHttpUrl still rejects plain ipv6 loopback`() {
        // 回归：IPv6 字面量单独出现也拒绝（L-4 既有向量不回归）
        assertFalse(providerWith().isPublicHttpUrl("http://[::1]:8080/"))
        assertFalse(providerWith().isPublicHttpUrl("https://[::1]/"))
    }

    // ==================== Q-LOW-5：响应体硬上限 ====================

    @Test
    fun `fetch tool rejects oversized response body`() = runBlocking {
        // Q-LOW-5（guardrail TKN-UXR9-GUARDRAIL-002）：病态超大响应（>MAX_FETCH_READ_CAP=1MB）
        // 被拒绝——Content-Length 预检或 readRemaining 读上限任一触发，均不崩溃/不耗尽内存。
        val big = "x".repeat(1_100_000)
        val engine = MockEngine {
            respond(
                content = big,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/huge")
        )
        assertTrue("超大响应应返回失败文案", result.contains("抓取失败"))
        assertTrue("应提示响应过大", result.contains("响应过大"))
        assertFalse("不应把 MB 级 body 全量带进结果", result.length > 1000)
    }

    @Test
    fun `fetch tool still truncates moderately long body to maxLength`() = runBlocking {
        // Q-LOW-5 回归：15k body（< 1MB 读上限）应正常读取并按 maxLength=10000 截断，
        // 不被误判为病态超大响应（LocalMcpToolProviderFetchSupplementTest 既有语义）。
        val engine = MockEngine {
            respond(
                content = "z".repeat(15_000),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/long", "maxLength" to 999999)
        )
        assertEquals("15k body 应截断到 10000", 10_000, result.length)
    }
}
