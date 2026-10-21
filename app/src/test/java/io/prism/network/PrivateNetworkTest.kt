package io.prism.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PrivateNetwork] 明文边界测试（ADR-064 明文策略的运行时补偿）。
 */
class PrivateNetworkTest {

    @Test
    fun `https 与 wss 不受明文限制`() {
        assertTrue(PrivateNetwork.isCleartextAllowed("https://api.example.com/v1"))
        assertTrue(PrivateNetwork.isCleartextAllowed("wss://example.com/socket"))
    }

    @Test
    fun `远端 http 被拒绝`() {
        assertFalse(PrivateNetwork.isCleartextAllowed("http://api.example.com/v1"))
        assertFalse(PrivateNetwork.isCleartextAllowed("http://10.0.0.5.evil.example"))
        assertFalse(PrivateNetwork.isCleartextAllowed("http://192.168.1.10.attacker.tld"))
    }

    @Test
    fun `单标签主机名也被拒绝（L-31：仅接受 IP 字面量）`() {
        assertFalse(PrivateNetwork.isCleartextAllowed("http://nas:3000"))
        assertFalse(PrivateNetwork.isCleartextAllowed("http://ollama:11434"))
    }

    @Test
    fun `本机与私网 http 允许`() {
        assertTrue(PrivateNetwork.isCleartextAllowed("http://127.0.0.1:3000"))
        assertTrue(PrivateNetwork.isCleartextAllowed("http://localhost:3000"))
        assertTrue(PrivateNetwork.isCleartextAllowed("http://192.168.1.10:3000"))
        assertTrue(PrivateNetwork.isCleartextAllowed("http://10.0.0.5:3000"))
        assertTrue(PrivateNetwork.isCleartextAllowed("http://172.16.0.1:3000"))
    }

    @Test
    fun `非法 URL 与未知 scheme fail-closed`() {
        assertFalse(PrivateNetwork.isCleartextAllowed("not a url"))
        assertFalse(PrivateNetwork.isCleartextAllowed("ftp://127.0.0.1"))
        assertFalse(PrivateNetwork.isCleartextAllowed(""))
    }
}
