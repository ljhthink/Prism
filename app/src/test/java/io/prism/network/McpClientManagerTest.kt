package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * McpClientManager 纯函数单元测试（ADR-005 5.4）。
 *
 * 覆盖 [McpClientManager.resolveHeaders] 与 [McpClientManager.renderResult]：
 * - 鉴权与自定义请求头合并规则（不覆盖显式 Authorization、大小写规范化）
 * - 工具调用结果文本渲染（TextContent 提取 / 错误标记 / 空结果）
 *
 * 端到端 Streamable HTTP 事务由真实 MCP Server 集成测试验证（本期不在 JVM 单测覆盖）。
 */
class McpClientManagerTest {

    private lateinit var manager: McpClientManager

    @Before
    fun setUp() {
        // 仅测试纯函数（resolveHeaders / renderResult），不发出真实请求；
        // 仍需提供 MockEngine handler，否则 Ktor 3.x 构造即抛异常。
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { respond("") }
            }
        }
        val apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
        manager = McpClientManager(httpClient, apiKeyRepository)
    }

    // ==================== resolveHeaders：鉴权头合并 ====================

    @Test
    fun resolveHeaders_noKey_noCustom_returnsEmpty() {
        assertEquals(emptyMap<String, String>(), manager.resolveHeaders(emptyMap(), null))
    }

    @Test
    fun resolveHeaders_withKey_injectsBearerAuth() {
        val headers = manager.resolveHeaders(emptyMap(), "sk-abc")
        assertEquals(mapOf("Authorization" to "Bearer sk-abc"), headers)
    }

    @Test
    fun resolveHeaders_blankKey_doesNotInject() {
        assertEquals(emptyMap<String, String>(), manager.resolveHeaders(emptyMap(), "  "))
    }

    @Test
    fun resolveHeaders_customAuthHeader_preservedNotOverridden() {
        val custom = mapOf("Authorization" to "CustomToken")
        val headers = manager.resolveHeaders(custom, "sk-abc")
        assertEquals("CustomToken", headers["Authorization"])
    }

    @Test
    fun resolveHeaders_lowercaseAuthHeader_preservedNotOverridden() {
        // 大小写规范化：用户配置小写 authorization 时不应重复注入 Bearer（CR-06 对齐）
        val custom = mapOf("authorization" to "LowerToken")
        val headers = manager.resolveHeaders(custom, "sk-abc")
        assertEquals("LowerToken", headers["authorization"])
        assertFalse("不应注入重复 Bearer 头", headers.values.contains("Bearer sk-abc"))
    }

    @Test
    fun resolveHeaders_customHeaders_preserved() {
        val custom = mapOf("X-API-Key" to "custom", "CONTEXT7_API_KEY_HEADER" to "CONTEXT7_API_KEY")
        val headers = manager.resolveHeaders(custom, "sk-abc")
        assertEquals("custom", headers["X-API-Key"])
        assertEquals("CONTEXT7_API_KEY", headers["CONTEXT7_API_KEY_HEADER"])
        assertEquals("Bearer sk-abc", headers["Authorization"])
    }

    @Test
    fun resolveHeaders_crlfValues_stripped() {
        // 纵深防御（guardrail M1 / CWE-113、CWE-93）：键值含 CR/LF 应被剔除，防止 HTTP 首部注入
        val custom = mapOf("X-Inject" to "legit\r\nX-Evil: 1", "X-Clean" to "value")
        val headers = manager.resolveHeaders(custom, "sk-abc")
        assertFalse("含 CRLF 的键值应被剔除", headers.containsKey("X-Inject"))
        assertEquals("合法键值保留", "value", headers["X-Clean"])
        assertEquals("Bearer sk-abc", headers["Authorization"])
    }

    @Test
    fun resolveHeaders_crlfKeys_stripped() {
        val custom = mapOf("X-Bad\r\nX-Evil" to "v", "X-Good" to "ok")
        val headers = manager.resolveHeaders(custom, null)
        assertFalse("含 CRLF 的键名应被剔除", headers.containsKey("X-Bad\r\nX-Evil"))
        assertEquals("合法键名保留", "ok", headers["X-Good"])
    }

    // ==================== isValidBaseUrl：连接层 URL 白名单校验 ====================

    @Test
    fun isValidBaseUrl_validHttps_returnsTrue() {
        assertTrue("合法 https URL 应通过", manager.isValidBaseUrl("https://mcp.context7.com/mcp"))
    }

    @Test
    fun isValidBaseUrl_validHttp_returnsTrue() {
        assertTrue("合法 http URL 应通过", manager.isValidBaseUrl("http://localhost:8080/mcp"))
    }

    @Test
    fun isValidBaseUrl_blank_returnsFalse() {
        assertFalse("空 baseUrl 应拒绝", manager.isValidBaseUrl(""))
        assertFalse("空白 baseUrl 应拒绝", manager.isValidBaseUrl("  "))
    }

    @Test
    fun isValidBaseUrl_missingScheme_returnsFalse() {
        assertFalse("无 http(s) 前缀应拒绝", manager.isValidBaseUrl("mcp.context7.com/mcp"))
    }

    @Test
    fun isValidBaseUrl_crlf_returnsFalse() {
        assertFalse("含 CRLF 应拒绝（CWE-113）", manager.isValidBaseUrl("https://mcp.context7.com\r\nX-Evil: 1"))
    }

    @Test
    fun isValidBaseUrl_trailingCrlf_returnsFalse() {
        // guardrail R3-1：尾部 CRLF 不得被 trim() 剥离后绕过校验（校验 CRLF 于 trim 前）
        assertFalse("尾部 CRLF 应拒绝", manager.isValidBaseUrl("https://mcp.context7.com\r\n"))
    }

    @Test
    fun isValidBaseUrl_whitespaceSurrounded_returnsTrue() {
        // 空白包围的合法 URL 应通过（trim 后前缀合法且无 CRLF）
        assertTrue("空白包围的合法 URL 应通过", manager.isValidBaseUrl("  https://mcp.context7.com  "))
    }

    // ==================== renderResult：结果文本渲染 ====================

    @Test
    fun renderResult_textContents_joinedByNewline() {
        val content: List<ContentBlock> = listOf(TextContent("line1"), TextContent("line2"))
        assertEquals("line1\nline2", manager.renderResult(content, false))
    }

    @Test
    fun renderResult_emptyContent_returnsEmpty() {
        assertEquals("", manager.renderResult(emptyList(), false))
    }

    @Test
    fun renderResult_blankText_filtered() {
        val content: List<ContentBlock> = listOf(TextContent("  "), TextContent("value"))
        assertEquals("value", manager.renderResult(content, false))
    }

    @Test
    fun renderResult_isError_prefixed() {
        val content: List<ContentBlock> = listOf(TextContent("failed"))
        assertEquals("工具执行出错：failed", manager.renderResult(content, true))
    }

    @After
    fun tearDown() {
        // HttpClient 无显式资源需关闭（MockEngine 无 I/O）
    }
}