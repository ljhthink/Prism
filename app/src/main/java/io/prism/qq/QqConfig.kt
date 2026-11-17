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

    /** 回复时读取的历史消息条数（US-3112；1~200）。 */
    val historyLimit: Int = DEFAULT_HISTORY_LIMIT,

    /** 上下文字符预算（2026-10-26 新增；超出时从最旧截断）。 */
    val historyCharBudget: Int = DEFAULT_HISTORY_CHAR_BUDGET,

    /** 自动回复中是否允许附带表情/语音（2026-10-26 新增；仅协议端生效）。 */
    val mediaReplyEnabled: Boolean = true,

    /** 自动回复附带表情的概率（百分比 0~100）。 */
    val stickerChancePercent: Int = 25,

    /** 自动回复改用语音的概率（百分比 0~100；失败自动降级文本）。 */
    val voiceChancePercent: Int = 10,

    /** 是否把 L2/L3 记忆注入 QQ 回复（US-3112 AC-4 可关闭）。 */
    val memoryInjectionEnabled: Boolean = true,

    /**
     * 熄屏时是否**临时点亮屏幕**执行定时任务（ADR-063 修订 2026-10-25；PRD US-3115 AC-6）。
     *
     * `true`（默认）：到点若屏幕熄灭，经 Shizuku 唤醒并保持亮屏，确保消息真的能发出；
     * `false`：跳过本次并记录原因（适合"只要不打扰我"的用户）。
     * 仅影响**定时主动消息**；被动回复永不唤醒屏幕。
     */
    val wakeOnScreenOff: Boolean = true,

    // ==================== 2026-11-16（ADR-077）：节奏 / 自定义表情 / 工具 / 日记 ====================

    /**
     * 拟人节奏（思考 + 打字延迟）总开关。
     *
     * 真机取证：开启前 `生成耗时` 2.4–4.4 s 就是全部耗时，用户感觉"秒回、有压力"。
     */
    val pacingEnabled: Boolean = true,

    /** 打字速度（字/秒；真人中文 5~10，取 7 为中止点）。 */
    val typingCharsPerSecond: Double = 7.0,

    /** 单轮最小延迟（毫秒）：再快也不早于这个时间。 */
    val minReplyDelayMs: Long = 1_500L,

    /** 单轮最大延迟（毫秒）：超过用户会以为卡死。 */
    val maxReplyDelayMs: Long = 25_000L,

    /** 每小时最多发几张表情（0=不限；防刷图，`force` 也受限）。 */
    val stickerMaxPerHour: Int = 6,

    /** 同图去重 TTL（小时；0=不去重）。 */
    val stickerDuplicateTtlHours: Int = 24,

    /** QQ 回复是否允许调用 Prism 工具（关闭时退回单次补全）。 */
    val replyToolsEnabled: Boolean = true,

    /** 是否允许在 QQ 回复里使用**手机操控**（高风险：后台建副屏/可能抢前台）。 */
    val replyPhoneControlEnabled: Boolean = false,

    /** 工具回路总预算（毫秒）：超时用已有文本收口，不丢回复。 */
    val replyToolBudgetMs: Long = 15_000L,

    /** 是否允许角色写日记（本地，仅自己可见）。 */
    val diaryEnabled: Boolean = true
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
            "historyLimit=$historyLimit, memoryInjectionEnabled=$memoryInjectionEnabled, " +
            "historyCharBudget=$historyCharBudget, mediaReplyEnabled=$mediaReplyEnabled, " +
            "stickerChance=$stickerChancePercent, voiceChance=$voiceChancePercent, " +
            "wakeOnScreenOff=$wakeOnScreenOff, pacingEnabled=$pacingEnabled, " +
            "typingCps=$typingCharsPerSecond, delay=$minReplyDelayMs~$maxReplyDelayMs, " +
            "replyTools=$replyToolsEnabled, replyPhoneControl=$replyPhoneControlEnabled, " +
            "replyToolBudget=$replyToolBudgetMs, diaryEnabled=$diaryEnabled)"

    /**
     * 归一化为合法值域（读/写两侧共用）。
     *
     * **2026-11-16（guardrail M-01）**：节奏相关的收敛改为**两步**——先收敛
     * `minReplyDelayMs`，再用**收敛后**的值作为 `maxReplyDelayMs` 的下界。
     * 原实现一次性 `copy(...)` 里引用的是未收敛的原始 `minReplyDelayMs`，
     * 用户填 `min=500_000 & max=100` 时 `coerceIn(500000, 90000)` 会抛
     * `IllegalArgumentException`，而本函数在**读配置**路径上（`toConfig`）→ 崩溃。
     * 与 `QqReplyPacing.Config.sanitized()` 的修复同源（guardrail 指出我做了横向遗漏）。
     */
    fun clamped(): QqConfig {
        val minDelay = minReplyDelayMs.coerceIn(0L, QqReplyPacing.MAX_MIN_DELAY_MS)
        val delayCeiling = QqReplyPacing.MAX_DELAY_CEILING_MS.coerceAtLeast(minDelay)
        val cps = if (typingCharsPerSecond.isNaN()) QqReplyPacing.DEFAULT_CHARS_PER_SECOND
        else typingCharsPerSecond.coerceIn(
            QqReplyPacing.MIN_CHARS_PER_SECOND, QqReplyPacing.MAX_CHARS_PER_SECOND
        )
        return copy(
            minIntervalMs = minIntervalMs.coerceIn(MIN_INTERVAL_MS_FLOOR, MAX_INTERVAL_MS_CEIL),
            dailyCap = dailyCap.coerceIn(MIN_DAILY_CAP, MAX_DAILY_CAP),
            windowStartMinute = windowStartMinute.coerceIn(0, MINUTES_PER_DAY - 1),
            windowEndMinute = windowEndMinute.coerceIn(1, MINUTES_PER_DAY),
            maxPerDay = maxPerDay.coerceIn(MIN_PER_DAY, MAX_PER_DAY_CEIL),
            oneBotSelfId = oneBotSelfId.coerceAtLeast(0L),
            oneBotTargetUserId = oneBotTargetUserId.coerceAtLeast(0L),
            historyLimit = historyLimit.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT),
            historyCharBudget = historyCharBudget.coerceIn(
                MIN_HISTORY_CHAR_BUDGET, MAX_HISTORY_CHAR_BUDGET
            ),
            stickerChancePercent = stickerChancePercent.coerceIn(0, 100),
            voiceChancePercent = voiceChancePercent.coerceIn(0, 100),
            // 2026-11-16（ADR-077）：节奏/表情闸门/工具预算一律收敛
            typingCharsPerSecond = cps,
            minReplyDelayMs = minDelay,
            maxReplyDelayMs = maxReplyDelayMs.coerceIn(minDelay, delayCeiling),
            stickerMaxPerHour = stickerMaxPerHour.coerceIn(0, 60),
            stickerDuplicateTtlHours = stickerDuplicateTtlHours.coerceIn(0, 24 * 7),
            replyToolBudgetMs = replyToolBudgetMs.coerceIn(
                QqReplyToolPolicy.MIN_BUDGET_MS, QqReplyToolPolicy.MAX_BUDGET_MS
            )
        )
    }

    companion object {
        const val MIN_INTERVAL_MS_FLOOR: Long = 60_000L
        const val MAX_INTERVAL_MS_CEIL: Long = 24 * 60 * 60 * 1000L
        const val DEFAULT_MIN_INTERVAL_MS: Long = 30 * 60 * 1000L

        const val MIN_DAILY_CAP: Int = 0
        const val MAX_DAILY_CAP: Int = 200
        const val DEFAULT_DAILY_CAP: Int = 20

        const val MINUTES_PER_DAY: Int = 24 * 60
        const val DEFAULT_WINDOW_START_MINUTE: Int = 20 * 60
        const val DEFAULT_WINDOW_END_MINUTE: Int = 23 * 60

        /**
         * 定时主动消息的每日上限（2026-10-31 用户裁定：**0~200，0=不限**）。
         *
         * 原为 1~20，用户反馈「随机定时消息的上限次数应该让用户自行决定」——
         * 20 这个天花板对"整天高频陪聊"场景不够用，且与 [MAX_DAILY_CAP] 的口径
         * （总消息上限 0~200）不一致。现统一为 0~200 自由填写：
         * - `0` = 不限制（与 [dailyCap] 的 0 语义一致）；
         * - 上限 200 只作为防呆（避免误输入极大值导致 LLM 费用失控）。
         *
         * 注意：这与 [dailyCap] 是两个正交约束——[maxPerDay] 只约束**定时主动**消息，
         * [dailyCap] 约束当日**全部**主动+被动消息。二者同时生效，取更严格者。
         */
        const val MIN_PER_DAY: Int = 0
        const val MAX_PER_DAY_CEIL: Int = 200
        const val DEFAULT_MAX_PER_DAY: Int = 3

        /**
         * 历史条数下/上限（2026-10-26 用户裁定：1~200）。
         *
         * 原为 6~30，用户反馈"最大 30 条"无法接受。真正的上下文长度由
         * [historyCharBudget] 的字符预算兜底，条数只是 UI 便利参数——
         * 参考 OneBot 生态（AstrBot 等）的做法：按会话持久化 + 按 token/字符预算裁剪。
         */
        const val MIN_HISTORY_LIMIT: Int = 1
        const val MAX_HISTORY_LIMIT: Int = 200
        const val DEFAULT_HISTORY_LIMIT: Int = 12

        /** 上下文字符预算：默认 6000，范围 1000~30000；0=不限制（不推荐）。 */
        const val MIN_HISTORY_CHAR_BUDGET: Int = 1_000
        const val MAX_HISTORY_CHAR_BUDGET: Int = 30_000
        const val DEFAULT_HISTORY_CHAR_BUDGET: Int = 6_000

        const val DEFAULT_ONEBOT_BASE_URL: String = "http://127.0.0.1:3000"
        const val DEFAULT_ONEBOT_WS_URL: String = "ws://127.0.0.1:3001"

        val DEFAULT: QqConfig = QqConfig()
    }
}
