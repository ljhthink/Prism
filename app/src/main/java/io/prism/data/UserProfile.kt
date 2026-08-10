package io.prism.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * 用户画像偏好类型（US-031，ADR-015 5.2）。
 *
 * 区分用户偏好的来源，便于 UI 展示与 AI 抽取逻辑分离：
 * - [EXPLICIT]：用户主动设定（如「我喜欢简洁的回复」「我的母语是中文」）
 * - [IMPLICIT]：LLM 从对话中隐式抽取（如检测到用户常用 Python、偏好技术深度内容）
 *
 * **存储方式**：[UserProfile.category] 字段存储为 String（`ProfileCategory.name`），
 * 而非 ObjectBox enum 转换器。理由：与项目既有 [ExecutionStatus] String 常量模式一致，
 * 避免 enum ObjectBox 转换器复杂度（考古报告 §2.6）。调用方通过 [fromName] / [name]
 * 进行 String ↔ enum 转换。
 */
enum class ProfileCategory {
    /** 显式偏好：用户主动设定 */
    EXPLICIT,

    /** 隐式偏好：LLM 从对话中抽取 */
    IMPLICIT;

    companion object {
        /**
         * 从 String 安全转换为 [ProfileCategory]。
         *
         * @param value 存储值（必须为 "EXPLICIT" 或 "IMPLICIT"）
         * @return 对应枚举值
         * @throws IllegalArgumentException 当 value 不是合法枚举名时
         */
        fun fromName(value: String): ProfileCategory =
            runCatching { ProfileCategory.valueOf(value) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "非法 ProfileCategory 值: $value（合法值: ${values().map { it.name }}）"
                    )
                }
    }
}

/**
 * 用户画像实体 —— M5 三层记忆系统 L3 层持久化单元（US-031，ADR-015 5.2）。
 *
 * 每条记录对应一个用户偏好键值对（如 `language=中文`、`tone=简洁`、`tech_stack=Python`），
 * 供 AI 跨会话理解用户，实现个性化对话。
 *
 * **三层记忆架构定位**（ADR-015）：
 * - L1 会话内：滑动窗口 + 摘要压缩
 * - L2 跨会话：向量化存储 + top-k 检索（[MemoryRecord]）
 * - L3 用户画像：**本实体**，结构化偏好存储（显式 + 隐式）
 *
 * **设计决策**（ADR-015 5.2 + M5 考古报告 §2.6）：
 * - 复用 M3 [KnowledgeBase] @Entity 模式：`@Id` + 简单字段。
 * - **category 存储为 String 而非 enum**：与 [ExecutionStatus] String 常量模式一致，
 *   调用方通过 [ProfileCategory.name] / [ProfileCategory.fromName] 转换。
 *   避免引入 ObjectBox enum 转换器复杂度。
 * - **[key] 字段加 @Index**：加速按 key 查询（upsert 校验、get(key)）。
 *   ObjectBox @Index 对 String 字段建立 B-Tree 索引，查询性能优于全表扫描。
 * - **单用户单 key 唯一约束**：ObjectBox 不支持原生唯一约束，由
 *   [UserProfileRepository.save] 实现 upsert 语义（先 query key，存在则更新 id，
 *   不存在则新建），与项目既有 Repository 模式一致。
 *
 * **字段语义**：
 * - [id] 主键（ObjectBox 自增）
 * - [key] 偏好键（如 "language"、"tone"、"tech_stack"），唯一约束由 Repository 保证
 * - [value] 偏好值（如 "中文"、"简洁"、"Python"）
 * - [category] 偏好类型（[ProfileCategory.EXPLICIT] 或 [ProfileCategory.IMPLICIT] 的 name）
 * - [updatedAt] 最后更新时间戳（毫秒），用于按时间排序展示
 *
 * US-031 验收标准 1：定义 UserProfile @Entity + ProfileCategory 枚举
 * US-031 验收标准 3：单用户单 key 唯一约束（相同 key upsert 而非 insert）
 *
 * @see UserProfileRepository
 * @see ProfileCategory
 * @see KnowledgeBase
 */
@Entity
data class UserProfile(
    @Id var id: Long = 0,
    @Index var key: String,
    var value: String,
    var category: String = ProfileCategory.EXPLICIT.name,
    var updatedAt: Long = System.currentTimeMillis()
)
