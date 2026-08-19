package io.prism.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import io.prism.crossapp.AppLauncherBridge
import io.prism.crossapp.CrossAppLauncher
import io.prism.data.ProviderConfig
import io.prism.document.DocumentParserRegistry
import io.prism.network.WebSearchLocalToolExecutor
import io.prism.rag.RagTarget
import io.prism.skill.AskUserQuestion
import io.prism.skill.SkillExecutor
import io.prism.ui.components.PrismAvatar
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismGlassCard
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismTopBar
import io.prism.ui.components.PrismTopBarAction
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.SearchResult
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismError
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismIndigoSoft
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天屏幕 —— 深空玻璃肌理（设计规范 v0.2 第 8.1 节）。
 *
 * 布局（自上而下）：
 * 1. 顶栏 [PrismTopBar]：主标题「Prism」+ 当前 Provider 副标题 + 新会话 / 能力操作
 * 2. Provider 选择器胶囊 + RAG 模式切换胶囊（US-019）
 * 3. 消息列表：AI 玻璃气泡（含 Markdown 渲染 + 可折叠思维链/引用来源/搜索来源）/ 用户靛蓝渐变气泡
 * 4. 打字指示：AI 回复中三点呼吸 + 状态文案
 * 5. 能力开关行（联网搜索 / 深度思考）+ 玻璃胶囊输入框 + 靛蓝渐变圆形发送钮
 *
 * **UX-001 体验修复**（ADR-021）：
 * - 问题 1：消息列表自动滚动到底部（[LaunchedEffect] 监听消息数 + animateScrollToItem）
 * - 问题 3：AI 消息改用 [Markdown] 渲染（替代 stripMarkdownSymbols 剥离）
 * - 问题 5：能力开关（联网搜索 / 深度思考）置于输入框上方胶囊行
 * - 问题 6：RAG 引用来源改为可折叠区域（[CollapsibleSourcesCard]）
 * - 问题 7：思维链改为可折叠区域（[CollapsibleThinkingCard]），与最终答案区分
 * - 问题 8：联网搜索结果改为可折叠来源卡片（[CollapsibleSearchCard]），链接可点击跳转外部网站
 * - 问题 9：工具调用指示不再混入正文（TOOL 消息独立紧凑气泡）
 */
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = viewModel(factory = ConversationViewModel.Factory),
    /**
     * UX-001 问题 4（ADR-021）：打开会话历史列表的入口回调（顶栏「历史会话」按钮）。
     * null 时不显示历史按钮（向后兼容测试 / 独立预览场景）。
     */
    onOpenHistory: (() -> Unit)? = null,
    /**
     * UX-001 问题 4（ADR-021）：从历史列表选中的待加载会话 id。
     * 非 null 时 [LaunchedEffect] 触发 [ConversationViewModel.loadSession] 恢复历史对话，
     * 完成后回调 [onSessionLoaded] 清空待加载状态（一次性消费）。
     */
    sessionIdToLoad: Long? = null,
    /** UX-001 问题 4（ADR-021）：loadSession 完成后回调（导航层清空待加载状态）。 */
    onSessionLoaded: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    // UXR9 US-906：发送后自动收起键盘（LocalSoftwareKeyboardController 需在组合内获取）
    val keyboardController = LocalSoftwareKeyboardController.current
    // UXR6 问题 2：每消息独立流式标记（替代「isTyping && lastOrNull()」全局推断）
    val streamingIds by viewModel.streamingIds.collectAsState()
    // UXR6 问题 3a：当前是否正在 RAG 检索（真实检索状态驱动「检索知识库」指示）
    val ragRetrieving by viewModel.ragRetrieving.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val ragTarget by viewModel.ragTarget.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabledFlow.collectAsState()
    // UXR8 N2 Phase 2（ADR-030）：LLM 反问/澄清待答问题（null = 无待答问题，不显示卡片）
    val pendingAskUser by viewModel.pendingAskUser.collectAsState()
    var providerSelectorVisible by remember { mutableStateOf(false) }
    var ragSelectorVisible by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    // UXR3 问题 13（ADR-023）：待编辑的用户消息 id（非 null 时发送改为编辑重发）
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    // R5（UXR10，ADR-032）：待发送附件草稿 —— 选择图片/文件后**不立即发送**，
    // 暂存于此并显示预览，用户输入需求文本后点发送统一发出。图片与文件互斥
    // （后选替换前选）；发送/移除/切换会话后清空。
    var pendingImageDataUrl by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var pendingFileText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // ============ M6 Phase C：跨 App 调用 UI 集成（US-039，ADR-016 5.5） ============

    val appLauncherBridge = viewModel.appLauncherBridge
    val crossAppLauncher = viewModel.crossAppLauncher

    // 待处理的 Intent 请求 id 与 action（launcher 回调时需要区分 open_app 与 share/picker 语义）
    var pendingIntentRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingIntentAction by remember { mutableStateOf<String?>(null) }

    // ActivityResult launcher：必须先于收集其回调的 state 声明（参考 KnowledgeBaseScreen 先例）
    val startActivityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        // DEF-006（Bug-7）：外部 App（如 QQ）通过 ACTION_VIEW 打开后不会 setResult(RESULT_OK)，
        // 其 Activity 结束默认回传 RESULT_CANCELED，此前被误映射为"用户取消"（实际已成功打开）。
        val resultText = mapCrossAppResult(
            resultCode = result.resultCode,
            dataString = result.data?.dataString,
            intentAction = pendingIntentAction
        )
        pendingIntentRequestId?.let { id ->
            appLauncherBridge?.respond(id, resultText)
            pendingIntentRequestId = null
            pendingIntentAction = null
        }
    }

    // 收集 AppLauncherBridge.requests 流 —— 触发 launcher.launch(intent)
    LaunchedEffect(appLauncherBridge) {
        appLauncherBridge?.requests?.collect { request ->
            pendingIntentRequestId = request.id
            pendingIntentAction = request.intent.action
            startActivityLauncher.launch(request.intent)
        }
    }

    // 生命周期清理：Composable 离开组合时清理 pending deferred，避免泄漏（ADR-016 R2）
    DisposableEffect(crossAppLauncher) {
        onDispose {
            crossAppLauncher?.cancelAll()
        }
    }

    // UXR8 N3（ADR-030）：图片消息 —— 系统图片选择器 → data URL → sendMessage(imageUrl)
    // 图片经 resize（最长边 1024px）+ JPEG 压缩（质量 80）后 base64，控制请求体大小。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Q-MED-4（guardrail TKN-UXR9-GUARDRAIL-002）：图片解码+压缩+base64 是重 CPU
            // 操作（BitmapFactory/ImageDecoder 兜底路径需全尺寸 bounds 读取），移入 IO 协程，
            // 与文件解析（下方 filePickerLauncher）对称，避免大图在 ActivityResult 回调（主线程）
            // 同步执行导致卡顿。
            scope.launch {
                val dataUrl = withContext(Dispatchers.IO) { encodeImageToDataUrl(context, uri) }
                if (dataUrl != null) {
                    // R5（UXR10，ADR-032）：不再立即发送 —— 暂存为待发送草稿，
                    // 用户输入需求文本后点发送统一发出（图片与文件互斥，替换旧附件）。
                    pendingImageDataUrl = dataUrl
                    pendingFileName = null
                    pendingFileText = null
                } else {
                    // Bug fix（UXR8-R3，guardrail M-2 修复）：编码失败时给予用户可见提示，
                    // 而非静默丢弃。走 notifyEncodingFailure —— isTyping（AI 回复中）期间
                    // 暂存队列，回复完成后显示，不被 sendMessage 守卫丢弃。
                    viewModel.notifyEncodingFailure()
                }
            }
        }
    }

    // UXR9 US-907：文件上传 —— 系统文件选择器（PDF/DOCX/XLSX/PPTX/MD/TXT/CSV）。
    // 本地解析提取文本 → 作为用户消息发送 LLM（PRD D-2 决策：方案 A，文本直发）。
    // 解析在 IO 协程执行（不阻塞主线程），失败走系统提示通道（不触发 LLM）。
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val name = queryDocumentDisplayName(context, uri) ?: "document"
                val parsed = withContext(Dispatchers.IO) { extractDocumentText(context, uri) }
                if (parsed != null) {
                    // R5（UXR10，ADR-032）：不再立即发送 —— 暂存为待发送草稿，
                    // 用户输入需求文本后点发送统一发出（文件与图片互斥，替换旧附件）。
                    pendingFileName = name
                    pendingFileText = parsed
                    pendingImageDataUrl = null
                } else {
                    viewModel.notifyDocumentError()
                }
            }
        }
    }

    // UX-001 问题 1（ADR-021）：消息列表自动滚动到底部。
    // UXR4 问题 5（ADR-024）节流：此前监听 `messages.lastOrNull()?.content?.length`，
    // 流式 delta 高频到达时每次长度变化都触发 animateScrollToItem → 弱设备上"一行一跳"式浮现。
    // 改为仅监听**消息数变化**（新消息/新气泡）与**末条消息 id 变化**（流式回答进行中）滚动；
    // 同一消息内部 content 增量不再触发滚动动画（内容已在视口内自然增长，无需追滚）。
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // UX-001 问题 4（ADR-021）：从历史列表返回时恢复指定会话。
    // 一次性消费：loadSession 完成后回调 onSessionLoaded 清空待加载状态，避免重复触发。
    LaunchedEffect(sessionIdToLoad) {
        if (sessionIdToLoad != null) {
            viewModel.loadSession(sessionIdToLoad)
            onSessionLoaded()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // UXR3 问题 13（ADR-023）：复制消息到剪贴板所需的 Context（消息气泡内使用）
        val context = LocalContext.current
        // UXR3 问题 1（ADR-023，键盘 IME 三次修复）：IME padding 由 PrismNavBar 底部
        // `ime.union(navigationBars)` 单一来源处理，本页不再单独 imePadding（避免叠加）。
        // 键盘弹出时 NavBar 上移带动 content 收缩，消息列表 weight(1f) 自然收缩，
        // 输入区贴合 NavBar 上缘；顶部状态栏 inset 由 PrismApp 的 statusBarsPadding 统一处理。
        Column(modifier = Modifier.fillMaxSize()) {
            PrismTopBar(
                title = "Prism",
                subtitle = "深空 AI · ${activeProvider?.name ?: "未配置"}",
                actions = {
                    // UX-001 问题 4（ADR-021）：历史会话入口（顶栏按钮，null 时隐藏）
                    if (onOpenHistory != null) {
                        PrismTopBarAction(
                            icon = { Icon(Icons.Filled.History, null, tint = PrismTextDim) },
                            contentDescription = "历史会话",
                            onClick = onOpenHistory
                        )
                    }
                    PrismTopBarAction(
                        icon = { Icon(Icons.Filled.Add, null, tint = PrismTextDim) },
                        contentDescription = "新会话",
                        onClick = { viewModel.startNewConversation() }
                    )
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
                    // UXR9 US-908：预构建 工具调用 id → 参数 查找表（assistant 消息携带的
                    // toolCalls 反向映射），供 TOOL 消息渲染「参数摘要」。
                    val toolArgsById = remember(messages) {
                        messages.flatMap { it.toolCalls }.associateBy { it.id }
                    }
                    MessageBubble(
                        message = message,
                        // UXR3 问题 13（ADR-023）：复制 / 编辑
                        onCopy = { copyToClipboard(context, message.content) },
                        onEdit = { id, original ->
                            // 编辑：回填输入框 + 标记待编辑消息 id（发送时走编辑重发）
                            editingMessageId = id
                            input = original
                        },
                        // UXR4 问题 1/4/6（ADR-024）：思考区展示受深度思考开关控制
                        //（协议层 reasoning_content 回传不受此影响，见 ADR-024 子决策 A）
                        showThinking = thinkingEnabled,
                        // UXR5 问题 1 / UXR6 问题 2（markdown 井号残留）：流式期间该消息渲染为
                        // 纯文本，避免 markdown-renderer 0.26.0 全量重解析的中间态闪烁/井号残留。
                        // UXR6 改用**每消息独立流式标记**（streamingIds），替代「isTyping &&
                        // lastOrNull()」全局推断 —— 后者在工具回路 Error 清 isTyping、或多消息
                        // 并发时误判，导致完成消息被 Markdown 渲染不完整中间态。
                        isStreaming = message.id in streamingIds,
                        // UXR9 US-908：工具调用卡片参数查找表
                        toolArgsById = toolArgsById
                    )
                }
                // UX-001 问题 7（ADR-022）：工具调用状态可视化 —— 调用中显示「正在调用工具: xxx」
                activeTool?.let { toolName ->
                    item { ToolCallIndicator(toolName) }
                }
                if (isTyping) {
                    // UXR6 问题 3a（替代 UXR3 问题 7 的全局 ragDone 推断）：是否显示「正在检索
                    // 知识库…」由**真实检索状态**（ragRetrieving）驱动 —— 仅当前消息实际执行 RAG
                    // 检索期间显示；检索完成/降级后切换为「正在生成回答…」。修复旧逻辑
                    // 「ragTarget 非 Off + 全局 ragDone」导致无论问题是否相关都显示检索画面。
                    item { TypingIndicator(isRagOn = ragRetrieving) }
                }
            }

            // 底部输入区：能力开关 + 输入栏。
            // UXR3 问题 1（键盘遮挡，三次修复未果）：不再单独 imePadding ——
            // Scaffold 已关闭 content insets、PrismNavBar 底部已 imePadding，
            // 键盘弹出时 NavBar 上移带动 content 高度收缩，此处消息列表 weight(1f)
            // 自然收缩、输入区贴合 NavBar 上缘。若此处再加 imePadding 会与 NavBar
            // 的 imePadding 叠加，导致输入框被顶得过高（MIUI 真机复现的问题）。
            Column(modifier = Modifier) {
                // UX-001 问题 5（ADR-021）：能力开关行置于输入框上方（联网搜索 / 深度思考）
                CapabilityToggleRow(
                    thinkingEnabled = thinkingEnabled,
                    webSearchEnabled = webSearchEnabled,
                    onThinkingChange = { viewModel.setThinkingEnabled(it) },
                    onWebSearchChange = { viewModel.setWebSearchEnabled(it) }
                )

                MessageInputBar(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        // UXR3 问题 13（ADR-023）：编辑模式下发送走编辑重发（替换原消息 + 重新回答）
                        val editingId = editingMessageId
                        if (editingId != null) {
                            viewModel.editUserMessageAndResend(editingId, input)
                            editingMessageId = null
                        } else {
                            // R5（UXR10，ADR-032）：统一发送附件草稿 + 需求文本。
                            // - 文件附件：文档文本与用户需求合并为一条用户消息（LLM 同轮收到内容+指令）
                            // - 图片附件：走 sendMessage(text, imageUrl) 多模态直传
                            // - 无附件：普通文本消息
                            val fileText = pendingFileText
                            if (fileText != null) {
                                viewModel.sendMessage(
                                    if (input.isBlank()) fileText else fileText + "\n\n" + input
                                )
                            } else {
                                viewModel.sendMessage(input, pendingImageDataUrl)
                            }
                        }
                        input = ""
                        pendingImageDataUrl = null
                        pendingFileName = null
                        pendingFileText = null
                        // UXR9 US-906：发送后自动收起键盘，给 LLM 输出留出可视空间
                        keyboardController?.hide()
                    },
                    // R5：待发送附件草稿预览 + 移除
                    pendingImageDataUrl = pendingImageDataUrl,
                    pendingFileName = pendingFileName,
                    onRemoveAttachment = {
                        pendingImageDataUrl = null
                        pendingFileName = null
                        pendingFileText = null
                    },
                    // UXR9 US-907：移除独立图片入口，统一走"＋"折叠栏（相册 / 文件）
                    onImagePick = { imagePickerLauncher.launch("image/*") },
                    onFilePick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "text/plain",
                                "text/markdown",
                                "text/csv"
                            )
                        )
                    }
                )
            }
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

        // UXR8 N2 Phase 2（ADR-030）：LLM 反问/澄清提问卡片。
        // pendingAskUser 非空时展示（工具回路已由 executeLoop 中断）——用户答复作为
        // 下一条 user 消息进入下一轮（sendMessage + clearAskUser）；跳过则发送跳过
        // 消息让 LLM 基于已有信息直接回答。dismiss 仅清状态（不额外发消息）。
        pendingAskUser?.let { questions ->
            PrismSheetHost(
                visible = true,
                onDismiss = { viewModel.clearAskUser() }
            ) {
                AskUserSheet(
                    questions = questions,
                    onSubmit = { answer ->
                        viewModel.sendMessage(answer)
                        viewModel.clearAskUser()
                    },
                    onSkip = {
                        viewModel.sendMessage(SKIP_ASK_USER_MESSAGE)
                        viewModel.clearAskUser()
                    }
                )
            }
        }
    }
}

/**
 * 能力开关行（UX-001 问题 5，ADR-021）—— 输入框上方胶囊式开关。
 *
 * 对齐主流 AI 手机助手（DeepSeek/Kimi/豆包）的「联网搜索 / 深度思考」开关摆放：
 * 两个开关并排置于输入框上方，选中态高亮，未选中浅灰。
 */
@Composable
private fun CapabilityToggleRow(
    thinkingEnabled: Boolean,
    webSearchEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CapabilityToggleChip(
            checked = webSearchEnabled,
            label = "联网搜索",
            onCheckedChange = onWebSearchChange,
            modifier = Modifier.weight(1f)
        )
        CapabilityToggleChip(
            checked = thinkingEnabled,
            label = "深度思考",
            onCheckedChange = onThinkingChange,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 单个能力开关胶囊（选中态 PrismIndigo 描边 + 标签加亮，未选中浅灰描边）。 */
@Composable
private fun CapabilityToggleChip(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // UX-001 问题 2（ADR-022 二次反馈）：toggleable(role = Switch) 暴露 checked 选中态语义，
    // 使开关状态对无障碍/读屏/UI Automator 可识别（用户反馈"看不出深度思考开关是否生效"）。
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) PrismIndigo.copy(alpha = 0.12f) else PrismPanel2)
            .border(1.dp, if (checked) PrismIndigo.copy(alpha = 0.6f) else PrismLine, RoundedCornerShape(10.dp))
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (checked) PrismIndigo else PrismTextFaint)
        )
        Text(
            text = label,
            color = if (checked) PrismText else PrismTextDim,
            fontSize = 12.sp,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
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
 * 单个消息气泡。
 *
 * - 用户消息：右侧，靛蓝紫渐变气泡 + 指向侧 6dp 圆角
 * - AI 消息：左侧，**无头像无气泡容器**（UX-001 问题 1 二次反馈，为 LLM 输出腾空间），
 *   直接渲染 Markdown 正文 + 可折叠思维链/引用/搜索来源
 * - TOOL 消息：紧凑工具结果气泡（工具名 + 截断内容，UX-001 问题 9）
 *
 * **UXR3 问题 13（ADR-023）**：用户与 AI 消息下方提供「复制」操作；用户消息额外提供
 * 「编辑」操作（回填输入框 + 标记编辑 id，发送时走编辑重发）。TOOL 消息不提供（协议占位）。
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onEdit: (Long, String) -> Unit,
    /** UXR4 问题 1/4/6（ADR-024）：是否展示「深度思考」折叠区。协议层仍回传 reasoning_content，UI 展示由开关控制。 */
    showThinking: Boolean,
    /** UXR5 问题 1（ADR-024 遗留）：该消息是否为当前正在流式生成的 AI 回复（流式期间用纯文本渲染）。 */
    isStreaming: Boolean,
    /** UXR9 US-908：工具调用 id → 参数引用 查找表（渲染工具卡片参数摘要）。 */
    toolArgsById: Map<String, io.prism.ui.model.ToolCallRef> = emptyMap()
) {
    val isUser = message.role == Role.USER
    val isTool = message.role == Role.TOOL

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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                when {
                    // UXR9 Bug3：系统提示消息（如"图片编码失败"）→ 居中弱化提示气泡，
                    // 不触发 LLM、不进请求历史（ConversationViewModel 已处理）
                    message.isSystemNotice -> SystemNoticeBubble(message)
                    isUser -> UserBubble(message)
                    isTool -> SkillCallCard(message, toolArgsById)
                    else -> AiBubble(message, showThinking, isStreaming)
                }
                // UXR3 问题 13（ADR-023）：消息操作行（复制 / 编辑）
                // 仅对 USER / ASSISTANT 非空内容消息展示；TOOL 占位消息不展示
                if (!isTool && message.content.isNotBlank()) {
                    MessageActionRow(
                        canEdit = isUser,
                        onCopy = onCopy,
                        onEdit = { onEdit(message.id, message.content) }
                    )
                }
            }
        }
    }
}

/**
 * 消息操作行（UXR3 问题 13，ADR-023）—— 复制 / 编辑 小号文字按钮。
 *
 * 置于消息气泡下方右侧（对齐用户/AI 消息的侧向），与正文视觉区分。
 * 复制：所有消息可用；编辑：仅用户消息可用（回填输入框 + 标记待编辑 id）。
 */
@Composable
private fun MessageActionRow(
    canEdit: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "复制",
            color = PrismTextFaint,
            fontSize = 10.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCopy
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        if (canEdit) {
            Text(
                text = "编辑",
                color = PrismTextFaint,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEdit
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** 复制文本到系统剪贴板（UXR3 问题 13，ADR-023）。 */
private fun copyToClipboard(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prism 消息", text))
}

/**
 * 系统提示气泡（UXR9 Bug3）：居中、弱化（小字号 + 淡色背景），无头像无操作行。
 * 用于"图片编码失败"等**不触发 LLM** 的一次性提示。
 */
@Composable
private fun SystemNoticeBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = message.content,
            color = PrismTextFaint,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(PrismPanel2.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Box(
        modifier = Modifier
            .background(
                Brush.linearGradient(listOf(PrismIndigo.copy(alpha = 0.45f), PrismIndigoSoft.copy(alpha = 0.35f))),
                RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            )
            .border(1.dp, PrismIndigo.copy(alpha = 0.4f), RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
            .padding(horizontal = 15.dp, vertical = 11.dp)
    ) {
        Column(horizontalAlignment = Alignment.End) {
            // UXR8 N3（ADR-030）：图片消息渲染（data URL 解码 → Bitmap → Image）
            message.imageUrl?.let { dataUrl ->
                decodeImageDataUrl(dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "用户发送的图片",
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    color = PrismText,
                    fontSize = 14.sp,
                    lineHeight = 23.sp
                )
            }
        }
    }
}

/**
 * 解码图片 data URL 为 [Bitmap]（UXR8 N3，ADR-030，零新增依赖）。
 *
 * data URL 格式：`data:image/jpeg;base64,<payload>`。解码失败返回 null（UI 降级不渲染）。
 */
private fun decodeImageDataUrl(dataUrl: String): Bitmap? {
    val marker = "base64,"
    val idx = dataUrl.indexOf(marker)
    if (idx < 0) return null
    return try {
        val bytes = android.util.Base64.decode(dataUrl.substring(idx + marker.length), android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

/**
 * 将图片 Uri 编码为 data URL（UXR8 N3，ADR-030，零新增依赖）。
 *
 * 读图 → 降采样解码 → 最长边缩放至 [MAX_IMAGE_EDGE_PX]（控请求体大小）→ JPEG 压缩（质量 80）
 * → base64 → `data:image/jpeg;base64,...`。读取/解码失败返回 null（调用方走
 * [ConversationViewModel.notifyEncodingFailure] 提示）。
 *
 * **UXR9 Bug3 修复（TKN-UXR9-ARCHAEOLOGY-001）**：
 * - **双解码链路**：优先 [ImageDecoder]（API 28+，原生支持 HEIC/HEIF，解码时直接
 *   `setTargetSize` 降采样），失败回退 [BitmapFactory]（API 26-27 / ImageDecoder 不可用）。
 *   此前仅 BitmapFactory，小米真机相册 HEIC 图片解码失败 → 全部图片提示"编码失败"。
 * - **可观测性**：失败路径补结构化日志（Log.w + 阶段 + 异常类型/消息），真机 logcat
 *   可定位根因（此前 catch 吞掉所有异常且零日志）。
 * - **OOM 捕获**：`OutOfMemoryError` 是 Error 而非 Exception，`catch (e: Exception)` 抓不到，
 *   单独捕获并提示。
 *
 * **隐私**：data URL 仅存在于内存与发送给用户自配的模型端点，不落盘、不入库。
 */
private fun encodeImageToDataUrl(context: Context, uri: Uri): String? {
    val bitmap = try {
        decodeImageBitmap(context, uri)
    } catch (e: OutOfMemoryError) {
        Log.w(IMAGE_ENCODE_TAG, "图片解码 OOM（图片过大或内存不足）", e)
        null
    } catch (e: Exception) {
        Log.w(IMAGE_ENCODE_TAG, "图片解码失败: ${e::class.simpleName}: ${e.message}", e)
        null
    } ?: return null

    return try {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    } catch (e: OutOfMemoryError) {
        Log.w(IMAGE_ENCODE_TAG, "图片压缩 OOM", e)
        null
    } catch (e: Exception) {
        Log.w(IMAGE_ENCODE_TAG, "图片压缩/编码失败: ${e::class.simpleName}: ${e.message}", e)
        null
    } finally {
        bitmap.recycle()
    }
}

/**
 * 解码图片 Bitmap（UXR9 Bug3 双解码链路）。
 *
 * **ImageDecoder 优先**（API 28+）：原生支持 HEIC/HEIF/WebP 等格式，且解码时通过
 * `setTargetSize` 直接降采样到 [MAX_IMAGE_EDGE_PX]，避免全尺寸位图驻留堆内存。
 * **BitmapFactory 兜底**（API 26-27 或 ImageDecoder 失败）：保持原有 inSampleSize
 * 降采样 + 等比缩放逻辑。
 *
 * @return 解码并缩放到 MAX_IMAGE_EDGE_PX 的 Bitmap；失败返回 null（调用方已记录日志）
 */
private fun decodeImageBitmap(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxEdge = MAX_IMAGE_EDGE_PX.toFloat()
                val scale = minOf(maxEdge / info.size.width, maxEdge / info.size.height, 1f)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(IMAGE_ENCODE_TAG, "ImageDecoder 解码失败，回退 BitmapFactory: ${e::class.simpleName}: ${e.message}")
        }
    }
    return decodeWithBitmapFactory(context, uri)
}

/** BitmapFactory 降采样解码 + 等比缩放（API 26-27 / ImageDecoder 兜底路径）。 */
private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap? {
    // 1. 仅读尺寸（inJustDecodeBounds 不分配像素内存）→ 计算 inSampleSize
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE_PX)

    // 2. 降采样解码（像素尺寸 ≈ target~2×target 边，内存可控）
    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null

    // 3. 等比缩放至 MAX_IMAGE_EDGE_PX（控 base64 体积：1024px JPEG q80 ≈ 200-500KB）
    val maxEdge = MAX_IMAGE_EDGE_PX.toFloat()
    val scale = minOf(maxEdge / bitmap.width, maxEdge / bitmap.height, 1f)
    return if (scale < 1f) {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != bitmap) bitmap.recycle()
        scaled
    } else {
        bitmap
    }
}

/**
 * 计算解码降采样率（UXR8 N3，ADR-030，Q-MED-4 修复，纯函数）。
 *
 * 返回 2 的幂，使解码后最长边 ∈ [targetEdge, 2*targetEdge)。例如 8000px + target=1024 →
 * sample=4 → 解码边 2000px（约 12MB ARGB），再缩放至 1024。防止全尺寸位图驻留堆内存。
 */
private fun computeInSampleSize(origWidth: Int, origHeight: Int, targetEdge: Int): Int {
    var sample = 1
    while (maxOf(origWidth, origHeight) / (sample * 2) >= targetEdge) {
        sample *= 2
    }
    return sample
}

/** UXR8 N3（ADR-030）：图片最长边像素上限（等比缩放，控请求体大小）。 */
private const val MAX_IMAGE_EDGE_PX = 1024

/** UXR9 Bug3：图片编码/解码日志标签（真机 logcat 定位根因用）。 */
private const val IMAGE_ENCODE_TAG = "ImageEncode"

/** UXR9 US-907：文档消息发送给 LLM 的最大字符数（超长截断，控 token 开销）。 */
private const val DOCUMENT_MESSAGE_MAX_LEN = 30000

/** UXR9 US-907：文档解析日志标签。 */
private const val DOCUMENT_PARSE_TAG = "DocumentParse"

/**
 * UXR9 US-907：从文件选择器返回的 [Uri] 本地解析文档并提取文本（IO 线程调用）。
 *
 * **流程**：查询显示名（OpenableColumns.DISPLAY_NAME）→ [DocumentParserRegistry] 按扩展名
 * 分发解析器 → 提取文本 → 以 `【文档：文件名】` 前缀包装（供 LLM 感知来源）→ 超长截断。
 *
 * **降级**：解析失败 / 空文本返回 null（调用方走系统提示通道，不触发 LLM）。
 * 与知识库 ingest 共用同一批解析器（PDF/DOCX/XLSX/PPTX/MD/TXT/CSV），零新增依赖。
 *
 * @param context 用于 contentResolver 读取
 * @param uri 文件选择器返回的文档 Uri
 * @return 包装后的文档文本；解析失败返回 null
 */
private fun extractDocumentText(context: Context, uri: Uri): String? {
    val displayName = queryDocumentDisplayName(context, uri) ?: "document"
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val parser = DocumentParserRegistry().parserFor(displayName)
            val text = parser.parse(input)
            if (text.isBlank()) return null
            buildString {
                append("【文档：$displayName】\n")
                append(text.take(DOCUMENT_MESSAGE_MAX_LEN))
                if (text.length > DOCUMENT_MESSAGE_MAX_LEN) {
                    append("\n…（文档过长，已截断，仅展示前 ${DOCUMENT_MESSAGE_MAX_LEN} 字）")
                }
            }
        }
    } catch (e: Exception) {
        // CWE-209：日志不含完整堆栈/路径细节；记录类型供真机排查。
        // Q-LOW-4（guardrail TKN-UXR9-GUARDRAIL-002）：文件名可能含 PII（如"身份证.pdf"），
        // 对齐 BR-error-handling-016 截断至 120 字符后再入日志。
        Log.w(DOCUMENT_PARSE_TAG, "文档解析失败: ${e::class.simpleName}: ${displayName.take(120)}")
        null
    }
}

/** 查询文件选择器返回 Uri 的显示名（OpenableColumns.DISPLAY_NAME），不可得返回 null。 */
private fun queryDocumentDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else null
    }
} catch (e: Exception) {
    Log.w(DOCUMENT_PARSE_TAG, "查询文档名失败: ${e::class.simpleName}")
    null
}

/**
 * LLM 反问/澄清提问卡片（UXR8 N2 Phase 2，ADR-030）。
 *
 * [ConversationScreen] 在 [ConversationViewModel.pendingAskUser] 非空时展示。
 * 每道问题渲染文本 + 建议选项（单选/多选按 [AskUserQuestion.multiSelect]）+ 自由文本补充。
 * 「提交回答」→ 答复作为下一条 user 消息进入下一轮（sendMessage + clearAskUser）；
 * 「跳过，直接回答」→ 发送跳过消息让 LLM 基于已有信息继续。
 */
@Composable
private fun AskUserSheet(
    questions: List<AskUserQuestion>,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    // Bug fix（UXR8-R3, TKN-UXR8-R3-ARCHAEOLOGY-001 根因）：此前用 MutableSet<String> 作为
    // mutableStateMapOf 的 value，但 MutableSet 不是 Compose 快照类型，.add/remove/clear
    // 不触发重组——选项点击选中态 UI 永不刷新，用户感知"无法点击"。
    // 改为用 Set<String>（不可变），每次点击替换整个 value 引用，写快照触发重组。
    val selected = remember { mutableStateMapOf<Int, Set<String>>() }
    // 每个问题的自由文本补充（question index → 文本）
    val freeTexts = remember { mutableStateMapOf<Int, String>() }

    /** 点击选项的选中态切换（快照安全，替换整个 set 引用）。 */
    fun toggleOption(qIndex: Int, label: String, multiSelect: Boolean) {
        val current = selected[qIndex] ?: emptySet()
        selected[qIndex] = if (multiSelect) {
            if (label in current) current - label else current + label
        } else {
            // 单选：直接替换为含 label 的 set
            setOf(label)
        }
    }

    PrismSheet(
        title = "AI 需要确认",
        subtitle = "为准确回答，先澄清以下问题",
        footer = {
            Column {
                PrismButton(
                    text = "提交回答",
                    onClick = {
                        onSubmit(buildAskUserAnswer(questions, selected, freeTexts))
                    }
                )
                Spacer(Modifier.height(8.dp))
                PrismButton(
                    text = "跳过，直接回答",
                    variant = PrismButtonVariant.Ghost,
                    onClick = onSkip
                )
            }
        }
    ) {
        questions.forEachIndexed { index, q ->
            Text(
                text = "${index + 1}. ${q.question}",
                color = PrismText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            q.options.forEach { option ->
                val isSelected = option.label in (selected[index] ?: emptySet())
                AskUserOptionChip(
                    label = option.label,
                    description = option.description,
                    selected = isSelected,
                    onClick = { toggleOption(index, option.label, q.multiSelect) }
                )
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            PrismField(
                label = if (q.options.isEmpty()) "回答" else "其他回答（可选）",
                value = freeTexts[index] ?: "",
                onValueChange = { freeTexts[index] = it },
                placeholder = "输入你的回答…"
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = "你的回答将作为下一条消息发给 AI（仅发送到自配的模型端点）",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
    }
}

/** 反问选项卡片（UXR8 N2 Phase 2，ADR-030）—— 可点选，选中态靛蓝描边 + 实心圆标。 */
@Composable
private fun AskUserOptionChip(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PrismIndigoSoft else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismIndigo.copy(alpha = 0.5f) else PrismLine,
                RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (selected) "◉" else "○",
            color = if (selected) PrismIndigo else PrismTextFaint,
            fontSize = 13.sp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (!description.isNullOrBlank()) {
                Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
            }
        }
    }
}

/**
 * 汇总提问卡片回答（UXR8 N2 Phase 2，ADR-030，纯函数可测）。
 *
 * 逐题生成「N. 问题\n回答：Y」段落：
 * - 选中选项 + 自由文本均有时 → 「选项（文本）」
 * - 仅选项 / 仅文本 → 各自内容
 * - 均未作答 → 「未回答」
 * 多题以空行分隔。空问题列表返回空串（调用方不发送）。
 */
private fun buildAskUserAnswer(
    questions: List<AskUserQuestion>,
    selected: Map<Int, Set<String>>,
    freeTexts: Map<Int, String>
): String {
    if (questions.isEmpty()) return ""
    return questions.mapIndexed { index, q ->
        val optionsText = selected[index]?.joinToString("、").orEmpty()
        val freeText = freeTexts[index]?.trim().orEmpty()
        val answer = when {
            optionsText.isNotEmpty() && freeText.isNotEmpty() -> "$optionsText（$freeText）"
            optionsText.isNotEmpty() -> optionsText
            freeText.isNotEmpty() -> freeText
            else -> "未回答"
        }
        "${index + 1}. ${q.question}\n回答：$answer"
    }.joinToString("\n\n")
}

/** UXR8 N2 Phase 2（ADR-030）：用户跳过反问时发送的消息（让 LLM 基于已有信息直接回答）。 */
private const val SKIP_ASK_USER_MESSAGE = "（已跳过反问）请基于已有信息直接回答我之前的请求。"

/**
 * Prism Markdown 排版（UX-001 问题 1 二次反馈，ADR-022）—— 压低标题字号，紧凑工整。
 *
 * 默认 MarkdownTypography 的 H1~H3 字号过大（24sp+），在窄屏手机显得突兀。
 * 将 H1/H2/H3 收敛为紧凑字号（对齐 Prism 正文 14sp 风格），缓解「标题过大、整体局促」。
 * 基于 MaterialTheme typography 的 title/body 系列，避免深空风格下字号突兀。
 */
@Composable
private fun prismMarkdownTypography(): MarkdownTypography {
    val t = androidx.compose.material3.MaterialTheme.typography
    return markdownTypography(
        h1 = t.titleLarge,
        h2 = t.titleMedium,
        h3 = t.titleSmall,
        h4 = t.bodyLarge,
        h5 = t.bodyLarge,
        h6 = t.bodyLarge
    )
}

/**
 * AI 消息内容 —— 无头像无气泡容器（UX-001 问题 1 二次反馈，ADR-022），直接渲染：
 * - 可折叠思维链（[ChatMessage.thinkingChain]，问题 7；UXR4：仅 [showThinking] 时展示）
 * - Markdown 渲染正文（问题 3）
 * - 可折叠联网搜索来源（[ChatMessage.searchResults]，问题 8）
 * - 可折叠 RAG 引用来源（[ChatMessage.sources]，问题 6）
 *
 * **UXR4 问题 5（ADR-024）+ UXR5 问题 1（ADR-025，F-03 注释漂移修正）**：
 * 0.26.0 的 mikepenz 渲染器**不含** `rememberMarkdownState` / `StreamingMarkdownState`
 * （该 API 自 0.42.0 引入，且 0.28+ 依赖 Compose 1.7+ ABI，项目 BOM 2024.06.00=Compose 1.6.8
 * 无法升级），**且库不支持增量解析**（mikepenz issue #315，每次 content 变化全量重解析——
 * 此前注释"增量重组仅重绘变化节点"与库官方行为矛盾，已修正）。流式渲染缓解依赖：
 * - [AiBubble] 的流式纯文本分支（[isStreaming] 时用 Text 渲染，完成后切换 Markdown）
 * - 自动滚动节流（仅消息数变化滚动，而非每次 content 长度变化）
 * 此限制记录于 ADR-024/025 风险表（完整增量解析需待 Compose 版本升级）。
 *
 * @param message 待渲染的消息
 * @param showThinking 是否展示「深度思考」折叠区（受深度思考开关控制；false 时即使
 *        thinkingChain 非空也不渲染，但协议层仍会回传 reasoning_content，见 ADR-024）
 * @param isStreaming 该消息是否为当前正在流式生成的 AI 回复；true 时用纯文本渲染，
 *        false（回答完成）时切换 markdown 渲染（UXR5 问题 1）
 */
@Composable
private fun AiBubble(message: ChatMessage, showThinking: Boolean, isStreaming: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 思维链折叠区域（UX-001 问题 7）：置于答案上方，默认收起，与最终答案视觉区分。
        // UXR4 问题 1/4/6（ADR-024）：展示受 [showThinking]（深度思考开关）控制——
        // 开关关闭时即使 thinkingChain 有内容（协议层为回传而累积）也不展示思考区。
        if (showThinking) {
            message.thinkingChain?.takeIf { it.isNotBlank() }?.let { chain ->
                CollapsibleThinkingCard(chain)
                Spacer(Modifier.height(10.dp))
            }
        }
        // Markdown 渲染正文（UX-001 问题 3，ADR-022）：
        // - 0.26.0（libs.versions.toml 实际版本；注释曾误写 0.37.0，属注释漂移）
        // - markdownTypography 定制：H1/H2/H3 映射为紧凑字号（标题过大修复）
        // - F-01（guardrail TKN-UX001-GUARDRAIL-001）：渲染前剥离非 http(s) scheme 链接
        //
        // UXR5 问题 1（ADR-024 遗留，关键修复）：markdown-renderer 0.26.0 **不支持增量解析**
        // （mikepenz issue #315，每次 content 变化全量重解析）。流式中间态（未完成的 `##` 标题、
        // 无换行结尾段落）被渲染为字面符号/逐词分行 → 井号残留 + 一行一词。
        // 修复：流式期间（[isStreaming]）用纯文本 Text 渲染（无 markdown 解析开销），
        // 回答完成（isStreaming=false）后一次性 markdown 渲染完整内容。避免中间态闪烁。
        //
        // UXR7 问题 2（根本性根因）：0.26.0 **无表格渲染组件**（考古实证 sources jar 无 Table
        // 文件 → TABLE 节点 fallback 平铺 → 每单元格垂直一行）。渲染前用 [sanitizeMarkdownTables]
        // 把 GFM 表格块转换为 markdown 列表（0.26.0 列表渲染正常）。
        if (message.content.isNotBlank()) {
            if (isStreaming) {
                Text(
                    text = sanitizeMarkdownLinks(message.content),
                    color = PrismText,
                    fontSize = 14.sp,
                    lineHeight = 23.sp
                )
            } else {
                Markdown(
                    content = sanitizeToolCallSyntax(
                        sanitizeMarkdownLinks(sanitizeMarkdownTables(message.content))
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    typography = prismMarkdownTypography()
                )
            }
        }
        // 联网搜索来源折叠区域（UX-001 问题 8）：置于答案下方，链接可点击
        message.searchResults?.takeIf { it.isNotEmpty() }?.let { results ->
            Spacer(Modifier.height(10.dp))
            CollapsibleSearchCard(results)
        }
        // RAG 引用来源折叠区域（UX-001 问题 6）：置于答案下方
        if (message.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            CollapsibleSourcesCard(message.sources)
        }
    }
}

/**
 * UXR9 US-908：Skill/工具调用卡片 —— 会话内嵌工具调用反馈。
 *
 * 展示：工具名（完整命名空间 `skillName__toolName`）+ 参数摘要 + 执行结果片段 + 状态徽标。
 * - 成功（结果文本不含错误标记）→ 绿色「✓ 成功」
 * - 失败（结果以"工具执行出错"/"⚠️"等开头）→ 红色「✕ 失败」
 * - 联网搜索 / 知识库结果继续由 [CollapsibleSearchCard] / 引用来源展示，此处跳过冗余卡片
 *
 * 满足 US-908 AC-1/AC-2：LLM 调用 skill 时界面有明确反馈（工具名 + 参数摘要 + 状态），
 * 与既有「正在调用工具: xxx」执行中指示（[ToolCallIndicator]）互补不冲突。
 */
@Composable
private fun SkillCallCard(
    message: ChatMessage,
    toolArgsById: Map<String, io.prism.ui.model.ToolCallRef>
) {
    val content = message.content.trim()
    // UXR11 U6（ADR-033）：空 content（协议占位）不渲染；**不再跳过 web_search 工具**——
    // 联网搜索也是 Skill 能力（web-research 等 Skill 依赖 web_search__search），
    // 用户反馈"LLM 调 skills 时无界面反馈"，此卡片给出明确的「工具被调用 ✓/✕」反馈，
    // 搜索结果正文仍由下方 CollapsibleSearchCard 完整展示（不重复）。
    if (content.isEmpty()) return
    val isWebSearch = message.toolName == WebSearchLocalToolExecutor.TOOL_SEARCH

    val toolFullName = message.toolName ?: "工具"
    // 状态判定（Q-LOW-7 修复，guardrail TKN-UXR9-GUARDRAIL-002/003）：复用 SkillExecutor 的
    // 单一事实来源 isFailureResult（固定失败前缀集合），而非 UI 自造启发式（此前
    // `contains("失败：")` 会把含该子串的成功结果误判为失败）。
    val isFailed = SkillExecutor.isFailureResult(content)
    val statusColor = if (isFailed) PrismError else PrismMint
    val statusText = if (isFailed) "✕ 失败" else "✓ 成功"

    // 参数摘要：按 toolCallId 从 assistant 消息的 toolCalls 反查 arguments JSON
    val argsSummary = toolArgsById[message.toolCallId]?.arguments
        ?.let { summarizeToolArguments(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel.copy(alpha = 0.5f))
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "◈ $toolFullName",
                color = PrismText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (argsSummary != null) {
            Text(
                text = "参数：$argsSummary",
                color = PrismTextFaint,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            // UXR11 U6：web_search 结果由 CollapsibleSearchCard 完整展示，卡片只给确认文本不重复摘要
            text = when {
                isWebSearch && !isFailed -> "搜索结果见下方来源卡片"
                isWebSearch -> "搜索失败，详见下方"
                isFailed -> content.take(160)
                else -> content.take(120) + if (content.length > 120) "…" else ""
            },
            color = if (isFailed) PrismError else PrismTextFaint,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * UXR9 US-908：将工具参数 JSON 压缩为单行摘要（纯函数，可测）。
 *
 * 去除空白/换行，截断至 [TOOL_ARGS_SUMMARY_MAX_LEN]，避免卡片被长参数撑爆。
 */
internal fun summarizeToolArguments(argumentsJson: String): String {
    val compact = argumentsJson.replace(Regex("\\s+"), "")
    return compact.take(TOOL_ARGS_SUMMARY_MAX_LEN) +
        if (compact.length > TOOL_ARGS_SUMMARY_MAX_LEN) "…" else ""
}

/** UXR9 US-908：工具参数摘要最大字符数。 */
private const val TOOL_ARGS_SUMMARY_MAX_LEN = 80

/**
 * 思维链折叠卡片（UX-001 问题 7，ADR-021）。
 *
 * 对齐 DeepSeek 手机端「深度思考区域 + 生成回答」两段式：思考区域为独立折叠卡片，
 * 置于答案上方；默认收起（节省空间），点击展开查看完整推理过程。
 * 思考内容用灰色小字 + 与答案区背景区分。
 */
@Composable
private fun CollapsibleThinkingCard(chain: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel2.copy(alpha = 0.6f))
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { expanded = !expanded }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = if (expanded) "▾" else "▸", color = PrismTextDim, fontSize = 12.sp)
            Text(text = "深度思考", color = PrismTextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "展开",
                color = PrismTextFaint,
                fontSize = 11.sp
            )
        }
        if (expanded) {
            Text(
                text = chain,
                color = PrismTextDim,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 联网搜索来源折叠卡片（UX-001 问题 8，ADR-021）。
 *
 * 对齐 Perplexity/Kimi 风格引用来源卡片：可折叠区域展示「编号 + 标题 + 域名 + 摘要」，
 * 每条整行可点击，经 [Intent.ACTION_VIEW] 在系统浏览器打开原网页（不内置 WebView 保持轻量）。
 */
@Composable
private fun CollapsibleSearchCard(results: List<SearchResult>) {
    var expanded by remember { mutableStateOf(true) }
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel2.copy(alpha = 0.6f))
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
    ) {
        // 折叠标题行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "🔍", fontSize = 12.sp)
            Text(text = "参考来源（${results.size}）", color = PrismTextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "▾" else "▸",
                color = PrismTextFaint,
                fontSize = 12.sp
            )
        }
        if (expanded) {
            results.forEachIndexed { index, result ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            // 点击跳转外部网站（系统浏览器，ACTION_VIEW）
                            // F-01（guardrail TKN-UX001-GUARDRAIL-001）：纵深防御 ——
                            // 即使 parseSearchResults 已过滤，点击前仍校验 http(s) scheme，
                            // 防止恶意 intent:// 等链接被触发（CWE-601）。
                            onClick = {
                                val link = result.link
                                if (link.startsWith("http://") || link.startsWith("https://")) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                        )
                                    }
                                }
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}. ",
                            color = PrismMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = result.title,
                            color = PrismText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = result.link,
                        color = PrismCyan,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (result.snippet.isNotBlank()) {
                        Text(
                            text = result.snippet,
                            color = PrismTextFaint,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * RAG 引用来源折叠卡片（UX-001 问题 6，ADR-021）。
 *
 * 原 [SourceChip] 在正文下方平铺展示，与 AI 输出文字视觉上易混淆/重叠。
 * 改为独立可折叠区域「引用来源（N）」，展开后逐条展示 [来源N] 文档名 #片段号。
 */
@Composable
private fun CollapsibleSourcesCard(sources: List<io.prism.ui.model.Citation>) {
    var expanded by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PrismMint.copy(alpha = 0.06f))
            .border(1.dp, PrismMint.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "📚", fontSize = 12.sp)
            Text(text = "引用来源（${sources.size}）", color = PrismMint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "▾" else "▸",
                color = PrismMint,
                fontSize = 12.sp
            )
        }
        if (expanded) {
            sources.forEach { citation ->
                val chunkPart = citation.chunkIndex?.let { " #$it" } ?: ""
                Text(
                    text = "[来源${citation.index}] ${citation.documentTitle}$chunkPart",
                    color = PrismMint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
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
    val baseText = if (isRagOn) "正在检索知识库" else "正在思考"
    // UXR11 U7（ADR-033）：真机（MIUI 低帧率/省电模式）下 rememberInfiniteTransition 的
    // 圆点呼吸动画可能不推进（模拟器正常、真机静止——用户实测）。加 LaunchedEffect 驱动的
    // 动态省略号，文字本身轮换 …/。。。/.. ，即使圆点动画不跑，文字变化也提供明确"进行中"反馈。
    var dotIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRagOn) {
        while (true) {
            dotIndex = (dotIndex + 1) % 3
            delay(500)
        }
    }
    val statusText = baseText + when (dotIndex) {
        0 -> "…"
        1 -> "。。"
        else -> ".."
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * 工具调用状态指示（UX-001 问题 7，ADR-022）—— 对齐 Claude Code 工具进度模型。
 *
 * 显示「◈ 正在调用工具: xxx」+ 三点呼吸，让用户明确看到 LLM 正在调用工具
 *（Skills / MCP / 联网搜索），而非静默等待。调用完成后由 [TypingIndicator] 接管。
 */
@Composable
private fun ToolCallIndicator(toolName: String) {
    val transition = rememberInfiniteTransition(label = "toolCall")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel.copy(alpha = 0.5f))
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val alpha = transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600 + i * 130, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "toolDot$i"
                ).value
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(PrismMint.copy(alpha = alpha))
                )
            }
        }
        Text(
            text = "◈ $toolName",
            color = PrismMint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f, fill = false)
        )
        Spacer(Modifier.weight(1f))
        // UXR9 US-908：执行中状态徽标（与完成卡片的「✓ 成功 / ✕ 失败」互补）
        Text(text = "⟳ 执行中", color = PrismMint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 底部输入栏（UXR9 US-907 重构）—— 玻璃胶囊输入框 + 发送钮 + "＋"折叠栏上传。
 *
 * **UXR9 US-907 变更**：移除原独立「🖼 图片」按钮，改为在发送按钮右侧新增「＋」按钮，
 * 点击展开折叠栏显示两种上传方式：相册（图片）/ 文件（PDF/DOCX/XLSX/PPTX 等）。
 * **UXR9 US-906**：发送后键盘自动收起由调用方（onSend 内）执行。
 *
 * **R5（UXR10，ADR-032）变更**：图片/文件选择后**不再立即发送**，而是暂存为待发送
 * 附件草稿（[pendingImageDataUrl] / [pendingFileName]），在输入框上方显示预览卡片
 * （图片缩略图 / 文件名），用户可继续输入需求文本或移除附件，点发送统一发出。
 * 发送按钮启用条件：输入框非空 **或** 存在待发送附件（允许"只发图/文件不配字"）。
 */
@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    /** UXR9 US-907：相册上传入口（系统图片选择器）。 */
    onImagePick: () -> Unit,
    /** UXR9 US-907：文件上传入口（系统文件选择器）。 */
    onFilePick: () -> Unit,
    /** R5：待发送图片草稿（data URL，非空时显示缩略图预览）。 */
    pendingImageDataUrl: String? = null,
    /** R5：待发送文件草稿文件名（非空时显示文件名预览）。 */
    pendingFileName: String? = null,
    /** R5：移除当前待发送附件草稿。 */
    onRemoveAttachment: () -> Unit = {}
) {
    var uploadBarExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // R5：待发送附件草稿预览卡片（图片缩略图 / 文件名 + 移除）。置于折叠栏与输入行之间。
        val hasAttachment = pendingImageDataUrl != null || pendingFileName != null
        AnimatedVisibility(
            visible = hasAttachment,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrismPanel2.copy(alpha = 0.7f))
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val imageDataUrl = pendingImageDataUrl
                if (imageDataUrl != null) {
                    // 图片草稿：解码缩略图预览（remember 缓存，避免重组时重复解码）
                    val bmp = remember(imageDataUrl) {
                        decodeImageDataUrl(imageDataUrl)
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "待发送图片预览",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text(
                        text = "待发送图片",
                        color = PrismTextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = "📄 ${pendingFileName ?: "文档"}",
                        color = PrismTextDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "输入需求后发送",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRemoveAttachment
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", color = PrismTextFaint, fontSize = 14.sp)
                }
            }
        }

        // UXR9 US-907：折叠栏 —— 点击"＋"展开，显示「相册 / 文件」两个上传入口。
        // 展开时挤占输入区上方空间（与键盘不同时使用：选完自动收起 + 收起键盘）。
        AnimatedVisibility(
            visible = uploadBarExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UploadActionChip(label = "🖼 相册", onClick = {
                    uploadBarExpanded = false
                    onImagePick()
                })
                UploadActionChip(label = "📄 文件", onClick = {
                    uploadBarExpanded = false
                    onFilePick()
                })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    // R5：附件存在时也可发送（"只发图/文件不配字"场景）
                    .clickable(enabled = value.isNotBlank() || hasAttachment) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "➤",
                    color = Color.White,
                    fontSize = 17.sp
                )
            }
            // UXR9 US-907："＋"折叠栏开关（发送按钮右侧）—— 相册 / 文件两种上传方式
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (uploadBarExpanded) PrismIndigoSoft.copy(alpha = 0.5f) else PrismPanel,
                        CircleShape
                    )
                    .border(1.dp, if (uploadBarExpanded) PrismIndigo else PrismLine, CircleShape)
                    .clickable { uploadBarExpanded = !uploadBarExpanded },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uploadBarExpanded) "✕" else "＋",
                    color = if (uploadBarExpanded) PrismIndigo else PrismTextDim,
                    fontSize = 18.sp
                )
            }
        }
    }
}

/** UXR9 US-907：折叠栏内的上传方式胶囊按钮（相册 / 文件）。 */
@Composable
private fun UploadActionChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel2.copy(alpha = 0.6f))
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = PrismTextDim, fontSize = 12.sp)
    }
}

/**
 * 跨 App ActivityResult 映射为结果文本（M-1 修复，guardrail TKN-P17-GUARDRAIL-001）。
 *
 * @param resultCode ActivityResult.resultCode（Activity.RESULT_OK / RESULT_CANCELED 等）
 * @param dataString result.data?.dataString（Picker 返回的 Uri）
 * @param intentAction 发起请求的 Intent action（ACTION_VIEW / ACTION_SEND / 等）
 * @return 结果文本（回灌给 LLM）
 */
internal fun mapCrossAppResult(resultCode: Int, dataString: String?, intentAction: String?): String = when (resultCode) {
    Activity.RESULT_OK -> dataString ?: "已完成"
    Activity.RESULT_CANCELED -> {
        if (intentAction == Intent.ACTION_VIEW) "已完成" else "用户取消"
    }
    else -> "未知结果（resultCode=$resultCode）"
}

/**
 * Markdown 链接 scheme 白名单净化（F-01，guardrail TKN-UX001-GUARDRAIL-001）。
 *
 * **安全动机**：LLM 输出（可能受 prompt 注入 / 第三方网页摘要 / 工具结果回灌影响）
 * 中的 `[text](url)` 链接，若 url 为 `intent://` / `file://` / `javascript:` 等
 * 非 http(s) scheme，点击后可能触发非预期 Intent（CWE-116 / CWE-601）。
 * 渲染前将非 http(s) 链接降级为纯文本 `text`（保留可读内容，丢弃危险链接）。
 *
 * **纯函数**（BR-testing-004）：不依赖 Compose/Android 运行时，可在纯 JVM 测试验证。
 *
 * @param markdown 原始 markdown 内容
 * @return 净化后的 markdown（非 http(s) 链接已降级为纯文本）
 */
internal fun sanitizeMarkdownLinks(markdown: String): String {
    // 匹配 `[text](url)`，url 允许任意 scheme（含一级嵌套括号，如 javascript:alert(1)）
    val linkRegex = Regex("""\[([^\]]+)\]\(((?:[^()]|\([^()]*\))*)\)""")
    return linkRegex.replace(markdown) { match ->
        val text = match.groupValues[1]
        val url = match.groupValues[2].trim()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // 安全 http(s) 链接：保留原样（可点击）
            match.value
        } else {
            // 危险/未知 scheme：降级为纯文本（保留文字，丢弃链接）
            text
        }
    }
}

/**
 * UXR11 U4（ADR-033）：净化 LLM 输出中的工具调用语法块（纯函数，可测）。
 *
 * **背景（真机实测）**：LLM（如 kimi-k2.6）在工具回路外/思考阶段会把工具调用意图
 * 写成 XML 风格文本块（`<tool_calls><invoke name="mcp_Fetch__fetch">…</invoke></tool_calls>`），
 * 或 `<|tool_calls|>` / `<|invoke|>` 管道符分隔变体（真机渲染为 `<｜…｜>` 乱码）。
 * mikepenz markdown-renderer **0.26.0** 会把 `<...>` 当 HTML/XML 标签解析，标签内字符
 * 被替换/错乱（真机出现 `<｜｜DSML｜｜tool_calls>` 乱码）。本函数在 Markdown 渲染前做单遍净化：
 * 1. **剥离完整工具调用块**（`<tool_calls>…</tool_calls>`、`<invoke …>…</invoke>` 及其
 *    `|`/`｜` 分隔变体）——这些是 LLM 幻觉输出的工具调用计划，不属于最终答案正文。
 *    用**深度计数状态机**配对开/闭标签（支持嵌套 + 自闭合 `/>`），跨行块整体剥离。
 * 2. **转义残余疑似标签起始 `<`**（仅当 `<` 后紧跟字母、`/` 或管道符 `|`/`｜`，
 *    即 HTML/XML 标签语法），使用户看到 LLM 实际输出文本而非乱码。
 *
 * **不触碰**：
 * - **代码围栏内的所有内容**（``` / ~~~ 状态机）：不剥离工具块、不转义 `<`，由
 *   markdown-renderer 按 code block 原样渲染（guardrail F5）
 * - 普通文本中的比较符（`a < b`，`<` 后是空格，不匹配）
 * - 已在 [sanitizeMarkdownLinks] 处理后的 markdown 链接语法（`](` 不匹配）
 *
 * @param markdown 待渲染的 markdown
 * @return 剥离工具调用块并转义标签起始 `<` 后的 markdown
 */
internal fun sanitizeToolCallSyntax(markdown: String): String {
    // 缓冲式深度状态机单遍处理（guardrail F5）：
    // - 工具块**仅在闭合时剥离**：检测到开标签后把后续行缓冲，闭合标签到达（深度归零）时丢弃缓冲
    //   （= 剥离整块）；若块未闭合就遇到代码围栏 / 输入结束，放弃块判定并 flush 缓冲（正文不丢失）
    // - 代码围栏（``` / ~~~）内的内容原样保留（不剥离、不转义）
    // - 自闭合/单行完整块按普通行转义显示（可见文本，非乱码）
    val out = mutableListOf<String>()
    var pending: MutableList<String>? = null
    var depth = 0
    var inFence = false
    for (line in markdown.split('\n')) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            // 围栏边界：若在块判定中则放弃（flush），围栏内容不参与块剥离
            if (pending != null) { flushPending(pending, out); pending = null; depth = 0 }
            inFence = !inFence
            out.add(line)
            continue
        }
        if (inFence) {
            if (pending != null) { flushPending(pending, out); pending = null; depth = 0 }
            out.add(line)
            continue
        }
        // 围栏外：统计本行非自闭合开标签与闭标签的净深度
        val openMatches = TOOL_BLOCK_OPEN_REGEX.findAll(line).toList()
        val blockOpens = openMatches.count { !it.value.trimEnd().endsWith("/>") }
        val closes = TOOL_BLOCK_CLOSE_REGEX.findAll(line).count()
        val delta = blockOpens - closes
        if (pending == null) {
            if (delta > 0) {
                // 打开工具块：保留开标签前的正文（转义后），仅从开标签起缓冲
                val firstOpen = openMatches.first { !it.value.trimEnd().endsWith("/>") }
                val prefix = line.substring(0, firstOpen.range.first)
                if (prefix.isNotEmpty()) {
                    out.add(prefix.replace(TAG_START_REGEX, "&lt;"))
                }
                pending = mutableListOf(line.substring(firstOpen.range.first))
                depth = delta
            } else {
                // 普通行 / 自闭合 / 单行完整块 → 转义显示
                out.add(line.replace(TAG_START_REGEX, "&lt;"))
            }
        } else {
            // 已在块判定中：缓冲 + 更新深度；深度归零 = 块闭合 → 丢弃缓冲（剥离）
            pending.add(line)
            depth += delta
            if (depth <= 0) {
                pending = null
                depth = 0
            }
        }
    }
    // 输入结束块仍未闭合 → 放弃块判定，flush（正文不丢失）
    if (pending != null) flushPending(pending, out)
    return out.joinToString("\n")
}

/** 放弃块判定时，把缓冲行作为普通文本转义后输出（不丢失正文）。 */
private fun flushPending(pending: List<String>, out: MutableList<String>) {
    out.addAll(pending.map { it.replace(TAG_START_REGEX, "&lt;") })
}

/**
 * 工具调用块开标签（`<tool_calls>` / `<invoke…>` 及其 `|`/`｜`（U+FF5C）/混合分隔变体）。
 * `(?!/)` 负向前瞻排除闭合标签；`[^>\n]{0,8}?` 容忍 `<` 与关键词间的**任意** 0~8 个非 `>` 字符。
 * 用 `[^>\n]`（而非早先的 `[^\w>]`）是因为部分 LLM 会输出 `<｜tool_calls｜>` 等分隔符中夹带
 * 字母数字（如 `<｜tool_calls｜>` 被某些渲染写成 `<｜invoke name｜>`），`\w` 词字符会被
 * `[^\w>]` 拒绝导致开标签漏配而残留乱码。关键词 `tool_calls|invoke` 足够特异，放宽前缀不误伤正文。
 */
private val TOOL_BLOCK_OPEN_REGEX = Regex(
    """<(?!/)[^>\n]{0,8}?(?:tool_calls|invoke)[^>\n]*>""",
    RegexOption.IGNORE_CASE
)

/**
 * 工具调用块闭标签（`</tool_calls>` / `</invoke>` 及其分隔变体）。
 */
private val TOOL_BLOCK_CLOSE_REGEX = Regex(
    """</[^>\n]{0,8}?(?:tool_calls|invoke)[^>\n]{0,6}?>""",
    RegexOption.IGNORE_CASE
)

/**
 * 疑似 HTML/XML 标签或管道符分隔标签的起始 `<`（后跟字母 / 斜杠 / `|` / 全角 `｜` / `!` / `?`）。
 * 不触碰：比较符（`a < b`，`<` 后空格）、数字比较（`速度<30`）、换行后的 `<`。
 */
private val TAG_START_REGEX = Regex("<(?=[a-zA-Z/|｜!?])")

/**
 * UXR7 问题 2（根本性根因，UXR7-R2 增强）：将 markdown 表格块转换为 markdown 列表（纯函数，可测）。
 *
 * **背景**：mikepenz markdown-renderer **0.26.0 无表格渲染组件**（考古实证：sources jar
 * 无 Table 文件，Markdown.kt 的 when(node.type) 无 TABLE 分支 → fallback 递归平铺 →
 * 每个单元格垂直一行，用户看到"每单元格一行"且无管道符）。且 0.28+ 受 Compose 1.6.8
 * ABI 限制无法升级（ADR-025 已确认；网络调研确认 0.27.0 起依赖 Compose 1.7.0，0.28.0
 * 才引入表格 PR #257，0.26.0 约束下无任何"支持表格且兼容"的版本）。
 *
 * **修复**：渲染前把表格块转换为 markdown 列表（表头加粗、每数据行一个列表项、
 * 字段用 ` | ` 分隔）。0.26.0 的列表渲染正常 → 用户看到可读的逐行列表（每行一个项目）。
 *
 * **识别规则（UXR7-R2 增强）**：
 * 1. 标准 GFM 表格：以 `|` 开头/结尾的连续行，且第 2 行为分隔行（`|---|`）。
 * 2. **紧凑表格（新增）**：连续 ≥2 行 `|` 行、无分隔行、但每行含 ≥2 个 `|`（≥2 列）
 *    ——LLM 在 MCP 工具场景常输出此类（不写分隔行），0.26.0 同样平铺，此前漏检。
 *
 * 两种形态均转换为列表。代码围栏内的 `|` 行不转换（guardrail M-2）。
 *
 * @param markdown 待渲染的 markdown 文本
 * @return 表格块转换为列表后的 markdown
 */
internal fun sanitizeMarkdownTables(markdown: String): String {
    val tableLine = Regex("""^\s*\|.*\|\s*$""")
    val separatorLine = Regex("""^\s*\|[\s:\-|]+\|\s*$""")
    val codeFence = Regex("""^\s*```""")
    val lines = markdown.split('\n')
    val result = mutableListOf<String>()
    var i = 0
    var inCodeFence = false
    while (i < lines.size) {
        val line = lines[i]
        if (codeFence.matches(line)) {
            // guardrail M-2（TKN-UXR7-GUARDRAIL-001）：感知围栏代码块，块内 `|` 行不当作表格
            inCodeFence = !inCodeFence
            result.add(line)
            i++
        } else if (!inCodeFence && tableLine.matches(line)) {
            // 收集连续表格行
            val tableRows = mutableListOf(line)
            var j = i + 1
            while (j < lines.size && tableLine.matches(lines[j])) {
                tableRows.add(lines[j])
                j++
            }
            // 判定表格块：
            // - 标准表格：第 2 行为分隔行
            // - 紧凑表格（UXR7-R2）：无分隔行，但行数 ≥2 且每行含 ≥2 个 `|`（≥2 列）
            val hasSeparator = tableRows.size >= 2 && separatorLine.matches(tableRows[1])
            val isCompact = !hasSeparator && tableRows.size >= 2 &&
                tableRows.all { row -> row.count { it == '|' } >= 2 }
            if (hasSeparator || isCompact) {
                result.addAll(convertTableToLines(tableRows))
                i = j
            } else {
                result.add(line)
                i++
            }
        } else {
            result.add(line)
            i++
        }
    }
    return result.joinToString("\n")
}

/** 将表格行转换为 markdown 列表行（表头加粗 + 数据行列表项）。 */
internal fun convertTableToLines(tableRows: List<String>): List<String> {
    if (tableRows.size < 1) return tableRows
    val header = splitTableCells(tableRows[0])
    // 标准表格第 2 行为分隔行 → 数据从第 3 行起；紧凑表格无分隔行 → 数据从第 2 行起
    val dataStart = if (tableRows.size >= 2 && Regex("""^\s*\|[\s:\-|]+\|\s*$""").matches(tableRows[1])) 2 else 1
    val dataRows = tableRows.drop(dataStart)
    val out = mutableListOf<String>()
    out.add("**${header.joinToString(" | ")}**")
    dataRows.forEach { row ->
        val cells = splitTableCells(row)
        // 仅保留与表头等长或更多的单元格（防列数错位丢内容）
        out.add("- ${cells.joinToString(" | ")}")
    }
    return out
}

/**
 * 按 `|` 切分表格行单元格（guardrail M-2，TKN-UXR7-GUARDRAIL-001）。
 *
 * 处理 `\|` 转义管道符：切分前把 `\|` 保护为占位符，切分后还原为字面 `|`，
 * 避免 `\|` 被误当作单元格分隔符破坏列结构。
 */
internal fun splitTableCells(raw: String): List<String> {
    val protected = raw.trim().trim('|').replace("\\|", "\u0000")
    return protected.split('|').map { it.trim().replace("\u0000", "|") }
}
