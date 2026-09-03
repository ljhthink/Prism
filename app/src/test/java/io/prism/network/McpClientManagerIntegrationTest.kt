package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.prism.data.McpServerConfig
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * McpClientManager 真实 MCP Server 集成测试（ADR-005 5.6 / GAP-001 覆盖）。
 *
 * **背景**：MCP 的 Streamable HTTP 传输涉及真实 JSON-RPC 握手（initialize → tools/list → tools/call），
 * `MockEngine` 无法完整模拟 MCP 协议状态机。故起嵌入式 Ktor Netty 服务器，通过 MCP Kotlin SDK
 * 的 [mcpStreamableHttp] 扩展托管真实 Streamable HTTP MCP Server，再用 [McpClientManager] 端到端
 * 验证 [McpToolProvider.listTools] 与 [McpToolProvider.callTool]。
 *
 * **覆盖**：
 * - listTools：真实握手返回注册工具名列表
 * - callTool：真实工具调用返回文本结果
 * - 非法 / 空白 baseUrl：连接层校验拒绝（不发起网络请求，降级为空列表 / 通用文案）
 *
 * 此测试补齐纯函数单测无法覆盖的 MCP 协议握手事务路径。
 */
class McpClientManagerIntegrationTest {

    // 与生产 PrismApplication.httpClient 逐项对齐（guardrail Q2）：OkHttp engine + SSE 插件 + expectSuccess=true
    //（非 2xx 抛 ClientRequestException，与生产一致；MCP 传输层仍负责协议级状态校验）
    private val httpClient = HttpClient(OkHttp) { install(SSE); expectSuccess = true }
    private val apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
    private val manager = McpClientManager(httpClient, apiKeyRepository)

    // 持有已启动 server 的清理回调，测试结束统一关闭（Ktor 3.x 的 EmbeddedServer 为 internal，
    // 无法显式声明类型，故用 suspend 清理 lambda 规避）
    private val teardowns = mutableListOf<suspend () -> Unit>()

    /** 启动嵌入式 Streamable HTTP MCP Server，注册两个工具（echo / ping），返回端口。
     *  teardown 于 server.start 后立即登记，即使后续端口解析失败也会被 stopServers() 清理（guardrail Q1）。 */
    private fun startMcpServer(): Int {
        val server = embeddedServer(Netty, port = 0) {
            // 测试仅回环地址，无需 DNS 重绑定防护；生产环境由远程部署侧启用（guardrail Q5）
            mcpStreamableHttp(enableDnsRebindingProtection = false) {
                Server(
                    serverInfo = Implementation("prism-test-server", "1.0.0"),
                    options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = true)
                        )
                    )
                ) {
                    addTool(
                        name = "echo",
                        description = "Echoes the input text",
                        inputSchema = ToolSchema(
                            schema = "object",
                            properties = buildJsonObject {
                                put("text", buildJsonObject { put("type", "string") })
                            },
                            required = emptyList<String>()
                        )
                    ) { request ->
                        // arguments.get("text") 返回 JsonPrimitive，.toString() 会带引号，需取 .content
                        val text = (request.params.arguments?.get("text") as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content ?: ""
                        CallToolResult(content = listOf(TextContent("echo:$text")))
                    }
                    addTool(name = "ping", description = "Returns pong") {
                        CallToolResult(content = listOf(TextContent("pong")))
                    }
                }
            }
        }
        server.start(wait = false)
        teardowns.add { server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) }
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /** 构造测试配置：MCP Streamable HTTP 端点挂载在 `/mcp`，baseUrl 需含该路径（KtorServer.kt path 默认 "/mcp"）。 */
    private fun config(port: Int): McpServerConfig =
        McpServerConfig(name = "Test", baseUrl = "http://127.0.0.1:$port/mcp")

    private suspend fun stopServers() {
        // 逐项容错：单个 server 停止失败不阻断其余清理（guardrail Q3，对齐生产 closeQuietly 哲学）
        teardowns.forEach { runCatching { it() } }
        teardowns.clear()
    }

    @Test
    fun `listTools returns registered tool names via real handshake`() = runBlocking {
        val port = startMcpServer()
        try {
            val tools = withTimeout(10.seconds) { manager.listTools(config(port)) }
            assertEquals("应返回注册的两个工具名", listOf("echo", "ping"), tools)
        } finally {
            stopServers()
        }
    }

    @Test
    fun `callTool invokes real tool and returns text result`() = runBlocking {
        val port = startMcpServer()
        try {
            val result = withTimeout(10.seconds) {
                manager.callTool(config(port), "echo", mapOf("text" to "hello"))
            }
            assertEquals("工具应返回拼接结果", "echo:hello", result)
        } finally {
            stopServers()
        }
    }

    @Test
    fun `callTool with unknown tool degrades to generic message`() = runBlocking {
        val port = startMcpServer()
        try {
            val result = withTimeout(10.seconds) {
                manager.callTool(config(port), "no_such_tool", emptyMap())
            }
            // 服务器对未知工具返回 CallToolResult(isError=true)，renderResult 前置「工具执行出错」标记。
            // 这是工具级错误的优雅降级（非连接异常），且不泄露内部堆栈/异常细节。
            assertTrue("未知工具应优雅降级为工具执行错误文案", result.startsWith("工具执行出错"))
        } finally {
            stopServers()
        }
    }

    @Test
    fun `listTools with invalid baseUrl rejects before network call`() = runBlocking {
        val bad = McpServerConfig(name = "Bad", baseUrl = "not-a-url")
        val tools = withTimeout(10.seconds) { manager.listTools(bad) }
        assertEquals("非法 baseUrl 应被连接层校验拒绝并降级为空列表", emptyList<String>(), tools)
    }

    @Test
    fun `callTool with blank baseUrl rejects before network call`() = runBlocking {
        val bad = McpServerConfig(name = "Blank", baseUrl = "  ")
        val result = withTimeout(10.seconds) { manager.callTool(bad, "echo", emptyMap()) }
        assertTrue("空白 baseUrl 应被拒绝并降级为通用文案", result.startsWith("工具调用失败"))
        // 确保降级文案不含内部校验异常细节（CWE-209）
        assertTrue("不得泄露 IllegalArgumentException 细节", !result.contains("require"))
    }

    // ==================== PRD MCP/API 增强（US-001）：Bocha 握手 ====================

    /** 启动注册 bocha_web_search / bocha_ai_search 工具的嵌入式 MCP Server（模拟博查远程端点）。 */
    private fun startBochaMcpServer(): Int {
        val server = embeddedServer(Netty, port = 0) {
            mcpStreamableHttp(enableDnsRebindingProtection = false) {
                Server(
                    serverInfo = Implementation("bocha-test-server", "1.0.0"),
                    options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = true)
                        )
                    )
                ) {
                    addTool(
                        name = "bocha_web_search",
                        description = "从博查搜索全网信息和网页链接",
                        inputSchema = ToolSchema(
                            schema = "object",
                            properties = buildJsonObject {
                                put("query", buildJsonObject { put("type", "string") })
                                put("count", buildJsonObject { put("type", "integer") })
                            },
                            required = listOf("query")
                        )
                    ) { request ->
                        CallToolResult(content = listOf(TextContent("web search result")))
                    }
                    addTool(name = "bocha_ai_search", description = "语义增强搜索与结构化模态卡") {
                        CallToolResult(content = listOf(TextContent("ai search result")))
                    }
                }
            }
        }
        server.start(wait = false)
        teardowns.add { server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) }
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun `listTools returns bocha tools via streamable http handshake`() = runBlocking {
        // PRD US-001 AC-3：Bocha 模板（mcp.bocha.cn/mcp，Streamable HTTP + Bearer）应能
        // 握手并 tools/list 出 bocha_web_search / bocha_ai_search。此处用嵌入式 Streamable HTTP
        // MCP Server 模拟博查端点（真实握手，MockEngine 无法模拟 MCP 状态机）。
        val port = startBochaMcpServer()
        try {
            // 复用 Bocha 预设（mcp.bocha.cn/mcp），端口替换为本地测试 server，验证协议路径
            val preset = io.prism.data.McpServerPresets.findByName("Bocha")!!
            val bochaConfig = preset.copy(baseUrl = "http://127.0.0.1:$port/mcp")
            assertEquals("Bocha 预设应使用 Streamable HTTP", io.prism.data.McpTransport.STREAMABLE_HTTP, bochaConfig.transport)
            val tools = withTimeout(10.seconds) { manager.listTools(bochaConfig) }
            assertEquals("应列出 bocha 两个搜索工具", listOf("bocha_web_search", "bocha_ai_search"), tools)
        } finally {
            stopServers()
        }
    }

    @Test
    fun `callTool bocha web search returns text result`() = runBlocking {
        // PRD US-001 AC-3：Bocha 工具调用返回文本结果（真实握手 + tools/call）
        val port = startBochaMcpServer()
        try {
            val preset = io.prism.data.McpServerPresets.findByName("Bocha")!!
            val bochaConfig = preset.copy(baseUrl = "http://127.0.0.1:$port/mcp")
            val result = withTimeout(10.seconds) {
                manager.callTool(bochaConfig, "bocha_web_search", mapOf("query" to "梧州一中"))
            }
            assertEquals("应返回博查搜索工具结果", "web search result", result)
        } finally {
            stopServers()
        }
    }
}