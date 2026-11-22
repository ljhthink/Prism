package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqInboxCoalescer] / [QqMediaFollow] 的**边界补充**单测（ac-verifier，2026-11-22；US-3139 AC-9/AC-10）。
 *
 * ac-verifier 要求的边界点：
 * - 静默窗 1199 / 1200 / 1201 ms；
 * - 硬上限 3999 / 4000 / 4001 ms；
 * - 折叠窗口 9999 / 10000 / 10001 ms（AC-10 的关键门限）；
 * - 空数组、单条、**未排序**输入、重复时间戳、`nowMs` 早于入队（时钟回拨）；
 * - 200+ 条入队的取值正确性与耗时上界（perf 基线见 `QqHumanlikePerfBaselineTest`）。
 */
class QqInboxCoalescerBoundaryTest {

    private val t0 = 1_700_000_000_000L

    // ==================== 静默窗边界 1199 / 1200 / 1201 ====================

    @Test
    fun `静默窗 1199ms 还差 1ms`() {
        assertEquals(1L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 1_199L)))
    }

    @Test
    fun `静默窗 1200ms 整立即取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 1_200L)))
    }

    @Test
    fun `静默窗 1201ms 立即取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 1_201L)))
    }

    // ==================== 硬上限边界 3999 / 4000 / 4001 ====================

    @Test
    fun `硬上限 3999ms —— 只剩 1ms`() {
        // 最后一条刚到（静默窗还想等 1200ms），但硬上限先到 → 只能等 1ms
        assertEquals(1L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 3_999L, t0)))
    }

    @Test
    fun `硬上限 4000ms 整立即取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 4_000L, t0)))
    }

    @Test
    fun `硬上限 4001ms 立即取件`() {
        assertEquals(0L, QqInboxCoalescer.waitMs(t0, listOf(t0 - 4_001L, t0)))
    }

    // ==================== 输入形状鲁棒性 ====================

    @Test
    fun `未排序与重复时间戳结果一致`() {
        val sorted = listOf(t0 - 900L, t0 - 300L, t0)
        val shuffled = listOf(t0, t0 - 900L, t0 - 300L, t0)
        assertEquals(
            "min/max 语义与顺序无关",
            QqInboxCoalescer.waitMs(t0, sorted),
            QqInboxCoalescer.waitMs(t0, shuffled)
        )
    }

    @Test
    fun `时钟回拨时等待可超过硬上限（由调用方循环上限兜住）`() {
        // 记录实际行为：nowMs 早于入队时刻（协议端时间戳比本机快）时，
        // untilMax 被整体后移，waitMs 可返回 > MAX_WAIT_MS(4000) 的值（此处 6200ms）。
        // 生产影响：`processInboxOnce` 的 while(waited < MAX_LOOP_WAIT_MS=6000) 兜住，
        // 最坏多等 6s 才取件，不会挂死；已记入报告「已知边界」。
        val wait = QqInboxCoalescer.waitMs(t0 - 5_000L, listOf(t0))
        assertTrue("必须为正，实际=$wait", wait > 0L)
        assertEquals(6_200L, wait)
    }

    @Test
    fun `等待时长恒落在 0 与硬上限之间`() {
        val cases = listOf(
            listOf(t0),
            listOf(t0 - 100L),
            listOf(t0 - 3_000L, t0 - 10L),
            listOf(t0 - 50_000L, t0),
            listOf(t0 - 1L, t0 - 2L, t0 - 3L)
        )
        for (c in cases) {
            val wait = QqInboxCoalescer.waitMs(t0, c)
            assertTrue("越界：$c → $wait", wait in 0L..QqInboxCoalescer.MAX_WAIT_MS)
        }
    }

    @Test
    fun `200 条入队仍按最早与最后一条取值`() {
        val many = (0 until 200).map { t0 - 199L + it } // t0-199 .. t0
        assertEquals(QqInboxCoalescer.QUIET_MS, QqInboxCoalescer.waitMs(t0, many))
        val spread = many + listOf(t0 - 3_000L)
        assertEquals(1_000L, QqInboxCoalescer.waitMs(t0, spread))
    }

    @Test
    fun `reasonOf 在硬上限附近报近硬上限`() {
        assertEquals("近硬上限", QqInboxCoalescer.reasonOf(t0, listOf(t0 - 3_500L, t0)))
    }

    // ==================== 折叠窗口边界 9999 / 10000 / 10001 ====================

    private fun fold(gapMs: Long) = QqMediaFollow.shouldFold(
        texts = listOf("[表情包]"),
        mediaKinds = listOf("STICKER"),
        nowMs = t0,
        lastRepliedAtMs = t0 - gapMs
    )

    @Test
    fun `折叠窗口 9999ms 仍折叠`() {
        assertTrue(fold(9_999L))
    }

    @Test
    fun `折叠窗口 10000ms 整仍折叠（闭区间）`() {
        assertTrue("AC-10 的「≤10s」必须含端点", fold(10_000L))
    }

    @Test
    fun `折叠窗口 10001ms 不折叠`() {
        assertFalse("超过窗口的贴图视为新话题开场", fold(10_001L))
    }

    @Test
    fun `lastRepliedAt 在未来（时钟回拨）不折叠`() {
        assertFalse(fold(-5_000L))
    }

    @Test
    fun `多张表情包同一批可折叠`() {
        assertTrue(
            QqMediaFollow.shouldFold(
                texts = listOf("[表情包]", "[表情]"),
                mediaKinds = listOf("STICKER", "FACE"),
                nowMs = t0,
                lastRepliedAtMs = t0 - 500L
            )
        )
    }

    @Test
    fun `媒体类型大小写敏感 —— 小写 sticker 不折叠（未知类型默认不折叠是安全方向）`() {
        assertFalse(
            QqMediaFollow.shouldFold(listOf("[表情包]"), listOf("sticker"), t0, t0 - 500L)
        )
    }

    @Test
    fun `空批不折叠`() {
        assertFalse(QqMediaFollow.shouldFold(emptyList(), emptyList(), t0, t0 - 500L))
        assertFalse(QqMediaFollow.shouldFold(emptyList(), listOf("STICKER"), t0, t0 - 500L))
    }

    @Test
    fun `日志行含秒数与不回复结论且不含正文`() {
        val folded = QqMediaFollow.logLine(t0, t0 - 3_200L, folded = true)
        assertTrue(folded.contains("纯表情跟随上一条已回复消息（+3.2s），不单独回复"))
        assertFalse("日志不得带聊天正文/方括号占位符", folded.contains("["))
        val notFolded = QqMediaFollow.logLine(t0, 0L, folded = false)
        assertTrue(notFolded.contains("单独处理"))
    }
}
