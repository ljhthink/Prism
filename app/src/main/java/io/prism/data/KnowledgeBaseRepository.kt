package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 知识库分库仓库 —— 管理 [KnowledgeBase] 的 CRUD 与级联删除（US-015）。
 *
 * **架构**（ADR-008 5.1/5.4，仿 [McpServerRepository] 轻量 CRUD +
 * [ProviderConfigRepository] 事务模式）：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [KnowledgeBase] Box
 * - [chunkBox] 关联的 [KnowledgeChunk] Box（用于级联删除）
 * - [knowledgeBases] 暴露全部自建库列表（按 createdAt 升序），供 UI 订阅
 *
 * **默认库语义**（ADR-008 5.3）：
 * `knowledgeBaseId = 0L` 代表虚拟默认库，**不持久化为本表记录**。
 * [remove] 入口校验 `require(id != DEFAULT_KB_ID)` 拒绝删除默认库，
 * 因默认库不属于本表 CRUD 范围。
 *
 * **级联删除原子性**（ADR-008 5.4，BR-concurrency-001）：
 * [remove] 在单个 [boxStore.runInTx] 事务内完成
 * 「查关联 chunk → 删 chunk → 删 KnowledgeBase」，保证全成功或全回滚，
 * 不会留下「库已删但 chunk 残留」的不一致状态。
 *
 * US-015 验收标准 2：KnowledgeBaseRepository 分库 CRUD
 * US-015 验收标准 4：分库 CRUD 单元测试通过
 *
 * @param boxStore ObjectBox BoxStore 实例
 */
class KnowledgeBaseRepository(private val boxStore: BoxStore) {

    private val box: Box<KnowledgeBase> = boxStore.boxFor(KnowledgeBase::class.java)
    private val chunkBox: Box<KnowledgeChunk> = boxStore.boxFor(KnowledgeChunk::class.java)

    private val _knowledgeBases = MutableStateFlow<List<KnowledgeBase>>(emptyList())
    /** 全部自建知识库列表（按 createdAt 升序），供 UI 订阅。不含虚拟默认库。 */
    val knowledgeBases: StateFlow<List<KnowledgeBase>> = _knowledgeBases.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新知识库。
     *
     * @param config 要保存的配置（id=0 为新建，id>0 为更新）
     * @return 保存后的 id
     */
    fun save(config: KnowledgeBase): Long {
        val id = box.put(config)
        refreshFlows()
        return id
    }

    /**
     * 按 id 获取知识库。
     *
     * @param id KnowledgeBase id（>=0；0L 为虚拟默认库，直接返回 null 不查表；<0 抛异常）
     * @return 配置对象，不存在或 id=0 返回 null
     * @throws IllegalArgumentException 当 id < 0 时
     */
    fun get(id: Long): KnowledgeBase? {
        require(id >= 0) { "KnowledgeBase id 不能为负数（id=$id）" }
        if (id == DEFAULT_KB_ID) return null
        return box.get(id)
    }

    /**
     * 获取全部自建知识库（不含虚拟默认库）。
     *
     * @return 配置列表（按 createdAt 升序）
     */
    fun getAll(): List<KnowledgeBase> = box.all.sortedBy { it.createdAt }

    /**
     * 按名称查找知识库。
     *
     * @param name 库名称（精确匹配）
     * @return 匹配的配置，未找到返回 null
     */
    fun findByName(name: String): KnowledgeBase? =
        box.all.find { it.name == name }

    /**
     * 删除指定 id 的知识库，并在同一事务内级联删除其下所有 [KnowledgeChunk]。
     *
     * **默认库拒绝删除**（ADR-008 5.4）：[id] = [DEFAULT_KB_ID] 时抛 [IllegalArgumentException]，
     * 因默认库是虚拟库不持久化为本表记录。负数 id 同样拒绝（G-04 纵深防御）。
     *
     * **级联删除原子性**（ADR-008 5.4，BR-concurrency-001）：整个「查 chunk → 删 chunk → 删库」
     * 操作在单个 [boxStore.runInTx] 事务中执行。若中途异常，事务回滚，
     * 不会留下「库已删但 chunk 残留」的不一致状态。
     *
     * **HNSW 索引删除策略**（ADR-008 5.4，规避 objectbox-java#1209）：
     * KnowledgeChunk 有 `@HnswIndex` 向量索引，`Query.remove()`（nativeRemove 路径）
     * 在 HNSW 索引下可能抛 `IllegalStateException: Vector is missing for neighbor to repair`
     * （已知 bug #1209，截至 5.4.2 未确认修复）。本方法改用 `Query.findIds()` 查 id +
     * `Box.remove(ids)` 走 Box native 路径删除，规避该 bug。同时 `findIds()` 后用
     * `use {}` 关闭 Query 释放 native 句柄（G-02）。
     *
     * @param id KnowledgeBase id（必须 >0，禁止 0L 默认库，禁止负数）
     * @throws IllegalArgumentException 当 id = [DEFAULT_KB_ID] 或 id < 0 时
     */
    fun remove(id: Long) {
        require(id >= 0) { "KnowledgeBase id 不能为负数（id=$id）" }
        require(id != DEFAULT_KB_ID) {
            "禁止删除虚拟默认库（id=$DEFAULT_KB_ID）。默认库不持久化为 KnowledgeBase 记录。"
        }
        boxStore.runInTx {
            // 1. 先删除关联 KnowledgeChunk（规避 HNSW #1209：findIds + Box.remove 而非 Query.remove）
            val chunkIds = chunkBox.query()
                .equal(KnowledgeChunk_.knowledgeBaseId, id)
                .build()
                .use { it.findIds() }
            if (chunkIds.isNotEmpty()) {
                chunkBox.remove(*chunkIds)
            }
            // 2. 再删除 KnowledgeBase 本身
            box.remove(id)
        }
        refreshFlows()
    }

    /**
     * 删除所有自建知识库及其下 [KnowledgeChunk]。
     *
     * 注意：仅清空自建库（id>0）及其 chunk，**不影响默认库（id=0L）下的 chunk**
     * （默认库 0L 不在 [box.all] 中，遍历不到）。用于测试清理或「重置知识库」功能。
     *
     * **原子性保证**（BR-concurrency-001）：整个「遍历删 chunk + 删所有 KnowledgeBase」
     * 操作在单个 [boxStore.runInTx] 事务中执行。
     *
     * **HNSW 索引删除策略**（同 [remove]）：用 `findIds()` + `Box.remove(ids)` 规避 #1209，
     * `use {}` 关闭 Query（G-02）。
     */
    fun removeAll() {
        boxStore.runInTx {
            // 遍历每个自建库，逐个删除其 chunk（默认库 0L 不在 box.all 中，不会被删）
            box.all.forEach { kb ->
                val chunkIds = chunkBox.query()
                    .equal(KnowledgeChunk_.knowledgeBaseId, kb.id)
                    .build()
                    .use { it.findIds() }
                if (chunkIds.isNotEmpty()) {
                    chunkBox.remove(*chunkIds)
                }
            }
            // 删除所有 KnowledgeBase 记录
            box.removeAll()
        }
        refreshFlows()
    }

    /**
     * 统计指定知识库下的 [KnowledgeChunk] 数量。
     *
     * 用于 UI 显示库的 chunk 计数（运行时聚合，ADR-008 5.1）。
     * [id] = [DEFAULT_KB_ID] 时返回默认库下的 chunk 数。
     *
     * **资源管理**（G-02）：Query 用 `use {}` 关闭，避免 native 句柄依赖 GC 终结化回收。
     *
     * @param id KnowledgeBase id（0L = 默认库，>0 = 自建库，禁止负数）
     * @return chunk 数量
     * @throws IllegalArgumentException 当 id < 0 时
     */
    fun chunkCount(id: Long): Long {
        require(id >= 0) { "KnowledgeBase id 不能为负数（id=$id）" }
        return chunkBox.query()
            .equal(KnowledgeChunk_.knowledgeBaseId, id)
            .build()
            .use { it.count() }
    }

    /**
     * 刷新自建知识库列表的 Flow。
     */
    private fun refreshFlows() {
        _knowledgeBases.value = box.all.sortedBy { it.createdAt }
    }

    companion object {
        /** 虚拟默认库标识（ADR-008 5.3）。不持久化为 KnowledgeBase 记录。 */
        const val DEFAULT_KB_ID: Long = 0L
    }
}
