package io.prism.qq

import io.prism.qq.channel.QqTransport

/**
 * QQ 小号助手配置（ADR-062 路线 / ADR-063 调度；PRD US-3101 / US-3105 / US-3108）。
 *
 * **为什么集中在一个值对象**（Karpathy §2 简洁优先）：本功能所有可调参数都属于
 * 同一条链路（开关 → 节流 → 定时窗口），拆成多个仓库只会增加装配点而无收益。
 *
 * **读侧钳制**（对齐 [io.prism.pet.PetConfigRepository] 纪律）：DataStore 中可能是旧版本
 * 残留或被外部写入，所有读取路径统一经 [clamped]，非法值不流入业务逻辑；
 * **写侧即钳制**：所有 setter 先钳再落盘。
 */
data class QqConfig(
    /** 总开关（PRD US-3101 AC-1，默认关闭）。 */
    val enabled: Boolean = false,
    /** 风险告知已确认（PRD US-3101 AC-2，未确认不得开启）。 */
    val riskAccepted: Boolean = false,
    /** 最小发送间隔（US-3105 AC-1）。 */
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** 每日发送上限（US-3105 AC-1）。 */
    val dailyCap: Int = DEFAULT_DAILY_CAP,
    /** 随机定时主动消息开关（US-3108，默认关闭）。 */
    val scheduleEnabled: Boolean = false,
    /** 随机时间窗起（当日分钟，含）。 */
    val windowStartMinute: Int = DEFAULT_WINDOW_START_MINUTE,
    /** 随机时间窗止（当日分钟，不含）。 */
    val windowEndMinute: Int = DEFAULT_WINDOW_END_MINUTE,
    /** 定时任务每日次数上限（US-3108 AC-1）。 */
    val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
    /** 随机化策略：true=泊松间隔，false=窗内均匀采样（US-3108 AC-2；默认均匀）。 */
    val usePoisson: Boolean = false,
    /** 定时主动消息的目标联系人（小号对用户主号的备注名；US-3108 AC-3）。空=未配置。 */
    val targetContact: String = "",
    /**
     * 双向对话开关（US-3109 新增）：开启后，当你（主号）给小号发消息，Prism 会感知新消息
     * 并由 LLM 以小号身份**后台回复**。默认关闭（须用户显式开启，纳入总开关与风险授权）。
     */
    val autoReplyEnabled: Boolean = false,

    // ==================== 协议端（ADR-064；US-3110） ====================

    /**
     * 传输通道。**默认协议端**（用户 2026-10-21 裁定 Q2）；未配置/不可用时自动降级无障碍。
     * 注意：本字段默认值变更不影响既有用户的持久化值（缺省时按默认读取）。
     */
    val transport: QqTransport = QqTransport.ONEBOT,

    /** OneBot HTTP 根地址（action 调用）。 */
    val oneBotBaseUrl: String = DEFAULT_ONEBOT_BASE_URL,

    /** OneBot 正向 WebSocket 地址（事件）。 */
    val oneBotWsUrl: String = DEFAULT_ONEBOT_WS_URL,

    /** OneBot 访问令牌（**必填**；为空时拒绝连接）。 */
    val oneBotToken: String = "",

    /** 小号 QQ 号（事件过滤 / 状态展示）。0=未配置。 */
    val oneBotSelfId: Long = 0L,

    /** 目标（主号）QQ 号。0=按 [targetContact] 从好友列表解析。 */
    val oneBotTargetUserId: Long = 0L,

    /** 回复时读取的历史消息条数（US-3112；6~30）。 */
    val historyLimit: Int = DEFAULT_HISTORY_LIMIT,

    /** 是否把 L2/L3 记忆注入 QQ 回复（US-3112 AC-4 可关闭）。 */
    val memoryInjectionEnabled: Boolean = true
) {

    /** 定时链路是否整体激活：总开关 + 风险已确认 + 定时开关 + 已配置目标联系人（四闸同开）。 */
    val schedulingActive: Boolean
        get() = enabled && riskAccepted && scheduleEnabled && targetContact.isNotBlank()

    /** 双向对话链路是否激活：总开关 + 风险已确认 + 双向开关 + 已配置目标联系人。 */
    val autoReplyActive: Boolean
        get() = enabled && riskAccepted && autoReplyEnabled && targetContact.isNotBlank()

    /**
     * 防 token 随日志/崩溃上报泄漏（guardrail L-03）：data class 默认 `toString()` 会打印全部字段。
     */
    override fun toString(): String =
        "QqConfig(enabled=$enabled, riskAccepted=$riskAccepted, transport=$transport, " +
            "minIntervalMs=$minIntervalMs, dailyCap=$dailyCap, scheduleEnabled=$scheduleEnabled, " +
            "window=$windowStartMinute-$windowEndMinute, maxPerDay=$maxPerDay, " +
            "usePoisson=$usePoisson, targetContact=${if (targetContact.isBlank()) "" else "<set>"}, " +
            "autoReplyEnabled=$autoReplyEnabled, " +
            "oneBotBaseUrl=$oneBotBaseUrl, oneBotWsUrl=$oneBotWsUrl, " +
            "oneBotToken=${if (oneBotToken.isBlank()) "<empty>" else "***"}, " +
            "oneBotSelfId=$oneBotSelfId, oneBotTargetUserId=$oneBotTargetUserId, " +
            "historyLimit=$historyLimit, memoryInjectionEnabled=$memoryInjectionEnabled)"

    /** 归一化为合法值域（读/写两侧共用）。 */
    fun clamped(): QqConfig = copy(
        minIntervalMs = minIntervalMs.coerceIn(MIN_INTERVAL_MS_FLOOR, MAX_INTERVAL_MS_CEIL),
        dailyCap = dailyCap.coerceIn(MIN_DAILY_CAP, MAX_DAILY_CAP),
        windowStartMinute = windowStartMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        windowEndMinute = windowEndMinute.coerceIn(1, MINUTES_PER_DAY),
        maxPerDay = maxPerDay.coerceIn(MIN_PER_DAY, MAX_PER_DAY_CEIL),
        oneBotSelfId = oneBotSelfId.coerceAtLeast(0L),
        oneBotTargetUserId = oneBotTargetUserId.coerceAtLeast(0L),
        historyLimit = historyLimit.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)
    )

    companion object {
        const val MIN_INTERVAL_MS_FLOOR: Long = 60_000L
        const val MAX_INTERVAL_MS_CEIL: Long = 24 * 60 * 60 * 1000L
        const val DEFAULT_MIN_INTERVAL_MS: Long = 30 * 60 * 1000L

        const val MIN_DAILY_CAP: Int = 1
        const val MAX_DAILY_CAP: Int = 200
        const val DEFAULT_DAILY_CAP: Int = 20

        const val MINUTES_PER_DAY: Int = 24 * 60
        const val DEFAULT_WINDOW_START_MINUTE: Int = 20 * 60
        const val DEFAULT_WINDOW_END_MINUTE: Int = 23 * 60

        const val MIN_PER_DAY: Int = 1
        const val MAX_PER_DAY_CEIL: Int = 20
        const val DEFAULT_MAX_PER_DAY: Int = 3

        const val MIN_HISTORY_LIMIT: Int = 6
        const val MAX_HISTORY_LIMIT: Int = 30
        const val DEFAULT_HISTORY_LIMIT: Int = 12

        const val DEFAULT_ONEBOT_BASE_URL: String = "http://127.0.0.1:3000"
        const val DEFAULT_ONEBOT_WS_URL: String = "ws://127.0.0.1:3001"

        val DEFAULT: QqConfig = QqConfig()
    }
}
