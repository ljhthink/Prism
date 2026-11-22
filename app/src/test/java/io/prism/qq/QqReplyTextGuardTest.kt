package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqReplyTextGuard] 单测（2026-11-22；ADR-080）。
 *
 * 数据基线来自真机 `files/qq_conversations.json` 的 101 条 bot 回复统计：
 * 32.7% 以问句收尾、42.6% 带 `\n\n` 段落、5.9% 命中「不是…而是」、工具轮次带 `[1][3]`。
 * 这些用例就是这四类的一一对应断言。
 */
class QqReplyTextGuardTest {

    @Test
    fun `引用编号全部清掉`() {
        val r = QqReplyTextGuard.apply("网确实是通的[1]，只是结果不太实在[3][12]。")
        assertEquals("网确实是通的，只是结果不太实在。", r.text)
        assertEquals(3, r.citationsRemoved)
    }

    @Test
    fun `Markdown 链接不被当成引用编号删掉`() {
        val r = QqReplyTextGuard.apply("看这个[1](https://example.com)：说得挺清楚")
        assertTrue("带括号的方括号数字是链接，必须原样保留", r.text.contains("[1](https://example.com)"))
        assertEquals(0, r.citationsRemoved)
    }

    @Test
    fun `代码下标不被当成引用编号删掉`() {
        val r = QqReplyTextGuard.apply("你把 arr[0] 改成 list[12] 试试")
        assertTrue("数组下标属于事实，不得删除", r.text.contains("arr[0]"))
        assertTrue(r.text.contains("list[12]"))
        assertEquals(0, r.citationsRemoved)
    }

    @Test
    fun `含围栏代码块时整段跳过引用清理`() {
        val code = "```kotlin\nval a = listOf(1)\nval b = a[0]\n```\n结果[1]"
        val r = QqReplyTextGuard.apply(code)
        assertTrue("代码块内不得改动", r.text.contains("a[0]"))
        assertEquals("含代码块时连正文引用也不清（宁可留编号，不改代码）", 0, r.citationsRemoved)
    }

    @Test
    fun `闲聊体里的 Markdown 被拆掉`() {
        val r = QqReplyTextGuard.apply("**好呀** 人家这就去\n- 先看第一处\n- 再看第二处")
        assertFalse(r.text.contains("**"))
        assertFalse(r.text.contains("- "))
        assertTrue(r.markdownRemoved >= 2)
    }

    @Test
    fun `长技术回复保留结构只清引用`() {
        val long = "先看一处：你的 flow 是在哪个作用域里起的？\n\n".repeat(12) + "[1][2]"
        val r = QqReplyTextGuard.apply(long)
        assertTrue("长回复不得被压成一段", r.text.contains("\n\n"))
        assertEquals(2, r.citationsRemoved)
        assertEquals(0, r.paragraphsMerged)
    }

    @Test
    fun `闲聊体的段落被折成单换行`() {
        val r = QqReplyTextGuard.apply("十二点半……行吧，人家记下了。\n\n到点要是没消息，人家就来敲门了。")
        assertFalse(r.text.contains("\n\n"))
        assertEquals(1, r.paragraphsMerged)
    }

    @Test
    fun `问句收敛到上限并优先删尾句`() {
        val r = QqReplyTextGuard.apply("真的吗？你在哪儿？几点回来？")
        assertEquals(1, r.questionsRemoved)
        assertFalse(r.text.contains("几点回来"))
        assertTrue(r.text.contains("真的吗？"))
    }

    @Test
    fun `未超额时不删问句`() {
        val r = QqReplyTextGuard.apply("在呀。怎么，这局输了还是赢了？")
        assertEquals(0, r.questionsRemoved)
        assertTrue(r.text.contains("输了还是赢了？"))
    }

    @Test
    fun `第二处起的不是而是被改写为直接断言`() {
        val text = "不是他不够努力，而是这件事本来就更难。" +
            "还有，不是时间不够，而是方向选错了。"
        val r = QqReplyTextGuard.apply(text)
        assertEquals(1, r.oppositionRewritten)
        assertTrue("第一处保留原样", r.text.contains("不是他不够努力，而是这件事本来就更难"))
        assertTrue("第二处改写成陈述", r.text.contains("不是时间不够，是方向选错了"))
    }

    @Test
    fun `不含代码块与表格的短文本判为闲聊体`() {
        assertTrue(QqReplyTextGuard.isChatty("在呀，刚洗完澡。"))
        assertFalse(QqReplyTextGuard.isChatty("```kotlin\nval a = 1\n```"))
        assertFalse(QqReplyTextGuard.isChatty("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertFalse(QqReplyTextGuard.isChatty("x".repeat(QqReplyTextGuard.CHATTY_MAX_CHARS + 1)))
    }

    @Test
    fun `空输入不崩且不改动`() {
        val r = QqReplyTextGuard.apply("   ")
        assertEquals("", r.text)
        assertFalse(r.changed)
    }

    @Test
    fun `真机样本回归 —— 不把正常回复改坏`() {
        val real = "十二点半……行吧，人家记下了。到点要是没消息，人家就来敲门了♪"
        val r = QqReplyTextGuard.apply(real)
        assertEquals(real, r.text)
        assertFalse(r.changed)
    }
}
