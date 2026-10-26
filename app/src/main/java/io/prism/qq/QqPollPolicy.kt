package io.prism.qq

/**
 * 无障碍兜底轮询的**屏幕态驱动**策略（2026-10-26 真机复盘新增）。
 *
 * **为什么改**：原策略是"探针 `false` 就跳过本轮"——但通知摘要不变有很多与"没有新消息"
 * 无关的原因（通知被关闭、摘要被折叠、QQ 在前台不产生通知、通知渠道被系统静音）。
 * 真机表现为「有时回有时不回」，且越用越不回。
 *
 * 现实语义应该是：
 * - **用户正在亮屏使用手机** → 新消息几乎必然马上看得到，此时不必抠通知摘要，**直接放行**；
 * - **熄屏** → 保持保守（不点亮、不建屏），只在探针命中或 15 分钟最长静默兜底时读一次；
 * - **熄屏 + 探针未知** → 最多连续 [QqUnreadProbe.FORCE_POLL_AFTER_UNKNOWN] 次后强制放行一次。
 *
 * 纯函数，便于 JVM 单测覆盖全部组合。
 */
object QqPollPolicy {

    /** 亮屏时的轮询间隔（足够快，又不至于疯狂建屏）。 */
    const val SCREEN_ON_POLL_MS: Long = 30_000L

    /** 熄屏时的轮询间隔（探针本身很轻，保持 90s）。 */
    const val SCREEN_OFF_POLL_MS: Long = 90_000L

    /** 决策结果。 */
    data class Decision(
        /** true=本轮应执行一次应答（读到消息就回）。 */
        val poll: Boolean,
        /** 下一次轮询的等待时间。 */
        val nextDelayMs: Long,
        /** 可读原因（日志用，不含正文）。 */
        val reason: String
    )

    /**
     * @param screenInteractive 屏幕是否处于可交互状态（亮屏且未锁屏）
     * @param probe 探针结果：true=有变化 / false=无变化 / null=未知（探针未装配或读取失败）
     */
    fun decide(screenInteractive: Boolean, probe: Boolean?): Decision = when {
        // 亮屏：不依赖通知摘要——用户在用手机，回复必须及时
        screenInteractive -> Decision(
            poll = true,
            nextDelayMs = SCREEN_ON_POLL_MS,
            reason = "亮屏：直接放行（不依赖通知摘要）"
        )
        // 熄屏 + 探针明确命中：读一次（不点亮屏幕）
        probe == true -> Decision(
            poll = true,
            nextDelayMs = SCREEN_OFF_POLL_MS,
            reason = "熄屏：探针命中"
        )
        // 熄屏 + 无变化：保持零建屏
        probe == false -> Decision(
            poll = false,
            nextDelayMs = SCREEN_OFF_POLL_MS,
            reason = "熄屏：探针无变化（零建屏）"
        )
        // 熄屏 + 未知：交给 QqUnreadProbe 的连续未知/最长静默兜底（它会把 true 返回上来）
        else -> Decision(
            poll = false,
            nextDelayMs = SCREEN_OFF_POLL_MS,
            reason = "熄屏：探针未知（等待兜底）"
        )
    }
}
