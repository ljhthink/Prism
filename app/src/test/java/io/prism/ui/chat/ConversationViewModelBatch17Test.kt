package io.prism.ui.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.objectbox.BoxStore
import io.prism.data.KnowledgeBase
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.data.Session
import io.prism.data.SessionRepository
import io.prism.embedding.Embedder
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.rag.RagTarget
import io.prism.security.FakePreferenceDataStore
import io.prism.skill.SkillExecutor
import io.prism.skill.TodoLocalToolExecutor
import io.prism.config.RagTargetConfigRepository
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.MessageVariant
import io.prism.ui.model.Role
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * v1 批次17 四项新功能单元测试（US-1701/1702/1704/1706/1707/1708）：
 *
 * 1. [TodoLocalToolExecutor]：合法更新 / 超 8 项 / 多 in_progress / 空清单清空 / 快照格式 / 工具定义
 * 2. buildTools / mergeSystemPrompt 扩展：todo_write 注册 + TODO_GUIDANCE 注入
 * 3. ChatMessageSerializer：variants roundtrip / 旧 JSON 兼容 / 空 variants 不落盘
 * 4. ConversationViewModel：editAiMessage / rollbackFromUserMessage / regenerateLastAiMessage / switchVariant
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelBatch17Test {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "conv-b17-test-").toFile()
        boxStore = MyObjectBoxBuilder.build(tempDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ==================== US-1701：TodoLocalToolExecutor ====================

    @Test
    fun `todo_write valid update updates state and returns snapshot`() = kotlinx.coroutines.test.runTest {
        val executor = TodoLocalToolExecutor()
        val result = executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf(
                "todos" to listOf(
                    mapOf("content" to "搜索A", "activeForm" to "正在搜索A", "status" to "completed"),
                    mapOf("content" to "搜索B", "activeForm" to "正在搜索B", "status" to "in_progress"),
                    mapOf("content" to "汇总", "activeForm" to "汇总", "status" to "pending")
                )
            )
        )
        assertTrue("合法更新应返回成功快照", result.contains("任务清单已更新（1/3 完成）"))
        assertTrue("快照应含 [x] 标记", result.contains("1.[x] 搜索A"))
        assertTrue("快照应含 [→] 标记", result.contains("2.[→] 搜索B"))
        assertTrue("快照应含 [ ] 标记", result.contains("3.[ ] 汇总"))
        val state = executor.state.value
        assertEquals(3, state.items.size)
        assertEquals(1L, state.version)
    }

    @Test
    fun `todo_write rejects more than maxItems`() = kotlinx.coroutines.test.runTest {
        val executor = TodoLocalToolExecutor()
        val tooMany = (1..9).map {
            mapOf("content" to "t$it", "status" to "pending", "activeForm" to "t$it")
        } + mapOf("content" to "进行中", "status" to "in_progress", "activeForm" to "x")
        val result = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to tooMany))
        assertTrue("超限应回灌错误", result.startsWith("错误："))
        assertTrue("错误应含上限说明", result.contains("最多 ${TodoLocalToolExecutor.MAX_ITEMS} 项"))
        assertEquals("被拒绝的更新不应改变状态", 0, executor.state.value.items.size)
    }

    @Test
    fun `todo_write rejects zero or multiple in_progress`() = kotlinx.coroutines.test.runTest {
        val executor = TodoLocalToolExecutor()
        val none = (1..3).map { mapOf("content" to "t$it", "status" to "pending", "activeForm" to "t$it") }
        assertTrue(executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to none)).startsWith("错误："))
        val multiple = listOf(
            mapOf("content" to "a", "status" to "in_progress", "activeForm" to "a"),
            mapOf("content" to "b", "status" to "in_progress", "activeForm" to "b")
        )
        assertTrue(executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to multiple)).startsWith("错误："))
        assertEquals("两次拒绝后状态仍为空", 0, executor.state.value.items.size)
    }

    @Test
    fun `todo_write empty todos clears state`() = kotlinx.coroutines.test.runTest {
        val executor = TodoLocalToolExecutor()
        executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf("todos" to listOf(mapOf("content" to "a", "status" to "in_progress", "activeForm" to "a")))
        )
        assertEquals(1, executor.state.value.items.size)
        val result = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to emptyList<Map<String, String>>()))
        assertFalse("空清单应成功（清空语义）", result.startsWith("错误："))
        assertEquals("状态应被清空", 0, executor.state.value.items.size)
    }

    @Test
    fun `todo_write reset clears state`() = kotlinx.coroutines.test.runTest {
        val executor = TodoLocalToolExecutor()
        executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf("todos" to listOf(mapOf("content" to "a", "status" to "in_progress", "activeForm" to "a")))
        )
        executor.reset()
        assertEquals("reset 应清空清单", 0, executor.state.value.items.size)
    }

    @Test
    fun `todo_write buildToolDefinition has valid schema`() {
        val def = TodoLocalToolExecutor.buildToolDefinition()
        assertEquals(TodoLocalToolExecutor.TOOL_NAME, def.function.name)
        val json = def.function.parameters.toString()
        assertTrue("schema 应含 todos 属性", json.contains("todos"))
        assertTrue("schema 应为 array 类型", json.contains("\"type\":\"array\""))
        assertTrue("schema 应含 enum 三态", json.contains("in_progress"))
        assertTrue("schema 应含 maxItems", json.contains("maxItems"))
    }

    // ==================== US-1702：buildTools / mergeSystemPrompt ====================

    @Test
    fun `buildTools includes todo_write when enabled`() {
        val tools = ConversationViewModel.buildTools(emptyList(), todoWriteEnabled = true)
        assertTrue("应含 todo_write 工具", tools.any { it.function.name == TodoLocalToolExecutor.TOOL_NAME })
    }

    @Test
    fun `buildTools excludes todo_write when disabled`() {
        val tools = ConversationViewModel.buildTools(emptyList(), todoWriteEnabled = false)
        assertFalse("不应含 todo_write 工具", tools.any { it.function.name == TodoLocalToolExecutor.TOOL_NAME })
    }

    @Test
    fun `mergeSystemPrompt injects todo guidance when provided`() {
        val prompt = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            todoGuidance = ConversationViewModel.TODO_GUIDANCE,
            enabledSkills = emptyList()
        )
        assertTrue("应注入 TODO 指引", prompt.contains(ConversationViewModel.TODO_GUIDANCE))
    }

    @Test
    fun `mergeSystemPrompt skips todo guidance when null`() {
        val prompt = ConversationViewModel.mergeSystemPrompt(
            ragPrompt = null,
            todoGuidance = null,
            enabledSkills = emptyList()
        )
        assertFalse("null 指引不应注入", prompt.contains(ConversationViewModel.TODO_GUIDANCE))
    }

    // ==================== US-1707：ChatMessageSerializer 变体序列化 ====================

    @Test
    fun `serializer roundtrip preserves variants`() {
        val msgs = listOf(
            ChatMessage(1, Role.USER, "q", 1000L),
            ChatMessage(
                2, Role.ASSISTANT, "v2 content", 2000L,
                thinkingChain = "thinking v2",
                variants = listOf(
                    MessageVariant(content = "v1 content", createdAt = 1500L),
                    MessageVariant(content = "v2 content", thinkingChain = "thinking v2", createdAt = 2000L)
                ),
                activeVariantIndex = 1
            )
        )
        val json = io.prism.util.ChatMessageSerializer.encodeList(msgs)
        val decoded = io.prism.util.ChatMessageSerializer.decodeList(json)
        assertEquals(2, decoded[1].variants.size)
        assertEquals("v1 content", decoded[1].variants[0].content)
        assertEquals(1, decoded[1].activeVariantIndex)
        assertEquals("v2 content", decoded[1].content)
    }

    @Test
    fun `serializer omits empty variants field`() {
        val msgs = listOf(ChatMessage(1, Role.ASSISTANT, "plain", 1000L))
        val json = io.prism.util.ChatMessageSerializer.encodeList(msgs)
        assertFalse("空 variants 不应落盘（防膨胀）", json.contains("variants"))
        assertFalse("默认 activeVariantIndex 不应落盘", json.contains("activeVariantIndex"))
    }

    @Test
    fun `serializer decodes legacy json without variants`() {
        // 旧版会话 JSON（无 variants/activeVariantIndex 字段）必须兼容
        val legacyJson = """[{"id":1,"role":"ASSISTANT","content":"legacy","timestamp":1000}]"""
        val decoded = io.prism.util.ChatMessageSerializer.decodeList(legacyJson)
        assertEquals(1, decoded.size)
        assertEquals("legacy", decoded[0].content)
        assertTrue("旧数据 variants 应为空列表", decoded[0].variants.isEmpty())
        assertEquals(0, decoded[0].activeVariantIndex)
    }

    // ==================== US-1704：editAiMessage ====================

    @Test
    fun `editAiMessage updates content and thinking in place`() = runTest(mainDispatcher) {
        val vm = buildVm(QueuedProvider(listOf(listOf(StreamEvent.Delta("v1"), StreamEvent.Done))))
        vm.sendMessage("q")
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id

        val ok = vm.editAiMessage(aiId, "覆写后的正文", "覆写后的思维链")
        assertTrue("编辑应生效", ok)
        val edited = vm.messages.value.last()
        assertEquals("覆写后的正文", edited.content)
        assertEquals("覆写后的思维链", edited.thinkingChain)
        assertEquals("不新增消息（原地修正）", 2, vm.messages.value.size)
    }

    @Test
    fun `editAiMessage ignores non assistant and blank content`() = runTest(mainDispatcher) {
        val vm = buildVm(QueuedProvider(listOf(listOf(StreamEvent.Delta("a"), StreamEvent.Done))))
        vm.sendMessage("q")
        advanceUntilIdle()
        val userId = vm.messages.value.first().id
        assertFalse("USER 消息应被忽略", vm.editAiMessage(userId, "x", null))
        val aiId = vm.messages.value.last().id
        assertFalse("空白正文应被忽略", vm.editAiMessage(aiId, "   ", null))
        assertEquals("a", vm.messages.value.last().content)
    }

    @Test
    fun `editAiMessage syncs active variant`() = runTest(mainDispatcher) {
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("v2"), StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        // 注意：重试生成会创建新占位消息（新 id），必须在重试后重新获取
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id
        assertEquals("v2", vm.messages.value.last().content)

        assertTrue(vm.editAiMessage(aiId, "v2-edited", null))
        val edited = vm.messages.value.last()
        assertEquals("v2-edited", edited.content)
        assertEquals("active 变体应同步编辑", "v2-edited", edited.variants[edited.activeVariantIndex].content)
    }

    // ==================== US-1706：rollback ====================

    @Test
    fun `rollback truncates after user message and re-answers`() = runTest(mainDispatcher) {
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("a1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("a2"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("a2-new"), StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q1")
        advanceUntilIdle()
        vm.sendMessage("q2")
        advanceUntilIdle()
        assertEquals(4, vm.messages.value.size)
        val u2 = vm.messages.value[2]

        assertTrue(vm.rollbackFromUserMessage(u2.id, saveCopyFirst = false))
        advanceUntilIdle()
        val msgs = vm.messages.value
        assertEquals("回退后保留 u1/a1/u2 + 新回答", 4, msgs.size)
        assertEquals("q1", msgs[0].content)
        assertEquals("a1", msgs[1].content)
        assertEquals("q2", msgs[2].content)
        assertEquals("a2-new", msgs[3].content)
    }

    @Test
    fun `rollback with saveCopyFirst creates snapshot session`() = runTest(mainDispatcher) {
        val sessionRepo = SessionRepository(boxStore)
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("a1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("a2"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("a2-new"), StreamEvent.Done)
                )
            ),
            sessionRepository = sessionRepo
        )
        vm.sendMessage("q1")
        advanceUntilIdle()
        vm.sendMessage("q2")
        advanceUntilIdle()
        val before = vm.messages.value.size
        val u2 = vm.messages.value[2]

        assertTrue(vm.rollbackFromUserMessage(u2.id, saveCopyFirst = true))
        advanceUntilIdle()
        // 副本会话包含回退前的完整 4 条消息
        val all = sessionRepo.sessions.value
        val copy = all.firstOrNull { it.title.endsWith("（副本）") }
        assertTrue("应存在副本会话", copy != null)
        assertEquals("副本应含回退前全部 $before 条消息", before, io.prism.util.ChatMessageSerializer.decodeList(copy!!.messagesJson).size)
    }

    @Test
    fun `rollback ignores non user target`() = runTest(mainDispatcher) {
        val vm = buildVm(QueuedProvider(listOf(listOf(StreamEvent.Delta("a1"), StreamEvent.Done))))
        vm.sendMessage("q1")
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id
        assertFalse("非 USER 目标应被忽略", vm.rollbackFromUserMessage(aiId, saveCopyFirst = false))
    }

    // ==================== US-1708：regenerate / switchVariant ====================

    @Test
    fun `regenerate appends variant and keeps old versions`() = runTest(mainDispatcher) {
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("v2"), StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        val v1Msg = vm.messages.value.last()

        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val regenerated = vm.messages.value.last()
        assertEquals("v2", regenerated.content)
        assertEquals("应保留 2 个版本", 2, regenerated.variants.size)
        assertEquals("v1", regenerated.variants[0].content)
        assertEquals("v2", regenerated.variants[1].content)
        assertEquals("active 应指向最新版本", 1, regenerated.activeVariantIndex)
        assertEquals("消息总数不变（原地替换）", 2, vm.messages.value.size)
        assertEquals("首版本内容应与旧回复一致", v1Msg.content, regenerated.variants[0].content)
    }

    @Test
    fun `switchVariant restores old version content`() = runTest(mainDispatcher) {
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("v2"), StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        // 重试生成会创建新占位消息（新 id），切换目标必须取自重试后的消息
        vm.regenerateLastAiMessage()
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id

        assertTrue(vm.switchVariant(aiId, 0))
        val switched = vm.messages.value.last()
        assertEquals("v1", switched.content)
        assertEquals(0, switched.activeVariantIndex)
        // 切回最新
        assertTrue(vm.switchVariant(aiId, 1))
        assertEquals("v2", vm.messages.value.last().content)
    }

    @Test
    fun `switchVariant ignores out of range index`() = runTest(mainDispatcher) {
        val vm = buildVm(QueuedProvider(listOf(listOf(StreamEvent.Delta("v1"), StreamEvent.Done))))
        vm.sendMessage("q")
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id
        assertFalse("无 variants 时切换应忽略", vm.switchVariant(aiId, 0))
    }

    @Test
    fun `saveCopyAsNewSession strips variant thinking chains when thinking disabled`() = runTest(mainDispatcher) {
        // guardrail P2-1（隐私 S1 旁路）：深度思考关闭时，variants 内历史版本思维链必须一并剥离
        val sessionRepo = SessionRepository(boxStore)
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.ReasoningDelta("secret-thinking-v1"), StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.ReasoningDelta("secret-thinking-v2"), StreamEvent.Delta("v2"), StreamEvent.Done)
                )
            ),
            sessionRepository = sessionRepo
        )
        vm.setThinkingEnabled(false)
        vm.sendMessage("q")
        advanceUntilIdle()
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        assertTrue(vm.saveCopyAsNewSession())

        val copy = sessionRepo.sessions.value.firstOrNull { it.title.endsWith("（副本）") }
        assertTrue(copy != null)
        val persisted = io.prism.util.ChatMessageSerializer.decodeList(copy!!.messagesJson)
        val ai = persisted.last()
        assertEquals("v2", ai.content)
        assertNull("顶层思维链应被剥离", ai.thinkingChain)
        assertTrue("应存在历史版本", ai.variants.isNotEmpty())
        ai.variants.forEach { variant ->
            assertNull("变体内思维链应被剥离（P2-1）", variant.thinkingChain)
        }
    }

    // ==================== ac-verifier 补充用例（TKN-V1B17-ACCEPTANCE-001）====================

    // ---- AC-1 边界与防御（TodoLocalToolExecutor）----

    @Test
    fun `todo_write accepts exactly maxItems boundary`() = kotlinx.coroutines.test.runTest {
        // 边界值分析：恰好 MAX_ITEMS=8 项（7 completed + 1 in_progress）必须接受
        val executor = TodoLocalToolExecutor()
        val items = (1..7).map {
            mapOf("content" to "t$it", "activeForm" to "t$it", "status" to "completed")
        } + mapOf("content" to "当前步骤", "activeForm" to "当前步骤", "status" to "in_progress")
        val result = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to items))
        assertFalse("恰好 8 项应接受（上边界）", result.startsWith("错误："))
        assertEquals(8, executor.state.value.items.size)
        assertTrue("进度计数应为 7/8", result.contains("任务清单已更新（7/8 完成）"))
        assertEquals("version 应推进到 1", 1L, executor.state.value.version)
    }

    @Test
    fun `todo_write rejects invalid status and keeps version`() = kotlinx.coroutines.test.runTest {
        // 等价类-无效：status 三态之外的值拒绝，且被拒更新不推进 version
        val executor = TodoLocalToolExecutor()
        executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf("todos" to listOf(mapOf("content" to "a", "activeForm" to "a", "status" to "in_progress")))
        )
        assertEquals(1L, executor.state.value.version)
        val bad = listOf(
            mapOf("content" to "a", "activeForm" to "a", "status" to "in_progress"),
            mapOf("content" to "b", "activeForm" to "b", "status" to "doing")
        )
        val result = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to bad))
        assertTrue("非法 status 应回灌错误", result.startsWith("错误："))
        assertTrue("错误应指明非法值", result.contains("doing"))
        assertEquals("被拒绝的更新不应推进 version", 1L, executor.state.value.version)
        assertEquals("被拒绝的更新不应改变清单", 1, executor.state.value.items.size)
    }

    @Test
    fun `todo_write tolerates malformed argument types without crash`() = kotlinx.coroutines.test.runTest {
        // 防御性：todos 非 List（String/Int/Map）与元素非 Map 均不得崩溃；
        // 解析为空走「空清单 = 清空」语义（validate 对空清单返回 null）
        val executor = TodoLocalToolExecutor()
        executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf("todos" to listOf(mapOf("content" to "a", "activeForm" to "a", "status" to "in_progress")))
        )
        assertEquals(1, executor.state.value.items.size)

        val r1 = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to "not-a-list"))
        assertFalse("todos 为 String 应防御不崩溃", r1.startsWith("错误："))
        assertEquals("解析为空应走清空语义", 0, executor.state.value.items.size)

        val r2 = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to 42))
        assertFalse("todos 为 Int 应防御不崩溃", r2.startsWith("错误："))

        val r3 = executor.execute(TodoLocalToolExecutor.TOOL_NAME, mapOf("todos" to mapOf("content" to "x")))
        assertFalse("todos 为 Map 应防御不崩溃", r3.startsWith("错误："))

        val r4 = executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf("todos" to listOf("plain", 42, null, mapOf("status" to "in_progress")))
        )
        assertFalse("元素非 Map / 缺 content 应逐项跳过不崩溃", r4.startsWith("错误："))
        assertEquals(0, executor.state.value.items.size)

        val r5 = executor.execute(TodoLocalToolExecutor.TOOL_NAME, emptyMap())
        assertFalse("缺 todos 键应防御不崩溃", r5.startsWith("错误："))
    }

    @Test
    fun `todo_write activeForm falls back to content when blank`() = kotlinx.coroutines.test.runTest {
        // 边界：activeForm 缺省/空白时回退 content（trim 后）
        val executor = TodoLocalToolExecutor()
        executor.execute(
            TodoLocalToolExecutor.TOOL_NAME,
            mapOf("todos" to listOf(mapOf("content" to "  打开淘宝搜索  ", "status" to "in_progress")))
        )
        val item = executor.state.value.items.single()
        assertEquals("content 应 trim", "打开淘宝搜索", item.content)
        assertEquals("activeForm 缺省应回退 content", "打开淘宝搜索", item.activeForm)
        assertEquals("in_progress", item.status)
    }

    // ---- AC-2 开关组合判定表 ----

    @Test
    fun `buildTools combines webSearch and todoWrite flags correctly`() {
        val both = ConversationViewModel.buildTools(emptyList(), webSearchEnabled = true, todoWriteEnabled = true)
        val bothNames = both.map { it.function.name }
        assertTrue("双开应同时含 todo_write", bothNames.contains(TodoLocalToolExecutor.TOOL_NAME))
        assertTrue("双开应同时含 web_search", bothNames.any { it.startsWith("web_search") })

        val onlySearch = ConversationViewModel.buildTools(emptyList(), webSearchEnabled = true, todoWriteEnabled = false)
        assertTrue(onlySearch.any { it.function.name.startsWith("web_search") })
        assertFalse("todoWrite 关闭不应含 todo_write", onlySearch.any { it.function.name == TodoLocalToolExecutor.TOOL_NAME })

        val onlyTodo = ConversationViewModel.buildTools(emptyList(), webSearchEnabled = false, todoWriteEnabled = true)
        assertTrue(onlyTodo.any { it.function.name == TodoLocalToolExecutor.TOOL_NAME })
        assertTrue("webSearch 关闭不应含 web_search", onlyTodo.none { it.function.name.startsWith("web_search") })
    }

    // ---- AC-8 边界与状态机（regenerate / markCompleted / switchVariant）----

    @Test
    fun `markCompleted evicts oldest variant beyond MAX_VARIANTS`() = runTest(mainDispatcher) {
        // 边界值：初始 1 次生成 + 10 次重试 → 累计 11 个变体触发滑动淘汰
        val seqs = buildList {
            add(listOf(StreamEvent.Delta("c0"), StreamEvent.Done))
            repeat(10) { i -> add(listOf(StreamEvent.Delta("v${i + 1}"), StreamEvent.Done)) }
        }
        val vm = buildVm(QueuedProvider(seqs))
        vm.sendMessage("q")
        advanceUntilIdle()
        repeat(10) {
            assertTrue(vm.regenerateLastAiMessage())
            advanceUntilIdle()
        }
        val ai = vm.messages.value.last()
        assertEquals("变体数应收敛到 MAX_VARIANTS 上限", ConversationViewModel.MAX_VARIANTS, ai.variants.size)
        assertEquals("active 应指向最新（尾部）版本", ai.variants.lastIndex, ai.activeVariantIndex)
        assertEquals("v10", ai.content)
        assertEquals("不变量：消息 content ≡ active 变体", ai.variants[ai.activeVariantIndex].content, ai.content)
        assertTrue("首版本 c0 应被滑动淘汰", ai.variants.none { it.content == "c0" })
        assertEquals("淘汰后最旧保留版本应为首个重试输出", "v1", ai.variants.first().content)
    }

    @Test
    fun `markCompleted blank content converges activeVariantIndex`() = runTest(mainDispatcher) {
        // guardrail P3-5 回归锁：重试以空内容终态（仅 Done，零增量）结束时，
        // 占位预留位 active=1 必须收敛（coerceIn）不越界
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val ai = vm.messages.value.last()
        assertEquals("空内容不应追加变体", 1, ai.variants.size)
        assertEquals("activeVariantIndex 应收敛到 0（不越界）", 0, ai.activeVariantIndex)
        assertEquals("", ai.content)
        assertEquals("继承的旧版本内容应保留", "v1", ai.variants[0].content)
        assertFalse("空内容消息不可再次作为重试锚点", vm.regenerateLastAiMessage())
    }

    @Test
    fun `markCompleted error path keeps variant index in bounds`() = runTest(mainDispatcher) {
        // 错误路径：Error 终态（已有部分增量）→ 错误文本成为变体追加，索引在界内
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("partial"), StreamEvent.Error("boom"))
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val ai = vm.messages.value.last()
        assertEquals("错误文本应成为变体追加", 2, ai.variants.size)
        assertEquals("active 应指向最新变体（在界内）", 1, ai.activeVariantIndex)
        assertTrue(ai.variants[1].content.contains("partial"))
        assertTrue("错误提示应入正文", ai.content.contains("⚠️"))
        assertEquals("不变量：消息 content ≡ active 变体", ai.variants[ai.activeVariantIndex].content, ai.content)
    }

    @Test
    fun `regenerate switch edit switch preserves variant invariant`() = runTest(mainDispatcher) {
        // 场景（任务书必测 c）：regenerate → switchVariant(0) → editAiMessage → 切回最新，
        // 「消息字段 ≡ variants[activeVariantIndex]」不变量全程保持；
        // 后续请求历史使用 active 版本内容（variants 本身不进请求语义）
        val provider = RecordingQueuedProvider(
            listOf(
                listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                listOf(StreamEvent.Delta("v2"), StreamEvent.Done),
                listOf(StreamEvent.Delta("next"), StreamEvent.Done)
            )
        )
        val vm = buildVm(provider)
        vm.sendMessage("q")
        advanceUntilIdle()
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id

        // 重试请求应以被重试消息的前置 user 消息重发（历史不含已删除的旧回复）
        val regenHistory = provider.capturedRequests[1]
        assertEquals("重试历史应仅含前置 user 消息", listOf("q"), regenHistory.filter { it.role == Role.USER }.map { it.content })
        assertTrue("重试历史不应残留旧回复", regenHistory.none { it.role == Role.ASSISTANT })

        assertTrue(vm.switchVariant(aiId, 0))
        assertEquals("v1", vm.messages.value.last().content)
        assertEquals(0, vm.messages.value.last().activeVariantIndex)

        assertTrue(vm.editAiMessage(aiId, "v1-edited", null))
        val afterEdit = vm.messages.value.last()
        assertEquals("v1-edited", afterEdit.content)
        assertEquals("编辑应同步进 active 变体", "v1-edited", afterEdit.variants[0].content)
        assertEquals("v2", afterEdit.variants[1].content)

        assertTrue(vm.switchVariant(aiId, 1))
        val afterSwitchBack = vm.messages.value.last()
        assertEquals("切回最新应恢复 v2", "v2", afterSwitchBack.content)
        assertEquals(1, afterSwitchBack.activeVariantIndex)
        assertEquals("非 active 变体的编辑应保留", "v1-edited", afterSwitchBack.variants[0].content)
        assertEquals("不变量：消息 content ≡ active 变体", afterSwitchBack.variants[afterSwitchBack.activeVariantIndex].content, afterSwitchBack.content)

        vm.sendMessage("q2")
        advanceUntilIdle()
        val nextHistory = provider.capturedRequests[2]
        val assistantContents = nextHistory.filter { it.role == Role.ASSISTANT }.map { it.content }
        assertTrue("后续请求应使用 active 版本内容", assistantContents.contains("v2"))
        assertFalse("非 active 变体内容不应进请求", assistantContents.contains("v1-edited"))
    }

    @Test
    fun `switchVariant rejects out of range indices with variants present`() = runTest(mainDispatcher) {
        // 边界：有 variants 时越界索引（-1 / ==size）拒绝且状态不变
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("v2"), StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val aiId = vm.messages.value.last().id
        val before = vm.messages.value.last()

        assertFalse("负索引应拒绝", vm.switchVariant(aiId, -1))
        assertFalse("越上界索引应拒绝", vm.switchVariant(aiId, 2))
        val after = vm.messages.value.last()
        assertEquals("被拒切换不应改变 active", before.activeVariantIndex, after.activeVariantIndex)
        assertEquals("被拒切换不应改变 content", before.content, after.content)
        assertEquals("被拒切换不应改变 variants", before.variants, after.variants)
    }

    @Test
    fun `batch17 operations ignored while typing`() = runTest(mainDispatcher) {
        // 并发边界：isTyping（生成中）窗口内 edit/switch/rollback/regenerate 全部忽略；
        // 门闩 Provider 使流挂起在 Delta 之后，构造稳定 isTyping=true 窗口
        val provider = GatedProvider()
        val vm = buildVm(provider)
        vm.sendMessage("q")
        assertTrue("前置：流应已产出部分增量", vm.messages.value.last().content.contains("partial"))
        assertTrue("前置：isTyping 应为 true", vm.isTyping.value)
        val aiId = vm.messages.value.last().id
        val userId = vm.messages.value.first().id

        assertFalse("编辑在 isTyping 期间应被忽略", vm.editAiMessage(aiId, "x", null))
        assertFalse("切换在 isTyping 期间应被忽略", vm.switchVariant(aiId, 0))
        assertFalse("回退在 isTyping 期间应被忽略", vm.rollbackFromUserMessage(userId, saveCopyFirst = false))
        assertFalse("重试在 isTyping 期间应被忽略", vm.regenerateLastAiMessage())

        provider.gate.complete(Unit)
        advanceUntilIdle()
        assertFalse("生成完成后 isTyping 应复位", vm.isTyping.value)
        assertTrue("生成完成后编辑应恢复生效", vm.editAiMessage(aiId, "edited-after-done", null))
    }

    @Test
    fun `regenerate uses real user message not system notice`() = runTest(mainDispatcher) {
        // guardrail R2 残留①验证：系统提示（isSystemNotice, USER 角色）不得成为重试重答依据
        val provider = RecordingQueuedProvider(
            listOf(
                listOf(StreamEvent.Delta("a1"), StreamEvent.Done),
                listOf(StreamEvent.Delta("a2"), StreamEvent.Done),
                listOf(StreamEvent.Delta("a3"), StreamEvent.Done)
            )
        )
        val vm = buildVm(provider)
        vm.sendMessage("q1")
        advanceUntilIdle()
        vm.notifyDocumentError() // 追加 isSystemNotice=true 的 USER 角色系统提示
        vm.sendMessage("q2")
        advanceUntilIdle()
        assertEquals(5, vm.messages.value.size)
        assertTrue(vm.messages.value.last().role == Role.ASSISTANT)

        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val regenHistory = provider.capturedRequests[2]
        val lastUser = regenHistory.last { it.role == Role.USER }
        assertEquals("重试应以真实用户消息重答", "q2", lastUser.content)
        assertTrue("系统提示文本不应成为重答依据", regenHistory.none { it.content.contains("文档解析失败") })
    }

    @Test
    fun `rollback rejects system notice as anchor`() = runTest(mainDispatcher) {
        // guardrail R2 残留①验证：系统提示不可作为回退锚点
        val vm = buildVm(QueuedProvider(listOf(listOf(StreamEvent.Delta("a1"), StreamEvent.Done))))
        vm.sendMessage("q1")
        advanceUntilIdle()
        vm.notifyDocumentError()
        val notice = vm.messages.value.last()
        assertTrue("前置：系统提示应为 USER 角色 + isSystemNotice", notice.role == Role.USER && notice.isSystemNotice)
        val before = vm.messages.value.size
        assertFalse("系统提示不可作为回退锚点", vm.rollbackFromUserMessage(notice.id, saveCopyFirst = false))
        assertEquals("被拒回退不应改变消息列表", before, vm.messages.value.size)
    }

    @Test
    fun `variant thinkingChain truncated to MAX_REASONING_LEN`() = runTest(mainDispatcher) {
        // 边界（PRD 风险表：thinkingChain 截断对齐 MAX_REASONING_LEN）：
        // markCompleted 捕获变体时思维链截断到 2000（SkillExecutor.MAX_REASONING_LEN）
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("v1"), StreamEvent.Done),
                    listOf(StreamEvent.ReasoningDelta("T".repeat(3000)), StreamEvent.Delta("v2"), StreamEvent.Done)
                )
            )
        )
        vm.sendMessage("q")
        advanceUntilIdle()
        assertTrue(vm.regenerateLastAiMessage())
        advanceUntilIdle()
        val ai = vm.messages.value.last()
        assertEquals("v2", ai.content)
        assertNull("首版本（无思维链）应为 null", ai.variants[0].thinkingChain)
        assertEquals(
            "变体思维链应截断到 MAX_REASONING_LEN",
            SkillExecutor.MAX_REASONING_LEN,
            ai.variants[1].thinkingChain?.length
        )
    }

    // ==================== ac-verifier 补充用例（TKN-V1B18-ACCEPTANCE-001，批次18 验收）====================

    // ---- AC-2 RAG opt-in：内存初始值 + init 异步恢复/缺库降级/异常 fail-safe 组合 ----

    @Test
    fun `batch18 ragTarget memory initial value is Off without any configuration`() = runTest(mainDispatcher) {
        // AC-2（guardrail P2-1 闭环独立验证）：内存初始值必须为 Off（opt-in）——
        // 未注入仓库/持久化读取返回前，buildRagPlan 与 kbTools 门控均按 Off 收敛，
        // 启动窗口期与异常路径不再复活「默认全库注入」（真机 RCA：未请求知识库却被注入）。
        val vm = buildB18Vm(ragDataStore = null)
        assertEquals(
            "内存初始 RAG 目标应为 Off（用户未明确开启知识库不得自动注入）",
            RagTarget.Off,
            vm.ragTarget.value
        )
    }

    @Test
    fun `batch18 init restores persisted all mode`() = runTest(mainDispatcher) {
        // AC-2 等价类-显式选择：持久化 "all" → init 异步恢复 AllLibraries（显式选择保留）
        val ds = FakePreferenceDataStore(b18Prefs(mode = "all", kbId = null))
        val vm = buildB18Vm(ragDataStore = ds)
        advanceUntilIdle()
        assertEquals(
            "init 应恢复持久化的 all 模式（用户显式选择不被 opt-in 默认覆盖）",
            RagTarget.AllLibraries,
            vm.ragTarget.value
        )
    }

    @Test
    fun `batch18 init keeps Off when persisted config missing`() = runTest(mainDispatcher) {
        // AC-2 等价类-缺失：DataStore 无记录（首次安装/设备上 preferences_pb 缺失）→ Off
        val ds = FakePreferenceDataStore(emptyPreferences())
        val vm = buildB18Vm(ragDataStore = ds)
        advanceUntilIdle()
        assertEquals(
            "持久化缺失（首次安装/prism_rag_config.preferences_pb 不存在）应收敛 Off",
            RagTarget.Off,
            vm.ragTarget.value
        )
    }

    @Test
    fun `batch18 init restores persisted specific library when library exists`() = runTest(mainDispatcher) {
        // AC-2 组合：specific + 库存在 → init 恢复 SpecificLibrary(kbId)
        val kbRepo = KnowledgeBaseRepository(boxStore)
        val kbId = kbRepo.save(KnowledgeBase(name = "工作库"))
        val ds = FakePreferenceDataStore(b18Prefs(mode = "specific", kbId = kbId))
        val vm = buildB18Vm(ragDataStore = ds)
        advanceUntilIdle()
        val target = vm.ragTarget.value
        assertTrue("库存在时 init 应恢复 SpecificLibrary", target is RagTarget.SpecificLibrary)
        assertEquals("kbId 应与持久化值一致", kbId, (target as RagTarget.SpecificLibrary).kbId)
    }

    @Test
    fun `batch18 init degrades persisted specific library to Off when library missing`() = runTest(mainDispatcher) {
        // AC-2 组合（ADR-028 MED-2）：specific + 指定库已被删除 → 降级 Off（不意外换库注入）
        val ds = FakePreferenceDataStore(b18Prefs(mode = "specific", kbId = 999L))
        val vm = buildB18Vm(ragDataStore = ds)
        advanceUntilIdle()
        assertEquals(
            "持久化指定库缺失（999 不存在）应降级 Off",
            RagTarget.Off,
            vm.ragTarget.value
        )
    }

    @Test
    fun `batch18 init keeps Off fail-safe when repository read throws`() = runTest(mainDispatcher) {
        // AC-2 异常路径（guardrail P2-1 窗口②）：init 读取抛异常 → fail-safe 停留 Off，
        // 不复活 AllLibraries（DataStore 持续读失败的用户不得复现「未请求知识库却被注入」）
        val vm = buildB18Vm(ragDataStore = ThrowingPreferenceDataStore())
        advanceUntilIdle()
        assertEquals(
            "init 读取异常应 fail-safe 停留 Off",
            RagTarget.Off,
            vm.ragTarget.value
        )
    }

    // ---- AC-3 loadSession id 唯一性（16:50 FATAL「Key N was already used」崩溃回归锁） ----

    @Test
    fun `batch18 loadSession advances nextId so new messages never collide with restored ids`() =
        runTest(mainDispatcher) {
            // AC-3 崩溃场景复刻：恢复会话消息 id（900/901）远大于新 VM 计数器（从 0 起）——
            // 旧行为不推进 nextId → 新消息 id 与恢复消息重复 → LazyColumn key 冲突 FATAL。
            val sessionRepo = SessionRepository(boxStore)
            val restored = listOf(
                ChatMessage(900L, Role.USER, "旧问题", 1000L),
                ChatMessage(901L, Role.ASSISTANT, "旧回答", 2000L)
            )
            val sessionId = sessionRepo.save(
                Session(title = "旧会话", messagesJson = io.prism.util.ChatMessageSerializer.encodeList(restored))
            )
            val vm = buildVm(
                QueuedProvider(listOf(listOf(StreamEvent.Delta("新回答"), StreamEvent.Done))),
                sessionRepository = sessionRepo
            )
            vm.loadSession(sessionId)
            advanceUntilIdle()
            assertEquals("恢复后应载入 2 条历史消息", 2, vm.messages.value.size)

            vm.sendMessage("新问题")
            advanceUntilIdle()
            val msgs = vm.messages.value
            assertEquals("恢复 2 条 + 新 user/AI 共 4 条", 4, msgs.size)
            val ids = msgs.map { it.id }
            assertEquals(
                "全部消息 id 全局唯一（LazyColumn key 崩溃回归锁）",
                ids.size,
                ids.distinct().size
            )
            assertEquals(
                "新用户消息 id 应为恢复最大 id + 1（nextId 推进语义）",
                902L,
                ids[2]
            )
            assertTrue("新 AI 消息 id 顺延唯一", ids[3] > ids[2])
        }

    @Test
    fun `batch18 loadSession with lower restored ids keeps counter monotonic`() = runTest(mainDispatcher) {
        // AC-3 边界：本会话已产生更大 id（nextId=2）后恢复小 id 会话（max=1）→
        // 计数器保持不回退，新消息 id（2/3）与恢复消息（1）无冲突。
        val sessionRepo = SessionRepository(boxStore)
        val restored = listOf(ChatMessage(1L, Role.USER, "早期消息", 500L))
        val sessionId = sessionRepo.save(
            Session(title = "早期会话", messagesJson = io.prism.util.ChatMessageSerializer.encodeList(restored))
        )
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("a0"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("a1"), StreamEvent.Done)
                )
            ),
            sessionRepository = sessionRepo
        )
        vm.sendMessage("q0")
        advanceUntilIdle()
        assertEquals(2, vm.messages.value.size)

        vm.loadSession(sessionId)
        advanceUntilIdle()
        vm.sendMessage("q1")
        advanceUntilIdle()
        val ids = vm.messages.value.map { it.id }
        assertEquals("载入 1 条 + 新 user/AI 共 3 条", 3, ids.size)
        assertEquals(
            "全部消息 id 全局唯一（计数器不回退）",
            ids.size,
            ids.distinct().size
        )
        assertTrue("新消息 id 应大于恢复最大 id（保持单调）", ids[1] > 1L && ids[2] > ids[1])
    }

    @Test
    fun `batch18 repeated loadSession stays idempotent for id generation`() = runTest(mainDispatcher) {
        // AC-3 幂等：重复 loadSession 同一会话不得使计数器回退/重复注入
        val sessionRepo = SessionRepository(boxStore)
        val restored = listOf(
            ChatMessage(900L, Role.USER, "旧问题", 1000L),
            ChatMessage(901L, Role.ASSISTANT, "旧回答", 2000L)
        )
        val sessionId = sessionRepo.save(
            Session(title = "旧会话", messagesJson = io.prism.util.ChatMessageSerializer.encodeList(restored))
        )
        val vm = buildVm(
            QueuedProvider(
                listOf(
                    listOf(StreamEvent.Delta("新回答1"), StreamEvent.Done),
                    listOf(StreamEvent.Delta("新回答2"), StreamEvent.Done)
                )
            ),
            sessionRepository = sessionRepo
        )
        vm.loadSession(sessionId)
        advanceUntilIdle()
        vm.loadSession(sessionId)
        advanceUntilIdle()
        assertEquals("重复 loadSession 不应重复注入消息", 2, vm.messages.value.size)
        vm.sendMessage("新问题")
        advanceUntilIdle()
        val ids = vm.messages.value.map { it.id }
        assertEquals(
            "重复恢复后新消息 id 仍全局唯一",
            ids.size,
            ids.distinct().size
        )
        assertTrue("新消息 id 仍应大于恢复最大 id", ids.last() > 901L)
    }

    @Test
    fun `batch18 loadSession with empty session restores safely`() = runTest(mainDispatcher) {
        // AC-3 退化场景：空会话（maxOfOrNull → -1L）→ 计数器不动，后续消息 id 从 0 正常生成
        val sessionRepo = SessionRepository(boxStore)
        val sessionId = sessionRepo.save(Session(title = "空会话", messagesJson = "[]"))
        val vm = buildVm(
            QueuedProvider(listOf(listOf(StreamEvent.Delta("回答"), StreamEvent.Done))),
            sessionRepository = sessionRepo
        )
        vm.loadSession(sessionId)
        advanceUntilIdle()
        assertEquals(0, vm.messages.value.size)
        vm.sendMessage("问题")
        advanceUntilIdle()
        val ids = vm.messages.value.map { it.id }
        assertEquals(2, ids.size)
        assertEquals("空会话恢复后 id 生成应不受影响", ids.size, ids.distinct().size)
    }

    // ---- 批次18 验收辅助（独立于既有 buildVm，避免改动既有基建） ----

    /**
     * 构造可注入 RAG 配置仓库的 [ConversationViewModel]（批次18 AC-2 专用）。
     *
     * @param ragDataStore RAG 配置 DataStore；null 表示不注入仓库（仅内存态）
     */
    private fun buildB18Vm(
        ragDataStore: DataStore<Preferences>?,
        sessionRepository: SessionRepository? = null,
        provider: ChatStreamProvider = QueuedProvider(
            listOf(listOf(StreamEvent.Delta("ok"), StreamEvent.Done))
        )
    ): ConversationViewModel = ConversationViewModel(
        providerRepository = ProviderConfigRepository(boxStore).apply {
            val active = ProviderConfig(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                apiKeyRef = "openai",
                models = listOf("gpt-4o")
            )
            save(active)
            setActive(findByName(active.name)!!.id)
        },
        provider = provider,
        embedder = B17StubEmbedder(),
        knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
        skillExecutor = null,
        mcpServerRepository = null,
        sessionRepository = sessionRepository,
        ioDispatcher = mainDispatcher,
        ragTargetConfigRepository = ragDataStore?.let { RagTargetConfigRepository(it) }
    )

    /** 构造 RAG 配置 Preferences（mode/kbId 可 null 模拟缺失字段）。 */
    private fun b18Prefs(mode: String?, kbId: Long?): Preferences {
        val mutable = emptyPreferences().toMutablePreferences()
        if (mode != null) mutable[stringPreferencesKey("rag_mode")] = mode
        if (kbId != null) mutable[longPreferencesKey("rag_kb_id")] = kbId
        return mutable.toPreferences()
    }

    /** 读取必抛异常的 DataStore fake（模拟 DataStore IO 持续失败）。 */
    private class ThrowingPreferenceDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> =
            kotlinx.coroutines.flow.flow { throw IllegalStateException("simulated datastore failure") }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences = throw IllegalStateException("simulated datastore failure")
    }

    // ==================== 辅助 ====================

    private fun buildVm(
        provider: ChatStreamProvider,
        sessionRepository: SessionRepository? = null
    ): ConversationViewModel = ConversationViewModel(
        providerRepository = ProviderConfigRepository(boxStore).apply {
            val active = ProviderConfig(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                apiKeyRef = "openai",
                models = listOf("gpt-4o")
            )
            save(active)
            setActive(findByName(active.name)!!.id)
        },
        provider = provider,
        embedder = B17StubEmbedder(),
        knowledgeBaseRepository = KnowledgeBaseRepository(boxStore),
        skillExecutor = null,
        mcpServerRepository = null,
        sessionRepository = sessionRepository,
        ioDispatcher = mainDispatcher
    ).apply { setRagTarget(RagTarget.Off) }
}

/** ObjectBox 构建辅助（隔离 MyObjectBox 直接引用，便于统一临时目录管理）。 */
private object MyObjectBoxBuilder {
    fun build(dir: File): BoxStore = io.prism.data.MyObjectBox.builder().directory(dir).build()
}

/**
 * 按调用序返回不同事件序列的 fake provider（批次17 测试专用：模拟多轮回答内容不同）。
 */
private class QueuedProvider(
    eventSequences: List<List<StreamEvent>>
) : ChatStreamProvider {
    private val queue = ArrayDeque(eventSequences)
    private val fallback = eventSequences.lastOrNull() ?: emptyList()

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
        val events = if (queue.isEmpty()) fallback else queue.removeFirst()
        return flow { events.forEach { emit(it) } }
    }
}

/** 空操作 Embedder（RAG 关闭场景不参与逻辑；命名区分 PhaseDTest 同名 fake）。 */
private class B17StubEmbedder : Embedder {
    override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
    override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    override fun isLoaded(): Boolean = true
    override fun checkAndUnload(maxIdleMs: Long): Boolean = false
    override fun close() {}
}

/**
 * 带请求捕获的 fake provider（ac-verifier 补充：验证请求历史构建不变量——
 * 每次调用记录收到的 messages 快照，供断言「请求使用 active 变体内容」等）。
 */
private class RecordingQueuedProvider(
    eventSequences: List<List<StreamEvent>>
) : ChatStreamProvider {
    private val queue = ArrayDeque(eventSequences)
    private val fallback = eventSequences.lastOrNull() ?: emptyList()

    /** 每次请求收到的消息历史快照（按调用序）。 */
    val capturedRequests = mutableListOf<List<ChatMessage>>()

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
        capturedRequests.add(messages.toList())
        val events = if (queue.isEmpty()) fallback else queue.removeFirst()
        return flow { events.forEach { emit(it) } }
    }
}

/**
 * 门闩 fake provider（ac-verifier 补充：构造稳定 isTyping=true 窗口——
 * 流在 Delta 后挂起于 [gate]，测试完成后 complete 放行 Done）。
 */
private class GatedProvider : ChatStreamProvider {

    /** 门闩：complete 前流挂起，模拟生成中窗口。 */
    val gate = CompletableDeferred<Unit>()

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
        emit(StreamEvent.Delta("partial"))
        gate.await()
        emit(StreamEvent.Done)
    }
}
