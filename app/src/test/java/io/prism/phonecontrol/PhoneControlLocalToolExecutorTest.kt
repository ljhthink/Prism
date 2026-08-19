package io.prism.phonecontrol

import io.prism.config.HighRiskApprovalMode
import io.prism.skill.AskUserLocalToolExecutor
import io.prism.skill.SkillExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhoneControlLocalToolExecutor] 单元测试（v1 US-201/202/203，纯逻辑部分 JVM 可测）。
 *
 * 覆盖：
 * - [PhoneControlLocalToolExecutor.handles] 命名空间匹配
 * - 参数解析 [PhoneControlLocalToolExecutor.parseInt]/[PhoneControlLocalToolExecutor.parseString]
 * - 工具定义生成 [PhoneControlLocalToolExecutor.buildToolDefinitions]（12 工具齐全 + double_tap）
 * - ask_user 载荷构造 [PhoneControlLocalToolExecutor.askUserJson]（可被
 *   [SkillExecutor.parseAskUserPayload] 解析，保证 take_over/强制 MANUAL 能触发提问卡片）
 * - 不依赖服务的执行路径：take_over / launch_app 缺参 / wait
 *
 * 注：执行类路径依赖 [PhoneControlAccessibilityService.instance]（JVM 下为 null），
 * 由模拟器/真机验证；本测试聚焦纯逻辑。
 */
class PhoneControlLocalToolExecutorTest {

    private val executor = PhoneControlLocalToolExecutor()

    // ==================== handles ====================

    @Test
    fun `handles returns true for phone control namespace`() {
        assertTrue(executor.handles("phone_control__tap"))
        assertTrue(executor.handles("phone_control__get_ui_state"))
        assertTrue(executor.handles("phone_control__take_over"))
    }

    @Test
    fun `handles returns false for other namespaces`() {
        assertFalse(executor.handles("cross_app__open_app"))
        assertFalse(executor.handles("web_search__search"))
        assertFalse(executor.handles("fs__read"))
        assertFalse(executor.handles(""))
    }

    // ==================== 参数解析 ====================

    @Test
    fun `parseInt parses number and numeric string`() {
        assertEquals(42, PhoneControlLocalToolExecutor.parseInt(mapOf("v" to 42), "v", 0))
        assertEquals(42, PhoneControlLocalToolExecutor.parseInt(mapOf("v" to "42"), "v", 0))
        // 非法字符串 + 提供 default → 回退 default（0）
        assertEquals(0, PhoneControlLocalToolExecutor.parseInt(mapOf("v" to "abc"), "v", 0))
        assertEquals(7, PhoneControlLocalToolExecutor.parseInt(mapOf("other" to 1), "v", 7))
    }

    @Test
    fun `parseInt missing returns null when no default`() {
        assertEquals(null, PhoneControlLocalToolExecutor.parseInt(mapOf(), "v"))
    }

    @Test
    fun `parseString parses string and coerces other types`() {
        assertEquals("abc", PhoneControlLocalToolExecutor.parseString(mapOf("v" to "abc"), "v"))
        assertEquals("123", PhoneControlLocalToolExecutor.parseString(mapOf("v" to 123), "v"))
        assertEquals(null, PhoneControlLocalToolExecutor.parseString(mapOf(), "v"))
    }

    // ==================== 工具定义 ====================

    @Test
    fun `buildToolDefinitions contains all 12 tools`() {
        val defs = PhoneControlLocalToolExecutor.buildToolDefinitions()
        val names = defs.map { it.function.name }.toSet()
        assertEquals(12, defs.size)
        val expected = setOf(
            "phone_control__get_ui_state",
            "phone_control__tap",
            "phone_control__long_press",
            "phone_control__double_tap",
            "phone_control__swipe",
            "phone_control__type",
            "phone_control__back",
            "phone_control__home",
            "phone_control__launch_app",
            "phone_control__wait",
            "phone_control__screenshot",
            "phone_control__take_over"
        )
        assertEquals(expected, names)
    }

    @Test
    fun `tool definitions have valid JSON schema parameters`() {
        PhoneControlLocalToolExecutor.buildToolDefinitions().forEach { def ->
            val json = def.function.parameters.toString()
            assertTrue("参数应为合法 JSON: ${def.function.name}", json.startsWith("{"))
        }
    }

    // ==================== ask_user 载荷（take_over / 强制 MANUAL 可解析） ====================

    @Test
    fun `askUserJson is parseable by SkillExecutor`() {
        val json = executor.askUserJson(
            question = "需要人工接管：请输入验证码",
            options = listOf("我已完成" to "人工操作完成后继续", "取消" to "停止自动操作")
        )
        val payload = SkillExecutor.parseAskUserPayload(json)
        assertNotNull(payload)
        assertEquals(1, payload!!.questions.size)
        assertEquals("需要人工接管：请输入验证码", payload.questions[0].question)
        assertEquals("我已完成", payload.questions[0].options[0].label)
        assertFalse(payload.questions[0].multiSelect)
    }

    @Test
    fun `take_over returns ask_user marker and parseable payload`() = runBlocking {
        val result = executor.execute("phone_control__take_over", mapOf("reason" to "登录验证码"))
        assertTrue(result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER))
        val payload = SkillExecutor.parseAskUserPayload(
            result.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER)
        )
        assertNotNull(payload)
        assertTrue(payload!!.questions[0].question.contains("登录验证码"))
        // 强制 MANUAL 选项：允许 / 取消
        val labels = payload.questions[0].options.map { it.label }
        assertTrue(labels.contains("允许"))
        assertTrue(labels.contains("取消"))
    }

    // ==================== 不依赖服务的执行路径 ====================

    @Test
    fun `launch_app missing package returns error`() = runBlocking {
        val result = executor.execute("phone_control__launch_app", emptyMap())
        assertTrue(result.startsWith("错误："))
    }

    @Test
    fun `unknown tool returns error`() = runBlocking {
        val result = executor.execute("phone_control__not_a_tool", emptyMap())
        assertTrue(result.startsWith("错误："))
    }

    @Test
    fun `wait returns confirmation`() = runBlocking {
        val result = executor.execute("phone_control__wait", mapOf("ms" to 10))
        assertEquals("已等待 10ms", result)
    }

    @Test
    fun `wait over max returns error`() = runBlocking {
        val result = executor.execute(
            "phone_control__wait",
            mapOf("ms" to (PhoneControlLocalToolExecutor.MAX_WAIT_MS + 1))
        )
        assertTrue(result.startsWith("错误："))
    }

    @Test
    fun `service-dependent tools degrade gracefully when service not connected`() = runBlocking {
        // JVM 下 PhoneControlAccessibilityService.instance == null → 执行类工具返回引导文案
        val result = executor.execute("phone_control__get_ui_state", emptyMap())
        assertTrue(result.startsWith("错误："))
        assertTrue(result.contains("无障碍服务"))
    }

    // ==================== v1 批次5 高危动作三态策略（ac-verifier P4b，ADR-038） ====================

    /** 高危输入文本：命中"发送"（MANUAL_ACTION_KEYWORDS）但不含凭据词。 */
    private val highRiskInput = mapOf("text" to "发送测试消息给李老师")

    @Test
    fun `high risk type in BLOCK mode returns blocked prefix without executing`() = runBlocking {
        val exec = PhoneControlLocalToolExecutor(
            highRiskApprovalProvider = { HighRiskApprovalMode.BLOCK }
        )
        val result = exec.execute("phone_control__type", highRiskInput)
        assertTrue("BLOCK 应硬拦截（⚠️ 前缀），实际: $result", result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        assertTrue("BLOCK 应提示被设置拦截", result.contains("拦截"))
    }

    @Test
    fun `high risk type in ASK mode returns ask_user marker for confirmation`() = runBlocking {
        val exec = PhoneControlLocalToolExecutor(
            highRiskApprovalProvider = { HighRiskApprovalMode.ASK }
        )
        val result = exec.execute("phone_control__type", highRiskInput)
        assertTrue("ASK 应触发提问（ask_user 标记），实际: $result", result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER))
        val payload = SkillExecutor.parseAskUserPayload(
            result.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER)
        )
        assertNotNull(payload)
        val labels = payload!!.questions[0].options.map { it.label }
        assertTrue(labels.contains("允许"))
        assertTrue(labels.contains("取消"))
    }

    @Test
    fun `high risk type in ALLOW mode attempts execution instead of blocking`() = runBlocking {
        val exec = PhoneControlLocalToolExecutor(
            highRiskApprovalProvider = { HighRiskApprovalMode.ALLOW }
        )
        val result = exec.execute("phone_control__type", highRiskInput)
        // ALLOW 直接放行执行：JVM 下无服务实例 → 走"未开启/重连中"引导文案，而不是被拦截
        assertFalse("ALLOW 不应返回拦截前缀，实际: $result", result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        assertTrue("ALLOW 应尝试执行（服务不可用才引导），实际: $result", result.contains("无障碍服务"))
    }

    @Test
    fun `non high risk type in default ASK executes without confirmation`() = runBlocking {
        val exec = PhoneControlLocalToolExecutor(
            highRiskApprovalProvider = { HighRiskApprovalMode.ASK }
        )
        // 普通输入（不含高危关键词）不应触发提问，走服务执行路径
        val result = exec.execute("phone_control__type", mapOf("text" to "输入普通内容"))
        assertFalse(result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER))
        assertFalse(result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
    }
}
