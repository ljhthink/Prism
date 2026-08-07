package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * US-017 向量检索极端/边缘场景补充测试（ac-verifier 补充）。
 *
 * 针对主 Agent 自问盲区：
 * 1. **正交向量场景**（主 Agent 最没把握）：查询与库内所有 chunk 完全正交时 HNSW 是否返回 < k 条
 * 2. **反向向量 similarity 中间值**（主 Agent 遗憾）：构造 120° 夹角（distance=1.5, similarity=-0.5）
 * 3. **跨库排序语义**（主 Agent 遗憾）：全库检索不同库 chunk 按相似度统一排序
 * 4. **k=Int.MAX_VALUE 极端值**
 * 5. **空 query 向量（全 0）**：零向量模为 0，COSINE 未定义行为探测
 * 6. **parseTitle 边界**（# 在首位/末尾，补充 guardrail L1 已覆盖的序号边界之外的 # 位置边界）
 * 7. **kbId 越界**（Long.MAX_VALUE / Long.MIN_VALUE）
 *
 * 关联 ADR：ADR-010 风险表（HNSW 近似性 + 无阈值过滤）
 * 关联 guardrail：L1~L5（已处理）
 * 关联测试：[KnowledgeBaseRetrievalTest]（24 基础用例）
 */
class KnowledgeBaseRetrievalEdgeCaseTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: KnowledgeBaseRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-retrieval-edge-").toFile()
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

    // ==================== 正交向量场景（主 Agent 最没把握） ====================

    /**
     * 正交向量场景：查询向量与库内所有 chunk 完全正交。
     *
     * 查询 oneHot(0)，库内 7 条 chunk 用 oneHot(1)~oneHot(7)（全部与查询正交，
     * distance=1.0, similarity=0.0）。
     *
     * ADR-010 风险表：HNSW 是近似最近邻算法，查询向量与库内向量正交时，
     * 可能不返回全部 k 条匹配。本测试验证实际行为（不假设 results.size == k）。
     *
     * 关键验证：
     * 1. 不抛异常（HNSW 处理正交向量不崩溃）
     * 2. 返回的结果（若有）similarity ≈ 0.0（正交）
     * 3. 返回数量 <= k（HNSW 近似性可能 < k）
     */
    @Test
    fun search_orthogonal_vectors_does_not_crash() {
        for (i in 1..7) {
            repository.addChunk(
                KnowledgeChunk(
                    title = "正交#$i",
                    content = "正交内容$i",
                    embedding = oneHot(i),
                    knowledgeBaseId = 0L
                )
            )
        }

        val results = repository.search(oneHot(0), k = 5)

        // 关键验证 1：不抛异常（到这里说明没抛）
        // 关键验证 2：返回数量 <= k
        assertTrue(
            "正交向量检索返回数应 <= k=5（实际 ${results.size}，HNSW 近似性可能 < k）",
            results.size <= 5
        )

        // 关键验证 3：返回的结果（若有）similarity ≈ 0.0
        results.forEach { r ->
            assertTrue(
                "正交结果 similarity 应 ≈ 0.0（实际 ${r.similarity}）",
                abs(r.similarity) < 0.01
            )
        }

        println("EDGE_CASE: 正交向量场景 k=5, 库内 7 条正交 chunk, 实际返回 ${results.size} 条")
    }

    /**
     * 正交向量 + 有效匹配混合：查询向量与部分 chunk 正交、部分同向。
     *
     * 验证 HNSW 优先返回同向 chunk（similarity=1.0），正交 chunk 可能不返回。
     */
    @Test
    fun search_mixed_orthogonal_and_aligned_prioritizes_aligned() {
        // 3 条同向（direction 0，与查询同向 similarity=1.0）
        for (i in 1..3) {
            repository.addChunk(
                KnowledgeChunk(title = "同向#$i", content = "同向$i", embedding = oneHot(0), knowledgeBaseId = 0L)
            )
        }
        // 4 条正交（direction 1~4，与查询正交 similarity=0.0）
        for (i in 1..4) {
            repository.addChunk(
                KnowledgeChunk(title = "正交#$i", content = "正交$i", embedding = oneHot(i), knowledgeBaseId = 0L)
            )
        }

        val results = repository.search(oneHot(0), k = 5)

        assertTrue("应返回 <= 5 条", results.size <= 5)
        // 前 3 条应为同向（similarity ≈ 1.0）
        val alignedCount = results.count { it.similarity > 0.99 }
        assertTrue("同向 chunk 应被优先返回（至少 3 条，实际 $alignedCount）", alignedCount >= 3)
    }

    // ==================== 反向向量 similarity 中间值（主 Agent 遗憾） ====================

    /**
     * 反向向量 similarity 中间值：构造夹角 120°（cos(θ)=-0.5，distance=1.5，similarity=-0.5）。
     *
     * 数学推导：
     * - query = oneHot(0) = [1.0, 0.0, 0.0, ...]（模=1.0）
     * - chunk = [-0.5, √3/2, 0.0, ...]（模=√(0.25+0.75)=1.0，已归一化）
     * - cos(θ) = query · chunk = 1.0×(-0.5) + 0.0×(√3/2) = -0.5
     * - distance = 1 - cos(θ) = 1 - (-0.5) = 1.5
     * - similarity = 1 - distance = 1 - 1.5 = -0.5
     *
     * 补充主 Agent 既有测试 search_opposite_vector_similarity_negative（仅覆盖 similarity≈-1.0 端点）
     * 的中间值路径。
     */
    @Test
    fun search_oblique_120_degrees_similarity_negative_half() {
        val chunkEmbedding = FloatArray(384)
        chunkEmbedding[0] = -0.5f
        chunkEmbedding[1] = (sqrt(3.0) / 2.0).toFloat()

        repository.addChunk(
            KnowledgeChunk(
                title = "120度#1",
                content = "120度夹角内容",
                embedding = chunkEmbedding,
                knowledgeBaseId = 0L
            )
        )

        val results = repository.search(oneHot(0), k = 1)
        assertEquals("应返回 1 条", 1, results.size)

        val actualSim = results[0].similarity
        println("EDGE_CASE: 120度夹角 similarity=$actualSim (预期 ≈ -0.5)")

        assertEquals(
            "120度夹角 similarity 应 ≈ -0.5（实际 $actualSim）",
            -0.5,
            actualSim,
            0.01
        )
        assertTrue("similarity 应为负值", actualSim < 0)
    }

    /**
     * 反向向量 similarity 中间值：构造夹角 60°（cos(θ)=0.5，distance=0.5，similarity=0.5）。
     *
     * - query = oneHot(0) = [1.0, 0.0, ...]
     * - chunk = [0.5, √3/2, 0.0, ...]（模=1.0）
     * - cos(θ) = 0.5, distance = 0.5, similarity = 0.5
     */
    @Test
    fun search_oblique_60_degrees_similarity_positive_half() {
        val chunkEmbedding = FloatArray(384)
        chunkEmbedding[0] = 0.5f
        chunkEmbedding[1] = (sqrt(3.0) / 2.0).toFloat()

        repository.addChunk(
            KnowledgeChunk(
                title = "60度#1",
                content = "60度夹角内容",
                embedding = chunkEmbedding,
                knowledgeBaseId = 0L
            )
        )

        val results = repository.search(oneHot(0), k = 1)
        assertEquals("应返回 1 条", 1, results.size)

        val actualSim = results[0].similarity
        println("EDGE_CASE: 60度夹角 similarity=$actualSim (预期 ≈ 0.5)")

        assertEquals(
            "60度夹角 similarity 应 ≈ 0.5（实际 $actualSim）",
            0.5,
            actualSim,
            0.01
        )
        assertTrue("similarity 应为正值", actualSim > 0)
    }

    // ==================== 跨库排序语义（主 Agent 遗憾） ====================

    /**
     * 跨库排序语义：全库检索时，不同库的 chunk 按相似度统一排序（非按库分组）。
     *
     * 构造：
     * - 默认库 chunk A: oneHot(0)（与 query oneHot(0) 同向 similarity=1.0）
     * - 自建库 chunk B: oneHot(0)（与 query oneHot(0) 同向 similarity=1.0）
     * - 默认库 chunk C: oneHot(5)（与 query oneHot(0) 正交 similarity=0.0）
     * - 自建库 chunk D: oneHot(5)（与 query oneHot(0) 正交 similarity=0.0）
     *
     * 全库检索 k=4，结果应按相似度统一排序：
     * A 和 B 在前（similarity≈1.0），C 和 D 在后（similarity≈0.0）
     * 关键验证：前 2 条来自不同库 → 证明跨库混合排序
     */
    @Test
    fun search_cross_kb_unified_similarity_sorting() {
        repository.addChunk(KnowledgeChunk(title = "默认_A#1", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "默认_C#3", content = "c", embedding = oneHot(5), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "自建_B#1", content = "b", embedding = oneHot(0), knowledgeBaseId = 1L))
        repository.addChunk(KnowledgeChunk(title = "自建_D#3", content = "d", embedding = oneHot(5), knowledgeBaseId = 1L))

        val results = repository.search(oneHot(0), k = 4, knowledgeBaseId = null)
        assertEquals("全库应返回 4 条", 4, results.size)

        // 前 2 条应比后 2 条更相似
        val secondSim = results[1].similarity
        val thirdSim = results[2].similarity
        assertTrue("前 2 条应比后 2 条更相似（第2项 $secondSim > 第3项 $thirdSim）", secondSim > thirdSim)

        // 关键验证：前 2 条来自不同库 → 跨库混合排序（非按库分组）
        val top2KbIds = setOf(results[0].knowledgeBaseId, results[1].knowledgeBaseId)
        assertTrue("前 2 条应来自不同库（跨库混合排序），实际 kbIds=$top2KbIds", top2KbIds.size > 1)

        // 后 2 条也应来自不同库
        val bottom2KbIds = setOf(results[2].knowledgeBaseId, results[3].knowledgeBaseId)
        assertTrue("后 2 条应来自不同库（跨库混合排序），实际 kbIds=$bottom2KbIds", bottom2KbIds.size > 1)

        // 全部相似度降序
        for (i in 0 until results.size - 1) {
            assertTrue(
                "第 $i 项相似度应 >= 第 ${i + 1} 项",
                results[i].similarity >= results[i + 1].similarity
            )
        }
    }

    // ==================== k=Int.MAX_VALUE 极端值 ====================

    /**
     * k=Int.MAX_VALUE：应返回全部可用 chunk，不溢出不报错。
     */
    @Test
    fun search_k_max_value_returns_all_available() {
        repository.addChunk(KnowledgeChunk(title = "A#1", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "B#2", content = "b", embedding = oneHot(1), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "C#3", content = "c", embedding = oneHot(2), knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = Int.MAX_VALUE)
        assertEquals("k=MAX_VALUE 应返回全部 3 条", 3, results.size)
    }

    // ==================== 空 query 向量（全 0） ====================

    /**
     * 空 query 向量（全 0）：零向量模为 0，COSINE 相似度未定义。
     *
     * 代码仅校验 query.size==384，不校验零向量。ObjectBox 对零向量的行为未定义。
     * 本测试验证实际行为（不崩溃），记录返回结果用于风险分析。
     *
     * 若 ObjectBox 抛异常 → 标记为缺陷（search 应增加零向量校验）
     * 若返回结果含 NaN similarity → 标记为风险（调用方需处理）
     */
    @Test
    fun search_zero_vector_query_does_not_crash() {
        repository.addChunk(KnowledgeChunk(title = "A#1", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))
        repository.addChunk(KnowledgeChunk(title = "B#2", content = "b", embedding = oneHot(1), knowledgeBaseId = 0L))

        val zeroVector = FloatArray(384)
        val results = repository.search(zeroVector, k = 5)

        println("EDGE_CASE: 零向量查询 k=5, 库内 2 条, 返回 ${results.size} 条")
        results.forEach { r ->
            println("  结果: title=${r.title}, similarity=${r.similarity}")
        }

        assertTrue("零向量查询返回数应 <= k=5", results.size <= 5)

        // 检查是否有 NaN（风险标记，不标记为失败——这是 ObjectBox 未定义行为）
        val hasNaN = results.any { r -> r.similarity.isNaN() }
        if (hasNaN) {
            println("EDGE_CASE_WARNING: 零向量查询结果含 NaN similarity，调用方需处理")
        }
    }

    // ==================== parseTitle 边界（# 在首位/末尾） ====================

    /**
     * parseTitle 边界：title="#1"（# 在首位），documentTitle="#1", chunkIndex=null。
     *
     * idx=0, `idx <= 0` → true → 返回 `title to null`。
     */
    @Test
    fun search_title_hash_at_start_returns_title_and_null() {
        repository.addChunk(
            KnowledgeChunk(title = "#1", content = "内容", embedding = oneHot(0), knowledgeBaseId = 0L)
        )
        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("documentTitle 应为 #1 原文", "#1", results[0].documentTitle)
        assertNull("chunkIndex 应为 null（#在首位）", results[0].chunkIndex)
    }

    /**
     * parseTitle 边界：title="doc#"（# 在末尾），documentTitle="doc#", chunkIndex=null。
     *
     * idx=3, title.length=4, `idx >= title.length - 1` (3>=3) → true → 返回 `title to null`。
     */
    @Test
    fun search_title_hash_at_end_returns_title_and_null() {
        repository.addChunk(
            KnowledgeChunk(title = "doc#", content = "内容", embedding = oneHot(0), knowledgeBaseId = 0L)
        )
        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("documentTitle 应为 doc# 原文", "doc#", results[0].documentTitle)
        assertNull("chunkIndex 应为 null（#在末尾）", results[0].chunkIndex)
    }

    /**
     * parseTitle 边界：title 为空串，documentTitle="", chunkIndex=null。
     */
    @Test
    fun search_title_empty_string_returns_empty_and_null() {
        repository.addChunk(
            KnowledgeChunk(title = "", content = "内容", embedding = oneHot(0), knowledgeBaseId = 0L)
        )
        val results = repository.search(oneHot(0), k = 1)
        assertEquals(1, results.size)
        assertEquals("documentTitle 应为空串", "", results[0].documentTitle)
        assertNull("chunkIndex 应为 null（空 title）", results[0].chunkIndex)
    }

    // ==================== kbId 越界 ====================

    /**
     * kbId 越界：Long.MAX_VALUE（不存在的库，应返回空，不溢出）。
     */
    @Test
    fun search_kb_id_max_value_returns_empty() {
        repository.addChunk(KnowledgeChunk(title = "A#1", content = "a", embedding = oneHot(0), knowledgeBaseId = 0L))

        val results = repository.search(oneHot(0), k = 5, knowledgeBaseId = Long.MAX_VALUE)
        assertTrue("Long.MAX_VALUE 库应返回空", results.isEmpty())
    }

    /**
     * kbId 越界：Long.MIN_VALUE（负数，抛 IllegalArgumentException）。
     */
    @Test(expected = IllegalArgumentException::class)
    fun search_kb_id_min_value_throws() {
        repository.search(oneHot(0), k = 5, knowledgeBaseId = Long.MIN_VALUE)
    }
}
