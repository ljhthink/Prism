package io.prism.network

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * McpToolProviderDispatcher 路由单元测试（ADR-006 5.6）。
 *
 * 验证按 [McpServerConfig.serverType] 将 listTools / callTool 分发给本地或远程实现。
 * 使用可记录的 fake [McpToolProvider] 断言各自被调用且透传参数。
 */
class McpToolProviderDispatcherTest {

    /** 可记录调用的 [McpToolProvider] fake。 */
    private class RecordingProvider(val tag: String) : McpToolProvider {
        var lastConfig: McpServerConfig? = null
        var lastToolName: String? = null
        var lastArguments: Map<String, Any?>? = null
        var toolsToReturn: List<String> = emptyList()
        var callToReturn: String = ""

        override suspend fun listTools(config: McpServerConfig): List<String> {
            lastConfig = config
            return toolsToReturn
        }

        override suspend fun callTool(
            config: McpServerConfig,
            name: String,
            arguments: Map<String, Any?>
        ): String {
            lastConfig = config
            lastToolName = name
            lastArguments = arguments
            return callToReturn
        }
    }

    private fun localConfig(): McpServerConfig =
        McpServerConfig(name = "Local", serverType = McpServerType.LOCAL, baseUrl = "")

    private fun remoteConfig(): McpServerConfig =
        McpServerConfig(name = "Remote", serverType = McpServerType.REMOTE, baseUrl = "https://mcp.example.com/mcp")

    @Test
    fun `listTools local config routes to local provider`() = runBlocking {
        val local = RecordingProvider("local").apply { toolsToReturn = listOf("read_file") }
        val remote = RecordingProvider("remote")
        val dispatcher = McpToolProviderDispatcher(local, remote)
        val cfg = localConfig()

        val tools = dispatcher.listTools(cfg)

        assertEquals(listOf("read_file"), tools)
        assertEquals(cfg, local.lastConfig)
        assertEquals(null, remote.lastConfig)
    }

    @Test
    fun `listTools remote config routes to remote provider`() = runBlocking {
        val local = RecordingProvider("local")
        val remote = RecordingProvider("remote").apply { toolsToReturn = listOf("echo") }
        val dispatcher = McpToolProviderDispatcher(local, remote)
        val cfg = remoteConfig()

        val tools = dispatcher.listTools(cfg)

        assertEquals(listOf("echo"), tools)
        assertEquals(cfg, remote.lastConfig)
        assertEquals(null, local.lastConfig)
    }

    @Test
    fun `callTool local config routes to local provider with args passthrough`() = runBlocking {
        val local = RecordingProvider("local").apply { callToReturn = "hello world" }
        val remote = RecordingProvider("remote")
        val dispatcher = McpToolProviderDispatcher(local, remote)
        val cfg = localConfig()
        val args = mapOf("path" to "notes/readme.md")

        val result = dispatcher.callTool(cfg, "read_file", args)

        assertEquals("hello world", result)
        assertEquals("read_file", local.lastToolName)
        assertEquals(args, local.lastArguments)
        assertEquals(null, remote.lastToolName)
    }

    @Test
    fun `callTool remote config routes to remote provider`() = runBlocking {
        val local = RecordingProvider("local")
        val remote = RecordingProvider("remote").apply { callToReturn = "pong" }
        val dispatcher = McpToolProviderDispatcher(local, remote)
        val cfg = remoteConfig()

        val result = dispatcher.callTool(cfg, "echo", emptyMap())

        assertEquals("pong", result)
        assertEquals("echo", remote.lastToolName)
        assertEquals(null, local.lastToolName)
    }
}