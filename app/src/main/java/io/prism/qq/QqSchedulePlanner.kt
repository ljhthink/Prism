package io.prism.qq

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
import kotlin.random.Random

/**
 * 随机定时主动消息的时刻规划（ADR-063；PRD US-3108 AC-2）。
 *
 * **目标**：产生**非等间隔**且**带抖动**的触发时刻，以营造"真人随机想起你"的观感
 * （对齐 PNAS 人机通信"泊松发起 + 突发"双模分布；固定时刻表可被识破，故否决）。
 *
 * **纯逻辑**：不依赖 Android；时钟以毫秒入参、随机源以 [Random] 注入 → 纯 JVM 可测。
 *
 * **两种策略**（ADR-063 Decision）：
 * - [Strategy.UNIFORM]（默认）：在生效时间窗内**均匀随机采样**——随机点本身即非等距。
 * - [Strategy.POISSON]：以**指数分布间隔**（泊松过程到达间隔）采样，更"成串/突发"。
 */
object QqSchedulePlanner {

    /** 随机化策略。 */
    enum class Strategy { UNIFORM, POISSON }

    private const val DAY_MS: Long = 24 * 60 * 60 * 1000L
    private const val MINUTE_MS: Long = 60_000L

    /**
     * 计算"下一次触发时刻"（epoch 毫秒）。
     *
     * 规则（PRD US-3108 AC-2 / AC-6）：
     * 1. 时间窗按**当日本地时区**解释为 `[windowStartMinute, windowEndMinute)`；
     * 2. 生效下界 = `max(窗起, now + minGapMs)`（保证不小于最小间隔）；
     * 3. 若当日下界已越过窗止 → 顺延到**明日整窗**；
     * 4. 在 `[下界, 窗止)` 内按 [strategy] 采样一个时刻。
     *
     * @param nowMs 当前时刻（epoch 毫秒）
     * @param windowStartMinute 窗起（当日分钟，含）
     * @param windowEndMinute 窗止（当日分钟，不含）
     * @param minGapMs 距现在的最小间隔（节流，来自 [QqConfig.minIntervalMs]）
     * @param random 随机源（测试注入 seed 随机）
     * @param strategy 随机化策略
     * @param zone 时区（默认系统时区；测试注入固定时区）
     * @return 下一次触发时刻；窗口非法（`start >= end`）时返回 null
     */
    fun nextTriggerAt(
        nowMs: Long,
        windowStartMinute: Int,
        windowEndMinute: Int,
        minGapMs: Long,
        random: Random,
        strategy: Strategy = Strategy.UNIFORM,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (windowStartMinute < 0) return null // N-6：负值非法（防 NaN 式脏值流入）
        if (windowStartMinute >= windowEndMinute) return null
        if (windowEndMinute > QqConfig.MINUTES_PER_DAY) return null

        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        var start = dayStart + windowStartMinute * MINUTE_MS
        var end = dayStart + windowEndMinute * MINUTE_MS
        var lower = maxOf(start, nowMs + minGapMs.coerceAtLeast(0L))

        if (lower >= end) {
            // 今日窗已无可用空间 → 顺延明日（CR-6：minGap 约束同样适用于明日下界）
            start += DAY_MS
            end += DAY_MS
            lower = maxOf(start, nowMs + minGapMs.coerceAtLeast(0L))
        }
        return sample(lower, end, random, strategy)
    }

    /**
     * 判断给定时刻是否落在其"当日"的时间窗内（N-9：复用已持久化的旧计划前，须校验其
     * 仍在**当前配置**的窗口内——否则用户改窗后旧计划可能落在新窗口之外，违反 US-3108 AC-2）。
     *
     * @return true=落在窗口内；窗口非法时一律 false（调用方应重算计划）
     */
    fun isWithinWindow(
        atMs: Long,
        windowStartMinute: Int,
        windowEndMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (windowStartMinute < 0) return false
        if (windowStartMinute >= windowEndMinute) return false
        if (windowEndMinute > QqConfig.MINUTES_PER_DAY) return false
        val dayStart = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return atMs >= dayStart + windowStartMinute * MINUTE_MS &&
            atMs < dayStart + windowEndMinute * MINUTE_MS
    }

    /**
     * 在 `[lower, end)` 内采样一个时刻。
     *
     * - UNIFORM：均匀随机（非等距）。
     * - POISSON：指数分布间隔（`-ln(1-U) * mean`），mean 取半窗——产生偏向前段的突发节奏，
     *   并钳制在 `end - 1` 内。
     */
    private fun sample(lower: Long, end: Long, random: Random, strategy: Strategy): Long? {
        val span = end - lower
        if (span <= 1L) return lower.takeIf { it < end }
        return when (strategy) {
            Strategy.UNIFORM -> lower + random.nextLong(span)
            Strategy.POISSON -> {
                val mean = (span / 2.0).coerceAtLeast(1.0)
                val u = 1.0 - random.nextDouble()
                val gap = if (u <= 0.0) 0L else (-ln(u) * mean).toLong()
                lower + gap.coerceIn(0L, span - 1L)
            }
        }
    }
}
