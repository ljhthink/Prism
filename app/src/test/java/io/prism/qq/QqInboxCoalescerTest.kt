package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqInboxCoalescer] / [QqMediaFollow] / [QqToolIntent] 单测（2026-11-22；ADR-080）。
 *
 * 前两组对应真机「连续两条 → 两次回复」；第三组对应「不主动查资料」。
 */
class QqInboxCoalescerTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `没有待处理条目时立刻取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, emptyList()))
    }

    @Test
    fun `刚入队一条要等满静默窗`() {
        val wait = QqInboxCoalescer.waitMs(t0, listOf(t0))
        assertEquals(QqInboxCoalescer.QUIET_MS, wait)
    }

    @Test
    fun `已经静默够了就立刻取件 —— 即使没到硬上限`() {
        // 3s 前入队、此后无新消息：静默窗（1.2s）早已满足，不必再等
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 3_000L)))
    }

    @Test
    fun `静默窗按最后一条延长`() {
        val a = QqInboxCoalescer.waitMs(t0, listOf(t0 - 500L))
        val b = QqInboxCoalescer.waitMs(t0, listOf(t0 - 500L, t0 - 100L))
        assertEquals(700L, a) // 500ms 前入队 → 还差 700ms 满 1200ms
        assertEquals(1_100L, b) // 100ms 前入队 → 还差 1100ms
    }

    @Test
    fun `已静默足够久立即取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 5_000L)))
    }

    @Test
    fun `硬上限 —— 用户不停手也必须回`() {
        // 最早一条已等 3.9s，即使最后一条刚刚到达，也只能再等 100ms
        val wait = QqInboxCoalescer.waitMs(t0, listOf(t0 - 3_900L, t0))
        assertEquals(100L, wait)
    }

    @Test
    fun `超过硬上限立刻取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 4_000L)))
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 30_000L)))
    }

    @Test
    fun `等待时长永不为负且不超过循环上限`() {
        val wait = QqInboxCoalescer.waitMs(t0, listOf(t0 - 100_000L, t0))
        assertTrue(wait >= 0L)
        assertTrue(wait <= QqInboxCoalescer.MAX_LOOP_WAIT_MS)
    }

    @Test
    fun `原因只给类别不给正文`() {
        assertEquals("静默窗", QqInboxCoalescer.reasonOf(t0, listOf(t0)))
        assertNull(QqInboxCoalescer.reasonOf(t0, listOf(t0 - 10_000L)))
    }
}

class QqMediaFollowTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `刚回过一句话之后的纯表情折叠`() {
        assertTrue(
            QqMediaFollow.shouldFold(
                texts = listOf("[表情包]"),
                mediaKinds = listOf("STICKER"),
                nowMs = t0,
                lastRepliedAtMs = t0 - 3_000L
            )
        )
    }

    @Test
    fun `内置表情也可折叠`() {
        assertTrue(
            QqMediaFollow.shouldFold(
                texts = listOf("[表情]"),
                mediaKinds = listOf("FACE"),
                nowMs = t0,
                lastRepliedAtMs = t0 - 1_000L
            )
        )
    }

    @Test
    fun `截图不折叠 —— 有信息量必须回应`() {
        assertFalse(
            QqMediaFollow.shouldFold(
                texts = listOf("[图片]"),
                mediaKinds = listOf("IMAGE"),
                nowMs = t0,
                lastRepliedAtMs = t0 - 1_000L
            )
        )
    }

    @Test
    fun `混合批次只要含图片就不折叠`() {
        assertFalse(
            QqMediaFollow.shouldFold(
                texts = listOf("[表情包]", "[图片]"),
                mediaKinds = listOf("STICKER", "IMAGE"),
                nowMs = t0,
                lastRepliedAtMs = t0 - 1_000L
            )
        )
    }

    @Test
    fun `超出跟随窗口的表情不折叠`() {
        assertFalse(
            QqMediaFollow.shouldFold(
                texts = listOf("[表情包]"),
                mediaKinds = listOf("STICKER"),
                nowMs = t0,
                lastRepliedAtMs = t0 - QqMediaFollow.FOLD_WINDOW_MS - 1
            )
        )
    }

    @Test
    fun `带文字的消息永不折叠`() {
        assertFalse(
            QqMediaFollow.shouldFold(
                texts = listOf("要去玩王者啦", "[表情包]"),
                mediaKinds = listOf("STICKER"),
                nowMs = t0,
                lastRepliedAtMs = t0 - 1_000L
            )
        )
    }

    @Test
    fun `从未回复过时折叠不成立`() {
        assertFalse(
            QqMediaFollow.shouldFold(listOf("[表情包]"), listOf("STICKER"), t0, 0L)
        )
    }

    @Test
    fun `无媒体时折叠不成立`() {
        assertFalse(
            QqMediaFollow.shouldFold(listOf(" "), emptyList(), t0, t0 - 500L)
        )
    }

    @Test
    fun `占位符识别`() {
        assertTrue(QqMediaFollow.isInvisible("[媒体消息]"))
        assertTrue(QqMediaFollow.isInvisible("[图片] [表情]"))
        assertTrue(QqMediaFollow.isInvisible("   "))
        assertFalse(QqMediaFollow.isInvisible("在吗"))
        assertFalse(QqMediaFollow.isInvisible("[图片] 这是啥"))
    }

    @Test
    fun `引用编号与未知方括号词不算占位符`() {
        // ac-verifier D-2：旧实现的正则近似会把 "[1]" 当占位符 → "你看这个[1]" 被判无可见文本
        assertFalse(QqMediaFollow.isInvisible("你看这个[1]"))
        assertFalse(QqMediaFollow.isInvisible("[笑]"))
        assertFalse(QqMediaFollow.isInvisible("[图片]这是啥"))
    }
}

class QqToolIntentTest {

    @Test
    fun `明确查询动作命中`() {
        assertTrue(QqToolIntent.shouldSearch("帮我查一下明天的天气"))
        assertTrue(QqToolIntent.shouldSearch("你搜搜这个"))
    }

    @Test
    fun `时效名词命中`() {
        assertTrue(QqToolIntent.shouldSearch("今天有什么新闻"))
        assertTrue(QqToolIntent.shouldSearch("现在金价多少"))
        assertTrue(QqToolIntent.shouldSearch("这个票多少钱"))
    }

    @Test
    fun `时间词加疑问命中`() {
        assertTrue(QqToolIntent.shouldSearch("最近有什么新番？"))
    }

    @Test
    fun `事实追问命中`() {
        assertTrue(QqToolIntent.shouldSearch("那个人是谁？"))
    }

    @Test
    fun `纯闲聊不触发检索`() {
        assertFalse(QqToolIntent.shouldSearch("今天好累"))
        assertFalse(QqToolIntent.shouldSearch("还没呢，还在忙"))
        assertFalse(QqToolIntent.shouldSearch("晚安"))
        assertFalse(QqToolIntent.shouldSearch(""))
    }

    @Test
    fun `提示文本含禁引用与禁念标题约束`() {
        val hint = QqToolIntent.hintFor("今天有什么新闻")
        assertNotNull(hint)
        assertTrue(hint!!.contains("web_search__search"))
        assertTrue(hint.contains("别带 [1][2]"))
        assertNull(QqToolIntent.hintFor("今天好累"))
    }

    @Test
    fun `原因只给类别`() {
        assertEquals("查询动作", QqToolIntent.reasonOf("帮我查一下"))
        assertEquals("时效事实", QqToolIntent.reasonOf("今天的热搜"))
        assertNull(QqToolIntent.reasonOf("晚安"))
    }
}
