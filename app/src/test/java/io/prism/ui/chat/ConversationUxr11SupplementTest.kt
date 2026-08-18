package io.prism.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXR11 补充测试（ac-verifier，TKN-UXR11-ACCEPTANCE-001）。
 *
 * 覆盖 U1（RAG 误注入根因修复）与 U4（Fetch 失败后乱码净化）的纯函数：
 * - **U1** [ConversationViewModel.needsRagRetrieval]：文档内容直发消息
 *   （`【文档：…】` 前缀）必须跳过 RAG 自动注入；普通查询/短查询照常保留（防误伤）。
 * - **U4** [sanitizeToolCallSyntax]：剥离 LLM 幻觉输出的完整工具调用块
 *   （`<tool_calls>…</tool_calls>` / `<invoke…>…</invoke>` / `<|…|>` / `<｜…｜>` 变体），
 *   并转义残余标签起始 `<`，使 Markdown 渲染显示实际文本而非乱码。
 */
class ConversationUxr11SupplementTest {

    // ==================== U1：RAG 文档前缀（ADR-033） ====================

    @Test
    fun `needsRagRetrieval skips document content message`() {
        // R5「＋→文件」上传后文本以【文档：…】包裹直发 —— 文档本身即本轮上下文，跳过 RAG
        assertFalse(
            "文档内容直发应跳过 RAG",
            ConversationViewModel.needsRagRetrieval("【文档：年度报告.pdf】\n第一章 概述\n…正文…")
        )
        assertFalse(
            "文档 + 用户需求合并仍应跳过",
            ConversationViewModel.needsRagRetrieval("【文档：设计稿.docx】请根据文档内容帮我总结要点")
        )
    }

    @Test
    fun `needsRagRetrieval keeps normal queries after document prefix check`() {
        // 非文档消息照常：含查询内容的长句保留（防误伤）
        assertTrue(
            "普通查询应保留",
            ConversationViewModel.needsRagRetrieval("帮我查一下项目里 RAG 的嵌入模型是什么")
        )
        // 文档前缀必须是完整前缀（【文档：），其它【xxx： 不触发跳过
        assertTrue(
            "非文档前缀的【内容应保留",
            ConversationViewModel.needsRagRetrieval("【提示】请帮我总结知识库")
        )
    }

    @Test
    fun `needsRagRetrieval document prefix does not break greeting skip`() {
        // 原 UXR8-R3 行为不回归：纯寒暄仍跳过
        assertFalse("寒暄仍应跳过", ConversationViewModel.needsRagRetrieval("你好"))
        assertFalse("确认语仍应跳过", ConversationViewModel.needsRagRetrieval("好的，谢谢"))
    }

    @Test
    fun `needsRagRetrieval boundary blank and prefix-only messages`() {
        // 边界：空白消息无检索需求
        assertFalse("空白消息应跳过", ConversationViewModel.needsRagRetrieval(""))
        assertFalse("纯空白应跳过", ConversationViewModel.needsRagRetrieval("   "))
        // 边界：仅前缀无正文（极端占位）也应跳过 RAG
        assertFalse("仅前缀也应跳过", ConversationViewModel.needsRagRetrieval("【文档："))
        // 边界：非文档前缀且非寒暄 → 保留（防误伤）
        assertTrue("含查询的长句应保留", ConversationViewModel.needsRagRetrieval("帮我查一下 Prism 的嵌入模型"))
    }

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
    fun `sanitizeToolCallSyntax keeps code fence containing tool_calls token`() {
        // guardrail F5：代码示例中出现 </tool_calls> 闭合标签时，围栏内不应被误删
        val fenced = "```xml\n" +
            "<tool_calls>\n<invoke name=\"x\"/>\n</tool_calls>\n" +
            "```"
        assertEquals("围栏内完整工具块不应剥离", fenced, sanitizeToolCallSyntax(fenced))
    }
}
