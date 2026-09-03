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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ac-verifier 补充（TKN-UXR7R2-ACCEPTANCE-001）：UXR7-R2 搜索修复的极端/边界场景验证。
 *
 * 覆盖主 Agent 测试（WebSearchLocalToolExecutorTest）未覆盖的盲区：
 * 1. AC-1.5 MAX_CORE_TERM_RETRIES=3 候选截断（超长 query 不放大网络请求）
 * 2. extractCoreTerms 重复候选去重
 * 3. execute 重试跳过 term==query（避免与主查询重复请求）
 * 4. isRelevant 空 snippet / 空结果集行为
 * 5. 全停用词 query 不触发重试（coreTerms 为空 → 直接返回主结果）
 */
class WebSearchLocalToolExecutorUxr7SupplementTest {

    private fun rssBody(vararg items: Triple<String, String, String>): String {
        // v1 批次6（RSS→HTML）：本文件所有 execute 测试的 MockEngine 响应体改为 Bing HTML `li.b_algo`。
        val sb = StringBuilder("""<html><ol id="b_results">""")
        items.forEach { (title, link, desc) ->
            sb.append("<li class=\"b_algo\"><h2><a href=\"").append(link).append("\">")
                .append(title).append("</a></h2><div class=\"b_caption\"><p>").append(desc)
                .append("</p></div></li>")
        }
        sb.append("</ol></html>")
        return sb.toString()
    }

    private fun xiOnly(): String =
        rssBody(Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"))

    @Test
    fun `execute caps core term retries at 3 for long query`() = runBlocking {
        // AC-1.5（LOW-01）：超长 query 产生 4 个非停用词候选 ["昔涟","星穹铁道","崩坏","三月七"]，
        // 截断前 3 个。前 3 个重试均不相关，第 4 个"三月七"若被重试会命中 → 验证不被重试。
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            val body = when (q) {
                "三月七" -> rssBody(Triple("三月七-百度百科", "https://baike.baidu.com/三月七", "三月七是游戏角色"))
                else -> xiOnly()
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
            mapOf("query" to "昔涟 星穹铁道 崩坏 三月七 角色")
        )
        // 主查询 + 最多 3 个候选 = 4 次请求
        // 候选截断为 3 个：Bing 主+3 重试 = 4 次；随后百度兜底再查 1 次完整 query + 3 核心词 = 4 次，共 8 次
        // 截断后应返回失败（第 4 候选未被重试）。v1 批次15（US-1504）：无 Key 且引擎全不相关
        // 时文案升级为「错误：」前缀的搜索增强引导（列出可配置引擎），替代旧「搜索失败」死胡同。
        assertEquals("候选应截断为 3 个，总请求 8 次（Bing 主+3 + 百度兜底 query+3）", 8, callCount)
        assertTrue(
            "截断后应返回「错误：」引导文案（US-1504）",
            result.startsWith("错误：") && result.contains("搜索增强")
        )
        // 搜索失败文案会回显完整 query（含"三月七"），此处断言"三月七"重试命中的结果特征不存在
        assertFalse("第 4 个候选'三月七'不应被重试命中", result.contains("三月七-百度百科"))
    }

    @Test
    fun `extractCoreTerms deduplicates repeated candidates preserving first order`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        // 重复候选去重：保留首次出现顺序
        assertEquals(
            listOf("昔涟", "星穹铁道"),
            executor.extractCoreTerms("昔涟 星穹铁道 昔涟")
        )
        // 数字/标点/单字中文不产生候选（中文片段需连续 ≥2 字）
        assertEquals(emptyList<String>(), executor.extractCoreTerms("2026年 8月 16日"))
        // 中文片段（"处理器"3 字）被提取；"A1" 非中文不参与
        assertEquals(listOf("处理器"), executor.extractCoreTerms("A1 处理器"))
    }

    @Test
    fun `execute skips retry when core term equals query`() = runBlocking {
        // query 本身就是核心词（如"昔涟"）：主查询不相关时重试循环跳过 term==query，
        // 不重复发请求，直接返回搜索失败（避免与主查询重复请求）。
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                content = xiOnly(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟")
        )
        // query==term 时不应重复请求：仅 1 次 Bing；随后百度兜底查 1 次完整 query（核心词==query 跳过），共 2 次
        assertEquals("query==term 时 Bing 仅 1 次 + 百度兜底 1 次 = 2 次", 2, callCount)
        // v1 批次15（US-1504）：无 Key 全不相关 → 「错误：」搜索增强引导文案
        assertTrue("应返回「错误：」引导文案", result.startsWith("错误：") && result.contains("搜索增强"))
    }

    @Test
    fun `execute returns primary results when coreTerms empty (all stop words)`() = runBlocking {
        // 全停用词 query（coreTerms 为空）→ 不触发降级重试，直接返回主查询结果
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                content = rssBody(Triple("最新新闻", "https://n.example.com", "今日新闻")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "最新 新闻 介绍")
        )
        assertEquals("coreTerms 为空不应触发重试，仅 1 次请求", 1, callCount)
        assertTrue("应返回主查询结果", result.contains("最新新闻"))
    }

    @Test
    fun `isRelevant handles empty snippet and empty items`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        val emptySnippet = listOf(
            WebSearchLocalToolExecutor.SearchItem(
                title = "昔涟（崩坏：星穹铁道角色）",
                link = "https://baike.baidu.com/昔涟",
                snippet = ""
            )
        )
        // 空 snippet 不影响 title 匹配
        assertTrue(executor.isRelevant(emptySnippet, listOf("昔涟")))
        // 空结果集 → 不相关（返回 false，触发降级重试逻辑）
        assertFalse(executor.isRelevant(emptyList(), listOf("昔涟")))
        // 空结果集 + 空核心词 → 无法判断，默认相关
        assertTrue(executor.isRelevant(emptyList(), emptyList()))
    }

    @Test
    fun `execute retries next candidate when first relevant only in snippet`() = runBlocking {
        // 重试判据：候选词仅出现在 snippet（非 title）也应判定相关（AC-1.2 相关性包含 snippet）
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            val body = when (q) {
                "昔涟 是谁" -> xiOnly()
                "昔涟" -> rssBody(
                    Triple("某角色介绍", "https://w.example.com/xi", "本文介绍昔涟的设定与背景")
                )
                else -> xiOnly()
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
            mapOf("query" to "昔涟 是谁")
        )
        assertEquals("应触发主查询 + 1 次重试", 2, callCount)
        assertTrue("重试命中（snippet 含核心词）应返回结果", result.contains("本文介绍昔涟"))
    }

    private fun noopClient(): HttpClient =
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
}
