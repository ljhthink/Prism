package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖动期告警单测（2026-11-15；ADR-073）。
 *
 * 真机实证的双重盲区：
 * 1. 未武装（未连续在线 10 分钟）时 `neverStarted()` 对 `QQ_OFFLINE` 直接 `Action.None`；
 * 2. `QQ_OFFLINE` 与 `UNREACHABLE` 每 30~60s 交替 → 两类 streak 互斥清零 → 永远攒不满阈值。
 * 两者叠加导致"抖动一整夜也一条告警都没有"。
 *
 * 修法：新增 `DOWN_SINCE_FALLBACK_MS`（15 分钟）兜底判据——只看"首次发现异常到现在多久"。
 */
class QqProtocolAlertFlappingTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun decide(
        probe: String,
        nowMs: Long,
        prev: QqProtocolAlertPolicy.State
    ) = QqProtocolAlertPolicy.decide(
        probe = probe, nowMs = nowMs, enabled = true, loopback = true, prev = prev
    )

    @Test
    fun `抖动阈值常量合理`() {
        assertTrue(
            "兜底阈值必须短于用户能容忍的沉默，且长于正常重连抖动",
            QqProtocolAlertPolicy.DOWN_SINCE_FALLBACK_MS in 5 * minute..30 * minute
        )
    }

    /**
     * 真机形态复现：`QQ_OFFLINE` ↔ `UNREACHABLE` 每 30 秒交替，持续 20 分钟。
     */
    @Test
    fun `离线与不可达交替抖动必须告警`() {
        var s = QqProtocolAlertPolicy.State()
        var notified = 0
        var t = t0
        repeat(40) { i ->
            val probe = if (i % 2 == 0) {
                QqProtocolAlertPolicy.PROBE_QQ_OFFLINE
            } else {
                QqProtocolAlertPolicy.PROBE_UNREACHABLE
            }
            val d = decide(probe, t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified++
            t += 30_000L
        }
        assertEquals("交替抖动持续 20 分钟必须能告警", 1, notified)
    }

    @Test
    fun `抖动未到兜底阈值时不打扰`() {
        var s = QqProtocolAlertPolicy.State()
        var notified = 0
        var t = t0
        // 只抖动 5 分钟
        repeat(10) { i ->
            val probe = if (i % 2 == 0) {
                QqProtocolAlertPolicy.PROBE_QQ_OFFLINE
            } else {
                QqProtocolAlertPolicy.PROBE_UNREACHABLE
            }
            val d = decide(probe, t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified++
            t += 30_000L
        }
        assertEquals("短时抖动不应告警", 0, notified)
    }

    @Test
    fun `未武装但账号离线持续过久仍要告警`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        var d = decide(QqProtocolAlertPolicy.PROBE_QQ_OFFLINE, t, s)
        s = d.state
        var notified = 0
        // 持续推进到超过兜底阈值（15 分钟 ≈ 30 个 30s 周期）
        repeat(40) {
            t += 30_000L
            d = decide(QqProtocolAlertPolicy.PROBE_QQ_OFFLINE, t, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified++
        }
        assertEquals(
            "未武装 + 账号长期离线 = 用户完全无感知，必须告警（且按 4h 节流只响一次）",
            1,
            notified
        )
    }

    @Test
    fun `正常在线不受兜底判据影响`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        repeat(30) {
            val d = decide(QqProtocolAlertPolicy.PROBE_ONLINE, t, s)
            s = d.state
            assertEquals(QqProtocolAlertPolicy.Action.None, d.action)
            t += minute
        }
        assertTrue(s.armed)
    }

    @Test
    fun `恢复在线会清空异常起点从而停止兜底计时`() {
        var s = QqProtocolAlertPolicy.State()
        var t = t0
        // 先抖动一下（未到阈值）
        repeat(6) { i ->
            val probe = if (i % 2 == 0) {
                QqProtocolAlertPolicy.PROBE_QQ_OFFLINE
            } else {
                QqProtocolAlertPolicy.PROBE_UNREACHABLE
            }
            s = decide(probe, t, s).state
            t += 30_000L
        }
        s = decide(QqProtocolAlertPolicy.PROBE_ONLINE, t, s).state
        assertEquals("恢复在线必须清空异常起点", 0L, s.downSinceMs)
    }
}
