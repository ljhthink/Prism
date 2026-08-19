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
}