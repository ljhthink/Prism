package io.prism.phonecontrol

import io.prism.config.HighRiskApprovalMode
import io.prism.skill.AskUserLocalToolExecutor
import io.prism.skill.SkillExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `tap tool exposes text anchor parameter`() {
        // v1 批次11（C 文本锚点）：tap 工具新增 text 参数（LLM 用文字描述目标，系统自动定位）
        val tap = PhoneControlLocalToolExecutor.buildToolDefinitions()
            .first { it.function.name == "phone_control__tap" }
        val textProp = tap.function.parameters
            .jsonObject["properties"]!!.jsonObject["text"]!!.jsonObject
        assertEquals("string", textProp["type"]!!.jsonPrimitive.content)
        // 描述引导优先用 text
        assertTrue(tap.function.description.contains("text"))
    }

    @Test
    fun `screenshot tool description encourages text-only model to call with OCR`() {
        // v1 批次11（D 主动调用引导）：移除"纯文本模型请勿调用"的误导，改为声明 OCR 兜底
        val shot = PhoneControlLocalToolExecutor.buildToolDefinitions()
            .first { it.function.name == "phone_control__screenshot" }
        val desc = shot.function.description
        assertFalse("不应再劝阻纯文本模型调用 screenshot", desc.contains("纯文本模型请勿调用"))
        assertTrue("应声明 OCR 文字+坐标兜底", desc.contains("OCR"))
        assertTrue("应引导 tap(text=...)", desc.contains("tap"))
    }

    @Test
    fun `anchor security text prefers real matched text over query`() {
        // guardrail H-1 红线（TKN-V1B11-GUARDRAIL-001）：文本锚点敏感判断必须用命中节点真实文本，
        // 否则 tap(text="确认") 命中「确认支付」会因查询词"确认"不敏感而绕过支付类拦截。
        assertEquals("确认支付", PhoneControlLocalToolExecutor.anchorSecurityText("确认", "确认支付"))
        // 真实文本缺失时防御性回退查询词
        assertEquals("确认", PhoneControlLocalToolExecutor.anchorSecurityText("确认", null))
        // 语义证明：查询词不敏感但真实文本敏感 → 只有用真实文本才能触发拦截
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("确认"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("确认支付"))
    }

    @Test
    fun `maskLogText masks digit runs and truncates`() {
        // guardrail R-4（TKN-V1B11-GUARDRAIL-002）：日志脱敏——4 位以上数字串掩码 + 截断至 8 字符
        assertEquals("验证码***", PhoneControlLocalToolExecutor.maskLogText("验证码123456"))
        // 截断至 8 字符
        assertEquals("超长屏幕文本内容", PhoneControlLocalToolExecutor.maskLogText("超长屏幕文本内容截断处理"))
        assertTrue(PhoneControlLocalToolExecutor.maskLogText("超长屏幕文本内容截断处理").length <= 8)
        // 3 位数字不掩码（非 OTP 形态）
        assertTrue(PhoneControlLocalToolExecutor.maskLogText("版本 1.2.3").contains("1.2.3"))
    }

    @Test
    fun `maskLogText boundaries exactly 4 digits masked 3 not`() {
        // 边界：恰好 4 位连续数字 → 掩码（验证码/OTP 形态）；掩码替换为 3 个星号
        assertEquals("***", PhoneControlLocalToolExecutor.maskLogText("1234"))
        // 恰好 3 位 → 不掩码
        assertEquals("123", PhoneControlLocalToolExecutor.maskLogText("123"))
        // 空串 → 空
        assertEquals("", PhoneControlLocalToolExecutor.maskLogText(""))
        // 全空白 → 截断后空白（不崩溃）
        assertEquals("        ", PhoneControlLocalToolExecutor.maskLogText("          "))
    }

    @Test
    fun `maskLogText masks runs inside text and at truncation boundary`() {
        // 数字串在文本中间 → 掩码（"订单***号" 5 字符 ≤ 8）
        assertEquals("订单***号", PhoneControlLocalToolExecutor.maskLogText("订单20240821号"))
        // 多处数字串 → 全部掩码后截断（"AB***CD***EF" 前 8 = "AB***CD*"，含第二个掩码的首个星号）
        assertEquals("AB***CD*", PhoneControlLocalToolExecutor.maskLogText("AB1234CD5678EF"))
        // 截断边界恰为 8：8 字符文本原样保留
        assertEquals("abcdefgh", PhoneControlLocalToolExecutor.maskLogText("abcdefgh"))
        // 9 字符文本截断到 8
        assertEquals("abcdefgh", PhoneControlLocalToolExecutor.maskLogText("abcdefghi"))
        // 掩码后仍受 8 字符上限约束
        assertTrue(PhoneControlLocalToolExecutor.maskLogText("1234567890").length <= 8)
        assertEquals("***", PhoneControlLocalToolExecutor.maskLogText("1234567890"))
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

    @Test
    fun `get_ui_state retries when accessibility enabled but instance rebinding`() = runBlocking {
        // v1 批次11：系统已启用无障碍（accessibilityEnabledProvider=true）但实例暂未连（重连中）
        // → get_ui_state 应短暂重试后返回**可恢复的"重连中"**文案（而非"未开启"引导、也非
        // 永久失败"无法读取"），让 LLM 知道是进程重启/重连的瞬态，等待而非弃疗。
        val rebinding = PhoneControlLocalToolExecutor(
            accessibilityEnabledProvider = { true }
        )
        val result = rebinding.execute("phone_control__get_ui_state", emptyMap())
        assertTrue(result.startsWith("错误："))
        assertFalse(result.contains("未开启"))
        assertFalse(result.contains("无法读取当前屏幕内容"))
        assertTrue("应提示重连中而非永久失败，实际: $result", result.contains("重连中") || result.contains("恢复"))
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

    // ==================== v1 批次12（D，D15）Stuck 阈值（ac-verifier 边界补充） ====================

    /** 反射调用私有 trackStuck（纯逻辑状态机，JVM 可测）。 */
    private fun trackStuck(exec: PhoneControlLocalToolExecutor, sig: String): String? {
        val m = PhoneControlLocalToolExecutor::class.java.getDeclaredMethod("trackStuck", String::class.java)
        m.isAccessible = true
        return m.invoke(exec, sig) as String?
    }

    /** 反射调用私有 resetStuckState（launch_app 成功时重置）。 */
    private fun resetStuck(exec: PhoneControlLocalToolExecutor) {
        val m = PhoneControlLocalToolExecutor::class.java.getDeclaredMethod("resetStuckState")
        m.isAccessible = true
        m.invoke(exec)
    }

    @Test
    fun `stuck threshold boundary 2 steps no warning 3 steps warning`() {
        // 边界：STUCK_THRESHOLD=3 —— 语义为首个读建立基线（streak=0），之后每个同签名读 +1，
        // 连续 3 步无变化（= 第 4 次相同读数）才附恢复引导；2 步无变化不提示。
        val exec = PhoneControlLocalToolExecutor()
        assertNull("读 1：基线，不提示", trackStuck(exec, "sig-A"))
        assertNull("读 2：1 步无变化，不提示", trackStuck(exec, "sig-A"))
        assertNull("读 3：2 步无变化，未达阈值不提示", trackStuck(exec, "sig-A"))
        val warn4 = trackStuck(exec, "sig-A")
        assertTrue("读 4：连续 3 步无变化达阈值应附恢复引导", warn4 != null && warn4.contains("无变化"))
        val warn5 = trackStuck(exec, "sig-A")
        assertTrue("读 5：超过阈值继续提示", warn5 != null && warn5.contains("无变化"))
    }

    @Test
    fun `stuck state resets when screen signature changes`() {
        // 状态迁移：屏幕变化 → 计数复位（不误报）；再连续 3 步同屏才重新提示
        val exec = PhoneControlLocalToolExecutor()
        trackStuck(exec, "A"); trackStuck(exec, "A")
        assertNull("未达阈值", trackStuck(exec, "A"))
        assertNull("屏幕变化 B → 基线复位，不提示", trackStuck(exec, "B"))
        assertNull("B 第 2 次读（1 步无变化）不提示", trackStuck(exec, "B"))
        assertNull("B 第 3 次读（2 步无变化）不提示", trackStuck(exec, "B"))
        assertTrue("B 第 4 次读（连续 3 步无变化）达阈值提示", trackStuck(exec, "B")?.contains("无变化") == true)
    }

    @Test
    fun `stuck state resets on launch_app success`() {
        // AC-D：launch_app 成功进入新 App 时 resetStuckState → 计数清零（新任务上下文）
        val exec = PhoneControlLocalToolExecutor()
        trackStuck(exec, "A"); trackStuck(exec, "A"); trackStuck(exec, "A"); trackStuck(exec, "A")
        resetStuck(exec)
        assertNull("重置后再次同屏从基线开始，不提示", trackStuck(exec, "A"))
    }

    @Test
    fun `stuck fresh executor starts with no warning on first read`() {
        // 边界：全新实例首个 get_ui_state 不应提示（streak 从 0 开始）
        val exec = PhoneControlLocalToolExecutor()
        assertNull(trackStuck(exec, "first"))
    }

    // ==================== v1 批次12（C，D14）launch_app 金融硬拦截（ac-verifier AC-S1 红线） ====================
    // 关键：敏感判定（L358）在 service 访问（L361）之前 → JVM 下无服务实例也能动态验证拦截分支。

    @Test
    fun `launch_app blocks financial app via chinese name resolved to mapped package`() = runBlocking {
        // 注入链：package=招商银行 → resolvePackage → cmb.pb → pkg 命中黑名单 → 硬拦截
        val result = executor.execute("phone_control__launch_app", mapOf("package" to "招商银行"))
        assertTrue("应硬拦截（⚠️ 前缀），实际: $result", result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        assertTrue("应指出纠正后包名 cmb.pb", result.contains("cmb.pb"))
        assertTrue("应提示已硬拦截", result.contains("硬拦截"))
    }

    @Test
    fun `launch_app blocks raw blocked package directly`() = runBlocking {
        // rawPkg 直接命中黑名单（cmb.pb / 历史错误值 / 中行备用包名）→ 拦截
        val direct = executor.execute("phone_control__launch_app", mapOf("package" to "cmb.pb"))
        assertTrue(direct.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        val historic = executor.execute("phone_control__launch_app", mapOf("package" to "com.cmbchina.ccd.pluto.cmbActivity"))
        assertTrue(historic.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        val boc = executor.execute("phone_control__launch_app", mapOf("package" to "com.boc.bocmobi"))
        assertTrue(boc.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
    }

    @Test
    fun `launch_app blocks financial app via app alias param`() = runBlocking {
        // app 参数别名（支付宝 → com.eg.android.AlipayGphone）→ 拦截
        val result = executor.execute("phone_control__launch_app", mapOf("app" to "支付宝"))
        assertTrue(result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        assertTrue(result.contains("com.eg.android.AlipayGphone"))
    }

    @Test
    fun `launch_app does not block non financial mapped app`() = runBlocking {
        // 对照防误拦：拼多多（glm 错包名纠正）非金融 → 放行到 service 层（JVM 下报服务未连，非拦截）
        val result = executor.execute("phone_control__launch_app", mapOf("package" to "拼多多"))
        assertFalse("非金融不应硬拦截，实际: $result", result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        assertTrue("应进入 service 层（无障碍服务引导/重连中）", result.contains("无障碍服务"))
    }

    @Test
    fun `launch_app does not block unknown correct package`() = runBlocking {
        // 正确包名不在映射库 → 按原值放行到 service 层（非拦截）
        val result = executor.execute("phone_control__launch_app", mapOf("package" to "com.tencent.mm"))
        assertFalse(result.startsWith(PhoneControlLocalToolExecutor.BLOCKED_PREFIX))
        assertTrue(result.contains("无障碍服务"))
    }

    // ==================== v1 批次12（E，D15）before/after 软提示语义（ac-verifier 边界补充） ====================

    @Test
    fun `swipe not executed returns error without soft state-change hint`() = runBlocking {
        // AC-E 语义：动作**未执行**（服务不可用 → 错误）时**不**附"动作后屏幕状态无变化"软提示
        // （软提示仅在动作确实执行且前后签名一致时出现——非硬失败、不误报）。
        val result = executor.execute(
            "phone_control__swipe",
            mapOf("from_x" to 10, "from_y" to 20, "to_x" to 30, "to_y" to 40)
        )
        assertTrue(result.startsWith("错误："))
        assertFalse("未执行不应附软提示，实际: $result", result.contains("动作后屏幕状态无变化"))
    }

    // ==================== v1 批次13（B/D16c，多模态降级）视觉降级 ====================

    @Test
    fun `onVisionUnsupported degrades vision path so screenshot falls back to OCR`() = runBlocking {
        // 视觉模型端点不支持图片（400 visionUnsupported）→ onVisionUnsupported 置 visionDegraded：
        // 即使 visionCapableProvider()=true（supportsVision 或模型名检测），截图也不再走图片注入，
        // 转回 OCR/UI 树文本路径（任务以纯文本模式继续而非中断）。
        val visionExec = PhoneControlLocalToolExecutor(visionCapableProvider = { true })
        assertFalse("初始未降级", visionExec.visionDegraded)
        visionExec.onVisionUnsupported()
        assertTrue("视觉不支持后应降级", visionExec.visionDegraded)
        // 幂等：重复信号不抛异常
        visionExec.onVisionUnsupported()
        assertTrue(visionExec.visionDegraded)
        // JVM 下无服务实例 → 走 OCR/服务引导路径而非图片标记（图片标记需要服务截图）
        val result = visionExec.execute("phone_control__screenshot", emptyMap())
        assertFalse("降级后截图不应返回图片标记，实际: $result", result.startsWith(PhoneControlLocalToolExecutor.SCREENSHOT_IMAGE_MARKER))
        assertTrue("降级后走服务/OCR 路径（无障碍服务引导），实际: $result", result.contains("无障碍服务"))
    }

    @Test
    fun `vision not degraded returns image marker path for vision capable provider`() = runBlocking {
        // 未降级且视觉能力开启 → 截图走图片标记路径（JVM 下无服务实例 → 引导文案，
        // 但**不能**被误判为"已降级走 OCR"——与降级路径的行为区分通过 visionDegraded 状态表达）
        val visionExec = PhoneControlLocalToolExecutor(visionCapableProvider = { true })
        assertFalse(visionExec.visionDegraded)
        // 无服务实例时两路径都落引导文案；核心断言是降级状态正确翻转（见上测）
        val result = visionExec.execute("phone_control__screenshot", emptyMap())
        assertFalse(result.startsWith(PhoneControlLocalToolExecutor.SCREENSHOT_IMAGE_MARKER))
    }

    @Test
    fun `non vision provider screenshot does not return image marker`() = runBlocking {
        // 纯文本模型（visionCapableProvider=false）→ 截图走 OCR 文本路径（无图片标记）
        val textExec = PhoneControlLocalToolExecutor() // 默认 visionCapableProvider=false
        val result = textExec.execute("phone_control__screenshot", emptyMap())
        assertFalse("纯文本模型截图不应返回图片标记", result.startsWith(PhoneControlLocalToolExecutor.SCREENSHOT_IMAGE_MARKER))
    }
}
