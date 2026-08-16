package io.prism.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.prism.crossapp.CrossAppLauncher
import io.prism.crossapp.CrossAppLocalToolExecutor
import io.prism.fs.UiConfirmationGate
import io.prism.ui.capabilities.CapabilitiesScreen
import io.prism.ui.chat.ConversationScreen
import io.prism.ui.components.PrismNavBar
import io.prism.ui.components.PrismNavItem
import io.prism.ui.conversationlist.ConversationListScreen
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
    /** UX-001 问题 4（ADR-021）：会话历史列表页（从聊天顶栏进入）。 */
    const val CONVERSATION_LIST = "conversation_list"
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

    // UX-001 问题 4（ADR-021）：从历史列表选中的待加载会话 id。
    // 历史页选中会话后写入，经此状态传递给聊天页；聊天页 LaunchedEffect 触发
    // `loadSession` 后通过 [ConversationScreen.onSessionLoaded] 清空（一次性消费）。
    var pendingSessionId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = PrismBg,
        // UXR3 问题 1（键盘遮挡，三次修复未果）：关闭 Scaffold 自动 content insets。
        // 根因：edge-to-edge + 默认 contentWindowInsets(systemBars) 时，键盘弹出后 Scaffold
        // 的 innerPadding 会错误叠加 navigationBar/ime padding 到 content，而聊天页底部又
        // 有 imePadding() → 双重 padding 导致输入框被顶得过高（模拟器通过、MIUI 真机失败）。
        // 改为 Scaffold 不处理 insets，由各页面内部自行 imePadding（ConversationScreen 底部
        // 输入区 + PrismNavBar 底部 imePadding），单一来源、无叠加。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                // UXR3 问题 1（guardrail M-4 修复）：Scaffold 已关闭 content insets（避免键盘
                // IME 叠加），但 edge-to-edge 下需**自行补顶部状态栏 inset**，否则各 Tab 顶栏
                // 会绘制到状态栏下方被遮挡。单一来源：在此处统一 statusBarsPadding，
                // 各页面无需各自处理（避免重复/遗漏）。
                .statusBarsPadding()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = PrismDestinations.CHAT,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(PrismDestinations.CHAT) {
                    ConversationScreen(
                        onOpenHistory = { navController.navigate(PrismDestinations.CONVERSATION_LIST) },
                        sessionIdToLoad = pendingSessionId,
                        onSessionLoaded = { pendingSessionId = null }
                    )
                }
                composable(PrismDestinations.KNOWLEDGE) { KnowledgeBaseScreen() }
                composable(PrismDestinations.CAPABILITIES) { CapabilitiesScreen() }
                composable(PrismDestinations.SETTINGS) { SettingsScreen() }
                // UX-001 问题 4（ADR-021）：会话历史列表页。
                // 选中会话 → 写入 pendingSessionId 并返回聊天页（聊天页自动 loadSession 恢复）。
                composable(PrismDestinations.CONVERSATION_LIST) {
                    ConversationListScreen(
                        onBack = { navController.popBackStack() },
                        onSessionSelected = { sessionId ->
                            pendingSessionId = sessionId
                            navController.popBackStack()
                        }
                    )
                }
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
    val crossAppLauncher = remember { app.crossAppLauncher }
    val queue = remember { mutableStateListOf<UiConfirmationGate.PendingConfirm>() }

    // 收集确认请求入队（FIFO）
    LaunchedEffect(gate) {
        gate.requests.collect { queue.add(it) }
    }

    // 逐条处理队首请求
    queue.firstOrNull()?.let { request ->
        // DEF-006（Bug-7）：解析富信息标题/内容（跨 App 工具显示 App 名），消除与聊天页重复弹窗后的体验降级
        val (title, content) = remember(request, crossAppLauncher) {
            resolveConfirmationContent(request, crossAppLauncher)
        }
        AlertDialog(
            onDismissRequest = { queue.remove(request); gate.respond(request.id, false) },
            containerColor = PrismPanel2,
            title = { Text(title, color = PrismText) },
            text = {
                Column {
                    Text(
                        text = content,
                        color = PrismTextFaint,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
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

/**
 * 解析确认对话框的标题与内容文案（DEF-006 从聊天页迁移到全局宿主，保证全局唯一弹窗仍展示富信息）。
 *
 * **工具类型识别**：
 * - `cross_app__open_app`：查询 App 显示名 + action，富信息展示
 * - `cross_app__share_content`：展示分享内容预览（截断到 100 字符防溢出）
 * - `cross_app__pick_media`：展示选取类型
 * - 其他工具（M4 Skill）：展示工具名 + JSON 参数
 *
 * @param request 待确认请求
 * @param crossAppLauncher 跨 App 调用入口（可空：null 时跨 App 工具仅展示工具名）
 * @return Pair(title, content)
 */
private fun resolveConfirmationContent(
    request: UiConfirmationGate.PendingConfirm,
    crossAppLauncher: CrossAppLauncher?
): Pair<String, String> {
    val toolName = request.toolName
    val args = request.arguments

    return when {
        toolName == CrossAppLocalToolExecutor.TOOL_OPEN_APP -> {
            val appId = args["appId"]?.toString()
            val appConfig = appId?.let { crossAppLauncher?.getAppConfig(it) }
            val appDisplayName = appConfig?.displayName ?: appId ?: "未知应用"
            val action = args["action"]?.toString() ?: "打开"
            val title = "AI 请求打开 $appDisplayName"
            val content = buildString {
                append("操作：$action\n")
                append("应用：$appDisplayName")
                if (appConfig?.fallbackUrl != null) {
                    append("\n备用：${appConfig.fallbackUrl}")
                }
                val extraParams = args.filterKeys { it !in setOf("appId", "action") }
                if (extraParams.isNotEmpty()) {
                    append("\n参数：$extraParams")
                }
            }
            title to content
        }
        toolName == CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT -> {
            val content = args["content"]?.toString() ?: ""
            val preview = if (content.length > 100) content.take(100) + "…" else content
            "AI 请求分享内容" to "分享文本：\n$preview"
        }
        toolName == CrossAppLocalToolExecutor.TOOL_PICK_MEDIA -> {
            val mediaType = args["mediaType"]?.toString() ?: "未知类型"
            "AI 请求选取媒体" to "类型：$mediaType\nAI 将通过系统选择器让你选取照片或文档。"
        }
        else -> {
            // M4 Skill 工具或其他工具：通用展示
            "AI 请求执行工具" to "工具：$toolName\n参数：$args"
        }
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