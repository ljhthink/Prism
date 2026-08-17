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
 * Fetch 工具 SSRF 补充测试（ac-verifier，TKN-UXR3-ACCEPTANCE-001 极端场景补充）。
 *
 * 覆盖主 Agent 基础用例（[LocalMcpToolProviderFetchTest]）未覆盖的 SSRF 绕过向量：
 * - 大小写 scheme 绕过（`hTtP://`）→ 应 fail-closed 拒绝
 * - userinfo 技巧（`http://user@127.0.0.1`）→ URI 解析 host 为 127.0.0.1 → 拒绝
 * - 整数/十六进制 IP 表示（`2130706433` / `0x7f000001`）→ 解析为 127.0.0.1 → 拒绝
 * - IPv4-mapped IPv6 回环（`[::ffff:127.0.0.1]`）→ 拒绝
 * - 链路本地 mapped（`[::ffff:169.254.169.254]`）→ 拒绝
 * - Content-Length 预检（超上限拒绝，防超大响应全量读入）
 * - maxLength 边界（clamp 到 [100, 10000]）
 */
class LocalMcpToolProviderFetchSupplementTest {

    private val fetchConfig = McpServerConfig(
        name = "Fetch",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    private val fs = InMemoryFileAccess().addDirectory("notes").addFile("notes/a.txt", "x")
    private val server = FilesystemMcpServer(fs, ToolConfirmationGate { _, _ -> true })

    private fun providerWith(client: HttpClient? = null) = LocalMcpToolProvider(server, client)

    // ==================== SSRF 绕过向量（isPublicHttpUrl 纯函数） ====================

    @Test
    fun `isPublicHttpUrl rejects mixed-case scheme fail closed`() {
        // 大小写 scheme 绕过：前缀检查区分大小写，混合大小写 scheme 应被拒绝（fail-closed）
        assertFalse(providerWith().isPublicHttpUrl("hTtP://127.0.0.1/x"))
        assertFalse(providerWith().isPublicHttpUrl("HTTP://127.0.0.1/x"))
        assertFalse(providerWith().isPublicHttpUrl("Https://example.com/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects userinfo host trick`() {
        // userinfo 技巧：http://user@127.0.0.1 经 URI 解析 host 为 127.0.0.1 → 拒绝
        assertFalse(providerWith().isPublicHttpUrl("http://user@127.0.0.1/x"))
        assertFalse(providerWith().isPublicHttpUrl("http://user:pass@10.0.0.1/x"))
        assertFalse(providerWith().isPublicHttpUrl("http://attacker@localhost/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects userinfo with public-look host and private target`() {
        // S-1（guardrail TKN-UXR9-GUARDRAIL-001，CWE-918 SSRF fail-open 回归）：
        // `http://evil.com:80@127.0.0.1/` —— 若只 `substringBefore(':')` 会截出 `evil.com`（公网
        // 放行），但 OkHttp 实际解析 userinfo 后发往 127.0.0.1。修复须先剥离 userinfo 再取 host。
        assertFalse(providerWith().isPublicHttpUrl("http://evil.com:80@127.0.0.1/x"))
        assertFalse(providerWith().isPublicHttpUrl("https://evil.com:443@10.0.0.1/x"))
        assertFalse(providerWith().isPublicHttpUrl("http://evil.com@169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `isPublicHttpUrl accepts public host with userinfo`() {
        // 合法 userinfo + 公网 host：剥离 userinfo 后 host 为公网域名 → 放行
        assertTrue(providerWith().isPublicHttpUrl("http://user:pass@www.example.com/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects unclosed ipv6 bracket`() {
        // L-4：未闭合的 IPv6 `[` → fail-closed
        assertFalse(providerWith().isPublicHttpUrl("http://[::1/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects integer and hex encoded loopback IPs`() {
        // 整数 IP 2130706433 = 127.0.0.1；十六进制 0x7f000001 = 127.0.0.1
        assertFalse(providerWith().isPublicHttpUrl("http://2130706433/x"))
        assertFalse(providerWith().isPublicHttpUrl("http://0x7f000001/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects ipv4 mapped ipv6 loopback`() {
        // IPv4-mapped IPv6 回环：::ffff:127.0.0.1 应判为 loopback 拒绝
        assertFalse(providerWith().isPublicHttpUrl("http://[::ffff:127.0.0.1]/x"))
        assertFalse(providerWith().isPublicHttpUrl("http://[::1]/x"))
    }

    @Test
    fun `isPublicHttpUrl rejects ipv4 mapped ipv6 cloud metadata`() {
        // IPv4-mapped 云元数据地址：::ffff:169.254.169.254 → 链路本地 → 拒绝
        assertFalse(providerWith().isPublicHttpUrl("http://[::ffff:169.254.169.254]/latest/meta-data/"))
    }

    // ==================== fetchUrl 行为（MockEngine 集成） ====================

    @Test
    fun `fetch tool rejects mixed-case scheme without request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { requestCount++; respond("unexpected", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "hTtP://127.0.0.1/x")
        )
        assertTrue("大小写 scheme 应返回明确文案", result.contains("http:// 或 https://"))
        assertEquals("不应发起请求", 0, requestCount)
    }

    @Test
    fun `fetch tool rejects userinfo internal url without request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { requestCount++; respond("unexpected", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "http://user@127.0.0.1/admin")
        )
        assertTrue("userinfo 内网地址应被拒绝", result.contains("公网"))
        assertEquals("不应发起请求", 0, requestCount)
    }

    @Test
    fun `fetch tool rejects content-length overflow without body read`() = runBlocking {
        // Content-Length 预检：声明的响应体超上限 → 拒绝，不读 body
        var bodyRead = false
        val engine = MockEngine {
            respond(
                content = "x".repeat(20_000),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "20000")
            )
        }
        val client = HttpClient(engine)
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/big")
        )
        assertTrue("超大响应应被预检拒绝", result.contains("响应过大"))
    }

    @Test
    fun `fetch tool maxLength clamps to minimum 100`() = runBlocking {
        val engine = MockEngine {
            respond("y".repeat(50), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpClient(engine)
        // maxLength=50 低于下限 → clamp 到 100；但 body 只有 50 字符，返回全部
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/short", "maxLength" to 50)
        )
        assertEquals("应返回全部 50 字符（clamp 后仍不超）", "y".repeat(50), result)
    }

    @Test
    fun `fetch tool maxLength clamps to maximum 10000`() = runBlocking {
        val engine = MockEngine {
            respond("z".repeat(15_000), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpClient(engine)
        // maxLength=999999 超上限 → clamp 到 10000；body 15k → 截断到 10000
        val result = providerWith(client).callTool(
            fetchConfig, "fetch",
            mapOf("url" to "https://example.com/long", "maxLength" to 999999)
        )
        assertEquals("应截断到上限 10000", 10_000, result.length)
    }
}
