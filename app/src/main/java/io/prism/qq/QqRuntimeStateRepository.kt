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
        val sameDay = advanceDay(prefs, todayKey)
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        prefs[SCHEDULED_COUNT] = (if (sameDay) prefs[SCHEDULED_COUNT] ?: 0 else 0) + 1
        prefs[FAILURE_STREAK] = 0
    }

    /**
     * 记录一次**成功响应式回复**（US-3109；guardrail M-06）：只计当日**总数**（[QqThrottle]
     * 每日上限仍生效），**不消耗**定时主动消息配额（`maxPerDay`）——两者语义不同，不应混用。
     */
    suspend fun recordReplied(nowMs: Long, todayKey: String) = edit { prefs ->
        val sameDay = advanceDay(prefs, todayKey)
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        prefs[FAILURE_STREAK] = 0
        // 2026-10-26 RC-3 修复：被动回复的滑动窗口记账（与去重集合解耦）
        prefs[PASSIVE_REPLY_AT] = appendPassiveTimestamp(prefs[PASSIVE_REPLY_AT], nowMs)
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
        val sameDay = advanceDay(prefs, todayKey)
        prefs[LAST_FIRED_AT] = nowMs
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
        val sameDay = advanceDay(prefs, todayKey)
        prefs[LAST_FIRED_AT] = nowMs
        prefs[FIRED_COUNT] = (if (sameDay) prefs[FIRED_COUNT] ?: 0 else 0) + 1
        // 刻意不写 SCHEDULED_COUNT：被动回复不消耗定时主动消息配额
        // 刻意不动 FAILURE_STREAK / CIRCUIT_OPEN：未确认既非成功也非账号失败
        // 2026-10-26 RC-3 修复：仍计入被动回复滑动窗口（否则窗口限流可被"未确认"绕过）
        prefs[PASSIVE_REPLY_AT] = appendPassiveTimestamp(prefs[PASSIVE_REPLY_AT], nowMs)
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

    /**
     * 记录一次协议端健康探测（2026-10-26；US-3125）。
     *
     * @param state `ONLINE` / `QQ_OFFLINE` / `UNREACHABLE`
     * @param offline true=本次检测到 QQ 离线（额外记一次离线时刻，供 UI 显示"最近离线"）
     */
    suspend fun recordProtocolHealth(
        state: String,
        detail: String,
        atMs: Long,
        offline: Boolean
    ) = edit { prefs ->
        prefs[PROTOCOL_STATE] = state.take(24)
        prefs[PROTOCOL_DETAIL] = detail.take(120)
        prefs[PROTOCOL_CHECKED_AT] = atMs
        if (offline) prefs[PROTOCOL_OFFLINE_AT] = atMs
    }

    /**
     * 推进一次**协议端失联告警判定**（M2；US-3128）。
     *
     * **为什么放在写入路径而不是读取路径**：告警状态是"事实的推进"（连续失败计数、是否已通知、
     * 节流锚点），必须在显式的此刻写入。`toState()` 保持只读——这条"读不写"原则来自 RC-5 的
     * `minOf` 收敛设计（B4 陷阱 3），破坏它会让 Compose 每次重组都写盘。
     *
     * 原子的「读上一份状态 → 调纯策略 → 写下一份状态」在**同一次 `edit`** 内完成，
     * 因此并发调用（60s 循环 + 手动刷新）不会出现丢失计数。
     *
     * @param probe 本次探测结果（[QqProtocolAlertPolicy.PROBE_ONLINE] 等）
     * @param nowMs 本次时刻
     * @param enabled 总开关（false 时策略返回清空且不告警，AC-8）
     * @param loopback 协议端地址是否回环（AC-6 归因）
     * @param detail 本次探测可读说明（已脱敏）
     * @return 策略决策（含要执行的通知动作）
     */
    suspend fun advanceProtocolAlert(
        probe: String,
        nowMs: Long,
        enabled: Boolean,
        loopback: Boolean,
        detail: String = ""
    ): QqProtocolAlertPolicy.Decision {
        var decision = QqProtocolAlertPolicy.Decision(
            QqProtocolAlertPolicy.State(),
            QqProtocolAlertPolicy.Action.None
        )
        edit { prefs ->
            val prev = readProtocolAlert(prefs)
            decision = QqProtocolAlertPolicy.decide(
                probe = probe,
                nowMs = nowMs,
                enabled = enabled,
                loopback = loopback,
                prev = prev,
                detail = detail
            )
            // 2026-11-01 验收：**状态未变就不写盘**。60s 一轮的探测里绝大多数时刻状态相同，
            // 无条件写 8 个 key 等于每分钟一次磁盘 IO（对锁屏续航有可测影响）。
            if (decision.state != prev) writeProtocolAlert(prefs, decision.state)
        }
        return decision
    }

    /**
     * 记一次**自动重启**记账（2026-11-01；US-3128；[QqProtocolRestartPolicy]）。
     *
     * 必须持久化：Prism 自己也会被 HyperOS 杀掉，内存计数清零后会出现"每次冷启动都重启一次"
     * 的崩溃循环。
     */
    suspend fun recordProtocolRestart(
        windowStartMs: Long,
        attempts: Int,
        lastRestartAtMs: Long
    ) = edit { prefs ->
        prefs[RESTART_WINDOW_START] = windowStartMs
        prefs[RESTART_ATTEMPTS] = attempts
        prefs[RESTART_LAST_AT] = lastRestartAtMs
    }

    /** 清空自动重启记账（用户手动启动/一键修复成功后调用）。 */
    suspend fun clearProtocolRestart() = edit { prefs ->
        prefs[RESTART_WINDOW_START] = 0L
        prefs[RESTART_ATTEMPTS] = 0
        prefs[RESTART_LAST_AT] = 0L
    }

    /**
     * 记一次"收到开机/升级恢复广播"（2026-11-14；ADR-072 F2）。
     *
     * 纯记账：下次冷启动据此判断"系统到底有没有把广播送到"。
     * 不抛异常（广播接收器里调用，失败不能影响恢复流程）。
     */
    suspend fun recordBootRecovery(action: String = "", nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            edit { prefs ->
                prefs[BOOT_RECOVERY_AT] = nowMs
            }
            android.util.Log.i("QqRuntimeStateRepository", "已记录开机恢复广播（$action）")
        }.onFailure {
            android.util.Log.w(
                "QqRuntimeStateRepository", "开机恢复记账失败（${it::class.simpleName}）"
            )
        }
    }

    /** 记一次"停摆补偿已提醒"（ADR-072 F2-2）。 */
    suspend fun recordStartupReport(nowMs: Long = System.currentTimeMillis()) = edit { prefs ->
        prefs[STARTUP_REPORT_AT] = nowMs
    }

    /** 读「前台化被拒」记账（次数 + 上次通知时刻）。 */
    /**
     * **原子**记一次「前台化被拒」（2026-11-15；ADR-073 / guardrail R2-01）。
     *
     * 为什么必须合成一次 `edit`：上一版是「read → 判断 → bump」三步，
     * 两次几乎同时的失败会读到同一个 prevCount → `attempt` 同档、**同刻发两条通知**。
     * DataStore 的 `edit` 在同一事务里串行，天然消除该竞态。
     *
     * @return (累加后的次数, 本次是否应发通知)；通知锚点只在应发时由本方法一并写回
     */
    suspend fun recordForegroundBlock(
        nowMs: Long,
        throttleMs: Long
    ): Pair<Int, Boolean> {
        var attempt = 1
        var shouldNotify = true
        edit { prefs ->
            attempt = (prefs[FGS_BLOCK_COUNT] ?: 0) + 1
            prefs[FGS_BLOCK_COUNT] = attempt
            val lastNotifyAt = prefs[FGS_BLOCK_NOTIFY_AT] ?: 0L
            val elapsed = nowMs - lastNotifyAt
            // guardrail R3-02：时钟回拨（elapsed<0）按"已过节流"处理，与告警策略的既有防御一致；
            // 否则改过系统时间后会被长期静默。
            shouldNotify = lastNotifyAt <= 0L || elapsed !in 0 until throttleMs
            if (shouldNotify) prefs[FGS_BLOCK_NOTIFY_AT] = nowMs
        }
        return attempt to shouldNotify
    }

    /** 读「前台化被拒」记账（诊断/UI 用；**不要**用它在写路径上做判断，见 [recordForegroundBlock]）。 */
    suspend fun readForegroundBlock(): Pair<Int, Long> {
        var result = 0 to 0L
        edit { prefs ->
            result = (prefs[FGS_BLOCK_COUNT] ?: 0) to (prefs[FGS_BLOCK_NOTIFY_AT] ?: 0L)
        }
        return result
    }

    /** 前台化成功 → 清零记账（连续失败才需要退避）。 */
    suspend fun resetForegroundBlock() = edit { prefs ->
        prefs[FGS_BLOCK_COUNT] = 0
    }

    /** 记录已回应过的收到消息指纹与时刻（US-3109 双向对话去重 + guardrail M-02 时间窗）。 */    suspend fun setLastReplied(hash: String, atMs: Long) = edit {
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

    /** 读回持久化的告警状态（只读；供 [advanceProtocolAlert] 与 [toState] 共用）。 */
    private fun readProtocolAlert(
        prefs: Preferences
    ): QqProtocolAlertPolicy.State = QqProtocolAlertPolicy.State(
        armedAtMs = prefs[ALERT_ARMED_AT] ?: 0L,
        onlineSinceMs = prefs[ALERT_ONLINE_SINCE] ?: 0L,
        unreachableStreak = prefs[ALERT_UNREACHABLE_STREAK] ?: 0,
        offlineStreak = prefs[ALERT_OFFLINE_STREAK] ?: 0,
        downNotified = prefs[ALERT_DOWN_NOTIFIED] ?: false,
        downKind = prefs[ALERT_DOWN_KIND] ?: "",
        downSinceMs = prefs[ALERT_DOWN_SINCE] ?: 0L,
        lastNotifyAtMs = prefs[ALERT_LAST_NOTIFY_AT] ?: 0L,
        flapCount = prefs[ALERT_FLAP_COUNT] ?: 0,
        lastKind = prefs[ALERT_LAST_KIND] ?: "",
        kindChangedAtMs = prefs[ALERT_KIND_CHANGED_AT] ?: 0L
    )

    /** 写回告警状态（只在显式写入路径调用）。 */
    private fun writeProtocolAlert(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        state: QqProtocolAlertPolicy.State
    ) {
        prefs[ALERT_ARMED_AT] = state.armedAtMs
        prefs[ALERT_ONLINE_SINCE] = state.onlineSinceMs
        prefs[ALERT_UNREACHABLE_STREAK] = state.unreachableStreak
        prefs[ALERT_OFFLINE_STREAK] = state.offlineStreak
        prefs[ALERT_DOWN_NOTIFIED] = state.downNotified
        prefs[ALERT_DOWN_KIND] = state.downKind
        prefs[ALERT_DOWN_SINCE] = state.downSinceMs
        prefs[ALERT_LAST_NOTIFY_AT] = state.lastNotifyAtMs
        prefs[ALERT_FLAP_COUNT] = state.flapCount
        prefs[ALERT_LAST_KIND] = state.lastKind
        prefs[ALERT_KIND_CHANGED_AT] = state.kindChangedAtMs
    }

    /**
     * 推进"当日日期键"，并在**跨天时同时重置两个计数**（2026-10-31 RC-4 修复）。
     *
     * **原缺陷**：`recordReplied` / `recordUnconfirmedReplied` 只把 `FIRED_DATE` 写成今天，
     * **没有重置 `SCHEDULED_COUNT`**。于是跨零点后若"先发生被动回复"（用户先说了句话），
     * 日期变成今天而定时数仍是昨天的值；`QqThrottle.decide` 的判据是
     * `firedDate == todayKey`（此时成立）→ `scheduledFiredCount >= maxPerDay` →
     * **当天剩余时间不再发任何定时主动消息**，且文案误导成"已达当日上限"。
     *
     * 真机证据：`fired_date=2026-10-27, fired_count=1, scheduled_count=4, max_per_day=3`，
     * 而 `recordFired` 里两个计数同增 ⇒ `scheduled(4) > fired(1)` 只可能来自昨天。
     *
     * 因此**所有**推进日期的写入必须走本函数：跨天一律把两个计数归零，
     * 调用方只需在 `sameDay` 基础上 +1。
     *
     * @return true=同一天（计数应继续累加）；false=已跨天（计数已被清零）
     */
    private fun advanceDay(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        todayKey: String
    ): Boolean {
        val sameDay = prefs[FIRED_DATE] == todayKey
        if (!sameDay) {
            prefs[FIRED_COUNT] = 0
            prefs[SCHEDULED_COUNT] = 0
            prefs[FIRED_DATE] = todayKey
        }
        return sameDay
    }

    /**
     * 追加一条被动回复时间戳并裁剪（RC-3）。
     *
     * 只保留最近 [QqRuntimeState.MAX_PASSIVE_TIMESTAMPS] 条，且淘汰超出滑动窗口 2 倍时长的旧值——
     * 既保证限流判定正确，又避免文件无限增长。
     */
    private fun appendPassiveTimestamp(existing: String?, nowMs: Long): String {
        val kept = existing.orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { ts -> ts > 0L && nowMs - ts < PASSIVE_TIMESTAMP_TTL_MS }
            .toMutableList()
        kept += nowMs
        return kept.takeLast(QqRuntimeState.MAX_PASSIVE_TIMESTAMPS).joinToString(",")
    }

    private fun toState(prefs: Preferences): QqRuntimeState = QqRuntimeState(
        plannedTriggerAt = prefs[PLANNED_AT] ?: 0L,
        lastFiredAt = prefs[LAST_FIRED_AT] ?: 0L,
        firedDate = prefs[FIRED_DATE] ?: "",
        firedCount = prefs[FIRED_COUNT] ?: 0,
        // 2026-10-31（RC-5 不变量自愈）：定时数**不可能大于**当日总数（recordFired 里两者同增）。
        // 出现 `scheduled > fired` 只可能是旧版本的跨天脏数据（真机实证 4 > 1），
        // 这里按总数收敛，使升级后**当天**就恢复可用，不必等到次日。
        scheduledFiredCount = minOf(
            prefs[SCHEDULED_COUNT] ?: 0,
            prefs[FIRED_COUNT] ?: 0
        ),
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
        retryCount = prefs[RETRY_COUNT] ?: 0,
        passiveReplyTimestamps = prefs[PASSIVE_REPLY_AT].orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() },
        lastProtocolState = prefs[PROTOCOL_STATE] ?: "",
        lastProtocolDetail = prefs[PROTOCOL_DETAIL] ?: "",
        lastProtocolCheckedAtMs = prefs[PROTOCOL_CHECKED_AT] ?: 0L,
        lastProtocolOfflineAtMs = prefs[PROTOCOL_OFFLINE_AT] ?: 0L,
        protocolAlert = readProtocolAlert(prefs),
        protocolRestartWindowStartMs = prefs[RESTART_WINDOW_START] ?: 0L,
        protocolRestartAttempts = prefs[RESTART_ATTEMPTS] ?: 0,
        protocolLastRestartAtMs = prefs[RESTART_LAST_AT] ?: 0L,
        bootRecoveryAtMs = prefs[BOOT_RECOVERY_AT] ?: 0L,
        startupReportAtMs = prefs[STARTUP_REPORT_AT] ?: 0L
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

        /** 被动回复时间戳（逗号分隔 epoch ms；RC-3 滑动窗口）。 */
        private val PASSIVE_REPLY_AT = stringPreferencesKey("qq_passive_reply_at")

        /** 协议端健康（2026-10-26；US-3125）。 */
        private val PROTOCOL_STATE = stringPreferencesKey("qq_protocol_state")
        private val PROTOCOL_DETAIL = stringPreferencesKey("qq_protocol_detail")
        private val PROTOCOL_CHECKED_AT = longPreferencesKey("qq_protocol_checked_at")
        private val PROTOCOL_OFFLINE_AT = longPreferencesKey("qq_protocol_offline_at")

        /** 协议端失联告警状态（M2；US-3128；[QqProtocolAlertPolicy]）。 */
        private val ALERT_ARMED_AT = longPreferencesKey("qq_alert_armed_at")
        private val ALERT_ONLINE_SINCE = longPreferencesKey("qq_alert_online_since")
        private val ALERT_UNREACHABLE_STREAK = intPreferencesKey("qq_alert_unreachable_streak")
        private val ALERT_OFFLINE_STREAK = intPreferencesKey("qq_alert_offline_streak")
        private val ALERT_DOWN_NOTIFIED = booleanPreferencesKey("qq_alert_down_notified")
        private val ALERT_DOWN_KIND = stringPreferencesKey("qq_alert_down_kind")
        private val ALERT_DOWN_SINCE = longPreferencesKey("qq_alert_down_since")
        private val ALERT_LAST_NOTIFY_AT = longPreferencesKey("qq_alert_last_notify_at")

        /** 本轮失联期间故障类型切换次数（ADR-073 抖动判据）。 */
        private val ALERT_FLAP_COUNT = intPreferencesKey("qq_alert_flap_count")

        /** 最近观测到的故障类型（ADR-073；未通知也记录，用于抖动判据）。 */
        private val ALERT_LAST_KIND = stringPreferencesKey("qq_alert_last_kind")

        /** 最近一次故障类型**切换**的时刻（ADR-073；用于判"kind 已稳定多久"）。 */
        private val ALERT_KIND_CHANGED_AT = longPreferencesKey("qq_alert_kind_changed_at")

        /** 协议端自动重启记账（US-3128）。 */
        private val RESTART_WINDOW_START = longPreferencesKey("qq_restart_window_start")
        private val RESTART_ATTEMPTS = intPreferencesKey("qq_restart_attempts")
        private val RESTART_LAST_AT = longPreferencesKey("qq_restart_last_at")

        /** 时间戳保留时长（滑动窗口的 2 倍，超出即淘汰）。 */
        private const val PASSIVE_TIMESTAMP_TTL_MS = 5 * 60_000L

        /** 开机/升级恢复广播记账（ADR-072 F2）。 */
        private val BOOT_RECOVERY_AT = longPreferencesKey("qq_boot_recovery_at")

        /** 停摆补偿提醒节流锚点（ADR-072 F2-2）。 */
        private val STARTUP_REPORT_AT = longPreferencesKey("qq_startup_report_at")

    /**
     * 「前台服务被系统拒绝」的连续次数与上次通知时刻（2026-11-15；ADR-073 / guardrail R-01）。
     *
     * **为什么必须落盘**：失败路径每次 `stopSelf()`，下次被闹钟唤起是**新的 Service 实例**，
     * 实例字段一律归零 → `attempt` 恒为 1，5/10/20/30 分钟退避与 30 分钟通知节流**全部失效**，
     * 真机表现为"每 5 分钟一条高优提醒"（正是 177 次拒绝的形态）。
     */
    private val FGS_BLOCK_COUNT = intPreferencesKey("qq_fgs_block_count")
    private val FGS_BLOCK_NOTIFY_AT = longPreferencesKey("qq_fgs_block_notify_at")
    }
}
