package io.prism.ui.capabilities

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.prism.PrismApplication
import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismCard
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismSegmented
import io.prism.ui.components.PrismStatusDot
import io.prism.ui.components.PrismDotState
import io.prism.ui.components.PrismSwitch
import io.prism.ui.components.PrismTopBar
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/** 能力中枢分段。 */
private enum class CapSegment(val label: String) { MCP("MCP 工具"), SKILLS("Skills"), MEMORY("记忆") }

/** Skill。 */
private data class PrismSkill(
    val icon: String,
    val name: String,
    val origin: String,
    val desc: String,
    val enabled: Boolean,
    val accent: Color
)

private val skills = listOf(
    PrismSkill("✎", "智能翻译", "本地", "中英互译", true, PrismIndigo),
    PrismSkill("⌂", "会议纪要", "远程", "自动摘要", true, PrismCyan),
    PrismSkill("⌁", "代码审查", "本地", "AI Code Review", true, PrismMint),
    PrismSkill("▣", "知识整理", "远程", "结构化管理", false, Color(0xFFFF9A5C))
)

/**
 * 能力中枢屏幕 —— 深空玻璃肌理（设计规范 v0.4 第 8.3 节，US-002/004/005/008）。
 *
 * 顶部三段式（MCP 工具 / Skills / 记忆）。MCP 段接入 [CapabilitiesViewModel]，展示动态、
 * 可配置的 MCP Server（US-008）：点击 Server 行 → 配置弹层（Base URL / API Key / 自定义头 /
 * 测试连接 / 启用 / 删除）；点击预设模板 → 一键创建；点击「新建」→ 自定义 Server。
 */
@Composable
fun CapabilitiesScreen(
    viewModel: CapabilitiesViewModel = viewModel(factory = CapabilitiesViewModel.Factory)
) {
    var segment by remember { mutableStateOf(CapSegment.MCP) }
    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                PrismTopBar(
                    title = "能力中枢",
                    subtitle = "MCP · Skills · 记忆",
                    actions = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(PrismPanel2, RoundedCornerShape(12.dp))
                                .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { viewModel.newCustomServer() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, null, tint = PrismTextDim)
                        }
                    }
                )
            }
            item {
                PrismSegmented(
                    options = CapSegment.entries,
                    selected = segment,
                    onSelect = { segment = it },
                    labelOf = { it.label },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                AnimatedVisibility(
                    visible = segment == CapSegment.MCP,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut()
                ) {
                    McpPanel(
                        servers = servers,
                        viewModel = viewModel,
                        onServerClick = { viewModel.selectServer(it) }
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = segment == CapSegment.SKILLS,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut()
                ) {
                    SkillsPanel(onSkillClick = { })
                }
            }
            item {
                AnimatedVisibility(
                    visible = segment == CapSegment.MEMORY,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut()
                ) {
                    MemoryPanel()
                }
            }
        }

        // MCP Server 配置弹层
        PrismSheetHost(visible = selectedServer != null, onDismiss = { viewModel.selectServer(null) }) {
            selectedServer?.let { McpConfigSheet(it, viewModel) }
        }
    }
}

/** MCP 工具面板 —— 本地/远程分组展示动态 Server + 预设快捷添加。 */
@Composable
private fun McpPanel(
    servers: List<McpServerConfig>,
    viewModel: CapabilitiesViewModel,
    onServerClick: (McpServerConfig) -> Unit
) {
    val local = servers.filter { it.serverType == McpServerType.LOCAL }
    val remote = servers.filter { it.serverType == McpServerType.REMOTE }

    Column {
        SectionHeader("本地内置 · ${local.size}", "管理")
        if (local.isEmpty()) EmptySection("暂无本地 Server，点击右上角 + 或下方预设添加")
        local.forEach { McpRow(it, Modifier.padding(horizontal = 20.dp), onClick = { onServerClick(it) }, onToggle = { checked -> viewModel.setEnabled(it.id, checked) }) }

        SectionHeader("远程模板 · ${remote.size}", "+ 自定义")
        if (remote.isEmpty()) EmptySection("暂无远程 Server，点击下方预设一键添加")
        remote.forEach { McpRow(it, Modifier.padding(horizontal = 20.dp), onClick = { onServerClick(it) }, onToggle = { checked -> viewModel.setEnabled(it.id, checked) }) }

        // 预设快捷添加（基于 McpServerPresets）
        SectionHeader("从预设添加", "模板")
        CapabilitiesViewModel.presets.forEach { preset ->
            PresetRow(preset, Modifier.padding(horizontal = 20.dp), onClick = {
                viewModel.createFromPreset(preset)
            })
        }
    }
}

/** 单个 MCP Server 行（点击打开配置）。 */
@Composable
private fun McpRow(
    server: McpServerConfig,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    PrismCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val accent = if (server.serverType == McpServerType.LOCAL) PrismCyan else PrismIndigo
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = server.name.firstOrNull()?.toString() ?: "?", color = accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = server.name.ifEmpty { "未命名 Server" }, color = PrismText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(text = server.baseUrl.ifEmpty { "本地内置 · 零配置" }, color = PrismTextFaint, fontSize = 11.sp)
            }
            PrismStatusDot(if (server.isEnabled) PrismDotState.OK else PrismDotState.ERR)
            // 本地内建 Server（零配置）无需 baseUrl 即可启用；远程 Server 需 baseUrl（guardrail M2）
            PrismSwitch(
                checked = server.isEnabled,
                onCheckedChange = onToggle,
                enabled = server.serverType == McpServerType.LOCAL || server.baseUrl.isNotBlank()
            )
        }
    }
}

/** 空态占位。 */
@Composable
private fun EmptySection(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/** 预设模板行 —— 点击一键创建。 */
@Composable
private fun PresetRow(preset: McpServerConfig, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PrismCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "＋", color = PrismIndigo, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = preset.name, color = PrismText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (preset.serverType == McpServerType.LOCAL) "本地内置 · 零配置" else "远程模板 · 需填 Key",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * MCP Server 配置弹层 —— 编辑参考 ProviderEditSheet（US-007）模式（ADR-005 5.4）。
 * 名称 / 传输类型（仅 Streamable HTTP）/ Base URL / API Key / 自定义头 / 测试连接 / 启用 / 删除。
 */
@Composable
private fun McpConfigSheet(config: McpServerConfig, viewModel: CapabilitiesViewModel) {
    val isNew = config.id == 0L
    var name by remember(config.id) { mutableStateOf(config.name) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config.id) { mutableStateOf("") }
    var enabled by remember(config.id) { mutableStateOf(config.isEnabled) }
    var showValidation by remember(config.id) { mutableStateOf(false) }
    val headers = remember(config.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(config.headers.entries.map { it.key to it.value })
        }
    }
    val testState by viewModel.testState.collectAsState()

    // 本地内建 Server（零配置，ADR-006 5.7）：无需 Base URL，跳过 http(s) 校验
    val isLocal = config.serverType == McpServerType.LOCAL

    // 输入校验：名称非空 + Base URL 为合法 http(s) 地址 + 拒绝 CRLF（纵深防御，guardrail S1）
    val nameValid = name.trim().isNotEmpty()
    val baseUrlTrimmed = baseUrl.trim()
    val urlValid = baseUrlTrimmed.startsWith("http://") || baseUrlTrimmed.startsWith("https://")
    val urlSafe = urlValid && !baseUrlTrimmed.contains('\r') && !baseUrlTrimmed.contains('\n')
    val canSave = nameValid && (isLocal || (urlValid && urlSafe))
    val validHeaders = headers
        .map { it.first.trim() to it.second.trim() }
        .filter { it.first.isNotEmpty() && !it.first.contains('\r') && !it.first.contains('\n') && !it.second.contains('\r') && !it.second.contains('\n') }

    /** 由当前输入组装草稿配置（测试连接 / 保存共用）。 */
    val draft = config.copy(
        name = name.trim().ifEmpty { config.name },
        baseUrl = baseUrl.trim(),
        headers = validHeaders.toMap(),
        isEnabled = enabled
    )

    PrismSheet(
        title = if (isNew) "新建 MCP Server" else "编辑 ${config.name}",
        subtitle = "Streamable HTTP · JSON-RPC 2.0"
    ) {
        PrismField(label = "名称", value = name, onValueChange = { name = it }, placeholder = "Server 名称")
        if (showValidation && !nameValid) ValidationError("名称不能为空")
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "传输类型",
            value = "Streamable HTTP",
            onValueChange = {},
            trailing = {
                PrismSegmented(
                    options = listOf("Streamable HTTP"),
                    selected = "Streamable HTTP",
                    onSelect = {},
                    labelOf = { it },
                    modifier = Modifier.width(160.dp)
                )
            }
        )
        Spacer(Modifier.height(16.dp))
        if (isLocal) {
            // 本地内置 Server：零配置，无需 Base URL（ADR-006 5.7）
            Text(
                text = "Base URL",
                color = PrismTextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(bottom = 7.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PrismPanel, RoundedCornerShape(10.dp))
                    .border(1.dp, PrismLineStrong, RoundedCornerShape(10.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp)
            ) {
                Text(text = "本地内置 · 零配置", color = PrismTextFaint, fontSize = 13.5.sp)
            }
            FilesystemAuthorizationSection()
        } else {
            PrismField(label = "Base URL", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://…/mcp")
            if (showValidation && !urlValid) ValidationError("Base URL 需以 http:// 或 https:// 开头")
        }
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "Token / API Key",
            value = apiKey,
            onValueChange = { apiKey = it },
            placeholder = "eyJ…",
            secret = true,
            hint = "Keystore 加密存储 · 明文仅在内存短暂存在",
            trailing = {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = PrismTextFaint, modifier = Modifier.size(16.dp))
            }
        )
        Spacer(Modifier.height(20.dp))

        // 自定义请求头编辑器（对齐 ProviderEditSheet）
        Text(text = "自定义请求头", color = PrismTextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        if (headers.isEmpty()) {
            Text(text = "无需额外请求头，通常由 Server 自动填充。", color = PrismTextFaint, fontSize = 11.sp)
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
                    placeholder = "Header 名",
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

        // 测试连接（对齐 viewModel.testConnection）
        val testEnabled = !isNew && nameValid && (isLocal || (urlValid && urlSafe))
        PrismButton(
            text = when (val s = testState) {
                is CapabilitiesViewModel.TestState.Testing -> "测试中…"
                is CapabilitiesViewModel.TestState.Success -> "连接成功 · ${s.toolCount} 个工具"
                is CapabilitiesViewModel.TestState.Fail -> "连接失败 · 重试"
                else -> "测试连接"
            },
            enabled = testEnabled && testState !is CapabilitiesViewModel.TestState.Testing,
            onClick = { viewModel.testConnection(draft) }
        )
        if (testState is CapabilitiesViewModel.TestState.Fail) {
            Spacer(Modifier.height(8.dp))
            ValidationError((testState as CapabilitiesViewModel.TestState.Fail).message)
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "启用该 Server", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            // 本地内建 Server 无需 baseUrl 即可启用；远程 Server 需 baseUrl（ADR-006 5.7）
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it }, enabled = isLocal || baseUrlTrimmed.isNotBlank())
        }
        Spacer(Modifier.height(16.dp))
        PrismButton(
            text = "保存配置",
            enabled = canSave,
            onClick = {
                if (!canSave) { showValidation = true; return@PrismButton }
                // 先落盘 API Key（加密存储，明文不落盘），再保存 Server 配置
                viewModel.saveApiKey(draft.apiKeyRef, apiKey)
                viewModel.saveServer(draft)
            }
        )
        if (showValidation && !canSave) {
            Spacer(Modifier.height(8.dp))
            ValidationError("请修正以上无效输入后再保存")
        }
        if (!isNew) {
            Spacer(Modifier.height(12.dp))
            PrismButton(
                text = "删除 Server",
                variant = PrismButtonVariant.Danger,
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = PrismDanger, modifier = Modifier.size(16.dp))
                },
                onClick = { viewModel.deleteServer(config) }
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

/** Skills 面板。 */
@Composable
private fun SkillsPanel(onSkillClick: (PrismSkill) -> Unit) {
    Column {
        SectionHeader("已安装 · 5", "+ 安装")
        skills.forEach { SkillRow(it, Modifier.padding(horizontal = 20.dp), onClick = { onSkillClick(it) }) }
    }
}

/** 单个 Skill 行（点击打开详情）。 */
@Composable
private fun SkillRow(skill: PrismSkill, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var enabled by remember { mutableStateOf(skill.enabled) }
    PrismCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(skill.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = skill.icon, color = skill.accent, fontSize = 17.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = skill.name, color = PrismText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${skill.origin} · ${skill.desc}",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
            }
            StatusChip(if (enabled) "已启用" else "已停用", enabled)
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it })
        }
    }
}

/** 状态徽章。 */
@Composable
private fun StatusChip(text: String, active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) PrismMint else PrismTextFaint
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = color, fontSize = 10.sp)
    }
}

/** 记忆面板 —— 三层卡片。 */
@Composable
private fun MemoryPanel() {
    Column {
        SectionHeader("三层记忆", "清除")
        MemoryCard("L1 · 会话内", "滑动窗口 + 每 10 轮摘要压缩", "当前会话已压缩 2 次", PrismIndigo, Modifier.padding(horizontal = 20.dp))
        MemoryCard("L2 · 跨会话", "对话历史向量化 · 按话题 top-3 检索", "最近主题：Prism 设计 / 知识库", PrismCyan, Modifier.padding(horizontal = 20.dp))
        MemoryCard("L3 · 用户画像", "偏好：简洁回答 · 中文优先 · 技术向", "2 项显式 · 7 项隐式", PrismMint, Modifier.padding(horizontal = 20.dp))
    }
}

/** 单层记忆卡。 */
@Composable
private fun MemoryCard(title: String, desc: String, meta: String, accent: Color, modifier: Modifier = Modifier) {
    PrismCard(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "◈  ", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(text = title, color = PrismText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(text = desc, color = PrismTextDim, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            Text(text = meta, color = PrismTextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 分组标题。 */
@Composable
private fun SectionHeader(title: String, action: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = PrismTextDim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        Text(text = action, color = PrismIndigo, fontSize = 11.sp)
    }
}

/**
 * 本地 Filesystem Server 的授权目录区块（US-009，ADR-006 5.3）。
 *
 * 展示当前已授权目录，并提供「选择授权目录」按钮触发 `ACTION_OPEN_DOCUMENT_TREE`。
 * 用户选择后：持久化 URI 授权（[android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION] /
 * [android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION]）→ 经
 * [PrismApplication.registerFilesystemRoot] 注册逻辑根目录并持久化。
 */
@Composable
private fun FilesystemAuthorizationSection() {
    val context = LocalContext.current
    val app = context.applicationContext as? PrismApplication ?: return
    val roots by app.safFileAccess.rootsFlow.collectAsState()

    Spacer(Modifier.height(16.dp))
    Text(
        text = "授权目录",
        color = PrismTextDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp
    )
    Spacer(Modifier.height(8.dp))
    if (roots.isEmpty()) {
        Text(
            text = "尚未授权任何目录。授权后 AI 可读取该目录内的文件。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    } else {
        roots.keys.forEach { rootName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = rootName,
                    color = PrismText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrismPanel2)
                        .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { app.removeFilesystemRoot(rootName) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化 URI 授权（跨重启有效，配合 FilesystemRootStore 逻辑注册表）
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            app.registerFilesystemRoot(uri)
        }
    }
    PrismButton(
        text = "选择授权目录",
        variant = PrismButtonVariant.Ghost,
        onClick = { launcher.launch(null) }
    )
}