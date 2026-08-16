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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.prism.ui.components.PrismSwitch
import io.prism.ui.components.PrismTopBar
import io.prism.ui.theme.PrismCyan
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

/**
 * 设置屏幕 —— 深空玻璃肌理（设计规范 v0.4 第 8.4 节）。
 *
 * 分组：模型与端点 / 隐私与安全 / 设备档位 / 关于。
 * Provider 配置与 API Key 两行接入 [SettingsViewModel] 数据层：
 * - Provider 配置 → 已配置列表弹层 + 详情编辑弹层（Base URL / 模型 / 激活 / 删除 / 从预设添加）
 * - API Key → 各 Provider 的 Key 加密读写（掩码 + Keystore 加密）
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    // 生物识别解锁：M0 起 UI 占位（仅本地 state，未接入 BiometricPrompt）。
    // 默认值改为 false 避免误导用户以为已启用保护（BR-ui-001）。
    // 完整实现需新 ADR + PRD（BiometricManager + DataStore 持久化 + App 启动锁）。
    var biometric by remember { mutableStateOf(false) }
    var tierSheetVisible by remember { mutableStateOf(false) }
    var providerListVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var thinkingSheetVisible by remember { mutableStateOf(false) }
    var toolApprovalSheetVisible by remember { mutableStateOf(false) }

    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    // 问题 8a（ADR-020）：深度思考开关 + 思考强度（DataStore 持久化）
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val reasoningEffort by viewModel.reasoningEffort.collectAsState()
    // UXR3 问题 10（ADR-023）：工具审批模式（DataStore 持久化）
    val toolApprovalMode by viewModel.toolApprovalMode.collectAsState()

    // M7 Phase C（US-042）：档位 UI 由 TierViewModel 驱动（替代原 PerfTier 本地 mock state）
    val tierViewModel: TierViewModel = viewModel(factory = TierViewModel.Factory)
    val tierOverride by tierViewModel.override.collectAsState()

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
            item { SetSection("隐私与安全") }
            item {
                SetRow(
                    icon = "◐",
                    iconColor = PrismCyan,
                    title = "生物识别解锁",
                    subtitle = "即将支持 · 可选二次解锁",
                    trailing = { PrismSwitch(checked = biometric, onCheckedChange = { biometric = it }) }
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
    }
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
                            isActive = false
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