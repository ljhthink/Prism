package io.prism.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXR11 补充测试（ac-verifier，TKN-UXR11-ACCEPTANCE-001）。
 *
 * 覆盖 U4（Fetch 失败后乱码净化）的纯函数：
 * - **U4** [sanitizeToolCallSyntax]：剥离 LLM 幻觉输出的完整工具调用块
 *   （`<tool_calls>…</tool_calls>` / `<invoke…>…</invoke>` / `<|…|>` / `<｜…｜>` 变体），
 *   并转义残余标签起始 `<`，使 Markdown 渲染显示实际文本而非乱码。
 *
 * **U1 相关用例已于 2026-09-12 随 ADR-050 移除**：原 4 个 `needsRagRetrieval` 用例
 * （文档前缀跳过 RAG 自动注入）验证的是"用启发式判据决定是否自动检索"这一范式下的行为。
 * ADR-050 撤除了客户端按轮自动检索注入（知识库正文只经 LLM 主动调用 `knowledge_base__*`
 * 工具进入上下文），该函数与其黑名单判据一并删除，故用例同批移除；
 * 新契约由 `ConversationViewModelKnowledgeBaseInjectionTest` 覆盖。
 */
class ConversationUxr11SupplementTest {

    // ==================== U4：工具调用语法块净化（ADR-033） ====================

    @Test
    fun `sanitizeToolCallSyntax strips xml tool_calls block`() {
        val input = "背景介绍。\n<tool_calls>\n<invoke name=\"mcp_Fetch__fetch\">\n" +
            "<parameter name=\"maxLength\">10000</parameter>\n</invoke>\n</tool_calls>\n这是结论。"
        val out = sanitizeToolCallSyntax(input)
        assertFalse("tool_calls 块应被剥离", out.contains("tool_calls"))
        assertFalse("invoke 块应被剥离", out.contains("invoke"))
        assertTrue("答案正文应保留", out.contains("背景介绍"))
        assertTrue("结论应保留", out.contains("这是结论"))
    }

    @Test
    fun `sanitizeToolCallSyntax strips pipe-delimited variant`() {
        // LLM 幻觉输出的 <|tool_calls|> 变体（ASCII 管道符）
        val input = "前面\n<|tool_calls|>\n<|invoke|>\n<|parameter|>10000</|parameter|>\n" +
            "</|invoke|>\n</|tool_calls|>\n后面"
        val out = sanitizeToolCallSyntax(input)
        assertFalse(out.contains("tool_calls"))
        assertFalse(out.contains("invoke"))
        assertTrue(out.contains("前面"))
        assertTrue(out.contains("后面"))
    }

    @Test
    fun `sanitizeToolCallSyntax strips fullwidth pipe variant`() {
        // 真机实测乱码变体（全角 ｜ U+FF5C 分隔符）
        val input = "结论正文。\n<｜tool_calls｜>\n<｜invoke name=\"web_search__search\"｜>\n" +
            "<｜parameter name=\"query\"｜>测试</｜parameter｜>\n</｜invoke｜>\n</｜tool_calls｜>"
        val out = sanitizeToolCallSyntax(input)
        assertFalse("全角 tool_calls 块应被剥离", out.contains("tool_calls"))
        assertFalse("全角 invoke 块应被剥离", out.contains("invoke"))
        assertTrue("正文应保留", out.contains("结论正文"))
    }

    @Test
    fun `sanitizeToolCallSyntax escapes stray tag start`() {
        // 未闭合/孤立的标签起始 < 转义为 &lt;（可见文本，非乱码）
        assertEquals("&lt;tool_calls>", sanitizeToolCallSyntax("<tool_calls>"))
        assertEquals("&lt;br>换行", sanitizeToolCallSyntax("<br>换行"))
        assertEquals("&lt;/div>结束", sanitizeToolCallSyntax("</div>结束"))
    }

    @Test
    fun `sanitizeToolCallSyntax leaves plain text untouched`() {
        // 比较符 / 数字比较 / 中英文正文不受影响
        assertEquals("a < b 且 c > d", sanitizeToolCallSyntax("a < b 且 c > d"))
        assertEquals("温度<30度", sanitizeToolCallSyntax("温度<30度"))
        assertEquals("你好，世界", sanitizeToolCallSyntax("你好，世界"))
    }

    @Test
    fun `sanitizeToolCallSyntax preserves markdown link and code fence`() {
        // markdown 链接语法（]( 不受影响）与代码围栏（renderer 按 code 处理）
        assertEquals("[链接](https://example.com)", sanitizeToolCallSyntax("[链接](https://example.com)"))
        val fenced = "```\n<foo>\n```"
        assertEquals(fenced, sanitizeToolCallSyntax(fenced))
    }

    @Test
    fun `sanitizeToolCallSyntax handles realistic garbled sample`() {
        // 用户报告的真实场景：Fetch 失败后 LLM 把工具调用计划写成块输出
        val garbled = "搜索结果显示「昔涟」是《崩坏：星穹铁道》里的角色，我继续深挖几个方向。" +
            "<｜｜tool_calls｜｜>\n" +
            "<｜｜invoke name=\"mcp_Fetch__fetch\"｜｜>\n" +
            "<｜｜parameter name=\"url\"｜｜>https://wiki.biligame.com/sr/昔涟</｜｜parameter｜｜>\n" +
            "</｜｜invoke｜｜>\n" +
            "</｜｜tool_calls｜｜>\n" +
            "背景故事与原型分析如下。"
        val out = sanitizeToolCallSyntax(garbled)
        assertFalse("乱码 tool_calls 块应被剥离", out.contains("tool_calls"))
        assertFalse("乱码 invoke 块应被剥离", out.contains("invoke"))
        assertTrue("正文前段应保留", out.contains("我继续深挖几个方向"))
        assertTrue("正文后段应保留", out.contains("背景故事与原型分析如下"))
    }

    @Test
    fun `sanitizeToolCallSyntax strips garbled open tag with alnum separators`() {
        // v1 Issue 1 回归用例：LLM 输出 `<与关键词间夹带字母数字分隔`（如 `｜tool_calls｜` 被写成
        // 带编号/字符分隔的变形 `<1tool_calls>`）。旧正则 `[^\w>]` 会拒绝 `\w` 词字符导致开标签
        // 漏配而残留乱码；改为 `[^>\n]` 后应正确剥离整块，仅保留正文。
        val input = "我先查一下。\n<1tool_calls>\n<2invoke name=\"web_search__search\">\n" +
            "<3parameter name=\"query\">梧州市第一中学</3parameter>\n</2invoke>\n</1tool_calls>\n" +
            "以上是查询过程。"
        val out = sanitizeToolCallSyntax(input)
        assertFalse("夹数字分隔的 tool_calls 块应被剥离", out.contains("tool_calls"))
        assertFalse("夹数字分隔的 invoke 块应被剥离", out.contains("invoke"))
        assertTrue("正文前段应保留", out.contains("我先查一下"))
        assertTrue("正文后段应保留", out.contains("以上是查询过程"))
    }

    @Test
    fun `sanitizeToolCallSyntax keeps code fence containing tool_calls token`() {
        // guardrail F5：代码示例中出现 </tool_calls> 闭合标签时，围栏内不应被误删
        val fenced = "```xml\n" +
            "<tool_calls>\n<invoke name=\"x\"/>\n</tool_calls>\n" +
            "```"
        assertEquals("围栏内完整工具块不应剥离", fenced, sanitizeToolCallSyntax(fenced))
    }
}
