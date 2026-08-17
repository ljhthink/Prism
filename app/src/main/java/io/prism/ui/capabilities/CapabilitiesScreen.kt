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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.prism.data.McpServerPresets
import io.prism.data.McpServerType
import io.prism.data.SkillSource
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

/**
 * 能力中枢屏幕 —— 深空玻璃肌理（设计规范 v0.4 第 8.3 节，US-002/004/005/008/027/036）。
 *
 * 顶部三段式（MCP 工具 / Skills / 记忆）：
 * - MCP 段接入 [CapabilitiesViewModel]，展示动态、可配置的 MCP Server（US-008）
 * - Skills 段接入 [SkillsViewModel]，展示动态 Skill 列表 + 启停 + 详情弹层（US-027，ADR-013 5.5）
 * - 记忆段接入 [MemoryManagementViewModel]，展示 L1 窗口配置 + L2 跨会话记忆 + L3 用户画像
 *   + 单条删除/编辑/一键清除（US-036，ADR-015 5.7）
 *
 * 点击 MCP Server 行 → 配置弹层；点击 Skill 行 → 详情弹层（展示 manifest 元数据）；
 * 点击 L3 画像行 → 编辑弹层；点击 L1 卡片 → 修改窗口大小 N。
 */
@Composable
fun CapabilitiesScreen(
    viewModel: CapabilitiesViewModel = viewModel(factory = CapabilitiesViewModel.Factory),
    skillsViewModel: SkillsViewModel = viewModel(factory = SkillsViewModel.Factory),
    memoryViewModel: MemoryManagementViewModel = viewModel(factory = MemoryManagementViewModel.Factory)
) {
    var segment by remember { mutableStateOf(CapSegment.MCP) }
    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val skills by skillsViewModel.skills.collectAsState()
    val selectedSkill by skillsViewModel.selectedSkill.collectAsState()
    val executionRecords by skillsViewModel.executionRecords.collectAsState()
    val installState by skillsViewModel.installState.collectAsState()
    var showInstallSheet by remember { mutableStateOf(false) }

    // M5 Phase E（US-036）：记忆管理状态订阅
    val memories by memoryViewModel.memories.collectAsState()
    val profiles by memoryViewModel.profiles.collectAsState()
    val windowSize by memoryViewModel.windowSize.collectAsState()
    val selectedProfile by memoryViewModel.selectedProfile.collectAsState()
    val showClearConfirm by memoryViewModel.showClearConfirm.collectAsState()
    val showWindowSizeEditor by memoryViewModel.showWindowSizeEditor.collectAsState()
    val uiMessage by memoryViewModel.uiMessage.collectAsState()

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
                    SkillsPanel(
                        skills = skills,
                        onSkillClick = { skillsViewModel.selectSkill(it) },
                        onToggle = { id, enabled -> skillsViewModel.setSkillEnabled(id, enabled) },
                        onInstallClick = {
                            skillsViewModel.resetInstallState()
                            showInstallSheet = true
                        }
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = segment == CapSegment.MEMORY,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut()
                ) {
                    MemoryPanel(
                        memories = memories,
                        profiles = profiles,
                        windowSize = windowSize,
                        viewModel = memoryViewModel
                    )
                }
            }
        }

        // MCP Server 配置弹层
        PrismSheetHost(visible = selectedServer != null, onDismiss = { viewModel.selectServer(null) }) {
            selectedServer?.let { McpConfigSheet(it, viewModel) }
        }

        // Skill 详情弹层（US-027 / US-029，ADR-013 5.5 / 5.7）—— 展示 manifest 元数据 + 启停开关 + 最近 10 次执行记录
        PrismSheetHost(visible = selectedSkill != null, onDismiss = { skillsViewModel.selectSkill(null) }) {
            selectedSkill?.let {
                SkillDetailSheet(
                    skill = it,
                    executionRecords = executionRecords,
                    onToggle = { enabled -> skillsViewModel.setSkillEnabled(it.config.id, enabled) },
                    onDelete = { skillsViewModel.deleteSkill(it.config.id) }
                )
            }
        }

        // Skill 远程安装弹层（US-028，ADR-013 5.6）
        PrismSheetHost(
            visible = showInstallSheet,
            onDismiss = {
                // 安装进行中不允许 dismiss（防误触中断下载）
                if (installState !is SkillsViewModel.InstallState.Installing) {
                    showInstallSheet = false
                    skillsViewModel.resetInstallState()
                }
            }
        ) {
            SkillInstallSheet(
                state = installState,
                onInstall = { url -> skillsViewModel.installFromUrl(url) },
                onDismiss = {
                    showInstallSheet = false
                    skillsViewModel.resetInstallState()
                }
            )
        }

        // M5 Phase E（US-036）：L3 用户画像编辑弹层
        PrismSheetHost(
            visible = selectedProfile != null,
            onDismiss = { memoryViewModel.selectProfileForEdit(null) }
        ) {
            selectedProfile?.let { profile ->
                ProfileEditSheet(
                    profile = profile,
                    onSave = { key, value, existingId ->
                        memoryViewModel.saveProfile(key, value, existingId)
                    }
                )
            }
        }

        // M5 Phase E（US-036）：一键清除二次确认弹层
        PrismSheetHost(
            visible = showClearConfirm,
            onDismiss = { memoryViewModel.hideClearConfirm() }
        ) {
            ClearConfirmSheet(
                memoryCount = memories.size.toLong(),
                profileCount = profiles.size.toLong(),
                onConfirm = { memoryViewModel.clearAll() },
                onCancel = { memoryViewModel.hideClearConfirm() }
            )
        }

        // M5 Phase E（US-036，US-032 AC-4）：L1 窗口大小编辑弹层
        PrismSheetHost(
            visible = showWindowSizeEditor,
            onDismiss = { memoryViewModel.hideWindowSizeEditor() }
        ) {
            WindowSizeEditSheet(
                currentSize = windowSize,
                onSet = { n -> memoryViewModel.setWindowSize(n) },
                onCancel = { memoryViewModel.hideWindowSizeEditor() }
            )
        }

        // M5 Phase E（US-036）：UI 消息横幅（错误/成功一次性提示）
        UiMessageBanner(message = uiMessage, onConsume = { memoryViewModel.consumeUiMessage() })
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
        local.forEach { server ->
            // UX-001 问题 8（ADR-022）：本地 Server 也观测连接状态（工具数探测），
            // 避免「未实现工具也显示连接成功」——McpRow 默认回退 isEnabled 绿点（误导）。
            val status: CapabilitiesViewModel.ConnectionStatus? = if (server.isEnabled) {
                val flow = remember(server.id, server.isEnabled) { viewModel.observeConnectionStatus(server) }
                flow.collectAsState(initial = CapabilitiesViewModel.ConnectionStatus.Connecting).value
            } else null
            McpRow(
                server,
                Modifier.padding(horizontal = 20.dp),
                connectionStatus = status,
                onClick = { onServerClick(server) },
                onToggle = { checked -> viewModel.setEnabled(server.id, checked) }
            )
        }

        SectionHeader("远程模板 · ${remote.size}", "+ 自定义")
        if (remote.isEmpty()) EmptySection("暂无远程 Server，点击下方预设一键添加")
        remote.forEach { RemoteMcpRow(it, viewModel, Modifier.padding(horizontal = 20.dp), onClick = { onServerClick(it) }) }

        // 预设快捷添加（基于 McpServerPresets；本地零配置直接创建，远程预设填 Key 后添加）
        SectionHeader("从预设添加", "模板")
        CapabilitiesViewModel.presets.forEach { preset ->
            PresetRow(preset, Modifier.padding(horizontal = 20.dp), onClick = {
                if (preset.serverType == McpServerType.LOCAL) {
                    viewModel.createFromPreset(preset)
                } else {
                    viewModel.startPresetEdit(preset)
                }
            })
        }
    }
}

/**
 * 远程 Server 行 —— 在 [McpRow] 基础上叠加连接状态观测（US-010）。
 *
 * 仅当 Server 已启用时才观测连接状态（[CapabilitiesViewModel.observeConnectionStatus]），
 * 避免对未启用 / 未配置的 Server 发起无谓的网络握手；未启用时回退默认启停指示。
 */
@Composable
private fun RemoteMcpRow(
    server: McpServerConfig,
    viewModel: CapabilitiesViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val status: CapabilitiesViewModel.ConnectionStatus? = if (server.isEnabled) {
        // key 含 baseUrl：编辑后 baseUrl 变化时重建 Flow，避免状态徽章陈旧（guardrail L-01）
        val flow = remember(server.id, server.baseUrl, server.isEnabled) { viewModel.observeConnectionStatus(server) }
        flow.collectAsState(initial = CapabilitiesViewModel.ConnectionStatus.Connecting).value
    } else {
        null
    }
    McpRow(
        server = server,
        modifier = modifier,
        onClick = onClick,
        onToggle = { checked -> viewModel.setEnabled(server.id, checked) },
        connectionStatus = status
    )
}

/** 单个 MCP Server 行（点击打开配置）。 */
@Composable
private fun McpRow(
    server: McpServerConfig,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    connectionStatus: CapabilitiesViewModel.ConnectionStatus? = null
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
                // O2（PRD UXR8）：本地内置 Server 副标题展示功能描述（baseUrl 恒空，此前恒为占位文案）；
                // 远程 Server 保留 baseUrl（用户需要看到端点与自定义 Server 区分预设来源）
                val subtitle = if (server.serverType == McpServerType.LOCAL) {
                    McpServerPresets.findMetaByName(server.name)?.description ?: server.baseUrl.ifEmpty { "本地内置 · 零配置" }
                } else {
                    server.baseUrl
                }
                Text(text = subtitle, color = PrismTextFaint, fontSize = 11.sp)
            }
            // 远程 Server 已启用时展示连接状态（连接中/已连接/错误）；否则展示启停指示
            if (connectionStatus != null) {
                ConnectionStatusBadge(connectionStatus)
            } else {
                PrismStatusDot(if (server.isEnabled) PrismDotState.OK else PrismDotState.ERR)
            }
            // 本地内建 Server（零配置）无需 baseUrl 即可启用；远程 Server 需 baseUrl（guardrail M2）
            PrismSwitch(
                checked = server.isEnabled,
                onCheckedChange = onToggle,
                enabled = server.serverType == McpServerType.LOCAL || server.baseUrl.isNotBlank()
            )
        }
    }
}

/** 连接状态徽章（US-010：连接中 / 已连接 · N 工具 / 连接失败）。 */
@Composable
private fun ConnectionStatusBadge(status: CapabilitiesViewModel.ConnectionStatus) {
    when (status) {
        is CapabilitiesViewModel.ConnectionStatus.Connecting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(text = "连接中", color = PrismTextFaint, fontSize = 10.sp)
        }
        is CapabilitiesViewModel.ConnectionStatus.Connected -> Text(
            text = "已连接 · ${status.toolCount}",
            color = PrismMint,
            fontSize = 10.sp
        )
        is CapabilitiesViewModel.ConnectionStatus.Error -> Text(
            text = "连接失败",
            color = PrismDanger,
            fontSize = 10.sp
        )
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
    // O2（PRD UXR8）：预设行展示功能描述（用户知道每个工具是干什么的），元数据查不到回退既有占位
    val meta = McpServerPresets.findMetaByName(preset.name)
    val tag = if (preset.serverType == McpServerType.LOCAL) "本地内置 · 零配置" else "远程模板 · 需填 Key"
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
                    text = meta?.description ?: tag,
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
                if (meta != null) {
                    Text(text = tag, color = PrismTextFaint, fontSize = 10.sp)
                }
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
        // O2（PRD UXR8，D-10）：预设来源的远程 Server 展示 API Key 获取指引
        //（按名称匹配预设元数据；从预设创建的 Server 名称与预设一致即命中）
        val keyHint = McpServerPresets.findMetaByName(config.name)?.keyHint
        if (!isLocal && !keyHint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Key 获取：$keyHint",
                color = PrismTextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
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
                // 先落盘 API Key（加密存储，明文不落盘），再保存 Server 配置。
                // 仅当用户新填入 Key 才落盘：编辑既有远程 Server 时 apiKey 字段初始为空，
                // 若无条件覆盖会清空已存密钥（guardrail M-01）；留空则保留原密钥。
                if (apiKey.isNotBlank()) {
                    viewModel.saveApiKey(draft.apiKeyRef, apiKey)
                }
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

/**
 * Skills 面板（US-027 / US-028，ADR-013 5.5 / 5.6）—— 从 [SkillsViewModel.skills] 取动态数据。
 *
 * - 列表为空时展示空态占位（内置 Skill 扫描失败或无用户自建/远程 Skill 时）
 * - 计数随实际 Skill 数量动态变化（修复 R-1：原硬编码 "已安装 · 5"）
 * - 点击 Skill 行打开详情弹层
 * - 启用/禁用开关落库（经 [SkillsViewModel.setSkillEnabled]）
 * - US-028："+ 安装" action 可点击，触发远程安装弹层
 */
@Composable
private fun SkillsPanel(
    skills: List<SkillUiModel>,
    onSkillClick: (SkillUiModel) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onInstallClick: () -> Unit
) {
    Column {
        SectionHeader("已安装 · ${skills.size}", "+ 安装", onActionClick = onInstallClick)
        if (skills.isEmpty()) {
            EmptySection("暂无 Skill，启动时自动扫描内置预设；点击右上角 + 从 URL 安装")
        }
        skills.forEach { skill ->
            SkillRow(
                skill = skill,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = { onSkillClick(skill) },
                onToggle = { enabled -> onToggle(skill.config.id, enabled) }
            )
        }
    }
}

/**
 * 单个 Skill 行（点击打开详情，US-027）。
 *
 * - icon 与 accent 按 [SkillSource] 映射（与 [SkillsViewModel.toUiModel] 的 icon 映射对齐）
 * - 启用状态实时取自 [SkillUiModel.config].isEnabled（不再用本地 remember state，
 *   避免与持久化状态漂移，BR-concurrency-004）
 * - 文件缺失（manifest==null）时降级展示"解析失败"，仍允许启停操作
 * - 来源标签（内置/本地/远程）经 [sourceToLabel] 映射
 */
@Composable
private fun SkillRow(
    skill: SkillUiModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val accent = sourceToAccent(skill.config.source)
    val sourceLabel = sourceToLabel(skill.config.source)
    val desc = skill.manifest?.description ?: "解析失败（SKILL.md 缺失或格式错误）"
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
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = skill.icon, color = accent, fontSize = 17.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.config.displayName.ifEmpty { skill.config.name },
                    color = PrismText,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$sourceLabel · $desc",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
            }
            StatusChip(if (skill.config.isEnabled) "已启用" else "已停用", skill.config.isEnabled)
            PrismSwitch(checked = skill.config.isEnabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * 按 [SkillSource] 映射 accent 色（UI 层职责，与 [SkillsViewModel.toUiModel] 的 icon 映射对齐）。
 *
 * internal 便于纯 JVM 单元测试（BR-testing-004）。
 */
internal fun sourceToAccent(source: String): Color = when (source) {
    SkillSource.LOCAL_BUILTIN -> PrismCyan
    SkillSource.LOCAL_USER -> PrismIndigo
    SkillSource.REMOTE -> PrismMint
    else -> PrismTextFaint
}

/**
 * 按 [SkillSource] 映射展示标签。
 *
 * internal 便于纯 JVM 单元测试（BR-testing-004）。
 */
internal fun sourceToLabel(source: String): String = when (source) {
    SkillSource.LOCAL_BUILTIN -> "内置"
    SkillSource.LOCAL_USER -> "本地"
    SkillSource.REMOTE -> "远程"
    else -> "未知"
}

/**
 * Skill 详情弹层（US-027 / US-029，ADR-013 5.5 / 5.7）—— 展示 manifest 元数据 + 启停开关 + 执行记录。
 *
 * **展示内容**：
 * - 标题：displayName（fallback 到 slug name）
 * - 副标题：来源标签 + 版本号
 * - 描述（manifest.description，缺失时降级提示）
 * - 指令正文（manifest.body，默认截断预览，可点击展开全文）
 * - 工具声明（manifest.tools，如有）
 * - systemPrompt 片段（如有，默认截断预览，可点击展开全文）
 * - 元数据：maxRounds / isInstalled / 时间戳
 * - 启用/禁用开关（落库）
 * - 执行记录（US-029）：最近 10 次，每条含开始时间 / 耗时 / 状态 / 可展开工具调用链
 * - 删除按钮（修复：缺失删除功能，任何来源 Skill 均可删除）
 *
 * **manifest==null 降级**：仅展示 config 信息 + "解析失败" 提示，仍允许启停与删除。
 *
 * @param skill 当前选中的 Skill UI 模型
 * @param executionRecords 该 Skill 的最近 10 次执行记录（按 startedAt 降序，来自 [SkillsViewModel.executionRecords]）
 * @param onToggle 启用/禁用回调（落库）
 * @param onDelete 删除回调（落库 isHidden + 清理执行记录 + 刷新注册中心）
 */
@Composable
private fun SkillDetailSheet(
    skill: SkillUiModel,
    executionRecords: List<io.prism.data.SkillExecutionRecord>,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val config = skill.config
    val manifest = skill.manifest
    val sourceLabel = sourceToLabel(config.source)
    val versionText = manifest?.version?.let { "v$it" } ?: "v${config.version}"
    // 展开全文状态（body / systemPrompt 默认收起，点击展开避免弹层过长）
    var bodyExpanded by remember(config.id) { mutableStateOf(false) }
    var promptExpanded by remember(config.id) { mutableStateOf(false) }
    // 删除二次确认
    var showDeleteConfirm by remember(config.id) { mutableStateOf(false) }

    PrismSheet(
        title = config.displayName.ifEmpty { config.name },
        subtitle = "$sourceLabel · $versionText",
        // BR-ui-003（guardrail TKN-UXR8-FIX-GUARDRAIL-001 LOW#3）：关键操作按钮置于 footer
        // 固定底部，长内容（指令/执行记录）滚动时删除按钮始终可见，不随内容滚出屏幕。
        footer = {
            Column {
                PrismButton(
                    text = "删除 Skill",
                    variant = PrismButtonVariant.Danger,
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = PrismDanger, modifier = Modifier.size(16.dp))
                    },
                    onClick = { showDeleteConfirm = true }
                )
                // 删除二次确认（内联于 footer，避免误触）
                if (showDeleteConfirm) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrismDanger.copy(alpha = 0.06f))
                            .border(1.dp, PrismDanger.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text = "确认删除「${config.displayName.ifEmpty { config.name }}」？",
                                color = PrismText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "删除后该 Skill 将从列表移除且不再恢复（含执行记录）。" +
                                    (if (config.source == io.prism.data.SkillSource.LOCAL_BUILTIN)
                                        "内置 Skill 无法删除文件，将标记为隐藏。"
                                    else
                                        "磁盘目录将被删除。"),
                                color = PrismTextDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrismButton(
                                    text = "取消",
                                    variant = PrismButtonVariant.Ghost,
                                    modifier = Modifier.weight(1f),
                                    onClick = { showDeleteConfirm = false }
                                )
                                PrismButton(
                                    text = "确认删除",
                                    variant = PrismButtonVariant.Danger,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        showDeleteConfirm = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        // 描述
        DetailSection("描述") {
            Text(
                text = manifest?.description ?: "解析失败（SKILL.md 缺失或格式错误）",
                color = if (manifest != null) PrismTextDim else PrismDanger,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(14.dp))

        // 指令正文（默认截断预览，点击展开全文 —— 修复内容被截断的问题）
        if (manifest != null && manifest.body.isNotBlank()) {
            DetailSection("指令") {
                ExpandableText(
                    text = manifest.body,
                    expanded = bodyExpanded,
                    onToggle = { bodyExpanded = !bodyExpanded },
                    previewMaxLen = BODY_PREVIEW_MAX_LEN,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // 工具声明（如有）
        if (manifest?.tools?.isNotEmpty() == true) {
            DetailSection("工具 · ${manifest.tools.size}") {
                manifest.tools.forEach { tool ->
                    Text(
                        text = "• ${tool.name}：${tool.description}",
                        color = PrismTextDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // systemPrompt 片段（默认截断预览，点击展开全文 —— 修复内容被截断的问题）
        if (manifest?.systemPrompt?.isNotBlank() == true) {
            DetailSection("System Prompt") {
                ExpandableText(
                    text = manifest.systemPrompt,
                    expanded = promptExpanded,
                    onToggle = { promptExpanded = !promptExpanded },
                    previewMaxLen = PROMPT_PREVIEW_MAX_LEN,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // 元数据
        DetailSection("元数据") {
            MetadataRow("最大轮次", manifest?.maxRounds?.toString() ?: "10（默认）")
            MetadataRow("安装状态", if (config.isInstalled) "已安装" else "未安装")
            MetadataRow("创建时间", formatTimestamp(config.createdAt))
            MetadataRow("更新时间", formatTimestamp(config.updatedAt))
            if (config.dependsOnMcpServers.isNotEmpty()) {
                MetadataRow("依赖 MCP", config.dependsOnMcpServers.joinToString(", "))
            }
        }
        Spacer(Modifier.height(16.dp))

        // 启用/禁用开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "启用该 Skill", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PrismSwitch(checked = config.isEnabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(16.dp))

        // 执行记录（US-029，ADR-013 5.7）—— 最近 10 次 + 可展开工具调用链
        DetailSection("执行记录 · ${executionRecords.size}") {
            if (executionRecords.isEmpty()) {
                Text(
                    text = "暂无执行记录（启用该 Skill 后，对话中触发工具调用会自动记录）",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            } else {
                executionRecords.forEach { record ->
                    ExecutionRecordItem(record)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 可展开文本（默认截断预览，点击展开全文 —— 修复 Skill 内容被截断的问题）。
 *
 * - [expanded] 为 true 时展示完整文本
 * - 为 false 且文本超过 [previewMaxLen] 时截断 + 展示「展开全文」提示
 * - 文本未超限时直接展示完整内容（无展开按钮）
 *
 * @param text 原始文本
 * @param expanded 是否展开全文
 * @param onToggle 展开/收起切换回调
 * @param previewMaxLen 预览截断长度（字符）
 * @param fontSize / [lineHeight] 文本样式
 */
@Composable
private fun ExpandableText(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    previewMaxLen: Int,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit
) {
    val truncated = text.length > previewMaxLen
    Column {
        Text(
            text = if (expanded || !truncated) text else text.take(previewMaxLen) + "\n…",
            color = PrismTextDim,
            fontSize = fontSize,
            lineHeight = lineHeight
        )
        if (truncated) {
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) "▲ 收起" else "▼ 展开全文",
                    color = PrismIndigo,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * 单条执行记录项（US-029，ADR-013 5.7）。
 *
 * **展示**：
 * - 第一行：状态徽章 + 开始时间 + 耗时 + 工具调用数
 * - 第二行（可选）：错误信息（FAIL/CANCELLED 时展示，已脱敏）
 * - 可展开工具调用链：点击展开后逐条展示 toolName / arguments / result / durationMs / status
 *
 * **状态色映射**（[executionStatusColor]）：
 * - SUCCESS → PrismMint（薄荷绿）
 * - FAIL → PrismDanger（警示红）
 * - CANCELLED → PrismTextFaint（暗灰）
 *
 * @param record 单条执行记录
 */
@Composable
private fun ExecutionRecordItem(record: io.prism.data.SkillExecutionRecord) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = executionStatusColor(record.status)
    val statusLabel = executionStatusLabel(record.status)
    val hasToolCalls = record.toolCalls.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel)
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 第一行：状态徽章 + 开始时间 + 耗时 + 工具调用数
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(text = statusLabel, active = record.status == io.prism.data.ExecutionStatus.SUCCESS)
            Text(
                text = formatTimestamp(record.startedAt),
                color = PrismTextDim,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${record.durationMs}ms",
                color = PrismTextFaint,
                fontSize = 11.sp
            )
            if (hasToolCalls) {
                Text(
                    text = "· ${record.toolCalls.size} 工具",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
            }
        }
        // 错误信息（FAIL/CANCELLED 时展示，已脱敏）
        // 用局部 val 避免 smart cast 失败（errorMessage 是 var mutable property）
        val errMsg = record.errorMessage
        if (!errMsg.isNullOrBlank()) {
            Text(
                text = errMsg,
                color = statusColor,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // 可展开工具调用链
        if (hasToolCalls) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { expanded = !expanded }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (expanded) "▼ 工具调用链" else "▶ 工具调用链",
                    color = PrismIndigo,
                    fontSize = 11.sp
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    record.toolCalls.forEachIndexed { index, tc ->
                        ToolCallItem(tc, isLast = index == record.toolCalls.lastIndex)
                    }
                }
            }
        }
    }
}

/**
 * 单条工具调用项（[ExecutionRecordItem] 展开后的子项，US-029）。
 *
 * **展示**：toolName（含命名空间前缀） / 状态色点 / 耗时 / arguments（JSON，截断 200 字符）/ result（截断 200 字符）
 *
 * @param tc 工具调用记录
 * @param isLast 是否最后一条（最后一条不渲染底部分隔）
 */
@Composable
private fun ToolCallItem(tc: io.prism.data.ToolCallRecord, isLast: Boolean) {
    val statusColor = executionStatusColor(tc.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLast) 0.dp else 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor)
            )
            Text(
                text = tc.toolName,
                color = PrismText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${tc.durationMs}ms",
                color = PrismTextFaint,
                fontSize = 10.5.sp
            )
        }
        Text(
            text = "args: ${tc.arguments.take(MAX_TOOL_ARG_PREVIEW_LEN)}",
            color = PrismTextFaint,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
        )
        Text(
            text = "result: ${tc.result.take(MAX_TOOL_RESULT_PREVIEW_LEN)}",
            color = PrismTextFaint,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
        )
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 12.dp)
                    .height(1.dp)
                    .background(PrismLine)
            )
        }
    }
}

/**
 * 执行状态 → 展示色映射（US-029）。
 *
 * internal 便于纯 JVM 单元测试（BR-testing-004）。
 */
internal fun executionStatusColor(status: String): Color = when (status) {
    io.prism.data.ExecutionStatus.SUCCESS -> PrismMint
    io.prism.data.ExecutionStatus.FAIL -> PrismDanger
    io.prism.data.ExecutionStatus.CANCELLED -> PrismTextFaint
    else -> PrismTextFaint
}

/**
 * 执行状态 → 中文标签映射（US-029）。
 *
 * internal 便于纯 JVM 单元测试（BR-testing-004）。
 */
internal fun executionStatusLabel(status: String): String = when (status) {
    io.prism.data.ExecutionStatus.SUCCESS -> "成功"
    io.prism.data.ExecutionStatus.FAIL -> "失败"
    io.prism.data.ExecutionStatus.CANCELLED -> "已取消"
    else -> "未知"
}

/** 详情区块标签。 */
@Composable
private fun DetailSection(label: String, content: @Composable () -> Unit) {
    Text(
        text = label,
        color = PrismTextDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
    content()
}

/**
 * Skill 远程安装弹层（US-028，ADR-013 5.6）。
 *
 * **交互流程**：
 * 1. 用户输入 URL（必须 https，扩展名 .skill.md / .zip / .md）
 * 2. 点击「安装」→ 触发 [onInstall] → state 变为 [InstallState.Installing]
 * 3. 安装中：禁用输入框 + 按钮，展示 loading
 * 4. 成功：展示 slug + 自动关闭弹层（[onDismiss]）
 * 5. 失败：展示脱敏错误信息，允许重试
 *
 * **安全提示**：展示「标准校验」策略说明（https + 大小限制 + 沙箱解析 + zip slip 防护），
 * 让用户了解安全边界。
 *
 * @param state 当前安装状态（来自 [SkillsViewModel.installState]）
 * @param onInstall 安装回调，接收 URL
 * @param onDismiss 关闭弹层回调（安装进行中由调用方阻止 dismiss）
 */
@Composable
private fun SkillInstallSheet(
    state: SkillsViewModel.InstallState,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    val isInstalling = state is SkillsViewModel.InstallState.Installing
    // URL 输入校验：非空 + https 前缀（与 SkillDownloader.validateUrl 第一道防线对齐，UI 即时反馈）
    val urlTrimmed = url.trim()
    val urlValid = urlTrimmed.startsWith("https://") && urlTrimmed.length > 8
    val canSubmit = urlValid && !isInstalling

    PrismSheet(
        title = "从 URL 安装 Skill",
        subtitle = "标准校验 · https · ≤10MB · 沙箱解析"
    ) {
        PrismField(
            label = "Skill URL",
            value = url,
            onValueChange = { if (!isInstalling) url = it },
            placeholder = "https://example.com/skill.skill.md",
            hint = "支持 .skill.md 单文件或 .zip 打包（含 SKILL.md）"
        )
        if (url.isNotEmpty() && !urlValid) {
            ValidationError("URL 需以 https:// 开头")
        }
        Spacer(Modifier.height(12.dp))

        // 安全策略说明（让用户了解校验边界）
        Text(
            text = "校验策略：仅 https · Content-Length ≤10MB · Content-Type 白名单 · " +
                "ZIP slip 防护 · YAML 沙箱解析 · slug 格式校验 · 30s 超时",
            color = PrismTextFaint,
            fontSize = 10.5.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(16.dp))

        // 状态展示
        when (state) {
            is SkillsViewModel.InstallState.Installing -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Text(text = "正在下载并校验…", color = PrismTextDim, fontSize = 12.sp)
                }
            }
            is SkillsViewModel.InstallState.Success -> {
                Text(text = "✓ 安装成功：${state.slug}", color = PrismMint, fontSize = 12.sp)
            }
            is SkillsViewModel.InstallState.Fail -> {
                ValidationError(state.message)
            }
            is SkillsViewModel.InstallState.Idle -> { /* 无状态展示 */ }
        }
        if (state is SkillsViewModel.InstallState.Success || state is SkillsViewModel.InstallState.Fail) {
            Spacer(Modifier.height(12.dp))
        }

        PrismButton(
            text = if (isInstalling) "安装中…" else "安装 Skill",
            enabled = canSubmit,
            onClick = { onInstall(urlTrimmed) }
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(
            text = if (state is SkillsViewModel.InstallState.Success) "完成" else "取消",
            variant = PrismButtonVariant.Ghost,
            enabled = !isInstalling,
            onClick = onDismiss
        )
    }
}

/** 元数据键值行。 */
@Composable
private fun MetadataRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = key, color = PrismTextFaint, fontSize = 11.5.sp)
        Text(text = value, color = PrismTextDim, fontSize = 11.5.sp)
    }
}

/**
 * 时间戳格式化（毫秒 → yyyy-MM-dd HH:mm）。
 *
 * internal 便于纯 JVM 单元测试（BR-testing-004）。
 * 边界：ms <= 0 返回占位符 "—"（未设置时间戳的 SkillConfig）。
 */
internal fun formatTimestamp(ms: Long): String {
    if (ms <= 0) return "—"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}

/** Skill 详情弹层 body 预览最大长度（字符）。 */
private const val BODY_PREVIEW_MAX_LEN = 500

/** Skill 详情弹层 systemPrompt 预览最大长度（字符）。 */
private const val PROMPT_PREVIEW_MAX_LEN = 200

/** 执行记录工具调用 arguments 预览最大长度（字符，US-029）。 */
private const val MAX_TOOL_ARG_PREVIEW_LEN = 200

/** 执行记录工具调用 result 预览最大长度（字符，US-029，与 SkillExecutor.MAX_RESULT_PREVIEW_LEN 对齐）。 */
private const val MAX_TOOL_RESULT_PREVIEW_LEN = 200

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

/**
 * 记忆面板 —— 三层记忆真实数据展示（US-036，ADR-015 5.7）。
 *
 * **结构**：
 * - 顶部 "三层记忆 · 一键清除" 入口（点击触发二次确认弹层）
 * - L1 卡片：展示当前窗口大小 N，点击可修改（US-032 AC-4 运行时配置入口）
 * - L2 列表：跨会话记忆条目，每条可单条删除（US-036 AC-2）
 * - L3 列表：用户画像条目，每条可点击编辑或删除（US-036 AC-3）
 * - L3 头部 "+ 新增" action 触发新建画像弹层
 *
 * **空态**：L2/L3 为空时展示空态提示文案，引导用户使用。
 */
@Composable
private fun MemoryPanel(
    memories: List<io.prism.data.MemoryRecord>,
    profiles: List<io.prism.data.UserProfile>,
    windowSize: Int,
    viewModel: MemoryManagementViewModel
) {
    Column {
        SectionHeader("三层记忆 · 一键清除", "清除", onActionClick = { viewModel.showClearConfirm() })

        // L1 会话内 —— 滑动窗口 + 摘要压缩（点击修改 N）
        MemoryOverviewCard(
            title = "L1 · 会话内",
            desc = "滑动窗口 + 每 N 轮摘要压缩",
            meta = "当前 N = $windowSize（点击修改）",
            accent = PrismIndigo,
            modifier = Modifier.padding(horizontal = 20.dp),
            onClick = { viewModel.showWindowSizeEditor() }
        )

        // L2 跨会话 —— 对话历史向量化存储 + top-k 检索
        SectionHeader("L2 · 跨会话 · ${memories.size} 条", null)
        if (memories.isEmpty()) {
            EmptySection("暂无跨会话记忆，对话结束时自动保存关键片段")
        } else {
            memories.forEach { record ->
                MemoryRow(
                    record = record,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    onDelete = { viewModel.deleteMemory(record.id) }
                )
            }
        }

        // L3 用户画像 —— 显式 + 隐式偏好
        SectionHeader(
            title = "L3 · 用户画像 · ${profiles.size} 条",
            action = "+ 新增",
            onActionClick = {
                // 传入 id=0 + 空 key/value 表示新建
                viewModel.selectProfileForEdit(io.prism.data.UserProfile(key = "", value = ""))
            }
        )
        if (profiles.isEmpty()) {
            EmptySection("暂无用户画像，可在对话中设定偏好或由 AI 自动抽取")
        } else {
            profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    onClick = { viewModel.selectProfileForEdit(profile) },
                    onDelete = { viewModel.deleteProfile(profile.key) }
                )
            }
        }
    }
}

/**
 * L1 概览卡（US-036 记忆管理 UI 入口）—— 可点击修改窗口大小 N。
 *
 * 与原 Mock [MemoryCard] 区别：[onClick] 非空时整卡可点击触发 L1 编辑弹层。
 */
@Composable
private fun MemoryOverviewCard(
    title: String,
    desc: String,
    meta: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    PrismCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        onClick = onClick
    ) {
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

/**
 * 单条 L2 跨会话记忆行（US-036 AC-2 单条删除）。
 *
 * **展示**：
 * - 左侧 accent 色点
 * - 中间：sessionId 简写（前 8 位）+ content 截断（前 80 字符）+ 时间戳
 * - 右侧：删除按钮（✕）
 */
@Composable
private fun MemoryRow(
    record: io.prism.data.MemoryRecord,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit
) {
    PrismCard(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PrismCyan)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.content.take(MAX_MEMORY_CONTENT_PREVIEW_LEN) +
                        if (record.content.length > MAX_MEMORY_CONTENT_PREVIEW_LEN) "…" else "",
                    color = PrismText,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    maxLines = 2
                )
                Text(
                    text = "会话 ${record.sessionId.take(8)} · ${formatTimestamp(record.timestamp)} · 第 ${record.turnCount} 轮",
                    color = PrismTextFaint,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
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
 * 单条 L3 用户画像行（US-036 AC-3 编辑/删除）。
 *
 * **展示**：
 * - 左侧 accent 色点（EXPLICIT=PrismIndigo / IMPLICIT=PrismMint）
 * - 中间：key + value + category 标签
 * - 右侧：删除按钮
 * - 整行点击 → 编辑弹层
 *
 * @param profile 待展示的画像
 * @param onClick 整行点击回调（编辑）
 * @param onDelete 删除回调
 */
@Composable
private fun ProfileRow(
    profile: io.prism.data.UserProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isExplicit = profile.category == io.prism.data.ProfileCategory.EXPLICIT.name
    val accent = if (isExplicit) PrismIndigo else PrismMint
    val categoryLabel = if (isExplicit) "显式" else "隐式"
    PrismCard(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // O1：显式偏好显示自然语言原句；隐式偏好 value 为 LLM 抽取短语，需 key 提供语义
                    text = if (isExplicit) profile.value else "${profile.key}：${profile.value}",
                    color = PrismText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$categoryLabel · 更新于 ${formatTimestamp(profile.updatedAt)}",
                    color = PrismTextFaint,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
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
 * L3 画像编辑弹层（US-036 AC-3，新建/编辑共用，O1/PRD UXR8 自然语言化）。
 *
 * **逻辑**：
 * - 标题：根据 profile.id 区分"添加偏好"/"编辑偏好"
 * - 单字段：自然语言句子（如"我喜欢简洁的回复"），无需理解键值语义（O1）
 * - 保存按钮：调用 [onSave]，由 ViewModel 自动推导 key（新建）或保留原 key（编辑）
 * - key 为内部标识，不暴露给用户
 *
 * @param profile 待编辑的画像（id=0 + 空 value 表示新建）
 * @param onSave 保存回调，参数：key（编辑传原 key，新建传空串）, value, existingId
 */
@Composable
private fun ProfileEditSheet(
    profile: io.prism.data.UserProfile,
    onSave: (key: String, value: String, existingId: Long) -> Unit
) {
    val isNew = profile.id == 0L
    var value by remember(profile.id) { mutableStateOf(profile.value) }
    var showValidation by remember(profile.id) { mutableStateOf(false) }

    val valueValid = value.trim().isNotEmpty()
    val canSave = valueValid
    val isExplicit = profile.category == io.prism.data.ProfileCategory.EXPLICIT.name
    val subtitle = when {
        isNew -> "用一句话描述，无需填写键值"
        isExplicit -> "显式偏好 · 你主动设定"
        else -> "隐式偏好 · AI 自动学习"
    }

    PrismSheet(
        title = if (isNew) "添加用户偏好" else "编辑用户偏好",
        subtitle = subtitle
    ) {
        PrismField(
            label = "偏好描述",
            value = value,
            onValueChange = { value = it },
            placeholder = "如：我喜欢简洁的回复 / 回复用中文 / 我是 Python 开发者",
            hint = "一条一句自然描述即可，AI 跨会话记住并遵循"
        )
        if (showValidation && !valueValid) ValidationError("偏好描述不能为空")
        Spacer(Modifier.height(20.dp))
        PrismButton(
            text = "保存",
            enabled = canSave,
            onClick = {
                if (!canSave) {
                    showValidation = true
                    return@PrismButton
                }
                onSave(if (isNew) "" else profile.key, value.trim(), profile.id)
            }
        )
    }
}

/**
 * "一键清除"二次确认弹层（US-036 AC-4）。
 *
 * **展示**：明确告知用户将删除的 L2 + L3 条数，二次确认避免误操作（GDPR 式控制权）。
 *
 * @param memoryCount 待删除的 L2 记忆条数
 * @param profileCount 待删除的 L3 画像条数
 * @param onConfirm 确认删除回调
 * @param onCancel 取消回调
 */
@Composable
private fun ClearConfirmSheet(
    memoryCount: Long,
    profileCount: Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    PrismSheet(
        title = "一键清除所有记忆",
        subtitle = "不可恢复 · 请谨慎操作"
    ) {
        Text(
            text = "将永久删除以下记忆数据：",
            color = PrismText,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "• L2 跨会话记忆：$memoryCount 条",
            color = PrismTextDim,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Text(
            text = "• L3 用户画像：$profileCount 条",
            color = PrismTextDim,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Text(
            text = "• L1 窗口大小配置保留（仅清数据，不清配置）",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(20.dp))
        PrismButton(
            text = "确认清除",
            variant = PrismButtonVariant.Danger,
            leadingIcon = {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = PrismDanger, modifier = Modifier.size(16.dp))
            },
            onClick = onConfirm
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(
            text = "取消",
            variant = PrismButtonVariant.Ghost,
            onClick = onCancel
        )
    }
}

/**
 * L1 窗口大小编辑弹层（US-032 AC-4，US-036 暴露入口）。
 *
 * **校验**：N 在 1..50 范围内（与 [io.prism.memory.MemoryConfigRepository] 一致）。
 *
 * @param currentSize 当前 N 值
 * @param onSet 设置回调
 * @param onCancel 取消回调
 */
@Composable
private fun WindowSizeEditSheet(
    currentSize: Int,
    onSet: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var input by remember { mutableStateOf(currentSize.toString()) }
    var showValidation by remember { mutableStateOf(false) }

    val parsed = input.trim().toIntOrNull()
    val valid = parsed != null && parsed in io.prism.memory.MemoryConfigRepository.MIN_WINDOW_SIZE..io.prism.memory.MemoryConfigRepository.MAX_WINDOW_SIZE

    PrismSheet(
        title = "设置滑动窗口大小 N",
        subtitle = "L1 会话内记忆 · 默认 ${io.prism.memory.MemoryConfigRepository.DEFAULT_WINDOW_SIZE}"
    ) {
        Text(
            text = "保留最近 N 轮原始消息，超出部分自动触发摘要压缩。N 越大上下文越完整但 token 消耗越高；N 越小越省 token 但可能丢失早期上下文。",
            color = PrismTextDim,
            fontSize = 11.5.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "N 值",
            value = input,
            onValueChange = { input = it.filter { ch -> ch.isDigit() } },
            placeholder = "1 ~ ${io.prism.memory.MemoryConfigRepository.MAX_WINDOW_SIZE}",
            hint = "范围 ${io.prism.memory.MemoryConfigRepository.MIN_WINDOW_SIZE}..${io.prism.memory.MemoryConfigRepository.MAX_WINDOW_SIZE}，过大将导致 token 溢出"
        )
        if (showValidation && !valid) {
            ValidationError(
                "N 必须为 ${io.prism.memory.MemoryConfigRepository.MIN_WINDOW_SIZE}..${io.prism.memory.MemoryConfigRepository.MAX_WINDOW_SIZE} 范围内的整数"
            )
        }
        Spacer(Modifier.height(20.dp))
        PrismButton(
            text = "保存",
            enabled = valid,
            onClick = {
                val n = parsed
                if (n == null || n !in io.prism.memory.MemoryConfigRepository.MIN_WINDOW_SIZE..io.prism.memory.MemoryConfigRepository.MAX_WINDOW_SIZE) {
                    showValidation = true
                    return@PrismButton
                }
                onSet(n)
            }
        )
        Spacer(Modifier.height(8.dp))
        PrismButton(
            text = "取消",
            variant = PrismButtonVariant.Ghost,
            onClick = onCancel
        )
    }
}

/**
 * UI 消息横幅（US-036）—— 一次性消费的错误/成功提示。
 *
 * 用 [androidx.compose.runtime.LaunchedEffect] 在展示 2.5 秒后自动调用 [onConsume] 清空，
 * 避免旋转/重组时重复展示。颜色按 [MemoryManagementViewModel.UiMessage] 类型映射。
 */
@Composable
private fun UiMessageBanner(
    message: MemoryManagementViewModel.UiMessage?,
    onConsume: () -> Unit
) {
    if (message == null) return
    val color = when (message) {
        is MemoryManagementViewModel.UiMessage.Error -> PrismDanger
        is MemoryManagementViewModel.UiMessage.Info -> PrismMint
    }
    val text = when (message) {
        is MemoryManagementViewModel.UiMessage.Error -> message.text
        is MemoryManagementViewModel.UiMessage.Info -> message.text
    }
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(UI_MESSAGE_AUTO_DISMISS_MS)
        onConsume()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text = text, color = color, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** L2 记忆 content 在列表行中预览的最大字符长度。 */
private const val MAX_MEMORY_CONTENT_PREVIEW_LEN = 80

/** UI 消息横幅自动消失时长（毫秒）。 */
private const val UI_MESSAGE_AUTO_DISMISS_MS = 2500L

/** 分组标题。 */
@Composable
private fun SectionHeader(title: String, action: String? = null, onActionClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = PrismTextDim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        if (!action.isNullOrEmpty()) {
            if (onActionClick != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onActionClick
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(text = action, color = PrismIndigo, fontSize = 11.sp)
                }
            } else {
                Text(text = action, color = PrismIndigo, fontSize = 11.sp)
            }
        }
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