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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.prism.ui.components.PrismIcons
import io.prism.ui.components.PrismTextAction
import io.prism.ui.components.PrismTopBar
import io.prism.ui.components.PrismTopBarAction
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType
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
    // UX-001 问题 2（ADR-021）：文本笔记弹层 + 库内文档管理弹层
    var textNoteSheetVisible by remember { mutableStateOf(false) }
    var textNoteTargetKbId by remember { mutableStateOf(KnowledgeBaseRepository.DEFAULT_KB_ID) }
    var manageTargetKb by remember { mutableStateOf<KnowledgeBase?>(null) }
    var manageTargetKbId by remember { mutableStateOf(KnowledgeBaseRepository.DEFAULT_KB_ID) }
    var manageTargetDefault by remember { mutableStateOf(false) }
    // 库内文档列表（每次打开管理弹层时刷新）
    var documents by remember { mutableStateOf<List<String>>(emptyList()) }
    var documentDeleted by remember { mutableStateOf(false) }
    // UXR3 问题 12（ADR-023）：文档内容查看弹层状态
    var viewTargetKbId by remember { mutableStateOf(KnowledgeBaseRepository.DEFAULT_KB_ID) }
    var viewDocumentTitle by remember { mutableStateOf<String?>(null) }
    var documentContent by remember { mutableStateOf("") }

    // 当前选中的导入目标库（默认 0L 默认库）
    // 必须在 OpenDocument launcher 之前声明，因 launcher 回调读取其当前值
    var importTargetKbId by remember { mutableStateOf(KnowledgeBaseRepository.DEFAULT_KB_ID) }

    // SAF OpenDocument launcher（ADR-011 5.4）
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "document"
            viewModel.startIngestion(uri.toString(), fileName, importTargetKbId)
        }
    }

    // 各库文档数。
    //
    // **必须在 IO 线程查**：`listDocuments` 是 ObjectBox 同步查询，写在 `remember {}`
    // 里会在组合期（主线程、每帧都可能）读库——库多时首帧掉帧，StrictMode 也会报磁盘读。
    // 用 `produceState` + IO 调度：首次组合异步取一次，库集合/分片数变化时重取。
    val docCounts by produceState(
        initialValue = emptyMap<Long, Int>(),
        state.libraries,
        state.defaultKbChunkCount,
        state.chunkCounts
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            buildMap {
                put(
                    KnowledgeBaseRepository.DEFAULT_KB_ID,
                    viewModel.listDocuments(KnowledgeBaseRepository.DEFAULT_KB_ID).size
                )
                state.libraries.forEach { kb -> put(kb.id, viewModel.listDocuments(kb.id).size) }
            }
        }
    }

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 设计稿 `.topbar`：标题 22sp + 右侧文字动作「导入」
            item {
                PrismTopBar(
                    title = "知识",
                    actions = {
                        PrismTextAction(text = "导入", onClick = { importSheetVisible = true })
                    }
                )
            }

            // `.count-line`：N 个库 · M 份文档 · K 段
            item {
                val libCount = state.libraries.size + 1
                val docTotal = docCounts.values.sum()
                val chunkTotal = state.defaultKbChunkCount + state.chunkCounts.values.sum()
                Text(
                    text = "$libCount 个库 · $docTotal 份文档 · $chunkTotal 段",
                    color = PrismTextFaint,
                    fontSize = PrismType.micro,
                    modifier = Modifier.padding(
                        start = PrismSize.page,
                        end = PrismSize.page,
                        top = PrismSize.s2,
                        bottom = PrismSize.s4
                    )
                )
            }

            item { GroupLabel("库") }

            // 自建库（行式：名称 + 文档数/时间 + 等宽段数 + chevron）
            items(state.libraries, key = { it.id }) { kb ->
                KbRow(
                    name = kb.name,
                    docCount = docCounts[kb.id] ?: 0,
                    chunkCount = state.chunkCounts[kb.id] ?: 0L,
                    subtitle = docSubtitle(docCounts[kb.id] ?: 0, kb),
                    onClick = {
                        manageTargetKb = kb
                        manageTargetKbId = kb.id
                        manageTargetDefault = false
                        documents = viewModel.listDocuments(kb.id)
                        documentDeleted = false
                    },
                    onDelete = { deleteTargetKb = kb }
                )
            }

            // 创建知识库行（设计稿：accent 标题 + 加号）
            item {
                CreateKbRow(onClick = { createSheetVisible = true })
            }

            // 分组：默认库
            item { GroupLabel("默认库") }
            item {
                KbRow(
                    name = "未分类文档",
                    docCount = docCounts[KnowledgeBaseRepository.DEFAULT_KB_ID] ?: 0,
                    chunkCount = state.defaultKbChunkCount,
                    subtitle = "未归入任何分库",
                    onClick = {
                        manageTargetKb = null
                        manageTargetKbId = KnowledgeBaseRepository.DEFAULT_KB_ID
                        manageTargetDefault = true
                        documents = viewModel.listDocuments(KnowledgeBaseRepository.DEFAULT_KB_ID)
                        documentDeleted = false
                    },
                    onDelete = null
                )
            }

            // `.footnote`
            item {
                Text(
                    text = "支持 PDF / DOCX / XLSX / PPTX / MD / TXT / CSV；导入后自动解析、分片、向量化。",
                    color = PrismTextFaint,
                    fontSize = PrismType.micro,
                    lineHeight = PrismType.microLine,
                    modifier = Modifier.padding(
                        start = PrismSize.page,
                        end = PrismSize.page,
                        top = PrismSize.s5
                    )
                )
            }

            // 摄入进度区（仅 Running / Completed / Failed 时展示）
            val ingestionState = state.ingestionState
            if (ingestionState !is KnowledgeBaseViewModel.IngestionUiState.Idle) {
                item { GroupLabel("摄入") }
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

        // 导入弹层（目标库选择 + 文件选择 + 文本笔记 + 进度展示）
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
                // UX-001 问题 2（ADR-021）：文本笔记入口
                onAddTextNote = {
                    textNoteTargetKbId = importTargetKbId
                    textNoteSheetVisible = true
                },
                onDismissIngestion = {
                    viewModel.clearIngestionState()
                    importSheetVisible = false
                }
            )
        }

        // UX-001 问题 2（ADR-021）：文本笔记弹层（只输入文字保存）
        PrismSheetHost(visible = textNoteSheetVisible, onDismiss = {
            textNoteSheetVisible = false
            viewModel.clearIngestionState()
        }) {
            TextNoteSheet(
                libraries = state.libraries,
                selectedTargetId = textNoteTargetKbId,
                onSelectTarget = { textNoteTargetKbId = it },
                ingestionState = state.ingestionState,
                onSubmit = { title, text ->
                    viewModel.startTextIngestion(title, text, textNoteTargetKbId)
                    textNoteSheetVisible = false
                },
                onClose = {
                    textNoteSheetVisible = false
                    viewModel.clearIngestionState()
                }
            )
        }

        // UX-001 问题 2（ADR-021）：库内文档管理弹层（删除 / 移动）
        PrismSheetHost(visible = manageTargetKb != null || manageTargetDefault, onDismiss = {
            manageTargetKb = null
            manageTargetDefault = false
            documentDeleted = false
        }) {
            val kbName = if (manageTargetDefault) "默认库" else manageTargetKb?.name ?: ""
            ManageDocumentsSheet(
                kbName = kbName,
                documents = documents,
                libraries = state.libraries,
                sourceKbId = manageTargetKbId,
                isDefault = manageTargetDefault,
                onRefresh = { documents = viewModel.listDocuments(manageTargetKbId) },
                onView = { title ->
                    // UXR3 问题 12（ADR-023）：查看文档内容
                    viewTargetKbId = manageTargetKbId
                    viewDocumentTitle = title
                    documentContent = viewModel.getDocumentContent(manageTargetKbId, title)
                },
                onDelete = { title ->
                    viewModel.deleteDocument(manageTargetKbId, title)
                    documents = viewModel.listDocuments(manageTargetKbId)
                    documentDeleted = true
                },
                onMove = { title, targetId ->
                    viewModel.moveDocument(manageTargetKbId, title, targetId)
                    documents = viewModel.listDocuments(manageTargetKbId)
                    documentDeleted = true
                },
                onClose = {
                    manageTargetKb = null
                    manageTargetDefault = false
                    documentDeleted = false
                }
            )
        }

        // UXR3 问题 12（ADR-023）：文档内容查看弹层
        PrismSheetHost(visible = viewDocumentTitle != null, onDismiss = { viewDocumentTitle = null }) {
            viewDocumentTitle?.let { title ->
                DocumentContentSheet(
                    documentTitle = title,
                    content = documentContent,
                    onClose = { viewDocumentTitle = null }
                )
            }
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

/**
 * 分组标签（无横向 padding）—— 供**弹层内**使用。
 *
 * `PrismSheet` 的内容区已有 `padding(horizontal = 20.dp)`；若这里再用 `GroupLabel`
 * （自带左右 20dp），弹层里的分组标题会比其它内容左右各窄 20dp，视觉上像被缩进了一层。
 */
@Composable
private fun GroupLabelBare(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = PrismType.meta,
        modifier = Modifier.padding(top = PrismSize.s3, bottom = PrismSize.s2)
    )
}

/** 分组标签 —— 设计稿 `.group-label`：12sp 弱化、下 8dp。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = PrismType.meta,
        modifier = Modifier.padding(
            start = PrismSize.page,
            end = PrismSize.page,
            top = PrismSize.s5,
            bottom = PrismSize.s2
        )
    )
}

/**
 * 库行 —— 设计稿 `.row` + `kbRow`：最小高 56dp、底部 1px 分隔线、无卡片。
 *
 * 左侧两行（名称 15sp / 状态 12sp），右侧等宽段数 + chevron；
 * [onDelete] 不为 null 时在 chevron 左侧给一个 36dp 删除热区（默认库没有）。
 */
@Composable
private fun KbRow(
    name: String,
    docCount: Int,
    chunkCount: Long,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PrismSize.row)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = PrismSize.page),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = PrismText,
                    fontSize = PrismType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = PrismTextFaint,
                    fontSize = PrismType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = formatChunks(chunkCount),
                color = PrismTextFaint,
                fontSize = PrismType.meta,
                fontFamily = FontFamily.Monospace
            )
            if (onDelete != null) {
                Spacer(Modifier.width(PrismSize.s2))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDelete
                        )
                        .semantics { contentDescription = "删除知识库" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = PrismIcons.Close,
                        contentDescription = null,
                        tint = PrismTextFaint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Spacer(Modifier.width(PrismSize.s2))
            Icon(
                imageVector = PrismIcons.ChevronRight,
                contentDescription = null,
                tint = PrismTextFaint,
                modifier = Modifier.size(16.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PrismSize.page)
                .height(1.dp)
                .background(PrismLine)
        )
    }
}

/** 创建知识库行 —— 设计稿：accent 标题 + 加号 chevron。 */
@Composable
private fun CreateKbRow(onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PrismSize.row)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = PrismSize.page),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("创建知识库", color = PrismAccent, fontSize = PrismType.body)
                Text(
                    "新建一个分库",
                    color = PrismTextFaint,
                    fontSize = PrismType.meta,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = PrismIcons.Plus,
                contentDescription = null,
                tint = PrismTextFaint,
                modifier = Modifier.size(16.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PrismSize.page)
                .height(1.dp)
                .background(PrismLine)
        )
    }
}

/** 段数格式化：≥1000 用 1,642 形态（设计稿 `.mono` 列）。 */
private fun formatChunks(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return "$s 段"
    return s.reversed().chunked(3).joinToString(",").reversed() + " 段"
}

/**
 * 库行副标题 —— 设计稿 kbRow 第二行是「N 份文档 · 日期」。
 *
 * 之前只给了「M-d 创建」，把文档数丢了（只在顶部 count-line 汇总），已修正。
 */
private fun docSubtitle(docCount: Int, kb: KnowledgeBase): String {
    val date = java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(kb.createdAt))
    return "$docCount 份文档 · $date 创建"
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
        // 设计稿 `.btn-row`：主按钮 + ghost **并排**（纵向两个全宽按钮看起来像两个同等重要的决定）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrismButton(
                text = "创建",
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
                fillWidth = false,
                modifier = Modifier.weight(1f)
            )
            PrismButton(
                text = "取消",
                variant = PrismButtonVariant.Ghost,
                onClick = onCancel,
                fillWidth = false,
                modifier = Modifier.weight(1f)
            )
        }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrismButton(
                text = "确认删除",
                variant = PrismButtonVariant.Danger,
                onClick = onConfirm,
                fillWidth = false,
                modifier = Modifier.weight(1f)
            )
            PrismButton(
                text = "取消",
                variant = PrismButtonVariant.Ghost,
                onClick = onCancel,
                fillWidth = false,
                modifier = Modifier.weight(1f)
            )
        }
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
    onAddTextNote: () -> Unit,
    onDismissIngestion: () -> Unit
) {
    // 目标库选项（默认库 + 自建库），用 id 与显示名 pair
    val targetOptions: List<Pair<Long, String>> = buildList {
        add(KnowledgeBaseRepository.DEFAULT_KB_ID to "默认库")
        libraries.forEach { add(it.id to it.name) }
    }

    PrismSheet(title = "导入内容", subtitle = "解析 → 分片 → 向量化索引") {
        // UI v2（ADR-079）：目标库选择改用**单选行**（`.option` 语言：圆环 + 名称 + 行尾对勾），
        // 取代旧的等宽实底 segmented 色块。库数量多时等高线性排布，不会像等宽胶囊那样挤压文字。
        GroupLabelBare("目标知识库")
        targetOptions.forEach { (id, name) ->
            val selected = id == selectedTargetId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelectTarget(id) }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PrismSize.s3)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(
                            width = 1.dp,
                            color = if (selected) PrismAccent else PrismLineStrong,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .background(
                            if (selected) PrismAccent.copy(alpha = 0.12f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
                Text(
                    text = name,
                    color = if (selected) PrismText else PrismTextDim,
                    fontSize = PrismType.body,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        imageVector = PrismIcons.Check,
                        contentDescription = "已选中",
                        tint = PrismAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(PrismSize.s4))

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
                // 设计稿 `.btn-row`：主按钮 + ghost 并排（不给纵向两个全宽按钮——它们看起来像两个同等重要的决定）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrismButton(
                        text = "选择文件",
                        onClick = onPickFile,
                        modifier = Modifier.weight(1f)
                    )
                    PrismButton(
                        text = "文本笔记",
                        variant = PrismButtonVariant.Ghost,
                        onClick = onAddTextNote,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(PrismSize.s3))
                Text(
                    text = "支持 PDF / DOCX / XLSX / PPTX / MD / TXT / CSV；导入后自动解析、分片、向量化。",
                    color = PrismTextFaint,
                    fontSize = PrismType.micro,
                    lineHeight = PrismType.microLine
                )
            }
        }
    }
}

/**
 * 文本笔记弹层（UX-001 问题 2，ADR-021）—— 只输入文字内容保存到知识库。
 *
 * - 标题 + 多行文本输入
 * - 目标库选择（默认库 + 自建库）
 * - 提交后经 [KnowledgeBaseViewModel.startTextIngestion] 直接入库
 */
@Composable
private fun TextNoteSheet(
    libraries: List<KnowledgeBase>,
    selectedTargetId: Long,
    onSelectTarget: (Long) -> Unit,
    ingestionState: KnowledgeBaseViewModel.IngestionUiState,
    onSubmit: (String, String) -> Unit,
    onClose: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val targetOptions: List<Pair<Long, String>> = buildList {
        add(KnowledgeBaseRepository.DEFAULT_KB_ID to "默认库")
        libraries.forEach { add(it.id to it.name) }
    }

    PrismSheet(title = "文本笔记", subtitle = "直接输入文字内容保存到知识库") {
        Text(
            text = "目标知识库",
            color = PrismTextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))
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
                        .border(1.dp, if (isSelected) Color.Transparent else PrismLine, RoundedCornerShape(10.dp))
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
        PrismField(
            label = "标题",
            value = title,
            onValueChange = { title = it },
            placeholder = "如：读书笔记 · 会议纪要",
            hint = "标题将作为文档名，可被后续管理"
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "内容",
            color = PrismTextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(PrismPanel2, RoundedCornerShape(12.dp))
                .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "输入要保存的文字内容…",
                    color = PrismTextFaint,
                    fontSize = 13.sp
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = PrismText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(PrismCyan),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 摄入进度/结果展示（复用状态机）
        when (ingestionState) {
            is KnowledgeBaseViewModel.IngestionUiState.Completed -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "已保存：${ingestionState.documentTitle}（${ingestionState.embedded} 分片）",
                    color = PrismMint,
                    fontSize = 11.sp
                )
            }
            is KnowledgeBaseViewModel.IngestionUiState.Failed -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = ingestionState.message,
                    color = PrismDanger,
                    fontSize = 11.sp
                )
            }
            else -> Unit
        }

        Spacer(Modifier.height(20.dp))
        PrismButton(
            text = "保存",
            onClick = { onSubmit(title, text) },
            enabled = title.isNotBlank() && text.isNotBlank()
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "取消", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 库内文档管理弹层（UX-001 问题 2，ADR-021）—— 展示库内文档列表，支持查看 / 删除 / 移动到其他库。
 *
 * 文档标题由 [KnowledgeBaseViewModel.listDocuments] 从 chunk title 聚合而来。
 * 每条文档行提供：
 * - 查看内容按钮（[onView]，UXR3 问题 12，ADR-023）
 * - 删除按钮（[onDelete]）
 * - 移动到其他库（[onMove]，目标库列表由 [libraries] 提供）
 */
@Composable
private fun ManageDocumentsSheet(
    kbName: String,
    documents: List<String>,
    libraries: List<KnowledgeBase>,
    sourceKbId: Long,
    isDefault: Boolean,
    onRefresh: () -> Unit,
    onView: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Long) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(title = "管理文档 · $kbName", subtitle = "点击库卡进入 · 查看 / 删除或移动") {
        if (documents.isEmpty()) {
            Text(
                text = "该库暂无文档。可在「导入」中选择文件，或使用「文本笔记」直接保存文字。",
                color = PrismTextFaint,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
            return@PrismSheet
        }
        // 可移动目标库（排除源库本身）
        val moveTargets: List<Pair<Long, String>> = buildList {
            if (!isDefault) {
                add(KnowledgeBaseRepository.DEFAULT_KB_ID to "默认库")
            }
            libraries.filter { it.id != sourceKbId }.forEach { add(it.id to it.name) }
        }

        documents.forEach { title ->
            // 文档行：标题 + 查看 + 删除 + 移动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrismPanel2)
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = PrismIcons.File,
                        contentDescription = null,
                        tint = PrismTextFaint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(PrismSize.s2))
                    Text(
                        text = title,
                        color = PrismText,
                        fontSize = PrismType.sub,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 查看内容：设计稿 `.mini` 是文字按钮，不是眼睛 emoji
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(PrismSize.rChip))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onView(title) }
                            )
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "查看", color = PrismTextDim, fontSize = PrismType.micro)
                    }
                    Spacer(Modifier.width(6.dp))
                    // 删除按钮
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrismDanger.copy(alpha = 0.12f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onDelete(title) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除文档",
                            tint = PrismDanger,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (moveTargets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "移动到：",
                        color = PrismTextFaint,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        moveTargets.forEach { (targetId, targetName) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PrismIndigo.copy(alpha = 0.12f))
                                    .border(1.dp, PrismIndigo.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onMove(title, targetId) }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(text = targetName, color = PrismIndigo, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 文档内容查看弹层（UXR3 问题 12，ADR-023）—— 直接展示已入库资料的全文。
 *
 * 从 [KnowledgeBaseViewModel.getDocumentContent] 获取分块拼接后的文档正文，
 * 供用户在不打开外部文件的情况下直接阅读知识库资料。
 *
 * 内容区用 LazyColumn 展示（长文档可滚动），顶部展示文档标题与分块数量提示。
 */
@Composable
private fun DocumentContentSheet(
    documentTitle: String,
    content: String,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "查看内容",
        subtitle = documentTitle
    ) {
        if (content.isBlank()) {
            Text(
                text = "该文档没有可展示的文本内容。",
                color = PrismTextFaint,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
            return@PrismSheet
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PrismPanel2)
                .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            LazyColumn {
                items(content.split('\n')) { line ->
                    Text(
                        text = line.ifEmpty { " " },
                        color = PrismText,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "按切片序号拼接 · ${content.length} 字符",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
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





