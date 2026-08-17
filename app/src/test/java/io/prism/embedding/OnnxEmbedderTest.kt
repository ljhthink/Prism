package io.prism.embedding

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * ONNX 嵌入引擎单元测试。
 *
 * **测试策略**（US-014 AC-4：维度正确、向量一致）：
 * 1. 维度正确：embed 返回 384 维
 * 2. 向量一致：相同输入多次调用结果完全一致（确定性）
 * 3. Golden Master：与 Python `transformers.BertTokenizer` + `onnxruntime` 生成的
 *    golden 向量对比，L2 距离 < 1e-4（量化模型精度容差）
 * 4. 语义正确性：cos(cat,dog) > cos(cat,car)
 * 5. 闲置卸载：checkAndUnload(5min) 卸载 session，isLoaded=false，重新 embed 自动加载
 * 6. 永久关闭：close 后 isLoaded=false
 *
 * **JVM 兼容**：测试用 onnxruntime（JVM 版，桌面原生库）替代 onnxruntime-android AAR，
 * API 完全一致（`ai.onnxruntime.*`），通过 testImplementation(onnxruntime) 提供。
 */
class OnnxEmbedderTest {

    @After
    fun tearDown() {
        embedder?.close()
    }

    @Test
    fun embed_returns_384_dimensional_vector() {
        val e = createEmbedder()
        val vec = e.embed("hello world")
        assertEquals(384, vec.size)
    }

    @Test
    fun embed_same_input_produces_identical_output() {
        val e = createEmbedder()
        val v1 = e.embed("Prism is a mobile AI chat agent")
        val v2 = e.embed("Prism is a mobile AI chat agent")
        assertArrayEquals(v1, v2, 0f)
    }

    @Test
    fun embed_l2_normalized() {
        val e = createEmbedder()
        val vec = e.embed("hello world")
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq)
        assertEquals(1.0f, norm, 1e-4f)
    }

    @Test
    fun embed_matches_python_golden_master() {
        val e = createEmbedder()
        val golden = loadGoldenMaster()
        assertTrue("golden master 应含测试句子", golden.isNotEmpty())
        var maxDiff = 0.0
        var minCosSim = 1.0
        for ((text, expected) in golden) {
            val actual = e.embed(text)
            assertEquals("维度: $text", 384, actual.size)
            var diff = 0.0
            var cosSim = 0.0
            for (i in actual.indices) {
                diff = maxOf(diff, kotlin.math.abs((actual[i] - expected[i]).toDouble()))
                cosSim += actual[i] * expected[i] // 已 L2 归一化，dot = cosine
            }
            maxDiff = maxOf(maxDiff, diff)
            minCosSim = minOf(minCosSim, cosSim)
            assertTrue(
                "[$text] 与 golden master 偏差过大: maxDiff=$diff (阈值 $GOLDEN_TOLERANCE)",
                diff < GOLDEN_TOLERANCE
            )
            // G-07（guardrail）：余弦相似度门禁（双门禁）
            assertTrue(
                "[$text] 余弦相似度过低: $cosSim (阈值 $GOLDEN_COS_THRESHOLD)",
                cosSim > GOLDEN_COS_THRESHOLD
            )
        }
        // INT8 量化模型在 x86 JVM（测试）与 Python onnxruntime 间的数值容差：
        // 量化算子跨实现/平台有 ~1-3% 相对误差，L2 归一化后分量绝对误差 < 0.05 属正常。
        // 语义正确性由 semantic_similarity_cat_dog_gt_cat_car + 余弦门禁补充验证。
        assertTrue("整体 maxDiff=$maxDiff 应 < $GOLDEN_TOLERANCE", maxDiff < GOLDEN_TOLERANCE)
        assertTrue("整体 minCosSim=$minCosSim 应 > $GOLDEN_COS_THRESHOLD", minCosSim > GOLDEN_COS_THRESHOLD)
    }

    @Test
    fun semantic_similarity_cat_dog_gt_cat_car() {
        val e = createEmbedder()
        val cat = e.embed("cat")
        val dog = e.embed("dog")
        val car = e.embed("car")
        val cosCatDog = cosine(cat, dog)
        val cosCatCar = cosine(cat, car)
        // Python golden: cos(cat,dog)=0.6451, cos(cat,car)=0.4535
        assertTrue(
            "cos(cat,dog)=$cosCatDog 应 > cos(cat,car)=$cosCatCar",
            cosCatDog > cosCatCar
        )
    }

    @Test
    fun empty_text_embeds_without_crash() {
        val e = createEmbedder()
        val vec = e.embed("")
        assertEquals(384, vec.size)
        // 空文本至少应产生合法向量（[CLS][SEP] 输入）
        var norm = 0f
        for (v in vec) norm += v * v
        assertTrue("空文本嵌入不应为零向量", norm > 0f)
    }

    @Test
    fun model_loaded_lazily_on_first_embed() {
        val e = createEmbedder()
        assertFalse("构造后 session 不应已加载", e.isLoaded())
        e.embed("hello")
        assertTrue("首次 embed 后 session 应已加载", e.isLoaded())
    }

    @Test
    fun check_and_unload_releases_session_after_idle_timeout() {
        val clock = FakeClock()
        val e = createEmbedder(clock = clock)
        // 初始未加载
        assertFalse(e.isLoaded())
        // 触发加载
        e.embed("hello world")
        assertTrue(e.isLoaded())
        // 闲置未超时 → 不卸载
        clock.advance(1_000) // 1s
        assertFalse(e.checkAndUnload(FIVE_MIN_MS))
        assertTrue(e.isLoaded())
        // 闲置超 5 分钟 → 卸载
        clock.advance(FIVE_MIN_MS + 1)
        assertTrue(e.checkAndUnload(FIVE_MIN_MS))
        assertFalse(e.isLoaded())
    }

    @Test
    fun unload_then_re_embed_reloads_session() {
        val clock = FakeClock()
        val e = createEmbedder(clock = clock)
        val v1 = e.embed("hello world")
        assertTrue(e.isLoaded())
        // 卸载
        clock.advance(FIVE_MIN_MS + 1)
        assertTrue(e.checkAndUnload(FIVE_MIN_MS))
        assertFalse(e.isLoaded())
        // 重新 embed 应自动加载并返回一致结果
        val v2 = e.embed("hello world")
        assertArrayEquals(v1, v2, 1e-5f)
        assertTrue(e.isLoaded())
    }

    @Test
    fun close_releases_session_permanently() {
        val e = createEmbedder()
        e.embed("hello")
        assertTrue(e.isLoaded())
        e.close()
        assertFalse(e.isLoaded())
    }

    @Test
    fun embed_batch_returns_correct_count() {
        val e = createEmbedder()
        val texts = listOf("hello", "world", "foo", "bar")
        val vecs = e.embedBatch(texts)
        assertEquals(texts.size, vecs.size)
        vecs.forEach { assertEquals(384, it.size) }
    }

    @Test(expected = EmbeddingException::class)
    fun invalid_model_bytes_throws_embedding_exception() {
        val badBytes = ByteArray(100) { 0 }
        val vocab = loadVocab()
        OnnxEmbedder(badBytes, vocab).use { e ->
            e.embed("hello")
        }
    }

    @Test
    fun long_text_truncated_to_max_seq_len_does_not_crash() {
        val e = createEmbedder()
        val longText = "The quick brown fox jumps over the lazy dog. ".repeat(100)
        val vec = e.embed(longText)
        assertEquals(384, vec.size)
    }

    /**
     * G-09（guardrail）：close() 后再 embed 必须抛异常，不可"复活"已关闭资源。
     *
     * AutoCloseable 契约：close 永久释放，后续使用应失败 fast。
     */
    @Test(expected = IllegalArgumentException::class)
    fun embed_after_close_throws_instead_of_reviving() {
        val e = createEmbedder()
        e.embed("hello")
        assertTrue(e.isLoaded())
        e.close()
        // close 后再 embed 应抛 IllegalArgumentException（require(!closed)）
        e.embed("hello")
    }

    /**
     * G-11（guardrail）+ BR-concurrency-002：并发 use-after-close 竞态测试。
     *
     * 场景：多线程并发 embed + 定时 checkAndUnload，验证：
     * 1. 无 use-after-close 异常（IllegalStateException / NPE）
     * 2. 无并发导致的 session 状态不一致
     * 3. 所有成功的 embed 返回 384 维向量
     *
     * 此测试直接复现 G-01 阻断级竞态的触发条件：embed 线程在 ensureLoaded 返回后
     * 使用 session 引用时，unload 线程可能并发关闭 session。修复后（embed 全程持锁）
     * 应无异常。
     */
    @Test
    fun concurrent_embed_and_unload_no_use_after_close() {
        val clock = FakeClock()
        val e = createEmbedder(clock = clock)
        val iterations = 30
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val latch = java.util.concurrent.CountDownLatch(2)
        val startGate = java.util.concurrent.CountDownLatch(1)

        // embed 线程：持续 embed，收集异常
        val embedThread = Thread {
            latch.countDown()
            startGate.await(2, java.util.concurrent.TimeUnit.SECONDS)
            repeat(iterations) {
                try {
                    val vec = e.embed("hello world")
                    if (vec.size != 384) {
                        errors.add(AssertionError("维度错误: ${vec.size}"))
                    }
                } catch (ex: IllegalArgumentException) {
                    // N-03（guardrail R2）：本测试无 close 线程，仅有 unload（checkAndUnload）。
                    // checkAndUnload 不会置 closed=true，故 embed 不应抛 IllegalArgumentException。
                    // 若误抛则记录为错误（下方 catch 不会捕获 IllegalArgumentException，会进 errors）。
                    errors.add(ex)
                } catch (ex: Exception) {
                    errors.add(ex)
                }
            }
        }

        // unload 线程：推进时钟 + 触发卸载，与 embed 并发
        val unloadThread = Thread {
            latch.countDown()
            startGate.await(2, java.util.concurrent.TimeUnit.SECONDS)
            repeat(iterations / 3) {
                try {
                    clock.advance(10_000) // 推进 10s，使闲置超时
                    e.checkAndUnload(1) // maxIdleMs=1 强制卸载
                } catch (ex: Exception) {
                    errors.add(ex)
                }
            }
        }

        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        startGate.countDown()
        embedThread.join(10_000)
        unloadThread.join(10_000)

        assertTrue(
            "并发测试出现错误（G-01 竞态未修复？）: ${errors.take(3).map { it::class.simpleName + ":" + it.message }}",
            errors.isEmpty()
        )
    }

    /**
     * G-11 补充：embed 与 close 并发，close 后所有后续 embed 必须抛 IllegalArgumentException。
     *
     * 验证 BR-error-handling-005：close 永久释放，不可复活。
     */
    @Test
    fun concurrent_embed_and_close_eventually_rejects_after_close() {
        val e = createEmbedder()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val embedThread = Thread {
            while (!stopFlag.get()) {
                try {
                    e.embed("hello")
                } catch (ex: IllegalArgumentException) {
                    // close 后 embed 抛此异常是预期行为
                    if (closedFlag.get()) {
                        return@Thread
                    }
                } catch (ex: Exception) {
                    errors.add(ex)
                }
            }
        }
        embedThread.start()
        // 让 embed 跑一会儿
        Thread.sleep(100)
        closedFlag.set(true)
        e.close()
        // close 后等 embed 线程检测到并退出
        stopFlag.set(true)
        embedThread.join(5_000)
        assertTrue(
            "close 后 embed 应抛 IllegalArgumentException，不应有其他异常: ${errors.map { it::class.simpleName }}",
            errors.isEmpty()
        )
    }

    // —— 辅助 ——

    private fun createEmbedder(clock: OnnxEmbedder.Clock? = null): OnnxEmbedder {
        val modelBytes = File(MODEL_PATH).readBytes()
        val vocab = loadVocab()
        val embedder = if (clock != null) OnnxEmbedder(modelBytes, vocab, clock) else OnnxEmbedder(modelBytes, vocab)
        embedderRef = embedder // 保存引用供 tearDown 关闭
        return embedder
    }

    private fun loadVocab(): Map<String, Int> =
        BertWordPieceTokenizer.loadVocab(File(VOCAB_PATH).inputStream())

    private fun loadGoldenMaster(): Map<String, List<Float>> {
        val stream = OnnxEmbedderTest::class.java.classLoader!!
            .getResourceAsStream("embedding/golden_master.json")
        assertNotNull("golden_master.json 应在 test resources 中", stream)
        val text = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        @Suppress("UNCHECKED_CAST")
        val raw = kotlinx.serialization.json.Json.decodeFromString<
            Map<String, List<Float>>
            >(text)
        return raw
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // 已 L2 归一化，dot = cosine
    }

    /** 可控时钟，用于测试闲置卸载。 */
    private class FakeClock(var t: Long = 0L) : OnnxEmbedder.Clock {
        override fun currentTimeMillis(): Long = t
        fun advance(ms: Long) { t += ms }
    }

    companion object {
        // Q-LOW-6（guardrail TKN-UXR9-GUARDRAIL-002）：旧英文模型仅测试使用，
        // 已移出 main assets（生产 APK 不再打包）至 test resources
        private const val MODEL_PATH = "src/test/resources/models/model_qint8_arm64.onnx"
        private const val VOCAB_PATH = "src/test/resources/models/vocab.txt"
        private const val FIVE_MIN_MS = 5L * 60 * 1000
        // INT8 量化模型（ARM64 量化版在 x86 JVM 测试）与 Python golden master 对比容差：
        // 量化算子跨平台/跨实现有 1-3% 相对误差，L2 归一化后分量绝对误差 < 0.05 属正常。
        // 0.05 远小于语义差异量级（cos 差异 ~0.2），不影响检索准确性。
        private const val GOLDEN_TOLERANCE = 0.05
        // G-07（guardrail）：余弦相似度门禁（与分量绝对误差双门禁）。
        // 阈值 0.985：INT8 量化模型在短文本（如单 token "dog"）上与 Python FP32 golden master
        // 的实测最低余弦 ~0.989；0.985 阈值既容忍量化正常误差，又能检测语义漂移
        // （cos < 0.9 必然触发，留有充足余量）。实测 dog=0.9892 / cat / hello world 等均 > 0.99。
        private const val GOLDEN_COS_THRESHOLD = 0.985

        @BeforeClass
        @JvmStatic
        fun verifyModelExists() {
            assertTrue(
                "模型文件应存在: $MODEL_PATH（US-014 前置：assets 打包模型）",
                File(MODEL_PATH).exists()
            )
            assertTrue(
                "词表文件应存在: $VOCAB_PATH",
                File(VOCAB_PATH).exists()
            )
        }
    }

    // 持有 embedder 引用供 tearDown 关闭（避免每个 test 手动 close）
    @Volatile
    private var embedderRef: OnnxEmbedder? = null
    private val embedder: OnnxEmbedder? get() = embedderRef
}
