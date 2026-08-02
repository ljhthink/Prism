package io.prism.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 DataStore<Preferences> 测试替身。
 *
 * 用于 JVM 单元测试中替代真实 DataStore（避免文件 I/O 与 Android 依赖）。
 * `edit` 扩展函数内部调用 [updateData]，因此本实现与真实 DataStore 语义一致。
 *
 * US-003 验收标准 3 的测试基础设施。
 */
class FakePreferenceDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val newValue = transform(state.value)
        state.value = newValue
        return newValue
    }
}
