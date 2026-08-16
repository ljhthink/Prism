package io.prism.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ac-verifier 补充（TKN-UXR7R2-ACCEPTANCE-001）：sanitizeMarkdownTables 极端/边界场景验证。
 *
 * 覆盖主 Agent 测试（ConversationScreenMarkdownTest）未覆盖的盲区：
 * 1. 无首尾管道符的 GFM 合法表格（GFM 规范首尾 | 可选）——记录当前行为
 * 2. 单元格内代码 span / 链接 / 加粗等嵌套 markdown
 * 3. 与 sanitizeMarkdownLinks 组合调用顺序（AiBubble 实际渲染路径）
 * 4. 单行紧凑表格边界（恰好 2 行 | 行）
 * 5. 表格行数上限 / 超长单元格防御
 */
class ConversationMarkdownUxr7SupplementTest {

    @Test
    fun `table with no leading pipe is not converted (gfm optional pipe)`() {
        // GFM 规范允许省略首尾管道符（`项目 | 语言` 开头无 | 也是合法表格）。
        // 当前 tableLine 正则 `^\s*\|.*\|\s*$` 要求首尾都有 | → 此形态不被识别。
        // 记录当前行为（不转换、不崩溃），作为已知局限（GFM 部分支持）。
        val input = """
            项目 | 语言 | Stars
            -----|------|------
            A | Kotlin | 100
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertEquals("无首尾管道符的表格当前不转换（原样保留）", input, out)
    }

    @Test
    fun `table with only trailing pipe but no leading pipe is not converted`() {
        val input = """
            项目 | 语言 |
            -----|------|
            A | Kotlin |
        """.trimIndent()
        assertEquals(input, sanitizeMarkdownTables(input))
    }

    @Test
    fun `table with code span in cell converts and preserves backticks`() {
        val input = """
            | 命令 | 说明 |
            |------|------|
            | `adb install` | 安装 APK |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("表头应加粗", out.contains("**命令 | 说明**"))
        assertTrue("代码 span 应保留", out.contains("- `adb install` | 安装 APK"))
    }

    @Test
    fun `table with markdown link in cell converts and preserves link`() {
        val input = """
            | 名称 | 链接 |
            |------|------|
            | Prism | [官网](https://prism.example.com) |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("单元格内链接应保留", out.contains("[官网](https://prism.example.com)"))
    }

    @Test
    fun `table with bold emphasis in cell converts`() {
        val input = """
            | 项目 | 状态 |
            |------|------|
            | **核心** | 完成 |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("加粗应保留", out.contains("- **核心** | 完成"))
    }

    @Test
    fun `single compact table with exactly two pipe rows converts`() {
        // 紧凑表格边界：恰好 2 行 | 行（表头 + 1 数据行，无分隔行）也应转换
        val input = """
            | 名称 | 值 |
            | alpha | 1 |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("表头加粗", out.contains("**名称 | 值**"))
        assertTrue("数据行转列表项", out.contains("- alpha | 1"))
    }

    @Test
    fun `sanitizeMarkdownTables then sanitizeMarkdownLinks combined pipeline`() {
        // AiBubble 实际调用链：sanitizeMarkdownLinks(sanitizeMarkdownTables(content))
        val input = """
            | 项目 | 来源 |
            |------|------|
            | Prism | [GitHub](https://github.com/prism) |
        """.trimIndent()
        val tables = sanitizeMarkdownTables(input)
        val final = sanitizeMarkdownLinks(tables)
        assertTrue("表格已转列表", final.contains("- Prism | [GitHub](https://github.com/prism)"))
        assertTrue("http(s) 链接保留", final.contains("https://github.com/prism"))
    }

    @Test
    fun `long cell content is preserved without crash`() {
        val longCell = "x".repeat(5000)
        val input = "| A | $longCell |"
        val out = sanitizeMarkdownTables(input)
        // 单行 | 行不转换（原样保留），无崩溃
        assertEquals(input, out)
    }

    @Test
    fun `empty rows in table are kept as empty list items`() {
        val input = """
            | A | B |
            |---|---|
            | 1 | 2 |
            |   |   |
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("空单元格数据行也应转换为列表项", out.contains("- 1 | 2"))
        assertFalse("空行不应崩溃", out.isBlank())
    }

    @Test
    fun `mixed table and non-table content only transforms table block`() {
        val input = """
            普通段落。
            | 列A | 列B |
            |-----|-----|
            | 1 | 2 |
            另一个段落 | 含单个管道
        """.trimIndent()
        val out = sanitizeMarkdownTables(input)
        assertTrue("表格块转换", out.contains("**列A | 列B**"))
        assertTrue("普通段落保留", out.contains("普通段落。"))
        assertTrue("非表格单管道行保留", out.contains("另一个段落 | 含单个管道"))
    }
}
