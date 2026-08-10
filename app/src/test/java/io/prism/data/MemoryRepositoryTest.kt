package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * MemoryRepository CRUD + 向量检索单元测试（US-030，ADR-015 5.1）。
 *
 * **验证内容**：
 * 1. MemoryRecord 持久化到 ObjectBox（含 @HnswIndex 向量索引）
 * 2. save / getBySession / searchByVector / deleteBySession / deleteAll / count 全方法覆盖
 * 3. searchByVector 复用 M3 ObjectBox 向量搜索基建（nearestNeighbors + findWithScores）
 * 4. 检索结果按相似度降序，相似度 = 1 - COSINE 距离 ∈ [-1, 1]
 * 5. 维度校验 fail-fast（query.size != 384 抛 IllegalArgumentException）
 * 6. HNSW 删除规避 #1209（findIds + Box.remove 模式）
 *
 * **测试策略**（BR-testing-004，复用 M3 [KnowledgeBaseRetrievalTest] 模式）：
 * - 真实 ObjectBox（`MyObjectBox.builder().directory(tempDir).build()`），无 mock
 * - oneHot 向量控制相似度（384 维，[dominantIndex] 位置为 1.0，其余 0.0）
 * - 不依赖 Android Context（ObjectBox directory 模式可在纯 JVM 运行）
 *
 * 关联 ADR：[ADR-015](../../docs/decisions/ADR-015-m5-memory-system-architecture.md)
 */
class MemoryRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: MemoryRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "memory-repo-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = MemoryRepository(boxStore)
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

    // ==================== AC-1：MemoryRecord @Entity 持久化 ====================

    @Test
    fun save_assigns_positive_id() {
        val record = MemoryRecord(
            sessionId = "session-1",
            content = "用户询问了 Kotlin 协程的用法",
            embedding = oneHot(0),
            timestamp = 1_000L,
            turnCount = 1
        )
        val id = repository.save(record)
        assertTrue("应分配正数 id", id > 0)
    }

    @Test
    fun save_persists_all_fields() {
        val original = MemoryRecord(
            sessionId = "session-2",
            content = "AI 回答了关于 Compose 状态管理的内容",
            embedding = oneHot(1),
            timestamp = 2_000L,
            turnCount = 3
        )
        val id = repository.save(original)

        val records = repository.getBySession("session-2")
        assertEquals("应返回 1 条记录", 1, records.size)
        val retrieved = records[0]
        assertEquals(id, retrieved.id)
        assertEquals("session-2", retrieved.sessionId)
        assertEquals("AI 回答了关于 Compose 状态管理的内容", retrieved.content)
        assertTrue("embedding 应正确往返", retrieved.embedding?.contentEquals(oneHot(1)) == true)
        assertEquals(2_000L, retrieved.timestamp)
        assertEquals(3, retrieved.turnCount)
    }

    @Test
    fun save_with_null_embedding_succeeds() {
        // embedding=null 支持「先入库文本后补充向量」的两阶段写入
        val record = MemoryRecord(
            sessionId = "session-3",
            content = "未向量化的记忆片段",
            embedding = null,
            timestamp = 3_000L,
            turnCount = 1
        )
        val id = repository.save(record)
        assertTrue("null embedding 也应成功保存", id > 0)
    }

    // ==================== AC-2：getBySession ====================

    @Test
    fun getBySession_returns_records_sorted_by_timestamp_asc() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "第三轮", embedding = oneHot(2),
                timestamp = 3_000L, turnCount = 3
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "第一轮", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "第二轮", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 2
            )
        )

        val records = repository.getBySession("s1")
        assertEquals("应返回 3 条记录", 3, records.size)
        assertEquals("应按 timestamp 升序", "第一轮", records[0].content)
        assertEquals("应按 timestamp 升序", "第二轮", records[1].content)
        assertEquals("应按 timestamp 升序", "第三轮", records[2].content)
    }

    @Test
    fun getBySession_filters_by_session_id() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "会话1内容", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s2", content = "会话2内容", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 1
            )
        )

        val s1Records = repository.getBySession("s1")
        assertEquals("s1 应返回 1 条", 1, s1Records.size)
        assertEquals("会话1内容", s1Records[0].content)

        val s2Records = repository.getBySession("s2")
        assertEquals("s2 应返回 1 条", 1, s2Records.size)
        assertEquals("会话2内容", s2Records[0].content)
    }

    @Test
    fun getBySession_returns_empty_for_nonexistent_session() {
        val records = repository.getBySession("nonexistent")
        assertTrue("不存在的 session 应返回空 list", records.isEmpty())
    }

    // ==================== AC-3：searchByVector 向量检索 ====================

    @Test
    fun searchByVector_returns_topk_with_default_k_3() {
        for (i in 0 until 5) {
            repository.save(
                MemoryRecord(
                    sessionId = "s$i",
                    content = "记忆#$i",
                    embedding = oneHot(i),
                    timestamp = i.toLong() * 1_000L,
                    turnCount = i
                )
            )
        }

        val results = repository.searchByVector(oneHot(0))
        assertEquals("默认 topK=3 应返回 3 条", 3, results.size)
    }

    @Test
    fun searchByVector_topk_configurable() {
        for (i in 0 until 5) {
            repository.save(
                MemoryRecord(
                    sessionId = "s$i",
                    content = "记忆#$i",
                    embedding = oneHot(i),
                    timestamp = i.toLong() * 1_000L,
                    turnCount = i
                )
            )
        }

        val results = repository.searchByVector(oneHot(0), topK = 2)
        assertEquals("topK=2 应返回 2 条", 2, results.size)
    }

    @Test
    fun searchByVector_returns_results_sorted_by_similarity_desc() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "A", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s2", content = "B", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s3", content = "C", embedding = oneHot(2),
                timestamp = 3_000L, turnCount = 1
            )
        )

        // 查询向量在 0/1/2 维递减，与 oneHot(0/1/2) 的 cos(θ) 严格递减
        val query = FloatArray(384)
        query[0] = 0.4f
        query[1] = 0.3f
        query[2] = 0.2f

        val results = repository.searchByVector(query, topK = 3)
        assertEquals("应返回 3 条", 3, results.size)
        assertEquals("最相似应为 A", "A", results[0].content)
        assertEquals("次相似应为 B", "B", results[1].content)
        assertEquals("最远应为 C", "C", results[2].content)

        // 相似度严格降序
        assertTrue(
            "相似度应严格降序: ${results[0].similarity} > ${results[1].similarity}",
            results[0].similarity > results[1].similarity
        )
        assertTrue(
            "相似度应严格降序: ${results[1].similarity} > ${results[2].similarity}",
            results[1].similarity > results[2].similarity
        )
    }

    @Test
    fun searchByVector_similarity_range_valid() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "完全匹配", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )

        val results = repository.searchByVector(oneHot(0), topK = 1)
        assertEquals(1, results.size)
        // oneHot(0) 与自身 COSINE 距离=0，相似度=1.0
        assertEquals(
            "完全匹配相似度应接近 1.0",
            1.0, results[0].similarity, 0.001
        )
        assertTrue(
            "相似度应在 [-1, 1] 范围内",
            results[0].similarity >= -1.0 && results[0].similarity <= 1.0
        )
    }

    @Test
    fun searchByVector_returns_empty_for_empty_repository() {
        val results = repository.searchByVector(oneHot(0))
        assertTrue("空库应返回空 list", results.isEmpty())
    }

    @Test
    fun searchByVector_excludes_null_embedding_records() {
        // null embedding 的记录不应参与向量检索（HNSW 自动排除）
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "有向量", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s2", content = "无向量", embedding = null,
                timestamp = 2_000L, turnCount = 1
            )
        )

        val results = repository.searchByVector(oneHot(0), topK = 5)
        assertEquals("应仅返回 1 条（排除 null embedding）", 1, results.size)
        assertEquals("有向量", results[0].content)
    }

    @Test
    fun searchByVector_result_fields_populated_correctly() {
        repository.save(
            MemoryRecord(
                sessionId = "session-xyz",
                content = "测试内容",
                embedding = oneHot(5),
                timestamp = 12_345L,
                turnCount = 7
            )
        )

        val results = repository.searchByVector(oneHot(5), topK = 1)
        assertEquals(1, results.size)
        val result = results[0]
        assertEquals("session-xyz", result.sessionId)
        assertEquals("测试内容", result.content)
        assertEquals(12_345L, result.timestamp)
        assertEquals(7, result.turnCount)
        assertTrue("similarity 应已计算", result.similarity > 0.99)
    }

    // ==================== 维度校验（fail-fast，ADR-010 5.6） ====================

    @Test(expected = IllegalArgumentException::class)
    fun searchByVector_throws_for_wrong_dimension() {
        val wrongDim = FloatArray(128) // 错误维度
        wrongDim[0] = 1.0f
        repository.searchByVector(wrongDim)
    }

    @Test(expected = IllegalArgumentException::class)
    fun searchByVector_throws_for_zero_topk() {
        repository.searchByVector(oneHot(0), topK = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun searchByVector_throws_for_negative_topk() {
        repository.searchByVector(oneHot(0), topK = -1)
    }

    // ==================== AC-2：deleteBySession ====================

    @Test
    fun deleteBySession_removes_all_records_for_session() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录1", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录2", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 2
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s2", content = "记录3", embedding = oneHot(2),
                timestamp = 3_000L, turnCount = 1
            )
        )

        val deletedCount = repository.deleteBySession("s1")
        assertEquals("应删除 2 条", 2L, deletedCount)

        // s1 应被清空
        assertTrue("s1 应无记录", repository.getBySession("s1").isEmpty())
        // s2 不受影响
        assertEquals("s2 应保留 1 条", 1, repository.getBySession("s2").size)
    }

    @Test
    fun deleteBySession_returns_zero_for_nonexistent_session() {
        val deletedCount = repository.deleteBySession("nonexistent")
        assertEquals("不存在的 session 应返回 0", 0L, deletedCount)
    }

    // ==================== AC-2：deleteAll ====================

    @Test
    fun deleteAll_removes_all_records() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录1", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s2", content = "记录2", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s3", content = "记录3", embedding = oneHot(2),
                timestamp = 3_000L, turnCount = 1
            )
        )

        val deletedCount = repository.deleteAll()
        assertEquals("应删除 3 条", 3L, deletedCount)
        assertEquals("count 应为 0", 0L, repository.count())
    }

    @Test
    fun deleteAll_returns_zero_for_empty_repository() {
        val deletedCount = repository.deleteAll()
        assertEquals("空库应返回 0", 0L, deletedCount)
    }

    // ==================== count ====================

    @Test
    fun count_returns_total_record_count() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录1", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录2", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 2
            )
        )

        assertEquals("count 应返回 2", 2L, repository.count())
    }

    @Test
    fun count_returns_zero_for_empty_repository() {
        assertEquals("空库 count 应为 0", 0L, repository.count())
    }

    // ==================== StateFlow 订阅 ====================

    @Test
    fun memoryRecords_flow_updates_after_save() {
        assertEquals("初始应为空", 0, repository.memoryRecords.value.size)

        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录1", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )

        assertEquals("save 后应有 1 条", 1, repository.memoryRecords.value.size)
    }

    @Test
    fun memoryRecords_flow_updates_after_delete() {
        repository.save(
            MemoryRecord(
                sessionId = "s1", content = "记录1", embedding = oneHot(0),
                timestamp = 1_000L, turnCount = 1
            )
        )
        repository.save(
            MemoryRecord(
                sessionId = "s2", content = "记录2", embedding = oneHot(1),
                timestamp = 2_000L, turnCount = 1
            )
        )

        repository.deleteBySession("s1")
        assertEquals("删除后应有 1 条", 1, repository.memoryRecords.value.size)
        assertEquals("s2", repository.memoryRecords.value[0].sessionId)
    }

    // ==================== HNSW 删除规避 #1209 验证 ====================

    @Test
    fun deleteBySession_with_hnsw_indexed_records_does_not_throw() {
        // HNSW 索引下的删除必须用 findIds + Box.remove 模式，否则可能抛
        // IllegalStateException: Vector is missing for neighbor to repair（objectbox-java#1209）
        // 本测试验证 deleteBySession 在有 HNSW 索引的记录上能正常执行
        for (i in 0 until 10) {
            repository.save(
                MemoryRecord(
                    sessionId = "s1",
                    content = "记忆#$i",
                    embedding = oneHot(i % 5),
                    timestamp = i.toLong() * 1_000L,
                    turnCount = i
                )
            )
        }

        // 不应抛 IllegalStateException
        val deletedCount = repository.deleteBySession("s1")
        assertEquals("应删除 10 条", 10L, deletedCount)
    }

    @Test
    fun deleteAll_with_hnsw_indexed_records_does_not_throw() {
        // 同上，验证 deleteAll 在 HNSW 索引下不抛 #1209
        for (i in 0 until 10) {
            repository.save(
                MemoryRecord(
                    sessionId = "s$i",
                    content = "记忆#$i",
                    embedding = oneHot(i % 5),
                    timestamp = i.toLong() * 1_000L,
                    turnCount = i
                )
            )
        }

        val deletedCount = repository.deleteAll()
        assertEquals("应删除 10 条", 10L, deletedCount)
    }

    // ==================== L-01 修复验证（AT-01，guardrail 建议补充） ====================

    @Test
    fun equals_two_null_embedding_records_are_equal() {
        // L-01 修复验证：两条 null embedding 的 MemoryRecord（其他字段相同）应为相等
        val record1 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = null, timestamp = 1_000L, turnCount = 1
        )
        val record2 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = null, timestamp = 1_000L, turnCount = 1
        )

        assertTrue("双 null embedding 的相同记录应为相等（L-01 修复）", record1 == record2)
        assertTrue("hashCode 应一致", record1.hashCode() == record2.hashCode())
    }

    @Test
    fun equals_two_nonnull_embedding_records_with_same_content_are_equal() {
        // 非null embedding 场景：内容相同应相等
        val embedding = oneHot(0)
        val record1 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = embedding, timestamp = 1_000L, turnCount = 1
        )
        val record2 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = oneHot(0), timestamp = 1_000L, turnCount = 1
        )

        assertTrue("双非null embedding 且内容相同应为相等", record1 == record2)
    }

    @Test
    fun equals_null_and_nonnull_embedding_records_are_not_equal() {
        // 单 null 场景：一条 null 一条非 null 应不相等
        val record1 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = null, timestamp = 1_000L, turnCount = 1
        )
        val record2 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = oneHot(0), timestamp = 1_000L, turnCount = 1
        )

        assertFalse("单 null 与非 null embedding 应不相等", record1 == record2)
    }

    @Test
    fun equals_two_nonnull_embedding_records_with_different_content_are_not_equal() {
        // 双非null 但内容不同应不相等
        val record1 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = oneHot(0), timestamp = 1_000L, turnCount = 1
        )
        val record2 = MemoryRecord(
            id = 1L, sessionId = "s1", content = "内容",
            embedding = oneHot(1), timestamp = 1_000L, turnCount = 1
        )

        assertFalse("双非null embedding 但内容不同应不相等", record1 == record2)
    }
}
