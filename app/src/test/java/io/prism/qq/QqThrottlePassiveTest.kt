package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqThrottle.decidePassive] 单测（2026-10-26 真机修复）。
 *
 * **真机缺陷**：18:53 回过一次 → 19:09 用户再发消息时被主动节流以
 * "未达最小间隔（还需约 14 分钟）"拒绝，消息被**直接丢弃**，用户看到"又不回了"。
 * 主动节流（minInterval/dailyCap/maxPerDay）的语义是"不要主动打扰"，不能约束
 * "用户说话 → 系统回话"。
 */
class QqThrottlePassiveTest {

    private val cfg = QqConfig(minIntervalMs = 30 * 60 * 1000L, dailyCap = 20, maxPerDay = 5)
    private val base = 1_700_000_000_000L

    @Test
    fun `刚刚才回复过也必须允许被动应答（真机 19时09分 缺陷回归锁）`() {
        // 上一轮回复在 1 分钟前 —— 主动节流会拒绝 30 分钟，被动必须放行
        val state = QqRuntimeState(lastRepliedAtMs = base - 60_000L, lastFiredAt = base - 60_000L)
        assertEquals(
            QqThrottle.Decision.Allow,
            QqThrottle.decidePassive(state, base)
        )
        // 对照：主动节流此时应当拒绝（证明两条路径语义确实不同）
        assertTrue(QqThrottle.decide(cfg, state, base) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `主动配额用满也不影响被动应答`() {
        val state = QqRuntimeState(
            firedCount = 99,
            scheduledFiredCount = 99,
            firedDate = QqThrottle.todayKey(base, java.time.ZoneId.systemDefault()),
            lastRepliedAtMs = base - 10 * 60_000L
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decidePassive(state, base))
        assertTrue(QqThrottle.decide(cfg, state, base) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `极短间隔仍拒绝（防同一条消息双发）`() {
        val state = QqRuntimeState(lastRepliedAtMs = base - 1_000L)
        val d = QqThrottle.decidePassive(state, base)
        assertTrue(d is QqThrottle.Decision.Deny)
        assertTrue((d as QqThrottle.Decision.Deny).reason.contains("间隔过短"))
    }

    @Test
    fun `熔断仍然拦截被动应答（防对坏通道反复重试）`() {
        val state = QqRuntimeState(circuitOpen = true, lastRepliedAtMs = 0L)
        val d = QqThrottle.decidePassive(state, base)
        assertTrue(d is QqThrottle.Decision.Deny)
        assertTrue((d as QqThrottle.Decision.Deny).reason.contains("熔断"))
    }

    @Test
    fun `窗口内回复过多时拒绝（防与另一个机器人互相应声）`() {
        val state = QqRuntimeState(
            lastRepliedAtMs = base - 60_000L,
            repliedMessageIds = (1..QqThrottle.PASSIVE_ID_WINDOW).map { "id$it" }
        )
        val d = QqThrottle.decidePassive(state, base)
        assertTrue(d is QqThrottle.Decision.Deny)
        assertTrue((d as QqThrottle.Decision.Deny).reason.contains("回复条数过多"))
    }

    @Test
    fun `首次回复（无历史）直接放行`() {
        assertEquals(
            QqThrottle.Decision.Allow,
            QqThrottle.decidePassive(QqRuntimeState(), base)
        )
    }
}
