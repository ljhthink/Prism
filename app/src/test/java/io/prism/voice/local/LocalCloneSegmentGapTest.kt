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

    // ---- 批次30：单次播报总静音上界（DEF-01）----

    /** 造 `n` 个以 `。` 结尾的段（每个边界按句末满额）。 */
    private fun sentenceSegments(n: Int): List<String> = List(n) { "第${it}句。" }

    /** 计划里静音总时长（毫秒），按样本数折算。 */
    private fun totalMs(plan: IntArray, sampleRate: Int): Long =
        plan.sumOf { it.toLong() } * 1000L / sampleRate

    @Test
    fun `总静音上界 - 累计恰好用满 20 秒（真实长度表逐一钉住）`() {
        // 复刻批次29 验收 §3.5 的长度表：不设上界时这些会到 78s/400s/2000s。
        // ⚠️ 断言必须用**精确等值**而不是 `<=`（验收 C3）：`<=` 在全 0 计划下也会绿，
        // 等于把"封顶生效"和"完全不插静音"混为一谈。这里同时钉住"用满"与"边界数"两件事。
        val cap = LocalCloneSegmentGap.MAX_TOTAL_GAP_MS
        for (n in listOf(40, 201, 901, 1001)) {
            val plan = LocalCloneSegmentGap.gapPlanFor(
                sentenceSegments(n), baseGapMs = 2_000, sampleRate = 24_000
            )
            val ms = totalMs(plan, 24_000)
            assertEquals(
                "段数=$n 时总静音必须**恰好**用满 $cap ms（`<=` 会放过全 0 计划）",
                cap.toLong(),
                ms
            )
            // 2000ms/边界 ⇒ 恰好 10 个边界能插入（10×2000 = 20000）
            assertEquals("段数=$n 时非 0 边界数必须恰为 10", 10, plan.count { it > 0 })
            assertEquals("末段之后恒 0", 0, plan.last())
        }
    }

    @Test
    fun `总静音上界 - 恰好在预算内的边界必须保留，越界处硬停`() {
        val cap = LocalCloneSegmentGap.MAX_TOTAL_GAP_MS
        // 2000ms/边界 ⇒ 预算恰好容纳 10 个边界（10×2000 = 20000 = cap，不算"超过"）
        val exact = LocalCloneSegmentGap.gapPlanFor(
            sentenceSegments(11), baseGapMs = 2_000, sampleRate = 24_000, maxTotalGapMs = cap
        )
        assertEquals("恰好吃满预算的 10 个边界必须都保留", 10, exact.count { it > 0 })
        assertEquals("第 11 个边界已超预算 ⇒ 0", 0, exact[10])
        assertEquals("末段之后恒 0", 0, exact.last())

        // 再小 1ms 预算 ⇒ 只能放 9 个（硬停，不是"绕过后再试"）
        val tight = LocalCloneSegmentGap.gapPlanFor(
            sentenceSegments(11), baseGapMs = 2_000, sampleRate = 24_000, maxTotalGapMs = cap - 1
        )
        assertEquals("预算 19999ms ⇒ 只能放 9 个边界", 9, tight.count { it > 0 })
    }

    @Test
    fun `总静音上界 - 硬停语义：预算用尽后其后全部为 0（不是跳过再试）`() {
        // 为什么不能只用同质句末边界（guardrail F2）：若全部边界都是 2000ms，则"预算用尽后
        // 剩下的边界本身也放不下" ⇒ `break` 与 `continue` **结果相同**（都全 0），用例无法区分。
        // 这里构造**混合边界**：预算将尽时紧跟一个**小停顿**（逗号 800ms）——
        //   · 硬停（`break`）：该小边界**不得**被插入，其后全 0；
        //   · 跳过（`continue`）：该小边界恰好放得下 ⇒ 会被插入，`break`/`continue` 因此可区分。
        val cap = LocalCloneSegmentGap.MAX_TOTAL_GAP_MS
        // 关键构造：让"放不下的边界"紧跟"放得下的边界"。
        //   · 24 个逗号边界：每个 0.4×2000 = 800ms ⇒ 用掉 19_200ms（剩 800ms）
        //   · 第 25 个是**句末**（2000ms）⇒ 19_200+2_000 = 21_200 > 20_000 ⇒ 放不下
        //   · 第 26 个又是**逗号**（800ms）⇒ 19_200+800 = 20_000 ≤ cap ⇒ **放得下**
        // ⇒ `break`（硬停）⇒ 第 26 个为 0；`continue`（跳过再试）⇒ 第 26 个被插入。二者可区分。
        // ⚠️ 末段之后不插静音（`plan[lastIndex]` 恒 0），故**必须在后面再挂一段**，
        // 否则"第 26 个边界"其实是末段、永远是 0 ⇒ `break`/`continue` 无法区分
        // （初版漏了这一点，实测 M2 仍全绿）。
        val segs = List(24) { "第${it}句，" } +
            listOf("到这里。") + listOf("再来一句，", "最后一句。")
        val plan = LocalCloneSegmentGap.gapPlanFor(
            segs, baseGapMs = 2_000, sampleRate = 24_000, maxTotalGapMs = cap
        )
        assertEquals("前置：前 24 个逗号边界都必须插入（各 800ms）", 24, plan.take(24).count { it > 0 })
        assertEquals(
            "第 25 个边界（句末 2000ms）超出剩余预算 ⇒ 必须为 0",
            0, plan[24]
        )
        assertEquals(
            "**硬停语义**：第 26 个边界虽然自己放得下，但前一个已越界 ⇒ 必须也为 0" +
                "（若此处非 0，说明实现是 `continue` 而不是 `break`）",
            0, plan[25]
        )
        assertTrue("硬停后其后全部为 0", plan.drop(24).all { it == 0 })
        assertTrue("总量仍受上界约束", totalMs(plan, 24_000) <= cap)
    }

    @Test
    fun `总静音上界 - 启用档 500ms 下正常长度与无上界逐样本相同（不得误伤）`() {
        // 上界不得影响正常长度：500ms 档、20 句 = 10s 预算够用
        val segs = sentenceSegments(20)
        val capped = LocalCloneSegmentGap.gapPlanFor(segs, baseGapMs = 500, sampleRate = 16_000)
        val uncapped = LocalCloneSegmentGap.gapPlanFor(
            segs, baseGapMs = 500, sampleRate = 16_000, maxTotalGapMs = Int.MAX_VALUE
        )
        assertArrayEquals("500ms 档 20 句不得被上界影响", uncapped, capped)
    }

    @Test
    fun `总静音上界 - 非法上界按"不插"处理，不得解释为无上限`() {
        val segs = sentenceSegments(5)
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            val plan = LocalCloneSegmentGap.gapPlanFor(
                segs, baseGapMs = 500, sampleRate = 24_000, maxTotalGapMs = bad
            )
            assertEquals("maxTotalGapMs=$bad ⇒ 全 0", 0, plan.sum())
        }
    }

    @Test
    fun `总静音上界 - 计划长度恒等于段数（调用方可直接 getOrElse）`() {
        for (n in listOf(0, 1, 2, 40)) {
            val plan = LocalCloneSegmentGap.gapPlanFor(
                sentenceSegments(n), baseGapMs = 500, sampleRate = 24_000
            )
            assertEquals("段数=$n 时计划长度必须相等", n, plan.size)
        }
    }

    @Test
    fun `总静音上界 - 子句边界同样计入预算（不是只算句末）`() {
        // 全部是逗号结尾 ⇒ 每边界 0.4×500 = 200ms；预算 20000 ⇒ 100 个边界可放
        val clauseSegs = List(120) { "第${it}句，" }
        val plan = LocalCloneSegmentGap.gapPlanFor(clauseSegs, baseGapMs = 500, sampleRate = 24_000)
        assertEquals("子句档每个边界 200ms", 200, plan[0] * 1000 / 24_000)
        assertTrue(
            "子句边界也必须受总预算约束",
            totalMs(plan, 24_000) <= LocalCloneSegmentGap.MAX_TOTAL_GAP_MS
        )
    }
}
