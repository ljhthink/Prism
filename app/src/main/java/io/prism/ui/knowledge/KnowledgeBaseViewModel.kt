package io.prism.ui.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.KnowledgeBase
import io.prism.data.KnowledgeBaseRepository
import io.prism.document.DocumentParseException
import io.prism.ingestion.IngestionEvent
import io.prism.ingestion.IngestionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 知识库管理 UI ViewModel（US-018，ADR-011）。
 *
 * **职责**：
 * - 暴露 [KnowledgeBaseUiState] 供 UI 订阅（库列表 + 默认库 chunk 计数 + 各库 chunk 计数 + 摄入状态 + 错误状态）
 * - 提供知识库 CRUD 操作：[createLibrary] / [deleteLibrary]
 * - 提供文档导入入口：[startIngestion]，内部收集 [IngestionEvent] 并映射到 [IngestionUiState]
 * - 错误安全映射：[IngestionEvent.Failed] 的 throwable 仅日志，UI 文案按异常类型映射（ADR-011 5.5）
 *
 * **架构**（ADR-011 5.3，仿 [io.prism.ui.settings.SettingsViewModel] / [io.prism.ui.capabilities.CapabilitiesViewModel]）：
 * - `by lazy` 注入 [KnowledgeBaseRepository] + [IngestionPipeline]，经 [Factory] 从 [PrismApplication] 取依赖
 * - [libraries] 直接订阅仓库 StateFlow（`stateIn` WhileSubscribed(5_000)）
 * - [uiState] 单一聚合 StateFlow，避免多 StateFlow 散落
 *
 * **线程安全**（ADR-011 风险表 / R-4 / guardrail G-01 G-02）：
 * - [startIngestion] 在 `Dispatchers.IO` 协程中 collect，避免 OnnxEmbedder 阻塞主线程
 * - [computeChunkCounts] / [refreshChunkCounts] 内的 ObjectBox 同步查询在调用方线程执行：
 *   init 块的 `libraries.collect` 在 Main 协程，[startIngestion] 的 Completed 事件在 IO 协程。
 *   4GB 低端机限制库容量（ADR-007），库数量少时 Main 线程阻塞可接受；若未来库数量增长，
 *   应将 init collect 改为 `withContext(Dispatchers.IO) { refreshChunkCounts(libs) }`。
 * - `_uiState.update { it.copy(...) }` 原子 CAS，避免 Main 协程与 IO 协程并发写导致 lost update
 * - 单次摄入仅维护最新 Running 状态（StateFlow 默认 conflate 最新值，无需额外节流，ADR-011 5.7）
 *
 * **错误安全**（BR-error-handling-003 / BR-error-handling-004 / ADR-011 5.5）：
 * - [IngestionEvent.Failed] 的 `throwable.message` / 堆栈禁止透传到 UI
 * - [mapFailedToMessage] 按异常类型映射通用文案：[DocumentParseException] →「文档格式不支持或已损坏」；其他 →「文档摄入失败，请检查文件或重试」
 * - Failed 事件与 catch 兜底分支用 [logger] 记录 WARN 级日志（仅含 throwable 类型 simpleName，不含 message/密钥/路径，
 *   ADR-011 5.5 契约；堆栈经 throwable 第三参数输出供开发诊断），不静默吞异常（BR-error-handling-004）。
 *   ViewModel 层用 `java.util.logging.Logger` 而非 `android.util.Log`，
 *   以保持纯 JVM 单测兼容（与项目其他 ViewModel 一致，无 Android 框架依赖）
 *
 * US-018 验收标准：
 * 1. 知识库列表页显示分库
 * 2. 支持创建/删除分库
 * 3. 支持导入文档（解析→摄入进度展示）
 * 4. 摄入失败与未建索引提示
 *
 * @param repository 知识库分库仓库（US-015）
 * @param pipeline 摄入管线（US-016）
 * @param inputStreamProvider 由 Screen 注入的 URI 字符串 → InputStream 转换器
 *        （解耦 android.net.Uri 与 Android ContentResolver，便于纯 JVM 单测；
 *        Factory 内部将 android.net.Uri 转为 String 传入，ViewModel 类本身不依赖 Android 框架类）
 */
class KnowledgeBaseViewModel(
    private val repository: KnowledgeBaseRepository,
    private val pipeline: IngestionPipeline,
    private val inputStreamProvider: (String) -> InputStream?
) : ViewModel() {

    private val logger = Logger.getLogger("KnowledgeBaseViewModel")

    /**
     * 知识库 UI 状态（ADR-011 5.3）。
     *
     * 单一数据类聚合所有 UI 状态，UI 通过 `collectAsState()` 一次性订阅。
     *
     * @param isLoading 首次加载中（库列表初始空时为 true，首次刷新完成后置 false）
     * @param libraries 自建知识库列表（不含虚拟默认库，按 createdAt 升序）
     * @param defaultKbChunkCount 默认库（id=0L）下的 chunk 计数，运行时聚合（ADR-008 5.1）
     * @param chunkCounts 各自建库 id → chunk 计数映射，UI 渲染时按 id 查找避免重复查询
     * @param createLibraryError 创建库错误文案（null 表示无错误）
     * @param deleteLibraryError 删除库错误文案（null 表示无错误）
     * @param ingestionState 摄入状态机（Idle/Running/Completed/Failed）
     */
    data class KnowledgeBaseUiState(
        val isLoading: Boolean = true,
        val libraries: List<KnowledgeBase> = emptyList(),
        val defaultKbChunkCount: Long = 0L,
        val chunkCounts: Map<Long, Long> = emptyMap(),
        val createLibraryError: String? = null,
        val deleteLibraryError: String? = null,
        val ingestionState: IngestionUiState = IngestionUiState.Idle
    )

    /**
     * 摄入状态机（ADR-011 5.3，仿 [io.prism.ui.capabilities.CapabilitiesViewModel.TestState]）。
     *
     * - [Idle]：初始或已清除状态
     * - [Running]：摄入中，实时反映 embedded/skipped/total 进度
     * - [Completed]：摄入完成（含成功 + 部分降级），展示未建索引片段数
     * - [Failed]：摄入致命错误，展示通用安全文案（不暴露 throwable）
     */
    sealed interface IngestionUiState {
        /** 初始或已清除状态。 */
        data object Idle : IngestionUiState

        /**
         * 摄入中。
         * @param documentTitle 文档标题（去扩展名）
         * @param embedded 已嵌入 chunk 数
         * @param total 总 chunk 数（来自 Chunked 事件）
         * @param skipped 未建索引 chunk 数（来自 ChunkSkipped 事件累计）
         */
        data class Running(
            val documentTitle: String,
            val embedded: Int,
            val total: Int,
            val skipped: Int
        ) : IngestionUiState

        /**
         * 摄入完成（含成功 + 部分降级）。
         * @param documentTitle 文档标题
         * @param embedded 已嵌入 chunk 数
         * @param skipped 未建索引 chunk 数（UI 据此提示「N 个片段未建索引」，AC-4）
         * @param durationMs 摄入耗时（毫秒）
         */
        data class Completed(
            val documentTitle: String,
            val embedded: Int,
            val skipped: Int,
            val durationMs: Long
        ) : IngestionUiState

        /**
         * 摄入致命错误（如解析失败、不可恢复异常）。
         * @param documentTitle 文档标题
         * @param message 通用安全文案（不暴露 throwable.message/堆栈，BR-error-handling-003）
         */
        data class Failed(
            val documentTitle: String,
            val message: String
        ) : IngestionUiState
    }

    /** 自建知识库列表（订阅仓库，UI 经 [uiState] 间接消费）。 */
    val libraries: StateFlow<List<KnowledgeBase>> = repository.knowledgeBases
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.knowledgeBases.value)

    private val _uiState = MutableStateFlow(KnowledgeBaseUiState())
    /** 聚合 UI 状态。 */
    val uiState: StateFlow<KnowledgeBaseUiState> = _uiState.asStateFlow()

    init {
        // 订阅库列表变化，同步刷新 chunk 计数与 isLoading
        viewModelScope.launch {
            libraries.collect { libs ->
                refreshChunkCounts(libs)
                // G-01 修复：原子 CAS，避免与 startIngestion 的 IO 协程并发写导致 lost update
                _uiState.update { it.copy(isLoading = false, libraries = libs) }
            }
        }
    }

    /**
     * 计算默认库与各自建库的 chunk 计数（纯函数，不写状态）。
     *
     * **性能说明**：ObjectBox `chunkCount` 是 `query().count()`，单次查询 ~ms 级。
     * 4GB 低端机限制库容量（ADR-007），库数量少时 Main 线程阻塞可接受。
     * 若未来库数量增长，应改用 `withContext(Dispatchers.IO)` 切换到 IO 线程。
     *
     * 由 [refreshChunkCounts] 与 [startIngestion] 的 `Completed` 事件处理调用。
     * 拆分为纯函数是为了让 `Completed` 事件能把 ingestionState 与 chunkCounts
     * 合并到**同一次** `_uiState.update`，避免「Completed 已发出但 chunkCounts
     * 尚未刷新」的中间状态被 UI 订阅者观察到（状态原子性，ADR-011 5.7）。
     *
     * @return Pair(defaultKbChunkCount, chunkCounts Map)
     */
    private fun computeChunkCounts(libs: List<KnowledgeBase>): Pair<Long, Map<Long, Long>> {
        val defaultCount = repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID)
        val counts = mutableMapOf<Long, Long>()
        for (kb in libs) {
            counts[kb.id] = repository.chunkCount(kb.id)
        }
        return defaultCount to counts
    }

    /**
     * 刷新默认库与各自建库的 chunk 计数到 [_uiState]（同步调用）。
     *
     * 由 init 块的 `libraries.collect` 调用（Main 协程）。摄入完成路径不走本方法，
     * 而是 [computeChunkCounts] + 一次性 copy（见 [startIngestion] 的 Completed 分支）。
     */
    private fun refreshChunkCounts(libs: List<KnowledgeBase>) {
        val (defaultCount, counts) = computeChunkCounts(libs)
        // G-01 修复：原子 CAS
        _uiState.update { it.copy(defaultKbChunkCount = defaultCount, chunkCounts = counts) }
    }

    /**
     * 创建知识库（AC-2）。
     *
     * **校验**：
     * - 名称非空且去除首尾空白后非空
     * - 名称不与既有库重名（[KnowledgeBaseRepository.findByName] 唯一性校验）
     * - 名称不含 `/` 与控制字符（仿 [PrismApplication.registerFilesystemRoot] C5 清洗规则）
     *
     * 失败时设置 [KnowledgeBaseUiState.createLibraryError]，不抛异常（UI 友好）。
     *
     * @param name 库名称
     */
    fun createLibrary(name: String) {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> {
                // G-01 修复：原子 CAS
                _uiState.update { it.copy(createLibraryError = "库名称不能为空") }
            }
            trimmed.any { it == '/' || it.isISOControl() } -> {
                _uiState.update { it.copy(createLibraryError = "库名称不能包含 / 或控制字符") }
            }
            repository.findByName(trimmed) != null -> {
                _uiState.update { it.copy(createLibraryError = "已存在同名知识库") }
            }
            else -> {
                // G-05 修复：兜底 ObjectBox 运行期异常（DbException/磁盘满），避免 UI onClick 同步调用崩溃。
                // createLibrary 为非 suspend 函数，由 UI onClick 同步调用，不会抛 CancellationException，
                // catch Exception 安全；与 deleteLibrary 既有 try-catch 模式对齐统一。
                try {
                    repository.save(KnowledgeBase(name = trimmed))
                    _uiState.update { it.copy(createLibraryError = null) }
                } catch (e: Exception) {
                    // BR-error-handling-004 + R2-1 修复（ac-verifier TKN-US018-AC-001）：
                    // 结构化日志仅保留异常类型（simpleName），不含 message/路径（ADR-011 5.5 契约）。
                    // 堆栈经 throwable 第三参数输出供开发诊断，不暴露给 UI。
                    logger.log(
                        Level.WARNING,
                        "createLibrary save failed: ${e.javaClass.simpleName}",
                        e
                    )
                    _uiState.update { it.copy(createLibraryError = "创建知识库失败，请重试") }
                }
            }
        }
    }

    /** 清除创建库错误状态（UI 用户 dismiss 错误提示时调用）。 */
    fun clearCreateLibraryError() {
        // G-01 修复：原子 CAS
        _uiState.update { it.copy(createLibraryError = null) }
    }

    /**
     * 删除知识库（AC-2）。
     *
     * **默认库拒绝删除**（ADR-008 5.4）：[KnowledgeBaseRepository.DEFAULT_KB_ID] 不在 [libraries] 列表中，
     * 但 UI 可能误传，ViewModel 入口再次校验拒绝。负数 id 同样拒绝（纵深防御）。
     *
     * 失败时设置 [KnowledgeBaseUiState.deleteLibraryError]，不抛异常（UI 友好）。
     *
     * @param id KnowledgeBase id（必须 >0，禁止 0L 默认库，禁止负数）
     */
    fun deleteLibrary(id: Long) {
        when {
            id < 0 -> {
                // G-01 修复：原子 CAS
                _uiState.update { it.copy(deleteLibraryError = "无效的知识库 id") }
            }
            id == KnowledgeBaseRepository.DEFAULT_KB_ID -> {
                _uiState.update { it.copy(deleteLibraryError = "默认知识库不可删除") }
            }
            repository.get(id) == null -> {
                _uiState.update { it.copy(deleteLibraryError = "知识库不存在或已被删除") }
            }
            else -> {
                // G-05 修复：catch 范围从 IllegalArgumentException 扩展到 Exception，
                // 覆盖 ObjectBox 运行期异常（DbException/磁盘满/HNSW 相关 IllegalStateException 等）。
                // deleteLibrary 为非 suspend 函数，由 UI onClick 同步调用，不会抛 CancellationException，
                // catch Exception 安全；与 createLibrary 修复后模式对齐统一。
                try {
                    repository.remove(id)
                    _uiState.update { it.copy(deleteLibraryError = null) }
                } catch (e: Exception) {
                    // BR-error-handling-004 + R2-1 修复（ac-verifier TKN-US018-AC-001）：
                    // 结构化日志仅保留异常类型（simpleName），不含 message/路径（ADR-011 5.5 契约）。
                    // 堆栈经 throwable 第三参数输出供开发诊断，不暴露给 UI。
                    logger.log(
                        Level.WARNING,
                        "deleteLibrary remove failed: ${e.javaClass.simpleName}",
                        e
                    )
                    _uiState.update { it.copy(deleteLibraryError = "删除知识库失败") }
                }
            }
        }
    }

    /** 清除删除库错误状态。 */
    fun clearDeleteLibraryError() {
        // G-01 修复：原子 CAS
        _uiState.update { it.copy(deleteLibraryError = null) }
    }

    /**
     * 启动文档摄入（AC-3 / AC-4）。
     *
     * **流程**（ADR-011 5.4）：
     * 1. 经 [inputStreamProvider] 从 Uri 打开 InputStream；返回 null 时安全降级，设置 Failed 状态
     * 2. 在 `Dispatchers.IO` 协程中 collect [IngestionEvent]，按事件类型映射到 [IngestionUiState]
     * 3. 完成后刷新 chunkCounts（chunk 已写入目标库）
     *
     * **线程安全**（R-4）：`Dispatchers.IO` collect，避免 OnnxEmbedder 阻塞主线程。
     *
     * **并发约束**：若当前已处于 [IngestionUiState.Running]，拒绝新摄入（单次仅一个任务，避免 OnnxEmbedder 锁竞争）。
     *
     * **协程取消**（BR-concurrency-002）：用户离开 Tab 时 viewModelScope 自动取消 collect；
     * IngestionPipeline 在 chunk 边界 ensureActive 响应取消（ADR-009 5.6）。
     * CancellationException 必须重新抛出，不吞（Kotlin 协程铁律）。
     *
     * @param uriString 文档 URI 字符串（由 SAF OpenDocument 返回的 android.net.Uri.toString()）
     * @param fileName 文件名（含扩展名，用于解析器分发与错误溯源）
     * @param knowledgeBaseId 目标知识库 id（0L=默认库，>0=自建库，禁止负数）
     */
    fun startIngestion(uriString: String, fileName: String, knowledgeBaseId: Long) {
        // 并发约束：摄入中拒绝新任务
        if (_uiState.value.ingestionState is IngestionUiState.Running) {
            return
        }
        // 入口校验 knowledgeBaseId（与 Repository 层 require 一致，但提前给出友好错误）
        if (knowledgeBaseId < 0) {
            // G-01 修复：原子 CAS
            _uiState.update {
                it.copy(
                    ingestionState = IngestionUiState.Failed(
                        documentTitle = extractDocumentTitle(fileName),
                        message = "无效的知识库 id"
                    )
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val input = try {
                inputStreamProvider(uriString)
            } catch (e: CancellationException) {
                // 结构化并发铁律：CancellationException 必须重新抛出，不吞
                throw e
            } catch (e: Exception) {
                // G-04 修复 + R2-1 修复（ac-verifier TKN-US018-AC-001）：BR-error-handling-004 禁止静默吞异常——
                // 记录异常类型（simpleName，不含 message/路径，ADR-011 5.5 契约），归一化为 null 触发 UI 通用降级文案。
                // 堆栈经 throwable 第三参数输出供开发诊断。
                logger.log(
                    Level.WARNING,
                    "openInputStream failed: ${e.javaClass.simpleName}",
                    e
                )
                null
            }
            if (input == null) {
                // G-01 修复：原子 CAS
                _uiState.update {
                    it.copy(
                        ingestionState = IngestionUiState.Failed(
                            documentTitle = extractDocumentTitle(fileName),
                            message = "无法打开所选文件，请重新选择"
                        )
                    )
                }
                return@launch
            }

            val documentTitle = extractDocumentTitle(fileName)
            var embedded = 0
            var skipped = 0
            var total = 0

            try {
                pipeline.ingest(fileName, input, knowledgeBaseId, documentTitle).collect { event ->
                    when (event) {
                        is IngestionEvent.Started -> {
                            // G-01 修复：原子 CAS
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Running(
                                        documentTitle = documentTitle,
                                        embedded = 0,
                                        total = 0,
                                        skipped = 0
                                    )
                                )
                            }
                        }
                        is IngestionEvent.Parsed -> {
                            // 解析完成，进度仍为 0（chunk 数未知，等待 Chunked 事件）
                        }
                        is IngestionEvent.Chunked -> {
                            total = event.totalChunks
                            // G-01 修复：原子 CAS
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Running(
                                        documentTitle = documentTitle,
                                        embedded = embedded,
                                        total = total,
                                        skipped = skipped
                                    )
                                )
                            }
                        }
                        is IngestionEvent.ChunkEmbedded -> {
                            embedded++
                            // G-01 修复：原子 CAS
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Running(
                                        documentTitle = documentTitle,
                                        embedded = embedded,
                                        total = event.total,
                                        skipped = skipped
                                    )
                                )
                            }
                        }
                        is IngestionEvent.ChunkSkipped -> {
                            skipped++
                            // G-01 修复：原子 CAS
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Running(
                                        documentTitle = documentTitle,
                                        embedded = embedded,
                                        total = event.total,
                                        skipped = skipped
                                    )
                                )
                            }
                        }
                        is IngestionEvent.Completed -> {
                            // 状态原子性（ADR-011 5.7）：ingestionState 与 chunkCounts 必须在同一次
                            // update 中刷新，避免「Completed 已发出但 chunkCounts 仍是旧值」
                            // 的中间状态被 UI 订阅者观察到（也避免测试 waitForState 捕获到不一致的快照）。
                            // G-01 修复：update 原子 CAS，避免与 init 的 Main 协程并发写导致 lost update。
                            val (defaultCount, counts) = computeChunkCounts(libraries.value)
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Completed(
                                        documentTitle = documentTitle,
                                        embedded = event.result.embeddedChunks,
                                        skipped = event.result.skippedChunks,
                                        durationMs = event.result.durationMs
                                    ),
                                    defaultKbChunkCount = defaultCount,
                                    chunkCounts = counts
                                )
                            }
                        }
                        is IngestionEvent.Failed -> {
                            // 安全映射（ADR-011 5.5）：throwable 仅日志，UI 文案按异常类型映射
                            // G-03 修复 + R2-1 修复（ac-verifier TKN-US018-AC-001）：BR-error-handling-004 + ADR-011 5.5 显式契约——
                            // 记录 WARN 级结构化日志，仅含异常类型（simpleName），不含 message/路径（IngestionEvent.Failed
                            // KDoc 明确 throwable.message 可能含内部路径/类名）。堆栈经 throwable 第三参数输出供开发诊断。
                            logger.log(
                                Level.WARNING,
                                "ingestion failed: ${event.throwable.javaClass.simpleName}",
                                event.throwable
                            )
                            // G-01 修复：原子 CAS
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Failed(
                                        documentTitle = documentTitle,
                                        message = mapFailedToMessage(event.throwable)
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消必须重新抛出，不吞（Kotlin 协程铁律）
                throw e
            } catch (e: LinkageError) {
                // DEF-005 止血（Bug-4）：POI/PDFBox/onnx 在真机的类加载/ServiceLoader/native
                // 加载失败抛 Error（NoClassDefFoundError/ServiceConfigurationError/UnsatisfiedLinkError），
                // 穿透协程闪退。此处归一化为 Failed 状态，阻断崩溃。
                logger.log(
                    Level.WARNING,
                    "ingestion pipeline collect failed (linkage): ${e.javaClass.simpleName}",
                    e
                )
                _uiState.update {
                    it.copy(
                        ingestionState = IngestionUiState.Failed(
                            documentTitle = documentTitle,
                            message = "文档摄入失败，请检查文件或重试"
                        )
                    )
                }
            } catch (e: Exception) {
                // G-05 修复：catch 范围从 IllegalArgumentException 扩展到 Exception，
                // 覆盖 ObjectBox 运行期异常（DbException/磁盘满/HNSW 相关 IllegalStateException 等）。
                // G-03 修复 + R2-1 修复（ac-verifier TKN-US018-AC-001）：BR-error-handling-004 记录结构化日志，
                // 仅含异常类型（simpleName），不含 message/路径（ADR-011 5.5 契约）；堆栈经 throwable 第三参数输出。
                logger.log(
                    Level.WARNING,
                    "ingestion pipeline collect failed: ${e.javaClass.simpleName}",
                    e
                )
                // G-01 修复：原子 CAS
                _uiState.update {
                    it.copy(
                        ingestionState = IngestionUiState.Failed(
                            documentTitle = documentTitle,
                            message = "文档摄入失败，请检查文件或重试"
                        )
                    )
                }
            }
        }
    }

    /** 清除摄入状态（UI 用户 dismiss 完成或错误提示时调用）。 */
    fun clearIngestionState() {
        // G-01 修复：原子 CAS
        _uiState.update { it.copy(ingestionState = IngestionUiState.Idle) }
    }

    /**
     * 启动纯文本直接入库（UX-001 问题 2，ADR-021）。
     *
     * 复用 [startIngestion] 的进度映射逻辑（[IngestionUiState] 状态机），
     * 经 [IngestionPipeline.ingestText] 直接文本切片 → 嵌入 → 入库。
     *
     * **校验**（fail-fast）：标题空白 / 文本空白时设置 Failed 状态；知识库 id 负数同样拒绝。
     *
     * **线程安全**：`Dispatchers.IO` collect，与 [startIngestion] 一致。
     *
     * @param title 文档标题（用户输入）
     * @param text 要入库的纯文本
     * @param knowledgeBaseId 目标知识库 id
     */
    fun startTextIngestion(title: String, text: String, knowledgeBaseId: Long) {
        if (_uiState.value.ingestionState is IngestionUiState.Running) return
        val trimmedTitle = title.trim()
        val trimmedText = text.trim()
        if (trimmedTitle.isEmpty()) {
            _uiState.update {
                it.copy(ingestionState = IngestionUiState.Failed(
                    documentTitle = "文本笔记",
                    message = "标题不能为空"
                ))
            }
            return
        }
        if (trimmedText.isEmpty()) {
            _uiState.update {
                it.copy(ingestionState = IngestionUiState.Failed(
                    documentTitle = trimmedTitle,
                    message = "内容不能为空"
                ))
            }
            return
        }
        if (knowledgeBaseId < 0) {
            _uiState.update {
                it.copy(ingestionState = IngestionUiState.Failed(
                    documentTitle = trimmedTitle,
                    message = "无效的知识库 id"
                ))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            var embedded = 0
            var skipped = 0
            var total = 0
            try {
                pipeline.ingestText(trimmedTitle, trimmedText, knowledgeBaseId).collect { event ->
                    when (event) {
                        is IngestionEvent.Started -> {
                            _uiState.update {
                                it.copy(ingestionState = IngestionUiState.Running(
                                    documentTitle = trimmedTitle, embedded = 0, total = 0, skipped = 0
                                ))
                            }
                        }
                        is IngestionEvent.Parsed -> Unit // 文本入库无解析事件
                        is IngestionEvent.Chunked -> {
                            total = event.totalChunks
                            _uiState.update {
                                it.copy(ingestionState = IngestionUiState.Running(
                                    documentTitle = trimmedTitle, embedded = embedded, total = total, skipped = skipped
                                ))
                            }
                        }
                        is IngestionEvent.ChunkEmbedded -> {
                            embedded++
                            _uiState.update {
                                it.copy(ingestionState = IngestionUiState.Running(
                                    documentTitle = trimmedTitle, embedded = embedded, total = event.total, skipped = skipped
                                ))
                            }
                        }
                        is IngestionEvent.ChunkSkipped -> {
                            skipped++
                            _uiState.update {
                                it.copy(ingestionState = IngestionUiState.Running(
                                    documentTitle = trimmedTitle, embedded = embedded, total = event.total, skipped = skipped
                                ))
                            }
                        }
                        is IngestionEvent.Completed -> {
                            val (defaultCount, counts) = computeChunkCounts(libraries.value)
                            _uiState.update {
                                it.copy(
                                    ingestionState = IngestionUiState.Completed(
                                        documentTitle = trimmedTitle,
                                        embedded = event.result.embeddedChunks,
                                        skipped = event.result.skippedChunks,
                                        durationMs = event.result.durationMs
                                    ),
                                    defaultKbChunkCount = defaultCount,
                                    chunkCounts = counts
                                )
                            }
                        }
                        is IngestionEvent.Failed -> {
                            logger.log(
                                Level.WARNING,
                                "text ingestion failed: ${event.throwable.javaClass.simpleName}",
                                event.throwable
                            )
                            _uiState.update {
                                it.copy(ingestionState = IngestionUiState.Failed(
                                    documentTitle = trimmedTitle,
                                    message = mapFailedToMessage(event.throwable)
                                ))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                logger.log(Level.WARNING, "text ingestion collect failed (linkage): ${e.javaClass.simpleName}", e)
                _uiState.update {
                    it.copy(ingestionState = IngestionUiState.Failed(
                        documentTitle = trimmedTitle,
                        message = "文档摄入失败，请检查文件或重试"
                    ))
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "text ingestion collect failed: ${e.javaClass.simpleName}", e)
                _uiState.update {
                    it.copy(ingestionState = IngestionUiState.Failed(
                        documentTitle = trimmedTitle,
                        message = "文档摄入失败，请检查文件或重试"
                    ))
                }
            }
        }
    }

    /**
     * 列出指定知识库下的文档标题列表（UX-001 问题 2，ADR-021）。
     *
     * 委托 [KnowledgeBaseRepository.listDocuments]，用于 UI 展示库内文档。
     * 同步查询（ObjectBox 快查，4GB 低端机库容量受限可接受）。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 文档标题列表
     */
    fun listDocuments(knowledgeBaseId: Long): List<String> = repository.listDocuments(knowledgeBaseId)

    /**
     * 获取指定知识库下某文档的完整内容（UXR3 问题 12，ADR-023）。
     *
     * 委托 [KnowledgeBaseRepository.getDocumentContent]，按分块序号升序拼接全文，
     * 供 UI「查看内容」弹层直接展示已入库资料。
     *
     * @param knowledgeBaseId 知识库 id
     * @param documentTitle 文档标题
     * @return 文档全文（无匹配时返回空串）
     */
    fun getDocumentContent(knowledgeBaseId: Long, documentTitle: String): String =
        repository.getDocumentContent(knowledgeBaseId, documentTitle)

    /**
     * 删除指定知识库下的文档（UX-001 问题 2，ADR-021）。
     *
     * 委托 [KnowledgeBaseRepository.deleteDocument]，删除后刷新 chunk 计数。
     * 同步调用（ObjectBox 事务），失败静默降级（不抛异常，UI 无需感知细节）。
     *
     * @param knowledgeBaseId 知识库 id
     * @param documentTitle 文档标题
     * @return 删除的 chunk 数量
     */
    fun deleteDocument(knowledgeBaseId: Long, documentTitle: String): Long {
        val removed = try {
            repository.deleteDocument(knowledgeBaseId, documentTitle)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "deleteDocument failed: ${e.javaClass.simpleName}", e)
            0L
        }
        refreshChunkCounts(libraries.value)
        return removed
    }

    /**
     * 移动文档到目标知识库（UX-001 问题 2，ADR-021）。
     *
     * 委托 [KnowledgeBaseRepository.moveDocument]，移动后刷新 chunk 计数。
     *
     * @param sourceKbId 源知识库 id
     * @param documentTitle 文档标题
     * @param targetKbId 目标知识库 id
     * @return 移动的 chunk 数量
     */
    fun moveDocument(sourceKbId: Long, documentTitle: String, targetKbId: Long): Long {
        val moved = try {
            repository.moveDocument(sourceKbId, documentTitle, targetKbId)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "moveDocument failed: ${e.javaClass.simpleName}", e)
            0L
        }
        refreshChunkCounts(libraries.value)
        return moved
    }

    /**
     * 将 [IngestionEvent.Failed] 的 throwable 映射为通用安全文案（ADR-011 5.5）。
     *
     * **安全约定**（[IngestionEvent.Failed] KDoc / BR-error-handling-003）：
     * - `throwable.message` / 堆栈禁止展示给用户（可能含内部路径/类名）
     * - 仅按异常类型区分可诊断类别
     *
     * @param throwable 摄入失败的异常（仅供类型判断，不读取 message）
     * @return 通用安全文案
     */
    private fun mapFailedToMessage(throwable: Throwable): String = when (throwable) {
        is DocumentParseException -> "文档格式不支持或已损坏"
        else -> "文档摄入失败，请检查文件或重试"
    }

    companion object {
        /**
         * 从文件名提取默认文档标题（去扩展名，与 [IngestionPipeline] 默认逻辑一致）。
         *
         * 复用 [IngestionPipeline] 的 `defaultTitle` 私有逻辑：取最后一个路径分隔符后的 basename，
         * 再去最后一个 `.` 后的扩展名。
         */
        private fun extractDocumentTitle(fileName: String): String {
            val lastSep = fileName.lastIndexOfAny(charArrayOf('/', '\\'))
            val base = if (lastSep >= 0) fileName.substring(lastSep + 1) else fileName
            val dot = base.lastIndexOf('.')
            return if (dot > 0) base.substring(0, dot) else base
        }

        /** 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                KnowledgeBaseViewModel(
                    repository = app.knowledgeBaseRepository,
                    pipeline = app.ingestionPipeline,
                    inputStreamProvider = { uriString -> app.contentResolver.openInputStream(android.net.Uri.parse(uriString)) }
                )
            }
        }
    }
}
