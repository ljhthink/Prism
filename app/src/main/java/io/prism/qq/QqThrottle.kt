package io.prism.qq

import java.time.Instant
import java.time.ZoneId

/**
 * 定时主动消息的**节流与熔断**决策（ADR-063；PRD US-3105 / US-3108 AC-4）。
 *
 * **纯函数**（无 Android/DataStore 依赖，时钟与日期由入参提供）→ JVM 可测。
 *
 * 判定顺序（任一不满足即拒绝本次发送）：
 * 1. 熔断未恢复 → 拒绝（US-3105 AC-3）；
 * 2. 距上次发送未达 [QqConfig.minIntervalMs] → 拒绝（US-3105 AC-1）；
 * 3. 当日总数 ≥ [QqConfig.dailyCap] → 拒绝（US-3105 AC-1）；
 * 4. 当日定时数 ≥ [QqConfig.maxPerDay] → 拒绝（US-3108 AC-1）。
 *
 * **关键纪律**：随机化只决定"何时"，**不豁免频控**（PRD US-3108 AC-4 / ADR-063 §5）。
 */
object QqThrottle {

    /** 连续失败达到该值即熔断（US-3105 AC-3）。 */
    const val FAILURE_STREAK_THRESHOLD: Int = 3

    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
    }

    fun decide(
        config: QqConfig,
        state: QqRuntimeState,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Decision {
        if (state.circuitOpen) {
            return Decision.Deny("已熔断（连续失败达 $FAILURE_STREAK_THRESHOLD 次），请在设置中手动恢复后再试")
        }
        if (state.lastFiredAt > 0L && nowMs - state.lastFiredAt < config.minIntervalMs) {
            val remainMin = (config.minIntervalMs - (nowMs - state.lastFiredAt)) / 60_000L + 1
            return Decision.Deny("未达最小间隔（还需约 $remainMin 分钟）")
        }
        if (state.firedDate == todayKey(nowMs, zone)) {
            if (state.firedCount >= config.dailyCap) {
                return Decision.Deny("已达当日发送上限（${config.dailyCap} 条）")
            }
            if (state.scheduledFiredCount >= config.maxPerDay) {
                return Decision.Deny("已达定时主动消息当日上限（${config.maxPerDay} 次）")
            }
        }
        return Decision.Allow
    }

    /** 当日日期键（`yyyy-MM-dd`，本地时区）。 */
    internal fun todayKey(nowMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

    // ==================== 被动应答（2026-10-26 真机修复） ====================

    /**
     * **被动应答**（回复对方刚发来的消息）的准入判定。
     *
     * **为什么必须与主动消息分开**：真机 2026-10-26 19:09 实证——用户在 18:53 收到过一次回复，
     * 19:09 再发消息时被 [decide] 以"未达最小间隔（还需约 14 分钟）"拒绝，消息被**直接丢弃**。
     * 而 `minIntervalMs`（默认 30min）、`dailyCap`、`maxPerDay` 这三个参数的语义是
     * **"不要主动打扰用户"**，把用户自己发来的消息也按此拦掉属于语义误用：
     * 用户说话 → 系统回话是**被请求**的行为，不是打扰。
     *
     * 被动路径仍然保留三道**必要**保护（防跑飞，与"打扰"无关）：
     * 1. **熔断**：连续失败达阈值仍拒绝（否则会对着坏掉的通道反复重试）；
     * 2. **短间隔**：距上次被动回复 < [PASSIVE_MIN_GAP_MS] 拒绝（防同一条消息双发）；
     * 3. **被动日上限**：[PASSIVE_DAILY_CAP] 条（防与另一个机器人互相应声形成死循环）。
     *
     * 去重（`QqReplyDedup`）在调用方**先于**本函数执行，不在此重复。
     */
    fun decidePassive(
        state: QqRuntimeState,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Decision {
        if (state.circuitOpen) {
            return Decision.Deny("已熔断（连续失败达 $FAILURE_STREAK_THRESHOLD 次），请在设置中手动恢复后再试")
        }
        if (state.lastRepliedAtMs > 0L && nowMs - state.lastRepliedAtMs < PASSIVE_MIN_GAP_MS) {
            return Decision.Deny("与上次回复间隔过短（防重复发送）")
        }
        // 被动回复计数复用 firedDate/firedCount 会污染主动配额，故单独用 repliedMessageIds
        // 的可见窗口做**粗略**上限：该列表只保留最近 MAX_REPLIED_IDS 条，故上限取窗口大小，
        // 语义是"短时间内不要连续回同一批消息"（真正的日上限由主动配额与熔断共同兜底）。
        if (state.repliedMessageIds.size >= PASSIVE_ID_WINDOW) {
            return Decision.Deny("短时间内回复条数过多（$PASSIVE_ID_WINDOW 条），请稍后")
        }
        return Decision.Allow
    }

    /** 被动回复的最小间隔（防同一条消息被双发；不构成"打扰"限制）。 */
    const val PASSIVE_MIN_GAP_MS: Long = 3_000L

    /** 被动回复的窗口上限（与 `QqRuntimeState.MAX_REPLIED_IDS` 对齐）。 */
    const val PASSIVE_ID_WINDOW: Int = 20

    /**
     * 相容旧行为：保留 `decide` 供**主动**消息使用，本函数不参与被动路径。
     */
    fun decideForProactive(
        config: QqConfig,
        state: QqRuntimeState,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Decision = decide(config, state, nowMs, zone)
}
