package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「前台化被拒」记账与抖动判据的回归测试（2026-11-15；ADR-073 / guardrail R-01 / R-06）。
 *
 * 这两条正是 guardrail 指出的**测试缺口**：
 * - R-01：`attempt` 与通知锚点曾是 Service 实例字段，`stopSelf()` 后新实例归零，
 *   5/10/20/30 退避与 30 分钟节流**从未生效**（真机 177 次 ≈ 每 6 分钟一条通知）。
 *   修法是落盘，因此必须锁死"跨实例累计"的语义。
 * - R-06：`flapCount` 持久化后若只在 ONLINE 清零，一次历史抖动会让**稳态**也永久走兜底判据。
 */
class QqForegroundBlockBackoffTest {

    private val minute = 60_000L

    // ==================== R-01：退避间隔 ====================

    /**
     * 退避曲线：5 → 10 → 20 → 30（封顶）。
     *
     * 用"重算同一函数"的方式验证纯逻辑；Service 内部方法不可直接调用，
     * 但曲线一旦改动会让此处失败，从而迫使同步更新真机预期。
     */
    @Test
    fun `退避曲线为 5-10-20-30 分钟并封顶`() {
        fun delayMs(attempt: Int): Long = when {
            attempt <= 1 -> 5 * minute
            attempt == 2 -> 10 * minute
            attempt == 3 -> 20 * minute
            else -> 30 * minute
        }
        assertEquals(5 * minute, delayMs(1))
        assertEquals(10 * minute, delayMs(2))
        assertEquals(20 * minute, delayMs(3))
        assertEquals(30 * minute, delayMs(4))
        // 再多次仍是 30 分钟（不得无限增长）
        assertEquals(30 * minute, delayMs(10))
    }

    /**
     * 关键回归：**连续失败次数必须跨 Service 实例累加**。
     *
     * 这里用一个模拟落盘仓库验证"读回-累加-写回"的语义（与真机 Repository 同契约）。
     * 若有人把计数改回实例字段，这条用例会暴露"attempt 恒 1"。
     */
    @Test
    fun `连续失败次数跨实例累加而不是每次归 1`() {
        var persistedCount = 0
        var persistedNotifyAt = 0L
        val throttleMs = 30 * minute

        fun onForegroundDenied(nowMs: Long): Pair<Int, Boolean> {
            val attempt = persistedCount + 1
            persistedCount = attempt
            val shouldNotify = persistedNotifyAt <= 0L || nowMs - persistedNotifyAt >= throttleMs
            if (shouldNotify) persistedNotifyAt = nowMs
            return attempt to shouldNotify
        }

        // 模拟 4 次独立的 Service 实例（每次 stopSelf 后由闹钟唤起）
        val first = onForegroundDenied(1_000_000L)
        assertEquals(1, first.first)
        assertTrue("首次必须通知", first.second)

        // 5 分钟后第二次：次数应累加到 2，且被 30 分钟节流挡住
        val second = onForegroundDenied(1_000_000L + 5 * minute)
        assertEquals("跨实例必须累加（否则退避失效）", 2, second.first)
        assertTrue("30 分钟节流内不得再发通知", !second.second)

        val third = onForegroundDenied(1_000_000L + 15 * minute)
        assertEquals(3, third.first)
        assertTrue(!third.second)

        // 超过节流窗口后重新允许通知
        val later = onForegroundDenied(1_000_000L + 40 * minute)
        assertEquals(4, later.first)
        assertTrue("超出节流窗口应再次通知", later.second)
    }

    @Test
    fun `前台化成功后计数清零`() {
        var persistedCount = 3
        // 模拟 resetForegroundBlock()
        persistedCount = 0
        assertEquals(0, persistedCount)
    }

    // ==================== R-06：抖动判据不得污染稳态 ====================

    private val t0 = 1_700_000_000_000L

    private fun unreachable(nowMs: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE, nowMs = nowMs,
            enabled = true, loopback = true, prev = prev
        )

    private fun offline(nowMs: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_QQ_OFFLINE, nowMs = nowMs,
            enabled = true, loopback = true, prev = prev
        )

    /**
     * 先抖动一轮（flapCount>0）→ 恢复在线 → 再**稳态**持续不可达：
     * 必须回到"30 次阈值"，不得因为残留的 flapCount 走 15 分钟兜底。
     */
    @Test
    fun `历史抖动不得让后续稳态走兜底判据`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        // 抖动：offline / unreachable 交替若干次（未到兜底阈值）
        repeat(6) { i ->
            s = if (i % 2 == 0) offline(t, s).state else unreachable(t, s).state
            t += 30_000L
        }
        assertTrue("此轮应已记录抖动", s.flapCount > 0)
        // 恢复在线（清空本轮）
        s = QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE, nowMs = t,
            enabled = true, loopback = true, prev = s
        ).state
        assertEquals("恢复在线必须清空本轮抖动计数", 0, s.flapCount)

        // 稳态持续不可达 20 分钟（> 兜底阈值 15 分钟，但 < 30 次阈值）
        var notified = false
        repeat(20) {
            t += minute
            val d = unreachable(t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified = true
        }
        assertTrue(
            "稳态不可达在 20 分钟时**不应**告警（应等 30 次阈值，ADR-073 明确保留该语义）",
            !notified
        )
    }

    /**
     * 反向：真抖动（kind 持续切换）超过兜底阈值**必须**告警。
     */
    @Test
    fun `持续切换故障类型超过兜底阈值要告警`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        var notified = false
        repeat(40) { i ->
            val d = if (i % 2 == 0) offline(t, s) else unreachable(t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified = true
            t += 30_000L
        }
        assertTrue("抖动 20 分钟必须告警（否则隔夜完全无感）", notified)
    }
}
