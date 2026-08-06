package io.prism.fs

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FilesystemRootStore 持久化仓库单元测试（ADR-006 5.3）。
 *
 * 基于 [FakePreferenceDataStore] 内存 DataStore 验证「逻辑目录名 → SAF 树 URI」映射的
 * 读写 / 覆盖 / 删除 / 损坏容错。
 */
class FilesystemRootStoreTest {

    private val ROOTS_KEY = stringPreferencesKey("filesystem_roots_json")

    private fun store(): FilesystemRootStore = FilesystemRootStore(FakePreferenceDataStore())

    @Test
    fun `loadRoots is empty by default`() = runTest {
        assertEquals("初始应无授权根目录", emptyMap<String, String>(), store().loadRoots())
    }

    @Test
    fun `putRoot then loadRoots returns mapping`() = runTest {
        val s = store()
        s.putRoot("notes", "content://tree/1")
        s.putRoot("docs", "content://tree/2")
        assertEquals(
            mapOf("notes" to "content://tree/1", "docs" to "content://tree/2"),
            s.loadRoots()
        )
    }

    @Test
    fun `putRoot overwrites existing entry`() = runTest {
        val s = store()
        s.putRoot("notes", "content://tree/1")
        s.putRoot("notes", "content://tree/2")
        assertEquals(mapOf("notes" to "content://tree/2"), s.loadRoots())
    }

    @Test
    fun `removeRoot removes only target entry`() = runTest {
        val s = store()
        s.putRoot("notes", "content://tree/1")
        s.putRoot("docs", "content://tree/2")
        s.removeRoot("notes")
        assertEquals(mapOf("docs" to "content://tree/2"), s.loadRoots())
    }

    @Test
    fun `removeRoot on missing key is no-op`() = runTest {
        val s = store()
        s.putRoot("notes", "content://tree/1")
        s.removeRoot("nonexistent")
        assertEquals(mapOf("notes" to "content://tree/1"), s.loadRoots())
    }

    @Test
    fun `corrupt stored json falls back to empty`() = runTest {
        val initial: Preferences = preferencesOf(ROOTS_KEY to "{not-valid-json")
        val s = FilesystemRootStore(FakePreferenceDataStore(initial))
        assertEquals("损坏 JSON 应容错回退为空表", emptyMap<String, String>(), s.loadRoots())
    }
}