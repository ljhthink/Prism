package io.prism.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.prism.qq.QqChannelResolver
import io.prism.qq.QqExecutionGuard
import io.prism.qq.QqProactiveGateway
import io.prism.qq.QqRuntimeStateRepositoryHolder
import kotlinx.coroutines.runBlocking

/**
 * 2026-10-25 真机探针（**仅 debug 构建**；见 `src/debug/AndroidManifest.xml`）。
 *
 * 目的：在没有 LLM 会话的情况下，独立验证本轮整改的三条关键判据——
 * 1. `channel`：通道解析结论（协议端未连接时是否真的降级到无障碍）；
 * 2. `display`：环境守卫判定（屏幕交互态 / 执显示面状态），不发送任何消息；
 * 3. `fire`：把计划设为 **now+5s** 并唤醒调度器，验证"精确闹钟/唤醒信号 → 到期判定"链路；
 * 4. `status`：运行时状态（计划/上次结果/熔断），用于和设置页展示对账；
 * 5. `onebot`：写入协议端连接参数（等价于用户在设置页填写；extras：base/ws/token/self_id/target_id/contact），
 *    用于自动化部署验收（**不修改**总开关与风险确认——那是用户同意，必须由用户自己点）；
 * 6. `send`：经 `QqLocalToolExecutor.sendProactive` 真发一条消息（extras：text），验证发送链路；
 * 7. `reply`：立即执行一次自动回复（等价于探针命中后的一轮），验证"收 → LLM → 回"链路。
 *
 * 用法：
 * `adb shell am broadcast -a io.prism.DEBUG_QQ --es action channel -p io.prism`
 * 结果输出到 logcat（tag `QqDebug`）。
 *
 * **安全**：本组件 `android:permission="android.permission.DUMP"`（仅 shell/系统可发），
 * 且只存在于 `src/debug`，release 构建不包含（见 src/debug/AndroidManifest.xml）。
 */
class QqDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_STATUS
        val app = context.applicationContext as? io.prism.PrismApplication
        if (app == null) {
            Log.e(TAG, "ERROR[$action] application 不是 PrismApplication")
            return
        }
        val pending = goAsync()
        Thread({
            try {
                val result = when (action) {
                    ACTION_CHANNEL -> runBlocking {
                        val cfg = app.qqConfigRepository.get()
                        val oneBot = runCatching { app.oneBotChannel.status().connected }.getOrDefault(false)
                        val a11y = runCatching { app.accessibilityQqChannel.status().connected }
                            .getOrDefault(false)
                        val r = QqChannelResolver.resolve(cfg.transport, oneBot, a11y)
                        "transport=${cfg.transport} oneBotConnected=$oneBot a11yAvailable=$a11y " +
                            "→ mode=${r.mode} reason=${r.reason}"
                    }
                    ACTION_DISPLAY -> {
                        val vdm = io.prism.phonecontrol.bg.VirtualDisplayManager
                        val guard = QqExecutionGuard.ensureReady(
                            displayId = vdm.currentDisplayId,
                            requireDisplay = true,
                            allowWake = false
                        )
                        "created=${vdm.isCreated} displayId=${vdm.currentDisplayId} hidden=${vdm.isHiddenMode} " +
                            "overlay=${vdm.isOverlayMode} guard=${guard.kind} blocked=${guard.blocked} " +
                            "reason=${guard.reason} frame=${vdm.frameSize()}"
                    }
                    ACTION_FIRE -> runBlocking {
                        val rt = QqRuntimeStateRepositoryHolder.get()
                            ?: return@runBlocking "fire=运行时仓库未装配"
                        val at = System.currentTimeMillis() + 5_000L
                        rt.setPlannedTriggerAt(at)
                        io.prism.qq.ProactiveScheduleService.start(app)
                        // 必须同时发一次 ACTION_WAKE：心跳循环此刻可能在 15 分钟分块睡眠中，
                        // 只改持久化计划不会被立刻复评（这正是本轮修复的"闹钟兜底"语义）。
                        runCatching {
                            app.startForegroundService(
                                android.content.Intent(
                                    app, io.prism.qq.ProactiveScheduleService::class.java
                                ).setAction(io.prism.qq.ProactiveScheduleService.ACTION_WAKE)
                            )
                        }
                        "fire=已把计划设为 now+5s（$at）并发送 wake 信号；观察 QqProactiveSchedule 日志"
                    }
                    ACTION_STATUS -> runBlocking {
                        val cfg = app.qqConfigRepository.get()
                        val rt = app.qqRuntimeStateRepository.get()
                        "schedulingActive=${cfg.schedulingActive} autoReplyActive=${cfg.autoReplyActive} " +
                            "window=${cfg.windowStartMinute}-${cfg.windowEndMinute} " +
                            "wakeOnScreenOff=${cfg.wakeOnScreenOff} " +
                            "planned=${rt.plannedTriggerAt} circuitOpen=${rt.circuitOpen} " +
                            "lastOutcome=${rt.lastOutcome} reason=${rt.lastOutcomeReason}"
                    }
                    ACTION_ONEBOT -> runBlocking {
                        val base = intent.getStringExtra(EXTRA_BASE).orEmpty()
                        val ws = intent.getStringExtra(EXTRA_WS).orEmpty()
                        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                        val selfId = intent.getStringExtra(EXTRA_SELF_ID)?.toLongOrNull() ?: 0L
                        val targetId = intent.getStringExtra(EXTRA_TARGET_ID)?.toLongOrNull() ?: 0L
                        val contact = intent.getStringExtra(EXTRA_CONTACT).orEmpty()
                        app.qqConfigRepository.setOneBotConnection(
                            baseUrl = base, wsUrl = ws, token = token,
                            selfId = selfId, targetUserId = targetId
                        )
                        if (contact.isNotBlank()) app.qqConfigRepository.setTargetContact(contact)
                        app.oneBotChannel.invalidateTarget()
                        // 触发一次重连（等同设置页改配置）
                        app.oneBotClient.stop()
                        app.oneBotClient.start()
                        "onebot=已写入 base=$base ws=$ws tokenSet=${token.isNotBlank()} " +
                            "selfId=$selfId targetId=$targetId contactSet=${contact.isNotBlank()}"
                    }
                    ACTION_SEND -> {
                        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                        if (text.isBlank()) {
                            "send=缺少 --es text"
                        } else {
                            runBlocking {
                                val cfg = app.qqConfigRepository.get()
                                app.qqLocalToolExecutor.sendProactive(cfg.targetContact, text)
                            }
                        }
                    }
                    ACTION_REPLY -> runBlocking {
                        val fn = io.prism.qq.QqProactiveGateway.autoReply
                            ?: return@runBlocking "reply=应答器未装配"
                        // D-01：三态显式输出（验收时要能区分"真的发了"与"跳过"）
                        val r = fn()
                        "reply kind=${r.kind} message=${r.message.take(80)}"
                    }
                    // 定时参数（等价于设置页保存；用于真机验收时快速构造窗口/上限场景）
                    ACTION_CONFIG -> runBlocking {
                        val repo = app.qqConfigRepository
                        intent.getStringExtra(EXTRA_MAX_PER_DAY)?.toIntOrNull()
                            ?.let { repo.setMaxPerDay(it) }
                        intent.getStringExtra(EXTRA_DAILY_CAP)?.toIntOrNull()
                            ?.let { repo.setDailyCap(it) }
                        intent.getStringExtra(EXTRA_MIN_INTERVAL_MIN)?.toLongOrNull()
                            ?.let { repo.setMinIntervalMs(it * 60_000L) }
                        intent.getStringExtra(EXTRA_MIN_INTERVAL_SEC)?.toLongOrNull()
                            ?.let { repo.setMinIntervalMs(it * 1_000L) }
                        intent.getStringExtra(EXTRA_WAKE_ON_SCREEN_OFF)?.let {
                            repo.setWakeOnScreenOff(it.equals("true", ignoreCase = true))
                        }
                        intent.getStringExtra(EXTRA_TRANSPORT)?.let { t ->
                            when (t.lowercase()) {
                                "onebot" -> repo.setTransport(io.prism.qq.channel.QqTransport.ONEBOT)
                                "accessibility" -> repo.setTransport(io.prism.qq.channel.QqTransport.ACCESSIBILITY)
                            }
                        }
                        val ws = intent.getStringExtra(EXTRA_WINDOW_START)
                        val we = intent.getStringExtra(EXTRA_WINDOW_END)
                        if (!ws.isNullOrBlank() && !we.isNullOrBlank()) {
                            val s = io.prism.qq.QqTimeOfDay.parse(ws)
                            val e = io.prism.qq.QqTimeOfDay.parse(we)
                            if (s == null || e == null || !io.prism.qq.QqTimeOfDay.isValidWindow(s, e)) {
                                return@runBlocking "config=窗口非法（$ws-$we）"
                            }
                            repo.setWindow(s, e)
                        }
                        val cfg = repo.get()
                        "config=window=${io.prism.qq.QqTimeOfDay.describe(cfg.windowStartMinute, cfg.windowEndMinute)} " +
                            "maxPerDay=${cfg.maxPerDay} dailyCap=${cfg.dailyCap} " +
                            "minIntervalMs=${cfg.minIntervalMs} wakeOnScreenOff=${cfg.wakeOnScreenOff}"
                    }
                    else -> "unknown action=$action"
                }
                Log.i(TAG, "RESULT[$action] $result")
            } catch (t: Throwable) {
                Log.e(TAG, "ERROR[$action] ${t.javaClass.name}: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }, "qq-debug").start()
    }

    private companion object {
        const val TAG = "QqDebug"
        const val EXTRA_ACTION = "action"
        const val EXTRA_BASE = "base"
        const val EXTRA_WS = "ws"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SELF_ID = "self_id"
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MAX_PER_DAY = "max_per_day"
        const val EXTRA_DAILY_CAP = "daily_cap"
        const val EXTRA_MIN_INTERVAL_MIN = "min_interval_min"
        const val EXTRA_MIN_INTERVAL_SEC = "min_interval_sec"
        const val EXTRA_WAKE_ON_SCREEN_OFF = "wake_on_screen_off"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_WINDOW_START = "window_start"
        const val EXTRA_WINDOW_END = "window_end"
        const val ACTION_CONFIG = "config"
        const val ACTION_CHANNEL = "channel"
        const val ACTION_DISPLAY = "display"
        const val ACTION_FIRE = "fire"
        const val ACTION_STATUS = "status"
        const val ACTION_ONEBOT = "onebot"
        const val ACTION_SEND = "send"
        const val ACTION_REPLY = "reply"
    }
}
