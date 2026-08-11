package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨会话记忆仓库 —— 管理 [MemoryRecord] 的 CRUD 与向量检索（US-030，ADR-015 5.1）。
 *
 * **架构**（ADR-015 5.1，复用 M3 [KnowledgeBaseRepository] 模式）：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [MemoryRecord] Box
 * - [memoryRecords] 暴露全部记忆列表（按 timestamp 升序），供 UI 订阅（US-036 记忆管理）
 *
 * **三层记忆定位**（ADR-015）：
 * - 本仓库为 L2 跨会话记忆的持久化层，由 [io.prism.memory.CrossSessionMemoryManager]
 *   （US-033）在会话结束时调用 [save] 持久化对话片段向量，在新会话首条消息时调用
 *   [searchByVector] 检索 top-k 相关历史。
 * - **防污染机制**（ADR-015 决策 2）：新会话不加载旧会话全文，仅通过 [searchByVector]
 *   注入 top-k 检索结果，保证新会话上下文干净。
 *
 * **向量检索复用 M3 基建**（ADR-015 决策 1，考古报告 §2.2）：
 * - `nearestNeighbors(query, k)` + `findWithScores()` + `use{}` 模式与
 *   [KnowledgeBaseRepository.search] 完全一致
 * - 距离 → 相似度转换：`similarity = 1.0 - distance`（COSINE 距离 d∈[0,2] → 相似度 s∈[-1,1]）
 * - 维度校验 `require(query.size == 384)` fail-fast（ADR-010 5.6）
 * - HNSW 近似性：可能返回少于 k 条结果（HNSW 固有特性，非缺陷）
 *
 * **HNSW 删除策略**（ADR-015 风险表 H-4，规避 objectbox-java#1209）：
 * [deleteBySession] / [deleteAll] 使用 `findIds()` + `Box.remove(ids)` 模式，
 * 不可用 `Query.remove()`（nativeRemove 路径在 HNSW 索引下可能抛
 * `IllegalStateException: Vector is missing for neighbor to repair`）。
 * Query 用 `use {}` 关闭释放 native 句柄（BR-concurrency-003）。
 *
 * **事务原子性**（BR-concurrency-001）：
 * [deleteBySession] / [deleteAll] 在单个 [boxStore.runInTx] 事务内完成
 * 「查 id → 删记录」，保证全成功或全回滚。
 *
 * US-030 验收标准 2：MemoryRepository CRUD（save / getBySession / searchByVector / deleteBySession / deleteAll）
 * US-030 验收标准 3：searchByVector 复用 M3 ObjectBox 向量搜索（nearVector）
 * US-030 验收标准 4：MemoryRepository 单元测试通过
 *
 * @param boxStore ObjectBox BoxStore 实例
 */
class MemoryRepository(private val boxStore: BoxStore) {

    private val box: Box<MemoryRecord> = boxStore.boxFor(MemoryRecord::class.java)

    private val _memoryRecords = MutableStateFlow<List<MemoryRecord>>(emptyList())
    /** 全部记忆记录列表（按 timestamp 升序），供 UI 订阅。 */
    val memoryRecords: StateFlow<List<MemoryRecord>> = _memoryRecords.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新记忆记录。
     *
     * @param record 待保存的记录（id=0 为新建，id>0 为更新）
     * @return 保存后的 id
     */
    fun save(record: MemoryRecord): Long {
        val id = box.put(record)
        refreshFlows()
        return id
    }

    /**
     * 按 sessionId 获取该会话的所有记忆记录。
     *
     * **查询方式**：使用 `box.all` 内存过滤（与 [SkillRepository.findByName]、
     * [McpServerRepository.findByName] 既有模式一致）。ObjectBox 5.4.2 的 String
     * 字段为 `Property<T>` 而非 `StringProperty`，`equal` 方法无 String 重载，
     * 内存过滤避免 API 兼容性问题。记忆记录数量远小于知识库 chunk，性能可接受。
     *
     * @param sessionId 会话标识
     * @return 按timestamp 升序的记忆列表；无记录返回空 list
     */
    fun getBySession(sessionId: String): List<MemoryRecord> =
        box.all.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }

    /**
     * 向量检索 —— 基于 HNSW nearestNeighbors 的 top-k 语义检索（US-030 AC-3，ADR-015 5.1）。
     *
     * **检索流程**（复用 M3 [KnowledgeBaseRepository.search] 模式，考古报告 §2.2）：
     * 1. 前置校验：query 维度 == 384、topK > 0（fail-fast）
     * 2. 构造 ObjectBox Query：`nearestNeighbors(query, topK)`
     * 3. `findWithScores()` 执行检索，返回 `List<QueryResult<MemoryRecord>>`（距离升序）
     * 4. 距离 → 相似度转换：`similarity = 1.0 - distance`（范围 [-1, 1]）
     * 5. Query 用 `use {}` 关闭释放 native 句柄（BR-concurrency-003）
     *
     * **防污染**（ADR-015 决策 2）：本方法仅返回 top-k 检索结果，调用方
     * （[io.prism.memory.CrossSessionMemoryManager]）将结果注入新会话上下文，
     * 不加载旧会话全文。
     *
     * **无相似度阈值过滤**（与 [KnowledgeBaseRepository.search] 一致）：数据层保留数学语义，
     * 阈值过滤由调用方根据业务需求决定（如 `results.filter { it.similarity > 0.3 }`）。
     *
     * **HNSW 近似性**：HNSW 是近似最近邻算法，可能返回少于 topK 条结果（HNSW 固有特性非缺陷），
     * 调用方应处理 `results.size < topK` 的情况。
     *
     * **空库/无匹配**：空库或纯 null embedding 库返回空 list（HNSW 自动排除 null embedding）。
     *
     * US-030 验收标准 3：searchByVector 复用 M3 ObjectBox 向量搜索（nearVector）
     *
     * @param queryEmbedding 查询向量（必须 384 维，与 MemoryRecord.embedding 索引维度一致）
     * @param topK 返回结果数上限（默认 3，必须 >0）
     * @return top-k 检索结果列表，按相似度降序（最相似在前）；空库或无匹配返回空 list
     * @throws IllegalArgumentException 当 query 维度 != 384、topK <= 0 时
     */
    fun searchByVector(
        queryEmbedding: FloatArray,
        topK: Int = DEFAULT_SEARCH_TOP_K
    ): List<MemorySearchResult> {
        require(queryEmbedding.size == EMBEDDING_DIM) {
            "查询向量维度必须为 $EMBEDDING_DIM（收到 ${queryEmbedding.size}）。" +
                "嵌入模型 all-MiniLM-L6-v2 固定 384 维，与 MemoryRecord.embedding 索引维度一致。"
        }
        require(topK > 0) { "topK 必须为正数（收到 $topK）" }

        return box.query(
            MemoryRecord_.embedding.nearestNeighbors(queryEmbedding, topK)
        ).build().use { q ->
            q.findWithScores().map { result ->
                val record = result.get()
                MemorySearchResult(
                    recordId = record.id,
                    sessionId = record.sessionId,
                    content = record.content,
                    similarity = 1.0 - result.getScore(),
                    timestamp = record.timestamp,
                    turnCount = record.turnCount
                )
            }
        }
    }

    /**
     * 删除指定 id 的单条记忆记录（US-036 记忆管理 UI 单条删除）。
     *
     * **HNSW 删除策略**（ADR-015 风险表 H-4，规避 objectbox-java#1209）：
     * 使用 `Box.remove(id)` 单条 native 路径，不可用 `Query.remove()`。
     *
     * **事务原子性**（BR-concurrency-001）：删除在单个 [boxStore.runInTx] 事务内执行。
     *
     * @param id 待删除记录的 id
     * @return true 表示删除成功（id 存在），false 表示 id 不存在
     */
    fun deleteById(id: Long): Boolean {
        var deleted = false
        boxStore.runInTx {
            if (box.contains(id)) {
                box.remove(id)
                deleted = true
            }
        }
        refreshFlows()
        return deleted
    }

    /**
     * 删除指定 sessionId 的所有记忆记录。
     *
     * **查询方式**：使用 `box.all` 内存过滤获取待删 id（同 [getBySession]），
     * 然后用 `Box.remove(ids)` 删除。
     *
     * **HNSW 删除策略**（ADR-015 风险表 H-4，规避 objectbox-java#1209）：
     * 使用 `find()` 获取记录 + `Box.remove(ids)` 模式，不可用 `Query.remove()`。
     *
     * **事务原子性**（BR-concurrency-001）：整个「查 id → 删记录」在单个
     * [boxStore.runInTx] 事务中执行。
     *
     * @param sessionId 会话标识
     * @return 删除的记录数
     */
    fun deleteBySession(sessionId: String): Long {
        var deletedCount = 0L
        boxStore.runInTx {
            val ids = box.all.filter { it.sessionId == sessionId }.map { it.id }.toLongArray()
            if (ids.isNotEmpty()) {
                box.remove(*ids)
                deletedCount = ids.size.toLong()
            }
        }
        refreshFlows()
        return deletedCount
    }

    /**
     * 删除所有记忆记录。
     *
     * **HNSW 删除策略**（同 [deleteBySession]）：用 `Box.removeAll()` 规避 #1209
     * （Box 级 native 路径，非 Query.remove）。
     *
     * @return 删除的记录数
     */
    fun deleteAll(): Long {
        val deletedCount = box.count()
        box.removeAll()
        refreshFlows()
        return deletedCount
    }

    /**
     * 获取全部记忆记录数（供 UI 展示统计）。
     */
    fun count(): Long = box.count()

    /**
     * 刷新记忆记录列表的 Flow。
     */
    private fun refreshFlows() {
        _memoryRecords.value = box.all.sortedBy { it.timestamp }
    }

    companion object {
        /**
         * 向量检索默认 top-k 值（US-030 AC-3，ADR-015 决策 2）。
         *
         * 默认 3，与 PRD US-005「新会话按当前话题 top-k 检索（默认 k=3）」对齐。
         * 调用方可按需配置。
         */
        const val DEFAULT_SEARCH_TOP_K: Int = 3

        /**
         * 嵌入向量维度（与 MemoryRecord.embedding `@HnswIndex(dimensions=384)` 对齐）。
         *
         * all-MiniLM-L6-v2 固定输出 384 维向量。searchByVector 用此常量校验 query 维度，
         * 规避 ObjectBox 5.4.2 对维度不匹配的未定义行为（ADR-010 5.6）。
         */
        const val EMBEDDING_DIM: Int = 384
    }
}

/**
 * 跨会话记忆检索结果（US-030，ADR-015 5.1）。
 *
 * 由 [MemoryRepository.searchByVector] 返回，承载 top-k 检索命中的记忆片段及其相似度。
 *
 * **字段语义**：
 * - [similarity]：相似度分数 ∈ [-1, 1]，1=完全相同，0=正交，-1=相反。
 *   由 ObjectBox COSINE 距离 d∈[0,2] 转换：`similarity = 1 - distance`（ADR-010 5.2）。
 * - [timestamp] / [turnCount]：用于调用方按时间或轮次排序检索结果。
 *
 * **不可变性**：data class 全字段 val，FloatArray 不出现在本类（避免 BR-security-001 引用比较问题）。
 *
 * @property recordId MemoryRecord id
 * @property sessionId 所属会话标识
 * @property content 记忆片段原文
 * @property similarity 相似度分数 ∈ [-1, 1]（1 - COSINE 距离）
 * @property timestamp 记录创建时间戳（毫秒）
 * @property turnCount 对话轮次
 */
data class MemorySearchResult(
    val recordId: Long,
    val sessionId: String,
    val content: String,
    val similarity: Double,
    val timestamp: Long,
    val turnCount: Int
)
