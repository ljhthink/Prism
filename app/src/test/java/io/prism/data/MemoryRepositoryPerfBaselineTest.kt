package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M5 Phase A MemoryRepository 性能基线测试（ac-verifier 补充，首版基线）。
 *
 * 生成 searchByVector / save / getBySession 在不同数据规模下的 p50/p95/p99 延迟初版基线。
 * 用 PERF_BASELINE 行输出（参考 US-017 性能基线模式），存档于
 * `docs/reports/perf/2026-08-10-m5-phaseA-memory-baseline.md`。
 *
 * **局限**：
 * - 使用 oneHot 向量（非真实 OnnxEmbedder 向量），HNSW 索引开销可能与真实场景略有差异
 * - 纯 JVM ObjectBox 测试（非 Android 设备），生产基线需在 Android 设备补测
 * - 不含 Embedder.embed 延迟（生产 ~100ms/次）
 *
 * 复现方式：
 * ```bash
 * ./gradlew.bat testDebugUnitTest --tests "io.prism.data.MemoryRepositoryPerfBaselineTest" --rerun-tasks
 * # 从 XML system-out 采集 PERF_BASELINE 行
 * ```
 */
class MemoryRepositoryPerfBaselineTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "memory-perf-baseline-").toFile()
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
     * 性能基线 1：searchByVector top-3 检索延迟（100 条记录）。
     */
    @Test
    fun perf_baseline_searchByVector_top3() {
        val store = MyObjectBox.builder().directory(tempDir).build()
        val repo = MemoryRepository(store)

        try {
            // 插入 100 条记忆记录
            for (i in 0 until 100) {
                repo.save(
                    MemoryRecord(
                        sessionId = "session-$i",
                        content = "记忆内容 #$i",
                        embedding = oneHot(i % 384),
                        timestamp = i.toLong() * 1_000L,
                        turnCount = i
                    )
                )
            }

            // 预热 3 次
            repeat(3) { repo.searchByVector(oneHot(0), topK = 3) }

            // 正式计时
            val iters = 30
            val latenciesUs = LongArray(iters)
            var failures = 0
            repeat(iters) { i ->
                val start = System.nanoTime()
                try {
                    repo.searchByVector(oneHot(0), topK = 3)
                } catch (e: Exception) {
                    failures++
                }
                latenciesUs[i] = (System.nanoTime() - start) / 1_000
            }

            latenciesUs.sort()
            println(
                "PERF_BASELINE|op=searchByVector_top3|records=100|iters=$iters|" +
                    "min=${latenciesUs[0]}us|p50=${latenciesUs[iters / 2]}us|" +
                    "p95=${latenciesUs[iters * 95 / 100]}us|" +
                    "p99=${latenciesUs[iters * 99 / 100]}us|" +
                    "max=${latenciesUs[iters - 1]}us|" +
                    "throughput=${"%.1f".format(1_000_000.0 / latenciesUs[iters / 2])}_search_per_s|" +
                    "failures=$failures"
            )
        } finally {
            store.close()
        }
    }

    /**
     * 性能基线 2：save 单条记录延迟（含 StateFlow 刷新）。
     */
    @Test
    fun perf_baseline_save_single_record() {
        val store = MyObjectBox.builder().directory(tempDir).build()
        val repo = MemoryRepository(store)

        try {
            val iters = 50
            val latenciesUs = LongArray(iters)
            var failures = 0
            repeat(iters) { i ->
                val record = MemoryRecord(
                    sessionId = "perf-session-$i",
                    content = "性能测试记录 #$i",
                    embedding = oneHot(i % 384),
                    timestamp = System.currentTimeMillis(),
                    turnCount = i
                )
                val start = System.nanoTime()
                try {
                    repo.save(record)
                } catch (e: Exception) {
                    failures++
                }
                latenciesUs[i] = (System.nanoTime() - start) / 1_000
            }

            latenciesUs.sort()
            println(
                "PERF_BASELINE|op=save_single|records=1|iters=$iters|" +
                    "min=${latenciesUs[0]}us|p50=${latenciesUs[iters / 2]}us|" +
                    "p95=${latenciesUs[iters * 95 / 100]}us|" +
                    "p99=${latenciesUs[iters * 99 / 100]}us|" +
                    "max=${latenciesUs[iters - 1]}us|" +
                    "throughput=${"%.1f".format(1_000_000.0 / latenciesUs[iters / 2])}_save_per_s|" +
                    "failures=$failures"
            )
        } finally {
            store.close()
        }
    }

    /**
     * 性能基线 3：getBySession 内存过滤延迟（100 条记录中过滤 10 条）。
     */
    @Test
    fun perf_baseline_getBySession_filter() {
        val store = MyObjectBox.builder().directory(tempDir).build()
        val repo = MemoryRepository(store)

        try {
            // 插入 100 条记录，10 个 session 各 10 条
            for (s in 0 until 10) {
                for (i in 0 until 10) {
                    repo.save(
                        MemoryRecord(
                            sessionId = "session-$s",
                            content = "记忆 #$s-$i",
                            embedding = oneHot((s * 10 + i) % 384),
                            timestamp = (s * 10 + i).toLong() * 1_000L,
                            turnCount = i
                        )
                    )
                }
            }

            // 预热
            repeat(3) { repo.getBySession("session-0") }

            val iters = 30
            val latenciesUs = LongArray(iters)
            var failures = 0
            repeat(iters) { i ->
                val start = System.nanoTime()
                try {
                    repo.getBySession("session-${i % 10}")
                } catch (e: Exception) {
                    failures++
                }
                latenciesUs[i] = (System.nanoTime() - start) / 1_000
            }

            latenciesUs.sort()
            println(
                "PERF_BASELINE|op=getBySession_filter|records=100|iters=$iters|" +
                    "min=${latenciesUs[0]}us|p50=${latenciesUs[iters / 2]}us|" +
                    "p95=${latenciesUs[iters * 95 / 100]}us|" +
                    "p99=${latenciesUs[iters * 99 / 100]}us|" +
                    "max=${latenciesUs[iters - 1]}us|" +
                    "throughput=${"%.1f".format(1_000_000.0 / latenciesUs[iters / 2])}_query_per_s|" +
                    "failures=$failures"
            )
        } finally {
            store.close()
        }
    }
}
