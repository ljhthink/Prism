package io.prism.phonecontrol.bg

/**
 * 执行面**拆卸步骤**的纯逻辑（ADR-065 修订 2026-10-26；PRD US-3119 AC-1）。
 *
 * **为什么单独抽出来**：真机上"AI 用完 App 后画面闪到主屏"的根因是**步骤顺序**——
 * 旧实现先销毁显示面、后 force-stop 目标，于是系统在两者之间把 task 迁回 display 0。
 * 顺序属于"实现里最容易再次被改错的一行"，必须做成**可断言的对象**，否则任何测试都只能
 * 复刻一遍顺序、与实现各写一份（恒真）。
 *
 * 本对象是生产代码的同源定义：`VirtualDisplayManager.releaseDisplay` 按它执行，
 * 单测断言它的顺序。
 */
internal object DisplayTeardown {

    /** 拆卸步骤。 */
    enum class Step {
        /** 回收本进程启动过的目标包（force-stop）。 */
        STOP_LAUNCHED_PACKAGES,

        /** 摘掉 ImageReader 监听（阻止新回调入队）。 */
        DETACH_LISTENER,

        /** 停止帧监听线程并等待在途回调。 */
        STOP_LISTENER_THREAD,

        /** 销毁显示面（VirtualDisplay.release / 后端 release）。 */
        RELEASE_DISPLAY,

        /** 关闭 ImageReader。 */
        CLOSE_READER,

        /** 清除 overlay 设置（legacy 模拟副屏小窗）。 */
        CLEAR_OVERLAY_SETTING
    }

    /**
     * **隐藏后端**的拆卸顺序：先回收目标、再销毁显示面。
     *
     * 关键约束：`STOP_LAUNCHED_PACKAGES` 必须早于 `RELEASE_DISPLAY`。
     */
    val HIDDEN_ORDER: List<Step> = listOf(
        Step.STOP_LAUNCHED_PACKAGES,
        Step.RELEASE_DISPLAY
    )

    /**
     * **legacy 自建屏**的拆卸顺序。
     *
     * 与隐藏后端的差别只在"需要先摘监听/停线程"；**同样**要求 stop 早于 release。
     * overlay 设置清理放在最后（它只影响可见小窗，与 task 迁移无关）。
     */
    val LEGACY_ORDER: List<Step> = listOf(
        Step.DETACH_LISTENER,
        Step.STOP_LISTENER_THREAD,
        Step.STOP_LAUNCHED_PACKAGES,
        Step.RELEASE_DISPLAY,
        Step.CLOSE_READER,
        Step.CLEAR_OVERLAY_SETTING
    )

    /**
     * 顺序是否满足"回收目标早于销毁显示面"（不变量检查，供测试与自检共用）。
     */
    fun stopsBeforeRelease(order: List<Step>): Boolean {
        val stop = order.indexOf(Step.STOP_LAUNCHED_PACKAGES)
        val release = order.indexOf(Step.RELEASE_DISPLAY)
        if (stop < 0 || release < 0) return false
        return stop < release
    }

    /**
     * 执行一份拆卸计划（**生产代码的唯一执行入口**）。
     *
     * guardrail 复核指出：若 `releaseDisplay` 自己写一段硬编码顺序、只是"引用"本清单，
     * 单测断言清单仍然恒绿。故把**执行**也收进本对象，`releaseDisplay` 只提供各步骤的实现，
     * 顺序由这里决定 —— 这样"改回旧顺序"必然要改本清单，测试立刻红。
     *
     * @param order 计划（HIDDEN_ORDER / LEGACY_ORDER）
     * @param handlers 步骤 → 实现（缺失的步骤按 no-op 处理，便于隐藏后端复用 LEGACY 计划）
     * @return 执行过的步骤序列（便于调用方记日志/测试断言）
     */
    fun run(order: List<Step>, handlers: Map<Step, () -> Unit>): List<Step> {
        require(stopsBeforeRelease(order)) {
            "拆卸顺序违规（必须先回收目标再销毁显示面）：$order"
        }
        val executed = mutableListOf<Step>()
        order.forEach { step ->
            handlers[step]?.invoke()
            executed += step
        }
        return executed
    }
}
