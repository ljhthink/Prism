package io.prism.memory

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.data.ProviderConfig
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SlidingWindowMemoryManager 单元测试（US-032 AC-2 + AC-3 + AC-5）。
 *
 * 测试覆盖：
 * - 滑动窗口边界（messages.size == N 不触发摘要，messages.size == N+1 触发）
 * - 摘要触发（超出 N 轮时调用 summarizer）
 * - 摘要注入（toSummarySystemPromptSection 格式化）
 * - N 可配置（不同 N 值测试）
 * - 摘要失败降级（summarizer 返回 null 时截断）
 * - 空消息列表处理
 * - 近期消息正确性（保留最后 N 条）
 */
class SlidingWindowMemoryManagerTest {

    private val testConfig = ProviderConfig(
        name = "test-provider",
        baseUrl = "https://api.test.com/v1",
        apiKeyRef = "test-key-ref",
        models = listOf("test-model"),
        headers = emptyMap()
    )

    private lateinit var fakeProvider: FakeCompletionProvider
    private lateinit var summarizer: ConversationSummarizer
    private lateinit var configRepository: MemoryConfigRepository
    private lateinit var manager: SlidingWindowMemoryManager

    @Before
    fun setUp() {
        fakeProvider = FakeCompletionProvider("测试摘要文本")
        summarizer = ConversationSummarizer(fakeProvider)
        configRepository = MemoryConfigRepository(
            io.prism.security.FakePreferenceDataStore(emptyPreferences())
        )
        manager = SlidingWindowMemoryManager(summarizer, configRepository)
    }

    // ==================== AC-2: 滑动窗口边界 ====================

    @Test
    fun processMessages_empty_messages_returns_empty_no_summary() = runBlocking {
        val result = manager.processMessages(emptyList(), testConfig)
        assertNull("空消息列表不应有摘要", result.summary)
        assertTrue("空消息列表应返回空 recentMessages", result.recentMessages.isEmpty())
    }

    @Test
    fun processMessages_size_equals_N_returns_all_no_summary() = runBlocking {
        configRepository.setWindowSize(5)
        val messages = createMessages(5)
        val result = manager.processMessages(messages, testConfig)
        assertNull("消息数 == N 时不应触发摘要", result.summary)
        assertEquals("消息数 == N 时应返回全部消息", 5, result.recentMessages.size)
        assertEquals("返回的消息应与输入一致", messages, result.recentMessages)
    }

    @Test
    fun processMessages_size_less_than_N_returns_all_no_summary() = runBlocking {
        configRepository.setWindowSize(10)
        val messages = createMessages(3)
        val result = manager.processMessages(messages, testConfig)
        assertNull("消息数 < N 时不应触发摘要", result.summary)
        assertEquals("消息数 < N 时应返回全部消息", 3, result.recentMessages.size)
    }

    @Test
    fun processMessages_size_equals_N_plus_1_triggers_summary() = runBlocking {
        configRepository.setWindowSize(5)
        val messages = createMessages(6)
        val result = manager.processMessages(messages, testConfig)
        assertNotNull("消息数 == N+1 时应触发摘要", result.summary)
        assertEquals("应保留最近 N 条消息", 5, result.recentMessages.size)
    }

    // ==================== AC-2 + AC-3: 摘要触发 + 保留近期消息 ====================

    @Test
    fun processMessages_size_greater_than_N_triggers_summary_and_keeps_last_N() = runBlocking {
        configRepository.setWindowSize(3)
        val messages = createMessages(10)
        val result = manager.processMessages(messages, testConfig)
        assertNotNull("消息数 > N 时应触发摘要", result.summary)
        assertEquals("应保留最近 N 条消息", 3, result.recentMessages.size)
        // 验证保留的是最后 3 条
        assertEquals("应保留消息 8", 8L, result.recentMessages[0].id)
        assertEquals("应保留消息 9", 9L, result.recentMessages[1].id)
        assertEquals("应保留消息 10", 10L, result.recentMessages[2].id)
    }

    @Test
    fun processMessages_summary_contains_llm_generated_text() = runBlocking {
        configRepository.setWindowSize(2)
        val messages = createMessages(5)
        val result = manager.processMessages(messages, testConfig)
        assertEquals("摘要应为 LLM 生成的文本", "测试摘要文本", result.summary)
    }

    @Test
    fun processMessages_summarizer_receives_old_messages() = runBlocking {
        configRepository.setWindowSize(3)
        val messages = createMessages(5)
        manager.processMessages(messages, testConfig)
        // summarizer 应收到前 2 条消息（dropLast(3)）
        assertEquals("Summarizer 应收到旧消息", 2, fakeProvider.lastMessages?.size)
        assertEquals("第一条旧消息 id=1", 1L, fakeProvider.lastMessages?.first()?.id)
        assertEquals("第二条旧消息 id=2", 2L, fakeProvider.lastMessages?.last()?.id)
    }

    // ==================== AC-4: N 可配置 ====================

    @Test
    fun processMessages_N_configurable_to_small_value() = runBlocking {
        configRepository.setWindowSize(1)
        val messages = createMessages(3)
        val result = manager.processMessages(messages, testConfig)
        assertNotNull("N=1 时 3 条消息应触发摘要", result.summary)
        assertEquals("N=1 时应只保留最后 1 条消息", 1, result.recentMessages.size)
        assertEquals("应保留最后一条消息", 3L, result.recentMessages[0].id)
    }

    @Test
    fun processMessages_N_configurable_to_large_value() = runBlocking {
        configRepository.setWindowSize(20)
        val messages = createMessages(5)
        val result = manager.processMessages(messages, testConfig)
        assertNull("N=20 时 5 条消息不应触发摘要", result.summary)
        assertEquals("N=20 时应返回全部 5 条消息", 5, result.recentMessages.size)
    }

    @Test
    fun processMessages_runtime_N_change_takes_effect() = runBlocking {
        // 第一次：N=10，不触发摘要
        configRepository.setWindowSize(10)
        val messages = createMessages(5)
        val result1 = manager.processMessages(messages, testConfig)
        assertNull("N=10 时 5 条消息不触发摘要", result1.summary)

        // 运行时修改 N=3，触发摘要
        configRepository.setWindowSize(3)
        val result2 = manager.processMessages(messages, testConfig)
        assertNotNull("N=3 时 5 条消息应触发摘要", result2.summary)
        assertEquals("N=3 时应保留最后 3 条消息", 3, result2.recentMessages.size)
    }

    // ==================== AC-1 + AC-5: 摘要失败降级 ====================

    @Test
    fun processMessages_summary_failure_degrades_to_truncation() = runBlocking {
        fakeProvider = FakeCompletionProvider(returnValue = null)  // 模拟摘要失败
        summarizer = ConversationSummarizer(fakeProvider)
        manager = SlidingWindowMemoryManager(summarizer, configRepository)
        configRepository.setWindowSize(2)
        val messages = createMessages(5)
        val result = manager.processMessages(messages, testConfig)
        assertNotNull("摘要失败时应降级为截断", result.summary)
        assertTrue("截断摘要应包含前缀标注", result.summary!!.contains("截断"))
    }

    @Test
    fun processMessages_summary_network_error_degrades_to_truncation() = runBlocking {
        fakeProvider = FakeCompletionProvider(throwOnCall = true)  // 模拟网络异常
        summarizer = ConversationSummarizer(fakeProvider)
        manager = SlidingWindowMemoryManager(summarizer, configRepository)
        configRepository.setWindowSize(2)
        val messages = createMessages(5)
        val result = manager.processMessages(messages, testConfig)
        assertNotNull("网络异常时应降级为截断", result.summary)
        assertTrue("截断摘要应包含前缀标注", result.summary!!.contains("截断"))
    }

    @Test
    fun processMessages_truncation_preserves_old_message_content() = runBlocking {
        fakeProvider = FakeCompletionProvider(returnValue = null)
        summarizer = ConversationSummarizer(fakeProvider)
        manager = SlidingWindowMemoryManager(summarizer, configRepository)
        configRepository.setWindowSize(2)
        // 4 条消息 + window=2 → oldMessages=[m1,m2], recentMessages=[m3,m4]
        // 截断摘要应保留 m1、m2 的内容
        val messages = listOf(
            ChatMessage(1, Role.USER, "用户提问1", 1000L),
            ChatMessage(2, Role.ASSISTANT, "AI回答1", 2000L),
            ChatMessage(3, Role.USER, "用户提问2", 3000L),
            ChatMessage(4, Role.ASSISTANT, "AI回答2", 4000L)
        )
        val result = manager.processMessages(messages, testConfig)
        assertNotNull(result.summary)
        assertTrue("截断摘要应包含旧消息内容", result.summary!!.contains("用户提问1"))
        assertTrue("截断摘要应包含旧消息内容", result.summary!!.contains("AI回答1"))
    }

    // ==================== AC-3: 摘要注入格式 ====================

    @Test
    fun toSummarySystemPromptSection_formats_summary_for_injection() = runBlocking {
        configRepository.setWindowSize(2)
        val messages = createMessages(5)
        val result = manager.processMessages(messages, testConfig)
        val section = result.toSummarySystemPromptSection()
        assertNotNull("有摘要时应返回格式化文本", section)
        assertTrue("应包含 [早期对话摘要] 前缀", section!!.contains("[早期对话摘要]"))
        assertTrue("应包含摘要内容", section.contains("测试摘要文本"))
    }

    @Test
    fun toSummarySystemPromptSection_returns_null_when_no_summary() = runBlocking {
        configRepository.setWindowSize(10)
        val messages = createMessages(3)
        val result = manager.processMessages(messages, testConfig)
        assertNull("无摘要时 toSummarySystemPromptSection 应返回 null", result.toSummarySystemPromptSection())
    }

    // ==================== truncateMessages 纯函数测试 ====================

    @Test
    fun truncateMessages_empty_messages_returns_fallback() {
        val result = manager.truncateMessages(emptyList())
        assertEquals("空消息列表应返回占位文本", SlidingWindowMemoryManager.FALLBACK_EMPTY_SUMMARY, result)
    }

    @Test
    fun truncateMessages_includes_role_labels() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "hello", 1000L),
            ChatMessage(2, Role.ASSISTANT, "hi", 2000L),
            ChatMessage(3, Role.TOOL, "result", 3000L)
        )
        val result = manager.truncateMessages(messages)
        assertTrue("应包含 [user] 标签", result.contains("[user]"))
        assertTrue("应包含 [assistant] 标签", result.contains("[assistant]"))
        assertTrue("应包含 [tool] 标签", result.contains("[tool]"))
    }

    @Test
    fun truncateMessages_truncates_long_content() {
        val longContent = "a".repeat(200)
        val messages = listOf(
            ChatMessage(1, Role.USER, longContent, 1000L)
        )
        val result = manager.truncateMessages(messages)
        // 每条消息截断到 100 字
        val contentPart = result.substringAfter("[user] ")
        assertTrue("单条消息应截断到 100 字", contentPart.length <= 100)
    }

    @Test
    fun truncateMessages_respects_total_length_limit() {
        val messages = (1..20).map { i ->
            ChatMessage(i.toLong(), Role.USER, "message_$i".repeat(20), i.toLong() * 1000)
        }
        val result = manager.truncateMessages(messages)
        assertTrue(
            "截断摘要总长应 ≤ ${SlidingWindowMemoryManager.MAX_TRUNCATED_SUMMARY_LENGTH + 100}（含前缀和后缀）",
            result.length <= SlidingWindowMemoryManager.MAX_TRUNCATED_SUMMARY_LENGTH + 50
        )
    }

    @Test
    fun truncateMessages_includes_truncation_prefix() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        val result = manager.truncateMessages(messages)
        assertTrue(
            "截断摘要应包含前缀标注",
            result.startsWith(SlidingWindowMemoryManager.TRUNCATION_PREFIX)
        )
    }

    // ==================== M-2 coerceIn 上界防御（guardrail-enforcer 纵深防御） ====================

    @Test
    fun processMessages_coerces_oversized_N_to_max() = runBlocking {
        // 模拟 DataStore 被外部写入超大值（绕过 setWindowSize 校验），
        // 验证 processMessages 的 coerceIn 防御将 N 限制为 MAX_WINDOW_SIZE。
        // 写入 N=1000（远超 MAX=50），提供 60 条消息：
        // - 若无 coerceIn：windowSize=1000 > 60，全部消息被视为"近期"，无摘要
        // - 有 coerceIn：windowSize=50 < 60，触发摘要，保留最后 50 条
        val oversizedDataStore = io.prism.security.FakePreferenceDataStore(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                androidx.datastore.preferences.core.intPreferencesKey("sliding_window_size") to 1000
            )
        )
        val oversizedConfigRepo = MemoryConfigRepository(oversizedDataStore)
        val oversizedManager = SlidingWindowMemoryManager(summarizer, oversizedConfigRepo)

        val messages = createMessages(60)
        val result = oversizedManager.processMessages(messages, testConfig)

        // coerceIn(1, 50) 后 N=50 < 60 → 触发摘要
        assertNotNull("超大 N 应被 coerceIn 到 MAX，触发摘要", result.summary)
        assertEquals("应保留最后 MAX_WINDOW_SIZE 条消息", 50, result.recentMessages.size)
        assertEquals("旧消息应为前 10 条", 10, messages.size - result.recentMessages.size)
    }

    @Test
    fun processMessages_coerces_zero_N_to_min() = runBlocking {
        // 模拟 DataStore 损坏导致 N=0，验证 coerceIn 下界防御
        val corruptedDataStore = io.prism.security.FakePreferenceDataStore(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                androidx.datastore.preferences.core.intPreferencesKey("sliding_window_size") to 0
            )
        )
        val corruptedConfigRepo = MemoryConfigRepository(corruptedDataStore)
        val corruptedManager = SlidingWindowMemoryManager(summarizer, corruptedConfigRepo)

        val messages = createMessages(5)
        val result = corruptedManager.processMessages(messages, testConfig)

        // coerceIn(1, 50) 后 N=1 < 5 → 触发摘要，保留最后 1 条
        assertNotNull("N=0 应被 coerceIn 到 MIN=1，触发摘要", result.summary)
        assertEquals("应保留最后 1 条消息", 1, result.recentMessages.size)
    }

    // ==================== 辅助函数 ====================

    /** 创建 N 条测试消息（id=1..N，交替 user/assistant）。 */
    private fun createMessages(count: Int): List<ChatMessage> =
        (1..count).map { i ->
            ChatMessage(
                id = i.toLong(),
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "消息内容 $i",
                timestamp = i.toLong() * 1000
            )
        }

    /**
     * Fake ChatCompletionProvider 用于测试。
     *
     * @param returnValue chatCompletion 返回的固定值（null 模拟失败）
     * @param throwOnCall 是否在调用时抛普通异常
     */
    private class FakeCompletionProvider(
        private val returnValue: String? = null,
        private val throwOnCall: Boolean = false
    ) : ChatCompletionProvider {

        var lastConfig: ProviderConfig? = null
            private set
        var lastMessages: List<ChatMessage>? = null
            private set
        var lastSystemPrompt: String? = null
            private set
        var lastRagContext: String? = null
            private set

        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?
        ): String? {
            lastConfig = config
            lastMessages = messages
            lastSystemPrompt = systemPrompt
            lastRagContext = ragContext
            return if (throwOnCall) throw RuntimeException("test error") else returnValue
        }
    }
}
