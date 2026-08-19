package io.prism.memory

/**
 * RRF（Reciprocal Rank Fusion）融合工具（v1 记忆深度优化 US-102）。
 *
 * **来源**：参照 TencentDB-Agent-Memory `search-utils.ts` 的 `rrfMerge`（RRF_K=60），
 * 以及 TREC 2025 RAG 冠军方案（BM25 + 稠密多路 + RRF k=60）实证。
 *
 * **原理**：对多路按排名排序的检索结果，按 `Σ 1/(k + rank + 1)` 累加融合分数，
 * 同 id 累加、按融合分数降序输出。核心优势是**不依赖各路分数可比较**——BM25 分数
 * 与向量余弦相似度量纲完全不同，RRF 只用排名位置，对分数分布差异/异常值鲁棒。
 *
 * @param k 平滑常数（参照 TencentDB-Agent-Memory 与 TREC 实践取 60）
 */
object RrfFusion {

    /**
     * 融合多路排名列表（纯函数，可测）。
     *
     * @param lists 多路检索结果（每路已按相关性降序；元素为各路的记录 id）
     * @param k RRF 平滑常数（默认 60）
     * @return 融合后的记录 id 列表（按融合分数降序；同分按 id 升序稳定）
     */
    fun rrfMerge(lists: List<List<Long>>, k: Int = DEFAULT_K): List<Long> {
        if (lists.isEmpty()) return emptyList()
        val fused = HashMap<Long, Double>()
        for (list in lists) {
            list.forEachIndexed { index, id ->
                fused[id] = (fused[id] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        return fused.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key }
            )
            .map { it.key }
    }

    /**
     * 融合并裁剪到 topK（便捷入口，纯函数可测）。
     *
     * @param lists 多路排名列表
     * @param topK 输出上限
     * @param k RRF 平滑常数
     * @return 融合后前 topK 条记录 id
     */
    fun rrfMergeTop(lists: List<List<Long>>, topK: Int, k: Int = DEFAULT_K): List<Long> =
        rrfMerge(lists, k).take(topK.coerceAtLeast(0))

    /** RRF 平滑常数（TencentDB-Agent-Memory / TREC 实践默认 60）。 */
    const val DEFAULT_K = 60
}
