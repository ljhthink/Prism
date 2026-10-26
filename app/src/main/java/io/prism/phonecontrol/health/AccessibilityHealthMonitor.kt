package io.prism.phonecontrol.health

import android.content.Context
import android.util.Log
import io.prism.phonecontrol.PhoneControlAccessibilityService

/**
 * 无障碍**健康监测**（ADR-046 修订 2026-10-26；PRD US-3116 AC-1/AC-4/AC-5）。
 *
 * **为什么需要**：HyperOS 安全中心会清掉本应用的无障碍注册（真机实测），而 Prism 此前
 * 只在工具调用失败时才被动发现。现在改为主动、低频、可解释：
 * - 只在**有会话/前台动作**时检查（由 [notifyAppActive] 打点），不常驻轮询；
 * - 间隔 ≥ [AccessibilityHealth.CHECK_INTERVAL_MS]（30s），不耗电；
 * - 状态变化才动作（`OK → DISABLED` 触发自愈 + 通知；`REBINDING` 只提示等它自己连回来）。
 *
 * **实际打点位置（guardrail G-09 / R2-07，如实声明）**：
 * 1. `PrismApplication.onCreate`（冷启动一次，限频）；
 * 2. `Activity.onActivityResumed`（每次回到前台，限频）；
 * 3. 设置页「无障碍自动恢复」开关打开时（强制一次）。
 *
 * **已知限制**：**后台执行期间不会检测**——用户不在 App 内、而 QQ 后台发消息的时段，
 * 若系统此时清掉注册，要等下次回前台才自愈。之所以不在工具入口加打点，是为了避免
 * 每次工具调用都触碰系统设置读取（成本与耗电权衡）；若后续需要，可在
 * `PhoneControlLocalToolExecutor` 前置守卫加一次（已有 30s 限频）。
 */
object AccessibilityHealthMonitor {

    private const val TAG = "A11yHealth"

    @Volatile
    private var lastCheckAtMs: Long = 0L

    @Volatile
    private var lastState: AccessibilityHealth.State = AccessibilityHealth.State.OK

    /**
     * **single-flight 闸**（guardrail **R4-03**）。
     *
     * 打点有三处（`onCreate` 线程 / `Activity.onActivityResumed` / 设置页开关 / 启动后就绪补一次），
     * 而 `lastState`、`AccessibilityRestorer` 的窗口计数都不是原子的。并发进入会导致：
     * ① 同一窗口内重复尝试（超出 10 分钟 3 次的上限）；② 重复通知；
     * ③ 两个线程各自用陈旧的 `before` 快照做"合并写回"，**可能覆盖掉期间新增的第三方无障碍服务**
     * ——这是本模块唯一能改坏用户别的配置的方向。
     *
     * 用 `tryLock` 做非阻塞互斥：拿不到锁说明已有一次检查在跑，直接返回当前状态（不排队）。
     */
    private val checkLock = java.util.concurrent.locks.ReentrantLock()

    /** 最近一次对外展示的状态（设置页/通知用）。 */
    val currentState: AccessibilityHealth.State
        get() = lastState

    /** 自愈开关（由 PrismApplication 从 DataStore 注入；默认开，用户可关）。 */
    @Volatile
    var selfHealEnabledProvider: () -> Boolean = { true }

    /** 状态变化回调（用于发通知/刷新设置页；由应用层注入，避免本类依赖 UI）。 */
    @Volatile
    var onStateChanged: ((old: AccessibilityHealth.State, new: AccessibilityHealth.State, message: String) -> Unit)? =
        null

    /**
     * 应用处于活跃期（打开 App、调用手机操控工具）时打点，触发一次**限频**检查。
     *
     * @return 本次检查结果（未到检查间隔时返回上次状态）
     */
    fun notifyAppActive(context: Context, force: Boolean = false): AccessibilityHealth.State {
        // R4-03：并发进入时直接返回当前状态（不排队、不重复写）
        if (!checkLock.tryLock()) {
            Log.i(TAG, "已有一次检查在进行，跳过本次打点")
            return lastState
        }
        try {
            return checkLocked(context, force)
        } finally {
            checkLock.unlock()
        }
    }

    private fun checkLocked(context: Context, force: Boolean): AccessibilityHealth.State {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAtMs < AccessibilityHealth.CHECK_INTERVAL_MS) {
            return lastState
        }
        lastCheckAtMs = now
        val state = AccessibilityRestorer.currentState(context)
        val old = lastState
        lastState = state

        if (state == old) {
            // 状态未变：`DISABLED` 持续存在时仍要按窗口限频重试（系统可能后续放行）
            if (state == AccessibilityHealth.State.DISABLED) {
                val message = maybeRestore(context)
                // 自愈成功时状态已变为 OK —— 必须把**新状态**回调出去，
                // 否则调用方会按 DISABLED 发"被系统关闭"通知（guardrail G-10）。
                val now = lastState
                if (now != state) onStateChanged?.invoke(state, now, message)
            }
            return lastState
        }
        Log.i(TAG, "无障碍状态变化：$old → $state")
        val message = when (state) {
            AccessibilityHealth.State.OK -> "无障碍已就绪"
            AccessibilityHealth.State.REBINDING -> "无障碍服务正在重连（通常数秒内自动恢复）"
            AccessibilityHealth.State.DISABLED -> maybeRestore(context)
        }
        // 自愈可能把状态改回 OK；以最终状态回调，避免"已恢复却通知说被关闭"
        val finalState = lastState
        onStateChanged?.invoke(old, finalState, message)
        return finalState
    }

    /**
     * 仅在 `DISABLED` 时尝试自愈，返回给用户看的说明。
     *
     * 自愈的三种结果都会**显式**返回文案（不允许静默），由调用方发通知/更新设置页。
     */
    private fun maybeRestore(context: Context): String {
        val enabled = runCatching { selfHealEnabledProvider() }.getOrDefault(true)
        val result = AccessibilityRestorer.tryRestore(context, enabled)
        // 恢复成功后立即复检（避免"写回成功但仍 DISABLED"的假成功进入展示）
        if (result is AccessibilityRestorer.Result.Restored) {
            val recheck = AccessibilityRestorer.currentState(context)
            if (recheck == AccessibilityHealth.State.OK) {
                lastState = recheck
                return result.describe
            }
            return "已写回注册项，但服务尚未连接（${recheck}）：请打开一次 Prism"
        }
        return result.describe
    }

    /** 供测试/设置页复位。 */
    internal fun resetForTest() {
        lastCheckAtMs = 0L
        lastState = AccessibilityHealth.State.OK
    }

    /** 强制读一次系统真实状态（不改 lastState；debug 探针用）。 */
    fun readState(context: Context): AccessibilityHealth.State =
        AccessibilityHealth.state(
            enabledInSystem = PhoneControlAccessibilityService.isEnabledInSystem(context),
            serviceConnected = PhoneControlAccessibilityService.instance != null
        )
}
