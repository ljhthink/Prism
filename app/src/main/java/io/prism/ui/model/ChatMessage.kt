package io.prism.ui.model

/** 消息角色。 */
enum class Role { USER, ASSISTANT }

/**
 * 聊天消息 UI 层数据类（ADR-002 4.6）。
 *
 * 本 US 消息为本地 Mock（发送后占位 AI 回复），不建 ObjectBox 实体。
 * 会话持久化属后续 US（记忆 / 会话历史）。
 *
 * @param id 本地唯一自增 id
 * @param role 消息角色（用户 / AI）
 * @param content 消息文本
 * @param timestamp 创建时间戳（毫秒）
 */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val timestamp: Long,
    /** 引用来源（US-003 防幻觉 UI 呈现，如「Q3规划.pdf · p.12」），AI 消息可空。 */
    val source: String? = null
)