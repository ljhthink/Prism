package io.prism.qq

/**
 * 无障碍兜底轮询的**建屏决策**（PRD US-3111 AC-4；guardrail H-05）。
 *
 * 只有探针**明确命中**（true）才允许建屏；未知（null）一律跳过——
 * [QqUnreadProbe] 会在连续 [QqUnreadProbe.FORCE_POLL_AFTER_UNKNOWN] 次未知后
 * 主动返回 true，所以不会永久失聪。
 */
object QqPollDecision {

    /**
     * @param probeResult true=有变化；false=无变化；null=未知/探针未装配
     * @return true=允许建屏读取
     */
    fun shouldPoll(probeResult: Boolean?): Boolean = probeResult == true
}
