package io.prism.data

/**
 * 向量检索结果（US-017，ADR-010 5.8）。
 *
 * 由 [KnowledgeBaseRepository.search] 返回，承载 top-k 检索命中的分块及其相似度与来源信息。
 *
 * **字段语义**：
 * - [similarity]：相似度分数 ∈ [-1, 1]，1=完全相同，0=正交，-1=相反。
 *   由 ObjectBox COSINE 距离 d∈[0,2] 转换：`similarity = 1 - distance`（ADR-010 5.2）。
 *   与数学余弦相似度 cos(θ) 严格对齐。UI 层展示时按需归一化为 [0,1]。
 * - [documentTitle] / [chunkIndex]：解析自 [title]，用于 US-019 RAG 引用标注。
 *   title 原文格式 `${documentTitle}#${index+1}`（IngestionPipeline 生成，ADR-009）。
 *   用 `lastIndexOf('#')` 分割规避文件名含 `#` 的歧义（ADR-010 5.9）。
 *
 * **不可变性**：data class 全字段 val，FloatArray 不出现在本类（避免 BR-security-001 引用比较问题）。
 *
 * US-017 验收标准 3：检索结果含相似度分数与来源（文件/片段位置）
 *
 * @property chunkId KnowledgeChunk id
 * @property content 分块原文
 * @property title 分块标题原文（格式 `${documentTitle}#${index+1}`）
 * @property similarity 相似度分数 ∈ [-1, 1]（1 - COSINE 距离）
 * @property documentTitle 解析自 title 的文档标题；title 不含 `#` 时等于 title 原文
 * @property chunkIndex 解析自 title 的分块序号（1-based）；title 不含 `#` 或序号非正整数时为 null
 * @property knowledgeBaseId 所属知识库 id（0L=默认库，>0=自建库）
 */
data class RetrievalResult(
    val chunkId: Long,
    val content: String,
    val title: String,
    val similarity: Double,
    val documentTitle: String,
    val chunkIndex: Int?,
    val knowledgeBaseId: Long
)
