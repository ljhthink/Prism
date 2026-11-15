package io.prism.embedding

/**
 * **延迟加载的嵌入器包装**（2026-11-15 新增；ADR-074 / guardrail R-01 / R-03 / R2-02）。
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
 * - **区分两种"不可用"**（guardrail R2-02）：
 *   1. **档位不启用**（`factory` 返回 null）→ 终态，永久降级，永不重试（省掉无谓 IO）；
 *   2. **加载失败**（`factory` 抛异常）→ 可恢复：按 [retryBackoffMs] 指数退避后重试，
 *      而不是像 `by lazy` 那样把失败结果永久缓存、让 RAG 在进程内静默死掉。
 */
class LazyLoadedEmbedder(
    /** 真正的加载动作（**必须是 IO**：会读模型/tokenizer）。返回 null = 当前档位不启用。 */
    private val factory: () -> Embedder?,
    /** 加载失败时的可读原因（不含敏感信息）。 */
    private val label: String = "embedder",
    /** 失败重试的基础退避（毫秒）；0=不重试（测试用）。 */
    private val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
    /** 时间源（测试可注入）。 */
    private val clock: () -> Long = { System.currentTimeMillis() }
) : Embedder {

    private val loadLock = Any()

    @Volatile
    private var delegate: Embedder? = null

    /** 档位不启用（终态）：不再尝试加载。 */
    @Volatile
    private var disabled: Boolean = false

    /** 下一次允许重试的时刻（0=立即可试）。 */
    @Volatile
    private var nextRetryAtMs: Long = 0L

    /** 上次加载失败的次数（诊断用）。 */
    @Volatile
    var failureCount: Int = 0
        private set

    /** 最近一次失败的原因（诊断用）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 取实际实现（必要时同步加载一次；档位不启用则终态，失败则退避重试）。 */
    private fun ensureDelegate(): Embedder? {
        delegate?.let { return it }
        if (disabled) return null
        if (nextRetryAtMs > 0L && clock() < nextRetryAtMs) return null
        return synchronized(loadLock) {
            delegate?.let { return it }
            if (disabled) return null
            if (nextRetryAtMs > 0L && clock() < nextRetryAtMs) return null
            try {
                val created = factory()
                if (created == null) {
                    // 档位不启用：**终态**，不是失败（guardrail R2-02）。
                    disabled = true
                    lastFailure = "$label 未启用（当前性能档位）"
                    android.util.Log.i("LazyLoadedEmbedder", lastFailure.orEmpty())
                }
                delegate = created
                created
            } catch (e: Throwable) {
                // 可恢复失败：指数退避后重试（不缓存失败结果本身）。
                failureCount += 1
                val backoff = if (retryBackoffMs <= 0L) 0L else {
                    (retryBackoffMs shl (failureCount - 1).coerceIn(0, MAX_BACKOFF_SHIFT))
                        .coerceAtMost(MAX_RETRY_BACKOFF_MS)
                }
                nextRetryAtMs = if (backoff <= 0L) 0L else clock() + backoff
                lastFailure = "加载失败（${e::class.simpleName}），" +
                    if (backoff <= 0L) "下次调用重试" else "${backoff / 1000}s 后重试"
                android.util.Log.w(
                    "LazyLoadedEmbedder",
                    "$label 加载失败（第 $failureCount 次）：${e::class.simpleName}",
                    e
                )
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

    override fun checkAndUnload(maxIdleMs: Long): Boolean =
        delegate?.checkAndUnload(maxIdleMs) ?: false

    override fun close() {
        synchronized(loadLock) {
            runCatching { delegate?.close() }
            delegate = null
        }
    }

    /** 诊断用：是否已完成过一次成功加载。 */
    fun hasDelegate(): Boolean = delegate != null

    /** 诊断用：是否因"档位不启用"而永久降级。 */
    fun isDisabled(): Boolean = disabled

    companion object {
        /** 失败重试基础退避（1 分钟；上限 16 分钟）。 */
        const val DEFAULT_RETRY_BACKOFF_MS: Long = 60_000L

        /** 指数退避的最大左移位数（60s << 4 = 16 分钟）。 */
        private const val MAX_BACKOFF_SHIFT = 4

        /** 退避上限。 */
        private const val MAX_RETRY_BACKOFF_MS: Long = 16 * 60_000L
    }
}
