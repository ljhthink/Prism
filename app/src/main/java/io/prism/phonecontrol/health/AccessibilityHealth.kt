package io.prism.phonecontrol.health

/**
 * 无障碍健康三态与恢复决策（ADR-046 修订 2026-10-26；PRD US-3116）。
 *
 * **背景**（真机取证）：HyperOS 安全中心会把本应用从
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 中清除（`null` / `accessibility_enabled=0`），
 * 而 Prism 此前只在工具调用失败时报"未开启"——用户要自己发现、自己手动去开。
 *
 * 本对象把"状态判定"和"要不要恢复"收敛为**纯函数**，便于单测穷举；真正的系统读写由
 * [AccessibilityRestorer] 执行。
 */
object AccessibilityHealth {

    /** 三态（区别于布尔：`REBINDING` 与 `DISABLED` 的处置完全不同）。 */
    enum class State {
        /** 已注册且服务实例已连接。 */
        OK,

        /** 系统注册项仍在，但服务实例未连接（进程被清理后重连中）——**不需要写 settings**。 */
        REBINDING,

        /** 系统注册项已被清除——**需要恢复**。 */
        DISABLED
    }

    /**
     * 判定状态。
     *
     * @param enabledInSystem `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 是否仍含本服务
     * @param serviceConnected 无障碍服务实例是否存活
     */
    fun state(enabledInSystem: Boolean, serviceConnected: Boolean): State = when {
        enabledInSystem && serviceConnected -> State.OK
        enabledInSystem -> State.REBINDING
        else -> State.DISABLED
    }

    /**
     * 是否应该执行"写回恢复"。
     *
     * @param state 当前三态
     * @param selfHealEnabled 用户开关（默认开；ADR-046 修订 Q1 裁定）
     * @param shizukuReady shell 身份是否可用（写 secure settings 必需）
     * @param attemptInWindow 本时间窗内已尝试次数
     * @param attemptLimit 本时间窗上限（默认 3）
     */
    fun shouldAttemptRestore(
        state: State,
        selfHealEnabled: Boolean,
        shizukuReady: Boolean,
        attemptInWindow: Int,
        attemptLimit: Int = DEFAULT_ATTEMPT_LIMIT
    ): Boolean = state == State.DISABLED &&
        selfHealEnabled &&
        shizukuReady &&
        attemptInWindow < attemptLimit

    /**
     * 不可恢复时的**可读原因**（必须告诉用户为什么，不允许静默）。
     *
     * @return null=可以尝试恢复；否则为给用户/日志的原因
     */
    fun blockReason(
        state: State,
        selfHealEnabled: Boolean,
        shizukuReady: Boolean,
        attemptInWindow: Int,
        attemptLimit: Int = DEFAULT_ATTEMPT_LIMIT
    ): String? = when {
        state != State.DISABLED -> null
        !selfHealEnabled -> "自动恢复已关闭：请在设置中开启，或手动前往系统无障碍设置打开 Prism"
        !shizukuReady -> "需要 Shizuku 才能自动恢复：请激活 Shizuku 后重试，或手动前往系统无障碍设置打开 Prism"
        attemptInWindow >= attemptLimit ->
            "本时间窗内已尝试 $attemptInWindow 次仍未成功（可能被系统反复清除）：请手动前往系统无障碍设置打开 Prism"
        else -> null
    }

    /** 同一状态下的恢复尝试上限（防与安全中心"拉锯"耗电）。 */
    const val DEFAULT_ATTEMPT_LIMIT = 3

    /** 尝试计数的重置窗口（毫秒）。 */
    const val ATTEMPT_WINDOW_MS = 10 * 60 * 1000L

    /** 健康检查最小间隔（毫秒）：只在有前台/会话时检查，避免常驻轮询耗电。 */
    const val CHECK_INTERVAL_MS = 30_000L
}
