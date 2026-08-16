package io.prism.ui.chat

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import io.objectbox.BoxStore
import io.prism.config.ThinkingConfigRepository
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
import io.prism.network.KnowledgeBaseLocalToolExecutor
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolDefinition
import io.prism.rag.RagTarget
import io.prism.security.FakePreferenceDataStore
import io.prism.skill.SkillExecutor
import io.prism.skill.SkillManifest
import io.prism.skill.SkillRegistry
import io.prism.skill.SkillToolDecl
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.ToolCallRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UXR6 真机 6 问题修复验收补充测试（主 Agent 基础用例）。
 *
 * 覆盖（纯 JVM，BR-testing-004）：
 * 1. **问题 3b（引用来源覆盖知识库工具）**：[ConversationViewModel.parseKnowledgeBaseCitations]
 *    从 knowledge_base__search TOOL 结果解析引用（含空格文件名 / 可选片段/相似度字段）。
 * 2. **问题 1（搜索失败纳入失败识别）**：[SkillExecutor.isFailureResult] 识别「搜索失败」前缀，
 *    使重复工具熔断可触发。
 * 3. **问题 2（每消息流式标记）**：流式期间 aiId 在 streamingIds 中、完成后移除，
 *    且工具回路中 Error 事件**不**提前清 streamingIds（Error 守卫 + finally 兜底）。
 * 4. **问题 3a（RAG 检索状态驱动指示）**：ragRetrieving 在 launchAnswer 期间短暂置位后复位。
 * 5. **问题 1（重复工具熔断常量）**：MAX_CONSECUTIVE_TOOL_FAILURES 存在且为 2。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelUxR6Test {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-uxr6-test-").toFile()
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
        repo.save(
            ProviderConfig(
                name = "OpenAI", baseUrl = "https://api.openai.com/v1",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
        )
        repo.setActive(repo.findByName("OpenAI")!!.id)
    }

    // ==================== 问题 3b：parseKnowledgeBaseCitations ====================

    @Test
    fun `parseKnowledgeBaseCitations extracts multiple citations with all fields`() {
        val content = """
            【知识库内容，来源为已上传的个人资料】
            [来源1] 文件=设计规范.md 片段=3 相似度=0.82
            [来源2] 文件=项目背景文档.txt 片段=1 相似度=0.66
            content snippet...
            【END 知识库内容】
        """.trimIndent()
        val citations = ConversationViewModel.parseKnowledgeBaseCitations(content)
        assertEquals("应解析出 2 条引用", 2, citations.size)
        assertEquals("设计规范.md", citations[0].documentTitle)
        assertEquals(3, citations[0].chunkIndex)
        assertEquals(0.82, citations[0].similarity, 0.001)
        assertEquals("项目背景文档.txt", citations[1].documentTitle)
        assertEquals(1, citations[1].chunkIndex)
        assertEquals(0.66, citations[1].similarity, 0.001)
    }

    @Test
    fun `parseKnowledgeBaseCitations handles filename with spaces`() {
        val content = "[来源1] 文件=我的学习笔记 2026.txt 相似度=0.75\ncontent"
        val citations = ConversationViewModel.parseKnowledgeBaseCitations(content)
        assertEquals(1, citations.size)
        assertEquals("我的学习笔记 2026.txt", citations[0].documentTitle)
        assertEquals(0.75, citations[0].similarity, 0.001)
        // 片段字段缺失 → null
        assertEquals(null, citations[0].chunkIndex)
    }

    @Test
    fun `parseKnowledgeBaseCitations returns empty for non-kb content`() {
        assertEquals(emptyList<Any>(), ConversationViewModel.parseKnowledgeBaseCitations(""))
        assertEquals(
            emptyList<Any>(),
            ConversationViewModel.parseKnowledgeBaseCitations("普通文本，无来源标记")
        )
    }

    // ==================== UXR7 问题 3：get_document_content 格式解析 ====================

    @Test
    fun `parseKnowledgeBaseCitations parses get_document_content format`() {
        // UXR7 问题 3：LLM 用 knowledge_base__get_document_content 读全文（【知识库文档：X】格式）
        val content = """
            【知识库文档：设计规范.md】
            这是设计规范的内容...
            【END】
        """.trimIndent()
        val citations = ConversationViewModel.parseKnowledgeBaseCitations(content)
        assertEquals("应解析出 1 篇文档引用", 1, citations.size)
        assertEquals("设计规范.md", citations[0].documentTitle)
    }

    @Test
    fun `parseKnowledgeBaseCitations parses multiple get_document_content documents`() {
        // UXR7 问题 3：LLM 并行读取两篇文档（真机日志 round=1 两个 get_document_content）
        val content = """
            【知识库文档：设计规范.md】
            content A
            【END】
            【知识库文档：项目背景文档.txt】
            content B
            【END】
        """.trimIndent()
        val citations = ConversationViewModel.parseKnowledgeBaseCitations(content)
        assertEquals("应解析出 2 篇文档引用", 2, citations.size)
        assertEquals("设计规范.md", citations[0].documentTitle)
        assertEquals("项目背景文档.txt", citations[1].documentTitle)
        // 去重：同文档重复读取只计一次
        val dup = content + "\n【知识库文档：设计规范.md】\nagain\n【END】"
        val dedup = ConversationViewModel.parseKnowledgeBaseCitations(dup)
        assertEquals("同文档重复读取应去重", 2, dedup.size)
    }

    @Test
    fun `parseKnowledgeBaseCitations merges search and get_document_content formats`() {
        val content = """
            【知识库内容，来源为已上传的个人资料】
            [来源1] 文件=文档A.txt 相似度=0.8
            【END 知识库内容】
            【知识库文档：文档B.md】
            content B
            【END】
        """.trimIndent()
        val citations = ConversationViewModel.parseKnowledgeBaseCitations(content)
        assertEquals("两种格式应合并解析", 2, citations.size)
        assertEquals("文档A.txt", citations[0].documentTitle)
        assertEquals("文档B.md", citations[1].documentTitle)
    }

    // ==================== UXR7-R2 问题 3：工具调用参数反向映射（引用池） ====================

    @Test
    fun `parseKnowledgeBaseCitationsFromToolCalls extracts document titles from args`() {
        // UXR7-R2：LLM 并行调用两次 get_document_content（真机日志 round=1 两个工具），
        // 从 assistant 占位消息的 toolCalls 参数反向提取引用（不依赖工具返回文本格式）
        val toolCalls = listOf(
            ToolCallRef(
                id = "call_1",
                functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
                arguments = """{"documentTitle": "设计规范.md"}"""
            ),
            ToolCallRef(
                id = "call_2",
                functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
                arguments = """{"documentTitle": "项目背景文档.txt", "knowledgeBaseId": 0}"""
            )
        )
        val citations = ConversationViewModel.parseKnowledgeBaseCitationsFromToolCalls(toolCalls)
        assertEquals("应解析出 2 篇文档引用", 2, citations.size)
        assertEquals("设计规范.md", citations[0].documentTitle)
        assertEquals("项目背景文档.txt", citations[1].documentTitle)
    }

    @Test
    fun `parseKnowledgeBaseCitationsFromToolCalls ignores non-kb tools and malformed args`() {
        // 白名单：非 get_document_content 工具不解析；arguments 缺失/非 JSON 跳过（不抛异常）
        val toolCalls = listOf(
            ToolCallRef(id = "a", functionName = "mcp_GitHub__search_repositories", arguments = """{"q":"prism"}"""),
            ToolCallRef(id = "b", functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT, arguments = "not-json"),
            ToolCallRef(id = "c", functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT, arguments = """{"other":"x"}"""),
            ToolCallRef(id = "d", functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT, arguments = """{"documentTitle": "有效.md"}""")
        )
        val citations = ConversationViewModel.parseKnowledgeBaseCitationsFromToolCalls(toolCalls)
        assertEquals("仅有效调用应解析出 1 篇", 1, citations.size)
        assertEquals("有效.md", citations[0].documentTitle)
        // 空列表 → 空结果
        assertTrue(ConversationViewModel.parseKnowledgeBaseCitationsFromToolCalls(emptyList()).isEmpty())
    }

    @Test
    fun `parseToolCallDocumentTitle extracts title tolerantly`() {
        assertEquals("昔涟介绍.md", ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "昔涟介绍.md"}"""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle(""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": ""}"""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("not-json"))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"other": 1}"""))
        // DEF-001（ac-verifier TKN-UXR7R2-ACCEPTANCE-001）：documentTitle 为 JSON null 或
        // 字面量 "null" 时不得产生假引用，应返回 null
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": null}"""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "null"}"""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "  null  "}"""))
    }

    @Test
    fun `successfulKbReadToolCallIds only includes successfully read documents`() {
        // MED-01（guardrail TKN-UXR7R2-GUARDRAIL-001）：仅收录**成功读取**的 get_document_content 调用。
        // 文档不存在时工具返回"知识库中未找到文档"（无【知识库文档：】标记）→ 不应计入引用池。
        val messages = listOf(
            ChatMessage(
                id = 1, role = Role.TOOL, content = "【知识库文档：设计规范.md】\n内容\n【END】",
                timestamp = 0, toolCallId = "call_ok_1", toolName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT
            ),
            ChatMessage(
                id = 2, role = Role.TOOL,
                content = "知识库中未找到文档「不存在的文档.md」",
                timestamp = 0, toolCallId = "call_fail_2", toolName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT
            )
        )
        val ids = ConversationViewModel.successfulKbReadToolCallIds(messages)
        assertEquals("仅成功读取的调用应入集合", setOf("call_ok_1"), ids)
        assertFalse("失败调用不应入集合", ids.contains("call_fail_2"))
        // 空列表 → 空集合
        assertTrue(ConversationViewModel.successfulKbReadToolCallIds(emptyList()).isEmpty())
    }

    @Test
    fun `successfulKbReadToolCallIds ignores other tool results`() {
        // 非 get_document_content 工具（如 search）即使内容含标记也不入集合（工具名白名单）
        val messages = listOf(
            ChatMessage(
                id = 1, role = Role.TOOL,
                content = "【知识库内容，来源为已上传的个人资料】\n[来源1] 文件=A.txt\n【END 知识库内容】",
                timestamp = 0, toolCallId = "call_search", toolName = KnowledgeBaseLocalToolExecutor.TOOL_SEARCH
            )
        )
        assertTrue(ConversationViewModel.successfulKbReadToolCallIds(messages).isEmpty())
    }

    // ==================== 问题 1：isFailureResult 识别「搜索失败」 ====================

    @Test
    fun `isFailureResult recognizes search failure prefix for circuit breaker`() {
        assertTrue(SkillExecutor.isFailureResult("搜索失败：未找到与「昔涟」相关的网页结果"))
        assertTrue(SkillExecutor.isFailureResult("搜索失败：联网搜索暂不可用，请基于已有信息回答"))
        assertFalse(SkillExecutor.isFailureResult("【网络搜索外部内容，未经验证】\n1. 昔涟 百科"))
    }

    @Test
    fun `isFailureResult recognizes fetch failure prefix for circuit breaker`() {
        // UXR7 问题 1（新发现）：Fetch 工具失败文案此前不在前缀列表，导致 LLM 反复用
        // Fetch 抓取直至 maxRounds。纳入后重复抓取失败可触发熔断。
        assertTrue(SkillExecutor.isFailureResult("抓取失败：网络错误或目标站点不可达"))
        assertTrue(SkillExecutor.isFailureResult("Fetch 工具不可用：未配置"))
    }

    @Test
    fun `circuit breaker constant is 2 consecutive failures`() {
        assertEquals(2, SkillExecutor.MAX_CONSECUTIVE_TOOL_FAILURES)
    }

    @Test
    fun `executeLoop circuit breaker empties tools after consecutive failures and gives answer`() =
        runTest(mainDispatcher) {
            // guardrail Medium-2（TKN-UXR6-GUARDRAIL-001）：真实 executeLoop 循环级集成测试。
            // 场景：web_search 连续 2 次返回失败（"搜索失败"前缀）→ 熔断置空 tools →
            // 第 3 轮 LLM 无工具可用 → 纯文本回答（Delta + Done）→ 无 maxRounds Error。
            val config = ProviderConfig(
                name = "OpenAI", baseUrl = "https://api.openai.com/v1",
                apiKeyRef = "openai", models = listOf("gpt-4o")
            )
            val chatProvider = CircuitBreakerChatProvider()
            val mcpProvider = object : McpToolProvider {
                override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
                override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String =
                    "搜索失败：联网搜索暂不可用，请基于已有信息回答"
            }
            val executor = SkillExecutor(
                mcpToolProvider = mcpProvider,
                confirmationGate = NoOpConfirmationGate,
                ioDispatcher = Dispatchers.Unconfined,
                approvalModeProvider = { io.prism.config.ToolApprovalMode.AUTO }
            )
            val events = mutableListOf<StreamEvent>()
            val result = executor.executeLoop(
                provider = chatProvider,
                config = config,
                messages = listOf(
                    ChatMessage(1L, Role.USER, "搜索昔涟", System.currentTimeMillis())
                ),
                systemPrompt = null,
                ragContext = null,
                tools = listOf(
                    ToolDefinition(
                        function = ToolDefinition.FunctionDef(
                            name = "web_search__search",
                            description = "search",
                            parameters = buildJsonObject { }
                        )
                    )
                ),
                mcpServers = listOf(
                    McpServerConfig(name = "search", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true)
                ),
                maxRounds = 10,
                onEvent = { events.add(it) }
            )

            // 第 1 轮 + 第 2 轮：LLM 调用工具（失败）→ 熔断
            // 第 3 轮：tools 为空，LLM 纯文本回答 → 回路自然结束
            assertEquals("应经历 3 轮（2 轮工具 + 1 轮纯文本）", 3, chatProvider.callCount)
            assertTrue(
                "第 3 轮应传入空 tools（熔断生效）",
                chatProvider.calls[2].tools.isNullOrEmpty()
            )
            assertTrue("应注入不要再调用工具的提示", chatProvider.calls[2].systemPrompt.orEmpty().contains("不要再调用"))
            assertFalse(
                "熔断后不应发射 maxRounds Error（用户应得到答案而非报错）",
                events.any { it is StreamEvent.Error && it.message.contains("循环达上限") }
            )
            assertTrue(
                "熔断后第 3 轮应产出最终文本回答（Delta 事件，即用户可见答案）",
                events.any { it is StreamEvent.Delta && it.content.contains("根据已有信息回答") }
            )
            // executeLoop 返回的是协议消息（assistant 占位 + tool result），最终文本经 onEvent(Delta)
            // 由 ConversationViewModel appendDelta 累积到 aiId（不在 executeLoop 返回值中）
            assertTrue(
                "返回消息应包含 web_search tool result（失败文案）",
                result.any { it.role == Role.TOOL && it.content.contains("搜索失败") }
            )
        }

    // ==================== 问题 2：每消息流式标记（streamingIds 状态机） ====================

    @Test
    fun `streamingIds marks ai message streaming during stream and removes on done`() =
        runTest(mainDispatcher) {
            saveActiveProvider()
            // 普通流式：只发 Delta 后 Done（无工具回路 → !toolLoopActive 分支清理）
            val provider = RecordingChatStreamProvider(
                listOf(
                    StreamEvent.Delta("你好"),
                    StreamEvent.Delta("，世界"),
                    StreamEvent.Done
                )
            )
            val vm = ConversationViewModel(
                providerRepository = ProviderConfigRepository(boxStore),
                provider = provider,
                embedder = StubEmbedder(),
                knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
                skillExecutor = null,
                ioDispatcher = mainDispatcher
            ).apply { setRagTarget(RagTarget.Off) }

            vm.sendMessage("hi")
            advanceUntilIdle()

            assertTrue(
                "回答完成后 aiId 不应再处于流式标记中",
                vm.streamingIds.value.isEmpty()
            )
            val aiMessage = vm.messages.value.lastOrNull { it.role == Role.ASSISTANT }
            assertTrue("AI 消息应存在", aiMessage != null)
            assertTrue("最终内容应完整", (aiMessage?.content ?: "").contains("你好，世界"))
        }

    @Test
    fun `streamingIds stays during tool loop and clears in finally`() = runTest(mainDispatcher) {
        saveActiveProvider()
        // 工具回路：注入带工具的 Skill → tools 非空 → executeWithToolLoop 分支。
        // FakeSkillExecutor 在回路内触发 ToolCallComplete + Error（此前 Error 无条件清
        // isTyping 破坏 isStreaming 的路径）。修复后 Error 在工具回路中不提前清
        // streamingIds，由 executeWithToolLoop finally 统一标记完成。
        val executor = FakeSkillExecutor(
            emitEvents = listOf(
                StreamEvent.ToolCallComplete("call_1", "web_search__search", emptyMap()),
                StreamEvent.Error("搜索失败：联网搜索暂不可用")
            )
        )
        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = RecordingChatStreamProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(
                listOf(
                    makeSkillEntry(
                        name = "translator",
                        tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
                    )
                )
            ),
            skillExecutor = executor,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("search 昔涟")
        advanceUntilIdle()

        assertTrue(
            "工具回路结束后 streamingIds 应被 finally 清空（不残留流式标记）",
            vm.streamingIds.value.isEmpty()
        )
    }

    // ==================== 问题 3a：ragRetrieving 状态 ====================

    @Test
    fun `ragRetrieving toggles during launchAnswer`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = RecordingChatStreamProvider(listOf(StreamEvent.Delta("ok"), StreamEvent.Done)),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillExecutor = null,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.AllLibraries) } // RAG 开启

        vm.sendMessage("你好") // 寒暄也应走检索流程（buildRagPlan）
        advanceUntilIdle()

        assertFalse(
            "回答完成后 ragRetrieving 应复位为 false（不残留「正在检索知识库」指示）",
            vm.ragRetrieving.value
        )
    }

    // ==================== Fakes（复用既有测试基建） ====================

    private class RecordingChatStreamProvider(
        private val events: List<StreamEvent>
    ) : ChatStreamProvider {
        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: io.prism.network.ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> = flow {
            events.forEach { emit(it) }
        }
    }

    /** 熔断测试用：前 2 次调用返回 ToolCallComplete（触发工具执行），第 3 次返回纯文本。 */
    private class CircuitBreakerChatProvider : ChatStreamProvider {
        data class Call(val tools: List<ToolDefinition>?, val systemPrompt: String?)
        val calls = mutableListOf<Call>()
        val callCount: Int get() = calls.size

        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: io.prism.network.ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> {
            calls.add(Call(tools = tools, systemPrompt = systemPrompt))
            return flow {
                when (calls.size) {
                    // 前 2 轮：LLM 反复调用 web_search（结果失败 → 熔断）
                    1, 2 -> emit(StreamEvent.ToolCallComplete("call_${calls.size}", "web_search__search", emptyMap()))
                    // 第 3 轮（熔断后 tools 空）：LLM 纯文本回答
                    else -> {
                        emit(StreamEvent.Delta("根据已有信息回答：暂未找到「昔涟」的搜索结果"))
                        emit(StreamEvent.Done)
                    }
                }
            }
        }
    }

    /** 无操作 [McpToolProvider]（仅供 FakeSkillExecutor 父类构造用）。 */
    private object NoOpMcpToolProvider : McpToolProvider {
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String = ""
    }

    /** 无操作 [ToolConfirmationGate]（仅供 FakeSkillExecutor 父类构造用）。 */
    private object NoOpConfirmationGate : ToolConfirmationGate {
        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = true
    }

    /** 覆写 executeLoop 触发事件（模拟工具回路 + Error），供 streamingIds/Error 守卫测试。 */
    private class FakeSkillExecutor(
        private val emitEvents: List<StreamEvent>
    ) : SkillExecutor(
        mcpToolProvider = NoOpMcpToolProvider,
        confirmationGate = NoOpConfirmationGate,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        override suspend fun executeLoop(
            provider: ChatStreamProvider,
            config: ProviderConfig,
            messages: List<ChatMessage>,
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
        ): List<ChatMessage> {
            emitEvents.forEach { onEvent(it) }
            return messages
        }
    }

    private class StubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }

    /** 构造带工具的 Skill 条目（触发 executeWithToolLoop 分支）。 */
    private fun makeSkillEntry(
        name: String,
        tools: List<SkillToolDecl>
    ): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
        config = SkillConfig(
            id = 0L,
            name = name,
            displayName = name,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/skills/$name",
            isEnabled = true,
            isInstalled = true,
            version = "1.0.0"
        ),
        manifest = SkillManifest(
            name = name,
            description = "Test skill $name",
            tools = tools,
            body = "test body"
        )
    )

    /** Stub [SkillRegistry] —— 覆写 [enabledSkills] 返回 canned 列表。 */
    private class StubSkillRegistry(
        private val stubSkills: List<SkillRegistry.SkillEntry>
    ) : SkillRegistry(
        context = android.app.Application(),
        skillRepository = io.prism.data.SkillRepository(
            MyObjectBox.builder().directory(
                kotlin.io.path.createTempDirectory(prefix = "stub-sr-").toFile()
            ).build()
        ),
        ioDispatcher = Dispatchers.Unconfined
    ) {
        override fun enabledSkills(): List<SkillEntry> = stubSkills
    }
}
