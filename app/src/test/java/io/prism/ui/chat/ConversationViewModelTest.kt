package io.prism.ui.chat

import io.objectbox.BoxStore
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ConversationViewModel 单元测试 —— 验证发送逻辑与流式接入（US-005 AC-4 / US-006 AC-5）。
 *
 * 覆盖：
 * - 发送消息追加用户消息 + 流式 AI 回复（注入 fake [ChatStreamProvider]）
 * - 空白输入被忽略
 * - 连续发送消息 id 递增
 * - 无激活 Provider 时追加提示消息而非崩溃
 * - 流式增量 token 被正确追加到 AI 消息
 *
 * 依赖注入：本测试通过 [FakeChatStreamProvider] 注入确定性的 [StreamEvent] 序列，
 * 避免依赖真实网络 / MockEngine（MockEngine 不支持 Ktor SSE `SSECapability`，见 ADR-004 4.7）。
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

    /** 构造注入 fake Provider 的 VM（返回流式 "该模型已回复"）。 */
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
        return ConversationViewModel(repo, provider)
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
        // id 递增：0,1,2,3
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
        val vm = ConversationViewModel(repo, provider)

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
        val vm = ConversationViewModel(repo, provider)

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
        val vm = ConversationViewModel(repo, provider)

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
        val vm = ConversationViewModel(repo, provider)
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
}

/** 确定性 [ChatStreamProvider] fake —— 按给定序列发射事件，供 VM 测试注入。 */
private class FakeChatStreamProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
    override fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent> =
        flow { events.forEach { emit(it) } }
}

/** 记录每次传入 [messages] 与 [config] 的 fake，用于断言请求历史与目标 Provider（CR-02 / US-007）。 */
private class RecordingChatStreamProvider(private val events: List<StreamEvent>) : ChatStreamProvider {
    val receivedMessages = mutableListOf<List<ChatMessage>>()
    val receivedConfigs = mutableListOf<ProviderConfig>()

    override fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent> {
        receivedConfigs += config
        receivedMessages += messages
        return flow { events.forEach { emit(it) } }
    }
}

/** 多轮 fake：按调用序依次返回不同事件序列，并记录每次请求历史（guardrail 发现 1 多轮场景）。 */
private class MultiRoundRecordingProvider(private val eventSequences: List<List<StreamEvent>>) : ChatStreamProvider {
    val receivedMessages = mutableListOf<List<ChatMessage>>()
    private var call = 0

    override fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent> {
        receivedMessages += messages
        val events = eventSequences[call.coerceAtMost(eventSequences.size - 1)]
        call++
        return flow { events.forEach { emit(it) } }
    }
}