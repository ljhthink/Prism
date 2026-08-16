package io.prism.skill

import io.prism.config.ToolApprovalMode
import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.data.ProviderConfig
import io.prism.fs.ToolConfirmationGate
import io.prism.network.ChatStreamProvider
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillExecutor 审批模式 executeLoop 多轮状态一致性补充测试
 * （ac-verifier，TKN-UXR3-ACCEPTANCE-001 极端场景补充）。
 *
 * 主 Agent 基础用例覆盖了 [SkillExecutor.executeToolCall] 单次调用的三模式分派，
 * 但未覆盖 **executeLoop 多轮回路中的审批模式状态一致性**：
 * - DISABLED：多轮 tool_call 全部返回禁用文案，不请求确认、不执行工具，回灌给 LLM
 * - AUTO：多轮 tool_call 全部直接放行，不请求确认（即使 gate 会拒绝）
 */
class SkillExecutorApprovalModeLoopTest {

    // ==================== DISABLED 模式：executeLoop 多轮一致性 ====================

    @Test
    fun `executeLoop DISABLED mode rejects all tool calls across rounds`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "skill__t1", mapOf("x" to 1))),
                listOf(StreamEvent.ToolCallComplete("c2", "skill__t2", mapOf("y" to 2))),
                listOf(StreamEvent.Delta("done"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(
            mcpProvider, gate, Dispatchers.Unconfined,
            approvalModeProvider = { ToolApprovalMode.DISABLED }
        )
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t1"), makeToolDefinition("skill__t2"))
        val servers = listOf(makeServer("fs", true))

        var idCounter = 1L
        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { idCounter++ }
        ) { /* ignore */ }

        // 每轮 tool_call 都回灌禁用文案（2 轮 tool + 1 轮纯文本）
        val toolResults = result.filter { it.role == Role.TOOL }
        assertEquals("应有 2 条 tool result（每轮 1 条）", 2, toolResults.size)
        toolResults.forEach { msg ->
            assertTrue("tool result 应含禁用文案", msg.content.contains("已禁用"))
            assertTrue("tool result 应含工具名", msg.content.contains("skill__t"))
        }
        // DISABLED 模式不请求确认、不执行工具
        assertEquals("DISABLED 模式不应请求用户确认", 0, gate.confirmCount)
        assertEquals("DISABLED 模式不应执行任何工具", 0, mcpProvider.callCount)
    }

    @Test
    fun `executeLoop DISABLED mode triggers circuit breaker rather than maxRounds error`() = runBlocking {
        // DISABLED 模式每轮都返回 tool_call → 连续失败 2 次后触发重复工具熔断
        //（UXR6 问题 1 修复：熔断后置空工具 + 提示 LLM 直接回答，不再发射 maxRounds Error）
        val provider = FakeChatStreamProvider(
            rounds = listOf(listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap()))),
            repeatLastRound = true
        )
        val executor = SkillExecutor(
            FakeMcpToolProvider("r"), FakeConfirmationGate(true), Dispatchers.Unconfined,
            approvalModeProvider = { ToolApprovalMode.DISABLED }
        )
        val events = mutableListOf<StreamEvent>()
        var idCounter = 1L
        executor.executeLoop(
            provider, makeProviderConfig(), listOf(makeUserMessage("hi")),
            systemPrompt = null, ragContext = null,
            tools = listOf(makeToolDefinition("skill__t")),
            mcpServers = listOf(makeServer("fs", true)),
            maxRounds = 2, idGenerator = { idCounter++ }
        ) { events.add(it) }

        // 熔断后不再发射 maxRounds Error（回路因工具为空自然结束，LLM 纯文本回答）
        assertFalse(
            "DISABLED 模式无限回路应触发重复工具熔断而非 maxRounds Error",
            events.any { it is StreamEvent.Error && it.message.contains("上限") }
        )
        // 熔断机制：连续 2 次失败后置空 tools，确保回路自然结束
        assertTrue(
            "熔断后回路应自然结束（无 maxRounds Error）",
            events.none { it is StreamEvent.Error }
        )
    }

    // ==================== AUTO 模式：executeLoop 多轮一致性 ====================

    @Test
    fun `executeLoop AUTO mode executes all tool calls across rounds without confirmation`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(
                    StreamEvent.ToolCallComplete("c1", "skill__t1", emptyMap()),
                    StreamEvent.ToolCallComplete("c2", "skill__t2", emptyMap())
                ),
                listOf(StreamEvent.Delta("done"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "auto result")
        // gate 设置为拒绝 —— AUTO 模式下不应被调用
        val gate = FakeConfirmationGate(approve = false)
        val executor = SkillExecutor(
            mcpProvider, gate, Dispatchers.Unconfined,
            approvalModeProvider = { ToolApprovalMode.AUTO }
        )
        val initialMessages = listOf(makeUserMessage("hi"))
        var idCounter = 1L
        val result = executor.executeLoop(
            provider, makeProviderConfig(), initialMessages,
            systemPrompt = null, ragContext = null,
            tools = listOf(makeToolDefinition("skill__t1"), makeToolDefinition("skill__t2")),
            mcpServers = listOf(makeServer("fs", true)),
            maxRounds = 10, idGenerator = { idCounter++ }
        ) { /* ignore */ }

        // 两个不同名工具都执行（AUTO 直放）
        assertEquals("AUTO 模式应执行全部 2 个工具", 2, mcpProvider.callCount)
        assertEquals("AUTO 模式不应请求用户确认", 0, gate.confirmCount)
        val toolResults = result.filter { it.role == Role.TOOL }
        assertEquals("应有 2 条 tool result", 2, toolResults.size)
        toolResults.forEach { assertTrue(it.content.contains("auto result")) }
    }

    // ==================== Fake 实现（与 SkillExecutorTest 私有 fake 对齐） ====================

    private class FakeMcpToolProvider(private val returnResult: String) : McpToolProvider {
        var callCount = 0
            private set
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(
            config: McpServerConfig, name: String, arguments: Map<String, Any?>
        ): String {
            callCount++
            return returnResult
        }
    }

    private class FakeConfirmationGate(private val approve: Boolean) : ToolConfirmationGate {
        var confirmCount = 0
            private set
        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean {
            confirmCount++
            return approve
        }
    }

    private class FakeChatStreamProvider(
        private val rounds: List<List<StreamEvent>>,
        private val repeatLastRound: Boolean = false
    ) : ChatStreamProvider {
        private var consumed = 0
        override fun streamChat(
            config: ProviderConfig, messages: List<ChatMessage>,
            systemPrompt: String?, ragContext: String?,
            tools: List<ToolDefinition>?, toolChoice: ToolChoice?,
            thinkingEnabled: Boolean?, reasoningEffort: String?
        ): Flow<StreamEvent> {
            val idx = if (repeatLastRound) consumed.coerceAtMost(rounds.size - 1) else consumed.coerceAtMost(rounds.size - 1)
            consumed++
            val events = rounds.getOrElse(idx) { emptyList() }
            return flow { events.forEach { emit(it) } }
        }
    }

    private fun makeServer(name: String, isEnabled: Boolean): McpServerConfig =
        McpServerConfig(name = name, serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = isEnabled)

    private fun makeUserMessage(content: String): ChatMessage =
        ChatMessage(id = 0L, role = Role.USER, content = content, timestamp = 0L)

    private fun makeProviderConfig(): ProviderConfig =
        ProviderConfig(name = "test", baseUrl = "https://api.test.com/v1", apiKeyRef = "ref")

    private fun makeToolDefinition(name: String): ToolDefinition =
        ToolDefinition(
            function = ToolDefinition.FunctionDef(
                name = name,
                description = "test tool",
                parameters = JsonObject(emptyMap())
            )
        )
}
