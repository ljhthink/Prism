package io.prism.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.prism.PrismApplication
import io.prism.fs.UiConfirmationGate
import io.prism.ui.capabilities.CapabilitiesScreen
import io.prism.ui.chat.ConversationScreen
import io.prism.ui.components.PrismNavBar
import io.prism.ui.components.PrismNavItem
import io.prism.ui.knowledge.KnowledgeBaseScreen
import io.prism.ui.settings.SettingsScreen
import io.prism.ui.theme.PrismBg
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

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

        // 全局工具确认宿主（US-009，ADR-006 5.4 / BR-concurrency-003）：
        // 置于 NavHost 外层，保证任意 Tab 下均有收集者，避免确认请求丢失。
        ToolConfirmationHost()
    }
}

/**
 * 全局工具调用确认对话框宿主（US-009，ADR-006 5.4）。
 *
 * 收集 [UiConfirmationGate.requests] 流到**先进先出队列**逐条确认（避免并发请求被单值状态覆盖，
 * 先到请求永不 resolve，BR-concurrency-003）。参数展示对 `content` 等长字符串做截断（C7），
 * 避免大段敏感内容在预览中完整展示。
 */
@Composable
private fun ToolConfirmationHost() {
    val app = LocalContext.current.applicationContext as? PrismApplication ?: return
    val gate = remember { app.confirmationGate }
    val queue = remember { mutableStateListOf<UiConfirmationGate.PendingConfirm>() }

    // 收集确认请求入队（FIFO）
    LaunchedEffect(gate) {
        gate.requests.collect { queue.add(it) }
    }

    // 逐条处理队首请求
    queue.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = { queue.remove(request); gate.respond(request.id, false) },
            containerColor = PrismPanel2,
            title = { Text("AI 请求执行工具", color = PrismText) },
            text = {
                Column {
                    Text("工具：${request.toolName}", color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "参数：${renderArguments(request.arguments)}",
                        color = PrismTextFaint,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { queue.remove(request); gate.respond(request.id, true) }) {
                    Text("允许", color = PrismIndigo)
                }
            },
            dismissButton = {
                TextButton(onClick = { queue.remove(request); gate.respond(request.id, false) }) {
                    Text("拒绝", color = PrismTextDim)
                }
            }
        )
    }
}

/** 渲染确认参数，长字符串值截断（C7）。 */
private fun renderArguments(arguments: Map<String, Any?>): String {
    if (arguments.isEmpty()) return "—"
    return arguments.entries.joinToString(", ") { (k, v) ->
        val text = v?.toString() ?: "null"
        val truncated = if (text.length <= MAX_ARG_DISPLAY) text else text.take(MAX_ARG_DISPLAY) + "…"
        "$k=$truncated"
    }
}

private const val MAX_ARG_DISPLAY = 80