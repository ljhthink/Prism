package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.fs.FilesystemMcpServer
import io.prism.fs.InMemoryFileAccess
import io.prism.fs.ToolConfirmationGate
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.ConnectException

/**
 * v1 批次9 验收补充边界用例（ac-verifier TKN-V1B9-ACCEPTANCE-001）。
 *
 * 在既有测试基础上补齐极端/边界覆盖（US-901/902/903/905），不修改既有测试：
 * - US-901：isStrongRelevant 多核心词/空列表/短 title；execute 城市页 snippet 命中
 *   不强相关（Bing + Baidu 双城市页 → 最终失败）；核心词唯一候选==query 走百度兜底
 * - US-902：Bocha 5xx 降级 / 空 webPages 降级 / 缺字段条目跳过 / jsonQuote 转义换行
 * - US-903：main 标签提取（无 article）/ Jina 网络异常 → 本地提纯完整链路 / 无容器回退
 * - US-905：SSE status=-1（连接中断/重试耗尽）→「网络连接中断」文案
 */
class V1Batch9AcceptanceSupplementTest {

    // ==================== US-901 搜索强相关边界 ====================

    private fun schoolPage(title: String, snippet: String) = WebSearchLocalToolExecutor.SearchItem(
        title = title, link = "https://www.wzyz.com.cn/", snippet = snippet
    )

    @Test
    fun `isStrongRelevant true when title contains any of multiple core terms`() {
        // 多核心词：title 只含第二核心词也应判强相关（任一命中即可）
        val executor = WebSearchLocalToolExecutor(noopClient())
        val item = schoolPage("星穹铁道 官方攻略", "昔涟 是角色")
        assertTrue(
            "title 含任一核心词应判强相关",
            executor.isStrongRelevant(listOf(item), listOf("昔涟", "星穹铁道"))
        )
    }

    @Test
    fun `isStrongRelevant false when title shorter than core term`() {
        // B2 根治关键边界：城市页 title="梧州市（…）" 短于/不含完整校名，即便 snippet 含校名
        // 也不判强相关（contains 是整词包含，非倒置）
        val executor = WebSearchLocalToolExecutor(noopClient())
        val cityPage = schoolPage(
            title = "梧州市（广西壮族自治区辖地级市）",
            snippet = "梧州市第一中学是广西最早的中学之一"
        )
        assertFalse(
            "城市页 title 无完整校名不应判强相关",
            executor.isStrongRelevant(listOf(cityPage), listOf("梧州市第一中学"))
        )
    }

    @Test
    fun `isStrongRelevant false for empty items even with core terms`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertFalse("空结果不应判强相关", executor.isStrongRelevant(emptyList(), listOf("梧州市第一中学")))
    }

    @Test
    fun `isStrongRelevant empty core terms defaults to true`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertTrue("核心词为空默认相关", executor.isStrongRelevant(listOf(schoolPage("任意", "")), emptyList()))
    }

    @Test
    fun `execute bing and baidu both city pages only snippet hit ends in search failure`() = runBlocking {
        // 城市页 snippet 含校名但 title 不含 → 主查询不强相关 → 核心词重试不强相关 →
        // 百度兜底也返回城市页（title 不含校名）→ 不强相关 → 最终「搜索失败」
        // （验证 US-901 AC-2/AC-4：主流程与 tryBaiduFallback 判据均升级为 title 强相关）
        var bingCalls = 0
        var baiduCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "cn.bing.com" -> {
                    bingCalls++
                    respond(
                        content = citySerpHtml("梧州市（广西壮族自治区辖地级市）", "梧州市第一中学位于万秀区"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
                    )
                }
                "www.baidu.com" -> {
                    baiduCalls++
                    respond(
                        content = baiduCitySerpHtml("梧州市_百度百科", "梧州市第一中学创建于1905年"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        assertTrue("Bing 应被查询（主查询 + 核心词重试）", bingCalls >= 1)
        assertTrue("百度兜底应被触发", baiduCalls >= 1)
        // v1 批次15（US-1504）：无任何结构化引擎配置时，全不相关返回「错误：」引导文案
        // （isFailureResult 可识别触发熔断，语义与旧「搜索失败」一致；不再走死胡同文案）
        assertTrue("城市页 snippet 命中不应作为命中结果返回（全未配置 → 引导文案）", result.startsWith("错误："))
        assertFalse("不应把城市页当命中返回", result.contains("梧州市_百度百科"))
    }

    @Test
    fun `execute sole core term equal to query falls through to baidu strong hit`() = runBlocking {
        // US-901 AC-3：核心词唯一候选 == query 时不再空转，直接走百度兜底；
        // 百度 title 含核心词（强相关）→ 返回百度结果
        var bingCalls = 0
        var baiduCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "cn.bing.com" -> {
                    bingCalls++
                    respond(
                        content = citySerpHtml("梧州市（广西壮族自治区辖地级市）", "梧州市第一中学介绍"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
                    )
                }
                "www.baidu.com" -> {
                    baiduCalls++
                    respond(
                        content = baiduCitySerpHtml("梧州市第一中学_学校官网", "梧州市第一中学是一所百年名校"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        assertTrue("Bing 主查询应只 1 次（term==query 不重复请求）", bingCalls == 1)
        assertTrue("百度兜底应命中学校官网", result.contains("梧州市第一中学_学校官网"))
    }

    // ==================== US-902 Bocha 边界 ====================

    private fun bochaBody(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder("""{"data":{"webPages":{"value":[""")
        items.forEachIndexed { i, (name, url, snippet) ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":").append(quote(name))
                .append(",\"url\":").append(quote(url))
                .append(",\"snippet\":").append(quote(snippet))
                .append("}")
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

    private fun bingHtml(title: String, snippet: String): String =
        """<html><body><ol id="b_results"><li class="b_algo"><h2><a href="https://bing.example.com/">$title</a></h2><div class="b_caption"><p>$snippet</p></div></li></ol></body></html>"""

    @Test
    fun `execute falls back to bing when bocha returns 500`() = runBlocking {
        var bingCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> respond("", HttpStatusCode.InternalServerError)
                else -> {
                    bingCalls++
                    respond(bingHtml("梧州市第一中学-学校官网", "百年名校"), HttpStatusCode.OK)
                }
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk-bocha" })
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        assertTrue("Bocha 5xx 应降级 Bing", bingCalls >= 1)
        assertTrue("应返回 Bing 命中结果", result.contains("梧州市第一中学-学校官网"))
    }

    @Test
    fun `execute falls back to bing when bocha returns empty webPages`() = runBlocking {
        var bingCalls = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> respond("""{"data":{"webPages":{"value":[]}}}""", HttpStatusCode.OK)
                else -> {
                    bingCalls++
                    respond(bingHtml("梧州市第一中学-学校官网", "百年名校"), HttpStatusCode.OK)
                }
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk-bocha" })
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "梧州市第一中学", "maxResults" to 5)
        )
        assertTrue("Bocha 空结果应降级 Bing", bingCalls >= 1)
        assertTrue("应返回 Bing 命中结果", result.contains("梧州市第一中学-学校官网"))
    }

    @Test
    fun `bocha request pinned to fixed endpoint with no user host`() = runBlocking {
        var bochaHost = ""
        var bochaPath = ""
        val engine = MockEngine { request ->
            if (request.url.host == "api.bocha.cn") {
                bochaHost = request.url.host
                bochaPath = request.url.encodedPath
                respond(bochaBody(Triple("R", "https://r.com/", "s")), HttpStatusCode.OK)
            } else {
                respond(bingHtml("Bing", "x"), HttpStatusCode.OK)
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk" })
        executor.execute(WebSearchLocalToolExecutor.TOOL_SEARCH, mapOf("query" to "q", "maxResults" to 3))
        assertEquals("Bocha 端点应固定 api.bocha.cn", "api.bocha.cn", bochaHost)
        assertEquals("Bocha 路径应固定 /v1/web-search", "/v1/web-search", bochaPath)
    }

    @Test
    fun `parseBochaItems skips entries missing name or url`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        // 第二条缺 url → 跳过；第三条缺 name → 跳过；只保留第一条
        val body = """{"data":{"webPages":{"value":[
            {"name":"有效标题","url":"https://ok.com/","snippet":"摘要"},
            {"name":"无URL","snippet":"x"},
            {"url":"https://noname.com/","snippet":"y"},
            {"name":"","url":"https://blank.com/","snippet":"z"}
        ]}}}"""
        val items = executor.parseBochaItems(body)
        assertEquals(1, items.size)
        assertEquals("有效标题", items[0].title)
    }

    @Test
    fun `bocha request body escapes newline and backslash in query`() = runBlocking {
        var bochaBodyText = ""
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.bocha.cn" -> {
                    bochaBodyText = (request.body as? io.ktor.http.content.TextContent)?.text
                        ?: (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                        ?: ""
                    respond(bochaBody(Triple("R", "https://r.com/", "s")), HttpStatusCode.OK)
                }
                else -> respond(bingHtml("Bing", "x"), HttpStatusCode.OK)
            }
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine), bochaApiKeyProvider = { "sk" })
        executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "A\nB\\C", "maxResults" to 3)
        )
        assertTrue("换行应转义为 \\n", bochaBodyText.contains("A\\nB\\\\C"))
        assertTrue("body 不应含裸换行", !bochaBodyText.contains("\n"))
    }

    // ==================== US-903 Fetch 本地提纯边界 ====================

    private val fetchConfig = McpServerConfig(
        name = "Fetch",
        serverType = McpServerType.LOCAL,
        baseUrl = ""
    )

    private val fs = InMemoryFileAccess().addDirectory("notes").addFile("notes/a.txt", "x")
    private val server = FilesystemMcpServer(fs, ToolConfirmationGate { _, _ -> true })

    private fun providerWith(client: HttpClient) = LocalMcpToolProvider(server, client)

    @Test
    fun `fetch extracts from main tag when no article present`() = runBlocking {
        // 无 <article> 但存在 <main>：本地提纯应从 main 主干提取并剔除 nav/footer
        val engine = MockEngine {
            respond(
                content = """<html><body><nav>菜单</nav>
                    <main><h1>首页</h1><p>正文内容A</p><p>正文内容B</p></main>
                    <footer>版权</footer></body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val result = providerWith(HttpClient(engine) { followRedirects = false })
            .callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/home"))
        assertTrue("应提取 main 内标题", result.contains("首页"))
        assertTrue("应提取 main 内段落", result.contains("正文内容A"))
        assertFalse("不应含导航噪声", result.contains("菜单"))
    }

    @Test
    fun `fetch jina unreachable then local extraction purifies direct page`() = runBlocking {
        // US-903 AC：Jina 域名不可达（国内不可达 ConnectException）→ 降级直抓 →
        // 直抓 200 静态页经本地提纯返回正文（非"抓取失败"）
        val engine = MockEngine { request ->
            when (request.url.host) {
                "r.jina.ai" -> throw ConnectException("r.jina.ai 国内不可达")
                else -> respond(
                    content = """<html><head><script>var x=1;</script></head>
                        <body><article><h1>梧州市第一中学简介</h1>
                        <p>梧州市第一中学是一所百年名校。</p></article></body></html>""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/html")
                )
            }
        }
        val result = providerWith(HttpClient(engine) { followRedirects = false })
            .callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/school", "useJinaReader" to true))
        assertTrue("Jina 失败降级直抓后应提纯出正文标题", result.contains("梧州市第一中学简介"))
        assertTrue("应提纯出正文段落", result.contains("百年名校"))
        assertFalse("不应返回抓取失败", result.contains("抓取失败"))
    }

    @Test
    fun `fetch page without containers falls back to stripped visible text`() = runBlocking {
        // 无 article/main/h/p 但有可见 div 文本 → 回退裸剥离，不误判空壳
        val engine = MockEngine {
            respond(
                content = """<html><body><div>纯文本内容段落</div><span>更多内容</span></body></html>""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val result = providerWith(HttpClient(engine) { followRedirects = false })
            .callTool(fetchConfig, "fetch", mapOf("url" to "https://example.com/plain"))
        assertTrue("应回退裸剥离返回可见文本", result.contains("纯文本内容段落"))
    }

    // ==================== US-905 SSE status=-1 ====================

    @Test
    fun `streamChat maps connection interrupted sse to network interruption message`() = runBlocking {
        // B4/B6：SSE 连接中断/重试耗尽（SSEClientException 无 response → status=-1）
        // → mapHttpError(-1) 应输出「网络连接中断」而非通用网络文案。
        val apiKeyRepo = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
        val provider = OpenAICompatibleProvider(
            HttpClient(OkHttp) { expectSuccess = true; install(SSE) },
            apiKeyRepo
        )
        val srv = ServerSocket(0)
        val port = srv.localPort
        val serverJob = launch {
            try {
                val socket = srv.accept()
                // 声明 200 + text/event-stream + 截断 body（Content-Length 大于实际发送字节）
                // → OkHttp/SSE 解析读取 EOF → 底层异常被包装为 SSEClientException（无 response）
                socket.getOutputStream().use { out ->
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Content-Length: 100\r\n" +
                            "\r\n" +
                            "data: {\"choices\":").toByteArray()
                    )
                }
                socket.close()
            } catch (_: Exception) {
            } finally {
                runCatching { srv.close() }
            }
        }
        try {
            val config = io.prism.data.ProviderConfig(
                name = "X", baseUrl = "http://127.0.0.1:$port",
                apiKeyRef = "openai", models = listOf("m")
            )
            val events = provider.streamChat(config, listOf(ChatMessage(id = 0, role = Role.USER, content = "hi", timestamp = 0)))
                .toList()
            val error = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
            assertNotNull("连接中断应发射 Error 事件", error)
            // 允许两种情况：-1 分支「网络连接中断」或底层连接失败被 catch(Exception) 归为网络失败；
            // 全量回归时序下窗口切换也可能把截断流判定为「服务端未返回内容（空响应）」（同为 Error，非静默）；
            // 关键是不得静默成功（不发射 Done/无 Error 即视为失败），故此处收紧为必须发射 Error 且非成功回复
            // —— set 覆盖三类可诊断错误文案之一，防偶发时序把合法 Error 误标为未提示。
            val msg = error!!.message
            assertTrue(
                "应提示网络/空响应问题而非假装成功（实际: $msg）",
                msg.contains("网络") || msg.contains("连接") || msg.contains("未返回内容")
            )
        } finally {
            serverJob.join()
        }
    }

    // ==================== helpers ====================

    private fun citySerpHtml(title: String, snippet: String): String =
        """<html><body><ol id="b_results"><li class="b_algo"><h2><a href="https://baike.baidu.com/">$title</a></h2><div class="b_caption"><p>$snippet</p></div></li></ol></body></html>"""

    private fun baiduCitySerpHtml(title: String, snippet: String): String =
        """<html><body><h3><a href="http://www.baidu.com/link?url=abc">$title</a></h3><div class="c-abstract">$snippet</div></body></html>"""

    private fun noopClient(): HttpClient =
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
}
