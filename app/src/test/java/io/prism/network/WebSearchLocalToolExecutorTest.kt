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
        // AC-B1：additionalProperties=false（严格参数）+ query 必填 + maxResults 1..10（O5 扩容后上限，JSON Schema 结构断言）
        val def = WebSearchLocalToolExecutor.buildToolDefinition()
        val params = def.function.parameters.toString()
        assertTrue("additionalProperties 应为 false", params.contains("\"additionalProperties\":false"))
        assertTrue("query 应声明 type=string", params.contains("\"type\":\"string\""))
        assertTrue("maxResults 应声明 type=integer", params.contains("\"type\":\"integer\""))
        assertTrue("maxResults 应声明 minimum=1", params.contains("\"minimum\":1"))
        assertTrue("maxResults 应声明 maximum=10", params.contains("\"maximum\":10"))
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
    fun `execute coerces maxResults above maximum to 10`() = runBlocking {
        // O5（PRD UXR8）：AC-B1 边界更新——LLM 传 maxResults=99（超上限）→ coerceIn 到 10，
        // 第 11 条不返回。query 为纯英文（无核心词变体）→ 不触发多查询合并路径。
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
                    Triple("r9", "https://9.example.com", "s9"),
                    Triple("r10", "https://10.example.com", "s10"),
                    Triple("r11", "https://11.example.com", "s11")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "x", "maxResults" to 99)
        )
        assertTrue("应包含第 10 条（O5 上限 8→10）", result.contains("r10"))
        assertFalse("超过 10 的结果不应包含", result.contains("r11"))
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

    // ==================== O5（PRD UXR8）：多查询合并扩容 + URL 归一化去重 + 引用要求 ====================

    @Test
    fun `normalizeUrl dedupes tracking params www prefix trailing slash and case`() {
        // O5：URL 归一化（合并去重判据）——同一网页的常见差异形态应归一为相同键
        val executor = WebSearchLocalToolExecutor(noopClient())
        // utm_* / spm 跟踪参数剔除，语义参数（id）保留
        assertEquals(
            "https://example.com/p?id=2",
            executor.normalizeUrl("https://example.com/p?utm_source=bing&id=2")
        )
        assertEquals(
            "https://example.com/p?id=2",
            executor.normalizeUrl("https://example.com/p?id=2&spm=1001.2014")
        )
        // www 前缀 + host 大小写 + 尾部斜杠 + fragment
        assertEquals(
            "https://example.com/a",
            executor.normalizeUrl("HTTPS://WWW.Example.COM/a/#section")
        )
        // path 大小写保留（URL 路径大小写敏感，不应误合并不同页面）
        assertEquals("https://example.com/Wiki-Page", executor.normalizeUrl("https://www.example.com/Wiki-Page/"))
        // 全部参数都是跟踪参数 → 退化为纯 path
        assertEquals("https://example.com/p", executor.normalizeUrl("https://example.com/p?utm_medium=cpc&ref=x"))
        // 非 http(s) 链接原样返回（仅去尾斜杠）
        assertEquals("ftp://files.example.com/x", executor.normalizeUrl("ftp://files.example.com/x/"))
        // http 与 https 不互相归一（不同站点语义）
        assertFalse(
            "http/https 不应归一为相同键",
            executor.normalizeUrl("http://example.com/a") == executor.normalizeUrl("https://example.com/a")
        )
    }

    @Test
    fun `execute merges variant queries when maxResults at least 8`() = runBlocking {
        // O5：maxResults=8（触发阈值）且主查询相关 → 互补核心词变体查询合并。
        // query="昔涟 星穹铁道 角色"（核心词 = [昔涟, 星穹铁道]，"角色"为停用词）：
        // - 主查询返回 3 条（含"昔涟"，判相关）
        // - 变体"昔涟"返回 3 条：1 条跟踪参数重复 URL（归一化去重）、1 条不含核心词（过滤）、1 条新来源（并入）
        // - 变体"星穹铁道"返回 1 条新来源（并入）
        // → 合并 5 条（3 主 + 2 新），总请求 3 次（预算 ≤3）
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            val body = when {
                q.contains("昔涟 星穹铁道") -> rssBody(
                    Triple("昔涟_百度百科", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
                    Triple("昔涟 - BWIKI", "https://wiki.biligame.com/昔涟", "昔涟资料"),
                    Triple("昔涟角色解析", "https://x.com/昔涟", "昔涟强度分析")
                )
                q == "昔涟" -> rssBody(
                    // 与主查询同页（跟踪参数差异）→ 去重
                    Triple("昔涟_百度百科（镜像）", "https://baike.baidu.com/昔涟?utm_source=variant", "昔涟"),
                    // 不含核心词 → 变体过滤
                    Triple("无关结果", "https://irrelevant.example.com/广告", "与查询无关"),
                    // 新来源 → 并入
                    Triple("昔涟同人图集", "https://art.example.com/昔涟", "昔涟同人作品")
                )
                else -> rssBody(
                    Triple("星穹铁道官网", "https://sr.example.com/星穹铁道", "星穹铁道是米哈游游戏")
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
            mapOf("query" to "昔涟 星穹铁道 角色", "maxResults" to 8)
        )
        assertEquals("应触发 3 次搜索（主查询 + 两个变体）", 3, callCount)
        // 主查询 3 条 + 变体新增 2 条 = 5 条（序号 1..5）
        assertTrue("应包含主查询结果", result.contains("昔涟 - BWIKI"))
        assertTrue("应包含变体'昔涟'新来源", result.contains("昔涟同人图集"))
        assertTrue("应包含变体'星穹铁道'新来源", result.contains("星穹铁道官网"))
        assertFalse("跟踪参数重复 URL 不应重复计入", result.contains("昔涟_百度百科（镜像）"))
        assertFalse("变体结果中不含核心词的应被过滤", result.contains("无关结果"))
        // 合并结果重新编号 1..5
        assertTrue("应重新编号至第 5 条", result.contains("5. 昔涟同人图集") || result.contains("5. 星穹铁道官网"))
    }

    @Test
    fun `execute does not merge when maxResults below 8`() = runBlocking {
        // O5：maxResults=5（默认路径，低于触发阈值 8）→ 单查询即返回，不发变体请求
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                content = rssBody(
                    Triple("昔涟_百度百科", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
                    Triple("昔涟 - BWIKI", "https://wiki.biligame.com/昔涟", "昔涟资料")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟 是谁", "maxResults" to 5)
        )
        assertEquals("低于阈值不应触发变体合并请求", 1, callCount)
    }

    @Test
    fun `execute merge tolerates variant query failure`() = runBlocking {
        // O5：变体查询网络失败不拖垮主结果——失败变体跳过，成功变体照常并入
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            when {
                q.contains("昔涟 星穹铁道") -> respond(
                    content = rssBody(Triple("昔涟_百度百科", "https://baike.baidu.com/昔涟", "昔涟是游戏角色")),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
                )
                q == "昔涟" -> throw RuntimeException("variant connection refused")
                else -> respond(
                    content = rssBody(Triple("星穹铁道官网", "https://sr.example.com/星穹铁道", "星穹铁道是游戏")),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
                )
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟 星穹铁道 角色", "maxResults" to 10)
        )
        assertEquals("应触发 3 次搜索（主查询 + 失败变体 + 成功变体）", 3, callCount)
        assertTrue("主结果应正常返回", result.contains("昔涟_百度百科"))
        assertTrue("成功变体应并入", result.contains("星穹铁道官网"))
    }

    @Test
    fun `execute merge caps merged results at 16`() = runBlocking {
        // O5：合并去重总上限 16（防 token 溢出）——主查询 10 条 + 变体 10 条全为新 URL → 截断 16
        fun items(prefix: String, count: Int, withTerm: Boolean = true): Array<Triple<String, String, String>> =
            (1..count).map { i ->
                Triple(
                    if (withTerm) "$prefix-昔涟-$i" else "irrelevant-$i",
                    "https://$prefix-$i.example.com/p$i",
                    if (withTerm) "昔涟相关 $prefix $i" else "不含核心词"
                )
            }.toTypedArray()
        val engine = MockEngine { request ->
            val q = request.url.parameters["q"].orEmpty()
            val body = when {
                q.contains("昔涟 星穹铁道") -> rssBody(*items("main", 10))
                q == "昔涟" -> rssBody(*items("v1", 10))
                else -> rssBody(*items("v2", 10))
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
            mapOf("query" to "昔涟 星穹铁道 角色", "maxResults" to 10)
        )
        val numberedEntries = Regex("""(?m)^\d+\.\s""").findAll(result).count()
        assertEquals("合并去重后应截断至 16 条", 16, numberedEntries)
        assertTrue("应含第 16 条", result.contains("16. "))
        assertFalse("不应含第 17 条", result.contains("17. "))
    }

    @Test
    fun `execute merge falls back to primary when variants add nothing`() = runBlocking {
        // O5：变体未带来任何新来源（全为去重重复或不相关）→ 回退单查询结果，不无谓多占 token
        val engine = MockEngine { request ->
            val q = request.url.parameters["q"].orEmpty()
            val body = when {
                q.contains("昔涟 星穹铁道") -> rssBody(
                    Triple("昔涟_百度百科", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
                    Triple("昔涟 - BWIKI", "https://wiki.biligame.com/昔涟", "昔涟资料")
                )
                else -> rssBody(
                    // 与主查询相同 URL（www + 尾斜杠差异）→ 归一化后重复
                    Triple("昔涟_百度百科", "https://www.baike.baidu.com/昔涟/", "昔涟"),
                    // 不含核心词 → 过滤
                    Triple("广告", "https://ads.example.com/x", "推广内容")
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
            mapOf("query" to "昔涟 星穹铁道 角色", "maxResults" to 8)
        )
        // 回退主结果（2 条），不受 maxResults=8 影响，也不含变体重复/无关内容
        assertTrue("应回退返回主查询结果", result.contains("昔涟 - BWIKI"))
        assertFalse("不应包含被过滤的广告", result.contains("ads.example.com"))
    }

    @Test
    fun `execute result includes inline citation requirement header`() = runBlocking {
        // O5 引用策略：结果头部追加内联 [N] 引用要求（驱动 LLM 覆盖全部相关来源）
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
        assertTrue("应包含引用要求指令", result.contains("【引用要求】"))
        assertTrue("应包含内联编号引用示例", result.contains("[1]"))
        assertTrue("边界标记仍应在最前", result.startsWith("【网络搜索外部内容"))
    }

    // ==================== G-03（guardrail TKN-UXR8-B2-GUARDRAIL-001）：搜索预算感知 ====================

    @Test
    fun `hasRequestBudget allows request when elapsed plus worst-case timeout fits budget`() {
        // 判据：elapsed + 10s <= 30s - 3s → elapsed <= 17s
        assertTrue("17s 已耗时应仍有预算", WebSearchLocalToolExecutor.hasRequestBudget(17_000L))
        assertTrue("0ms 已耗时应有预算", WebSearchLocalToolExecutor.hasRequestBudget(0L))
    }

    @Test
    fun `hasRequestBudget rejects request when budget exhausted`() {
        assertFalse("18s 已耗时应无预算", WebSearchLocalToolExecutor.hasRequestBudget(18_000L))
        assertFalse("30s 已耗时应无预算", WebSearchLocalToolExecutor.hasRequestBudget(30_000L))
    }

    @Test
    fun `hasRequestBudget boundary is exactly 17 seconds`() {
        assertTrue(
            "恰好 17s（含）应有预算",
            WebSearchLocalToolExecutor.hasRequestBudget(
                WebSearchLocalToolExecutor.TOTAL_TOOL_BUDGET_MS -
                    WebSearchLocalToolExecutor.BUDGET_SAFETY_MARGIN_MS -
                    WebSearchLocalToolExecutor.SEARCH_REQUEST_TIMEOUT_MS
            )
        )
        assertFalse(
            "17s + 1ms 应无预算",
            WebSearchLocalToolExecutor.hasRequestBudget(
                WebSearchLocalToolExecutor.TOTAL_TOOL_BUDGET_MS -
                    WebSearchLocalToolExecutor.BUDGET_SAFETY_MARGIN_MS -
                    WebSearchLocalToolExecutor.SEARCH_REQUEST_TIMEOUT_MS + 1
            )
        )
    }

    @Test
    fun `G2-03 budget constants stay aligned with SkillExecutor and client config`() {
        // 常量漂移守护（guardrail TKN-UXR8-B2-GUARDRAIL-002 G2-03）：
        // TOTAL_TOOL_BUDGET_MS 必须与 SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS（外层
        // withTimeout）一致；SEARCH_REQUEST_TIMEOUT_MS 必须与 PrismApplication 中
        // searchHttpClient 的 requestTimeoutMillis（private 常量 10_000L，L830）一致。
        // 任一端单方面修改时本测试失败，防止预算判据失真。
        assertEquals(
            "总预算须与 SkillExecutor 默认工具超时对齐",
            io.prism.skill.SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS,
            WebSearchLocalToolExecutor.TOTAL_TOOL_BUDGET_MS
        )
        assertEquals(
            "单请求超时须与 PrismApplication searchHttpClient 配置对齐",
            10_000L,
            WebSearchLocalToolExecutor.SEARCH_REQUEST_TIMEOUT_MS
        )
    }
}
