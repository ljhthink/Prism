package io.prism.document

import org.junit.Assume
import org.junit.Test

/**
 * Chunker 切片性能基准测试（ac-verifier 补充，US-013 性能基线 + O(n) 验证）。
 *
 * 测量不同规模输入（10k / 100k / 500k 字符）切片耗时 p50/p95/p99，
 * 验证 appendChunk 的 O(n) 复杂度（每窗口至多扫描 chunkSize 字符）。
 * 默认跳过；手动运行获取基线数据：
 *   .\gradlew.bat testDebugUnitTest --tests "*.ChunkerPerformanceBenchmark" -Dprism.runPerformanceTests=true
 */
class ChunkerPerformanceBenchmark {

    @Test
    fun chunk_latency_and_complexity_benchmark() {
        Assume.assumeTrue(
            "性能基准默认跳过；运行: ... -Dprism.runPerformanceTests=true",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
        val chunker = Chunker(chunkSize = 512, overlap = 64)

        // 构造含句号/段落的自然文本，模拟真实文档
        val sizes = intArrayOf(10_000, 100_000, 500_000)
        for (size in sizes) {
            val text = buildProsaicText(size)
            repeat(WARMUP) { chunker.chunk(text) }
            val latencies = LongArray(ITERATIONS)
            repeat(ITERATIONS) { i ->
                val start = System.nanoTime()
                chunker.chunk(text)
                latencies[i] = System.nanoTime() - start
            }
            printStats("Chunker chunk(${size / 1000}k 字符)", latencies)
        }
    }

    @Test
    fun chunk_hard_cut_latency_benchmark() {
        // 纯无边界长串（最坏路径：每窗口都硬切），验证 O(n) 无退化
        Assume.assumeTrue(
            "性能基准默认跳过；运行: ... -Dprism.runPerformanceTests=true",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
        val chunker = Chunker(chunkSize = 512, overlap = 0)
        val text = "a".repeat(500_000)
        repeat(WARMUP) { chunker.chunk(text) }
        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            chunker.chunk(text)
            latencies[i] = System.nanoTime() - start
        }
        printStats("Chunker hard-cut(500k 字符)", latencies)
    }

    private fun buildProsaicText(charCount: Int): String {
        val sb = StringBuilder()
        while (sb.length < charCount) {
            sb.append("这是用于检索的测试段落。包含若干句子，用于验证切片边界。\n\n")
        }
        return sb.toString()
    }

    private fun printStats(label: String, latencies: LongArray) {
        val sorted = latencies.sortedArray()
        val n = sorted.size
        val p50 = sorted[n * 50 / 100]
        val p95 = sorted[(n * 95 + 99) / 100 - 1]
        val p99 = sorted[(n * 99 + 99) / 100 - 1]
        val mean = latencies.average().toLong()
        val min = sorted.first()
        val max = sorted.last()

        println()
        println("===== $label =====")
        println("Iterations: $n")
        println("  p50: ${p50 / 1_000.0} us")
        println("  p95: ${p95 / 1_000.0} us")
        println("  p99: ${p99 / 1_000.0} us")
        println("  mean: ${mean / 1_000.0} us")
        println("  min:  ${min / 1_000.0} us")
        println("  max:  ${max / 1_000.0} us")
    }

    companion object {
        private const val ITERATIONS = 50
        private const val WARMUP = 10
    }
}