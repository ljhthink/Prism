package io.prism

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            PrismTheme {
                PrismApp()
            }
        }
    }
}