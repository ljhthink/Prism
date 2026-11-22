package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqReplyTextGuard] 的**边界与恶意输入**补充单测（ac-verifier，2026-11-22；US-3139 AC-2/AC-3）。
 *
 * 与 `QqReplyTextGuardTest` 的分工：那边是「真机四类特征的正常路径」，
 * 这里是 ac-verifier 要求的等价类/边界/恶意输入：
 * - 空、纯空白、纯引用（形态塌缩）、超长（4000+ 字）、Unicode（emoji / 代理对 / 组合字符）；
 * - `---` 气泡分隔符与问句收敛的交互（下游 `QqReplyBubbles.split` 按独立行 `---` 拆气泡）；
 * - 超长、深层嵌套括号、`[[` 残留、字面量 `[[sticker:…]]`；
 * - **幂等性**（闸门被重复调用不得二次改写）与「无灾难性回溯」的耗时上界。
 *
 * 断言只钉「形态」，不钉具体措辞（人设层负责语义）。
 */
class QqReplyTextGuardBoundaryTest {

    // ==================== 空 / 退化输入 ====================

    @Test
    fun `空字符串返回空且未改动`() {
        val r = QqReplyTextGuard.apply("")
        assertEquals("", r.text)
        assertFalse(r.changed)
    }

    @Test
    fun `只有换行与制表符等同空白`() {
        val r = QqReplyTextGuard.apply("\n\n\t \r\n")
        assertEquals("", r.text)
        assertFalse(r.changed)
    }

    @Test
    fun `纯引用编号的回复不会被清成空串 —— 保留原文是可接受的降级`() {
        // 记录实际行为：清完后为空会回落 original（`text.ifEmpty { original }`）。
        // 判定：可接受（宁可发一条 `[1]` 也不要发空气泡），但不是"引用一定被清掉"，
        // 因此 AC-2 的结论里必须写明这条边界。
        val r = QqReplyTextGuard.apply("[1][2]")
        assertEquals("[1][2]", r.text)
        assertEquals(2, r.citationsRemoved)
    }

    // ==================== 长度边界（闲聊体判据） ====================

    @Test
    fun `恰好 200 字仍按闲聊体处理`() {
        // 拼到**恰好** CHATTY_MAX_CHARS（含 `\n\n`），判据是 `length > 200` 才不算闲聊体
        val text = "好".repeat(QqReplyTextGuard.CHATTY_MAX_CHARS - 3) + "\n\n好"
        assertEquals(QqReplyTextGuard.CHATTY_MAX_CHARS, text.length)
        assertTrue(QqReplyTextGuard.isChatty(text))
        val r = QqReplyTextGuard.apply(text)
        assertFalse("200 字（含）以内必须折段落", r.text.contains("\n\n"))
        assertEquals(1, r.paragraphsMerged)
    }

    @Test
    fun `201 字起按正事处理 —— 只清引用不动结构`() {
        val body = "好".repeat(QqReplyTextGuard.CHATTY_MAX_CHARS + 1)
        assertFalse(QqReplyTextGuard.isChatty(body))
        val r = QqReplyTextGuard.apply(body + "\n\n第二段[1]")
        assertTrue("超阈值不得折段落", r.text.contains("\n\n"))
        assertEquals(1, r.citationsRemoved)
        assertEquals(0, r.paragraphsMerged)
    }

    @Test
    fun `4000 字以上超长文本只清引用且不丢内容`() {
        val body = "这一句是正常的中文内容。".repeat(400) // ≈ 4400 字
        val r = QqReplyTextGuard.apply(body + "[1][2][3]")
        assertTrue("长度必须基本保留（只减 9 个字符的引用标记）", r.text.length >= body.length)
        assertEquals(3, r.citationsRemoved)
        assertEquals(0, r.markdownRemoved)
        assertEquals(0, r.questionsRemoved)
    }

    @Test
    fun `超长文本里的大量引用编号被逐个清掉`() {
        val text = ("结论。[1] ").repeat(500) // 2500 字以上、500 个引用
        val r = QqReplyTextGuard.apply(text)
        assertEquals(500, r.citationsRemoved)
        assertFalse("不得残留引用编号", r.text.contains("[1]"))
    }

    // ==================== Unicode ====================

    @Test
    fun `emoji 与代理对不被截断`() {
        val text = "好呀😊😊\n\n晚安🌙🎐"
        val r = QqReplyTextGuard.apply(text)
        assertTrue("emoji 必须原样保留", r.text.contains("😊😊"))
        assertTrue(r.text.contains("🌙🎐"))
        assertFalse(r.text.contains("\n\n"))
    }

    @Test
    fun `组合字符与全角标点不被改写`() {
        val text = "好呀～１２３，嗯。"
        val r = QqReplyTextGuard.apply(text)
        assertEquals(text, r.text)
        assertFalse(r.changed)
    }

    @Test
    fun `零宽字符只占长度不改变形态判断`() {
        val r = QqReplyTextGuard.apply("在\u200b呀。")
        assertTrue(r.text.contains("\u200b"))
    }

    // ==================== `---` 气泡分隔与问句收敛 ====================

    @Test
    fun `问句收敛删掉整段时不得留下悬空的 - 分隔符`() {
        // `---` 是 `QqReplyBubbles` 的气泡分隔符（独立行），且分隔符归属于**后一句**——
        // 删掉最后一句时必须连它的 `---` 一起删，否则下游 split 会多出一个空气泡。
        val text = "好呀。\n---\n你在干嘛？\n---\n几点回来？\n---\n吃什么？"
        val r = QqReplyTextGuard.apply(text)
        assertEquals(1, r.questionsRemoved)
        assertEquals(2, Regex("""[？?]""").findAll(r.text).count())
        assertFalse("不得以悬空分隔符收尾", r.text.trimEnd().endsWith("---"))
        assertTrue("保留的两句仍在", r.text.contains("你在干嘛？") && r.text.contains("几点回来？"))
    }

    @Test
    fun `未超额时 - 分隔符原样保留`() {
        val text = "好呀。\n---\n你在干嘛？"
        val r = QqReplyTextGuard.apply(text)
        assertEquals(text, r.text)
        assertEquals(0, r.questionsRemoved)
    }

    @Test
    fun `气泡分隔不影响 markdown 清理`() {
        val r = QqReplyTextGuard.apply("**好呀**\n---\n- 第一件事")
        assertTrue("分隔符必须在", r.text.contains("---"))
        assertFalse(r.text.contains("**"))
        assertFalse("列表符必须去掉", r.text.contains("- 第一件事"))
    }

    // ==================== 恶意 / 畸形输入 ====================

    @Test
    fun `深层嵌套括号不崩溃且不误删下标`() {
        val text = "a" + "[".repeat(50) + "1" + "]".repeat(50)
        val r = QqReplyTextGuard.apply(text)
        assertEquals("被 [ 前缀保护的编号不得删除", text, r.text)
        assertEquals(0, r.citationsRemoved)
    }

    @Test
    fun `残留的双方括号与 sticker 字面量原样保留`() {
        // 指令标记本应在 `QqReplyDirective.parse` 里剥掉；若因模型畸形输出残留到这里，
        // 闸门**不得**二次加工（否则会把可见文本吃掉一半）。
        val text = "[[sticker:cyrene_happy:0.9]]好呀"
        val r = QqReplyTextGuard.apply(text)
        assertTrue(r.text.contains("[[sticker:cyrene_happy:0.9]]"))
        assertTrue(r.text.contains("好呀"))
    }

    @Test
    fun `超长无标点单句不超时（无灾难性回溯）`() {
        val text = "啊".repeat(20_000)
        val started = System.nanoTime()
        val r = QqReplyTextGuard.apply(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(text, r.text)
        assertTrue("20k 字单句不得超 2000ms（实测见 perf 基线）", elapsedMs < 2_000)
    }

    @Test
    fun `大量不是而是对偶只改写第二处起`() {
        val text = ("不是他，而是命。" ).repeat(20)
        val r = QqReplyTextGuard.apply(text)
        assertEquals(19, r.oppositionRewritten)
        // 第一处保持原样，其余变成「不是他，是命。」
        assertTrue(r.text.startsWith("不是他，而是命。"))
        assertTrue(r.text.count { it == '而' } == 1)
    }

    // ==================== 幂等性 ====================

    @Test
    fun `闸门幂等 —— 二次调用不再改动`() {
        val samples = listOf(
            "网确实是通的[1]，只是结果不太实在[3][12]。",
            "**好呀** 人家这就去\n- 先看第一处\n- 再看第二处",
            "真的吗？你在哪儿？几点回来？",
            "不是他不够努力，而是这件事本来就更难。还有，不是时间不够，而是方向选错了。",
            "好呀。\n---\n你在干嘛？\n---\n几点回来？\n---\n吃什么？"
        )
        for (raw in samples) {
            val once = QqReplyTextGuard.apply(raw)
            val twice = QqReplyTextGuard.apply(once.text)
            assertEquals("幂等失败：$raw", once.text, twice.text)
            assertFalse("二次调用不应再改动：$raw", twice.changed)
        }
    }

    @Test
    fun `capQuestions max 为 0 时一个都不留`() {
        val (text, removed) = QqReplyTextGuard.capQuestions("在吗？吃了吗？", max = 0)
        assertEquals(0, Regex("""[？?]""").findAll(text).count())
        assertEquals(2, removed)
    }
}
