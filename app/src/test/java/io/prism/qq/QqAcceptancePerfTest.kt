package io.prism.qq

import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * **性能门禁**（ac-verifier 补充；2026-11-15）。
 *
 * 只打印 p50/p95 并做**极宽松**的上界断言（防 CI 抖动误红）；门禁比较见
 * `docs/reports/perf/2026-11-15-qq-context-composer-baseline.md`。
 *
 * 覆盖：
 * - `QqProactiveComposer.buildReplyUserContext`：200 条历史 + 480 字文本 + 全动态段（生产形态）；
 * - 兼容形态（`now=null`、无 pending/shape）：即改动前调用形态，用于估算新增段的相对开销；
 * - `QqReplyBubbles.split`：480 字、3 段。
 */
class QqAcceptancePerfTest {

    private fun history(n: Int): List<QqMessage> = (1..n).map { i ->
        QqMessage(
            messageId = "m$i",
            incoming = i % 2 == 0,
            text = "这是第 $i 条历史消息，用来撑满上下文预算。".repeat(3).take(110),
            timestampMs = 1_760_000_000_000L + i * 1_000L,
            mediaNote = if (i % 17 == 0) "对方发来一个抱抱的表情包" else null
        )
    }

    private fun pct(sorted: LongArray, p: Double): Long =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    private fun bench(label: String, warmup: Int, rounds: Int, block: () -> Unit): Pair<Long, Long> {
        repeat(warmup) { block() }
        val samples = LongArray(rounds) {
            val t0 = System.nanoTime()
            block()
            System.nanoTime() - t0
        }
        samples.sort()
        val p50 = pct(samples, 0.50) / 1000
        val p95 = pct(samples, 0.95) / 1000
        val p99 = pct(samples, 0.99) / 1000
        println("[PERF] $label p50=${p50}us p95=${p95}us p99=${p99}us n=$rounds")
        assertTrue("$label p95 必须远低于一帧预算（10ms）", p95 < 10_000)
        return p50 to p95
    }

    @Test
    fun `buildReplyUserContext 生产形态与兼容形态耗时`() {
        val h = history(200)
        val incoming = "对方刚发来的这一条".repeat(40).take(480)
        val now = LocalDateTime.of(2026, 11, 15, 23, 30)
        val pending = history(3)

        bench("composer.full(200hist/480char/pending/shape)", 200, 1000) {
            QqProactiveComposer.buildReplyUserContext(
                history = h,
                incoming = incoming,
                mediaNote = "对方发来一张图，OCR 未识别出文字",
                pending = pending,
                memorySection = "记忆段".repeat(100),
                mediaHint = "可以带一个表情",
                shapeHint = "这次就一句话接住，别追问，也别解释。",
                now = now,
                lastMessageAtMs = h.last().timestampMs
            )
        }
        bench("composer.legacy(now=null,无动态段)", 200, 1000) {
            QqProactiveComposer.buildReplyUserContext(history = h, incoming = incoming)
        }
    }

    @Test
    fun `QqReplyBubbles split 480 字三气泡耗时`() {
        val seg = "这是气泡正文的一段内容，用来测拆分成本。".repeat(8).take(158)
        val text = "$seg\n---\n$seg\n---\n${seg.take(150)}"
        assertTrue("夹具必须是 480 字级别", text.length in 400..520)
        bench("bubbles.split(480char/3parts)", 500, 2000) {
            QqReplyBubbles.split(text)
        }
    }
}
