package io.prism.ui.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import io.prism.data.KnowledgeBase
import io.prism.data.KnowledgeBaseRepository
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismCard
import io.prism.ui.components.PrismField
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
import io.prism.ui.theme.PrismWarning

/**
 * 知识库管理屏幕（US-018，ADR-011）。
 *
 * **架构**（ADR-011 5.1）：保留底部导航一级 Tab，改造既有 Mock 原型为真实数据驱动。
 *
 * **UI 结构**：
 * - 顶栏：标题 + 统计副标题（N 个库 · M 分片）+ 导入操作钮
 * - 列表：默认库卡 + 自建库列表（含删除按钮）+ 添加库卡
 * - 弹层：
 *   - [CreateLibrarySheet]：库名输入 + 创建
 *   - [DeleteLibraryConfirmSheet]：删除确认
 *   - [ImportSheet]：目标库选择 + 文件选择 + 进度/完成/错误展示
 *
 * **数据源**：[KnowledgeBaseViewModel.uiState] 单一 StateFlow 订阅，无任何 Mock 数据。
 *
 * **SAF 集成**（ADR-011 5.4）：[ActivityResultContracts.OpenDocument] 选单文件，
 * 选中后经 `DocumentFile.fromSingleUri` 取文件名，调用 [KnowledgeBaseViewModel.startIngestion]。
 *
 * US-018 验收标准：
 * 1. 知识库列表页显示分库
 * 2. 支持创建/删除分库
 * 3. 支持导入文档（解析→摄入进度展示）
 * 4. 摄入失败与未建索引提示
 */
@Composable
fun KnowledgeBaseScreen() {
    val viewModel: KnowledgeBaseViewModel = viewModel(factory = KnowledgeBaseViewModel.Factory)
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var createSheetVisible by remember { mutableStateOf(false) }
    var deleteTargetKb by remember { mutableStateOf<KnowledgeBase?>(null) }
    var importSheetVisible by remember { mutableStateOf(false) }

    // 当前选中的导入目标库（默认 0L 默认库）
    // 必须在 OpenDocument launcher 之前声明，因 launcher 回调读取其当前值
    var importTargetKbId by remember { mutableStateOf(KnowledgeBaseRepository.DEFAULT_KB_ID) }

    // SAF OpenDocument launcher（ADR-011 5.4）
    // MIME 限制：覆盖 DocumentType 支持的 6 种格式（PDF/DOCX/XLSX/MD/TXT/CSV，R-8）
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // DocumentFile.fromSingleUri 取文件名（含扩展名），用于解析器分发与错误溯源
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "document"
            // 使用用户在 ImportSheet 中选中的目标库 id
            // Uri → String 解耦 ViewModel 与 Android 框架类，便于纯 JVM 单测（ADR-011 5.4）
            viewModel.startIngestion(uri.toString(), fileName, importTargetKbId)
        }
    }

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                PrismTopBar(
                    title = "知识库",
                    subtitle = formatStatsSubtitle(state),
                    actions = {
                        PrismTopBarAction(
                            icon = { Icon(Icons.Filled.Add, null, tint = PrismTextDim) },
                            contentDescription = "导入",
                            onClick = { importSheetVisible = true }
                        )
                    }
                )
            }
            item { SectionHeader("知识库", "管理") }

            // 默认库卡（ADR-011 5.6：单独展示，禁用删除）
            item {
                DefaultKbCard(
                    chunkCount = state.defaultKbChunkCount,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // 自建库列表（含删除按钮）
            items(state.libraries, key = { it.id }) { kb ->
                KbSpaceCard(
                    kb = kb,
                    chunkCount = state.chunkCounts[kb.id] ?: 0L,
                    onDelete = { deleteTargetKb = kb },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // 添加库卡
            item {
                AddSpaceCard(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    onClick = { createSheetVisible = true }
                )
            }

            // 摄入进度区（仅 Running / Completed / Failed 时展示）
            val ingestionState = state.ingestionState
            if (ingestionState !is KnowledgeBaseViewModel.IngestionUiState.Idle) {
                item { SectionHeader("摄入", if (ingestionState is KnowledgeBaseViewModel.IngestionUiState.Running) "进行中" else "完成") }
                item {
                    IngestionStatusRow(
                        state = ingestionState,
                        onDismiss = viewModel::clearIngestionState,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }

        // 创建库弹层
        PrismSheetHost(visible = createSheetVisible, onDismiss = {
            createSheetVisible = false
            viewModel.clearCreateLibraryError()
        }) {
            CreateLibrarySheet(
                error = state.createLibraryError,
                onCreate = { name ->
                    viewModel.createLibrary(name)
                    // 创建成功（无错误）则关闭弹层
                    if (viewModel.uiState.value.createLibraryError == null) {
                        createSheetVisible = false
                    }
                },
                onCancel = {
                    createSheetVisible = false
                    viewModel.clearCreateLibraryError()
                }
            )
        }

        // 删除确认弹层
        PrismSheetHost(visible = deleteTargetKb != null, onDismiss = {
            deleteTargetKb = null
            viewModel.clearDeleteLibraryError()
        }) {
            DeleteLibraryConfirmSheet(
                kb = deleteTargetKb,
                error = state.deleteLibraryError,
                onConfirm = {
                    deleteTargetKb?.let { viewModel.deleteLibrary(it.id) }
                    if (viewModel.uiState.value.deleteLibraryError == null) {
                        deleteTargetKb = null
                    }
                },
                onCancel = {
                    deleteTargetKb = null
                    viewModel.clearDeleteLibraryError()
                }
            )
        }

        // 导入弹层（目标库选择 + 文件选择 + 进度展示）
        PrismSheetHost(visible = importSheetVisible, onDismiss = { importSheetVisible = false }) {
            ImportSheet(
                libraries = state.libraries,
                defaultKbChunkCount = state.defaultKbChunkCount,
                selectedTargetId = importTargetKbId,
                onSelectTarget = { importTargetKbId = it },
                ingestionState = state.ingestionState,
                onPickFile = {
                    openDocumentLauncher.launch(SUPPORTED_MIME_TYPES)
                },
                onDismissIngestion = {
                    viewModel.clearIngestionState()
                    importSheetVisible = false
                }
            )
        }
    }
}

/** SAF OpenDocument 支持的 MIME 类型（覆盖 DocumentType 的 6 种格式，R-8）。 */
private val SUPPORTED_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/plain",
    "text/markdown",
    "text/csv"
)

/** 格式化顶栏统计副标题：N 个库 · M 分片。 */
private fun formatStatsSubtitle(state: KnowledgeBaseViewModel.KnowledgeBaseUiState): String {
    val libCount = state.libraries.size + 1 // +1 包含默认库
    val totalChunks = state.defaultKbChunkCount + state.chunkCounts.values.sum()
    return "$libCount 个库 · $totalChunks 分片"
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

/** 默认库卡（ADR-011 5.6：单独展示，禁用删除）。 */
@Composable
private fun DefaultKbCard(chunkCount: Long, modifier: Modifier = Modifier) {
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 配色光晕（青色，与自建库区分）
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(45.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(PrismCyan.copy(alpha = 0.4f), Color.Transparent)
                        )
                    )
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "◈  默认库",
                    color = PrismText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "未分类文档\n$chunkCount 分片",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = "默认",
                    color = PrismCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

/** 自建库卡片（含删除按钮）。 */
@Composable
private fun KbSpaceCard(
    kb: KnowledgeBase,
    chunkCount: Long,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 配色光晕
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(45.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(PrismIndigo.copy(alpha = 0.5f), Color.Transparent)
                        )
                    )
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "◈  ${kb.name}",
                        color = PrismText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // 删除按钮（图标按钮）
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrismPanel2.copy(alpha = 0.6f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDelete
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = PrismDanger,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "$chunkCount 分片",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** 添加库卡（虚线玻璃风格）。 */
@Composable
private fun AddSpaceCard(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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
            text = "创建知识库",
            color = PrismTextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** 摄入状态行（Running 进度条 / Completed 完成 / Failed 错误）。 */
@Composable
private fun IngestionStatusRow(
    state: KnowledgeBaseViewModel.IngestionUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    PrismCard(modifier = modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (state) {
                is KnowledgeBaseViewModel.IngestionUiState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.documentTitle,
                            color = PrismText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        val progress = if (state.total > 0) state.embedded * 100 / state.total else 0
                        Text(
                            text = "$progress%",
                            color = PrismMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PrismIndexBar(
                        progress = if (state.total > 0) state.embedded * 100 / state.total else 0
                    )
                    if (state.skipped > 0) {
                        Text(
                            text = "${state.skipped} 个片段未建索引",
                            color = PrismWarning,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                is KnowledgeBaseViewModel.IngestionUiState.Completed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.documentTitle,
                            color = PrismText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "完成",
                            color = PrismMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已嵌入 ${state.embedded} 个片段" +
                            if (state.skipped > 0) " · ${state.skipped} 个未建索引" else "",
                        color = PrismTextFaint,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "耗时 ${state.durationMs} ms",
                        color = PrismTextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (state.skipped > 0) {
                        Text(
                            text = "${state.skipped} 个片段未建索引",
                            color = PrismWarning,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onDismiss)
                }
                is KnowledgeBaseViewModel.IngestionUiState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.documentTitle,
                            color = PrismText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "失败",
                            color = PrismDanger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.message,
                        color = PrismDanger,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onDismiss)
                }
                KnowledgeBaseViewModel.IngestionUiState.Idle -> {
                    // 不会渲染（外层判断 Idle 不显示）
                }
            }
        }
    }
}

/**
 * 创建库弹层（AC-2）。
 */
@Composable
private fun CreateLibrarySheet(
    error: String?,
    onCreate: (String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    PrismSheet(title = "创建知识库", subtitle = "输入名称以新建分库") {
        PrismField(
            label = "库名称",
            value = name,
            onValueChange = { name = it },
            placeholder = "如：工作 · 学习 · 个人",
            hint = "名称不能包含 / 或控制字符，且不能与既有库重名"
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = error, color = PrismDanger, fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        PrismButton(
            text = "创建",
            onClick = { onCreate(name) },
            enabled = name.isNotBlank()
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "取消", variant = PrismButtonVariant.Ghost, onClick = onCancel)
    }
}

/**
 * 删除确认弹层（AC-2）。
 */
@Composable
private fun DeleteLibraryConfirmSheet(
    kb: KnowledgeBase?,
    error: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    PrismSheet(title = "删除知识库", subtitle = "此操作不可撤销，库内所有分片将一并删除") {
        if (kb != null) {
            Text(
                text = "确认删除「${kb.name}」？",
                color = PrismText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "库内所有已嵌入的分片将级联删除（ADR-008 5.4 事务保证原子性）。",
                color = PrismTextFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = error, color = PrismDanger, fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        PrismButton(text = "确认删除", variant = PrismButtonVariant.Danger, onClick = onConfirm)
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "取消", variant = PrismButtonVariant.Ghost, onClick = onCancel)
    }
}

/**
 * 导入弹层（AC-3 / AC-4）。
 *
 * - 目标库选择（PrismSegmented，含默认库 + 自建库）
 * - 文件选择按钮（触发 OpenDocument launcher）
 * - 摄入进度/完成/错误展示
 */
@Composable
private fun ImportSheet(
    libraries: List<KnowledgeBase>,
    defaultKbChunkCount: Long,
    selectedTargetId: Long,
    onSelectTarget: (Long) -> Unit,
    ingestionState: KnowledgeBaseViewModel.IngestionUiState,
    onPickFile: () -> Unit,
    onDismissIngestion: () -> Unit
) {
    // 目标库选项（默认库 + 自建库），用 id 与显示名 pair
    val targetOptions: List<Pair<Long, String>> = buildList {
        add(KnowledgeBaseRepository.DEFAULT_KB_ID to "默认库")
        libraries.forEach { add(it.id to it.name) }
    }

    PrismSheet(title = "导入内容", subtitle = "解析 → 分片 → 向量化索引") {
        // 目标库选择
        Text(
            text = "目标知识库",
            color = PrismTextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))
        // 用 PrismSegmented 选择目标库；库数量多时可能溢出，故用 LazyRow 风格的横向 Row
        // 简化：直接用 Row 平铺（库数量预期 <=5，4GB 低端机限制）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            targetOptions.forEach { (id, name) ->
                val isSelected = id == selectedTargetId
                val bg = if (isSelected) PrismIndigo else PrismPanel2
                val fg = if (isSelected) Color.White else PrismTextDim
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .border(
                            1.dp,
                            if (isSelected) Color.Transparent else PrismLine,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectTarget(id) }
                        )
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = name, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 摄入状态展示（Running / Completed / Failed）
        when (ingestionState) {
            is KnowledgeBaseViewModel.IngestionUiState.Running -> {
                val progress = if (ingestionState.total > 0)
                    ingestionState.embedded * 100 / ingestionState.total else 0
                Text(
                    text = "正在摄入：${ingestionState.documentTitle}",
                    color = PrismText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                PrismIndexBar(progress = progress)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${ingestionState.embedded}/${ingestionState.total} 已嵌入" +
                        if (ingestionState.skipped > 0) " · ${ingestionState.skipped} 未建索引" else "",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(20.dp))
                PrismButton(
                    text = "选择文件",
                    onClick = onPickFile,
                    enabled = false
                )
            }
            is KnowledgeBaseViewModel.IngestionUiState.Completed -> {
                Text(
                    text = "摄入完成：${ingestionState.documentTitle}",
                    color = PrismMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已嵌入 ${ingestionState.embedded} 个片段" +
                        if (ingestionState.skipped > 0) " · ${ingestionState.skipped} 个未建索引" else "",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
                if (ingestionState.skipped > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${ingestionState.skipped} 个片段未建索引",
                        color = PrismWarning,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(20.dp))
                PrismButton(text = "完成", onClick = onDismissIngestion)
            }
            is KnowledgeBaseViewModel.IngestionUiState.Failed -> {
                Text(
                    text = "摄入失败：${ingestionState.documentTitle}",
                    color = PrismDanger,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(text = ingestionState.message, color = PrismDanger, fontSize = 11.sp)
                Spacer(Modifier.height(20.dp))
                PrismButton(text = "关闭", onClick = onDismissIngestion)
            }
            KnowledgeBaseViewModel.IngestionUiState.Idle -> {
                PrismButton(text = "选择文件", onClick = onPickFile)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "支持 PDF / DOCX / XLSX / MD / TXT / CSV",
                    color = PrismTextFaint,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** 渐变流光进度条（保留 Mock 既有视觉，复用 v0.4 风格）。 */
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
                .fillMaxWidth(progress.coerceIn(0, 100) / 100f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(PrismIndigo, PrismCyan)))
        )
    }
}
