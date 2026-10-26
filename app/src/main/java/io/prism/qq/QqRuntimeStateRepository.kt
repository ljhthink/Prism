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
 * 定时主动消息**运行时状态仓库**（ADR-063；PRD US-3105 / US-3108）。
 *
 * 与 [QqConfigRepository] 共用同一个 `DataStore<Preferences>` 单例（由 `PrismApplication`
 * 创建），但存取的是**事实**而非配置。所有写操作以 `edit` 原子完成（读-改-写不存在竞态）。
 */
class QqRuntimeStateRepository(
    private val dataStore: DataStore<Preferences>
) {

    fun state(): Flow<QqRuntimeState> = dataStore.data.map { toState(it) }

    suspend fun get(): QqRuntimeState = state().first()

    /** 记录本次计划的触发时刻（跨重启补发/去重用）。 */
    suspend fun setPlannedTriggerAt(ms: Long) = edit { it[PLANNED_AT] = ms }

    /** 清除计划（本次已消费）。 */
    suspend fun clearPlan() = edit { it[PLANNED_AT] = 0L }

    /**
     * 记录一次**成功发送**：更新上次发送时刻与计数；跨日则计数清零。
     *
     * @param nowMs 发送时刻
     * @param todayKey 当日日期键（[QqThrottle.todayKey]）
     */
    suspend fun recordFired(nowMs: Long, todayKey: String) = edit { prefs ->
        val sameDay = prefs[FIRED_DATE] == todayKey
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_DATE] = todayKey
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        prefs[SCHEDULED_COUNT] = (if (sameDay) prefs[SCHEDULED_COUNT] ?: 0 else 0) + 1
        prefs[FAILURE_STREAK] = 0
    }

    /**
     * 记录一次**成功响应式回复**（US-3109；guardrail M-06）：只计当日**总数**（[QqThrottle]
     * 每日上限仍生效），**不消耗**定时主动消息配额（`maxPerDay`）——两者语义不同，不应混用。
     */
    suspend fun recordReplied(nowMs: Long, todayKey: String) = edit { prefs ->
        val sameDay = prefs[FIRED_DATE] == todayKey
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_DATE] = todayKey
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        prefs[FAILURE_STREAK] = 0
    }

    /**
     * 记录一次**结果未确认但很可能已送达**的发送（2026-10-25 真机实证；guardrail **G-r3-01**）。
     *
     * 与 [recordFired] 的唯一区别：**不重置失败连击**（`FAILURE_STREAK` 保持原值）——
     * 未确认既不算成功也不算账号失败，不应影响熔断判定。
     *
     * **注意**：这里**刻意不写** `FAILURE_STREAK = 0`。把"未确认"当成功会给熔断"洗白"：
     * 连续真失败中夹一次假阴性，就会让 streak 归零、熔断永不触发。改本方法前请先读这段。
     *
     * 但**必须**记账：否则 `lastFiredAt` 不前进（可在最小间隔内再发）、
     * 当日计数不增（可超每日上限），直接违反 PRD US-3108 AC-4「随机化不豁免频控」。
     */
    suspend fun recordUnconfirmedFired(nowMs: Long, todayKey: String) = edit { prefs ->
        val sameDay = prefs[FIRED_DATE] == todayKey
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_DATE] = todayKey
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        prefs[SCHEDULED_COUNT] = (if (sameDay) prefs[SCHEDULED_COUNT] ?: 0 else 0) + 1
        // 刻意不动 FAILURE_STREAK / CIRCUIT_OPEN
    }

    /**
     * 记录一次**被动回复结果未确认但很可能已送达**（guardrail **G-r5-01**）。
     *
     * 与 [recordUnconfirmedFired] 的唯一区别：**不写 `SCHEDULED_COUNT`**。
     * 原因：`scheduledFiredCount` 是 `QqThrottle.decide` 里 `maxPerDay` 的判据，而
     * `maxPerDay` 语义是"**定时主动消息**每日上限"——被动回复不该占用它（对齐 [recordReplied]
     * 的既有 M-06 不变量）。`maxPerDay` 默认可配到 1，占用即等于"当天定时消息被静默关停"，
     * 且设置页会显示"已达定时主动消息当日上限"，把用户引向错误原因。
     *
     * 仍然推进 `lastFiredAt` 与当日**总数**（最小间隔 / `dailyCap` 生效），不动 streak。
     */
    suspend fun recordUnconfirmedReplied(nowMs: Long, todayKey: String) = edit { prefs ->
        val sameDay = prefs[FIRED_DATE] == todayKey
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_DATE] = todayKey
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        // 刻意不写 SCHEDULED_COUNT：被动回复不消耗定时主动消息配额
        // 刻意不动 FAILURE_STREAK / CIRCUIT_OPEN：未确认既非成功也非账号失败
    }

    /** 记录一次**失败**：连续失败达到阈值即熔断（US-3105 AC-3）。 */
    suspend fun recordFailure(threshold: Int) = edit { prefs ->
        val streak = (prefs[FAILURE_STREAK] ?: 0) + 1
        prefs[FAILURE_STREAK] = streak
        if (streak >= threshold) prefs[CIRCUIT_OPEN] = true
    }

    /** 手动恢复熔断（US-3106 停用后重开 / 设置页"恢复"）。 */
    suspend fun resetCircuit() = edit { prefs ->
        prefs[CIRCUIT_OPEN] = false
        prefs[FAILURE_STREAK] = 0
    }

    /**
     * 记录一次定时任务**结果**（成功/跳过/失败类别 + 可读原因），供设置页与状态工具展示
     * （ADR-063 修订 2026-10-25；PRD US-3115 AC-6）。
     *
     * @param outcome `SUCCESS` / `SKIP:<kind>` / `FAIL:<kind>`
     * @param reason 已脱敏的可读原因（不写联系人/正文）
     */
    suspend fun recordOutcome(outcome: String, reason: String, atMs: Long) = edit {
        it[LAST_OUTCOME] = outcome.take(64)
        it[LAST_OUTCOME_REASON] = reason.take(160)
        it[LAST_OUTCOME_AT] = atMs
    }

    /**
     * 记录一次「环境类跳过」重试（guardrail **G-r2-02**：预算必须是**硬上限**且跨重启持久化）。
     *
     * @param anchor 当前窗口实例锚点（[QqSchedulePlanner.windowAnchor]）
     * @return 该窗口实例内累计次数（含本次）
     */
    suspend fun recordRetry(anchor: Long): Int {
        var count = 0
        edit { prefs ->
            if (prefs[RETRY_ANCHOR] != anchor) {
                prefs[RETRY_ANCHOR] = anchor
                prefs[RETRY_COUNT] = 0
            }
            count = (prefs[RETRY_COUNT] ?: 0) + 1
            prefs[RETRY_COUNT] = count
        }
        return count
    }

    /** 清空重试预算（成功发送后调用，使下一次窗口从满额开始）。 */
    suspend fun resetRetry() = edit {
        it[RETRY_COUNT] = 0
        it[RETRY_ANCHOR] = 0L
    }

    /** 记录已回应过的收到消息指纹与时刻（US-3109 双向对话去重 + guardrail M-02 时间窗）。 */
    suspend fun setLastReplied(hash: String, atMs: Long) = edit {
        it[LAST_REPLIED_HASH] = hash
        it[LAST_REPLIED_AT] = atMs
    }

    /**
     * 记录已回应过的协议端消息 ID（US-3111 AC-3 幂等键；跨重启持久化）。
     *
     * 与 [setLastReplied] 一起写入，使无障碍通道的去重逻辑不受影响。
     */
    suspend fun setLastRepliedMessageId(messageId: String) = edit {
        // L-21：仓库层再规范化一次（公共 API 自防御；幂等）
        val normalized = messageId.trim()
            .filter { c -> c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c == '-' }
            .take(64)
        if (normalized.isEmpty()) {
            it.remove(LAST_REPLIED_MESSAGE_ID)
            return@edit
        }
        it[LAST_REPLIED_MESSAGE_ID] = normalized
        // D-03：维护最近 N 条已回应 ID（A→B→重复 A 不得再次回应）
        val existing = it[LAST_REPLIED_IDS].orEmpty()
            .split(',')
            .filter { id -> id.isNotBlank() }
        val merged = (existing + normalized).distinct()
            .takeLast(QqRuntimeState.MAX_REPLIED_IDS)
        it[LAST_REPLIED_IDS] = merged.joinToString(",")
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun toState(prefs: Preferences): QqRuntimeState = QqRuntimeState(
        plannedTriggerAt = prefs[PLANNED_AT] ?: 0L,
        lastFiredAt = prefs[LAST_FIRED_AT] ?: 0L,
        firedDate = prefs[FIRED_DATE] ?: "",
        firedCount = prefs[FIRED_COUNT] ?: 0,
        scheduledFiredCount = prefs[SCHEDULED_COUNT] ?: 0,
        failureStreak = prefs[FAILURE_STREAK] ?: 0,
        circuitOpen = prefs[CIRCUIT_OPEN] ?: false,
        lastRepliedMessageHash = prefs[LAST_REPLIED_HASH] ?: "",
        lastRepliedAtMs = prefs[LAST_REPLIED_AT] ?: 0L,
        lastRepliedMessageId = prefs[LAST_REPLIED_MESSAGE_ID] ?: "",
        repliedMessageIds = prefs[LAST_REPLIED_IDS].orEmpty()
            .split(',')
            .filter { id -> id.isNotBlank() },
        lastOutcome = prefs[LAST_OUTCOME] ?: "",
        lastOutcomeReason = prefs[LAST_OUTCOME_REASON] ?: "",
        lastOutcomeAtMs = prefs[LAST_OUTCOME_AT] ?: 0L,
        retryWindowAnchor = prefs[RETRY_ANCHOR] ?: 0L,
        retryCount = prefs[RETRY_COUNT] ?: 0
    )

    companion object {
        private val PLANNED_AT = longPreferencesKey("qq_planned_trigger_at")
        private val LAST_FIRED_AT = longPreferencesKey("qq_last_fired_at")
        private val FIRED_DATE = stringPreferencesKey("qq_fired_date")
        private val FIRED_COUNT = intPreferencesKey("qq_fired_count")
        private val SCHEDULED_COUNT = intPreferencesKey("qq_scheduled_count")
        private val FAILURE_STREAK = intPreferencesKey("qq_failure_streak")
        private val CIRCUIT_OPEN = booleanPreferencesKey("qq_circuit_open")
        private val LAST_REPLIED_HASH = stringPreferencesKey("qq_last_replied_hash")
        private val LAST_REPLIED_AT = longPreferencesKey("qq_last_replied_at")
        private val LAST_REPLIED_MESSAGE_ID = stringPreferencesKey("qq_last_replied_message_id")
        private val LAST_REPLIED_IDS = stringPreferencesKey("qq_last_replied_ids")
        private val LAST_OUTCOME = stringPreferencesKey("qq_last_outcome")
        private val LAST_OUTCOME_REASON = stringPreferencesKey("qq_last_outcome_reason")
        private val LAST_OUTCOME_AT = longPreferencesKey("qq_last_outcome_at")
        private val RETRY_ANCHOR = longPreferencesKey("qq_retry_window_anchor")
        private val RETRY_COUNT = intPreferencesKey("qq_retry_count")
    }
}
