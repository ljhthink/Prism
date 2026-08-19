package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 手机操控高危动作确认策略配置仓库（v1 真机二次修复 Issue 4b）。
 *
 * 用户对「发送/删除/拨号/短信」等危险动作的确认级别持久化（[HighRiskApprovalMode]：
 * BLOCK 全部拦截 / ALLOW 全部放行 / ASK 逐次询问）。
 *
 * **设计**：与 [ThinkingConfigRepository] 同模式——独立 DataStore 文件（`prism_high_risk_approval`），
 * 原子读写、多协程安全。消费方（[io.prism.phonecontrol.PhoneControlLocalToolExecutor]）
 * 经 [io.prism.PrismApplication] 注入 [mode] 秒读通道，实时生效。
 *
 * @param dataStore 高危动作策略专用 DataStore（`prism_high_risk_approval`）
 */
class HighRiskApprovalRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察高危动作确认策略（热流，配置变更自动推送）。 */
    fun mode(): Flow<HighRiskApprovalMode> = dataStore.data.map { prefs ->
        HighRiskApprovalMode.fromStored(prefs[KEY] ?: HighRiskApprovalMode.DEFAULT_NAME)
    }

    /** 一次性读取当前策略。 */
    suspend fun getMode(): HighRiskApprovalMode = mode().first()

    /** 设置策略（持久化到 DataStore）。 */
    suspend fun setMode(mode: HighRiskApprovalMode) {
        dataStore.edit { prefs -> prefs[KEY] = mode.name }
    }

    companion object {
        private val KEY = stringPreferencesKey("high_risk_approval_mode")
    }
}