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
import io.prism.ui.model.Role
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UXR8-R3 Bug 修复验收 · 集成/边界极端用例（ac-verifier，TKN-UXR8-R3-ACCEPTANCE-001）。
 *
 * 本文件由 ac-verifier 独立创建，聚焦主 Agent 自问的三处盲区：
 *
 * Bug 1（RAG 需求预判）：既有测试只覆盖纯函数 [ConversationViewModel.needsRagRetrieval]，
 *   未覆盖 **launchAnswer 门控集成**（问候消息是否真的不再触发 embed/search）。本文件用
 *   [CountingEmbedder] 做 embed 调用计数（可观测性断言）：问候消息 embed 调用数 = 0，
 *   真实查询仍注入（防误伤）。
 *
 * Bug 2（图片队列）：既有测试只覆盖 2 张图的正常队列；本文件覆盖 **队列超限**（MAX_PENDING_IMAGES
 *   = 8，第 9 张丢弃 + 超限提示）与 **纯图片（空文本）** 边界、**纯文本 isTyping 守卫保持原行为**。
 *
 * Bug 1 输入边界（安全检查）：超长 / 全标点 / 全数字 / 表情消息不崩溃（CWE-20 输入边界）。
 *
 * 依赖注入与 [ConversationViewModelTest] 一致（UnconfinedTestDispatcher + 临时 ObjectBox）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelUxr8R3EdgeCaseTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "uxr8r3-edge-").toFile()
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

    // =====================================================================
    // Bug 1：RAG 需求预判门控 —— 可观测性（embed 调用计数）与防误伤
    // =====================================================================

    @Test
    fun `greeting message does not trigger embed or rag injection`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 库中存在与 DefaultStubEmbedder 完全一致的 chunk（修复前必命中注入）
        kbRepo.addChunk(KnowledgeChunk(
            title = "问候文档.txt#1", content = "问候相关片段",
            embedding = FloatArray(384) { 0.5f }, knowledgeBaseId = 0L
        ))
        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("你好"), StreamEvent.Done))
        val embedder = CountingEmbedder()
        val vm = ConversationViewModel(
            repo, provider, embedder, kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        assertEquals("问候消息不应调用 embed（可观测性：embed 调用数应为 0）", 0, embedder.embedCount)
        assertEquals("问候消息不应注入 ragContext", null, provider.receivedRagContexts.single())
        val sysPrompt = provider.receivedSystemPrompts.single()
        assertNotNull(sysPrompt)
        assertFalse("问候消息 systemPrompt 不应含 RAG grounding rules",
            sysPrompt!!.contains(RagContextBuilder.SYSTEM_PROMPT))
        assertTrue("问候消息 AI 应正常回复", vm.messages.value[1].content.contains("你好"))
    }

    @Test
    fun `real query still triggers rag injection after the gate`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val kbRepo = KnowledgeBaseRepository(boxStore)
        kbRepo.addChunk(KnowledgeChunk(
            title = "真实文档.md#1", content = "真实查询对应片段",
            embedding = FloatArray(384) { 0.5f }, knowledgeBaseId = 0L
        ))
        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("基于知识库"), StreamEvent.Done))
        val embedder = CountingEmbedder()
        val vm = ConversationViewModel(
            repo, provider, embedder, kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询一下真实文档的关键信息")
        advanceUntilIdle()

        assertEquals("真实查询应调用 embed（不被需求预判误伤）", 1, embedder.embedCount)
        val ragCtx = provider.receivedRagContexts.single()
        assertNotNull("真实查询应注入 ragContext", ragCtx)
        assertTrue("ragContext 应以【知识库片段】开头", ragCtx!!.startsWith("【知识库片段】"))
        assertTrue("真实查询 systemPrompt 应含 RAG grounding rules",
            provider.receivedSystemPrompts.single()!!.contains(RagContextBuilder.SYSTEM_PROMPT))
    }

    @Test
    fun `greeting then query only query triggers embed`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val kbRepo = KnowledgeBaseRepository(boxStore)
        kbRepo.addChunk(KnowledgeChunk(
            title = "文档C.pdf#1", content = "知识片段",
            embedding = FloatArray(384) { 0.5f }, knowledgeBaseId = 0L
        ))
        val provider = MultiRoundRecordingProvider(
            listOf(
                listOf(StreamEvent.Delta("回1"), StreamEvent.Done),
                listOf(StreamEvent.Delta("回2"), StreamEvent.Done)
            )
        )
        val embedder = CountingEmbedder()
        val vm = ConversationViewModel(
            repo, provider, embedder, kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("好的，谢谢")
        advanceUntilIdle()
        vm.sendMessage("总结一下文档C的知识")
        advanceUntilIdle()

        assertEquals("两轮中仅真实查询触发 embed（问候轮 embed 数 0）", 1, embedder.embedCount)
        // 第一轮（问候）无 RAG 注入，第二轮（查询）注入
        assertEquals("第 1 轮 ragContext 应为 null（问候跳过）", null, provider.receivedRagContexts[0])
        assertNotNull("第 2 轮 ragContext 应注入（查询）", provider.receivedRagContexts[1])
    }

    // =====================================================================
    // Bug 1 输入边界（安全）：超长 / 全标点 / 全数字 / 表情消息不崩溃
    // =====================================================================

    @Test
    fun `needsRagRetrieval extreme inputs do not crash`() {
        val longText = "好".repeat(10_000) + "请总结知识库"
        // 超长消息（含 '好' 前缀，归一化后不整句命中）→ 应保留（保守）
        assertTrue("超长消息不应崩溃且应保留", ConversationViewModel.needsRagRetrieval(longText))
        // 全标点 / 全空白 → 归一化后为空串，不崩溃
        assertTrue("全标点消息不应崩溃", ConversationViewModel.needsRagRetrieval("！！！？？？。。。"))
        assertFalse("全空白消息返回 false", ConversationViewModel.needsRagRetrieval(" \t\n "))
        // 表情 / emoji（非字母数字）→ 归一化空串，不崩溃
        assertTrue("纯 emoji 消息不应崩溃", ConversationViewModel.needsRagRetrieval("😀😀👍"))
        // 全数字 → 保留
        assertTrue("全数字消息应保留", ConversationViewModel.needsRagRetrieval("123456789"))
        // 大小写混合英文确认语 → 归一化后命中（整句精确）
        assertFalse("OK 大写应归一化命中", ConversationViewModel.needsRagRetrieval("OK"))
        assertFalse("Hello 混合大小写应命中", ConversationViewModel.needsRagRetrieval("HeLLo"))
        // 确认语 + 真实查询内容（归一化后非整句命中）→ 保留（不误伤）
        assertTrue("确认语+查询应保留", ConversationViewModel.needsRagRetrieval("好，帮我查一下知识库"))
        // 独立整句确认语 "好" → 正常跳过（在 RAG_SKIP_PHRASES 集合内）
        assertFalse("独立整句确认语应跳过", ConversationViewModel.needsRagRetrieval("好"))
    }

    // =====================================================================
    // Bug 2：图片队列边界 —— 超限 / 纯图片 / 纯文本守卫保持
    // =====================================================================

    @Test
    fun `image queue overflow beyond limit drops newest and enqueues notice`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = GatedStreamProvider()
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("第一问")
        provider.awaitFirstRoundStarted()  // isTyping=true

        // 连选 MAX_PENDING_IMAGES+1 = 9 张图，第 9 张应被拒绝 + 超限提示
        val limit = ConversationViewModel.MAX_PENDING_IMAGES
        for (i in 1..(limit + 1)) {
            vm.sendMessage("图$i", "data:image/jpeg;base64,IMG$i")
        }

        provider.releaseFirstRound()
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        val imgMsgs = userMsgs.filter { it.imageUrl != null }
        assertEquals("最多暂存 $limit 张并全部送达", limit, imgMsgs.size)
        assertTrue("第 9 张（最新）应被丢弃，不出现 IMG9",
            imgMsgs.none { it.imageUrl!!.endsWith("IMG${limit + 1}") })
        assertTrue("应有一条超限提示", userMsgs.any { it.content.contains("待发送图片过多") })
        assertTrue("前 $limit 张图应全部送达",
            (1..limit).all { n -> imgMsgs.any { it.imageUrl!!.endsWith("IMG$n") } })
    }

    @Test
    fun `image only with blank text during typing is queued and flushed`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = GatedStreamProvider()
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("第一问")
        provider.awaitFirstRoundStarted()

        // 纯图片（空文本）在 isTyping 期间 → 应入队
        vm.sendMessage("", "data:image/jpeg;base64,ONLYIMG")

        provider.releaseFirstRound()
        advanceUntilIdle()

        val imgMsgs = vm.messages.value.filter { it.role == Role.USER && it.imageUrl != null }
        assertTrue("纯图片消息应送达", imgMsgs.any { it.imageUrl!!.endsWith("ONLYIMG") })
    }

    @Test
    fun `plain text during typing still respects guard not queued`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = GatedStreamProvider()
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("第一问")
        provider.awaitFirstRoundStarted()

        // 纯文本消息在 isTyping 期间 → 保持原守卫行为（丢弃，不排队）—— 防误排队回归
        vm.sendMessage("回复期间纯文本")

        provider.releaseFirstRound()
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        assertFalse("isTyping 期间纯文本应被丢弃（守卫保持原行为）",
            userMsgs.any { it.content == "回复期间纯文本" })
        assertEquals("isTyping 期间纯文本不应产生额外用户消息", 1, userMsgs.size)
    }

    // =====================================================================
    // Fakes
    // =====================================================================

    /** 记录每次传入 [messages]/[systemPrompt]/[ragContext] 的 fake，用于断言注入。 */
    private class RecordingChatStreamProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
        val receivedMessages = mutableListOf<List<io.prism.ui.model.ChatMessage>>()
        val receivedSystemPrompts = mutableListOf<String?>()
        val receivedRagContexts = mutableListOf<String?>()

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
            receivedSystemPrompts += systemPrompt
            receivedRagContexts += ragContext
            return flow { events.forEach { emit(it) } }
        }
    }

    /** 多轮 fake：按调用序依次返回不同事件序列。 */
    private class MultiRoundRecordingProvider(
        private val eventSequences: List<List<StreamEvent>>
    ) : ChatStreamProvider {
        val receivedRagContexts = mutableListOf<String?>()
        private var call = 0

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
            receivedRagContexts += ragContext
            val events = eventSequences[call.coerceAtMost(eventSequences.size - 1)]
            call++
            return flow { events.forEach { emit(it) } }
        }
    }

    /** 门控流式 fake（M-1/M-2 测试）—— 第一轮挂起直到 [releaseFirstRound] 放行。 */
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
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> = flow {
            val current = round++
            if (current == 0) {
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

    /** 计数 Embedder —— 可观测性断言 embed 调用次数（Bug 1 门控验证）。 */
    private class CountingEmbedder : Embedder {
        var embedCount = 0
            private set

        override fun embed(text: String): FloatArray {
            embedCount++
            return FloatArray(384) { 0.5f }
        }

        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }

    /** 单例 stub embedder（返回固定 384 维向量，与 chunk 一致）。 */
    private class DefaultStubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
