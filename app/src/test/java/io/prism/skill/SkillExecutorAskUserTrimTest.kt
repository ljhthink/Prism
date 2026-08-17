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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillExecutor ask_user 占位裁剪路径补充测试（UXR8 N2 Phase 2，ADR-030，guardrail N-LOW-B）——
 * ac-verifier TKN-UXR8-B3-ACCEPTANCE-001。
 *
 * 补盲区：guardrail 复审（TKN-UXR8-B3-GUARDRAIL-002）建议级观察 N-LOW-B ——
 * Q-LOW-2 裁剪路径（ask_user **非本轮末尾 tool_call**、`executedToolCallIds < uniqueToolCalls`）
 * 无直接单测。本文件用「同一轮 LLM 声明 [ask_user, 普通工具] 且 ask_user 在前执行」的
 * fake provider 覆盖：
 * 1. 发射 1 个 AskUser 事件
 * 2. 返回消息中 assistant.toolCalls **仅含已执行的 ask_user**（未执行的普通工具引用被裁剪）
 * 3. TOOL 消息数 == 已执行数（无孤儿 TOOL、无孤儿 tool_calls 引用，下一轮协议不 400）
 */
class SkillExecutorAskUserTrimTest {

    /** 第 1 轮并行声明 [ask_user, 普通工具]（ask_user 在前）的 fake provider。 */
    private class MultiToolAskUserFirstProvider : ChatStreamProvider {
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
                if (roundsConsumed == 1) {
                    // LLM 一轮多 tool_call：ask_user 在前，普通 MCP 工具在后（两者均声明）
                    emit(
                        StreamEvent.ToolCallComplete(
                            toolCallId = "call_ask",
                            toolName = AskUserLocalToolExecutor.TOOL_ASK,
                            arguments = mapOf("questions" to listOf(mapOf("question" to "Q?")))
                        )
                    )
                    emit(
                        StreamEvent.ToolCallComplete(
                            toolCallId = "call_reg",
                            toolName = "mcp_test__regular",
                            arguments = emptyMap()
                        )
                    )
                } else {
                    emit(StreamEvent.Delta("final"))
                    emit(StreamEvent.Done)
                }
            }
        }
    }

    private class FakeMcpToolProvider : McpToolProvider {
        var callToolCalls = 0
            private set
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String {
            callToolCalls++
            return "MCP 结果"
        }
    }

    private val approveGate = ToolConfirmationGate { _, _ -> true }

    private fun makeUserMessage(): ChatMessage =
        ChatMessage(id = 0L, role = Role.USER, content = "帮我决策", timestamp = 0L)

    private fun makeProviderConfig(): ProviderConfig =
        ProviderConfig(name = "test", baseUrl = "https://api.test.com/v1", apiKeyRef = "ref")

    private fun makeToolDefinition(): ToolDefinition = AskUserLocalToolExecutor.buildToolDefinition()

    private fun makeServer(): McpServerConfig =
        McpServerConfig(name = "test", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true)

    @Test
    fun `ask_user not last tool call in round trims placeholder and no orphan tools`() = runBlocking {
        val provider = MultiToolAskUserFirstProvider()
        val mcp = FakeMcpToolProvider()
        val executor = SkillExecutor(
            mcpToolProvider = mcp,
            confirmationGate = approveGate,
            localToolExecutor = AskUserLocalToolExecutor(),
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

        // 1. 发射 1 个 AskUser 事件（携带问题）
        val ask = events.filterIsInstance<StreamEvent.AskUser>()
        assertEquals("应发射 1 个 AskUser 事件", 1, ask.size)
        assertEquals("Q?", ask[0].questions[0].question)

        // 2. StopAtTools：回路中断，不再请求 LLM 第 2 轮
        assertEquals("ask_user 触发应中断回路（仅 1 轮）", 1, provider.roundsConsumed)

        // 3. assistant 占位 toolCalls 被裁剪为已执行子集（仅 call_ask，未执行的 call_reg 被剔除）
        val assistantPlaceholder = result.filter { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
        assertEquals("应恰有 1 条 assistant 占位", 1, assistantPlaceholder.size)
        assertEquals(
            "占位 toolCalls 应仅含已执行的 call_ask（未执行的 call_reg 被裁剪）",
            1, assistantPlaceholder[0].toolCalls.size
        )
        assertEquals("call_ask", assistantPlaceholder[0].toolCalls[0].id)
        assertEquals(AskUserLocalToolExecutor.TOOL_ASK, assistantPlaceholder[0].toolCalls[0].functionName)

        // 4. TOOL 消息数 == 已执行数（仅 call_ask 结果），无孤儿 TOOL、无孤儿 tool_calls
        val toolMessages = result.filter { it.role == Role.TOOL }
        assertEquals("TOOL 消息应恰为 1 条（仅 call_ask 结果，call_reg 未执行无结果）", 1, toolMessages.size)
        assertEquals("call_ask", toolMessages[0].toolCallId)
        assertTrue(
            "TOOL 结果应带 ask_user 标记前缀",
            toolMessages[0].content.startsWith(AskUserLocalToolExecutor.RESULT_MARKER)
        )
        // call_reg 因中断从未执行（MCP 未被调用）
        assertEquals("未执行工具不应触发 MCP 调用", 0, mcp.callToolCalls)

        // 5. 无孤儿配对：每条 TOOL 消息的 toolCallId 都能在前置 assistant.toolCalls 中找到
        val placeholderIds = assistantPlaceholder[0].toolCalls.map { it.id }.toSet()
        assertTrue("无孤儿 TOOL（每条 toolCallId 均有前置 tool_calls 配对）", toolMessages.all { it.toolCallId in placeholderIds })
    }
}
