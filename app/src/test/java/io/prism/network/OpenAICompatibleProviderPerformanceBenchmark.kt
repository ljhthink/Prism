package io.prism.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.prism.data.ProviderConfig
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * OpenAICompatibleProvider SSE 流式性能基准（ac-verifier 补充，US-006 AC-2 初版基线）。
 *
 * 测量 /v1/chat/completions SSE 流式的：
 * - 首字延迟（TTFB）：从发起请求到收到首个 Delta 的时间（AC-2「首字延迟 <1s」的 JVM 近似基线）
 * - 吞吐：单位时间接收的 Delta token 数（token/s）
 *
 * 默认跳过（与既有 PerformanceBenchmark 模式一致）；手动运行获取基线：
 *   .\gradlew.bat :app:testDebugUnitTest --tests "*.OpenAICompatibleProviderPerformanceBenchmark" -PignorePerformanceTests=false
 *
 * 说明：AC-2 在 JVM 单测无真实公网端点，以嵌入式 Ktor Netty SSE 服务器（localhost）计时作为可验证近似基线。
 * 真机 PoC 首字延迟需在 US-007 或后期补测（guardrail 转交项）。
 */
class OpenAICompatibleProviderPerformanceBenchmark {

    private val apiKeyRepo = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())
    private val httpClient = HttpClient(OkHttp) { install(SSE) }
    private val provider = OpenAICompatibleProvider(httpClient, apiKeyRepo)

    companion object {
        private const val ITERATIONS = 30
        private const val WARMUP = 5
        private const val DELTAS_PER_STREAM = 50
    }

    @Before
    fun setUp() {
        // 仅当 Gradle 传入 -PignorePerformanceTests=false（注入 prism.runPerformanceTests=true）时运行。
        Assume.assumeTrue(
            "性能基准默认跳过；运行: testDebugUnitTest ... -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
    }

    @After
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun sse_first_token_latency_benchmark() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        repeat(DELTAS_PER_STREAM) { i ->
                            send(ServerSentEvent(data = """{"choices":[{"delta":{"content":"x$i"}}]}"""))
                        }
                        send(ServerSentEvent(data = "[DONE]"))
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(name = "Perf", baseUrl = "http://127.0.0.1:$port", apiKeyRef = "k", models = listOf("m"))

            fun measureOnce(): Long {
                var first = -1L
                val start = System.nanoTime()
                runBlocking {
                    provider.streamChat(config, sampleMessages()).collect { ev ->
                        if (ev is StreamEvent.Delta && first < 0) first = System.nanoTime() - start
                    }
                }
                return first
            }

            repeat(WARMUP) { measureOnce() }
            val latencies = LongArray(ITERATIONS)
            repeat(ITERATIONS) { i -> latencies[i] = measureOnce() }
            printLatencyStats("首字延迟 (SSE localhost Netty)", latencies)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun sse_throughput_benchmark() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.sse.SSE)
            routing {
                route("/chat/completions", HttpMethod.Post) {
                    sse {
                        repeat(DELTAS_PER_STREAM) { i ->
                            send(ServerSentEvent(data = """{"choices":[{"delta":{"content":"token-$i-"}}]}"""))
                        }
                        send(ServerSentEvent(data = "[DONE]"))
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = ProviderConfig(name = "Perf", baseUrl = "http://127.0.0.1:$port", apiKeyRef = "k", models = listOf("m"))

            fun measureRate(): Double {
                val start = System.nanoTime()
                val events = runBlocking { provider.streamChat(config, sampleMessages()).toList() }
                val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0
                val tokens = events.count { it is StreamEvent.Delta }
                return tokens / elapsedSeconds
            }

            repeat(WARMUP) { measureRate() }
            val rates = DoubleArray(ITERATIONS)
            repeat(ITERATIONS) { i -> rates[i] = measureRate() }
            printRateStats("吞吐 ($DELTAS_PER_STREAM deltas/stream)", rates)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private fun sampleMessages() = listOf(
        ChatMessage(id = 0, role = Role.USER, content = "你好", timestamp = 0)
    )

    private fun printLatencyStats(label: String, latencies: LongArray) {
        val sorted = latencies.sortedArray()
        val n = sorted.size
        val p50 = sorted[n * 50 / 100]
        val p95 = sorted[(n * 95 + 99) / 100 - 1]
        val p99 = sorted[(n * 99 + 99) / 100 - 1]
        val mean = latencies.average().toLong()
        val ms: (Long) -> Double = { v -> v / 1_000_000.0 }
        println()
        println("===== $label =====")
        println("Iterations: $n")
        println("  p50: %.2f ms".format(ms(p50)))
        println("  p95: %.2f ms".format(ms(p95)))
        println("  p99: %.2f ms".format(ms(p99)))
        println("  mean: %.2f ms".format(ms(mean)))
        println("  min:  %.2f ms".format(ms(sorted.first())))
        println("  max:  %.2f ms".format(ms(sorted.last())))
    }

    private fun printRateStats(label: String, rates: DoubleArray) {
        val sorted = rates.sortedArray()
        val n = sorted.size
        val p50 = sorted[n * 50 / 100]
        val p95 = sorted[(n * 95 + 99) / 100 - 1]
        println()
        println("===== $label =====")
        println("Iterations: $n")
        println("  p50: %.1f token/s".format(p50))
        println("  p95: %.1f token/s".format(p95))
        println("  mean: %.1f token/s".format(rates.average()))
        println("  min: %.1f token/s".format(sorted.first()))
        println("  max: %.1f token/s".format(sorted.last()))
    }
}