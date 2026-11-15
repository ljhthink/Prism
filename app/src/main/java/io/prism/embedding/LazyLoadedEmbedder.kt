package io.prism.embedding

/**
 * **延迟加载的嵌入器包装**（2026-11-15 新增；ADR-074 / guardrail R-01 / R-03）。
 *
 * ## 为什么需要它
 *
 * 真机内存优化的第一版是「取消进程启动时的预热，改为首次使用时加载」。但审查发现：
 * `embedder` 是 `by lazy`，而它唯一的**生产调用点**是 `ConversationViewModel.Factory`
 * 的 `initializer{}`（**主线程**）。于是首次开聊天页会同步执行：
 * 113MB 模型解压写盘 + rename + 9MB tokenizer 解析 → 高概率 ANR。
 * 即"把 IO 从预热搬到了主线程首用"，只是换了个地方爆。
 *
 * ## 设计
 *
 * 本类本身**不做任何 IO**（构造零成本），真正加载推迟到第一次 [embed]/[embedBatch]：
 * - `embed` 已经是 suspend 调用链上的 IO 操作（调用方在 `Dispatchers.IO`）；
 * - 用 [loadLock] 保证并发首次调用只加载一次；
 * - **加载失败不缓存**（guardrail R-03）：上次失败后下次调用会重试，
 *   而不是像 `by lazy` 那样把失败结果永久缓存、让 RAG 在进程内静默死掉。
 *
 * ## 与既有实现的关系
 *
 * [factory] 返回 null 表示"当前档位不需要 embedder"（如 MINIMAL 档）→ 永久降级为
 * [NullEmbedder] 的等价行为（`isLoaded=false`、`embed` 抛 [EmbeddingException]）。
 */
class LazyLoadedEmbedder(
    /** 真正的加载动作（**必须是 IO**：会读模型/tokenizer）。 */
    private val factory: () -> Embedder?,
    /** 加载失败时的可读原因（不含敏感信息）。 */
    private val label: String = "embedder"
) : Embedder {

    private val loadLock = Any()

    @Volatile
    private var delegate: Embedder? = null

    /** 上次加载失败的次数（诊断用；不缓存失败本身）。 */
    @Volatile
    var failureCount: Int = 0
        private set

    /** 最近一次失败的原因（诊断用）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 取实际实现（必要时同步加载一次；失败不缓存）。 */
    private fun ensureDelegate(): Embedder? {
        delegate?.let { return it }
        return synchronized(loadLock) {
            delegate?.let { return it }
            try {
                val created = factory()
                if (created == null) {
                    lastFailure = "$label 不可用（当前性能档位不启用或模型缺失）"
                }
                delegate = created
                created
            } catch (e: Throwable) {
                // guardrail R-03：**不缓存失败**——下次调用会重试，避免 RAG 进程内静默死掉。
                failureCount += 1
                lastFailure = "加载失败（${e::class.simpleName}）"
                android.util.Log.w("LazyLoadedEmbedder", "$label 加载失败，下次调用将重试", e)
                null
            }
        }
    }

    override fun embed(text: String): FloatArray {
        val d = ensureDelegate()
            ?: throw EmbeddingException(
                EmbeddingException.Stage.MODEL_LOAD,
                lastFailure ?: "$label 未加载"
            )
        return d.embed(text)
    }

    override fun embedBatch(texts: List<String>): List<FloatArray> {
        val d = ensureDelegate()
            ?: throw EmbeddingException(
                EmbeddingException.Stage.MODEL_LOAD,
                lastFailure ?: "$label 未加载"
            )
        return d.embedBatch(texts)
    }

    override fun isLoaded(): Boolean = delegate?.isLoaded() ?: false

    override fun checkAndUnload(maxIdleMs: Long): Boolean = delegate?.checkAndUnload(maxIdleMs) ?: false

    override fun close() {
        synchronized(loadLock) {
            runCatching { delegate?.close() }
            delegate = null
        }
    }

    /** 诊断用：是否已完成过一次成功加载。 */
    fun hasDelegate(): Boolean = delegate != null
}
