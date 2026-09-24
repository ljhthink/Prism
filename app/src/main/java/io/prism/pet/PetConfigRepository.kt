package io.prism.pet

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.prism.pet.model.PetConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 批次24（ADR-051）：桌宠配置仓库 —— 持久化 [PetConfig] 到独立 DataStore。
 *
 * **设计**：与 [io.prism.config.ToolApprovalConfigRepository] 同模式（独立
 * `DataStore<Preferences>`，key 级原子读写）。DataStore 单例由 `PrismApplication` 的
 * `preferencesDataStore` 委托保证（多实例会崩溃，仓库自身不创建）。
 *
 * **读侧钳制**（PRD US-2412 AC-4）：DataStore 里的值可能是旧版本残留或被外部写入，
 * 所有读取路径统一经 [PetConfig.clamped]，非法值不会流进业务逻辑。
 * 选择"钳制"而非"抛异常"：桌宠是体验型功能，宁可位置回到默认也不要让设置页打不开。
 *
 * **写侧即钳制**：所有 setter 都先钳再落盘，保证落盘值域与读取期望一致。
 */
class PetConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察配置（热流，变更自动推送）。 */
    fun config(): Flow<PetConfig> = dataStore.data.map { toConfig(it) }

    /** 一次性读取（suspend 单值）。 */
    suspend fun get(): PetConfig = config().first()

    suspend fun setEnabled(enabled: Boolean) = edit { it[ENABLED] = enabled }

    suspend fun setAppearanceId(id: String?) = edit { prefs ->
        if (id.isNullOrBlank()) prefs.remove(APPEARANCE_ID) else prefs[APPEARANCE_ID] = id
    }

    suspend fun setScale(scale: Float) = edit { it[SCALE] = clampScale(scale) }

    suspend fun setOpacity(opacity: Float) = edit { it[OPACITY] = clampOpacity(opacity) }

    /** 写入归一化位置（0..1）。非法（NaN/±Inf）回落到默认位置——见 [clampUnit]。 */
    suspend fun setPosition(x: Float, y: Float) = edit {
        it[POS_X] = clampUnit(x, PetConfig.DEFAULT.posX)
        it[POS_Y] = clampUnit(y, PetConfig.DEFAULT.posY)
    }

    suspend fun setIdleEnabled(enabled: Boolean) = edit { it[IDLE_ENABLED] = enabled }

    suspend fun setReduceMotion(reduce: Boolean) = edit { it[REDUCE_MOTION] = reduce }

    suspend fun setBubbleEnabled(enabled: Boolean) = edit { it[BUBBLE_ENABLED] = enabled }

    suspend fun setLowPowerMode(enabled: Boolean) = edit { it[LOW_POWER] = enabled }

    suspend fun setExpressionResetSeconds(seconds: Int) = edit {
        it[EXPRESSION_RESET_SECONDS] = seconds.coerceIn(
            PetConfig.MIN_EXPRESSION_RESET_SECONDS,
            PetConfig.MAX_EXPRESSION_RESET_SECONDS
        )
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun toConfig(prefs: Preferences): PetConfig = PetConfig(
        enabled = prefs[ENABLED] ?: false,
        appearanceId = prefs[APPEARANCE_ID]?.takeIf { it.isNotBlank() },
        scale = prefs[SCALE] ?: 1.0f,
        opacity = prefs[OPACITY] ?: 1.0f,
        posX = prefs[POS_X] ?: PetConfig.DEFAULT.posX,
        posY = prefs[POS_Y] ?: PetConfig.DEFAULT.posY,
        idleEnabled = prefs[IDLE_ENABLED] ?: true,
        reduceMotion = prefs[REDUCE_MOTION] ?: false,
        expressionResetSeconds = prefs[EXPRESSION_RESET_SECONDS]
            ?: PetConfig.DEFAULT.expressionResetSeconds,
        bubbleEnabled = prefs[BUBBLE_ENABLED] ?: true,
        lowPowerMode = prefs[LOW_POWER] ?: false
    ).clamped()

    companion object {
        private val ENABLED = booleanPreferencesKey("pet_enabled")
        private val APPEARANCE_ID = stringPreferencesKey("pet_appearance_id")
        private val SCALE = floatPreferencesKey("pet_scale")
        private val OPACITY = floatPreferencesKey("pet_opacity")
        private val POS_X = floatPreferencesKey("pet_pos_x")
        private val POS_Y = floatPreferencesKey("pet_pos_y")
        private val IDLE_ENABLED = booleanPreferencesKey("pet_idle_enabled")
        private val REDUCE_MOTION = booleanPreferencesKey("pet_reduce_motion")
        private val BUBBLE_ENABLED = booleanPreferencesKey("pet_bubble_enabled")
        private val LOW_POWER = booleanPreferencesKey("pet_low_power")
        private val EXPRESSION_RESET_SECONDS = intPreferencesKey("pet_expression_reset_seconds")

        private fun clampScale(v: Float) =
            if (v.isFinite()) v.coerceIn(PetConfig.MIN_SCALE, PetConfig.MAX_SCALE) else 1.0f

        private fun clampOpacity(v: Float) =
            if (v.isFinite()) v.coerceIn(PetConfig.MIN_OPACITY, PetConfig.MAX_OPACITY) else 1.0f

        /**
         * 归一化坐标钳制（guardrail L-9）。
         *
         * 与 [clampScale]/[clampOpacity] 保持同一纪律：**先判有限再钳制**。
         * `Float.coerceIn` 对 NaN 是恒等返回（比较全为 false），
         * 只写 `coerceIn(0f, 1f)` 会让 NaN 直接落盘，之后窗口位置算出 0 或抛异常，
         * 表现为"桌宠莫名跑到左上角"且日志无线索。
         */
        private fun clampUnit(v: Float, fallback: Float) =
            if (v.isFinite()) v.coerceIn(0f, 1f) else fallback
    }
}
