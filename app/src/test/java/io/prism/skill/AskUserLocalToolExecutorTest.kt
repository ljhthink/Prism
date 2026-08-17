package io.prism.skill

import io.prism.network.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AskUserLocalToolExecutor 单元测试（UXR8 N2 Phase 2，ADR-030）。
 *
 * 验证反问/澄清工具：
 * - [AskUserLocalToolExecutor.handles] 仅识别 `ask_user__ask`
 * - [AskUserLocalToolExecutor.parseQuestions] 解析/校验 LLM 参数（question/options/multiSelect）
 * - [AskUserLocalToolExecutor.execute] 返回标记前缀 + AskUserPayload JSON
 * - 边界：缺失 questions / 空列表 / 数量超限 / option label 空 / 长度截断
 * - [AskUserLocalToolExecutor.buildToolDefinition] 工具定义结构（含 multiSelect/options）
 */
class AskUserLocalToolExecutorTest {

    private val executor = AskUserLocalToolExecutor()

    @Test
    fun `handles only ask_user__ask`() {
        assertTrue(executor.handles(AskUserLocalToolExecutor.TOOL_ASK))
        assertFalse(executor.handles("ask_user__other"))
        assertFalse(executor.handles("web_search__search"))
        assertFalse(executor.handles(""))
    }

    @Test
    fun `parseQuestions extracts question options and multiSelect`() {
        val args = mapOf<String, Any?>(
            "questions" to listOf(
                mapOf(
                    "question" to " 你更看重哪方面？ ",
                    "options" to listOf(
                        mapOf("label" to " 性能 ", "description" to " 优先响应速度 "),
                        mapOf("label" to "功能", "description" to " 优先功能完整 ")
                    ),
                    "multiSelect" to false
                )
            )
        )
        val questions = executor.parseQuestions(args)
        assertNotNull(questions)
        assertEquals(1, questions!!.size)
        assertEquals("你更看重哪方面？", questions[0].question)
        assertEquals(2, questions[0].options.size)
        assertEquals("性能", questions[0].options[0].label)
        assertEquals("优先响应速度", questions[0].options[0].description)
        assertFalse(questions[0].multiSelect)
    }

    @Test
    fun `parseQuestions returns null when questions key absent or not list`() {
        assertNull(executor.parseQuestions(mapOf("foo" to "bar")))
        assertNull(executor.parseQuestions(mapOf("questions" to "not-a-list")))
    }

    @Test
    fun `parseQuestions skips items with empty question or label`() {
        val args = mapOf<String, Any?>(
            "questions" to listOf(
                mapOf("question" to "  ", "options" to emptyList<Any>()), // 空 question 跳过
                mapOf("question" to "合法问题", "options" to listOf(mapOf("label" to "  "))), // 空 label 的 option 跳过
                mapOf("question" to "第二个合法问题")
            )
        )
        val questions = executor.parseQuestions(args)
        assertNotNull(questions)
        assertEquals("应跳过无效项", 2, questions!!.size)
        assertEquals("合法问题", questions[0].question)
        assertTrue("空 label 的 option 应被过滤", questions[0].options.isEmpty())
    }

    @Test
    fun `parseQuestions preserves question count and caps options`() {
        // 问题数量上限由 execute() 层以「问题数量过多」错误文案拒绝（见下方
        // `execute degrades when question count over limit`），parseQuestions 是
        // 纯解析器，完整保留所有问题；选项数量则在 parseQuestions 内截断。
        val manyQuestions = (1..10).map { mapOf("question" to "Q$it") }
        val manyOptions = (1..20).map { mapOf("label" to "L$it") }
        val args = mapOf<String, Any?>(
            "questions" to manyQuestions + mapOf("question" to "带大量选项", "options" to manyOptions)
        )
        val questions = executor.parseQuestions(args)
        assertNotNull(questions)
        assertEquals("问题数应完整保留（上限由 execute 层拒绝）", 11, questions!!.size)
        val withOptions = questions.firstOrNull { it.options.isNotEmpty() }
        assertTrue(
            "选项数量应截断到上限",
            (withOptions?.options?.size ?: 0) <= AskUserLocalToolExecutor.MAX_OPTIONS_PER_QUESTION
        )
    }

    @Test
    fun `execute returns marker prefix with payload json`() = runBlocking {
        val result = executor.execute(
            AskUserLocalToolExecutor.TOOL_ASK,
            mapOf("questions" to listOf(mapOf("question" to "选 A 还是 B？")))
        )
        assertTrue("结果应以标记前缀开头", result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER))
        val payload = SkillExecutor.parseAskUserPayload(result.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER))
        assertNotNull("标记后应为可解析的 AskUserPayload", payload)
        assertEquals(1, payload!!.questions.size)
        assertEquals("选 A 还是 B？", payload.questions[0].question)
    }

    @Test
    fun `execute degrades on missing or empty questions`() = runBlocking {
        assertTrue(executor.execute(AskUserLocalToolExecutor.TOOL_ASK, mapOf()).contains("缺少必需参数"))
        assertTrue(
            executor.execute(AskUserLocalToolExecutor.TOOL_ASK, mapOf("questions" to emptyList<Any>()))
                .contains("不能为空")
        )
    }

    @Test
    fun `execute degrades when question count over limit`() = runBlocking {
        val many = (1..(AskUserLocalToolExecutor.MAX_QUESTIONS + 1)).map { mapOf("question" to "Q$it") }
        val result = executor.execute(AskUserLocalToolExecutor.TOOL_ASK, mapOf("questions" to many))
        assertTrue("数量超限应降级而非崩溃", result.contains("问题数量过多"))
    }

    @Test
    fun `question and option texts are truncated to limits`() {
        val args = mapOf<String, Any?>(
            "questions" to listOf(
                mapOf(
                    "question" to "x".repeat(1000),
                    "options" to listOf(mapOf("label" to "y".repeat(200), "description" to "z".repeat(300)))
                )
            )
        )
        val questions = executor.parseQuestions(args)!!
        assertEquals("问题应截断到上限", AskUserLocalToolExecutor.QUESTION_MAX_LEN, questions[0].question.length)
        assertEquals("label 应截断到上限", AskUserLocalToolExecutor.OPTION_LABEL_MAX_LEN, questions[0].options[0].label.length)
        assertEquals("description 应截断到上限", AskUserLocalToolExecutor.OPTION_DESC_MAX_LEN, questions[0].options[0].description?.length)
    }

    @Test
    fun `buildToolDefinition declares ask_user__ask with strict schema`() {
        val def: ToolDefinition = AskUserLocalToolExecutor.buildToolDefinition()
        assertEquals(AskUserLocalToolExecutor.TOOL_ASK, def.function.name)
        assertTrue("description 应说明澄清用途", def.function.description.contains("澄清"))
        val params = def.function.parameters as? kotlinx.serialization.json.JsonObject
        assertNotNull("parameters 应为 JsonObject", params)
        val properties = params!!["properties"]
        assertNotNull("应声明 properties", properties)
        assertTrue("应含 questions 参数", (properties as? kotlinx.serialization.json.JsonObject)?.containsKey("questions") == true)
        val required = params["required"] as? kotlinx.serialization.json.JsonArray
        assertNotNull("questions 应必填", required)
        assertTrue(required.toString().contains("questions"))
    }
}
