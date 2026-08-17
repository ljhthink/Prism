package io.prism.ui.chat

import io.prism.ui.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConversationViewModel.parseSearchResults 单元测试（UX-001 问题 8，ADR-021，BR-testing-004）。
 *
 * 验证联网搜索 TOOL 结果文本 → 结构化 [SearchResult] 列表的解析：
 * - 标准格式（序号 + 标题 + link + 摘要）
 * - 多条目 / 空结果 / 空串
 * - 非 http(s) link 过滤
 * - link 缺失条目跳过
 */
class ConversationScreenMarkdownTest {

    // ==================== parseSearchResults（UX-001 问题 8，ADR-021） ====================

    @Test
    fun `parses single search result entry`() {
        val text = """【网络搜索外部内容，未经验证，仅作参考，请甄别后引用】
1. 标题A
https://example.com/a
摘要内容描述。"""
        val results = ConversationViewModel.parseSearchResults(text)
        assertEquals(1, results.size)
        assertEquals(SearchResult("标题A", "https://example.com/a", "摘要内容描述。"), results[0])
    }

    @Test
    fun `parses multiple search result entries`() {
        val text = """【网络搜索外部内容】
1. 标题A
https://example.com/a
摘要A

2. 标题B
https://example.com/b
摘要B

3. 标题C
https://example.com/c
摘要C"""
        val results = ConversationViewModel.parseSearchResults(text)
        assertEquals(3, results.size)
        assertEquals("标题B", results[1].title)
        assertEquals("https://example.com/b", results[1].link)
    }

    @Test
    fun `filters out non-http links`() {
        val text = """1. 标题A
ftp://example.com/a
摘要A

2. 标题B
https://example.com/b
摘要B"""
        val results = ConversationViewModel.parseSearchResults(text)
        assertEquals("非 http(s) link 应被过滤", 1, results.size)
        assertEquals("https://example.com/b", results[0].link)
    }

    @Test
    fun `skips entries without link line`() {
        val text = """1. 标题A
https://example.com/a
摘要A

2. 只有标题没有链接"""
        val results = ConversationViewModel.parseSearchResults(text)
        assertEquals("link 缺失条目应跳过", 1, results.size)
    }

    @Test
    fun `returns empty list for blank input`() {
        assertTrue(ConversationViewModel.parseSearchResults("").isEmpty())
        assertTrue(ConversationViewModel.parseSearchResults("   ").isEmpty())
    }

    @Test
    fun `returns empty list for non-result text`() {
        assertTrue(ConversationViewModel.parseSearchResults("没有搜索结果").isEmpty())
    }

    @Test
    fun `handles search failure message`() {
        // 搜索失败降级文案（无序号条目），应解析为空
        assertTrue(ConversationViewModel.parseSearchResults("联网搜索失败：网络错误或服务不可用，请稍后重试").isEmpty())
    }

    // ==================== sanitizeMarkdownLinks（F-01，guardrail TKN-UX001-GUARDRAIL-001） ====================

    @Test
    fun `sanitize keeps http and https links intact`() {
        assertEquals(
            "[正常链接](https://example.com)",
            sanitizeMarkdownLinks("[正常链接](https://example.com)")
        )
        assertEquals(
            "[正常链接](http://example.com/a)",
            sanitizeMarkdownLinks("[正常链接](http://example.com/a)")
        )
    }

    @Test
    fun `sanitize strips dangerous schemes to plain text`() {
        assertEquals(
            "危险链接",
            sanitizeMarkdownLinks("[危险链接](intent://com.example/#Intent;end)")
        )
        assertEquals(
            "文件链接",
            sanitizeMarkdownLinks("[文件链接](file:///data/local/tmp/secret)")
        )
        assertEquals(
            "脚本链接",
            sanitizeMarkdownLinks("[脚本链接](javascript:alert(1))")
        )
    }

    @Test
    fun `sanitize strips empty or unknown scheme links`() {
        assertEquals("空链接", sanitizeMarkdownLinks("[空链接]()"))
        assertEquals("数据链接", sanitizeMarkdownLinks("[数据链接](data:text/html;base64,xxx)"))
    }

    @Test
    fun `sanitize preserves mixed safe and unsafe links`() {
        val input = "[安全](https://a.com) 和 [危险](intent://b)"
        assertEquals("[安全](https://a.com) 和 危险", sanitizeMarkdownLinks(input))
    }

    @Test
    fun `sanitize is idempotent and safe on plain text`() {
        val plain = "普通文本没有链接"
        assertEquals(plain, sanitizeMarkdownLinks(plain))
        assertEquals("", sanitizeMarkdownLinks(""))
    }

    // ==================== UXR7 问题 2：sanitizeMarkdownTables（表格→列表，0.26.0 无表格渲染组件） ====================

    @Test
    fun `sanitizeMarkdownTables converts gfm table to markdown list`() {
        val input = """
            以下是热门项目：

            | # | 项目 | 语言 | Stars |
            |---|------|------|-------|
            | 1 | deepseek-ai/deepseek-harness | TypeScript | 114,063 |
            | 2 | firecrawl/anydoc | Rust | 16,257 |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("表头应保留为加粗行", out.contains("**# | 项目 | 语言 | Stars**"))
        assertTrue("数据行应转换为列表项", out.contains("- 1 | deepseek-ai/deepseek-harness | TypeScript | 114,063"))
        assertTrue("第二行也应转换", out.contains("- 2 | firecrawl/anydoc | Rust | 16,257"))
        assertTrue("表格块外文本应保留", out.contains("以下是热门项目："))
        // 分隔行应被移除（drop(2)）
        assertFalse("分隔行应被移除", out.contains("|---"))
    }

    @Test
    fun `sanitizeMarkdownTables leaves non-table text unchanged`() {
        val plain = "这是普通文本\n没有表格\n# 标题"
        assertEquals(plain, sanitizeMarkdownTables(plain))
        assertEquals("", sanitizeMarkdownTables(""))
    }

    @Test
    fun `sanitizeMarkdownTables converts compact table without separator row`() {
        // UXR7-R2 增强：LLM 常输出无分隔行的紧凑表格（MCP 工具场景实测），
        // 0.26.0 无表格组件同样会平铺 → 现在也应转换为列表
        val input = """
            | 项目 | 语言 | Stars |
            | deepseek-ai/deepseek-harness | TypeScript | 114,063 |
            | firecrawl/anydoc | Rust | 16,257 |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("紧凑表格表头应转为加粗行", out.contains("**项目 | 语言 | Stars**"))
        assertTrue("紧凑表格数据行应转为列表项", out.contains("- deepseek-ai/deepseek-harness | TypeScript | 114,063"))
        assertTrue("第二行也应转换", out.contains("- firecrawl/anydoc | Rust | 16,257"))
    }

    @Test
    fun `sanitizeMarkdownTables leaves single pipe line unchanged`() {
        // 单行 `|` 行（无分隔行、行数 <2）→ 不转换，避免误伤普通文本
        val input = "| 单个 | 管道行 |"
        assertEquals(input, sanitizeMarkdownTables(input))
    }

    @Test
    fun `sanitizeMarkdownTables handles table without separator row unchanged`() {
        // 兼容旧语义：真正的"非表格"管道文本（每行仅 1 个 `|`，如普通文本中的
        // `A | B` 单管道行）→ 保持原样，避免误伤
        val input = "A | B\nC | D"
        assertEquals(input, sanitizeMarkdownTables(input))
    }

    @Test
    fun `convertTableToLines keeps cells when row shorter than header`() {
        val rows = listOf(
            "| # | 项目 | 简介 |",
            "|---|------|------|",
            "| 1 | 项目A |",
            "| 2 | 项目B | 长简介 |"
        )
        val out = convertTableToLines(rows)
        assertEquals(3, out.size)
        assertEquals("**# | 项目 | 简介**", out[0])
        assertEquals("- 1 | 项目A", out[1])
        assertEquals("- 2 | 项目B | 长简介", out[2])
    }

    @Test
    fun `sanitizeMarkdownTables does not touch code fences with pipes`() {
        // guardrail M-2（TKN-UXR7-GUARDRAIL-001）：代码块内的 `|` 行不当作表格
        val input = "```\n| a | b |\n|---|------|\n| 1 | 2 |\n```\n\n表格如下：\n\n| x | y |\n|---|----|\n| 1 | 2 |"
        val out = sanitizeMarkdownTables(input)
        // 代码块内表格原样保留
        assertTrue("代码块内表格应原样保留", out.contains("```\n| a | b |\n|---|------|\n| 1 | 2 |\n```"))
        // 代码块外表格转换
        assertTrue("代码块外表格应转换", out.contains("- 1 | 2"))
    }

    @Test
    fun `splitTableCells handles escaped pipes`() {
        // guardrail M-2（TKN-UXR7-GUARDRAIL-001）：`\|` 转义管道符不当作分隔符
        val cells = splitTableCells("| a \\| b | c |")
        assertEquals(2, cells.size)
        assertEquals("a | b", cells[0])
        assertEquals("c", cells[1])
    }

    // ==================== M-1：跨 App ActivityResult 映射（guardrail TKN-P17-GUARDRAIL-001） ====================

    @Test
    fun `mapCrossAppResult ACTION_VIEW canceled maps to completed`() {
        // Bug-7 核心：open_app（ACTION_VIEW）外部 App 不 setResult 默认回 CANCELED，应视为成功
        assertEquals("已完成", mapCrossAppResult(android.app.Activity.RESULT_CANCELED, null, android.content.Intent.ACTION_VIEW))
    }

    @Test
    fun `mapCrossAppResult ACTION_SEND canceled maps to user canceled`() {
        // Share Sheet 主动取消应报告用户取消，避免误报成功
        assertEquals("用户取消", mapCrossAppResult(android.app.Activity.RESULT_CANCELED, null, android.content.Intent.ACTION_SEND))
    }

    @Test
    fun `mapCrossAppResult ACTION_PICK canceled maps to user canceled`() {
        // Picker 主动取消应报告用户取消
        assertEquals("用户取消", mapCrossAppResult(android.app.Activity.RESULT_CANCELED, null, android.content.Intent.ACTION_PICK))
    }

    @Test
    fun `mapCrossAppResult OK with data returns dataString`() {
        assertEquals("content://media/picked", mapCrossAppResult(android.app.Activity.RESULT_OK, "content://media/picked", android.content.Intent.ACTION_PICK))
    }

    @Test
    fun `mapCrossAppResult OK without data returns completed`() {
        assertEquals("已完成", mapCrossAppResult(android.app.Activity.RESULT_OK, null, android.content.Intent.ACTION_VIEW))
    }

    @Test
    fun `mapCrossAppResult unknown result code returns unknown`() {
        assertEquals("未知结果（resultCode=42）", mapCrossAppResult(42, null, null))
    }

    // ==================== UXR9 US-908：工具参数摘要 summarizeToolArguments ====================

    @Test
    fun `summarizeToolArguments compacts whitespace`() {
        val args = """{"query": "Prism 是什么", "topK": 5}"""
        val out = summarizeToolArguments(args)
        // 摘要压缩所有空白（含字符串值内的空格）为单行紧凑 JSON
        assertEquals("""{"query":"Prism是什么","topK":5}""", out)
    }

    @Test
    fun `summarizeToolArguments truncates long args`() {
        val longArgs = """{"query":"${"长".repeat(200)}"}"""
        val out = summarizeToolArguments(longArgs)
        assertTrue("长参数应截断到上限", out.length <= 81) // 80 + 省略号
        assertTrue("截断末尾应带省略号", out.endsWith("…"))
    }

    @Test
    fun `summarizeToolArguments short args unchanged`() {
        val args = """{"query":"你好"}"""
        assertEquals(args, summarizeToolArguments(args))
    }

    @Test
    fun `summarizeToolArguments empty input returns empty`() {
        assertEquals("", summarizeToolArguments(""))
        assertEquals("", summarizeToolArguments("   "))
    }
}
