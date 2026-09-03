package io.prism.hotlist

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD MCP/API 增强（US-002）—— 今日热榜本地工具单测。
 *
 * 验证：
 * - [HotListLocalToolExecutor.parseNodes]：全部榜单列表 JSON → 节点（hashid/name/display）
 * - [HotListLocalToolExecutor.parseBoard]：单榜详情 JSON → 条目（title/url/extra）
 * - [HotListLocalToolExecutor.resolveHashId]：按平台名匹配 hashid（精确/display/包含三级）
 * - execute 端到端：配置 Key → 返回格式化热榜；未配置 Key → 引导文案；平台不存在 → 中性文案
 * - 端点固定无用户可控 host（无 SSRF）；请求头注入 Authorization
 */
class HotListLocalToolExecutorTest {

    private val nodesJson = """
        {"error":false,"status":200,"data":[
          {"hashid":"KqndgxeLl9","name":"微博","display":"热搜榜","domain":"weibo.com"},
          {"hashid":"mproPpoq6O","name":"知乎","display":"热榜","domain":"zhihu.com"},
          {"hashid":"YpDRoQDolV","name":"百度","display":"热点榜","domain":"baidu.com"}
        ]}
    """.trimIndent()

    private val boardJson = """
        {"error":false,"status":200,"data":{
          "hashid":"KqndgxeLl9","name":"微博","display":"热搜榜","domain":"weibo.com",
          "items":[
            {"extra":"531 万热度","url":"https://s.weibo.com/weibo?q=a","title":"今日热点A"},
            {"extra":"206 万热度","url":"https://s.weibo.com/weibo?q=b","title":"今日热点B"},
            {"extra":"88 万热度","url":"https://s.weibo.com/weibo?q=c","title":"今日热点C"}
          ]
        }}
    """.trimIndent()

    private fun buildExecutor(client: HttpClient, key: String? = "test-key"): HotListLocalToolExecutor =
        HotListLocalToolExecutor(client, apiKeyProvider = { key })

    @Test
    fun `parseNodes extracts hashid name display`() {
        val nodes = buildExecutor(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })).parseNodes(nodesJson)
        assertEquals(3, nodes.size)
        assertEquals("KqndgxeLl9", nodes[0].hashId)
        assertEquals("微博", nodes[0].name)
        assertEquals("热搜榜", nodes[0].display)
    }

    @Test
    fun `parseNodes returns empty on malformed json`() {
        val executor = buildExecutor(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }))
        assertTrue(executor.parseNodes("not json").isEmpty())
        assertTrue(executor.parseNodes("{}").isEmpty())
    }

    @Test
    fun `parseBoard extracts title url extra`() {
        val items = buildExecutor(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })).parseBoard(boardJson)
        assertEquals(3, items.size)
        assertEquals("今日热点A", items[0].title)
        assertEquals("https://s.weibo.com/weibo?q=a", items[0].url)
        assertEquals("531 万热度", items[0].extra)
    }

    @Test
    fun `resolveHashId matches exact name then display then substring`() = runBlocking {
        val engine = MockEngine { request ->
            // 仅 nodes 请求；校验 Authorization 注入
            assertTrue("请求应携带 Authorization header", request.headers.contains("Authorization"))
            assertFalse("请求不应暴露 Key 到日志", request.url.toString().contains("test-key"))
            respond(nodesJson, HttpStatusCode.OK)
        }
        val executor = buildExecutor(HttpClient(engine), key = "test-key")
        assertEquals("精确 name 匹配（微博）", "KqndgxeLl9", executor.resolveHashId("微博", "test-key"))
        assertEquals("display 匹配（热榜→知乎）", "mproPpoq6O", executor.resolveHashId("热榜", "test-key"))
        assertEquals("子串匹配（百度热点→百度）", "YpDRoQDolV", executor.resolveHashId("百度", "test-key"))
        assertNull("未知平台返回 null", executor.resolveHashId("不存在平台", "test-key"))
    }

    @Test
    fun `resolveHashId throws on http error with status`() = runBlocking {
        // guardrail M-1：expectSuccess=false 下非 2xx 抛 HotListHttpException（携带状态码）
        val engine = MockEngine { respond("{}", HttpStatusCode.InternalServerError) }
        val executor = buildExecutor(HttpClient(engine), key = "test-key")
        try {
            executor.resolveHashId("微博", "test-key")
            org.junit.Assert.fail("500 应抛 HotListHttpException")
        } catch (e: HotListHttpException) {
            assertEquals(500, e.status)
        }
    }

    @Test
    fun `execute returns diagnostic for http 401 unauthorized`() = runBlocking {
        // guardrail M-1：401 → "Key 无效或未授权"可诊断文案
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        val executor = buildExecutor(HttpClient(engine), key = "test-key")
        val result = executor.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("401 应提示 Key 无效", result.contains("API Key 无效"))
    }

    @Test
    fun `execute returns formatted hotlist with key configured`() = runBlocking {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/nodes")) respond(nodesJson, HttpStatusCode.OK)
            else if (path.endsWith("/KqndgxeLl9")) respond(boardJson, HttpStatusCode.OK)
            else respond("{}", HttpStatusCode.NotFound)
        }
        val executor = buildExecutor(HttpClient(engine), key = "test-key")
        val result = executor.execute("hotlist__get", mapOf("platform" to "微博", "limit" to 2))
        assertTrue("结果应含平台名", result.contains("微博"))
        assertTrue("结果应含条目1", result.contains("今日热点A"))
        assertTrue("结果应含条目2", result.contains("今日热点B"))
        assertFalse("limit=2 不应含条目3", result.contains("今日热点C"))
        assertTrue("结果应含热度", result.contains("531 万热度"))
    }

    @Test
    fun `execute returns guidance when key missing`() = runBlocking {
        val executor = buildExecutor(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }), key = null)
        val result = executor.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("未配置 Key 应返回引导文案", result.contains("API Key"))
        assertTrue("引导应指向 tophubdata", result.contains("tophubdata"))
    }

    @Test
    fun `execute returns neutral message when platform not found`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/nodes")) respond(nodesJson, HttpStatusCode.OK)
            else respond("{}", HttpStatusCode.OK)
        }
        val executor = buildExecutor(HttpClient(engine), key = "test-key")
        val result = executor.execute("hotlist__get", mapOf("platform" to "不存在平台"))
        assertTrue("平台不存在应返回中性文案", result.contains("未找到平台"))
    }

    @Test
    fun `execute returns error message on network failure`() = runBlocking {
        // 模拟客户端异常（真正网络层失败，非 HTTP 状态码）→ 命中 catch 失败分支
        val engine = MockEngine { throw java.io.IOException("connection reset") }
        val executor = buildExecutor(HttpClient(engine), key = "test-key")
        val result = executor.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("网络失败应返回失败文案", result.contains("失败"))
    }

    @Test
    fun `execute missing platform param fails fast`() = runBlocking {
        val executor = buildExecutor(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }), key = "test-key")
        val result = executor.execute("hotlist__get", emptyMap())
        assertTrue("缺 platform 应返回必填提示", result.contains("platform"))
    }

    @Test
    fun `handles only matches hotlist namespace`() {
        val executor = buildExecutor(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }), key = "test-key")
        assertTrue(executor.handles("hotlist__get"))
        assertFalse(executor.handles("web_search__search"))
        assertFalse(executor.handles("hotlist__other"))
    }

    @Test
    fun `buildToolDefinition schema is valid and has required platform`() {
        val def = HotListLocalToolExecutor.buildToolDefinition()
        assertEquals("hotlist__get", def.function.name)
        assertTrue("描述应提及平台热榜", def.function.description.contains("热榜"))
        // 参数含 platform 必填
        val params = def.function.parameters
        assertTrue("参数应含 platform", params.toString().contains("platform"))
        assertTrue("参数应含 limit", params.toString().contains("limit"))
    }
}
