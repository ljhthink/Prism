package io.prism.qq.onebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OneBotEndpoint] normalization matrix (ADR-066). */
class OneBotEndpointTest {

    @Test
    fun `bare ip derives default http 3000 and ws 3001`() {
        val r = OneBotEndpoint.normalize("192.168.1.10")
        assertTrue(r.ok)
        assertEquals("http://192.168.1.10:3000", r.baseUrl)
        assertEquals("ws://192.168.1.10:3001", r.wsUrl)
    }

    @Test
    fun `ip with port derives ws port plus one`() {
        val r = OneBotEndpoint.normalize("10.92.122.126:3000")
        assertEquals("http://10.92.122.126:3000", r.baseUrl)
        assertEquals("ws://10.92.122.126:3001", r.wsUrl)
    }

    @Test
    fun `http url keeps host and port`() {
        val r = OneBotEndpoint.normalize("http://192.168.1.10:8080/")
        assertEquals("http://192.168.1.10:8080", r.baseUrl)
        assertEquals("ws://192.168.1.10:8081", r.wsUrl)
    }

    @Test
    fun `ws url back derives http port`() {
        val r = OneBotEndpoint.normalize("ws://192.168.1.10:3001")
        assertEquals("ws://192.168.1.10:3001", r.wsUrl)
        assertEquals("http://192.168.1.10:3000", r.baseUrl)
    }

    @Test
    fun `https keeps same port for wss`() {
        val r = OneBotEndpoint.normalize("https://192.168.1.10")
        assertEquals("https://192.168.1.10:3000", r.baseUrl)
        assertEquals("wss://192.168.1.10:3000", r.wsUrl)
    }

    @Test
    fun `public ip is rejected`() {
        val r = OneBotEndpoint.normalize("8.8.8.8:3000")
        assertFalse(r.ok)
        assertTrue(r.error.orEmpty().contains("私网"))
    }

    @Test
    fun `domain is rejected`() {
        assertFalse(OneBotEndpoint.normalize("napcat.example.com").ok)
    }

    @Test
    fun `invalid port is rejected`() {
        assertFalse(OneBotEndpoint.normalize("192.168.1.10:70000").ok)
        assertFalse(OneBotEndpoint.normalize("192.168.1.10:abc").ok)
    }

    @Test
    fun `blank is rejected`() {
        assertFalse(OneBotEndpoint.normalize("   ").ok)
    }

    @Test
    fun `loopback detection`() {
        assertTrue(OneBotEndpoint.isLoopback("http://127.0.0.1:3000"))
        assertTrue(OneBotEndpoint.isLoopback("ws://localhost:3001"))
        assertFalse(OneBotEndpoint.isLoopback("http://10.92.122.126:3000"))
    }

    @Test
    fun `display target has no scheme`() {
        assertEquals("10.92.122.126:3000", OneBotEndpoint.normalize("10.92.122.126").displayTarget)
    }
}
