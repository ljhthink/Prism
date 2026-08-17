package io.prism.skill

import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.network.ToolDefinition
import io.prism.ui.chat.ConversationViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AskUserLocalToolExecutor 补充测试（UXR8 N2 Phase 2，ADR-030）—— ac-verifier TKN-UXR8-B3-ACCEPTANCE-001。
 *
 * 补盲区：
 * - `multiSelect=true` 解析保留（主 Agent 测试仅覆盖缺省 false）
 * - [ConversationViewModel.buildTools] 注入 `ask_user__ask` 工具定义（executor 非 null 时注入、
 *   null 时不注入，向后兼容）
 */
class AskUserLocalToolExecutorEdgeTest {

    private val executor = AskUserLocalToolExecutor()

    @Test
    fun `parseQuestions preserves multiSelect true`() {
        val args = mapOf<String, Any?>(
            "questions" to listOf(
                mapOf("question" to "选多个？", "options" to listOf(mapOf("label" to "A"), mapOf("label" to "B")), "multiSelect" to true)
            )
        )
        val questions = executor.parseQuestions(args)
        assertNotNull(questions)
        assertEquals(1, questions!!.size)
        assertTrue("multiSelect=true 应保留", questions[0].multiSelect)
    }

    @Test
    fun `parseQuestions defaults multiSelect false when absent or non boolean`() {
        val absent = executor.parseQuestions(mapOf("questions" to listOf(mapOf("question" to "Q?"))))
        assertFalse("缺省 multiSelect 应为 false", absent!![0].multiSelect)
        val nonBoolean = executor.parseQuestions(
            mapOf("questions" to listOf(mapOf("question" to "Q?", "multiSelect" to "yes")))
        )
        assertFalse("非布尔 multiSelect 应回退 false", nonBoolean!![0].multiSelect)
    }

    @Test
    fun `parseQuestions rejects non map option items`() {
        // options 内含非 Map 项（如字符串）→ 该 option 被跳过，不崩溃
        val args = mapOf<String, Any?>(
            "questions" to listOf(
                mapOf(
                    "question" to "Q?",
                    "options" to listOf(mapOf("label" to "A"), "not-a-map", mapOf("label" to "B"))
                )
            )
        )
        val questions = executor.parseQuestions(args)
        assertEquals("非 Map option 应被过滤", 2, questions!![0].options.size)
    }

    @Test
    fun `buildTools injects ask_user__ask when executor provided`() {
        val tools = ConversationViewModel.buildTools(
            enabledSkills = emptyList(),
            askUserExecutor = AskUserLocalToolExecutor()
        )
        val askUserTool = tools.firstOrNull { it.function.name == AskUserLocalToolExecutor.TOOL_ASK }
        assertNotNull("askUserExecutor 非 null 时应注入 ask_user__ask", askUserTool)
        assertTrue("description 应说明澄清用途", askUserTool!!.function.description.contains("澄清"))
    }

    @Test
    fun `buildTools omits ask_user__ask when executor null for backward compat`() {
        val tools = ConversationViewModel.buildTools(enabledSkills = emptyList())
        assertFalse("executor null 时不应注入 ask_user__ask（向后兼容）", tools.any { it.function.name == AskUserLocalToolExecutor.TOOL_ASK })
    }

    @Test
    fun `execute result JSON round trips through SkillExecutor parse`() = runBlocking {
        val result = executor.execute(
            AskUserLocalToolExecutor.TOOL_ASK,
            mapOf("questions" to listOf(mapOf("question" to "Q?", "options" to listOf(mapOf("label" to "A")), "multiSelect" to true)))
        )
        val payload = SkillExecutor.parseAskUserPayload(result.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER))
        assertNotNull(payload)
        assertTrue("multiSelect=true 应随 payload 往返", payload!!.questions[0].multiSelect)
    }

    @Test
    fun `execute unknown tool name degrades safely`() = runBlocking {
        // handles() 返回 false 的工具名不应被 execute 直接执行（防御）；即使被误调也不崩溃
        assertFalse(executor.handles("ask_user__other"))
    }

    @Test
    fun `buildToolDefinition parameters declare questions required`() {
        val def: ToolDefinition = AskUserLocalToolExecutor.buildToolDefinition()
        val params = def.function.parameters as? kotlinx.serialization.json.JsonObject
        assertNotNull(params)
        val required = params!!["required"] as? kotlinx.serialization.json.JsonArray
        assertTrue("questions 必填", required.toString().contains("questions"))
        val properties = params["properties"] as? kotlinx.serialization.json.JsonObject
        assertTrue("应声明 questions 属性", properties!!.containsKey("questions"))
    }
}
