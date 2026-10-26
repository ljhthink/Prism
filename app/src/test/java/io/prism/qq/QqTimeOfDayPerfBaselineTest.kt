package io.prism.qq

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **ac-verifier 性能基线**（TKN-QQ-FIX-001；rules/04 §11.4）。
 *
 * 项目此前无 `QqTimeOfDay` 的性能基线，本用例建立**初版基线**并把上界写成宽松阈值，
 * 目的不是压测，而是**发现数量级回退**（例如有人把正则改成回溯型、或引入依赖注入/同步锁）。
 *
 * 断言只保证"不会慢到影响 UI/工具调用"（p95 < 5ms，实测约 0.2µs/次），
 * 具体观测值打印到 stdout（`[PERF]` 前缀），供报告引用。
 */
class QqTimeOfDayPerfBaselineTest {

    private fun percentile(sorted: LongArray, p: Double): Long =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    @Test
    fun `HH mm 解析 p50 p95 基线`() {
        val inputs = listOf("07:00", "7:5", "23:59", "24:00", "0:00", "12:30")
        val samples = LongArray(20_000)
        // 预热（JIT + 正则 Pattern 缓存）
        repeat(5_000) { i ->
            QqTimeOfDay.parse(inputs[i % inputs.size])
            QqTimeOfDay.format(i % 1441)
        }
        for (i in samples.indices) {
            val t0 = System.nanoTime()
            QqTimeOfDay.parse(inputs[i % inputs.size])
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        val p50 = percentile(samples, 0.50)
        val p95 = percentile(samples, 0.95)
        val p99 = percentile(samples, 0.99)
        val mean = samples.average()
        println(
            String.format(
                Locale.ROOT,
                "[PERF] QqTimeOfDay.parse n=%d p50=%dns p95=%dns p99=%dns mean=%.1fns",
                samples.size, p50, p95, p99, mean
            )
        )
        assertTrue("p95 解析延迟异常（疑似回退）：${p95}ns", p95 < 5_000_000L)
        assertTrue("p50 解析延迟异常：${p50}ns", p50 < 1_000_000L)
    }

    @Test
    fun `时窗合法性判定 p50 p95 基线`() {
        val samples = LongArray(20_000)
        repeat(5_000) { QqTimeOfDay.isValidWindow(1320, 120) }
        for (i in samples.indices) {
            val t0 = System.nanoTime()
            QqTimeOfDay.isValidWindow(1320, 120)
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        println(
            String.format(
                Locale.ROOT,
                "[PERF] QqTimeOfDay.isValidWindow n=%d p50=%dns p95=%dns p99=%dns",
                samples.size, percentile(samples, 0.50), percentile(samples, 0.95),
                percentile(samples, 0.99)
            )
        )
        assertTrue(percentile(samples, 0.95) < 1_000_000L)
    }

    @Test
    fun `FrameEncodePlan 生成 p50 p95 基线（热路径：每次截图编码一次）`() {
        val samples = LongArray(5_000)
        repeat(2_000) {
            io.prism.phonecontrol.bg.FrameEncodePlan.steps(720, 1560, 88, png = false)
        }
        for (i in samples.indices) {
            val t0 = System.nanoTime()
            io.prism.phonecontrol.bg.FrameEncodePlan.steps(720, 1560, 88, png = false)
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        println(
            String.format(
                Locale.ROOT,
                "[PERF] FrameEncodePlan.steps n=%d p50=%dns p95=%dns p99=%dns",
                samples.size, percentile(samples, 0.50), percentile(samples, 0.95),
                percentile(samples, 0.99)
            )
        )
        // 计划生成必须是微秒级；若退化为"每次分配大对象/反射"，这里会先红
        assertTrue(percentile(samples, 0.95) < 2_000_000L)
    }
}
