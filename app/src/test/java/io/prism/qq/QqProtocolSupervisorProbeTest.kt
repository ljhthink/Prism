package io.prism.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议端「是否在跑」判据的**兜底证据链**单测（2026-11-02；ADR-071 / guardrail R-4）。
 *
 * 背景：设备上若仍跑旧脚本（`onebot3000` 恒 0），Prism 会判"没起来"并反复 `pkill` 重启，
 * 把扫码窗口毁掉、账号登录态反复失效。新增 HTTP 探针作为兜底证据：
 * 只要协议端能回 `get_status`，就绝不再重启（不新增周期性探测源）。
 *
 * 判据顺序是本用例的核心：`need_qr`/`starting` **优先**（扫码期 3000 本就不监听）→
 * 端口监听 → HTTP 可达。顺序错了就会重新引入自踢。
 */
class QqProtocolSupervisorProbeTest {

    @Test
    fun `HTTP 可达时即使状态文件说 0 也不重启`() {
        assertTrue(
            QqProtocolSupervisor.isRunningDecision(
                onebot3000 = 0, state = "", httpReachable = true
            )
        )
        assertFalse(
            QqProtocolSupervisor.isRunningDecision(
                onebot3000 = 0, state = "", httpReachable = false
            )
        )
    }

    @Test
    fun `端口监听即为在跑`() {
        assertTrue(
            QqProtocolSupervisor.isRunningDecision(
                onebot3000 = 1, state = "online", httpReachable = false
            )
        )
    }

    @Test
    fun `扫码与启动中一律视为在跑且不依赖端口`() {
        listOf(
            QqProtocolSupervisor.STATE_NEED_QR,
            QqProtocolSupervisor.STATE_STARTING
        ).forEach { st ->
            assertTrue(
                "状态 $st 必须判为在跑",
                QqProtocolSupervisor.isRunningDecision(
                    onebot3000 = 0, state = st, httpReachable = false
                )
            )
        }
    }

    @Test
    fun `qq_dead 且端口不通且 HTTP 不可达才判为停止`() {
        assertFalse(
            QqProtocolSupervisor.isRunningDecision(
                onebot3000 = 0,
                state = QqProtocolSupervisor.STATE_QQ_DEAD,
                httpReachable = false
            )
        )
    }

    @Test
    fun `状态机常量与脚本约定一致`() {
        // 与 tools/napcat-on-phone/run2-inner.sh 的 state= 取值逐字对应
        assertTrue(QqProtocolSupervisor.STATE_STARTING == "starting")
        assertTrue(QqProtocolSupervisor.STATE_NEED_QR == "need_qr")
        assertTrue(QqProtocolSupervisor.STATE_ONLINE == "online")
        assertTrue(QqProtocolSupervisor.STATE_QQ_DEAD == "qq_dead")
    }
}
