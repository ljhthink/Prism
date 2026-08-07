package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 探针测试 —— 验证 ObjectBox `nearestNeighbors` 与 `.equal(knowledgeBaseId)` 组合可行性。
 *
 * **背景**（考古报告 TKN-US017-ARCH-001 §5.2）：
 * 全项目 11 处 nearestNeighbors 用法均不带 equal 过滤；3 处 equal(knowledgeBaseId) 全部用于
 * count/findIds。组合用法零先例。ADR-008 风险表假设可「叠加 equal 过滤」但未实证。
 * WebSearch 发现 ObjectBox Dart API 支持 `nearestNeighbors.and(otherCondition)` 组合，
 * 暗示 Java/Kotlin API 应同样支持，但需经验验证。
 *
 * **本探针的目标**：在编码 US-017 检索方法前，确认以下组合返回正确分库 top-k：
 * ```
 * box.query(KnowledgeChunk_.embedding.nearestNeighbors(queryVector, k))
 *     .equal(KnowledgeChunk_.knowledgeBaseId, kbId)
 *     .build()
 *     .findWithScores()
 * ```
 *
 * 若探针失败（抛异常或返回错误结果），US-017 须回退到「全库 top-N + 内存过滤」备选方案，
 * 并在 ADR-010 中记录。
 *
 * 本测试为一次性探针，US-017 实现后可保留作为回归保护（分库检索契约测试）。
 */
class ProbeNearestNeighborsWithEqualTest {

    private lateinit var boxStore: BoxStore
    private lateinit var box: Box<KnowledgeChunk>
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-probe-equal-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        box = boxStore.boxFor(KnowledgeChunk::class.java)
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

    /**
     * 探针 1：分库检索仅返回指定库的 chunk。
     *
     * 构造两个库（kbId=0L 默认库、kbId=1L 自建库），各 3 条 chunk，
     * 用 oneHot 控制相似度。查询 kbId=0L 时应只返回默认库的 3 条。
     */
    @Test
    fun probe_nearestNeighbors_with_equal_returns_only_specified_kb() {
        // 默认库 kbId=0L：3 条 chunk，方向 0/1/2
        box.put(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "默认#2", content = "d2", embedding = oneHot(1), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "默认#3", content = "d3", embedding = oneHot(2), knowledgeBaseId = 0L))
        // 自建库 kbId=1L：3 条 chunk，方向 3/4/5（与查询方向不同，确保不会混入）
        box.put(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(3), knowledgeBaseId = 1L))
        box.put(KnowledgeChunk(title = "自建#2", content = "k2", embedding = oneHot(4), knowledgeBaseId = 1L))
        box.put(KnowledgeChunk(title = "自建#3", content = "k3", embedding = oneHot(5), knowledgeBaseId = 1L))

        // 查询向量在 0/1/2 维有正分量，与默认库 chunk 相似，与自建库 chunk 正交（距离=1.0）
        val queryVector = FloatArray(384)
        queryVector[0] = 0.4f
        queryVector[1] = 0.3f
        queryVector[2] = 0.2f

        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 3)
        ).equal(KnowledgeChunk_.knowledgeBaseId, 0L).build()
        try {
            val matches = query.findWithScores()
            assertEquals("分库检索应只返回默认库的 3 条", 3, matches.size)
            // 全部结果应属于 kbId=0L
            matches.forEach { m ->
                assertEquals("结果应全部来自默认库", 0L, m.get().knowledgeBaseId)
            }
            // 应按距离升序（默认#1 最近，默认#3 最远）
            val titles = matches.map { it.get().title }
            assertEquals("排序应为 默认#1,2,3", listOf("默认#1", "默认#2", "默认#3"), titles)
            // 距离单调递增
            for (i in 0 until matches.size - 1) {
                assertTrue(
                    "第 $i 项距离应 <= 第 ${i + 1} 项",
                    matches[i].getScore() <= matches[i + 1].getScore()
                )
            }
        } finally {
            query.close()
        }
    }

    /**
     * 探针 2：自建库分库检索。
     *
     * 查询 kbId=1L 时应只返回自建库的 3 条。
     */
    @Test
    fun probe_nearestNeighbors_with_equal_returns_self_built_kb() {
        box.put(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "默认#2", content = "d2", embedding = oneHot(1), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(3), knowledgeBaseId = 1L))
        box.put(KnowledgeChunk(title = "自建#2", content = "k2", embedding = oneHot(4), knowledgeBaseId = 1L))
        box.put(KnowledgeChunk(title = "自建#3", content = "k3", embedding = oneHot(5), knowledgeBaseId = 1L))

        // 查询向量在 3/4/5 维有正分量，与自建库 chunk 相似
        val queryVector = FloatArray(384)
        queryVector[3] = 0.4f
        queryVector[4] = 0.3f
        queryVector[5] = 0.2f

        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 3)
        ).equal(KnowledgeChunk_.knowledgeBaseId, 1L).build()
        try {
            val matches = query.findWithScores()
            assertEquals("分库检索应只返回自建库的 3 条", 3, matches.size)
            matches.forEach { m ->
                assertEquals("结果应全部来自自建库", 1L, m.get().knowledgeBaseId)
            }
            val titles = matches.map { it.get().title }
            assertEquals("排序应为 自建#1,2,3", listOf("自建#1", "自建#2", "自建#3"), titles)
        } finally {
            query.close()
        }
    }

    /**
     * 探针 3：分库检索 k 超量返回全部。
     *
     * 指定库只有 2 条，k=5，应返回该库全部 2 条（不跨库补足）。
     */
    @Test
    fun probe_nearestNeighbors_with_equal_k_greater_than_available() {
        box.put(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "默认#2", content = "d2", embedding = oneHot(1), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(3), knowledgeBaseId = 1L))
        box.put(KnowledgeChunk(title = "自建#2", content = "k2", embedding = oneHot(4), knowledgeBaseId = 1L))
        box.put(KnowledgeChunk(title = "自建#3", content = "k3", embedding = oneHot(5), knowledgeBaseId = 1L))

        val queryVector = oneHot(0)
        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5)
        ).equal(KnowledgeChunk_.knowledgeBaseId, 0L).build()
        try {
            val matches = query.findWithScores()
            assertEquals("k>可用量时应只返回默认库的 2 条（不跨库补足）", 2, matches.size)
            matches.forEach { m ->
                assertEquals("结果应全部来自默认库", 0L, m.get().knowledgeBaseId)
            }
        } finally {
            query.close()
        }
    }

    /**
     * 探针 4：分库检索空库返回空。
     *
     * 指定库无任何 chunk，应返回空 list（不跨库返回）。
     */
    @Test
    fun probe_nearestNeighbors_with_equal_empty_kb_returns_empty() {
        box.put(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "默认#2", content = "d2", embedding = oneHot(1), knowledgeBaseId = 0L))

        val queryVector = oneHot(0)
        // 查询不存在的自建库 kbId=999L
        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5)
        ).equal(KnowledgeChunk_.knowledgeBaseId, 999L).build()
        try {
            val matches = query.findWithScores()
            assertTrue("空库分库检索应返回空", matches.isEmpty())
        } finally {
            query.close()
        }
    }

    /**
     * 探针 5：全库检索（不带 equal）跨库返回。
     *
     * 对照组：不带 equal 时应跨库返回所有 chunk。
     */
    @Test
    fun probe_nearestNeighbors_without_equal_returns_cross_kb() {
        box.put(KnowledgeChunk(title = "默认#1", content = "d1", embedding = oneHot(0), knowledgeBaseId = 0L))
        box.put(KnowledgeChunk(title = "自建#1", content = "k1", embedding = oneHot(1), knowledgeBaseId = 1L))

        val queryVector = oneHot(0)
        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5)
        ).build()
        try {
            val matches = query.findWithScores()
            assertEquals("全库检索应跨库返回 2 条", 2, matches.size)
            // 应包含两个库的 chunk
            val kbIds = matches.map { it.get().knowledgeBaseId }.toSet()
            assertTrue("全库结果应包含默认库与自建库", kbIds.contains(0L) && kbIds.contains(1L))
        } finally {
            query.close()
        }
    }
}
