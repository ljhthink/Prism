package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * SessionRepository 单元测试（UX-001 问题 4，ADR-021）。
 *
 * 覆盖：
 * - 会话保存 / 更新（id 分配、messagesJson 持久化）
 * - 按 id 获取 / 删除
 * - sessions StateFlow 按 updatedAt 倒序
 * - removeAll 清空
 */
class SessionRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: SessionRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "session-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = SessionRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun save_assigns_positive_id_and_persists() {
        val id = repository.save(Session(title = "会话A", messagesJson = "[]"))
        assertTrue("ObjectBox 应分配正数 id", id > 0)

        val retrieved = repository.get(id)
        assertEquals("会话A", retrieved?.title)
        assertEquals("[]", retrieved?.messagesJson)
    }

    @Test
    fun update_preserves_id_and_refreshes() {
        val id = repository.save(Session(title = "会话A", messagesJson = "[]"))
        repository.save(Session(id = id, title = "会话A-更新", messagesJson = "[{}]", createdAt = repository.get(id)!!.createdAt, updatedAt = 999L))

        val retrieved = repository.get(id)
        assertEquals("更新后应保留 id", id, retrieved?.id)
        assertEquals("会话A-更新", retrieved?.title)
        assertEquals("[{}]", retrieved?.messagesJson)
        assertEquals("updatedAt 应更新", 999L, retrieved?.updatedAt)
    }

    @Test
    fun sessions_sorted_by_updatedAt_desc() {
        repository.save(Session(title = "旧", messagesJson = "[]", updatedAt = 1000L))
        repository.save(Session(title = "新", messagesJson = "[]", updatedAt = 3000L))
        repository.save(Session(title = "中", messagesJson = "[]", updatedAt = 2000L))

        val all = repository.sessions.value
        assertEquals("应按 updatedAt 倒序", listOf("新", "中", "旧"), all.map { it.title })
    }

    @Test
    fun remove_deletes_session_and_returns_true() {
        val id = repository.save(Session(title = "会话A", messagesJson = "[]"))

        assertTrue("删除应返回 true", repository.remove(id))
        assertNull("删除后 get 应返回 null", repository.get(id))
        assertTrue("删除后列表为空", repository.sessions.value.isEmpty())
    }

    @Test
    fun remove_nonexistent_returns_false() {
        assertFalse("不存在的会话删除应返回 false", repository.remove(99999L))
    }

    @Test
    fun removeAll_clears_all_sessions() {
        repository.save(Session(title = "A", messagesJson = "[]"))
        repository.save(Session(title = "B", messagesJson = "[]"))

        repository.removeAll()

        assertTrue("removeAll 后列表为空", repository.sessions.value.isEmpty())
    }
}
