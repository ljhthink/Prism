package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次6（Issue 2，RSS→HTML）专项单测：Bing HTML SERP 解析主路径 + 校名命中 golden。
 *
 * 背景证据：Bing `format=rss` 对"梧州市第一中学"等中文校名排名坍缩（只返回市级百科）；
 * 改用 HTML `li.b_algo` 后实测能直接命中学校官网/词条。本文件锁定：
 * - [WebSearchLocalToolExecutor.parseBingHtml]：真实 HTML 结构提取 title/link/snippet
 * - [WebSearchLocalToolExecutor.decodeBingUrl]：直链 + `//cn.bing.com/ck/a?...u=<base64>` 跳转解码
 * - execute 端到端：校名 query 返回学校而非市级百科（回归黄金用例）
 */
class WebSearchLocalToolExecutorHtmlBingParsingTest {

    private fun noopClient(): HttpClient =
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })

    private fun htmlBody(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder("""<html><body><ol id="b_results">""")
        items.forEach { (title, link, desc) ->
            sb.append("<li class=\"b_algo\"><h2><a href=\"").append(link).append("\">")
                .append(title).append("</a></h2><div class=\"b_caption\"><p>").append(desc)
                .append("</p></div></li>")
        }
        sb.append("</ol></body></html>")
        return sb.toString()
    }

    /** 模拟百度搜索页结果（h3>a + c-abstract）。 */
    private fun baiduBody(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder("""<html><div id="content_left">""")
        items.forEach { (title, link, desc) ->
            sb.append("<div class=\"result c-container\"><h3><a href=\"").append(link).append("\">")
                .append(title).append("</a></h3>")
            if (desc.isNotBlank()) {
                sb.append("<div class=\"c-abstract\">").append(desc).append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</div></html>")
        return sb.toString()
    }

    @Test
    fun `parseBingHtml extracts title link snippet from b_algo blocks`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = executor.parseBingHtml(
            htmlBody(
                Triple("梧州市第一中学-学校官网", "https://www.wzyz.com.cn/", "梧州市第一中学是一所百年名校"),
                Triple("梧州市第一中学_百度百科", "https://baike.baidu.com/梧州市第一中学", "梧州市第一中学简介")
            )
        )
        assertEquals(2, items.size)
        assertEquals("梧州市第一中学-学校官网", items[0].title)
        assertEquals("https://www.wzyz.com.cn/", items[0].link)
        assertEquals("梧州市第一中学是一所百年名校", items[0].snippet)
    }

    @Test
    fun `parseBingHtml skips blocks missing link or title`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val body = """
            <ol id="b_results">
            <li class="b_algo"><h2><a href="https://ok.example.com">正常标题</a></h2></li>
            <li class="b_algo"><h2>缺 href 的标题</h2></li>
            <li class="b_algo"><a href="https://nolink.example.com">unsupported</a></li>
            </ol>
        """.trimIndent()
        val items = executor.parseBingHtml(body)
        assertEquals("只保留含标题+a href 的块", 1, items.size)
        assertEquals("正常标题", items[0].title)
    }

    @Test
    fun `decodeBingUrl returns direct https link unchanged`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertEquals("https://baike.baidu.com/梧州市第一中学", executor.decodeBingUrl("https://baike.baidu.com/梧州市第一中学"))
        // 无 scheme 的 // 直链（常见于协议相对地址）补 https
        assertEquals("https://www.example.com/p", executor.decodeBingUrl("//www.example.com/p"))
    }

    @Test
    fun `decodeBingUrl decodes ck redirect link to real url`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        // `u=a1aHR0cHM6...` = base64url("https://...")，模拟 cn.bing.com /ck/a 跟踪跳转链接
        val target = "https://www.wzyz.com.cn/about"
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(target.toByteArray(Charsets.UTF_8)).removePrefix("a1")
        val ck = "//cn.bing.com/ck/a??&u=a1$b64&ntb=1"
        assertEquals(target, executor.decodeBingUrl(ck))
    }

    @Test
    fun `decodeBingUrl returns empty for invalid ck link or base64`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertEquals("", executor.decodeBingUrl("//cn.bing.com/ck/a??&ntb=1"))
        assertEquals("", executor.decodeBingUrl("//cn.bing.com/ck/a??&u=a1!!!invalid"))
    }

    @Test
    fun `execute returns school result not city when query is full school name`() = runBlocking {
        // 回归黄金用例（Issue 2）：HTML SERP 返回校名匹配结果而非市级百科；
        // 主查询命中"梧州市第一中学"判为相关，不再降级、不返回城市。
        val engine = MockEngine { request ->
            val q = request.url.parameters["q"].orEmpty()
            val body = htmlBody(
                Triple("梧州市第一中学_百度百科", "https://baike.baidu.com/梧州市第一中学", "梧州市第一中学是广西最早建立的中学之一"),
                if (q.contains("梧州市第一中学")) {
                    Triple("梧州市第一中学-学校官网", "https://www.wzyz.com.cn/", "梧州市第一中学官网，百年名校")
                } else {
                    Triple("梧州市第一中学-招生网", "https://www.wzyz.com.cn/zs", "梧州市第一中学招生信息")
                }
            )
            respond(content = body, status = HttpStatusCode.OK)
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        // 直接命中学校（题目精确含校名），不应退化为市/其它
        assertTrue("应命中校名相关内容", result.contains("梧州市第一中学_百度百科"))
        assertTrue("应命中学校官网链接", result.contains("wzyz.com.cn"))
    }

    @Test
    fun `extractCoreTerms keeps full school name ending in zhong xue`() {
        // 回归：后缀表不得再剥实体词"中学/大学/学校"（v1 批次6 修复）——"梧州市第一中学"保持完整
        val executor = WebSearchLocalToolExecutor(noopClient())
        val terms = executor.extractCoreTerms("梧州市第一中学")
        assertTrue("应保留完整校名实体", terms.contains("梧州市第一中学"))
        assertFalse("不应误剥出'梧州市第一'", terms.contains("梧州市第一"))
    }

    // ==================== 百度兜底（v1 批次7，Issue 2） ====================

    @Test
    fun `parseBaiduHtml extracts title link snippet from h3 a`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = executor.parseBaiduHtml(
            baiduBody(
                Triple("梧州市第一中学_百度百科", "http://www.baidu.com/link?url=abc", "梧州市第一中学是广西最早建立的中学"),
                Triple("梧州一中-学校官网", "http://www.baidu.com/link?url=def", "")
            )
        )
        assertEquals(2, items.size)
        assertEquals("梧州市第一中学_百度百科", items[0].title)
        assertEquals("http://www.baidu.com/link?url=abc", items[0].link)
        assertEquals("梧州市第一中学是广西最早建立的中学", items[0].snippet)
    }

    @Test
    fun `parseBaiduHtml skips h3 blocks without anchor`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val body = """<html><h3>无链接标题</h3><h3><a href="http://www.baidu.com/link?url=x">正常标题</a></h3></html>"""
        val items = executor.parseBaiduHtml(body)
        assertEquals("只保留含 <a> 的 h3", 1, items.size)
        assertEquals("正常标题", items[0].title)
    }

    @Test
    fun `execute falls back to baidu when bing returns city for school`() = runBlocking {
        // 黄金用例（Issue 2）：Bing 返回城市（无关），百度返回学校 → 最终必须命中学校。
        // 模拟真实：Bing 只返回"梧州市(城市)"，百度返回"梧州市第一中学"学校百科。
        val engine = MockEngine { request ->
            val host = request.url.host
            val body = if (host.contains("baidu")) {
                baiduBody(
                    Triple("梧州市第一中学_百度百科", "http://www.baidu.com/link?url=school", "梧州市第一中学是广西最早建立的中学"),
                    Triple("梧州一中（荣列自治区示范性高中）", "http://www.baidu.com/link?url=gov", "梧州一中即梧州市第一中学")
                )
            } else {
                htmlBody(Triple("梧州市（中国广西壮族自治区下辖地级市）_百度百科", "https://baike.baidu.com/item/梧州", "梧州市是地级市"))
            }
            respond(content = body, status = HttpStatusCode.OK)
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        // Bing 全不相关 → 百度兜底命中学校
        assertTrue("百度兜底应把学校塞进引用来源", result.contains("梧州市第一中学_百度百科"))
        assertFalse("不应再返回 Bing 的城市百科", result.contains("下辖地级市"))
    }
}