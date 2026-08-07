package io.prism.ingestion

/**
 * 摄入管线汇总结果 —— [IngestionEvent.Completed] 的载荷（ADR-009 5.3）。
 *
 * US-016 验收标准 1：解析→切片→嵌入→写入指定库
 * US-016 验收标准 3：嵌入为 null 的片段不建索引并提示
 *
 * @property totalChunks 切片总数
 * @property embeddedChunks 嵌入成功并建索引的 chunk 数
 * @property skippedChunks 嵌入失败降级的 chunk 数（embedding=null，不参与向量检索）
 * @property skippedDetails 降级 chunk 的详情列表，供 UI 提示用户
 * @property knowledgeBaseId 入库目标知识库 id
 * @property documentTitle 文档标题（用于 chunk title 前缀）
 * @property durationMs 管线总耗时（毫秒）
 */
data class IngestionResult(
    val totalChunks: Int,
    val embeddedChunks: Int,
    val skippedChunks: Int,
    val skippedDetails: List<SkippedChunk>,
    val knowledgeBaseId: Long,
    val documentTitle: String,
    val durationMs: Long
) {
    /** 简单一致性校验：embedded + skipped 应等于 total。 */
    init {
        require(embeddedChunks + skippedChunks == totalChunks) {
            "embedded($embeddedChunks) + skipped($skippedChunks) != total($totalChunks)"
        }
    }
}

/**
 * 降级 chunk 详情 —— 嵌入失败的 chunk 信息，供 UI 提示用户（AC-3）。
 *
 * @property index chunk 在切片列表中的下标（0-based）
 * @property title chunk 标题
 * @property reason 降级原因（如「嵌入失败: INFERENCE」）
 */
data class SkippedChunk(
    val index: Int,
    val title: String,
    val reason: String
)
