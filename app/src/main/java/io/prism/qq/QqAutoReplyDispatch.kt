package io.prism.qq

/**
 * 被动回复的**轮询/事件分流判据**（ADR-064 修订；guardrail G-01 的单一来源）。
 *
 * 抽成对象的原因：该判据原本内联在 `PrismApplication.autoReplyOnce` 的局部函数里——
 * 局部函数无法在 JVM 单测中构造，导致「协议端未配置 + 无障碍可用」这条**真机原始故障路径**
 * 完全没有回归保护（guardrail G-01 的两次逃逸正是这个盲区）。现在业务与测试共用同一实现。
 */
object QqAutoReplyDispatch {

    /**
     * 轮询路径是否应让位给事件驱动。
     *
     * 判据必须是**实际生效的通道**（[QqChannelResolver.Resolution.mode]），
     * 而不是用户配置的 `transport`：配置为协议端但其未连接时，实际走的是无障碍。
     *
     * @return true=跳过本次轮询（协议端已连接，由 WS 事件处理；或两条通道都不可用）
     */
    fun shouldSkipForEventDriven(resolution: QqChannelResolver.Resolution): Boolean =
        when (resolution.mode) {
            QqChannelResolver.Mode.ONEBOT -> true // 事件驱动，轮询不做事
            QqChannelResolver.Mode.ACCESSIBILITY -> false // 必须轮询读会话
            QqChannelResolver.Mode.NONE -> true // 都不可用：不空转、不建屏
        }

    /** 该通道模式下，应答是否由**事件携带的消息**触发（否则要自己去读会话）。 */
    fun isEventPayload(mode: QqChannelResolver.Mode): Boolean =
        mode == QqChannelResolver.Mode.ONEBOT
}
