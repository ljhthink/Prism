package io.prism.ui.chat

import io.objectbox.BoxStore
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.KnowledgeChunk
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.embedding.Embedder
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.rag.RagContextBuilder
import io.prism.rag.RagTarget
import io.prism.fs.ToolConfirmationGate
import io.prism.skill.SkillExecutor
import io.prism.network.McpToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **ADR-050（TASK-20260912-002）知识库注入契约测试**（主 Agent 基础用例，BR-testing-004）。
 *
 * **契约（本文件钉住的不变量）**：
 * 1. **客户端永不把知识库正文放进请求体**——无论用户消息内容、无论知识库中是否存在高相似度片段、
 *    无论知识库模式为何。`ragContext`（协议层独立 user 消息）恒为 `null`。
 * 2. **知识库模式开关只控制"能力是否暴露"**：[RagTarget.Off] 与低端档（`ragTopK <= 0`）
 *    → 不注入 `knowledge_base__*` 工具、不注入能力声明；其余模式 → 工具 + 能力声明都在。
 * 3. **能力声明稳定**：同一配置下多轮请求的 systemPrompt 逐字节相同（前缀缓存友好），
 *    且随 [RagTarget] 变化（含库范围信息）。
 * 4. **正文只说经工具进入**：知识库正文只能出现在 role=TOOL 的消息里（即 LLM 主动调用工具的结果），
 *    该场景由 `SkillExecutor` / `KnowledgeBaseLocalToolExecutor` 的既有测试覆盖。
 *
 * **上游依据**（本契约针对的真机缺陷）：
 * - `docs/reports/2026-09-12-kb-auto-injection-archaeology.md`（结构性根因：自动注入范式 +
 *   以相似度阈值充当需求判据）
 * - `docs/reports/2026-09-12-kb-injection-fix-history.md`（5 轮修复均未改范式 → 反复复发）
 * - `docs/reports/2026-09-12-kb-corpus-recovery.md`（真机语料实测：40 条日常提问 13 条越阈，
 *   大量无检索意图的消息会命中 → 客户端自动注入必然污染上下文）
 *
 * @see ConversationViewModel.buildRagPromptFor
 * @see RagContextBuilder.buildToolGuidance
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelKnowledgeBaseInjectionTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "kb-injection-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun providerRepo(): ProviderConfigRepository {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(
            name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            apiKeyRef = "openai", models = listOf("gpt-4o")
        )
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
        return repo
    }

    // ==================== 1. 纯函数：能力声明的门控与内容 ====================

    @Test
    fun `buildRagPromptFor returns null when rag target off`() {
        assertNull(
            "关闭知识库 → 不声明能力",
            ConversationViewModel.buildRagPromptFor(RagTarget.Off, ragTopK = 3)
        )
    }

    @Test
    fun `buildRagPromptFor returns null on low tier even when target on`() {
        // MINIMAL / CHAT_ONLY 档：ragTopK <= 0 → 不加载嵌入模型，故不暴露知识库能力
        assertNull(
            "低端档（ragTopK=0）→ 不声明能力",
            ConversationViewModel.buildRagPromptFor(RagTarget.AllLibraries, ragTopK = 0)
        )
        assertNull(
            "防御性负值同样不声明",
            ConversationViewModel.buildRagPromptFor(RagTarget.AllLibraries, ragTopK = -1)
        )
    }

    @Test
    fun `buildRagPromptFor declares capability with scope on full libraries`() {
        val prompt = ConversationViewModel.buildRagPromptFor(RagTarget.AllLibraries, ragTopK = 3)
        assertNotNull("全库模式应声明能力", prompt)
        assertTrue("应含能力声明标题", prompt!!.contains("【知识库能力】"))
        assertTrue("应含全库范围描述", prompt.contains("全部知识库"))
        assertTrue("应指示主动调用检索工具", prompt.contains("knowledge_base__search"))
        assertTrue("应含否定约束（无关问题不要查）", prompt.contains("不要调用知识库工具"))
    }

    /**
     * guardrail R-2（BR-testing-014）：**生产实际注入的**能力声明必须把 grounding 语义钉住。
     *
     * 背景：ADR-050 后生产注入的是 [RagContextBuilder.buildToolGuidance]（不再是
     * `SYSTEM_PROMPT`）；若只让测试钉住已无注入点的 `SYSTEM_PROMPT`，这些语义可被静默删除
     * 而全量测试仍绿（BR-testing-014 禁止的行为契约文案无钉子）。
     * 本用例逐条承接原 `SYSTEM_PROMPT` 四条约束中与"资料已提供时"相关的三条：
     * 优先使用检索结果 / `[来源N]` 引用且不引用未提供编号 / 检索无果时诚实降级话术。
     */
    @Test
    fun `production tool guidance pins grounding semantics`() {
        val prompt = ConversationViewModel.buildRagPromptFor(RagTarget.AllLibraries, ragTopK = 3)!!
        assertTrue("应要求优先使用检索结果而非自身猜测", prompt.contains("优先使用检索结果"))
        assertTrue("应规定 [来源N] 引用格式", prompt.contains("[来源N]"))
        assertTrue("应禁止捏造来源与引用未提供的编号", prompt.contains("不捏造来源") && prompt.contains("不引用未提供的编号"))
        assertTrue("应保留无果时的诚实降级话术", prompt.contains("知识库中未找到相关内容，以下回答基于模型自身知识"))
    }

    @Test
    fun `buildRagPromptFor carries specific library scope`() {
        val prompt = ConversationViewModel.buildRagPromptFor(RagTarget.SpecificLibrary(7L), ragTopK = 5)
        assertNotNull(prompt)
        assertTrue("指定库模式应携带 kbId", prompt!!.contains("knowledgeBaseId=7"))
        assertFalse("不应描述为全库", prompt.contains("全部知识库"))
    }

    @Test
    fun `buildRagPromptFor is stable across calls`() {
        // 纯函数 + 只随配置变化 → 前缀缓存友好（ADR-050 决策依据之一）
        val first = ConversationViewModel.buildRagPromptFor(RagTarget.AllLibraries, ragTopK = 3)
        val second = ConversationViewModel.buildRagPromptFor(RagTarget.AllLibraries, ragTopK = 3)
        assertEquals("同一配置下能力声明必须逐字节稳定", first, second)
    }

    // ==================== 2. 集成：请求体绝不含知识库正文 ====================

    @Test
    fun `request never carries kb content on any message`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 3 条与 query embedding 完全一致的片段（修复前 similarity=1.0，必注入）
        repeat(3) { i ->
            kbRepo.addChunk(KnowledgeChunk(
                title = "私密日记$i.md#1",
                content = "不应进入请求体的知识库正文$i",
                embedding = FloatArray(384) { 0.5f },
                knowledgeBaseId = 0L
            ))
        }
        val provider = RecordingProvider()
        val vm = ConversationViewModel(
            repo, provider, FixedVectorEmbedder(), kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        listOf(
            "你好",
            "帮我看看这个怎么样",     // 真机语料实测 0.682 越阈（无检索意图）
            "帮我总结一下这个",
            "你觉得人生的意义是什么",
            "查询知识库里的资料"       // 明确有检索意图
        ).forEach { text ->
            vm.sendMessage(text)
            advanceUntilIdle()
        }

        assertEquals("5 条消息应产生 5 次请求", 5, provider.receivedRagContexts.size)
        assertTrue(
            "ADR-050：ragContext 必须恒为 null",
            provider.receivedRagContexts.all { it == null }
        )
        assertTrue(
            "ADR-050：请求历史不得出现任何知识库正文",
            provider.receivedMessages.flatten().none { it.content.contains("不应进入请求体的知识库正文") }
        )
    }

    @Test
    fun `system prompt stays byte identical across turns for same config`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val kbRepo = KnowledgeBaseRepository(boxStore)
        kbRepo.addChunk(KnowledgeChunk(
            title = "日记.md#1", content = "正文",
            embedding = FloatArray(384) { 0.5f }, knowledgeBaseId = 0L
        ))
        val provider = RecordingProvider()
        val vm = ConversationViewModel(
            repo, provider, FixedVectorEmbedder(), kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("第一条")
        advanceUntilIdle()
        vm.sendMessage("第二条完全不同的内容，用于验证提示词稳定性")
        advanceUntilIdle()

        val prompts = provider.receivedSystemPrompts
        assertEquals("两次请求都应带 systemPrompt", 2, prompts.size)
        assertEquals(
            "同一配置下 systemPrompt 必须逐字节一致（前缀缓存友好）",
            prompts[0], prompts[1]
        )
    }

    @Test
    fun `kb tools are exposed when capability is declared`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val executor = RecordingSkillExecutor()
        val vm = ConversationViewModel(
            repo, RecordingProvider(), FixedVectorEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher,
            skillExecutor = executor
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        val toolNames = executor.receivedTools.single().map { it.function.name }
        assertTrue("知识库开启时应暴露 search 工具", toolNames.contains("knowledge_base__search"))
        assertTrue("知识库开启时应暴露 list_documents 工具", toolNames.contains("knowledge_base__list_documents"))
        assertTrue("知识库开启时应暴露 get_document_content 工具", toolNames.contains("knowledge_base__get_document_content"))
        // guardrail G-5：声明与工具必须同源**同时在场**（工具回路分支的 systemPrompt 取自 executor）
        assertTrue(
            "声明与工具必须同时在场",
            executor.receivedSystemPrompts.single()!!.contains("【知识库能力】")
        )
    }

    @Test
    fun `kb tools are not exposed when capability is off`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = RecordingProvider()
        val executor = RecordingSkillExecutor()
        val vm = ConversationViewModel(
            repo, provider, FixedVectorEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher,
            skillExecutor = executor
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        // 关闭知识库后工具列表为空 → 走 executePlainStream 分支（不进入工具回路），
        // 故用 flatten 断言"任何一次工具回路都不含 knowledge_base__*"，两种分支均覆盖。
        val toolNames = executor.receivedTools.flatten().map { it.function.name }
        assertTrue(
            "关闭知识库 → 不应暴露任何 knowledge_base__* 工具",
            toolNames.none { it.startsWith("knowledge_base__") }
        )
        assertFalse(
            "关闭知识库 → systemPrompt 不应含能力声明",
            provider.receivedSystemPrompts.single()!!.contains("【知识库能力】")
        )
    }

    @Test
    fun `low tier hides kb tools and capability`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = RecordingProvider()
        val executor = RecordingSkillExecutor()
        val vm = ConversationViewModel(
            repo, provider, FixedVectorEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher,
            ragTopK = 0,  // MINIMAL / CHAT_ONLY 档
            skillExecutor = executor
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        // 低端档工具列表为空 → 走 executePlainStream 分支；flatten 兼容两种分支。
        val toolNames = executor.receivedTools.flatten().map { it.function.name }
        assertTrue(
            "低端档 → 不应暴露 knowledge_base__* 工具",
            toolNames.none { it.startsWith("knowledge_base__") }
        )
        assertFalse(
            "低端档 → systemPrompt 不应含能力声明",
            provider.receivedSystemPrompts.single()!!.contains("【知识库能力】")
        )
    }

    // ==================== 3. 工具指示文案映射（撤除自动注入后的唯一可见通道） ====================

    @Test
    fun `knowledge base tool names map to a user readable indicator`() = runTest(mainDispatcher) {
        // guardrail G-9：撤除自动注入后，"正在查资料"的可见性完全依赖工具调用指示；
        // 默认的 substringAfterLast("__") 会显示泛化的 search/list_documents，用户无法分辨。
        val expected = "查知识库"
        assertEquals(
            expected,
            ConversationViewModel.describeActiveTool("knowledge_base__search")
        )
        assertEquals(
            expected,
            ConversationViewModel.describeActiveTool("knowledge_base__list_documents")
        )
        assertEquals(
            expected,
            ConversationViewModel.describeActiveTool("knowledge_base__get_document_content")
        )
        // 其它工具保持既有展示约定（去命名空间前缀）
        assertEquals("search", ConversationViewModel.describeActiveTool("web_search__search"))
        assertEquals("webSearchPro", ConversationViewModel.describeActiveTool("mcp___Web_Search__webSearchPro"))
    }

    // ==================== 测试基础设施 ====================

    /** 固定向量 embedder（与用例插入的 chunk 向量一致，确保"若仍检索就必然命中"）。 */
    private class FixedVectorEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() = Unit
    }

    /**
     * 记录每次 `executeLoop` 收到 tools 的 [SkillExecutor] fake（工具回路分支才能观察到 tools——
     * 无 skillExecutor 时走 `executePlainStream`，tools 不进入 provider 调用）。
     */
    private class RecordingSkillExecutor : SkillExecutor(
        mcpToolProvider = NoOpMcpToolProvider,
        confirmationGate = NoOpConfirmationGate,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        val receivedTools = mutableListOf<List<ToolDefinition>>()
        val receivedSystemPrompts = mutableListOf<String?>()

        override suspend fun executeLoop(
            provider: ChatStreamProvider,
            config: ProviderConfig,
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>,
            mcpServers: List<io.prism.data.McpServerConfig>,
            maxRounds: Int,
            idGenerator: () -> Long,
            skillConfigId: Long?,
            skillName: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?,
            onEvent: (StreamEvent) -> Unit
        ): List<io.prism.ui.model.ChatMessage> {
            receivedTools += tools
            receivedSystemPrompts += systemPrompt
            onEvent(StreamEvent.Delta("回复"))
            onEvent(StreamEvent.Done)
            return messages
        }
    }

    /** 无操作 [McpToolProvider]（仅供 [RecordingSkillExecutor] 父类构造用）。 */
    private object NoOpMcpToolProvider : McpToolProvider {
        override suspend fun listTools(config: io.prism.data.McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(
            config: io.prism.data.McpServerConfig,
            name: String,
            arguments: Map<String, Any?>
        ): String = ""
    }

    /** 无操作 [ToolConfirmationGate]（仅供 [RecordingSkillExecutor] 父类构造用）。 */
    private object NoOpConfirmationGate : ToolConfirmationGate {
        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = true
    }

    /** 记录每次请求的 messages / systemPrompt / ragContext / tools。 */
    private class RecordingProvider : ChatStreamProvider {
        val receivedMessages = mutableListOf<List<io.prism.ui.model.ChatMessage>>()
        val receivedRagContexts = mutableListOf<String?>()
        val receivedSystemPrompts = mutableListOf<String?>()

        override fun streamChat(
            config: ProviderConfig,
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> {
            receivedMessages += messages
            receivedRagContexts += ragContext
            receivedSystemPrompts += systemPrompt
            return flow {
                emit(StreamEvent.Delta("回复"))
                emit(StreamEvent.Done)
            }
        }
    }
}
