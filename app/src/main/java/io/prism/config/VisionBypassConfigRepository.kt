package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * v1 US-301（方案 B 云端视觉旁路）配置仓库 —— 持久化旁路授权与熔断状态。
 *
 * **设计**：DataStore<Preferences>（`prism_vision_bypass_config`），与 [io.prism.security.ApiKeyRepository]
 * 同模式。独立 DataStore 文件，与其它配置隔离。
 *
 * **配置项**：
 * - [CONSENT_KEY]：用户是否已授权「图片外发到视觉 Provider」（隐私刚性要求，首次触发前
 *   必须用户明示确认，D-6 确认）；未授权则旁路不可用
 * - [AUTO_BYPASS_KEY]：自动旁路开关（默认 true；用户可一键关闭）
 * - [CONSECUTIVE_FAILURES_KEY]：连续失败计数（熔断：连续 [MAX_FAILURES] 次失败后自动停用
 *   自动旁路，提示手动换模型）
 *
 * **线程安全**：DataStore 保证原子读写。
 *
 * @param dataStore 视觉旁路配置专用 DataStore
 */
class VisionBypassConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察用户授权状态（热流）。 */
    fun consent(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CONSENT_KEY] ?: false
    }

    /** 一次性读取用户授权状态。 */
    suspend fun isConsentGiven(): Boolean = consent().first()

    /** 设置用户授权状态（首次触发旁路前弹窗确认后置 true）。 */
    suspend fun setConsent(given: Boolean) {
        dataStore.edit { prefs -> prefs[CONSENT_KEY] = given }
    }

    /** 观察自动旁路开关。 */
    fun autoBypass(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_BYPASS_KEY] ?: DEFAULT_AUTO_BYPASS
    }

    /** 一次性读取自动旁路开关。 */
    suspend fun isAutoBypassEnabled(): Boolean = autoBypass().first()

    /** 设置自动旁路开关。 */
    suspend fun setAutoBypassEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_BYPASS_KEY] = enabled }
    }

    /** 一次性读取连续失败计数（熔断状态）。 */
    suspend fun getConsecutiveFailures(): Int = dataStore.data.first()[CONSECUTIVE_FAILURES_KEY] ?: 0

    /**
     * 记录一次旁路失败（熔断计数 +1；达到 [MAX_FAILURES] 后自动停用旁路）。
     */
    suspend fun recordFailure() {
        dataStore.edit { prefs ->
            val current = prefs[CONSECUTIVE_FAILURES_KEY] ?: 0
            prefs[CONSECUTIVE_FAILURES_KEY] = (current + 1).coerceAtMost(MAX_FAILURES)
        }
    }

    /** 旁路成功 → 清零失败计数（熔断恢复）。 */
    suspend fun resetFailures() {
        dataStore.edit { prefs -> prefs[CONSECUTIVE_FAILURES_KEY] = 0 }
    }

    /**
     * 旁路是否可用（未熔断）：授权 + 自动开关 + 失败计数 < [MAX_FAILURES]。
     *
     * @return true 可自动旁路；false 熔断/未授权/关闭
     */
    suspend fun isBypassAvailable(): Boolean {
        val consent = isConsentGiven()
        val auto = isAutoBypassEnabled()
        val failures = getConsecutiveFailures()
        return consent && auto && failures < MAX_FAILURES
    }

    companion object {
        private val CONSENT_KEY = booleanPreferencesKey("bypass_consent")
        private val AUTO_BYPASS_KEY = booleanPreferencesKey("bypass_auto")
        private val CONSECUTIVE_FAILURES_KEY = intPreferencesKey("bypass_consecutive_failures")

        /** 自动旁路默认开启（需授权后生效）。 */
        const val DEFAULT_AUTO_BYPASS = true

        /** 熔断阈值：连续失败 N 次后自动停用自动旁路（防限流放大 + 反复失败）。 */
        const val MAX_FAILURES = 3
    }
}
