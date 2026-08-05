package io.prism.ui.knowledge

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismGlassCard
import io.prism.ui.components.PrismSegmented
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismTopBar
import io.prism.ui.components.PrismTopBarAction
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/** 知识库 Mock 数据。 */
private data class KbSpace(
    val name: String,
    val docs: Int,
    val chunks: Int,
    val updated: String,
    val indexed: Int,
    val citations: Int,
    val glow: Color
)

/** 导入来源类型。 */
private enum class ImportSource(val label: String) { FILES("文件"), URL("网址"), TEXT("粘贴") }

/** 导入目标库。 */
private data class ImportTarget(val name: String) {
    companion object {
        val Default = ImportTarget("工作")
    }
}

private val kbSpaces = listOf(
    KbSpace("工作", 62, 2134, "最近更新 2 分钟前", 100, 38, PrismIndigo),
    KbSpace("学习", 41, 1588, "含 PDF / 笔记", 100, 21, PrismCyan),
    KbSpace("个人", 25, 780, "最近更新 1 天前", 64, 9, PrismMint)
)

/** 最近导入 Mock 数据。 */
private data class RecentDoc(
    val name: String,
    val type: Char,
    val library: String,
    val note: String,
    val progress: Int,
    val accent: Color
)

private val recentDocs = listOf(
    RecentDoc("Q3产品规划.pdf", 'D', "工作", "昨晚导入", 100, PrismIndigo),
    RecentDoc("机器学习笔记.xlsx", 'X', "学习", "正在分片", 64, PrismCyan)
)

/**
 * 知识库屏幕 —— 深空玻璃肌理（设计规范 v0.2 第 8.2 节，US-003）。
 *
 * - 顶栏统计：库数 / 文档 / 分片
 * - Bento 双列分库卡片（配色光晕 + 已索引进度 + 引用数）+ 虚线玻璃「导入新文档」卡
 * - 最近导入列表（文档图标按类型着色 + 渐变流光进度条）
 */
@Composable
fun KnowledgeBaseScreen() {
    var importVisible by remember { mutableStateOf(false) }

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                PrismTopBar(
                    title = "知识库",
                    subtitle = "3 个库 · 128 文档 · 4,502 分片",
                    actions = {
                        PrismTopBarAction(
                            icon = { Icon(Icons.Filled.Add, null, tint = PrismTextDim) },
                            contentDescription = "导入",
                            onClick = { importVisible = true }
                        )
                    }
                )
            }
            item {
                SectionHeader("知识库", "管理")
            }
            // Bento 双列网格：3 个分库卡 + 1 个「导入新文档」添加卡（null 占位）
            ((kbSpaces as List<KbSpace?>) + null).chunked(2).forEach { row ->
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { space ->
                            if (space != null) {
                                KbSpaceCard(space, Modifier.weight(1f))
                            } else {
                                AddSpaceCard(Modifier.weight(1f), onClick = { importVisible = true })
                            }
                        }
                    }
                }
            }
            item { SectionHeader("最近导入", "全部") }
            recentDocs.forEach { doc ->
                item {
                    RecentDocRow(doc, Modifier.padding(horizontal = 20.dp))
                }
            }
        }

        // 导入弹层（来源类型 / 目标库 / 路径 / 分片设置）
        PrismSheetHost(visible = importVisible, onDismiss = { importVisible = false }) {
            ImportSheet()
        }
    }
}

/**
 * 导入弹层 —— 设计规范 v0.4，接入 PrismSheet / PrismSegmented / PrismField / PrismButton。
 * 来源类型（文件/网址/粘贴）→ 目标库 → 路径/内容 → 分片设置 → 开始导入。
 */
@Composable
private fun ImportSheet() {
    var source by remember { mutableStateOf(ImportSource.FILES) }
    var target by remember { mutableStateOf(ImportTarget.Default) }
    var path by remember { mutableStateOf("") }
    var chunkSize by remember { mutableStateOf("512") }

    PrismSheet(
        title = "导入内容",
        subtitle = "解析 → 分片 → 向量化索引"
    ) {
        PrismField(
            label = "来源类型",
            value = source.label,
            onValueChange = {},
            trailing = {
                PrismSegmented(
                    options = ImportSource.entries,
                    selected = source,
                    onSelect = { source = it },
                    labelOf = { it.label },
                    modifier = Modifier.width(160.dp)
                )
            }
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "目标知识库",
            value = target.name,
            onValueChange = {},
            trailing = {
                PrismSegmented(
                    options = listOf(
                        ImportTarget("工作"),
                        ImportTarget("学习"),
                        ImportTarget("个人")
                    ),
                    selected = target,
                    onSelect = { target = it },
                    labelOf = { it.name },
                    modifier = Modifier.width(160.dp)
                )
            }
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = if (source == ImportSource.FILES) "文件路径" else if (source == ImportSource.URL) "网址" else "粘贴内容",
            value = path,
            onValueChange = { path = it },
            placeholder = when (source) {
                ImportSource.FILES -> "选择 PDF / Markdown / Office …"
                ImportSource.URL -> "https://…"
                ImportSource.TEXT -> "将文本粘贴到这里 …"
            },
            hint = "自动识别格式 · 支持批量"
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "分片大小",
            value = chunkSize,
            onValueChange = { chunkSize = it },
            placeholder = "512",
            hint = "Token 数 · 建议 256–1024"
        )
        Spacer(Modifier.height(20.dp))
        PrismButton(text = "开始导入", onClick = {})
        Spacer(Modifier.height(12.dp))
        PrismButton(
            text = "取消",
            variant = PrismButtonVariant.Ghost,
            onClick = {}
        )
    }
}

/** 顶栏分组标题。 */
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

/** 分库卡片（Bento）。 */
@Composable
private fun KbSpaceCard(space: KbSpace, modifier: Modifier = Modifier) {
    PrismGlassCard(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 配色光晕
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(45.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(space.glow.copy(alpha = 0.5f), Color.Transparent)
                        )
                    )
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "◈  ${space.name}",
                    color = PrismText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${space.docs} 文档 · ${space.chunks} 分片\n${space.updated}",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StatBlock("已索引", "${space.indexed}%")
                    StatBlock("引用", "${space.citations}")
                }
            }
        }
    }
}

/** 数据统计块。 */
@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(text = value, color = PrismText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = PrismTextFaint, fontSize = 11.sp)
    }
}

/** 虚线玻璃「导入新文档」添加卡。 */
@Composable
private fun AddSpaceCard(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(PrismPanel2.copy(alpha = 0.6f))
            .border(1.dp, PrismLine, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(PrismPanel2, RoundedCornerShape(14.dp))
                .border(1.dp, PrismLine, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = PrismIndigo)
        }
        Text(
            text = "导入新文档",
            color = PrismTextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** 最近导入行（类型图标 + 名称 + 进度条）。 */
@Composable
private fun RecentDocRow(doc: RecentDoc, modifier: Modifier = Modifier) {
    PrismGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(doc.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = doc.type.toString(), color = doc.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = doc.name, color = PrismText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "${doc.library} · ${doc.note}", color = PrismTextFaint, fontSize = 11.sp)
                PrismIndexBar(doc.progress, Modifier.padding(top = 8.dp))
            }
            Text(
                text = "${doc.progress}%",
                color = PrismMint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 渐变流光进度条。 */
@Composable
private fun PrismIndexBar(progress: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress / 100f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(PrismIndigo, PrismCyan)))
        )
    }
}