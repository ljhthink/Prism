package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次15.1（US-1509）：首选引擎 + 显式 engine 参数测试。
 *
 * 真机根因（2026-09-03）：用户自建 SearXNG 且 Bocha Key 同配时，默认链在 Bocha 成功
 * 即短路 → SearXNG 永不被尝试（日志零 searxng 行）。修复：新增首选引擎设置与 engine
 * 参数，命中者首个尝试，失败落回默认顺序链。
 */
class WebSearchLocalToolExecutorPreferredEngineTest {

    private fun titleUrlContentBody(vararg items: Triple<String, String, String>): String {
        val arr = items.joinToString(",") { (t, u, c) ->
            """{"title":"$t","url":"$u","content":"$c"}"""
        }
        return """{"query":"q","results":[$arr]}"""
    }

    private fun bingHtml(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder("""<html><ol id="b_results">""")
        items.forEach { (title, link, desc) ->
            sb.append("<li class=\"b_algo\"><h2><a href=\"").append(link).append("\">")
                .append(title).append("</a></h2><div class=\"b_caption\"><p>").append(desc)
                .append("</p></div></li>")
        }
        sb.append("</ol></html>")
        return sb.toString()
    }

    /** 标准三引擎配置：bocha/zhipu Key 已配 + SearXNG 端点已配。 */
    private fun executorWith(
        engine: MockEngine,
        preferred: String? = null
    ): WebSearchLocalToolExecutor = WebSearchLocalToolExecutor(
        HttpClient(engine),
        bochaApiKeyProvider = { "bocha-test-key" },
        zhipuApiKeyProvider = { "zhipu-test-key" },
        searxngConfigProvider = {
            WebSearchLocalToolExecutor.SearxngConfig("http://192.168.1.10:8080", "", "")
        },
        preferredEngineProvider = { preferred }
    )

    @Test
    fun `preferred engine searxng is tried first before bocha`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests.add(request)
            when {
                request.url.host == "192.168.1.10" -> respond(
                    content = titleUrlContentBody(Triple("SearXNG 命中", "https://s.example.com", "内容")),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(content = bingHtml(), status = HttpStatusCode.OK) // 不应到达
            }
        }
        val result = executorWith(engine, preferred = "searxng").execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "测试")
        )
        assertEquals("SearXNG 应首个尝试", "192.168.1.10", requests[0].url.host)
        assertEquals("SearXNG 成功即返回，不应再请求 Bocha/Bing", 1, requests.size)
        assertTrue("应返回 SearXNG 结果", result.contains("SearXNG 命中"))
    }

    @Test
    fun `explicit engine param overrides preferred engine`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests.add(request)
            when {
                request.url.host == "192.168.1.10" -> respond(
                    content = titleUrlContentBody(Triple("engine 参数命中", "https://s.example.com", "内容")),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(content = bingHtml(), status = HttpStatusCode.OK)
            }
        }
        // 首选引擎设置为 bocha，但显式 engine=searxng 优先
        val result = executorWith(engine, preferred = "bocha").execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "测试", "engine" to "searxng")
        )
        assertEquals("engine 参数应覆盖首选引擎设置", "192.168.1.10", requests[0].url.host)
        assertEquals(1, requests.size)
        assertTrue(result.contains("engine 参数命中"))
    }

    @Test
    fun `preferred searxng failure falls back to default chain starting bocha`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests.add(request)
            when {
                request.url.host == "192.168.1.10" -> respond("err", HttpStatusCode.InternalServerError)
                request.url.host == "api.bocha.cn" -> respond(
                    content = """{"code":200,"data":{"webPages":{"value":[{"name":"Bocha 兜底命中","url":"https://b.example.com","snippet":"内容"}]}}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(content = bingHtml(), status = HttpStatusCode.OK)
            }
        }
        val result = executorWith(engine, preferred = "searxng").execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "测试")
        )
        assertEquals("SearXNG 失败应先降级 Bocha（默认链首位）", "192.168.1.10", requests[0].url.host)
        assertEquals("api.bocha.cn", requests[1].url.host)
        assertTrue("应返回 Bocha 结果", result.contains("Bocha 兜底命中"))
    }

    @Test
    fun `explicit zhipu tool still queries zhipu first`() = runBlocking {
        // 回归锁定：重构后显式引擎工具语义保持（批次15：显式调用先请求该引擎）
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests.add(request)
            when {
                request.url.host == "open.bigmodel.cn" -> respond(
                    content = """{"search_result":[{"title":"智谱命中","link":"https://z.example.com","content":"内容"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(content = bingHtml(), status = HttpStatusCode.OK)
            }
        }
        val result = executorWith(engine, preferred = "bocha").execute(
            WebSearchLocalToolExecutor.TOOL_ZHIPU,
            mapOf("query" to "测试")
        )
        assertEquals("显式 zhipu 工具应首个请求智谱", "open.bigmodel.cn", requests[0].url.host)
        assertEquals(1, requests.size)
        assertTrue(result.contains("智谱命中"))
    }

    @Test
    fun `tool schema contains engine enum`() {
        val schema = WebSearchLocalToolExecutor.buildToolDefinition().function.parameters.toString()
        assertTrue("schema 应含 engine 参数", schema.contains("\"engine\""))
        assertTrue("engine 枚举应含 searxng", schema.contains("searxng"))
        assertTrue("engine 枚举应含 auto", schema.contains("auto"))
    }
}
