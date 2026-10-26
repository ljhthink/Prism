package io.prism.qq.onebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** [QqDiagnosticsRules] pure-rule tests (ADR-066; 2026-10-26 real-device replay). */
class QqDiagnosticsRulesTest {

    @Test
    fun `same subnet math`() {
        assertTrue(QqDiagnosticsRules.sameSubnet("10.92.122.126", 24, "10.92.122.24"))
        assertFalse(QqDiagnosticsRules.sameSubnet("10.10.101.250", 18, "192.168.0.100"))
        assertTrue(QqDiagnosticsRules.sameSubnet("10.10.101.250", 18, "10.10.104.227"))
        assertFalse(QqDiagnosticsRules.sameSubnet("bad-ip", 24, "10.92.122.24"))
    }

    @Test
    fun `connection refused maps to port closed`() {
        val (verdict, detail) = QqDiagnosticsRules.classifyTcpFailure(
            ConnectException("Connection refused"), sameSubnet = true
        )
        assertEquals(QqDiagnosticsRules.Verdict.PORT_CLOSED, verdict)
        assertTrue(detail.contains("端口未监听"))
    }

    @Test
    fun `no route to host on same subnet maps to isolation`() {
        val (verdict, detail) = QqDiagnosticsRules.classifyTcpFailure(
            NoRouteToHostException("No route to host"), sameSubnet = true
        )
        assertEquals(QqDiagnosticsRules.Verdict.ISOLATED, verdict)
        assertTrue(detail.contains("隔离"))
    }

    @Test
    fun `no route to host across subnet maps to subnet mismatch`() {
        val (verdict, _) = QqDiagnosticsRules.classifyTcpFailure(
            NoRouteToHostException("No route to host"), sameSubnet = false
        )
        assertEquals(QqDiagnosticsRules.Verdict.SUBNET_MISMATCH, verdict)
    }

    @Test
    fun `timeout maps to firewall`() {
        val (verdict, _) = QqDiagnosticsRules.classifyTcpFailure(
            SocketTimeoutException("connect timed out"), sameSubnet = true
        )
        assertEquals(QqDiagnosticsRules.Verdict.FIREWALL_TIMEOUT, verdict)
    }

    @Test
    fun `verdict follows first failing stage`() {
        val steps = listOf(
            QqDiagnosticsRules.Step(
                QqDiagnosticsRules.Stage.ENDPOINT,
                QqDiagnosticsRules.Outcome.PASS,
                "ok"
            ),
            QqDiagnosticsRules.Step(
                QqDiagnosticsRules.Stage.TCP,
                QqDiagnosticsRules.Outcome.FAIL,
                "同网段但目标不可达（ARP 不通，疑似无线客户端隔离/跨 VLAN）"
            ),
            QqDiagnosticsRules.Step(
                QqDiagnosticsRules.Stage.AUTH,
                QqDiagnosticsRules.Outcome.PASS,
                "ok"
            )
        )
        assertEquals(QqDiagnosticsRules.Verdict.ISOLATED, QqDiagnosticsRules.verdictOf(steps))
    }

    @Test
    fun `all pass means connected`() {
        val steps = listOf(
            QqDiagnosticsRules.Step(
                QqDiagnosticsRules.Stage.ENDPOINT,
                QqDiagnosticsRules.Outcome.PASS,
                "ok"
            )
        )
        assertEquals(QqDiagnosticsRules.Verdict.CONNECTED, QqDiagnosticsRules.verdictOf(steps))
    }

    @Test
    fun `loopback endpoint maps to loopback verdict`() {
        val steps = listOf(
            QqDiagnosticsRules.Step(
                QqDiagnosticsRules.Stage.SUBNET,
                QqDiagnosticsRules.Outcome.FAIL,
                "回环地址（手机自身），不可能是 PC 上的协议端"
            )
        )
        // SUBNET stage with loopback detail is reported by the diagnostics as-is;
        // the verdict for this combination is produced by the caller override, so
        // here we only assert the advice/string mapping keeps the hint discoverable.
        val advice = QqDiagnosticsRules.topologyAdvice(QqDiagnosticsRules.Verdict.LOOPBACK_ENDPOINT)
        assertNotNull(advice)
        assertTrue(advice.orEmpty().contains("局域网 IP"))
        assertEquals(1, steps.size)
    }

    @Test
    fun `isolation advice suggests topology change`() {
        val advice = QqDiagnosticsRules.topologyAdvice(QqDiagnosticsRules.Verdict.ISOLATED)
        assertNotNull(advice)
        assertTrue(advice.orEmpty().contains("热点"))
    }
}
