package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议端告警的**重提醒**单测（2026-11-14；ADR-072 F4）。
 *
 * 真机故障形态：账号 11-03 03:11 离线 → 通知一次 → 用户没看到 → **静默 11 天**。
 * 只发一次等于没发，因此同一轮故障持续期间必须按 [QqProtocolAlertPolicy.RE_ALERT_INTERVAL_MS]
 * 周期性重提醒。本用例锁死"能重提醒"与"不会刷屏"两侧边界。
 */
class QqProtocolAlertRealertTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_700_000_000_000L

    private fun armed(): QqProtocolAlertPolicy.State {
        // 先在线满 10 分钟完成"武装"
        var s = QqProtocolAlertPolicy.State()
        s = QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE, nowMs = t0, enabled = true,
            loopback = true, prev = s
        ).state
        s = QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE, nowMs = t0 + 11 * minute,
            enabled = true, loopback = true, prev = s
        ).state
        return s
    }

    private fun unreachable(nowMs: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE, nowMs = nowMs,
            enabled = true, loopback = true, prev = prev
        )

    @Test
    fun `首轮告警的 reminder 为 false`() {
        var s = armed()
        val t = t0 + 11 * minute
        var first: QqProtocolAlertPolicy.Action.NotifyDown? = null
        repeat(3) { i ->
            val d = unreachable(t + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) {
                first = d.action as QqProtocolAlertPolicy.Action.NotifyDown
            }
        }
        assertEquals(false, first?.reminder)
    }

    @Test
    fun `持续失联 4 小时后重提醒一次`() {
        var s = armed()
        val t = t0 + 11 * minute
        repeat(3) { i -> s = unreachable(t + i * minute, s).state }
        // 首次通知锚点由状态给出（不是探测时刻），避免测试与实现各算一套
        val anchor = s.lastNotifyAtMs

        // 差 1 分钟到重提醒时刻 → 静默
        val early = unreachable(anchor + QqProtocolAlertPolicy.RE_ALERT_INTERVAL_MS - 60_000L, s)
        assertEquals(QqProtocolAlertPolicy.Action.None, early.action)

        // 恰好到点 → 重提醒
        val due = unreachable(anchor + QqProtocolAlertPolicy.RE_ALERT_INTERVAL_MS, s)
        assertTrue(due.action is QqProtocolAlertPolicy.Action.NotifyDown)
        assertEquals(
            true,
            (due.action as QqProtocolAlertPolicy.Action.NotifyDown).reminder
        )
    }

    @Test
    fun `重提醒按周期重复而不是只发一次`() {
        var s = armed()
        val t = t0 + 11 * minute
        repeat(3) { i -> s = unreachable(t + i * minute, s).state }
        var count = 0
        // 连续 3 个周期：每次从"上次通知锚点 + 周期"探测
        repeat(3) {
            val d = unreachable(
                s.lastNotifyAtMs + QqProtocolAlertPolicy.RE_ALERT_INTERVAL_MS, s
            )
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) count++
            s = d.state
        }
        assertEquals("每个周期一条重提醒", 3, count)
    }

    @Test
    fun `重提醒不会让 10 分钟内的抖动刷屏`() {
        var s = armed()
        val t = t0 + 11 * minute
        var count = 0
        // 10 分钟内连续 10 次探测：只应有 1 条（首轮）
        repeat(10) { i ->
            val d = unreachable(t + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) count++
        }
        assertEquals(1, count)
    }

    @Test
    fun `时钟回拨时按已到重提醒处理而不是永久静默`() {
        var s = armed()
        val t = t0 + 11 * minute
        repeat(3) { i -> s = unreachable(t + i * minute, s).state }
        // 系统时间被调回：nowMs 小于上次通知时刻 → elapsed 为负
        val back = unreachable(t - 10 * hour, s)
        assertTrue(
            "时钟回拨必须能提醒（否则新掉线会被长期静默）",
            back.action is QqProtocolAlertPolicy.Action.NotifyDown
        )
    }

    @Test
    fun `恢复后重提醒计数不再生效`() {
        var s = armed()
        val t = t0 + 11 * minute
        repeat(3) { i -> s = unreachable(t + i * minute, s).state }
        // 在线 → 清洗 downNotified
        s = QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE, nowMs = t + 5 * minute,
            enabled = true, loopback = true, prev = s
        ).state
        assertFalse(s.downNotified)
        assertEquals(0L, s.downSinceMs)
    }
}
