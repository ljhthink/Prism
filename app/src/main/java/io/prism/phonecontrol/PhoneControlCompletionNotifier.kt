package io.prism.phonecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.prism.MainActivity

/**
 * 手机操控任务完成回报通知（v1 批次16 US-1605）。
 *
 * **问题（真机反馈 2026-09-03）**：LLM 操控手机时 Prism 常处于后台（用户去看被操控的 App），
 * 任务完成后 LLM 的汇报只渲染在聊天流里——用户不知道任务是否完成/结果如何，需要手动切回
 * Prism 查看 terrific 体验断点。
 *
 * **方案**：本条消息的工具调用含 `phone_control__*` 且回复完成时，若 App 在后台 → 发一条
 * 「任务完成」通知（IMPORTANCE_DEFAULT，不打扰），点击回到 [MainActivity] 查看汇报。
 * 前台时不发（聊天流可见）。与高危确认通知（[PhoneControlAskUserNotifier]，HIGH + 按钮）
 * 分渠道：完成回报是低打扰状态通知。
 *
 * @param context 应用上下文
 */
class PhoneControlCompletionNotifier(
    context: Context
) {
    private val appContext = context.applicationContext

    /**
     * 发送任务完成回报通知（M-1 修复：区分成功/异常结束标题）。
     *
     * @param summary 回报摘要（LLM 最终回复文本，截断展示）
     * @param failed 任务是否异常结束（true → 标题「异常结束」，避免失败误报已完成）
     */
    fun notifyCompleted(summary: String, failed: Boolean = false) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            appContext, REQUEST_CODE_COMPLETION,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (failed) "手机操控任务异常结束" else "手机操控任务已完成")
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(500)))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "prism_phone_status"
        private const val CHANNEL_NAME = "手机操控状态"
        private const val REQUEST_CODE_COMPLETION = 8001

        /** 完成回报通知固定 id（新回报覆盖旧回报——只关心最新任务状态）。 */
        const val NOTIFICATION_ID = 8100

        /** 创建状态通知渠道（在 Application.onCreate 调用一次；API 26+ 需要渠道）。 */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "LLM 操控手机任务的完成/失败回报"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(channel)
        }
    }
}
