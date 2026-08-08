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

        assertTrue("RAG 关闭时不应注入 system prompt", provider.receivedSystemPrompts.single() == null)
        assertTrue("RAG 关闭时不应注入 ragContext", provider.receivedRagContexts.single() == null)
        assertEquals("ok", vm.messages.value[1].content)
    }

    /**
     * US-019 AC：RAG 开启但 embedder 抛异常时，降级为普通对话，不阻断。
     * 验证 ADR-012 5.5 三级降级之「embed 失败」分支。
     *
     * **G-02 修复后行为**（TKN-US019-RAG-GUARDRAIL-001）：embed 失败 → appendDelta 提示用户
     * 「⚠️ 知识库检索失败，已降级为普通对话」+ AI 流式回复。提示在前，回复在后。
     */
    @Test
    fun `rag on with embedder failure degrades to normal chat`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("降级回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            StubEmbedderImpl(throwOnEmbed = true),  // embed 抛异常
            KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher  // 注入 test dispatcher，避免 withContext(IO) 脱离 test scheduler
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        assertTrue("embed 失败应降级，systemPrompt 为 null", provider.receivedSystemPrompts.single() == null)
        assertTrue("embed 失败应降级，ragContext 为 null", provider.receivedRagContexts.single() == null)
        // G-02 修复：embed 失败应 appendDelta 提示 + AI 流式回复
        val content = vm.messages.value[1].content
        assertTrue("应包含降级提示", content.contains("知识库检索失败"))
        assertTrue("应包含降级提示文案", content.contains("已降级为普通对话"))
        assertTrue("应包含 AI 流式回复", content.contains("降级回复"))
        assertTrue("降级时不应附引用来源", vm.messages.value[1].sources.isEmpty())
        // G-02 修复配套：降级提示消息不应进请求历史（aiId 过滤）
        val sent = provider.receivedMessages.single()
        assertTrue("请求历史不应包含降级提示消息", sent.none { it.content.contains("知识库检索失败") })
    }

    /**
     * US-019 AC：RAG 开启但知识库为空（search 返回空）时，降级为普通对话。
     * 验证 ADR-012 5.5 三级降级之「search 失败或空结果」分支。
     */
    @Test
    fun `rag on with empty knowledgebase degrades to normal chat`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("空库回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider,
            DefaultStubEmbedder,  // embed 正常
            KnowledgeBaseRepository(boxStore),  // 空库，search 返回空
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        assertTrue("空库应降级，systemPrompt 为 null", provider.receivedSystemPrompts.single() == null)
        assertTrue("空库应降级，ragContext 为 null", provider.receivedRagContexts.single() == null)
        assertEquals("空库回复", vm.messages.value[1].content)
        assertTrue("空库时不应附引用来源", vm.messages.value[1].sources.isEmpty())
    }

    /**
     * US-019 AC：RAG 模式切换器三态正确切换 + 默认值为 AllLibraries。
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
        assertEquals("默认应为 AllLibraries", RagTarget.AllLibraries, vm.ragTarget.value)

        vm.setRagTarget(RagTarget.Off)
        assertEquals(RagTarget.Off, vm.ragTarget.value)
        vm.setRagTarget(RagTarget.SpecificLibrary(42L))
        assertEquals(RagTarget.SpecificLibrary(42L), vm.ragTarget.value)
        vm.setRagTarget(RagTarget.AllLibraries)
        assertEquals(RagTarget.AllLibraries, vm.ragTarget.value)
    }

    // ==================== G-05 修复：正向快乐路径 + 阈值过滤 + SpecificLibrary 校验 ====================
    // 以下测试补齐 guardrail TKN-US019-RAG-GUARDRAIL-001 G-05/G-07 发现的覆盖缺口。

    /**
     * US-019 AC-3 核心验证（G-05 修复）：RAG 开启 + embed 成功 + search 返回结果 +
     * 阈值过滤通过 → systemPrompt + ragContext + citations 均正确注入。
     *
     * 构造方式：向默认库（kbId=0L）插入一条 chunk，embedding 与 StubEmbedderImpl 返回值一致
     * （FloatArray(384){0.5f}），COSINE 距离=0 → similarity=1.0，通过 0.3 阈值。
     */
    @Test
    fun `rag on with matching chunks injects system prompt rag context and citations`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // 构造含 chunk 的 KB（默认库 kbId=0L，AllLibraries 模式可检索）
        val kbRepo = KnowledgeBaseRepository(boxStore)
        kbRepo.addChunk(KnowledgeChunk(
            title = "文档A.pdf#1",
            content = "这是文档A的第一个片段，含关键信息。",
            embedding = FloatArray(384) { 0.5f },  // 与 DefaultStubEmbedder 返回值一致
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

        // 1. systemPrompt 注入：应为 RagContextBuilder.SYSTEM_PROMPT
        assertEquals(
            "应注入 RAG system prompt",
            RagContextBuilder.SYSTEM_PROMPT,
            provider.receivedSystemPrompts.single()
        )

        // 2. ragContext 注入：应含【知识库片段】头 + [来源N] 编号 + 文件信息
        val ragCtx = provider.receivedRagContexts.single()
        assertNotNull("ragContext 不应为 null", ragCtx)
        assertTrue("ragContext 应以【知识库片段】开头", ragCtx!!.startsWith("【知识库片段】"))
        assertTrue("ragContext 应含 [来源1]", ragCtx.contains("[来源1]"))
        assertTrue("ragContext 应含文档A", ragCtx.contains("文档A.pdf"))
        assertTrue("ragContext 应含片段=1", ragCtx.contains("片段=1"))

        // 3. citations 附着：AI 消息 sources 非空，编号与 context 对齐
        val aiMsg = vm.messages.value[1]
        assertTrue("AI 消息应附引用来源", aiMsg.sources.isNotEmpty())
        assertEquals("top-k=3 但只有 2 条 chunk，应返回 2 条 citation", 2, aiMsg.sources.size)
        assertEquals("第 1 条 citation index=1", 1, aiMsg.sources[0].index)
        assertEquals("第 1 条 citation documentTitle", "文档A.pdf", aiMsg.sources[0].documentTitle)
        assertEquals("第 1 条 citation chunkIndex=1", 1, aiMsg.sources[0].chunkIndex)
        assertEquals("第 2 条 citation index=2", 2, aiMsg.sources[1].index)

        // 4. AI 流式回复正常追加
        assertTrue("应包含 AI 流式回复", aiMsg.content.contains("基于知识库回复"))
    }

    /**
     * G-07 修复：search 返回非空但全部 similarity < 0.3 阈值 → 降级为普通对话，无引用。
     *
     * 构造方式：chunk embedding 与 query embedding 正交（点积为 0），COSINE 距离=1 → similarity=0，
     * 低于 0.3 阈值，全部被过滤。
     */
    @Test
    fun `rag on with below threshold results degrades to normal chat`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // chunk embedding 全 0，与 query embedding（全 0.5）正交，similarity = 1 - 1 = 0 < 0.3
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

        // 阈值过滤后为空 → NormalChat 降级，无 systemPrompt / ragContext / citations
        assertTrue("阈值过滤空应降级，systemPrompt 为 null", provider.receivedSystemPrompts.single() == null)
        assertTrue("阈值过滤空应降级，ragContext 为 null", provider.receivedRagContexts.single() == null)
        assertEquals("普通回复", vm.messages.value[1].content)
        assertTrue("阈值过滤空不应附引用来源", vm.messages.value[1].sources.isEmpty())
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
     * G-04 修复验证：SpecificLibrary(kbId > 0) 指定库检索，命中该库 chunk。
     */
    @Test
    fun `rag on with specific library retrieves only that library chunks`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 自建库 id=1，插入匹配 chunk
        kbRepo.addChunk(KnowledgeChunk(
            title = "自建库文档.pdf#1",
            content = "自建库内容",
            embedding = FloatArray(384) { 0.5f },
            knowledgeBaseId = 1L
        ))
        // 默认库 kbId=0L，插入匹配 chunk（不应被 SpecificLibrary(1L) 检索到）
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

        // 应只命中自建库 chunk，不命中默认库 chunk
        val ragCtx = provider.receivedRagContexts.single()
        assertNotNull(ragCtx)
        assertTrue("应含自建库文档", ragCtx!!.contains("自建库文档.pdf"))
        assertFalse("不应含默认库文档", ragCtx.contains("默认库文档.pdf"))
        assertEquals("应只返回 1 条 citation", 1, vm.messages.value[1].sources.size)
        assertEquals("自建库文档.pdf", vm.messages.value[1].sources[0].documentTitle)
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
        toolChoice: ToolChoice?
    ): Flow<StreamEvent> = flow { events.forEach { emit(it) } }
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
        toolChoice: ToolChoice?
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
        toolChoice: ToolChoice?
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
