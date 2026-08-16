package io.prism.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * 会话实体（UX-001 问题 4，ADR-021）—— 历史对话记录持久化。
 *
 * 承载一次完整对话的元信息与消息内容：
 * - [title]：自动生成标题（首条用户消息截断）
 * - [messagesJson]：消息序列的 JSON 编码（消息列表以 JSON 整体存储，简化持久化）
 * - [createdAt] / [updatedAt]：创建 / 最后更新时间（列表按 updatedAt 倒序）
 *
 * **存储策略**：整条对话以 [messagesJson] 存储（简化实现）。
 * 消息数受 L1 滑动窗口约束（默认保留近期 N 轮），JSON 体量可控。
 * 会话历史恢复时反序列化重建内存消息列表。
 *
 * **与记忆系统的区别**（ADR-021）：本实体存储**完整对话**（用户可回溯查看），
 * L2 跨会话记忆 [MemoryRecord] 仅存向量化摘要（供检索注入），两者不冲突。
 *
 * @see <a href="https://docs.objectbox.io/entity-annotations">ObjectBox Entity Annotations</a>
 */
@Entity
data class Session(
    @Id var id: Long = 0,
    var title: String,
    var messagesJson: String = "[]",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)
