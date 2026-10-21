package io.prism.qq

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.prism.config.HighRiskApprovalMode
import io.prism.skill.AskUserLocalToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [QqLocalToolExecutor] 闸门编排单测（JVM：DataStore 经 `PreferenceDataStoreFactory`
 * 落临时文件，范式同 PetDescriptorCodecTest.PetConfigRepositoryTest）。
 *
 * **为什么补这批用例**（guardrail r2 R2-01 遗留缺口，ac-verifier 2026-10-19）：
 * H-01 修复"create 必须过三态确认"此前无任何自动化覆盖——根因是
 * QqConfigRepository 为 final class 且此前测试未构造真实 DataStore。
 * 本类以真实仓库 + 注入确认策略锁定：
 * 1. create 三态（BLOCK 拒绝且零写入 / ASK 询问且零写入 / ALLOW 放行并写入）；
 * 2. create 前置闸（总开关 + 风险确认，US-3101）与参数校验顺序（US-3108 AC-1）；
 * 3. 定时路径凭据硬拦截（B-01：sendProactive 无绕过面）；
 * 4. 交互路径节流/熔断编排（US-3105 AC-1/AC-3 对 send_message 生效）；
 * 5. cancel/status 动作（US-3108）。
 */
class QqLocalToolExecutorGateTest {

    private lateinit var dir: File

    private fun newRepos(): Pair<QqConfigRepository, QqRuntimeStateRepository> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cfg = QqConfigRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "qq-cfg-${System.nanoTime()}.preferences_pb")
            }
        )
        val rt = QqRuntimeStateRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "qq-rt-${System.nanoTime()}.preferences_pb")
            }
        )
        return cfg to rt
    }

    private fun executor(
        cfg: QqConfigRepository?,
        rt: QqRuntimeStateRepository?,
        mode: HighRiskApprovalMode
    ): QqLocalToolExecutor = QqLocalToolExecutor(
        configRepoProvider = { cfg },
        runtimeRepoProvider = { rt },
        highRiskApprovalProvider = { mode }
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("prism-qq-gate").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ==================== H-01：create 三态（R2-01 闭合） ====================

    @Test
    fun `create 在 BLOCK 模式被拒且零写入（guardrail H-01）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        val e = executor(cfg, rt, HighRiskApprovalMode.BLOCK)
        val r = e.execute(
            "qq__schedule_message",
            mapOf("action" to "create", "target_contact" to "小主", "window_start_min" to 1200, "window_end_min" to 1380)
        )
        assertTrue("BLOCK 应拒绝：$r", r.contains("已被") && r.contains("阻止"))
        assertEquals("拒绝时不得写入定时开关", false, cfg.get().scheduleEnabled)
        assertEquals("拒绝时不得写入目标联系人", "", cfg.get().targetContact)
    }

    @Test
    fun `create 在 ASK 模式返回 ask_user 且零写入（guardrail H-01）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        val e = executor(cfg, rt, HighRiskApprovalMode.ASK)
        val r = e.execute(
            "qq__schedule_message",
            mapOf("action" to "create", "target_contact" to "小主")
        )
        assertTrue("ASK 应返回 ask_user 标记：$r", r.startsWith(AskUserLocalToolExecutor.RESULT_MARKER))
        val parsed = io.prism.skill.SkillExecutor.parseAskUserPayload(
            r.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER)
        )
        assertTrue("ASK 载荷可解析", parsed != null && parsed.questions.isNotEmpty())
        assertTrue("确认文案要求核对小号（M-02）", parsed!!.questions.first().question.contains("小号"))
        assertEquals("询问期间不得写入定时开关", false, cfg.get().scheduleEnabled)
    }

    @Test
    fun `create 在 ALLOW 模式放行并写入配置（H-01 放行路径）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW)
        val r = e.execute(
            "qq__schedule_message",
            mapOf(
                "action" to "create", "target_contact" to "小主",
                "window_start_min" to 1200, "window_end_min" to 1380,
                "max_per_day" to 5, "use_poisson" to "true"
            )
        )
        assertTrue("ALLOW 应创建成功：$r", r.contains("已创建定时主动消息"))
        val c = cfg.get()
        assertTrue(c.scheduleEnabled)
        assertEquals("小主", c.targetContact)
        assertEquals(1200, c.windowStartMinute)
        assertEquals(1380, c.windowEndMinute)
        assertEquals(5, c.maxPerDay)
        assertTrue(c.usePoisson)
    }

    // ==================== create 前置闸与参数校验（US-3101 / US-3108 AC-1） ====================

    @Test
    fun `create 在总开关或风险确认缺失时拒绝（US-3101 前置闸）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW)
        val r1 = e.execute("qq__schedule_message", mapOf("action" to "create", "target_contact" to "小主"))
        assertTrue("开关未开应拒绝：$r1", r1.contains("未开启或未完成风险确认"))
        cfg.setEnabled(true)
        val r2 = e.execute("qq__schedule_message", mapOf("action" to "create", "target_contact" to "小主"))
        assertTrue("风险未确认应拒绝：$r2", r2.contains("未开启或未完成风险确认"))
        assertEquals(false, cfg.get().scheduleEnabled)
    }

    @Test
    fun `create 参数校验 - 时间窗非法与缺 target_contact（US-3108 AC-1）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW)
        val badWindow = e.execute(
            "qq__schedule_message",
            mapOf("action" to "create", "target_contact" to "小主", "window_start_min" to 1380, "window_end_min" to 1200)
        )
        assertTrue("start>=end 应拒绝：$badWindow", badWindow.contains("时间窗非法"))
        val noContact = e.execute("qq__schedule_message", mapOf("action" to "create"))
        assertTrue("缺 target_contact 应拒绝：$noContact", noContact.contains("target_contact"))
        assertEquals(false, cfg.get().scheduleEnabled)
    }

    // ==================== 定时路径凭据拦截（B-01 无绕过面） ====================

    @Test
    fun `sendProactive 定时路径同样硬拦截凭据（B-01 无绕过面）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW) // 放行策略也拦不住凭据
        val r = e.sendProactive("小主", "你的验证码是881234，记一下")
        assertTrue("定时路径凭据必须硬拦截：$r", r.startsWith("⚠️"))
    }

    // ==================== 节流/熔断编排（US-3105 AC-1/AC-3，send 路径生效） ====================

    @Test
    fun `send_message 受熔断闸拒绝（US-3105 AC-3 编排生效）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        // 真实熔断链路：连续失败 3 次达阈值 → 熔断
        repeat(QqThrottle.FAILURE_STREAK_THRESHOLD) { rt.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD) }
        assertTrue(rt.get().circuitOpen)
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW)
        val r = e.execute("qq__send_message", mapOf("contact" to "测试", "message" to "晚上好呀"))
        assertTrue("熔断态发送应被节流闸拒绝：$r", r.contains("熔断"))
    }

    @Test
    fun `send_message 受每日上限闸拒绝（US-3105 AC-1 编排生效）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        val today = QqThrottle.todayKey(System.currentTimeMillis(), java.time.ZoneId.systemDefault())
        // 用"1 小时前"记录，绕过最小间隔判定（30min）直达上限分支
        val anHourAgo = System.currentTimeMillis() - 60L * 60_000
        repeat(20) { rt.recordFired(anHourAgo, today) } // dailyCap 默认 20
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW)
        val r = e.execute("qq__send_message", mapOf("contact" to "测试", "message" to "在忙吗"))
        assertTrue("达上限应拒绝：$r", r.contains("上限"))
    }

    // ==================== cancel / status（US-3108） ====================

    @Test
    fun `cancel 关闭定时开关且 status 汇报状态（US-3108）`() = runBlocking<Unit> {
        val (cfg, rt) = newRepos()
        cfg.setEnabled(true)
        cfg.setRiskAccepted(true)
        cfg.setScheduleEnabled(true)
        cfg.setTargetContact("小主")
        val e = executor(cfg, rt, HighRiskApprovalMode.ALLOW)
        val canceled = e.execute("qq__schedule_message", mapOf("action" to "cancel"))
        assertTrue(canceled.contains("已取消"))
        assertFalse("cancel 后定时开关必须关闭", cfg.get().scheduleEnabled)
        val status = e.execute("qq__schedule_message", emptyMap()) // action 缺省 → status
        assertTrue("status 应输出状态摘要：$status", status.contains("定时主动消息状态"))
        assertTrue(status.contains("总开关=true"))
    }

    @Test
    fun `双向对话开关落盘与读回（US-3109 仓库往返）`() = runBlocking<Unit> {
        val (cfg, _) = newRepos()
        assertFalse("双向对话默认必须关闭", cfg.get().autoReplyEnabled)
        cfg.setAutoReplyEnabled(true)
        assertTrue("开启后应读回 true", cfg.get().autoReplyEnabled)
        cfg.setAutoReplyEnabled(false)
        assertFalse(cfg.get().autoReplyEnabled)
    }

    @Test
    fun `配置仓库未装配时 schedule 动作 fail-fast（fail-fast 契约）`() = runBlocking<Unit> {
        val e = executor(null, null, HighRiskApprovalMode.ALLOW)
        val r = e.execute("qq__schedule_message", mapOf("action" to "cancel"))
        assertTrue("仓库未装配应可读拒绝：$r", r.contains("未装配"))
    }

    // ==================== 双向对话运行时状态（US-3109 AC-3/AC-4，guardrail M-06 补盲） ====================

    @Test
    fun `recordReplied 不消耗定时配额但计入当日总数（US-3109 AC-4、guardrail M-06）`() = runBlocking<Unit> {
        val (_, rt) = newRepos()
        val now = System.currentTimeMillis()
        val today = QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
        // 一次响应式回复
        rt.recordReplied(now, today)
        val s1 = rt.get()
        assertEquals("回复应计入当日总数（每日上限仍生效）", 1, s1.firedCount)
        assertEquals("回复不得消耗定时主动消息配额 maxPerDay", 0, s1.scheduledFiredCount)
        // 对照：一次定时发送，两者皆计
        rt.recordFired(now, today)
        val s2 = rt.get()
        assertEquals(2, s2.firedCount)
        assertEquals("定时发送才消耗定时配额", 1, s2.scheduledFiredCount)
    }

    @Test
    fun `setLastReplied 指纹与时刻落盘并读回（US-3109 AC-3 去重状态持久化）`() = runBlocking<Unit> {
        val (_, rt) = newRepos()
        val h = "收到消息正文".hashCode().toString()
        rt.setLastReplied(h, 123456789L)
        val s = rt.get()
        assertEquals("去重指纹应持久化", h, s.lastRepliedMessageHash)
        assertEquals("去重时刻应持久化", 123456789L, s.lastRepliedAtMs)
    }

    @Test
    fun `recordReplied 清除失败连续计数（回复成功不得残留熔断风险）`() = runBlocking<Unit> {
        val (_, rt) = newRepos()
        repeat(QqThrottle.FAILURE_STREAK_THRESHOLD - 1) { rt.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD) }
        assertEquals(QqThrottle.FAILURE_STREAK_THRESHOLD - 1, rt.get().failureStreak)
        val now = System.currentTimeMillis()
        rt.recordReplied(now, QqThrottle.todayKey(now, java.time.ZoneId.systemDefault()))
        assertEquals("成功回复应清零连续失败计数", 0, rt.get().failureStreak)
    }
}
