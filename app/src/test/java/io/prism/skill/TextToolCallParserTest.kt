package io.prism.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextToolCallParser] 单元测试（v1 批次12，A/D13 —— glm-4.6v-flash 文本型工具调用解析）。
 */
class TextToolCallParserTest {

    @Test
    fun `parses fenced html tool call block from glm output`() {
        // 用户真机证据原样：glm 把工具调用写在 ```html 围栏内
        val text = """
            我会帮助您打开拼多多并搜索相关产品。首先，我需要启动拼多多应用。

            ```html 
            <tool_call>phone_control__launch_app 
            <arg_key>package</arg_key> 
            <arg_value>com.pinduoduo.pinduoduo</arg_value> 
            </tool_call> 
            ```
        """.trimIndent()
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("phone_control__launch_app", calls[0].name)
        assertEquals("com.pinduoduo.pinduoduo", calls[0].arguments["package"])
    }

    @Test
    fun `parses bare block without fence`() {
        val text = "<tool_call>phone_control__tap\n<arg_key>node_id</arg_key>\n<arg_value>3</arg_value>\n</tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("phone_control__tap", calls[0].name)
        // 数字参数解析为 Long
        assertEquals(3L, calls[0].arguments["node_id"])
    }

    @Test
    fun `parses multiple blocks in order`() {
        val text = """
            <tool_call>skill__a<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>
            正文
            <tool_call>skill__b<arg_key>y</arg_key><arg_value>2</arg_value></tool_call>
        """.trimIndent()
        val calls = TextToolCallParser.parse(text)
        assertEquals(2, calls.size)
        assertEquals("skill__a", calls[0].name)
        assertEquals("skill__b", calls[1].name)
    }

    @Test
    fun `parses json object and boolean args`() {
        val text = """
            <tool_call>skill__t
            <arg_key>obj</arg_key><arg_value>{"a":1,"b":"x"}</arg_value>
            <arg_key>flag</arg_key><arg_value>true</arg_value>
            </tool_call>
        """.trimIndent()
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        @Suppress("UNCHECKED_CAST")
        val obj = calls[0].arguments["obj"] as? Map<String, Any?>
        assertEquals(1L, obj?.get("a"))
        assertEquals("x", obj?.get("b"))
        assertEquals(true, calls[0].arguments["flag"])
    }

    @Test
    fun `rejects invalid tool names`() {
        // 名字为空 / 含非法字符 → 忽略该块
        val text = "<tool_call>  \n<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>"
        assertTrue(TextToolCallParser.parse(text).isEmpty())
        val bad = "<tool_call>中文名字<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>"
        assertTrue(TextToolCallParser.parse(bad).isEmpty())
    }

    @Test
    fun `empty and no-tool text returns empty`() {
        assertTrue(TextToolCallParser.parse("").isEmpty())
        assertTrue(TextToolCallParser.parse("  纯文本，无工具调用   ").isEmpty())
        assertTrue(TextToolCallParser.parse("<div>普通 html</div>").isEmpty())
    }

    @Test
    fun `stripTextToolCalls removes fenced and bare blocks`() {
        val fenced = "正文\n```html\n<tool_call>phone_control__tap\n<arg_key>x</arg_key>\n<arg_value>1</arg_value>\n</tool_call>\n```"
        val stripped = TextToolCallParser.stripTextToolCalls(fenced)
        assertTrue("应移除围栏工具块", !stripped.contains("tool_call"))
        assertTrue("保留正文", stripped.contains("正文"))

        val bare = "A <tool_call>skill__t<arg_key>x</arg_key><arg_value>1</arg_value></tool_call> B"
        assertTrue(!TextToolCallParser.stripTextToolCalls(bare).contains("tool_call"))
    }

    @Test
    fun `stripTextToolCalls preserves normal code fences`() {
        val code = "代码如下：\n```kotlin\nval x = 1\n```"
        assertEquals(code, TextToolCallParser.stripTextToolCalls(code))
    }

    // ==================== ac-verifier 边界补充（TKN-V1B12-ACCEPTANCE-001） ====================

    @Test
    fun `parse ignores unclosed block without closing tag`() {
        // 边界：缺少 </tool_call> 的未闭合块 → 不解析（避免把半截 XML 当工具调用执行）
        val text = "<tool_call>skill__t\n<arg_key>x</arg_key>\n<arg_value>1</arg_value>\n"
        assertTrue(TextToolCallParser.parse(text).isEmpty())
        // 剥离同样不应删除未闭合块（保留原文，防止吞正文）
        assertTrue(TextToolCallParser.stripTextToolCalls(text).contains("<tool_call"))
    }

    @Test
    fun `parse handles attributes on tool_call tag`() {
        // 边界：<tool_call> 带属性变体（容错）
        val text = "<tool_call name=\"a\" type=\"single\">skill__t\n<arg_key>x</arg_key>\n<arg_value>1</arg_value>\n</tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("skill__t", calls[0].name)
    }

    @Test
    fun `parse is case insensitive for tool_call tag`() {
        // 边界：大写 <TOOL_CALL> 标签（IGNORE_CASE）
        val text = "<TOOL_CALL>skill__t<arg_key>x</arg_key><arg_value>1</arg_value></TOOL_CALL>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("skill__t", calls[0].name)
    }

    @Test
    fun `parse duplicate arg key keeps first value`() {
        // 边界：重复 arg_key → 首值保留（防参数覆盖注入）
        val text = "<tool_call>skill__t<arg_key>p</arg_key><arg_value>1</arg_value><arg_key>p</arg_key><arg_value>2</arg_value></tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals(1L, calls[0].arguments["p"])
    }

    @Test
    fun `parse mismatched key value counts pairs only first`() {
        // 边界：key/value 数量不一致 → 仅按序配对 min(n,m)，多余忽略
        val extraKeys = "<tool_call>skill__t<arg_key>a</arg_key><arg_value>1</arg_value><arg_key>b</arg_key></tool_call>"
        val calls1 = TextToolCallParser.parse(extraKeys)
        assertEquals(1, calls1.size)
        assertEquals(1L, calls1[0].arguments["a"])
        assertTrue("多余 key 应被忽略", !calls1[0].arguments.containsKey("b"))

        val extraValues = "<tool_call>skill__t<arg_key>a</arg_key><arg_value>1</arg_value><arg_value>2</arg_value></tool_call>"
        val calls2 = TextToolCallParser.parse(extraValues)
        assertEquals(1, calls2.size)
        assertEquals("多余 value 应被忽略（仅 a=1）", 1, calls2[0].arguments.size)
    }

    @Test
    fun `parse empty arg value returns empty string`() {
        // 边界：空 <arg_value></arg_value> → 空串（非 null）
        val text = "<tool_call>skill__t<arg_key>x</arg_key><arg_value></arg_value></tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("", calls[0].arguments["x"])
    }

    @Test
    fun `parse non json chinese string value preserved verbatim`() {
        // 边界：非 JSON 中文参数值 → 原样字符串（包名/文本锚点场景）
        val text = "<tool_call>phone_control__launch_app<arg_key>package</arg_key><arg_value>拼多多</arg_value></tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals("拼多多", calls[0].arguments["package"])
    }

    @Test
    fun `parse float and negative number values`() {
        // 边界：浮点/负数参数 → Double/Long
        val text = "<tool_call>skill__t<arg_key>f</arg_key><arg_value>3.5</arg_value><arg_key>n</arg_key><arg_value>-2</arg_value></tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(3.5, calls[0].arguments["f"] as Double, 0.0)
        assertEquals(-2L, calls[0].arguments["n"])
    }

    @Test
    fun `parse mixed fenced and bare blocks in one text`() {
        // 边界：围栏块 + 裸块混合 → 全部按序解析
        val text = "```html\n<tool_call>skill__a<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>\n```\n正文\n<tool_call>skill__b<arg_key>y</arg_key><arg_value>2</arg_value></tool_call>"
        val calls = TextToolCallParser.parse(text)
        assertEquals(2, calls.size)
        assertEquals("skill__a", calls[0].name)
        assertEquals("skill__b", calls[1].name)
    }

    @Test
    fun `stripTextToolCalls handles block at very start and end plus trims`() {
        // 边界：块在文本最前/最后 + 首尾空白 → 剥离并 trim
        val leading = "<tool_call>skill__t<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>\n\n正文"
        assertEquals("正文", TextToolCallParser.stripTextToolCalls(leading))
        val trailing = "正文\n\n<tool_call>skill__t<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>"
        assertEquals("正文", TextToolCallParser.stripTextToolCalls(trailing))
        val onlyBlock = "<tool_call>skill__t<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>"
        assertEquals("", TextToolCallParser.stripTextToolCalls(onlyBlock))
    }

    @Test
    fun `stripTextToolCalls blank and whitespace only unchanged`() {
        // 边界：空串/纯空白 → 原样返回（blank 早退，不 trim）
        assertEquals("", TextToolCallParser.stripTextToolCalls(""))
        assertTrue(TextToolCallParser.stripTextToolCalls("   ").isBlank())
    }

    @Test
    fun `stripTextToolCalls removes tool block inside fence with leading text leaves fence residue`() {
        // 已知限制（guardrail Q5，TKN-V1B12-GUARDRAIL-001 附录 A）：围栏首行后夹带其它文字时，
        // FENCED_TOOL_BLOCK（要求 ```lang 行后紧跟 <tool_call）不整块匹配；但 TOOL_CALL_BLOCK
        // 仍会剥离内部 <tool_call> 块 → 关键安全目标达成（XML 不泄漏到 UI/历史），
        // 仅残留 ``` 围栏标记（LOW 外观问题，非安全泄漏，文档化当前行为）。
        val text = "正文\n```html\n说明文字\n<tool_call>skill__t<arg_key>x</arg_key><arg_value>1</arg_value></tool_call>\n```"
        val stripped = TextToolCallParser.stripTextToolCalls(text)
        assertFalse("工具块内容不应泄漏", stripped.contains("<tool_call"))
        assertTrue("正文保留", stripped.contains("正文"))
    }
}
