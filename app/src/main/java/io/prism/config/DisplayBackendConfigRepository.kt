package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * ADR-065（US-3114）：执行面后端偏好仓库。
 *
 * 仅持久化「隐藏执行面」开关（默认开启）：
 * - 开启：优先 shell 进程自建隐藏虚拟屏（主屏零窗口）；
 * - 关闭：使用 legacy 兼容后端（overlay / 自建虚拟屏，主屏可能出现可见小窗）。
 *
 * 后端创建失败时**始终允许降级**到 legacy（开关只表达偏好，不阻断可用性），
 * 实际形态由设置页与工具文案如实展示（[io.prism.phonecontrol.bg.VirtualDisplayManager.lastBackendNote]）。
 *
 * @param dataStore 执行面配置专用 DataStore（`prism_display_backend`，与其它配置隔离）
 */
class DisplayBackendConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察「隐藏执行面」开关（热流）。
     *
     * guardrail r1 Q-08：DataStore 读异常不得让下游 `stateIn`/`collect` 永久终止——
     * 失败时发射默认值（保持"可用优先"），并把真实错误留给写入侧反馈。 */
    fun hiddenDisplayEnabled(): Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[HIDDEN_ENABLED_KEY] ?: DEFAULT_HIDDEN_ENABLED }
        .catch { emit(DEFAULT_HIDDEN_ENABLED) }

    /** 一次性读取「隐藏执行面」开关。 */
    suspend fun getHiddenDisplayEnabled(): Boolean = hiddenDisplayEnabled().first()

    /** 设置「隐藏执行面」开关（持久化，运行期即时生效）。 */
    suspend fun setHiddenDisplayEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[HIDDEN_ENABLED_KEY] = enabled }
    }

    companion object {
        private val HIDDEN_ENABLED_KEY = booleanPreferencesKey("hidden_display_enabled")

        /** 默认开启（ADR-065 默认后端）。 */
        const val DEFAULT_HIDDEN_ENABLED = true
    }
}
