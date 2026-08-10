package io.prism.memory

import io.prism.data.ProviderConfig
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConversationSummarizer 单元测试（US-032 AC-1）。
 *
 * 测试覆盖：
 * - 空消息列表返回 null
 * - 正常摘要生成（fake provider 返回摘要）
 * - 摘要失败降级（fake provider 返回 null）
 * - 网络异常降级（fake provider 抛异常）
 * - CancellationException 正确重抛
 * - 摘要 prompt 非空且包含关键指示
 * - ProviderConfig 正确传递给 provider
 */
class ConversationSummarizerTest {

    /** 测试用 ProviderConfig。 */
    private val testConfig = ProviderConfig(
        name = "test-provider",
        baseUrl = "https://api.test.com/v1",
        apiKeyRef = "test-key-ref",
        models = listOf("test-model"),
        headers = emptyMap()
    )

    @Test
    fun summarize_returns_null_for_empty_messages() = runBlocking {
        val summarizer = ConversationSummarizer(FakeCompletionProvider("summary"))
        val result = summarizer.summarize(emptyList(), testConfig)
        assertNull("空消息列表应返回 null", result)
    }

    @Test
    fun summarize_returns_summary_on_success() = runBlocking {
        val provider = FakeCompletionProvider("用户询问了 Kotlin 协程的用法。")
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "什么是 Kotlin 协程？", 1000L),
            ChatMessage(2, Role.ASSISTANT, "协程是轻量级线程...", 2000L)
        )
        val result = summarizer.summarize(messages, testConfig)
        assertEquals("用户询问了 Kotlin 协程的用法。", result)
    }

    @Test
    fun summarize_returns_null_when_provider_returns_null() = runBlocking {
        val provider = FakeCompletionProvider(null)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        val result = summarizer.summarize(messages, testConfig)
        assertNull("Provider 返回 null 时应降级为 null", result)
    }

    @Test
    fun summarize_returns_null_when_provider_throws_exception() = runBlocking {
        val provider = FakeCompletionProvider(throwOnCall = true)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        val result = summarizer.summarize(messages, testConfig)
        assertNull("Provider 抛异常时应降级为 null", result)
    }

    @Test(expected = CancellationException::class)
    fun summarize_rethrows_cancellation_exception(): Unit = runBlocking {
        val provider = FakeCompletionProvider(throwCancellation = true)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        summarizer.summarize(messages, testConfig)
    }

    @Test
    fun summarize_passes_config_to_provider() = runBlocking {
        val provider = FakeCompletionProvider("summary")
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        summarizer.summarize(messages, testConfig)
        assertEquals("Provider 应收到正确的 config", testConfig, provider.lastConfig)
    }

    @Test
    fun summarize_passes_messages_to_provider() = runBlocking {
        val provider = FakeCompletionProvider("summary")
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "hello", 1000L),
            ChatMessage(2, Role.ASSISTANT, "hi", 2000L),
            ChatMessage(3, Role.USER, "bye", 3000L)
        )
        summarizer.summarize(messages, testConfig)
        assertEquals("Provider 应收到完整的消息列表", messages, provider.lastMessages)
    }

    @Test
    fun summarize_passes_summarization_prompt_as_system_prompt() = runBlocking {
        val provider = FakeCompletionProvider("summary")
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        summarizer.summarize(messages, testConfig)
        assertNotNull("systemPrompt 不应为 null", provider.lastSystemPrompt)
        assertTrue(
            "systemPrompt 应包含摘要指示",
            provider.lastSystemPrompt?.contains("摘要") == true
        )
    }

    @Test
    fun buildSummarizationPrompt_returns_non_empty_prompt() {
        val provider = FakeCompletionProvider("summary")
        val summarizer = ConversationSummarizer(provider)
        val prompt = summarizer.buildSummarizationPrompt()
        assertTrue("Prompt 不应为空", prompt.isNotBlank())
        assertTrue("Prompt 应包含 '摘要' 关键词", prompt.contains("摘要"))
        assertTrue("Prompt 应包含 '200 字' 约束", prompt.contains("200 字"))
        assertTrue("Prompt 应包含 '第三人称' 约束", prompt.contains("第三人称"))
    }

    @Test
    fun summarize_passes_null_ragContext() = runBlocking {
        val provider = FakeCompletionProvider("summary")
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "test", 1000L)
        )
        summarizer.summarize(messages, testConfig)
        assertNull("ragContext 应为 null（摘要任务不需要 RAG 上下文）", provider.lastRagContext)
    }

    /**
     * Fake ChatCompletionProvider 用于测试。
     *
     * @param returnValue chatCompletion 返回的固定值（null 模拟失败）
     * @param throwOnCall 是否在调用时抛普通异常
     * @param throwCancellation 是否在调用时抛 CancellationException
     */
    private class FakeCompletionProvider(
        private val returnValue: String? = null,
        private val throwOnCall: Boolean = false,
        private val throwCancellation: Boolean = false
    ) : ChatCompletionProvider {

        var lastConfig: ProviderConfig? = null
            private set
        var lastMessages: List<ChatMessage>? = null
            private set
        var lastSystemPrompt: String? = null
            private set
        var lastRagContext: String? = null
            private set
        var callCount: Int = 0
            private set

        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?
        ): String? {
            callCount++
            lastConfig = config
            lastMessages = messages
            lastSystemPrompt = systemPrompt
            lastRagContext = ragContext
            return when {
                throwCancellation -> throw CancellationException("test cancellation")
                throwOnCall -> throw RuntimeException("test network error")
                else -> returnValue
            }
        }
    }
}
