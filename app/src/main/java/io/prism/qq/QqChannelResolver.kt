package io.prism.qq

import io.prism.qq.channel.QqTransport

/**
 * 通道解析：把「用户选择的通道」与「当前真正可用的通道」分开（ADR-064 修订 2026-10-25）。
 *
 * **为什么需要**：真机取证（2026-10-25）显示用户选了 `transport=ONEBOT` 但协议端从未配置，
 * 于是 WS 永不连接；而调度器的 `replyLoop` 见到 `transport==ONEBOT` 就跳过无障碍轮询
 * → **自动回复在任何情况下都不会发生**。判定必须按**能力**（是否已连接/是否可用），
 * 不能按**配置项**。
 *
 * 纯函数：输入为两个布尔能力 + 首选通道，输出决策与可读原因 → 纯 JVM 可测。
 */
object QqChannelResolver {

    /** 实际生效的通道。 */
    enum class Mode { ONEBOT, ACCESSIBILITY, NONE }

    /** 解析结果（含日志/UI 可读原因，不含任何账号标识）。 */
    data class Resolution(val mode: Mode, val reason: String)

    /**
     * @param preferred 用户首选通道（配置项）
     * @param oneBotConnected 协议端当前是否**已连接**（不是"已配置"）
     * @param accessibilityAvailable 无障碍通道是否可用（服务实例存在）
     */
    fun resolve(
        preferred: QqTransport,
        oneBotConnected: Boolean,
        accessibilityAvailable: Boolean
    ): Resolution = when (preferred) {
        QqTransport.ONEBOT -> when {
            oneBotConnected -> Resolution(Mode.ONEBOT, "协议端已连接（首选通道可用）")
            accessibilityAvailable -> Resolution(
                Mode.ACCESSIBILITY,
                "首选协议端未连接，已自动降级为无障碍通道"
            )
            else -> Resolution(Mode.NONE, "协议端未连接且无障碍不可用")
        }
        QqTransport.ACCESSIBILITY -> when {
            accessibilityAvailable -> Resolution(Mode.ACCESSIBILITY, "无障碍通道可用（首选）")
            oneBotConnected -> Resolution(Mode.ONEBOT, "首选无障碍不可用，已改用协议端")
            else -> Resolution(Mode.NONE, "无障碍不可用且协议端未连接")
        }
    }

    /** 该模式下是否应由**事件**驱动（协议端），否则走探针轮询（无障碍）。 */
    fun isEventDriven(mode: Mode): Boolean = mode == Mode.ONEBOT
}
