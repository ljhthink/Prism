package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ProviderConfig CRUD + 类型转换器性能基准测试（ac-verifier 补充，US-004 性能基线）。
 *
 * 测量 ProviderConfig save/get/setActive 及 StringListConverter/StringMapConverter
 * 往返的 p50/p95/p99 延迟初版基线。
 * 默认跳过（DEF-02 修复）；手动运行获取基线数据：
 *   .\gradlew.bat testDebugUnitTest --tests "*.ProviderConfigPerformanceBenchmark" -PignorePerformanceTests=false
 */
class ProviderConfigPerformanceBenchmark {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: ProviderConfigRepository
    private lateinit var tempDir: File

    companion object {
        private const val ITERATIONS = 500
        private const val WARMUP = 50
    }

    @Before
    fun setUp() {
        // 仅当 Gradle 传入 -PignorePerformanceTests=false（注入 prism.runPerformanceTests=true）时运行；
        // 否则整类跳过。
        Assume.assumeTrue(
            "性能基准默认跳过；运行: testDebugUnitTest ... -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
        tempDir = kotlin.io.path.createTempDirectory(prefix = "provider-perf-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = ProviderConfigRepository(boxStore)
    }

    @After
    fun tearDown() {
        // Assume 失败（跳过）时字段未初始化，需判空避免二次异常
        if (::boxStore.isInitialized) boxStore.close()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    @Test
    fun save_latency_benchmark() {
        repeat(WARMUP) {
            repository.save(ProviderConfig(name = "warmup", baseUrl = "url", apiKeyRef = "k"))
        }
        repository.removeAll()

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val cfg = ProviderConfig(
                name = "perf-$i",
                baseUrl = "https://api.example.com/v1",
                apiKeyRef = "k-$i",
                models = listOf("gpt-4o", "gpt-4o-mini"),
                headers = mapOf("X-Ctx" to "ctx-$i")
            )
            val start = System.nanoTime()
            repository.save(cfg)
            latencies[i] = System.nanoTime() - start
        }
        printStats("SAVE (ProviderConfig)", latencies)
    }

    @Test
    fun get_latency_benchmark() {
        val ids = LongArray(ITERATIONS + WARMUP)
        repeat(ITERATIONS + WARMUP) { i ->
            ids[i] = repository.save(ProviderConfig(name = "get-$i", baseUrl = "url", apiKeyRef = "k"))
        }
        repeat(WARMUP) { repository.get(ids[it]) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            repository.get(ids[i])
            latencies[i] = System.nanoTime() - start
        }
        printStats("GET (ProviderConfig)", latencies)
    }

    @Test
    fun setActive_latency_benchmark() {
        // 准备 10 个 Provider，预热 setActive
        val ids = LongArray(10)
        repeat(10) { i -> ids[i] = repository.save(ProviderConfig(name = "p$i", baseUrl = "url", apiKeyRef = "k$i")) }
        repeat(WARMUP) { repository.setActive(ids[it % 10]) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val target = ids[i % 10]
            val start = System.nanoTime()
            repository.setActive(target)
            latencies[i] = System.nanoTime() - start
        }
        printStats("SET_ACTIVE (10 providers)", latencies)
    }

    @Test
    fun stringListConverter_roundtrip_benchmark() {
        val converter = StringListConverter()
        val models = (1..100).map { "org.openai.model-$it-v1.0-${"a".repeat(30)}" }
        val encoded = converter.convertToDatabaseValue(models)

        // 预热
        repeat(WARMUP) { converter.convertToEntityProperty(encoded); converter.convertToDatabaseValue(models) }

        val encodeTimes = LongArray(ITERATIONS)
        val decodeTimes = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            var s = System.nanoTime(); converter.convertToDatabaseValue(models); encodeTimes[i] = System.nanoTime() - s
            s = System.nanoTime(); converter.convertToEntityProperty(encoded); decodeTimes[i] = System.nanoTime() - s
        }
        printStats("LIST_CONVERTER_ENCODE (100 models)", encodeTimes)
        printStats("LIST_CONVERTER_DECODE (100 models)", decodeTimes)
    }

    @Test
    fun stringMapConverter_roundtrip_benchmark() {
        val converter = StringMapConverter()
        val headers = mapOf(
            "Authorization" to "Bearer sk-test-token-1234567890",
            "X-Custom" to "value-a\\b=c\nd",
            "Content-Type" to "application/json; charset=UTF-8",
            "X-Unicode" to "中文值-emoji-\uD83D\uDE00"
        )
        val encoded = converter.convertToDatabaseValue(headers)

        repeat(WARMUP) { converter.convertToEntityProperty(encoded); converter.convertToDatabaseValue(headers) }

        val encodeTimes = LongArray(ITERATIONS)
        val decodeTimes = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            var s = System.nanoTime(); converter.convertToDatabaseValue(headers); encodeTimes[i] = System.nanoTime() - s
            s = System.nanoTime(); converter.convertToEntityProperty(encoded); decodeTimes[i] = System.nanoTime() - s
        }
        printStats("MAP_CONVERTER_ENCODE (4 headers)", encodeTimes)
        printStats("MAP_CONVERTER_DECODE (4 headers)", decodeTimes)
    }

    private fun printStats(label: String, latencies: LongArray) {
        val sorted = latencies.sortedArray()
        val n = sorted.size
        val p50 = sorted[n * 50 / 100]
        val p95 = sorted[(n * 95 + 99) / 100 - 1]
        val p99 = sorted[(n * 99 + 99) / 100 - 1]
        val mean = latencies.average().toLong()
        val min = sorted.first()
        val max = sorted.last()

        println()
        println("===== $label =====")
        println("Iterations: $n")
        println("  p50: ${p50 / 1_000.0} us")
        println("  p95: ${p95 / 1_000.0} us")
        println("  p99: ${p99 / 1_000.0} us")
        println("  mean: ${mean / 1_000.0} us")
        println("  min:  ${min / 1_000.0} us")
        println("  max:  ${max / 1_000.0} us")
    }
}