package io.prism.qq

import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 回复节奏的**拟人延迟**模型（2026-11-16 新增；ADR-077）。
 *
 * ## 为什么要有这一层
 *
 * 真机取证（2026-11-16 22:43–22:55）：`生成耗时` 就是"用户发出 → 小号回复"的全部耗时，
 * 实测 **2420 / 2678 / 3140 / 3799 / 4389 ms**——回复前**没有任何延迟层**，用户感知是
 * "秒回"，反而制造压力。多气泡之间也只有固定 700 ms，与内容长度、时段、思考量无关。
 *
 * ## 模型来源（不自己发明）
 *
 * [Stephanie2（EMNLP 2026）](https://aclanthology.org/2026.emnlp-main.501/) 的实测结论：
 * 逐步聊天系统"缺乏主动等待机制、消息节奏不自然"，正确做法是把延迟建模为
 * **思考时间 + 打字时间**之和。本实现照此拆成两项：
 *
 * ```text
 * 思考时间 = base × 时段系数 × 对话间隔系数 × 抖动
 * 打字时间 = 正文字数 / 打字速度
 * 总延迟   = clamp(思考 + 打字, minDelay, maxDelay)
 * ```
 *
 * ## 约束
 *
 * - **纯函数**，随机源与时间均可注入（便于 JVM 单测）。
 * - 单轮上限 [Config.maxDelayMs]（默认 25 s）：超过会让用户以为卡死，宁可"显得急一点"。
 * - 与 [QqThrottle.PASSIVE_MIN_GAP_MS]（3 s，防双发）语义不同：那是**安全闸**，
 *   这里是**表演层**；两者叠加时以较大者为准（节奏延迟通常远大于 3 s）。
 * - 关闭开关时返回 0，行为退回 ADR-076 之前（便于对照与排障）。
 */
object QqReplyPacing {

    /**
     * 节奏配置（默认值 = 用户 2026-11-16 裁定的"中档"）。
     *
     * @param charsPerSecond 打字速度（真人中文输入实测 5–10 字/秒，取 7 为中止点）
     * @param thinkingBaseMs 思考时间基准（中档 1.5–3 s 的均值取 2 s）
     * @param minDelayMs 单轮下限：再快也不早于这个时间（否则仍像机器）
     * @param maxDelayMs 单轮上限：超过就是"卡死"而非"在打字"
     */
    data class Config(
        val enabled: Boolean = true,
        val charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
        val thinkingBaseMs: Long = DEFAULT_THINKING_BASE_MS,
        val minDelayMs: Long = DEFAULT_MIN_DELAY_MS,
        val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS
    ) {
        /**
         * 收敛到安全区间（设置页用户可乱填）。
         *
         * **2026-11-16 自查修复（会抛异常的缺陷）**：原实现写成一次 `copy(...)`，其中
         * `maxDelayMs.coerceIn(minDelayMs.coerceAtLeast(1L), MAX_DELAY_CEILING_MS)` 引用的
         * 是**未收敛的原始** `minDelayMs`（`copy` 的参数在赋值前求值）。用户填
         * `minDelayMs=500_000` + `maxDelayMs=100` 时，下界 500000 > 上界 90000 →
         * `coerceIn` 抛 `IllegalArgumentException`，且它在**读配置**路径上，会直接崩掉回复链路。
         * 现改为**两步**：先收敛 min，再用收敛后的 min 作为 max 的下界，并确保上界恒 ≥ 下界。
         *
         * `Double` 的 NaN 也一并处理：`NaN.coerceIn(a, b)` 因比较恒 false 会**原样返回 NaN**
         * （绕过收敛），随后 `字数 / NaN = NaN` → `toLong()` 得 0，打字时间静默失效。
         */
        fun sanitized(): Config {
            val minFinal = minDelayMs.coerceIn(0L, MAX_MIN_DELAY_MS)
            // 上界恒不小于下界：先把 ceiling 抬到至少 minFinal，避免 coerceIn 抛异常
            val ceiling = MAX_DELAY_CEILING_MS.coerceAtLeast(minFinal)
            val maxFinal = maxDelayMs.coerceIn(minFinal, ceiling)
            val cps = if (charsPerSecond.isNaN()) DEFAULT_CHARS_PER_SECOND
            else charsPerSecond.coerceIn(MIN_CHARS_PER_SECOND, MAX_CHARS_PER_SECOND)
            val thinking = thinkingBaseMs.coerceIn(MIN_THINKING_MS, MAX_THINKING_MS)
            return copy(
                charsPerSecond = cps,
                thinkingBaseMs = thinking,
                minDelayMs = minFinal,
                maxDelayMs = maxFinal
            )
        }
    }

    /**
     * 计算本轮回复开始前的等待时长。
     *
     * @param replyText 模型生成的完整回复正文（用于估算打字时间；含指令标记也不影响量级）
     * @param now 当前时刻（决定时段系数）
     * @param lastMessageAtMs 上一条消息时间（毫秒；0=未知 → 不加间隔系数）
     * @param config 节奏配置
     * @param random 随机源（测试注入）
     * @return 需要等待的毫秒数（≥0）
     */
    fun delayMsFor(
        replyText: String,
        now: LocalDateTime,
        lastMessageAtMs: Long,
        config: Config,
        random: Random = Random.Default
    ): Long {
        val cfg = config.sanitized()
        if (!cfg.enabled) return 0L
        val thinking = thinkingMs(now, lastMessageAtMs, cfg, random)
        val typing = typingMs(replyText, cfg)
        // 防御：copy() 若被绕过 sanitized() 直接构造（单测/未来调用方），仍不得抛异常
        val lo = cfg.minDelayMs
        val hi = cfg.maxDelayMs.coerceAtLeast(lo)
        return (thinking + typing).coerceIn(lo, hi)
    }

    /**
     * 多气泡场景下，**每个气泡之间**的间隔。
     *
     * 2026-11-16 起取代 ADR-076 的固定 `BUBBLE_GAP_MS=700ms`：真人补一句的间隔
     * 取决于那句有多长（打"嗯"几乎立刻，打一整句要几秒）。
     *
     * @return 与 [bubbles] 等长的列表，`[i]` 表示"发完第 i 条后"应等待的毫秒数
     *   （最后一项为 0——发完就不再等）
     */
    fun bubbleGapsMs(
        bubbles: List<String>,
        config: Config,
        random: Random = Random.Default
    ): List<Long> {
        val cfg = config.sanitized()
        if (bubbles.isEmpty()) return emptyList()
        if (!cfg.enabled) {
            return bubbles.indices.map { if (it < bubbles.lastIndex) LEGACY_BUBBLE_GAP_MS else 0L }
        }
        return bubbles.indices.map { index ->
            if (index >= bubbles.lastIndex) {
                0L
            } else {
                // 同一气泡内的"打完这句 + 想到下一句"：打字时间 + 一点抖动
                val typing = typingMs(bubbles[index], cfg)
                (typing + jitterMs(BUBBLE_THINK_BASE_MS, random))
                    .coerceIn(BUBBLE_GAP_FLOOR_MS, BUBBLE_GAP_CEILING_MS)
            }
        }
    }

    // ==================== 内部分解（纯函数，便于单测逐项验证） ====================

    /** 思考时间：基准 × 时段 × 间隔 × 抖动。 */
    internal fun thinkingMs(
        now: LocalDateTime,
        lastMessageAtMs: Long,
        config: Config,
        random: Random
    ): Long {
        val hourFactor = when (now.hour) {
            // 深夜/凌晨：人困了，反应慢（也正好避免"半夜秒回"的失真）
            in 0..5 -> 1.45
            // 清晨刚醒
            in 6..8 -> 1.15
            // 白天正常
            in 9..17 -> 1.0
            // 晚间活跃
            in 18..23 -> 0.9
            else -> 1.0
        }
        val gapFactor = gapFactor(lastMessageAtMs, now)
        val base = config.thinkingBaseMs * hourFactor * gapFactor
        return (base + jitterMs(base * JITTER_RATIO, random)).toLong().coerceAtLeast(0L)
    }

    /**
     * 对话间隔系数。
     *
     * - **刚聊过**（<2 min）：还在同一个话头上，几乎不用"重新想"→ 0.6
     * - **隔了一会儿**（2–30 min）：正常 → 1.0
     * - **久未联系**（>30 min，尤其隔夜）：得先看一眼上下文 → 1.25
     */
    internal fun gapFactor(lastMessageAtMs: Long, now: LocalDateTime): Double {
        if (lastMessageAtMs <= 0L) return 1.0
        val nowMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delta = nowMs - lastMessageAtMs
        if (delta <= 0L) return 1.0
        val minutes = delta / 60_000L
        return when {
            minutes < 2L -> 0.6
            minutes < 30L -> 1.0
            minutes < 60L * 12 -> 1.25
            // 隔夜：还得"回想一下昨天"
            else -> 1.4
        }
    }

    /** 打字时间：字数 / 速度。按"可见字符"计（不含空白），空文本至少按 1 字算。 */
    internal fun typingMs(replyText: String, config: Config): Long {
        val visible = replyText.count { !it.isWhitespace() }.coerceAtLeast(1)
        return (visible / config.charsPerSecond * 1000.0).toLong()
    }

    /**
     * 抖动：以 [spread] 为尺度的对称三角分布（比均匀分布更接近"人有时快有时慢"）。
     *
     * 不用 `nextGaussian()` 的原因：Java 的 `nextGaussian` 无界，极端值会偶发
     * 把单轮延迟推到上限，真机表现为"偶尔卡一下"。三角分布有界且实现简单。
     */
    internal fun jitterMs(spread: Double, random: Random): Long {
        if (spread <= 0.0) return 0L
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        return ((u1 + u2) / 2.0 * 2.0 - 1.0).times(spread).toLong()
    }

    // ==================== 默认值与安全区间 ====================

    /** 默认打字速度（字/秒）。中档 7：短句 2–4 字 ≈ 0.3–0.6 s，长句 60 字 ≈ 8.6 s。 */
    const val DEFAULT_CHARS_PER_SECOND: Double = 7.0
    const val MIN_CHARS_PER_SECOND: Double = 2.0
    const val MAX_CHARS_PER_SECOND: Double = 20.0

    /** 默认思考基准（毫秒）。 */
    const val DEFAULT_THINKING_BASE_MS: Long = 2_000L
    const val MIN_THINKING_MS: Long = 300L
    const val MAX_THINKING_MS: Long = 12_000L

    /** 默认上下限。 */
    const val DEFAULT_MIN_DELAY_MS: Long = 1_500L
    const val DEFAULT_MAX_DELAY_MS: Long = 25_000L
    const val MAX_MIN_DELAY_MS: Long = 10_000L
    const val MAX_DELAY_CEILING_MS: Long = 90_000L

    /** 抖动幅度（相对思考基准的比例）。 */
    internal const val JITTER_RATIO = 0.35

    /** 气泡之间的额外"想到下一句"基准与上下限。 */
    internal const val BUBBLE_THINK_BASE_MS: Double = 450.0
    internal const val BUBBLE_GAP_FLOOR_MS: Long = 250L
    internal const val BUBBLE_GAP_CEILING_MS: Long = 4_000L

    /** 关闭开关时的旧行为（ADR-076 的固定间隔）。 */
    const val LEGACY_BUBBLE_GAP_MS: Long = 700L
}
