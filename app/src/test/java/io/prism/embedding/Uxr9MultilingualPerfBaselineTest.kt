package io.prism.embedding

import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UXR9 多语言嵌入链路初版性能基线（ac-verifier，TKN-UXR9-ACCEPTANCE-002）。
 *
 * **背景**：UXR9 US-901 将生产嵌入链路从英文 MiniLM（BertWordPiece，~23MB）切换到
 * 多语言 paraphrase-multilingual-MiniLM-L12-v2 qint8（~113MB）+ Unigram tokenizer。
 * 既有 `OnnxEmbedderPerformanceBenchmark` 仍指向 test/resources 中的旧英文模型，
 * 因此新链路的 tokenizeIds / embed 延迟需要**初版基线**供后续回退检测。
 *
 * **方法**：JVM 桌面 onnxruntime（x86），与 US-014 基线同环境；warmup + iterations，
 * System.nanoTime 计时。绝对延迟低于 Android ARM64 真机，仅作为相对回退基准。
 *
 * 复现：
 * ```bash
 * ./gradlew.bat :app:testDebugUnitTest --tests "io.prism.embedding.Uxr9MultilingualPerfBaselineTest" --rerun-tasks --no-build-cache
 * ```
 */
class Uxr9MultilingualPerfBaselineTest {

    private val tokenizerJson = File("src/main/assets/models/tokenizer.json")
    private val modelBytes = File("src/main/assets/models/model_multilingual_qint8_arm64.onnx")

    @Before
    fun skipUnlessEnabled() {
        // 与 OnnxEmbedderPerformanceBenchmark 一致：加载 113MB 真实模型拖慢全量回归，
        // 默认跳过；复跑需 -PignorePerformanceTests=false（build.gradle.kts 注入系统属性）。
        Assume.assumeTrue(
            "性能基准默认跳过；运行需 -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
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
        println(
            "UXR9_PERF_BASELINE|op=$label|iters=$n|min=${TimeUnit.NANOSECONDS.toMicros(min)}us|" +
                "p50=${TimeUnit.NANOSECONDS.toMicros(p50)}us|p95=${TimeUnit.NANOSECONDS.toMicros(p95)}us|" +
                "p99=${TimeUnit.NANOSECONDS.toMicros(p99)}us|max=${TimeUnit.NANOSECONDS.toMicros(max)}us|" +
                "mean=${TimeUnit.NANOSECONDS.toMicros(mean)}us"
        )
    }

    @Test
    fun baseline_unigram_tokenizeIds_short_chinese() {
        val tk = UnigramTokenizer(tokenizerJson.inputStream())
        val text = "昔涟这个角色做了哪些事情？"
        repeat(50) { tk.tokenizeIds(text) } // 预热
        val iters = 500
        val times = LongArray(iters)
        repeat(iters) { i ->
            val start = System.nanoTime()
            tk.tokenizeIds(text)
            times[i] = System.nanoTime() - start
        }
        printStats("tokenizeIds_short_cjk", times)
    }

    @Test
    fun baseline_unigram_tokenizeIds_long_chinese() {
        val tk = UnigramTokenizer(tokenizerJson.inputStream())
        val text = "这是一段用于测试分词器在长无空格中文段落上的切分行为是否仍然保持子词合并而不是退化为逐字符切分的样例文本".repeat(3)
        repeat(30) { tk.tokenizeIds(text) } // 预热
        val iters = 200
        val times = LongArray(iters)
        repeat(iters) { i ->
            val start = System.nanoTime()
            tk.tokenizeIds(text)
            times[i] = System.nanoTime() - start
        }
        printStats("tokenizeIds_long_cjk", times)
    }

    @Test
    fun baseline_multilingual_embed_short_text() {
        require(modelBytes.isFile) { "多语言模型缺失: $modelBytes" }
        OnnxEmbedder(modelBytes.readBytes(), UnigramTokenizer(tokenizerJson.inputStream())).use { e ->
            e.embed("昔涟这个角色做了哪些事情？")
            e.embed("prism is an assistant")
            val iters = 30
            val times = LongArray(iters)
            repeat(iters) { i ->
                val start = System.nanoTime()
                e.embed("昔涟这个角色做了哪些事情？")
                times[i] = System.nanoTime() - start
            }
            printStats("multilingual_embed_short", times)
        }
    }

    @Test
    fun baseline_multilingual_embed_long_text() {
        require(modelBytes.isFile) { "多语言模型缺失: $modelBytes" }
        OnnxEmbedder(modelBytes.readBytes(), UnigramTokenizer(tokenizerJson.inputStream())).use { e ->
            val longText = ("Prism 是一个基于多语言嵌入模型实现语义检索的 Android 助手，支持中文长文本的端侧向量化。").repeat(5)
            e.embed(longText)
            val iters = 20
            val times = LongArray(iters)
            repeat(iters) { i ->
                val start = System.nanoTime()
                e.embed(longText)
                times[i] = System.nanoTime() - start
            }
            printStats("multilingual_embed_long", times)
        }
    }

    @Test
    fun baseline_multilingual_model_load() {
        require(modelBytes.isFile) { "多语言模型缺失: $modelBytes" }
        val iters = 3
        val times = LongArray(iters)
        repeat(iters) { i ->
            val start = System.nanoTime()
            OnnxEmbedder(modelBytes.readBytes(), UnigramTokenizer(tokenizerJson.inputStream())).use { e ->
                e.embed("模型加载测试")
            }
            times[i] = System.nanoTime() - start
        }
        printStats("multilingual_model_load_first_embed", times)
    }
}
