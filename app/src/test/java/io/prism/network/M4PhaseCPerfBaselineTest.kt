package io.prism.network

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.data.ProviderConfig
import io.prism.fs.ToolConfirmationGate
import io.prism.skill.SkillExecutor
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * M4 Phase C 性能基线测试（ac-verifier，CLAUDE.md 第十一节 4）。
 *
 * 无既有性能基线 → 对关键函数生成初版基线。
 * 测量 chunkToEvents / completeToolCalls / executeToolCall / executeLoop 的 p50/p95/p99 延迟。
 *
 * 结果通过 println 输出到 Gradle 测试日志，供 ac-verifier 提取记录基线。
 */
class M4PhaseCPerfBaselineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val provider = OpenAICompatibleProvider(
        HttpClient(OkHttp) { install(SSE) },  // 仅用于构造实例，不实际发请求（只测纯函数）
        io.prism.security.ApiKeyRepository(
            io.prism.security.FakePreferenceDataStore(),
            io.prism.security.RecordingCryptoService()
        )
    )

    // 辅助：计算百分位数（sorted 必须已排序）
    private fun percentile(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((p * sorted.size).toInt()).coerceAtMost(sorted.size - 1)
        return sorted[idx]
    }

    // 辅助：格式化结果
    private fun formatResult(name: String, times: LongArray): String {
        val sorted = times.sortedArray()
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val p99 = percentile(sorted, 0.99)
        return "[PERF] $name: p50=${p50}ns p95=${p95}ns p99=${p99}ns (n=${times.size})"
    }

    @Test
    fun `perf baseline chunkToEvents single delta chunk`() {
        val data = """{"choices":[{"delta":{"content":"hello","tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\"/tmp\"}"}}]}}]}"""
        val warmup = 100
        val iterations = 1000
        val times = LongArray(iterations)
        // 预热
        repeat(warmup) {
            val chunk = provider.parseChunk(data)!!
            val state = mutableMapOf<Int, ToolCallAccumulator>()
            provider.chunkToEvents(chunk, state, json)
        }
        // 计时
        for (i in 0 until iterations) {
            val chunk = provider.parseChunk(data)!!
            val state = mutableMapOf<Int, ToolCallAccumulator>()
            val start = System.nanoTime()
            provider.chunkToEvents(chunk, state, json)
            times[i] = System.nanoTime() - start
        }
        println(formatResult("chunkToEvents(single delta)", times))
    }

    @Test
    fun `perf baseline completeToolCalls JSON parse`() {
        val warmup = 100
        val iterations = 1000
        val times = LongArray(iterations)
        // 预热
        repeat(warmup) {
            val state = mutableMapOf(
                0 to ToolCallAccumulator(
                    id = "call_1", name = "read_file",
                    arguments = StringBuilder("""{"path":"/tmp/file.txt","mode":"read","encoding":"utf-8"}""")
                )
            )
            provider.completeToolCalls(state, json)
        }
        // 计时
        for (i in 0 until iterations) {
            val state = mutableMapOf(
                0 to ToolCallAccumulator(
                    id = "call_1", name = "read_file",
                    arguments = StringBuilder("""{"path":"/tmp/file.txt","mode":"read","encoding":"utf-8"}""")
                )
            )
            val start = System.nanoTime()
            provider.completeToolCalls(state, json)
            times[i] = System.nanoTime() - start
        }
        println(formatResult("completeToolCalls(JSON parse)", times))
    }

    @Test
    fun `perf baseline executeToolCall single execution`() = runBlocking {
        val mcpProvider = PerfFakeMcpToolProvider(returnResult = "file content")
        val gate = PerfFakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete(
            "call_1", "skill__read_file",
            mapOf("path" to "/tmp/file.txt", "mode" to "read")
        )
        val servers = listOf(
            McpServerConfig(name = "fs", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true)
        )
        val warmup = 10
        val iterations = 100
        val times = LongArray(iterations)
        // 预热
        repeat(warmup) { executor.executeToolCall(toolCall, servers) }
        // 计时
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            executor.executeToolCall(toolCall, servers)
            times[i] = System.nanoTime() - start
        }
        println(formatResult("executeToolCall(confirm+MCP)", times))
    }

    @Test
    fun `perf baseline executeLoop two rounds`() = runBlocking {
        val provider = PerfFakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "skill__tool", mapOf("x" to 1))),
                listOf(StreamEvent.Delta("done"), StreamEvent.Done)
            )
        )
        val mcpProvider = PerfFakeMcpToolProvider(returnResult = "result")
        val gate = PerfFakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(ChatMessage(id = 0, role = Role.USER, content = "hi", timestamp = 0))
        val config = ProviderConfig(name = "test", baseUrl = "http://h", apiKeyRef = "ref")
        val tools = listOf(
            ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = "skill__tool", description = "test",
                    parameters = Json.decodeFromString("""{"type":"object"}""")
                )
            )
        )
        val servers = listOf(
            McpServerConfig(name = "fs", serverType = McpServerType.LOCAL, baseUrl = "", isEnabled = true)
        )
        val warmup = 5
        val iterations = 50
        val times = LongArray(iterations)
        var idCounter = 1L
        // 预热
        repeat(warmup) {
            executor.executeLoop(provider, config, initialMessages, null, null, tools, servers, 10, { idCounter++ }) {}
            provider.resetRounds()
        }
        // 计时
        for (i in 0 until iterations) {
            provider.resetRounds()
            val start = System.nanoTime()
            executor.executeLoop(provider, config, initialMessages, null, null, tools, servers, 10, { idCounter++ }) {}
            times[i] = System.nanoTime() - start
        }
        println(formatResult("executeLoop(2 rounds)", times))
    }

    @Test
    fun `perf baseline sanitizeErrorMessage regex replace`() {
        val warmup = 100
        val iterations = 1000
        val times = LongArray(iterations)
        val rawMsg = "failed to open /tmp/secret/file.txt at C:\\Users\\admin\\creds.txt connection refused"
        // 预热
        repeat(warmup) { SkillExecutor.sanitizeErrorMessage(rawMsg) }
        // 计时
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            SkillExecutor.sanitizeErrorMessage(rawMsg)
            times[i] = System.nanoTime() - start
        }
        println(formatResult("sanitizeErrorMessage(regex)", times))
    }

    // ==================== Fake 实现（性能测试用，最小化） ====================

    private class PerfFakeMcpToolProvider(private val returnResult: String) : McpToolProvider {
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(
            config: McpServerConfig, name: String, arguments: Map<String, Any?>
        ): String = returnResult
    }

    private class PerfFakeConfirmationGate(private val approve: Boolean) : ToolConfirmationGate {
        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean = approve
    }

    private class PerfFakeChatStreamProvider(
        private val rounds: List<List<StreamEvent>>
    ) : ChatStreamProvider {
        private val roundCounter = AtomicInteger(0)
        fun resetRounds() { roundCounter.set(0) }

        override fun streamChat(
            config: ProviderConfig, messages: List<ChatMessage>,
            systemPrompt: String?, ragContext: String?,
            tools: List<ToolDefinition>?, toolChoice: ToolChoice?
        ): Flow<StreamEvent> {
            val idx = roundCounter.getAndIncrement().coerceAtMost(rounds.size - 1)
            val events = rounds.getOrElse(idx) { emptyList() }
            return flow { events.forEach { emit(it) } }
        }
    }
}
