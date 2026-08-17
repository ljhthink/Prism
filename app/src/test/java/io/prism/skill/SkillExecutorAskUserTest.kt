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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillExecutor UXR8 N2 Phase 2（ADR-030）反问回路集成测试。
 *
 * 验证 `ask_user__ask` 工具在 executeLoop 中的 StopAtTools 语义：
 * - LLM 调用 ask_user__ask → 本地执行器返回标记前缀 → 发射 [StreamEvent.AskUser]
 * - 中断当前工具回路（不再请求 LLM 第 2 轮）
 * - 工具结果仍回灌历史（协议一致）
 *
 * 采用「真实 executeLoop + fake ChatStreamProvider」（BR-testing-006 循环级测试）。
 */
class SkillExecutorAskUserTest {

    /** Fake ChatStreamProvider：第 1 轮发射 tool_call，第 2 轮本应被短路（不应被消费）。 */
    private class FakeChatStreamProvider(
        private val events: List<StreamEvent>
    ) : ChatStreamProvider {
        var roundsConsumed = 0
            private set

        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> {
            roundsConsumed++
            return flow { events.forEach { emit(it) } }
        }
    }

    private class FakeMcpToolProvider : McpToolProvider {
        var callToolCalls = 0
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String {
            callToolCalls++
            return "MCP 结果"
        }
    }

    /**
     * 按轮次变化的 Fake：第 [askUserOnRound] 轮发射 ask_user，其余轮发射普通工具调用。
     *
     * 用于验证 Q-MED-2（guardrail TKN-UXR8-B3-GUARDRAIL-001）：ask_user 恰在第 maxRounds
     * 轮触发时应复位 lastRoundHadToolCall，不误发「工具调用循环达上限」Error。
     */
    private class RoundsAwareProvider(
        private val askUserOnRound: Int
    ) : ChatStreamProvider {
        var roundsConsumed = 0
            private set

        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> {
            roundsConsumed++
            return flow {
                if (roundsConsumed < askUserOnRound) {
                    // 普通工具调用（走 MCP 路径，FakeMcpToolProvider 返回结果）
                    emit(StreamEvent.ToolCallComplete("call_reg_$roundsConsumed", "mcp_test__regular", emptyMap()))
                } else {
                    emit(
                        StreamEvent.ToolCallComplete(
                            toolCallId = "call_ask",
                            toolName = AskUserLocalToolExecutor.TOOL_ASK,
                            arguments = mapOf("questions" to listOf(mapOf("question" to "Q?")))
                        )
                    )
                }
            }
        }
    }

    private val approveGate = ToolConfirmationGate { _, _ -> true }

    private fun makeUserMessage(): ChatMessage =
        ChatMessage(id = 0L, role = Role.USER, content = "帮我选一个方案", timestamp = 0L)

    private fun makeProviderConfig(): ProviderConfig =
        ProviderConfig(name = "test", baseUrl = "https://api.test.com/v1", apiKeyRef = "ref")

    private fun makeToolDefinition(): ToolDefinition = AskUserLocalToolExecutor.buildToolDefinition()

    private fun makeServer(): McpServerConfig =
        McpServerConfig(name = "test", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true)

    @Test
    fun `ask_user tool emits AskUser event and stops loop`() = runBlocking {
        val provider = FakeChatStreamProvider(
            listOf(
                StreamEvent.ToolCallComplete(
                    toolCallId = "call_ask",
                    toolName = AskUserLocalToolExecutor.TOOL_ASK,
                    arguments = mapOf(
                        "questions" to listOf(
                            mapOf("question" to "你想要 A 还是 B？", "options" to listOf(mapOf("label" to "A"), mapOf("label" to "B")))
                        )
                    )
                )
            )
        )
        val executor = SkillExecutor(
            mcpToolProvider = FakeMcpToolProvider(),
            confirmationGate = approveGate,
            localToolExecutor = AskUserLocalToolExecutor(),
            // AUTO 审批：免确认直接执行（AskUser 是纯 UI 交互工具，无需用户确认放行）
            approvalModeProvider = { ToolApprovalMode.AUTO }
        )

        val events = mutableListOf<StreamEvent>()
        val result = executor.executeLoop(
            provider = provider,
            config = makeProviderConfig(),
            messages = listOf(makeUserMessage()),
            systemPrompt = "你是助手",
            ragContext = null,
            tools = listOf(makeToolDefinition()),
            mcpServers = listOf(makeServer()),
            onEvent = { events.add(it) }
        )

        // 1. 应发射 AskUser 事件（携带问题）
        val ask = events.filterIsInstance<StreamEvent.AskUser>()
        assertEquals("应发射 1 个 AskUser 事件", 1, ask.size)
        assertEquals(1, ask[0].questions.size)
        assertEquals("你想要 A 还是 B？", ask[0].questions[0].question)
        assertEquals(2, ask[0].questions[0].options.size)

        // 2. StopAtTools：回路中断，不再请求 LLM 第 2 轮
        assertEquals("应中断回路，仅消费 1 轮（不再请求 LLM）", 1, provider.roundsConsumed)

        // 3. 工具结果仍回灌历史（ask_user TOOL 消息存在）
        val toolResult = result.filter { it.role == Role.TOOL }
        assertEquals("ask_user 工具结果应回灌历史", 1, toolResult.size)
        assertTrue("结果应带标记前缀", toolResult[0].content.startsWith(AskUserLocalToolExecutor.RESULT_MARKER))
    }

    @Test
    fun `ask_user with malformed payload degrades without AskUser event`() = runBlocking {
        // LLM 传非法 questions（非数组）→ execute 降级文案回灌，不发射 AskUser（不中断）
        val provider = FakeChatStreamProvider(
            listOf(
                StreamEvent.ToolCallComplete(
                    toolCallId = "call_bad",
                    toolName = AskUserLocalToolExecutor.TOOL_ASK,
                    arguments = mapOf("questions" to "not-a-list")
                )
            )
        )
        val executor = SkillExecutor(
            mcpToolProvider = FakeMcpToolProvider(),
            confirmationGate = approveGate,
            localToolExecutor = AskUserLocalToolExecutor(),
            approvalModeProvider = { ToolApprovalMode.AUTO }
        )
        val events = mutableListOf<StreamEvent>()
        // 第 2 轮返回空 → 回路自然结束（仍会消费第 2 轮，因为第 1 轮无 AskUser 中断）
        val provider2 = FakeChatStreamProvider(listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap())))
        executor.executeLoop(
            provider = provider2,
            config = makeProviderConfig(),
            messages = listOf(makeUserMessage()),
            systemPrompt = "你是助手",
            ragContext = null,
            tools = listOf(makeToolDefinition()),
            mcpServers = listOf(makeServer()),
            onEvent = { events.add(it) }
        )
        assertTrue("非法参数不应发射 AskUser 事件", events.none { it is StreamEvent.AskUser })
    }

    @Test
    fun `parseAskUserPayload round trips valid json and rejects malformed`() {
        val payloadJson = "{\"questions\":[{\"question\":\"Q?\",\"options\":[{\"label\":\"A\"}],\"multiSelect\":false}]}"
        val parsed = SkillExecutor.parseAskUserPayload(payloadJson)
        assertNotNull(parsed)
        assertEquals("Q?", parsed!!.questions[0].question)
        assertEquals("A", parsed.questions[0].options[0].label)
        assertEquals(null, SkillExecutor.parseAskUserPayload("{broken"))
    }

    @Test
    fun `ask_user on maxRounds round does not emit loop limit error`() = runBlocking {
        // Q-MED-2（guardrail TKN-UXR8-B3-GUARDRAIL-001）：ask_user 恰在第 maxRounds 轮触发时，
        // 应复位 lastRoundHadToolCall 使 shouldEmitMaxRoundsError 不触发——ask_user 是主动终止
        // 工具循环（等价熔断语义），不应误发「工具调用循环达上限」Error。
        val maxRounds = 3
        val provider = RoundsAwareProvider(askUserOnRound = maxRounds)
        val executor = SkillExecutor(
            mcpToolProvider = FakeMcpToolProvider(),
            confirmationGate = approveGate,
            localToolExecutor = AskUserLocalToolExecutor(),
            approvalModeProvider = { ToolApprovalMode.AUTO }
        )
        val events = mutableListOf<StreamEvent>()
        executor.executeLoop(
            provider = provider,
            config = makeProviderConfig(),
            messages = listOf(makeUserMessage()),
            systemPrompt = "你是助手",
            ragContext = null,
            tools = listOf(makeToolDefinition()),
            mcpServers = listOf(makeServer()),
            maxRounds = maxRounds,
            onEvent = { events.add(it) }
        )

        // 1. ask_user 在第 maxRounds 轮触发并中断（发射 AskUser 事件）
        assertEquals("应发射 1 个 AskUser 事件", 1, events.filterIsInstance<StreamEvent.AskUser>().size)
        // 2. 不应误发「工具调用循环达上限」Error
        assertTrue(
            "ask_user 主动终止不应误发循环达上限",
            events.filterIsInstance<StreamEvent.Error>().none { it.message.contains("循环达上限") }
        )
        // 3. 完整消费 maxRounds 轮（前 2 轮普通工具 + 第 3 轮 ask_user）
        assertEquals("应消费 $maxRounds 轮", maxRounds, provider.roundsConsumed)
    }
}
