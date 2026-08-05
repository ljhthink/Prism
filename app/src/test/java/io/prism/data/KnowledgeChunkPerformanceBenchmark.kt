package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * KnowledgeChunk CRUD 性能基准测试（ac-verifier 补充，US-002 性能基线）。
 *
 * 生成 put/get/remove 操作的 p50/p95/p99 延迟初版基线。
 * 默认跳过（DEF-02 修复）；手动运行获取基线数据：
 *   .\gradlew.bat testDebugUnitTest --tests "*.KnowledgeChunkPerformanceBenchmark" -PignorePerformanceTests=false
 */
class KnowledgeChunkPerformanceBenchmark {

    private lateinit var boxStore: BoxStore
    private lateinit var box: Box<KnowledgeChunk>
    private lateinit var tempDir: File

    companion object {
        private const val ITERATIONS = 500
        private const val BULK_SIZE = 1000
        private const val WARMUP = 50
    }

    @Before
    fun setUp() {
        // 仅当 Gradle 传入 -PignorePerformanceTests=false（注入 prism.runPerformanceTests=true）时运行；
        // 否则整类跳过。
        Assume.assumeTrue(
            "性能基准默认跳过；运行: testDebugUnitTest ... -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-perf-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        box = boxStore.boxFor(KnowledgeChunk::class.java)
    }

    @After
    fun tearDown() {
        // Assume 失败（跳过）时字段未初始化，需判空避免二次异常
        if (::boxStore.isInitialized) boxStore.close()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    @Test
    fun put_latency_benchmark() {
        // 预热
        repeat(WARMUP) {
            box.put(KnowledgeChunk(title = "warmup", content = "warmup"))
        }
        box.removeAll()

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val chunk = KnowledgeChunk(title = "perf-$i", content = "content-$i")
            val start = System.nanoTime()
            box.put(chunk)
            latencies[i] = System.nanoTime() - start
        }

        printStats("PUT (single)", latencies)
    }

    @Test
    fun get_latency_benchmark() {
        // 预先插入数据
        val ids = LongArray(ITERATIONS + WARMUP)
        repeat(ITERATIONS + WARMUP) { i ->
            ids[i] = box.put(KnowledgeChunk(title = "get-$i", content = "content-$i"))
        }

        // 预热
        repeat(WARMUP) { box.get(ids[it]) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            box.get(ids[i])
            latencies[i] = System.nanoTime() - start
        }

        printStats("GET (single)", latencies)
    }

    @Test
    fun remove_latency_benchmark() {
        // 预先插入数据
        val ids = LongArray(ITERATIONS + WARMUP)
        repeat(ITERATIONS + WARMUP) { i ->
            ids[i] = box.put(KnowledgeChunk(title = "rm-$i", content = "content-$i"))
        }

        // 预热（删除前补充数据）
        repeat(WARMUP) { box.remove(ids[it]) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            box.remove(ids[i + WARMUP])
            latencies[i] = System.nanoTime() - start
        }

        printStats("REMOVE (single)", latencies)
    }

    @Test
    fun bulk_put_benchmark() {
        // 预热
        repeat(2) {
            val chunks = (1..BULK_SIZE).map { KnowledgeChunk(title = "warmup-$it", content = "warmup") }
            chunks.forEach { box.put(it) }
            box.removeAll()
        }

        val latencies = LongArray(10)
        repeat(10) { round ->
            val chunks = (1..BULK_SIZE).map { KnowledgeChunk(title = "bulk-$round-$it", content = "content-$it") }
            val start = System.nanoTime()
            chunks.forEach { box.put(it) }
            latencies[round] = System.nanoTime() - start
            box.removeAll()
        }

        printStats("BULK PUT ($BULK_SIZE items)", latencies)
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
        println("  throughput: ${1_000_000_000.0 / mean * n / (max / 1_000_000_000.0)} ops/s (approx)")
    }
}
