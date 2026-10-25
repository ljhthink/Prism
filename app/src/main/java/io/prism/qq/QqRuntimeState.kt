package io.prism.qq

/**
 * 定时主动消息的**运行时状态**（ADR-063；PRD US-3105 / US-3108）。
 *
 * 与 [QqConfig]（用户配置）分离：这些是"已发生的事实"（计划时刻、上次发送、计数、熔断），
 * 由调度器在发送前后读写，用于**去重**（避免同一计划时刻重复发送）与**节流/熔断**。
 */
data class QqRuntimeState(
    /** 已计划的下一次触发时刻（0=无计划）。用于跨进程重启后的到期补发与去重。 */
    val plannedTriggerAt: Long = 0L,
    /** 上次成功发送时刻（0=从未）。 */
    val lastFiredAt: Long = 0L,
    /** 上次计数所属日期（`yyyy-MM-dd`；跨日需清零）。 */
    val firedDate: String = "",
    /** 当日已发送总数（US-3105 dailyCap）。 */
    val firedCount: Int = 0,
    /** 当日定时主动消息发送数（US-3108 maxPerDay）。 */
    val scheduledFiredCount: Int = 0,
    /** 连续失败次数（达到阈值即熔断）。 */
    val failureStreak: Int = 0,
    /** 是否已熔断（US-3105 AC-3，需用户手动恢复）。 */
    val circuitOpen: Boolean = false,
    /**
     * 已回应过的最后一条**收到**消息的内容指纹（US-3109 双向对话去重）。
     * 空=从未回应；与当前探测到的收到消息指纹一致**且**在去重时间窗内时跳过（避免对同一
     * 消息重复回复；guardrail M-02：超出时间窗的同文消息视为新消息，允许再次回应）。
     */
    val lastRepliedMessageHash: String = "",
    /** 上次响应式回复的时刻（guardrail M-02 去重时间窗判据；0=从未）。 */
    val lastRepliedAtMs: Long = 0L,
    /**
     * 已回应过的**协议端消息 ID**（US-3111 AC-3 幂等键；空=从未）。
     *
     * 协议端事件可能重复投递/重连补投，`message_id` 是稳定唯一键；跨进程重启仍持久化，
     * 避免重复回复。无障碍通道无 ID，仍用 [lastRepliedMessageHash] + 时间窗兜底。
     */
    val lastRepliedMessageId: String = "",
    /**
     * 最近已回应过的**协议端消息 ID 集合**（US-3111 AC-3；guardrail D-03）。
     *
     * 只保留最后一条会让「A→B→重复 A」再次回应；本集合保留最近 [MAX_REPLIED_IDS] 条，
     * 跨重启持久化，重复投递/重连补投在窗口内一律跳过。
     */
    val repliedMessageIds: List<String> = emptyList(),
    /**
     * 上次定时任务的**结果类别**（ADR-063 修订 2026-10-25；PRD US-3115 AC-6）。
     *
     * 取值：`SUCCESS` / `SKIP:<原因类别>` / `FAIL:<原因类别>` / 空=从未执行。
     * 用于设置页与 `qq__schedule_message(status)` 如实展示"上次到底发生了什么"。
     */
    val lastOutcome: String = "",
    /** 上次结果的可读原因（已脱敏；空=无）。 */
    val lastOutcomeReason: String = "",
    /** 上次结果的时刻（0=从未）。 */
    val lastOutcomeAtMs: Long = 0L,
    /**
     * 「环境类跳过」重试预算所属的**窗口实例锚点**（epoch ms；0=未开始）。
     *
     * 用窗口实例起点而非日期作键——跨零点窗口（22:00–02:00）在午夜前后是同一实例，
     * 用日期会在 00:00 清零预算、导致整夜反复点亮屏幕（guardrail G-r2-02）。
     * **持久化**：服务被系统回收重启后预算不重置，"熄屏整夜"不会因重启而重来一轮。
     */
    val retryWindowAnchor: Long = 0L,
    /** 当前窗口实例内已执行的「环境类跳过」重试次数（成功/换窗口清零）。 */
    val retryCount: Int = 0
) {
    companion object {
        /** 幂等集合上限（防无限增长；远大于一次重连可能补投的条数）。 */
        const val MAX_REPLIED_IDS = 20
    }
}
