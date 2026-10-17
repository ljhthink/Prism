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
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ConversationViewModel 单元测试 —— 验证发送逻辑与流式接入（US-005 AC-4 / US-006 AC-5 / US-019 RAG）。
 *
 * 覆盖：
 * - 发送消息追加用户消息 + 流式 AI 回复（注入 fake [ChatStreamProvider]）
 * - 空白输入被忽略
 * - 连续发送消息 id 递增
 * - 无激活 Provider 时追加提示消息而非崩溃
 * - 流式增量 token 被正确追加到 AI 消息
 * - US-019：RAG 关闭时不调用 embedder；RAG 开启但 embedder 抛异常时降级为普通对话
 *
 * 依赖注入：本测试通过 [FakeChatStreamProvider] 注入确定性的 [StreamEvent] 序列，
 * 避免依赖真实网络 / MockEngine（MockEngine 不支持 Ktor SSE `SSECapability`，见 ADR-004 4.7）。
 * 默认 RAG 关闭（[RagTarget.Off]），既有测试保持原意不被 RAG 逻辑影响。
 *
 * [ConversationViewModel.sendMessage] 在 [androidx.lifecycle.viewModelScope] 中订阅流式请求，
 * 故须设置 Main 为测试调度器并在 [runTest] 中推进虚拟时钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    /**
     * 构造注入 fake Provider 的 VM（返回流式 "该模型已回复"）。
     *
     * **US-019**：默认 RAG 关闭（[RagTarget.Off]），避免触发 embedder/search 调用，
     * 保证既有测试保持原意。RAG 专属测试单独构造 VM。
     */
    private fun buildVm(active: ProviderConfig?): ConversationViewModel {
        val repo = ProviderConfigRepository(boxStore)
        active?.let { repo.save(it); repo.setActive(repo.findByName(it.name)!!.id) }

        val provider = FakeChatStreamProvider(
            listOf(
                StreamEvent.Delta("该模型"),
                StreamEvent.Delta("已回复"),
                StreamEvent.Done
            )
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = DefaultStubEmbedder,
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore)
        )
        vm.setRagTarget(RagTarget.Off)
        return vm
    }

    @Test
    fun `sendMessage appends user and streamed assistant messages`() = runTest(mainDispatcher) {
        val vm = buildVm(
            active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        )

        vm.sendMessage("你好")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("你好", messages[0].content)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertEquals("该模型已回复", messages[1].content)
        assertTrue("流结束后 isTyping 应为 false", !vm.isTyping.value)
    }

    @Test
    fun `sendMessage trims whitespace`() = runTest(mainDispatcher) {
        val vm = buildVm(null)

        vm.sendMessage("  你好  ")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals("你好", messages[0].content)
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest(mainDispatcher) {
        val vm = buildVm(null)

        vm.sendMessage("   ")
        vm.sendMessage("")
        advanceUntilIdle()

        assertEquals(0, vm.messages.value.size)
    }

    @Test
    fun `consecutive sends assign increasing ids`() = runTest(mainDispatcher) {
        val vm = buildVm(null)

        vm.sendMessage("a")
        vm.sendMessage("b")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals("无激活 Provider 时每条消息追加一条提示", 4, messages.size)
        assertEquals(listOf(0L, 1L, 2L, 3L), messages.map { it.id })
    }

    @Test
    fun `sendMessage without active provider appends hint not crash`() = runTest(mainDispatcher) {
        val vm = buildVm(null)

        vm.sendMessage("你好")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertTrue("应包含未配置 Provider 提示", messages[1].content.contains("Provider"))
        assertTrue("流结束后 isTyping 应为 false", !vm.isTyping.value)
    }

    @Test
    fun `activeProvider reflects repository active state`() = runTest(mainDispatcher) {
        val vm = buildVm(
            active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        )

        assertEquals("OpenAI", vm.activeProvider.value?.name)
    }

    @Test
    fun `setActiveProvider switches provider preserving history and routing new messages`() = runTest(mainDispatcher) {
        // US-007 核心验收：切换 Provider 后保留对话历史，新消息走新 Provider。
        val repo = ProviderConfigRepository(boxStore)
        val p1 = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        val p2 = ProviderConfig(name = "Anthropic", baseUrl = "https://api.anthropic.com", apiKeyRef = "anthropic", models = listOf("claude"))
        val id1 = repo.save(p1)
        repo.setActive(id1)
        val id2 = repo.save(p2)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("hi"), StreamEvent.Done))
        val vm = ConversationViewModel(repo, provider, DefaultStubEmbedder, KnowledgeBaseRepository(boxStore)).apply {
            setRagTarget(RagTarget.Off)
        }

        // activeProvider/providers 用 WhileSubscribed 共享；测试需显式收集以激活共享协程，
        // 否则直接读 .value 不会拾取上游新值（生产环境由 collectAsState() 持续订阅，无此问题）。
        val activeJob = launch { vm.activeProvider.collect {} }
        val providersJob = launch { vm.providers.collect {} }

        // 初始：激活 OpenAI，providers 暴露两个
        assertEquals("OpenAI", vm.activeProvider.value?.name)
        assertEquals(2, vm.providers.value.size)

        // 首次发送走当前激活 Provider（OpenAI）
        vm.sendMessage("hello")
        advanceUntilIdle()
        assertEquals("OpenAI", provider.receivedConfigs.first().name)
        val historyBeforeSwitch = vm.messages.value.size

        // 切换激活到 Anthropic（经仓库单激活事务）
        vm.setActiveProvider(id2)
        advanceUntilIdle()
        assertEquals("Anthropic", vm.activeProvider.value?.name)
        assertEquals("切换 Provider 后应保留对话历史", historyBeforeSwitch, vm.messages.value.size)

        // 新消息走新 Provider（Anthropic）
        vm.sendMessage("world")
        advanceUntilIdle()
        assertEquals("切换后新消息应走新 Provider", "Anthropic", provider.receivedConfigs.last().name)

        activeJob.cancel()
        providersJob.cancel()
    }

    @Test
    fun `stream error appends warning and stops typing`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = FakeChatStreamProvider(listOf(StreamEvent.Error("401 未授权")))
        val vm = ConversationViewModel(repo, provider, DefaultStubEmbedder, KnowledgeBaseRepository(boxStore)).apply {
            setRagTarget(RagTarget.Off)
        }

        vm.sendMessage("你好")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertTrue("应包含错误提示", messages[1].content.contains("401"))
        assertTrue("错误后 isTyping 应为 false", !vm.isTyping.value)
    }

    @Test
    fun `request history excludes empty placeholder message`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("hi"), StreamEvent.Done))
        val vm = ConversationViewModel(repo, provider, DefaultStubEmbedder, KnowledgeBaseRepository(boxStore)).apply {
            setRagTarget(RagTarget.Off)
        }

        vm.sendMessage("hello")
        advanceUntilIdle()

        val sent = provider.receivedMessages.single { it.isNotEmpty() }
        assertEquals("请求历史应只含用户消息（排除空 AI 占位）", 1, sent.size)
        assertEquals(Role.USER, sent[0].role)
        assertEquals("hello", sent[0].content)
        assertTrue("不应包含空 content 消息", sent.none { it.content.isEmpty() })
    }

    @Test
    fun `request history excludes stale empty ai message from previous zero-delta round`() = runTest(mainDispatcher) {
        // guardrail 发现 1：上一轮因服务端零增量（仅 [DONE]）结束会残留空 content 的 AI 消息，
        // 下一轮发送时该空消息必须被排除，否则严格网关会拒绝 400。
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // 同一 VM 多轮：第一轮仅 [DONE]（残留空 AI 消息），第二轮正常回复并记录请求历史
        val provider = MultiRoundRecordingProvider(
            listOf(
                listOf(StreamEvent.Done),                     // 第一轮：零增量结束
                listOf(StreamEvent.Delta("ok"), StreamEvent.Done) // 第二轮：正常回复
            )
        )
        val vm = ConversationViewModel(repo, provider, DefaultStubEmbedder, KnowledgeBaseRepository(boxStore)).apply {
            setRagTarget(RagTarget.Off)
        }
        vm.sendMessage("first")
        advanceUntilIdle()
        vm.sendMessage("second")
        advanceUntilIdle()

        val secondRound = provider.receivedMessages[1]
        assertTrue("第二轮请求历史不应包含任何空 content 消息", secondRound.none { it.content.isEmpty() })
        assertEquals("应包含上轮用户消息 + 本轮用户消息（上轮空 AI 消息被过滤）", 2, secondRound.size)
        assertEquals("第一条应为上一轮用户消息", "first", secondRound[0].content)
        assertEquals("第二条应为本轮用户消息", "second", secondRound[1].content)
    }

    // ==================== US-019 RAG 集成测试 ====================

    /**
     * US-019 AC：RAG 关闭时不调用 embedder，systemPrompt/ragContext 均为 null（普通对话）。
     */
    @Test
    fun `rag off does not inject system prompt or rag context`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("ok"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            StubEmbedderImpl(throwOnEmbed = true),  // 即使 embed 抛异常，RAG 关闭也不会调用
            KnowledgeBaseRepository(boxStore)
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        // ADR-018：RAG 关闭时 systemPrompt 应为默认 persona（不再为 null）
        assertEquals("RAG 关闭时应注入默认 persona", ConversationViewModel.DEFAULT_PERSONA, provider.receivedSystemPrompts.single())
        assertTrue("RAG 关闭时不应注入 ragContext", provider.receivedRagContexts.single() == null)
        assertEquals("ok", vm.messages.value[1].content)
    }

    /**
     * ADR-050（TASK-20260912-002）：embedder 异常不再影响对话——知识库不再被自动检索。
     *
     * 原用例（US-019 AC「embed 失败 → appendDelta 提示降级」）验证的是已撤除的自动注入范式中的
     * 「三级降级」分支。ADR-050 后客户端不做自动 embed，故 embedder 抛异常也不影响本轮请求，
     * 更不会向用户插入"知识库检索失败"提示（消除无意义噪音）。
     * 本用例保留"注入 StubEmbedderImpl(throwOnEmbed = true)"以证伪"仍存在自动 embed 调用"。
     */
    @Test
    fun `rag on with embedder failure no longer degrades nor notifies user`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("正常回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            StubEmbedderImpl(throwOnEmbed = true),  // 若被调用即抛异常（ADR-050 下不应被调用）
            KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询一下知识库内容")
        advanceUntilIdle()

        // 能力声明仍在（systemPrompt = persona + 能力声明），但无正文注入
        val sp = provider.receivedSystemPrompts.single()
        assertTrue("systemPrompt 应以默认 persona 开头", sp!!.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("systemPrompt 应含知识库能力声明", sp.contains("【知识库能力】"))
        assertTrue("ragContext 恒为 null", provider.receivedRagContexts.single() == null)
        // 不再有"知识库检索失败"降级提示（不再自动检索）
        val content = vm.messages.value[1].content
        assertFalse("不应再出现自动检索降级提示", content.contains("知识库检索失败"))
        assertEquals("正常回复", content)
        assertTrue("不应附自动填充的引用来源", vm.messages.value[1].sources.isEmpty())
    }

    /**
     * ADR-050：知识库为空时同样不自动检索、不注入——能力声明仍注入（模型可主动调用工具查询，
     * 由工具返回"知识库暂无资料/未找到相关片段"）。
     */
    @Test
    fun `rag on with empty knowledgebase still declares capability only`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("空库回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            DefaultStubEmbedder,  // embed 正常
            KnowledgeBaseRepository(boxStore),  // 空库
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询一下知识库内容")
        advanceUntilIdle()

        val sp = provider.receivedSystemPrompts.single()
        assertTrue("空库时仍声明能力（模型可主动调用工具）", sp!!.contains("【知识库能力】"))
        assertTrue("空库时 ragContext 为 null", provider.receivedRagContexts.single() == null)
        assertEquals("空库回复", vm.messages.value[1].content)
        assertTrue("空库时不应附引用来源", vm.messages.value[1].sources.isEmpty())
    }

    /**
     * US-019 AC：RAG 模式切换器三态正确切换 + 默认值为 Off（v1 批次18 知识库 opt-in）。
     */
    @Test
    fun `setRagTarget switches between three states`() = runTest(mainDispatcher) {
        // 直接构造 VM，不通过 buildVm（buildVm 会切到 Off），验证默认值
        val repo = ProviderConfigRepository(boxStore)
        val vm = ConversationViewModel(
            repo,
            FakeChatStreamProvider(listOf(StreamEvent.Done)),
            DefaultStubEmbedder,
            KnowledgeBaseRepository(boxStore)
        )
        assertEquals("默认应为 Off（v1 批次18：知识库 opt-in，未开启不注入）", RagTarget.Off, vm.ragTarget.value)

        vm.setRagTarget(RagTarget.Off)
        assertEquals(RagTarget.Off, vm.ragTarget.value)
        vm.setRagTarget(RagTarget.SpecificLibrary(42L))
        assertEquals(RagTarget.SpecificLibrary(42L), vm.ragTarget.value)
        vm.setRagTarget(RagTarget.AllLibraries)
        assertEquals(RagTarget.AllLibraries, vm.ragTarget.value)
    }

    // ==================== UXR8 Bug1（ADR-028）：RagTarget 持久化端到端 ====================

    /**
     * UXR8 Bug1 AC（ac-verifier 补强项 1）：用户关闭 RAG 后点「新对话」，
     * RagTarget 保持 Off 且持久化层也为 Off（不再被系统重置为全库）。
     */
    @Test
    fun `rag target off survives startNewConversation with repository injected`() = runTest(mainDispatcher) {
        val dataStore = io.prism.security.FakePreferenceDataStore(
            androidx.datastore.preferences.core.emptyPreferences()
        )
        val ragRepo = io.prism.config.RagTargetConfigRepository(dataStore)
        val vm = ConversationViewModel(
            ProviderConfigRepository(boxStore),
            FakeChatStreamProvider(listOf(StreamEvent.Done)),
            DefaultStubEmbedder,
            KnowledgeBaseRepository(boxStore),
            ragTargetConfigRepository = ragRepo
        )

        vm.setRagTarget(RagTarget.Off)
        vm.startNewConversation()
        advanceUntilIdle()

        assertEquals("新对话后内存态应保持 Off（UXR8 Bug1 核心）", RagTarget.Off, vm.ragTarget.value)
        assertEquals("持久化层应保持 Off", RagTarget.Off, ragRepo.getRagTarget())
    }

    /**
     * UXR8 Bug1 AC（ac-verifier 补强项 1）：init 从 DataStore 恢复上次持久化的 RagTarget。
     * 模拟「用户关闭 RAG → 杀进程重启 → 打开 App」场景。
     */
    @Test
    fun `init restores persisted rag target from repository`() = runTest(mainDispatcher) {
        val dataStore = io.prism.security.FakePreferenceDataStore(
            androidx.datastore.preferences.core.emptyPreferences()
        )
        val ragRepo = io.prism.config.RagTargetConfigRepository(dataStore)
        // 模拟上一进程持久化了 Off
        ragRepo.setRagTarget(RagTarget.Off)

        val vm = ConversationViewModel(
            ProviderConfigRepository(boxStore),
            FakeChatStreamProvider(listOf(StreamEvent.Done)),
            DefaultStubEmbedder,
            KnowledgeBaseRepository(boxStore),
            ragTargetConfigRepository = ragRepo
        )
        advanceUntilIdle()

        assertEquals("init 应恢复持久化的 Off（重启后不重置为全库）", RagTarget.Off, vm.ragTarget.value)
    }

    /**
     * UXR8 guardrail MED-2（ADR-028）：持久化的指定库已被删除时，
     * init 恢复降级为 Off（而非回退 AllLibraries —— 避免突然注入全库的意图/隐私意外）。
     */
    @Test
    fun `init degrades to Off when persisted specific library was deleted`() = runTest(mainDispatcher) {
        val dataStore = io.prism.security.FakePreferenceDataStore(
            androidx.datastore.preferences.core.emptyPreferences()
        )
        val ragRepo = io.prism.config.RagTargetConfigRepository(dataStore)
        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 模拟上一进程持久化了 SpecificLibrary(999999)，但该库在当前数据中不存在
        ragRepo.setRagTarget(RagTarget.SpecificLibrary(999_999L))

        val vm = ConversationViewModel(
            ProviderConfigRepository(boxStore),
            FakeChatStreamProvider(listOf(StreamEvent.Done)),
            DefaultStubEmbedder,
            kbRepo,
            ragTargetConfigRepository = ragRepo
        )
        advanceUntilIdle()

        assertEquals("库已删除时应降级 Off", RagTarget.Off, vm.ragTarget.value)
    }

    /**
     * UXR8 guardrail MED-2 对照组：持久化的指定库仍存在时正常恢复 SpecificLibrary。
     */
    @Test
    fun `init restores persisted specific library when it still exists`() = runTest(mainDispatcher) {
        val dataStore = io.prism.security.FakePreferenceDataStore(
            androidx.datastore.preferences.core.emptyPreferences()
        )
        val ragRepo = io.prism.config.RagTargetConfigRepository(dataStore)
        val kbRepo = KnowledgeBaseRepository(boxStore)
        val kbId = kbRepo.save(io.prism.data.KnowledgeBase(name = "med2-existing"))
        ragRepo.setRagTarget(RagTarget.SpecificLibrary(kbId))

        val vm = ConversationViewModel(
            ProviderConfigRepository(boxStore),
            FakeChatStreamProvider(listOf(StreamEvent.Done)),
            DefaultStubEmbedder,
            kbRepo,
            ragTargetConfigRepository = ragRepo
        )
        advanceUntilIdle()

        assertEquals("库仍存在时应恢复 SpecificLibrary", RagTarget.SpecificLibrary(kbId), vm.ragTarget.value)
    }

    // ==================== G-05 修复：正向快乐路径 + 阈值过滤 + SpecificLibrary 校验 ====================
    // 以下测试补齐 guardrail TKN-US019-RAG-GUARDRAIL-001 G-05/G-07 发现的覆盖缺口。

    /**
     * ADR-050 核心回归（原 US-019 AC-3 / G-05）：**即便知识库中存在与 query 完全匹配的片段
     * （similarity=1.0），客户端也不再自动注入正文**。
     *
     * 构造方式保持不变（默认库插入 embedding 与 StubEmbedder 一致的 2 条 chunk，修复前必注入），
     * 断言改为新契约：ragContext 恒 null、sources 不由客户端预填、systemPrompt 只含能力声明。
     */
    @Test
    fun `rag on with matching chunks no longer auto injects content`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // 构造含 chunk 的 KB（默认库 kbId=0L，AllLibraries 模式可检索）
        val kbRepo = KnowledgeBaseRepository(boxStore)
        kbRepo.addChunk(KnowledgeChunk(
            title = "文档A.pdf#1",
            content = "这是文档A的第一个片段，含关键信息。",
            embedding = FloatArray(384) { 0.5f },  // 与 DefaultStubEmbedder 返回值一致（修复前 similarity=1.0）
            knowledgeBaseId = 0L
        ))
        kbRepo.addChunk(KnowledgeChunk(
            title = "文档B.md#3",
            content = "文档B的第三个片段，补充说明。",
            embedding = FloatArray(384) { 0.5f },
            knowledgeBaseId = 0L
        ))

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("基于知识库回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            DefaultStubEmbedder,  // 返回 FloatArray(384){0.5f}，与 chunk embedding 完全一致
            kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询关键信息")
        advanceUntilIdle()

        // 1. systemPrompt = 默认 persona + 知识库能力声明（**无 RAG grounding rules、无正文**）
        val sysPrompt = provider.receivedSystemPrompts.single()
        assertNotNull("systemPrompt 不应为 null", sysPrompt)
        val sp = sysPrompt!!
        assertTrue("systemPrompt 应以默认 persona 开头", sp.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("systemPrompt 应包含知识库能力声明", sp.contains("【知识库能力】"))
        assertFalse("systemPrompt 不应包含【知识库片段】正文块", sp.contains("【知识库片段】"))
        assertFalse("systemPrompt 不应包含文档正文", sp.contains("这是文档A的第一个片段"))

        // 2. ragContext 恒为 null —— 知识库正文只在 LLM 主动调用工具后进入上下文
        assertTrue("ADR-050：ragContext 恒为 null", provider.receivedRagContexts.single() == null)

        // 3. 请求历史中不得出现任何知识库正文
        val sent = provider.receivedMessages.single()
        assertTrue(
            "ADR-050：请求历史不得含知识库正文",
            sent.none { it.content.contains("这是文档A的第一个片段") || it.content.contains("文档B的第三个片段") }
        )

        // 4. 客户端不再预填引用来源（工具路径由 parseKnowledgeBaseCitations* 从工具结果解析）
        val aiMsg = vm.messages.value[1]
        assertTrue("ADR-050：不应由客户端预填引用来源", aiMsg.sources.isEmpty())

        // 5. AI 流式回复正常追加
        assertTrue("应包含 AI 流式回复", aiMsg.content.contains("基于知识库回复"))
    }

    /**
     * ADR-050：similarity 低于任何阈值的无关片段同样不可能被注入（改为断言"任何相似度都不注入"）。
     */
    @Test
    fun `rag on with below threshold results never injects anything`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // chunk embedding 全 0，与 query embedding（全 0.5）正交，similarity = 0
        kbRepo.addChunk(KnowledgeChunk(
            title = "无关文档.txt#1",
            content = "无关内容",
            embedding = FloatArray(384) { 0f },
            knowledgeBaseId = 0L
        ))

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("普通回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            DefaultStubEmbedder,
            kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询")
        advanceUntilIdle()

        assertTrue("ragContext 恒为 null", provider.receivedRagContexts.single() == null)
        assertEquals("普通回复", vm.messages.value[1].content)
        assertTrue("不应附引用来源", vm.messages.value[1].sources.isEmpty())
        assertFalse("无关内容不得进入 systemPrompt",
            provider.receivedSystemPrompts.single()!!.contains("无关内容"))
    }

    /**
     * ADR-050：similarity ∈ [0.5, 0.7) 的"部分相关"片段（旧阈值下会被注入）同样不注入 ——
     * 这是真机污染的实际形态（语料同质时 0.5~0.7 命中率极高）。
     */
    @Test
    fun `rag on with partially relevant chunks is never auto injected`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // chunk = [0.6, √0.64, 0...]，与 query = [1,0,0...] 的 COSINE 相似度 = 0.6（旧阈值 0.5 通过）
        val chunkEmb = FloatArray(384)
        chunkEmb[0] = 0.6f
        chunkEmb[1] = kotlin.math.sqrt(0.64).toFloat()
        kbRepo.addChunk(KnowledgeChunk(
            title = "部分相关文档.txt#1",
            content = "部分相关内容",
            embedding = chunkEmb,
            knowledgeBaseId = 0L
        ))

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("普通回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            DefaultStubEmbedder,
            kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询")
        advanceUntilIdle()

        assertTrue("0.6 相似度（旧阈值下必注入）在 ADR-050 下不再注入",
            provider.receivedRagContexts.single() == null)
        assertFalse("部分相关内容不得进入 systemPrompt",
            provider.receivedSystemPrompts.single()!!.contains("部分相关内容"))
        assertTrue("不应附引用来源", vm.messages.value[1].sources.isEmpty())
    }

    /**
     * M-02 修复验证（guardrail TKN-M7-GUARDRAIL-001，ac-verifier TKN-M7-ACCEPTANCE-001）：
     * MINIMAL/CHAT_ONLY 档 ragTopK=0 时，buildRagPlan 应短路返回 NormalChat，
     * 不调用 embedder.embed()（若 embed 被调用，StubEmbedderImpl(throwOnEmbed=true) 会抛异常
     * 触发 EmbedFailed 降级，AI 消息内容会含 "⚠️" 前缀）。
     */
    /**
     * ADR-050 + M-02：MINIMAL/CHAT_ONLY 档（ragTopK=0）——**低端档不暴露知识库能力**：
     * 既不注入能力声明（systemPrompt 仅默认 persona），也不注入知识库工具，且不调用 embed。
     *
     * （原用例验证"buildRagPlan 短路"，ADR-050 后 buildRagPlan 已删除，语义由能力门控承接。）
     */
    @Test
    fun `ragTopK zero hides kb capability without calling embed`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 插入匹配 chunk（若仍自动检索会命中并注入）
        kbRepo.addChunk(KnowledgeChunk(
            title = "匹配文档.txt#1",
            content = "匹配内容",
            embedding = FloatArray(384) { 0.5f },
            knowledgeBaseId = 0L
        ))

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("普通回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            StubEmbedderImpl(throwOnEmbed = true),  // 若 embed 被调用会抛异常
            kbRepo,
            ioDispatcher = mainDispatcher,
            ragTopK = 0  // MINIMAL/CHAT_ONLY 档
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询")
        advanceUntilIdle()

        assertEquals("低端档不应声明知识库能力，systemPrompt 仅默认 persona",
            ConversationViewModel.DEFAULT_PERSONA, provider.receivedSystemPrompts.single())
        assertTrue("ragContext 恒为 null", provider.receivedRagContexts.single() == null)
        assertEquals("内容应为纯回复（无降级提示）", "普通回复", vm.messages.value[1].content)
        assertTrue("不应附引用来源", vm.messages.value[1].sources.isEmpty())
    }

    /**
     * ADR-050 防御性验证：ragTopK 负值同样不暴露能力（<= 0 条件覆盖负数）。
     */
    @Test
    fun `ragTopK negative also hides kb capability`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("安全回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            StubEmbedderImpl(throwOnEmbed = true),
            KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher,
            ragTopK = -1  // 防御性负值
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询")
        advanceUntilIdle()

        assertEquals("ragTopK 负值内容为纯回复",
            "安全回复", vm.messages.value[1].content)
        assertEquals("ragTopK 负值不应声明知识库能力",
            ConversationViewModel.DEFAULT_PERSONA, provider.receivedSystemPrompts.single())
    }

    /**
     * G-04 修复验证：RagTarget.SpecificLibrary(kbId <= 0) 应抛 IllegalArgumentException。
     */
    @Test
    fun `SpecificLibrary rejects non positive kbId`() {
        assertThrows(IllegalArgumentException::class.java) {
            RagTarget.SpecificLibrary(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RagTarget.SpecificLibrary(-1L)
        }
        // 正向：kbId > 0 应正常构造
        assertEquals(42L, RagTarget.SpecificLibrary(42L).kbId)
    }

    /**
     * ADR-050：SpecificLibrary 模式的**能力声明范围**——声明中携带该库 id（供 LLM 调用工具时
     * 指定 knowledgeBaseId），且不再有任何库内容被自动注入。
     */
    @Test
    fun `rag on with specific library declares scoped capability only`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 自建库 id=1 + 默认库各插入匹配 chunk（修复前 SpecificLibrary 会只注入自建库片段）
        kbRepo.addChunk(KnowledgeChunk(
            title = "自建库文档.pdf#1",
            content = "自建库内容",
            embedding = FloatArray(384) { 0.5f },
            knowledgeBaseId = 1L
        ))
        kbRepo.addChunk(KnowledgeChunk(
            title = "默认库文档.pdf#1",
            content = "默认库内容",
            embedding = FloatArray(384) { 0.5f },
            knowledgeBaseId = 0L
        ))

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("命中自建库"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            DefaultStubEmbedder,
            kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.SpecificLibrary(1L)) }

        vm.sendMessage("查询")
        advanceUntilIdle()

        val sp = provider.receivedSystemPrompts.single()!!
        assertTrue("能力声明应含库范围", sp.contains("knowledgeBaseId=1"))
        assertFalse("不得自动注入任何库正文", sp.contains("自建库内容") || sp.contains("默认库内容"))
        assertTrue("ragContext 恒为 null", provider.receivedRagContexts.single() == null)
        assertTrue("不应由客户端预填引用来源", vm.messages.value[1].sources.isEmpty())
    }

    // ===== ADR-050（2026-09-12）：RAG 自动注入预判用例已随范式撤除一并移除 =====
    // 原 4 个 `needsRagRetrieval` 用例（寒暄黑名单 / 短查询保留 / 前缀不误伤 / 空白）
    // 验证的是"用启发式短语黑名单决定是否自动检索"这一被撤除的范式。
    // 新契约（不自动注入 + 能力声明 + 工具按需检索）由
    // `ConversationViewModelKnowledgeBaseInjectionTest` 覆盖。

    // ===== UXR3 问题 13（ADR-023）：消息编辑重发 =====

    @Test
    fun `editUserMessageAndResend replaces message and truncates subsequent messages`() = runTest(mainDispatcher) {
        val vm = buildVm(
            active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        )
        // 先发送一条消息产生 user + AI 回复
        vm.sendMessage("原始问题")
        advanceUntilIdle()
        val originalUser = vm.messages.value[0]
        assertEquals(Role.USER, originalUser.role)
        assertEquals(2, vm.messages.value.size)

        // 编辑该消息并重新发送
        vm.editUserMessageAndResend(originalUser.id, "编辑后的问题")
        advanceUntilIdle()

        val messages = vm.messages.value
        // 期望：编辑后的 user 消息 + 新 AI 回复（旧 AI 回复已被截断）
        assertEquals("编辑后应剩 user + 新 AI 回复", 2, messages.size)
        assertEquals("编辑后的内容应被替换", "编辑后的问题", messages[0].content)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertEquals("AI 应基于编辑后的问题重新回答", "该模型已回复", messages[1].content)
    }

    @Test
    fun `editUserMessageAndResend ignores blank new text`() = runTest(mainDispatcher) {
        val vm = buildVm(
            active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        )
        vm.sendMessage("原始问题")
        advanceUntilIdle()
        val originalUser = vm.messages.value[0]
        val snapshot = vm.messages.value

        vm.editUserMessageAndResend(originalUser.id, "   ")
        advanceUntilIdle()

        // 空白编辑应被忽略，消息列表不变
        assertEquals(snapshot, vm.messages.value)
    }

    @Test
    fun `editUserMessageAndResend ignores unknown message id`() = runTest(mainDispatcher) {
        val vm = buildVm(
            active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        )
        vm.sendMessage("原始问题")
        advanceUntilIdle()
        val snapshot = vm.messages.value

        vm.editUserMessageAndResend(99999L, "新内容")
        advanceUntilIdle()

        assertEquals("未知 id 应被忽略", snapshot, vm.messages.value)
    }

    @Test
    fun `editUserMessageAndResend ignores non-user message`() = runTest(mainDispatcher) {
        val vm = buildVm(
            active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        )
        vm.sendMessage("原始问题")
        advanceUntilIdle()
        // AI 消息 id 不应被编辑（仅 USER 角色）
        val aiId = vm.messages.value[1].id
        val snapshot = vm.messages.value

        vm.editUserMessageAndResend(aiId, "新内容")
        advanceUntilIdle()

        assertEquals("AI 消息编辑应被忽略", snapshot, vm.messages.value)
    }

    // ===== Bug fix（UXR8-R3，guardrail M-1/M-2）：图片发送队列与编码失败提示 =====

    @Test
    fun `images sent during ai reply are queued not dropped then flushed after reply`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
        val provider = GatedStreamProvider()
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder, KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 第一轮进行中（isTyping=true）时选两张图
        vm.sendMessage("第一问")
        provider.awaitFirstRoundStarted()  // 确保第一轮已挂起（isTyping=true）
        val imgA = "data:image/jpeg;base64,AAAA"
        val imgB = "data:image/jpeg;base64,BBBB"
        vm.sendMessage("图A", imgA)
        vm.sendMessage("图B", imgB)

        // 放行第一轮 → 完成 → finally 逐条 flush 图A、图B（每轮一条，串行）
        provider.releaseFirstRound()
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        assertTrue("图A 不应被丢弃", userMsgs.any { it.imageUrl?.endsWith("AAAA") == true })
        assertTrue("图B 不应被丢弃", userMsgs.any { it.imageUrl?.endsWith("BBBB") == true })
        // 无图纯文本的"图A/图B"文本内容（随图发送）
        assertTrue("图A 文本内容应随图发送", userMsgs.any { it.content == "图A" && it.imageUrl != null })
        assertTrue("图B 文本内容应随图发送", userMsgs.any { it.content == "图B" && it.imageUrl != null })
    }

    @Test
    fun `notifyEncodingFailure during ai reply is queued then shown after reply`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
        val provider = GatedStreamProvider()
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder, KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("第一问")
        provider.awaitFirstRoundStarted()
        vm.notifyEncodingFailure()  // isTyping=true → 暂存队列，不应被守卫丢弃

        provider.releaseFirstRound()
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        assertTrue("编码失败提示应最终显示", userMsgs.any { it.content.contains("图片编码失败") })
    }

    @Test
    fun `notifyEncodingFailure when not typing sends immediately`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
        val vm = ConversationViewModel(
            repo, RecordingChatStreamProvider(listOf(StreamEvent.Delta("ok"), StreamEvent.Done)),
            DefaultStubEmbedder, KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.notifyEncodingFailure()  // isTyping=false → 立即发送
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        assertTrue("非回复期间提示应立即显示", userMsgs.any { it.content.contains("图片编码失败") })
    }
}

/** 确定性 [ChatStreamProvider] fake —— 按给定序列发射事件，供 VM 测试注入。 */
private class FakeChatStreamProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>?,
        toolChoice: ToolChoice?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?
    ): Flow<StreamEvent> = flow { events.forEach { emit(it) } }
}

/**
 * 门控流式 fake（UXR8-R3 M-1/M-2 测试）—— 第一轮挂起直到 [releaseFirstRound] 放行，
 * 用于模拟「AI 回复进行中（isTyping=true）」窗口；后续轮次立即完成。
 */
private class GatedStreamProvider : ChatStreamProvider {
    private val firstRoundStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val releaseGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var round = 0

    suspend fun awaitFirstRoundStarted() = firstRoundStarted.await()

    fun releaseFirstRound() {
        releaseGate.complete(Unit)
    }

    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>?,
        toolChoice: ToolChoice?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?
    ): Flow<StreamEvent> = flow {
        val current = round++
        if (current == 0) {
            // 第一轮：发射后挂起，保持 isTyping=true
            firstRoundStarted.complete(Unit)
            emit(StreamEvent.Delta("回复中"))
            releaseGate.await()
            emit(StreamEvent.Done)
        } else {
            emit(StreamEvent.Delta("后续"))
            emit(StreamEvent.Done)
        }
    }
}

/** 记录每次传入 [messages] 与 [config] 的 fake，用于断言请求历史与目标 Provider（CR-02 / US-007 / US-019）。 */
private class RecordingChatStreamProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
    val receivedMessages = mutableListOf<List<ChatMessage>>()
    val receivedConfigs = mutableListOf<ProviderConfig>()
    val receivedSystemPrompts = mutableListOf<String?>()
    val receivedRagContexts = mutableListOf<String?>()

    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>?,
        toolChoice: ToolChoice?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?
    ): Flow<StreamEvent> {
        receivedConfigs += config
        receivedMessages += messages
        receivedSystemPrompts += systemPrompt
        receivedRagContexts += ragContext
        return flow { events.forEach { emit(it) } }
    }
}

/** 多轮 fake：按调用序依次返回不同事件序列，并记录每次请求历史（guardrail 发现 1 多轮场景）。 */
private class MultiRoundRecordingProvider(private val eventSequences: List<List<StreamEvent>>) : ChatStreamProvider {
    val receivedMessages = mutableListOf<List<ChatMessage>>()
    private var call = 0

    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>?,
        toolChoice: ToolChoice?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?
    ): Flow<StreamEvent> {
        receivedMessages += messages
        val events = eventSequences[call.coerceAtMost(eventSequences.size - 1)]
        call++
        return flow { events.forEach { emit(it) } }
    }
}

/**
 * Stub [Embedder] —— US-019 测试用，返回固定 384 维向量。
 *
 * 默认返回有效向量；[throwOnEmbed]=true 时抛 [IllegalStateException] 模拟嵌入失败（验证降级）。
 */
private class StubEmbedderImpl(private val throwOnEmbed: Boolean = false) : Embedder {
    override fun embed(text: String): FloatArray {
        if (throwOnEmbed) throw IllegalStateException("stub embed failure")
        return FloatArray(384) { 0.5f }
    }

    override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    override fun isLoaded(): Boolean = true
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false
    override fun close() {}
}

/** 单例 [StubEmbedderImpl]，供既有测试默认使用（RAG 关闭，embed 不会被调用）。 */
private val DefaultStubEmbedder: StubEmbedderImpl = StubEmbedderImpl()
