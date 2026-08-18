package io.prism.memory

import io.prism.data.ProviderConfig
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ==================== UXR11 U5：原子记忆抽取 extractMemories（ADR-033） ====================

    @Test
    fun extractMemories_returns_empty_for_empty_messages() = runBlocking {
        val provider = FakeCompletionProvider("无")
        val summarizer = ConversationSummarizer(provider)
        val result = summarizer.extractMemories(emptyList(), testConfig)
        assertEquals("空消息列表应返回空列表（不触发 LLM）", emptyList<String>(), result)
        assertEquals("空消息不应触发 LLM 调用", 0, provider.callCount)
    }

    @Test
    fun extractMemories_parses_list_lines_and_strips_numbering() = runBlocking {
        // LLM 返回带序号/列表符号的多行记忆
        val raw = """1. 用户偏好使用简体中文交流
- 用户是 Android 开发者，使用 Kotlin 和 Jetpack Compose
• 用户决定项目采用方案 A
2. 用户每周日健身"""
        val provider = FakeCompletionProvider(raw)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "我是 Android 开发者", 1000L),
            ChatMessage(2, Role.ASSISTANT, "了解了。", 2000L)
        )
        val result = summarizer.extractMemories(messages, testConfig)
        assertEquals(4, result?.size)
        assertEquals("用户偏好使用简体中文交流", result?.get(0))
        assertEquals("用户是 Android 开发者，使用 Kotlin 和 Jetpack Compose", result?.get(1))
        assertEquals("用户决定项目采用方案 A", result?.get(2))
        assertEquals("用户每周日健身", result?.get(3))
        // 抽取任务应使用记忆抽取 prompt 作为 systemPrompt
        assertTrue(
            "应使用记忆抽取 prompt",
            provider.lastSystemPrompt?.contains("原子记忆") == true
        )
    }

    @Test
    fun extractMemories_filters_empty_and_无_lines() = runBlocking {
        val raw = """无

1. 用户喜欢喝美式咖啡
   
2. 用户计划下周去成都出差"""
        val provider = FakeCompletionProvider(raw)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(
            ChatMessage(1, Role.USER, "我平时喜欢喝美式咖啡", 1000L)
        )
        val result = summarizer.extractMemories(messages, testConfig)
        assertEquals("应过滤'无'与空行", 2, result?.size)
        assertFalse("不应包含'无'", result?.any { it == "无" } == true)
    }

    @Test
    fun extractMemories_caps_at_MEMORY_EXTRACT_MAX() = runBlocking {
        val raw = (1..10).joinToString("\n") { "$it. 用户第 $it 条记忆" }
        val provider = FakeCompletionProvider(raw)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        val result = summarizer.extractMemories(messages, testConfig)
        assertEquals("应截断到上限", ConversationSummarizer.MEMORY_EXTRACT_MAX, result?.size)
    }

    @Test
    fun extractMemories_preserves_digit_leading_memory_without_list_marker() = runBlocking {
        // guardrail F6：仅剥完整序号格式（1. 等），不裸剥数字开头的真实记忆
        val raw = """1. 用户喜欢养宠物
- 用户有 2 个孩子
3）用户计划 2026 年买车"""
        val provider = FakeCompletionProvider(raw)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        val result = summarizer.extractMemories(messages, testConfig)
        assertEquals(3, result?.size)
        assertEquals("用户喜欢养宠物", result?.get(0))
        assertEquals("用户有 2 个孩子", result?.get(1))
        assertEquals("用户计划 2026 年买车", result?.get(2))
    }

    @Test
    fun extractMemories_truncates_single_memory_to_MAX_MEMORY_ITEM_CHARS() = runBlocking {
        // guardrail M-1（第二轮复审）：病态/幻觉超长单行记忆截断到单条上限，防无界入库
        val longLine = "用户特别喜欢".repeat(100)
        val raw = "1. $longLine"
        val provider = FakeCompletionProvider(raw)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        val result = summarizer.extractMemories(messages, testConfig)
        assertEquals(1, result?.size)
        assertTrue("单条记忆应截断到上限", result!![0].length <= ConversationSummarizer.MAX_MEMORY_ITEM_CHARS)
        assertEquals("截断长度应为上限", ConversationSummarizer.MAX_MEMORY_ITEM_CHARS, result[0].length)
    }

    @Test
    fun extractMemories_returns_empty_when_provider_returns_null() = runBlocking {
        val provider = FakeCompletionProvider(null)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        val result = summarizer.extractMemories(messages, testConfig)
        assertEquals("Provider 返回 null 应视为无记忆（空列表）", emptyList<String>(), result)
    }

    @Test
    fun extractMemories_returns_null_when_provider_throws_exception() = runBlocking {
        val provider = FakeCompletionProvider(throwOnCall = true)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        val result = summarizer.extractMemories(messages, testConfig)
        assertNull("Provider 抛异常应返回 null（调用方降级规则抽取）", result)
    }

    @Test(expected = CancellationException::class)
    fun extractMemories_rethrows_cancellation_exception(): Unit = runBlocking {
        val provider = FakeCompletionProvider(throwCancellation = true)
        val summarizer = ConversationSummarizer(provider)
        val messages = listOf(ChatMessage(1, Role.USER, "test", 1000L))
        summarizer.extractMemories(messages, testConfig)
    }

    @Test
    fun buildMemoryExtractionPrompt_returns_non_empty_prompt() {
        val provider = FakeCompletionProvider("无")
        val summarizer = ConversationSummarizer(provider)
        val prompt = summarizer.buildMemoryExtractionPrompt()
        assertTrue("Prompt 不应为空", prompt.isNotBlank())
        assertTrue("Prompt 应包含 '原子记忆' 定义", prompt.contains("原子记忆"))
        assertTrue("Prompt 应显式排除一次性信息查询", prompt.contains("一次性信息查询"))
        assertTrue("Prompt 应含第三人称约束", prompt.contains("第三人称"))
        assertTrue("Prompt 应含输出上限约束", prompt.contains("最多 5 条"))
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
            ragContext: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
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
