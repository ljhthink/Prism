package io.prism.memory

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.data.ProviderConfig
import io.prism.network.ChatCompletionProvider
import io.prism.network.OpenAICompatibleProvider
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * M5 Phase B 性能基线测试（ac-verifier 补充，首版基线）。
 *
 * 测量 SlidingWindowMemoryManager / ConversationSummarizer / OpenAICompatibleProvider
 * 非流式路径的关键操作延迟初版基线。
 *
 * **测量内容**：
 * - `truncateMessages` 不同消息数量下的截断延迟
 * - `parseCompletionResponse` 典型 LLM 响应的解析延迟
 * - `processMessages` 无摘要路径（messages.size <= N）延迟
 * - `processMessages` 摘要路径（FakeProvider，测量滑动窗口逻辑开销）
 *
 * **局限**：
 * - 纯 JVM 测试（非 Android 设备）
 * - 摘要路径使用 FakeProvider（不测量真实网络延迟）
 * - DataStore 使用 FakePreferenceDataStore（内存操作，不测量磁盘 I/O）
 *
 * 复现方式：
 * ```bash
 * ./gradlew.bat testDebugUnitTest --tests "io.prism.memory.M5PhaseBPerfBaselineTest" --rerun-tasks
 * ```
 */
class M5PhaseBPerfBaselineTest {

    private val testConfig = ProviderConfig(
        name = "perf-provider",
        baseUrl = "https://api.test.com/v1",
        apiKeyRef = "perf-key-ref",
        models = listOf("perf-model"),
        headers = emptyMap()
    )

    private lateinit var provider: OpenAICompatibleProvider
    private lateinit var configRepository: MemoryConfigRepository
    private lateinit var manager: SlidingWindowMemoryManager

    @Before
    fun setUp() {
        provider = OpenAICompatibleProvider(
            HttpClient(OkHttp) { expectSuccess = true; install(SSE) },
            ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
        )
        configRepository = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        val summarizer = ConversationSummarizer(InstantFakeProvider("性能测试摘要"))
        manager = SlidingWindowMemoryManager(summarizer, configRepository)
    }

    /** 创建 N 条测试消息。 */
    private fun createMessages(count: Int, contentLength: Int = 50): List<ChatMessage> =
        (1..count).map { i ->
            ChatMessage(
                id = i.toLong(),
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "消息内容".repeat(contentLength / 4 + 1).take(contentLength),
                timestamp = i.toLong() * 1000
            )
        }

    /** 典型 LLM 非流式响应 JSON。 */
    private val typicalLlmResponse = """
        {"id":"chatcmpl-abc123","object":"chat.completion","created":1699999999,
        "model":"gpt-4o","choices":[{"index":0,
        "message":{"role":"assistant","content":"用户询问了 Kotlin 协程的用法。用户选择了 coroutines 方案。重要决策：使用 SupervisorJob 管理子协程。"},
        "finish_reason":"stop"}],
        "usage":{"prompt_tokens":150,"completion_tokens":80,"total_tokens":230}}
    """.trimIndent()

    /** 计算统计指标并输出 PERF_BASELINE 行。 */
    private fun reportStats(op: String, iters: Int, times: List<Long>) {
        val sorted = times.sorted()
        val min = sorted.first()
        val p50 = sorted[iters / 2]
        val p95 = sorted[(iters * 95 / 100).coerceAtMost(iters - 1)]
        val p99 = sorted[(iters * 99 / 100).coerceAtMost(iters - 1)]
        val max = sorted.last()
        val throughput = if (min > 0) 1_000_000_000.0 / p50 else Double.MAX_VALUE
        println("PERF_BASELINE|op=$op|iters=$iters|min=${min}ns|p50=${p50}ns|p95=${p95}ns|p99=${p99}ns|max=${max}ns|throughput=${"%.1f".format(throughput)}_ops_per_s|failures=0")
    }

    @Test
    fun `baseline truncateMessages with 10 messages`() {
        val messages = createMessages(10, contentLength = 100)
        val iters = 1000
        val times = (1..iters).map {
            val start = System.nanoTime()
            manager.truncateMessages(messages)
            System.nanoTime() - start
        }
        reportStats("truncateMessages_10msgs", iters, times)
    }

    @Test
    fun `baseline truncateMessages with 50 messages`() {
        val messages = createMessages(50, contentLength = 100)
        val iters = 1000
        val times = (1..iters).map {
            val start = System.nanoTime()
            manager.truncateMessages(messages)
            System.nanoTime() - start
        }
        reportStats("truncateMessages_50msgs", iters, times)
    }

    @Test
    fun `baseline parseCompletionResponse typical response`() {
        val iters = 1000
        val times = (1..iters).map {
            val start = System.nanoTime()
            provider.parseCompletionResponse(typicalLlmResponse)
            System.nanoTime() - start
        }
        reportStats("parseCompletionResponse_typical", iters, times)
    }

    @Test
    fun `baseline parseCompletionResponse invalid JSON`() {
        val iters = 1000
        val times = (1..iters).map {
            val start = System.nanoTime()
            provider.parseCompletionResponse("<html>error</html>")
            System.nanoTime() - start
        }
        reportStats("parseCompletionResponse_invalid", iters, times)
    }

    @Test
    fun `baseline processMessages no-summary path`() = runBlocking {
        configRepository.setWindowSize(20)
        val messages = createMessages(10) // 10 < 20, no summary
        val iters = 500
        val times = (1..iters).map {
            val start = System.nanoTime()
            manager.processMessages(messages, testConfig)
            System.nanoTime() - start
        }
        reportStats("processMessages_noSummary", iters, times)
    }

    @Test
    fun `baseline processMessages summary path with FakeProvider`() = runBlocking {
        configRepository.setWindowSize(5)
        val messages = createMessages(20) // 20 > 5, triggers summary
        val iters = 500
        val times = (1..iters).map {
            val start = System.nanoTime()
            manager.processMessages(messages, testConfig)
            System.nanoTime() - start
        }
        reportStats("processMessages_summary_fake", iters, times)
    }

    @Test
    fun `baseline processMessages truncation fallback path`() = runBlocking {
        // Use a provider that returns null to force truncation fallback
        val nullSummarizer = ConversationSummarizer(InstantFakeProvider(null))
        val nullManager = SlidingWindowMemoryManager(nullSummarizer, configRepository)
        configRepository.setWindowSize(5)
        val messages = createMessages(20)
        val iters = 500
        val times = (1..iters).map {
            val start = System.nanoTime()
            nullManager.processMessages(messages, testConfig)
            System.nanoTime() - start
        }
        reportStats("processMessages_truncation_fallback", iters, times)
    }

    /** 即时返回的 FakeProvider（无延迟）。 */
    private class InstantFakeProvider(
        private val returnValue: String?
    ) : ChatCompletionProvider {
        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): String? = returnValue
    }
}
