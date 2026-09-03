package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次9（US-902）：博查 Bocha REST 搜索引擎单测。
 *
 * 覆盖：
 * - [WebSearchLocalToolExecutor.parseBochaItems]：真实 Bocha Web Search 响应解析
 * - execute 端到端：配置 Key → 优先走 Bocha；无 Key → 降级 Bing；Bocha 失败 → 降级 Bing
 * - 请求体：POST JSON 含 query（转义）/summary/count + Bearer 鉴权头
 */
class WebSearchLocalToolExecutorBochaTest {

    /** Bocha Web Search 响应体样例（含 data.webPages.value[]）。 */
    private fun bochaBody(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder("""{"data":{"webPages":{"value":[""")
        items.forEachIndexed { i, (name, url, snippet) ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":").append(quote(name))
                .append(",\"url\":").append(quote(url))
                .append(",\"snippet\":").append(quote(snippet))
                .append(",\"siteName\":\"\",\"datePublished\":null}")
        }
        sb.append("]}}}")
        return sb.toString()
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun bingHtml(items: Triple<String, String, String>): String =
        """<html><body><ol id="b_results"><li class="b_algo"><h2><a href="${items.second}">${items.first}</a></h2><div class="b_caption"><p>${items.third}</p></div></li></ol></body></html>"""

    @Test
    fun `parseBochaItems extracts name url snippet`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = executor.parseBochaItems(
            bochaBody(
                Triple("梧州市第一中学_百度百科", "https://baike.baidu.com/梧州市第一中学", "梧州市第一中学是广西最早建立的中学"),
                Triple("梧州市第一中学-学校官网", "https://www.wzyz.com.cn/", "梧州市第一中学官网")
            )
        )
        assertEquals(2, items.size)
        assertEquals("梧州市第一中学_百度百科", items[0].title)
        assertEquals("https://baike.baidu.com/梧州市第一中学", items[0].link)
        assertEquals("梧州市第一中学是广西最早建立的中学", items[0].snippet)
    }

    @Test
    fun `parseBochaItems missing webPages returns empty`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertTrue(executor.parseBochaItems("""{"data":{}}""").isEmpty())
        assertTrue(executor.parseBochaItems("""{"error":"invalid"}""").isEmpty())
        assertTrue(executor.parseBochaItems("not json").isEmpty())
    }

    @Test
    fun `execute uses bocha first when key configured`() = runBlocking {
        // 有 Key → 主请求走 Bocha（POST api.bocha.cn），返回 Bocha 结果，不降级 Bing。
        var bingCalls = 0
        var bochaCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> {
                    bochaCalls++
                    respond(
                        content = bochaBody(
                            Triple("梧州市第一中学-学校官网", "https://www.wzyz.com.cn/", "梧州市第一中学是一所百年名校")
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> {
                    bingCalls++
                    respond(bingHtml(Triple("Bing 结果", "https://bing.example.com/", "Bing 摘要")), HttpStatusCode.OK)
                }
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk-bocha-test" })
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        assertEquals("Bocha 优先应只调 Bocha", 1, bochaCalls)
        assertEquals("不应降级 Bing", 0, bingCalls)
        assertTrue("应返回 Bocha 学校官网结果", result.contains("梧州市第一中学-学校官网"))
        assertTrue("结果应含外部内容边界标记", result.contains("网络搜索外部内容"))
    }

    @Test
    fun `execute falls back to bing when bocha fails`() = runBlocking {
        // Bocha 非 2xx（如 429）→ 降级 Bing，不阻断零配置搜索。
        var bingCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> respond("", HttpStatusCode.TooManyRequests)
                else -> {
                    bingCalls++
                    // 返回 title 含核心词的结果，使降级路径的强相关判据命中（避免落入百度兜底）
                    respond(
                        bingHtml(Triple("测试查询相关结果", "https://bing.example.com/", "关于测试查询的摘要")),
                        HttpStatusCode.OK
                    )
                }
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk-bocha-test" })
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "测试查询", "maxResults" to 5)
        )
        assertTrue("Bocha 失败应降级 Bing（≥1 次非 Bocha 请求）", bingCalls >= 1)
        assertTrue("应返回 Bing 结果", result.contains("测试查询相关结果"))
    }

    @Test
    fun `execute skips bocha when key is null`() = runBlocking {
        // 未配置 Key → 直接走 Bing（行为与 v1 批次8 完全一致，零 Bocha 请求）。
        var bingCalls = 0
        var bochaCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> {
                    bochaCalls++
                    respond("", HttpStatusCode.OK)
                }
                else -> {
                    bingCalls++
                    respond(bingHtml(Triple("Bing 结果", "https://bing.example.com/", "Bing 摘要")), HttpStatusCode.OK)
                }
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine)) // 默认 bochaApiKeyProvider = null
        executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "测试查询", "maxResults" to 5)
        )
        assertEquals("无 Key 不应调 Bocha", 0, bochaCalls)
        assertTrue("无 Key 应走 Bing", bingCalls > 0)
    }

    @Test
    fun `bocha request body contains quoted query and bearer auth`() = runBlocking {
        // 断言 Bocha 请求：POST JSON body（query 转义）+ Bearer 鉴权头。
        var bochaBody = ""
        var authHeader: String? = null
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> {
                    authHeader = request.headers[HttpHeaders.Authorization]
                    bochaBody = (request.body as? io.ktor.http.content.TextContent)?.text
                        ?: (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                        ?: ""
                    respond(
                        content = bochaBody(Triple("结果", "https://x.com/", "摘要")),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond(bingHtml(Triple("Bing", "https://b.com/", "x")), HttpStatusCode.OK)
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk-key-123" })
        executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "含\"引号\"测试", "maxResults" to 3)
        )
        assertEquals("Bearer 鉴权头应注入", "Bearer sk-key-123", authHeader)
        assertTrue("body 应含转义后的 query", bochaBody.contains("含\\\"引号\\\"测试"))
        assertTrue("body 应含 count=3", bochaBody.contains("\"count\":3"))
        assertTrue("body 应含 summary", bochaBody.contains("\"summary\":true"))
    }

    @Test
    fun `parseBochaItems returns empty for empty value array`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertTrue(executor.parseBochaItems("""{"data":{"webPages":{"value":[]}}}""").isEmpty())
    }

    private fun noopClient(): HttpClient =
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
}
