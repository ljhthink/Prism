package io.prism.qq

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
import kotlin.random.Random

/**
 * 随机定时主动消息的时刻规划（ADR-063；PRD US-3108 AC-2 / US-3115 AC-1）。
 *
 * **目标**：产生**非等间隔**且**带抖动**的触发时刻，以营造"真人随机想起你"的观感
 * （对齐 PNAS 人机通信"泊松发起 + 突发"双模分布；固定时刻表可被识破，故否决）。
 *
 * **纯逻辑**：不依赖 Android；时钟以毫秒入参、随机源以 [Random] 注入 → 纯 JVM 可测。
 *
 * **两种策略**（ADR-063 Decision）：
 * - [Strategy.UNIFORM]（默认）：在生效时间窗内**均匀随机采样**——随机点本身即非等距。
 * - [Strategy.POISSON]：以**指数分布间隔**（泊松过程到达间隔）采样，更"成串/突发"。
 *
 * **跨零点（ADR-063 修订 2026-10-25）**：窗口语义为 `[start, end)`，当 `start > end`
 * 时窗口跨到**次日**（如 22:00–02:00 = 22:00 到次日 02:00）。因此"当前是否在窗内"
 * 必须同时检查**昨日开启的窗口**（offset=-1），否则凌晨 00:30 会被误判为"窗已过"。
 */
object QqSchedulePlanner {

    /** 随机化策略。 */
    enum class Strategy { UNIFORM, POISSON }

    private const val DAY_MS: Long = 24 * 60 * 60 * 1000L
    private const val MINUTE_MS: Long = 60_000L

    /** 向前/向后搜索的日偏移范围（-1 覆盖跨零点窗口，+2 覆盖 ≥24h 的最小间隔）。 */
    private val DAY_OFFSETS = -1..2

    /**
     * 计算"下一次触发时刻"（epoch 毫秒）。
     *
     * 规则（PRD US-3108 AC-2 / AC-6；US-3115 AC-1）：
     * 1. 窗口按**当日本地时区**解释为 `[start, end)`；`start > end` 时跨到次日；
     * 2. 生效下界 = `max(窗起, now + minGapMs)`（保证不小于最小间隔）；
     * 3. 当日窗（含跨零点窗口的次日部分）已过 → 顺延到下一个窗口；
     * 4. 在 `[下界, 窗止)` 内按 [strategy] 采样一个时刻。
     *
     * @param nowMs 当前时刻（epoch 毫秒）
     * @param windowStartMinute 窗起（当日分钟，含）
     * @param windowEndMinute 窗止（当日分钟，不含；`start > end` 表示次日）
     * @param minGapMs 距现在的最小间隔（节流，来自 [QqConfig.minIntervalMs]）
     * @param random 随机源（测试注入 seed 随机）
     * @param strategy 随机化策略
     * @param zone 时区（默认系统时区；测试注入固定时区）
     * @return 下一次触发时刻；窗口非法（越界或 `start == end`）时返回 null
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
        // N-6：负值/越界一律拒绝（防 NaN 式脏值流入）
        if (!QqTimeOfDay.isValidWindow(windowStartMinute, windowEndMinute)) return null

        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        // 跨零点等价于「窗止 +1 天」；同值非法（窗口长度为 0，永不触发）
        val endOffsetMinutes = if (windowEndMinute > windowStartMinute) {
            windowEndMinute
        } else {
            windowEndMinute + QqTimeOfDay.MINUTES_PER_DAY
        }
        val lower = nowMs + minGapMs.coerceAtLeast(0L)

        for (dayOffset in DAY_OFFSETS) {
            val base = dayStart + dayOffset * DAY_MS
            val start = base + windowStartMinute * MINUTE_MS
            val end = base + endOffsetMinutes * MINUTE_MS
            val from = maxOf(start, lower)
            if (from < end) return sample(from, end, random, strategy)
        }
        return null
    }

    /**
     * 判断给定时刻是否落在其"当日"的时间窗内（N-9：复用已持久化的旧计划前，须校验其
     * 仍在**当前配置**的窗口内——否则用户改窗后旧计划可能落在新窗口之外，违反 US-3108 AC-2）。
     *
     * 跨零点窗口需要检查**昨日开启的那一段**（凌晨时段），因此偏移取 -1 与 0。
     *
     * @return true=落在窗口内；窗口非法时一律 false（调用方应重算计划）
     */
    fun isWithinWindow(
        atMs: Long,
        windowStartMinute: Int,
        windowEndMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!QqTimeOfDay.isValidWindow(windowStartMinute, windowEndMinute)) return false
        val dayStart = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val endOffsetMinutes = if (windowEndMinute > windowStartMinute) {
            windowEndMinute
        } else {
            windowEndMinute + QqTimeOfDay.MINUTES_PER_DAY
        }
        for (dayOffset in -1..0) {
            val base = dayStart + dayOffset * DAY_MS
            val start = base + windowStartMinute * MINUTE_MS
            val end = base + endOffsetMinutes * MINUTE_MS
            if (atMs >= start && atMs < end) return true
        }
        return false
    }

    /**
     * 给定窗口实例锚点，返回**下一个**窗口实例的起点（guardrail **G-r3-03**）。
     *
     * 用途：本窗口重试预算耗尽后，把计划推到下一窗口起点——否则 `clearPlan` 会让心跳在
     * 同一窗口重新 `planNext`，反复注册闹钟/拉起前台服务/再短路（窗口剩 3h 可空转 3~6 轮，
     * 还会把"上次结果"反复刷成 SKIP）。
     */
    fun nextWindowAnchor(anchor: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (anchor <= 0L) return 0L
        val date = Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() +
            // 保留原锚点的"当日分钟"偏移（锚点 = 当日 00:00 + start）
            (anchor - date.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    /**
     * 「窗口实例」的锚点（guardrail **G-r2-02**）。
     *
     * **为什么不能用日期作键**：跨零点窗口（22:00–02:00）在 23:30 与次日 00:30 属于**同一个**
     * 窗口实例，但 `yyyy-MM-dd` 已经翻页——用它当重试预算的键会在午夜清零，导致"熄屏整夜
     * 反复点亮屏幕"。
     *
     * 因此锚点取**该窗口实例起点的 epoch 毫秒**：同一实例恒定，跨日不漂移。
     *
     * @return 覆盖 [atMs] 的窗口实例起点；不在任何窗口内时返回**下一个**窗口实例的起点
     *   （保证"已放弃"的判定也稳定，不会因为落在窗外就换键）
     */
    fun windowAnchor(
        atMs: Long,
        windowStartMinute: Int,
        windowEndMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        // 与其他三个函数一致：窗口非法时返回 0（调用方不得据此做预算判定）
        if (!QqTimeOfDay.isValidWindow(windowStartMinute, windowEndMinute)) return 0L
        val dayStart = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val endOffsetMinutes = if (windowEndMinute > windowStartMinute) {
            windowEndMinute
        } else {
            windowEndMinute + QqTimeOfDay.MINUTES_PER_DAY
        }
        // 先找"包含此刻"的实例（含昨日开启的跨零点段），否则找最近的未来实例
        for (dayOffset in -1..0) {
            val base = dayStart + dayOffset * DAY_MS
            val start = base + windowStartMinute * MINUTE_MS
            val end = base + endOffsetMinutes * MINUTE_MS
            if (atMs >= start && atMs < end) return start
        }
        // 不在窗内：返回下一个起点（今日未开始则今日，否则明日）
        val todayStart = dayStart + windowStartMinute * MINUTE_MS
        return if (todayStart > atMs) todayStart else todayStart + DAY_MS
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
