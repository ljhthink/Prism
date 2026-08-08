package io.prism.ui.knowledge

import io.objectbox.BoxStore
import io.prism.data.KnowledgeBase
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.MyObjectBox
import io.prism.document.Chunker
import io.prism.document.DocumentParserRegistry
import io.prism.embedding.Embedder
import io.prism.embedding.EmbeddingException
import io.prism.ingestion.IngestionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * KnowledgeBaseViewModel 单元测试（US-018 验收标准 1/2/3/4）。
 *
 * **测试策略**（ADR-011 5.3）：
 * - 真实 [KnowledgeBaseRepository]（+ 真实 ObjectBox 临时目录）验证 CRUD 与 chunk 计数
 * - 真实 [IngestionPipeline] + [FakeEmbedder] 替身验证摄入事件 → [KnowledgeBaseViewModel.IngestionUiState] 映射
 * - [CountingInputStreamProvider] 跟踪 openInputStream 调用次数，验证并发拒绝
 * - `Dispatchers.setMain(Dispatchers.Unconfined)` 使 init 块的 `libraries.collect` 同步执行
 *
 * **Dispatchers.IO 处理**：
 * [KnowledgeBaseViewModel.startIngestion] 在 `Dispatchers.IO` 协程中 collect 事件流。
 * 使用 `runBlocking` + [waitForState] 等待 IO 协程更新状态，避免虚拟时间复杂性。
 * [waitForState] 含 30s 超时保护，防止 IO 协程异常导致测试永久挂起。
 *
 * **Uri 解耦**（ADR-011 5.4）：
 * ViewModel 接受 `String` URI 而非 `android.net.Uri`，使纯 JVM 单测无需 Robolectric。
 *
 * US-018 验收标准：
 * 1. 知识库列表页显示分库
 * 2. 支持创建/删除分库
 * 3. 支持导入文档（解析→摄入进度展示）
 * 4. 摄入失败与未建索引提示
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeBaseViewModelTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: KnowledgeBaseRepository
    private lateinit var tempDir: File
    private lateinit var pipeline: IngestionPipeline

    @Before
    fun setUp() {
        // 使用 Dispatchers.Unconfined 作为 Main 调度器：
        // - 使 viewModelScope.init 的 libraries.collect 同步执行
        // - 不需要 TestCoroutineScheduler（runBlocking 无 scheduler）
        Dispatchers.setMain(Dispatchers.Unconfined)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "kb-vm-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = KnowledgeBaseRepository(boxStore)
        pipeline = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = FakeEmbedder(),
            repository = repository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        tempDir.deleteRecursively()
    }

    /**
     * 创建 ViewModel，注入可控的 [CountingInputStreamProvider]。
     *
     * @param text inputStreamProvider 返回的文档内容（null 表示模拟打开失败）
     */
    private fun createViewModel(
        text: String? = "段一。\n\n段二。"
    ): Pair<KnowledgeBaseViewModel, CountingInputStreamProvider> {
        val provider = CountingInputStreamProvider(text)
        val vm = KnowledgeBaseViewModel(repository, pipeline, provider::open)
        return vm to provider
    }

    /** 用指定 Embedder 构造 pipeline + ViewModel。 */
    private fun createViewModelWithEmbedder(
        embedder: Embedder,
        text: String? = "段一。\n\n段二。"
    ): KnowledgeBaseViewModel {
        val customPipeline = IngestionPipeline(
            parserRegistry = DocumentParserRegistry(),
            chunker = Chunker(chunkSize = 50, overlap = 0),
            embedder = embedder,
            repository = repository
        )
        val provider = CountingInputStreamProvider(text)
        return KnowledgeBaseViewModel(repository, customPipeline, provider::open)
    }

    /**
     * 等待 UI 状态满足 [predicate]，超时 30 秒（防止 IO 协程异常导致测试永久挂起）。
     *
     * startIngestion 在 Dispatchers.IO 协程中 collect 事件流，
     * 使用 runBlocking + first { } 等待 IO 协程更新状态。
     */
    private suspend fun waitForState(
        vm: KnowledgeBaseViewModel,
        timeoutMs: Long = 30_000,
        predicate: (KnowledgeBaseViewModel.KnowledgeBaseUiState) -> Boolean
    ): KnowledgeBaseViewModel.KnowledgeBaseUiState {
        val state = withTimeoutOrNull(timeoutMs) {
            vm.uiState.first { predicate(it) }
        }
        assertNotNull("等待 UI 状态超时（${timeoutMs}ms），当前状态: ${vm.uiState.value}", state)
        return state!!
    }

    // ==================== AC-1: 知识库列表页显示分库 ====================

    @Test
    fun `init loads empty libraries and sets isLoading false`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        val state = waitForState(vm) { !it.isLoading }
        assertFalse("init 后 isLoading 应为 false", state.isLoading)
        assertTrue("初始库列表应为空", state.libraries.isEmpty())
        assertEquals("默认库 chunk 计数应为 0", 0L, state.defaultKbChunkCount)
        assertTrue("chunkCounts 应为空", state.chunkCounts.isEmpty())
    }

    @Test
    fun `init reflects pre-existing libraries from repository`() = runBlocking {
        repository.save(KnowledgeBase(name = "工作"))
        repository.save(KnowledgeBase(name = "学习"))

        val (vm, _) = createViewModel(text = null)
        val state = waitForState(vm) { it.libraries.size == 2 }

        assertEquals("应显示 2 个预存库", 2, state.libraries.size)
        assertEquals("工作", state.libraries[0].name)
        assertEquals("学习", state.libraries[1].name)
    }

    // ==================== AC-2: 支持创建/删除分库 ====================

    // ===== createLibrary =====

    @Test
    fun `createLibrary valid name adds to list and clears error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)
        waitForState(vm) { !it.isLoading }

        vm.createLibrary("工作")

        val state = vm.uiState.value
        assertEquals("应新增 1 个库", 1, state.libraries.size)
        assertEquals("工作", state.libraries[0].name)
        assertNull("创建成功后 createLibraryError 应为 null", state.createLibraryError)
    }

    @Test
    fun `createLibrary empty name sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.createLibrary("")

        assertEquals("空名不应创建库", 0, vm.uiState.value.libraries.size)
        assertEquals("库名称不能为空", vm.uiState.value.createLibraryError)
    }

    @Test
    fun `createLibrary blank name sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.createLibrary("   ")

        assertEquals("空白名不应创建库", 0, vm.uiState.value.libraries.size)
        assertEquals("库名称不能为空", vm.uiState.value.createLibraryError)
    }

    @Test
    fun `createLibrary name with slash sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.createLibrary("工作/学习")

        assertEquals("含 / 的名称不应创建库", 0, vm.uiState.value.libraries.size)
        assertEquals("库名称不能包含 / 或控制字符", vm.uiState.value.createLibraryError)
    }

    @Test
    fun `createLibrary name with control char sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.createLibrary("工作\u0001学习")

        assertEquals("含控制字符的名称不应创建库", 0, vm.uiState.value.libraries.size)
        assertEquals("库名称不能包含 / 或控制字符", vm.uiState.value.createLibraryError)
    }

    @Test
    fun `createLibrary duplicate name sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)
        vm.createLibrary("工作")
        assertNull("首次创建应成功", vm.uiState.value.createLibraryError)

        vm.createLibrary("工作")

        assertEquals("重复名不应创建第二个库", 1, vm.uiState.value.libraries.size)
        assertEquals("已存在同名知识库", vm.uiState.value.createLibraryError)
    }

    @Test
    fun `createLibrary trims whitespace before validation`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.createLibrary("  工作  ")

        assertEquals("应去除首尾空白后创建库", 1, vm.uiState.value.libraries.size)
        assertEquals("工作", vm.uiState.value.libraries[0].name)
        assertNull(vm.uiState.value.createLibraryError)
    }

    @Test
    fun `clearCreateLibraryError resets error state`() = runBlocking {
        val (vm, _) = createViewModel(text = null)
        vm.createLibrary("")
        assertNotNull(vm.uiState.value.createLibraryError)

        vm.clearCreateLibraryError()

        assertNull(vm.uiState.value.createLibraryError)
    }

    // ===== deleteLibrary =====

    @Test
    fun `deleteLibrary valid id removes from list`() = runBlocking {
        val (vm, _) = createViewModel(text = null)
        vm.createLibrary("工作")
        val kbId = vm.uiState.value.libraries[0].id

        vm.deleteLibrary(kbId)

        assertEquals("删除后库列表应为空", 0, vm.uiState.value.libraries.size)
        assertNull("删除成功后 deleteLibraryError 应为 null", vm.uiState.value.deleteLibraryError)
    }

    @Test
    fun `deleteLibrary default kb id sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.deleteLibrary(KnowledgeBaseRepository.DEFAULT_KB_ID)

        assertEquals("默认知识库不可删除", vm.uiState.value.deleteLibraryError)
    }

    @Test
    fun `deleteLibrary negative id sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.deleteLibrary(-1L)

        assertEquals("无效的知识库 id", vm.uiState.value.deleteLibraryError)
    }

    @Test
    fun `deleteLibrary nonexistent id sets error`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.deleteLibrary(999L)

        assertEquals("知识库不存在或已被删除", vm.uiState.value.deleteLibraryError)
    }

    @Test
    fun `clearDeleteLibraryError resets error state`() = runBlocking {
        val (vm, _) = createViewModel(text = null)
        vm.deleteLibrary(-1L)
        assertNotNull(vm.uiState.value.deleteLibraryError)

        vm.clearDeleteLibraryError()

        assertNull(vm.uiState.value.deleteLibraryError)
    }

    @Test
    fun `deleteLibrary cascades chunks and updates chunkCounts`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。\n\n段二。")
        vm.createLibrary("工作")
        val kbId = vm.uiState.value.libraries[0].id

        vm.startIngestion("content://test/doc.txt", "doc.txt", kbId)
        waitForState(vm) { it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed }

        assertEquals("摄入后应有 2 chunk", 2L, repository.chunkCount(kbId))
        assertTrue("chunkCounts 应包含该库", vm.uiState.value.chunkCounts.containsKey(kbId))

        vm.deleteLibrary(kbId)

        assertEquals("删除库后 chunk 应级联删除", 0L, repository.chunkCount(kbId))
        assertFalse("chunkCounts 不应再包含该库", vm.uiState.value.chunkCounts.containsKey(kbId))
    }

    // ==================== AC-3: 支持导入文档（解析→摄入进度展示） ====================

    @Test
    fun `startIngestion success transitions Running then Completed`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。\n\n段二。")

        vm.startIngestion("content://test/doc.txt", "doc.txt", KnowledgeBaseRepository.DEFAULT_KB_ID)

        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed
        }
        val completed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Completed
        assertEquals("doc", completed.documentTitle)
        assertEquals("应嵌入 2 chunk", 2, completed.embedded)
        assertEquals("不应有未建索引", 0, completed.skipped)
        assertTrue("duration 应 >= 0", completed.durationMs >= 0)
    }

    @Test
    fun `startIngestion transitions through Running state`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。\n\n段二。")

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)

        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Running
        }
        val running = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Running
        assertEquals("doc", running.documentTitle)
    }

    @Test
    fun `startIngestion to custom kb persists chunks correctly`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。\n\n段二。")
        vm.createLibrary("工作")
        val kbId = vm.uiState.value.libraries[0].id

        vm.startIngestion("content://test/doc.txt", "doc.txt", kbId)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed
        }

        assertEquals("自建库应有 2 chunk", 2L, repository.chunkCount(kbId))
        assertEquals("默认库不应被污染", 0L, repository.chunkCount(KnowledgeBaseRepository.DEFAULT_KB_ID))
        assertEquals(2L, state.chunkCounts[kbId])
    }

    // ==================== AC-4: 摄入失败与未建索引提示 ====================

    @Test
    fun `startIngestion parse failure transitions to Failed with safe message`() = runBlocking {
        val (vm, _) = createViewModel(text = "binary content")

        vm.startIngestion("content://test/doc.xyz", "doc.xyz", 0L)

        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Failed
        }
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertEquals("doc", failed.documentTitle)
        assertEquals(
            "DocumentParseException 应映射为格式不支持文案",
            "文档格式不支持或已损坏",
            failed.message
        )
    }

    @Test
    fun `startIngestion null input stream transitions to Failed`() = runBlocking {
        val (vm, _) = createViewModel(text = null)

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)

        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Failed
        }
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertEquals("doc", failed.documentTitle)
        assertEquals("无法打开所选文件，请重新选择", failed.message)
    }

    @Test
    fun `startIngestion negative kbId transitions to Failed`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。")

        vm.startIngestion("content://test/doc.txt", "doc.txt", -1L)

        val state = vm.uiState.value
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertEquals("doc", failed.documentTitle)
        assertEquals("无效的知识库 id", failed.message)
    }

    @Test
    fun `startIngestion partial embedding failure completes with skipped count`() = runBlocking {
        val embedder = FakeEmbedder(failOnText = "第二段内容。")
        val vm = createViewModelWithEmbedder(
            embedder,
            text = "第一段内容。\n\n第二段内容。\n\n第三段内容。"
        )

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed
        }
        val completed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Completed
        assertEquals("总 chunk 数应为 3", 3, completed.embedded + completed.skipped)
        assertEquals("2 个嵌入成功", 2, completed.embedded)
        assertEquals("1 个未建索引", 1, completed.skipped)
    }

    @Test
    fun `startIngestion Running state shows skipped count when chunks skipped`() = runBlocking {
        val embedder = FakeEmbedder(failOnText = "第二段内容。")
        val vm = createViewModelWithEmbedder(
            embedder,
            text = "第一段内容。\n\n第二段内容。\n\n第三段内容。"
        )

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)

        val state = waitForState(vm) {
            val s = it.ingestionState
            s is KnowledgeBaseViewModel.IngestionUiState.Running && s.skipped > 0
        }
        val running = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Running
        assertEquals("doc", running.documentTitle)
        assertTrue("应有至少 1 个未建索引", running.skipped > 0)
    }

    @Test
    fun `startIngestion all chunks fail still completes with all skipped`() = runBlocking {
        val embedder = FakeEmbedder(failAll = true)
        val vm = createViewModelWithEmbedder(embedder, text = "段一。\n\n段二。")

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed
        }
        val completed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Completed
        assertEquals("全部嵌入失败", 0, completed.embedded)
        assertEquals("2 个未建索引", 2, completed.skipped)
    }

    // ==================== 并发约束 ====================

    @Test
    fun `startIngestion rejects concurrent call when already running`() = runBlocking {
        val text = (1..10).joinToString("\n\n") { "第${it}段独立内容，需足够长以确保独立成块。" }
        val (vm, provider) = createViewModel(text = text)

        vm.startIngestion("content://test/doc1.txt", "doc1.txt", 0L)
        waitForState(vm) { it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Running }

        // 第二次摄入（应被拒绝）
        vm.startIngestion("content://test/doc2.txt", "doc2.txt", 0L)

        waitForState(vm) { it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed }

        assertEquals("并发约束：第二次摄入应被拒绝", 1, provider.callCount)
    }

    // ==================== clearIngestionState ====================

    @Test
    fun `clearIngestionState resets to Idle`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。")
        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)
        waitForState(vm) { it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed }

        vm.clearIngestionState()

        assertTrue("清除后应为 Idle", vm.uiState.value.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Idle)
    }

    // ==================== chunkCounts 刷新 ====================

    @Test
    fun `chunkCounts updated after ingestion to default kb`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。\n\n段二。")
        waitForState(vm) { !it.isLoading }
        assertEquals("摄入前默认库 chunk 计数应为 0", 0L, vm.uiState.value.defaultKbChunkCount)

        vm.startIngestion("content://test/doc.txt", "doc.txt", KnowledgeBaseRepository.DEFAULT_KB_ID)
        waitForState(vm) { it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed }

        assertEquals("摄入后默认库 chunk 计数应为 2", 2L, vm.uiState.value.defaultKbChunkCount)
    }

    @Test
    fun `chunkCounts updated after ingestion to custom kb`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。\n\n段二。")
        vm.createLibrary("工作")
        val kbId = vm.uiState.value.libraries[0].id
        assertEquals("摄入前该库 chunk 计数应为 0", 0L, vm.uiState.value.chunkCounts[kbId])

        vm.startIngestion("content://test/doc.txt", "doc.txt", kbId)
        waitForState(vm) { it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed }

        assertEquals("摄入后该库 chunk 计数应为 2", 2L, vm.uiState.value.chunkCounts[kbId])
    }

    // ==================== 文档标题提取 ====================

    @Test
    fun `startIngestion extracts document title from filename with extension`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。")

        // 使用 .txt 扩展名匹配纯文本内容（.pdf 解析器无法解析纯文本，会触发 Failed）
        vm.startIngestion("content://test/report.txt", "report.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed
        }
        val completed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Completed
        assertEquals("report", completed.documentTitle)
    }

    @Test
    fun `startIngestion extracts document title from path-like filename`() = runBlocking {
        val (vm, _) = createViewModel(text = "段一。")

        vm.startIngestion("content://test/path", "/storage/emulated/0/docs/notes.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Completed
        }
        val completed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Completed
        assertEquals("notes", completed.documentTitle)
    }

    // ==================== 错误安全（BR-error-handling-003） ====================

    @Test
    fun `startIngestion failed message does not leak throwable details`() = runBlocking {
        val (vm, _) = createViewModel(text = "binary content")

        vm.startIngestion("content://test/doc.xyz", "doc.xyz", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Failed
        }
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertFalse("不应包含 RuntimeException", failed.message.contains("RuntimeException"))
        assertFalse("不应包含 Exception", failed.message.contains("Exception"))
        assertFalse("不应包含 java.", failed.message.contains("java."))
    }

    // ==================== openInputStream 异常路径（G-04 修复覆盖，ac-verifier TKN-US018-AC-001 补充） ====================
    // 验证 G-04 修复后新增的 catch(e: Exception) 分支：inputStreamProvider 抛异常时
    // 应捕获、记日志、归一化为 null，触发 UI Failed「无法打开所选文件，请重新选择」降级，不崩溃。

    @Test
    fun `startIngestion provider throws SecurityException transitions to Failed`() = runBlocking {
        val throwingProvider: (String) -> InputStream? = { throw SecurityException("test permission denied") }
        val vm = KnowledgeBaseViewModel(repository, pipeline, throwingProvider)

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Failed
        }
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertEquals("doc", failed.documentTitle)
        assertEquals("SecurityException 应降级为通用文案", "无法打开所选文件，请重新选择", failed.message)
    }

    @Test
    fun `startIngestion provider throws FileNotFoundException transitions to Failed`() = runBlocking {
        val throwingProvider: (String) -> InputStream? = { throw java.io.FileNotFoundException("test not found") }
        val vm = KnowledgeBaseViewModel(repository, pipeline, throwingProvider)

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Failed
        }
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertEquals("FileNotFoundException 应降级为通用文案", "无法打开所选文件，请重新选择", failed.message)
    }

    @Test
    fun `startIngestion provider throws IOException transitions to Failed`() = runBlocking {
        val throwingProvider: (String) -> InputStream? = { throw java.io.IOException("test io error") }
        val vm = KnowledgeBaseViewModel(repository, pipeline, throwingProvider)

        vm.startIngestion("content://test/doc.txt", "doc.txt", 0L)
        val state = waitForState(vm) {
            it.ingestionState is KnowledgeBaseViewModel.IngestionUiState.Failed
        }
        val failed = state.ingestionState as KnowledgeBaseViewModel.IngestionUiState.Failed
        assertEquals("IOException 应降级为通用文案", "无法打开所选文件，请重新选择", failed.message)
    }

    // ==================== 辅助类 ====================

    /**
     * 可控 FakeEmbedder（BR-testing-001，复用 IngestionPipelineTest 模式）。
     *
     * @param failOnText 匹配该文本时抛 EmbeddingException
     * @param failAll 全部嵌入失败
     */
    private class FakeEmbedder(
        private val failOnText: String? = null,
        private val failAll: Boolean = false
    ) : Embedder {
        override fun embed(text: String): FloatArray {
            if (failAll || text == failOnText) {
                throw EmbeddingException(EmbeddingException.Stage.INFERENCE, "Fake 注入失败")
            }
            val vector = FloatArray(384)
            vector[Math.floorMod(text.hashCode(), 384)] = 1.0f
            return vector
        }

        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }

    /**
     * 跟踪调用次数的 InputStream Provider。
     *
     * - [text] 非 null 时返回对应 ByteArrayInputStream
     * - [text] 为 null 时返回 null（模拟打开文件失败）
     * - [callCount] 记录被调用次数，用于验证并发拒绝
     */
    private class CountingInputStreamProvider(private val text: String?) {
        var callCount = 0
            private set

        fun open(uriString: String): InputStream? {
            callCount++
            return text?.let { ByteArrayInputStream(it.toByteArray()) }
        }
    }
}
