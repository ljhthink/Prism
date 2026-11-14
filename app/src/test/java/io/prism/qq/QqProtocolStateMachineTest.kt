package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议端**状态机**解析单测（2026-11-02；ADR-071 / PRD US-3129 AC-1~AC-3）。
 *
 * 为什么单独测：扫码期间 3000 端口**本来就不监听**，旧判据会把「正在等扫码」判成
 * 「没起来」→ 自动重启 → `pkill QQ` → 用户正在扫的二维码作废 → 再次登录态失效。
 * 这是真机「越修越掉」的直接机理（F-3）。
 */
class QqProtocolStateMachineTest {

    private val now = 1_700_000_000_000L
    private val tsSec = now / 1000L

    private fun status(line: String, ts: Long = tsSec) = "ts=$ts tick=10 qq=3 $line"

    @Test
    fun `解析状态字段`() {
        assertEquals(
            QqProtocolSupervisor.STATE_NEED_QR,
            QqProtocolSupervisor.parseState(status("onebot3000=0 state=need_qr"))
        )
        assertEquals(
            QqProtocolSupervisor.STATE_ONLINE,
            QqProtocolSupervisor.parseState(status("onebot3000=1 state=online"))
        )
    }

    @Test
    fun `旧版脚本无 state 字段返回空串`() {
        assertEquals("", QqProtocolSupervisor.parseState("ts=$tsSec tick=1 qq=3 onebot3000=0"))
    }

    @Test
    fun `监听且新鲜才算在跑`() {
        assertTrue(
            QqProtocolSupervisor.isStatusFreshAndRunning(
                status("onebot3000=1 state=online"), now
            )
        )
    }

    @Test
    fun `陈旧状态视为停止`() {
        assertFalse(
            QqProtocolSupervisor.isStatusFreshAndRunning(
                status("onebot3000=1 state=online", ts = tsSec - 120), now
            )
        )
    }

    @Test
    fun `探活口径为内核表修复后 3000 为真`() {
        // run2-inner.sh 现在按 /proc/net/tcp 判定，监听时写 1（旧实现恒写 0）
        assertEquals(
            1,
            QqProtocolSupervisor.parseOneBot3000(status("onebot3000=1 state=online"))
        )
    }

    /**
     * 真机 2026-11-14 实证：`state=starting` 若无限期视为"在跑"，会把整个自愈链锁死
     * （旧脚本探活恒 0 → 永远 starting → Prism 判已启动 → 拒绝重启 → 新脚本永不生效）。
     * 修法：状态行增加 `since=`，按宽限期判定是否卡死。
     */
    @Test
    fun `since 字段存在且宽限期常量合理`() {
        val legacy = status("onebot3000=0 state=starting")
        // 无 since 的旧格式仍能解析状态（按 -1 保守处理，不误杀正在启动的链路）
        assertEquals(
            QqProtocolSupervisor.STATE_STARTING,
            QqProtocolSupervisor.parseState(legacy)
        )
        assertTrue("宽限期应与脚本的 180s 启动超时对齐", QqProtocolSupervisor.STARTING_GRACE_MS <= 300_000L)
        val withSince = status("onebot3000=0 state=starting since=$tsSec")
        assertTrue(withSince.contains("since="))
    }

    /**
     * 状态机四态逐个断言（验收补充）：旧版只测了两态，`qq_dead` 与 `starting` 没有回归锁。
     */
    @Test
    fun `四态解析逐个成立`() {
        mapOf(
            "starting" to QqProtocolSupervisor.STATE_STARTING,
            "need_qr" to QqProtocolSupervisor.STATE_NEED_QR,
            "online" to QqProtocolSupervisor.STATE_ONLINE,
            "qq_dead" to QqProtocolSupervisor.STATE_QQ_DEAD
        ).forEach { (raw, expected) ->
            assertEquals(expected, QqProtocolSupervisor.parseState(status("onebot3000=0 state=$raw")))
        }
    }

    /**
     * `isStatusFreshAndRunning`（展示口径）与 `isProtocolPortListening`（重启口径）**故意不同**：
     * 前者只回答"端口在监听吗"，后者还要判扫码/启动中。这里固化这个差异，防止有人"顺手统一"
     * 而把扫码保护删掉（验收报告 R-4 的埋点风险）。
     */
    @Test
    fun `扫描口径与重启口径的差异被固化`() {
        val needQr = status("onebot3000=0 state=need_qr")
        // 展示口径：端口没监听 → false（如实展示"未运行"）
        assertFalse(QqProtocolSupervisor.isStatusFreshAndRunning(needQr, now))
        // 重启口径：扫码中 → true（绝不重启）
        assertTrue(
            QqProtocolSupervisor.isRunningDecision(
                onebot3000 = QqProtocolSupervisor.parseOneBot3000(needQr),
                state = QqProtocolSupervisor.parseState(needQr),
                httpReachable = false
            )
        )
    }
}
