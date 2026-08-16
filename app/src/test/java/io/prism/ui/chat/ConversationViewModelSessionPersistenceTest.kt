package io.prism.ui.chat

import io.objectbox.BoxStore
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.data.SessionRepository
import io.prism.embedding.Embedder
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
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
 * UXR4 问题 8/9（ADR-024）验收测试 —— 会话持久化时机与 updatedAt 语义。
 *
 * 验证：
 * 1. 回答完成（Done）时落库，updatedAt=最后消息结束时刻（问题 8/9）
 * 2. 只读打开历史会话再退出**不**刷新 updatedAt（脏标记，避免"打开即顶到刚刚"）
 * 3. 新会话首条消息回答完成后落库（问题 9：崩溃不丢）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelSessionPersistenceTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-session-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun saveActiveProvider() {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(
            name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            apiKeyRef = "openai", models = listOf("gpt-4o")
        )
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
    }

    /** 发射 [Done] 的普通流式 provider（无工具 → executePlainStream 分支）。 */
    private class DoneChatStreamProvider : ChatStreamProvider {
        override fun streamChat(
            config: ProviderConfig,
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<io.prism.network.ToolDefinition>?,
            toolChoice: io.prism.network.ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> = flow {
            emit(StreamEvent.Delta("回答内容"))
            emit(StreamEvent.Done)
        }
    }

    @Test
    fun `sendMessage persists session with updatedAt on answer completion`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val sessionRepo = SessionRepository(boxStore)
        val before = System.currentTimeMillis()

        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = DoneChatStreamProvider(),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            sessionRepository = sessionRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("你好")
        advanceUntilIdle()

        // 回答完成（Done）已触发 persistSession → 会话落库
        val sessions = sessionRepo.sessions.value
        assertEquals("回答完成应落库 1 个会话", 1, sessions.size)
        val session = sessions[0]
        assertTrue("updatedAt 应反映回答完成时刻（>= 发送时刻）", session.updatedAt >= before)
        assertTrue("会话 JSON 应包含消息", session.messagesJson.contains("你好"))
        assertTrue("会话 JSON 应包含回答", session.messagesJson.contains("回答内容"))
    }

    @Test
    fun `read-only open session then exit does not refresh updatedAt`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val sessionRepo = SessionRepository(boxStore)

        // 1. 创建并完成一个会话（updatedAt=T1）
        val vm1 = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = DoneChatStreamProvider(),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            sessionRepository = sessionRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }
        vm1.sendMessage("第一问")
        advanceUntilIdle()

        val created = sessionRepo.sessions.value.single()
        val originalUpdatedAt = created.updatedAt

        // 2. 模拟"只读打开"该会话（loadSession）→ 加载后退出（onCleared → persistSession）
        val vm2 = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = DoneChatStreamProvider(),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            sessionRepository = sessionRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }
        vm2.loadSession(created.id)
        advanceUntilIdle()

        // UXR4 问题 8（ADR-024）：只读打开后退出（模拟 onCleared）不应刷新 updatedAt
        // persistSession 是 private，通过 startNewConversation（会先 persistSession）间接触发
        vm2.startNewConversation()
        advanceUntilIdle()

        val after = sessionRepo.sessions.value.single()
        assertEquals(
            "只读打开再退出不应刷新 updatedAt（脏标记拦截）",
            originalUpdatedAt,
            after.updatedAt
        )
    }

    @Test
    fun `new conversation after answer persists as separate session`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val sessionRepo = SessionRepository(boxStore)

        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = DoneChatStreamProvider(),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            sessionRepository = sessionRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("第一问")
        advanceUntilIdle()
        vm.startNewConversation()
        advanceUntilIdle()
        vm.sendMessage("第二问")
        advanceUntilIdle()

        assertEquals("应有两个独立会话", 2, sessionRepo.sessions.value.size)
        // 新会话 updatedAt 应晚于旧会话（列表按 updatedAt 倒序，最新在前）
        val sorted = sessionRepo.sessions.value
        assertTrue("新会话应在列表最前", sorted[0].messagesJson.contains("第二问"))
        assertTrue("旧会话应在列表后", sorted[1].messagesJson.contains("第一问"))
    }

    @Test
    fun `thinkingChain persisted in JSON when thinkingEnabled true`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val sessionRepo = SessionRepository(boxStore)
        // 发射 reasoning delta 的 provider
        val thinkingProvider = ReasoningChatStreamProvider()

        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = thinkingProvider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            sessionRepository = sessionRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 开启深度思考（默认 false，显式开启后 thinkingChain 应持久化）
        vm.setThinkingEnabled(true)
        vm.sendMessage("思考问题")
        advanceUntilIdle()

        val json = sessionRepo.sessions.value.single().messagesJson
        assertTrue("thinkingChain 应在 JSON 中持久化（thinkingEnabled=true）", json.contains("thinkingChain"))
        assertTrue("思考内容应包含在 JSON 中", json.contains("这是推理过程"))
    }

    @Test
    fun `thinkingChain stripped from JSON when thinkingEnabled false`() = runTest(mainDispatcher) {
        // S1（guardrail TKN-UXR4-GUARDRAIL-001）：开关关闭时 thinkingChain 在内存中保留
        //（供协议回传），但**不**持久化进会话 JSON（隐私边界）。
        saveActiveProvider()
        val sessionRepo = SessionRepository(boxStore)
        val thinkingProvider = ReasoningChatStreamProvider()

        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = thinkingProvider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            sessionRepository = sessionRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 默认 thinkingEnabled=false
        vm.sendMessage("思考问题")
        advanceUntilIdle()

        // 双面断言（BR-testing-005 / guardrail R2-NEW-2）：
        // ① 内存中 thinkingChain 应保留（协议回传不受 S1 剥离影响）
        val msgs = vm.messages.value
        val aiMsg = msgs.lastOrNull { it.role == Role.ASSISTANT }
        assertNotNull("AI 消息应存在", aiMsg)
        assertEquals("内存 thinkingChain 应保留（供协议回传）", "这是推理过程", aiMsg!!.thinkingChain)

        // ② 持久化 JSON 不应含思考内容（隐私边界：思考链值被剥离为 null）
        val json = sessionRepo.sessions.value.single().messagesJson
        assertFalse("思考内容不应在 JSON 中 persist（thinkingEnabled=false）", json.contains("这是推理过程"))
    }

    /** 发射 ReasoningDelta + Delta + Done 的 provider（用于 S1 思考链测试）。 */
    private class ReasoningChatStreamProvider : ChatStreamProvider {
        override fun streamChat(
            config: ProviderConfig,
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<io.prism.network.ToolDefinition>?,
            toolChoice: io.prism.network.ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> = flow {
            emit(StreamEvent.ReasoningDelta("这是推理过程"))
            emit(StreamEvent.Delta("最终答案"))
            emit(StreamEvent.Done)
        }
    }

    /** 无操作 Embedder（仅满足构造签名）。 */
    private class StubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384)
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
