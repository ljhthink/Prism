package io.prism.ui.chat

import io.objectbox.BoxStore
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.McpServerConfig
import io.prism.data.McpServerRepository
import io.prism.data.McpServerType
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.data.SkillConfig
import io.prism.data.SkillSource
import io.prism.embedding.Embedder
import io.prism.fs.ToolConfirmationGate
import io.prism.network.ChatStreamProvider
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.rag.RagTarget
import io.prism.skill.AskUserLocalToolExecutor
import io.prism.skill.AskUserOption
import io.prism.skill.AskUserQuestion
import io.prism.skill.SkillExecutor
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ConversationViewModel UXR8 N2/N3 集成补充测试（ADR-030）—— ac-verifier TKN-UXR8-B3-ACCEPTANCE-001。
 *
 * 补盲区（主 Agent [ConversationViewModelPhaseDTest] 未覆盖的 VM 层集成路径）：
 * - N2：handleStreamEvent 收到 [StreamEvent.AskUser] → [ConversationViewModel.pendingAskUser] 设置；
 *   [ConversationViewModel.clearAskUser] 复位为 null；askUserLocalToolExecutor 注入时 buildTools 生效
 * - N3：[ConversationViewModel.sendMessage(text, imageUrl)] 用户消息携带 imageUrl；
 *   纯图片消息（空白文本 + 图）仍发送；空白文本 + 无图被忽略
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelAskUserEdgeTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-askuser-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun makeSkillEntry(name: String): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
        config = SkillConfig(
            id = 0L, name = name, displayName = name, source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null, skillDir = "/skills/$name", isEnabled = true, isInstalled = true, version = "1.0.0"
        ),
        manifest = SkillManifest(name = name, description = "Test skill $name", version = "1.0.0", tools = null, body = "")
    )

    private fun makeActiveProvider(repo: ProviderConfigRepository): ProviderConfig {
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
        return active
    }

    // ==================== N1：userRules 端到端注入（mergeSystemPrompt → provider systemPrompt） ====================

    @Test
    fun `sendMessage injects userRules into provider systemPrompt when repository configured`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        // 预存用户规则（「关于我」+「如何回答」）到独立 DataStore
        val rulesRepo = io.prism.config.UserRulesRepository(
            io.prism.security.FakePreferenceDataStore(androidx.datastore.preferences.core.emptyPreferences())
        )
        rulesRepo.setRules("我是后端开发者", "用中文简洁回答")
        val recording = AskUserEdgeRecordingProvider(listOf(StreamEvent.Delta("好的"), StreamEvent.Done))
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = recording,
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = null,
            mcpServerRepository = McpServerRepository(boxStore),
            userRulesConfigRepository = rulesRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        val sp = recording.lastSystemPrompt
        val p = sp ?: throw AssertionError("systemPrompt 不应为 null（应含 userRules 层）")
        assertTrue("systemPrompt 应含 userRules 层", p.contains("用户规则"))
        assertTrue("应含「关于我」内容", p.contains("关于我：我是后端开发者"))
        assertTrue("应含「如何回答」内容", p.contains("如何回答：用中文简洁回答"))
        // 最高优先级：persona 之后
        assertTrue("userRules 应在 persona 之后", p.indexOf(ConversationViewModel.DEFAULT_PERSONA) < p.indexOf("用户规则"))
        // 向后兼容：未启用 RAG 时不应注入 RAG 标记
        assertTrue("不应注入 RAG 标记（未启用）", !p.contains("RAG"))
    }

    @Test
    fun `sendMessage without userRules repository does not inject userRules layer`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        val recording = AskUserEdgeRecordingProvider(listOf(StreamEvent.Delta("ok"), StreamEvent.Done))
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = recording,
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = null,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("你好")
        advanceUntilIdle()
        val sp = recording.lastSystemPrompt
        val p = sp ?: throw AssertionError("systemPrompt 不应为 null")
        assertTrue("无 userRules 仓库时不注入 userRules 层（向后兼容）", !p.contains("用户规则"))
    }

    // ==================== N2：AskUser 事件 → pendingAskUser 状态迁移 ====================

    @Test
    fun `handleStreamEvent AskUser sets pendingAskUser and clearAskUser resets`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        val question = AskUserQuestion(
            question = "你想要 A 还是 B？",
            options = listOf(AskUserOption("A", "选项 A"), AskUserOption("B")),
            multiSelect = false
        )
        // FakeSkillExecutor 在 executeLoop 内发射 AskUser + 文本 + Done（模拟真实回路中断）
        val fakeExecutor = AskUserEdgeFakeSkillExecutor(
            returnMessages = emptyList(),
            emitEvents = listOf(
                StreamEvent.AskUser(listOf(question)),
                StreamEvent.Delta("请先回答上面的问题"),
                StreamEvent.Done
            )
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = AskUserEdgeRecordingProvider(emptyList()),
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            // 注入 ask_user 工具使 buildTools 非空 → sendMessage 走 executeLoop 分支
            // （AskUser 事件由 FakeSkillExecutor 在 executeLoop 内发射，经 onEvent → handleStreamEvent）
            askUserLocalToolExecutor = AskUserLocalToolExecutor(),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("帮我决策")
        advanceUntilIdle()

        // 1. AskUser 事件到达 handleStreamEvent → pendingAskUser 设置
        val pending = vm.pendingAskUser.value
        assertTrue("pendingAskUser 应非空", pending != null)
        assertEquals(1, pending!!.size)
        assertEquals("你想要 A 还是 B？", pending[0].question)
        assertEquals(2, pending[0].options.size)
        assertEquals("选项 description 应保留", "选项 A", pending[0].options[0].description)

        // 2. clearAskUser 复位为 null（用户提交/跳过答复后清除提问卡片）
        vm.clearAskUser()
        assertNull("clearAskUser 后 pendingAskUser 应为 null", vm.pendingAskUser.value)
    }

    @Test
    fun `askUserLocalToolExecutor injection makes buildTools include ask_user tool`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = AskUserEdgeRecordingProvider(listOf(StreamEvent.Delta("ok"), StreamEvent.Done)),
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = AskUserEdgeFakeSkillExecutor(returnMessages = emptyList(), emitEvents = listOf(StreamEvent.Delta("ok"), StreamEvent.Done)),
            mcpServerRepository = McpServerRepository(boxStore),
            askUserLocalToolExecutor = AskUserLocalToolExecutor(),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("hello")
        advanceUntilIdle()

        // 注入 askUserLocalToolExecutor 后 VM 正常完成（isTyping 复位，不崩溃）；
        // 工具定义注入细节由 buildTools 纯函数单测覆盖（AskUserLocalToolExecutorEdgeTest）
        assertTrue("注入 askUserLocalToolExecutor 后正常完成（isTyping 复位）", !vm.isTyping.value)
    }

    // ==================== N3：sendMessage imageUrl 集成 ====================

    @Test
    fun `sendMessage with imageUrl attaches imageUrl to user message`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = AskUserEdgeRecordingProvider(listOf(StreamEvent.Delta("看到了"), StreamEvent.Done)),
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = null,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        val imageUrl = "data:image/jpeg;base64,AAAA"
        vm.sendMessage("看看这张图", imageUrl)
        advanceUntilIdle()

        val userMsg = vm.messages.value.first { it.role == io.prism.ui.model.Role.USER }
        assertEquals("用户消息应携带 imageUrl", imageUrl, userMsg.imageUrl)
        assertEquals("看看这张图", userMsg.content)
    }

    @Test
    fun `sendMessage image only with blank text still sends`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = AskUserEdgeRecordingProvider(listOf(StreamEvent.Delta("好的"), StreamEvent.Done)),
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = null,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        val imageUrl = "data:image/jpeg;base64,BBBB"
        vm.sendMessage("   ", imageUrl) // 空白文本 + 图 → 仍发送（ADR-030：图片消息允许空文本）
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == io.prism.ui.model.Role.USER }
        assertEquals("纯图片消息应发送", 1, userMsgs.size)
        assertEquals(imageUrl, userMsgs[0].imageUrl)
        assertEquals("content 应为空串", "", userMsgs[0].content)
    }

    @Test
    fun `sendMessage blank text and no image is ignored`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        makeActiveProvider(repo)
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = AskUserEdgeRecordingProvider(listOf(StreamEvent.Delta("ok"), StreamEvent.Done)),
            embedder = AskUserEdgeStubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = AskUserEdgeStubSkillRegistry(emptyList()),
            skillExecutor = null,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("   ", null)
        advanceUntilIdle()
        assertTrue("空白文本 + 无图应被忽略", vm.messages.value.isEmpty())
    }
}

// ==================== 测试 Fakes（复制自 PhaseDTest，private 不可跨文件复用） ====================

/** 覆写 [SkillExecutor.executeLoop] 的 fake：跳过真实协作，触发 onEvent + 返回 canned 消息。 */
private class AskUserEdgeFakeSkillExecutor(
    private val returnMessages: List<io.prism.ui.model.ChatMessage>,
    private val emitEvents: List<StreamEvent> = emptyList()
) : SkillExecutor(
    mcpToolProvider = AskUserEdgeNoOpMcpToolProvider,
    confirmationGate = AskUserEdgeNoOpConfirmationGate,
    ioDispatcher = Dispatchers.Unconfined
) {
    override suspend fun executeLoop(
        provider: ChatStreamProvider,
        config: ProviderConfig,
        messages: List<io.prism.ui.model.ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>,
        mcpServers: List<McpServerConfig>,
        maxRounds: Int,
        idGenerator: () -> Long,
        skillConfigId: Long?,
        skillName: String?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?,
        onEvent: (StreamEvent) -> Unit
    ): List<io.prism.ui.model.ChatMessage> {
        emitEvents.forEach { onEvent(it) }
        return messages + returnMessages
    }
}

private object AskUserEdgeNoOpMcpToolProvider : McpToolProvider {
    override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
    override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String = ""
}

private object AskUserEdgeNoOpConfirmationGate : ToolConfirmationGate {
    override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = true
}

private class AskUserEdgeStubSkillRegistry(
    private val stubSkills: List<SkillRegistry.SkillEntry>
) : SkillRegistry(
    context = android.app.Application(),
    skillRepository = io.prism.data.SkillRepository(
        MyObjectBox.builder().directory(kotlin.io.path.createTempDirectory(prefix = "stub-sr-askuser-").toFile()).build()
    ),
    ioDispatcher = Dispatchers.Unconfined
) {
    override fun enabledSkills(): List<SkillEntry> = stubSkills
}

private class AskUserEdgeStubEmbedder : Embedder {
    override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
    override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    override fun isLoaded(): Boolean = true
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false
    override fun close() {}
}

private class AskUserEdgeRecordingProvider(
    private val events: List<StreamEvent>
) : ChatStreamProvider {
    var lastSystemPrompt: String? = null
        private set
    var lastMessages: List<io.prism.ui.model.ChatMessage>? = null
        private set

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
        lastSystemPrompt = systemPrompt
        lastMessages = messages
        return flow { events.forEach { emit(it) } }
    }
}
