package io.prism.qq

/**
 * 「每日时刻」的解析、格式化与校验（ADR-063 修订 2026-10-25；PRD US-3115 AC-1）。
 *
 * **背景**：设置页与 LLM 工具此前直接暴露「当日分钟」（0~1439），用户需要自己把
 * `7:00` 换算成 `420`，体验极差（真机反馈）。现在**对外一律用 `HH:mm`**，
 * 存储层仍沿用「当日分钟」（向后兼容，不迁移既有数据）。
 *
 * **为什么是纯函数**：解析是唯一的边界密集点（大小写、缺省冒号、24:00、越界、跨零点），
 * 全部纯 JVM 可测；UI 与工具共用同一实现，避免两处口径漂移。
 */
object QqTimeOfDay {

    /** 一天分钟数（与 [QqConfig.MINUTES_PER_DAY] 同源）。 */
    const val MINUTES_PER_DAY: Int = QqConfig.MINUTES_PER_DAY

    /**
     * 解析 `HH:mm`（宽松：允许 `7:00`、`07:00`、`7`、`7:5`；允许 `24:00` 表示当日结束）。
     *
     * @return 当日分钟（0~1440）；非法输入返回 null
     */
    fun parse(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = PATTERN.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        if (hour !in 0..24) return null
        val total = hour * 60 + minute
        // 24:00 = 当日结束（一天的下界）；24:01 之类非法
        if (hour == 24 && minute != 0) return null
        return total
    }

    /**
     * 格式化为 `HH:mm`（1440 → `24:00`）。
     *
     * @param minutes 当日分钟（越界按 0 钳制）
     */
    fun format(minutes: Int): String {
        val v = minutes.coerceIn(0, MINUTES_PER_DAY)
        val h = v / 60
        val m = v % 60
        return "%02d:%02d".format(h, m)
    }

    /**
     * 活跃时段是否合法（PRD US-3115 AC-1；ADR-063 修订）。
     *
     * 规则：`0 <= start < 1440`、`0 < end <= 1440`、`start != end`。
     * **允许 `start > end`** —— 语义为跨零点（如 22:00–02:00）；
     * 起止相同没有可用的时间跨度，视为非法（避免"窗口长度 0"退化为永不触发）。
     */
    fun isValidWindow(startMinute: Int, endMinute: Int): Boolean =
        startMinute in 0 until MINUTES_PER_DAY &&
            endMinute in 1..MINUTES_PER_DAY &&
            startMinute != endMinute

    /** 该时段是否跨零点（`start > end`）。 */
    fun crossesMidnight(startMinute: Int, endMinute: Int): Boolean = startMinute > endMinute

    /**
     * 面向用户/LLM 的时段描述（`07:00-22:00`；跨零点追加「次日」说明）。
     */
    fun describe(startMinute: Int, endMinute: Int): String {
        val base = "${format(startMinute)}-${format(endMinute)}"
        return if (isValidWindow(startMinute, endMinute) && crossesMidnight(startMinute, endMinute)) {
            "$base（跨零点，次日 ${format(endMinute)} 结束）"
        } else {
            base
        }
    }

    /** `HH:mm` 语法：1~2 位小时，可选 `:mm`（分钟 1~2 位）。 */
    private val PATTERN = Regex("^(\\d{1,2})(?::(\\d{1,2}))?$")
}
