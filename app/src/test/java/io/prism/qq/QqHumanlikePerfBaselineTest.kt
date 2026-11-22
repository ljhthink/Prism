package io.prism.qq

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-3139 性能门禁（ac-verifier，2026-11-22；rules/04 §十一 4）。
 *
 * 本轮无既有基线 → 生成**初版基线**（结果落 `docs/reports/perf/2026-11-22-qq-humanlike-acceptance-baseline.md`）。
 * 被测对象是本轮新增的两个纯函数热路径：
 * - `QqReplyTextGuard.apply`：每条出站回复必经，输入是模型正文（含超长技术回答）；
 * - `QqInboxCoalescer.waitMs`：收件箱每轮循环调用，输入是**全部待处理条目**（积压时 200+ 条）。
 *
 * 判据（rules/04 §十一 4）：
 * - p95 分别不得突破 [BUDGET_GUARD_MS] / [BUDGET_COALESCE_MS]（没有基线时用绝对预算兜底，
 *   防止"正则灾难性退化成秒级"这类事故被当成正常）；
 * - `waitMs` 必须是 O(n) 少常数的：200 条与 2000 条耗时不得出现数量级差（1 个数量级容忍）。
 */
class QqHumanlikePerfBaselineTest {

    /** 出口闸门 p95 预算：单条回复后处理 < 50ms 对 4s 生成耗时可忽略。 */
    private val budgetGuardMs = 50.0

    /** 合并窗口 p95 预算：每轮调度调用 < 5ms。 */
    private val budgetCoalesceMs = 5.0

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (p * sorted.size).toInt().coerceAtMost(sorted.size - 1)
        return sorted[idx]
    }

    private fun measure(iterations: Int, warmup: Int, body: () -> Unit): DoubleArray {
        repeat(warmup) { body() }
        val times = DoubleArray(iterations)
        for (i in 0 until iterations) {
            val started = System.nanoTime()
            body()
            times[i] = (System.nanoTime() - started) / 1_000_000.0
        }
        times.sort()
        return times
    }

    private fun report(name: String, times: DoubleArray): Pair<Double, Double> {
        val p50 = percentile(times, 0.50)
        val p95 = percentile(times, 0.95)
        val p99 = percentile(times, 0.99)
        println(
            "[PERF] %-46s p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms (n=%d)"
                .format(name, p50, p95, p99, times.last(), times.size)
        )
        return p50 to p95
    }

    // ==================== QqReplyTextGuard.apply ====================

    @Test
    fun `perf 出口闸门 —— 闲聊体短回复`() {
        val text = "十二点半……行吧，人家记下了[1]。\n\n到点要是没消息，人家就来敲门了♪[2]"
        val (_, p95) = report("QqReplyTextGuard.apply(闲聊体 ~60字)", measure(2_000, 200) {
            QqReplyTextGuard.apply(text)
        })
        assertTrue("闲聊体 p95 超预算：%.3fms".format(p95), p95 < budgetGuardMs)
    }

    @Test
    fun `perf 出口闸门 —— 4000 字以上技术长文`() {
        // 构造 4500+ 字：带引用编号、`\n\n` 段落、问句、对偶（真实工具轮次形态）
        val para = "这一段是在解释实现细节，含引用编号[1]与第二处[2]，以及一个问句吗？\n\n"
        val text = para.repeat(150) // ≈ 5700 字
        assertTrue("样本必须 >4000 字，实际=${text.length}", text.length > 4_000)
        val (_, p95) = report("QqReplyTextGuard.apply(技术长文 ${text.length}字)", measure(500, 50) {
            QqReplyTextGuard.apply(text)
        })
        assertTrue("长文 p95 超预算：%.3fms".format(p95), p95 < budgetGuardMs)
    }

    @Test
    fun `perf 出口闸门 —— 500 个引用编号的最坏清理`() {
        val text = ("结论。[1] ").repeat(500)
        val (_, p95) = report("QqReplyTextGuard.apply(500 引用正则清理)", measure(500, 50) {
            QqReplyTextGuard.apply(text)
        })
        assertTrue("引用清理 p95 超预算：%.3fms".format(p95), p95 < budgetGuardMs)
    }

    @Test
    fun `perf 出口闸门 —— 20k 字无标点单句（回溯压力）`() {
        val text = "啊".repeat(20_000)
        val (_, p95) = report("QqReplyTextGuard.apply(20k 单句)", measure(200, 20) {
            QqReplyTextGuard.apply(text)
        })
        assertTrue("20k 单句 p95 超预算：%.3fms".format(p95), p95 < budgetGuardMs)
    }

    // ==================== QqInboxCoalescer.waitMs ====================

    private fun enqueued(n: Int, now: Long) = (0 until n).map { now - n + it }

    @Test
    fun `perf 合并窗口 —— 200 条入队`() {
        val now = 1_700_000_000_000L
        val batch = enqueued(200, now)
        val (_, p95) = report("QqInboxCoalescer.waitMs(200 条)", measure(2_000, 200) {
            QqInboxCoalescer.waitMs(now, batch)
        })
        assertTrue("200 条 p95 超预算：%.3fms".format(p95), p95 < budgetCoalesceMs)
    }

    @Test
    fun `perf 合并窗口 —— 2000 条入队必须仍是 O(n) 少常数`() {
        val now = 1_700_000_000_000L
        val small = enqueued(200, now)
        val large = enqueued(2_000, now)

        repeat(200) { QqInboxCoalescer.waitMs(now, small) }
        var t0 = System.nanoTime()
        repeat(500) { QqInboxCoalescer.waitMs(now, small) }
        val smallMs = (System.nanoTime() - t0) / 1_000_000.0 / 500

        repeat(50) { QqInboxCoalescer.waitMs(now, large) }
        t0 = System.nanoTime()
        repeat(500) { QqInboxCoalescer.waitMs(now, large) }
        val largeMs = (System.nanoTime() - t0) / 1_000_000.0 / 500

        println("[PERF] waitMs 200 条=%.4fms / 2000 条=%.4fms".format(smallMs, largeMs))
        assertTrue(
            "10 倍数据量耗时不得出现 100 倍以上（疑似非线性）：%.4f → %.4f".format(smallMs, largeMs),
            largeMs < (smallMs * 100.0).coerceAtLeast(1.0)
        )
        assertTrue("2000 条单次必须 < 1ms，实际=%.4fms".format(largeMs), largeMs < 1.0)
    }
}
