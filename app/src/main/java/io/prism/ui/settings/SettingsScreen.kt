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
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismGlassCard
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismSwitch
import io.prism.ui.components.PrismTopBar
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/** 性能档位。 */
private enum class PerfTier(val label: String) { MINIMAL("极简"), STANDARD("标准"), FULL("全功能") }

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
    var tier by remember { mutableStateOf(PerfTier.STANDARD) }
    var providerListVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()

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
                    subtitle = "自动识别 · 8GB RAM",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PerfTier.entries.forEach { t ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (t == tier) PrismMint.copy(alpha = 0.08f) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (t == tier) PrismMint.copy(alpha = 0.4f) else PrismLine,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { tier = t }
                                        .padding(horizontal = 9.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = t.label,
                                        color = if (t == tier) PrismMint else PrismTextDim,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
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

/** Provider 详情编辑弹层（config.id=0 为新建自定义模式）。 */
@Composable
private fun ProviderEditSheet(config: ProviderConfig, viewModel: SettingsViewModel) {
    val isNew = config.id == 0L
    var name by remember(config.id) { mutableStateOf(config.name) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var models by remember(config.id) { mutableStateOf(config.models.joinToString(", ")) }
    var apiKey by remember(config.id) { mutableStateOf("") }
    var apiKeyLoaded by remember(config.id) { mutableStateOf(false) }
    var enabled by remember(config.id) { mutableStateOf(config.isActive) }

    // 仅首次进入弹层时回显已存 Key，避免重组覆盖用户输入（与 ApiKeySheet 守卫一致）
    if (!apiKeyLoaded) {
        viewModel.loadApiKey(config.apiKeyRef) { loaded ->
            if (loaded != null) apiKey = loaded
            apiKeyLoaded = true
        }
    }

    PrismSheet(
        title = if (isNew) "新建 Provider" else "编辑 ${config.name}",
        subtitle = "端点 · 模型 · 激活"
    ) {
        PrismField(label = "名称", value = name, onValueChange = { name = it }, placeholder = "自定义 Provider 名称")
        Spacer(Modifier.height(16.dp))
        PrismField(label = "Base URL", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://…")
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "设为激活 Provider", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(16.dp))
        PrismButton(
            text = "保存配置",
            onClick = {
                viewModel.saveApiKey(config.apiKeyRef, apiKey)
                // 激活态绝不直接写入 isActive（会绕过单激活不变式），统一经 setActive 事务处理
                val savedId = viewModel.saveProvider(
                    config.copy(
                        name = name.trim().ifEmpty { config.name },
                        baseUrl = baseUrl.trim(),
                        models = models.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        isActive = false
                    )
                )
                if (enabled) {
                    viewModel.setActive(savedId)
                }
            }
        )
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