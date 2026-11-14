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
     * 最近若干次**被动回复**的时间戳（2026-10-26 RC-3 修复）。
     *
     * 滑动窗口限流专用：与 [repliedMessageIds]（去重集合）**彻底解耦**。
     * 原实现把「去重集合长度」当限流判据，而该集合会被裁剪到固定 20 条且永不缩短，
     * 导致累计回复 20 条后被动回复被**永久拒绝**。
     *
     * 只保留最近 [MAX_PASSIVE_TIMESTAMPS] 条（按时间升序），更旧的在写入时淘汰。
     */
    val passiveReplyTimestamps: List<Long> = emptyList(),
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
    val retryCount: Int = 0,
    /**
     * 最近一次协议端健康探测的结论（2026-10-26；US-3125）。
     *
     * 取值：`ONLINE` / `QQ_OFFLINE` / `UNREACHABLE` / 空=尚未探测。
     * **WS 连接态不足以代表"能收发"**——账号态会周期性失效。
     */
    val lastProtocolState: String = "",
    /** 最近一次探测的可读说明（已脱敏）。 */
    val lastProtocolDetail: String = "",
    /** 最近一次探测时刻（0=从未）。 */
    val lastProtocolCheckedAtMs: Long = 0L,
    /** 最近一次检测到「QQ 离线」的时刻（0=从未；用于 UI 显示"最近离线"）。 */
    val lastProtocolOfflineAtMs: Long = 0L,
    /**
     * 协议端失联**告警状态**（M2；US-3128；[QqProtocolAlertPolicy]）。
     *
     * **必须持久化**：10-31 事故里 Prism 自己也被 `OneKeyClean` 杀掉，
     * 「曾经稳定在线过」这一事实若只存在内存里，重启后协议端一直不回来就**永远不会告警**——
     * 恰好是最需要告警的场景。
     */
    val protocolAlert: QqProtocolAlertPolicy.State = QqProtocolAlertPolicy.State(),
    /** 自动重启统计窗口起点（0=无窗口；US-3128；[QqProtocolRestartPolicy]）。 */
    val protocolRestartWindowStartMs: Long = 0L,
    /** 窗口内已自动重启次数。 */
    val protocolRestartAttempts: Int = 0,
    /** 上次自动重启时刻（0=从未）。 */
    val protocolLastRestartAtMs: Long = 0L,

    /**
     * 最近一次收到"开机/升级恢复"广播的时刻（2026-11-14；ADR-072 F2）。
     *
     * 用途：下次冷启动时区分"这段时间根本没收到广播"与"收到了但恢复失败"。
     */
    val bootRecoveryAtMs: Long = 0L,

    /**
     * 最近一次**停摆补偿**已提醒用户的时刻（2026-11-14；ADR-072 F2-2）。
     *
     * 24 小时节流，避免用户每次打开 App 都被同一条"曾中断"提醒打扰。
     */
    val startupReportAtMs: Long = 0L
) {

    companion object {
        /**
         * 幂等集合上限（防无限增长）。
         *
         * 2026-11-02（ADR-071 / F-5）：由 20 提高到 **200**。20 条时集合会很快滚出旧 id，
         * 一旦断连补发重新拉到同一条消息（历史接口返回最近 30 条），就会**重复回复**。
         * 200 覆盖"一次补发 + 一段正常对话"，与 [QqInboxStore.MAX_ITEMS] 同量级。
         */
        const val MAX_REPLIED_IDS = 200

        /**
         * 被动回复时间戳保留条数。
         *
         * 只需覆盖一个滑动窗口（[QqThrottle.PASSIVE_WINDOW_MS] 内最多 20 条），
         * 这里留 2 倍余量，且写入时按时间淘汰，文件不会无限增长。
         */
        const val MAX_PASSIVE_TIMESTAMPS = 40
    }
}
