package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * US-017 向量检索性能基线测试（ac-verifier 补充，首版基线）。
 *
 * 生成 search 方法在不同 chunk 数量下的 p50/p95/p99 延迟初版基线。
 * 用 PERF_BASELINE 行输出（参考 US-016 性能基线模式），存档于
 * `docs/reports/perf/2026-08-07-us017-retrieval-baseline.md`。
 *
 * **局限**：
 * - 使用 oneHot 向量（非真实 OnnxEmbedder 向量），HNSW 索引开销可能与真实场景略有差异
 * - 纯 JVM ObjectBox 测试（非 Android 设备），生产基线需在 Android 设备补测
 * - 不含 Embedder.embed 延迟（生产 ~100ms/次，BR-concurrency-002 持锁）
 *
 * 复现方式：
 * ```bash
 * ./gradlew.bat testDebugUnitTest --tests "io.prism.data.KnowledgeBaseRetrievalPerfBaselineTest" --rerun-tasks
 * # 从 XML system-out 采集 PERF_BASELINE 行
 * ```
 */
class KnowledgeBaseRetrievalPerfBaselineTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-retrieval-perf-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun oneHot(dominantIndex: Int): FloatArray {
        val vector = FloatArray(384)
        vector[dominantIndex] = 1.0f
        return vector
    }

    /**
     * 性能基线：100/500/1000 chunk 的 top-5 检索延迟。
     *
     * 每个配置独立 BoxStore（隔离数据），预热 3 次后正式计时。
     */
    @Test
    fun perf_baseline_search_top5() {
        val configs = listOf(
            PerfConfig(chunkCount = 100, iters = 20),
            PerfConfig(chunkCount = 500, iters = 10),
            PerfConfig(chunkCount = 1000, iters = 5)
        )

        for (config in configs) {
            val result = measureSearchLatency(config)
            println(
                "PERF_BASELINE|chunks=${config.chunkCount}|iters=${config.iters}|" +
                    "min=${result.minUs}us|p50=${result.p50Us}us|p95=${result.p95Us}us|" +
                    "p99=${result.p99Us}us|max=${result.maxUs}us|" +
                    "throughput=${result.throughput}_search_per_s|failures=${result.failures}"
            )
        }
    }

    private data class PerfConfig(val chunkCount: Int, val iters: Int)

    private data class PerfResult(
        val minUs: Long,
        val p50Us: Long,
        val p95Us: Long,
        val p99Us: Long,
        val maxUs: Long,
        val throughput: String,
        val failures: Int
    )

    private fun measureSearchLatency(config: PerfConfig): PerfResult {
        // 为每个配置创建独立 BoxStore
        val perfDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-perf-${config.chunkCount}-").toFile()
        val store = MyObjectBox.builder().directory(perfDir).build()
        val repo = KnowledgeBaseRepository(store)

        try {
            // 插入 chunk（oneHot 向量，循环使用维度 0~383）
            for (i in 0 until config.chunkCount) {
                repo.addChunk(
                    KnowledgeChunk(
                        title = "doc#${i + 1}",
                        content = "content-$i",
                        embedding = oneHot(i % 384),
                        knowledgeBaseId = 0L
                    )
                )
            }

            // 预热（3 次，HNSW 索引 page cache 预热）
            repeat(3) { repo.search(oneHot(0), k = 5) }

            // 正式计时（微秒精度，搜索操作通常 <1ms）
            val latenciesUs = LongArray(config.iters)
            var failures = 0
            repeat(config.iters) { i ->
                val start = System.nanoTime()
                try {
                    repo.search(oneHot(0), k = 5)
                } catch (e: Exception) {
                    failures++
                }
                latenciesUs[i] = (System.nanoTime() - start) / 1_000  // ns → us
            }

            latenciesUs.sort()
            val minUs = latenciesUs[0]
            val p50Us = latenciesUs[config.iters / 2]
            val p95Idx = (config.iters * 95 / 100).coerceAtMost(config.iters - 1)
            val p99Idx = (config.iters * 99 / 100).coerceAtMost(config.iters - 1)
            val p95Us = latenciesUs[p95Idx]
            val p99Us = latenciesUs[p99Idx]
            val maxUs = latenciesUs[config.iters - 1]
            // 吞吐：基于 p50 微秒计算每秒搜索次数
            val throughput = if (p50Us > 0) "%.1f".format(1_000_000.0 / p50Us) else "0.0"

            return PerfResult(minUs, p50Us, p95Us, p99Us, maxUs, throughput, failures)
        } finally {
            store.close()
            perfDir.deleteRecursively()
        }
    }
}
