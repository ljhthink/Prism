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
                scheduleIdleCheckLocked()
                Log.i(TAG, "手机操控会话开始，启动任务期保活")
            } else {
                Log.d(TAG, "手机操控会话保活刷新")
            }
        }
    }

    /**
     * 空闲到期检查（主线程回调）：距最近一次工具调用满 [IDLE_TIMEOUT_MS] 则停止保活；
     * 否则说明期间有新调用刷新了时间戳，继续排定下一轮检查。
     */
    internal fun checkIdle() {
        synchronized(sessionLock) {
            if (!sessionActive) return
            val idleFor = nowMillis() - lastActiveAtMs
            if (idleFor >= IDLE_TIMEOUT_MS) {
                sessionActive = false
                val ctx = appContextProvider()
                if (ctx != null) keepAliveStopper(ctx)
                Log.i(TAG, "手机操控空闲 ${idleFor}ms ≥ ${IDLE_TIMEOUT_MS}ms，停止保活前台服务")
            } else {
                scheduleIdleCheckLocked()
            }
        }
    }

    /**
     * 会话复位：无障碍服务断开（[PhoneControlAccessibilityService.onDestroy]）时调用，
     * 保证下次连接后的首个工具调用重新走 start 分支；亦用于单元测试隔离。
     */
    internal fun reset() {
        synchronized(sessionLock) {
            sessionActive = false
            lastActiveAtMs = 0L
        }
    }

    private fun scheduleIdleCheckLocked() {
        scheduler?.invoke(IDLE_TIMEOUT_MS) { checkIdle() }
    }
}
