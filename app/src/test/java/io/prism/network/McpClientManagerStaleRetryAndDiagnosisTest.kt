package io.prism.network

import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次16（US-1601/1602）：MCP 连接稳定性与诊断分类单元测试。
 *
 * 覆盖：
 * - [McpClientManager.classifyError] 全分支（真机四类失败场景的映射正确性）
 * - [McpClientManager.isStaleConnectionError]（H-1 重试条件——仅链路类瞬态错误重试）
 */
class McpClientManagerStaleRetryAndDiagnosisTest {

    private val manager = McpClientManager(
        // classifyError/isStaleConnectionError 为纯函数——httpClient 不会被真正使用，
        // 注入 MockEngine 占位（构造参数为非空类型）
        httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("unused") }),
        apiKeyRepository = io.prism.security.ApiKeyRepository(
            FakePreferenceDataStore(),
            RecordingCryptoService()
        )
    )

    // ==================== classifyError（US-1602） ====================

    @Test
    fun `classifyError maps plaintext block`() {
        val (kind, msg) = manager.classifyError(
            java.net.UnknownServiceException("CLEARTEXT communication to 192.168.1.10 not permitted by network security policy")
        )
        assertEquals(McpErrorKind.PLAINTEXT_BLOCKED, kind)
        assertTrue(msg.contains("adb reverse"))
    }

    @Test
    fun `classifyError maps connection refused`() {
        val (kind, msg) = manager.classifyError(
            java.net.ConnectException("Failed to connect to localhost/127.0.0.1:8001")
        )
        assertEquals(McpErrorKind.CONNECTION_REFUSED, kind)
        assertTrue(msg.contains("adb reverse"))
    }

    @Test
    fun `classifyError maps timeout`() {
        val (kind, _) = manager.classifyError(
            java.net.SocketTimeoutException("Connect timed out")
        )
        assertEquals(McpErrorKind.TIMEOUT, kind)
    }

    @Test
    fun `classifyError maps tls error`() {
        val (kind, _) = manager.classifyError(
            javax.net.ssl.SSLException("Certificate validation failed")
        )
        assertEquals(McpErrorKind.TLS, kind)
    }

    @Test
    fun `classifyError maps auth failures`() {
        assertEquals(
            McpErrorKind.AUTH,
            manager.classifyError(IllegalStateException("HTTP 401 Unauthorized")).first
        )
        assertEquals(
            McpErrorKind.AUTH,
            manager.classifyError(IllegalStateException("HTTP 403 Forbidden")).first
        )
    }

    @Test
    fun `classifyError maps stale network and protocol fallback`() {
        assertEquals(
            McpErrorKind.NETWORK,
            manager.classifyError(java.io.IOException("unexpected end of stream on http://127.0.0.1:8000")).first
        )
        assertEquals(
            McpErrorKind.INVALID_URL,
            manager.classifyError(IllegalArgumentException("非法 MCP Server baseUrl")).first
        )
        assertEquals(
            McpErrorKind.PROTOCOL,
            manager.classifyError(IllegalStateException("weird server response")).first
        )
    }

    // ==================== isStaleConnectionError（H-1 重试条件） ====================

    @Test
    fun `isStaleConnectionError matches link-level transient errors only`() {
        assertTrue(
            manager.isStaleConnectionError(java.io.IOException("unexpected end of stream on http://127.0.0.1:8000"))
        )
        assertTrue(
            manager.isStaleConnectionError(java.io.IOException("Connection reset by peer"))
        )
        assertTrue(
            manager.isStaleConnectionError(java.net.ConnectException("Failed to connect"))
        )
        // 非链路类：不重试（H-1：callTool 副作用类异常不在重试范围）
        assertTrue(
            !manager.isStaleConnectionError(javax.net.ssl.SSLException("certificate"))
        )
        assertTrue(
            !manager.isStaleConnectionError(IllegalStateException("protocol violation"))
        )
        assertTrue(
            !manager.isStaleConnectionError(java.net.SocketTimeoutException("timeout"))
        )
    }
}
