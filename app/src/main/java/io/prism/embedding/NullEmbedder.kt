package io.prism.embedding

/**
 * 空嵌入引擎（ADR-017 4.5）—— MINIMAL / CHAT_ONLY 档位的 embedder 占位实现。
 *
 * **设计动机**：[PrismApplication.embedder] 声明为非空 `val embedder: Embedder by lazy`，
 * 下游 [io.prism.ingestion.IngestionPipeline] / [io.prism.memory.CrossSessionMemoryManager]
 * 直接注入 `Embedder` 类型。MINIMAL / CHAT_ONLY 档不加载 ~23MB ONNX 模型，但需提供一个
 * 符合接口契约的实现，避免下游 NPE。
 *
 * **降级语义**（ADR-017 4.5）：
 * - [embed] 返回空 [FloatArray]（长度为 0）
 * - 下游 RAG 检索因空向量无相似度匹配，自然降级为「无检索结果」
 * - L2 跨会话记忆因 embed 结果空，无法向量化存储
 * - [CrossSessionMemoryManager] 在 MINIMAL/CHAT_ONLY 档直接传 null 给 ConversationViewModel
 *
 * **资源语义**：
 * - [isLoaded] 永远返回 false（无模型加载）
 * - [checkAndUnload] 永远返回 false（无资源可卸载）
 * - [close] 无操作（无资源可释放）
 *
 * **线程安全**：无状态，所有方法线程安全。
 *
 * US-007 验收标准 4-5：3-4GB 禁用 RAG，<3GB 仅聊天 + BYOK
 */
class NullEmbedder : Embedder {

    /**
     * 返回空向量（长度为 0）。
     *
     * **M-02 修复后**（guardrail TKN-M7-GUARDRAIL-001）：[io.prism.ui.chat.ConversationViewModel.buildRagPlan]
     * 在 `ragTopK <= 0` 时短路返回 [io.prism.ui.chat.RagBuildResult.NormalChat]，不会调用本方法。
     * 仅在 FULL/STANDARD 档（embedder 为真实 OnnxEmbedder）时 embed 被调用。
     * 本方法作为防御性占位实现保留，若被意外调用，下游 [io.prism.data.KnowledgeBaseRepository.search]
     * 的 `require(query.size == 384)` 会抛 IllegalArgumentException，被 buildRagPlan catch 块兜底降级。
     *
     * @param text 原始文本（忽略，不进行任何编码）
     * @return 空 [FloatArray]（长度为 0）
     */
    override fun embed(text: String): FloatArray = FloatArray(0)

    /**
     * 批量返回空向量列表。
     *
     * @param texts 文本列表（忽略，返回等长空列表）
     * @return 与输入等长的列表，每个元素为空 [FloatArray]
     */
    override fun embedBatch(texts: List<String>): List<FloatArray> =
        List(texts.size) { FloatArray(0) }

    /** 永远返回 false（无模型加载）。 */
    override fun isLoaded(): Boolean = false

    /** 永远返回 false（无资源可卸载）。 */
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false

    /** 无操作（无资源可释放）。 */
    override fun close() = Unit
}
