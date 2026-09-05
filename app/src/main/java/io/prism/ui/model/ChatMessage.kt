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
    val imageUrl: String? = null,
    /**
     * v1 批次13（F1，guardrail TKN-V1B13-GUARDRAIL-001）：瞬态截图图片标记。
     *
     * 为 true 时该消息的 [imageUrl]（手机操控截图 base64）**仅用于当前会话的 LLM 请求**
     * （image_url 注入让视觉模型看真图），**持久化时由 [io.prism.util.ChatMessageSerializer]
     * 剥离 imageUrl**——防止截图 base64 进会话 JSON 膨胀（真机 ANR 根因）+ 切纯文本模型后
     * 历史请求每轮 400。默认 false（用户主动发图等需持久化的图片消息不受影响）。
     */
    val transientImage: Boolean = false,
    /**
     * UXR9 Bug3 修复（TKN-UXR9-ARCHAEOLOGY-001）：系统提示标记（如"图片编码失败"）。
     *
     * 为 true 的消息仅供 UI 展示为提示气泡，**不进入 LLM 请求历史**（见
     * ConversationViewModel 历史过滤）也**不触发 launchAnswer**（LLM 不被调用）。
     * 默认 false（向后兼容既有测试与持久化数据；kotlinx.serialization 对缺失字段
     * 应用默认值）。
     */
    val isSystemNotice: Boolean = false,
    /**
     * v1 批次17（US-1707，ADR-043 D4）：历史版本列表（SillyTavern swipes 同构，仅 AI 消息使用）。
     *
     * 重新生成时：首次把当前内容迁移为 variants[0]，此后每次重试完成追加新版本；
     * [activeVariantIndex] 指向当前展示/进请求的版本（消息 content 即该版本内容，
     * **variants 本身不进 LLM 请求**——请求构建只用 content 字段，协议层零感知）。
     * 序列化防膨胀：空列表为默认值，`ChatMessageSerializer` 以 `encodeDefaults=false`
     * 省略该字段（旧 JSON 向后兼容）。
     */
    val variants: List<MessageVariant> = emptyList(),
    /** v1 批次17（US-1707）：当前激活的变体索引（与 [variants] 配套，默认 0）。 */
    val activeVariantIndex: Int = 0
)

/**
 * AI 消息历史版本（v1 批次17 US-1707，ADR-043 D4）。
 *
 * 记录一次生成的完整可展示产物；切换变体时由调用方把字段拷回消息本体
 *（消息 content/thinkingChain/searchResults/sources 恒等于 active 变体内容）。
 *
 * @property content 该版本回复正文
 * @property thinkingChain 该版本思维链（可空）
 * @property searchResults 该版本联网搜索结果（可空）
 * @property sources 该版本引用来源
 * @property createdAt 版本生成时间戳（毫秒）
 */
@Serializable
data class MessageVariant(
    val content: String,
    val thinkingChain: String? = null,
    val searchResults: List<SearchResult>? = null,
    val sources: List<Citation> = emptyList(),
    val createdAt: Long
)