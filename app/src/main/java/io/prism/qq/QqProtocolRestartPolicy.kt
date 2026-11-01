package io.prism.qq

/**
 * 协议端**重启退避**策略（2026-11-01；US-3128）。
 *
 * ## 为什么需要退避而不是"掉了就拉"
 *
 * 1. **防崩溃循环**：协议端若因容器损坏/凭据失效而起不来，每分钟重启一次会持续烧电、
 *    刷满通知，用户还看不到真正原因。必须"试几次就停手，交给告警"。
 * 2. **防自踢**：ADR-069 踩过坑——自动重启版脚本会与既有实例互踢 QQ 账号
 *    （`[KickedOffLine] 您的账号已在另一台终端登录`）。所以重启前必须确认
 *    **没有已在跑的实例**，且两次尝试之间留足观察窗口。
 *
 * ## 纯函数
 *
 * 决策只依赖"上次重启时刻 / 窗口内次数 / 现在"，不碰 IO、不碰协程，可穷举边界单测。
 */
object QqProtocolRestartPolicy {

    /** 统计窗口：窗口内最多 [MAX_ATTEMPTS] 次自动重启。 */
    const val WINDOW_MS = 60 * 60_000L

    /** 窗口内最多自动重启次数；超过即停手并交给告警（AC-3）。 */
    const val MAX_ATTEMPTS = 3

    /**
     * 第 n 次重启**之后**到下一次重启前的最小间隔（attempts=1 时用 [0]）。
     *
     * 第 1 次**立即**执行（进程刚被杀，没有等待的必要）；第 2 次前等 1 分钟；
     * 第 3 次前等 5 分钟。三次用尽即停手并把问题交给告警——
     * 继续按更长的间隔瞎试只会烧电并刷通知，且掩盖真正的失败原因。
     */
    private val BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L)

    /** 重启记账状态（持久化在 DataStore，进程被杀也不丢）。 */
    data class State(
        /** 统计窗口起点（0=无窗口，下次允许时重置） */
        val windowStartMs: Long = 0L,
        /** 窗口内已自动重启次数 */
        val attempts: Int = 0,
        /** 上次自动重启时刻（0=从未） */
        val lastRestartAtMs: Long = 0L
    )

    /** 决策。 */
    sealed interface Decision {
        /** 允许重启，附原因（写日志用）与调整后的状态。 */
        data class Allow(val reason: String, val state: State) : Decision

        /** 暂不重启（仍在退避间隔内）。 */
        data class Wait(val waitMs: Long) : Decision

        /** 停手：窗口内已用满次数，交给告警（AC-3 的"连续 3 次失败后升级通知"）。 */
        data object GiveUp : Decision
    }

    /**
     * 判定此刻是否该自动重启。
     *
     * @param nowMs 当前时刻
     * @param state 记账状态
     * @param alreadyRunning 协议端是否其实已经在跑（true → [Decision.GiveUp]，**绝不**重复拉起，
     *   否则会自踢账号）
     * @param enabled 总开关（false → [Decision.GiveUp]）
     */
    fun decide(
        nowMs: Long,
        state: State,
        alreadyRunning: Boolean,
        enabled: Boolean
    ): Decision {
        if (!enabled) return Decision.GiveUp
        // 已在运行：不重启。这不是"放弃"，而是"不需要"——调用方只会在确认不可达时才问。
        if (alreadyRunning) return Decision.GiveUp

        // 窗口过期 → 记账清零，从第一次退避开始
        val windowExpired = state.windowStartMs <= 0L || nowMs - state.windowStartMs >= WINDOW_MS
        val effective = if (windowExpired) State(windowStartMs = nowMs, attempts = 0, lastRestartAtMs = 0L) else state

        if (effective.attempts >= MAX_ATTEMPTS) return Decision.GiveUp

        // 第 1 次立即执行（attempts==0 → gap 0）；此后第 n 次用 BACKOFF[n-1]
        val gap = if (effective.attempts <= 0) 0L else {
            BACKOFF_MS[(effective.attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]
        }
        val elapsed = if (effective.lastRestartAtMs <= 0L) Long.MAX_VALUE else nowMs - effective.lastRestartAtMs
        if (elapsed < gap) return Decision.Wait(gap - elapsed)

        return Decision.Allow(
            reason = if (gap <= 0L) "第 1 次自动重启（立即）"
            else "第 ${effective.attempts + 1} 次自动重启（已等待 ${gap / 60_000} 分钟）",
            state = effective.copy(
                attempts = effective.attempts + 1,
                lastRestartAtMs = nowMs
            )
        )
    }

    /** 人工/恢复成功后清零记账（用户手动点了"启动"也算）。 */
    fun cleared(): State = State()
}
