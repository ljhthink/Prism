package io.prism.ui.capabilities

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
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismGlassCard
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
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/** 能力中枢分段。 */
private enum class CapSegment(val label: String) { MCP("MCP 工具"), SKILLS("Skills"), MEMORY("记忆") }

/** MCP Server 状态。 */
private data class McpServer(
    val icon: String,
    val name: String,
    val desc: String,
    val state: PrismDotState,
    val enabled: Boolean,
    val accent: Color = PrismIndigo
)

private val localMcp = listOf(
    McpServer("F", "Filesystem", "本地文件系统", PrismDotState.OK, true),
    McpServer("⊕", "Fetch", "网页抓取", PrismDotState.RUN, true, PrismCyan),
    McpServer("M", "Memory", "记忆读写", PrismDotState.OK, true),
    McpServer("◈", "Sequential Thinking", "深度推理", PrismDotState.OK, true)
)

private val remoteMcp = listOf(
    McpServer("G", "GitHub", "已连接 · 填 Key 激活", PrismDotState.OK, true, PrismCyan),
    McpServer("N", "Notion", "需配置 Token", PrismDotState.ERR, false),
    McpServer("C", "Context7", "需配置 API Key", PrismDotState.ERR, false)
)

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
 * 能力中枢屏幕 —— 深空玻璃肌理（设计规范 v0.4 第 8.3 节，US-002/004/005）。
 *
 * 顶部三段式（MCP 工具 / Skills / 记忆）。点击 MCP Server 行 → 打开**配置弹层**（传输类型 /
 * Base URL / Token / 测试连接 / 启用 / 删除）；点击 Skill 行 → 打开**详情弹层**（说明 / 参数 / 启用）。
 */
@Composable
fun CapabilitiesScreen() {
    var segment by remember { mutableStateOf(CapSegment.MCP) }
    var mcpTarget by remember { mutableStateOf<McpServer?>(null) }
    var skillTarget by remember { mutableStateOf<PrismSkill?>(null) }

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
                                .border(1.dp, PrismLine, RoundedCornerShape(12.dp)),
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
                    McpPanel(onServerClick = { mcpTarget = it })
                }
            }
            item {
                AnimatedVisibility(
                    visible = segment == CapSegment.SKILLS,
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut()
                ) {
                    SkillsPanel(onSkillClick = { skillTarget = it })
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
        PrismSheetHost(visible = mcpTarget != null, onDismiss = { mcpTarget = null }) {
            mcpTarget?.let { McpConfigSheet(it) }
        }
        // Skill 详情弹层
        PrismSheetHost(visible = skillTarget != null, onDismiss = { skillTarget = null }) {
            skillTarget?.let { SkillDetailSheet(it) }
        }
    }
}

/** MCP 工具面板。 */
@Composable
private fun McpPanel(onServerClick: (McpServer) -> Unit) {
    Column {
        SectionHeader("本地内置 · 6", "管理")
        localMcp.forEach { McpRow(it, Modifier.padding(horizontal = 20.dp), onClick = { onServerClick(it) }) }
        SectionHeader("远程模板 · 9", "+ 自定义")
        remoteMcp.forEach { McpRow(it, Modifier.padding(horizontal = 20.dp), onClick = { onServerClick(it) }) }
    }
}

/** 单个 MCP Server 行（点击打开配置）。 */
@Composable
private fun McpRow(server: McpServer, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var enabled by remember { mutableStateOf(server.enabled) }
    PrismGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(server.accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = server.icon, color = server.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = server.name, color = PrismText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(text = server.desc, color = PrismTextFaint, fontSize = 11.sp)
            }
            PrismStatusDot(server.state)
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it })
        }
    }
}

/**
 * MCP Server 配置弹层 —— 设计规范 v0.4 第 8.3 节。
 * 连接信息名称 / 传输类型（stdio/SSE→HTTP）/ Base URL / Token / Schema 校验 / 测试连接 / 启用 / 删除。
 */
@Composable
private fun McpConfigSheet(server: McpServer) {
    var transport by remember(server.name) { mutableStateOf("SSE → HTTP") }
    var baseUrl by remember(server.name) { mutableStateOf("https://api.example.com/mcp") }
    var token by remember(server.name) { mutableStateOf("") }
    var enabled by remember(server.name) { mutableStateOf(server.enabled) }

    PrismSheet(
        title = server.name,
        subtitle = server.desc
    ) {
        PrismField(
            label = "传输类型",
            value = transport,
            onValueChange = {},
            trailing = {
                PrismSegmented(
                    options = listOf("stdio", "SSE → HTTP"),
                    selected = transport,
                    onSelect = { transport = it },
                    labelOf = { it },
                    modifier = Modifier.width(160.dp)
                )
            }
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "Base URL",
            value = baseUrl,
            onValueChange = { baseUrl = it },
            placeholder = "https://…",
            hint = "Schema 校验通过 · JSON-RPC 2.0"
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "Token / API Key",
            value = token,
            onValueChange = { token = it },
            placeholder = "eyJ…",
            secret = true,
            trailing = {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = PrismTextFaint, modifier = Modifier.size(16.dp))
            }
        )
        Spacer(Modifier.height(20.dp))
        PrismButton(text = "测试连接", onClick = {})
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "启用该 Server", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(16.dp))
        PrismButton(text = "删除 Server", variant = PrismButtonVariant.Danger, leadingIcon = {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = PrismDanger, modifier = Modifier.size(16.dp))
        }, onClick = {})
    }
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
    PrismGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
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

/** Skill 详情弹层 —— 设计规范 v0.4 第 8.3 节。说明 / 来源 / 安装参数 / 启用。 */
@Composable
private fun SkillDetailSheet(skill: PrismSkill) {
    var installArgs by remember(skill.name) { mutableStateOf("") }
    var enabled by remember(skill.name) { mutableStateOf(skill.enabled) }

    PrismSheet(
        title = skill.name,
        subtitle = "${skill.origin} · ${skill.desc}"
    ) {
        PrismField(
            label = "安装参数",
            value = installArgs,
            onValueChange = { installArgs = it },
            placeholder = "--model llama3 …",
            hint = "远程 Skill 需配置来源仓库 / 参数"
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "启用该 Skill", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PrismSwitch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(16.dp))
        PrismButton(text = "更新配置", onClick = {})
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "卸载 Skill", variant = PrismButtonVariant.Danger, leadingIcon = {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = PrismDanger, modifier = Modifier.size(16.dp))
        }, onClick = {})
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
    PrismGlassCard(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
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