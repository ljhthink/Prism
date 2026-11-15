package io.prism.qq

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import kotlin.random.Random

/**
 * 随机定时主动消息的调度承载服务（ADR-063；PRD US-3108）。
 *
 * **双层调度**：
 * 1. **前台服务心跳**（本服务）：`specialUse` FGS 豁免 Doze，进程内以随机延迟推进；
 * 2. **AlarmManager 精确闹钟兜底**：`ACTION_WAKE` 只负责**唤醒服务**（进程被杀后重启），
 *    使心跳循环重新评估——**不再直接发送**（CR-1：消除"闹钟 + 心跳各发一次"的重复发送）。
 *
 * **单一发送路径**：所有发送只经 [heartbeatLoop] 的"到期判定 → `fireOnce`"，
 * 到期判定依据持久化的 [QqRuntimeState.plannedTriggerAt]，故进程重启后亦**恰好发送一次**。
 *
 * **节流与熔断**：`fireOnce` 内先复查激活态（CR-2）与 [QqThrottle]（CR-3），
 * 随机化只决定"何时"，**不豁免频控**。
 *
 * **用户裁定**：打开 Prism 后常态化（无 `BOOT_COMPLETED`）；`START_NOT_STICKY`。
 */
class ProactiveScheduleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var replyJob: Job? = null

    /**
     * 用户是否**显式**停止了本服务（通知栏「停止」）。
     *
     * 2026-11-14（ADR-072 F1）：新增该标志是为了区分"用户不要了"与"系统把我杀了"——
     * 前者必须撤销所有唤醒源（否则会被反复叫醒，等于停止失效），后者必须**保留**唤醒源
     * （否则死了就再也不会回来）。
     */
    private var stopRequested = false

    /**
     * 连续"前台化被系统拒绝"次数（2026-11-15；ADR-073 / guardrail R-01）。
     *
     * **真值落盘**在 `QqRuntimeStateRepository`；这里只是本次实例的缓存。
     * 为什么不能只用实例字段：失败路径每次 `stopSelf()`，下次被闹钟唤起是**新实例**，
     * 实例字段归零 → `attempt` 恒 1、退避与 30 分钟通知节流全部失效
     * （真机 177 次拒绝 ≈ 每 6 分钟一条高优提醒，正是该缺陷的形态）。
     */
    @Volatile
    private var foregroundBlockCountCache = 0

    /**
     * 精确闹钟的**唤醒信号**（ADR-063 修订 2026-10-25）。
     *
     * 原实现里 `ACTION_WAKE` 只是再 `startForeground` 一次：`loopJob` 非空时不会打断正在
     * `delay` 的心跳协程，兜底**完全无效**。现在心跳的等待改为
     * `withTimeoutOrNull(...) { wakeSignal.receive() }`，闹钟到达即刻复评到期判定。
     *
     * **为什么用 `Channel.CONFLATED` 而不是 `MutableSharedFlow.tryEmit`（guardrail G-10）**：
     * SharedFlow(replay=0) 在**暂无订阅者**时 `tryEmit` 直接返回值被丢弃（订阅者恰好不在
     * `first()` 挂起点上，例如正在跑发送逻辑）——那一次唤醒就白发了，最坏要等 15 分钟分块。
     * CONFLATED 保证"总有 1 个待消费信号"，且多次唤醒自动合并（不需要排队）。
     */
    private val wakeSignal = kotlinx.coroutines.channels.Channel<Unit>(
        capacity = kotlinx.coroutines.channels.Channel.CONFLATED
    )

    /**
     * 当前重试计数所属的「窗口实例锚点」（epoch ms；0=尚未计算）。
     *
     * 与持久化的 `QqRuntimeState.retryWindowAnchor` 保持同样语义——进程内这份只用于
     * 「跳过通知」这类一次性提示的去重（计数本身以持久化状态为准，跨重启不重置）。
     */
    private var skipWindowKey: Long = 0L

    /** 「熄屏跳过」通知在本窗口是否已发过（防刷屏）。 */
    private var skipNoticeSentForWindow = false

    /**
     * **亮屏唤醒信号**（2026-10-26 新增）。
     *
     * 亮屏时若还在等 90s 轮询间隔，用户发完消息要等近一分半才可能被回——
     * 注册 `ACTION_SCREEN_ON` 后立即打断等待，复评一次（[QqPollPolicy] 亮屏分支直接放行）。
     */
    private val replyWake = Channel<Unit>(Channel.CONFLATED)

    private var screenReceiver: android.content.BroadcastReceiver? = null

    /**
     * 统一维护「本窗口」的进程内状态（guardrail **G-r2-04**）。
     *
     * 原实现里 `notifySkipped` 先读 `skipNoticeSentForWindow`、再由 `retryOrClear` 重置 →
     * **新窗口的第一次跳过通知会被吞掉**。现在窗口切换只在 [handleDue] 开头处理一次，
     * 通知与预算读到的必然是同一窗口的状态。
     *
     * @return 是否刚切换到新窗口（调用方可据此重置其它窗口级状态）
     */
    private fun ensureWindow(anchor: Long): Boolean {
        if (anchor == skipWindowKey) return false
        skipWindowKey = anchor
        skipNoticeSentForWindow = false
        return true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "用户请求停止定时主动消息（通知栏动作）")
            // CR-9：必须落盘——否则配置流的 collect 会立即把服务重新拉起（"停止"失效）
            stopRequested = true
            scope.launch {
                runCatching { QqConfigRepositoryHolder.get()?.setScheduleEnabled(false) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // 2026-11-14（ADR-072 F1）：**任何**进入服务的路径都要重挂存活看门狗。
        // 这是"进程被杀后还能自己回来"的唯一不依赖定时计划的入口——
        // 旧实现只在有定时计划时才登记闹钟，于是"只开自动回复"的用户死了就再也起不来。
        scheduleLivenessWatchdog()
        if (!startInForeground()) {
            // CR-10：前台服务启动失败（系统限制）→ 不能直接认输。
            //
            // 2026-11-15（真机决定性取证；ADR-073）：dumpsys 里 177 条
            // `Service.startForeground() not allowed due to bg restriction` 说明
            // **系统在后台拒绝启动 FGS**，而我们这里"只登记 5 分钟重试 + stopSelf"，
            // 于是每 5 分钟失败一次、直到用户手动打开——用户看到的就是"隔了几小时才恢复"。
            //
            // 现在的处置（三层，缺一不可）：
            //  ① **如实告知用户**：发一条通知"后台被系统限制、自动回复已暂停，请打开 App"。
            //     通知不需要 FGS，任何进程内代码都能发——这是唯一在受限态下一定能送达的通道；
            //  ② 退避重试：5 分钟 → 10 分钟 → 20 分钟（封顶），避免每 5 分钟刷一次失败；
            //  ③ 把状态落盘，供下次冷启动诊断（`qq_fgs_blocked_at`）。
            Log.w(TAG, "前台化被系统拒绝（bg restriction），转为通知用户 + 退避重试")
            // guardrail R-01 / R2-01：计数与通知锚点都必须**落盘**（失败路径每次 stopSelf，
            // 新实例字段归零）；且「读-判断-写」必须**合成一次 DataStore 事务**，
            // 否则两次几乎同时的失败会读到同一 prevCount → attempt 同档、同刻发两条通知。
            val ctx = applicationContext
            val sid = startId
            scope.launch {
                var attempt = foregroundBlockCountCache + 1
                // guardrail R3-01：仓库未装配/异常时**不得**退化成"每次都通知"
                // （否则又会回到 177 条轰炸）。默认不通知，只在确实拿到记账结果时按它决定。
                var notify = false
                var decided = false
                runCatching {
                    val repo = QqRuntimeStateRepositoryHolder.get()
                    if (repo != null) {
                        val r = repo.recordForegroundBlock(
                            nowMs = System.currentTimeMillis(),
                            throttleMs = FG_BLOCK_NOTIFY_THROTTLE_MS
                        )
                        attempt = r.first
                        notify = r.second
                        decided = true
                    }
                }.onFailure { Log.w(TAG, "前台化被拒记账失败（${it::class.simpleName}）") }
                if (!decided) {
                    // 记账不可用时保守处理：只在**首次**（缓存为 0）提醒一次，
                    // 后续失败只记日志，避免退化回刷屏。
                    notify = foregroundBlockCountCache == 0
                }
                foregroundBlockCountCache = attempt
                scheduleForegroundRetryWithBackoff(attempt)
                if (notify) {
                    runCatching {
                        io.prism.qq.QqProtocolAlertNotifier.notifyBackgroundRestricted(
                            ctx, attempt = attempt, hasExactAlarm = canScheduleExactAlarms()
                        )
                    }.onFailure { Log.w(TAG, "受限通知发送失败（${it::class.simpleName}）") }
                }
                Log.i(
                    TAG,
                    "前台化被拒第 $attempt 次（已落盘；通知=${if (notify) "已发" else "节流跳过"}）"
                )
                // guardrail R2-01：带 startId 停止，避免把随后**成功前台化**的新实例一起停掉。
                stopSelf(sid)
            }
            return START_NOT_STICKY
        }
        // 前台化成功即清零"被拒"计数（连续失败才需要退避）
        resetForegroundBlockCount()
        // 闹钟唤醒：必须优先于"循环已存在就不动"的分支——否则兜底形同虚设
        if (intent?.action == ACTION_WAKE) {
            Log.i(TAG, "闹钟唤醒：立即复评到期计划")
            wakeSignal.trySend(Unit)
        }
        if (loopJob == null) loopJob = scope.launch { heartbeatLoop() }
        if (replyJob == null) replyJob = scope.launch { replyLoop() }
        registerScreenReceiver()
        // 2026-11-14：START_STICKY 让系统在内存压力回收后**重建**本服务（intent 为 null，
        // 上面的 `intent?.action` 分支已能容忍）。用户显式停止仍返回 NOT_STICKY。
        return START_STICKY
    }

    /**
     * 注册亮屏广播（`ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`）。
     *
     * 只用于**打断轮询等待**，不做任何数据操作；注销在 [onDestroy]。
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                replyWake.trySend(Unit)
            }
        }
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        }.onFailure { Log.w(TAG, "亮屏广播注册失败（${it::class.simpleName}）") }
    }

    override fun onDestroy() {
        loopJob = null
        replyJob = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        scope.cancel()
        // 2026-11-14（ADR-072 F1）：**只有用户显式停止才撤闹钟**。
        // 旧实现在任何退出路径（含前台化失败、系统回收）都 cancelExactAlarm()，
        // 于是"闹钟叫醒 → 服务退出 → 自己把下次唤醒源撤掉"，形成自毁闭环，
        // 进程死后永久失去自愈能力（真机 11-03~11-14 静默 11 天即此形态）。
        //
        // guardrail R-01：用户显式停止时必须把**看门狗 PendingIntent 一并撤掉**。
        // 只撤 `REQUEST_WAKE` 的话，30 分钟后的看门狗仍会把服务复活并重新 startForeground，
        // 而 `stopRequested` 只是实例字段（新实例为 false）→ 用户"停止"形同虚设。
        if (stopRequested) {
            cancelAllAlarms()
        } else {
            scheduleLivenessWatchdog()
        }
        Log.i(TAG, "定时主动消息调度服务已停止（stopRequested=$stopRequested）")
        super.onDestroy()
    }

    /** 撤掉**全部**唤醒源（定时精确闹钟 + 存活看门狗 + 前台化重试）。 */
    private fun cancelAllAlarms() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(firePendingIntent()) }
        runCatching { am.cancel(watchdogPendingIntent()) }
        runCatching { am.cancel(foregroundRetryPendingIntent()) }
    }

    // ==================== 心跳循环（唯一发送路径） ====================

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            try {
                val cfg = QqConfigRepositoryHolder.get()?.get() ?: run {
                    Log.w(TAG, "配置仓库未装配，停止调度")
                    stopSelf()
                    return
                }
                if (!cfg.schedulingActive) {
                    // 定时链路未激活：若双向对话仍在激活则本循环静默待命（**不 stopSelf**——
                    // 否则会连带停掉 replyLoop）；两者皆未激活才整服务停止。
                    if (!cfg.autoReplyActive) {
                        Log.i(TAG, "定时与双向对话均未激活，停止调度服务")
                        stopSelf()
                    } else {
                        delay(IDLE_POLL_MS)
                    }
                    continue
                }
                val rt = QqRuntimeStateRepositoryHolder.get()?.get() ?: run {
                    Log.w(TAG, "运行时状态仓库未装配，停止调度")
                    stopSelf()
                    return
                }
                val now = System.currentTimeMillis()

                // 到期判定（含进程重启后的补发；plannedTriggerAt 持久化 → 恰好一次）
                if (rt.plannedTriggerAt in 1..now) {
                    // 2026-11-14（ADR-072 E 节 / PRD US-3108 AC-5 边界）：
                    // **过期计划一律丢弃，不补发**。
                    //
                    // 为什么必须在这里拦：`plannedTriggerAt` 是持久化的，而本服务现在有了
                    // 存活看门狗（被系统杀掉后会自己回来）。若不加这条，进程停摆几小时后被唤醒，
                    // 心跳会立刻发现"计划时刻早过了"→ 当场补发一条主动消息 ——
                    // 这正是 AC-5 要防的"重启后莫名收到消息"，而且会让自愈机制变成骚扰源。
                    //
                    // 阈值 10 分钟：正常运行（含 15 分钟分块睡眠）的到点抖动远小于它，
                    // 只有"真的停摆过"才会超过。
                    val staleMs = now - rt.plannedTriggerAt
                    if (staleMs > STALE_PLAN_MS) {
                        Log.i(TAG, "计划已过期 ${staleMs / 60_000} 分钟，丢弃并重排（AC-5）")
                        QqRuntimeStateRepositoryHolder.get()?.clearPlan()
                        continue
                    }
                    handleDue(cfg, rt, now)
                    continue
                }

                val planned = rt.plannedTriggerAt
                // N-9：复用旧计划前校验其仍在"当前配置"的窗口内；失效则清除并重算
                val planValid = planned > now && QqSchedulePlanner.isWithinWindow(
                    planned, cfg.windowStartMinute, cfg.windowEndMinute
                )
                if (planned != 0L && !planValid) {
                    QqRuntimeStateRepositoryHolder.get()?.clearPlan()
                }
                // DEF-102（2026-10-19 M2 真机实测）：minGap 语义 =「距**上次发送**的冷却剩余」，
                // 非「距现在至少 minIntervalMs」——后者导致首次触发也强制等 30min，且窗止临近时
                // 必然顺延明日（真机实证：计划被顺延 1441 分钟）。无发送历史时冷却为 0。
                val cooldownRemaining = if (rt.lastFiredAt > 0) {
                    (rt.lastFiredAt + cfg.minIntervalMs - now).coerceAtLeast(0L)
                } else {
                    0L
                }
                val next = if (planValid) planned else planNext(cfg, now, cooldownRemaining)
                if (next == null) {
                    // CR-8：时间窗非法 → 本循环待命（不再每分钟空转）；guardrail M-04：仅当双向
                    // 对话也未激活才停整个服务，否则不得连带杀死仍激活的 replyLoop。
                    Log.w(TAG, "时间窗非法（start>=end），定时循环待命")
                    if (stopWholeServiceIfIdle()) return
                    delay(IDLE_POLL_MS)
                    continue
                }
                if (rt.plannedTriggerAt != next) {
                    QqRuntimeStateRepositoryHolder.get()?.setPlannedTriggerAt(next)
                }
                scheduleExactAlarm(next)
                Log.i(TAG, "下次主动消息计划于 $next（约 ${(next - now) / 60_000} 分钟后）")
                // N-2：分块睡眠——每 ≤15 分钟回到循环顶部重读配置，使"运行期改配置"及时生效。
                // 2026-10-25：改为可被 [wakeSignal] 打断——精确闹钟到达即刻复评到期判定。
                awaitWakeOrTimeout((next - now).coerceAtLeast(0L).coerceAtMost(MAX_SLEEP_CHUNK_MS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // N-4：循环体异常不得穿透 scope.launch（无 CoroutineExceptionHandler 时会崩溃进程）
                Log.w(TAG, "心跳循环异常：${e::class.simpleName}")
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    /**
     * 是否需要停止**整个服务**（guardrail M-04）：仅当定时与双向对话**均**未激活时为真；
     * 为真则 [stopSelf]。否则定时链路的局部故障不得连带杀死仍在服务的 replyLoop。
     *
     * @return true=已停服（调用方应 return）；false=仍有其它链路在服务（调用方应待命/继续）
     */
    private suspend fun stopWholeServiceIfIdle(): Boolean {
        val cfg = QqConfigRepositoryHolder.get()?.get()
        if (cfg?.autoReplyActive == true) return false
        stopSelf()
        return true
    }

    private fun planNext(cfg: QqConfig, nowMs: Long, minGapMs: Long): Long? =
        QqSchedulePlanner.nextTriggerAt(
            nowMs = nowMs,
            windowStartMinute = cfg.windowStartMinute,
            windowEndMinute = cfg.windowEndMinute,
            minGapMs = minGapMs,
            random = Random.Default,
            strategy = if (cfg.usePoisson) QqSchedulePlanner.Strategy.POISSON else QqSchedulePlanner.Strategy.UNIFORM
        )

    /**
     * 环境守卫阻塞原因 → 失败类别（guardrail G-07）。
     *
     * 顺序：先判**权限/依赖**（无障碍/Shizuku/悬浮窗 → 通道类），再判屏幕，最后才是执行面。
     * 老的 `reason.contains("屏幕")` 会把"屏幕已熄灭（本次不允许唤醒）"与"屏幕已熄灭需要唤醒"
     * 混为一谈，且把权限缺失误记为显示面故障。
     */
    private fun classifyGuardBlock(reason: String): QqFailureClassifier.Kind = when {
        reason.contains("无障碍") || reason.contains("Shizuku") || reason.contains("悬浮窗") ->
            QqFailureClassifier.Kind.CHANNEL_UNAVAILABLE
        reason.contains("屏幕") -> QqFailureClassifier.Kind.SCREEN_OFF_BLOCKED
        else -> QqFailureClassifier.Kind.DISPLAY_UNAVAILABLE
    }

    /**
     * 「跳过并通知」（PRD US-3115 AC-6；guardrail G-07 / **G-r3-08**）。
     *
     * 两类情况都通知，但语义不同：
     * - **权限/依赖类**（无障碍/Shizuku/悬浮窗）：与"熄屏策略"无关——即使开着"点亮执行"
     *   也发不出去，用户必须知道并去修配置，否则会以为功能坏了；
     * - **熄屏跳过类**：仅在用户显式选择"熄屏跳过"时提示（默认"点亮执行"成功不打扰）。
     *
     * 限频：同一窗口实例只提示一次。
     */
    private fun notifySkipped(cfg: QqConfig, reason: String, kind: QqFailureClassifier.Kind) {
        val permissionLike = kind == QqFailureClassifier.Kind.CHANNEL_UNAVAILABLE
        if (!permissionLike && cfg.wakeOnScreenOff) return
        if (skipNoticeSentForWindow) return
        skipNoticeSentForWindow = true
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        SKIP_CHANNEL_ID, SKIP_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val n = NotificationCompat.Builder(this, SKIP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("本次定时消息已跳过")
                .setContentText(reason.take(60))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(SKIP_NOTIFICATION_ID, n)
        }.onFailure { Log.w(TAG, "跳过通知发送失败（${it::class.simpleName}）") }
    }

    /** 等待 [timeoutMs] 的睡眠，可被精确闹钟的 [wakeSignal] 提前打断（ADR-063 修订）。 */
    private suspend fun awaitWakeOrTimeout(timeoutMs: Long) {
        if (timeoutMs <= 0L) {
            // 到期判定由调用方紧接着做；清掉可能残留的信号，避免无谓空转一次
            wakeSignal.tryReceive()
            return
        }
        withTimeoutOrNull(timeoutMs) { wakeSignal.receive() }
    }

    /** 被动轮询等待：可被**亮屏广播**提前打断（2026-10-26）。 */
    private suspend fun awaitReplyOrTimeout(timeoutMs: Long) {
        if (timeoutMs <= 0L) {
            replyWake.tryReceive()
            return
        }
        withTimeoutOrNull(timeoutMs) { replyWake.receive() }
    }

    /**
     * 屏幕是否处于**可交互**状态（亮屏且未锁屏）。
     *
     * 判定失败按"熄屏"处理——保守方向：不点亮、不建屏（宁少打扰）。
     */
    private fun isScreenInteractive(): Boolean = runCatching {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val interactive = pm?.isInteractive == true
        val locked = km?.isKeyguardLocked == true
        interactive && !locked
    }.getOrDefault(false)

    /**
     * 到期处理（**唯一发送路径**；ADR-063 修订 2026-10-25）。
     *
     * 顺序：节流/熔断 → 通道可用性 → **执行环境守卫** → 发送 → 结果分类 → 计划处置。
     * 环境类阻塞（熄屏/显示面缺失/通道离线）**不计账号失败**，按退避重试若干次后放弃本窗口。
     */
    private suspend fun handleDue(cfg: QqConfig, state: QqRuntimeState, now: Long) {
        if (!cfg.schedulingActive) return // CR-2
        val runtimeRepo = QqRuntimeStateRepositoryHolder.get() ?: return
        // G-r2-04：窗口级状态（跳过通知去重）在进入业务分支**之前**统一换挡
        val anchor = QqSchedulePlanner.windowAnchor(now, cfg.windowStartMinute, cfg.windowEndMinute)
        ensureWindow(anchor)
        // G-r2-02：该窗口实例的重试预算已耗尽 → 短路（不发送、不唤醒、不重排计划）。
        // G-r3-03：**推到下一窗口起点**而不是 clearPlan()——否则心跳会在同窗口重新 planNext，
        // 反复注册闹钟/拉起前台服务/再短路（窗口剩 3h 可空转 3~6 轮）。
        if (state.retryWindowAnchor == anchor && state.retryCount >= MAX_TRANSIENT_RETRIES) {
            val nextWindow = QqSchedulePlanner.nextWindowAnchor(anchor)
            Log.i(
                TAG,
                "本窗口环境类重试已耗尽（${state.retryCount}/$MAX_TRANSIENT_RETRIES），" +
                    "顺延到下一窗口（锚点 $anchor → $nextWindow）"
            )
            runtimeRepo.recordOutcome("SKIP:RETRY_EXHAUSTED", "本窗口重试已耗尽", now)
            if (nextWindow > now) {
                runtimeRepo.setPlannedTriggerAt(nextWindow)
                scheduleExactAlarm(nextWindow)
            } else {
                runtimeRepo.clearPlan()
            }
            return
        }
        if (state.circuitOpen) {
            // N-1：熔断后停止**定时发送**（不空转），恢复由用户在设置页重新开启开关触发 resetCircuit。
            // guardrail M-04：若双向对话仍激活则**不得整体停服**（否则连带杀死 replyLoop）。
            Log.i(TAG, "已熔断（连续失败达阈值），定时发送停止，等待用户手动恢复")
            runtimeRepo.recordOutcome("SKIP:CIRCUIT_OPEN", "熔断中，等待用户恢复", now)
            runtimeRepo.clearPlan()
            stopWholeServiceIfIdle()
            return
        }
        when (val d = QqThrottle.decide(cfg, state, now)) {
            is QqThrottle.Decision.Deny -> {
                Log.i(TAG, "本次跳过：${d.reason}")
                runtimeRepo.recordOutcome("SKIP:THROTTLE", d.reason.take(120), now)
                runtimeRepo.clearPlan()
                return
            }
            QqThrottle.Decision.Allow -> Unit
        }

        // US-3110 AC-6：通道不可用（协议端离线且无障碍不可用）→ 跳过且**不记失败**。
        val protocolOnline = runCatching {
            QqProactiveGateway.protocolOnlineCheck?.invoke() ?: false
        }.getOrDefault(false)
        val online = runCatching {
            QqProactiveGateway.onlineCheck?.invoke() ?: true
        }.getOrDefault(true)
        if (!online) {
            Log.i(TAG, "当前无可用 QQ 通道（协议端离线/无障碍不可用），本次跳过（不计失败）")
            runtimeRepo.recordOutcome("SKIP:CHANNEL_UNAVAILABLE", "协议端离线且无障碍不可用", now)
            // G-r3-08：通道不可用属"用户需修配置"，即使开着"点亮执行"也要提示一次
            notifySkipped(cfg, "协议端离线且无障碍不可用", QqFailureClassifier.Kind.CHANNEL_UNAVAILABLE)
            retryOrClear(cfg, runtimeRepo, now)
            return
        }

        // 执行环境守卫（ADR-063 修订）：协议端发送不需要 UI 执行面；无障碍路径必须先确认
        // 前置条件（无障碍/Shizuku/悬浮窗）与屏幕可交互，否则注入会被系统丢弃（项目既有实证）。
        // guardrail G-02：先做**无副作用**的前置判定，确认"唤醒后确实发得出去"才点亮屏幕；
        // 执行面本身由发送链路惰性创建（displayId=INVALID 表示"待创建"，不是"不可用"）。
        if (!protocolOnline) {
            val displayId = runCatching {
                QqProactiveGateway.executionDisplayId?.invoke() ?: android.view.Display.INVALID_DISPLAY
            }.getOrDefault(android.view.Display.INVALID_DISPLAY)
            val guard = QqExecutionGuard.ensureReady(
                displayId = displayId,
                requireDisplay = true,
                allowWake = true // 定时任务：默认临时点亮执行（用户可切"熄屏跳过"）
            )
            if (guard.blocked) {
                Log.i(TAG, "执行环境未就绪，本次跳过（不计失败）：${guard.reason}")
                val kind = classifyGuardBlock(guard.reason)
                runtimeRepo.recordOutcome("SKIP:$kind", guard.reason.take(120), now)
                notifySkipped(cfg, guard.reason, kind)
                retryOrClear(cfg, runtimeRepo, now)
                return
            }
            if (guard.kind == QqExecutionGuard.Kind.WOKEN) {
                Log.i(TAG, "已临时点亮屏幕以执行本次定时任务")
                runtimeRepo.recordOutcome("WOKEN", "已点亮屏幕", now)
            }
            // 唤醒后给目标应用留出首帧时间（避免刚点亮就取到旧帧）
            if (guard.kind == QqExecutionGuard.Kind.WOKEN) delay(GUARD_SETTLE_MS)
        }

        val sender = QqProactiveGateway.sender
        if (sender == null) {
            Log.w(TAG, "主动消息发送器未装配（QQ 发送链路未就绪），本次跳过")
            runtimeRepo.recordOutcome("SKIP:CHANNEL_UNAVAILABLE", "发送器未装配", now)
            runtimeRepo.clearPlan()
            return
        }
        val startedAt = System.currentTimeMillis()
        val result = try {
            sender()
        } catch (e: CancellationException) {
            throw e // CR-7：中断类异常必须透传，不得被吞
        } catch (e: Exception) {
            Log.w(TAG, "主动消息发送异常：${e::class.simpleName}")
            runtimeRepo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)
            runtimeRepo.recordOutcome("FAIL:${QqFailureClassifier.Kind.UNKNOWN}", e::class.simpleName ?: "异常", now)
            runtimeRepo.clearPlan()
            return
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        if (!result.startsWith("错误") && !result.startsWith("⚠️")) {
            Log.i(TAG, "主动消息派发成功（耗时 ${elapsedMs}ms）")
            runtimeRepo.recordFired(now, QqThrottle.todayKey(now, ZoneId.systemDefault()))
            runtimeRepo.recordOutcome("SUCCESS", "耗时 ${elapsedMs}ms", now)
            runtimeRepo.resetRetry() // 成功后预算归零，下一窗口从满额开始
            runtimeRepo.clearPlan()
            return
        }
        // guardrail L-02：不回显联系人/正文，只记类别与耗时
        val kind = QqFailureClassifier.classify(result)
        Log.w(TAG, "主动消息发送失败：$kind（耗时 ${elapsedMs}ms）")
        runtimeRepo.recordOutcome("FAIL:$kind", "耗时 ${elapsedMs}ms", now)
        if (QqFailureClassifier.isUnconfirmedDelivery(kind)) {
            // 2026-10-25 真机实证：无障碍链路"点完发送但未能读回气泡"时消息**其实已送达**。
            // 此时若按环境失败重试 → 重复发送（用户会连续收到两条）。故：不熔断、不重试、
            // 消耗本次计划，并在状态里如实标注"未确认"。
            //
            // guardrail G-r3-01：**必须仍然记账**——否则 lastFiredAt 不前进（可在最小间隔内
            // 再发）、当日计数不增（可超每日上限），直接违反 US-3108 AC-4「不豁免频控」。
            Log.w(TAG, "发送结果未确认（可能已送达）：不重试、不熔断，按「已发送」记账")
            runtimeRepo.recordUnconfirmedFired(
                now, QqThrottle.todayKey(now, ZoneId.systemDefault())
            )
            runtimeRepo.resetRetry()
            runtimeRepo.clearPlan()
            return
        }
        if (QqFailureClassifier.countsTowardCircuit(kind)) {
            runtimeRepo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)
            runtimeRepo.clearPlan()
        } else if (!QqFailureClassifier.isRetryable(kind)) {
            // 明确被拒（SEND_REJECTED 已在上一分支熔断）或不可重试：结束本窗口
            runtimeRepo.clearPlan()
        } else {
            // 环境类失败：不熔断，按退避重试（避免夜间一次熄屏就丢掉整次计划）
            retryOrClear(cfg, runtimeRepo, now)
        }
    }

    /**
     * 环境类跳过后的重试处置（guardrail **G-r2-02**：预算必须是硬上限）。
     *
     * 三重约束缺一不可：
     * 1. **锚点用窗口实例起点**（不是日期）——跨零点窗口午夜前后是同一实例，用日期会在
     *    00:00 清零，导致整夜反复点亮屏幕；
     * 2. **计数持久化**（[QqRuntimeStateRepository.recordRetry]）——服务被系统回收重启后
     *    预算不重置，不会"重启再来一轮"；
     * 3. **耗尽后写 `retryCount` 超限**，[handleDue] 首行短路——不会因心跳在同窗口重新
     *    `planNext` 而再次进入发送路径。
     */
    private suspend fun retryOrClear(cfg: QqConfig, repo: QqRuntimeStateRepository, now: Long) {
        // G-r3-05：窗口非法时 windowAnchor 返回 0 → 不做预算判定（直接结束本计划）
        val anchor = QqSchedulePlanner.windowAnchor(now, cfg.windowStartMinute, cfg.windowEndMinute)
        if (anchor == 0L) {
            repo.clearPlan()
            Log.w(TAG, "活跃时段非法，环境类重试不生效，本计划结束")
            return
        }
        val used = repo.recordRetry(anchor)
        val retryAt = now + TRANSIENT_RETRY_DELAY_MS
        val withinWindow = QqSchedulePlanner.isWithinWindow(
            retryAt, cfg.windowStartMinute, cfg.windowEndMinute
        )
        if (used <= MAX_TRANSIENT_RETRIES && withinWindow) {
            repo.setPlannedTriggerAt(retryAt)
            Log.i(
                TAG,
                "环境类跳过：${TRANSIENT_RETRY_DELAY_MS / 60_000} 分钟后重试" +
                    "（本窗口第 $used/$MAX_TRANSIENT_RETRIES 次）"
            )
        } else {
            repo.clearPlan()
            Log.i(
                TAG,
                "环境类跳过重试耗尽（$used/$MAX_TRANSIENT_RETRIES）或已出活跃时段，" +
                    "本窗口不再重试（锚点 $anchor）"
            )
        }
    }

    // ==================== 双向对话循环（US-3109） ====================

    /**
     * 双向对话轮询循环：小号会话页出现**新的"收到"消息**时，由 LLM 生成回复并**后台**发出。
     *
     * 与心跳循环独立（心跳按到期决策睡眠，本循环固定间隔轮询）。仅当 [QqConfig.autoReplyActive]
     * （总开关 + 风险确认 + 双向开关 + 目标联系人）成立时执行；应答器未装配时静默等待。
     * 每次应答内部受 [QqThrottle] 节流/熔断约束（与主动消息共用当日计数与熔断态）。
     */
    private suspend fun replyLoop() {
        // D-06：探针决策日志限频（AC-4 要求「日志可证无变化时零建屏」）
        var lastProbeLogAtMs = 0L
        // 通道解析结论限频日志（避免每 60s 刷屏；决策变化仍会体现在下一行）
        var lastChannelLogAtMs = 0L
        while (scope.isActive) {
            try {
                val cfg = QqConfigRepositoryHolder.get()?.get()
                if (cfg == null || !cfg.autoReplyActive) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                // ADR-064 修订（2026-10-25）：**按能力而非按配置**判定事件/轮询。
                // 旧实现见到 `transport==ONEBOT` 就直接跳过轮询——协议端未配置时 WS 永不连接，
                // 于是自动回复永久静默（真机取证）。现在由 [QqChannelResolver] 给出"真正可用"的通道。
                val resolution = runCatching { QqProactiveGateway.channelMode?.invoke() }.getOrNull()
                    ?: QqChannelResolver.Resolution(
                        mode = if (cfg.transport == io.prism.qq.channel.QqTransport.ONEBOT) {
                            QqChannelResolver.Mode.ONEBOT
                        } else {
                            QqChannelResolver.Mode.ACCESSIBILITY
                        },
                        reason = "通道解析器未装配（按配置兜底）"
                    )
                val nowResolveMs = System.currentTimeMillis()
                if (nowResolveMs - lastChannelLogAtMs >= PROBE_LOG_INTERVAL_MS) {
                    lastChannelLogAtMs = nowResolveMs
                    Log.i(TAG, "自动回复通道=${resolution.mode}（${resolution.reason}）")
                }
                // 协议端已连接 → 由 WS 事件驱动（PrismApplication 订阅），本循环不轮询，
                // 否则会重新引入"无消息也建屏"的 DEF-118；两条通道都不可用同样跳过。
                // 判据与 autoReplyOnce 共用 QqAutoReplyDispatch（防止两处漂移）。
                if (QqAutoReplyDispatch.shouldSkipForEventDriven(resolution)) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val poller = QqProactiveGateway.autoReply
                if (poller == null) {
                    Log.w(TAG, "双向对话应答器未装配，等待")
                    delay(IDLE_POLL_MS)
                    continue
                }
                // US-3111 AC-4（guardrail H-04/H-05）：**只有明确命中才建屏**。
                // null 结果不建屏；探针内部有「连续未知 5 次」与「最长静默 15 分钟」两道兜底。
                // L-25：探针**未装配**是装配缺陷，不能与"未知"混同——按旧行为轮询并告警一次。
                //
                // 2026-10-26 真机修复：改为**屏幕态驱动**（[QqPollPolicy]）——
                // 亮屏时不再依赖通知摘要（通知被关/摘要在前台不更新都会让原策略永久失聪），
                // 直接放行；熄屏仍保持"不点亮 + 探针命中才建屏 + 15 分钟兜底"。
                val probe = QqProactiveGateway.unreadProbe
                val decision = if (probe == null) {
                    Log.w(TAG, "新消息探针未装配（装配缺陷）：本次按旧行为轮询")
                    io.prism.qq.QqPollPolicy.Decision(
                        poll = true,
                        nextDelayMs = io.prism.qq.QqPollPolicy.SCREEN_OFF_POLL_MS,
                        reason = "探针未装配：按旧行为轮询"
                    )
                } else {
                    io.prism.qq.QqPollPolicy.decide(
                        screenInteractive = isScreenInteractive(),
                        probe = probe.invoke()
                    )
                }
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProbeLogAtMs >= PROBE_LOG_INTERVAL_MS) {
                    lastProbeLogAtMs = nowMs
                    Log.i(
                        TAG,
                        "轮询决策：${decision.reason} → ${if (decision.poll) "放行（读会话）" else "跳过（零建屏）"}"
                    )
                }
                if (!decision.poll) {
                    awaitReplyOrTimeout(decision.nextDelayMs)
                    continue
                }
                val result = poller()
                // D-01：三态由应答器显式给出——"跳过"绝不能再被记成"成功"
                //（原实现用 `!startsWith("错误")` 二分，真机出现"日志成功但 NapCat 无出站消息"）。
                Log.i(TAG, result.logLine)
                awaitReplyOrTimeout(decision.nextDelayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "双向对话循环异常：${e::class.simpleName}")
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    // ==================== 前台服务 ====================

    /** @return true=已进入前台；false=失败（调用方须停止服务）。 */
    private fun startInForeground(): Boolean = try {
        // R-02（ac-verifier）：specialUse 类型是 API 34 才定义的 FGS 类型；在 Android 10~13 上
        // 传入未知类型会被系统拒绝（服务随即 stopSelf → 定时/回复不可用）。故按 API 34 分流：
        // 低版本走两参重载（类型由 manifest 决定，未知属性被忽略）。
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "前台服务启动失败（${e::class.simpleName}）")
        false
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, ProactiveScheduleService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, REQUEST_STOP, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("QQ 小号助手已开启")
            .setContentText("按你的设置执行定时消息与自动回复")
            .setOngoing(true)
            .addAction(0, "停止", stopPending)
            .build()
    }

    // ==================== 精确闹钟兜底（仅唤醒，不直接发送） ====================

    private fun scheduleExactAlarm(triggerAtMs: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 2026-11-14（ADR-072 F1-3）：权限缺失**降级**而不是静默放弃。
            // 旧实现只打一行日志就 return，于是"没授精确闹钟权限"= 没有任何闹钟兜底，
            // 进程一死就再也叫不醒。setAndAllowWhileIdle 在 Doze 下仍会唤醒（只是不精确），
            // 对"随机主动消息"语义完全够用。
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePendingIntent())
                Log.i(TAG, "未授予精确闹钟权限，已降级为非精确闹钟（时刻可能延后数分钟）")
            }.onFailure { Log.w(TAG, "降级闹钟登记失败（${it::class.simpleName}）") }
            return
        }
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePendingIntent())
            Log.i(TAG, "已登记精确闹钟兜底 @ $triggerAtMs")
        }.onFailure { Log.w(TAG, "精确闹钟登记失败（${it::class.simpleName}）") }
    }

    /**
     * **存活看门狗**（2026-11-14 新增；ADR-072 F1）。
     *
     * 与"定时主动消息"完全解耦：只要有 QQ 小号助手开着，就每 [WATCHDOG_INTERVAL_MS]
     * 唤醒一次本服务。它是"进程被系统回收后能自己回来"的唯一不依赖定时计划的入口。
     *
     * - 用 `setAndAllowWhileIdle`（非精确）：Doze 下仍会唤醒，且**不需要**「闹钟与提醒」权限；
     * - 每次进入服务都重挂（幂等，`FLAG_UPDATE_CURRENT` 只保留一条）。
     */
    private fun scheduleLivenessWatchdog() {
        // guardrail R4-03：`stopRequested` 与本函数之间没有 happens-before——
        // 本函数在 appScope 里异步读配置，而 `ACTION_STOP` 的落盘同样异步。
        // 没有这行守卫时，用户点「停止」后一个迟到的协程仍会补挂一条 30 分钟闹钟，
        // 表现为"已停止却仍闪一次前台通知"。首行早退是最小且确定的修法。
        if (stopRequested) return
        val holder = QqConfigRepositoryHolder.get() ?: return
        // onStartCommand 是同步的，读配置需要协程；用 scope 异步登记，失败只记日志。
        val appScope = (application as? io.prism.PrismApplication)?.appScope
        if (appScope == null) {
            // guardrail R4-08/R-14：静默失败会让"看门狗从未挂上"无从排查
            Log.w(TAG, "无法获取 appScope，存活看门狗未登记")
            return
        }
        appScope.launch {
            val cfg = runCatching { holder.get() }.getOrNull() ?: return@launch
            // guardrail R5-01：入口守卫不够——协程 `holder.get()` 挂起期间用户可能已点停止，
            // 恢复后仍会补挂一条 30 分钟闹钟。**读配置之后必须再复检一次**。
            if (stopRequested) return@launch
            // 2026-11-14：只在"确实有链路要服务"时挂看门狗。
            // 用 schedulingActive || autoReplyActive（与配置流启停服务的判据一致），
            // 而不是只看 enabled——否则"开了总开关但没配好目标联系人"的用户
            // 会被每 30 分钟唤醒一次却什么都不做，纯耗电。
            if (!cfg.autoReplyActive && !cfg.schedulingActive) return@launch
            val am = getSystemService(AlarmManager::class.java) ?: return@launch
            val at = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            runCatching {
                // 2026-11-15（真机决定性取证；ADR-073）：**必须优先用精确闹钟**。
                //
                // 177 次实证：`Service.startForeground() not allowed due to bg restriction`。
                // 原因是 Android 12+ 的后台 FGS 限制——**只有 `setExactAndAllowWhileIdle`
                // 会给应用一段临时白名单（允许启动 FGS）**；`setAndAllowWhileIdle`（不精确）
                // **不给**。上一轮为了"免去闹钟权限"改用不精确闹钟，等于亲手把 FGS 豁免弄丢了：
                // 闹钟能把进程叫醒，但服务一前台化就被拒 → 我们 `stopSelf()` → 自愈彻底失效。
                //
                // 因此：有精确闹钟权限 → 用精确（拿豁免）；没有 → 退化为不精确，
                // 但**同时**把"无法前台化"如实告知用户（见 startInForeground 失败分支）。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, at, watchdogPendingIntent()
                    )
                    Log.i(TAG, "存活看门狗已按**精确**闹钟登记（保留 FGS 启动豁免）")
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogPendingIntent())
                    Log.w(
                        TAG,
                        "存活看门狗降级为不精确闹钟（无「闹钟与提醒」权限）：" +
                            "系统可能拒绝后台启动前台服务，自愈能力受限"
                    )
                }
            }.onFailure { Log.w(TAG, "存活看门狗登记失败（${it::class.simpleName}）") }
        }
    }

    /**
     * 前台化被系统拒绝后的**退避重试**（2026-11-15；ADR-073）。
     *
     * 旧实现固定 5 分钟，而 Android 12+ 的后台 FGS 限制**不会**在 5 分钟后自动解除
     * （它取决于应用是否回到前台/是否进入豁免态），于是 5 分钟一次、失败 177 次，
     * 既耗电又没有任何成功概率。改为指数退避，避免无效轰炸。
     */
    private fun scheduleForegroundRetryWithBackoff(attempt: Int) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val delayMs = foregroundRetryDelayMs(attempt)
        val at = System.currentTimeMillis() + delayMs
        runCatching {
            // guardrail R-02：**必须用独立的 PendingIntent**。复用看门狗那个会被
            // 30 分钟后的看门狗覆盖（等于重试从未生效），反之亦然。
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, foregroundRetryPendingIntent())
            Log.i(TAG, "已登记前台化重试（第 $attempt 次，${delayMs / 60_000} 分钟后）")
        }.onFailure { Log.w(TAG, "前台化重试闹钟登记失败（${it::class.simpleName}）") }
    }

    /** 前台化重试的退避间隔（5 → 10 → 20 → 30 分钟封顶）。 */
    private fun foregroundRetryDelayMs(attempt: Int): Long = when {
        attempt <= 1 -> 5 * 60_000L
        attempt == 2 -> 10 * 60_000L
        attempt == 3 -> 20 * 60_000L
        else -> 30 * 60_000L
    }
    /** 是否持有「闹钟与提醒」权限（Android 12+）。 */
    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /** 前台化成功 → 清零"被拒"计数（落盘 + 内存缓存）。 */
    private fun resetForegroundBlockCount() {
        foregroundBlockCountCache = 0
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    QqRuntimeStateRepositoryHolder.get()?.resetForegroundBlock()
                }
            }
        }.start()
    }

    private fun cancelExactAlarm() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(firePendingIntent()) }
    }

    private fun firePendingIntent(): PendingIntent {
        val intent = Intent(this, ProactiveScheduleService::class.java).setAction(ACTION_WAKE)
        return PendingIntent.getService(
            this, REQUEST_WAKE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 存活看门狗（ACTION_WAKE）。 */
    private fun watchdogPendingIntent(): PendingIntent {
        val intent = Intent(this, ProactiveScheduleService::class.java).setAction(ACTION_WAKE)
        return PendingIntent.getService(
            this, REQUEST_WATCHDOG, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 前台化失败后的 5 分钟重试（guardrail R-02：独立 requestCode，不被看门狗覆盖）。 */
    private fun foregroundRetryPendingIntent(): PendingIntent {
        val intent = Intent(this, ProactiveScheduleService::class.java).setAction(ACTION_WAKE)
        return PendingIntent.getService(
            this, REQUEST_FG_RETRY, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        /**
         * **仅 debug**：以指定延时登记一次存活看门狗（生产仍走 [WATCHDOG_INTERVAL_MS]）。
         *
         * 为什么需要：真机验收必须证明"进程被杀后闹钟仍会把它拉起来"，而 30 分钟的
         * 等待不适合反复验证。该入口只缩短**本次**登记的延时，不改生产常量，
         * 投递路径仍是同一个 [REQUEST_WATCHDOG] PendingIntent。
         */
        fun scheduleWatchdogForDebug(context: android.content.Context, delayMs: Long) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val at = System.currentTimeMillis() + delayMs.coerceIn(5_000L, WATCHDOG_INTERVAL_MS)
            val pi = PendingIntent.getService(
                context, REQUEST_WATCHDOG,
                Intent(context, ProactiveScheduleService::class.java).setAction(ACTION_WAKE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure { Log.w(TAG, "debug 看门狗登记失败（${it::class.simpleName}）") }
            Log.i(TAG, "debug：存活看门狗已登记（${delayMs / 1000}s 后）")
        }

        /**
         * 服务因**配置关闭**主动停止时，必须撤掉看门狗（guardrail A-02）。
         *
         * 不撤的后果：用户关掉 QQ 小号助手 → 服务 stopSelf → 30 分钟看门狗仍会把它叫醒，
         * 于是刚关掉又闪一次前台服务通知（随后因配置未激活再退出）。
         * 语义上"用户关闭"与"用户点停止"等价，都应撤销全部唤醒源。
         */
        fun cancelAllAlarms(context: android.content.Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val cls = ProactiveScheduleService::class.java
            listOf(REQUEST_WAKE, REQUEST_WATCHDOG, REQUEST_FG_RETRY).forEach { code ->
                val pi = PendingIntent.getService(
                    context, code,
                    Intent(context, cls).setAction(ACTION_WAKE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                runCatching { am.cancel(pi) }
            }
            Log.i(TAG, "已撤销全部协议端唤醒源（配置关闭）")
        }

        private const val TAG = "QqProactiveSchedule"

        private const val CHANNEL_ID = "prism_proactive_message"
        private const val CHANNEL_NAME = "主动消息"

        /** CR-5：独立通知 ID（避开既有 FGS 通知 ID，防止同号互相覆盖）。 */
        private const val NOTIFICATION_ID = 4101

        /** 「熄屏跳过」通知渠道（独立于定时任务 FGS 通知；PRD US-3115 AC-6）。 */
        private const val SKIP_CHANNEL_ID = "prism_proactive_skipped"
        private const val SKIP_CHANNEL_NAME = "定时消息跳过提示"
        private const val SKIP_NOTIFICATION_ID = 4102

        /** 精确闹钟动作：仅唤醒服务（由心跳循环判定是否到期并发送）。 */
        const val ACTION_WAKE = "io.prism.qq.ACTION_WAKE"

        /** 通知栏停止动作。 */
        const val ACTION_STOP = "io.prism.qq.ACTION_STOP"

        private const val REQUEST_WAKE = 3002
        private const val REQUEST_STOP = 3003

        /** 存活看门狗 / 前台化重试（ADR-072 F1）。 */
        private const val REQUEST_WATCHDOG = 3004

        /** 前台化失败后的 5 分钟重试（guardrail R-02：独立于看门狗）。 */
        private const val REQUEST_FG_RETRY = 3005

        /**
         * 存活看门狗间隔。
         *
         * 30 分钟：`setAndAllowWhileIdle` 在 Doze 下每天只有少量唤醒配额，
         * 30 分钟足够把"被系统回收"控制在可接受范围内，又不会明显耗电。
         */
        private const val WATCHDOG_INTERVAL_MS = 30 * 60 * 1000L

        /** 前台服务启动失败后的重试间隔（不精确；实际用 [foregroundRetryDelayMs] 退避）。 */
        private const val FOREGROUND_RETRY_MS = 5 * 60 * 1000L

        /** 「后台受限」通知的节流间隔（ADR-073）：避免 177 条轰炸，但保证用户能看到。 */
        private const val FG_BLOCK_NOTIFY_THROTTLE_MS = 30 * 60 * 1000L

        /**
         * 过期计划的容忍上限（ADR-072：自愈不得补发过期主动消息）。
         *
         * 超过即 `clearPlan()` 重排，而不是当场发送——这是 PRD US-3108 AC-5
         * 「重启后不恢复定时任务」在"自愈能让进程回来"之后必须显式守住的那条线。
         *
         * guardrail A-04（2026-11-14 验收）：取 **30 分钟**而不是 10 分钟。
         * 因为"未授予精确闹钟权限"时会降级为 `setAndAllowWhileIdle`，Doze 下延迟
         * 十几分钟是常态；10 分钟阈值会把**正常到点**误判成"过期"，反而漏发。
         * 30 分钟仍远小于"停摆数小时/数天"，AC-5 的意图不受影响。
         */
        private const val STALE_PLAN_MS = 30 * 60 * 1000L

        /** N-2：单次睡眠分块上限——每 ≤15 分钟重读配置，运行期改动及时生效。 */
        private const val MAX_SLEEP_CHUNK_MS = 15 * 60 * 1000L

        /** N-4：心跳循环异常后的退避（避免紧密自旋）。 */
        private const val ERROR_BACKOFF_MS = 60_000L

        /** 未激活/应答器未装配时的静默轮询间隔（保持循环可被配置变更唤醒）。 */
        private const val IDLE_POLL_MS = 60_000L

        /** 双向对话轮询间隔（US-3109）：每 90s 在副屏检查一次是否有新的收到消息。 */
        private const val REPLY_POLL_INTERVAL_MS = 90_000L

        /** D-06：探针决策日志限频（60s；用于证明「无变化时零建屏」且不刷屏）。 */
        private const val PROBE_LOG_INTERVAL_MS = 60_000L

        /** 环境类跳过后的重试间隔（ADR-063 修订）：足够让用户开屏/通道重连，又不至于空转。 */
        private const val TRANSIENT_RETRY_DELAY_MS = 10 * 60 * 1000L

        /** 环境类跳过的窗口内重试上限（6 次 ≈ 1 小时；之后放弃本窗口，防整夜无效唤醒）。 */
        private const val MAX_TRANSIENT_RETRIES = 6

        /** 环境守卫（唤醒/重建）后等待目标应用首帧的时间。 */
        private const val GUARD_SETTLE_MS = 800L

        /** 启动调度服务（容错：系统限制 FGS 后台启动时静默降级，不阻断主流程）。 */
        fun start(context: Context) {
            try {
                val intent = Intent(context, ProactiveScheduleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "请求启动定时主动消息调度服务")
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    Log.w(TAG, "调度服务后台启动被系统拒绝（Android 12+ 限制）")
                } else {
                    Log.w(TAG, "调度服务启动失败（${e::class.simpleName}）")
                }
            }
        }

        /** 停止调度服务。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ProactiveScheduleService::class.java)) }
                .onFailure { Log.w(TAG, "调度服务停止失败（${it::class.simpleName}）") }
        }
    }
}
