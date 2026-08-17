package io.prism.embedding

import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ac-verifier 性能基准测试（US-014，CLAUDE.md 第十一节 4 性能回退检查）。
 *
 * 测量 embed / 模型加载 / embedBatch 延迟（p50/p95/p99）与吞吐。
 * 使用真实 onnxruntime JVM + INT8 量化模型，与生产 Android 路径同代码。
 *
 * 默认跳过；手动运行获取基线数据：
 *   .\gradlew.bat :app:testDebugUnitTest --tests "*.OnnxEmbedderPerformanceBenchmark" -PignorePerformanceTests=false
 *
 * 注：JVM 测试用 x86 桌面原生库，绝对延迟低于 Android ARM64 真机；
 *     但作为初版基线用于后续回退检测（>50% 失败 / >20% 警告）。
 */
class OnnxEmbedderPerformanceBenchmark {

    private val warmup = 10
    private val iterations = 100

    private var embedder: OnnxEmbedder? = null

    @Before
    fun skipUnlessEnabled() {
        Assume.assumeTrue(
            "性能基准默认跳过；运行需 -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
    }

    @After
    fun tearDown() {
        embedder?.close()
    }

    /** 短文本 embed 延迟：典型查询场景。 */
    @Test
    fun benchmark_embed_short_text() {
        val e = createEmbedder()
        // warmup
        repeat(warmup) { e.embed("hello world") }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            e.embed("hello world")
            System.nanoTime() - start
        }
        printStats("EMBED_SHORT", times)
    }

    /** 长文本 embed 延迟：切片后片段长度（~400 chars）。 */
    @Test
    fun benchmark_embed_long_text() {
        val e = createEmbedder()
        val longText = "The quick brown fox jumps over the lazy dog. ".repeat(10) // ~440 chars
        // warmup
        repeat(warmup) { e.embed(longText) }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            e.embed(longText)
            System.nanoTime() - start
        }
        printStats("EMBED_LONG", times)
    }

    /** 模型加载延迟：首次 embed 含 OrtSession 创建（卸载后重载场景）。 */
    @Test
    fun benchmark_model_load() {
        val iterations = 30 // 模型加载较慢，减少迭代次数
        val times = LongArray(iterations) {
            val e = createEmbedder()
            try {
                val start = System.nanoTime()
                e.embed("hello")
                System.nanoTime() - start
            } finally {
                e.close()
            }
        }
        printStats("MODEL_LOAD_AND_FIRST_EMBED", times)
    }

    /** 批量 embed 吞吐：4 条文本（典型 RAG 文档批次）。 */
    @Test
    fun benchmark_embed_batch_throughput() {
        val e = createEmbedder()
        val texts = listOf("hello world", "Prism AI chat agent", "knowledge base retrieval", "machine learning")
        // warmup
        repeat(warmup) { e.embedBatch(texts) }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            e.embedBatch(texts)
            System.nanoTime() - start
        }
        printStats("EMBED_BATCH_4", times)
        // 吞吐：文本/秒
        val totalDocs = iterations.toLong() * texts.size
        val totalMs = times.sum() / 1_000_000.0
        println("  throughput: ${"%.1f".format(totalDocs / (totalMs / 1000.0))} docs/s")
    }

    private fun createEmbedder(): OnnxEmbedder {
        val modelBytes = File(MODEL_PATH).readBytes()
        val vocab = BertWordPieceTokenizer.loadVocab(File(VOCAB_PATH).inputStream())
        return OnnxEmbedder(modelBytes, vocab)
    }

    private fun printStats(label: String, times: LongArray) {
        val sorted = times.sortedArray()
        val n = sorted.size
        val p50 = sorted[n / 2]
        val p95 = sorted[(n * 95 / 100)]
        val p99 = sorted[(n * 99 / 100)]
        val mean = sorted.sum() / n
        val min = sorted[0]
        val max = sorted[n - 1]

        println()
        println("=== $label (n=$n) ===")
        println("  p50  : ${TimeUnit.NANOSECONDS.toMillis(p50)} ms")
        println("  p95  : ${TimeUnit.NANOSECONDS.toMillis(p95)} ms")
        println("  p99  : ${TimeUnit.NANOSECONDS.toMillis(p99)} ms")
        println("  mean : ${TimeUnit.NANOSECONDS.toMillis(mean)} ms")
        println("  min  : ${TimeUnit.NANOSECONDS.toMillis(min)} ms")
        println("  max  : ${TimeUnit.NANOSECONDS.toMillis(max)} ms")
        println("  p99/p50 ratio: ${"%.1f".format(p99.toDouble() / p50.toDouble())}x")
    }

    companion object {
        private const val MODEL_PATH = "src/test/resources/models/model_qint8_arm64.onnx"
        private const val VOCAB_PATH = "src/test/resources/models/vocab.txt"
    }
}
