package io.prism.security

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * ac-verifier 性能基准测试（US-003，CLAUDE.md 第十一节 4 性能回退检查）。
 *
 * 测量 encrypt / decrypt / saveApiKey / readApiKey 延迟（p50/p95/p99）。
 * 使用 RecordingCryptoService（纯 JVM Tink AEAD AES-256-GCM）。
 *
 * 默认跳过（DEF-02 迁移）；手动运行获取基线数据：
 *   .\gradlew.bat testDebugUnitTest --tests "*.ApiKeyPerformanceBenchmark" -PignorePerformanceTests=false
 */
class ApiKeyPerformanceBenchmark {

    private val iterations = 500
    private val warmupIterations = 50

    @Before
    fun skipUnlessEnabled() {
        // 仅当 Gradle 传入 -PignorePerformanceTests=false（注入 prism.runPerformanceTests=true）时运行；
        // 否则整类跳过。
        Assume.assumeTrue(
            "性能基准默认跳过；运行: testDebugUnitTest ... -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
    }

    @Test
    fun benchmark_encrypt() {
        val crypto = RecordingCryptoService()
        val plaintext = "sk-test-api-key-for-benchmark-1234567890".toByteArray(Charsets.UTF_8)

        // warmup
        repeat(warmupIterations) { crypto.encrypt(plaintext) }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            crypto.encrypt(plaintext)
            System.nanoTime() - start
        }
        printStats("ENCRYPT", times)
    }

    @Test
    fun benchmark_decrypt() {
        val crypto = RecordingCryptoService()
        val plaintext = "sk-test-api-key-for-benchmark-1234567890".toByteArray(Charsets.UTF_8)

        // 预先生成密文用于解密
        val ciphertext = crypto.encrypt(plaintext)

        // warmup
        repeat(warmupIterations) { crypto.decrypt(ciphertext) }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            crypto.decrypt(ciphertext)
            System.nanoTime() - start
        }
        printStats("DECRYPT", times)
    }

    @Test
    fun benchmark_saveApiKey() = runTest {
        val dataStore = FakePreferenceDataStore()
        val crypto = RecordingCryptoService()
        val repo = ApiKeyRepository(dataStore, crypto)
        val apiKey = "sk-test-api-key-for-benchmark-1234567890"

        // warmup
        repeat(warmupIterations) { repo.saveApiKey("warmup", apiKey) }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            repo.saveApiKey("bench-$it", apiKey)
            System.nanoTime() - start
        }
        printStats("SAVE_API_KEY", times)
    }

    @Test
    fun benchmark_readApiKey() = runTest {
        val dataStore = FakePreferenceDataStore()
        val crypto = RecordingCryptoService()
        val repo = ApiKeyRepository(dataStore, crypto)
        val apiKey = "sk-test-api-key-for-benchmark-1234567890"

        // 预存数据
        for (i in 0 until iterations) {
            repo.saveApiKey("bench-$i", apiKey)
        }

        // warmup
        repeat(warmupIterations) { repo.readApiKey("bench-0").first() }

        val times = LongArray(iterations) {
            val start = System.nanoTime()
            repo.readApiKey("bench-$it").first()
            System.nanoTime() - start
        }
        printStats("READ_API_KEY", times)
    }

    private fun printStats(label: String, times: LongArray) {
        val sorted = times.sortedArray()
        val n = sorted.size
        val p50 = sorted[n / 2]
        val p95 = sorted[(n * 95 / 100)]
        val p99 = sorted[(n * 99 / 100)]
        val mean = sorted.sum() / n
        val min = sorted[0]
        val max = sorted[n - 1]

        println()
        println("=== $label (n=$n) ===")
        println("  p50  : ${p50 / 1000.0} us (${p50 / 1_000_000.0} ms)")
        println("  p95  : ${p95 / 1000.0} us (${p95 / 1_000_000.0} ms)")
        println("  p99  : ${p99 / 1000.0} us (${p99 / 1_000_000.0} ms)")
        println("  mean : ${mean / 1000.0} us")
        println("  min  : ${min / 1000.0} us")
        println("  max  : ${max / 1000.0} us")
        println("  p99/p50 ratio: ${"%.1f".format(p99.toDouble() / p50.toDouble())}x")
    }
}