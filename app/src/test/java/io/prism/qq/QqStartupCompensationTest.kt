package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 停摆补偿判定单测（2026-11-14；ADR-072 F2-2）。
 *
 * 真机形态：11-03 凌晨消息无人回复，直到 11-14 用户自己发现。期间 App 不在，
 * 任何告警都发不出去；**唯一能触及用户的时刻是下次冷启动**。本用例锁死"该不该打扰"。
 */
class QqStartupCompensationTest {

    private val hour = 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    private fun eval(
        nowMs: Long = t0,
        lastSeenAtMs: Long = 0L,
        lastProtocolCheckedAtMs: Long = 0L,
        lastReportAtMs: Long = 0L
    ) = QqStartupCompensation.evaluate(
        nowMs, lastSeenAtMs, lastProtocolCheckedAtMs, lastReportAtMs
    )

    @Test
    fun `首次运行不提醒`() {
        assertEquals(QqStartupCompensation.Verdict.NO_HISTORY, eval())
    }

    @Test
    fun `停摆 5 小时 59 分不提醒`() {
        assertEquals(
            QqStartupCompensation.Verdict.HEALTHY,
            eval(lastSeenAtMs = t0 - (6 * hour - 60_000L))
        )
    }

    @Test
    fun `停摆 6 小时整即提醒`() {
        assertEquals(
            QqStartupCompensation.Verdict.STALLED,
            eval(lastSeenAtMs = t0 - 6 * hour)
        )
    }

    @Test
    fun `真机形态 11 天停摆必须提醒`() {
        assertEquals(
            QqStartupCompensation.Verdict.STALLED,
            eval(lastSeenAtMs = t0 - 11 * 24 * hour)
        )
    }

    @Test
    fun `任一证据较新即判健康`() {
        // 水位很旧，但协议端探测很新 → 链路其实在工作
        assertEquals(
            QqStartupCompensation.Verdict.HEALTHY,
            eval(lastSeenAtMs = t0 - 48 * hour, lastProtocolCheckedAtMs = t0 - 2 * 60_000L)
        )
        // 两者都旧 → 停摆
        assertEquals(
            QqStartupCompensation.Verdict.STALLED,
            eval(lastSeenAtMs = t0 - 48 * hour, lastProtocolCheckedAtMs = t0 - 48 * hour)
        )
    }

    /**
     * 真机 2026-11-14 实证推翻的设计错误：曾把"上次开机恢复时刻"当活着证据，
     * 结果一次历史恢复就把停摆 11 天的判定压成 HEALTHY。现在它**不参与**判定。
     */
    @Test
    fun `开机恢复时刻不参与存活判定`() {
        assertEquals(
            QqStartupCompensation.Verdict.STALLED,
            eval(lastSeenAtMs = t0 - 11 * 24 * hour)
        )
    }

    @Test
    fun `24 小时内已提醒过则不重复打扰`() {
        assertEquals(
            QqStartupCompensation.Verdict.THROTTLED,
            eval(lastSeenAtMs = t0 - 48 * hour, lastReportAtMs = t0 - 2 * hour)
        )
        // 超过 24 小时可再提醒
        assertEquals(
            QqStartupCompensation.Verdict.STALLED,
            eval(lastSeenAtMs = t0 - 72 * hour, lastReportAtMs = t0 - 25 * hour)
        )
    }

    @Test
    fun `时钟回拨不得触发打扰`() {
        // "最后活着"在未来 → 不可信，按无历史处理
        assertEquals(
            QqStartupCompensation.Verdict.NO_HISTORY,
            eval(lastSeenAtMs = t0 + 10 * hour)
        )
    }

    @Test
    fun `停摆时长文案可选且不超量级`() {
        assertEquals("30 分钟", QqStartupCompensation.describeStall(t0, t0 - 30 * 60_000L))
        assertEquals("5 小时", QqStartupCompensation.describeStall(t0, t0 - 5 * hour))
        assertEquals("11 天", QqStartupCompensation.describeStall(t0, t0 - 11 * 24 * hour))
    }

    /**
     * 真机 2026-11-14 实证缺陷：新装 APK 首启时 60s 探测比补偿先跑，
     * 把 `lastProtocolCheckedAtMs` 刷成"现在"，于是停摆 11 天也判 HEALTHY。
     * 修法：只接受**本次进程启动之前**写入的历史值。
     */
    @Test
    fun `本次启动污染的探测时刻被过滤掉`() {
        val processStart = t0
        // 本次启动后写入（被污染）→ 不可用
        assertEquals(0L, QqStartupCompensation.usableCheckedAt(t0 + 1_000L, processStart))
        assertEquals(0L, QqStartupCompensation.usableCheckedAt(t0 - 10_000L, processStart))
        // 上次会话写入（早于启动 ≥60s）→ 可用
        val prevSession = t0 - 11 * 24 * hour
        assertEquals(prevSession, QqStartupCompensation.usableCheckedAt(prevSession, processStart))
        // 未写入过 → 0
        assertEquals(0L, QqStartupCompensation.usableCheckedAt(0L, processStart))
    }

    @Test
    fun `过滤后 11 天停摆仍判 STALLED`() {
        val processStart = t0
        val checked = QqStartupCompensation.usableCheckedAt(t0 + 2_000L, processStart)
        assertEquals(
            QqStartupCompensation.Verdict.STALLED,
            eval(
                lastSeenAtMs = t0 - 11 * 24 * hour,
                lastProtocolCheckedAtMs = checked
            )
        )
    }
}
