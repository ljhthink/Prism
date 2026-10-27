package io.prism.qq

import io.prism.qq.onebot.QqDiagnosticsRules
import io.prism.network.PrivateNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨网络可达（2026-10-27；ADR-068 / US-3126）：
 * - RFC 6598 `100.64.0.0/10`（Tailscale/ZeroTier 默认网段）必须被视为私网；
 * - 但**不得**顺带放开公网与域名（回归锁，防 SSRF）。
 */
class PrivateNetworkCgnatTest {

    @Test
    fun `tailscale 网段被视为私网`() {
        assertTrue(PrivateNetwork.isPrivateHost("100.64.0.1"))
        assertTrue(PrivateNetwork.isPrivateHost("100.92.122.126"))
        assertTrue(PrivateNetwork.isPrivateHost("100.127.255.254"))
        assertTrue(PrivateNetwork.isPrivateEndpoint("http://100.92.122.126:3000"))
        assertTrue(PrivateNetwork.isPrivateEndpoint("ws://100.92.122.126:3001"))
        // 明文策略：私网 http 允许（与 10/8 同等对待）
        assertTrue(PrivateNetwork.isCleartextAllowed("http://100.92.122.126:3000"))
    }

    @Test
    fun `100 段之外仍不是私网（不能整段放开）`() {
        // 100.63/100.128 不属于 100.64.0.0/10
        assertFalse(PrivateNetwork.isPrivateHost("100.63.0.1"))
        assertFalse(PrivateNetwork.isPrivateHost("100.128.0.1"))
        assertFalse(PrivateNetwork.isPrivateHost("100.64.0.1".replace("100.64", "100.0")))
    }

    @Test
    fun `公网与域名仍被拒绝（回归锁）`() {
        assertFalse(PrivateNetwork.isPrivateEndpoint("http://8.8.8.8:3000"))
        assertFalse(PrivateNetwork.isPrivateEndpoint("https://napcat.example.com"))
        assertFalse(PrivateNetwork.isPrivateEndpoint("http://qq.com.evil.example:3000"))
        assertFalse(PrivateNetwork.isPrivateEndpoint("ftp://100.92.122.126:3000"))
    }

    @Test
    fun `原有私网段不受影响`() {
        assertTrue(PrivateNetwork.isPrivateHost("10.92.122.126"))
        assertTrue(PrivateNetwork.isPrivateHost("172.20.1.1"))
        assertTrue(PrivateNetwork.isPrivateHost("192.168.1.10"))
        assertTrue(PrivateNetwork.isPrivateHost("127.0.0.1"))
    }
}

/**
 * 旧局域网地址识别（2026-10-27；US-3126 AC-3）。
 *
 * 真机实证：离开热点后地址仍是 `10.92.122.126`，手机只剩蜂窝 `10.244.60.226/30`，
 * 只报"连接超时（防火墙/未启动）"会把人引错方向。
 */
class QqStaleLanAddressTest {

    @Test
    fun `超时且不同网段时判为旧局域网地址`() {
        assertTrue(
            QqDiagnosticsRules.isStaleLanAddress(
                sameSubnet = false, cellOnly = false, tcpFailedWithTimeout = true
            )
        )
    }

    @Test
    fun `只剩蜂窝网时即使同网段也算旧地址`() {
        // 蜂窝网 10/8 与配置的 10.x 同网段，但热点已关：仍然不可达
        assertTrue(
            QqDiagnosticsRules.isStaleLanAddress(
                sameSubnet = true, cellOnly = true, tcpFailedWithTimeout = true
            )
        )
    }

    @Test
    fun `同网段且非蜂窝时不判旧地址（应归为防火墙或未启动）`() {
        assertFalse(
            QqDiagnosticsRules.isStaleLanAddress(
                sameSubnet = true, cellOnly = false, tcpFailedWithTimeout = true
            )
        )
    }

    @Test
    fun `TCP 未失败时不判旧地址`() {
        assertFalse(
            QqDiagnosticsRules.isStaleLanAddress(
                sameSubnet = false, cellOnly = true, tcpFailedWithTimeout = false
            )
        )
    }

    @Test
    fun `接口名识别蜂窝网`() {
        assertTrue(QqDiagnosticsRules.isCellOnly("rmnet_data2"))
        assertTrue(QqDiagnosticsRules.isCellOnly("ccmni0"))
        assertFalse(QqDiagnosticsRules.isCellOnly("wlan0"))
        assertFalse(QqDiagnosticsRules.isCellOnly("eth0"))
        assertFalse(QqDiagnosticsRules.isCellOnly(null))
    }

    @Test
    fun `旧地址结论带可执行建议`() {
        val advice = QqDiagnosticsRules.topologyAdvice(
            QqDiagnosticsRules.Verdict.STALE_LAN_ADDRESS
        )
        assertTrue(advice.orEmpty().contains("100.x"))
        assertTrue(advice.orEmpty().contains("无障碍"))
    }
}
