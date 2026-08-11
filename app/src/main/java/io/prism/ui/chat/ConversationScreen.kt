package io.prism.ui.chat

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.prism.crossapp.AppLauncherBridge
import io.prism.crossapp.CrossAppLauncher
import io.prism.crossapp.CrossAppLocalToolExecutor
import io.prism.data.ProviderConfig
import io.prism.fs.UiConfirmationGate
import io.prism.rag.RagTarget
import io.prism.ui.components.PrismAvatar
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismGlassCard
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismTopBar
import io.prism.ui.components.PrismTopBarAction
import io.prism.ui.model.Citation
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismIndigoSoft
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/**
 * 聊天屏幕 —— 深空玻璃肌理（设计规范 v0.2 第 8.1 节）。
 *
 * 布局（自上而下）：
 * 1. 顶栏 [PrismTopBar]：主标题「Prism」+ 当前 Provider 副标题 + 新会话 / 能力操作
 * 2. Provider 选择器胶囊 + RAG 模式切换胶囊（US-019）
 * 3. 消息列表：AI 玻璃气泡（含引用胶囊列表）/ 用户靛蓝渐变气泡，入场上浮 + 瀑布错峰
 * 4. 打字指示：AI 回复中三点呼吸 + 状态文案（RAG 开启时显示「正在检索知识库…」）
 * 5. 玻璃胶囊输入框 + 靛蓝渐变圆形发送钮（带光晕）
 *
 * **US-019 文案修正**（ADR-012 5.8，修复 R-7）：
 * - TypingIndicator 文案从「正在调用 MCP 检索知识库…」改为按 RAG 状态切换
 * - MessageInputBar 占位符从「输入问题，@知识库 检索…」改为「输入问题…」（移除未实现语法）
 *
 * **M6 Phase C 集成**（US-039，ADR-016 5.5）：
 * - **用户确认 UI**：收集 [UiConfirmationGate.requests] 流，展示 [AlertDialog] 供用户允许/拒绝
 *   工具调用（覆盖 M4 Skill 工具 + M6 跨 App 工具，复用同一确认对话框）。
 *   确认/取消按钮调用 `gate.respond(id, allow)` 回灌结果，SkillExecutor 挂起协程恢复。
 * - **跨 App 跳转 launcher**：注册 [ActivityResultContracts.StartActivityForResult] launcher，
 *   收集 [AppLauncherBridge.requests] 流触发 `launcher.launch(intent)`，
 *   回调中按 [ActivityResult.getResultCode] 提取结果文本回灌 `bridge.respond(id, result)`。
 * - **生命周期清理**：[DisposableEffect] 在 Composable 离开组合时调用 [CrossAppLauncher.cancelAll]
 *   （委托至 [AppLauncherBridge.cancelAll]），避免 Activity 销毁时 pending deferred 泄漏（ADR-016 R2）。
 * - **降级策略**：[ConversationViewModel.confirmationGate] / [appLauncherBridge] / [crossAppLauncher]
 *   任一为 null 时跳过对应 UI 注册（向后兼容既有测试，工具调用走超时降级文案）。
 */
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = viewModel(factory = ConversationViewModel.Factory)
) {
    val messages by viewModel.messages.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val ragTarget by viewModel.ragTarget.collectAsState()
    var providerSelectorVisible by remember { mutableStateOf(false) }
    var ragSelectorVisible by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // ============ M6 Phase C：跨 App 调用 UI 集成（US-039，ADR-016 5.5） ============

    val confirmationGate = viewModel.confirmationGate
    val appLauncherBridge = viewModel.appLauncherBridge
    val crossAppLauncher = viewModel.crossAppLauncher

    // 待确认请求（一次只展示一个对话框，用户响应后清空并处理下一个）
    var pendingConfirm by remember { mutableStateOf<UiConfirmationGate.PendingConfirm?>(null) }
    // 待处理的 Intent 请求 id（launcher 回调时需要传回 bridge.respond）
    var pendingIntentRequestId by remember { mutableStateOf<Long?>(null) }

    // ActivityResult launcher：必须先于收集其回调的 state 声明（参考 KnowledgeBaseScreen 先例）
    val startActivityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        // 从 ActivityResult 提取结果文本回灌 bridge
        val resultText = when (result.resultCode) {
            Activity.RESULT_OK -> {
                // Photo/Document Picker 返回 Uri；Deep Link/Share Sheet 通常无 data
                result.data?.dataString ?: "已完成"
            }
            Activity.RESULT_CANCELED -> "用户取消"
            else -> "未知结果（resultCode=${result.resultCode}）"
        }
        pendingIntentRequestId?.let { id ->
            appLauncherBridge?.respond(id, resultText)
            pendingIntentRequestId = null
        }
    }

    // 收集 UiConfirmationGate.requests 流 —— 展示工具确认对话框
    // 覆盖 M4 Skill 工具 + M6 跨 App 工具（统一确认门禁，ADR-016 R8 缓解）
    LaunchedEffect(confirmationGate) {
        confirmationGate?.requests?.collect { request ->
            pendingConfirm = request
        }
    }

    // 收集 AppLauncherBridge.requests 流 —— 触发 launcher.launch(intent)
    LaunchedEffect(appLauncherBridge) {
        appLauncherBridge?.requests?.collect { request ->
            pendingIntentRequestId = request.id
            startActivityLauncher.launch(request.intent)
        }
    }

    // 生命周期清理：Composable 离开组合时清理 pending deferred，避免泄漏（ADR-016 R2）
    DisposableEffect(crossAppLauncher) {
        onDispose {
            crossAppLauncher?.cancelAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrismTopBar(
                title = "Prism",
                subtitle = "深空 AI · ${activeProvider?.name ?: "未配置"}",
                actions = {
                    PrismTopBarAction(icon = { Icon(Icons.Filled.Add, null, tint = PrismTextDim) }, contentDescription = "新会话")
                    PrismTopBarAction(icon = { Icon(Icons.Filled.Bolt, null, tint = PrismTextDim) }, contentDescription = "能力")
                }
            )

            // Provider 选择器 + RAG 模式切换器并排（US-007 + US-019）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProviderChip(
                    name = activeProvider?.name,
                    onClick = { providerSelectorVisible = true },
                    modifier = Modifier.weight(1f)
                )
                RagModeChip(
                    target = ragTarget,
                    onClick = { ragSelectorVisible = true }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (isTyping) {
                    item { TypingIndicator(isRagOn = ragTarget !is RagTarget.Off) }
                }
            }

            MessageInputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    viewModel.sendMessage(input)
                    input = ""
                }
            )
        }

        // Provider 切换弹层（US-007）
        PrismSheetHost(visible = providerSelectorVisible, onDismiss = { providerSelectorVisible = false }) {
            ProviderSelectorSheet(
                providers = providers,
                activeId = activeProvider?.id,
                onSelect = { viewModel.setActiveProvider(it); providerSelectorVisible = false },
                onClose = { providerSelectorVisible = false }
            )
        }

        // RAG 模式切换弹层（US-019）
        PrismSheetHost(visible = ragSelectorVisible, onDismiss = { ragSelectorVisible = false }) {
            RagModeSelectorSheet(
                current = ragTarget,
                onSelect = { viewModel.setRagTarget(it); ragSelectorVisible = false },
                onClose = { ragSelectorVisible = false }
            )
        }

        // M6 Phase C：工具调用确认对话框（US-039，ADR-016 5.5）
        // 覆盖 M4 Skill 工具 + M6 跨 App 工具，复用同一 UiConfirmationGate
        pendingConfirm?.let { request ->
            ToolConfirmationDialog(
                request = request,
                crossAppLauncher = crossAppLauncher,
                onAllow = {
                    confirmationGate?.respond(request.id, true)
                    pendingConfirm = null
                },
                onDeny = {
                    confirmationGate?.respond(request.id, false)
                    pendingConfirm = null
                }
            )
        }
    }
}

/**
 * 工具调用确认对话框（M6 Phase C，US-039，ADR-016 5.5）。
 *
 * **统一确认门禁**（ADR-016 R8 缓解）：
 * - M4 Skill 工具（`skillName__toolName`）：仅展示工具名 + 参数
 * - M6 跨 App 工具（`cross_app__*`）：从 [CrossAppLauncher.getAppConfig] 查询 App 显示名，
 *   展示「App 名称 + 操作类型 + 内容预览」富信息
 *
 * **降级提示**：跨 App 工具若目标 App 未安装，对话框文本提示用户「未安装，将返回降级提示」，
 * 用户仍可允许执行（CrossAppLauncher.launchApp 会返回 fallbackUrl 描述回灌 LLM）。
 *
 * @param request 待确认请求（来自 [UiConfirmationGate.requests]）
 * @param crossAppLauncher 跨 App 调用入口（可空：null 时仅展示工具名 + 参数，不查询 App 信息）
 * @param onAllow 用户允许回调（调用方回灌 `gate.respond(id, true)`）
 * @param onDeny 用户拒绝回调（调用方回灌 `gate.respond(id, false)`）
 */
@Composable
private fun ToolConfirmationDialog(
    request: UiConfirmationGate.PendingConfirm,
    crossAppLauncher: CrossAppLauncher?,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    // 解析确认文案：跨 App 工具富信息 / 通用工具名 + 参数
    val (title, content) = remember(request, crossAppLauncher) {
        resolveConfirmationContent(request, crossAppLauncher)
    }

    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(text = title, color = PrismText, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(text = content, color = PrismTextDim, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            PrismButton(text = "允许", variant = PrismButtonVariant.Primary, onClick = onAllow)
        },
        dismissButton = {
            PrismButton(text = "拒绝", variant = PrismButtonVariant.Ghost, onClick = onDeny)
        }
    )
}

/**
 * 解析确认对话框的标题与内容文案（[ToolConfirmationDialog] 辅助函数）。
 *
 * **纯函数**（BR-testing-004）：可在纯 JVM 测试中直接验证，无 Android 依赖。
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

/** 当前 Provider 胶囊 —— 点击弹出切换列表（US-007）。 */
@Composable
private fun ProviderChip(name: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel2)
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "◈", color = PrismMint, fontSize = 12.sp)
        Text(
            text = name ?: "选择 Provider",
            color = if (name != null) PrismText else PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = "切换 ▾", color = PrismTextFaint, fontSize = 10.sp)
    }
}

/**
 * RAG 模式切换胶囊（US-019，ADR-012 5.2/5.8）。
 *
 * 三态显示：全库（默认，薄荷色）/ 指定库（薄荷色 + 库 id）/ 关闭（灰色）。
 */
@Composable
private fun RagModeChip(target: RagTarget, onClick: () -> Unit) {
    val (label, accent) = when (target) {
        RagTarget.Off -> "RAG 关" to PrismTextFaint
        RagTarget.AllLibraries -> "RAG 全库" to PrismMint
        is RagTarget.SpecificLibrary -> "RAG #${target.kbId}" to PrismMint
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel2)
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "◉", color = accent, fontSize = 12.sp)
        Text(
            text = label,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** RAG 模式切换弹层（US-019，ADR-012 5.2）。 */
@Composable
private fun RagModeSelectorSheet(
    current: RagTarget,
    onSelect: (RagTarget) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "RAG 检索模式",
        subtitle = "对话时检索知识库并标注引用来源（默认全库检索）"
    ) {
        RagModeOption(
            label = "全库检索",
            description = "跨所有知识库检索 top-3 片段",
            selected = current is RagTarget.AllLibraries,
            onClick = { onSelect(RagTarget.AllLibraries) }
        )
        Spacer(Modifier.height(8.dp))
        RagModeOption(
            label = "关闭 RAG",
            description = "普通对话，不检索知识库",
            selected = current is RagTarget.Off,
            onClick = { onSelect(RagTarget.Off) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "提示：指定库检索将通过知识库管理页选择具体库后启用（暂未开放）",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** RAG 模式选项行（US-019）。 */
@Composable
private fun RagModeOption(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "◉", color = if (selected) PrismMint else PrismTextFaint, fontSize = 14.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
        }
        if (selected) {
            Text(text = "当前", color = PrismMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Provider 切换列表弹层（US-007）。 */
@Composable
private fun ProviderSelectorSheet(
    providers: List<ProviderConfig>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "切换 Provider",
        subtitle = "切换后保留对话历史，新消息走新 Provider"
    ) {
        if (providers.isEmpty()) {
            Text(text = "尚未配置 Provider，请到「设置」中添加并激活。", color = PrismTextFaint, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
        providers.forEach { config ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrismPanel2)
                    .border(1.dp, if (config.id == activeId) PrismMint.copy(alpha = 0.4f) else PrismLine, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(config.id) }
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "◈", color = PrismIndigo, fontSize = 14.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = config.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = config.baseUrl, color = PrismTextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (config.id == activeId) {
                    Text(text = "当前", color = PrismMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 单个消息气泡（入场上浮 + 透明度）。
 *
 * - 用户消息：右侧，靛蓝紫渐变 + 指向侧 6dp 圆角
 * - AI 消息：左侧，玻璃气泡 + [PrismAvatar] + 引用来源胶囊列表（[ChatMessage.sources]，US-019）
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(
            animationSpec = tween(durationMillis = 400)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                PrismAvatar(
                    modifier = Modifier.padding(end = 10.dp),
                    avatarSize = 30.dp
                )
            }
            Column(
                modifier = Modifier.widthIn(max = 280.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (isUser) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(listOf(PrismIndigo.copy(alpha = 0.45f), PrismIndigoSoft.copy(alpha = 0.35f))),
                                RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                            )
                            .border(1.dp, PrismIndigo.copy(alpha = 0.4f), RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                            .padding(horizontal = 15.dp, vertical = 11.dp)
                    ) {
                        Text(
                            text = message.content,
                            color = PrismText,
                            fontSize = 14.sp,
                            lineHeight = 23.sp
                        )
                    }
                } else {
                    PrismGlassCard(
                        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                    ) {
                        Text(
                            text = message.content,
                            color = PrismText,
                            fontSize = 14.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)
                        )
                        // 引用来源列表（US-019，ADR-012 5.3）：检索阶段已附在 AI 占位消息上
                        if (message.sources.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                message.sources.forEach { citation ->
                                    SourceChip(citation = citation)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 引用来源胶囊（薄荷色 + 边框，US-003/US-019 防幻觉 UI 呈现）。
 *
 * **US-019 变更**：从单 String 改为接收 [Citation]，显示「[来源N] 文档名 #片段号」。
 * citation.chunkIndex 为 null 时省略片段号（解析失败容错）。
 */
@Composable
private fun SourceChip(citation: Citation, modifier: Modifier = Modifier) {
    val chunkPart = citation.chunkIndex?.let { " #$it" } ?: ""
    val text = "[来源${citation.index}] ${citation.documentTitle}$chunkPart"
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PrismMint.copy(alpha = 0.08f))
            .border(1.dp, PrismMint.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "◈  $text",
            color = PrismMint,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * AI 打字指示 —— 三点呼吸 + 状态文案（US-019 文案修正，ADR-012 5.8 修复 R-7）。
 *
 * @param isRagOn RAG 是否开启；true 显示「正在检索知识库…」，false 显示「正在思考…」
 */
@Composable
private fun TypingIndicator(isRagOn: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    val statusText = if (isRagOn) "正在检索知识库…" else "正在思考…"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        PrismAvatar(modifier = Modifier.padding(end = 10.dp), avatarSize = 30.dp)
        PrismGlassCard(
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i ->
                        val alpha = transition.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 700 + i * 150, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dot$i"
                        ).value
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(PrismTextDim.copy(alpha = alpha))
                        )
                    }
                }
                Text(
                    text = statusText,
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** 底部输入栏 —— 玻璃胶囊输入框 + 靛蓝渐变圆形发送钮（带光晕）。 */
@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(PrismPanel, RoundedCornerShape(24.dp))
                .border(1.dp, PrismLine, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "输入问题…",
                    color = PrismTextFaint,
                    fontSize = 14.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = PrismText,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(PrismCyan),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(PrismIndigo, PrismIndigoSoft)),
                    CircleShape
                )
                .border(1.dp, PrismIndigo.copy(alpha = 0.5f), CircleShape)
                .clickable(enabled = value.isNotBlank()) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "➤",
                color = Color.White,
                fontSize = 17.sp
            )
        }
    }
}