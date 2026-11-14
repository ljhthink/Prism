package io.prism.qq

/**
 * 「App 曾停摆多久」的判定（2026-11-14 新增；ADR-072 F2-2）。
 *
 * **为什么需要**：真机 2026-11-03 凌晨消息无人回复，直到 11-14 用户自己发现——
 * 期间 Prism 进程不在，App 内任何提示都发不出去，通知也没人发。唯一能触及用户的时刻是
 * **下一次冷启动**。本对象回答"这次冷启动该不该告诉用户'你不在的时候它停了很久'"。
 *
 * **为什么是纯函数**：这条判定决定"要不要打扰用户"，必须有可穷举的单测，
 * 且不能因为 IO/时钟注入问题在真机上表现不一致。
 *
 * 三个输入都是持久化的"最后活着"证据：
 * - [lastSeenAtMs]：`QqInboxStore` 水位（每收一条消息推进，最灵敏）；
 * - [lastProtocolCheckedAtMs]：60s 健康探测时刻（协议端在线时持续推进）。
 *
 * 取两者最大值作为"最后活着时刻"——任何一个推进都说明链路还在工作。
 *
 * **为什么不再用"上次开机恢复时刻"**（2026-11-14 真机实测推翻）：开机恢复成功会写入该值，
 * 它只证明"那一刻 App 被拉起过"，不证明之后链路一直在工作。实测发现它让停摆 11 天的
 * 场景仍判 `HEALTHY`（值 = 51 分钟前，来自一次历史恢复），属于**长期抑制告警**的设计错误。
 * 该时间戳仍保留在运行时状态里用于诊断，但**不参与**本判定。
 */
object QqStartupCompensation {

    /** 停摆多久才值得打扰用户（6 小时：跨过一整夜）。 */
    const val STALL_THRESHOLD_MS = 6 * 60 * 60 * 1000L

    /** 同一条停摆提醒的最小间隔（24 小时，防每次开 App 都弹）。 */
    const val REPORT_THROTTLE_MS = 24 * 60 * 60 * 1000L

    /** 判定结果。 */
    enum class Verdict {
        /** 首次运行/无历史：不提醒（避免新用户一装就被吓到）。 */
        NO_HISTORY,

        /** 一切正常：不提醒。 */
        HEALTHY,

        /** 停摆超过阈值：应提醒 + 立即补发。 */
        STALLED,

        /** 确实停摆了，但 24 小时内已提醒过：不重复打扰（仍会补发）。 */
        THROTTLED
    }

    /**
     * @param nowMs 当前时刻
     * @param lastSeenAtMs 收件箱水位（0=从未）
     * @param lastProtocolCheckedAtMs 上次协议端探测（0=从未；**必须传入已过滤本会话污染的值**）
     * @param lastReportAtMs 上次停摆提醒（0=从未）
     */
    fun evaluate(
        nowMs: Long,
        lastSeenAtMs: Long,
        lastProtocolCheckedAtMs: Long,
        lastReportAtMs: Long
    ): Verdict {
        // 时钟回拨防御：任何"未来时刻"都不可信，直接按无历史处理，绝不据此打扰用户
        val candidates = listOf(lastSeenAtMs, lastProtocolCheckedAtMs)
            .filter { it > 0L && it <= nowMs }
        if (candidates.isEmpty()) return Verdict.NO_HISTORY
        val lastAliveAt = candidates.max()
        val stallMs = nowMs - lastAliveAt
        if (stallMs < STALL_THRESHOLD_MS) return Verdict.HEALTHY
        val reportElapsed = nowMs - lastReportAtMs
        val throttled = lastReportAtMs > 0L && reportElapsed in 0 until REPORT_THROTTLE_MS
        return if (throttled) Verdict.THROTTLED else Verdict.STALLED
    }

    /** 停摆时长的人类可读文案（只用于通知，不含任何账号信息）。 */
    fun describeStall(nowMs: Long, lastAliveAtMs: Long): String {
        val minutes = ((nowMs - lastAliveAtMs).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 60L -> "$minutes 分钟"
            minutes < 24 * 60L -> "${minutes / 60} 小时"
            else -> "${minutes / (24 * 60)} 天"
        }
    }

    /**
     * 过滤**被本次冷启动污染**的探测时刻（2026-11-14 真机实证的缺陷）。
     *
     * 真机现象：新装 APK 首启时，60s 探测循环比停摆补偿先跑，立刻把
     * `lastProtocolCheckedAtMs` 刷成"现在"——于是补偿判定永远得到 `HEALTHY`，
     * 停摆 11 天的场景也一声不响（与 F2-2 的目的完全相反）。
     *
     * 判据：本次进程启动之后写入的时刻一律不可信（那是"我现在还活着"，不是"上次活着"）。
     * 留 [SLACK_MS] 余量容纳"探测写入发生在进程启动瞬间"的竞态。
     *
     * @param value 持久化里的探测时刻
     * @param processStartedAtMs 本次进程启动时刻
     * @return 可用的历史时刻；不可用返回 0
     */
    fun usableCheckedAt(value: Long, processStartedAtMs: Long): Long =
        if (value > 0L && value < processStartedAtMs - SLACK_MS) value else 0L

    /** 冷启动竞态余量。 */
    const val SLACK_MS = 60_000L
}
