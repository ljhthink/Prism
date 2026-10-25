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
            scope.launch {
                runCatching { QqConfigRepositoryHolder.get()?.setScheduleEnabled(false) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!startInForeground()) {
            // CR-10：前台服务启动失败（系统限制）→ 立即停止，避免"无通知仍在跑"
            stopSelf()
            return START_NOT_STICKY
        }
        // 闹钟唤醒：必须优先于"循环已存在就不动"的分支——否则兜底形同虚设
        if (intent?.action == ACTION_WAKE) {
            Log.i(TAG, "精确闹钟唤醒：立即复评到期计划")
            wakeSignal.trySend(Unit)
        }
        if (loopJob == null) loopJob = scope.launch { heartbeatLoop() }
        if (replyJob == null) replyJob = scope.launch { replyLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopJob = null
        replyJob = null
        scope.cancel()
        cancelExactAlarm()
        Log.i(TAG, "定时主动消息调度服务已停止")
        super.onDestroy()
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
                val probe = QqProactiveGateway.unreadProbe
                if (probe == null) {
                    Log.w(TAG, "新消息探针未装配（装配缺陷）：本次按旧行为轮询")
                } else {
                    val hit = probe.invoke()
                    val shouldPoll = io.prism.qq.QqPollDecision.shouldPoll(hit)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastProbeLogAtMs >= PROBE_LOG_INTERVAL_MS) {
                        lastProbeLogAtMs = nowMs
                        Log.i(
                            TAG,
                            "探针结果=$hit → ${if (shouldPoll) "放行（建屏读取）" else "跳过（零建屏）"}"
                        )
                    }
                    if (!shouldPoll) {
                        delay(REPLY_POLL_INTERVAL_MS)
                        continue
                    }
                }
                val result = poller()
                // D-01：三态由应答器显式给出——"跳过"绝不能再被记成"成功"
                //（原实现用 `!startsWith("错误")` 二分，真机出现"日志成功但 NapCat 无出站消息"）。
                Log.i(TAG, result.logLine)
                delay(REPLY_POLL_INTERVAL_MS)
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
            Log.w(TAG, "未授予精确闹钟权限（Android 12+「闹钟与提醒」），兜底降级为仅心跳调度")
            return
        }
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePendingIntent())
            Log.i(TAG, "已登记精确闹钟兜底 @ $triggerAtMs")
        }.onFailure { Log.w(TAG, "精确闹钟登记失败（${it::class.simpleName}）") }
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

    companion object {
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
