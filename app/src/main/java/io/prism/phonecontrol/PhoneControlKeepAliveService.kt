package io.prism.phonecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * v1 批次11（F2 手机操控保活前台服务）→ v1 批次14 修订（TKN-V1B14-KEEPALIVE-BUG-001）：
 * 手机操控**任务期间**的动态保活。
 *
 * **背景（真机证据 + 网络调研，prd-v1-b11 §1）**：MIUI/HyperOS 对后台应用进程激进回收，
 * 打开微信/应用商店等重内存 App 时会把 Prism 主进程杀掉（日志 PID 30643→11700），导致
 * 无障碍服务与 UI 树缓存一并丢失，`get_ui_state` 永久"无法感知"、kimi SSE 流被断报
 * "网络连接中断"（问题①②的共同根因）。前台服务能显著降低进程被杀概率（配合用户侧
 * MIUI 保活配置，见 F1 引导页）。
 *
 * **批次14 修订（ADR-041，真机取证 docs/reports/2026-08-23-keepalive-bug-debug.md）**：
 * 批次11 的「无障碍连上即常驻」导致用户不使用软件时通知不间断 + 后台高优先级驻留致卡顿
 * （dumpsys：服务常驻 1d8h10m）。修订为任务期动态启停——由 [PhoneControlSessionManager]
 * 在首个 `phone_control__*` 工具调用启动、空闲 120s 停止；无障碍 onDestroy 兜底停止。
 *
 * **合规**：`startForegroundService` + `startForeground(type=specialUse)`（Android 14+ 需
 * `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + Manifest `<property>` 声明用途），不做双进程守护/
 * Native 保活等违规方案。`START_NOT_STICKY`：任务期服务被系统回收后不自动重启，下次工具调用
 * 由 SessionManager 重新拉起（避免「杀不死」循环）。
 */
class PhoneControlKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        Log.i(TAG, "手机操控保活前台服务已启动")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "手机操控保活前台服务已停止")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Prism 正在操控手机")
            .setContentText("执行 LLM 操控手机任务中，请勿强制停止")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PhoneControlKeepAlive"

        private const val CHANNEL_ID = "prism_phone_keepalive"
        private const val CHANNEL_NAME = "手机操控保活"
        private const val NOTIFICATION_ID = 2001

        /** 启动保活前台服务（容错：权限未授予/被系统限制时静默降级，不阻断主流程）。 */
        fun start(context: Context) {
            try {
                val intent = Intent(context, PhoneControlKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "请求启动保活前台服务")
            } catch (e: Exception) {
                // v1 批次14：Android 12+ 后台启动 FGS 受限（官方豁免场景外抛
                // ForegroundServiceStartNotAllowedException）——任务降级为无保活继续运行，
                // 与批次11 之前行为一致；可诊断类别单独记录便于真机定位。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    Log.w(TAG, "保活前台服务后台启动被系统拒绝（Android 12+ 限制），降级为无保活继续任务")
                } else {
                    Log.w(TAG, "保活前台服务启动失败（${e::class.simpleName}）")
                }
            }
        }

        /** 停止保活前台服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PhoneControlKeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "保活前台服务停止失败（${e::class.simpleName}）")
            }
        }
    }
}