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
}
