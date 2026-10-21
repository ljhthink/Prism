package io.prism.qq

/**
 * 主动消息发送网关（ADR-063；PRD US-3102 / US-3108）。
 *
 * **为什么用持有器而非构造注入**：定时触发发生在 `Service` 内（无 DI 容器），而真正的
 * "生成消息并发送"链路（LLM + 角色人设 + QQ 发送）由应用层装配。用进程级持有器把
 * "何时触发"（调度器职责）与"触发后做什么"（发送职责）解耦——调度器可独立测试与运行，
 * 发送实现（含 M0 真机 PoC 通过后的 QQ 导航链路）可在应用启动时注入。
 *
 * **未注入时的行为**：返回明确的可读文本（不抛异常），[ProactiveScheduleService] 会记录并继续。
 */
object QqProactiveGateway {

    /** 发送一次主动消息；返回可读结果（成功/失败描述）。null=未装配。 */
    @Volatile
    var sender: (suspend () -> String)? = null

    /**
     * 执行一次**双向对话应答**（US-3109）：读取小号会话页是否有新的"收到"消息 → 若有且未回应过
     * → 由 LLM 生成回复 → 后台发出。返回可读结果（含"跳过"原因）。null=未装配。
     */
    @Volatile
    var autoReply: (suspend () -> String)? = null

    /**
     * 当前是否存在**可用通道**（协议端已连接，或无障碍可用）。
     *
     * 用途（US-3110 AC-6）：协议端离线是**通道故障**而非账号故障，调度器据此跳过本次发送
     * 且**不记失败**，避免把离线误判为账号异常而熔断。null=未装配（按可用处理，保持旧行为）。
     */
    @Volatile
    var onlineCheck: (suspend () -> Boolean)? = null

    /**
     * 无障碍兜底通道的**轻量新消息探针**（US-3111 AC-4）。
     *
     * 返回 true=有变化（可建屏读取）、false=无变化（跳过）、null=未知（按兜底策略放行）。
     * null 未装配时按"未知"处理，保持旧行为。
     */
    @Volatile
    var unreadProbe: (suspend () -> Boolean?)? = null

    /** 是否已装配发送器。 */
    val isReady: Boolean get() = sender != null
}
