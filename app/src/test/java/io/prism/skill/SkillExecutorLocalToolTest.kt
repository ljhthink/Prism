package io.prism.skill

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.fs.ToolConfirmationGate
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillExecutor M6 Phase B 本地工具分支测试（ADR-016）。
 *
 * 验证 [SkillExecutor] 新增的 [LocalToolExecutor] 分支：
 * - localToolExecutor 为 null 时行为不变（仅走 MCP 路径，向后兼容）
 * - localToolExecutor 非空且 handles 返回 true 时走本地路径
 * - localToolExecutor 非空但 handles 返回 false 时走 MCP 路径
 * - 本地工具超时/异常降级文案
 * - 用户确认仍对本地工具生效
 */
class SkillExecutorLocalToolTest {

    /** Fake LocalToolExecutor，可配置 handles 和 execute 返回值。 */
    private class FakeLocalToolExecutor(
        private val handledTools: Set<String>,
        private val result: String = "本地工具执行成功",
        private val delayMs: Long = 0,
        private val throwException: Exception? = null
    ) : LocalToolExecutor {
        var executeCalls = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun handles(toolName: String): Boolean = toolName in handledTools

        override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
            executeCalls.add(toolName to arguments)
            if (delayMs > 0) delay(delayMs)
            throwException?.let { throw it }
            return result
        }
    }

    /** 始终批准的确认门禁。 */
    private val approveGate = ToolConfirmationGate { _, _ -> true }

    /** Fake MCP Provider，用于验证 MCP 路径未被调用。 */
    private class FakeMcpProvider : McpToolProvider {
        var callToolCalls = 0

        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()

        override suspend fun callTool(
            config: McpServerConfig,
            name: String,
            arguments: Map<String, Any?>
        ): String {
            callToolCalls++
            return "MCP 工具结果"
        }
    }

    private val fakeMcp = FakeMcpProvider()
    private val mcpServer = McpServerConfig(
        name = "test-server",
        baseUrl = "http://localhost",
        isEnabled = true
    )

    private fun toolCall(name: String, args: Map<String, Any?> = emptyMap()) =
        StreamEvent.ToolCallComplete(
            toolCallId = "call_test",
            toolName = name,
            arguments = args
        )

    // ==================== 向后兼容测试 ====================

    @Test
    fun `executeToolCall without localToolExecutor uses MCP path`() = runBlocking {
        val executor = SkillExecutor(fakeMcp, approveGate)
        val result = executor.executeToolCall(toolCall("filesystem__read_file"), listOf(mcpServer))

        assertEquals("MCP 工具结果", result)
        assertEquals(1, fakeMcp.callToolCalls)
    }

    // ==================== 本地工具分支测试 ====================

    @Test
    fun `executeToolCall with local tool uses local path and skips MCP`() = runBlocking {
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__open_app"),
            result = "已打开微信"
        )
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("cross_app__open_app", mapOf("appId" to "wechat")),
            listOf(mcpServer)
        )

        assertEquals("已打开微信", result)
        assertEquals(1, localExecutor.executeCalls.size)
        assertEquals("cross_app__open_app", localExecutor.executeCalls[0].first)
        assertEquals("MCP should not be called for local tools", 0, fakeMcp.callToolCalls)
    }

    @Test
    fun `executeToolCall with local tool not handled falls through to MCP`() = runBlocking {
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__open_app")
        )
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("filesystem__read_file"),
            listOf(mcpServer)
        )

        assertEquals("MCP 工具结果", result)
        assertEquals(0, localExecutor.executeCalls.size)
        assertEquals(1, fakeMcp.callToolCalls)
    }

    @Test
    fun `executeToolCall passes arguments to local executor`() = runBlocking {
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__share_content")
        )
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)
        val args = mapOf<String, Any?>("content" to "Hello World")

        executor.executeToolCall(toolCall("cross_app__share_content", args), listOf(mcpServer))

        assertEquals(args, localExecutor.executeCalls[0].second)
    }

    // ==================== 用户确认测试 ====================

    @Test
    fun `executeToolCall local tool requires user confirmation`() = runBlocking {
        val rejectGate = ToolConfirmationGate { _, _ -> false }
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__open_app")
        )
        val executor = SkillExecutor(fakeMcp, rejectGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("cross_app__open_app", mapOf("appId" to "wechat")),
            listOf(mcpServer)
        )

        assertEquals("用户拒绝执行工具: cross_app__open_app", result)
        assertEquals("Local executor should not be called when rejected", 0, localExecutor.executeCalls.size)
    }

    // ==================== 错误处理测试 ====================

    @Test
    fun `executeToolCall local tool timeout returns timeout message`() = runBlocking {
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__open_app"),
            delayMs = 5000
        )
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("cross_app__open_app"),
            listOf(mcpServer),
            maxTimeoutMs = 200 // 200ms 超时
        )

        assertTrue("should return timeout message", result.startsWith("工具执行超时"))
    }

    @Test
    fun `executeToolCall local tool exception returns error message`() = runBlocking {
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__open_app"),
            throwException = RuntimeException("bridge error")
        )
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("cross_app__open_app"),
            listOf(mcpServer)
        )

        assertTrue("should return error message", result.startsWith("工具执行失败"))
        assertTrue("should contain tool name", result.contains("cross_app__open_app"))
    }

    @Test
    fun `executeToolCall local tool propagates CancellationException`() = runBlocking {
        val localExecutor = FakeLocalToolExecutor(
            handledTools = setOf("cross_app__open_app"),
            throwException = CancellationException("coroutine cancelled")
        )
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        var cancellationThrown = false
        try {
            executor.executeToolCall(
                toolCall("cross_app__open_app"),
                listOf(mcpServer)
            )
        } catch (e: CancellationException) {
            cancellationThrown = true
        }
        assertTrue("CancellationException should be propagated", cancellationThrown)
    }

    // ==================== 本地工具失败前缀测试 ====================

    @Test
    fun `isFailureResult recognizes local tool failure prefixes`() {
        // 测试 M6 新增的本地工具失败前缀
        assertTrue(SkillExecutor.isFailureResult("未找到应用配置: unknown_app"))
        assertTrue(SkillExecutor.isFailureResult("未安装微信，请手动打开"))
        assertTrue(SkillExecutor.isFailureResult("跨 App 调用超时（30000ms），未收到结果"))
        assertTrue(SkillExecutor.isFailureResult("缺少必需参数 appId"))
        assertTrue(SkillExecutor.isFailureResult("不支持的媒体类型: video"))
        assertTrue(SkillExecutor.isFailureResult("未知跨 App 工具: cross_app__unknown"))
    }

    @Test
    fun `isFailureResult does not flag local tool success messages`() {
        // 验证成功消息不被误判为失败
        assertTrue(!SkillExecutor.isFailureResult("已打开微信"))
        assertTrue(!SkillExecutor.isFailureResult("已分享文本"))
        assertTrue(!SkillExecutor.isFailureResult("已选取照片"))
    }

    @Test
    fun `isFailureResult still recognizes MCP failure prefixes`() {
        // 验证 M4 既有前缀仍然有效（向后兼容）
        assertTrue(SkillExecutor.isFailureResult("用户拒绝执行工具: read_file"))
        assertTrue(SkillExecutor.isFailureResult("工具执行超时（30000ms）: read_file"))
        assertTrue(SkillExecutor.isFailureResult("工具执行失败: read_file（timeout）"))
        assertTrue(SkillExecutor.isFailureResult("无可用 MCP Server，无法执行工具: read_file"))
        assertTrue(SkillExecutor.isFailureResult("用户确认失败: read_file（error）"))
    }
}
