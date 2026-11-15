package io.prism

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.prism.ui.PrismApp
import io.prism.ui.theme.PrismTheme

/**
 * Prism 入口 Activity。
 *
 * US-005 起承载完整聊天 UI 骨架：[PrismApp]（NavHost 三路由 + 底部导航）。
 * 依 ADR-002，使用 M3 动态主题 [PrismTheme]。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * App 前后台判定（v1 批次16 US-1605）：手机操控任务完成回报通知仅在前台时抑制——
         * App 在前台用户看得到聊天流，无需通知打扰；后台时发通知引导回 App。
         * 轻量实现（onResume/onPause 维护静态标志），不引入 lifecycle-process 依赖。
         */
        @Volatile
        var isForeground: Boolean = false
            private set

        /** 「闹钟与提醒」引导记账（2026-11-15；ADR-073）。 */
        private const val KEY_EXACT_ALARM_ASKED_AT = "exact_alarm_asked_at"

        /** 引导间隔（7 天）：用户明确拒绝时不要天天弹设置页。 */
        private const val EXACT_ALARM_ASK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // v1 真机二次修复（Issue 5）：Android 13+ 发通知（手机操控高危确认、搜索/思考等系统通知）
        // 属运行时权限。启动即请求，未授予时后台确认通知静默不出（提问卡片仍可用）。用户拒绝不阻塞。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        // 2026-11-15（ADR-073 / guardrail R-02）：**「闹钟与提醒」权限**。
        //
        // 真机取证 177 次 `startForeground() not allowed due to bg restriction`：
        // Android 12+ 的后台前台服务启动豁免只由 **exact alarm** 给出，而
        // `SCHEDULE_EXACT_ALARM` 在 targetSdk 33+ 上**默认不授予**，全仓此前也没有申请入口
        // → 看门狗的精确闹钟分支永远走不到，自愈在后台必被拒。
        // 这里在应用可见时引导用户跳转系统设置授予一次（不能弹运行时对话框，只能跳设置页）。
        ensureExactAlarmPermission()
        setContent {
            PrismTheme {
                PrismApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    /**
     * 引导用户授予「闹钟与提醒」权限（2026-11-15；ADR-073 / guardrail R-02）。
     *
     * 为什么必须显式引导：`SCHEDULE_EXACT_ALARM` 在 targetSdk 33+ 上默认不授予，
     * 而**只有精确闹钟**能让应用在后台获得"启动前台服务"的临时豁免。缺它 →
     * 看门狗只能降级为不精确闹钟 → 后台自愈必被系统拒绝（真机 177 次实证）。
     *
     * 这是系统级开关，不能弹运行时对话框，只能跳到设置页；用户拒绝不阻塞（如实降级）。
     * 只在**未授予且本进程还没引导过**时跳一次，避免每次回到前台都跳设置。
     */
    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return
        if (runCatching { am.canScheduleExactAlarms() }.getOrDefault(true)) return
        // 本进程只引导一次（跨进程用 SharedPreferences 记账）
        val prefs = getSharedPreferences("prism_perm_prompt", MODE_PRIVATE)
        val lastAsked = prefs.getLong(KEY_EXACT_ALARM_ASKED_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAsked < EXACT_ALARM_ASK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_EXACT_ALARM_ASKED_AT, now).apply()
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            android.util.Log.w("MainActivity", "跳转「闹钟与提醒」设置失败（${it::class.simpleName}）")
        }
    }
}