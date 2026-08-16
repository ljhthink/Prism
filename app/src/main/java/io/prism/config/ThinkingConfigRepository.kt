package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 深度思考配置仓库（问题 8a，ADR-020）—— 持久化深度思考开关与思考强度。
 *
 * **背景**：DeepSeek V4 思考模式（thinking）通过顶层 `thinking={"type":"enabled"}` +
 * 平级 `reasoning_effort`（low/high/max）参数开启。但 `thinking`/`reasoning_effort` 是
 * DeepSeek 专有参数，OpenAI / Claude / Ollama 等兼容端点不识别，直接发送会返回 400。
 * 因此默认**关闭**，由用户显式开启（[DEFAULT_ENABLED]=false），关闭时不发送这两个字段
 * （向后兼容所有端点）。
 *
 * **设计**：使用 DataStore<Preferences> 存储，与 [io.prism.memory.MemoryConfigRepository]
 * 同模式（DataStore 进程级单例 + 委托属性）。独立 DataStore 文件（`prism_thinking_config`），
 * 与 API Key / 记忆 / 档位 DataStore 隔离，避免耦合。
 *
 * **配置项**：
 * - [ENABLED_KEY]：深度思考开关（默认 false）
 * - [EFFORT_KEY]：思考强度 `reasoning_effort`（low / high / max，默认 high）
 *
 * **校验**（BR-security-005 fail-fast 纵深防御）：[setReasoningEffort] 拒绝不在
 * [EFFORT_VALUES] 中的值，防止 DataStore 被外部写入非法强度导致下游 400。
 *
 * **线程安全**：DataStore 保证原子读写，多协程并发安全。
 *
 * @param dataStore 深度思考配置专用 DataStore（`prism_thinking_config`）
 */
class ThinkingConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察深度思考开关（热流，配置变更时自动推送）。 */
    fun thinkingEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ENABLED_KEY] ?: DEFAULT_ENABLED
    }

    /** 一次性读取深度思考开关（suspend 单值）。 */
    suspend fun getThinkingEnabled(): Boolean = thinkingEnabled().first()

    /** 设置深度思考开关（持久化到 DataStore）。 */
    suspend fun setThinkingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[ENABLED_KEY] = enabled }
    }

    /** 观察思考强度（热流，配置变更时自动推送）。 */
    fun reasoningEffort(): Flow<String> = dataStore.data.map { prefs ->
        prefs[EFFORT_KEY] ?: DEFAULT_EFFORT
    }

    /** 一次性读取思考强度（suspend 单值）。 */
    suspend fun getReasoningEffort(): String = reasoningEffort().first()

    /**
     * 设置思考强度（持久化到 DataStore）。
     *
     * **纵深防御**（BR-security-005）：仅接受 [EFFORT_VALUES] 中的值，
     * 防止非法强度值经请求体发送到 Provider 返回 400。
     *
     * @param effort 思考强度，必须为 low / high / max 之一
     * @throws IllegalArgumentException 当 effort 不在 [EFFORT_VALUES] 中时抛出（fail-fast）
     */
    suspend fun setReasoningEffort(effort: String) {
        require(effort in EFFORT_VALUES) {
            "思考强度必须为 ${EFFORT_VALUES.joinToString("/")} 之一（收到 $effort）"
        }
        dataStore.edit { prefs -> prefs[EFFORT_KEY] = effort }
    }

    companion object {
        /** 深度思考开关的 DataStore key。 */
        private val ENABLED_KEY = booleanPreferencesKey("thinking_enabled")

        /** 思考强度的 DataStore key。 */
        private val EFFORT_KEY = stringPreferencesKey("reasoning_effort")

        /** 默认开关状态（关闭：避免向不兼容端点发送 thinking 参数返回 400）。 */
        const val DEFAULT_ENABLED = false

        /** 默认思考强度（DeepSeek 官方推荐 high，复杂 Agent 场景建议 max）。 */
        const val DEFAULT_EFFORT = "high"

        /** 合法的思考强度取值集合（对齐 DeepSeek API 文档）。 */
        val EFFORT_VALUES = setOf("low", "high", "max")
    }
}
