package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * KnowledgeBaseRepository.search 向量检索单元测试（US-017）。
 *
 * 覆盖 AC：
 * - AC-1：top-k 检索（默认 k=5，可配置）基于 nearestNeighbors
 * - AC-2：支持指定库或全库检索
 * - AC-3：检索结果含相似度分数与来源（文件/片段位置）
 * - AC-4：检索单元测试通过（含空库、无匹配）
 *
 * 测试模式复用考古报告 §6.1：临时目录 + 纯 JVM ObjectBox + oneHot 向量控制相似度。
 * 通过 [KnowledgeBaseRepository.addChunk] 写入（黑盒，不直接操作 Box）。
 *
 * 关联 ADR：[ADR-010](../../docs/decisions/ADR-010-m3-vector-retrieval.md)
 * 关联探针：[ProbeNearestNeighborsWithEqualTest]（已验证 nearestNeighbors+equal 组合可行性）
 */
class KnowledgeBaseRetrievalTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: KnowledgeBaseRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-retrieval-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = KnowledgeBaseRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    /** 构造 384 维向量，[dominantIndex] 位置为 1.0，其余为 0.0。 */
    private fun oneHot(dominantIndex: Int): FloatArray {
        val vector = FloatArray(384)
        vector[dominantIndex] = 1.0f
        return vector
    }

    // ==================== AC-1：top-k 检索（默认 k=5，可配置） ====================

    /**
     * AC-1：top-k 检索返回正确数量，默认 k=5。
     *
     * 插入 7 条 chunk，默认 k=5 应返回 5 条。
     */
    @Test
    fun search_returns_topk_with_default_k_5() {
        for (i in 0 until 7) {
            repository.addChunk(
                KnowledgeChunk(
                    title = "文档#$i",
                    content = "内容$i",
                    embedding = oneHot(i),
                    knowledgeBaseId = 0L
                )
            )
        }

        val results = repository.search(oneHot(0))
        assertEquals("默认 k=5 应返回 5 条", 5, results.size)
    }

    /**
     * AC-1：k 可配置。
     *
     * k=3 应返回 3 条。
     */
    @Test
    fun search_k_configurable() {
        for (i in 0 until 7) {
            repository.addChunk(
                KnowledgeChunk(
                    title = "文档#$i",
                    content = "内容$i",
                    embedding = oneHot(i),
                    knowledgeBaseId = 0L
                )
            )
        }

        val results = repository.search(oneHot(0), k = 3)
        assertEquals("k=3 应返回 3 条", 3, results.size)
    }

    /**
     * AC-1/AC-3：结果按相似度降序（最相似在前），相似度范围 [-1, 1]。
     *
     * 查询向量在 0/1/2 维递减，与 oneHot(0/1/2) 的 cos(θ) 严格递减，
     * 相似度应 A > B > C（similarity = cos(θ)，未归一化查询向量下 similarity < 1.0）。
     */
    @Test
    fun search_returns_results_sorted_by_similarity_desc() {
        repository.addChunk(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "B", content = "b", embedding = oneHot(1), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "C", content = "c", embedding = oneHot(2), knowledgeBaseId = 0L))

        // 查询向量在 0/1/2 维递减，与各 oneHot 方向的 cos(θ) 严格可区分
        val query = FloatArray(384)
        query[0] = 0.4f
        query[1] = 0.3f
        query[2] = 0.2f

        val results = repository.search(query, k = 3)
        assertEquals("应返回 3 条", 3, results.size)
        assertEquals("最相似应为 A", "A", results[0].title)
        assertEquals("次相似应为 B", "B", results[1].title)
        assertEquals("最远应为 C", "C", results[2].title)

        // 相似度严格降序
        for (i in 0 until results.size - 1) {
            assertTrue(
                "第 $i 项相似度应 > 第 ${i + 1} 项（严格降序）",
                results[i].similarity > results[i + 1].similarity
            )
        }
        // 最相似项 similarity > 0（同向分量存在）
        assertTrue("最相似项 similarity 应 > 0（实际 ${results[0].similarity}）", results[0].similarity > 0)
        // 相似度范围 [-1, 1]
        results.forEach { r ->
            assertTrue("similarity 应 ∈ [-1, 1]（实际 ${r.similarity}）", r.similarity in -1.0..1.0)
        }
    }

    /**
     * AC-3：完全相同向量 similarity ≈ 1.0（COSINE 距离 ≈ 0）。
     *
     * 查询向量与 chunk embedding 完全相同时，similarity 应 ≈ 1.0。
     */
    @Test
    fun search_identical_vector_similarity_approx_one() {
        repository.addChunk(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("完全相同向量 similarity 应 ≈ 1.0", 1.0, results[0].similarity, 1e-5)
    }

    // ==================== AC-2：支持指定库或全库检索 ====================

    /**
     * AC-2：指定自建库检索仅返回该库 chunk。
     *
     * 查询向量在 3/4/5 维有正分量（与自建库 chunk 同向），与默认库 chunk（方向 0/1/2）正交。
     * 指定 kbId=1L 时应只返回自建库 3 条，不返回默认库的。
     */
    @Test
    fun search_specified_kb_filters_correctly() {
        // 默认库 3 条（方向 0/1/2，与查询正交）
        repository.addChunk(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "默认#2", content = "d2", embedding = oneHot(1), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "默认#3", content = "d3", embedding = oneHot(2), knowledgeBaseId = 0L))
        // 自建库 kbId=1L 3 条（方向 3/4/5，与查询同向）
        repository.addChunk(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(3), knowledgeBaseId = 1L))
        repository.addChunk(KnowledgeChunk(title = "自建#2", content = "k2", embedding = oneHot(4), knowledgeBaseId = 1L))
        repository.addChunk(KnowledgeChunk(title = "自建#3", content = "k3", embedding = oneHot(5), knowledgeBaseId = 1L))

        // 查询向量在 3/4/5 维递减，与自建库 chunk 同向，与默认库 chunk 正交
        val query = FloatArray(384)
        query[3] = 0.4f
        query[4] = 0.3f
        query[5] = 0.2f

        val results = repository.search(query, k = 5, knowledgeBaseId = 1L)
        assertEquals("指定自建库应只返回该库 3 条", 3, results.size)
        results.forEach { r ->
            assertEquals("结果应全部来自自建库", 1L, r.knowledgeBaseId)
        }
        // 排序应为 自建#1,2,3（与查询相似度递减）
        assertEquals("最相似应为自建#1", "自建#1", results[0].title)
        assertEquals("次相似应为自建#2", "自建#2", results[1].title)
        assertEquals("最远应为自建#3", "自建#3", results[2].title)
    }

    /**
     * AC-2：指定默认库（kbId=0L）仅返回默认库 chunk。
     */
    @Test
    fun search_default_kb_returns_only_default() {
        repository.addChunk(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "默认#2", content = "d2", embedding = oneHot(1), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(0), knowledgeBaseId = 1L))

        val results = repository.search(oneHot(0), k = 5, knowledgeBaseId = 0L)
        assertEquals("指定默认库应只返回默认库 2 条", 2, results.size)
        results.forEach { r ->
            assertEquals("结果应全部来自默认库", 0L, r.knowledgeBaseId)
        }
    }

    /**
     * AC-2：全库检索（kbId=null）跨库返回。
     */
    @Test
    fun search_all_kb_returns_cross_kb() {
        repository.addChunk(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(0), knowledgeBaseId = 1L))

        val results = repository.search(oneHot(0), k = 5, knowledgeBaseId = null)
        assertEquals("全库检索应跨库返回 2 条", 2, results.size)
        val kbIds = results.map { it.knowledgeBaseId }.toSet()
        assertTrue("全库结果应包含默认库与自建库", kbIds.contains(0L) && kbIds.contains(1L))
    }

    // ==================== AC-4：空库、无匹配 ====================

    /**
     * AC-4：空库返回空 list。
     */
    @Test
    fun search_empty_kb_returns_empty() {
        val results = repository.search(oneHot(0), k = 5)
        assertTrue("空库应返回空 list", results.isEmpty())
    }

    /**
     * AC-4：指定空库返回空（不存在的 kbId）。
     */
    @Test
    fun search_nonexistent_kb_returns_empty() {
        repository.addChunk(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 5, knowledgeBaseId = 999L)
        assertTrue("不存在的库应返回空", results.isEmpty())
    }

    /**
     * AC-4：纯 null embedding 库返回空（HNSW 自动排除 null embedding）。
     */
    @Test
    fun search_null_embedding_excluded() {
        repository.addChunk(KnowledgeChunk(title = "纯文本1", content = "未嵌入", embedding = null, knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "纯文本2", content = "未嵌入", embedding = null, knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 5)
        assertTrue("纯 null embedding 库应返回空", results.isEmpty())
    }

    /**
     * AC-4：null embedding 与有效 embedding 混合时，仅返回有效 embedding 的 chunk。
     */
    @Test
    fun search_mixed_null_and_valid_embeddings() {
        repository.addChunk(KnowledgeChunk(title = "有效", content = "已嵌入", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "无效", content = "未嵌入", embedding = null, knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 5)
        assertEquals("混合库应只返回 1 条有效 chunk", 1, results.size)
        assertEquals("应返回有效 chunk", "有效", results[0].title)
    }

    // ==================== AC-3：检索结果含相似度分数与来源 ====================

    /**
     * AC-3：title 解析为 documentTitle 与 chunkIndex。
     *
     * title「RAG架构.pdf#3」→ documentTitle="RAG架构.pdf", chunkIndex=3
     */
    @Test
    fun search_title_parsed_to_source() {
        repository.addChunk(
            KnowledgeChunk(
                title = "RAG架构.pdf#3",
                content = "分块内容",
                embedding = oneHot(0),
                knowledgeBaseId = 0L
            )
        )

        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("title 原文应保留", "RAG架构.pdf#3", results[0].title)
        assertEquals("documentTitle 应为 # 左侧", "RAG架构.pdf", results[0].documentTitle)
        assertEquals("chunkIndex 应为 3", 3, results[0].chunkIndex)
    }

    /**
     * AC-3：title 含 # 的文档名解析（如「C#入门.pdf#1」）。
     *
     * lastIndexOf('#') 取最后一个 #，documentTitle="C#入门.pdf", chunkIndex=1
     */
    @Test
    fun search_title_with_hash_in_docname() {
        repository.addChunk(
            KnowledgeChunk(
                title = "C#入门.pdf#1",
                content = "C# 分块",
                embedding = oneHot(0),
                knowledgeBaseId = 0L
            )
        )

        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("documentTitle 应保留文件名中的 #", "C#入门.pdf", results[0].documentTitle)
        assertEquals("chunkIndex 应为 1", 1, results[0].chunkIndex)
    }

    /**
     * AC-3：title 不含 # 时 documentTitle=title 原文，chunkIndex=null（容错降级）。
     */
    @Test
    fun search_title_no_hash_returns_title_and_null_index() {
        repository.addChunk(
            KnowledgeChunk(
                title = "无序号标题",
                content = "内容",
                embedding = oneHot(0),
                knowledgeBaseId = 0L
            )
        )

        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("documentTitle 应等于 title 原文", "无序号标题", results[0].documentTitle)
        assertNull("chunkIndex 应为 null", results[0].chunkIndex)
    }

    /**
     * AC-3：RetrievalResult 含完整字段（chunkId/content/title/similarity/documentTitle/chunkIndex/knowledgeBaseId）。
     */
    @Test
    fun search_result_contains_all_required_fields() {
        repository.addChunk(
            KnowledgeChunk(
                title = "文档.pdf#2",
                content = "分块内容",
                embedding = oneHot(0),
                knowledgeBaseId = 1L
            )
        )

        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        val r = results[0]
        assertTrue("chunkId 应 > 0", r.chunkId > 0)
        assertEquals("content 应匹配", "分块内容", r.content)
        assertEquals("title 应匹配", "文档.pdf#2", r.title)
        assertTrue("similarity 应 ∈ [-1,1]", r.similarity in -1.0..1.0)
        assertEquals("documentTitle 应匹配", "文档.pdf", r.documentTitle)
        assertEquals("chunkIndex 应为 2", 2, r.chunkIndex)
        assertEquals("knowledgeBaseId 应为 1L", 1L, r.knowledgeBaseId)
    }

    /**
     * AC-3 边界：title 序号为 0 时 chunkIndex=null（parseTitle 容错降级，guardrail L1）。
     *
     * IngestionPipeline 用 `index+1` 从 1 开始，序号 0 非法，应降级为 null。
     */
    @Test
    fun search_title_chunk_index_zero_returns_null() {
        repository.addChunk(
            KnowledgeChunk(title = "文档#0", content = "内容", embedding = oneHot(0), knowledgeBaseId = 0L)
        )
        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("documentTitle 应为 # 左侧", "文档", results[0].documentTitle)
        assertNull("序号 0 非正整数，chunkIndex 应为 null", results[0].chunkIndex)
    }

    /**
     * AC-3 边界：title 序号为负数时 chunkIndex=null（parseTitle 容错降级，guardrail L1）。
     */
    @Test
    fun search_title_chunk_index_negative_returns_null() {
        repository.addChunk(
            KnowledgeChunk(title = "文档#-1", content = "内容", embedding = oneHot(0), knowledgeBaseId = 0L)
        )
        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertNull("负数序号，chunkIndex 应为 null", results[0].chunkIndex)
    }

    /**
     * AC-3 边界：title 序号为非数字时 chunkIndex=null（parseTitle 容错降级，guardrail L1）。
     */
    @Test
    fun search_title_chunk_index_non_numeric_returns_null() {
        repository.addChunk(
            KnowledgeChunk(title = "文档#abc", content = "内容", embedding = oneHot(0), knowledgeBaseId = 0L)
        )
        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertNull("非数字序号，chunkIndex 应为 null", results[0].chunkIndex)
    }

    /**
     * AC-3 边界：反向向量 similarity 为负值（guardrail L2）。
     *
     * chunk A = oneHot(0) = [1,0,...]，query = [-1,0,...]（与 A 反向）。
     * cos(θ) = -1，distance = 1 - (-1) = 2，similarity = 1 - 2 = -1。
     */
    @Test
    fun search_opposite_vector_similarity_negative() {
        repository.addChunk(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))

        val oppositeVector = FloatArray(384)
        oppositeVector[0] = -1.0f

        val results = repository.search(oppositeVector, k = 1)
        assertEquals(1, results.size)
        assertTrue("反向向量 similarity 应为负值（实际 ${results[0].similarity}）", results[0].similarity < 0)
        assertEquals("反向向量 similarity 应 ≈ -1.0", -1.0, results[0].similarity, 1e-5)
    }

    // ==================== 边界与防御 ====================

    /**
     * 防御：query 维度 != 384 抛 IllegalArgumentException（ADR-010 5.6）。
     */
    @Test(expected = IllegalArgumentException::class)
    fun search_dimension_mismatch_throws() {
        val shortVector = FloatArray(2)
        shortVector[0] = 1.0f
        repository.search(shortVector, k = 5)
    }

    /**
     * 防御：k=0 抛 IllegalArgumentException（ADR-010 5.5）。
     */
    @Test(expected = IllegalArgumentException::class)
    fun search_k_zero_throws() {
        repository.search(oneHot(0), k = 0)
    }

    /**
     * 防御：k 负数抛 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException::class)
    fun search_k_negative_throws() {
        repository.search(oneHot(0), k = -1)
    }

    /**
     * 防御：knowledgeBaseId 负数抛 IllegalArgumentException（ADR-010 5.6）。
     */
    @Test(expected = IllegalArgumentException::class)
    fun search_knowledgeBaseId_negative_throws() {
        repository.search(oneHot(0), k = 5, knowledgeBaseId = -1L)
    }

    /**
     * 边界：k 超量返回该库全部可用 chunk（不跨库补足）。
     */
    @Test
    fun search_k_greater_than_available_returns_all() {
        repository.addChunk(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "B", content = "b", embedding = oneHot(1), knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 10)
        assertEquals("k>可用量应返回全部 2 条", 2, results.size)
    }

    /**
     * 边界：k=1 返回单条最相似。
     */
    @Test
    fun search_k_one_returns_single_most_similar() {
        repository.addChunk(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "B", content = "b", embedding = oneHot(1), knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 1)
        assertEquals("k=1 应返回 1 条", 1, results.size)
        assertEquals("应返回最相似的 A", "A", results[0].title)
    }

    /**
     * 资源管理：多次检索无 native 句柄泄漏（间接验证 Query.use{} 关闭）。
     *
     * 若 Query 未 close，多次检索后可能因 native 句柄耗尽抛异常或性能退化。
     * 本测试连续执行 50 次检索，验证无异常。
     */
    @Test
    fun search_multiple_invocations_no_leak() {
        repository.addChunk(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))

        repeat(50) { i ->
            val results = repository.search(oneHot(0), k = 1)
            assertEquals("第 $i 次检索应返回 1 条", 1, results.size)
        }
    }

    /**
     * 集成：search 与 chunkCount/addChunk 既有方法协同工作。
     *
     * 验证检索不破坏既有写入与计数功能。
     */
    @Test
    fun search_coexists_with_existing_methods() {
        repository.addChunk(KnowledgeChunk(title = "A#1", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "A#2", content = "b", embedding = oneHot(1), knowledgeBaseId = 0L))

        assertEquals("chunkCount 应为 2", 2L, repository.chunkCount(0L))

        val results = repository.search(oneHot(0), k = 2)
        assertEquals("search 应返回 2 条", 2, results.size)

        // 检索后 chunkCount 仍正确（检索不修改数据）
        assertEquals("检索后 chunkCount 应仍为 2", 2L, repository.chunkCount(0L))
    }
}
