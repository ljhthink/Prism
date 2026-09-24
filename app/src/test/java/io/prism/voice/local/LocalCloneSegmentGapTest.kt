package io.prism.voice.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段间停顿纯函数守卫（[LocalCloneSegmentGap]）。
 *
 * 背景：真机"本地音色语速过快"的实测根因**不是音素念得快，而是停顿被压缩**
 * （有声帧占比 0.849 vs 训练语料 0.736；平均停顿 0.232s vs 0.538s，−57%），
 * 加上端侧"切段合成 + 连续播放、段间不插静音"又进一步压缩了停顿。
 *
 * 本类钉住三件事：① 毫秒 → 样本的换算；② **越界/非法一律 0（不插）**而不是 clamp 到边界；
 * ③ **大采样率不溢出**（Int 中间量相乘会绕回负数 ⇒ 负长度数组直接抛异常）。
 */
class LocalCloneSegmentGapTest {

    @Test
    fun `换算 - 正常值按毫秒与采样率折算样本数`() {
        assertEquals(0, LocalCloneSegmentGap.silenceSamples(0, 24_000))
        assertEquals(24, LocalCloneSegmentGap.silenceSamples(1, 24_000))
        assertEquals(2_400, LocalCloneSegmentGap.silenceSamples(100, 24_000))
        assertEquals(8_000, LocalCloneSegmentGap.silenceSamples(500, 16_000))
        // 上限 2000ms 必须落在合法域内并正常折算（不是回落 0）
        assertEquals(
            48_000,
            LocalCloneSegmentGap.silenceSamples(LocalCloneConfig.MAX_SENTENCE_GAP_MS, 24_000)
        )
    }

    @Test
    fun `越界与非法 - 一律返回 0 而不是钳到边界`() {
        // 与 LocalCloneConfig.sanitized 同一约定：脏数据不得静默改变听感基线
        assertEquals("负数不得被钳成 0 之外的值（此处恰为 0）", 0, LocalCloneSegmentGap.silenceSamples(-5, 24_000))
        assertEquals("超上限不得被钳成 2000ms 的样本数", 0, LocalCloneSegmentGap.silenceSamples(2_001, 24_000))
        assertEquals("采样率非法", 0, LocalCloneSegmentGap.silenceSamples(300, 0))
        assertEquals("采样率非法", 0, LocalCloneSegmentGap.silenceSamples(300, -16_000))
    }

    @Test
    fun `大采样率与超长毫秒不得溢出成负数`() {
        // 超长毫秒先被合法域挡下（回落 0，不是钳到上限）
        assertEquals("Int 上限的 gap 属越界 ⇒ 0", 0, LocalCloneSegmentGap.silenceSamples(Int.MAX_VALUE, 48_000))
        // 极大采样率：`sampleRate.toLong() * gapMs` 用 Int 中间量会绕回负数 ⇒ 负长度数组。
        // 现在**超硬上界一律 0**（独立审查 L-1）：不能让调用方按 Int.MAX_VALUE 去分配数 GiB。
        // 该值 = 2000ms × 96kHz 的两倍余量；正常生产采样率（16k/24k/48k）远低于它。
        assertEquals(
            "极大采样率超硬上界 ⇒ 不插（0），绝不为负、也绝不给出巨大分配量",
            0,
            LocalCloneSegmentGap.silenceSamples(2_000, Int.MAX_VALUE)
        )
        assertEquals("48kHz 上限仍正常折算", 96_000, LocalCloneSegmentGap.silenceSamples(2_000, 48_000))
        assertEquals(
            "超出硬上界即 0（192000 样本 = 2s@96kHz）",
            0,
            LocalCloneSegmentGap.silenceSamples(2_000, 96_001)
        )
    }

    @Test
    fun `边界类型判定 - 句末给满额`() {
        // 段边界 ≠ 句边界（segmentForSynthesis 会退到子句标点甚至硬切），故按上一段结尾字符分档
        listOf("你好。", "真的吗？", "太好了！", "hello!", "why?", "end.").forEach {
            assertEquals("句末标点必须给满额：$it", 400, LocalCloneSegmentGap.gapMsFor(it, 400))
        }
        assertEquals("尾随空白必须先 trimEnd 再判（按 `。` 判 full）", 400, LocalCloneSegmentGap.gapMsFor("你好。  ", 400))
    }

    @Test
    fun `边界类型判定 - 子句标点约四成且封顶 base`() {
        listOf("你好，", "这样；", "注意：", "hi,", "yes;", "note:").forEach {
            assertEquals("子句标点必须打折（不是满额）：$it", 160, LocalCloneSegmentGap.gapMsFor(it, 400))
        }
        assertEquals("base=500 的子句档 = round(200)", 200, LocalCloneSegmentGap.gapMsFor("你好，", 500))
        // base 很小时不得算出比满额更大的值
        assertEquals("base=1 的子句档不得 > 1", 1, LocalCloneSegmentGap.gapMsFor("你好，", 1))
        assertEquals("base=2 的子句档不得 > 2", 1, LocalCloneSegmentGap.gapMsFor("你好，", 2))
        assertTrue("任何 base 下子句档都不得超过 base", LocalCloneSegmentGap.gapMsFor("你好，", 3) <= 3)
    }

    @Test
    fun `边界类型判定 - 硬切无标点一律 0 默认与空串也为 0`() {
        // 硬切可能切在词中间：此处插停顿 = "词中间凭空停顿"，比改动前更糟 ⇒ 必须 0
        assertEquals("无标点硬切", 0, LocalCloneSegmentGap.gapMsFor("然后我们一起去看", 400))
        assertEquals("英文字母硬切", 0, LocalCloneSegmentGap.gapMsFor("abcdef", 400))
        assertEquals("base<=0 ⇒ 恒 0（默认不改变行为）", 0, LocalCloneSegmentGap.gapMsFor("你好。", 0))
        assertEquals("base 为负 ⇒ 0", 0, LocalCloneSegmentGap.gapMsFor("你好。", -400))
        assertEquals("空串 ⇒ 0", 0, LocalCloneSegmentGap.gapMsFor("", 400))
        assertEquals("全空白 ⇒ 0", 0, LocalCloneSegmentGap.gapMsFor("   ", 400))
    }

    @Test
    fun `静音数组 - 长度正确且全零，为 0 时返回空数组`() {
        val gap = LocalCloneSegmentGap.silence(100, 24_000)
        assertEquals("长度必须等于 silenceSamples", 2_400, gap.size)
        assertArrayEquals("必须是全零静音", FloatArray(2_400), gap, 0f)

        assertEquals("gapMs=0 ⇒ 空数组（调用方据此跳过插入）", 0, LocalCloneSegmentGap.silence(0, 24_000).size)
        assertEquals("非法采样率 ⇒ 空数组", 0, LocalCloneSegmentGap.silence(300, 0).size)
        assertEquals("负 gap ⇒ 空数组", 0, LocalCloneSegmentGap.silence(-1, 24_000).size)
    }
}
