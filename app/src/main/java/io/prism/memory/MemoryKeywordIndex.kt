package io.prism.memory

import io.prism.data.MemoryRecord

/**
 * 记忆关键词索引（v1 记忆深度优化 US-102）—— 为混合检索提供 BM25 关键词召回。
 *
 * **定位**：与 [io.prism.data.MemoryRepository.searchByVector]（HNSW 向量）组成双路召回，
 * 经 [RrfFusion.rrfMerge]（k=60）融合。关键词路召回解决「向量语义相似但关键词未精确命中」
 * 与「中文精确词句（如"上次说的 Kotlin 协程"）」的召回短板。
 *
 * **同步模型**（[reconcile]）：以 [MemoryRecord] 与数据变更版本号做**版本化重建**——
 * 调用方（[CrossSessionMemoryManager.retrieveRelevantMemories]）在检索前传入
 * `memoryRepository.mutationVersion`；版本未变化则跳过重建（避免每次 O(N) 分词）。
 *
 * **实现**：
 * - [InMemoryMemoryKeywordIndex]：纯 Kotlin 倒排索引 + BM25（JVM 单测可用，作生产降级）
 * - [SqliteFtsMemoryIndex]：Android SQLite FTS5（生产主路径，零 APK 体积增量）
 *
 * @see RrfFusion
 * @see MemoryFtsTokenizer
 */
interface MemoryKeywordIndex {

    /**
     * 与当前记忆数据对齐（版本化增量重建）。
     *
     * @param records 当前全部记忆记录
     * @param version 数据变更版本号（[io.prism.data.MemoryRepository.mutationVersion]）
     */
    fun reconcile(records: List<MemoryRecord>, version: Long)

    /**
     * BM25 关键词检索。
     *
     * @param query 用户查询文本（内部经 [MemoryFtsTokenizer] 预分词）
     * @param topK 返回结果数上限
     * @return 命中列表（按 BM25 分数降序）；空索引/无命中返回空列表
     */
    fun search(query: String, topK: Int): List<MemoryKeywordHit>
}

/**
 * 关键词命中结果（v1 US-102）。
 *
 * @property recordId 命中的 [MemoryRecord] id
 * @property score BM25 分数（仅用于排序，不同实现间不可直接比较）
 */
data class MemoryKeywordHit(
    val recordId: Long,
    val score: Double
)

/**
 * 纯 Kotlin BM25 倒排索引实现（v1 US-102）。
 *
 * **用途**：JVM 单元测试主用 + 生产 SQLite 不可用时的降级。数据规模 ≤1 万条记忆时
 * 内存索引 + 重建成本（仅分词）完全可接受。
 *
 * **BM25 参数**：k1=1.2、b=0.75（Lucene 默认），IDF 采用
 * `ln(1 + (N - df + 0.5) / (df + 0.5))`（防负 IDF，语言模型变体）。
 */
class InMemoryMemoryKeywordIndex : MemoryKeywordIndex {

    /** 倒排索引：token → (docId → 词频)。 */
    private var invertedIndex: Map<String, Map<Long, Int>> = emptyMap()

    /** docId → 文档长度（token 数）。 */
    private var docLengths: Map<Long, Int> = emptyMap()

    /** 文档总数。 */
    private var docCount: Int = 0

    /** 上次同步的版本号（[reconcile] 用于跳过未变更重建）。 */
    private var lastVersion: Long = -1

    override fun reconcile(records: List<MemoryRecord>, version: Long) {
        if (version == lastVersion) return
        rebuild(records)
        lastVersion = version
    }

    /**
     * 全量重建倒排索引（纯函数可测：经 [reconcile] 版本化触发）。
     *
     * @param records 全部记忆记录
     */
    internal fun rebuild(records: List<MemoryRecord>) {
        val inverted = HashMap<String, MutableMap<Long, Int>>()
        val lengths = HashMap<Long, Int>()
        for (record in records) {
            val tokens = MemoryFtsTokenizer.tokenize(record.content)
            if (tokens.isEmpty()) continue
            lengths[record.id] = tokens.size
            val tf = HashMap<String, Int>()
            for (token in tokens) {
                tf[token] = (tf[token] ?: 0) + 1
            }
            for ((token, count) in tf) {
                inverted.getOrPut(token) { HashMap() }[record.id] = count
            }
        }
        invertedIndex = inverted
        docLengths = lengths
        docCount = records.size
    }

    override fun search(query: String, topK: Int): List<MemoryKeywordHit> {
        if (topK <= 0) return emptyList()
        val queryTokens = MemoryFtsTokenizer.tokenize(query)
        if (queryTokens.isEmpty() || invertedIndex.isEmpty()) return emptyList()

        val avgDocLength = if (docCount > 0) {
            docLengths.values.sum().toDouble() / docCount
        } else 0.0

        val scores = HashMap<Long, Double>()
        for (token in queryTokens) {
            val postings = invertedIndex[token] ?: continue
            val df = postings.size
            val idf = Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5))
            for ((docId, tf) in postings) {
                val docLen = docLengths[docId] ?: continue
                val denom = tf + K1 * (1 - B + B * docLen / avgDocLength)
                scores[docId] = (scores[docId] ?: 0.0) + idf * (tf * (K1 + 1)) / denom
            }
        }

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
            .take(topK)
            .map { MemoryKeywordHit(it.key, it.value) }
    }

    companion object {
        /** BM25 词频饱和参数（Lucene 默认）。 */
        private const val K1 = 1.2

        /** BM25 文档长度归一化参数（Lucene 默认）。 */
        private const val B = 0.75
    }
}
