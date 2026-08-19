package io.prism.phonecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 手机操控高危确认通知桥（v1 真机二次修复 Issue 5）。
 *
 * **问题**：LLM 操控手机时 Prism 常处于后台，发送/删除/拨号的「逐次询问」(ask_user) 仅渲染在
 * 聊天页 Compose 内，用户看不到 → 静默超时（UiConfirmationGate 默认拒绝，安全但体验差）。
 *
 * **方案**：用**高优先级通知 + 允许/拒绝操作按钮**让确认在后台也可见（免 SYSTEM_ALERT_WINDOW
 * 悬浮窗权限，走系统标准通知通道，符合安全约束）。用户点「允许」→ [onResult] 回调，把答案
 * 喂回工具回路（等价于在提问卡片点“允许”后发送）。
 *
 * **线程安全**：答案经 [MutableSharedFlow] 声明式推送，VM 侧 collect。
 *
 * @param context 应用上下文（用于发通知）
 */
class PhoneControlAskUserNotifier(
    context: Context
) {
    private val appContext = context.applicationContext
    private val _answers = MutableSharedFlow<Answer>(extraBufferCapacity = 8)
    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 8)

    /** 当前正展示的确认通知 askId（用于发新通知时先撤旧通知，防陈旧通知按钮误批新确认）。 */
    @Volatile
    private var activeAskId: Long = -1L

    /** 用户点「允许」后回传的答案流（askId 与问题一一对应）。 */
    val answers: SharedFlow<Answer> = _answers.asSharedFlow()

    /** 发起一个确认请求（发通知 + 推送内部请求流；请求流供 VM 兜底监控超时）。 */
    suspend fun request(requestedQuestion: String): Long {
        val askId = nextId()
        _requests.emit(Request(askId, requestedQuestion))
        // 防御（guardrail TKN-V1B5 LOW-1）：ask_user 为单槽，旧确认通知未作答前又触发新确认时，
        // 先撤掉旧通知，杜绝"残留旧按钮误批新高危确认"。
        if (activeAskId > 0) {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID_BASE + activeAskId.toInt())
        }
        activeAskId = askId
        postNotification(askId, requestedQuestion)
        return askId
    }

    /** 用户点「允许」后作答 → 推送答案到 answers 流并撤掉该通知。 */
    fun resolve(askId: Long, action: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_BASE + askId.toInt())
        if (activeAskId == askId) activeAskId = -1L
        _answers.tryEmit(Answer(askId, action))
    }

    private fun postNotification(askId: Long, question: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val allowPi = PendingIntent.getBroadcast(
            appContext, askId.toInt(),
            Intent(appContext, ConfirmActionReceiver::class.java)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_ASK_ID, askId)
                .putExtra(EXTRA_ACTION, ACTION_ALLOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPi = PendingIntent.getBroadcast(
            appContext, askId.toInt() * 31,
            Intent(appContext, ConfirmActionReceiver::class.java)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_ASK_ID, askId)
                .putExtra(EXTRA_ACTION, ACTION_DENY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("手机操控需你确认")
            .setContentText(question.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(denyPi) // 点通知本体默认拒绝（安全）
            .addAction(0, "拒绝", denyPi)
            .addAction(0, "允许", allowPi)
            .build()
        nm.notify(NOTIFICATION_ID_BASE + askId.toInt(), notification)
    }

    /** 数据：一次确认请求。 */
    data class Request(val askId: Long, val question: String)

    /** 数据：一次用户作答。 */
    data class Answer(val askId: Long, val action: String)

    /** 通知操作接收器：解析 askId + 结果并回调 [PhoneControlAskUserNotifier] 单例。 */
    class ConfirmActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val askId = intent.getLongExtra(EXTRA_ASK_ID, -1L)
            val action = intent.getStringExtra(EXTRA_ACTION) ?: return
            if (askId < 0) return
            holder?.resolve(askId, action)
        }

        companion object {
            /** 应用刚启动注册的单例（由 PrismApplication 注入）。 */
            @Volatile
            var holder: PhoneControlAskUserNotifier? = null
        }
    }

    companion object {
        private const val CHANNEL_ID = "prism_phone_confirm"
        private const val CHANNEL_NAME = "手机操控确认"
        private const val ACTION_CONFIRM = "io.prism.action.PHONE_CONFIRM"
        const val ACTION_ALLOW = "allow"
        const val ACTION_DENY = "deny"
        private const val EXTRA_ASK_ID = "ask_id"
        private const val EXTRA_ACTION = "action"
        /** 确认通知 id 基址（测试经此映射断言通知存在）。 */
        const val NOTIFICATION_ID_BASE = 7000

        private val nextAskId = java.util.concurrent.atomic.AtomicLong(1L)

        @JvmStatic
        fun nextId(): Long = nextAskId.getAndIncrement()

        /**
         * 创建确认通知渠道（在 Application.onCreate 调用一次；API 26+ 需要渠道）。
         */
        @RequiresApi(26)
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "LLM 操控手机时的发送/删除/拨号等高危操作确认"
                enableVibration(true)
                lightColor = Color.parseColor("#FF7A6B")
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(channel)
        }
    }
}