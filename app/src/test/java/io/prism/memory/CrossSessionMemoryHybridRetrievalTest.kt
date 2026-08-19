package io.prism.memory

import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.MyObjectBox
import io.prism.embedding.Embedder
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * v1 US-102：混合检索集成测试（FTS5 BM25 + 向量 → RRF(k=60) 融合）。
 *
 * **核心验证**：仅靠关键词命中的记忆（向量相似度 < 阈值 0.4 被过滤）经 RRF 关键词
 * 路径仍能被检索注入——解决「精确词句向量相似度不足但关键词命中」的召回短板。
 *
 * **向量可控**：用 [QueryAlignedEmbedder] 返回固定查询向量 Q，记录嵌入分别构造为
 * 与 Q 正交（相似度 0，被向量阈值过滤）和与 Q 同向（相似度 1，通过向量阈值）。
 */
class CrossSessionMemoryHybridRetrievalTest {

    private lateinit var boxStore: BoxStore
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var keywordIndex: InMemoryMemoryKeywordIndex

    @Before
    fun setUp() {
        val tempDir = Files.createTempDirectory("prism-hybrid-test").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        memoryRepository = MemoryRepository(boxStore)
        keywordIndex = InMemoryMemoryKeywordIndex()
    }

    @After
    fun tearDown() {
        boxStore.close()
    }

    @Test
    fun `hybrid retrieval recovers keyword only memory filtered by vector threshold`() = runBlocking {
        // 查询向量 Q（维度 0 激活）
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        // 记录 A：与 Q 正交（相似度 0 → 被 0.4 向量阈值过滤），但内容含关键词
        val recordA = MemoryRecord(
            id = 0, sessionId = "s1", content = "用户上次说过 Kotlin 协程的知识",
            embedding = FloatArray(384) { if (it == 1) 1f else 0f },
            timestamp = System.currentTimeMillis(), turnCount = 1
        )
        // 记录 B：与 Q 同向（相似度 1 → 通过向量阈值），内容不含关键词
        val recordB = MemoryRecord(
            id = 0, sessionId = "s1", content = "用户喜欢跑步健身",
            embedding = queryVector,
            timestamp = System.currentTimeMillis(), turnCount = 2
        )
        memoryRepository.save(recordA)
        memoryRepository.save(recordB)

        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            keywordIndex = keywordIndex
        )

        val results = manager.retrieveRelevantMemories("Kotlin 协程", topK = 5)

        assertTrue("混合检索应命中关键词记忆 A", results.any { it.content.contains("Kotlin 协程") })
        assertTrue("混合检索应命中向量记忆 B", results.any { it.content.contains("跑步健身") })
        assertTrue("结果数 ≤ topK", results.size <= 5)
    }

    @Test
    fun `hybrid retrieval without keywordIndex stays pure vector`() = runBlocking {
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val recordA = MemoryRecord(
            id = 0, sessionId = "s1", content = "用户喜欢 Kotlin 协程",
            embedding = FloatArray(384) { if (it == 1) 1f else 0f }, // 正交 → 被过滤
            timestamp = System.currentTimeMillis(), turnCount = 1
        )
        memoryRepository.save(recordA)

        // 未注入 keywordIndex → 纯向量路径，关键词记忆被阈值过滤
        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4
        )
        val results = manager.retrieveRelevantMemories("Kotlin 协程", topK = 5)
        assertTrue("纯向量路径应过滤关键词记忆", results.isEmpty())
    }

    @Test
    fun `retrieval increments accessCount on hits`() = runBlocking {
        val queryVector = FloatArray(384) { if (it == 0) 1f else 0f }
        val record = MemoryRecord(
            id = 0, sessionId = "s1", content = "用户喜欢 Kotlin 协程",
            embedding = queryVector, // 与查询同向 → 通过
            timestamp = System.currentTimeMillis(), turnCount = 1
        )
        val savedId = memoryRepository.save(record)

        val manager = CrossSessionMemoryManager(
            embedder = QueryAlignedEmbedder(queryVector),
            memoryRepository = memoryRepository,
            retrievalThreshold = 0.4,
            keywordIndex = keywordIndex
        )
        manager.retrieveRelevantMemories("Kotlin", topK = 5)
        // accessCount 自增（但不触发 mutationVersion 变化）
        val updated = memoryRepository.all().first { it.id == savedId }
        assertEquals("命中后 accessCount 应自增", 1L, updated.accessCount)
    }

    /**
     * 固定返回查询向量的 Embedder（查询侧向量可控）。
     */
    private class QueryAlignedEmbedder(
        private val vector: FloatArray
    ) : Embedder {
        override fun embed(text: String): FloatArray = vector.copyOf()
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
