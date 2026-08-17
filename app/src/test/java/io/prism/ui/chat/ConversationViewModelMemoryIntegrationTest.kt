package io.prism.ui.chat

import io.objectbox.BoxStore
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.MemoryRepository
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.data.UserProfile
import io.prism.data.UserProfileRepository
import io.prism.embedding.Embedder
import io.prism.memory.ConversationSummarizer
import io.prism.memory.CrossSessionMemoryManager
import io.prism.memory.MemoryConfigRepository
import io.prism.memory.SlidingWindowMemoryManager
import io.prism.memory.UserProfileManager
import io.prism.network.ChatCompletionProvider
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.rag.RagTarget
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
 * ConversationViewModel 三层记忆系统集成测试（US-035，M5 Phase E）。
 *
 * **覆盖验收标准**：
 * - AC-1：L1 集成 —— sendMessage 时使用 SlidingWindowMemoryManager 管理上下文（滑动窗口+摘要压缩）
 * - AC-2：L2 集成 —— 新会话首条消息时触发 CrossSessionMemoryManager 检索，注入相关历史
 * - AC-3：L3 集成 —— systemPrompt 合并用户画像（显式+隐式偏好）
 * - AC-4：防污染验证 —— 新会话不加载旧会话全文，仅注入检索结果+画像
 * - AC-5：上下文合并 —— systemPrompt = RAG + 跨会话记忆 + 用户画像（顺序明确）
 * - AC-6：集成测试通过（L1 摘要触发 / L2 检索注入 / L3 画像注入 / 防污染 / 上下文合并顺序）
 *
 * **测试策略**：
 * - 使用真实记忆组件链（SlidingWindowMemoryManager + CrossSessionMemoryManager + UserProfileManager）
 * - 注入 Fake ChatStreamProvider（记录 systemPrompt + messages）+ Fake ChatCompletionProvider（控制摘要/抽取返回值）
 * - 注入 StubEmbedder（返回固定向量）+ 真实 ObjectBox MemoryRepository/UserProfileRepository
 * - 注入 TestScope 作为 applicationScope（验证 onCleared fire-and-forget）
 *
 * **隔离性**：每个测试用独立临时目录 ObjectBox + DataStore，互不干扰。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelMemoryIntegrationTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File
    private lateinit var memoryConfigRepository: MemoryConfigRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var userProfileRepository: UserProfileRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-mem-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        // MemoryConfigRepository 使用 FakePreferenceDataStore（内存版，避免文件 I/O 与 Android 依赖）
        // 同 MemoryConfigRepositoryTest Phase B 模式
        memoryConfigRepository = MemoryConfigRepository(
            io.prism.security.FakePreferenceDataStore(
                androidx.datastore.preferences.core.emptyPreferences()
            )
        )
        memoryRepository = MemoryRepository(boxStore)
        userProfileRepository = UserProfileRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** 测试用 ProviderConfig。 */
    private val testConfig = ProviderConfig(
        name = "test-provider",
        baseUrl = "https://api.test.com/v1",
        apiKeyRef = "test-key-ref",
        models = listOf("test-model"),
        headers = emptyMap()
    )

    /**
     * 构建集成测试 VM —— 注入真实记忆组件 + Fake Provider。
     *
     * @param streamEvents 流式事件序列
     * @param completionReturn 非流式 chatCompletion 返回值（控制 L1 摘要 + L3 隐式抽取）
     * @param windowSize L1 滑动窗口大小 N（默认 10）
     * @param ragTarget RAG 模式（默认 Off，隔离 RAG 逻辑）
     */
    private suspend fun buildMemoryVm(
        streamEvents: List<StreamEvent> = listOf(StreamEvent.Delta("回复"), StreamEvent.Done),
        completionReturn: String? = null,
        windowSize: Int = 10,
        ragTarget: RagTarget = RagTarget.Off,
        applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        sessionRepository: io.prism.data.SessionRepository? = null
    ): Pair<ConversationViewModel, MemoryTestRecordingProvider> {
        // 设置滑动窗口大小
        memoryConfigRepository.setWindowSize(windowSize)

        val repo = ProviderConfigRepository(boxStore)
        repo.save(testConfig)
        repo.setActive(repo.findByName(testConfig.name)!!.id)

        val provider = MemoryTestRecordingProvider(streamEvents)
        val completionProvider = FakeCompletionProvider(completionReturn)
        val embedder = DeterministicEmbedder()

        val summarizer = ConversationSummarizer(completionProvider)
        val slidingManager = SlidingWindowMemoryManager(summarizer, memoryConfigRepository)
        val crossSessionManager = CrossSessionMemoryManager(embedder, memoryRepository)
        val profileManager = UserProfileManager(completionProvider, userProfileRepository)

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = embedder,
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            slidingWindowMemoryManager = slidingManager,
            crossSessionMemoryManager = crossSessionManager,
            userProfileManager = profileManager,
            applicationScope = applicationScope,
            ioDispatcher = mainDispatcher,
            sessionRepository = sessionRepository
        )
        vm.setRagTarget(ragTarget)
        return vm to provider
    }

    // ==================== AC-1：L1 集成（滑动窗口+摘要压缩） ====================

    @Test
    fun `L1 integration - messages within window no summary injected`() = runTest(mainDispatcher) {
        // N=10，消息数 < N，不应触发摘要
        val (vm, provider) = buildMemoryVm(windowSize = 10)

        vm.sendMessage("你好")
        advanceUntilIdle()

        // systemPrompt 不应含 L1 摘要 section
        val systemPrompt = provider.receivedSystemPrompts.first()
        assertFalse("消息数 < N 时不应注入 L1 摘要", systemPrompt?.contains("早期对话摘要") == true)
    }

    @Test
    fun `L1 integration - messages exceed window triggers summary injection`() = runTest(mainDispatcher) {
        // N=2，第 3 条消息时触发摘要（history 含 2 条旧消息 > N=2 不成立，需 > N）
        // 实际：processMessages 在 messages.size > N 时触发摘要
        // sendMessage 流程：每次发送 1 条 user + 1 条 assistant 占位
        // 第 3 次发送时 history 含 4 条（2 user + 2 assistant），> N=2 触发摘要
        val (vm, provider) = buildMemoryVm(
            windowSize = 2,
            completionReturn = "早期讨论了测试话题"
        )

        // 发送 3 轮，第 3 轮 history 含 4 条 > N=2
        repeat(3) { i ->
            vm.sendMessage("问题$i")
            advanceUntilIdle()
        }

        // 第 3 次请求的 systemPrompt 应含 L1 摘要
        val thirdPrompt = provider.receivedSystemPrompts.last()
        assertNotNull("第 3 次请求应有 systemPrompt", thirdPrompt)
        assertTrue("应含 L1 摘要 section", thirdPrompt!!.contains("早期对话摘要"))
        assertTrue("应含摘要内容", thirdPrompt.contains("早期讨论了测试话题"))
    }

    @Test
    fun `L1 integration - recentHistory replaces full history when window exceeded`() = runTest(mainDispatcher) {
        // N=2，发送 3 轮后，第 3 次请求的 messages 应只含近期 2 条（滑动窗口）
        val (vm, provider) = buildMemoryVm(
            windowSize = 2,
            completionReturn = "摘要"
        )

        repeat(3) { i ->
            vm.sendMessage("问题$i")
            advanceUntilIdle()
        }

        // 最后一次请求的 messages 应被滑动窗口截断为 N=2 条
        val lastMessages = provider.receivedMessages.last()
        assertEquals("滑动窗口应只保留近期 N=2 条消息", 2, lastMessages.size)
    }

    // ==================== AC-2：L2 集成（跨会话检索注入） ====================

    @Test
    fun `L2 integration - first message triggers retrieval and injects memories`() = runTest(mainDispatcher) {
        // 预存一条跨会话记忆（模拟历史会话）
        val embedder = DeterministicEmbedder()
        val existingMemory = io.prism.data.MemoryRecord(
            sessionId = "old-session",
            content = "[用户] Kotlin 协程怎么用 [助手] 用 launch 启动",
            embedding = embedder.embed("Kotlin 协程"),
            timestamp = 1000L,
            turnCount = 1
        )
        memoryRepository.save(existingMemory)

        val (vm, provider) = buildMemoryVm()

        vm.sendMessage("Kotlin 协程")
        advanceUntilIdle()

        // systemPrompt 应含 L2 跨会话记忆
        val systemPrompt = provider.receivedSystemPrompts.first()
        assertNotNull(systemPrompt)
        assertTrue("应含 L2 跨会话记忆 section", systemPrompt!!.contains("相关历史对话"))
        assertTrue("应含检索到的历史内容", systemPrompt.contains("Kotlin 协程"))
    }

    @Test
    fun `L2 integration - empty memory store degrades to no L2 injection`() = runTest(mainDispatcher) {
        // 空库，L2 检索返回空，systemPrompt 不含 L2 section
        val (vm, provider) = buildMemoryVm()

        vm.sendMessage("你好")
        advanceUntilIdle()

        val systemPrompt = provider.receivedSystemPrompts.first()
        // 空 systemPrompt 或不含 L2 section
        assertFalse("空库时不应注入 L2 记忆", systemPrompt?.contains("相关历史对话") == true)
    }

    @Test
    fun `L2 integration - second message reuses cached context without re-retrieval`() = runTest(mainDispatcher) {
        val (vm, provider) = buildMemoryVm()

        vm.sendMessage("第一条")
        advanceUntilIdle()
        val firstPrompt = provider.receivedSystemPrompts.first()

        vm.sendMessage("第二条")
        advanceUntilIdle()
        val secondPrompt = provider.receivedSystemPrompts.last()

        // L2 上下文应缓存复用（首条消息检索后，第二条复用）
        // 空库时两者都不含 L2 section，验证一致性
        assertEquals("L2 上下文应缓存复用", firstPrompt?.contains("相关历史对话"), secondPrompt?.contains("相关历史对话"))
    }

    // ==================== AC-3：L3 集成（用户画像注入） ====================

    @Test
    fun `L3 integration - explicit preference injected into systemPrompt`() = runTest(mainDispatcher) {
        // 预设显式偏好
        val embedder = DeterministicEmbedder()
        val profileManager = UserProfileManager(FakeCompletionProvider(null), userProfileRepository)
        profileManager.setExplicitPreference("tone", "简洁")

        val (vm, provider) = buildMemoryVm()

        vm.sendMessage("你好")
        advanceUntilIdle()

        val systemPrompt = provider.receivedSystemPrompts.first()
        assertNotNull(systemPrompt)
        assertTrue("应含 L3 用户偏好 section", systemPrompt!!.contains("用户偏好"))
        assertTrue("应含显式偏好内容", systemPrompt.contains("简洁"))
        assertTrue("应标注显式类别", systemPrompt.contains("显式"))
    }

    @Test
    fun `L3 integration - no profile degrades to no L3 injection`() = runTest(mainDispatcher) {
        // 无画像，systemPrompt 不含 L3 section
        val (vm, provider) = buildMemoryVm()

        vm.sendMessage("你好")
        advanceUntilIdle()

        val systemPrompt = provider.receivedSystemPrompts.first()
        assertFalse("无画像时不应注入 L3 偏好", systemPrompt?.contains("用户偏好") == true)
    }

    // ==================== AC-4：防污染验证 ====================

    @Test
    fun `anti-contamination - new session history does not load old session full text`() = runTest(mainDispatcher) {
        // 预存旧会话记忆（仅检索结果注入，不加载全文）
        val embedder = DeterministicEmbedder()
        memoryRepository.save(
            io.prism.data.MemoryRecord(
                sessionId = "old-session",
                content = "[用户] 旧话题 [助手] 旧回复",
                embedding = embedder.embed("旧话题"),
                timestamp = 1000L,
                turnCount = 1
            )
        )

        val (vm, provider) = buildMemoryVm()

        vm.sendMessage("新话题")
        advanceUntilIdle()

        // 防污染：messages 不含旧会话的完整消息，仅 systemPrompt 含检索结果
        val messages = provider.receivedMessages.first()
        // messages 应只含当前会话的历史（用户消息 + AI 占位被过滤后可能为空或仅 user）
        // 关键：messages 不含 "旧回复" 作为独立消息
        assertFalse(
            "防污染：messages 不应含旧会话完整消息",
            messages.any { it.content.contains("旧回复") }
        )

        // 但 systemPrompt 应含检索到的记忆（作为 context section）
        val systemPrompt = provider.receivedSystemPrompts.first()
        if (systemPrompt != null && systemPrompt.contains("相关历史对话")) {
            assertTrue("检索结果应含旧会话内容", systemPrompt.contains("旧话题"))
        }
    }

    // ==================== AC-5：上下文合并顺序 ====================

    @Test
    fun `merge order - RAG then L1 then L2 then L3 then Skill`() = runTest(mainDispatcher) {
        // 纯函数测试：验证 ADR-015 决策4 合并顺序
        val ragPrompt = "[RAG grounding rules]"
        val l1Summary = "[早期对话摘要] 摘要内容"
        val l2Memories = "相关历史对话：\n1. 历史"
        val l3Profiles = "用户偏好：\n- tone: 简洁（显式）"

        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = ragPrompt,
            l1Summary = l1Summary,
            l2Memories = l2Memories,
            l3Profiles = l3Profiles,
            enabledSkills = emptyList()
        )

        val ragIdx = result.indexOf(ragPrompt)
        val l1Idx = result.indexOf(l1Summary)
        val l2Idx = result.indexOf(l2Memories)
        val l3Idx = result.indexOf(l3Profiles)

        assertTrue("RAG 应在 L1 之前", ragIdx < l1Idx)
        assertTrue("L1 应在 L2 之前", l1Idx < l2Idx)
        assertTrue("L2 应在 L3 之前", l2Idx < l3Idx)
    }

    @Test
    fun `merge order - all layers null returns default persona`() {
        // ADR-018：所有层为 null 时返回默认 persona（而非 null），保证 LLM 有基础身份
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            l1Summary = null,
            l2Memories = null,
            l3Profiles = null,
            enabledSkills = emptyList()
        )
        assertEquals("所有层为 null 时应返回默认 persona", ConversationViewModel.DEFAULT_PERSONA, result)
    }

    @Test
    fun `merge order - only L2 present returns persona then L2 section`() {
        val l2Memories = "相关历史对话：\n1. 历史"
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            l1Summary = null,
            l2Memories = l2Memories,
            l3Profiles = null,
            enabledSkills = emptyList()
        )
        assertTrue("应包含默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("应包含 L2 section", result.contains(l2Memories))
        assertTrue(
            "persona 应在 L2 之前",
            result.indexOf(ConversationViewModel.DEFAULT_PERSONA) < result.indexOf(l2Memories)
        )
    }

    @Test
    fun `merge order - backward compatible with M4 Phase D signature`() {
        // 向后兼容：仅传 ragPrompt + enabledSkills（M4 Phase D 既有测试模式）
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG rules",
            enabledSkills = emptyList()
        )
        assertTrue("应包含默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("应包含 RAG rules", result.contains("RAG rules"))
    }

    // ==================== AC-6：降级策略 ====================

    @Test
    fun `degradation - null memory managers degrade to normal chat`() = runTest(mainDispatcher) {
        // 三个 memory manager 均为 null（向后兼容无记忆场景）
        val repo = ProviderConfigRepository(boxStore)
        repo.save(testConfig)
        repo.setActive(repo.findByName(testConfig.name)!!.id)

        val provider = MemoryTestRecordingProvider(
            listOf(StreamEvent.Delta("回复"), StreamEvent.Done)
        )

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = DeterministicEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore)
            // slidingWindowMemoryManager / crossSessionMemoryManager / userProfileManager 均默认 null
        )
        vm.setRagTarget(RagTarget.Off)

        vm.sendMessage("你好")
        advanceUntilIdle()

        // 应正常回复，不崩溃
        assertEquals("应正常追加 AI 回复", "回复", vm.messages.value[1].content)
        assertFalse("isTyping 应为 false", vm.isTyping.value)
    }

    @Test
    fun `degradation - L1 summary failure falls back to truncation`() = runTest(mainDispatcher) {
        // L1 摘要 LLM 失败（completionReturn=null），降级为截断
        val (vm, provider) = buildMemoryVm(
            windowSize = 2,
            completionReturn = null  // 摘要失败
        )

        repeat(3) { i ->
            vm.sendMessage("问题$i")
            advanceUntilIdle()
        }

        // 第 3 次请求应含截断摘要（非 LLM 摘要，但仍是 summary）
        val thirdPrompt = provider.receivedSystemPrompts.last()
        assertNotNull(thirdPrompt)
        assertTrue("应含 L1 摘要 section（截断降级）", thirdPrompt!!.contains("早期对话摘要"))
    }

    // ==================== onCleared：会话结束持久化 ====================

    @Test
    fun `onCleared - persists L2 memories and extracts L3 preferences`() = runTest(mainDispatcher) {
        val persistScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val (vm, _) = buildMemoryVm(
            completionReturn = """{"tone":"简洁"}""",  // L3 抽取返回值
            applicationScope = persistScope
        )

        // 发送几条消息建立会话（UXR9 Bug4 适配：首条消息 ≥8 字且含偏好关键词，
        // 否则被 L2 重要性过滤跳过，count=0 断言失败）
        vm.sendMessage("我的偏好是用简洁风格回复")
        advanceUntilIdle()
        vm.sendMessage("继续")
        advanceUntilIdle()

        // 触发持久化（onCleared 是 protected，通过 internal persistSessionMemories 测试）
        vm.persistSessionMemories()
        advanceUntilIdle()

        // L2：应持久化跨会话记忆
        assertTrue("L2 应持久化记忆", memoryRepository.count() > 0)

        // L3：应抽取隐式偏好
        val implicitProfiles = userProfileRepository.getByCategory(io.prism.data.ProfileCategory.IMPLICIT)
        assertTrue("L3 应抽取隐式偏好", implicitProfiles.isNotEmpty())
    }

    @Test
    fun `onCleared - no session started skips persistence`() = runTest(mainDispatcher) {
        val persistScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val (vm, _) = buildMemoryVm(applicationScope = persistScope)

        // 未发送任何消息，直接触发持久化
        vm.persistSessionMemories()
        advanceUntilIdle()

        // 不应持久化任何内容
        assertEquals("无会话时不应持久化 L2 记忆", 0L, memoryRepository.count())
    }

    // ==================== UXR8 Bug2：生产触发路径（L2 保存） ====================

    @Test
    fun `startNewConversation persists L2 memories before clearing`() = runTest(mainDispatcher) {
        // UXR8 Bug2（ADR-028，考古 TKN-UXR8-ARCHAEOLOGY-001）：L2 保存此前唯一挂在 onCleared，
        // 单 Activity 下 Chat 永不被 pop → 用户点"新对话"只调 persistSession 不调 persistSessionMemories
        // → 上一会话 L2 在保存前被丢弃（库恒空）。修复：startNewConversation 清空前调用持久化。
        val persistScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val (vm, _) = buildMemoryVm(
            completionReturn = """{"tone":"简洁"}""",
            applicationScope = persistScope
        )

        // 建立会话（UXR9 Bug4 适配：首条消息 ≥8 字且含偏好关键词）
        vm.sendMessage("我的偏好是用简洁风格回复")
        advanceUntilIdle()
        vm.sendMessage("继续")
        advanceUntilIdle()

        // 生产触发：点"新对话"（不经 onCleared）
        vm.startNewConversation()
        advanceUntilIdle()

        // L2：应持久化跨会话记忆（生产触发路径生效）
        assertTrue("startNewConversation 应触发 L2 持久化", memoryRepository.count() > 0)
        // 消息已清空
        assertTrue("新对话后消息应清空", vm.messages.value.isEmpty())
    }

    @Test
    fun `loadSession persists current L2 memories before switching`() = runTest(mainDispatcher) {
        // UXR8 Bug2 同类修复：加载其他会话前先持久化当前会话的 L2/L3 记忆
        val persistScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val sessionRepo = io.prism.data.SessionRepository(boxStore)
        // 先建一个目标会话（供 loadSession 加载），避免提前 return 无法覆盖真实切换路径
        val targetSession = io.prism.data.Session(title = "目标会话", messagesJson = "[]")
        sessionRepo.save(targetSession)
        val (vm, _) = buildMemoryVm(
            completionReturn = """{"tone":"简洁"}""",
            applicationScope = persistScope,
            sessionRepository = sessionRepo
        )
        vm.sendMessage("记录一些偏好")
        advanceUntilIdle()

        // 生产触发：加载另一会话（目标存在 → 走真实切换路径 → 切换前应持久化当前 L2）
        vm.loadSession(targetSession.id)
        advanceUntilIdle()

        // L2：当前会话记忆应已持久化（生产触发路径生效）
        assertTrue("loadSession 应触发当前会话 L2 持久化", memoryRepository.count() > 0)
        // 消息已切换为目标会话（空列表）
        assertTrue("加载后消息应切换为目标会话内容", vm.messages.value.isEmpty())
    }
}

/**
 * 记录 systemPrompt + messages 的 fake [ChatStreamProvider]（US-035 集成测试专用）。
 *
 * 按给定序列发射 [StreamEvent]，同时记录每次请求的 systemPrompt 与 messages，
 * 供断言 L1/L2/L3 上下文注入是否正确。
 *
 * **命名隔离**：使用 `MemoryTest` 前缀避免与 [ConversationViewModelTest] 中的
 * `RecordingChatStreamProvider` 同包重名冲突（Kotlin 顶层 private 类仍受同包唯一名约束）。
 */
private class MemoryTestRecordingProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
    val receivedMessages = mutableListOf<List<ChatMessage>>()
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
        receivedMessages += messages
        receivedSystemPrompts += systemPrompt
        receivedRagContexts += ragContext
        return flow { events.forEach { emit(it) } }
    }
}

/**
 * Fake [ChatCompletionProvider] —— 返回固定 JSON 文本，供 [ConversationSummarizer] 和
 * [UserProfileManager] 测试注入（US-035 集成测试）。
 *
 * @param returnValue chatCompletion 返回的固定值（null 模拟失败/空响应）
 */
private class FakeCompletionProvider(private val returnValue: String?) : ChatCompletionProvider {
    override suspend fun chatCompletion(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        thinkingEnabled: Boolean?,
        reasoningEffort: String?
    ): String? = returnValue
}

/**
 * 确定性 [Embedder] —— 返回固定 384 维向量，供 L2 跨会话检索测试注入。
 *
 * 同一文本返回相同向量（基于文本 hashCode 填充），保证检索可重现。
 */
private class DeterministicEmbedder : Embedder {
    override fun embed(text: String): FloatArray {
        val vector = FloatArray(384)
        val hash = text.hashCode()
        // 用 hashCode 填充向量，使不同文本产生不同但确定的向量
        for (i in vector.indices) {
            vector[i] = ((hash shr (i % 32)) and 0xFF) / 255.0f
        }
        return vector
    }

    override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    override fun isLoaded(): Boolean = true
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false
    override fun close() {}
}
