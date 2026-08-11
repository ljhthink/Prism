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
    var biometric by remember { mutableStateOf(true) }
    var tierSheetVisible by remember { mutableStateOf(false) }
    var providerListVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()

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
            item { SetSection("隐私与安全") }
            item {
                SetRow(
                    icon = "◐",
                    iconColor = PrismCyan,
                    title = "生物识别解锁",
                    subtitle = "可选二次解锁",
                    trailing = { PrismSwitch(checked = biometric, onCheckedChange = { biometric = it }) }
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
    var enabled by remember(config.id) { mutableStateOf(config.isActive) }
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
        subtitle = "端点 · 模型 · 请求头 · 激活"
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
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(16.dp))
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
                if (enabled) {
                    viewModel.setActive(savedId)
                }
            }
        )
        if (showValidation && !canSave) {
            Spacer(Modifier.height(8.dp))
            ValidationError("请修正以上无效输入后再保存")
        }
        Spacer(Modifier.height(12.dp))
        PrismButton(
            text = "激活",
            variant = PrismButtonVariant.Ghost,
            enabled = !isNew && !enabled,
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
                onClick = { viewModel.saveApiKey(config.apiKeyRef, key) }
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