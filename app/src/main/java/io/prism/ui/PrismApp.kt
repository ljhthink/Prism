package io.prism.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.prism.ui.capabilities.CapabilitiesScreen
import io.prism.ui.chat.ConversationScreen
import io.prism.ui.components.PrismNavBar
import io.prism.ui.components.PrismNavItem
import io.prism.ui.knowledge.KnowledgeBaseScreen
import io.prism.ui.settings.SettingsScreen
import io.prism.ui.theme.PrismBg

/** 主路由定义（深空玻璃 4 Tab + 能力中枢，设计规范 v0.2 第 7 节）。 */
object PrismDestinations {
    const val CHAT = "chat"
    const val KNOWLEDGE = "knowledge"
    const val CAPABILITIES = "capabilities"
    const val SETTINGS = "settings"
}

/** 底部 4 主入口。 */
private val bottomNavItems = listOf(
    PrismNavItem(PrismDestinations.CHAT, "聊天", Icons.AutoMirrored.Filled.Chat),
    PrismNavItem(PrismDestinations.KNOWLEDGE, "知识库", Icons.Filled.MenuBook),
    PrismNavItem(PrismDestinations.CAPABILITIES, "能力", Icons.Filled.Bolt),
    PrismNavItem(PrismDestinations.SETTINGS, "设置", Icons.Filled.Settings)
)

/**
 * Prism 应用根 Composable —— 4 Tab 导航 + 深空背景。
 *
 * 导航策略（设计规范 v0.2）：聊天为主入口，底部四个 Tab 切换，
 * launchSingleTop + restoreState 保留各 Tab 状态。能力中枢内嵌 MCP/Skills/记忆三段。
 */
@Composable
fun PrismApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = PrismBg,
        bottomBar = {
            PrismNavBar(
                items = bottomNavItems,
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = PrismDestinations.CHAT,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(PrismDestinations.CHAT) { ConversationScreen() }
                composable(PrismDestinations.KNOWLEDGE) { KnowledgeBaseScreen() }
                composable(PrismDestinations.CAPABILITIES) { CapabilitiesScreen() }
                composable(PrismDestinations.SETTINGS) { SettingsScreen() }
            }
        }
    }
}