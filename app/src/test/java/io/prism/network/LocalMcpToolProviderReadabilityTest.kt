package io.prism.network

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.fs.FilesystemMcpServer
import io.prism.fs.InMemoryFileAccess
import io.prism.fs.ToolConfirmationGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-1505（v1 批次15 A4）Readability4J 提纯换库测试。
 *
 * 覆盖：
 * - 大文档（正文 ≥ 500 字符，超过 Readability wordThreshold）article 型样本：
 *   Readability 主路径提纯，含正文关键词且不含 script/nav/footer 内容
 * - 大文档论坛型样本（帖子流 + 广告侧栏）：保留楼层正文、剔除广告
 * - 小文档（< 500 字符）降级策略：Readability 对小文档退化为「倾倒 body」（噪声混入），
 *   正则版精确提取优先（V1Batch9AcceptanceSupplementTest main 标签回归的对策）
 * - og:title 与正文开头不同时前置页面主题（小文档路径同样生效）
 * - 正则降级函数 [LocalMcpToolProvider.extractReadableTextRegexFallback] 直接单测（行为不变）
 */
class LocalMcpToolProviderReadabilityTest {

    private val fetchConfig = McpServerConfig(
        name = "Fetch",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    private val fs = InMemoryFileAccess().addDirectory("notes").addFile("notes/a.txt", "x")
    private val server = FilesystemMcpServer(fs, ToolConfirmationGate { _, _ -> true })

    private fun providerWith(client: HttpClient) = LocalMcpToolProvider(server, client)

    private fun fetch(url: String, engine: MockEngine): String = runBlocking {
        providerWith(HttpClient(engine) { followRedirects = false })
            .callTool(fetchConfig, "fetch", mapOf("url" to url))
    }

    /** 生成 N 字符的中文填充句（无逗号以外的标点干扰，保证段落 ≥ 25 字符可参与评分）。 */
    private fun filler(sentence: String, targetChars: Int): String =
        sentence.repeat(targetChars / sentence.length + 1).take(targetChars)

    // ==================== Readability4J 主路径（大文档，正文 ≥ 500 字符） ====================

    @Test
    fun `fetch purifies long article page with script and nav noise via readability`() = runBlocking {
        // article 型大文档样本：正文段落合计远超 500 字符（wordThreshold），
        // Readability 打分选出 article 容器；script/nav/footer 为噪声
        val p1 = filler("梧州市第一中学是一所百年名校，创办于一九零五年，历史悠久。", 400)
        val p2 = filler("学校位于广西梧州市万秀区，是当地重点中学，师资力量雄厚。", 400)
        val engine = MockEngine {
            respond(
                content = """<html><head><script>var tracking = "should-not-appear";</script>
                    <style>.nav { color: red; }</style></head>
                    <body><nav>首页导航 关于我们 登录</nav>
                    <article><h1>梧州市第一中学简介</h1>
                    <p>$p1</p><p>$p2</p></article>
                    <footer>版权所有 备案号</footer>
                    <script>console.log("footer script");</script></body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = fetch("https://example.com/school", engine)

        assertTrue("应提取正文标题", result.contains("梧州市第一中学简介"))
        assertTrue("应提取正文段落", result.contains("百年名校"))
        assertTrue("应提取第二段", result.contains("重点中学"))
        assertFalse("不应包含导航噪声", result.contains("首页导航"))
        assertFalse("不应包含脚本文本", result.contains("should-not-appear"))
        assertFalse("不应包含第二段脚本", result.contains("footer script"))
        assertFalse("不应包含页脚噪声", result.contains("版权所有"))
        assertFalse("不应含 HTML 标签", result.contains("<article"))
    }

    @Test
    fun `fetch purifies long forum thread page keeping posts dropping ads`() = runBlocking {
        // 论坛型大文档样本：无 <article>，正文为帖子流容器（每层 > 25 字符参与评分）+ 广告侧栏
        val post1 = filler("一楼正文内容：这个问题我研究了一周，答案是 settings.yml 里加 json 格式。", 300)
        val post2 = filler("二楼回复内容：赞同楼上，search.formats 配置段必须包含 json 才能返回结构化结果。", 300)
        val engine = MockEngine {
            respond(
                content = """<html><body>
                    <div class="thread"><h1>求助：如何配置 SearXNG</h1>
                    <div class="post"><p>$post1</p></div>
                    <div class="post"><p>$post2</p></div></div>
                    <aside class="ad-sidebar sponsor-banner">广告位推广文本 点此购买</aside>
                    </body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = fetch("https://example.com/thread/1", engine)

        assertTrue("应保留一楼正文", result.contains("一楼正文内容"))
        assertTrue("应保留二楼回复", result.contains("二楼回复内容"))
        assertFalse("不应包含广告侧栏", result.contains("广告位推广文本"))
    }

    // ==================== 小文档降级策略（< wordThreshold=500） ====================

    @Test
    fun `fetch small page prefers regex purification over readability body dump`() = runBlocking {
        // 回归对策：小文档下 Readability 走「倾倒 body」路径会把 nav/footer 混入输出；
        // 小文档策略改用正则版（script/nav/footer 先剥 + main 容器精确提取）
        val engine = MockEngine {
            respond(
                content = """<html><body><nav>菜单</nav>
                    <main><h1>首页</h1><p>正文内容A</p><p>正文内容B</p></main>
                    <footer>版权</footer></body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = fetch("https://example.com/home", engine)

        assertTrue("应提取 main 内标题", result.contains("首页"))
        assertTrue("应提取 main 内段落", result.contains("正文内容A"))
        assertFalse("不应含导航噪声（body 倾倒被正则版替代）", result.contains("菜单"))
        assertFalse("不应含页脚噪声", result.contains("版权"))
    }

    @Test
    fun `fetch falls back to regex text when page has no extractable article structure`() = runBlocking {
        // 无 <p>/<h1>/<article> 结构：正则兜底裸剥离 div 文本（与 Readability 输出同文）
        val engine = MockEngine {
            respond(
                content = """<html><body><div>纯文本内容在div里没有段落标签也能读到</div></body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = fetch("https://example.com/divonly", engine)

        assertTrue("正则降级兜底应产出 div 文本", result.contains("纯文本内容在div里"))
    }

    @Test
    fun `fetch prepends og title when different from content head`() = runBlocking {
        // og:title 与正文开头不同 → title 前置（小文档路径同样生效）
        val engine = MockEngine {
            respond(
                content = """<html><head><meta property="og:title" content="页面主标题"/>
                    <script>var meta = 1;</script></head>
                    <body><article><h1>文章标题</h1>
                    <p>正文第一段落内容，包含关键词。</p></article></body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val result = fetch("https://example.com/og", engine)

        assertTrue("应前置 og:title", result.contains("页面主标题"))
        assertTrue("应提取正文标题", result.contains("文章标题"))
        assertTrue("应提取正文段落", result.contains("正文第一段落内容"))
        assertFalse("不应包含脚本文本", result.contains("var meta"))
    }

    // ==================== 正则降级兜底 ====================

    @Test
    fun `regex fallback strips scripts and extracts article blocks directly`() {
        // 直接单测降级函数（internal）：行为与 US-1505 重构前完全一致
        val provider = LocalMcpToolProvider(server, null)
        val html = """<html><head><script>var x = "script-noise";</script></head>
            <body><nav>导航噪声</nav><article><h1>降级标题</h1><p>降级段落正文</p></article></body></html>"""

        val text = provider.extractReadableTextRegexFallback(html)

        assertTrue("降级应提取标题", text.contains("降级标题"))
        assertTrue("降级应提取段落", text.contains("降级段落正文"))
        assertFalse("降级不应包含脚本", text.contains("script-noise"))
        assertFalse("降级不应包含导航", text.contains("导航噪声"))

        // 纯脚本空壳：降级同样返回空（调用方按空壳处理）
        val shell = provider.extractReadableTextRegexFallback(
            """<html><body><script>window.location='/challenge';</script></body></html>"""
        )
        assertFalse("纯脚本壳降级应为空", shell.isNotBlank())
    }
}
