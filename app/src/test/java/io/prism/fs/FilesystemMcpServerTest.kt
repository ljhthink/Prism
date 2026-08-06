package io.prism.fs

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.network.LocalMcpToolProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * FilesystemMcpServer 端到端集成测试（ADR-006 5.5）。
 *
 * 经 [LocalMcpToolProvider] 走完整 MCP 握手（initialize → tools/list → tools/call），复用
 * [InProcessTransport] 进程内桥接，验证 8 个文件工具注册与调用、确认门禁拒绝路径、参数缺失降级。
 *
 * **数据层**：使用 [InMemoryFileAccess] 内存 fake（SAF 依赖 Android 无法在 JVM 单测实例化）。
 *
 * **确认门禁**：[ToolConfirmationGate] 为 fun interface，测试直接以 lambda 提供放行/拒绝策略。
 */
class FilesystemMcpServerTest {

    /** 组装 测试 Server + 内存文件系统 + 本地工具提供者。 */
    private fun build(
        allow: Boolean = true
    ): Triple<FilesystemMcpServer, InMemoryFileAccess, LocalMcpToolProvider> {
        val fs = InMemoryFileAccess()
            .addDirectory("notes")
            .addFile("notes/readme.md", "hello world")
            .addFile("notes/todo.txt", "buy milk")
            .addDirectory("docs")
            .addFile("docs/guide.md", "# Guide")
        val gate = ToolConfirmationGate { _, _ -> allow }
        val server = FilesystemMcpServer(fs, gate)
        return Triple(server, fs, LocalMcpToolProvider(server))
    }

    private val localConfig = McpServerConfig(
        name = "Filesystem",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    @Test
    fun `listTools returns all 8 registered file tools`() = runBlocking {
        val (_, _, provider) = build()
        val tools = withTimeout(10.seconds) { provider.listTools(localConfig) }
        assertEquals(
            "应注册全部 8 个文件工具",
            listOf(
                "read_file",
                "read_multiple_files",
                "list_directory",
                "directory_tree",
                "search_files",
                "get_file_info",
                "list_allowed_directories",
                "write_file"
            ).sorted(),
            tools.sorted()
        )
    }

    @Test
    fun `callTool read_file returns file content`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }
        assertEquals("hello world", result)
    }

    @Test
    fun `callTool read_file missing path degrades to error`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", emptyMap())
        }
        assertTrue("缺失参数应返回错误文案", result.contains("缺少参数"))
        assertTrue("错误文案不得泄露内部细节", !result.contains("Exception"))
    }

    @Test
    fun `callTool read_file rejected by gate returns error`() = runBlocking {
        val (_, _, provider) = build(allow = false)
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }
        assertTrue("门禁拒绝应返回拒绝文案", result.contains("拒绝"))
    }

    @Test
    fun `callTool list_directory returns direct children`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "list_directory", mapOf("path" to "notes"))
        }
        assertTrue(result.contains("readme.md"))
        assertTrue(result.contains("todo.txt"))
    }

    @Test
    fun `callTool directory_tree returns nested entries`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "directory_tree", mapOf("path" to "notes"))
        }
        assertTrue(result.contains("readme.md"))
        assertTrue(result.contains("todo.txt"))
    }

    @Test
    fun `callTool search_files matches file name`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "search_files", mapOf("path" to "notes", "query" to "readme"))
        }
        assertTrue("应匹配 readme.md 文件名", result.contains("readme.md"))
    }

    @Test
    fun `callTool get_file_info returns entry metadata`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "get_file_info", mapOf("path" to "notes/readme.md"))
        }
        assertTrue(result.contains("readme.md"))
        assertTrue(result.contains("kind=file"))
    }

    @Test
    fun `callTool list_allowed_directories returns authorized roots`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "list_allowed_directories", emptyMap())
        }
        assertTrue(result.contains("notes"))
        assertTrue(result.contains("docs"))
    }

    @Test
    fun `callTool write_file persists content in shared file system`() = runBlocking {
        val (_, fs, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "write_file",
                mapOf("path" to "notes/new.txt", "content" to "data")
            )
        }
        assertEquals("写入成功", result)
        // FilesystemMcpServer 共享同一 InMemoryFileAccess，写入应持久化。
        assertEquals("data", fs.readFile("notes/new.txt"))
    }

    @Test
    fun `callTool read_multiple_files returns mapped contents`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "read_multiple_files",
                mapOf("paths" to listOf("notes/readme.md", "notes/todo.txt"))
            )
        }
        assertTrue(result.contains("hello world"))
        assertTrue(result.contains("buy milk"))
    }
}