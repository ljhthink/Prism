package io.prism.qq

import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqCatchUp] 补发窗口单测（2026-11-02；ADR-071 / PRD US-3129 AC-4 / AC-5）。
 *
 * 真机故障形态：19:49 链死 → 20:23 消息「在吗」 → 21:18 恢复。
 * 恢复时这条消息落在 60 分钟窗口内，必须被补回；而 00:41 那种隔夜消息必须**不**补，
 * 否则会出现"17 小时后突然回昨天的消息"。
 */
class QqCatchUpTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun m(at: Long, incoming: Boolean = true, id: String? = null, text: String = "hi") =
        QqMessage(
            messageId = id ?: "m$at",
            incoming = incoming,
            text = text,
            timestampMs = at
        )

    @Test
    fun `窗口内的入站消息被补回`() {
        val history = listOf(m(t0 - 40 * minute), m(t0 - 20 * minute))
        val got = QqCatchUp.selectMissed(history, lastSeenAtMs = t0 - 60 * minute, nowMs = t0)
        assertEquals(2, got.size)
        // 时间升序
        assertTrue(got[0].timestampMs < got[1].timestampMs)
    }

    @Test
    fun `超出 60 分钟窗口不补`() {
        val history = listOf(m(t0 - 120 * minute), m(t0 - 90 * minute))
        val got = QqCatchUp.selectMissed(history, lastSeenAtMs = t0 - 300 * minute, nowMs = t0)
        assertTrue(got.isEmpty())
        assertEquals(2, QqCatchUp.countExpired(history, t0 - 300 * minute, t0))
    }

    @Test
    fun `水位之前的消息不补`() {
        val history = listOf(m(t0 - 10 * minute), m(t0 - 5 * minute))
        val got = QqCatchUp.selectMissed(history, lastSeenAtMs = t0 - 6 * minute, nowMs = t0)
        assertEquals(1, got.size)
        assertEquals("m${t0 - 5 * minute}", got.first().messageId)
    }

    @Test
    fun `自己发出的消息不补`() {
        val history = listOf(m(t0 - 10 * minute, incoming = false))
        assertTrue(QqCatchUp.selectMissed(history, t0 - 60 * minute, t0).isEmpty())
    }

    @Test
    fun `首次运行不补发历史`() {
        val history = listOf(m(t0 - 10 * minute))
        assertTrue(QqCatchUp.selectMissed(history, lastSeenAtMs = 0L, nowMs = t0).isEmpty())
    }

    @Test
    fun `已处理过的 id 不重复补`() {
        val history = listOf(m(t0 - 10 * minute, id = "dup"))
        val got = QqCatchUp.selectMissed(
            history, lastSeenAtMs = t0 - 60 * minute, nowMs = t0, knownIds = setOf("dup")
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `窗口内命中的全部入队（不再截断）`() {
        // guardrail R-02：旧实现只 take(5) 却把水位前移到历史最新 → 第 6 条起永久丢失。
        val history = (1..20).map { m(t0 - it * minute, id = "x$it") }
        val got = QqCatchUp.selectMissed(history, t0 - 60 * minute, t0)
        assertEquals(20, got.size)
        // 时间升序：最旧的先补
        assertEquals("x20", got.first().messageId)
    }

    @Test
    fun `显式 maxBatch 仍可限流`() {
        val history = (1..20).map { m(t0 - it * minute, id = "x$it") }
        val got = QqCatchUp.selectMissed(history, t0 - 60 * minute, t0, maxBatch = 5)
        assertEquals(5, got.size)
    }

    @Test
    fun `水位推进到历史最新且不回退`() {
        val history = listOf(m(t0 - 30 * minute), m(t0 - 1 * minute, incoming = false))
        assertEquals(t0 - 1 * minute, QqCatchUp.newWatermark(history, 0L))
        assertEquals(t0 + minute, QqCatchUp.newWatermark(history, t0 + minute))
    }

    @Test
    fun `安全水位只推进到已入队或已知的消息`() {
        // guardrail R2-03：历史里第 3 条没有着落（未入队、未回复）→ 水位不得越过它
        val a = m(t0 - 30 * minute, id = "a")
        val b = m(t0 - 20 * minute, id = "b")
        val c = m(t0 - 10 * minute, id = "c")
        val history = listOf(a, b, c)
        val wm = QqCatchUp.advanceWatermark(
            history, current = t0 - 40 * minute,
            enqueued = setOf("a"), knownIds = setOf("b")
        )
        assertEquals(t0 - 20 * minute, wm)
    }

    @Test
    fun `一条都没着落时水位不前进`() {
        val history = listOf(m(t0 - 10 * minute, id = "x"))
        assertEquals(
            t0 - 40 * minute,
            QqCatchUp.advanceWatermark(history, current = t0 - 40 * minute, enqueued = emptySet())
        )
    }

    @Test
    fun `水位不跨过未着落的消息（历史被截断时的保护）`() {
        // guardrail R3-01：历史只返回最近 N 条；窗口内第 1 条拿不到（未着落），
        // 若水位推到最新，那条会永久丢失。这里必须封顶在未着落点之前。
        val a = m(t0 - 50 * minute, id = "a")
        val b = m(t0 - 40 * minute, id = "b")
        val c = m(t0 - 30 * minute, id = "c")
        val history = listOf(b, c) // a 被截断、不在历史里 → 但 b 未着落时不得越过 b
        val wm = QqCatchUp.advanceWatermark(
            history, current = t0 - 60 * minute, enqueued = setOf("c")
        )
        assertEquals(t0 - 40 * minute - 1, wm)
        // 未着落的 b 仍在"水位之后"，下一轮重连会重新扫描到它（不会被永久跨过）
        assertTrue(b.timestampMs > wm)
    }

    @Test
    fun `窗口边界 59分59秒 60分整 60分01秒`() {
        // 验收报告要求：窗口边界三断言（`nowMs - ts <= windowMs` 为含端点）
        val inside = m(t0 - (59 * minute + 59_000L), id = "in")
        val edge = m(t0 - 60 * minute, id = "edge")
        val outside = m(t0 - 60 * minute - 1_000L, id = "out")
        val history = listOf(inside, edge, outside)
        val got = QqCatchUp.selectMissed(history, lastSeenAtMs = t0 - 90 * minute, nowMs = t0)
        // 时间升序：更早的 edge 在前
        assertEquals(listOf("edge", "in"), got.map { it.messageId })
        assertEquals(1, QqCatchUp.countExpired(history, t0 - 90 * minute, t0))
    }

    @Test
    fun `自己发出的消息不推进水位`() {
        val history = listOf(m(t0 - 1 * minute, incoming = false, id = "self"))
        assertEquals(
            t0 - 40 * minute,
            QqCatchUp.advanceWatermark(history, current = t0 - 40 * minute, enqueued = setOf("self"))
        )
    }
}
