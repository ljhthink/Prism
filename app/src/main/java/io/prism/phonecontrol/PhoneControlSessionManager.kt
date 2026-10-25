package io.prism.phonecontrol

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * v1 批次14（Bug 修复 TKN-V1B14-KEEPALIVE-BUG-001）：手机操控任务期动态保活会话管理。
 *
 * **背景（真机证据 docs/reports/2026-08-23-keepalive-bug-debug.md）**：批次11 F2 将保活前台
 * 服务绑定在「无障碍启用期」而非「操控任务期」——真机 dumpsys 证据：服务常驻 1d8h10m、
 * 通知 ONGOING|NO_CLEAR 不间断展示、START_STICKY 被杀自动重启，用户不使用软件也弹窗 +
 * 后台高优先级驻留致整机卡顿。
 *
 * **修订策略（ADR-041）**：保活绑定「任务期」——每个 `phone_control__*` 工具调用经
 * [onPhoneToolInvoked] 刷新活跃时间戳（首个调用确保前台服务运行）；空闲超过
 * [IDLE_TIMEOUT_MS] 自动停止服务。功能完整性不变（任务进行中始终保活，防 MIUI 回收
 * 导致 get_ui_state 失效/流中断，批次10/11 修复目标保持），闲置期零占用、无常驻弹窗。
 *
 * **可测性**（BR-testing-004）：时间源/上下文/启停/调度四个依赖均为注入点，核心状态机
 * （幂等 start / 时间戳刷新 / 空闲释放 / 重启会话）可在纯 JVM 单元测试覆盖。
 */
object PhoneControlSessionManager {

    private const val TAG = "PhoneControlSession"

    /**
     * 任务空闲超时：最后一次手机工具调用后保留保活的时长。
     * LLM 多轮思考间隔通常 <60s，取约双倍余量防误停（用户确认决策：120s）。
     */
    internal const val IDLE_TIMEOUT_MS = 120_000L

    /** 时间源（测试注入虚拟时钟）。 */
    @Volatile
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }

    /** Application context 提供者：无障碍未连接时为 null（无保活意义，静默跳过）。 */
    @Volatile
    internal var appContextProvider: () -> android.content.Context? =
        { PhoneControlAccessibilityService.instance?.applicationContext }

    /** 保活服务启动委托（测试注入计数器替代真实 startForegroundService）。 */
    @Volatile
    internal var keepAliveStarter: (android.content.Context) -> Unit =
        { PhoneControlKeepAliveService.start(it) }

    /** 保活服务停止委托。 */
    @Volatile
    internal var keepAliveStopper: (android.content.Context) -> Unit =
        { PhoneControlKeepAliveService.stop(it) }

    /**
     * 延迟调度器：(delayMs, action)。生产为主线程 Handler；测试注入手动触发的 fake。
     * null 表示禁用调度（仅测试兜底）。
     */
    @Volatile
    internal var scheduler: ((delayMs: Long, action: Runnable) -> Unit)? =
        { delay, action -> mainHandler().postDelayed(action, delay) }

    private fun mainHandler(): Handler = Handler(Looper.getMainLooper())

    /** 保护状态机读改写（IO 工具协程写入 + 主线程 idle 回调读取）。 */
    private val sessionLock = Any()

    /** 是否存在待决的空闲检查（true = 保活会话进行中，服务应处于前台）。 */
    @Volatile
    private var sessionActive = false

    /** 最近一次手机工具调用时间戳（会话活跃判定基准）。 */
    @Volatile
    private var lastActiveAtMs = 0L

    /**
     * 批次22 修复轮五（2026-09-07）：任务期亮屏锁——**息屏时注入到虚拟屏的触摸/按键会被
     * 系统输入策略丢弃**（Display OFF，真机实证"shell 注入成功但屏幕无变化"）。SCREEN_BRIGHT
     * 仅保持亮屏（不主动唤醒已息屏设备），20 分钟上限自愈。
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLockLocked(ctx: android.content.Context) {
        // 非引用计数锁：重复 acquire 会重置 20min 窗口（真正实现"刷新"）——guardrail F2
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "prism:bgControl")
        lock.setReferenceCounted(false)
        runCatching { lock.acquire(20 * 60_000L) }  // guardrail B1 兜底：ROM 差异不逃逸
        runCatching { wakeLock?.release() }
        wakeLock = lock
        Log.i(TAG, "bg 会话亮屏锁已持有（20min 窗口，工具调用刷新）")
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    /**
     * 手机工具调用入口（由 [PhoneControlLocalToolExecutor.execute] 每次调用）。
     *
     * - 首次调用：标记会话活跃 + 启动保活前台服务 + 排定空闲检查；
     * - 后续调用：仅刷新时间戳（服务已在运行，重复 startForegroundService 无必要）；
     * - 无障碍未连接：静默跳过（工具层本就会返回引导开启文案）。
     *
     * FGS 后台启动受限（Android 12+）由 [PhoneControlKeepAliveService.start] 容错降级，
     * 不阻断工具执行。
     *
     * guardrail M-1（TKN-V1B14-GUARDRAIL-001）：context 取值与判空必须在锁内一次性完成——
     * 锁外预检 + 锁内重复取值存在 TOCTOU（无障碍 onDestroy 恰在两行之间执行时，
     * `!!` 抛 KNPE 且 sessionActive 已置 true 而 idle 检查未排定，保活静默失效且本次
     * 连接内无自愈路径）。
     */
    fun onPhoneToolInvoked() {
        synchronized(sessionLock) {
            val ctx = appContextProvider() ?: return
            lastActiveAtMs = nowMillis()
            if (!sessionActive) {
                sessionActive = true
                keepAliveStarter(ctx)
                acquireWakeLockLocked(ctx)
                scheduleIdleCheckLocked()
                Log.i(TAG, "手机操控会话开始，启动任务期保活")
            } else {
                // 批次22 修复轮五：刷新亮屏锁（acquire 延长 20min 窗口）
                acquireWakeLockLocked(ctx)
                Log.d(TAG, "手机操控会话保活刷新")
            }
        }
    }

    /**
     * 为**定时任务**临时保持亮屏（2026-10-25，ADR-063 修订；PRD US-3115 AC-6）。
     *
     * 与 [onPhoneToolInvoked] 的区别：定时主动消息不走交互工具入口，若不单独持锁，
     * 唤醒后屏幕可能在 UI 自动化过程中再次熄灭（注入再次被丢弃）。这里只刷新亮屏锁，
     * **不启动保活服务、不改变会话状态机**；锁为非引用计数、20 分钟自然过期，
     * 下一个交互工具调用会照常刷新。
     *
     * @return 是否成功持锁（无上下文时 false）
     */
    fun holdScreenForScheduledTask(): Boolean {
        val ctx = appContextProvider() ?: return false
        return synchronized(sessionLock) {
            runCatching {
                acquireWakeLockLocked(ctx)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 空闲到期检查（主线程回调）：距最近一次工具调用满 [IDLE_TIMEOUT_MS] 则停止保活并
     * 释放后台虚拟屏（guardrail P1-2：任务期结束 = 空闲超时，虚拟屏 ImageReader/HandlerThread
     * 与 force-stop 回收集中在此收口——[io.prism.phonecontrol.bg.VirtualDisplayManager.ensureDisplay]
     * 幂等，下次后台调用自动重建）；否则说明期间有新调用刷新了时间戳，继续排定下一轮检查。
     */
    internal fun checkIdle() {
        synchronized(sessionLock) {
            if (!sessionActive) return
            val idleFor = nowMillis() - lastActiveAtMs
            if (idleFor >= IDLE_TIMEOUT_MS) {
                sessionActive = false
                releaseWakeLock()
                val ctx = appContextProvider()
                if (ctx != null) {
                    keepAliveStopper(ctx)
                    // 批次21（guardrail R5 P2-1）：虚拟屏回收移到后台线程——Shizuku force-stop
                    // 单条命令最长 5s，主线程同步执行会阻塞 checkIdle 回调（ANR 风险）。
                    // checkIdle 在主线程回调（scheduler=mainHandler）。
                    val releaseCtx = ctx.applicationContext
                    Thread {
                        runCatching {
                            io.prism.phonecontrol.bg.VirtualDisplayManager.releaseDisplay(releaseCtx)
                        }
                    }.start()
                }
                Log.i(TAG, "手机操控空闲 ${idleFor}ms ≥ ${IDLE_TIMEOUT_MS}ms，停止保活前台服务（虚拟屏回收已在后台线程执行）")
            } else {
                scheduleIdleCheckLocked()
            }
        }
    }

    /**
     * 会话复位：无障碍服务断开（[PhoneControlAccessibilityService.onDestroy]）时调用，
     * 保证下次连接后的首个工具调用重新走 start 分支；亦用于单元测试隔离。
     * 修复轮八：无障碍断开 = 后台操控能力不可用 → 同步释放后台虚拟屏（进程死亡前的
     * 最后一次释放机会；后台线程执行防 ANR——Shizuku 命令单条最长 5s）。
     */
    internal fun reset() {
        val ctx = appContextProvider()
        synchronized(sessionLock) {
            sessionActive = false
            lastActiveAtMs = 0L
            releaseWakeLock()
        }
        if (ctx != null) {
            val releaseCtx = ctx.applicationContext
            Thread {
                runCatching {
                    io.prism.phonecontrol.bg.VirtualDisplayManager.releaseDisplay(releaseCtx)
                }
            }.start()
        }
    }

    private fun scheduleIdleCheckLocked() {
        scheduler?.invoke(IDLE_TIMEOUT_MS) { checkIdle() }
    }
}
