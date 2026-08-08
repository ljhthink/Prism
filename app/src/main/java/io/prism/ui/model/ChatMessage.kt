package io.prism.ui.model

/**
 * 消息角色。USER=用户，ASSISTANT=AI。
 *
 * 注：无 SYSTEM 角色。RAG 的 system prompt 通过 [io.prism.network.ChatStreamProvider.streamChat]
 * 的 `systemPrompt` 参数注入（ADR-012 5.4 方案 C，依赖倒置），不污染消息历史。
 */
enum class Role { USER, ASSISTANT }

/**
 * RAG 引用来源（US-019，ADR-012 5.3）。
 *
 * 由 [io.prism.data.RetrievalResult] 转换而来，承载 AI 回复引用的文档片段元信息。
 * 用于 [ChatMessage.sources] 列表渲染 + 引用编号映射。
 *
 * **字段语义**：
 * - [index]：引用编号（1-based），对应 system prompt 中「[来源N]」的 N
 * - [documentTitle]：文档标题（解析自 KnowledgeChunk.title）
 * - [chunkIndex]：分块序号（1-based），title 不含 `#` 或序号非正整数时为 null
 * - [similarity]：相似度分数 ∈ [-1, 1]，UI 层可展示百分比
 *
 * @property index 引用编号（1-based，对应 prompt 中 [来源N]）
 * @property documentTitle 文档标题
 * @property chunkIndex 分块序号（1-based），无法解析时为 null
 * @property similarity 相似度分数 ∈ [-1, 1]
 */
data class Citation(
    val index: Int,
    val documentTitle: String,
    val chunkIndex: Int?,
    val similarity: Double
)

/**
 * 聊天消息 UI 层数据类（ADR-002 4.6 / ADR-012 5.3）。
 *
 * 本 US 消息为本地内存态（不建 ObjectBox 实体），会话持久化属后续 US（记忆 / 会话历史）。
 *
 * **US-019 变更**：`source: String?` 单字段 → `sources: List<Citation>` 多引用列表。
 * 破坏性变更，但 ChatMessage 仅内存使用无持久化，影响可控（ADR-012 后果）。
 *
 * @param id 本地唯一自增 id
 * @param role 消息角色（用户 / AI）
 * @param content 消息文本
 * @param timestamp 创建时间戳（毫秒）
 * @param sources 引用来源列表（US-019 RAG 防幻觉 UI 呈现），AI 消息可空（普通对话无引用）
 */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val sources: List<Citation> = emptyList()
)
