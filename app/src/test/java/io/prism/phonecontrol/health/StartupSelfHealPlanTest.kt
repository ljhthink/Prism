package io.prism.phonecontrol.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StartupSelfHealPlan] 单测（guardrail **R4-04**）。
 *
 * 真机缺陷（2026-10-26 14:29）：启动期自愈在 Shizuku 就绪之前执行 → 以"需要 Shizuku"
 * 跳过且无后续重试点 → 无障碍一直关闭。这里把"等多久、等完做什么、是否无条件补自愈"
 * 钉死，防止再次退化。
 */
class StartupSelfHealPlanTest {

    @Test
    fun `未就绪且未超轮询上限时继续等待`() {
        assertTrue(StartupSelfHealPlan.shouldKeepWaiting(0, shizukuReady = false))
        assertTrue(StartupSelfHealPlan.shouldKeepWaiting(11, shizukuReady = false))
    }

    @Test
    fun `超过上限必须放弃（不能无限等）`() {
        assertFalse(
            StartupSelfHealPlan.shouldKeepWaiting(StartupSelfHealPlan.MAX_POLLS, shizukuReady = false)
        )
        assertFalse(
            StartupSelfHealPlan.shouldKeepWaiting(StartupSelfHealPlan.MAX_POLLS + 5, shizukuReady = false)
        )
    }

    @Test
    fun `已就绪时立即停止等待`() {
        assertFalse(StartupSelfHealPlan.shouldKeepWaiting(0, shizukuReady = true))
    }

    @Test
    fun `等待上限约 60 秒（不无限阻塞启动线程）`() {
        val totalMs = StartupSelfHealPlan.POLL_INTERVAL_MS * StartupSelfHealPlan.MAX_POLLS
        assertTrue("等待上限过大：${totalMs}ms", totalMs <= 60_000L)
        assertTrue("等待上限过小，Shizuku 可能来不及就绪：${totalMs}ms", totalMs >= 30_000L)
    }

    @Test
    fun `Shizuku 就绪时的动作序列：清扫 → 自愈 → 对账`() {
        assertEquals(
            listOf(
                StartupSelfHealPlan.Step.SWEEP_ORPHAN_USER_SERVICES,
                StartupSelfHealPlan.Step.FORCE_ACCESSIBILITY_SELF_HEAL,
                StartupSelfHealPlan.Step.RECONCILE_RESIDUAL_DISPLAY
            ),
            StartupSelfHealPlan.steps(shizukuReadyAfterWait = true)
        )
    }

    @Test
    fun `未就绪时仍必须补一次自愈（这是真机 14时29分 的缺陷根因）`() {
        val steps = StartupSelfHealPlan.steps(shizukuReadyAfterWait = false)
        assertFalse(
            "未就绪时不能清扫孤儿（会失败）",
            StartupSelfHealPlan.Step.SWEEP_ORPHAN_USER_SERVICES in steps
        )
        assertTrue(
            "未就绪时也必须补一次自愈，否则给出不了『需要 Shizuku』的可读原因",
            StartupSelfHealPlan.Step.FORCE_ACCESSIBILITY_SELF_HEAL in steps
        )
        assertTrue(
            "对账与 Shizuku 无关，必须始终执行",
            StartupSelfHealPlan.Step.RECONCILE_RESIDUAL_DISPLAY in steps
        )
    }
}
