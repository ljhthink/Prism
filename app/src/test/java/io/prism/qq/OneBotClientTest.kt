package io.prism.qq

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.prism.qq.onebot.OneBotClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OneBotClient] 鉴权与地址边界的纯 JVM 测试（guardrail L-07）。
 *
 * 注入 Ktor MockEngine：不触网、不依赖 Android。
 */
class OneBotClientTest {

    private fun client(
        baseUrl: String = "http://127.0.0.1:3000",
        wsUrl: String = "ws://127.0.0.1:3001",
        token: String = "secret",
        requestCounter: () -> Unit = {}
    ): OneBotClient {
        val engine = MockEngine { _ ->
            requestCounter()
            respond("""{"status":"ok","retcode":0,"data":null}""", HttpStatusCode.OK)
        }
        return OneBotClient(
            connectionProvider = {
                OneBotClient.OneBotConnection(baseUrl, wsUrl, token, 900)
            },
            httpClient = HttpClient(engine),
            logger = {}
        )
    }

    @Test
    fun `token 为空时拒绝调用且不发请求`() = runBlocking {
        var requests = 0
        val c = client(token = "", requestCounter = { requests++ })
        val response = c.call("send_private_msg", "{}")
        assertFalse(response.ok)
        assertTrue(response.message!!.contains("access_token"))
        assertEquals(0, requests)
    }

    @Test
    fun `公网地址被拒绝且不发请求`() = runBlocking {
        var requests = 0
        val c = client(baseUrl = "https://api.example.com", requestCounter = { requests++ })
        val response = c.call("send_private_msg", "{}")
        assertFalse(response.ok)
        assertTrue(response.message!!.contains("私网"))
        assertEquals(0, requests)
    }

    @Test
    fun `域名前缀伪造被拒绝`() = runBlocking {
        var requests = 0
        val c = client(baseUrl = "http://10.0.0.5.evil.example", requestCounter = { requests++ })
        val response = c.call("send_private_msg", "{}")
        assertFalse(response.ok)
        assertEquals(0, requests)
    }

    @Test
    fun `合法私网地址正常发起请求`() = runBlocking {
        var requests = 0
        val c = client(baseUrl = "http://192.168.1.10:3000", requestCounter = { requests++ })
        val response = c.call("get_login_info", "{}")
        assertTrue(response.ok)
        assertEquals(1, requests)
    }
}
