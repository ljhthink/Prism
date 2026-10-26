package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqThrottle.decidePassive] / [QqThrottle.decideManual] 单测。
 *
 * **真机缺陷 1（2026-10-26 19:09）**：18:53 回过一次 → 19:09 用户再发消息被主动节流以
 * "未达最小间隔（还需约 14 分钟）"拒绝，消息被**直接丢弃**。主动节流
 * （minInterval/dailyCap/maxPerDay）语义是"不要主动打扰"，不能约束"用户说话 → 系统回话"。
 *
 * **真机缺陷 2（2026-10-26 RC-3）**：被动限流原用
 * `repliedMessageIds.size >= PASSIVE_ID_WINDOW`，而该集合被裁剪到恰好 20 条且**永不缩短**
 * → 累计回复 20 条后被动回复被**永久拒绝**（"越用越不回"）。
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
            passiveReplyTimestamps = (0 until QqThrottle.PASSIVE_MAX_IN_WINDOW)
                .map { base - 10_000L }
        )
        val d = QqThrottle.decidePassive(state, base)
        assertTrue(d is QqThrottle.Decision.Deny)
        assertTrue((d as QqThrottle.Decision.Deny).reason.contains("过于频繁"))
    }

    /**
     * **RC-3 回归锁**：去重集合装满 20 条（历史遗留数据）不得再阻塞被动回复。
     *
     * 旧实现用集合长度做限流，此用例必然失败；新实现只按时间窗口判定。
     */
    @Test
    fun `去重集合装满也不得永久拒绝被动应答（RC-3 回归锁）`() {
        val state = QqRuntimeState(
            lastRepliedAtMs = base - 60_000L,
            repliedMessageIds = (1..QqRuntimeState.MAX_REPLIED_IDS).map { "id$it" },
            passiveReplyTimestamps = emptyList()
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decidePassive(state, base))
    }

    @Test
    fun `连续 25 条消息不会因累计条数被永久卡死（RC-3 端到端回归）`() {
        var lastRepliedAt = 0L
        val timestamps = mutableListOf<Long>()
        var allowed = 0
        for (i in 0 until 25) {
            // 每条间隔 5s 且分属不同窗口轮次：60s 窗口内至多 20 条，之后随窗口滑动恢复
            val now = base + i * 5_000L
            val state = QqRuntimeState(
                lastRepliedAtMs = lastRepliedAt,
                repliedMessageIds = (1..20).map { "old$it" },
                passiveReplyTimestamps = timestamps.toList()
            )
            if (QqThrottle.decidePassive(state, now) is QqThrottle.Decision.Allow) {
                allowed++
                lastRepliedAt = now
                timestamps += now
            }
        }
        // 旧实现：第 21 条起永久拒绝（集合长度恒为 20）。新实现：持续有回复被放行。
        assertTrue("allowed=$allowed", allowed > QqThrottle.PASSIVE_MAX_IN_WINDOW)
        // 窗口滑过（+61s）后必须再次放行——证明限流不是永久状态
        val later = base + 25 * 5_000L + 61_000L
        val after = QqRuntimeState(
            lastRepliedAtMs = lastRepliedAt,
            repliedMessageIds = (1..20).map { "old$it" },
            passiveReplyTimestamps = timestamps.toList()
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decidePassive(after, later))
    }

    @Test
    fun `首次回复（无历史）直接放行`() {
        assertEquals(
            QqThrottle.Decision.Allow,
            QqThrottle.decidePassive(QqRuntimeState(), base)
        )
    }

    @Test
    fun `手动发送不受主动配额约束（真机 send 被拦回归）`() {
        val state = QqRuntimeState(
            firedCount = 99,
            scheduledFiredCount = 99,
            firedDate = QqThrottle.todayKey(base, java.time.ZoneId.systemDefault()),
            lastFiredAt = base - 10 * 60_000L
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decideManual(state, base))
        assertTrue(QqThrottle.decide(cfg, state, base) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `手动发送仍受熔断与短间隔约束`() {
        assertTrue(
            QqThrottle.decideManual(QqRuntimeState(circuitOpen = true), base)
                is QqThrottle.Decision.Deny
        )
        assertTrue(
            QqThrottle.decideManual(QqRuntimeState(lastFiredAt = base - 1_000L), base)
                is QqThrottle.Decision.Deny
        )
    }

    @Test
    fun `每日上限 0 表示不限制`() {
        val unlimited = QqConfig(dailyCap = 0, maxPerDay = 0, minIntervalMs = 60_000L)
        val state = QqRuntimeState(
            firedDate = QqThrottle.todayKey(base, java.time.ZoneId.systemDefault()),
            firedCount = 999,
            scheduledFiredCount = 999,
            lastFiredAt = base - 10 * 60_000L
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(unlimited, state, base))
    }
}
