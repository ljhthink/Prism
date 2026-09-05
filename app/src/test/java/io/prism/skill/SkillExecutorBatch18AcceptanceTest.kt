package io.prism.skill

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次18 三项真机问题修复验收测试（ac-verifier，TKN-V1B18-ACCEPTANCE-001）—— AC-1 熔断外科化。
 *
 * 覆盖任务书 AC-1 四个可验证条件（既有用例已覆盖「双工具外科化场景」
 * ConversationViewModelUxR6Test 与「文本路径阈值 3 熔断」SkillExecutorTest，
 * 本文件补齐其边界与防护缺口）：
 * 1. **阈值下边界**：网络类工具 2 次连续失败（< 阈值 3）不得提前禁用（防回退旧阈值 2）；
 *    第 3 次失败达阈值 → 唯一工具被禁 → 下一轮 tools 为空 → LLM 纯文本收尾（TC-B18-001）
 * 2. **phone_control 类别阈值 4 边界**：3 次失败不禁用、第 4 次失败才禁用（TC-B18-002）
 * 3. **原生路径幻觉重调防护**：已禁用工具被 LLM 重调 → 不真实执行 + 禁用文案 tool result
 *    回灌（tool_calls 协议配对保持）+ 其余工具仍可用（TC-B18-003）
 * 4. **文本路径幻觉重调防护**（guardrail P2-2 修复 + R2-Obs-2 补强）：已禁用文本工具被
 *    重调 → 不真实执行 + 禁用文案 user 消息回灌（TC-B18-004）
 *
 * 全部场景断言：无 maxRounds Error 误报（熔断优先于循环上限错误）。
 */
class SkillExecutorBatch18AcceptanceTest {

    // ==================== TC-B18-001：网络类阈值 3 下边界 + 全部禁用纯文本收尾 ====================

    @Test
    fun `executeLoop network tool survives 2 consecutive failures and trips at threshold 3`() = runBlocking {
        // 场景（真机 RCA 复现缩放版）：长任务中唯一网络工具连续失败——
        // 旧语义（MAX_CONSECUTIVE_TOOL_FAILURES=2）2 次失败即清空全部工具中断任务；
        // 新语义：阈值 3，第 2 次失败后工具仍可用，第 3 次失败才禁用该工具。
        val provider = RecordingChatProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "web_search__search", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c2", "web_search__search", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c3", "web_search__search", emptyMap())),
                listOf(StreamEvent.Delta("基于已有信息直接回答用户"), StreamEvent.Done)
            )
        )
        val mcp = RecordingMcpProvider("错误：模拟联网搜索服务持续失败")
        val executor = SkillExecutor(mcp, FakeGate(approve = true), Dispatchers.Unconfined)
        val events = mutableListOf<StreamEvent>()
        val result = executor.executeLoop(
            provider, makeProviderConfig(), listOf(makeUserMessage("搜索资料")),
            systemPrompt = null, ragContext = null,
            tools = listOf(makeToolDefinition("web_search__search")),
            mcpServers = listOf(makeServer("fs", true)),
            maxRounds = 10, idGenerator = { 1L }
        ) { events.add(it) }

        // AC-1a 下边界：2 次失败（< 阈值 3）后，第 3 轮请求中工具**未被禁用**
        assertTrue(
            "第 2 次失败未达阈值 3，第 3 轮工具应仍可用（防回退旧阈值 2）",
            provider.calls[2].tools.orEmpty().any { it.function.name == "web_search__search" }
        )
        // 第 3 次失败达阈值 → 唯一工具被禁 → 第 4 轮 tools 为空 → LLM 纯文本收尾（AC-1f）
        assertTrue(
            "达阈值后唯一工具应被禁用（第 4 轮 tools 为空）",
            provider.calls[3].tools.isNullOrEmpty()
        )
        assertTrue(
            "第 4 轮应注入单工具禁用提示",
            provider.calls[3].systemPrompt.orEmpty().contains("已被禁用")
        )
        assertEquals("工具真实执行 3 次后熔断（不无限重试）", 3, mcp.callCount)
        assertEquals("熔断后仅再跑 1 轮纯文本收尾（共 4 轮）", 4, provider.calls.size)
        assertTrue(
            "最终应向用户输出纯文本回答",
            events.any { it is StreamEvent.Delta && it.content.contains("基于已有信息直接回答") }
        )
        assertTrue(
            "3 次失败结果均应以 tool role 消息回灌（协议配对完整）",
            result.count { it.role == Role.TOOL && it.content.contains("错误：模拟联网搜索服务持续失败") } == 3
        )
        assertFalse(
            "不应误报 maxRounds Error（熔断目标为给出答案而非报错）",
            events.any { it is StreamEvent.Error && it.message.contains("上限") }
        )
    }

    // ==================== TC-B18-002：phone_control 类别阈值 4 边界 ====================

    @Test
    fun `executeLoop phone control tool trips only at 4 consecutive failures`() = runBlocking {
        // 场景（真机 19:37 RCA）：phone_control 软失败（输入未生效）在真机 UI 差异下常见，
        // 类别阈值 4——3 次失败不得禁用（保留工具可用），第 4 次失败才禁用。
        val provider = RecordingChatProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "phone_control__tap", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c2", "phone_control__tap", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c3", "phone_control__tap", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c4", "phone_control__tap", emptyMap())),
                listOf(StreamEvent.Delta("改为口头引导用户手动操作"), StreamEvent.Done)
            )
        )
        val mcp = RecordingMcpProvider("错误：模拟真机输入未生效（屏幕无变化）")
        val executor = SkillExecutor(mcp, FakeGate(approve = true), Dispatchers.Unconfined)
        val events = mutableListOf<StreamEvent>()
        executor.executeLoop(
            provider, makeProviderConfig(), listOf(makeUserMessage("点击搜索框")),
            systemPrompt = null, ragContext = null,
            tools = listOf(makeToolDefinition("phone_control__tap")),
            mcpServers = listOf(makeServer("fs", true)),
            maxRounds = 10, idGenerator = { 1L }
        ) { events.add(it) }

        // 下边界：3 次失败（< phone 阈值 4）后，第 4 轮工具仍可用
        assertTrue(
            "3 次失败未达 phone_control 阈值 4，第 4 轮工具应仍可用",
            provider.calls[3].tools.orEmpty().any { it.function.name == "phone_control__tap" }
        )
        // 第 4 次失败达阈值 → 第 5 轮禁用 + 纯文本收尾
        assertTrue(
            "第 4 次失败达阈值 4，第 5 轮 tools 应为空",
            provider.calls[4].tools.isNullOrEmpty()
        )
        assertTrue(
            "第 5 轮应注入禁用提示",
            provider.calls[4].systemPrompt.orEmpty().contains("已被禁用")
        )
        assertEquals("phone 工具真实执行 4 次后熔断", 4, mcp.callCount)
        assertEquals("熔断后仅再跑 1 轮纯文本（共 5 轮）", 5, provider.calls.size)
        assertFalse(
            "不应误报 maxRounds Error",
            events.any { it is StreamEvent.Error && it.message.contains("上限") }
        )
    }

    // ==================== TC-B18-003：原生路径幻觉重调防护 ====================

    @Test
    fun `executeLoop native path blocks hallucinated recall of disabled tool`() = runBlocking {
        // 场景：web_search__search 连续 3 次失败被禁用后，LLM 第 4 轮幻觉重调该工具 →
        // 必须被拦截（不真实执行、禁用文案回灌），第 5 轮换用未被禁用的本地兜底工具成功。
        val provider = RecordingChatProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "web_search__search", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c2", "web_search__search", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c3", "web_search__search", emptyMap())),
                // 第 4 轮：幻觉重调已禁用工具
                listOf(StreamEvent.ToolCallComplete("c4", "web_search__search", emptyMap())),
                // 第 5 轮：换用未被禁用的本地兜底工具
                listOf(StreamEvent.ToolCallComplete("c5", "web_search__search_local", emptyMap())),
                listOf(StreamEvent.Delta("基于本地兜底结果汇报"), StreamEvent.Done)
            )
        )
        val mcp = RecordingMcpProvider(
            defaultResult = "错误：模拟失败",
            resultsByName = mapOf(
                "search" to "错误：模拟联网搜索服务持续失败",
                "search_local" to "本地兜底结果：本地缓存命中相关条目"
            )
        )
        val executor = SkillExecutor(mcp, FakeGate(approve = true), Dispatchers.Unconfined)
        val events = mutableListOf<StreamEvent>()
        val result = executor.executeLoop(
            provider, makeProviderConfig(), listOf(makeUserMessage("搜索资料")),
            systemPrompt = null, ragContext = null,
            tools = listOf(
                makeToolDefinition("web_search__search"),
                makeToolDefinition("web_search__search_local")
            ),
            mcpServers = listOf(makeServer("fs", true)),
            maxRounds = 10, idGenerator = { 1L }
        ) { events.add(it) }

        // 幻觉重调**未真实执行**：物理工具 search 仅前 3 轮真实执行（重调被拦截）
        assertEquals(
            "被禁工具幻觉重调不得真实执行（仅熔断前 3 次真实调用）",
            3, mcp.callCountFor("search")
        )
        // 重调以禁用文案 tool result 回灌（协议配对保持）
        val disabledResultMsg = result.lastOrNull {
            it.role == Role.TOOL && it.content.contains("web_search__search") && it.content.contains("已因连续多次调用失败被禁用")
        }
        assertTrue("幻觉重调应以禁用文案 tool result 回灌", disabledResultMsg != null)
        // 第 5 轮请求协议配对完整：assistant 占位（携带 tool_calls）后紧跟 tool 结果
        val round5 = provider.calls[4].messages
        val lastAssistantIdx = round5.indexOfLast { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
        assertTrue("第 5 轮请求应含 assistant 占位 + tool 结果配对", lastAssistantIdx >= 0)
        assertEquals("占位之后应为 tool 结果消息（禁用文案）", Role.TOOL, round5[lastAssistantIdx + 1].role)
        assertTrue(round5[lastAssistantIdx + 1].content.contains("已因连续多次调用失败被禁用"))
        // 其余工具未被牵连：第 5 轮本地兜底工具可用且执行成功
        assertEquals("未被禁用的本地兜底工具应正常执行", 1, mcp.callCountFor("search_local"))
        assertTrue(
            "本地兜底结果应回灌",
            result.any { it.role == Role.TOOL && it.content.contains("本地兜底结果") }
        )
        assertTrue(
            "第 5 轮请求 tools 不应再含被禁工具",
            provider.calls[4].tools.orEmpty().none { it.function.name == "web_search__search" }
        )
        assertFalse(
            "不应误报 maxRounds Error",
            events.any { it is StreamEvent.Error && it.message.contains("上限") }
        )
    }

    // ==================== TC-B18-004：文本路径幻觉重调防护（guardrail P2-2 / R2-Obs-2） ====================

    @Test
    fun `executeLoop text path blocks hallucinated recall of disabled tool`() = runBlocking {
        // 场景：文本工具型模型（glm-4.6v-flash 族，手机操控主力）skill__t 连续 3 次失败
        // 被禁用后，第 4 轮幻觉重调（文本 <tool_call> 块）→ guardrail P2-2 守卫拦截：
        // 不真实执行 + 禁用文案并入 results 回灌；第 5 轮 LLM 纯文本收尾。
        val toolCallText = "<tool_call>skill__t\n<arg_key>v</arg_key>\n<arg_value>1</arg_value>\n</tool_call>"
        val provider = RecordingChatProvider(
            rounds = listOf(
                listOf(StreamEvent.Delta(toolCallText), StreamEvent.Done),
                listOf(StreamEvent.Delta(toolCallText), StreamEvent.Done),
                listOf(StreamEvent.Delta(toolCallText), StreamEvent.Done),
                // 第 4 轮：幻觉重调已禁用的文本工具
                listOf(StreamEvent.Delta(toolCallText), StreamEvent.Done),
                // 第 5 轮：纯文本收尾
                listOf(StreamEvent.Delta("工具已不可用，直接基于已有信息回答"), StreamEvent.Done)
            )
        )
        val mcp = RecordingMcpProvider("错误：mock 工具持续失败")
        val executor = SkillExecutor(mcp, FakeGate(approve = true), Dispatchers.Unconfined)
        val events = mutableListOf<StreamEvent>()
        executor.executeLoop(
            provider, makeProviderConfig(), listOf(makeUserMessage("hi")),
            systemPrompt = null, ragContext = null,
            tools = listOf(makeToolDefinition("skill__t")),
            mcpServers = listOf(makeServer("fs", true)),
            maxRounds = 10, idGenerator = { 1L }
        ) { events.add(it) }

        // 熔断在第 3 轮触发：第 4 轮请求 tools 已移除该工具 + 注入禁用提示
        assertTrue(
            "熔断后第 4 轮请求 tools 应已移除被禁工具",
            provider.calls[3].tools.isNullOrEmpty()
        )
        assertTrue(
            "熔断后第 4 轮请求应注入禁用提示",
            provider.calls[3].systemPrompt.orEmpty().contains("已被禁用")
        )
        // 幻觉重调**未真实执行**：仅熔断前 3 次真实调用
        assertEquals(
            "被禁文本工具幻觉重调不得真实执行",
            3, mcp.callCount
        )
        // 重调以禁用文案 user 消息回灌（文本路径 results 聚合语义）
        val round5 = provider.calls[4].messages
        val lastUserFeedback = round5.lastOrNull { it.role == Role.USER }
        assertTrue(
            "幻觉重调的禁用文案应并入 user 结果消息回灌",
            lastUserFeedback?.content?.contains("已因连续多次调用失败被禁用") == true
        )
        // 第 5 轮纯文本收尾 + 无 maxRounds 误报
        assertEquals("重调拦截后回路应继续至纯文本收尾（共 5 轮）", 5, provider.calls.size)
        assertTrue(
            "最终应向用户输出纯文本回答",
            events.any { it is StreamEvent.Delta && it.content.contains("直接基于已有信息回答") }
        )
        assertFalse(
            "不应误报 maxRounds Error",
            events.any { it is StreamEvent.Error && it.message.contains("上限") }
        )
    }

    // ==================== Fake 实现与辅助（与既有测试基建同型，独立声明避免跨文件耦合） ====================

    /** 记录每轮请求（messages/tools/systemPrompt）的 ChatStreamProvider fake。 */
    private class RecordingChatProvider(
        private val rounds: List<List<StreamEvent>>
    ) : ChatStreamProvider {
        data class Call(
            val messages: List<ChatMessage>,
            val tools: List<ToolDefinition>?,
            val systemPrompt: String?
        )

        val calls = mutableListOf<Call>()

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
            calls.add(Call(messages.toList(), tools, systemPrompt))
            val idx = calls.size - 1
            val events = rounds.getOrElse(idx) { emptyList() }
            return flow { events.forEach { emit(it) } }
        }
    }

    /** 按物理工具名记录调用并返回可配置结果的 McpToolProvider fake。 */
    private class RecordingMcpProvider(
        private val defaultResult: String,
        private val resultsByName: Map<String, String> = emptyMap()
    ) : McpToolProvider {
        private val callCountByName = mutableMapOf<String, Int>()
        val callCount: Int get() = callCountByName.values.sum()

        fun callCountFor(physicalName: String): Int = callCountByName[physicalName] ?: 0

        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()

        override suspend fun callTool(
            config: McpServerConfig,
            name: String,
            arguments: Map<String, Any?>
        ): String {
            callCountByName[name] = (callCountByName[name] ?: 0) + 1
            return resultsByName[name] ?: defaultResult
        }
    }

    private class FakeGate(private val approve: Boolean) : ToolConfirmationGate {
        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = approve
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
                parameters = kotlinx.serialization.json.JsonObject(emptyMap())
            )
        )
}
