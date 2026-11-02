package io.prism.qq

/**
 * 协议端**失联告警策略**（M2；ADR-069 登记的未实施项；PRD US-3128）。
 *
 * ## 为什么需要
 *
 * 2026-10-31 22:32 Termux 被 HyperOS `AutoPowerKill` 杀死后，协议端**静默离线 19h42m**
 * 才被用户发现。当时 `QqProtocolHealth` 已经能探测出 `UNREACHABLE`，但只写进设置页，
 * **不推送、不响铃、不弹通知**。本对象就是把「探测结果 → 是否该通知」这一步抽成纯函数。
 *
 * ## 为什么是纯函数
 *
 * 告警判定天然充满边界（稳定在线多久才算数、几次失败才算掉、多久内不重复），
 * 若与通知副作用、协程、时钟搅在一起，只能真机试错。抽成纯函数后可穷举边界：
 * 9m59s / 10min、2 次 / 3 次、29min / 31min 节流全部能在 JVM 单测里钉死。
 *
 * ## 不变量
 *
 * 1. **不新增探测源**（AC-7）：本对象只是既有 60s `QqProtocolHealth` 轮询结果的**消费者**，
 *    自身不发任何网络请求。
 * 2. **总开关关闭不告警**（AC-8）：`enabled=false` 时一律 `Action.None`，并清空状态。
 * 3. **读路径不写盘**：状态推进由显式的 `record*` 写入路径完成（见
 *    [QqRuntimeStateRepository.advanceProtocolAlert]），`toState()` 只读。
 * 4. **节流基于时间戳**，不是集合大小（ADR-066 教训）。
 */
object QqProtocolAlertPolicy {

    /** 判定「该告警」的三种原因（决定通知文案与可执行动作）。 */
    enum class Kind {
        /** 本机模式（回环地址）+ 不可达：协议端进程没跑（AC-6）。 */
        LOCAL_PROTOCOL_NOT_RUNNING,

        /** 非本机模式 + 不可达：网络/对端不可达（AC-6）。 */
        PROTOCOL_UNREACHABLE,

        /** 协议端可达但账号离线：需要重新扫码登录。 */
        QQ_ACCOUNT_OFFLINE,

        /**
         * **从未连上过**（2026-11-02 真机实证新增）。
         *
         * 原策略要求"先稳定在线 10 分钟才武装"，副作用是：协议端**一次都没启动成功**时
         * 永不 arm、永不告警——用户只看到"协议端不可达"，却不知道是配置没做完。
         * 真机实测正处在这个状态（状态文件停在几天前，而一条通知都没有）。
         *
         * 用**长时间连续不可达**作为替代判据，单独文案、只发一次。
         */
        PROTOCOL_NEVER_STARTED
    }

    /** 探测结果字符串（与 [QqProtocolHealth.State.name] 对齐，便于持久化）。 */
    const val PROBE_ONLINE = "ONLINE"
    const val PROBE_QQ_OFFLINE = "QQ_OFFLINE"
    const val PROBE_UNREACHABLE = "UNREACHABLE"

    /**
     * AC-1：协议端需**稳定 ONLINE** 达到本时长后，才允许告警。
     *
     * 理由：扫码登录期间、App 刚启动、协议端刚重启时本来就会短暂不可达；
     * 立即告警会刷屏并把用户训练成"忽略通知"。
     */
    const val STABLE_ONLINE_MS = 10 * 60_000L

    /**
     * AC-2：连续多少次 `UNREACHABLE` 才认定「确实掉了」。
     *
     * 单次失败多为网络抖动/息屏；协议端自带退避重连（1s→30s），
     * 60s 轮询下 3 次 ≈ 3 分钟，足以覆盖一次正常重连。
     */
    const val CONFIRM_UNREACHABLE = 3

    /**
     * 连续多少次 `QQ_OFFLINE` 才告警。
     *
     * 比 [CONFIRM_UNREACHABLE] 少一次：`get_status` 成功返回 `online=false` 是**确定态**
     * （不是超时/连不上），只有一次抖动可能（刚被踢、正在重登）。取 2 是"快速告知 + 抗单次抖动"。
     */
    const val CONFIRM_QQ_OFFLINE = 2

    /** AC-3：同一轮失联最多通知一次，且两次通知之间至少间隔本时长。 */
    const val NOTIFY_THROTTLE_MS = 30 * 60_000L

    /**
     * 「从未连上过」的判定门槛：连续不可达达到本次数才告警。
     *
     * 取 30（≈ 30 分钟 @60s 轮询）：远大于 AC-2 的 3 次，确保"只是启动慢"不会误报；
     * 又足够短，让用户在一次配置失败后半小时内就能收到明确指引，而不是无限沉默。
     * 该告警**只发一次**（`downNotified` 一旦置位就不再重复），不会刷屏。
     */
    const val NEVER_STARTED_CONFIRM = 30

    /**
     * 告警状态（**必须持久化**，否则 Prism 自己被系统杀掉后「曾经在线过」这个事实就丢了，
     * 重启后协议端一直不回来反而永远不告警——这正是 10-31 事故的形态）。
     *
     * @param armedAtMs 达成「稳定在线 ≥[STABLE_ONLINE_MS]」的时刻（0=尚未达标）。
     *   **一旦非 0 就不再清零**（除非用户关闭总开关）：它是历史事实，不是当前状态。
     * @param onlineSinceMs 本轮连续 ONLINE 的起点（0=不在线/未记录）
     * @param unreachableStreak 连续 UNREACHABLE 次数
     * @param offlineStreak 连续 QQ_OFFLINE 次数
     * @param downNotified 本轮失联是否已通知过（AC-3 的"本轮"标记）
     * @param downKind 已通知的失联原因（[Kind.name]；用于恢复通知与去重）
     * @param downSinceMs 本轮失联起始时刻（0=无）
     * @param lastNotifyAtMs 最近一次**发出**通知的时刻（节流锚点）
     */
    data class State(
        val armedAtMs: Long = 0L,
        val onlineSinceMs: Long = 0L,
        val unreachableStreak: Int = 0,
        val offlineStreak: Int = 0,
        val downNotified: Boolean = false,
        val downKind: String = "",
        val downSinceMs: Long = 0L,
        val lastNotifyAtMs: Long = 0L
    ) {
        /** AC-1：是否已进入"可告警"状态。 */
        val armed: Boolean get() = armedAtMs > 0L
    }

    /** 策略动作。 */
    sealed interface Action {
        data object None : Action

        /** AC-5：通知内容必须可执行，[kind] 决定文案与入口。 */
        data class NotifyDown(val kind: Kind, val downSinceMs: Long, val detail: String) : Action

        /** AC-4：恢复通知（仅在本轮确实通知过"掉线"之后才发）。 */
        data class NotifyRecovered(val downKind: String, val downDurationMs: Long) : Action
    }

    /** 一次判定的结果：**下一份状态** + 要执行的动作。 */
    data class Decision(val state: State, val action: Action)

    /**
     * 判定一次（纯函数，无副作用、无 IO、无时钟依赖）。
     *
     * @param probe 本次探测结果（[PROBE_ONLINE] / [PROBE_QQ_OFFLINE] / [PROBE_UNREACHABLE]；
     *   其它值按 [Action.None] 处理且不改状态——防御未知字符串写入）
     * @param nowMs 本次时刻
     * @param enabled QQ 小号助手总开关（false → 清空状态、永不告警，AC-8）
     * @param loopback 协议端地址是否为回环（决定不可达时归因"本机没跑"还是"网络不通"，AC-6）
     * @param prev 上一份状态
     * @param detail 本次探测的可读说明（已脱敏；用于通知正文补充，可为空）
     */
    fun decide(
        probe: String,
        nowMs: Long,
        enabled: Boolean,
        loopback: Boolean,
        prev: State,
        detail: String = ""
    ): Decision {
        // AC-8：总开关关闭 → 不通告、并清空（避免"关掉再打开"时带着旧的 downNotified 直接补发）
        if (!enabled) return Decision(State(), Action.None)

        return when (probe) {
            PROBE_ONLINE -> onOnline(nowMs, prev)
            PROBE_QQ_OFFLINE -> onDown(
                nowMs = nowMs,
                prev = prev,
                kind = Kind.QQ_ACCOUNT_OFFLINE,
                confirmCount = CONFIRM_QQ_OFFLINE,
                nextOfflineStreak = prev.offlineStreak + 1,
                nextUnreachableStreak = 0,
                detail = "协议端可达但 QQ 账号已离线，需要在手机上重新扫码登录"
            )
            PROBE_UNREACHABLE -> onDown(
                nowMs = nowMs,
                prev = prev,
                kind = if (loopback) Kind.LOCAL_PROTOCOL_NOT_RUNNING else Kind.PROTOCOL_UNREACHABLE,
                confirmCount = CONFIRM_UNREACHABLE,
                nextOfflineStreak = 0,
                nextUnreachableStreak = prev.unreachableStreak + 1,
                detail = detail.ifBlank {
                    if (loopback) "本机协议端未运行（端口无监听）" else "协议端不可达（网络或对端进程）"
                }
            )
            else -> Decision(prev, Action.None)
        }
    }

    /** 在线分支：推进"稳定在线"计时、重置失败计数、必要时发恢复通知。 */
    private fun onOnline(nowMs: Long, prev: State): Decision {
        // 连续在线的起点：本次首次看到 ONLINE 时开始计时。
        // 若中途掉过线（onlineSinceMs 已被 onDown 清零），这里会重新起算——符合"稳定"语义。
        //
        // 2026-11-01 验收：**时钟回拨防御**——若记录的起点在未来（用户改过系统时间/NTP 回跳），
        // 直接用 nowMs 重设起点，否则 `nowMs - onlineSince` 恒为负、协议端**永远无法再武装**。
        val rawSince = prev.onlineSinceMs.takeIf { it > 0L }
        val onlineSince = if (rawSince == null || rawSince > nowMs) nowMs else rawSince

        // AC-1：达标即"武装"，且**一旦武装就保持**（历史事实；重启后不丢）。
        val armedAt = prev.armedAtMs.takeIf { it > 0L }
            ?: if (nowMs - onlineSince >= STABLE_ONLINE_MS) onlineSince + STABLE_ONLINE_MS else 0L

        val recovered = if (prev.downNotified) {
            Action.NotifyRecovered(
                downKind = prev.downKind,
                downDurationMs = if (prev.downSinceMs > 0L) (nowMs - prev.downSinceMs) else 0L
            )
        } else {
            Action.None
        }

        val next = State(
            armedAtMs = armedAt,
            onlineSinceMs = onlineSince,
            unreachableStreak = 0,
            offlineStreak = 0,
            downNotified = false,
            downKind = "",
            downSinceMs = 0L,
            // 2026-11-01 验收 R-1：恢复通知**不再推高节流锚点**。
            // 原实现把 lastNotifyAtMs 置为 now，于是"恢复后 30 分钟内又掉线"会被静默——
            // 而那恰恰是最需要告警的抖动场景。恢复通知本身是"好消息"，
            // 不值得占用掉线告警的节流额度。
            lastNotifyAtMs = prev.lastNotifyAtMs
        )
        return Decision(next, recovered)
    }

    /** 失联分支（UNREACHABLE / QQ_OFFLINE 共用）。 */
    private fun onDown(
        nowMs: Long,
        prev: State,
        kind: Kind,
        confirmCount: Int,
        nextOfflineStreak: Int,
        nextUnreachableStreak: Int,
        detail: String
    ): Decision {
        val streakReached = if (kind == Kind.QQ_ACCOUNT_OFFLINE) {
            nextOfflineStreak >= confirmCount
        } else {
            nextUnreachableStreak >= confirmCount
        }

        // 失联起算点：同一 kind 延续时沿用第一次的；**换 kind 也保留首次起点**
        // （2026-11-01 审查 M3：原来换 kind 就重置，恢复通知会少报总时长）。
        val downSince = prev.downSinceMs.takeIf { it > 0L } ?: nowMs

        val base = State(
            armedAtMs = prev.armedAtMs,
            // 掉线即打断"稳定在线"计时（恢复后要重新满 10 分钟才算再次稳定）
            onlineSinceMs = 0L,
            unreachableStreak = nextUnreachableStreak,
            offlineStreak = nextOfflineStreak,
            downNotified = prev.downNotified,
            downKind = prev.downKind,
            downSinceMs = downSince,
            lastNotifyAtMs = prev.lastNotifyAtMs
        )

        // AC-1：从未稳定在线过 → 不按"掉线"告警（首次部署/刚扫码期间的正常重连不打扰）
        // 但 2026-11-02：**长期从未连上**是另一回事，见下（否则配置失败会永远沉默）。
        if (!base.armed) {
            return neverStarted(nowMs, base, prev, kind, nextUnreachableStreak)
        }
        // AC-2：失败次数不足 → 继续观察
        if (!streakReached) return Decision(base, Action.None)

        val sameKindRound = prev.downNotified && prev.downKind == kind.name
        // AC-3：同一轮已通知过同一种故障 → 静默
        if (sameKindRound) return Decision(base, Action.None)

        /**
         * 2026-11-01 审查 M3：**升级型故障允许突破节流**。
         *
         * 场景：协议端进程先死（已通知「协议端未运行」），用户照着提示去启动，
         * 结果发现其实是**账号被踢了**需要扫码。若被 30 分钟节流压住，
         * 用户在最需要"去扫码"这条指引时反而收不到任何消息。
         *
         * 因此只对「从不可达类升级为账号离线」这一种方向开例外：
         * 它信息量更大、动作更明确，且天然不会高频发生（账号失效是低频事件）。
         * 其余一切跨类型切换仍受节流约束（避免"进程死→账号离线"连发两条）。
         */
        val isUpgradeToAccountOffline =
            kind == Kind.QQ_ACCOUNT_OFFLINE &&
                prev.downNotified &&
                prev.downKind != Kind.QQ_ACCOUNT_OFFLINE.name &&
                base.armed

        // AC-3：节流窗口内不重复（升级型故障例外，见上）
        // 2026-11-01 验收：**时钟回拨防御**——`nowMs - lastNotifyAtMs` 为负说明系统时间被调回，
        // 此时按"已过节流"处理，否则新掉线会被长期静默。
        val throttleElapsed = nowMs - prev.lastNotifyAtMs
        val withinThrottle = prev.lastNotifyAtMs > 0L &&
            throttleElapsed in 0 until NOTIFY_THROTTLE_MS
        if (!isUpgradeToAccountOffline && withinThrottle) {
            return Decision(base, Action.None)
        }

        return Decision(
            base.copy(
                downNotified = true,
                downKind = kind.name,
                downSinceMs = downSince,
                lastNotifyAtMs = nowMs
            ),
            Action.NotifyDown(kind = kind, downSinceMs = downSince, detail = detail)
        )
    }

    /**
     * 「从未连上过」分支（2026-11-02）：未武装 + 连续不可达达到
     * [NEVER_STARTED_CONFIRM] → 发**一条**「协议端从未启动成功」告警。
     *
     * 与"掉线"告警的区别：
     * - 判据不是"曾经在线过"，而是"持续很久都没起来"；
     * - **只发一次**（`downNotified` 置位后本分支不再触发），不参与 30 分钟节流循环；
     * - 文案不同：不是"掉了"，而是"从来没起来，检查配置"。
     *
     * 账号离线不适用本分支（`get_status` 能通说明协议端起来了，不算"从未连上"）。
     */
    private fun neverStarted(
        nowMs: Long,
        base: State,
        prev: State,
        kind: Kind,
        unreachableStreak: Int
    ): Decision {
        if (kind == Kind.QQ_ACCOUNT_OFFLINE) return Decision(base, Action.None)
        if (prev.downNotified) return Decision(base, Action.None)
        if (unreachableStreak < NEVER_STARTED_CONFIRM) return Decision(base, Action.None)

        val since = prev.downSinceMs.takeIf { it > 0L } ?: nowMs
        return Decision(
            base.copy(
                downNotified = true,
                downKind = Kind.PROTOCOL_NEVER_STARTED.name,
                downSinceMs = since,
                lastNotifyAtMs = nowMs
            ),
            Action.NotifyDown(
                kind = Kind.PROTOCOL_NEVER_STARTED,
                downSinceMs = since,
                detail = "协议端连续 ${unreachableStreak} 次探测都不可达，且从未成功连上过"
            )
        )
    }
}
