package io.prism.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType

/**
 * 跨会话记忆实体 —— M5 三层记忆系统 L2 层持久化单元（US-030，ADR-015 5.1）。
 *
 * 每条记录对应一段被向量化存储的对话片段，供新会话按话题 top-k 检索相关历史，
 * 实现「越用越懂我」的跨会话记忆能力，同时避免旧会话全文污染新上下文。
 *
 * **三层记忆架构定位**（ADR-015）：
 * - L1 会话内：滑动窗口 + 摘要压缩（[io.prism.memory.SlidingWindowMemoryManager]）
 * - L2 跨会话：**本实体**，向量化存储 + top-k 检索（[MemoryRepository.searchByVector]）
 * - L3 用户画像：结构化偏好（[UserProfile]）
 *
 * **设计决策**（ADR-015 5.1 + M5 考古报告 §2.1）：
 * - 复用 M3 [KnowledgeChunk] @Entity 模式：`@HnswIndex(dimensions=384, COSINE)` + `FloatArray?`
 *   可空向量字段 + 扁平外键。考古报告 V-2 已验证此模式可直接复用。
 * - **不引入 MemoryRecordConverter**（prd.json US-030 偏离说明）：原 AC 提及
 *   "MemoryRecordConverter（List<Float> ↔ float[]）"，但 KnowledgeChunk 证实
 *   `FloatArray? + @HnswIndex` 已满足向量持久化需求，引入 Converter 反而增加冗余
 *   转换层与出错点。本实体直接用 `FloatArray?`，与既有向量实体保持一致。
 * - **sessionId 采用 String 类型**（而非 Long）：会话 ID 在未来会话持久化时大概率是
 *   UUID 字符串（唯一性强），且记忆记录数量远小于知识库 chunk，String 查询性能可接受。
 *   避免未来会话实体化时的类型迁移成本。
 * - **embedding 可为 null**：与 KnowledgeChunk 一致，支持「先入库文本后补充向量」的
 *   两阶段写入模式；HNSW 索引自动排除 null embedding（M3 已验证）。
 *
 * **字段语义**：
 * - [id] 主键（ObjectBox 自增）
 * - [sessionId] 会话标识（扁平 String 外键，关联未来会话实体；当前为运行时生成的 UUID）
 * - [content] 对话片段文本（用于检索后注入新会话上下文）
 * - [embedding] 384 维 L2 归一化向量（all-MiniLM-L6-v2，与 M3 Embedder 对齐）
 * - [timestamp] 记录创建时间戳（毫秒），用于按时间排序检索结果
 * - [turnCount] 对话轮次（记录该片段来自会话的第几轮，便于上下文排序）
 *
 * **HNSW 删除策略**（ADR-015 风险表 H-4，规避 objectbox-java#1209）：
 * [MemoryRepository.deleteBySession] / [MemoryRepository.deleteAll] 使用
 * `findIds()` + `Box.remove(ids)` 模式，不可用 `Query.remove()`（M3 已验证的规避策略）。
 *
 * **FloatArray equals 引用比较**（H-3，BR-security-001）：
 * data class 自动生成的 equals/hashCode 对 FloatArray 使用引用比较。
 * 本实体不依赖 equals 比较 embedding（向量比较通过相似度计算），如需内容比较需覆盖。
 *
 * US-030 验收标准 1：定义 MemoryRecord @Entity
 * US-030 验收标准 3：searchByVector 复用 M3 ObjectBox 向量搜索（nearVector）
 *
 * @see MemoryRepository
 * @see KnowledgeChunk
 * @see <a href="https://docs.objectbox.io/entity-annotations">ObjectBox Entity Annotations</a>
 */
@Entity
data class MemoryRecord(
    @Id var id: Long = 0,
    var sessionId: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var turnCount: Int = 0,
    /**
     * 记忆重要性评分（v1 记忆深度优化 US-101，参照 TencentDB-Agent-Memory priority）。
     *
     * 0-100，由 LLM 原子记忆抽取时赋值（未走 LLM 抽取的规则存储默认 [DEFAULT_PRIORITY]）。
     * 用于 [io.prism.memory.CrossSessionMemoryManager] 的软衰减评分
     * `recallScore = priority × exp(-λ·age) × (1+α·accessCount)` 与容量回收排序。
     */
    var priority: Int = DEFAULT_PRIORITY,
    /**
     * 记忆被检索命中的次数（v1 记忆深度优化 US-101）。
     *
     * 每次 [MemoryRepository.searchByVector] / 混合检索命中该记录时 +1（软衰减的
     * 使用频率信号，对应 Bjork「检索强度」模型：越常命中越难遗忘）。
     */
    var accessCount: Long = 0,
    /**
     * 记忆版本号（v1 记忆深度优化 US-101，参照 TencentDB-Agent-Memory version）。
     *
     * 新增=1，批量去重 update/merge 时单调递增（US-103），供溯源与去重审计。
     */
    var version: Int = 1,
    /**
     * 记忆来源消息引用（v1 记忆深度优化 US-101）。
     *
     * 记录该条记忆源自哪些 [io.prism.ui.model.ChatMessage] id（逗号分隔），
     * 实现记忆溯源（来源消息可追溯）。LLM 抽取路径由管理端按输入消息 id 赋值；
     * 规则存储路径为空串。
     *
     * **可空原因（v1 真机修复）**：US-101 新增该字段时 ObjectBox 自动迁移仅对新增列
     * 写入 SQL NULL（不反填非空默认值），旧版本遗留的记忆行该字段为 null。若声明为非空，
     * 读库时 ObjectBox 将 null 传给 Kotlin 非空构造参数触发 Intrinsics NPE 崩溃。
     * 改为可空后读取 null 安全；调用方在结果对象处 `?: ""` 归一化。
     */
    var sourceMessageIds: String? = null
) {
    /**
     * FloatArray 字段的 equals/hashCode 覆盖（H-3，BR-security-001 + L-01 修复）。
     *
     * data class 自动生成的版本对 FloatArray 使用引用比较（`===`），
     * 导致两条内容相同的 MemoryRecord 判为不等。本覆盖改用 [FloatArray.contentEquals]
     * 进行内容比较，确保业务逻辑按内容判断实体相等性时行为正确。
     *
     * **L-01 修复**（TKN-M5-PHASEA-GUARDRAIL-001）：
     * 原实现 `embedding?.contentEquals(other.embedding) == true` 在双 null embedding 时
     * 短路为 `null == true` → false，语义不正确。改用 nullable 扩展函数
     * `FloatArray?.contentEquals(FloatArray?): Boolean`（Kotlin 标准库），双 null 返回 true，
     * 单 null 返回 false，双非 null 做内容比较。详见 BR-security-001 补充条款。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryRecord
        return id == other.id &&
            sessionId == other.sessionId &&
            content == other.content &&
            embedding.contentEquals(other.embedding) &&
            timestamp == other.timestamp &&
            turnCount == other.turnCount &&
            priority == other.priority &&
            accessCount == other.accessCount &&
            version == other.version &&
            sourceMessageIds == other.sourceMessageIds
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + turnCount.hashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + accessCount.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + (sourceMessageIds?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * 记忆重要性评分默认值（v1 记忆深度优化 US-101）。
         *
         * LLM 抽取失败/未走抽取路径时使用；范围 0-100（[MIN_PRIORITY]..[MAX_PRIORITY]）。
         */
        const val DEFAULT_PRIORITY = 50

        /** 记忆重要性评分下界。 */
        const val MIN_PRIORITY = 0

        /** 记忆重要性评分上界。 */
        const val MAX_PRIORITY = 100
    }
}
