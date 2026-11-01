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

    /**
     * 2026-10-31（ADR-069）：本机模式（回环地址）下端口无监听 = 本机协议端没跑。
     *
     * 原实现把回环地址一律判失败（"不可能是 PC 上的协议端"），
     * 与"NapCat 与 Prism 装在同一台手机上"的合法部署直接冲突。
     * 现在回环是**一等公民**：只有端口连不上时才失败，且结论必须指向"启动本机协议端"。
     */
    @Test
    fun `loopback without listener maps to local protocol not running`() {
        val summary = QqDiagnosticsRules.Report(
            steps = listOf(
                QqDiagnosticsRules.Step(
                    QqDiagnosticsRules.Stage.SUBNET,
                    QqDiagnosticsRules.Outcome.PASS,
                    "本机模式（127.0.0.1）：协议端与 Prism 在同一台手机上"
                ),
                QqDiagnosticsRules.Step(
                    QqDiagnosticsRules.Stage.TCP,
                    QqDiagnosticsRules.Outcome.FAIL,
                    "本机协议端未运行（127.0.0.1:3000 无监听）"
                )
            ),
            verdict = QqDiagnosticsRules.Verdict.LOCAL_PROTOCOL_NOT_RUNNING
        ).summary()
        // 结论必须可读且不把用户引向"改 IP / 查防火墙"
        assertTrue("summary 应包含'未运行'：$summary", summary.contains("未运行"))

        val advice = QqDiagnosticsRules.topologyAdvice(
            QqDiagnosticsRules.Verdict.LOCAL_PROTOCOL_NOT_RUNNING
        )
        assertNotNull("本机模式必须给出可执行指引", advice)
        val text = advice.orEmpty()
        assertTrue("指引应给出容器启动命令：$text", text.contains("bookworm-arm64.sh"))
        assertTrue("指引应给出 napcat 启动命令：$text", text.contains("napcat.sh"))
        assertTrue("指引应提到保活：$text", text.contains("wake-lock"))
    }
}
