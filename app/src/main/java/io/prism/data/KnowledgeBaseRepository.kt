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
     * 将单个 [KnowledgeChunk] 写入指定知识库（US-016 摄入管线写入入口，ADR-009 5.2）。
     *
     * **设计**（ADR-009 5.2）：
     * - KnowledgeChunk 无独立 Repository，由本类代管（US-015 既有模式）。
     * - 入口校验 `knowledgeBaseId >= 0`，拒绝负数（纵深防御）。
     * - `embedding = null` 时仍入库（content/title 可被 US-017 全文检索兜底），
     *   HNSW 向量索引自动排除 null embedding（AC-3）。
     * - 不刷新 `_knowledgeBases` Flow：chunk 增删不影响 KB 列表，但 [chunkCount] 反映新值。
     *
     * **事务边界**（ADR-009 5.5）：chunk 级独立 put，不强制文档级 `runInTx`。
     * 嵌入是昂贵操作，文档级事务中途失败会丢失已嵌入结果。chunk 级 put 失败不影响已入库 chunk。
     *
     * @param chunk 待写入的分块（knowledgeBaseId 必须 >= 0）
     * @return 写入后的 chunk id
     * @throws IllegalArgumentException 当 chunk.knowledgeBaseId < 0 时
     */
    fun addChunk(chunk: KnowledgeChunk): Long {
        require(chunk.knowledgeBaseId >= 0) {
            "knowledgeBaseId 不能为负数（收到 ${chunk.knowledgeBaseId}）"
        }
        return chunkBox.put(chunk)
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
     * 向量检索 —— 基于 HNSW nearestNeighbors 的 top-k 语义检索（US-017，ADR-010）。
     *
     * **检索流程**（ADR-010 5.1~5.9）：
     * 1. 前置校验：query 维度 == 384、k > 0、knowledgeBaseId >= 0（fail-fast）
     * 2. 构造 ObjectBox Query：`nearestNeighbors(query, k)` 条件 + 可选 `equal(knowledgeBaseId)` 过滤
     * 3. `findWithScores()` 执行检索，返回 `List<QueryResult<KnowledgeChunk>>`（距离升序，最相似在前）
     * 4. 距离 → 相似度转换：`similarity = 1.0 - distance`（范围 [-1, 1]，ADR-010 5.2）
     * 5. title 解析：`lastIndexOf('#')` 提取 documentTitle 与 chunkIndex（ADR-010 5.9）
     * 6. Query 用 `use {}` 关闭释放 native 句柄（BR-concurrency-003，ADR-010 5.7）
     *
     * **kbId 三态语义**（ADR-010 5.4）：
     * - `null`：全库检索（所有 chunk 不分库，不加 equal 过滤）
     * - `0L`：默认库（DEFAULT_KB_ID，虚拟库，加 equal(knowledgeBaseId, 0L)）
     * - `>0`：指定自建库（加 equal(knowledgeBaseId, id)）
     *
     * **分库检索可行性**：探针测试 ProbeNearestNeighborsWithEqualTest 5 用例全通过，
     * 确认 nearestNeighbors + equal(knowledgeBaseId) 组合在 ObjectBox 5.4.2 下可用（ADR-010 5.3）。
     *
     * **维度校验**（ADR-010 5.6）：ObjectBox 5.4.2 对维度不匹配属未定义行为，
     * 本方法 `require(query.size == EMBEDDING_DIM)` 前置校验，不依赖未定义行为。
     *
     * **资源管理**：Query 用 `use {}` 关闭，与 chunkCount/remove 既有模式一致。
     * 禁止照搬 KnowledgeChunkVectorSearchTest 未 close 的瑕疵模式（考古报告 §5.4）。
     *
     * **空库/无匹配**：空库或纯 null embedding 库返回空 list（HNSW 自动排除 null embedding）。
     *
     * **无相似度阈值过滤**（guardrail L3 / ADR-010 5.2）：本方法返回 top-k 不论相似度高低，
     * 即使所有结果 similarity ≈ 0 也会返回。相似度阈值过滤是业务决策，由上层 US-019 RAG 集成时
     * 由调用方根据业务需求决定（如 `results.filter { it.similarity > 0.3 }`）。数据层保留数学语义，
     * 不固化业务阈值，便于不同场景调优。
     *
     * **HNSW 近似性**（guardrail L4 / ADR-010 风险表）：HNSW 是近似最近邻算法，查询向量与库内
     * 向量正交（distance≈1）时，可能不返回全部 k 条匹配（如 k=5 但仅返回 2 条）。这是近似算法
     * 固有特性非缺陷，调用方应处理 `results.size < k` 的情况。
     *
     * US-017 验收标准 1：top-k 检索（默认 k=5，可配置）基于 nearestNeighbors
     * US-017 验收标准 2：支持指定库或全库检索
     * US-017 验收标准 3：检索结果含相似度分数与来源（文件/片段位置）
     * US-017 验收标准 4：检索单元测试通过（含空库、无匹配）
     *
     * @param query 查询向量（必须 384 维，与 KnowledgeChunk.embedding 索引维度一致）
     * @param k 返回结果数上限（默认 5，必须 >0）
     * @param knowledgeBaseId 知识库 id；null=全库，0L=默认库，>0=指定自建库
     * @return top-k 检索结果列表，按相似度降序（最相似在前）；空库或无匹配返回空 list
     * @throws IllegalArgumentException 当 query 维度 != 384、k <= 0、knowledgeBaseId < 0 时
     */
    fun search(
        query: FloatArray,
        k: Int = DEFAULT_SEARCH_K,
        knowledgeBaseId: Long? = null
    ): List<RetrievalResult> {
        require(query.size == EMBEDDING_DIM) {
            "查询向量维度必须为 $EMBEDDING_DIM（收到 ${query.size}）。" +
                "嵌入模型 all-MiniLM-L6-v2 固定 384 维，与 KnowledgeChunk.embedding 索引维度一致。"
        }
        require(k > 0) { "k 必须为正数（收到 $k）" }
        require(knowledgeBaseId == null || knowledgeBaseId >= 0) {
            "knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）"
        }

        val queryBuilder = chunkBox.query(
            KnowledgeChunk_.embedding.nearestNeighbors(query, k)
        )
        if (knowledgeBaseId != null) {
            queryBuilder.equal(KnowledgeChunk_.knowledgeBaseId, knowledgeBaseId)
        }
        return queryBuilder.build().use { q ->
            q.findWithScores().map { result ->
                val chunk = result.get()
                val (documentTitle, chunkIndex) = parseTitle(chunk.title)
                RetrievalResult(
                    chunkId = chunk.id,
                    content = chunk.content,
                    title = chunk.title,
                    similarity = 1.0 - result.getScore(),
                    documentTitle = documentTitle,
                    chunkIndex = chunkIndex,
                    knowledgeBaseId = chunk.knowledgeBaseId
                )
            }
        }
    }

    /**
     * 解析 KnowledgeChunk.title 为文档标题与分块序号（ADR-010 5.9）。
     *
     * title 原文格式 `${documentTitle}#${index+1}`（IngestionPipeline 生成，ADR-009）。
     * documentTitle 可能含 `#`（如「C#入门.pdf」→「C#入门#1」），用 `lastIndexOf('#')`
     * 取最后一个 `#` 分割，规避文件名含 `#` 的歧义。
     *
     * **容错降级**（不抛异常，保持检索可用）：
     * - title 不含 `#`、`#` 在首位/末尾时：返回 `title to null`（无法分割，documentTitle=title 原文）。
     * - 序号非正整数（0/负数/非数字）时：返回 `documentTitle to null`（保留 `#` 左侧有效部分作为文档标题，
     *   丢弃非法序号；UI 层展示 documentTitle 不需要看到 `#0` 等非法序号）。
     *
     * @param title KnowledgeChunk.title 原文
     * @return Pair(documentTitle, chunkIndex?)；chunkIndex 为 1-based，无法解析时为 null
     */
    private fun parseTitle(title: String): Pair<String, Int?> {
        val idx = title.lastIndexOf('#')
        if (idx <= 0 || idx >= title.length - 1) {
            return title to null
        }
        val documentTitle = title.substring(0, idx)
        val chunkIndex = title.substring(idx + 1).toIntOrNull()
        return if (chunkIndex != null && chunkIndex > 0) {
            documentTitle to chunkIndex
        } else {
            documentTitle to null
        }
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

        /** 向量检索默认 k 值（US-017 AC：默认 k=5，可配置；ADR-010 5.5）。 */
        const val DEFAULT_SEARCH_K: Int = 5

        /**
         * 嵌入向量维度（与 KnowledgeChunk.embedding `@HnswIndex(dimensions=384)` 对齐，ADR-010 5.6）。
         *
         * all-MiniLM-L6-v2 固定输出 384 维向量。search 方法用此常量校验 query 维度，
         * 规避 ObjectBox 5.4.2 对维度不匹配的未定义行为。
         */
        const val EMBEDDING_DIM: Int = 384
    }
}
