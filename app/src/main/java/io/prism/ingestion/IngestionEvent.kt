package io.prism.ingestion

/**
 * 摄入管线事件 —— [IngestionPipeline.ingest] 产出的事件流类型（ADR-009 5.3）。
 *
 * **事件序列**（典型 happy path）：
 * ```
 * Started → Parsed → Chunked → ChunkEmbedded×N → Completed
 * ```
 *
 * **降级路径**（部分 chunk 嵌入失败）：
 * ```
 * Started → Parsed → Chunked → (ChunkEmbedded | ChunkSkipped)×N → Completed
 * ```
 *
 * **致命错误路径**（解析失败等）：
 * ```
 * Started → Failed
 * ```
 *
 * **设计**（ADR-009 5.3）：
 * - 用 `Flow<IngestionEvent>` 而非 `StateFlow<IngestionProgress>`：摄入是事件流非状态快照，
 *   `ChunkSkipped`/`Completed` 等事件用状态表达别扭。
 * - chunk 边界 emit（非 embed 锁内 emit），避免 OnnxEmbedder 锁竞争。
 * - `Failed` 封装致命异常（如 `DocumentParseException`），调用方 collect 时处理；
 *   可恢复错误（如单 chunk `EmbeddingException`）通过 `ChunkSkipped` 降级，不终止管线。
 *
 * US-016 验收标准 2：摄入进度与错误可观察。
 */
sealed class IngestionEvent {

    /** 管线启动。 */
    object Started : IngestionEvent()

    /** 文档解析完成。 */
    data class Parsed(val textLength: Int) : IngestionEvent()

    /** 切片完成，准备开始逐条嵌入。 */
    data class Chunked(val totalChunks: Int) : IngestionEvent()

    /** 一个 chunk 嵌入成功并已入库。 */
    data class ChunkEmbedded(
        val index: Int,
        val total: Int,
        val title: String
    ) : IngestionEvent()

    /** 一个 chunk 嵌入失败已降级（embedding=null 仍入库，不参与向量检索）。 */
    data class ChunkSkipped(
        val index: Int,
        val total: Int,
        val title: String,
        val reason: String
    ) : IngestionEvent()

    /** 管线正常完成，携带汇总结果。 */
    data class Completed(val result: IngestionResult) : IngestionEvent()

    /**
     * 管线致命错误终止（如解析失败、不可恢复异常）。
     *
     * **安全约定**（M2，TKN-US016-GUARDRAIL-001）：
     * [throwable] 仅供调用方日志/调试，**禁止直接展示 [throwable.message] 或堆栈给终端用户**，
     * 因其可能含内部路径/类名等敏感信息。UI 层（US-018）须映射为通用安全文案
     * （如「文档摄入失败，请检查文件格式或重试」），并按异常类型区分可诊断类别
     * （如 DocumentParseException → 「文档格式不支持」），遵循 BR-error-handling-003。
     */
    data class Failed(val throwable: Throwable) : IngestionEvent()
}
