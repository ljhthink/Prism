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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                    fireOnce(cfg)
                    QqRuntimeStateRepositoryHolder.get()?.clearPlan()
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
                // N-2：分块睡眠——每 ≤15 分钟回到循环顶部重读配置，使"运行期改配置"及时生效
                delay((next - now).coerceAtLeast(0L).coerceAtMost(MAX_SLEEP_CHUNK_MS))
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

    /** 派发一次主动消息（先复查激活态 + 节流/熔断；失败按阈值熔断）。 */
    private suspend fun fireOnce(cfg: QqConfig) {
        if (!cfg.schedulingActive) return // CR-2
        val runtimeRepo = QqRuntimeStateRepositoryHolder.get() ?: return
        val now = System.currentTimeMillis()
        val state = runtimeRepo.get()
        if (state.circuitOpen) {
            // N-1：熔断后停止**定时发送**（不空转），恢复由用户在设置页重新开启开关触发 resetCircuit。
            // guardrail M-04：若双向对话仍激活则**不得整体停服**（否则连带杀死 replyLoop）。
            Log.i(TAG, "已熔断（连续失败达阈值），定时发送停止，等待用户手动恢复")
            stopWholeServiceIfIdle()
            return
        }
        when (val d = QqThrottle.decide(cfg, state, now)) {
            is QqThrottle.Decision.Deny -> {
                Log.i(TAG, "本次跳过：${d.reason}")
                return
            }
            QqThrottle.Decision.Allow -> Unit
        }
        // US-3110 AC-6：通道不可用（协议端离线且无障碍不可用）→ 跳过且**不记失败**。
        val online = runCatching {
            QqProactiveGateway.onlineCheck?.invoke() ?: true
        }.getOrDefault(true)
        if (!online) {
            Log.i(TAG, "当前无可用 QQ 通道（协议端离线/无障碍不可用），本次跳过（不计失败）")
            return
        }
        val sender = QqProactiveGateway.sender
        if (sender == null) {
            Log.w(TAG, "主动消息发送器未装配（QQ 发送链路未就绪），本次跳过")
            return
        }
        try {
            val result = sender()
            if (result.startsWith("错误") || result.startsWith("⚠️")) {
                // guardrail L-02：不回显联系人/正文
                Log.w(TAG, "主动消息发送返回失败（已脱敏）")
                runtimeRepo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)
            } else {
                Log.i(TAG, "主动消息派发成功")
                runtimeRepo.recordFired(now, QqThrottle.todayKey(now, ZoneId.systemDefault()))
            }
        } catch (e: CancellationException) {
            throw e // CR-7：中断类异常必须透传，不得被吞
        } catch (e: Exception) {
            Log.w(TAG, "主动消息发送异常：${e::class.simpleName}")
            runtimeRepo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)
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
        while (scope.isActive) {
            try {
                val cfg = QqConfigRepositoryHolder.get()?.get()
                if (cfg == null || !cfg.autoReplyActive) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                // ADR-064 / US-3111 AC-1：协议端由 WS 事件驱动（PrismApplication 订阅），
                // 本循环**不轮询**——否则会重新引入"无消息也建屏"的 DEF-118。
                if (cfg.transport == io.prism.qq.channel.QqTransport.ONEBOT) {
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
                val ok = !result.startsWith("错误") && !result.startsWith("⚠️")
                Log.i(TAG, if (ok) "双向对话应答：成功" else "双向对话应答：失败（已脱敏）")
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
