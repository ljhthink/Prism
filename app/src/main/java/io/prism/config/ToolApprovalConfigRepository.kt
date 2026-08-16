package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 工具审批模式配置仓库（UXR3 问题 10，ADR-023）—— 持久化 LLM 工具调用权限策略。
 *
 * **背景**：用户要求对 LLM 操作权限做三种模式划分（手动审批 / 自动审批 / 禁用），
 * 切换按钮放在设置中。本仓库负责持久化 [ToolApprovalMode] 到 DataStore。
 *
 * **设计**：使用 DataStore<Preferences> 存储，与 [ThinkingConfigRepository] 同模式。
 * 独立 DataStore 文件（`prism_tool_approval`），与 API Key / 记忆 / 档位 / 思考 DataStore 隔离。
 *
 * **配置项**：
 * - [MODE_KEY]：工具审批模式（默认 [ToolApprovalMode.DEFAULT] = MANUAL）
 *
 * **校验**（BR-security-005 fail-fast 纵深防御）：[mode] 读取时对持久化的非法字符串
 * `valueOf` 兜底为 [ToolApprovalMode.DEFAULT]，防止 DataStore 被外部写入未知模式导致运行期 when 失配。
 * 写入侧 [setMode] 入参为枚举类型，天然无非法字符串路径（编译期保证）。
 *
 * **线程安全**：DataStore 保证原子读写，多协程并发安全。
 *
 * @param dataStore 工具审批模式配置专用 DataStore（`prism_tool_approval`）
 */
class ToolApprovalConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察当前审批模式（热流，配置变更时自动推送）。 */
    fun mode(): Flow<ToolApprovalMode> = dataStore.data.map { prefs ->
        val raw = prefs[MODE_KEY]
        if (raw == null) {
            ToolApprovalMode.DEFAULT
        } else {
            runCatching { ToolApprovalMode.valueOf(raw) }
                .getOrDefault(ToolApprovalMode.DEFAULT)
        }
    }

    /** 一次性读取当前审批模式（suspend 单值）。 */
    suspend fun getMode(): ToolApprovalMode = mode().first()

    /**
     * 设置审批模式（持久化到 DataStore）。
     *
     * **纵深防御**（BR-security-005）：拒绝未知模式字符串，防止经数据注入使运行期 when 失配。
     *
     * @param mode 审批模式（MANUAL / AUTO / DISABLED）
     */
    suspend fun setMode(mode: ToolApprovalMode) {
        dataStore.edit { prefs -> prefs[MODE_KEY] = mode.name }
    }

    companion object {
        /** 审批模式的 DataStore key。 */
        private val MODE_KEY = stringPreferencesKey("tool_approval_mode")

        /** 合法取值集合（对齐 [ToolApprovalMode] 枚举，供校验/展示）。 */
        val MODES: List<ToolApprovalMode> = ToolApprovalMode.entries
    }
}
