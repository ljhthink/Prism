package io.prism.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * ONNX Runtime 嵌入引擎实现 —— all-MiniLM-L6-v2 INT8 端侧推理。
 *
 * **架构**（ADR-007 5.2）：
 * - [OrtEnvironment]：进程级单例（[OrtEnvironment.getEnvironment]），不随实例关闭
 * - [OrtSession]：按需创建/闲置卸载，[modelBytes] 常驻用于重新加载
 * - [BertWordPieceTokenizer]：文本 → 子词 id，do_lower_case=true 对齐 BERT uncased
 *
 * **推理流程**（[embed]）：
 * 1. tokenizer.encode(text) → input_ids / attention_mask / token_type_ids
 * 2. ONNX session.run → last_hidden_state [1, seq, 384]
 * 3. mean pooling（按 attention_mask 加权平均，对齐 sentence-transformers 1_Pooling）
 * 4. L2 归一化（对齐 sentence-transformers normalize=true，COSINE 检索一致性）
 *
 * **线程安全**（BR-concurrency-002）：
 * - 所有公开方法（[embed] / [isLoaded] / [checkAndUnload] / [close]）通过 [lock] 串行化，
 *   保证 session 生命周期一致。`embed()` 全程持锁，`close()` 等待活跃 embed 完成后再关闭。
 * - 端侧单用户场景，串行化可接受（ONNX session.run 本身持锁 ~100ms 量级）。
 *
 * **按需加载 + 闲置卸载**（US-014 AC-3）：
 * - 首次 [embed] 触发 [ensureLoadedLocked]：创建 OrtSession
 * - [embed] 完成更新 [lastUsedAt]
 * - [checkAndUnload] 由上层定时调度调用，闲置超 5 分钟则 session.close()
 * - [close] 永久释放 session，置 [closed]=true，后续 [embed] 抛异常（BR-error-handling-005）
 *
 * **测试兼容**：构造参数为 `modelBytes: ByteArray`（不依赖 Android AssetManager），
 * JVM 单测注入 onnxruntime（桌面原生库）+ 模型 bytes 即可运行真实推理。
 *
 * US-014 验收标准 1：onnxruntime-android 加载 assets 中 all-MiniLM-L6-v2 ONNX INT8 模型
 * US-014 验收标准 2：embed(text) 将文本编码为 384 维向量
 * US-014 验收标准 3：模型按需加载，闲置 5 分钟后卸载释放内存
 *
 * @param modelBytes ONNX 模型字节数组（all-MiniLM-L6-v2 INT8，~23MB）
 * @param vocab BERT 词表（token → id），由 [BertWordPieceTokenizer.loadVocab] 加载
 * @param clock 时间源，用于闲置卸载判断（测试可注入 fake clock）
 * @param maxSeqLen 最大序列长度（all-MiniLM-L6-v2 上限 512）
 * @param embeddingDim 嵌入维度（all-MiniLM-L6-v2 = 384）
 */
class OnnxEmbedder(
    private val modelBytes: ByteArray,
    vocab: Map<String, Int>,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val maxSeqLen: Int = 512,
    private val embeddingDim: Int = 384
) : Embedder {

    /** 时间源接口，便于测试注入虚拟时钟验证闲置卸载。 */
    fun interface Clock {
        fun currentTimeMillis(): Long
    }

    private val tokenizer = BertWordPieceTokenizer(vocab)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = ReentrantLock()

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var lastUsedAt: Long = 0L

    /** 永久关闭标志（BR-error-handling-005 / guardrail G-09）。 */
    @Volatile
    private var closed: Boolean = false

    /** 输入名缓存（首次加载时从 session 读取，避免每次推理重复查询）。 */
    @Volatile
    private var inputNames: List<String> = emptyList()

    override fun embed(text: String): FloatArray = lock.withLock {
        require(!closed) { "Embedder 已 close，不可复用（BR-error-handling-005）" }
        val activeSession = ensureLoadedLocked()
        val names = inputNames
        val tokens = tokenizer.encode(text, maxSeqLen)
        // G-05：三个 tensor 统一在 finally 中 close，避免部分创建失败时原生资源泄漏
        var inputIdsTensor: OnnxTensor? = null
        var attentionMaskTensor: OnnxTensor? = null
        var tokenTypeIdsTensor: OnnxTensor? = null
        try {
            inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(tokens.inputIds))
            attentionMaskTensor = OnnxTensor.createTensor(env, arrayOf(tokens.attentionMask))
            tokenTypeIdsTensor = OnnxTensor.createTensor(env, arrayOf(tokens.tokenTypeIds))
            val inputs = HashMap<String, OnnxTensor>(names.size).apply {
                this[names[INPUT_IDS_IDX]] = inputIdsTensor!!
                this[names[ATTENTION_MASK_IDX]] = attentionMaskTensor!!
                if (names.size > TOKEN_TYPE_IDS_IDX) {
                    this[names[TOKEN_TYPE_IDS_IDX]] = tokenTypeIdsTensor!!
                }
            }
            val result = try {
                activeSession.run(inputs)
            } catch (e: Exception) {
                throw EmbeddingException(EmbeddingException.Stage.INFERENCE, "ONNX run 失败", e)
            }
            try {
                // G-03：unchecked cast 捕获 ClassCastException 转 EmbeddingException
                val output = try {
                    @Suppress("UNCHECKED_CAST")
                    result[0].value as Array<Array<FloatArray>> // [1][seq][384]
                } catch (e: ClassCastException) {
                    throw EmbeddingException(
                        EmbeddingException.Stage.INFERENCE,
                        "模型输出结构不符合 BERT last_hidden_state 预期",
                        e
                    )
                }
                return meanPoolAndNormalize(output[0], tokens.attentionMask)
            } finally {
                result.close()
            }
        } finally {
            inputIdsTensor?.close()
            attentionMaskTensor?.close()
            tokenTypeIdsTensor?.close()
        }
    }

    override fun isLoaded(): Boolean = lock.withLock { session != null && !closed }

    override fun checkAndUnload(maxIdleMs: Long): Boolean = lock.withLock {
        if (closed) return@withLock false
        val s = session ?: return@withLock false
        val idle = clock.currentTimeMillis() - lastUsedAt
        if (idle <= maxIdleMs) return@withLock false
        // G-02（BR-error-handling-005）：先置 null，无论 close 是否成功
        session = null
        try {
            s.close()
        } catch (e: Exception) {
            throw EmbeddingException(EmbeddingException.Stage.UNLOAD, "session.close 失败", e)
        }
        true
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        val s = session
        session = null
        if (s != null) {
            try {
                s.close()
            } catch (e: Exception) {
                throw EmbeddingException(EmbeddingException.Stage.UNLOAD, "close 失败", e)
            }
        }
    }

    /**
     * 确保模型已加载，返回 session。
     *
     * **调用方必须持有 [lock]**（BR-concurrency-002）。
     */
    private fun ensureLoadedLocked(): OrtSession {
        session?.let {
            lastUsedAt = clock.currentTimeMillis()
            return it
        }
        // G-15：SessionOptions 用完即 close（实现 AutoCloseable）
        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            // 内存约束：限制线程数为 1（4GB 低端机友好，ADR-007 5.2）
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(1)
            val s = try {
                env.createSession(modelBytes, options)
            } catch (e: Exception) {
                throw EmbeddingException(
                    EmbeddingException.Stage.MODEL_LOAD,
                    "模型加载失败 (${modelBytes.size} bytes)",
                    e
                )
            }
            session = s
            inputNames = s.inputNames.toList()
            try {
                validateInputNames(inputNames)
            } catch (e: IllegalArgumentException) {
                // N-01（guardrial R2）：校验失败须清理已创建的 session，避免泄漏
                session = null
                inputNames = emptyList()
                try { s.close() } catch (closeEx: Exception) { /* 忽略关闭异常，主异常优先 */ }
                throw e
            }
            lastUsedAt = clock.currentTimeMillis()
            return s
        } finally {
            options.close()
        }
    }

    /** 校验模型输入名符合 BERT 约定（input_ids / attention_mask / token_type_ids）。 */
    private fun validateInputNames(names: List<String>) {
        require(names.isNotEmpty()) { "模型无输入" }
        require(names.size >= 2) {
            "BERT 模型应至少有 input_ids 与 attention_mask 两个输入，实际: $names"
        }
        // 不强制名称完全匹配（不同导出可能命名不同），仅按位置取用
    }

    /**
     * Mean Pooling + L2 归一化。
     *
     * 对齐 sentence-transformers `1_Pooling`（pooling_mode_mean_tokens=true）
     * 与默认 normalize=true 配置：
     * 1. 按 attention_mask 对 last_hidden_state 逐 token 加权求和
     * 2. 除以有效 token 数得到均值
     * 3. L2 归一化（向量除以自身 L2 范数）
     *
     * @param hiddenStates [seq][384] token 级嵌入
     * @param attentionMask [seq] 注意力掩码（1=有效，0=pad）
     * @return [embeddingDim] 维 L2 归一化向量
     */
    private fun meanPoolAndNormalize(
        hiddenStates: Array<FloatArray>,
        attentionMask: LongArray
    ): FloatArray {
        require(hiddenStates.isNotEmpty()) { "hiddenStates 为空" }
        require(hiddenStates[0].size == embeddingDim) {
            "嵌入维度不匹配: 期望 $embeddingDim，实际 ${hiddenStates[0].size}"
        }
        // G-04：长度不一致 fail-fast，不静默截断
        require(hiddenStates.size == attentionMask.size) {
            "序列长度不一致: hidden=${hiddenStates.size} mask=${attentionMask.size}"
        }
        val pooled = FloatArray(embeddingDim)
        var tokenCount = 0f
        for (i in attentionMask.indices) {
            if (attentionMask[i] == 1L) {
                val tokenVec = hiddenStates[i]
                for (j in 0 until embeddingDim) {
                    pooled[j] += tokenVec[j]
                }
                tokenCount += 1f
            }
        }
        if (tokenCount == 0f) {
            throw EmbeddingException(
                EmbeddingException.Stage.POOLING,
                "attention_mask 全 0，无有效 token 可 pooling"
            )
        }
        // mean
        for (j in 0 until embeddingDim) pooled[j] /= tokenCount
        // L2 normalize
        var normSq = 0f
        for (j in 0 until embeddingDim) normSq += pooled[j] * pooled[j]
        val norm = sqrt(normSq)
        if (norm > 0f) {
            for (j in 0 until embeddingDim) pooled[j] /= norm
        }
        return pooled
    }

    private companion object {
        const val INPUT_IDS_IDX = 0
        const val ATTENTION_MASK_IDX = 1
        const val TOKEN_TYPE_IDS_IDX = 2
    }
}
