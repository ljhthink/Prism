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
 * KnowledgeChunk HNSW 向量检索单元测试（US-011 验收标准 4）。
 *
 * 验证基于 [io.objectbox.annotation.HnswIndex] 的 nearestNeighbors 近邻查询：
 * - top-k 按相似度返回正确片段；
 * - query.findWithScores() 返回带分数结果；
 * - embedding 为 null 的记录不参与近邻检索。
 *
 * 使用 [BoxStore.directory] 在临时目录构建纯 JVM ObjectBox，
 * 与 [KnowledgeChunkCrudTest] 同模式。
 */
class KnowledgeChunkVectorSearchTest {

    private lateinit var boxStore: BoxStore
    private lateinit var box: Box<KnowledgeChunk>
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-vector-").toFile()
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

    @Test
    fun nearestNeighbors_returns_topk_by_similarity() {
        // 三个方向不同的 384 维向量
        box.put(KnowledgeChunk(title = "文档A", content = "关于协程", embedding = oneHot(0)))
        box.put(KnowledgeChunk(title = "文档B", content = "关于网络", embedding = oneHot(50)))
        box.put(KnowledgeChunk(title = "文档C", content = "关于存储", embedding = oneHot(100)))

        // 查询向量接近文档A 方向
        val queryVector = FloatArray(384)
        queryVector[0] = 0.9f
        queryVector[1] = 0.1f

        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 3)
        ).build()
        val matches = query.findWithScores()

        assertEquals("应返回 top-3", 3, matches.size)
        assertEquals("第一个结果应为文档A", "文档A", matches[0].get().title)
        // ObjectBox COSINE 返回距离分数：值越低越相似，故最相似结果分数最低
        assertTrue("文档A 距离分数应最低（最相似）", matches[0].getScore() < matches[1].getScore())
    }

    @Test
    fun nearestNeighbors_embedding_null_excluded() {
        box.put(KnowledgeChunk(title = "有向量", content = "已嵌入", embedding = oneHot(0)))
        // embedding 为 null：仅文本入库，不建向量
        box.put(KnowledgeChunk(title = "无向量", content = "未嵌入", embedding = null))

        val queryVector = oneHot(0)
        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5)
        ).build()
        val matches = query.findWithScores()

        assertEquals("仅应返回有向量的记录", 1, matches.size)
        assertEquals("结果应为有向量记录", "有向量", matches[0].get().title)
    }

    @Test
    fun nearestNeighbors_empty_box_returns_empty() {
        val queryVector = oneHot(0)
        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 5)
        ).build()
        val matches = query.findWithScores()

        assertTrue("空库应返回空结果", matches.isEmpty())
    }

    @Test
    fun nearestNeighbors_k_honors_limit() {
        box.put(KnowledgeChunk(title = "A", content = "a", embedding = oneHot(0)))
        box.put(KnowledgeChunk(title = "B", content = "b", embedding = oneHot(1)))
        box.put(KnowledgeChunk(title = "C", content = "c", embedding = oneHot(2)))
        box.put(KnowledgeChunk(title = "D", content = "d", embedding = oneHot(3)))

        val queryVector = oneHot(0)
        val query = box.query(
            KnowledgeChunk_.embedding.nearestNeighbors(queryVector, 2)
        ).build()
        val matches = query.findWithScores()

        assertEquals("k=2 应只返回 2 条", 2, matches.size)
    }
}