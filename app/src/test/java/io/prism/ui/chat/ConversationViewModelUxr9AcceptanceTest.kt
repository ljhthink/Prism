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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UXR9 验收补充测试（ac-verifier，TKN-UXR9-ACCEPTANCE-001）。
 *
 * 覆盖主 Agent 测试未覆盖的验收盲区：
 *
 * **US-901 AC-4**：`RagContextBuilder.buildContext` 限制注入片段条数（top-2）——
 * 既有 [ConversationViewModelTest] 仅验证 2 条 chunk 全注入（≤2 自然满足），未验证
 * 3+ 条相关 chunk 时**仅注入 top-2**（[来源1]/[来源2]，不出现 [来源3]）。
 *
 * **US-903 AC-4**：图片编码失败提示走系统消息通道、**绝不触发 launchAnswer（LLM 不被调用）**——
 * 既有测试仅验证提示显示，未断言 provider 未被调用；且未验证系统提示被请求历史过滤排除。
 *
 * **US-907 AC-5**：文档解析失败提示同语义（notifyDocumentError 不触发 LLM、isSystemNotice 排除）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelUxr9AcceptanceTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "uxr9-acc-").toFile()
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

    // ==================== US-901 AC-4：buildContext 注入片段 top-2 上限 ====================

    @Test
    fun `rag injection caps at top-2 when 3 chunks pass threshold`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val kbRepo = KnowledgeBaseRepository(boxStore)
        // 3 条 chunk 与 stub embedder 向量完全一致（相似度 1.0，全部通过 0.5 阈值）
        repeat(3) { i ->
            kbRepo.addChunk(KnowledgeChunk(
                title = "文档$i.md#1",
                content = "相关片段内容$i",
                embedding = FloatArray(384) { 0.5f },
                knowledgeBaseId = 0L
            ))
        }
        val provider = RecordingProvider(listOf(StreamEvent.Delta("基于知识库回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        vm.sendMessage("查询相关内容")
        advanceUntilIdle()

        val ragCtx = provider.receivedRagContexts.single()
        assertTrue("应注入 ragContext", ragCtx != null)
        assertTrue("应含来源1", ragCtx!!.contains("[来源1]"))
        assertTrue("应含来源2", ragCtx.contains("[来源2]"))
        assertFalse("top-2 上限：不应出现 [来源3]", ragCtx.contains("[来源3]"))
        // citations 同样 ≤2（编号与 context 对齐）
        val aiMsg = vm.messages.value[1]
        assertTrue("AI 消息应附引用来源", aiMsg.sources.isNotEmpty())
        assertTrue("citations 应 ≤ 2（top-2 上限）", aiMsg.sources.size <= 2)
        assertTrue("systemPrompt 应含 RAG grounding rules",
            provider.receivedSystemPrompts.single()!!.contains(RagContextBuilder.SYSTEM_PROMPT))
    }

    // ==================== US-903 AC-4：图片编码失败不触发 LLM ====================

    @Test
    fun `notifyEncodingFailure does not call LLM and shows system notice`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = RecordingProvider(listOf(StreamEvent.Delta("不应发生"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.notifyEncodingFailure() // 非回复期
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        assertTrue("应有编码失败提示", userMsgs.any { it.content.contains("图片编码失败") })
        val notice = userMsgs.first { it.content.contains("图片编码失败") }
        assertTrue("提示应标记 isSystemNotice", notice.isSystemNotice)
        assertEquals("提示绝不触发 LLM 调用（streamChat 应为 0 次）", 0, provider.streamCallCount)
        assertFalse("不应产生 AI 回复消息", vm.messages.value.any { it.role == Role.ASSISTANT && it.content.isNotBlank() })
    }

    @Test
    fun `encoding failure notice is excluded from request history`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = RecordingProvider(listOf(StreamEvent.Delta("正常回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 先产生一条编码失败提示（isSystemNotice=true），再发送真实问题
        vm.notifyEncodingFailure()
        vm.sendMessage("请帮我总结文档")
        advanceUntilIdle()

        assertEquals("真实问题应触发 1 次 LLM 调用", 1, provider.streamCallCount)
        val sentMessages = provider.receivedMessages.single()
        assertFalse(
            "系统提示不得进入请求历史（isSystemNotice 过滤）",
            sentMessages.any { it.isSystemNotice || it.content.contains("图片编码失败") }
        )
        assertTrue("真实问题应在请求历史中", sentMessages.any { it.content == "请帮我总结文档" })
    }

    // ==================== US-907 AC-5：文档解析失败不触发 LLM ====================

    @Test
    fun `notifyDocumentError does not call LLM and shows system notice`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = RecordingProvider(listOf(StreamEvent.Delta("不应发生"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.notifyDocumentError()
        advanceUntilIdle()

        val userMsgs = vm.messages.value.filter { it.role == Role.USER }
        assertTrue("应有文档解析失败提示", userMsgs.any { it.content.contains("文档解析失败") })
        val notice = userMsgs.first { it.content.contains("文档解析失败") }
        assertTrue("提示应标记 isSystemNotice", notice.isSystemNotice)
        assertEquals("文档失败提示绝不触发 LLM 调用", 0, provider.streamCallCount)
    }

    @Test
    fun `document error notice is excluded from request history`() = runTest(mainDispatcher) {
        val repo = providerRepo()
        val provider = RecordingProvider(listOf(StreamEvent.Delta("正常回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            repo, provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.notifyDocumentError()
        vm.sendMessage("分析这个 PPT 的内容")
        advanceUntilIdle()

        assertEquals("真实问题应触发 1 次 LLM 调用", 1, provider.streamCallCount)
        val sentMessages = provider.receivedMessages.single()
        assertFalse(
            "文档失败提示不得进入请求历史",
            sentMessages.any { it.isSystemNotice || it.content.contains("文档解析失败") }
        )
        assertTrue("真实问题应在请求历史中", sentMessages.any { it.content == "分析这个 PPT 的内容" })
    }

    // ==================== Fakes ====================

    /** 记录每次 streamChat 调用与消息/上下文，并统计调用次数（可观测性断言）。 */
    private class RecordingProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
        val receivedMessages = mutableListOf<List<io.prism.ui.model.ChatMessage>>()
        val receivedSystemPrompts = mutableListOf<String?>()
        val receivedRagContexts = mutableListOf<String?>()
        var streamCallCount = 0
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
            streamCallCount++
            receivedMessages += messages
            receivedSystemPrompts += systemPrompt
            receivedRagContexts += ragContext
            return flow { events.forEach { emit(it) } }
        }
    }

    /** 返回固定 384 维向量（与 chunk embedding 一致，相似度 1.0）。 */
    private class DefaultStubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
