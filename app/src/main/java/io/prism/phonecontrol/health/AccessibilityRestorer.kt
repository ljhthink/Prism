package io.prism.phonecontrol.health

import android.content.Context
import android.util.Log
import io.prism.phonecontrol.PhoneControlAccessibilityService
import io.prism.phonecontrol.bg.ShizukuShell

/**
 * 无障碍**自愈**执行器（ADR-046 修订 2026-10-26；PRD US-3116）。
 *
 * **职责**：检测到 [AccessibilityHealth.State.DISABLED] 时，用 shell（Shizuku）**合并写回**
 * 本服务的注册项并校验。**不碰**其它无障碍服务、**不 force-stop** 任何系统组件。
 *
 * **为什么用 shell 而不是 App 自己写**：`ENABLED_ACCESSIBILITY_SERVICES` 是 `secure` 设置，
 * 写它需要 `WRITE_SECURE_SETTINGS`；普通 App 拿不到，而 shell 天然持有（这也正是社区
 * 工具的统一做法，见 `docs/reports/2026-10-26-a11y-shizuku-qq-fix-plan.md` §1.1）。
 *
 * **线程与频率**：只由 [AccessibilityHealthMonitor] 在主线程之外低频调用；内部有
 * [AccessibilityHealth.ATTEMPT_WINDOW_MS] 窗口限频，避免与安全中心反复拉锯。
 */
object AccessibilityRestorer {

    private const val TAG = "AccessibilityRestorer"

    /** 本服务组件名（必须与 AndroidManifest 中 `android:name` 完全一致）。 */
    private const val COMPONENT =
        "io.prism/io.prism.phonecontrol.PhoneControlAccessibilityService"

    private const val SECURE_NS = "secure"

    /** 时间窗内已尝试次数（进程内；窗口过期自动归零）。 */
    @Volatile
    private var attemptsInWindow: Int = 0

    @Volatile
    private var windowStartedAtMs: Long = 0L

    /** 最近一次结果（供设置页/通知展示；空=从未尝试）。 */
    @Volatile
    var lastResult: String = ""
        private set

    /** 最近一次尝试时刻（0=从未）。 */
    @Volatile
    var lastAttemptAtMs: Long = 0L
        private set

    /**
     * 尝试恢复一次。
     *
     * @param context 应用上下文
     * @param selfHealEnabled 用户开关（默认开）
     * @return 结果三态之一，附可读说明（成功/失败都写日志与 [lastResult]）
     */
    fun tryRestore(context: Context, selfHealEnabled: Boolean): Result {
        val state = currentState(context)
        // guardrail G-04：准入闸必须与单测断言的是**同一个函数**（此前用 blockReason，
        // 它对非 DISABLED 返回 null → REBINDING 也会写 settings，与文档矛盾）。
        if (!AccessibilityHealth.shouldAttemptRestore(
                state = state,
                selfHealEnabled = selfHealEnabled,
                shizukuReady = ShizukuShell.isReady(),
                attemptInWindow = attemptsWithinWindow()
            )
        ) {
            val reason = AccessibilityHealth.blockReason(
                state = state,
                selfHealEnabled = selfHealEnabled,
                shizukuReady = ShizukuShell.isReady(),
                attemptInWindow = attemptsWithinWindow()
            ) ?: "无需恢复（当前状态=$state）"
            val r = Result.Skipped(reason)
            record(r.describe)
            return r
        }
        val before = readSetting(context)
        val merged = AccessibilityMerge.append(before, COMPONENT)
        attemptsInWindow = attemptsWithinWindow() + 1
        lastAttemptAtMs = System.currentTimeMillis()

        // 1) 写合并值 + 打开总开关（两条命令都必须成功）
        val wroteServices = ShizukuShell.putSecureSetting(
            "enabled_accessibility_services", merged
        )
        val enabledFlag = ShizukuShell.putSecureSetting("accessibility_enabled", "1")
        if (!wroteServices || !enabledFlag) {
            val r = Result.Failed("写入系统设置失败（Shizuku 可能刚断开）")
            record(r.describe)
            return r
        }

        // 2) 校验：读回值里本服务与原有服务都在（防"写成功但被系统回滚"）
        val after = readSetting(context)
        val lost = AccessibilityMerge.lostExisting(before, after)
        if (lost.isNotEmpty()) {
            // guardrail G-06：回滚**不能**整体写回写前快照（期间可能有系统/第三方新增条目，
            // 会被一并抹掉）。改为"在当前读回值里移除本组件"，只撤销我们这一笔。
            val rolled = AccessibilityMerge.remove(after, COMPONENT)
            runCatching { ShizukuShell.putSecureSetting("enabled_accessibility_services", rolled) }
            val verify = readSetting(context)
            val stillLost = AccessibilityMerge.lostExisting(before, verify)
            val r = Result.Failed(
                if (stillLost.isEmpty()) {
                    "写回后丢失了 ${lost.size} 个既有服务，已撤销本次写入（原服务已恢复）"
                } else {
                    "写回后丢失了 ${lost.size} 个既有服务，撤销未完全生效：请手动检查系统无障碍设置"
                }
            )
            record(r.describe)
            return r
        }
        if (!AccessibilityMerge.isApplied(merged, after)) {
            val r = Result.Failed("写回未生效（系统可能再次清除）：请手动前往系统无障碍设置打开 Prism")
            record(r.describe)
            return r
        }

        // 3) 等待服务实例重新绑定（注册成功 ≠ 实例已连）
        val bound = awaitBinding(context, timeoutMs = BIND_WAIT_MS)
        val r = if (bound) {
            Result.Restored("已自动恢复无障碍（${AccessibilityMerge.parse(after).size} 项服务保持启用）")
        } else {
            Result.Failed("已写回注册项，但服务实例尚未连接：请打开一次 Prism 或稍候")
        }
        record(r.describe)
        return r
    }

    /** 当前三态（供 Monitor / 设置页 / debug 探针共用）。 */
    fun currentState(context: Context): AccessibilityHealth.State =
        AccessibilityHealth.state(
            enabledInSystem = PhoneControlAccessibilityService.isEnabledInSystem(context),
            serviceConnected = PhoneControlAccessibilityService.instance != null
        )

    /** 读 `enabled_accessibility_services` 原值。 */
    private fun readSetting(context: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    }.getOrNull()

    /** 轮询等待服务实例连接（最多 [timeoutMs]）。 */
    private fun awaitBinding(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (PhoneControlAccessibilityService.instance != null) return true
            runCatching { Thread.sleep(BIND_POLL_MS) }
        }
        return PhoneControlAccessibilityService.instance != null
    }

    /** 时间窗内尝试次数（窗口过期归零）。 */
    private fun attemptsWithinWindow(): Int {
        val now = System.currentTimeMillis()
        if (now - windowStartedAtMs > AccessibilityHealth.ATTEMPT_WINDOW_MS) {
            windowStartedAtMs = now
            attemptsInWindow = 0
        }
        return attemptsInWindow
    }

    private fun record(description: String) {
        lastResult = description
        // 成功与失败都留痕（对齐 BR-observability-004）
        Log.i(TAG, description)
    }

    /** 恢复结果（三态；`Skipped` 与 `Failed` 必须可读，不允许静默）。 */
    sealed interface Result {
        val describe: String

        data class Restored(override val describe: String) : Result
        data class Failed(override val describe: String) : Result
        data class Skipped(override val describe: String) : Result
    }

    /** 组件名常量对外只读（debug 探针/测试用）。 */
    fun componentName(): String = COMPONENT

    private const val BIND_WAIT_MS = 2_500L
    private const val BIND_POLL_MS = 150L
}
