package io.prism.phonecontrol.health

/**
 * 启动期自愈的**决策纯逻辑**（guardrail **R4-04**：把真机验证过的时序抽成可测单元）。
 *
 * **真机缺陷（2026-10-26 14:29 复现）**：`PrismApplication.onCreate` 里无障碍自愈先于
 * Shizuku 就绪执行 → 以"需要 Shizuku"跳过；而后续没有重试触发点（只有回前台触发，
 * 后台执行期不回前台），于是无障碍**一直关闭**。修法是在"Shizuku 就绪 + 孤儿清理"之后
 * 补一次强制自愈。
 *
 * 本对象只描述"什么时候该再补一次"，便于 JVM 单测穷举；真正的调用在 `PrismApplication`。
 */
object StartupSelfHealPlan {

    /** 等待 Shizuku 就绪的轮询间隔（毫秒）。 */
    const val POLL_INTERVAL_MS = 5_000L

    /** 最多轮询次数（12 × 5s ≈ 60s 上限，超过即放弃，不无限等）。 */
    const val MAX_POLLS = 12

    /**
     * 是否应该继续等待 Shizuku 就绪。
     *
     * @param polls 已轮询次数
     * @param shizukuReady 当前是否就绪
     */
    fun shouldKeepWaiting(polls: Int, shizukuReady: Boolean): Boolean =
        !shizukuReady && polls < MAX_POLLS

    /**
     * 启动线程应执行的动作序列（按序执行；纯函数，便于断言"顺序与条件"）。
     *
     * @param shizukuReadyAfterWait 等待结束后 Shizuku 是否就绪
     */
    fun steps(shizukuReadyAfterWait: Boolean): List<Step> = buildList {
        if (shizukuReadyAfterWait) add(Step.SWEEP_ORPHAN_USER_SERVICES)
        // **无论** Shizuku 是否就绪都补一次强制自愈：未就绪时会给出"需要 Shizuku"的可读原因
        // （比"静默不动"好），就绪时则真正完成写回。
        add(Step.FORCE_ACCESSIBILITY_SELF_HEAL)
        add(Step.RECONCILE_RESIDUAL_DISPLAY)
    }

    /** 启动线程的动作。 */
    enum class Step {
        /** 清理孤儿 `io.prism:shizuku` 进程（需 Shizuku 就绪）。 */
        SWEEP_ORPHAN_USER_SERVICES,

        /** 强制跑一次无障碍健康检查/自愈（限频由 Monitor 内部窗口控制）。 */
        FORCE_ACCESSIBILITY_SELF_HEAL,

        /** 对账残留隐藏虚拟屏 / 模拟副屏设置。 */
        RECONCILE_RESIDUAL_DISPLAY
    }
}
