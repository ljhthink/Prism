package io.prism.qq

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * QQ 小号助手配置仓库（ADR-062；PRD US-3101 / US-3105 / US-3108）。
 *
 * **设计**：与 [io.prism.pet.PetConfigRepository] 同模式——独立 `DataStore<Preferences>`，
 * key 级原子读写；DataStore 单例由 `PrismApplication` 的 `preferencesDataStore` 委托保证
 * （多实例会崩溃，本仓库自身不创建）。写侧经 [QqConfig.clamped] 钳制，读侧同样钳制。
 */
class QqConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察配置（热流，变更自动推送）。 */
    fun config(): Flow<QqConfig> = dataStore.data.map { toConfig(it) }

    /** 一次性读取（suspend 单值）。 */
    suspend fun get(): QqConfig = config().first()

    suspend fun setEnabled(enabled: Boolean) = edit { it[ENABLED] = enabled }

    suspend fun setRiskAccepted(accepted: Boolean) = edit { it[RISK_ACCEPTED] = accepted }

    suspend fun setMinIntervalMs(ms: Long) = edit {
        it[MIN_INTERVAL_MS] = ms.coerceIn(QqConfig.MIN_INTERVAL_MS_FLOOR, QqConfig.MAX_INTERVAL_MS_CEIL)
    }

    suspend fun setDailyCap(cap: Int) = edit {
        it[DAILY_CAP] = cap.coerceIn(QqConfig.MIN_DAILY_CAP, QqConfig.MAX_DAILY_CAP)
    }

    suspend fun setScheduleEnabled(enabled: Boolean) = edit { it[SCHEDULE_ENABLED] = enabled }

    suspend fun setWindow(startMinute: Int, endMinute: Int) = edit {
        it[WINDOW_START] = startMinute.coerceIn(0, QqConfig.MINUTES_PER_DAY - 1)
        it[WINDOW_END] = endMinute.coerceIn(1, QqConfig.MINUTES_PER_DAY)
    }

    suspend fun setMaxPerDay(n: Int) = edit {
        it[MAX_PER_DAY] = n.coerceIn(QqConfig.MIN_PER_DAY, QqConfig.MAX_PER_DAY_CEIL)
    }

    suspend fun setUsePoisson(usePoisson: Boolean) = edit { it[USE_POISSON] = usePoisson }

    /** 双向对话开关（US-3109；默认关闭）。 */
    suspend fun setAutoReplyEnabled(enabled: Boolean) = edit { it[AUTO_REPLY] = enabled }

    /** 设置定时消息目标联系人（小号对用户主号的备注名；空白即清除）。 */
    suspend fun setTargetContact(contact: String) = edit {
        val v = contact.trim()
        val previous = it[TARGET_CONTACT].orEmpty()
        if (v.isEmpty()) it.remove(TARGET_CONTACT) else it[TARGET_CONTACT] = v.take(50)
        // guardrail M-11：联系人变更时清除与之绑定的目标 QQ 号，避免继续发给旧对象
        if (shouldClearTargetId(previous, v)) it.remove(ONEBOT_TARGET_ID)
    }

    // ==================== 协议端（ADR-064；US-3110） ====================

    /** 切换传输通道（ONEBOT / ACCESSIBILITY）。 */
    suspend fun setTransport(transport: io.prism.qq.channel.QqTransport) = edit {
        it[TRANSPORT] = transport.name
    }

    /**
     * 保存协议端连接参数。
     *
     * @param token 访问令牌；**空白=保持原值**（避免"只改地址"顺手清空凭据），
     *   显式清除须传 [clearToken]=true（guardrail H-03）
     * @param clearToken true=删除已保存令牌
     */
    suspend fun setOneBotConnection(
        baseUrl: String,
        wsUrl: String,
        token: String,
        selfId: Long,
        targetUserId: Long,
        clearToken: Boolean = false
    ) = edit {
        val b = baseUrl.trim()
        if (b.isEmpty()) it.remove(ONEBOT_BASE_URL) else it[ONEBOT_BASE_URL] = b.take(200)
        val w = wsUrl.trim()
        if (w.isEmpty()) it.remove(ONEBOT_WS_URL) else it[ONEBOT_WS_URL] = w.take(200)
        val t = token.trim()
        when {
            clearToken -> it.remove(ONEBOT_TOKEN)
            t.isNotEmpty() -> it[ONEBOT_TOKEN] = t.take(256)
            else -> Unit // 留空=保持原值
        }
        it[ONEBOT_SELF_ID] = selfId.coerceAtLeast(0L)
        it[ONEBOT_TARGET_ID] = targetUserId.coerceAtLeast(0L)
    }

    /** 回复时读取的历史条数（US-3112；读侧/写侧均钳制）。 */
    suspend fun setHistoryLimit(n: Int) = edit {
        it[HISTORY_LIMIT] = n.coerceIn(QqConfig.MIN_HISTORY_LIMIT, QqConfig.MAX_HISTORY_LIMIT)
    }

    /** 是否注入 L2/L3 记忆（US-3112 AC-4）。 */
    suspend fun setMemoryInjectionEnabled(enabled: Boolean) = edit {
        it[MEMORY_INJECTION] = enabled
    }

    /** 熄屏时是否临时点亮屏幕执行定时任务（US-3115 AC-6；默认开）。 */
    suspend fun setWakeOnScreenOff(enabled: Boolean) = edit {
        it[WAKE_ON_SCREEN_OFF] = enabled
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun toConfig(prefs: Preferences): QqConfig = QqConfig(
        enabled = prefs[ENABLED] ?: false,
        riskAccepted = prefs[RISK_ACCEPTED] ?: false,
        minIntervalMs = prefs[MIN_INTERVAL_MS] ?: QqConfig.DEFAULT_MIN_INTERVAL_MS,
        dailyCap = prefs[DAILY_CAP] ?: QqConfig.DEFAULT_DAILY_CAP,
        scheduleEnabled = prefs[SCHEDULE_ENABLED] ?: false,
        windowStartMinute = prefs[WINDOW_START] ?: QqConfig.DEFAULT_WINDOW_START_MINUTE,
        windowEndMinute = prefs[WINDOW_END] ?: QqConfig.DEFAULT_WINDOW_END_MINUTE,
        maxPerDay = prefs[MAX_PER_DAY] ?: QqConfig.DEFAULT_MAX_PER_DAY,
        usePoisson = prefs[USE_POISSON] ?: false,
        targetContact = prefs[TARGET_CONTACT] ?: "",
        autoReplyEnabled = prefs[AUTO_REPLY] ?: false,
        transport = when (prefs[TRANSPORT]) {
            io.prism.qq.channel.QqTransport.ACCESSIBILITY.name -> io.prism.qq.channel.QqTransport.ACCESSIBILITY
            else -> io.prism.qq.channel.QqTransport.ONEBOT
        },
        oneBotBaseUrl = prefs[ONEBOT_BASE_URL] ?: QqConfig.DEFAULT_ONEBOT_BASE_URL,
        oneBotWsUrl = prefs[ONEBOT_WS_URL] ?: QqConfig.DEFAULT_ONEBOT_WS_URL,
        oneBotToken = prefs[ONEBOT_TOKEN] ?: "",
        oneBotSelfId = prefs[ONEBOT_SELF_ID] ?: 0L,
        oneBotTargetUserId = prefs[ONEBOT_TARGET_ID] ?: 0L,
        historyLimit = prefs[HISTORY_LIMIT] ?: QqConfig.DEFAULT_HISTORY_LIMIT,
        memoryInjectionEnabled = prefs[MEMORY_INJECTION] ?: true,
        wakeOnScreenOff = prefs[WAKE_ON_SCREEN_OFF] ?: true
    ).clamped()

    companion object {
        /**
         * 联系人变更时是否必须清除 `oneBotTargetUserId`（guardrail M-11；纯函数可测）。
         *
         * 目标 QQ 号是**绑定在联系人上的**；联系人不一致时继续沿用它会把消息发给旧对象。
         */
        internal fun shouldClearTargetId(previous: String, next: String): Boolean = next != previous

        private val ENABLED = booleanPreferencesKey("qq_enabled")
        private val RISK_ACCEPTED = booleanPreferencesKey("qq_risk_accepted")
        private val MIN_INTERVAL_MS = longPreferencesKey("qq_min_interval_ms")
        private val DAILY_CAP = intPreferencesKey("qq_daily_cap")
        private val SCHEDULE_ENABLED = booleanPreferencesKey("qq_schedule_enabled")
        private val WINDOW_START = intPreferencesKey("qq_window_start_minute")
        private val WINDOW_END = intPreferencesKey("qq_window_end_minute")
        private val MAX_PER_DAY = intPreferencesKey("qq_max_per_day")
        private val USE_POISSON = booleanPreferencesKey("qq_use_poisson")
        private val TARGET_CONTACT = stringPreferencesKey("qq_target_contact")
        private val AUTO_REPLY = booleanPreferencesKey("qq_auto_reply")

        // 协议端（ADR-064）：token 与 SearXNG 凭据同范式存于本 App 私有 DataStore，
        // 不写日志、不上传；仅个人自用部署。
        private val TRANSPORT = stringPreferencesKey("qq_transport")
        private val ONEBOT_BASE_URL = stringPreferencesKey("qq_onebot_base_url")
        private val ONEBOT_WS_URL = stringPreferencesKey("qq_onebot_ws_url")
        private val ONEBOT_TOKEN = stringPreferencesKey("qq_onebot_token")
        private val ONEBOT_SELF_ID = longPreferencesKey("qq_onebot_self_id")
        private val ONEBOT_TARGET_ID = longPreferencesKey("qq_onebot_target_id")
        private val HISTORY_LIMIT = intPreferencesKey("qq_history_limit")
        private val MEMORY_INJECTION = booleanPreferencesKey("qq_memory_injection")
        private val WAKE_ON_SCREEN_OFF = booleanPreferencesKey("qq_wake_on_screen_off")
    }
}
