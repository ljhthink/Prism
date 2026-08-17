package io.prism.ui.model

import kotlinx.serialization.Serializable

/**
 * 消息角色。USER=用户，ASSISTANT=AI，TOOL=工具结果（M4 tool_calling）。
 *
 * 注：无 SYSTEM 角色。RAG 的 system prompt 通过 [io.prism.network.ChatStreamProvider.streamChat]
 * 的 `systemPrompt` 参数注入（ADR-012 5.4 方案 C，依赖倒置），不污染消息历史。
 *
 * **M4 新增**（ADR-014 5.6）：[TOOL] 角色用于 tool_calling 结果回灌。
 * tool 消息携带 [ChatMessage.toolCallId] 关联 LLM 返回的 tool_call，content 为工具执行结果。
 *
 * **UX-001 问题 4（ADR-021）**：[Serializable] 以支持会话历史 [io.prism.data.Session]
 * 的 JSON 持久化与恢复。
 */
@Serializable
enum class Role { USER, ASSISTANT, TOOL }

/**
 * RAG 引用来源（US-019，ADR-012 5.3）。
 *
 * 由 [io.prism.data.RetrievalResult] 转换而来，承载 AI 回复引用的文档片段元信息。
 * 用于 [ChatMessage.sources] 列表渲染 + 引用编号映射。
 *
 * @property index 引用编号（1-based），对应 prompt 中「[来源N]」的 N
 * @property documentTitle 文档标题
 * @property chunkIndex 分块序号（1-based），无法解析时为 null
 * @property similarity 相似度分数 ∈ [-1, 1]
 */
@Serializable
data class Citation(
    val index: Int,
    val documentTitle: String,
    val chunkIndex: Int? = null,
    val similarity: Double = 0.0
)

/**
 * assistant 消息携带的 tool_call 引用（M4，ADR-014 5.6）。
 *
 * @property id 工具调用 id（`call_xxx`）
 * @property type 固定 `"function"`
 * @property functionName 工具名（命名空间隔离后的 `skillName__toolName`）
 * @property arguments arguments JSON string（未解析，原样回放）
 */
@Serializable
data class ToolCallRef(
    val id: String,
    val type: String = "function",
    val functionName: String,
    val arguments: String
)

/**
 * 联网搜索结果（UX-001 问题 8，ADR-021）。
 *
 * 由 [io.prism.network.WebSearchLocalToolExecutor] 返回的结构化搜索结果，
 * UI 层可渲染为可折叠区域 + 可点击的外部链接。
 *
 * @property title 搜索结果标题
 * @property link 可点击的外部链接 URL
 * @property snippet 摘要文本
 */
@Serializable
data class SearchResult(
    val title: String,
    val link: String,
    val snippet: String
)

/**
 * 聊天消息 UI 层数据类。
 *
 * @param id 本地唯一自增 id
 * @param role 消息角色（用户 / AI / 工具结果）
 * @param content 消息文本
 * @param timestamp 创建时间戳（毫秒）
 * @param sources 引用来源列表（RAG，AI 消息可空）
 * @param toolCallId role=TOOL 时关联的 tool_call id（M4）
 * @param toolName role=TOOL 时的工具名（M4，UI 展示）
 * @param toolCalls role=ASSISTANT 携带的 tool_calls 引用列表（M4，构建请求时回放）
 * @param thinkingChain 深度思考推理过程（UX-001 问题 7，ADR-021），非空时 UI 展示可折叠区域
 * @param searchResults 联网搜索结果列表（UX-001 问题 8，ADR-021），非空时 UI 展示可折叠来源卡片
 */
@Serializable
data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val sources: List<Citation> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCallRef> = emptyList(),
    val thinkingChain: String? = null,
    val searchResults: List<SearchResult>? = null,
    /**
     * UXR8 N3（ADR-030）：用户消息附带图片（多模态直传）。
     *
     * 值为 data URL（`data:image/...;base64,...`）或公网图片 URL。非空时请求体
     * 该 user 消息的 content 由字符串改为 OpenAI 兼容多模态数组：
     * `[{"type":"text","text":...},{"type":"image_url","image_url":{"url":...}}]`。
     * 纯文本端点（如 DeepSeek）收到该结构返回 400 → 协议层按「含图 + 400」信号降级
     * 提示「当前模型不支持图片」。默认 null（向后兼容既有测试与持久化数据）。
     */
    val imageUrl: String? = null
)