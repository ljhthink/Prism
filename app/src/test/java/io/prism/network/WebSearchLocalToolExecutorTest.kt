package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSearchLocalToolExecutor 单元测试（问题 8b，ADR-020）。
 *
 * 通过 [MockEngine] 注入 canned Bing RSS 响应验证 [execute] 结果格式化，
 * 纯函数验证 RSS 解析 / HTML 实体解码 / 工具定义结构（BR-testing-004）。
 */
class WebSearchLocalToolExecutorTest {

    private fun rssBody(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><rss><channel>""")
        items.forEach { (title, link, desc) ->
            sb.append("<item><title>").append(title).append("</title><link>")
                .append(link).append("</link><description>").append(desc)
                .append("</description></item>")
        }
        sb.append("</channel></rss>")
        return sb.toString()
    }

    /** 无操作 MockEngine client（纯函数测试不需要实际请求，但 [MockEngine] 必须提供 handler）。 */
    private fun noopClient(): HttpClient =
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })

    @Test
    fun `handles returns true only for web_search__search`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertTrue(executor.handles(WebSearchLocalToolExecutor.TOOL_SEARCH))
        assertFalse(executor.handles("cross_app__open_app"))
        assertFalse(executor.handles("web_search__other"))
        assertFalse(executor.handles(""))
    }

    @Test
    fun `execute formats search results from Bing RSS`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(
                    Triple("Prism 官网", "https://prism.example.com", "Prism 是一个 AI 助手"),
                    Triple("Prism 文档", "https://docs.example.com/prism", "使用指南")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))

        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "prism", "maxResults" to 5)
        )

        assertTrue("应包含第一条标题", result.contains("1. Prism 官网"))
        assertTrue("应包含第一条链接", result.contains("https://prism.example.com"))
        assertTrue("应包含第二条标题", result.contains("2. Prism 文档"))
    }

    @Test
    fun `execute returns missing query error when query absent`() = runBlocking {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            emptyMap()
        )
        assertEquals("缺少必需参数 query", result)
    }

    @Test
    fun `execute returns not found when no search results`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "nothing")
        )
        assertTrue("应返回未找到提示", result.contains("未找到"))
    }

    @Test
    fun `execute degrades to error message on network failure`() = runBlocking {
        val engine = MockEngine { _ ->
            throw RuntimeException("connection refused")
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "prism")
        )
        // UXR6 问题 1：失败文案改为中性（不含"请稍后重试"，避免诱导 LLM 反复重试）
        assertTrue("应降级为搜索失败文案", result.contains("搜索失败"))
        assertFalse("不应含诱导重试的「请稍后重试」", result.contains("请稍后重试"))
    }

    @Test
    fun `execute respects maxResults limit`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(
                    Triple("A", "https://a.example.com", "desc A"),
                    Triple("B", "https://b.example.com", "desc B"),
                    Triple("C", "https://c.example.com", "desc C")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "x", "maxResults" to 2)
        )
        assertTrue(result.contains("1. A"))
        assertTrue(result.contains("2. B"))
        assertFalse("超过 maxResults 的结果不应包含", result.contains("C"))
    }

    // ==================== 纯函数：RSS 解析 ====================

    @Test
    fun `parseRssItems extracts title link snippet from RSS body`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = executor.parseRssItems(
            rssBody(
                Triple("标题一", "https://one.example.com", "摘要一"),
                Triple("标题二", "https://two.example.com", "摘要二")
            )
        )
        assertEquals(2, items.size)
        assertEquals("标题一", items[0].title)
        assertEquals("https://one.example.com", items[0].link)
        assertEquals("摘要一", items[0].snippet)
    }

    @Test
    fun `parseRssItems skips items missing title or link`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val body = """
            <rss><channel>
            <item><title>只有标题</title></item>
            <item><link>https://x.example.com</link></item>
            <item><title>正常</title><link>https://ok.example.com</link></item>
            </channel></rss>
        """.trimIndent()
        val items = executor.parseRssItems(body)
        assertEquals("缺失 title 或 link 的 item 应被跳过", 1, items.size)
        assertEquals("正常", items[0].title)
    }

    @Test
    fun `clean strips html tags decodes entities and truncates`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        // 真实 Bing RSS description 含真实 HTML 标签（<b>），实体编码仅在文本内容中（&amp; 等）
        val cleaned = executor.clean("<b>加粗</b> &amp; 更多")
        assertEquals("加粗 & 更多", cleaned)
    }

    @Test
    fun `decodeHtmlEntities decodes common and numeric entities`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertEquals(
            """他说 "你好" & 再见 'x'""",
            executor.decodeHtmlEntities("他说 &quot;你好&quot; &amp; 再见 &#39;x&#39;")
        )
        assertEquals("A", executor.decodeHtmlEntities("&#65;"))
        // R-3（guardrail TKN-P8-GUARDRAIL-002）：十六进制数字实体断言
        assertEquals("A", executor.decodeHtmlEntities("&#x41;"))
        // 孤立代理项移除（R-1）
        assertEquals("", executor.decodeHtmlEntities("&#xD800;"))
    }

    @Test
    fun `stripHtmlTags removes html tags keeping text`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertEquals("纯文本", executor.stripHtmlTags("<p><span>纯文本</span></p>"))
    }

    // ==================== 工具定义 ====================

    @Test
    fun `buildToolDefinition defines web_search__search with required query`() {
        val def = WebSearchLocalToolExecutor.buildToolDefinition()
        assertEquals(WebSearchLocalToolExecutor.TOOL_SEARCH, def.function.name)
        assertTrue("description 应描述联网搜索能力", def.function.description.contains("联网搜索"))
        assertTrue("parameters 应为 object", def.function.parameters.toString().contains("\"type\":\"object\""))
        assertTrue("query 应必填", def.function.parameters.toString().contains("\"required\""))
        assertTrue("参数应含 query", def.function.parameters.toString().contains("query"))
    }

    // ==================== S-5 补充（guardrail TKN-P8-GUARDRAIL-001） ====================

    @Test
    fun `execute returns missing query error for blank query`() = runBlocking {
        // S-5：空白 query 应 fail-fast（M-2 修复验证），不发起网络请求
        val executor = WebSearchLocalToolExecutor(noopClient())
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "   ")
        )
        assertEquals("缺少必需参数 query", result)
    }

    @Test
    fun `execute URL-encodes query parameter`() = runBlocking {
        // S-5：查询词经 Ktor 参数编码（含空格/特殊字符时不被原样拼接，SSRF/注入防御）
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = rssBody(Triple("R", "https://r.example.com", "d")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "prism ai agent", "maxResults" to 1)
        )
        assertTrue("URL 应包含编码后的 q 参数", capturedUrl.orEmpty().contains("q=prism+ai+agent"))
        assertTrue("URL 应包含 format=rss", capturedUrl.orEmpty().contains("format=rss"))
    }

    @Test
    fun `execute URL-encodes Chinese query and adds zh-CN market params`() = runBlocking {
        // UXR5 问题 3 / UXR6 问题 1：中文 query（如"昔涟"）应被正确 URL 编码（UTF-8）。
        // UXR6 将 UXR5 无效的 `language=zh-cn` 参数改为 Bing 官方市场参数 `mkt=zh-CN`
        // + `setlang=zh-hans`（实测确认 mkt 对中文搜索生效；language 非 Bing 认可参数）。
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = rssBody(Triple("昔涟", "https://r.example.com", "描述")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟", "maxResults" to 1)
        )
        // Ktor 3.3.3 的 URLBuilder 对中文参数做 UTF-8 百分号编码，具体编码值因
        // Ktor 版本而异，此处仅验证中文被编码（不含原始中文字符）+ 市场参数存在
        assertNotNull("捕获的 URL 不应为空", capturedUrl)
        val url = capturedUrl
        assertFalse("中文 query 不应含原始中文字符于 URL（已被编码）", url!!.contains("昔涟"))
        assertTrue("应含 mkt=zh-CN（Bing 官方市场参数）", url.contains("mkt=zh-CN"))
        assertTrue("应含 cc=cn", url.contains("cc=cn"))
        assertTrue("应含 setlang=zh-hans", url.contains("setlang=zh-hans"))
        assertTrue("结果应包含完整中文标题（昔涟）", result.contains("昔涟"))
    }

    @Test
    fun `decodeCharset returns declared charset and defaults to UTF-8`() = runBlocking {
        // UXR5 问题 3（编码防御）：Content-Type 声明 charset=GBK 时应返回 GBK；
        // 无声明或缺省时应返回 UTF-8。
        val executor = WebSearchLocalToolExecutor(noopClient())
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/xml; charset=GBK")
            )
        }
        val gbkResponse = HttpClient(engine).get("http://localhost")
        assertEquals("应解析声明 charset=GBK", java.nio.charset.Charset.forName("GBK"), executor.decodeCharset(gbkResponse))

        val engine2 = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val noCharsetResponse = HttpClient(engine2).get("http://localhost")
        assertEquals("无 charset 声明应回退 UTF-8", Charsets.UTF_8, executor.decodeCharset(noCharsetResponse))
    }

    @Test
    fun `decodeHtmlEntities filters control characters from numeric entities`() {
        // S-5（S-1 修复验证）：&#0;/&#1; 等控制字符实体应被过滤，不注入 NUL/控制字符
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertEquals("正常文本", executor.decodeHtmlEntities("正常&#0;文本"))
        assertEquals("", executor.decodeHtmlEntities("&#1;"))
        assertEquals("A", executor.decodeHtmlEntities("&#65;"))
        // 超范围码点保留实体原文（避免非法码点）
        assertTrue("超范围码点应保留原文", executor.decodeHtmlEntities("&#99999999;").contains("&#"))
    }

    @Test
    fun `execute result includes external content boundary marker`() = runBlocking {
        // S-5（S-2 修复验证）：第三方网页内容回灌 LLM 前有「不可信内容」边界标记
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(Triple("标题", "https://x.example.com", "摘要")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "x")
        )
        assertTrue("应包含外部内容边界标记", result.startsWith("【网络搜索外部内容"))
        assertTrue("应包含搜索标题", result.contains("1. 标题"))
    }

    // ==================== ac-verifier 补充（TKN-P8-ACCEPTANCE-001）：AC-B1 工具定义 Schema 与 maxResults 边界 ====================

    @Test
    fun `buildToolDefinition schema enforces additionalProperties false and maxResults bounds`() {
        // AC-B1：additionalProperties=false（严格参数）+ query 必填 + maxResults 1..8（JSON Schema 结构断言）
        val def = WebSearchLocalToolExecutor.buildToolDefinition()
        val params = def.function.parameters.toString()
        assertTrue("additionalProperties 应为 false", params.contains("\"additionalProperties\":false"))
        assertTrue("query 应声明 type=string", params.contains("\"type\":\"string\""))
        assertTrue("maxResults 应声明 type=integer", params.contains("\"type\":\"integer\""))
        assertTrue("maxResults 应声明 minimum=1", params.contains("\"minimum\":1"))
        assertTrue("maxResults 应声明 maximum=8", params.contains("\"maximum\":8"))
        val required = (def.function.parameters as JsonObject)["required"] as JsonArray
        assertTrue("query 应处于 required 数组", required.contains(JsonPrimitive("query")))
    }

    // ==================== UXR7 问题 1：核心词提取 + 相关性检查 + 降级重试 ====================

    @Test
    fun `extractCoreTerms returns chinese terms of length 2 or more in order`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        // 单实体 query：疑问词"是谁"是停用词，只留"昔涟"
        assertEquals(listOf("昔涟"), executor.extractCoreTerms("昔涟 是谁"))
        // 多实体 query：全部非停用词候选按出现顺序返回，"角色"为停用词被过滤
        assertEquals(listOf("昔涟", "星穹铁道"), executor.extractCoreTerms("昔涟 星穹铁道 角色"))
        // 实体 + 停用词：只留实体
        assertEquals(listOf("小米"), executor.extractCoreTerms("小米 最新 新闻"))
        // 纯英文/空白/单字中文 → 空列表
        assertEquals(emptyList<String>(), executor.extractCoreTerms("deepseek AI"))
        assertEquals(emptyList<String>(), executor.extractCoreTerms(""))
        assertEquals(emptyList<String>(), executor.extractCoreTerms("   "))
        assertEquals(emptyList<String>(), executor.extractCoreTerms("昔 涟"))
    }

    @Test
    fun `extractCoreTerms skips chinese stop words`() {
        // guardrail M-1（TKN-UXR7-GUARDRAIL-001）：跳过常见中文停用词，
        // 避免 "DeepSeek 最新 新闻" 误取"最新"为实体触发无谓降级重试
        val executor = WebSearchLocalToolExecutor(noopClient())
        // "DeepSeek" 是英文不匹配，剩余中文片段全为停用词 → 空
        assertEquals(emptyList<String>(), executor.extractCoreTerms("DeepSeek 最新 新闻"))
        // 首个非停用词中文片段
        assertEquals(listOf("小米"), executor.extractCoreTerms("小米 最新 新闻"))
        assertEquals(listOf("昔涟"), executor.extractCoreTerms("最新 昔涟 新闻"))
        // 全停用词 → 空
        assertEquals(emptyList<String>(), executor.extractCoreTerms("最新 新闻 介绍"))
        // UXR7-R2 扩充停用词："角色/游戏/百科" 等通用词不误取为实体
        assertEquals(listOf("昔涟"), executor.extractCoreTerms("昔涟 角色"))
        assertEquals(listOf("昔涟"), executor.extractCoreTerms("昔涟 游戏 大全"))
        assertEquals(listOf("星穹铁道"), executor.extractCoreTerms("星穹铁道 角色"))
    }

    @Test
    fun `isRelevant detects bing tokenization failure`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val xiliItem = listOf(
            WebSearchLocalToolExecutor.SearchItem(
                title = "昔涟（游戏《崩坏：星穹铁道》中的角色）_百度百科",
                link = "https://baike.baidu.com/昔涟",
                snippet = "昔涟是游戏角色"
            )
        )
        // 含核心词 → 相关
        assertTrue(executor.isRelevant(xiliItem, listOf("昔涟")))
        // 只含"昔"（Bing 分词失败）→ 不相关
        val xiOnly = listOf(
            WebSearchLocalToolExecutor.SearchItem(
                title = "昔_百度百科",
                link = "https://baike.baidu.com/昔",
                snippet = "昔 xī - 汉典"
            )
        )
        assertFalse("Bing 分词失败（结果不含核心词）应判为不相关", executor.isRelevant(xiOnly, listOf("昔涟")))
        // 多候选：任一核心词命中即相关
        assertTrue(executor.isRelevant(xiliItem, listOf("星穹铁道", "昔涟")))
        // 核心词为空 → 无法判断，默认相关
        assertTrue(executor.isRelevant(xiOnly, emptyList()))
    }

    @Test
    fun `execute retries with core term when primary result irrelevant`() = runBlocking {
        // UXR7 问题 1：query="昔涟 是谁" 第一次搜索返回"昔"相关（分词失败）→
        // 自动用核心词"昔涟"单独重试 → 返回"昔涟"相关结果
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            val body = if (q.contains("昔涟 是谁")) {
                // 主查询：Bing 分词失败，返回"昔"相关
                rssBody(Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"))
            } else {
                // 核心词重试："昔涟"单独查询 → 返回正确结果
                rssBody(
                    Triple("昔涟（崩坏：星穹铁道角色）", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
                    Triple("昔涟 - BWIKI", "https://wiki.biligame.com/昔涟", "昔涟资料")
                )
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟 是谁", "maxResults" to 5)
        )
        assertEquals("应触发 2 次搜索（主查询 + 核心词重试）", 2, callCount)
        assertTrue("重试结果应包含核心词'昔涟'相关内容", result.contains("昔涟"))
        assertFalse("不应返回主查询的'昔'相关结果", result.contains("昔 xī"))
    }

    @Test
    fun `execute tries next core term when first retry irrelevant`() = runBlocking {
        // UXR7-R2 多候选：query="昔涟 星穹铁道 角色"，主查询坍缩 →
        // 候选 ["昔涟","星穹铁道"]，第一个"昔涟"重试仍不相关 → 第二个"星穹铁道"重试命中
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            val body = when {
                q.contains("昔涟 星穹铁道") -> {
                    // 主查询坍缩为"昔"
                    rssBody(Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"))
                }
                q == "昔涟" -> {
                    // 第一个核心词"昔涟"重试仍返回"昔"（极端：Bing 连短词也坍缩）
                    rssBody(Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"))
                }
                else -> {
                    // 第二个核心词"星穹铁道"重试命中
                    rssBody(Triple("星穹铁道-百度百科", "https://baike.baidu.com/星穹铁道", "星穹铁道是游戏"))
                }
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟 星穹铁道 角色", "maxResults" to 5)
        )
        assertEquals("应触发 3 次搜索（主查询 + 两个候选重试）", 3, callCount)
        assertTrue("最终应命中'星穹铁道'相关结果", result.contains("星穹铁道"))
    }

    @Test
    fun `execute marks search failed when all retries irrelevant`() = runBlocking {
        // UXR7 问题 1：主查询 + 全部核心词重试都不相关 → 返回"搜索失败"触发熔断（防循环达上限）
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟 是谁")
        )
        assertTrue("全部重试不相关应返回搜索失败（触发熔断）", result.startsWith("搜索失败"))
    }

    @Test
    fun `execute coerces maxResults above maximum to 8`() = runBlocking {
        // AC-B1 边界：LLM 传 maxResults=10（超上限）→ coerceIn 到 8，第 9 条不返回
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(
                    Triple("r1", "https://1.example.com", "s1"),
                    Triple("r2", "https://2.example.com", "s2"),
                    Triple("r3", "https://3.example.com", "s3"),
                    Triple("r4", "https://4.example.com", "s4"),
                    Triple("r5", "https://5.example.com", "s5"),
                    Triple("r6", "https://6.example.com", "s6"),
                    Triple("r7", "https://7.example.com", "s7"),
                    Triple("r8", "https://8.example.com", "s8"),
                    Triple("r9", "https://9.example.com", "s9")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "x", "maxResults" to 10)
        )
        assertTrue("应包含第 8 条", result.contains("r8"))
        assertFalse("超过 8 的结果不应包含", result.contains("r9"))
    }

    @Test
    fun `execute coerces maxResults below minimum to 1`() = runBlocking {
        // AC-B1 边界：LLM 传 maxResults=0（低于下限）→ coerceIn 到 1，仅返回第 1 条
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(
                    Triple("result-a", "https://a.example.com", "snip-a"),
                    Triple("result-b", "https://b.example.com", "snip-b")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "x", "maxResults" to 0)
        )
        assertTrue("应包含第 1 条", result.contains("result-a"))
        assertFalse("超过 1 条的结果不应包含", result.contains("result-b"))
    }

    @Test
    fun `execute uses default maxResults 5 when maxResults non numeric`() = runBlocking {
        // AC-B1 边界：maxResults 非数字 → 默认 5
        val engine = MockEngine { _ ->
            respond(
                content = rssBody(
                    Triple("r1", "https://1.example.com", "s1"),
                    Triple("r2", "https://2.example.com", "s2"),
                    Triple("r3", "https://3.example.com", "s3"),
                    Triple("r4", "https://4.example.com", "s4"),
                    Triple("r5", "https://5.example.com", "s5"),
                    Triple("r6", "https://6.example.com", "s6")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "x", "maxResults" to "three")
        )
        assertTrue("应包含第 5 条（默认上限）", result.contains("r5"))
        assertFalse("超过默认 5 条的结果不应包含", result.contains("r6"))
    }

    @Test
    fun `execute fails fast for blank query with full-width space`() = runBlocking {
        // AC-B3 边界：全角空格/NBSP 等空白 query 同样 fail-fast（trim 覆盖 isWhitespace 类字符）
        val executor = WebSearchLocalToolExecutor(noopClient())
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "\u3000\u00A0")
        )
        assertEquals("缺少必需参数 query", result)
    }
}
