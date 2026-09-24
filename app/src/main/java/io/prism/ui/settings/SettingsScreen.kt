package io.prism.ui.settings

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.prism.data.ProviderConfig
import io.prism.data.ProviderPresets
import io.prism.config.ToolApprovalMode
import io.prism.tier.PerformanceTier
import io.prism.tier.TierConfigRepository
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismGlassCard
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismSegmented
import io.prism.ui.components.PrismSwitch
import io.prism.ui.components.PrismTopBar
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismWarning
import java.util.Locale

/** v1 US-201：手机操控无障碍服务状态轮询间隔（毫秒）。 */
private const val PHONE_CONTROL_POLL_MS = 1_000L

/**
 * 设置屏幕 —— 深空玻璃肌理（设计规范 v0.4 第 8.4 节）。
 *
 * 分组：模型与端点 / 设备档位 / 手机操控（LLM 操控手机）/ 识图 / 搜索增强 / 关于。
 * Provider 配置与 API Key 两行接入 [SettingsViewModel] 数据层：
 * - Provider 配置 → 已配置列表弹层 + 详情编辑弹层（Base URL / 模型 / 激活 / 删除 / 从预设添加）
 * - API Key → 各 Provider 的 Key 加密读写（掩码 + Keystore 加密）
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    // v1 批次16（US-1603）：「生物识别解锁」M0 起 UI 占位从未实现，已整行移除（连同本地 state）。
    var tierSheetVisible by remember { mutableStateOf(false) }
    var providerListVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var thinkingSheetVisible by remember { mutableStateOf(false) }
    var toolApprovalSheetVisible by remember { mutableStateOf(false) }
    // UXR8 N1（ADR-030）：用户规则编辑弹层（「关于我」+「如何回答」双字段）
    var userRulesSheetVisible by remember { mutableStateOf(false) }
    // v1 批次15（US-1501/1502/1507）：搜索增强配置弹层（智谱/Tavily Key + SearXNG 端点）
    var searchEnhancementSheetVisible by remember { mutableStateOf(false) }

    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    // 问题 8a（ADR-020）：深度思考开关 + 思考强度（DataStore 持久化）
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val reasoningEffort by viewModel.reasoningEffort.collectAsState()
    // UXR3 问题 10（ADR-023）：工具审批模式（DataStore 持久化）
    val toolApprovalMode by viewModel.toolApprovalMode.collectAsState()
    // UXR8 N1（ADR-030）：用户规则（「关于我」+「如何回答」双字段，DataStore 持久化）
    val userRulesAboutMe by viewModel.userRulesAboutMe.collectAsState()
    val userRulesHowToRespond by viewModel.userRulesHowToRespond.collectAsState()
    // v1 US-301（方案 B 识图）：视觉旁路授权 + 自动开关
    val visionConsent by viewModel.visionConsent.collectAsState()
    val visionAutoBypass by viewModel.visionAutoBypass.collectAsState()
    // v1 真机反馈（Issue 4）：云端视觉旁路需一个被标记为视觉回退的 Provider；
    // 未配置时向用户明示（用户此前误以为"在设置里没有显示"、功能不可用）。
    val visionFallbackName = providers.firstOrNull { it.isVisionFallback }?.name
    // v1 US-201（LLM 操控手机）：无障碍服务连接状态（设置页引导）
    val phoneControlConnected by viewModel.phoneControlConnected.collectAsState()
    // v1 真机二次修复（Issue 4b）：手机操控高危动作（发送/删除/拨号）确认策略
    val highRiskApprovalMode by viewModel.highRiskApprovalMode.collectAsState()
    // v1 批次15（US-1501/1502/1507）：搜索增强（智谱/Tavily Key + SearXNG 端点 + WebView 抓取开关）
    val zhipuKeyConfigured by viewModel.zhipuKeyConfigured.collectAsState()
    val tavilyKeyConfigured by viewModel.tavilyKeyConfigured.collectAsState()
    val searxngSettings by viewModel.searxngSettings.collectAsState()
    val preferredEngine by viewModel.preferredEngine.collectAsState()
    val webviewFetchEnabled by viewModel.webviewFetchEnabled.collectAsState()

    // M7 Phase C（US-042）：档位 UI 由 TierViewModel 驱动（替代原 PerfTier 本地 mock state）
    val tierViewModel: TierViewModel = viewModel(factory = TierViewModel.Factory)
    val tierOverride by tierViewModel.override.collectAsState()

    // v1 US-201（LLM 操控手机）：打开系统无障碍设置（用途声明见下方 SetRow 文案）
    val context = LocalContext.current
    val openAccessibilitySettings = {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    // 批次20（BG-005，ADR-045）：悬浮窗授权状态与跳转（后台操控后台启动 Activity 的官方豁免条件）。
    // guardrail P3-4：状态为可刷新 State（组合期快照会过期），在下方既有轮询循环中一并刷新。
    var canDrawOverlays by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val openOverlaySettings = {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    // 批次21（SZ-004，ADR-046）：Shizuku 四态（未安装/已装未激活/未授权/就绪）——轮询刷新；未授权点击请求授权。
    // 真机修复 R3：用户装了 Shizuku App 但未激活 server 时，原三态误报"未安装"——拆分 NOT_RUNNING 并给激活指引。
    var shizukuState by remember { mutableStateOf(io.prism.phonecontrol.bg.ShizukuShell.currentState()) }
    val shizukuSubtitle = when (shizukuState) {
        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_INSTALLED ->
            "未安装 Shizuku（免费开源）· 完整后台化需安装并激活：shizuku.rikka.app"
        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_RUNNING ->
            "已安装未激活 · 请打开 Shizuku App 按引导激活（Android 11+ 可用无线调试配对，无需电脑）"
        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_AUTHORIZED ->
            "已激活未授权 · 点击在弹窗中允许 Prism 使用 Shizuku"
        io.prism.phonecontrol.bg.ShizukuShell.State.READY ->
            "Shizuku 就绪 · 后台模式全能力（手势/滑动/返回均可用）"
    }

    // v1 US-201：UI 侧轮询无障碍服务连接状态（用户在系统设置开关后返回本页自动刷新引导）。
    // VM 侧无循环（避免 JVM 单测 runTest 调度器挂起），轮询放 Composable LaunchedEffect。
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshPhoneControlStatus()
            // 批次20（guardrail P3-4）：同步刷新悬浮窗授权状态（用户从系统授权页返回后生效）
            canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
            // 批次21（SZ-004）：同步刷新 Shizuku 三态
            shizukuState = io.prism.phonecontrol.bg.ShizukuShell.currentState()
            kotlinx.coroutines.delay(PHONE_CONTROL_POLL_MS)
        }
    }

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                PrismTopBar(title = "设置", subtitle = "偏好 · 安全 · 设备")
            }
            item { SetSection("模型与端点") }
            item {
                SetRow(
                    icon = "◈",
                    iconColor = PrismIndigo,
                    title = "Provider 配置",
                    subtitle = activeProvider?.let { "已激活：${it.name}" } ?: "OpenAI / Anthropic / Ollama …",
                    onClick = { providerListVisible = true }
                )
            }
            item {
                SetRow(
                    icon = "⌁",
                    iconColor = PrismMint,
                    title = "API Key",
                    subtitle = "Keystore 加密 · ${if (activeProvider != null) "已配置" else "未配置"}",
                    onClick = { apiKeyVisible = true }
                )
            }
            // 问题 8a（ADR-020）：深度思考设置（开关 + 强度选择）
            item {
                SetRow(
                    icon = "◔",
                    iconColor = PrismIndigo,
                    title = "深度思考",
                    subtitle = if (thinkingEnabled) {
                        "已开启 · 强度 ${effortLabel(reasoningEffort)}"
                    } else {
                        "关闭 · 开启后模型先推理再回答"
                    },
                    trailing = {
                        PrismSwitch(checked = thinkingEnabled, onCheckedChange = { viewModel.setThinkingEnabled(it) })
                    },
                    onClick = { thinkingSheetVisible = true }
                )
            }
            // UXR8 N1（ADR-030）：用户规则（类 ChatGPT Custom Instructions 双字段）
            item { SetSection("用户规则") }
            item {
                SetRow(
                    icon = "✎",
                    iconColor = Color(0xFF9BB8FF),
                    title = "AI 行为偏好",
                    subtitle = userRulesSubtitle(userRulesAboutMe, userRulesHowToRespond),
                    onClick = { userRulesSheetVisible = true }
                )
            }
            // v1 批次16（US-1603）：移除「生物识别解锁」占位（从未实现，M0 起 UI 占位造成
            // 「功能存在但无效」的误导）；「隐私与安全」区块仅此一项，一并移除。
            // v1 批次16（US-1604）：安全拦截提示并入「高危操作」行（同属安全拦截功能，
            // 拆成两行造成认知割裂——见下方手机操控区块）。
            item { SetSection("识图（纯文本模型）") }
            item {
                SetRow(
                    icon = "👁",
                    iconColor = Color(0xFFFFB84D),
                    title = "云端视觉旁路",
                    subtitle = buildString {
                        if (visionConsent) append("已授权") else append("未授权")
                        val vp = visionFallbackName
                        append(if (vp != null) " · 视觉模型：$vp" else " · 需配置并标记一个视觉 Provider")
                    },
                    trailing = {
                        PrismSwitch(checked = visionConsent, onCheckedChange = { viewModel.setVisionConsent(it) })
                    },
                    onClick = { viewModel.setVisionConsent(!visionConsent) }
                )
            }
            item {
                SetRow(
                    icon = "◎",
                    iconColor = PrismIndigo,
                    title = "自动旁路",
                    subtitle = if (visionAutoBypass) "开启 · 图片自动转为描述/文字后回答" else "关闭",
                    trailing = {
                        PrismSwitch(checked = visionAutoBypass, onCheckedChange = { viewModel.setVisionAutoBypass(it) })
                    }
                )
            }
            // v1 US-201（LLM 操控手机）：无障碍服务引导 + 用途声明（prominent disclosure）
            item { SetSection("手机操控（LLM 操控手机）") }
            item {
                SetRow(
                    icon = "🖐",
                    iconColor = PrismMint,
                    title = "无障碍服务",
                    subtitle = if (phoneControlConnected) {
                        "已开启 · AI 可读取屏幕并执行点击/滑动/输入"
                    } else {
                        "未开启 · 在系统「设置 → 无障碍」中开启"
                    },
                    trailing = {
                        PrismButton(
                            text = if (phoneControlConnected) "已开启" else "去开启",
                            variant = if (phoneControlConnected) PrismButtonVariant.Ghost else PrismButtonVariant.Primary,
                            onClick = openAccessibilitySettings
                        )
                    }
                )
            }
            // v1 批次16（US-1604）：安全拦截提示并入高危操作行——两者同属安全拦截功能：
            // 支付/密码/验证码为不可绕过硬拦截（固定行为），发送/删除/拨号策略由本行循环切换。
            item {
                SetRow(
                    icon = "🛡",
                    iconColor = Color(0xFFFF7A6B),
                    title = "高危操作拦截",
                    subtitle = "支付/密码/验证码一律硬拦截 · 发送/删除/拨号：" +
                        highRiskApprovalSubtitle(highRiskApprovalMode),
                    onClick = { viewModel.setHighRiskApprovalMode(nextHighRiskApprovalMode(highRiskApprovalMode)) }
                )
            }
            // 批次20（BG-005，ADR-045）：后台模式说明 + 悬浮窗权限引导入口（点击跳系统授权页）
            item {
                SetRow(
                    icon = "🪟",
                    iconColor = PrismIndigo,
                    title = "后台操控（虚拟屏）",
                    subtitle = "聊天页开启「后台操控」后，AI 把应用启动到后台虚拟屏执行，你可同时使用手机。" +
                        if (canDrawOverlays) "悬浮窗权限：已授权" else "悬浮窗权限：未授权（后台启动需要，点此去授权）",
                    onClick = {
                        if (!canDrawOverlays) {
                            openOverlaySettings()
                        }
                    }
                )
            }
            // 批次21（SZ-004，ADR-046）：Shizuku 四态行（未装→下载指引；已装未激活→打开 Shizuku App；
            // 未授权→点击授权；就绪→提示全能力）
            item {
                SetRow(
                    icon = "⚡",
                    iconColor = PrismIndigo,
                    title = "Shizuku（后台化增强）",
                    subtitle = shizukuSubtitle,
                    onClick = {
                        when (shizukuState) {
                            io.prism.phonecontrol.bg.ShizukuShell.State.NOT_AUTHORIZED ->
                                io.prism.phonecontrol.bg.ShizukuShell.requestPermission(2026)
                            io.prism.phonecontrol.bg.ShizukuShell.State.NOT_RUNNING -> {
                                // 已装未激活：直接打开 Shizuku App（用户按其引导完成无线调试配对/adb 激活）
                                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                if (launch != null) {
                                    context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            io.prism.phonecontrol.bg.ShizukuShell.State.NOT_INSTALLED ->
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://shizuku.rikka.app/download/")
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            io.prism.phonecontrol.bg.ShizukuShell.State.READY -> Unit
                        }
                    }
                )
            }
            // UXR3 问题 10（ADR-023）：工具审批模式（手动审批 / 自动审批 / 禁用）
            item {
                SetRow(
                    icon = "⚙",
                    iconColor = PrismIndigo,
                    title = "工具权限",
                    subtitle = toolApprovalSubtitle(toolApprovalMode),
                    onClick = { toolApprovalSheetVisible = true }
                )
            }
            // UXR8 N3（ADR-030）：图片直传隐私说明（方案 A 无云端旁路，图片直达用户自配端点）
            item {
                SetRow(
                    icon = "🖼",
                    iconColor = Color(0xFF9BB8FF),
                    title = "图片消息",
                    subtitle = "图片直传你配置的模型端点（无云端旁路），不支持视觉的模型会提示降级"
                )
            }
            // 批次22 修复轮五（2026-09-07）：语音通话热词（同音字偏置，用户词库）
            item { SetSection("语音通话") }
            item { VoiceHotwordsEditor() }
            // 批次23-A（ADR-048，A4）：语音引擎（ASR/TTS 云端双轨手选 + Key 配置 + 连通性自检）
            item { VoiceEngineSection(viewModel) }
            // 批次23-C（ADR-048）：角色包（导入 / 列表切换 / 删除——通用模板，不含具体角色内容）
            item { RolePackSection(viewModel) }
            // 批次24（ADR-051）：桌宠（开关 / 形象导入 / 尺寸 / 待机 / 权限引导）
            item { SetSection("桌宠") }
            item { PetSection(viewModel) }
            // v1 批次15（US-1501/1502/1507）：搜索增强（智谱 / Tavily / SearXNG）
            item { SetSection("搜索增强") }
            item {
                SetRow(
                    icon = "⌕",
                    iconColor = PrismMint,
                    title = "搜索引擎增强",
                    subtitle = searchEnhancementSubtitle(zhipuKeyConfigured, tavilyKeyConfigured, searxngSettings != null),
                    onClick = { searchEnhancementSheetVisible = true }
                )
            }
            item {
                SetRow(
                    icon = "◍",
                    iconColor = PrismIndigo,
                    title = "WebView 渲染抓取",
                    subtitle = "增强被反爬拦截页面的抓取成功率；仅访问公网 https 页面",
                    trailing = {
                        PrismSwitch(
                            checked = webviewFetchEnabled,
                            onCheckedChange = { viewModel.setWebviewFetchEnabled(it) }
                        )
                    }
                )
            }
            item { SetSection("设备档位") }
            item {
                SetRow(
                    icon = "▣",
                    iconColor = Color(0xFFFF9A5C),
                    title = "性能档位",
                    subtitle = tierSubtitle(
                        override = tierOverride,
                        detectedTier = tierViewModel.detectedTier,
                        currentTier = tierViewModel.currentTier,
                        totalRamBytes = tierViewModel.totalRamBytes
                    ),
                    onClick = { tierSheetVisible = true }
                )
            }
            item { SetSection("关于") }
            item {
                SetRow(
                    icon = "◉",
                    iconColor = PrismTextDim,
                    title = "关于 Prism",
                    subtitle = "v0.1 · 零后端"
                )
            }
        }

        // Provider 列表弹层
        PrismSheetHost(visible = providerListVisible, onDismiss = { providerListVisible = false }) {
            ProviderListSheet(
                providers = providers,
                activeId = activeProvider?.id,
                onSelect = { viewModel.selectProvider(it) },
                onCreateFromPreset = { viewModel.createFromPreset(it) },
                onNewCustom = { viewModel.newCustomProvider() },
                onClose = { providerListVisible = false }
            )
        }
        // Provider 详情编辑弹层
        PrismSheetHost(visible = selectedProvider != null, onDismiss = { viewModel.selectProvider(null) }) {
            selectedProvider?.let { config ->
                ProviderEditSheet(config, viewModel)
            }
        }
        // API Key 管理弹层
        PrismSheetHost(visible = apiKeyVisible, onDismiss = { apiKeyVisible = false }) {
            ApiKeySheet(providers, viewModel)
        }
        // M7 Phase C（US-042）：性能档位选择弹层
        PrismSheetHost(visible = tierSheetVisible, onDismiss = { tierSheetVisible = false }) {
            TierSheet(
                override = tierOverride,
                detectedTier = tierViewModel.detectedTier,
                currentTier = tierViewModel.currentTier,
                totalRamBytes = tierViewModel.totalRamBytes,
                onSelect = { value ->
                    if (value == TierConfigRepository.OVERRIDE_AUTO) {
                        tierViewModel.clearOverride()
                    } else {
                        tierViewModel.setOverride(value)
                    }
                    tierSheetVisible = false
                },
                onClose = { tierSheetVisible = false }
            )
        }
        // 问题 8a（ADR-020）：深度思考设置弹层（开关 + 强度选择）
        PrismSheetHost(visible = thinkingSheetVisible, onDismiss = { thinkingSheetVisible = false }) {
            ThinkingSheet(
                enabled = thinkingEnabled,
                effort = reasoningEffort,
                onToggle = { viewModel.setThinkingEnabled(it) },
                onSelectEffort = { effort ->
                    viewModel.setReasoningEffort(effort)
                    thinkingSheetVisible = false
                },
                onClose = { thinkingSheetVisible = false }
            )
        }
        // UXR3 问题 10（ADR-023）：工具审批模式弹层（手动 / 自动 / 禁用）
        PrismSheetHost(visible = toolApprovalSheetVisible, onDismiss = { toolApprovalSheetVisible = false }) {
            ToolApprovalSheet(
                current = toolApprovalMode,
                onSelect = { mode ->
                    viewModel.setToolApprovalMode(mode)
                    toolApprovalSheetVisible = false
                },
                onClose = { toolApprovalSheetVisible = false }
            )
        }
        // UXR8 N1（ADR-030）：用户规则编辑弹层（「关于我」+「如何回答」双字段）
        PrismSheetHost(visible = userRulesSheetVisible, onDismiss = { userRulesSheetVisible = false }) {
            UserRulesSheet(
                aboutMe = userRulesAboutMe,
                howToRespond = userRulesHowToRespond,
                onSave = { about, respond ->
                    viewModel.saveUserRules(about, respond)
                    userRulesSheetVisible = false
                },
                onClose = { userRulesSheetVisible = false }
            )
        }
        // v1 批次15（US-1501/1502/1507）：搜索增强配置弹层（智谱/Tavily Key + SearXNG 端点）
        PrismSheetHost(visible = searchEnhancementSheetVisible, onDismiss = { searchEnhancementSheetVisible = false }) {
            SearchEnhancementSheet(
                searxngEndpoint = searxngSettings?.endpoint.orEmpty(),
                searxngUsername = searxngSettings?.username.orEmpty(),
                searxngPassword = searxngSettings?.password.orEmpty(),
                preferredEngine = preferredEngine,
                viewModel = viewModel,
                onClose = { searchEnhancementSheetVisible = false }
            )
        }
    }
}

/**
 * 搜索增强行副标题（v1 批次15，US-1501/1502/1507）。
 *
 * 无任何配置 → "未配置 · 配置 Key/端点提升搜索命中率"；有配置 → 列出已配置引擎。
 */
private fun searchEnhancementSubtitle(zhipu: Boolean, tavily: Boolean, searxng: Boolean): String {
    val configured = listOfNotNull(
        "智谱".takeIf { zhipu },
        "Tavily".takeIf { tavily },
        "SearXNG".takeIf { searxng }
    )
    return if (configured.isEmpty()) {
        "未配置 · 配置 Key/端点提升搜索命中率"
    } else {
        "已配置：${configured.joinToString("、")}"
    }
}

/**
 * 用户规则行副标题（UXR8 N1，ADR-030）。
 *
 * 两字段均空 → "未配置 · 告诉我你的背景与回复偏好"；仅配置一个 → 显示已配置字数；
 * 两个都配置 → "关于我 ✓ · 如何回答 ✓"。
 */
private fun userRulesSubtitle(aboutMe: String, howToRespond: String): String = when {
    aboutMe.isBlank() && howToRespond.isBlank() -> "未配置 · 告诉我你的背景与回复偏好"
    aboutMe.isBlank() -> "仅「如何回答」已配置 · 最高优先级约束 LLM"
    howToRespond.isBlank() -> "仅「关于我」已配置 · 最高优先级约束 LLM"
    else -> "「关于我」+「如何回答」已配置 · 最高优先级约束 LLM"
}

/**
 * 格式化档位行副标题（US-042）。
 *
 * - 覆盖为 AUTO：显示「自动检测 · {RAM}GB · 当前 {tier}」
 * - 覆盖为手动：显示「手动覆盖 · {tier}」
 *
 * @param override 用户覆盖值（[TierConfigRepository.OVERRIDE_AUTO] 或档位枚举名）
 * @param detectedTier RAM 检测到的档位
 * @param currentTier 当前生效档位（运行中，重启后反映新覆盖）
 * @param totalRamBytes 设备 RAM 总量（字节）
 */
private fun tierSubtitle(
    override: String,
    detectedTier: PerformanceTier,
    currentTier: PerformanceTier,
    totalRamBytes: Long
): String {
    val ramGb = totalRamBytes.toDouble() / (1024L * 1024L * 1024L)
    val ramStr = String.format(Locale.US, "%.1f", ramGb) + "GB"
    return if (override == TierConfigRepository.OVERRIDE_AUTO) {
        "自动检测 · $ramStr · ${currentTier.label}"
    } else {
        "手动覆盖 · ${currentTier.label}"
    }
}

/** 档位中文标签（仅 UI 展示，与 [PerformanceTier] 枚举名解耦）。 */
private val PerformanceTier.label: String
    get() = when (this) {
        PerformanceTier.FULL -> "全功能"
        PerformanceTier.STANDARD -> "标准"
        PerformanceTier.MINIMAL -> "极简"
        PerformanceTier.CHAT_ONLY -> "仅聊天"
    }

/** 思考强度中文标签（问题 8a，仅 UI 展示，与 [ThinkingConfigRepository] 值解耦）。 */
private fun effortLabel(effort: String): String = when (effort) {
    "low" -> "轻"
    "high" -> "标准"
    "max" -> "最强"
    else -> effort
}

/** 工具审批模式中文标签（UXR3 问题 10，仅 UI 展示，与 [ToolApprovalMode] 枚举值解耦）。 */
private fun ToolApprovalMode.label(): String = when (this) {
    ToolApprovalMode.MANUAL -> "手动审批"
    ToolApprovalMode.AUTO -> "自动审批"
    ToolApprovalMode.DISABLED -> "禁用工具"
}

/** 工具权限设置行副标题（UXR3 问题 10）。 */
private fun toolApprovalSubtitle(mode: ToolApprovalMode): String = when (mode) {
    ToolApprovalMode.MANUAL -> "每次调用工具需确认（默认）"
    ToolApprovalMode.AUTO -> "所有工具直接放行"
    ToolApprovalMode.DISABLED -> "不向 AI 提供任何工具"
}

/** 手机操控高危动作（发送/删除/拨号）策略副标题（v1 真机二次修复 Issue 4b）。 */
private fun highRiskApprovalSubtitle(mode: io.prism.config.HighRiskApprovalMode): String = when (mode) {
    io.prism.config.HighRiskApprovalMode.BLOCK -> "全部拦截 · 发送/删除/拨号一律不执行（最安全）"
    io.prism.config.HighRiskApprovalMode.ALLOW -> "全部放行 · 发送/删除/拨号直接执行（最便捷）"
    io.prism.config.HighRiskApprovalMode.ASK -> "逐次询问 · 每次发送/删除/拨号前向你确认（默认）"
}

/** 高危操作策略循环顺序：BLOCK → ALLOW → ASK → BLOCK…（点击切换）。 */
private fun nextHighRiskApprovalMode(mode: io.prism.config.HighRiskApprovalMode): io.prism.config.HighRiskApprovalMode =
    when (mode) {
        io.prism.config.HighRiskApprovalMode.BLOCK -> io.prism.config.HighRiskApprovalMode.ALLOW
        io.prism.config.HighRiskApprovalMode.ALLOW -> io.prism.config.HighRiskApprovalMode.ASK
        io.prism.config.HighRiskApprovalMode.ASK -> io.prism.config.HighRiskApprovalMode.BLOCK
    }

/**
 * 工具审批模式弹层（UXR3 问题 10，ADR-023）—— 三选一。
 *
 * - MANUAL（手动审批）：LLM 每次调用工具都询问用户（白名单只读工具免审批）
 * - AUTO（自动审批）：所有工具直接放行，不需用户审核
 * - DISABLED（禁用）：不向 LLM 注入任何工具，AI 无法调用工具
 *
 * 运行时即时生效（无需重启）：ConversationViewModel 与 SkillExecutor 均从配置仓库实时读取。
 */
@Composable
private fun ToolApprovalSheet(
    current: ToolApprovalMode,
    onSelect: (ToolApprovalMode) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "工具权限",
        subtitle = "控制 AI 调用工具（搜索 / 文件 / MCP）的权限策略"
    ) {
        ToolApprovalOptionRow(
            label = "手动审批",
            description = "每次调用工具都需你确认（推荐）",
            selected = current == ToolApprovalMode.MANUAL,
            onClick = { onSelect(ToolApprovalMode.MANUAL) }
        )
        Spacer(Modifier.height(8.dp))
        ToolApprovalOptionRow(
            label = "自动审批",
            description = "所有工具直接放行，不需审核",
            selected = current == ToolApprovalMode.AUTO,
            onClick = { onSelect(ToolApprovalMode.AUTO) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "⚠️ 自动审批下 AI 可直接读写已授权文件、打开应用、联网抓取，请谨慎开启。",
            color = PrismWarning,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        ToolApprovalOptionRow(
            label = "禁用工具",
            description = "不向 AI 提供任何工具，仅普通对话",
            selected = current == ToolApprovalMode.DISABLED,
            onClick = { onSelect(ToolApprovalMode.DISABLED) }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "切换后即时生效，无需重启。",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** 工具审批模式选项行（单选样式，选中态高亮，复用 [TierOptionRow] 视觉）。 */
@Composable
private fun ToolApprovalOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
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
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "⚙",
                color = if (selected) PrismMint else PrismTextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
        }
    }
}

/**
 * 深度思考设置弹层（问题 8a，ADR-020）—— 开关 + 思考强度选择。
 *
 * **强度说明**（DeepSeek API 文档）：
 * - low：轻度推理，适合简单任务、快速响应
 * - high：标准推理（默认），效果与延迟平衡
 * - max：最强推理，复杂 Agent 场景推荐（推理 token 占比高，可能压缩正文空间）
 *
 * **兼容性提示**：thinking/reasoning_effort 为 DeepSeek 专有参数，不兼容端点可能返回 400。
 * 弹层内明示该限制，避免用户误用后困惑。
 *
 * @param enabled 当前深度思考开关状态
 * @param effort 当前思考强度（low/high/max）
 * @param onToggle 开关切换回调
 * @param onSelectEffort 强度选择回调（选中后由调用方关闭弹层）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun ThinkingSheet(
    enabled: Boolean,
    effort: String,
    onToggle: (Boolean) -> Unit,
    onSelectEffort: (String) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "深度思考",
        subtitle = "模型先推理再回答 · 运行时即时生效"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "启用深度思考", color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PrismSwitch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "开启后请求携带 thinking + reasoning_effort 参数（DeepSeek 等支持思考模式的端点）。" +
                "不兼容的端点可能返回 400，请确认你的 Provider 支持。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(text = "思考强度", color = PrismTextDim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = "low",
            description = "轻度推理 · 快速响应",
            selected = effort == "low",
            onClick = { onSelectEffort("low") }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = "high",
            description = "标准推理 · 推荐",
            selected = effort == "high",
            onClick = { onSelectEffort("high") }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = "max",
            description = "最强推理 · 复杂任务",
            selected = effort == "max",
            onClick = { onSelectEffort("max") }
        )
        Spacer(Modifier.height(16.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 性能档位选择弹层（US-042）。
 *
 * 列出 5 个选项（自动 + 四档），每个选项显示档位名 + RAM 范围 + 功能简述。
 * 底部固定提示「修改后需重启 App 生效」（ADR-017 4.4：覆盖仅持久化，运行档位需重启才反映）。
 *
 * @param override 当前用户覆盖值（用于标记选中项）
 * @param detectedTier RAM 检测到的档位（用于「自动」选项描述）
 * @param currentTier 当前生效档位（运行中，弹层顶部展示）
 * @param totalRamBytes 设备 RAM 总量（字节，用于弹层副标题展示）
 * @param onSelect 选项点击回调，参数为覆盖值（[TierConfigRepository.OVERRIDE_AUTO] 或档位枚举名）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun TierSheet(
    override: String,
    detectedTier: PerformanceTier,
    currentTier: PerformanceTier,
    totalRamBytes: Long,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    val ramGb = totalRamBytes.toDouble() / (1024L * 1024L * 1024L)
    val ramStr = String.format(Locale.US, "%.1f", ramGb) + "GB"
    PrismSheet(
        title = "性能档位",
        subtitle = "检测 $ramStr · 当前 ${currentTier.label}"
    ) {
        TierOptionRow(
            label = "自动",
            description = "按 RAM 检测结果（当前 ${detectedTier.label}）",
            selected = override == TierConfigRepository.OVERRIDE_AUTO,
            onClick = { onSelect(TierConfigRepository.OVERRIDE_AUTO) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.FULL.label,
            description = "≥6GB · RAG 标准批次 + 嵌入常驻 + L2 跨会话",
            selected = override == PerformanceTier.FULL.name,
            onClick = { onSelect(PerformanceTier.FULL.name) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.STANDARD.label,
            description = "4-6GB · RAG 小批次 + 嵌入按需 2min 卸载",
            selected = override == PerformanceTier.STANDARD.name,
            onClick = { onSelect(PerformanceTier.STANDARD.name) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.MINIMAL.label,
            description = "3-4GB · 禁用 RAG/L2，嵌入不加载",
            selected = override == PerformanceTier.MINIMAL.name,
            onClick = { onSelect(PerformanceTier.MINIMAL.name) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.CHAT_ONLY.label,
            description = "<3GB · 仅聊天 + BYOK",
            selected = override == PerformanceTier.CHAT_ONLY.name,
            onClick = { onSelect(PerformanceTier.CHAT_ONLY.name) }
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "修改后需重启 App 生效",
            color = PrismTextFaint,
            fontSize = 11.sp,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** 档位选项行（单选样式，选中态高亮）。 */
@Composable
private fun TierOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
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
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "▣",
                color = if (selected) PrismMint else PrismTextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
        }
    }
}

/** Provider 已配置列表 + 预设添加。 */
@Composable
private fun ProviderListSheet(
    providers: List<ProviderConfig>,
    activeId: Long?,
    onSelect: (ProviderConfig) -> Unit,
    onCreateFromPreset: (ProviderConfig) -> Unit,
    onNewCustom: () -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "Provider 配置",
        subtitle = "已配置 ${providers.size} · 点击编辑 / 激活"
    ) {
        if (providers.isEmpty()) {
            Text(text = "尚未配置 Provider，可从下方预设添加。", color = PrismTextFaint, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
        providers.forEach { config ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrismPanel2)
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(config) }
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(PrismIndigo.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "◈", color = PrismIndigo, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = config.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = config.baseUrl, color = PrismTextFaint, fontSize = 11.sp)
                }
                if (config.id == activeId) {
                    Text(text = "激活", color = PrismMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        PrismButton(
            text = "＋ 新建自定义 Provider",
            variant = PrismButtonVariant.Primary,
            onClick = onNewCustom
        )
        Spacer(Modifier.height(12.dp))
        Text(text = "从预设添加", color = PrismTextDim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        ProviderPresets.all.forEach { preset ->
            val exists = providers.any { it.name.equals(preset.name, ignoreCase = true) }
            PrismButton(
                text = "添加 ${preset.name}",
                variant = PrismButtonVariant.Ghost,
                enabled = !exists,
                onClick = { onCreateFromPreset(preset) }
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** Provider 详情编辑弹层（config.id=0 为新建自定义模式）。含自定义请求头编辑（US-007）与输入校验（N1）。 */
@Composable
private fun ProviderEditSheet(config: ProviderConfig, viewModel: SettingsViewModel) {
    val isNew = config.id == 0L
    var name by remember(config.id) { mutableStateOf(config.name) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var models by remember(config.id) { mutableStateOf(config.models.joinToString(", ")) }
    var apiKey by remember(config.id) { mutableStateOf("") }
    var apiKeyLoaded by remember(config.id) { mutableStateOf(false) }
    // BR-naming-002：原变量名 `enabled` 与 PrismButton.enabled 参数同名导致语义混淆（DEF-001 考古报告 §3）。
    // 重命名为 `activateAfterSave` 明确表达「保存后是否激活此 Provider」的意图。
    var activateAfterSave by remember(config.id) { mutableStateOf(config.isActive) }
    // v1 US-301（方案 B 识图）：是否设为「视觉旁路 Provider」——主模型不支持图片时，
    // 图片会发往该 Provider 生成描述（隐私：设置开启前需在「识图」设置中授权）。
    var visionFallback by remember(config.id) { mutableStateOf(config.isVisionFallback) }
    // v1 批次13（B）：该 Provider 是否支持视觉（多模态图片输入）——手机操控截图直接以图片
    // 注入会话供模型查看（发挥多模态），否则走 OCR 文字+坐标。保存时按模型名自动提示。
    var supportsVision by remember(config.id) { mutableStateOf(config.supportsVision) }
    // v1 批次13（B/D16b）：supportsVision 是否被用户**显式触碰**（隐私语义：显式关闭不得被
    // 模型名自动检测覆盖，防截图内容静默外发）。初始取 config.supportsVisionSet 保持跨编辑持久。
    var supportsVisionTouched by remember(config.id) { mutableStateOf(config.supportsVisionSet) }
    // 自定义请求头用 SnapshotStateList：原地改值（headers[index]=…）会触发重组，
    // 与删除/新增重建列表行为一致，避免丢输入（guardrail Q2）。
    val headers = remember(config.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(config.headers.entries.map { it.key to it.value })
        }
    }
    var showValidation by remember(config.id) { mutableStateOf(false) }

    // 仅首次进入弹层时回显已存 Key，避免重组覆盖用户输入（与 ApiKeySheet 守卫一致）
    if (!apiKeyLoaded) {
        viewModel.loadApiKey(config.apiKeyRef) { loaded ->
            if (loaded != null) apiKey = loaded
            apiKeyLoaded = true
        }
    }

    // 输入校验（N1）：名称非空 + Base URL 为合法 http(s) 地址。
    // CRLF 纵深防御（guardrail S1）：baseUrl / header key/value 拒绝含 \r\n 的输入，
    // 防止经 buildEndpoint / applyCustomHeaders 注入首部（OkHttp 引擎虽已 fail-closed，此处兜底）。
    val nameValid = name.trim().isNotEmpty()
    val baseUrlTrimmed = baseUrl.trim()
    val urlValid = baseUrlTrimmed.startsWith("http://") || baseUrlTrimmed.startsWith("https://")
    val urlSafe = urlValid && !baseUrlTrimmed.contains('\r') && !baseUrlTrimmed.contains('\n')
    val canSave = nameValid && urlValid && urlSafe
    val validHeaders = headers
        .map { it.first.trim() to it.second.trim() }
        .filter { it.first.isNotEmpty() && !it.first.contains('\r') && !it.first.contains('\n') && !it.second.contains('\r') && !it.second.contains('\n') }

    PrismSheet(
        title = if (isNew) "新建 Provider" else "编辑 ${config.name}",
        subtitle = "端点 · 模型 · 请求头 · 激活",
        // BR-ui-003：保存配置按钮放在 footer 固定底部，确保始终可见可点击（DEF-001 根因修复）。
        footer = {
            PrismButton(
                text = "保存配置",
                enabled = canSave,
                onClick = {
                    if (!canSave) { showValidation = true; return@PrismButton }
                    viewModel.saveApiKey(config.apiKeyRef, apiKey)
                    // 激活态绝不直接写入 isActive（会绕过单激活不变式），统一经 setActive 事务处理
                    val savedId = viewModel.saveProvider(
                        config.copy(
                            name = name.trim().ifEmpty { config.name },
                            baseUrl = baseUrl.trim(),
                            models = models.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            headers = validHeaders.toMap(),
                            isActive = false,
                            isVisionFallback = visionFallback,
                            // v1 批次13（B）：多模态开关（保存时若用户未显式改过，可经模型名自动提示——
                            // 此处取 UI 当前值 + 是否被显式触碰；SettingsViewModel.saveProvider 内做自动提示合并）
                            supportsVision = supportsVision,
                            supportsVisionSet = supportsVisionTouched
                        )
                    )
                    if (activateAfterSave) {
                        viewModel.setActive(savedId)
                    }
                }
            )
            if (showValidation && !canSave) {
                Spacer(Modifier.height(8.dp))
                ValidationError("请修正以上无效输入后再保存")
            }
        }
    ) {
        PrismField(label = "名称", value = name, onValueChange = { name = it }, placeholder = "自定义 Provider 名称")
        if (showValidation && !nameValid) {
            ValidationError("名称不能为空")
        }
        Spacer(Modifier.height(16.dp))
        PrismField(label = "Base URL", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://…")
        if (showValidation && !urlValid) {
            ValidationError("Base URL 需以 http:// 或 https:// 开头")
        }
        Spacer(Modifier.height(16.dp))
        PrismField(label = "模型（逗号分隔）", value = models, onValueChange = { models = it })
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "API Key",
            value = apiKey,
            onValueChange = { apiKey = it },
            placeholder = "sk-…",
            secret = true,
            hint = "Keystore 加密存储 · 明文仅在内存短暂存在"
        )
        Spacer(Modifier.height(20.dp))

        // 自定义请求头编辑器（US-007，上轮决策纳入）
        Text(text = "自定义请求头", color = PrismTextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        if (headers.isEmpty()) {
            Text(text = "无需额外请求头，通常由 Provider 自动填充。", color = PrismTextFaint, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        headers.forEachIndexed { index, (k, v) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrismField(
                    label = null,
                    value = k,
                    onValueChange = { headers[index] = it to v },
                    placeholder = "Header 名（如 X-API-Key）",
                    modifier = Modifier.weight(1f)
                )
                PrismField(
                    label = null,
                    value = v,
                    onValueChange = { headers[index] = k to it },
                    placeholder = "值",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrismPanel2)
                        .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { headers.removeAt(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        PrismButton(
            text = "＋ 添加请求头",
            variant = PrismButtonVariant.Ghost,
            onClick = { headers.add("" to "") }
        )
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "设为激活 Provider", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PrismSwitch(checked = activateAfterSave, onCheckedChange = { activateAfterSave = it })
        }
        Spacer(Modifier.height(12.dp))
        // v1 US-301（方案 B 识图）：视觉旁路 Provider 开关（主模型不支持图片时，图片发往该
        // Provider 生成描述。需在「识图」设置中授权后方可自动触发，隐私提示见下）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = "设为视觉旁路 Provider", color = PrismText, fontSize = 14.sp)
                Text(
                    text = "主模型不支持图片时，图片将发送到此视觉模型生成描述（需在「识图」设置授权）",
                    color = PrismTextFaint, fontSize = 10.sp
                )
            }
            PrismSwitch(checked = visionFallback, onCheckedChange = { visionFallback = it })
        }
        Spacer(Modifier.height(12.dp))
        // v1 批次13（B）：支持视觉（多模态）开关 —— 手机操控截图直接以图片注入会话供模型查看，
        // 否则走 OCR 文字+坐标。保存时按模型名自动提示（detectVisionSupport）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = "支持视觉（多模态）", color = PrismText, fontSize = 14.sp)
                Text(
                    text = "手机操控截图将直接以图片发送给该模型查看（需模型支持图片输入），否则用 OCR 文字+坐标",
                    color = PrismTextFaint, fontSize = 10.sp
                )
            }
            PrismSwitch(
                checked = supportsVision,
                onCheckedChange = {
                    supportsVision = it
                    // 用户触碰开关 → 标记显式设置（保存时不再被模型名自动检测覆盖）
                    supportsVisionTouched = true
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        PrismButton(
            text = "激活",
            variant = PrismButtonVariant.Ghost,
            enabled = !isNew && !activateAfterSave,
            onClick = { viewModel.setActive(config.id) }
        )
        if (!isNew) {
            Spacer(Modifier.height(12.dp))
            PrismButton(
                text = "删除 Provider",
                variant = PrismButtonVariant.Danger,
                onClick = { viewModel.deleteProvider(config) }
            )
        }
    }
}

/** 校验错误提示（薄荷红系警示文案）。 */
@Composable
private fun ValidationError(text: String) {
    Text(
        text = text,
        color = PrismDanger,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** API Key 管理弹层。 */
@Composable
private fun ApiKeySheet(providers: List<ProviderConfig>, viewModel: SettingsViewModel) {
    PrismSheet(
        title = "API Key",
        subtitle = "Keystore 加密 · 明文不落盘"
    ) {
        if (providers.isEmpty()) {
            Text(text = "请先在 Provider 配置中添加 Provider。", color = PrismTextFaint, fontSize = 12.sp)
        }
        providers.forEach { config ->
            var key by remember(config.id) { mutableStateOf("") }
            var loaded by remember(config.id) { mutableStateOf(false) }

            if (!loaded) {
                viewModel.loadApiKey(config.apiKeyRef) { v ->
                    key = v.orEmpty()
                    loaded = true
                }
            }

            PrismField(
                label = config.name,
                value = key,
                onValueChange = { key = it },
                placeholder = if (key.isEmpty()) "未设置 API Key" else "",
                secret = true,
                hint = "加密保存到 DataStore"
            )
            Spacer(Modifier.height(8.dp))
            PrismButton(
                text = "保存 ${config.name} Key",
                variant = PrismButtonVariant.Ghost,
                onClick = {
                    // BR-security-006：空值时删除已存密钥，非空时加密保存。
                    // 修复 guardrail-enforcer B-2：原实现空值时 saveApiKey 静默跳过，
                    // 导致用户清空输入框后保存无法清除已存密钥。
                    if (key.isEmpty()) {
                        viewModel.removeApiKey(config.apiKeyRef)
                    } else {
                        viewModel.saveApiKey(config.apiKeyRef, key)
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 分组标题。 */
@Composable
private fun SetSection(title: String) {
    Text(
        text = title,
        color = PrismTextDim,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

/** 设置行（玻璃面板 + 图标 + 标题/副标题 + 可选尾随控件 + 点击）。 */
@Composable
private fun SetRow(
    icon: String,
    iconColor: Color,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    PrismGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onClick != null,
                onClick = { onClick?.invoke() }
            )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PrismPanel2, RoundedCornerShape(12.dp))
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, color = iconColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = PrismTextFaint, fontSize = 11.sp) 
            }
            trailing()
        }
    }
}

/**
 * 用户规则编辑弹层（UXR8 N1，ADR-030）—— 类 ChatGPT Custom Instructions 双字段。
 *
 * - 「关于我」：身份/背景/场景（如"我是 Python 开发者，主要做后端"）
 * - 「如何回答」：语气/格式/禁忌（如"回答用中文，简洁，先给结论再解释"）
 *
 * 两字段分别 ≤ [io.prism.config.UserRulesRepository.MAX_RULE_LEN] 字符，本地先截断
 * （纵深防御，仓库层 fail-fast 兜底）。保存后下一轮对话生效（最高优先级约束 LLM）。
 *
 * @param aboutMe 当前「关于我」内容
 * @param howToRespond 当前「如何回答」内容
 * @param onSave 保存回调（aboutMe, howToRespond）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun UserRulesSheet(
    aboutMe: String,
    howToRespond: String,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val maxLen = io.prism.config.UserRulesRepository.MAX_RULE_LEN
    var about by remember { mutableStateOf(aboutMe) }
    var respond by remember { mutableStateOf(howToRespond) }
    PrismSheet(
        title = "AI 行为偏好",
        subtitle = "用户规则 · 除安全限制外最高优先级"
    ) {
        Text(
            text = "类 ChatGPT Custom Instructions：告诉 AI 关于你的背景与回复偏好，每轮对话自动生效。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "关于我",
            value = about,
            onValueChange = { about = it.take(maxLen) },
            placeholder = "如：我是 Python 开发者，主要做后端服务"
        )
        Spacer(Modifier.height(8.dp))
        PrismField(
            label = "如何回答",
            value = respond,
            onValueChange = { respond = it.take(maxLen) },
            placeholder = "如：用中文，简洁，先给结论再解释"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "每字段最多 $maxLen 字符 · 本地存储 · 仅发送到你自配的模型端点",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(20.dp))
        PrismButton(text = "保存", onClick = { onSave(about, respond) })
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 搜索引擎增强配置弹层（v1 批次15，US-1501/1502/1507）。
 *
 * - **智谱 API Key**（apiKeyRef=zhipu）：到 bigmodel.cn 注册获取；Keystore 加密存储。
 * - **Tavily API Key**（apiKeyRef=tavily）：到 tavily.com 注册获取；Keystore 加密存储。
 * - **SearXNG 端点**：用户自建服务地址（http/https），可选 Basic Auth 用户名/密码；
 *   部署教程见 docs/runbooks/searxng-selfhost.md。端点为用户显式配置，可达性由用户自担。
 *
 * Key 保存语义与 [ApiKeySheet] 一致（BR-security-006：清空保存 = 删除已存密钥）；
 * SearXNG 端点填空保存 = 清除配置（引擎链完全跳过该引擎）。
 *
 * @param searxngEndpoint 当前 SearXNG 端点（空 = 未配置）
 * @param searxngUsername 当前 Basic Auth 用户名
 * @param searxngPassword 当前 Basic Auth 密码
 * @param viewModel 设置 ViewModel（Key/SearXNG 读写）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun SearchEnhancementSheet(
    searxngEndpoint: String,
    searxngUsername: String,
    searxngPassword: String,
    preferredEngine: String,
    viewModel: SettingsViewModel,
    onClose: () -> Unit
) {
    var zhipuKey by remember { mutableStateOf("") }
    var zhipuLoaded by remember { mutableStateOf(false) }
    var tavilyKey by remember { mutableStateOf("") }
    var tavilyLoaded by remember { mutableStateOf(false) }
    var endpoint by remember(searxngEndpoint) { mutableStateOf(searxngEndpoint) }
    var username by remember(searxngUsername) { mutableStateOf(searxngUsername) }
    var password by remember(searxngPassword) { mutableStateOf(searxngPassword) }
    // v1 批次15.1（US-1509）：首选引擎（空串 = 跟随默认顺序）
    val engineOptions = listOf("默认顺序", "博查", "智谱", "SearXNG", "Tavily")
    val engineValues = listOf("", "bocha", "zhipu", "searxng", "tavily")
    var selectedEngine by remember(preferredEngine) {
        mutableStateOf(engineValues.indexOf(preferredEngine).takeIf { it >= 0 } ?: 0)
    }

    if (!zhipuLoaded) {
        viewModel.loadApiKey(SettingsViewModel.ZHIPU_API_KEY_REF) { v ->
            zhipuKey = v.orEmpty()
            zhipuLoaded = true
        }
    }
    if (!tavilyLoaded) {
        viewModel.loadApiKey(SettingsViewModel.TAVILY_API_KEY_REF) { v ->
            tavilyKey = v.orEmpty()
            tavilyLoaded = true
        }
    }

    PrismSheet(
        title = "搜索引擎增强",
        subtitle = "智谱 / Tavily / SearXNG · 配置后自动接入搜索链"
    ) {
        Text(
            text = "配置任一引擎后，联网搜索将按 博查 → 智谱 → SearXNG → Tavily → Bing/Baidu 的顺序优先使用，首个成功即返回。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(16.dp))
        // v1 批次15.1（US-1509）：首选引擎——自建 SearXNG 后需选它为首选才会被实际使用
        //（默认顺序下 Bocha 成功即短路，后续引擎不再尝试）
        Text(
            text = "首选引擎（首个尝试）",
            color = PrismTextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(bottom = 7.dp)
        )
        PrismSegmented(
            options = engineOptions,
            selected = engineOptions[selectedEngine],
            onSelect = { option ->
                val idx = engineOptions.indexOf(option)
                if (idx >= 0 && idx != selectedEngine) {
                    selectedEngine = idx
                    viewModel.setPreferredEngine(engineValues[idx])
                }
            },
            labelOf = { it }
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "智谱 API Key",
            value = zhipuKey,
            onValueChange = { zhipuKey = it },
            placeholder = "到 bigmodel.cn 注册获取",
            secret = true,
            hint = "加密保存 · 清空后保存即删除"
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(
            text = "保存智谱 Key",
            variant = PrismButtonVariant.Ghost,
            onClick = {
                if (zhipuKey.isEmpty()) {
                    viewModel.removeApiKey(SettingsViewModel.ZHIPU_API_KEY_REF)
                } else {
                    viewModel.saveApiKey(SettingsViewModel.ZHIPU_API_KEY_REF, zhipuKey)
                }
            }
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "Tavily API Key",
            value = tavilyKey,
            onValueChange = { tavilyKey = it },
            placeholder = "到 tavily.com 注册获取",
            secret = true,
            hint = "加密保存 · 清空后保存即删除"
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(
            text = "保存 Tavily Key",
            variant = PrismButtonVariant.Ghost,
            onClick = {
                if (tavilyKey.isEmpty()) {
                    viewModel.removeApiKey(SettingsViewModel.TAVILY_API_KEY_REF)
                } else {
                    viewModel.saveApiKey(SettingsViewModel.TAVILY_API_KEY_REF, tavilyKey)
                }
            }
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "SearXNG 端点",
            value = endpoint,
            onValueChange = { endpoint = it },
            placeholder = "如 http://192.168.1.10:8080",
            hint = "自建 SearXNG 服务地址（http/https）· 部署见 docs/runbooks/searxng-selfhost.md"
        )
        Spacer(Modifier.height(8.dp))
        PrismField(
            label = "SearXNG 用户名（可选 Basic Auth）",
            value = username,
            onValueChange = { username = it },
            placeholder = "未启用认证可留空"
        )
        Spacer(Modifier.height(8.dp))
        PrismField(
            label = "SearXNG 密码（可选 Basic Auth）",
            value = password,
            onValueChange = { password = it },
            placeholder = "未启用认证可留空",
            secret = true
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(
            text = "保存 SearXNG 配置",
            variant = PrismButtonVariant.Ghost,
            onClick = { viewModel.saveSearxngSettings(endpoint, username, password) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "SearXNG 端点为你显式配置的自建服务，可达性由你自担；端点留空保存即清除配置。",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(20.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}
/**
 * 批次22 修复轮五（2026-09-07，SZ-201 同音字优化）：语音热词编辑区。
 *
 * 用户维护"人名/专有名词"热词表（每行一个），保存到 filesDir/voice_hotwords.txt；
 * SherpaAsrEngine 下次启动识别循环时以 modified_beam_search + hotwordsScore=2.0 偏置解码
 *（sherpa-onnx 官方 contextual biasing，greedy_search 不支持热词）。
 * 行业实践：个性化热词是同音字/专名识别优化的标准手段（讯飞/会议转写均提供用户词库）。
 */
@Composable
fun VoiceHotwordsEditor() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val file = java.io.File(context.filesDir, io.prism.voice.VOICE_HOTWORDS_FILE)
    var text by remember { mutableStateOf("") }
    var hotwordCount by remember { mutableStateOf(0) }
    // 批次22 修复轮六：默认**收缩态**（已有热词时折叠为一行摘要，点击展开编辑——与其他
    // 设置行形态一致；无热词时直接展开引导填写）。保存后自动收缩。
    var expanded by remember { mutableStateOf(false) }

    // 进入时读取现有热词文件（IO 线程）
    LaunchedEffect(Unit) {
        val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        }
        text = content
        hotwordCount = content.lines().count { it.isNotBlank() }
        expanded = hotwordCount == 0  // 无热词时展开引导；有热词时收缩
    }

    if (!expanded) {
        SetRow(
            icon = "🔤",
            iconColor = PrismMint,
            title = "语音热词",
            subtitle = if (hotwordCount > 0) {
                "已配置 $hotwordCount 个 · 识别时加权提升同音字区分 · 点此编辑"
            } else {
                "未配置 · 添加人名/专有名词可提升同音字识别 · 点此编辑"
            },
            onClick = { expanded = true }
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        androidx.compose.material3.Text(
            "语音热词（每行一个：人名/联系人/专有名词）",
            color = PrismTextDim,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                androidx.compose.material3.Text("张三\n某公司简称…", color = PrismTextFaint, fontSize = 13.sp)
            },
            minLines = 3,
            maxLines = 6,
            textStyle = androidx.compose.ui.text.TextStyle(color = PrismText, fontSize = 14.sp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismButton(
                text = "保存",
                variant = PrismButtonVariant.Primary,
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            if (text.isBlank()) file.delete() else file.writeText(text)
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            hotwordCount = text.lines().count { it.isNotBlank() }
                            expanded = false  // 保存后收缩
                        }
                    }
                }
            )
            PrismButton(
                text = "取消",
                variant = PrismButtonVariant.Ghost,
                onClick = { expanded = false }
            )
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.Text(
            "保存后下次语音通话生效；识别时将加权匹配这些词，提升同音字区分",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
    }
}

/**
 * 批次23-A（ADR-048，A4）：语音引擎设置区（加在「语音通话」区内）。
 *
 * - **ASR 引擎选择**：端侧（默认）/ 云端阿里云——云端项仅在 DashScope Key 非空时可选
 *   （未配置置灰 + 说明，选择持久化经 [io.prism.voice.VoiceEnginePrefs]）；
 * - **TTS 引擎选择**：系统（默认）/ 云端 MiniMax——同理（Key + GroupId 非空时可选）；
 * - **Key 配置输入行**：DashScope API Key / MiniMax API Key / MiniMax GroupId（复用
 *   [PrismField] 遮蔽样式；保存走 [SettingsViewModel.saveApiKey]，清空保存 = 删除，
 *   BR-security-006 语义与 [ApiKeySheet] 一致）；
 * - **连通性自检**：每个云端引擎一个按钮（ASR=WS run-task 握手 / TTS=task_start→
 *   task_started，3s 预算），结果文案"自检通过/失败原因"就地展示。
 *
 * 默认收缩态（与其他设置行形态一致），点击展开配置。
 */
@Composable
private fun VoiceEngineSection(viewModel: SettingsViewModel) {
    val asrChoice by viewModel.voiceAsrEngine.collectAsState()
    val ttsChoice by viewModel.voiceTtsEngine.collectAsState()
    val dashScopeReady by viewModel.dashScopeKeyConfigured.collectAsState()
    val minimaxKeyReady by viewModel.minimaxKeyConfigured.collectAsState()
    val minimaxGroupReady by viewModel.minimaxGroupConfigured.collectAsState()
    val asrCheck by viewModel.asrSelfCheck.collectAsState()
    val ttsCheck by viewModel.ttsSelfCheck.collectAsState()
    // LVC-1（ADR-053）：本地音色包安装状态（null = 尚未检测，与"未安装"区分呈现）
    val localVoiceStatus by viewModel.localVoiceStatus.collectAsState()
    // TKN-LVC-016（ADR-056/057）：档位、播报模式、已安装音色包与导入态
    val localTier by viewModel.localVoiceTier.collectAsState()
    val playbackMode by viewModel.localPlaybackMode.collectAsState()
    val installedPacks by viewModel.localVoicePacks.collectAsState()
    val packBusy by viewModel.voicePackBusy.collectAsState()
    val packMessage by viewModel.voicePackMessage.collectAsState()
    val pendingOverwrite by viewModel.pendingVoicePackOverwrite.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    // 展开时检测一次（侧载/换角色包后重新进入即可刷新——不引入文件监听，避免常驻轮询）
    LaunchedEffect(expanded) {
        if (expanded) {
            viewModel.refreshLocalVoiceStatus()
            viewModel.refreshLocalVoicePacks()
        }
    }

    // 音色包导入（TKN-LVC-016）：SAF 选 `.voicepack` → 拷到 cache（可 seek，便于按中央目录预检）
    // → 交 VM 校验/落盘。**上限与导入器同源**：超限的归档不进入应用私有目录之外的任何路径。
    val packImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val tmp = java.io.File(
                            context.cacheDir,
                            SettingsViewModel.VOICEPACK_PICK_PREFIX +
                                "${System.currentTimeMillis()}.voicepack"
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选文件")
                        tmp
                    }
                }
                copied.fold(
                    onSuccess = { file -> viewModel.importVoicePack(file) },
                    onFailure = { e ->
                        viewModel.onVoicePackPickFailed(e.message ?: e::class.simpleName.orEmpty())
                    }
                )
            }
        }
    }

    // 覆盖二次确认（ADR-057 决策 5：**不静默覆盖**同名音色包）
    pendingOverwrite?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelVoicePackOverwrite() },
            title = { Text("已存在同名音色包") },
            text = { Text("已安装「${pending.voiceId}」。覆盖后旧包会被替换，且无法撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.confirmVoicePackOverwrite() }
                ) { Text("覆盖") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.cancelVoicePackOverwrite() }
                ) { Text("取消") }
            }
        )
    }

    if (!expanded) {
        SetRow(
            icon = "🎛",
            iconColor = PrismIndigo,
            title = "语音引擎",
            subtitle = voiceEngineSubtitle(asrChoice, ttsChoice),
            onClick = { expanded = true }
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        // ---- ASR 引擎选择（云端项仅在 DashScope Key 就绪时可选）----
        Text(
            text = "识别引擎（ASR）",
            color = PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))
        VoiceEngineOptionRow(
            label = "端侧识别（默认）",
            description = "完全离线 · 音频不出设备",
            selected = asrChoice == io.prism.voice.AsrEngineChoice.SYSTEM,
            onClick = { viewModel.setVoiceAsrEngine(io.prism.voice.AsrEngineChoice.SYSTEM) }
        )
        Spacer(Modifier.height(8.dp))
        VoiceEngineOptionRow(
            label = "云端识别（阿里云百炼）",
            description = if (dashScopeReady) {
                "Paraformer 实时流式 · 音频实时推流到阿里云（Key 已配置）"
            } else {
                "需先配置并保存 DashScope API Key（配置后可选）"
            },
            selected = asrChoice == io.prism.voice.AsrEngineChoice.ALI_CLOUD,
            enabled = dashScopeReady,
            onClick = { viewModel.setVoiceAsrEngine(io.prism.voice.AsrEngineChoice.ALI_CLOUD) }
        )
        Spacer(Modifier.height(8.dp))
        VoiceKeyField(
            label = "DashScope API Key",
            placeholder = "到阿里云百炼控制台获取",
            keyRef = io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF,
            secret = true,
            viewModel = viewModel
        )

        Spacer(Modifier.height(12.dp))
        // ---- TTS 引擎选择（云端项仅在 Key + GroupId 就绪时可选）----
        Text(
            text = "播报引擎（TTS）",
            color = PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))
        VoiceEngineOptionRow(
            label = "系统播报（默认）",
            description = "零依赖 · 本机 TextToSpeech",
            selected = ttsChoice == io.prism.voice.TtsEngineChoice.SYSTEM,
            onClick = { viewModel.setVoiceTtsEngine(io.prism.voice.TtsEngineChoice.SYSTEM) }
        )
        Spacer(Modifier.height(8.dp))
        VoiceEngineOptionRow(
            label = "云端播报（MiniMax）",
            description = if (minimaxKeyReady && minimaxGroupReady) {
                "T2A v2 流式合成 · 音色/语速可由角色包 voice.minimax 指定"
            } else {
                "需先配置并保存 MiniMax API Key 与 GroupId（配置后可选）"
            },
            selected = ttsChoice == io.prism.voice.TtsEngineChoice.MINIMAX,
            enabled = minimaxKeyReady && minimaxGroupReady,
            onClick = { viewModel.setVoiceTtsEngine(io.prism.voice.TtsEngineChoice.MINIMAX) }
        )
        Spacer(Modifier.height(8.dp))
        VoiceKeyField(
            label = "MiniMax API Key",
            placeholder = "到 MiniMax 开放平台获取",
            keyRef = io.prism.voice.cloud.MiniMaxTtsConfig.KEY_API_KEY,
            secret = true,
            viewModel = viewModel
        )
        Spacer(Modifier.height(8.dp))
        VoiceKeyField(
            label = "MiniMax GroupId",
            placeholder = "MiniMax 开放平台账号基本信息页查看",
            keyRef = io.prism.voice.cloud.MiniMaxTtsConfig.KEY_GROUP_ID,
            secret = true,
            viewModel = viewModel
        )

        Spacer(Modifier.height(8.dp))
        // ---- LVC-1（ADR-053）：本地音色（端侧 ZipVoice 克隆，离线零费用）----
        // 门控：仅当「生效角色包声明 voice.local.voiceId」且「音色包已侧载且完整」时可选；
        // 文案同时给出"当前是什么状态"，避免用户面对灰按钮不知为何不可选。
        VoiceEngineOptionRow(
            label = "本地昔涟（离线）",
            description = localVoiceDescription(localVoiceStatus),
            selected = ttsChoice == io.prism.voice.TtsEngineChoice.LOCAL_CLONE,
            enabled = isLocalVoiceSelectable(localVoiceStatus),
            onClick = { viewModel.setVoiceTtsEngine(io.prism.voice.TtsEngineChoice.LOCAL_CLONE) }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本地音色包可在此直接导入（.voicepack 归档），也可继续用电脑侧载" +
                "（见 docs/runbooks/setup-local-voice-clone.md）；完全离线、不产生云端费用。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )

        // ---- TKN-LVC-016（ADR-056）：档位与播报模式（仅本地音色生效）----
        Spacer(Modifier.height(10.dp))
        Text(
            text = "本地音色档位（仅影响本地引擎）",
            color = PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(6.dp))
        VoiceEngineOptionRow(
            label = "质量优先（默认）",
            description = "完全沿用音色包参数（步数/线程不变）",
            selected = localTier == io.prism.voice.LocalVoiceTier.QUALITY,
            onClick = { viewModel.setLocalVoiceTier(io.prism.voice.LocalVoiceTier.QUALITY) }
        )
        Spacer(Modifier.height(6.dp))
        VoiceEngineOptionRow(
            label = "均衡",
            description = "6 步 / 3 线程 · 合成约快一倍，音色略降",
            selected = localTier == io.prism.voice.LocalVoiceTier.BALANCED,
            onClick = { viewModel.setLocalVoiceTier(io.prism.voice.LocalVoiceTier.BALANCED) }
        )
        Spacer(Modifier.height(6.dp))
        VoiceEngineOptionRow(
            label = "速度优先",
            description = "4 步 / 4 线程 · 最快，音色相似度实测略降；长时间播报可能发热降速",
            selected = localTier == io.prism.voice.LocalVoiceTier.SPEED,
            onClick = { viewModel.setLocalVoiceTier(io.prism.voice.LocalVoiceTier.SPEED) }
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "播报方式（仅影响本地引擎）",
            color = PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(6.dp))
        VoiceEngineOptionRow(
            label = "连续播报（默认）",
            description = "边合成边播 · 首声最快；合成速度跟不上时可能出现短暂停顿",
            selected = playbackMode == io.prism.voice.LocalClonePlaybackMode.CONTINUOUS,
            onClick = {
                viewModel.setLocalPlaybackMode(io.prism.voice.LocalClonePlaybackMode.CONTINUOUS)
            }
        )
        Spacer(Modifier.height(6.dp))
        VoiceEngineOptionRow(
            label = "整段播报",
            description = "全部语句合成完再一次性播放 · 绝不中断，但开口要等更久" +
                "（超过 200 字自动改用连续播报）",
            selected = playbackMode == io.prism.voice.LocalClonePlaybackMode.WHOLE,
            onClick = { viewModel.setLocalPlaybackMode(io.prism.voice.LocalClonePlaybackMode.WHOLE) }
        )

        // ---- TKN-LVC-016（ADR-057）：音色包导入 / 列表 / 删除 ----
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "本地音色包",
                color = PrismTextDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp
            )
            PrismButton(
                text = if (packBusy) "处理中…" else "导入音色包",
                variant = PrismButtonVariant.Ghost,
                enabled = !packBusy,
                onClick = {
                    viewModel.consumeVoicePackMessage()
                    // MIME 无法可靠表达 `.voicepack`（zip），用 application/zip + 通配兜底
                    packImportLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream", "*/*")
                    )
                }
            )
        }
        packMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = if (message.startsWith("导入成功") || message.startsWith("已覆盖") ||
                    message.startsWith("已删除")
                ) PrismMint else PrismDanger,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        if (installedPacks.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "尚未导入任何音色包。",
                color = PrismTextFaint,
                fontSize = 11.sp
            )
        } else {
            installedPacks.forEach { pack ->
                Spacer(Modifier.height(6.dp))
                VoicePackRow(
                    pack = pack,
                    busy = packBusy,
                    onDelete = { viewModel.deleteVoicePack(pack.voiceId) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "导入的包存放在应用私有目录，卸载 App 后一并清除；" +
                "要让某个角色使用它，请在该角色的角色包里把 voice.local.voiceId 设为包名。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )

        Spacer(Modifier.height(12.dp))
        // ---- 连通性自检（每个云端引擎一个按钮；结果就地展示）----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismButton(
                text = "自检云端识别",
                variant = PrismButtonVariant.Ghost,
                enabled = asrCheck?.running != true,
                onClick = { viewModel.runAsrSelfCheck() }
            )
            PrismButton(
                text = "自检云端 TTS",
                variant = PrismButtonVariant.Ghost,
                enabled = ttsCheck?.running != true,
                onClick = { viewModel.runTtsSelfCheck() }
            )
        }
        asrCheck?.let { check ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "识别自检：${check.message}",
                color = voiceSelfCheckColor(check.message),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        ttsCheck?.let { check ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "播报自检：${check.message}",
                color = voiceSelfCheckColor(check.message),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "云端引擎仅在 Key 就绪时可选；通话中云端连接失败会自动回退端侧并提示。",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "收起", variant = PrismButtonVariant.Ghost, onClick = { expanded = false })
    }
}

/** 语音引擎区收缩态副标题（当前选择摘要）。 */
private fun voiceEngineSubtitle(
    asr: io.prism.voice.AsrEngineChoice,
    tts: io.prism.voice.TtsEngineChoice
): String =
    "识别：${if (asr == io.prism.voice.AsrEngineChoice.ALI_CLOUD) "云端（阿里云）" else "端侧"} · " +
        "播报：${when (tts) {
            io.prism.voice.TtsEngineChoice.MINIMAX -> "云端（MiniMax）"
            io.prism.voice.TtsEngineChoice.LOCAL_CLONE -> "本地昔涟"
            io.prism.voice.TtsEngineChoice.SYSTEM -> "系统"
        }} · 点此配置"

/**
 * LVC-1（ADR-053）：本地音色选项的描述文案（把"为什么不可选"直接说清楚）。
 *
 * [status] 为 null = **尚未检测**（与"未安装"是两回事，不能等同显示）。
 * 说明：不展示绝对路径（对齐 rules/07 §19.3 日志安全口径，UI 也不必暴露内部布局）。
 */
private fun localVoiceDescription(status: io.prism.voice.local.LocalVoiceStatus?): String = when (status) {
    null -> "检测中…（进入本区即自动检测）"
    is io.prism.voice.local.LocalVoiceStatus.Ready ->
        "端侧 ZipVoice 克隆 · 完全离线 · ${status.pack.references.size} 条参考 · " +
            "${status.pack.totalBytes / (1024 * 1024)} MB"
    is io.prism.voice.local.LocalVoiceStatus.Incomplete ->
        "音色包不完整：${status.problems.joinToString("；")}"
    is io.prism.voice.local.LocalVoiceStatus.NotInstalled ->
        "未安装音色包，或当前角色未声明本地音色（侧载后重新进入本页刷新）"
}

/** LVC-1：本地音色选项是否可选（仅"就绪"可为真）。 */
private fun isLocalVoiceSelectable(status: io.prism.voice.local.LocalVoiceStatus?): Boolean =
    status is io.prism.voice.local.LocalVoiceStatus.Ready

/** 自检结果文案颜色（"自检通过"前缀 = 薄荷绿；失败原因 = 警示色）。 */
private fun voiceSelfCheckColor(message: String): Color =
    if (message.startsWith("自检通过")) PrismMint else PrismWarning

/**
 * 引擎选择选项行（批次23-A，A4）—— 单选样式 + 置灰态（云端引擎 Key 未配置时不可选）。
 * 视觉复用 [ToolApprovalOptionRow] 模式，增加 enabled 维度。
 */
@Composable
private fun VoiceEngineOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else if (enabled) "◈" else "🔒",
                color = if (selected) PrismMint else PrismTextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
        }
    }
}

/**
 * 已安装音色包一行（TKN-LVC-016，ADR-057）：包名 + 引擎 + 体积 + 就绪状态 + 删除。
 *
 * 为什么把"未就绪"的包也列出来（带问题原因）：导入链路校验通过的包才会落盘，但**用户手动
 * 修改过**或**侧载残留**的目录同样会出现在扫描结果里。若这类目录直接不显示，用户会看到
 * "明明有目录却选不了"而无从排查；显示 + 给出问题原因才能自证。
 */
@Composable
private fun VoicePackRow(
    pack: SettingsViewModel.InstalledVoicePack,
    busy: Boolean,
    onDelete: () -> Unit
) {
    val engineLabel = when (pack.ttsWire) {
        "zipvoice" -> "ZipVoice"
        "vits" -> "VITS"
        else -> "未知引擎"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PrismPanel2)
            .border(
                1.dp,
                if (pack.ready) PrismLine else PrismDanger.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = pack.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = buildString {
                    append(pack.voiceId)
                    append(" · ")
                    append(engineLabel)
                    if (pack.totalBytes > 0L) {
                        append(" · ")
                        append("%.0fMB".format(pack.totalBytes / 1024.0 / 1024.0))
                    }
                    if (!pack.ready) {
                        append(" · 不可用：")
                        append(pack.problems.joinToString("；").take(60))
                    }
                },
                color = if (pack.ready) PrismTextFaint else PrismDanger,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        PrismButton(
            text = "删除",
            variant = PrismButtonVariant.Ghost,
            enabled = !busy,
            onClick = onDelete
        )
    }
}

/**
 * 语音引擎 Key 配置输入行（批次23-A，A4）—— 复用 [PrismField] 遮蔽样式 + ApiKeyRepository
 * 加密读写（与 [SearchEnhancementSheet] 的 Key 行同模式：仅首次进入回显已存 Key 防重组覆盖；
 * 清空保存 = 删除已存密钥，BR-security-006）。
 */
@Composable
private fun VoiceKeyField(
    label: String,
    placeholder: String,
    keyRef: String,
    secret: Boolean,
    viewModel: SettingsViewModel
) {
    var value by remember(keyRef) { mutableStateOf("") }
    var loaded by remember(keyRef) { mutableStateOf(false) }

    if (!loaded) {
        viewModel.loadApiKey(keyRef) { v ->
            value = v.orEmpty()
            loaded = true
        }
    }

    PrismField(
        label = label,
        value = value,
        onValueChange = { value = it },
        placeholder = placeholder,
        secret = secret,
        hint = "Keystore 加密存储 · 清空后保存即删除"
    )
    Spacer(Modifier.height(8.dp))
    PrismButton(
        text = "保存 $label",
        variant = PrismButtonVariant.Ghost,
        onClick = {
            if (value.isEmpty()) {
                viewModel.removeApiKey(keyRef)
            } else {
                viewModel.saveApiKey(keyRef, value)
            }
        }
    )
}

/**
 * 批次23-C（ADR-048）：角色包管理区（加在「语音通话」区内，通用模板——不含任何具体角色内容）。
 *
 * - **导入**：SAF [androidx.activity.result.contract.ActivityResultContracts.OpenDocument]
 *   （mime 用通配符——.rolepack/.json 均可由内容识别）→ 复制到应用缓存再读文本（content URI
 *   不保证可重复打开）→ [SettingsViewModel.importRolePack]（先解析校验再落盘，文件名以
 *   包 id 规范化）；结果就地展示（成功/失败原因）；
 * - **列表**：已导入角色包（按显示名排序），当前选中带「生效中」标记；点击切换
 *   （loader.select，热切换——下次语音轮次 persona 覆盖与播报装配即生效）；行尾 ✕ 按钮
 *   删除（loader.remove，删除选中包自动回落出厂默认助手）；
 * - 装配语义在 ConversationViewModel（语音轮次 persona）与 VoiceSessionController
 *   （voice.system / style 分段）内实现，本区只调 loader，无额外接线。
 *
 * 默认收缩态（与其他设置行形态一致），点击展开管理。
 */
@Composable
private fun RolePackSection(viewModel: SettingsViewModel) {
    val packs by viewModel.rolePacks.collectAsState()
    val selectedId by viewModel.selectedRolePackId.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var importOk by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    // 进入设置页即刷新（收缩态副标题也依赖列表/选中态；loader 自带 IO 调度）
    LaunchedEffect(Unit) { viewModel.refreshRolePacks() }

    // SAF 文件选择（批次23-C）：选中后复制到缓存再读文本，交 VM 解析导入
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val tmp = java.io.File(context.cacheDir, "rolepack_import.tmp")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选文件")
                        val text = tmp.readText()
                        tmp.delete()
                        text
                    }
                }
                read.fold(
                    onSuccess = { text ->
                        viewModel.importRolePack(text) { ok, message ->
                            importOk = ok
                            importMessage = message
                        }
                    },
                    onFailure = { e ->
                        importOk = false
                        importMessage = "导入失败：${e.message ?: e::class.simpleName}"
                    }
                )
            }
        }
    }

    if (!expanded) {
        val selectedName = packs.firstOrNull { it.id == selectedId }?.name
        SetRow(
            icon = "🎭",
            iconColor = PrismIndigo,
            title = "角色包",
            subtitle = when {
                packs.isEmpty() -> "未导入 · 导入角色包 JSON 可切换语音通话人格 · 点此管理"
                selectedName != null -> "已导入 ${packs.size} 个 · 当前：$selectedName"
                else -> "已导入 ${packs.size} 个 · 当前：默认助手"
            },
            onClick = { expanded = true }
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "已导入角色包（点击切换 · 下次语音轮次生效）",
            color = PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))
        if (packs.isEmpty()) {
            Text(text = "尚未导入角色包。", color = PrismTextFaint, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        // 出厂默认角色包行（BUG-20260908-002）：始终展示——未选中任何包时即「生效中」，
        // 点击清除选中回落 B0；默认包不可删除（无 ✕）
        RolePackRow(
            pack = io.prism.voice.rolepack.RolePack.DEFAULT_ROLE_PACK,
            selected = selectedId == null,
            onSelect = { viewModel.clearRolePackSelection() },
            onDelete = {},
            showDelete = false
        )
        Spacer(Modifier.height(8.dp))
        packs.forEach { pack ->
            RolePackRow(
                pack = pack,
                selected = pack.id == selectedId,
                onSelect = { viewModel.selectRolePack(pack.id) },
                onDelete = { viewModel.deleteRolePack(pack.id) }
            )
            Spacer(Modifier.height(8.dp))
        }
        PrismButton(
            text = "导入角色包（.json）",
            variant = PrismButtonVariant.Primary,
            onClick = { importLauncher.launch(arrayOf("*/*")) }
        )
        importMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = if (importOk) PrismMint else PrismWarning,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "角色包为本地 JSON 文件（Schema prism-rolepack/1），存储于应用私有目录，随应用卸载清除；" +
                "选中后作用于语音通话人设与播报风格，未选中时使用出厂默认助手。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "收起", variant = PrismButtonVariant.Ghost, onClick = { expanded = false })
    }
}

/** 角色包列表行（批次23-C）：点击切换选中，行尾 ✕ 按钮删除（复用引擎选项行视觉）；
 *  showDelete=false 时隐藏删除按钮（出厂默认行专用，默认包不可删）。 */
@Composable
private fun RolePackRow(
    pack: io.prism.voice.rolepack.RolePack,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "🎭",
                color = if (selected) PrismMint else PrismTextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = pack.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "v${pack.version} · ${pack.id}", color = PrismTextFaint, fontSize = 11.sp)
        }
        if (selected) {
            Text(text = "生效中", color = PrismMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showDelete) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PrismPanel2)
                    .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDelete
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
            }
        }
    }
}

/**
 * 桌宠设置区（批次24，ADR-051 / PRD US-2412）。
 *
 * **权限文案必须说清是"桌宠显示"用途**：项目里既有的 `SYSTEM_ALERT_WINDOW` 引导
 * （后台操控/虚拟屏）说的是"后台启动应用"，与悬浮窗绘制**不是同一件事**
 * （考古报告 §C：现有权限语义是"后台启动 Activity 的豁免条件"）。
 * 复用同一句文案会造成用户误解，故本区自带独立说明。
 */
@Composable
private fun PetSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val config by viewModel.petConfig.collectAsState()
    val appearances by viewModel.petAppearances.collectAsState()
    val runtime by viewModel.petRuntime.collectAsState()
    val lastError by viewModel.petLastError.collectAsState()
    val message by viewModel.petMessage.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var canOverlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }

    LaunchedEffect(Unit) { viewModel.refreshPet() }
    // 权限状态**必须轮询**（guardrail M-3）：用户点"前往设置"授权后回到 App，
    // 在 startActivity 之后立刻重读必然还是 false（系统设置页是另一个 Task，
    // 此刻还没走完）。项目既有做法是 1s 轮询（同文件后台操控引导段），此处对齐。
    LaunchedEffect(expanded) {
        while (expanded) {
            canOverlay = android.provider.Settings.canDrawOverlays(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    val packLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) viewModel.importPetAsset(uri, isPack = true)
    }
    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) viewModel.importPetAsset(uri, isPack = false)
    }

    if (!expanded) {
        SetRow(
            icon = "🐾",
            iconColor = PrismIndigo,
            title = "桌宠",
            subtitle = when {
                !config.enabled -> "已关闭 · 打开后角色会出现在手机桌面上 · 点此设置"
                runtime == io.prism.pet.PetManager.RuntimeState.RUNNING ->
                    "显示中 · ${appearances.firstOrNull { it.id == config.appearanceId }?.name ?: "未选形象"}"
                runtime == io.prism.pet.PetManager.RuntimeState.FAILED ->
                    "启动失败 · ${lastError ?: "未知原因"}"
                else -> "已开启（等待生效）"
            },
            onClick = { expanded = true }
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        SetRow(
            icon = "🐾",
            iconColor = PrismIndigo,
            title = "显示桌宠",
            subtitle = "在手机桌面显示角色形象（悬浮窗，可随时收起）",
            trailing = {
                PrismSwitch(
                    checked = config.enabled,
                    enabled = canOverlay,
                    onCheckedChange = { viewModel.setPetEnabled(it) }
                )
            },
            onClick = { if (canOverlay) viewModel.setPetEnabled(!config.enabled) }
        )

        if (!canOverlay) {
            Spacer(Modifier.height(6.dp))
            SetRow(
                icon = "⚠",
                iconColor = PrismWarning,
                title = "需要「显示在其他应用上层」权限",
                subtitle = "桌宠是一个浮在桌面上的小窗口，必须授予该权限才能显示。点此前往系统设置开启。",
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    canOverlay = android.provider.Settings.canDrawOverlays(context)
                }
            )
        }

        lastError?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(text = "上次启动失败：$err", color = PrismWarning, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "桌宠形象（点选切换 · 立即生效）",
            color = PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))

        if (appearances.isEmpty()) {
            Text(
                text = "尚未导入形象。可导入 .petpack 形象包（含 Live2D 模型），或直接选一张图片。",
                color = PrismTextFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
        }
        appearances.forEach { appearance ->
            PetAppearanceRow(
                name = appearance.name,
                detail = if (appearance.kind == io.prism.pet.model.PetAppearanceKind.LIVE2D) {
                    "Live2D · ${appearance.actions.size} 个动作"
                } else {
                    "图片"
                },
                selected = appearance.id == config.appearanceId,
                onSelect = { viewModel.setPetAppearance(appearance.id) },
                onDelete = { viewModel.deletePetAppearance(appearance.id) }
            )
            Spacer(Modifier.height(8.dp))
        }

        // 署名展示（ADR-051 §后果"导入页显著标注"；guardrail M-5 指出数据已解析但从未接线）。
        // 合规意义：昔涟模型的原作者授权**以署名为条件**，把署名只放进包里不展示等于没履行。
        appearances.firstOrNull { it.id == config.appearanceId }?.attribution?.let { attribution ->
            Text(
                text = attribution,
                color = PrismTextFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismButton(
                text = "导入形象包（.petpack）",
                variant = PrismButtonVariant.Primary,
                onClick = { packLauncher.launch(arrayOf("*/*")) }
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismButton(
                text = "导入单张图片",
                variant = PrismButtonVariant.Ghost,
                onClick = { imageLauncher.launch(arrayOf("image/*")) }
            )
        }

        message?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(text = msg, color = PrismTextFaint, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(12.dp))
        PetChoiceRow(
            label = "大小",
            options = listOf("小" to 0.7f, "中" to 1.0f, "大" to 1.3f),
            selectedValue = config.scale,
            onSelect = { viewModel.setPetScale(it) }
        )
        PetChoiceRow(
            label = "不透明度",
            options = listOf("70%" to 0.7f, "85%" to 0.85f, "100%" to 1.0f),
            selectedValue = config.opacity,
            onSelect = { viewModel.setPetOpacity(it) }
        )
        PetChoiceRow(
            label = "表情回正",
            options = listOf("关闭" to 0f, "5 秒" to 5f, "8 秒" to 8f, "15 秒" to 15f),
            selectedValue = config.expressionResetSeconds.toFloat(),
            onSelect = { viewModel.setPetExpressionResetSeconds(it.toInt()) }
        )

        Spacer(Modifier.height(6.dp))
        PetToggleRow(
            label = "待机小动作",
            subtitle = "没人理的时候偶尔自己动一下",
            checked = config.idleEnabled,
            onCheckedChange = { viewModel.setPetIdleEnabled(it) }
        )
        PetToggleRow(
            label = "减少动效",
            subtitle = "停止待机动作与呼吸，仅保留眨眼和你触发的动作",
            checked = config.reduceMotion,
            onCheckedChange = { viewModel.setPetReduceMotion(it) }
        )
        PetToggleRow(
            label = "显示对话气泡",
            subtitle = "角色回复时在形象旁显示一小段文字",
            checked = config.bubbleEnabled,
            onCheckedChange = { viewModel.setPetBubbleEnabled(it) }
        )
        PetToggleRow(
            label = "低功耗模式",
            subtitle = "渲染帧率降到 15fps，省电优先",
            checked = config.lowPowerMode,
            onCheckedChange = { viewModel.setPetLowPowerMode(it) }
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismButton(
                text = "重置位置",
                variant = PrismButtonVariant.Ghost,
                onClick = { viewModel.resetPetPosition() }
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "形象资产保存在应用私有目录（filesDir/pets/），随应用卸载清除，不会上传；" +
                "请确认你拥有所导入形象的使用权。若形象来自他人创作，请保留其署名信息。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "收起", variant = PrismButtonVariant.Ghost, onClick = { expanded = false })
    }
}

/** 桌宠形象行：点击切换选中，行尾 ✕ 删除（视觉与角色包行一致）。 */
@Composable
private fun PetAppearanceRow(
    name: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) PrismMint else PrismText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = detail, color = PrismTextFaint, fontSize = 11.sp)
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PrismPanel2)
                .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDelete
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
        }
    }
}

/** 桌宠离散选项行（复用分段选择的视觉：选中项薄荷色描边）。 */
@Composable
private fun <T> PetChoiceRow(
    label: String,
    options: List<Pair<String, T>>,
    selectedValue: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = label, color = PrismTextDim, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        options.forEach { (text, value) ->
            val selected = value == selectedValue
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2)
                    .border(
                        1.dp,
                        if (selected) PrismMint.copy(alpha = 0.5f) else PrismLine,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(value) }
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = text,
                    color = if (selected) PrismMint else PrismTextDim,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** 桌宠开关行（标题 + 说明 + 开关）。 */
@Composable
private fun PetToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = PrismTextFaint, fontSize = 11.sp)
        }
        PrismSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
