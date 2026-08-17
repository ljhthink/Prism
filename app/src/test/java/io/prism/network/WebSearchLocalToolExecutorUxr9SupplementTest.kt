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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXR9 US-902 补充测试（ac-verifier，TKN-UXR9-ACCEPTANCE-001）。
 *
 * 主 Agent 测试仅覆盖「结果集整体不相关 → 核心词重试」路径；本文件补充覆盖
 * UXR9 Bug2 核心修复 `filterRelevantItems`（**条目级**过滤）的直接单测与集成场景：
 *
 * - AC-1：搜索"昔涟"，返回结果中**不含**仅命中"昔"一个字的条目（逐条目按核心词过滤）
 *   —— 混合集（同一次结果含完整"昔涟"条目 + 单字"昔"噪声条目）时，噪声条目被剔除
 * - AC-2：完整关键字命中（"昔涟"）的条目正常保留
 * - AC-3：既有「多候选核心词短整词降级重试」逻辑不回归（execute 集成验证）
 * - 边界：coreTerms 为空（纯英文）不过滤、空结果集、term.length>=2 守卫
 */
class WebSearchLocalToolExecutorUxr9SupplementTest {

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

    private fun item(title: String, link: String, snippet: String) =
        WebSearchLocalToolExecutor.SearchItem(title = title, link = link, snippet = snippet)

    // ==================== filterRelevantItems 直接单测（AC-1 / AC-2） ====================

    @Test
    fun `filterRelevantItems drops single-char noise and keeps full keyword items in mixed set`() {
        // UXR9 Bug2 核心场景：Bing 长 query 分词坍缩返回混合集 —— 部分条目含完整"昔涟"，
        // 部分条目仅命中单字"昔"（噪声）。filterRelevantItems 应按**条目**过滤。
        val executor = WebSearchLocalToolExecutor(noopClient())
        val mixed = listOf(
            item("昔涟（崩坏：星穹铁道角色）_百度百科", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
            item("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"),
            item("昔涟 - BWIKI", "https://wiki.biligame.com/昔涟", "昔涟资料与技能"),
            item("昔 在 古汉语中的用法", "https://www.example.com/昔", "古汉语 昔 表示从前")
        )
        val filtered = executor.filterRelevantItems(mixed, listOf("昔涟"))
        assertEquals("混合集应只保留 2 条含完整'昔涟'的条目", 2, filtered.size)
        assertTrue("完整命中条目应保留", filtered.any { it.title.contains("昔涟（崩坏") })
        assertTrue("完整命中条目应保留", filtered.any { it.title.contains("BWIKI") })
        assertFalse("仅命中单字'昔'的噪声条目应被剔除", filtered.any { it.title == "昔_百度百科" })
        assertFalse("仅命中单字'昔'的噪声条目应被剔除", filtered.any { it.title.contains("古汉语") })
    }

    @Test
    fun `filterRelevantItems matches full keyword in title or snippet`() {
        // 核心词可能仅出现在 snippet 中（标题不含）→ 应保留
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = listOf(
            item("某角色介绍", "https://w.example.com/xi", "本文介绍昔涟的设定与背景"),
            item("完全无关标题", "https://x.example.com/none", "完全无关的摘要")
        )
        val filtered = executor.filterRelevantItems(items, listOf("昔涟"))
        assertEquals("snippet 含核心词的条目应保留", 1, filtered.size)
        assertEquals("应保留 snippet 命中条目", "某角色介绍", filtered[0].title)
    }

    @Test
    fun `filterRelevantItems multiple core terms keeps items matching any term`() {
        // 多候选：任一核心词完整命中即保留（对齐 isRelevant 语义，逐条目放宽）
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = listOf(
            item("星穹铁道-百度百科", "https://baike.baidu.com/星穹铁道", "星穹铁道是游戏"),
            item("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"),
            item("无关条目", "https://x.example.com/none", "无关")
        )
        val filtered = executor.filterRelevantItems(items, listOf("星穹铁道", "昔涟"))
        assertEquals("命中任一核心词的条目应保留", 1, filtered.size)
        assertEquals("应保留星穹铁道条目", "星穹铁道-百度百科", filtered[0].title)
    }

    @Test
    fun `filterRelevantItems empty core terms returns original list`() {
        // 纯英文查询 / 无法提取核心词 → 不过滤（防误杀英文相关结果）
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = listOf(
            item("DeepSeek official", "https://www.deepseek.com", "DeepSeek AI"),
            item("GitHub trending", "https://github.com/trending", "repos")
        )
        assertEquals("coreTerms 为空应原样返回", items, executor.filterRelevantItems(items, emptyList()))
    }

    @Test
    fun `filterRelevantItems empty items returns empty`() {
        val executor = WebSearchLocalToolExecutor(noopClient())
        assertTrue(executor.filterRelevantItems(emptyList(), listOf("昔涟")).isEmpty())
        assertTrue(executor.filterRelevantItems(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `filterRelevantItems single char core term filters everything fail-closed`() {
        // term.length >= 2 守卫（防御性）：单字 term 永远不满足 length>=2，`any` 判定为
        // false → 全部条目被过滤（fail-closed，宁缺毋滥）。生产路径 extractCoreTerms
        // 保证核心词连续中文 ≥2 字，单字 term 仅由防御性语义覆盖。
        val executor = WebSearchLocalToolExecutor(noopClient())
        val items = listOf(
            item("昔 的用法", "https://x.example.com/xi", "昔 字"),
            item("昔涟角色", "https://x.example.com/xilian", "昔涟")
        )
        val filtered = executor.filterRelevantItems(items, listOf("昔"))
        assertTrue("单字 term 无法满足 length>=2 → 全部过滤（fail-closed）", filtered.isEmpty())
    }

    // ==================== execute 集成（AC-1 主查询混合集 + AC-3 重试不回归） ====================

    @Test
    fun `execute primary mixed set keeps only full keyword items without retry`() = runBlocking {
        // 主查询"昔涟"直接返回混合集（含完整"昔涟"条目 + 单字"昔"噪声条目）：
        // 集合级 isRelevant 判相关（有完整命中），**不再触发降级重试**；但返回结果
        // 必须经 filterRelevantItems 剔除单字噪声条目（AC-1 逐条目过滤）。
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                content = rssBody(
                    Triple("昔涟（崩坏：星穹铁道角色）", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
                    Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"),
                    Triple("昔涟 - BWIKI", "https://wiki.biligame.com/昔涟", "昔涟资料")
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/rss+xml")
            )
        }
        val executor = WebSearchLocalToolExecutor(HttpClient(engine))
        val result = executor.execute(
            WebSearchLocalToolExecutor.TOOL_SEARCH,
            mapOf("query" to "昔涟", "maxResults" to 5)
        )
        assertEquals("混合集判相关，不应触发额外重试请求", 1, callCount)
        assertTrue("完整命中条目应保留", result.contains("昔涟（崩坏"))
        assertTrue("完整命中条目应保留", result.contains("BWIKI"))
        assertFalse("单字'昔'噪声条目不得进入结果", result.contains("昔 xī"))
        assertFalse("单字'昔'噪声条目不得进入结果", result.contains("古汉语"))
    }

    @Test
    fun `execute core term retry still filters single-char noise in retried results`() = runBlocking {
        // AC-3：降级重试路径不回归 —— 主查询坍缩为"昔"（不相关），核心词"昔涟"重试
        // 返回**混合集**：完整命中 + 单字噪声。重试结果同样须经条目级过滤（代码注释要求）。
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val q = request.url.parameters["q"].orEmpty()
            val body = if (q.contains("昔涟 是谁")) {
                rssBody(Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"))
            } else {
                // "昔涟"重试混合集
                rssBody(
                    Triple("昔涟（崩坏：星穹铁道角色）", "https://baike.baidu.com/昔涟", "昔涟是游戏角色"),
                    Triple("昔_百度百科", "https://baike.baidu.com/昔", "昔 xī - 汉典"),
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
        assertTrue("重试结果应含完整命中条目", result.contains("昔涟（崩坏"))
        assertFalse("重试结果的单字噪声条目应被剔除", result.contains("昔 xī"))
    }

    private fun noopClient(): HttpClient =
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
}
