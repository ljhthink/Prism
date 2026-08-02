package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * KnowledgeChunk 极端/边缘场景补充测试（ac-verifier 补充，US-002 验收）。
 *
 * 覆盖主 Agent 基础用例 [KnowledgeChunkCrudTest] 未覆盖的盲区：
 * - 空字符串边界值（空 title / 空 content）
 * - 超长输入（10000 字符 content）
 * - 真实场景向量维度（384 维 all-MiniLM-L6-v2）
 * - 大量数据插入（1000 条，资源边界）
 * - 重启后数据持久化（状态迁移）
 * - 删除后访问（异常路径）
 */
class KnowledgeChunkEdgeCaseTest {

    private lateinit var boxStore: BoxStore
    private lateinit var box: Box<KnowledgeChunk>
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-edge-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        box = boxStore.boxFor(KnowledgeChunk::class.java)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ===== 边界值：空字符串 =====

    @Test
    fun empty_title_and_content_persist_correctly() {
        val chunk = KnowledgeChunk(title = "", content = "")
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertEquals("空 title 应正确持久化", "", retrieved.title)
        assertEquals("空 content 应正确持久化", "", retrieved.content)
    }

    // ===== 边界值：超长输入 =====

    @Test
    fun very_long_content_persists_correctly() {
        val longContent = "a".repeat(10_000)
        val chunk = KnowledgeChunk(title = "超长测试", content = longContent)
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertEquals("超长 content 长度应一致", 10_000, retrieved.content.length)
        assertEquals("超长 content 内容应一致", longContent, retrieved.content)
    }

    @Test
    fun very_long_title_persists_correctly() {
        val longTitle = "标题".repeat(500) // 1000 字符
        val chunk = KnowledgeChunk(title = longTitle, content = "内容")
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertEquals("超长 title 长度应一致", longTitle, retrieved.title)
    }

    // ===== 真实场景向量维度（384 维 all-MiniLM-L6-v2）=====

    @Test
    fun real_384_dim_embedding_round_trip() {
        val embedding = FloatArray(384) { (it % 256) / 256.0f }
        val chunk = KnowledgeChunk(
            title = "向量检索测试",
            content = "all-MiniLM-L6-v2 量化输出",
            embedding = embedding
        )
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertNotNull("384 维 embedding 不应为 null", retrieved.embedding)
        assertEquals("embedding 维度应为 384", 384, retrieved.embedding!!.size)
        assertArrayEquals("384 维 embedding 应精确往返", embedding, retrieved.embedding, 0.0001f)
    }

    // ===== 资源边界：大量数据 =====

    @Test
    fun bulk_insert_1000_chunks() {
        val chunks = (1..1000).map {
            KnowledgeChunk(title = "块$it", content = "内容$it")
        }

        chunks.forEach { box.put(it) }

        assertEquals("1000 条应全部持久化", 1000L, box.count())
    }

    // ===== 状态迁移：重启后持久化 =====

    @Test
    fun data_persists_across_boxstore_restart() {
        val chunk = KnowledgeChunk(title = "持久化测试", content = "重启后应存在")
        val id = box.put(chunk)

        // 关闭并重新打开同一个目录
        boxStore.close()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        box = boxStore.boxFor(KnowledgeChunk::class.java)

        val retrieved = box.get(id)
        assertEquals("重启后 title 应一致", "持久化测试", retrieved.title)
        assertEquals("重启后 content 应一致", "重启后应存在", retrieved.content)
    }

    // ===== 异常路径：删除后访问 =====

    @Test
    fun get_after_remove_returns_not_found() {
        val chunk = KnowledgeChunk(title = "待删", content = "将删除")
        val id = box.put(chunk)
        assertTrue("删除前应存在", box.contains(id))

        box.remove(id)
        assertFalse("删除后 contains 应为 false", box.contains(id))
    }

    @Test
    fun remove_already_removed_id_is_idempotent() {
        val chunk = KnowledgeChunk(title = "幂等测试", content = "重复删除")
        val id = box.put(chunk)
        box.remove(id)

        // 重复删除不应抛出异常
        box.remove(id)
        assertEquals("重复删除后 count 应为 0", 0L, box.count())
    }

    // ===== 精度边界：embedding 浮点精度 =====

    @Test
    fun embedding_extreme_float_values_round_trip() {
        val embedding = floatArrayOf(
            Float.MAX_VALUE,
            Float.MIN_VALUE,
            0.0f,
            -0.0f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        )
        val chunk = KnowledgeChunk(
            title = "极值测试",
            content = "浮点极值往返",
            embedding = embedding
        )
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertNotNull(retrieved.embedding)
        assertEquals("极值 embedding 长度应一致", 7, retrieved.embedding!!.size)
        // NaN 不等于自身，需特殊处理
        assertEquals(Float.MAX_VALUE, retrieved.embedding!![0], 0.0f)
        assertEquals(Float.MIN_VALUE, retrieved.embedding!![1], 0.0f)
        assertEquals(0.0f, retrieved.embedding!![2], 0.0f)
    }
}
