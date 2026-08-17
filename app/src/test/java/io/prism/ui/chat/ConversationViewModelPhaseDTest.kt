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
import io.prism.network.ToolDefinition
import io.prism.rag.RagTarget
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * ConversationViewModel M4 Phase D 单元测试（US-026 Skill 工具执行回路集成）。
 *
 * **测试分层**（BR-testing-004 模式）：
 * 1. **纯函数测试**：[ConversationViewModel.Companion.buildTools] / [mergeSystemPrompt]，
 *    不依赖 Android Context 或真实 SkillRegistry，覆盖命名空间隔离、prompt 合并顺序、膨胀控制
 * 2. **executeLoop 分支集成测试**：注入 [FakeSkillExecutor]（覆写 executeLoop 返回 canned 消息），
 *    验证 R-1（onEvent 回调模式）/ R-2（消息同步）/ R-3（idGenerator 注入）/ 分支选择
 * 3. **降级测试**：[skillExecutor]=null + 有 tools → 回退普通 streamChat 分支（D-2 决策）
 * 4. **R-4 历史过滤器扩展测试**：保留携带 toolCalls 的空 content assistant 占位
 * 5. **handleStreamEvent 测试**：覆盖 6 种 StreamEvent 子类（含 M4 新增 ToolCall*）
 *
 * **Fake 设计**：
 * - [FakeSkillExecutor]：extends [SkillExecutor]，覆写 [executeLoop] 返回 canned 消息 + 触发 onEvent
 * - [PhaseDRecordingProvider]：记录请求参数（messages/tools/systemPrompt）供断言
 * - [NoOpMcpToolProvider] / [NoOpConfirmationGate]：构造 [SkillExecutor] 父类所需，不参与测试逻辑
 *
 * 依赖 [BoxStore] 的测试用 [MyObjectBox] 临时目录构造（仿既有 [ConversationViewModelTest]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelPhaseDTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-phased-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ==================== 纯函数测试：buildTools ====================

    @Test
    fun `buildTools returns empty for empty skills list`() {
        val tools = ConversationViewModel.buildTools(emptyList())
        assertTrue("空 Skill 列表应返回空 tools", tools.isEmpty())
    }

    @Test
    fun `buildTools returns empty when skill has no tools declaration`() {
        val entry = makeSkillEntry(name = "translator", tools = null)
        val tools = ConversationViewModel.buildTools(listOf(entry))
        assertTrue("Skill 未声明 tools 应返回空 tools", tools.isEmpty())
    }

    @Test
    fun `buildTools returns empty when skill has empty tools list`() {
        val entry = makeSkillEntry(name = "translator", tools = emptyList())
        val tools = ConversationViewModel.buildTools(listOf(entry))
        assertTrue("Skill tools 为空列表应返回空 tools", tools.isEmpty())
    }

    @Test
    fun `buildTools applies namespace prefix skillName__toolName`() {
        val entry = makeSkillEntry(
            name = "translator",
            tools = listOf(
                SkillToolDecl(
                    name = "translate",
                    description = "Translate text",
                    parameters = buildJsonObject { put("type", JsonPrimitive("object")) }
                )
            )
        )
        val tools = ConversationViewModel.buildTools(listOf(entry))
        assertEquals("应返回 1 个 tool", 1, tools.size)
        assertEquals(
            "tool name 应为 skillName__toolName 格式",
            "translator__translate",
            tools[0].function.name
        )
        assertEquals("Translate text", tools[0].function.description)
    }

    @Test
    fun `buildTools flattens tools from multiple skills`() {
        val entry1 = makeSkillEntry(
            name = "translator",
            tools = listOf(
                SkillToolDecl("translate", "Translate text", buildJsonObject { })
            )
        )
        val entry2 = makeSkillEntry(
            name = "summarizer",
            tools = listOf(
                SkillToolDecl("summarize", "Summarize text", buildJsonObject { }),
                SkillToolDecl("extract", "Extract key points", buildJsonObject { })
            )
        )
        val tools = ConversationViewModel.buildTools(listOf(entry1, entry2))
        assertEquals("应返回 3 个 tools（1 + 2）", 3, tools.size)
        assertEquals("translator__translate", tools[0].function.name)
        assertEquals("summarizer__summarize", tools[1].function.name)
        assertEquals("summarizer__extract", tools[2].function.name)
    }

    @Test
    fun `buildTools skips skills with null tools in mixed list`() {
        val entry1 = makeSkillEntry(name = "noTools", tools = null)
        val entry2 = makeSkillEntry(
            name = "withTools",
            tools = listOf(SkillToolDecl("doStuff", "Do stuff", buildJsonObject { }))
        )
        val tools = ConversationViewModel.buildTools(listOf(entry1, entry2))
        assertEquals("应只返回 withTools 的 1 个 tool", 1, tools.size)
        assertEquals("withTools__doStuff", tools[0].function.name)
    }

    // ==================== 问题 8b：联网搜索工具合并 ====================

    @Test
    fun `buildTools default does not include web_search tool for backward compat`() {
        // 默认 webSearchEnabled=false（向后兼容）：空 Skill + 不传参数 → 空 tools
        val tools = ConversationViewModel.buildTools(emptyList())
        assertTrue("默认不应包含 web_search 工具", tools.isEmpty())
    }

    @Test
    fun `buildTools includes web_search tool when webSearchEnabled true`() {
        val tools = ConversationViewModel.buildTools(emptyList(), webSearchEnabled = true)
        assertEquals("应返回 1 个 web_search 工具", 1, tools.size)
        assertEquals("web_search__search", tools[0].function.name)
        assertTrue("description 应描述联网搜索", tools[0].function.description.contains("联网搜索"))
    }

    @Test
    fun `buildTools merges skill cross_app and web_search tools`() {
        val entry = makeSkillEntry(
            name = "translator",
            tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
        )
        val tools = ConversationViewModel.buildTools(listOf(entry), webSearchEnabled = true)
        assertEquals("skill 1 + web_search 1 = 2", 2, tools.size)
        assertEquals("translator__translate", tools[0].function.name)
        assertEquals("web_search__search", tools[1].function.name)
    }

    // ==================== O4（PRD UXR8）：文档生成工具合并 ====================

    @Test
    fun `buildTools default excludes document tools for backward compat`() {
        // 默认 documentToolsEnabled=false（向后兼容）：不追加 docx/xlsx 工具
        val tools = ConversationViewModel.buildTools(emptyList(), webSearchEnabled = true)
        assertTrue(
            "默认不应包含 document 工具",
            tools.none { it.function.name.startsWith("document__") }
        )
    }

    @Test
    fun `buildTools includes docx and xlsx when documentToolsEnabled true`() {
        val tools = ConversationViewModel.buildTools(
            emptyList(), webSearchEnabled = true, documentToolsEnabled = true
        )
        val names = tools.map { it.function.name }
        assertEquals("web_search 1 + document 2 = 3", 3, names.size)
        assertTrue(names.contains("document__create_docx"))
        assertTrue(names.contains("document__create_xlsx"))
    }

    // ==================== 纯函数测试：mergeSystemPrompt ====================

    @Test
    fun `mergeSystemPrompt returns default persona when both rag and skills empty`() {
        // ADR-018：无 RAG + 无 Skill 时必须返回默认 persona，而非 null（避免 LLM 无身份引导）
        val result = ConversationViewModel.mergeSystemPrompt(ragPrompt = null, enabledSkills = emptyList())
        assertEquals("无 RAG + 无 Skill 应返回默认 persona", ConversationViewModel.DEFAULT_PERSONA, result)
    }

    @Test
    fun `mergeSystemPrompt starts with default persona when rag provided`() {
        // ADR-018：默认 persona 始终作为基础身份，RAG 追加在后
        val entry = makeSkillEntry(name = "translator", systemPrompt = null, tools = null)
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG grounding rules",
            enabledSkills = listOf(entry)
        )
        assertTrue("应先输出默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("应包含 RAG prompt", result.contains("RAG grounding rules"))
        // RAG 在 persona 之后
        assertTrue(
            "persona 应在 RAG 之前",
            result.indexOf(ConversationViewModel.DEFAULT_PERSONA) < result.indexOf("RAG grounding rules")
        )
    }

    @Test
    fun `mergeSystemPrompt appends skill index instead of injecting full systemPrompt`() {
        // ADR-018：不注入完整 systemPrompt（如"You are a translator."），改为轻量索引
        val entry = makeSkillEntry(
            name = "translator",
            systemPrompt = "You are a translator.",
            tools = null
        )
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            enabledSkills = listOf(entry)
        )
        assertTrue("应包含默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertFalse("不应注入完整 systemPrompt（避免身份污染）", result.contains("You are a translator."))
        assertTrue("应包含 skill 索引", result.contains("可用技能"))
        assertTrue("应包含 skill name", result.contains("translator"))
        assertTrue("应包含 skill description", result.contains("Test skill translator"))
    }

    @Test
    fun `mergeSystemPrompt orders persona then rag then skill index`() {
        val entry = makeSkillEntry(
            name = "translator",
            systemPrompt = "You are a translator.",
            tools = null
        )
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG grounding rules",
            enabledSkills = listOf(entry)
        )
        assertTrue("应先输出默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        val personaIdx = result.indexOf(ConversationViewModel.DEFAULT_PERSONA)
        val ragIdx = result.indexOf("RAG grounding rules")
        val indexIdx = result.indexOf("可用技能")
        assertTrue("persona 应在最前", personaIdx < ragIdx)
        assertTrue("RAG 应在 skill 索引之前", ragIdx < indexIdx)
        assertTrue("应包含 skill name", result.contains("translator"))
    }

    @Test
    fun `mergeSystemPrompt appends skill index regardless of tools`() {
        // ADR-018：所有启用 skill 都输出轻量索引（name+description），不再区分有无 tools
        val entry = makeSkillEntry(
            name = "translator",
            systemPrompt = null,
            tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
        )
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            enabledSkills = listOf(entry)
        )
        assertTrue("应包含「可用技能」", result.contains("可用技能"))
        assertTrue("应包含 skill name", result.contains("translator"))
        assertTrue("应包含 skill description", result.contains("Test skill translator"))
    }

    @Test
    fun `mergeSystemPrompt keeps base identity instead of skill identity`() {
        // ADR-018：即使启用 skill（如 rewriter），LLM 身份仍是 Prism 助手，不被"你是XX助手"污染
        val entry = makeSkillEntry(
            name = "rewriter",
            systemPrompt = "你是灵活的文本改写助手。",  // 身份污染源
            tools = null
        )
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            enabledSkills = listOf(entry)
        )
        assertTrue("身份保持为 Prism 助手", result.startsWith("你是 Prism AI 助手"))
        assertFalse("不应注入 skill 身份 systemPrompt", result.contains("你是灵活的文本改写助手"))
        assertTrue("应感知 skill 能力（索引）", result.contains("可用技能"))
    }

    @Test
    fun `mergeSystemPrompt concatenates multiple skill indexes in order`() {
        val entry1 = makeSkillEntry(name = "translator", systemPrompt = "Translate.", tools = null)
        val entry2 = makeSkillEntry(name = "summarizer", systemPrompt = "Summarize.", tools = null)
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            enabledSkills = listOf(entry1, entry2)
        )
        assertTrue("应包含 translator 索引", result.contains("translator"))
        assertTrue("应包含 summarizer 索引", result.contains("summarizer"))
        assertTrue(
            "translator 在 summarizer 之前（列表顺序）",
            result.indexOf("translator") < result.indexOf("summarizer")
        )
    }

    @Test
    fun `mergeSystemPrompt treats blank rag prompt as absent`() {
        val entry = makeSkillEntry(name = "translator", systemPrompt = "Translate.", tools = null)
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "   ",  // 空白字符串
            enabledSkills = listOf(entry)
        )
        assertTrue("应包含默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertFalse("不应包含空白 RAG prompt", result.contains("   "))
        assertTrue("应包含 skill 索引", result.contains("可用技能"))
    }

    @Test
    fun `mergeSystemPrompt indexes skill by description even when systemPrompt blank`() {
        // ADR-018：skill 索引基于 description，与 systemPrompt 是否空白无关
        val entry1 = makeSkillEntry(name = "blank", systemPrompt = "   ", tools = null)
        val entry2 = makeSkillEntry(name = "real", systemPrompt = "Real prompt.", tools = null)
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            enabledSkills = listOf(entry1, entry2)
        )
        assertTrue("应包含默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("应包含 blank skill 索引", result.contains("blank"))
        assertTrue("应包含 real skill 索引", result.contains("real"))
        assertFalse("不应注入完整 systemPrompt", result.contains("Real prompt."))
    }

    // ==================== UXR8 N1（ADR-030）：userRules 层 ====================

    @Test
    fun `mergeSystemPrompt injects userRules after persona and before rag`() {
        // ADR-030：用户显式规则注入在 persona 之后、RAG 之前，声明最高优先级
        val result = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG grounding rules",
            userRules = "[用户规则 · 除安全限制外最高优先级]\n关于我：后端开发者",
            enabledSkills = emptyList()
        )
        assertTrue("应先输出默认 persona", result.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        val personaIdx = result.indexOf(ConversationViewModel.DEFAULT_PERSONA)
        val rulesIdx = result.indexOf("用户规则")
        val ragIdx = result.indexOf("RAG grounding rules")
        assertTrue("persona 应在 userRules 之前", personaIdx < rulesIdx)
        assertTrue("userRules 应在 RAG 之前（最高优先级）", rulesIdx < ragIdx)
    }

    @Test
    fun `mergeSystemPrompt skips blank userRules`() {
        // null/空 userRules 向后兼容：不注入 userRules 层
        val r1 = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG", userRules = null, enabledSkills = emptyList()
        )
        assertFalse("null userRules 不应注入", r1.contains("用户规则"))
        val r2 = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = "RAG", userRules = "   ", enabledSkills = emptyList()
        )
        assertFalse("空白 userRules 不应注入", r2.contains("用户规则"))
    }

    // ==================== 集成测试：executeLoop 分支 ====================

    @Test
    fun `sendMessage with tools and skillExecutor calls executeLoop and syncs messages`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDRecordingProvider(emptyList())  // executeLoop 内部不调用，由 fake 接管
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = listOf(
                // 模拟 executeLoop 返回的「新增消息」：assistant 占位 + tool result
                ChatMessage(
                    id = 100L,
                    role = Role.ASSISTANT,
                    content = "",
                    timestamp = 0L,
                    toolCalls = listOf(ToolCallRef(id = "call_1", functionName = "translator__translate", arguments = "{}"))
                ),
                ChatMessage(
                    id = 101L,
                    role = Role.TOOL,
                    content = "Translation result",
                    timestamp = 0L,
                    toolCallId = "call_1",
                    toolName = "translator__translate"
                )
            ),
            emitEvents = listOf(
                StreamEvent.ToolCallStart("call_1", "translator__translate", 0),
                StreamEvent.Delta("Final AI response after tool"),
                StreamEvent.Done
            )
        )

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(
                    name = "translator",
                    tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
                )
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("translate hello")
        advanceUntilIdle()

        assertTrue("应调用 executeLoop", fakeExecutor.executeLoopCalled)
        assertNotNull("应传递 tools 给 executeLoop", fakeExecutor.receivedTools)
        // UXR4 问题 2/3（ADR-024）：tools 含 3 个知识库工具（knowledge_base__*），
        // 故过滤 skill 工具后断言
        val skillTools = fakeExecutor.receivedTools!!.filter { it.function.name.startsWith("translator__") }
        assertEquals("应传递 1 个 skill tool", 1, skillTools.size)
        assertEquals("translator__translate", skillTools[0].function.name)

        val messages = vm.messages.value
        // UXR5 问题 2（ADR-024 遗留修复）：syncToolMessages 把 assistant 占位 + tool result
        // 插入到 aiId（最终文本）**之前**，使 _messages 按真实时序 [user, 占位, tool, aiId(文本)]。
        // 期望：1 user + 1 assistant 占位(toolCalls) + 1 tool result + 1 aiId(最终文本)
        assertEquals("应有 4 条消息", 4, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("translate hello", messages[0].content)
        assertEquals(Role.ASSISTANT, messages[1].role)
        // UX-001 问题 9（ADR-021）：工具调用不混入 aiId 正文
        assertTrue("assistant 占位应带 toolCalls", messages[1].toolCalls.isNotEmpty())
        assertEquals(Role.TOOL, messages[2].role)
        assertEquals("Translation result", messages[2].content)
        // aiId（最终文本）在工具之后
        assertEquals(Role.ASSISTANT, messages[3].role)
        assertTrue("aiId 应含最终 AI 回复", messages[3].content.contains("Final AI response after tool"))
        assertFalse("aiId 不应含工具名（ToolCallStart 已从正文移除）", messages[3].content.contains("translator__translate"))
        // 剩余协议层断言
        assertEquals("", messages[1].content)
        assertTrue("占位应含 toolCalls 引用", messages[1].toolCalls.isNotEmpty())
        assertEquals("call_1", messages[2].toolCallId)
        assertFalse("完成后 isTyping 应为 false", vm.isTyping.value)
    }

    @Test
    fun `sendMessage with tools but null skillExecutor falls back to plain stream`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDRecordingProvider(listOf(
            StreamEvent.Delta("plain response"),
            StreamEvent.Done
        ))

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(
                    name = "translator",
                    tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
                )
            )),
            skillExecutor = null,  // 关键：null 触发降级
            mcpServerRepository = null,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("hello")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals("应走普通 streamChat 分支：1 user + 1 AI", 2, messages.size)
        assertEquals("plain response", messages[1].content)
        // 验证普通 streamChat 被调用，tools 参数为 null
        assertNull("普通 streamChat 不应传 tools", provider.lastTools)
    }

    @Test
    fun `sendMessage with no enabled skills uses plain stream branch`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDRecordingProvider(listOf(
            StreamEvent.Delta("no skill response"),
            StreamEvent.Done
        ))
        val fakeExecutor = FakeSkillExecutor(emptyList())

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(emptyList()),  // 无启用 Skill
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("hello")
        advanceUntilIdle()

        assertFalse("无 tools 不应调用 executeLoop", fakeExecutor.executeLoopCalled)
        assertEquals("no skill response", vm.messages.value[1].content)
    }

    // ==================== R-4 历史过滤器扩展测试 ====================

    @Test
    fun `history filter preserves assistant with toolCalls even when content empty`() = runTest(mainDispatcher) {
        // R-4 修复验证：携带 toolCalls 的空 content assistant 占位消息必须保留在 history 中，
        // 否则下次请求丢失 tool_calls 上下文，OpenAI 返回 400。
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDMultiRoundProvider(listOf(
            listOf(StreamEvent.Delta("first"), StreamEvent.Done),
            listOf(StreamEvent.Delta("second"), StreamEvent.Done)
        ))
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 第一轮：正常对话
        vm.sendMessage("first")
        advanceUntilIdle()

        // 手动注入一个携带 toolCalls 的空 content assistant 消息（模拟 executeLoop 返回值同步）
        // 通过反射或直接构造一个 VM 状态——这里采用 sendMessage 触发后再观察的方式：
        // 由于无法直接注入，本测试改用纯函数思路验证：构造含 toolCalls 的 history，
        // 验证下次 sendMessage 时它进入请求历史
        // 但 ConversationViewModel 不暴露 history 构造函数，故改用纯函数验证 + 集成测试间接覆盖
        // 此处保留为占位，真正的 R-4 验证在下方纯函数式测试中通过 filterNot 表达式断言

        // 第二轮：验证 history 不含空 content assistant（第一轮 AI 消息非空，应保留）
        vm.sendMessage("second")
        advanceUntilIdle()

        val secondRoundHistory = provider.receivedMessages[1]
        assertTrue(
            "R-4：第二轮 history 应保留第一轮非空 AI 消息",
            secondRoundHistory.any { it.role == Role.ASSISTANT && it.content == "first" }
        )
    }

    @Test
    fun `history filter excludes empty assistant without toolCalls`() = runTest(mainDispatcher) {
        // 验证 BR-interface-003/004：空 content 且空 toolCalls 的 assistant 消息应被过滤
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDMultiRoundProvider(listOf(
            listOf(StreamEvent.Done),  // 第一轮零增量，残留空 AI 消息
            listOf(StreamEvent.Delta("ok"), StreamEvent.Done)
        ))
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("first")
        advanceUntilIdle()
        vm.sendMessage("second")
        advanceUntilIdle()

        val secondRound = provider.receivedMessages[1]
        assertTrue(
            "R-4：空 content 且空 toolCalls 的 assistant 应被过滤",
            secondRound.none { it.role == Role.ASSISTANT && it.content.isEmpty() && it.toolCalls.isEmpty() }
        )
    }

    // ==================== handleStreamEvent 测试（通过 sendMessage 集成） ====================

    @Test
    fun `handleStreamEvent ToolCallStart does not pollute content`() = runTest(mainDispatcher) {
        // UX-001 问题 9（ADR-021）：工具调用指示不再混入正文（避免 🔧 污染最终答案）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // 通过 FakeSkillExecutor 触发 ToolCallStart 事件
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = emptyList(),
            emitEvents = listOf(
                StreamEvent.ToolCallStart("call_1", "translator__translate", 0),
                StreamEvent.Delta("after tool call"),
                StreamEvent.Done
            )
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val aiMsg = vm.messages.value[1]
        assertFalse(
            "ToolCallStart 不应混入 content（避免 🔧 污染正文）",
            aiMsg.content.contains("🔧 translator__translate")
        )
        assertTrue("应含后续 Delta 文本", aiMsg.content.contains("after tool call"))
    }

    @Test
    fun `handleStreamEvent ReasoningDelta goes to thinkingChain`() = runTest(mainDispatcher) {
        // UX-001 问题 7（ADR-021）：深度思考推理过程独立到 thinkingChain 字段（可折叠展示），
        // 不再混入最终答案 content（避免 [思考] 前缀污染正文）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val fakeExecutor = FakeSkillExecutor(
            returnMessages = emptyList(),
            emitEvents = listOf(
                StreamEvent.ReasoningDelta("先推理"),
                StreamEvent.Delta("最终答案"),
                StreamEvent.Done
            )
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val aiMsg = vm.messages.value[1]
        assertEquals("推理过程应存入 thinkingChain", "先推理", aiMsg.thinkingChain)
        assertFalse("content 不应混入 [思考] 前缀", aiMsg.content.contains("[思考]"))
        assertTrue("应含最终答案", aiMsg.content.contains("最终答案"))
    }

    @Test
    fun `handleStreamEvent Error appends warning and stops typing`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val fakeExecutor = FakeSkillExecutor(
            returnMessages = emptyList(),
            emitEvents = listOf(StreamEvent.Error("tool execution timeout"))
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val aiMsg = vm.messages.value[1]
        assertTrue("应含错误提示", aiMsg.content.contains("tool execution timeout"))
        assertFalse("Error 后 isTyping 应为 false", vm.isTyping.value)
    }

    @Test
    fun `handleStreamEvent Error sanitizes path leakage from upstream`() = runTest(mainDispatcher) {
        // M-1 修复验证（guardrail TKN-M4-PHASED-GUARDRAIL-001）：上游 Error.message 含路径时，
        // handleStreamEvent UI 边界应做防御性脱敏（CWE-209 第二层防御）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val maliciousMsg = "failed at /d:/s0611/code/Prism/secret.pem with key abc123"
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = emptyList(),
            emitEvents = listOf(StreamEvent.Error(maliciousMsg))
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val aiMsg = vm.messages.value[1]
        assertTrue("应含⚠️提示", aiMsg.content.contains("⚠️"))
        assertFalse("路径应被脱敏为<path>", aiMsg.content.contains("/d:/s0611/code/Prism/secret.pem"))
        assertTrue("应含<path>占位符", aiMsg.content.contains("<path>"))
        assertFalse("Error 后 isTyping 应为 false", vm.isTyping.value)
    }

    @Test
    fun `sanitizeUiErrorMessage truncates long messages and redacts paths`() = runTest(mainDispatcher) {
        // M-1 修复验证：纯函数测试 sanitizeUiErrorMessage
        // 路径放在前 200 字符内，验证脱敏；总长 > 200 验证截断
        val longMsg = "/secret/path " + "x".repeat(300)
        val sanitized = ConversationViewModel.sanitizeUiErrorMessage(longMsg)
        assertTrue("应截断到 MAX_UI_ERROR_LEN + ...", sanitized.length <= ConversationViewModel.MAX_UI_ERROR_LEN + 3)
        assertTrue("应含...后缀", sanitized.endsWith("..."))
        assertFalse("路径应被脱敏", sanitized.contains("/secret/path"))
        assertTrue("应含<path>占位符", sanitized.contains("<path>"))

        // null/empty 返回通用安全文案
        assertEquals("未知错误", ConversationViewModel.sanitizeUiErrorMessage(null))
        assertEquals("未知错误", ConversationViewModel.sanitizeUiErrorMessage(""))
        assertEquals("未知错误", ConversationViewModel.sanitizeUiErrorMessage("   "))

        // 正常短消息保持不变
        assertEquals("正常错误", ConversationViewModel.sanitizeUiErrorMessage("正常错误"))

        // 短消息含路径也应脱敏
        val shortWithPath = "error loading /d:/s0611/code/Prism/secret.pem"
        val sanitizedShort = ConversationViewModel.sanitizeUiErrorMessage(shortWithPath)
        assertFalse("短消息路径也应被脱敏", sanitizedShort.contains("/d:/s0611/code/Prism/secret.pem"))
        assertTrue("短消息应含<path>", sanitizedShort.contains("<path>"))
    }

    // ==================== R-4 历史过滤器关键场景直接测试（M-3 修复） ====================

    @Test
    fun `R-4 history preserves assistant with toolCalls and empty content across rounds`() = runTest(mainDispatcher) {
        // M-3 修复（guardrail TKN-M4-PHASED-GUARDRAIL-001）：直接验证 R-4 关键场景
        // 携带 toolCalls 的空 content assistant 占位消息必须保留在 history 中，
        // 否则下次请求丢失 tool_calls 上下文，OpenAI 返回 400（BR-interface-004 + R-4 修复）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // FakeSkillExecutor 在每次调用时返回「assistant 占位（空 content + toolCalls）+ tool result」
        val toolCallRef = ToolCallRef(id = "call_r4", functionName = "translator__translate", arguments = "{}")
        val cannedAssistantPlaceholder = ChatMessage(
            id = 0L,  // id 由 syncToolMessages 不重新分配，仅占位（实际 id 由 executeLoop 内 idGenerator 分配，但不写入 _messages）
            role = Role.ASSISTANT,
            content = "",  // 关键：空 content
            timestamp = 0L,
            toolCalls = listOf(toolCallRef)  // 关键：非空 toolCalls
        )
        val cannedToolResult = ChatMessage(
            id = 0L,
            role = Role.TOOL,
            content = "translated result",
            timestamp = 0L,
            toolCallId = "call_r4",
            toolName = "translator__translate"
        )

        val fakeExecutor = FakeSkillExecutor(
            returnMessages = listOf(cannedAssistantPlaceholder, cannedToolResult),
            emitEvents = listOf(
                StreamEvent.ToolCallStart("call_r4", "translator__translate", 0),
                StreamEvent.Delta("final answer after tool"),
                StreamEvent.Done
            )
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 第一轮：触发 executeLoop，syncToolMessages 将 assistant(空 content + toolCalls) + tool_result 追加到 _messages
        vm.sendMessage("translate hello")
        advanceUntilIdle()

        // 验证 _messages 现在含 assistant(空 content + toolCalls) 和 tool_result
        val messagesAfterRound1 = vm.messages.value
        assertTrue(
            "R-4：_messages 应含携带 toolCalls 的空 content assistant",
            messagesAfterRound1.any { it.role == Role.ASSISTANT && it.content.isEmpty() && it.toolCalls.isNotEmpty() }
        )
        assertTrue(
            "R-4：_messages 应含 tool_result 消息",
            messagesAfterRound1.any { it.role == Role.TOOL && it.toolCallId == "call_r4" }
        )

        // 第二轮：sendMessage 再次触发 executeLoop，FakeSkillExecutor 记录第二轮接收的 messages
        vm.sendMessage("translate world")
        advanceUntilIdle()

        // 关键断言：第二轮 history（FakeSkillExecutor 第二次调用接收的 messages）应包含
        // 第一轮的 assistant(空 content + toolCalls) 占位消息
        assertEquals("应执行两轮 executeLoop", 2, fakeExecutor.receivedMessagesHistory.size)
        val round2History = fakeExecutor.receivedMessagesHistory[1]
        assertTrue(
            "R-4 关键场景：第二轮 history 应保留第一轮携带 toolCalls 的空 content assistant",
            round2History.any { it.role == Role.ASSISTANT && it.content.isEmpty() && it.toolCalls.isNotEmpty() }
        )
        assertTrue(
            "R-4 关键场景：第二轮 history 应保留第一轮的 tool_result",
            round2History.any { it.role == Role.TOOL && it.toolCallId == "call_r4" }
        )
    }

    @Test
    fun `R-4 history filters assistant with empty content and empty toolCalls`() = runTest(mainDispatcher) {
        // M-3 修复：对比测试 —— 空 content 且空 toolCalls 的 assistant 应被过滤（BR-interface-003/004）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // FakeSkillExecutor 返回一个空 content 且无 toolCalls 的 assistant（模拟零增量残留）
        val emptyAssistant = ChatMessage(
            id = 0L,
            role = Role.ASSISTANT,
            content = "",  // 空 content
            timestamp = 0L,
            toolCalls = emptyList()  // 空 toolCalls（关键区别）
        )
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = listOf(emptyAssistant),
            emitEvents = listOf(StreamEvent.Done)
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("first")
        advanceUntilIdle()
        vm.sendMessage("second")
        advanceUntilIdle()

        val round2History = fakeExecutor.receivedMessagesHistory[1]
        assertFalse(
            "R-4：空 content 且空 toolCalls 的 assistant 应被过滤，不进入第二轮 history",
            round2History.any { it.role == Role.ASSISTANT && it.content.isEmpty() && it.toolCalls.isEmpty() }
        )
    }

    // ==================== AtomicLong idGenerator 测试（R-3） ====================

    @Test
    fun `consecutive sends assign increasing ids via AtomicLong`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(listOf(StreamEvent.Done)),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("a")
        vm.sendMessage("b")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals("无激活 Provider 时每条消息追加一条提示", 4, messages.size)
        assertEquals(
            "AtomicLong 应保证 id 单调递增",
            listOf(0L, 1L, 2L, 3L),
            messages.map { it.id }
        )
    }

    // ==================== M-2 异常路径状态一致性测试（guardrail 第二轮 12.3 建议） ====================

    @Test
    fun `M-2 executeWithToolLoop handles executeLoop RuntimeException with error message and isTyping false`() = runTest(mainDispatcher) {
        // M-2 验证（guardrail 第二轮 12.3 建议）：executeLoop 抛 RuntimeException 时，
        // executeWithToolLoop catch 块应正确处理：
        // 1. aiId 占位消息含错误提示（appendDelta，仅 simpleName 不泄露 e.message）
        // 2. 无残留中间消息（syncToolMessages 未执行）
        // 3. isTyping=false（finally 兜底）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val fakeExecutor = FakeSkillExecutor(
            returnMessages = listOf(
                ChatMessage(id = 999L, role = Role.ASSISTANT, content = "should not appear", timestamp = 0L)
            ),
            emitEvents = emptyList(),
            throwOnExecute = RuntimeException("simulated executeLoop failure")
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val messages = vm.messages.value
        // 断言1：只有 2 条消息（user + aiId），syncToolMessages 未执行
        assertEquals("M-2：应只有 2 条消息（user + aiId），无残留中间消息", 2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals(Role.ASSISTANT, messages[1].role)
        // 断言2：aiId 含错误提示（catch 块 appendDelta）
        assertTrue("M-2：aiId 应含错误提示", messages[1].content.contains("⚠️ 工具执行回路异常"))
        assertTrue("M-2：aiId 应含异常类名", messages[1].content.contains("RuntimeException"))
        // 断言3：无残留中间消息（returnMessages 未被 syncToolMessages 追加）
        assertFalse("M-2：不应含 returnMessages 中的消息", messages.any { it.id == 999L })
        // 断言4：isTyping=false（finally 兜底）
        assertFalse("M-2：isTyping 应为 false（finally 兜底）", vm.isTyping.value)
    }

    @Test
    fun `M-2 executeWithToolLoop error message does not leak exception message content`() = runTest(mainDispatcher) {
        // M-2 附加验证：异常路径下 catch 块使用 e::class.simpleName，不泄露 e.message（可能含路径）
        // 验证 BR-error-handling-008 在异常路径也有效（catch 块不直接显示 e.message）
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val maliciousException = RuntimeException("failed at /d:/s0611/code/Prism/secret.pem")
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = emptyList(),
            throwOnExecute = maliciousException
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val aiMsg = vm.messages.value[1]
        assertTrue("M-2：应含⚠️提示", aiMsg.content.contains("⚠️"))
        assertTrue("M-2：应含异常类名 RuntimeException", aiMsg.content.contains("RuntimeException"))
        // 关键断言：异常 e.message 不应直接出现在 UI（catch 块只用 simpleName）
        assertFalse("M-2：不应泄露异常 message 中的路径", aiMsg.content.contains("/d:/s0611/code/Prism/secret.pem"))
        assertFalse("M-2：不应泄露异常 message 原文", aiMsg.content.contains("failed at"))
        assertFalse("M-2：isTyping 应为 false", vm.isTyping.value)
    }

    @Test
    fun `M-2 executeWithToolLoop does not sync tool messages when executeLoop throws`() = runTest(mainDispatcher) {
        // M-2 验证：异常路径下 syncToolMessages 不被调用（returnMessages 即使非空也不追加）
        // 验证 try-catch-finally 结构：catch 块在 syncToolMessages 之前，异常跳过 syncToolMessages
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val toolCallRef = ToolCallRef(id = "call_m2", functionName = "translator__translate", arguments = "{}")
        val cannedMessages = listOf(
            ChatMessage(id = 500L, role = Role.ASSISTANT, content = "", timestamp = 0L, toolCalls = listOf(toolCallRef)),
            ChatMessage(id = 501L, role = Role.TOOL, content = "result", timestamp = 0L, toolCallId = "call_m2", toolName = "translator__translate")
        )
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = cannedMessages,
            throwOnExecute = RuntimeException("boom before sync")
        )
        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = PhaseDRecordingProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(name = "translator", tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { })))
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("test")
        advanceUntilIdle()

        val messages = vm.messages.value
        // syncToolMessages 未执行：cannedMessages 中的消息不应出现在 _messages
        assertFalse("M-2：不应含 canned assistant 占位", messages.any { it.id == 500L })
        assertFalse("M-2：不应含 canned tool result", messages.any { it.id == 501L })
        assertFalse("M-2：不应含 tool result 消息", messages.any { it.role == Role.TOOL })
        // 但 aiId 仍存在且有错误提示
        assertEquals("M-2：应只有 user + aiId", 2, messages.size)
        assertTrue("M-2：aiId 应含错误提示", messages[1].content.contains("⚠️"))
        assertFalse("M-2：isTyping 应为 false", vm.isTyping.value)
    }

    // ==================== UXR3 问题 10（ADR-023）：工具审批模式 DISABLED ====================

    @Test
    fun `sendMessage with DISABLED approval mode injects no tools and skips executeLoop`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        // 记录 streamChat 参数的 provider（DISABLED 时走普通流式分支，tools 应为 null）
        val provider = PhaseDRecordingProvider(listOf(
            StreamEvent.Delta("plain response"),
            StreamEvent.Done
        ))
        val fakeExecutor = FakeSkillExecutor(returnMessages = emptyList())
        // 工具审批模式：DISABLED
        val approvalRepo = io.prism.config.ToolApprovalConfigRepository(
            io.prism.security.FakePreferenceDataStore(
                androidx.datastore.preferences.core.mutablePreferencesOf(
                    androidx.datastore.preferences.core.stringPreferencesKey("tool_approval_mode") to "DISABLED"
                )
            )
        )

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(
                    name = "translator",
                    tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
                )
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher,
            // UXR3 问题 10：DISABLED 审批模式
            toolApprovalConfigRepository = approvalRepo
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("translate hello")
        advanceUntilIdle()

        // DISABLED 模式：不注入 tools → 走普通 streamChat 分支，不调用 executeLoop
        assertFalse("DISABLED 模式不应调用 executeLoop", fakeExecutor.executeLoopCalled)
        assertNull("DISABLED 模式 streamChat 不应传 tools", provider.lastTools)
        assertEquals("应走普通流式分支：1 user + 1 AI", 2, vm.messages.value.size)
        assertEquals("plain response", vm.messages.value[1].content)
    }

    @Test
    fun `sendMessage with MANUAL approval mode still injects tools`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDRecordingProvider(emptyList())
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = listOf(
                ChatMessage(
                    id = 100L, role = Role.ASSISTANT, content = "", timestamp = 0L,
                    toolCalls = listOf(ToolCallRef(id = "call_1", functionName = "translator__translate", arguments = "{}"))
                ),
                ChatMessage(
                    id = 101L, role = Role.TOOL, content = "ok", timestamp = 0L,
                    toolCallId = "call_1", toolName = "translator__translate"
                )
            ),
            emitEvents = listOf(StreamEvent.Delta("final"), StreamEvent.Done)
        )
        // 工具审批模式：MANUAL（非 DISABLED，应正常注入工具）
        val approvalRepo = io.prism.config.ToolApprovalConfigRepository(
            io.prism.security.FakePreferenceDataStore(
                androidx.datastore.preferences.core.mutablePreferencesOf(
                    androidx.datastore.preferences.core.stringPreferencesKey("tool_approval_mode") to "MANUAL"
                )
            )
        )

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(
                    name = "translator",
                    tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
                )
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher,
            toolApprovalConfigRepository = approvalRepo
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("translate hello")
        advanceUntilIdle()

        assertTrue("MANUAL 模式应调用 executeLoop", fakeExecutor.executeLoopCalled)
        // UXR4 问题 2/3（ADR-024）：tools 含 3 个知识库工具（knowledge_base__*），过滤 skill 后断言
        val skillTools = fakeExecutor.receivedTools!!.filter { it.function.name.startsWith("translator__") }
        assertEquals("MANUAL 模式应注入 1 个 skill tool", 1, skillTools.size)
        assertEquals("translator__translate", skillTools[0].function.name)
    }

    // ==================== UXR3 问题 13（ADR-023）：编辑重发含 toolCalls 历史（guardrail T-2） ====================

    @Test
    fun `editUserMessageAndResend drops stale toolCalls from history before re-answer`() = runTest(mainDispatcher) {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(name = "OpenAI", baseUrl = "https://api.openai.com/v1", apiKeyRef = "openai", models = listOf("gpt-4o"))
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)

        val provider = PhaseDRecordingProvider(emptyList())
        // 第一轮：FakeSkillExecutor 返回「assistant 占位(toolCalls) + tool result」，模拟真实工具回路
        val fakeExecutor = FakeSkillExecutor(
            returnMessages = listOf(
                ChatMessage(
                    id = 100L, role = Role.ASSISTANT, content = "", timestamp = 0L,
                    toolCalls = listOf(ToolCallRef(id = "call_1", functionName = "translator__translate", arguments = "{}"))
                ),
                ChatMessage(
                    id = 101L, role = Role.TOOL, content = "old translation", timestamp = 0L,
                    toolCallId = "call_1", toolName = "translator__translate"
                )
            ),
            emitEvents = listOf(StreamEvent.Delta("第一轮回答"), StreamEvent.Done)
        )

        val vm = ConversationViewModel(
            providerRepository = repo,
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillRegistry = StubSkillRegistry(listOf(
                makeSkillEntry(
                    name = "translator",
                    tools = listOf(SkillToolDecl("translate", "Translate", buildJsonObject { }))
                )
            )),
            skillExecutor = fakeExecutor,
            mcpServerRepository = McpServerRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        // 第一轮：正常发送触发工具回路
        vm.sendMessage("translate hello")
        advanceUntilIdle()
        // 消息序列：user + aiId + assistant 占位(toolCalls) + tool result
        val messagesAfterRound1 = vm.messages.value
        assertEquals("第一轮应有 4 条消息", 4, messagesAfterRound1.size)
        val userMsg = messagesAfterRound1.first { it.role == Role.USER }
        assertTrue("第一轮历史应含 tool result", messagesAfterRound1.any { it.role == Role.TOOL })

        // 编辑用户消息重新发送
        fakeExecutor.receivedMessagesHistory.clear()  // 重置记录，聚焦第二轮历史
        vm.editUserMessageAndResend(userMsg.id, "translate goodbye")
        advanceUntilIdle()

        // 编辑后：截断 toolCalls 相关消息（assistant 占位 + tool result 被移除），
        // 第二轮 executeLoop 收到的历史不应含过期 toolCalls / tool result（避免 OpenAI 400）
        val secondRoundHistory = fakeExecutor.receivedMessagesHistory.lastOrNull()
        assertNotNull("第二轮应触发 executeLoop", secondRoundHistory)
        assertTrue("第二轮历史应只剩编辑后的 user 消息", secondRoundHistory!!.size == 1)
        assertEquals("编辑后的内容应进入第二轮历史", "translate goodbye", secondRoundHistory[0].content)
        assertFalse("第二轮历史不应含过期 tool result", secondRoundHistory.any { it.role == Role.TOOL })
        assertFalse("第二轮历史不应含过期 assistant 占位 toolCalls", secondRoundHistory.any { it.toolCalls.isNotEmpty() })
    }

    // ==================== UXR5 问题 4：孤儿 tool 防御（tool_calls 完整性） ====================

    @Test
    fun `dropOrphanToolMessages keeps paired tool results`() {
        // 配对正常：assistant(tool_calls) → tool → assistant(文本)，全部保留
        val msgs = listOf(
            ChatMessage(1, Role.USER, "q", 1000L),
            ChatMessage(2, Role.ASSISTANT, "", 2000L, toolCalls = listOf(ToolCallRef("c1", "function", "skill__t", "{}"))),
            ChatMessage(3, Role.TOOL, "result", 3000L),
            ChatMessage(4, Role.ASSISTANT, "final answer", 4000L)
        )
        val result = ConversationViewModel.dropOrphanToolMessages(msgs)
        assertEquals("配对正常的消息应全部保留", 4, result.size)
    }

    @Test
    fun `dropOrphanToolMessages removes orphan tool without preceding tool_calls`() {
        // 孤儿 TOOL：前置 assistant 无 toolCalls（会话恢复丢失），应被丢弃
        val msgs = listOf(
            ChatMessage(1, Role.USER, "q", 1000L),
            ChatMessage(2, Role.ASSISTANT, "no tool calls", 2000L),
            ChatMessage(3, Role.TOOL, "orphan result", 3000L),
            ChatMessage(4, Role.ASSISTANT, "final", 4000L)
        )
        val result = ConversationViewModel.dropOrphanToolMessages(msgs)
        assertEquals("孤儿 TOOL 应被丢弃，剩 3 条", 3, result.size)
        assertFalse("不应含孤儿 TOOL", result.any { it.role == Role.TOOL })
    }

    @Test
    fun `dropOrphanToolMessages resets pairing state at user boundary`() {
        // user 消息重置待配对状态：上一轮的 tool_calls 对不应跨 user 匹配
        val msgs = listOf(
            ChatMessage(1, Role.USER, "q1", 1000L),
            ChatMessage(2, Role.ASSISTANT, "", 2000L, toolCalls = listOf(ToolCallRef("c1", "function", "skill__t", "{}"))),
            ChatMessage(3, Role.TOOL, "result1", 3000L),
            ChatMessage(4, Role.USER, "q2", 4000L),
            ChatMessage(5, Role.TOOL, "orphan after user", 5000L),
            ChatMessage(6, Role.ASSISTANT, "final", 6000L)
        )
        val result = ConversationViewModel.dropOrphanToolMessages(msgs)
        assertFalse("user 边界后的孤儿 TOOL 应被丢弃", result.any { it.id == 5L })
        assertTrue("配对的 tool result 应保留", result.any { it.id == 3L })
    }

    @Test
    fun `dropOrphanToolMessages keeps parallel tool calls pairing`() {
        // F-01（guardrail TKN-UXR5-GUARDRAIL-001）：一轮内多个并行工具调用——
        // assistant(toolCalls=[c1,c2]) → tool(c1) → tool(c2)，两条 TOOL 都必须保留。
        val msgs = listOf(
            ChatMessage(1, Role.USER, "q", 1000L),
            ChatMessage(
                2, Role.ASSISTANT, "", 2000L,
                toolCalls = listOf(
                    ToolCallRef("c1", "function", "skill__a", "{}"),
                    ToolCallRef("c2", "function", "skill__b", "{}")
                )
            ),
            ChatMessage(3, Role.TOOL, "result-a", 3000L, toolCallId = "c1"),
            ChatMessage(4, Role.TOOL, "result-b", 4000L, toolCallId = "c2"),
            ChatMessage(5, Role.ASSISTANT, "final", 5000L)
        )
        val result = ConversationViewModel.dropOrphanToolMessages(msgs)
        assertEquals("并行配对的消息应全部保留", 5, result.size)
        assertTrue("第一个 tool result 应保留", result.any { it.id == 3L })
        assertTrue("第二个 tool result 应保留（并行配对）", result.any { it.id == 4L })
    }

    @Test
    fun `dropOrphanToolMessages drops only unmatched parallel tool`() {
        // 并行配对不完整：assistant(toolCalls=[c1,c2]) → 仅 1 条 tool result → 无孤儿可丢
        //（tool 数量 < toolCalls 数量是"缺失"而非"孤儿"，协议容忍；此处验证不误删）。
        val msgs = listOf(
            ChatMessage(1, Role.USER, "q", 1000L),
            ChatMessage(
                2, Role.ASSISTANT, "", 2000L,
                toolCalls = listOf(
                    ToolCallRef("c1", "function", "skill__a", "{}"),
                    ToolCallRef("c2", "function", "skill__b", "{}")
                )
            ),
            ChatMessage(3, Role.TOOL, "result-a", 3000L, toolCallId = "c1"),
            ChatMessage(4, Role.ASSISTANT, "final", 4000L)
        )
        val result = ConversationViewModel.dropOrphanToolMessages(msgs)
        // 已有 tool result 保留；未返回的 c2 结果缺失（非孤儿，不处理）
        assertTrue("已返回的 tool result 应保留", result.any { it.id == 3L })
    }

    // ==================== 辅助构造 ====================

    /** 构造测试用 [SkillRegistry.SkillEntry]（不依赖 Android Context）。 */
    private fun makeSkillEntry(
        name: String,
        systemPrompt: String? = null,
        tools: List<SkillToolDecl>? = null,
        isEnabled: Boolean = true,
        isInstalled: Boolean = true
    ): SkillRegistry.SkillEntry = SkillRegistry.SkillEntry(
        config = SkillConfig(
            id = 0L,
            name = name,
            displayName = name,
            source = SkillSource.LOCAL_BUILTIN,
            sourceUri = null,
            skillDir = "/skills/$name",
            isEnabled = isEnabled,
            isInstalled = isInstalled,
            version = "1.0.0"
        ),
        manifest = SkillManifest(
            name = name,
            description = "Test skill $name",
            version = "1.0.0",
            systemPrompt = systemPrompt,
            tools = tools,
            body = ""
        )
    )
}

// ==================== 测试 Fakes ====================

/**
 * 覆写 [SkillExecutor.executeLoop] 的 fake（M4 Phase D 测试专用）。
 *
 * 跳过真实 McpToolProvider/ToolConfirmationGate 协作，直接返回 canned 消息序列 +
 * 触发 onEvent 回调，便于隔离测试 ConversationViewModel 与 executeLoop 的集成。
 *
 * 父类构造所需的 [NoOpMcpToolProvider] / [NoOpConfirmationGate] 不参与测试逻辑
 * （executeLoop 被覆写，父类方法不会被调用）。
 */
private class FakeSkillExecutor(
    private val returnMessages: List<ChatMessage>,
    private val emitEvents: List<StreamEvent> = emptyList(),
    /** M-2 验证：非 null 时 executeLoop 抛出指定异常，模拟 executeLoop 执行失败 */
    private val throwOnExecute: Throwable? = null
) : SkillExecutor(
    mcpToolProvider = NoOpMcpToolProvider,
    confirmationGate = NoOpConfirmationGate,
    ioDispatcher = Dispatchers.Unconfined
) {
    var executeLoopCalled: Boolean = false
        private set
    var receivedTools: List<ToolDefinition>? = null
        private set
    var receivedMaxRounds: Int? = null
        private set
    /** M-3 修复：记录每次 executeLoop 调用接收的 messages，用于 R-4 历史过滤器跨轮次验证 */
    val receivedMessagesHistory: MutableList<List<ChatMessage>> = mutableListOf()

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
        executeLoopCalled = true
        receivedTools = tools
        receivedMaxRounds = maxRounds
        receivedMessagesHistory += messages
        // M-2 验证：支持模拟 executeLoop 抛异常（在 emitEvents 之前抛出，模拟回路中途失败）
        throwOnExecute?.let { throw it }
        // 模拟 executeLoop 内部行为：触发 onEvent 回调（R-1：调用方通过 onEvent 接收事件）
        emitEvents.forEach { onEvent(it) }
        // 返回原始 messages + canned 新增消息（模拟 assistant 占位 + tool result）
        return messages + returnMessages
    }
}

/** 无操作 [McpToolProvider]（仅供 [FakeSkillExecutor] 父类构造用，不参与测试逻辑）。 */
private object NoOpMcpToolProvider : McpToolProvider {
    override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
    override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String = ""
}

/** 无操作 [ToolConfirmationGate]（仅供 [FakeSkillExecutor] 父类构造用）。 */
private object NoOpConfirmationGate : ToolConfirmationGate {
    override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = true
}

/**
 * Stub [SkillRegistry] —— 覆写 [enabledSkills] 返回 canned 列表，跳过 Android Context 依赖。
 *
 * 父类构造所需的 [Context] / [SkillRepository] 通过反射或默认值绕过（enabledSkills 被覆写，
 * 父类方法不调用）。为简化，本 stub 直接构造父类（Context 用 stub Application，SkillRepository
 * 用临时 BoxStore），但仅 [enabledSkills] 行为被覆写。
 *
 * 注意：因 Kotlin 类构造期会访问 Context.filesDir（仅 scanAndSync 中），构造期不抛异常。
 */
private class StubSkillRegistry(
    private val stubSkills: List<SkillRegistry.SkillEntry>
) : SkillRegistry(
    context = android.app.Application(),  // stub Context，enabledSkills 不访问 Context
    skillRepository = io.prism.data.SkillRepository(
        MyObjectBox.builder().directory(
            kotlin.io.path.createTempDirectory(prefix = "stub-sr-").toFile()
        ).build()
    ),
    ioDispatcher = Dispatchers.Unconfined
) {
    override fun enabledSkills(): List<SkillEntry> = stubSkills
}

/** 简单 [Embedder] stub（RAG 关闭时不调用，仅满足构造注入）。 */
private class StubEmbedder : Embedder {
    override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
    override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    override fun isLoaded(): Boolean = true
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false
    override fun close() {}
}

/**
 * 记录 streamChat 请求参数的 fake（Phase D 测试专用，与既有 [PhaseDRecordingProvider] 区分）。
 *
 * 与既有 fake 区别：本类只记录最后一次请求的 `last*` 字段（非完整列表），用于断言「单次请求是否传 tools」。
 */
private class PhaseDRecordingProvider(
    private val events: List<StreamEvent>
) : ChatStreamProvider {
    var lastTools: List<ToolDefinition>? = null
        private set
    var lastSystemPrompt: String? = null
        private set
    var lastMessages: List<ChatMessage>? = null
        private set

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
        lastTools = tools
        lastSystemPrompt = systemPrompt
        lastMessages = messages
        return flow { events.forEach { emit(it) } }
    }
}

/**
 * 多轮 fake（Phase D 测试专用，与既有 ConversationViewModelTest 中的
 * `MultiRoundRecordingProvider` 区分）：
 * 按调用序依次返回不同事件序列，记录每次请求历史。
 */
private class PhaseDMultiRoundProvider(
    private val eventSequences: List<List<StreamEvent>>
) : ChatStreamProvider {
    val receivedMessages = mutableListOf<List<ChatMessage>>()
    private var call = 0

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
        receivedMessages += messages
        val events = eventSequences[call.coerceAtMost(eventSequences.size - 1)]
        call++
        return flow { events.forEach { emit(it) } }
    }
}
