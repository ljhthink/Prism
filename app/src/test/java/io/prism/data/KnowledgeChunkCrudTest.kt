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
 * KnowledgeChunk 基础 CRUD 单元测试（US-002 验收标准 4）。
 *
 * 使用 [BoxStore.directory] 在临时目录中构建纯 JVM ObjectBox 实例，
 * 无需 Android 设备/模拟器。ObjectBox Java 桌面原生库由 objectbox-java 提供。
 */
class KnowledgeChunkCrudTest {

    private lateinit var boxStore: BoxStore
    private lateinit var box: Box<KnowledgeChunk>
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "objectbox-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        box = boxStore.boxFor(KnowledgeChunk::class.java)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun put_assigns_positive_id() {
        val chunk = KnowledgeChunk(title = "Kotlin 协程", content = "协程是轻量级线程...")
        val id = box.put(chunk)
        assertTrue("ObjectBox 应分配正数 id", id > 0)
    }

    @Test
    fun get_returns_persisted_chunk_without_embedding() {
        val chunk = KnowledgeChunk(title = "Kotlin 协程", content = "协程是轻量级线程...")
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertEquals("Kotlin 协程", retrieved.title)
        assertEquals("协程是轻量级线程...", retrieved.content)
        assertNull("未设置 embedding 时应为 null", retrieved.embedding)
    }

    @Test
    fun put_with_embedding_persists_vector() {
        val embedding = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val chunk = KnowledgeChunk(
            title = "向量测试",
            content = "带嵌入的知识块",
            embedding = embedding
        )
        val id = box.put(chunk)

        val retrieved = box.get(id)
        assertArrayEquals(embedding, retrieved.embedding, 0.0001f)
    }

    @Test
    fun remove_deletes_chunk() {
        val chunk = KnowledgeChunk(title = "待删除", content = "即将被删除的块")
        val id = box.put(chunk)
        assertTrue("删除前应存在", box.contains(id))

        box.remove(id)
        assertFalse("删除后应不存在", box.contains(id))
    }

    @Test
    fun put_with_existing_id_updates_chunk() {
        val chunk = KnowledgeChunk(title = "原标题", content = "原内容")
        val id = box.put(chunk)

        chunk.id = id
        chunk.title = "更新后标题"
        chunk.content = "更新后内容"
        box.put(chunk)

        val updated = box.get(id)
        assertEquals("更新后标题", updated.title)
        assertEquals("更新后内容", updated.content)
    }

    @Test
    fun put_multiple_chunks_each_gets_unique_id() {
        val id1 = box.put(KnowledgeChunk(title = "块1", content = "内容1"))
        val id2 = box.put(KnowledgeChunk(title = "块2", content = "内容2"))
        val id3 = box.put(KnowledgeChunk(title = "块3", content = "内容3"))

        assertTrue(id1 != id2)
        assertTrue(id2 != id3)
        assertTrue(id1 != id3)
        assertEquals(3, box.count())
    }

    @Test
    fun get_nonexistent_id_not_found_via_contains() {
        assertFalse("不存在的 id 应 contains=false", box.contains(99999L))
    }

    @Test
    fun remove_all_clears_box() {
        box.put(KnowledgeChunk(title = "块1", content = "内容1"))
        box.put(KnowledgeChunk(title = "块2", content = "内容2"))
        assertEquals(2, box.count())

        box.removeAll()
        assertEquals(0, box.count())
    }

    @Test
    fun empty_embedding_round_trip() {
        val chunk = KnowledgeChunk(
            title = "空向量",
            content = "embedding 为空数组",
            embedding = FloatArray(0)
        )
        val id = box.put(chunk)
        val retrieved = box.get(id)

        assertNotNull(retrieved.embedding)
        assertEquals(0, retrieved.embedding!!.size)
    }
}
