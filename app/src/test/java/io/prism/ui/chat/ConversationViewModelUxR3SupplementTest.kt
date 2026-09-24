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
import io.prism.rag.RagTarget
import io.prism.ui.model.ChatMessage
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 编辑重发补充测试（ac-verifier，TKN-UXR3-ACCEPTANCE-001 极端场景补充）。
 *
 * 主 Agent 基础用例（编辑重发含 toolCalls 历史）仅在知识库关闭（`RagTarget.Off`）时验证。
 * 本测试验证 **知识库开启时编辑重发的行为**（**ADR-050 更新后的契约**）：
 * - 编辑重发**同样不注入知识库正文**（`ragContext` 恒 null，自动检索链路已撤除）
 * - 编辑后的新 AI 消息**不由客户端预填**引用来源（`sources` 仅来自工具结果解析）
 * - 编辑后的历史（不含过期 toolCalls）作为请求历史，且历史中不得出现知识库正文
 *
 * 变更说明：本 KDoc 原称"编辑重发会重新触发 RAG 检索（launchAnswer 内 buildRagPlan 每轮执行）
 * / 第二轮 ragContext 非空"——与同文件断言（现为 null）矛盾（guardrail R2-N2 遗留）；随 ADR-050
 * 撤除自动注入，该描述与符号一并作废，故按事实改写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelUxR3SupplementTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-uxr3-supp-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun `edit resend with RAG enabled never injects kb content in either round`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val kbRepo = KnowledgeBaseRepository(boxStore)
        // chunk embedding 与 query embedding（全 0.5）一致 → 修复前 similarity=1.0 必注入
        kbRepo.addChunk(
            KnowledgeChunk(
                title = "工作.md#1",
                content = "季度总结的重点内容。",
                embedding = FloatArray(384) { 0.5f },
                knowledgeBaseId = 0L
            )
        )

        val provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("基于知识库回复"), StreamEvent.Done))
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = DefaultStubEmbedder,
            knowledgeBaseRepository = kbRepo,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) }

        // 第一轮：知识库开启 → 仅能力声明，无正文注入
        vm.sendMessage("总结一下")
        advanceUntilIdle()
        assertEquals("第一轮应产生 1 次请求", 1, provider.receivedRagContexts.size)
        assertNull("ADR-050：第一轮不得注入 ragContext", provider.receivedRagContexts[0])
        val aiAfterRound1 = vm.messages.value.last { it.role == Role.ASSISTANT }
        assertTrue("第一轮不应由客户端预填引用来源", aiAfterRound1.sources.isEmpty())

        // 编辑用户消息重新发送（知识库仍开启）
        val userMsg = vm.messages.value.first { it.role == Role.USER }
        vm.editUserMessageAndResend(userMsg.id, "重新总结季度要点")
        advanceUntilIdle()

        // 编辑后第二轮同样不得注入
        assertEquals("第二轮也应产生请求", 2, provider.receivedRagContexts.size)
        assertNull("ADR-050：第二轮同样不得注入 ragContext", provider.receivedRagContexts[1])
        // 第二轮请求历史不应含过期 assistant 占位/tool result（编辑截断后只有编辑后的 user 消息）
        val secondHistory = provider.receivedMessages.last()
        assertTrue("第二轮历史不应含 TOOL 消息", secondHistory.none { it.role == Role.TOOL })
        assertTrue("第二轮历史应含编辑后的内容", secondHistory.any { it.role == Role.USER && it.content == "重新总结季度要点" })
        assertTrue(
            "第二轮历史不得含知识库正文",
            secondHistory.none { it.content.contains("季度总结的重点内容") }
        )
        val aiAfterEdit = vm.messages.value.last { it.role == Role.ASSISTANT }
        assertTrue("编辑后新 AI 消息也不应有客户端预填引用", aiAfterEdit.sources.isEmpty())
        assertTrue("编辑后新 AI 消息应含回复", aiAfterEdit.content.contains("基于知识库回复"))
    }

    // ==================== 测试基础设施（与既有测试对齐） ====================

    /** 记录每次请求参数（含 ragContext / systemPrompt / messages）。 */
    private class RecordingChatStreamProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
        val receivedMessages = mutableListOf<List<ChatMessage>>()
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
            receivedRagContexts += ragContext
            return flow { events.forEach { emit(it) } }
        }
    }

    /** Stub Embedder —— 返回固定 384 维向量（与 chunk embedding 一致 → similarity=1.0）。 */
    private object DefaultStubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
