package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖动判据的**基准时刻**回归（2026-11-15；guardrail 第二轮 R2-01）。
 *
 * 首个实现用"本轮已持续多久"（`downSinceMs`）判"kind 是否已稳定"，
 * 于是**抖动超过 30 分钟仍在交替**时会被误判成稳定 → 兜底判据永久失效。
 * 真机 177 次/18h 的形态正好命中该漏洞（每 30~60 秒切换一次，永远不会"稳定"）。
 *
 * 本用例用 `kindChangedAtMs`（最后一次切换时刻）锁死正确语义。
 */
class QqProtocolAlertKindStabilityTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun offline(nowMs: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_QQ_OFFLINE, nowMs = nowMs,
            enabled = true, loopback = true, prev = prev
        )

    private fun unreachable(nowMs: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE, nowMs = nowMs,
            enabled = true, loopback = true, prev = prev
        )

    private fun online(nowMs: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE, nowMs = nowMs,
            enabled = true, loopback = true, prev = prev
        )

    /**
     * **核心回归**：长时间交替（> 30 分钟）仍必须告警。
     *
     * 旧实现（用 downSinceMs 判稳定）在这里会永久静默——这正是要防的。
     */
    @Test
    fun `抖动超过三十分钟仍在交替时必须告警`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        var notified = 0
        // 45 分钟，每 30 秒交替一次（远超 2×15min 的"稳定"阈值）
        repeat(90) { i ->
            val d = if (i % 2 == 0) offline(t, s) else unreachable(t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified++
            t += 30_000L
        }
        assertTrue(
            "长时间交替也必须有告警（旧实现按'本轮时长'判稳定会永久静默）",
            notified >= 1
        )
    }

    @Test
    fun `kind 切换会刷新 kindChangedAtMs`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        s = offline(t, s).state
        val first = s.kindChangedAtMs
        t += minute
        s = unreachable(t, s).state
        assertTrue("切换后必须刷新", s.kindChangedAtMs > first)
        // 回环地址下 UNREACHABLE 归因为 LOCAL_PROTOCOL_NOT_RUNNING（不是 UNREACHABLE 本身）
        assertEquals(QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING.name, s.lastKind)
    }

    @Test
    fun `kind 不变时不刷新 kindChangedAtMs`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        s = unreachable(t, s).state
        val at = s.kindChangedAtMs
        repeat(5) {
            t += minute
            s = unreachable(t, s).state
        }
        assertEquals("同一 kind 连续出现不得刷新切换时刻", at, s.kindChangedAtMs)
    }

    @Test
    fun `恢复在线清空抖动记账`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        s = offline(t, s).state
        t += minute
        s = unreachable(t, s).state
        assertTrue(s.flapCount > 0)
        s = online(t, s).state
        assertEquals(0, s.flapCount)
        assertEquals("", s.lastKind)
        assertEquals(0L, s.kindChangedAtMs)
    }

    /**
     * 稳态（kind 一直不变）超过 `2×FALLBACK` 后必须回到原阈值语义：
     * 不得因为曾经抖动过就一路走兜底。
     */
    @Test
    fun `抖动后转入稳态按原阈值而不是兜底`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        // 先抖动一小会（未到 15 分钟兜底阈值）
        repeat(4) { i ->
            s = if (i % 2 == 0) offline(t, s).state else unreachable(t, s).state
            t += 30_000L
        }
        // 之后长时间只出现 UNREACHABLE（稳态），持续 40 分钟
        var notified = false
        repeat(40) {
            t += minute
            val d = unreachable(t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified = true
        }
        assertTrue("稳态不可达 40 分钟必然已过 30 次阈值，应告警", notified)
    }
}
