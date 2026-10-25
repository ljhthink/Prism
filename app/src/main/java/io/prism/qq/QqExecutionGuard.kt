package io.prism.qq

/**
 * 后台执行前的**环境守卫**（ADR-063 修订 2026-10-25；PRD US-3115 AC-6）。
 *
 * **为什么需要**：真机取证（2026-10-25）显示隐藏虚拟屏会随整机熄屏进入 `state=OFF`，
 * 此时 `ImageReader` 不再产出新帧、`input` 注入也会被系统输入策略丢弃（项目既有规则
 * 已实证）。而定时任务恰好常在夜间/熄屏时到点 → "点不动、发不出"，且原实现把它记成
 * 账号失败并熔断。
 *
 * **职责**：只做**判定**与**唤醒动作**，不做业务决策：
 * 1. 屏幕是否可交互；
 * 2. 执行面显示是否处于可用状态；
 * 3. 需要时执行一次唤醒（仅当调用方允许，例如定时任务；被动回复不唤醒）。
 *
 * **可测性**：所有系统能力均为注入函数（时钟/唤醒/显示状态/交互态），纯 JVM 可测。
 */
object QqExecutionGuard {

    /** 判定结果。 */
    enum class Kind {
        /** 环境就绪。 */
        READY,

        /** 屏幕熄灭，已按策略唤醒并恢复可交互。 */
        WOKEN,

        /** 环境阻塞（原因可读，调用方必须跳过且**不记账号失败**）。 */
        BLOCKED
    }

    data class Status(val kind: Kind, val reason: String = "") {
        val blocked: Boolean get() = kind == Kind.BLOCKED
    }

    // ==================== 注入点（生产在 PrismApplication 装配） ====================

    /** 屏幕是否处于可交互状态（`PowerManager.isInteractive`）。 */
    @Volatile
    var interactiveProvider: () -> Boolean = { true }

    /** 执行一次"点亮屏幕"（生产：Shizuku `input keyevent KEYCODE_WAKEUP`）。 */
    @Volatile
    var wakeUpAction: () -> Boolean = { false }

    /**
     * 唤醒成功后**临时保持亮屏**（生产：复用 `PhoneControlSessionManager` 的 SCREEN_BRIGHT 锁）。
     *
     * 必要性：只发一次 WAKEUP 后屏幕可能在 UI 自动化过程中再次熄灭，届时注入又被系统丢弃——
     * 表现为"唤醒过但还是没发出去"。
     */
    @Volatile
    var keepScreenOnAction: () -> Unit = { }

    /** 显示面电源状态（生产：`Display.getState()`；null=显示不存在）。 */
    @Volatile
    var displayStateProvider: (Int) -> Int? = { null }

    /**
     * 创建执行面的**前置条件**探测（guardrail G-07）。
     *
     * @return null=前置齐备；否则返回可读的阻塞原因（无障碍未开/Shizuku 未就绪/缺悬浮窗权限）。
     *   定时任务在**唤醒屏幕之前**必须先用它确认"即使唤醒也发得出去"，否则会出现
     *   「先点亮屏幕、持 20 分钟亮屏锁、再放弃」的扰民副作用。
     */
    @Volatile
    var preflightProvider: () -> String? = { null }

    /** 熄屏策略：true=临时点亮执行（默认）；false=跳过并通知。 */
    @Volatile
    var wakeOnScreenOffProvider: () -> Boolean = { true }

    /** 休眠（唤醒后等待系统恢复合成；测试可注入空实现）。 */
    @Volatile
    var sleeper: (Long) -> Unit = { ms ->
        runCatching { Thread.sleep(ms) }
    }

    // 与 android.view.Display 常量对齐（避免本对象依赖 Android 类以便纯 JVM 测试）
    const val STATE_UNKNOWN = 0
    const val STATE_OFF = 1
    const val STATE_ON = 2
    const val STATE_DOZE = 3
    const val STATE_DOZE_SUSPEND = 4
    const val STATE_ON_SUSPEND = 5

    /** 唤醒后等待合成恢复的时长。 */
    private const val WAKE_SETTLE_MS = 600L

    /**
     * 纯判定（无副作用；唤醒动作由 [ensureReady] 负责）。
     *
     * **`displayState == null` 的语义（guardrail G-02）**：执行面是**惰性创建**的
     * （发送链路 `withQqBackground` 会 `ensureDisplay`），所以"尚未创建"不等于"不可用"。
     * 真正的可行性由 [preflightProvider] 与 [ensureReady] 前置检查负责；这里只在
     * **已存在但已关闭**（`STATE_OFF`）时判阻塞——那种情况即使唤醒屏幕也要重建，属可重试项。
     *
     * @param interactive 屏幕可交互
     * @param wakePolicy 熄屏时是否允许唤醒（true=α 点亮执行）
     * @param displayState 执行面显示状态（null=尚未创建）
     * @param requireDisplay 是否要求执行面可用（协议端发送不需要）
     */
    fun decide(
        interactive: Boolean,
        wakePolicy: Boolean,
        displayState: Int?,
        requireDisplay: Boolean
    ): Status = when {
        // 区分"用户策略选择熄屏跳过"与"本次调用不允许唤醒（被动回复不打扰用户）"——
        // 两者结果相同但原因不同，混用同一句会让真机 RCA 指向错误的假设。
        !interactive && !wakePolicy ->
            Status(Kind.BLOCKED, "屏幕已熄灭（本次不允许唤醒屏幕）")
        !interactive -> Status(Kind.READY, "屏幕已熄灭，需要先唤醒")
        requireDisplay && displayState == STATE_OFF ->
            Status(Kind.BLOCKED, "后台执行面不可用（显示面已关闭）")
        else -> Status(Kind.READY)
    }

    /**
     * 确保环境就绪（必要时唤醒一次）。
     *
     * **顺序（guardrail G-02）**：先 [preflightProvider]（无障碍/Shizuku/悬浮窗）→
     * 再 [decide]（屏幕/显示面）→ 才执行唤醒。这样"注定发不出去"的情况**不会点亮屏幕**。
     *
     * @param displayId 执行面 displayId（`INVALID_DISPLAY`=尚未创建，由发送链路惰性创建）
     * @param requireDisplay 是否需要 UI 执行面
     * @param allowWake 是否允许唤醒屏幕（定时任务 true；被动回复 false——不打扰用户）
     * @return [Status]；`blocked=true` 时调用方必须跳过并记录原因
     */
    fun ensureReady(displayId: Int, requireDisplay: Boolean, allowWake: Boolean): Status {
        // ① 前置条件：不满足就直接放弃，绝不唤醒屏幕
        if (requireDisplay) {
            val missing = runCatching { preflightProvider() }.getOrNull()
            if (!missing.isNullOrBlank()) {
                return Status(Kind.BLOCKED, missing)
            }
        }

        var interactive = runCatching { interactiveProvider() }.getOrDefault(true)
        val wakePolicy = allowWake && runCatching { wakeOnScreenOffProvider() }.getOrDefault(true)

        val initial = decide(
            interactive = interactive,
            wakePolicy = wakePolicy,
            displayState = if (requireDisplay) readDisplayState(displayId) else STATE_ON,
            requireDisplay = requireDisplay
        )
        if (initial.kind == Kind.BLOCKED) return initial
        if (interactive) return initial

        // 熄屏 + 允许唤醒：执行一次唤醒并等待合成恢复，再复检
        val woke = runCatching { wakeUpAction() }.getOrDefault(false)
        if (!woke) {
            return Status(Kind.BLOCKED, "屏幕已熄灭且唤醒失败（Shizuku 可能未就绪）")
        }
        // 临时保持亮屏（执行期间不再熄灭）；失败不阻断——最多退化为"可能被中途熄灭"
        runCatching { keepScreenOnAction() }
        sleeper(WAKE_SETTLE_MS)
        interactive = runCatching { interactiveProvider() }.getOrDefault(false)
        if (!interactive) {
            return Status(Kind.BLOCKED, "已尝试唤醒屏幕，但屏幕仍不可交互")
        }
        val after = decide(
            interactive = true,
            wakePolicy = wakePolicy,
            displayState = if (requireDisplay) readDisplayState(displayId) else STATE_ON,
            requireDisplay = requireDisplay
        )
        if (after.kind == Kind.BLOCKED) return after
        return Status(Kind.WOKEN, "已临时点亮屏幕以执行本次任务")
    }

    private fun readDisplayState(displayId: Int): Int? =
        runCatching { displayStateProvider(displayId) }.getOrNull()
}
