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
import io.prism.embedding.Embedder
import io.prism.fs.ToolConfirmationGate
import io.prism.network.ChatStreamProvider
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.rag.RagTarget
import io.prism.security.FakePreferenceDataStore
import io.prism.skill.SkillExecutor
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UX-001 二次反馈（ADR-022）验收补充测试 —— ac-verifier 覆盖主 Agent 基础用例盲区。
 *
 * 覆盖三个关键集成路径（纯 JVM 可测，BR-testing-004）：
 * 1. **子决策 C（MCP 工具名规范化构造）**：`sendMessage` 中 MCP 工具名经
 *    `SkillExecutor.toMcpNamespace` 规范化（含空格/中文 server 名 → 合法工具名），
 *    且 `distinctBy` 去重同名 server 重复工具名（问题 5/6 根因）。
 * 2. **子决策 G（activeTool 状态机）**：ToolCallStart 置位（去命名空间展示）、
 *    Done/Error 清除（问题 7 工具调用可视化）。
 * 3. **子决策 D（深度思考开关竞态）**：用户手动切换后 init 异步读取不再覆盖
 *    （`thinkingToggledByUser` 标记，问题 2「点了没反应」根因）。
 *
 * 依赖注入仿 [ConversationViewModelTest] / [ConversationViewModelPhaseDTest]：
 * Fake ChatStreamProvider / Fake SkillExecutor / Fake McpToolProvider / Stub Embedder。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelUxR2Test {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-uxr2-test-").toFile()
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

    // ==================== 子决策 C：MCP 工具名规范化构造集成 ====================

    @Test
    fun `sendMessage builds legal MCP tool names via toMcpNamespace`() = runTest(mainDispatcher) {
        saveActiveProvider()
        // 含空格 server（修复前：非法工具名被 OpenAI/DeepSeek 400 拒绝或本地过滤）
        val mcpRepo = McpServerRepository(boxStore)
        mcpRepo.save(
            McpServerConfig(
                name = "Sequential Thinking",
                serverType = McpServerType.LOCAL,
                baseUrl = "",
                isEnabled = true
            )
        )
        val mcpProvider = FakeMcpDescribeProvider(toolNames = listOf("sequentialthinking", "generate_text"))
        val executor = RecordingSkillExecutor()

        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = RecordingChatStreamProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillExecutor = executor,
            mcpServerRepository = mcpRepo,
            mcpToolProvider = mcpProvider,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("use sequential thinking")
        advanceUntilIdle()

        val names = executor.receivedTools?.map { it.function.name } ?: emptyList()
        val mcpNames = names.filter { it.startsWith("mcp_") }
        assertEquals("应注入 2 个 MCP 工具", 2, mcpNames.size)
        assertTrue(
            "含空格 server 名应规范化为合法命名空间（Sequential_Thinking）",
            mcpNames.contains("mcp_Sequential_Thinking__sequentialthinking")
        )
        assertTrue(mcpNames.contains("mcp_Sequential_Thinking__generate_text"))
        assertTrue(
            "所有注入工具名应合法（[a-zA-Z0-9_-]）",
            names.all { ConversationViewModel.isLegalToolName(it) }
        )
        assertFalse("工具名不应含空格（否则被 API 拒绝）", names.any { it.contains(' ') })
    }

    @Test
    fun `sendMessage dedupes identical MCP tool names across same-name servers`() = runTest(mainDispatcher) {
        saveActiveProvider()
        // 重复添加同名 server → 相同工具名 → 去重保留唯一，避免 400 "Tool names must be unique"
        val mcpRepo = McpServerRepository(boxStore)
        mcpRepo.save(McpServerConfig(name = "Time", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true))
        mcpRepo.save(McpServerConfig(name = "Time", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true))
        val mcpProvider = FakeMcpDescribeProvider(toolNames = listOf("get_current_time"))
        val executor = RecordingSkillExecutor()

        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = RecordingChatStreamProvider(emptyList()),
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            skillExecutor = executor,
            mcpServerRepository = mcpRepo,
            mcpToolProvider = mcpProvider,
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("what time")
        advanceUntilIdle()

        val names = executor.receivedTools?.map { it.function.name } ?: emptyList()
        val mcpNames = names.filter { it.startsWith("mcp_") }
        assertEquals("同名 server 重复工具名应去重为唯一", 1, mcpNames.size)
        assertEquals(listOf("mcp_Time__get_current_time"), mcpNames)
    }

    // ==================== 子决策 G：activeTool 状态机 ====================

    @Test
    fun `activeTool set on ToolCallStart with namespace stripped`() = runTest(mainDispatcher) {
        saveActiveProvider()
        // 无 skill/mcp → 普通流式分支；流仅发 ToolCallStart（无 Done）
        val provider = RecordingChatStreamProvider(
            listOf(StreamEvent.ToolCallStart("call_1", "web_search__search", 0))
        )
        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("search")
        advanceUntilIdle()

        assertEquals("activeTool 应去命名空间前缀展示（search）", "search", vm.activeTool.value)
    }

    @Test
    fun `activeTool cleared on Done`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val provider = RecordingChatStreamProvider(
            listOf(
                StreamEvent.ToolCallStart("call_1", "web_search__search", 0),
                StreamEvent.Delta("result"),
                StreamEvent.Done
            )
        )
        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("search")
        advanceUntilIdle()

        assertNull("Done 后应清除 activeTool", vm.activeTool.value)
    }

    @Test
    fun `activeTool cleared on Error`() = runTest(mainDispatcher) {
        saveActiveProvider()
        val provider = RecordingChatStreamProvider(
            listOf(
                StreamEvent.ToolCallStart("call_1", "filesystem__read_file", 0),
                StreamEvent.Error("tool execution failed")
            )
        )
        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("read")
        advanceUntilIdle()

        assertNull("Error 后应清除 activeTool", vm.activeTool.value)
    }

    @Test
    fun `activeTool maintained and isTyping reset on ToolCallComplete in plain stream`() = runTest(mainDispatcher) {
        // UXR4 问题 7/10（ADR-024）：ToolCallComplete 表示工具即将执行，应保持 activeTool
        //（「正在调用工具」指示）+ 置 isTyping=true，避免工具执行阶段 UI 空白（指示一闪而过）。
        saveActiveProvider()
        val provider = RecordingChatStreamProvider(
            listOf(
                StreamEvent.ToolCallStart("call_1", "web_search__search", 0),
                StreamEvent.ToolCallComplete("call_1", "web_search__search", mapOf("query" to "天气"))
            )
        )
        val vm = ConversationViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            provider = provider,
            embedder = StubEmbedder(),
            knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher
        ).apply { setRagTarget(RagTarget.Off) }

        vm.sendMessage("search")
        advanceUntilIdle()

        // 普通流式分支（无工具回路）ToolCallComplete 后无 Done → 流结束 finally 复位 isTyping。
        // UXR6 R2-1（TKN-UXR6-GUARDRAIL-R2）防御纵深：与 executeWithToolLoop finally / catch
        // 分支对称，避免 isTyping 残留永久屏蔽 sendMessage 守卫；activeTool 由 ToolCallStart 保持。
        assertEquals("ToolCallComplete 应保持 activeTool（search）", "search", vm.activeTool.value)
        assertFalse(
            "普通流结束（无 Done）isTyping 应复位为 false（R2-1 防御纵深，避免屏蔽用户发送）",
            vm.isTyping.value
        )
    }

    // ==================== 子决策 D：深度思考开关竞态 ====================

    @Test
    fun `init applies stored thinking value when user has not toggled`() = runTest(mainDispatcher) {
        // 正向对照：无用户切换时，init 异步读取应应用 DataStore 存储值（开关机制本身有效）
        val standard = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standard)
        try {
            val dataStore = FakePreferenceDataStore(
                preferencesOf(booleanPreferencesKey("thinking_enabled") to true)
            )
            val vm = ConversationViewModel(
                providerRepository = ProviderConfigRepository(boxStore),
                provider = RecordingChatStreamProvider(emptyList()),
                embedder = StubEmbedder(),
                knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
                thinkingConfigRepository = ThinkingConfigRepository(dataStore),
                ioDispatcher = mainDispatcher
            ).apply { setRagTarget(RagTarget.Off) }

            // init 协程此时尚未执行（StandardTestDispatcher 延迟），直接推进
            advanceUntilIdle()

            assertTrue("无用户切换时 init 应应用存储值 true", vm.thinkingEnabled.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `user toggle before init read completes is not overwritten`() = runTest(mainDispatcher) {
        // 竞态复现：DataStore 已持久化 stored=true，但用户在此期间手动切到 false。
        // 修复前：init 异步读取用旧值 true 覆盖用户选择 → 「点了没反应」（问题 2）。
        // 修复后：thinkingToggledByUser=true 时 init 读取不再应用（ADR-022 子决策 D）。
        val standard = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standard)
        try {
            val dataStore = FakePreferenceDataStore(
                preferencesOf(booleanPreferencesKey("thinking_enabled") to true)
            )
            val vm = ConversationViewModel(
                providerRepository = ProviderConfigRepository(boxStore),
                provider = RecordingChatStreamProvider(emptyList()),
                embedder = StubEmbedder(),
                knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
                thinkingConfigRepository = ThinkingConfigRepository(dataStore),
                ioDispatcher = mainDispatcher
            ).apply { setRagTarget(RagTarget.Off) }

            // init 协程被 StandardTestDispatcher 延迟，先让用户手动关闭
            vm.setThinkingEnabled(false)

            // 推进：init 协程读取到 stored=true，但 thinkingToggledByUser=true 不应覆盖
            advanceUntilIdle()

            assertFalse("用户手动切换后 init 读取不应覆盖用户选择", vm.thinkingEnabled.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ==================== 测试 Fakes ====================

    /** 返回固定工具定义的 [McpToolProvider] fake（覆写 [McpToolProvider.describeTools]）。 */
    private class FakeMcpDescribeProvider(
        private val toolNames: List<String>
    ) : McpToolProvider {
        override suspend fun listTools(config: McpServerConfig): List<String> = toolNames

        override suspend fun describeTools(config: McpServerConfig): List<ToolDefinition> =
            toolNames.map { name ->
                ToolDefinition(
                    function = ToolDefinition.FunctionDef(
                        name = name,
                        description = "tool $name",
                        parameters = kotlinx.serialization.json.JsonObject(emptyMap())
                    )
                )
            }

        override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String = "ok"
    }

    /** 记录 executeLoop 收到 tools 的 [SkillExecutor] fake（覆盖 MCP 工具名构造路径）。 */
    private class RecordingSkillExecutor(
        private val emitEvents: List<StreamEvent> = emptyList()
    ) : SkillExecutor(
        mcpToolProvider = NoOpMcpToolProvider,
        confirmationGate = NoOpConfirmationGate,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        var receivedTools: List<ToolDefinition>? = null
            private set

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
            receivedTools = tools
            emitEvents.forEach { onEvent(it) }
            return messages
        }
    }

    /** 无操作 [McpToolProvider]（仅供 [RecordingSkillExecutor] 父类构造用）。 */
    private object NoOpMcpToolProvider : McpToolProvider {
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(config: McpServerConfig, name: String, arguments: Map<String, Any?>): String = ""
    }

    /** 无操作 [ToolConfirmationGate]（仅供 [RecordingSkillExecutor] 父类构造用）。 */
    private object NoOpConfirmationGate : ToolConfirmationGate {
        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = true
    }

    /** 发射固定事件序列的 [ChatStreamProvider] fake。 */
    private class RecordingChatStreamProvider(
        private val events: List<StreamEvent>
    ) : ChatStreamProvider {
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

    /** 简单 [Embedder] stub（RAG 关闭时不调用，仅满足构造注入）。 */
    private class StubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
