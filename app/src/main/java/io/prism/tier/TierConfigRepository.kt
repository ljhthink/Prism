package io.prism.tier

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设备档位配置仓库（ADR-017 4.4）—— 持久化用户手动覆盖的档位偏好。
 *
 * **设计**：仿 [io.prism.memory.MemoryConfigRepository] 模式（ADR-015 5.3）：
 * - 独立 DataStore 文件 `prism_tier_config`（与 `prism_memory_config` / `prism_api_keys` 隔离）
 * - [stringPreferencesKey] 存档位枚举名或 `AUTO`（表示无覆盖，使用 RAM 检测结果）
 * - Flow 暴露 + suspend 单值读取 + 校验
 *
 * **覆盖语义**：
 * - [OVERRIDE_AUTO]：无覆盖，使用 [TierManager] RAM 检测结果（默认）
 * - `FULL` / `STANDARD` / `MINIMAL` / `CHAT_ONLY`：强制使用指定档位
 *
 * **生效时机**（ADR-017 4.4）：用户修改覆盖后需重启 App 生效。
 * [TierManager] 在 `PrismApplication.onCreate` 中通过 `runBlocking` 一次性读取覆盖值缓存到内存，
 * 后续 `by lazy` 注入直接读内存字段，避免 suspend 传播。UI 修改覆盖后明确提示用户重启。
 *
 * **线程安全**：DataStore 保证原子读写，多协程并发安全。
 *
 * **校验**：[setOverride] 拒绝无法识别的枚举名（fail-fast 纵深防御，BR-security-005 同模式）。
 *
 * US-007 验收标准 6：用户可在设置中手动覆盖档位
 *
 * @param dataStore Tier Config 专用 DataStore（`prism_tier_config`）
 */
class TierConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /**
     * 观察用户覆盖的档位（热流，配置变更时自动推送）。
     *
     * @return 覆盖值的 Flow，首次读取若未配置返回 [OVERRIDE_AUTO]
     */
    fun override(): Flow<String> = dataStore.data.map { prefs ->
        prefs[OVERRIDE_KEY] ?: OVERRIDE_AUTO
    }

    /**
     * 一次性读取用户覆盖的档位（suspend 单值）。
     *
     * 供 [TierManager] 在 `PrismApplication.onCreate` 中 `runBlocking` 调用一次，
     * 缓存到内存供后续 `by lazy` 注入读取。
     *
     * @return 当前覆盖值；未配置时返回 [OVERRIDE_AUTO]
     */
    suspend fun getOverride(): String = override().first()

    /**
     * 设置用户覆盖的档位（持久化到 DataStore）。
     *
     * **校验**（BR-security-005 同模式）：仅接受 [VALID_VALUES] 中的值，
     * 防止 DataStore 被外部写入无效字符串导致下游解析异常。
     *
     * @param value 覆盖值，必须在 [VALID_VALUES] 范围内
     * @throws IllegalArgumentException 当 value 不在 [VALID_VALUES] 中时抛出（fail-fast）
     */
    suspend fun setOverride(value: String) {
        require(value in VALID_VALUES) {
            "档位覆盖值必须是 $VALID_VALUES 之一（收到 $value）"
        }
        dataStore.edit { prefs ->
            prefs[OVERRIDE_KEY] = value
        }
    }

    /**
     * 清除用户覆盖（恢复为 AUTO，使用 RAM 检测结果）。
     *
     * 等价于 [setOverride]([OVERRIDE_AUTO])，但语义更清晰，供 UI「恢复自动」按钮调用。
     */
    suspend fun clearOverride() = setOverride(OVERRIDE_AUTO)

    companion object {
        /** 档位覆盖的 DataStore key。 */
        private val OVERRIDE_KEY = stringPreferencesKey("tier_override")

        /** 无覆盖标记（使用 RAM 检测结果），为默认值。 */
        const val OVERRIDE_AUTO = "AUTO"

        /** 所有合法的覆盖值（[OVERRIDE_AUTO] + 四档枚举名）。 */
        val VALID_VALUES: Set<String> = setOf(
            OVERRIDE_AUTO,
            PerformanceTier.FULL.name,
            PerformanceTier.STANDARD.name,
            PerformanceTier.MINIMAL.name,
            PerformanceTier.CHAT_ONLY.name
        )
    }
}
