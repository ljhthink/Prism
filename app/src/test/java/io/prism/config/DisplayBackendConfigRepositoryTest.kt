package io.prism.config

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-065（US-3114）：[DisplayBackendConfigRepository] 单测（JVM，FakePreferenceDataStore）。
 *
 * guardrail r1 C-10 要求覆盖：默认值、持久化读写、读异常降级（Q-08）。
 */
class DisplayBackendConfigRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: DisplayBackendConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = DisplayBackendConfigRepository(dataStore)
    }

    @Test
    fun `默认开启隐藏执行面`() = runBlocking {
        assertTrue("ADR-065：默认使用隐藏虚拟屏", DisplayBackendConfigRepository.DEFAULT_HIDDEN_ENABLED)
        assertTrue(repository.getHiddenDisplayEnabled())
        assertTrue(repository.hiddenDisplayEnabled().first())
    }

    @Test
    fun `写入后读回一致（关闭与再开启）`() = runBlocking {
        repository.setHiddenDisplayEnabled(false)
        assertFalse(repository.getHiddenDisplayEnabled())
        assertFalse(repository.hiddenDisplayEnabled().first())

        repository.setHiddenDisplayEnabled(true)
        assertTrue(repository.getHiddenDisplayEnabled())
        assertTrue(repository.hiddenDisplayEnabled().first())
    }

    @Test
    fun `DataStore 读异常时降级为默认值（不中断下游流）`() = runBlocking {
        // FakePreferenceDataStore 的 data 为基础实现；此处用自定义失败 DataStore 验证二义性
        val failing = FailingPreferenceDataStore()
        val repo = DisplayBackendConfigRepository(failing)
        assertEquals(
            DisplayBackendConfigRepository.DEFAULT_HIDDEN_ENABLED,
            repo.hiddenDisplayEnabled().first()
        )
    }
}

/** 读操作恒失败的 DataStore（仅验证仓库的 catch → 默认值契约）。 */
private class FailingPreferenceDataStore :
    androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> =
        kotlinx.coroutines.flow.flow { throw java.io.IOException("datastore read failed") }

    override suspend fun updateData(
        transform: suspend (t: androidx.datastore.preferences.core.Preferences) ->
        androidx.datastore.preferences.core.Preferences
    ): androidx.datastore.preferences.core.Preferences = throw java.io.IOException("read only")
}
