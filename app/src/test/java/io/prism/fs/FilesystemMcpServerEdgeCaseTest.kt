package io.prism.fs

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.network.LocalMcpToolProvider
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * FilesystemMcpServer 极端/边界/恶意输入/安全补充测试（ac-verifier 补充，US-009）。
 *
 * 在既有 11 个基础用例之上，覆盖主 Agent 与 guardrail 自问的盲区：
 * - 恶意输入：路径穿越段（`..`/`.`/空段）、绝对路径、参数类型错误（非字符串）
 * - CWE-209：FileSystemAccess 抛带内部细节异常时工具结果不得泄露内部细节
 * - 边界值：search_files.limit（0/负数/超大/非整数）、空 content、未知工具名
 * - 状态迁移：同一 Server 多次调用状态保持（write → read round-trip）
 * - 隔离性：read_multiple_files 单个失败不影响其他
 */
class FilesystemMcpServerEdgeCaseTest {

    /** 组装 测试 Server + 内存文件系统 + 本地工具提供者；门禁默认放行。 */
    private fun build(
        allow: Boolean = true,
        fs: FileSystemAccess = InMemoryFileAccess()
            .addDirectory("notes")
            .addFile("notes/readme.md", "hello world")
            .addFile("notes/todo.txt", "buy milk")
    ): Triple<FilesystemMcpServer, FileSystemAccess, LocalMcpToolProvider> {
        val gate = ToolConfirmationGate { _, _ -> allow }
        val server = FilesystemMcpServer(fs, gate)
        return Triple(server, fs, LocalMcpToolProvider(server))
    }

    private val localConfig = McpServerConfig(
        name = "Filesystem",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    // ---------- 恶意输入 / 路径穿越（S2 纵深防御 + 越权隔离） ----------

    @Test
    fun `callTool read_file with parent traversal segment is rejected`() = runBlocking {
        val (_, _, provider) = build()
        // InMemory fake 无 `..` 节点，resolve 失败 → IOException → 通用错误，不泄露
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/../secret"))
        }
        assertTrue("穿越段应返回错误结果", result.contains("执行出错"))
        assertFalse("不得泄露异常细节", result.contains("Exception"))
        assertFalse("不得泄露原始路径", result.contains("secret"))
    }

    @Test
    fun `callTool read_file with absolute path is rejected`() = runBlocking {
        val (_, _, provider) = build()
        // 首段 `etc` 非授权根 → resolve 失败 → 错误
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "/etc/passwd"))
        }
        assertTrue("绝对路径应返回错误结果", result.contains("执行出错"))
    }

    @Test
    fun `callTool read_file with dot segment is rejected`() = runBlocking {
        val (_, _, provider) = build()
        // InMemory fake 无 `.` 节点，resolve 失败 → 错误（与 Saf isSafeSegment 均 fail-closed）
        val dot = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/./readme.md"))
        }
        assertTrue("`.` 段路径应返回错误", dot.contains("执行出错"))
    }

    @Test
    fun `callTool read_file with doubled slash does not crash`() = runBlocking {
        val (_, _, provider) = build()
        // 注：InMemory fake 会过滤空段返回成功「hello world」；Saf isSafeSegment 拒绝空段返回错误。
        // 两实现语义不一致（见报告未覆盖项），此处仅断言不崩溃、不泄露内部细节。
        val empty = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes//readme.md"))
        }
        assertFalse("空段路径不得泄露内部异常", empty.contains("Exception"))
        assertFalse("空段路径不得泄露原始路径", empty.contains("IOException"))
    }

    @Test
    fun `callTool write_file with dot traversal does not crash`() = runBlocking {
        val (_, _, provider) = build()
        // 注：InMemory fake 将 `..` 视为普通段名可成功创建；Saf isSafeSegment 拒绝 `..` 返回失败。
        // 两实现语义不一致（见报告未覆盖项），此处仅断言不崩溃、不泄露。
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "write_file",
                mapOf("path" to "notes/../evil.txt", "content" to "x")
            )
        }
        assertFalse("穿越段写入不得泄露内部异常", result.contains("Exception"))
    }

    @Test
    fun `callTool read_file on unauthorized root is isolated`() = runBlocking {
        val (_, _, provider) = build()
        // 未授权根 `secret` 不在根注册表 → resolve 失败 → 错误（越权隔离真实验证）
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "secret/innermost.txt"))
        }
        assertTrue("未授权根应被隔离为错误", result.contains("执行出错"))
        assertFalse("不得泄露内部细节", result.contains("Exception"))
    }

    // ---------- 参数类型错误 / 缺参 ----------

    @Test
    fun `callTool read_file with numeric path param does not crash`() = runBlocking {
        val (_, _, provider) = build()
        // arg() 将 JsonPrimitive 数字的 content 提取为 "123" 作为路径 → 路径不存在 → 执行出错。
        // 不区分字面量类型是既有设计（缺省拒绝，方向安全），此处断言不崩溃且返回错误。
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to 123))
        }
        assertTrue("数字 path 应返回错误结果而非崩溃", result.contains("执行出错"))
        assertFalse("不得泄露内部细节", result.contains("Exception"))
    }

    @Test
    fun `callTool write_file with missing content degrades to missing param`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "write_file", mapOf("path" to "notes/a.txt"))
        }
        assertTrue("缺 content 应降级为缺参错误", result.contains("缺少参数"))
    }

    @Test
    fun `callTool unknown tool name returns error without crash`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "nonexistent_tool", emptyMap())
        }
        // 未知工具经 MCP SDK 返回错误，本地 provider 降级为通用错误文案，不崩溃
        assertTrue("未知工具不应崩溃，应有错误文案", result.isNotBlank())
    }

    // ---------- 边界值：search_files.limit ----------

    @Test
    fun `callTool search_files limit zero still returns match`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "search_files",
                mapOf("path" to "notes", "query" to "readme", "limit" to 0)
            )
        }
        // InMemory 实现 coerceAtLeast(1)，limit=0 应至少返回 1 个匹配
        assertTrue("limit=0 应返回至少一个匹配", result.contains("readme.md"))
    }

    @Test
    fun `callTool search_files negative limit does not crash`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "search_files",
                mapOf("path" to "notes", "query" to "readme", "limit" to -5)
            )
        }
        assertTrue("负 limit 不应崩溃，应返回匹配", result.contains("readme.md"))
    }

    @Test
    fun `callTool search_files huge limit does not crash`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "search_files",
                mapOf("path" to "notes", "query" to "readme", "limit" to Int.MAX_VALUE)
            )
        }
        assertTrue("超大 limit 不应崩溃", result.contains("readme.md"))
    }

    @Test
    fun `callTool search_files noninteger limit falls back to default`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "search_files",
                mapOf("path" to "notes", "query" to "readme", "limit" to "abc")
            )
        }
        assertTrue("非整数 limit 应回退默认并返回匹配", result.contains("readme.md"))
    }

    // ---------- CWE-209：执行期异常不得泄露内部细节 ----------

    @Test
    fun `callTool does not leak internal exception details`() = runBlocking {
        // 构造一个 readFile 抛含内部路径/异常类型细节的实现的 FileSystemAccess
        val leakyFs = object : FileSystemAccess {
            override suspend fun listAllowedDirectories() = emptyList<String>()
            // 模拟「已授权但读取失败」：hasAuthorizedRoots 返回 true，使 read_file 走执行分支
            override suspend fun hasAuthorizedRoots() = true
            override suspend fun readFile(path: String): String =
                throw IOException("内部路径 /data/secret/db 访问失败: PermissionDenied")
            override suspend fun readMultipleFiles(paths: List<String>) = emptyMap<String, String>()
            override suspend fun listDirectory(path: String) = emptyList<FileEntry>()
            override suspend fun directoryTree(path: String) = emptyList<FileEntry>()
            override suspend fun searchFiles(path: String, query: String, limit: Int) = emptyList<String>()
            override suspend fun getFileInfo(path: String): FileEntry =
                FileEntry("", "", false)
            override suspend fun writeFile(path: String, content: String) = true
        }
        val (_, _, provider) = build(fs = leakyFs)
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }
        assertTrue("应返回通用错误", result.contains("执行出错"))
        assertFalse("不得泄露内部路径", result.contains("/data/secret"))
        assertFalse("不得泄露异常类型", result.contains("PermissionDenied"))
        assertFalse("不得泄露异常类别名", result.contains("IOException"))
    }

    @Test
    fun `callTool write_file on read-only access returns failure not exception`() = runBlocking {
        val readOnlyFs = object : FileSystemAccess {
            override suspend fun listAllowedDirectories() = listOf("notes")
            override suspend fun hasAuthorizedRoots() = true
            override suspend fun readFile(path: String) = "data"
            override suspend fun readMultipleFiles(paths: List<String>) = emptyMap<String, String>()
            override suspend fun listDirectory(path: String) = emptyList<FileEntry>()
            override suspend fun directoryTree(path: String) = emptyList<FileEntry>()
            override suspend fun searchFiles(path: String, query: String, limit: Int) = emptyList<String>()
            override suspend fun getFileInfo(path: String): FileEntry = FileEntry("", "", false)
            override suspend fun writeFile(path: String, content: String) = false
        }
        val (_, _, provider) = build(fs = readOnlyFs)
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "write_file", mapOf("path" to "notes/a.txt", "content" to "x"))
        }
        assertTrue("写失败应返回失败文案", result.contains("写入失败"))
    }

    // ---------- 状态迁移：同一 Server 多次调用状态保持 ----------

    @Test
    fun `callTool write then read round-trip persists across calls`() = runBlocking {
        val (_, _, provider) = build()
        val write = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "write_file",
                mapOf("path" to "notes/roundtrip.txt", "content" to "persisted")
            )
        }
        assertEquals("写入成功", write)

        val read = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/roundtrip.txt"))
        }
        assertEquals("同 Server 二次调用应读到写入内容", "persisted", read)

        val list = withTimeout(10.seconds) {
            provider.callTool(localConfig, "list_directory", mapOf("path" to "notes"))
        }
        assertTrue("新文件应出现在目录列表", list.contains("roundtrip.txt"))
    }

    // ---------- 隔离性：read_multiple_files 单个失败不影响其他 ----------

    @Test
    fun `callTool read_multiple_files isolates missing file`() = runBlocking {
        val (_, _, provider) = build()
        val result = withTimeout(10.seconds) {
            provider.callTool(
                localConfig,
                "read_multiple_files",
                mapOf("paths" to listOf("notes/readme.md", "notes/does_not_exist.txt"))
            )
        }
        assertTrue("存在的文件应正常返回", result.contains("hello world"))
        assertFalse("失败文件不得泄露内部异常", result.contains("Exception"))
    }

    // ---------- 空内容 / 空文件 ----------

    @Test
    fun `callTool write then read empty content round-trips`() = runBlocking {
        val (_, _, provider) = build()
        val write = withTimeout(10.seconds) {
            provider.callTool(localConfig, "write_file", mapOf("path" to "notes/empty.txt", "content" to ""))
        }
        assertEquals("写入成功", write)

        val read = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/empty.txt"))
        }
        // 空内容经 renderResult 过滤空白 → 空串
        assertEquals("空内容应可读回", "", read)
    }

    // ==================== UXR3 问题 8（ADR-023）：未授权根目录引导文案（guardrail T-6 补齐） ====================

    @Test
    fun `callTool read_file returns guide message when no roots authorized`() = runBlocking {
        // 空文件系统（无授权根目录）→ 目录/文件工具返回明确引导文案，而非泛化「工具执行出错」
        val (_, _, provider) = build(fs = InMemoryFileAccess())
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }
        assertTrue("未授权时应返回引导文案", result.contains("未授权任何目录"))
        assertTrue("引导文案应指向能力页", result.contains("能力页"))
        assertFalse("不应是泛化执行出错", result.contains("工具执行出错") && !result.contains("未授权"))
    }

    @Test
    fun `callTool list_directory returns guide message when no roots authorized`() = runBlocking {
        val (_, _, provider) = build(fs = InMemoryFileAccess())
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "list_directory", mapOf("path" to "notes"))
        }
        assertTrue("未授权时 list_directory 应返回引导文案", result.contains("未授权任何目录"))
    }

    @Test
    fun `callTool directory_tree returns guide message when no roots authorized`() = runBlocking {
        val (_, _, provider) = build(fs = InMemoryFileAccess())
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "directory_tree", mapOf("path" to "notes"))
        }
        assertTrue("未授权时 directory_tree 应返回引导文案", result.contains("未授权任何目录"))
    }

    @Test
    fun `callTool list_allowed_directories works without authorized roots`() = runBlocking {
        // list_allowed_directories 是引导工具，未授权时也应正常返回（空列表），不触发引导文案
        val (_, _, provider) = build(fs = InMemoryFileAccess())
        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "list_allowed_directories", emptyMap())
        }
        // 空文件系统 → 空列表；不报「未授权」引导（该工具用于引导用户授权）
        assertFalse("list_allowed_directories 不应返回引导文案", result.contains("未授权任何目录"))
    }

    // ==================== UXR3 问题 10（ADR-023）：FilesystemMcpServer 审批模式（guardrail N-1 补齐） ====================

    @Test
    fun `callTool read_file in AUTO mode skips confirmation gate`() = runBlocking {
        var confirmCount = 0
        val gate = ToolConfirmationGate { _, _ -> confirmCount++; true }
        val server = FilesystemMcpServer(
            fileSystemAccess = InMemoryFileAccess().addDirectory("notes").addFile("notes/readme.md", "hello"),
            confirmationGate = gate,
            approvalModeProvider = { io.prism.config.ToolApprovalMode.AUTO }
        )
        val provider = LocalMcpToolProvider(server)

        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }

        assertEquals("AUTO 模式应直接返回内容", "hello", result)
        assertEquals("AUTO 模式不应调用确认门禁", 0, confirmCount)
    }

    @Test
    fun `callTool read_file in DISABLED mode rejects without confirmation or execution`() = runBlocking {
        var confirmCount = 0
        var readCalled = false
        val fs = object : FileSystemAccess {
            override suspend fun listAllowedDirectories() = listOf("notes")
            override suspend fun hasAuthorizedRoots() = true
            override suspend fun readFile(path: String): String { readCalled = true; return "data" }
            override suspend fun readMultipleFiles(paths: List<String>) = emptyMap<String, String>()
            override suspend fun listDirectory(path: String) = emptyList<FileEntry>()
            override suspend fun directoryTree(path: String) = emptyList<FileEntry>()
            override suspend fun searchFiles(path: String, query: String, limit: Int) = emptyList<String>()
            override suspend fun getFileInfo(path: String): FileEntry = FileEntry("", "", false)
            override suspend fun writeFile(path: String, content: String) = true
        }
        val gate = ToolConfirmationGate { _, _ -> confirmCount++; true }
        val server = FilesystemMcpServer(
            fileSystemAccess = fs,
            confirmationGate = gate,
            approvalModeProvider = { io.prism.config.ToolApprovalMode.DISABLED }
        )
        val provider = LocalMcpToolProvider(server)

        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }

        assertTrue("DISABLED 模式应返回禁用文案", result.contains("已禁用"))
        assertEquals("DISABLED 模式不应调用确认门禁", 0, confirmCount)
        assertFalse("DISABLED 模式不应执行文件读取", readCalled)
    }

    @Test
    fun `callTool read_file in MANUAL mode asks confirmation gate`() = runBlocking {
        var confirmCount = 0
        val gate = ToolConfirmationGate { _, _ -> confirmCount++; true }
        val server = FilesystemMcpServer(
            fileSystemAccess = InMemoryFileAccess().addDirectory("notes").addFile("notes/readme.md", "hello"),
            confirmationGate = gate,
            approvalModeProvider = { io.prism.config.ToolApprovalMode.MANUAL }
        )
        val provider = LocalMcpToolProvider(server)

        val result = withTimeout(10.seconds) {
            provider.callTool(localConfig, "read_file", mapOf("path" to "notes/readme.md"))
        }

        assertEquals("MANUAL 模式应返回内容", "hello", result)
        assertEquals("MANUAL 模式应调用确认门禁", 1, confirmCount)
    }
}