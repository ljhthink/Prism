package io.prism.memory

import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.data.MyObjectBox
import io.prism.embedding.FakeEmbedder
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * M5 Phase C CrossSessionMemoryManager 性能基线测试（ac-verifier，首版基线）。
 *
 * 生成 saveSessionMemories / retrieveRelevantMemories / formatMemoriesAsContext
 * 在不同数据规模下的 p50/p95/p99 延迟初版基线。
 *
 * **与 Phase A 基线对比**：
 * - Phase A searchByVector top-3 (100 records): p50=62us
 * - Phase A save single: p50=1311us
 * - 本测试测量 CrossSessionMemoryManager 在 MemoryRepository 之上增加的管理层开销
 *   （filter/group/format + FakeEmbedder.embed 调用）
 *
 * **局限**：
 * - 使用 FakeEmbedder（非真实 OnnxEmbedder），embed 开销远低于生产（~0.01ms vs ~100ms）
 * - 纯 JVM ObjectBox 测试（非 Android 设备）
 * - 管理层开销（filter/group/format）是主要测量目标，embed 开销差异在报告中说明
 *
 * 复现方式：
 * ```bash
 * ./gradlew.bat testDebugUnitTest --tests "io.prism.memory.CrossSessionMemoryManagerPerfBaselineTest" --rerun-tasks
 * ```
 */
class CrossSessionMemoryManagerPerfBaselineTest {

    private lateinit var tempDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var embedder: FakeEmbedder
    private lateinit var manager: CrossSessionMemoryManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("prism-phaseC-perf-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        memoryRepository = MemoryRepository(boxStore)
        embedder = FakeEmbedder()
        manager = CrossSessionMemoryManager(embedder, memoryRepository, retrievalThreshold = 0.0)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    private fun makeTurnPairs(count: Int): List<ChatMessage> {
        return (1..count).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "请记住性能测试问题$i Kotlin协程", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "性能测试回答$i 使用launch启动", i.toLong() * 1000 + 500)
            )
        }
    }

    private fun printStats(op: String, scale: String, iters: Int, latenciesUs: LongArray, failures: Int, unit: String) {
        latenciesUs.sort()
        val p50 = latenciesUs[iters / 2]
        val p95 = latenciesUs[iters * 95 / 100]
        val p99 = latenciesUs[iters * 99 / 100]
        val min = latenciesUs[0]
        val max = latenciesUs[iters - 1]
        val throughput = "%.1f".format(1_000_000.0 / p50)
        println(
            "PERF_BASELINE|op=$op|scale=$scale|iters=$iters|" +
                "min=${min}us|p50=${p50}us|p95=${p95}us|p99=${p99}us|max=${max}us|" +
                "throughput=${throughput}_${unit}|failures=$failures"
        )
    }

    /**
     * 性能基线 1：saveSessionMemories 保存 1 个轮次对（对比 Phase A save single）。
     */
    @Test
    fun perf_baseline_saveSessionMemories_1_pair() {
        val iters = 50
        val latenciesUs = LongArray(iters)
        var failures = 0
        repeat(iters) { i ->
            val messages = listOf(
                ChatMessage(1, Role.USER, "请记住问题$i Kotlin", 1000L),
                ChatMessage(2, Role.ASSISTANT, "回答$i 协程", 2000L)
            )
            val start = System.nanoTime()
            try {
                runBlocking { manager.saveSessionMemories("perf-session-$i", messages) }
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printStats("saveSessionMemories", "1_pair", iters, latenciesUs, failures, "save_per_s")
    }

    /**
     * 性能基线 2：saveSessionMemories 保存 5 个轮次对。
     */
    @Test
    fun perf_baseline_saveSessionMemories_5_pairs() {
        // 预热
        runBlocking { manager.saveSessionMemories("warmup", makeTurnPairs(5)) }

        val iters = 30
        val latenciesUs = LongArray(iters)
        var failures = 0
        repeat(iters) { i ->
            val messages = makeTurnPairs(5)
            val start = System.nanoTime()
            try {
                runBlocking { manager.saveSessionMemories("perf-session-5-$i", messages) }
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printStats("saveSessionMemories", "5_pairs", iters, latenciesUs, failures, "save_per_s")
    }

    /**
     * 性能基线 3：saveSessionMemories 保存 10 个轮次对。
     */
    @Test
    fun perf_baseline_saveSessionMemories_10_pairs() {
        // 预热
        runBlocking { manager.saveSessionMemories("warmup", makeTurnPairs(10)) }

        val iters = 30
        val latenciesUs = LongArray(iters)
        var failures = 0
        repeat(iters) { i ->
            val messages = makeTurnPairs(10)
            val start = System.nanoTime()
            try {
                runBlocking { manager.saveSessionMemories("perf-session-10-$i", messages) }
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printStats("saveSessionMemories", "10_pairs", iters, latenciesUs, failures, "save_per_s")
    }

    /**
     * 性能基线 4：retrieveRelevantMemories top-3（100 条记录，对比 Phase A searchByVector top-3）。
     */
    @Test
    fun perf_baseline_retrieveRelevantMemories_top3_100_records() {
        // 插入 100 条记忆记录（50 个轮次对）
        runBlocking {
            (1..50).forEach { batch ->
                val messages = makeTurnPairs(2)
                manager.saveSessionMemories("seed-session-$batch", messages)
            }
        }
        assertEquals(100L, memoryRepository.count())

        // 预热
        runBlocking { repeat(3) { manager.retrieveRelevantMemories("Kotlin 协程", topK = 3) } }

        val iters = 30
        val latenciesUs = LongArray(iters)
        var failures = 0
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                runBlocking { manager.retrieveRelevantMemories("Kotlin 协程问题$i", topK = 3) }
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printStats("retrieveRelevantMemories", "100_records_top3", iters, latenciesUs, failures, "retrieve_per_s")
    }

    /**
     * 性能基线 5：formatMemoriesAsContext 格式化 3 条结果（纯函数，无 IO）。
     */
    @Test
    fun perf_baseline_formatMemoriesAsContext_3_results() {
        val results = listOf(
            MemorySearchResult(1, "s1", "[用户] 问题1\n[助手] 回答1", 0.9, 1000L, 1),
            MemorySearchResult(2, "s2", "[用户] 问题2\n[助手] 回答2", 0.8, 2000L, 2),
            MemorySearchResult(3, "s3", "[用户] 问题3\n[助手] 回答3", 0.7, 3000L, 3)
        )
        // 预热
        repeat(10) { manager.formatMemoriesAsContext(results) }

        val iters = 100
        val latenciesUs = LongArray(iters)
        var failures = 0
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                manager.formatMemoriesAsContext(results)
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printStats("formatMemoriesAsContext", "3_results", iters, latenciesUs, failures, "format_per_s")
    }

    private fun assertEquals(expected: Long, actual: Long) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
