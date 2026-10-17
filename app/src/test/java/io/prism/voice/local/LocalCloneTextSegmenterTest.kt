package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长文本切段的**不变量**用例（TKN-LVC-012 / BUG-20260920-006）。
 *
 * 最重要的不变量是**不丢字符**：`segments.joinToString("") == text`。
 * 真机缺陷背景见 [segmentForSynthesis] 的 KDoc（长文本单次无界合成 ⇒ 无声 + 进程被 SIGKILL）。
 */
class LocalCloneTextSegmenterTest {

    private val max = 40

    @Test
    fun `短文本原样返回 —— 不改变既有行为`() {
        val t = "先别急着说正事，慢慢来"
        assertEquals(listOf(t), segmentForSynthesis(t, max))
        assertEquals(listOf(t), segmentForSynthesis(t, 0))
    }

    @Test
    fun `不丢字符 —— 拼接必须与原文逐字相同（最关键不变量）`() {
        val cases = listOf(
            "今天天气很好，我们一起出去走走吧。等风停了，我就带你去看那边的云。",
            "这是一段没有任何标点的很长很长的文本".repeat(6),
            "短句。短句。短句。短句。短句。短句。短句。短句。短句。短句。",
            "混合：标点、English words, and 数字 12345，还有很长的一段话需要被切开处理。",
            "只有逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号，逗号"
        )
        for (t in cases) {
            val segs = segmentForSynthesis(t, max)
            assertEquals("切段不得丢字符（原文长度=${t.length}）", t, segs.joinToString(""))
        }
    }

    @Test
    fun `每段都不超过上限 —— 峰值内存因此有界`() {
        val t = "这是一段很长的没有句末标点的文本".repeat(10)
        for (s in segmentForSynthesis(t, max)) {
            // 允许 +2：收尾处"仅含标点的残余"会并回上一段（已在 KDoc 显式声明；guardrail N5）
            assertTrue("段长 ${s.length} 不得超过 $max+2：$s", s.length <= max + 2)
        }
    }

    @Test
    fun `优先在句末标点切 —— 不破坏自然停顿`() {
        val t = "第一句到此结束。第二句也到此结束。" // 各 7 字，均 >= max/2 ⇒ 应按句切
        val segs = segmentForSynthesis(t, 8)
        assertEquals(listOf("第一句到此结束。", "第二句也到此结束。"), segs)
    }

    @Test
    fun `无句末标点时回退到子句标点 —— 不切在词中间`() {
        val t = "一二三四五，六七八九十，十一十二十三" // 无句末标点，需在逗号处切
        val segs = segmentForSynthesis(t, 8)
        assertEquals(t, segs.joinToString(""))
        assertTrue("应在逗号后收束，实际=$segs", segs.first().endsWith("，"))
    }

    @Test
    fun `规则6 —— 短文本也能按句末标点切分（不丢字符、每段不超上限）`() {
        val t = "第一句。第二句。第三句。"
        val segs = segmentForSynthesis(t, max, splitSentences = true)
        assertEquals(listOf("第一句。", "第二句。", "第三句。"), segs)
        assertEquals("切段不得丢字符", t, segs.joinToString(""))
        for (s in segs) assertTrue("段长 ${s.length} 不得超过 $max：$s", s.length <= max)
    }

    @Test
    fun `规则6 —— 关闭时短文本行为与既有逐字相同（回归门，防误伤既有音色包）`() {
        val t = "第一句。第二句。第三句。"
        assertEquals(listOf(t), segmentForSynthesis(t, max))
        assertEquals(listOf(t), segmentForSynthesis(t, max, splitSentences = false))
    }

    @Test
    fun `规则6 —— 短文本无句末标点时仍为单段（不得因此被硬切）`() {
        val t = "先别急着说正事，慢慢来"
        assertEquals(listOf(t), segmentForSynthesis(t, max, splitSentences = true))
    }

    @Test
    fun `规则6 —— 代理对安全（句末标点后切分不劈开代理对）`() {
        val t = "\uD834\uDD1E第一句。\uD834\uDD1E第二句。"
        val segs = segmentForSynthesis(t, max, splitSentences = true)
        assertEquals("切段不得丢字符（含代理对）", t, segs.joinToString(""))
        assertEquals(2, segs.size)
    }

    @Test
    fun `默认段长常量必须落在看门狗阈值内可完成的范围`() {
        // 首段上限必须显著小于常规上限（N1：首声延迟 = 首段合成时间）
        assertTrue("首段上限应远小于常规上限", FIRST_SEGMENT_CHARS < DEFAULT_SEGMENT_CHARS)
        assertEquals(16, FIRST_SEGMENT_CHARS)
        assertEquals(40, DEFAULT_SEGMENT_CHARS)
    }
}
