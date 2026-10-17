package io.prism.voice

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

/**
 * 批次20（VC-001，ADR-044）：语音通话前台服务（type=microphone）。
 *
 * **用途**：通话期间锁屏/切后台保持麦克风可用（Android 14+ FGS type 强制），并给用户
 * 一个可见的「语音通话中」提示与挂断入口。**不执行任何音频逻辑**——采集/识别/播报仍在
 * 会话层（[VoiceSessionController]），服务仅保活 + 通知。
 */
class VoiceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // 挂断动作：回调会话层执行完整挂断逻辑（asr.cancel + tts.stop + watchJob 取消 +
        // callActive=false + 停本服务——guardrail P1-1：通知挂断必须回写状态机，防孤儿化），
        // 再 stopSelf 兜底。
        if (intent?.action == ACTION_HANG_UP) {
            Log.i(TAG, "通知挂断请求 → 回调会话层 hangUp")
            hangUpRequested?.invoke()
            stopSelf()
        }
        // START_NOTICKY：挂断后不自动重启（与 ADR-041 保活服务同策略）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音通话", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "语音通话进行中的前台服务通知" }
            )
        }
        val hangUp = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Prism 语音通话中")
            .setContentText("点击挂断结束本次通话")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "挂断", hangUp).build())
            .build()
    }

    companion object {
        private const val TAG = "VoiceFGS"
        const val CHANNEL_ID = "prism_voice_call"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_HANG_UP = "io.prism.voice.HANG_UP"

        /**
         * guardrail P1-1：通知挂断 → 会话层完整挂断逻辑的回调钩子（由
         * [VoiceSessionController] 在通话开始时注册、hangUp/release 时清空）。
         * 回调在主线程执行（Service.onStartCommand）。
         */
        @Volatile
        internal var hangUpRequested: (() -> Unit)? = null

        /** 启动通话保活（幂等；主线程调用）。返回是否启动成功（guardrail P3-2：失败时调用方回滚状态）。 */
        fun start(context: Context): Boolean =
            try {
                context.startForegroundService(
                    Intent(context, VoiceForegroundService::class.java)
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "启动语音 FGS 失败：${e.message}")
                false
            }

        /** 结束通话保活（幂等）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceForegroundService::class.java)) }
        }
    }
}
