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
}