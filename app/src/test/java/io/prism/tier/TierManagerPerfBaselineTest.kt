package io.prism.tier

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import org.junit.Test

/**
 * M7 TierManager.initialize 性能基线测试（ac-verifier TKN-M7-ACCEPTANCE-001，首版基线）。
 *
 * 测量 [TierManager.initialize] 在不同覆盖场景下的延迟（p50/p95/p99）。
 * 存档于 `docs/reports/perf/2026-08-11-m7-tiermanager-baseline.md`。
 *
 * **局限**：
 * - 使用 FakePreferenceDataStore（纯内存），不反映真实 Android DataStore I/O 延迟
 *   （ADR-017 4.4 预期真实 DataStore 首次读取 <50ms）
 * - 使用 fake TierDetector（直接返回内存值），不反映 ActivityManager.MemoryInfo 调用开销
 * - 纯 JVM 测试（非 Android 设备），生产基线需在 Android 设备补测
 * - 本基线仅测量 initialize() 逻辑开销（RAM 映射 + 覆盖解析 + resolveTier），
 *   不含 by lazy embedder 加载（~200ms，仅在首次访问时发生）
 *
 * 复现方式：
 * ```bash
 * ./gradlew.bat testDebugUnitTest --tests "io.prism.tier.TierManagerPerfBaselineTest" --rerun-tasks
 * # 从 XML system-out 采集 PERF_BASELINE 行
 * ```
 */
class TierManagerPerfBaselineTest {

    /**
     * 性能基线：TierManager.initialize 在 AUTO / FULL覆盖 / CHAT_ONLY覆盖 三场景下的延迟。
     *
     * 每场景预热 5 次后正式计时 50 次（initialize 为轻量内存操作，高频采样）。
     */
    @Test
    fun perf_baseline_tier_manager_initialize() {
        val configs = listOf(
            PerfConfig(scenario = "AUTO_no_override", iters = 50, override = TierConfigRepository.OVERRIDE_AUTO),
            PerfConfig(scenario = "FULL_override", iters = 50, override = PerformanceTier.FULL.name),
            PerfConfig(scenario = "CHAT_ONLY_override", iters = 50, override = PerformanceTier.CHAT_ONLY.name)
        )

        for (config in configs) {
            val result = measureInitializeLatency(config)
            println(
                "PERF_BASELINE|scenario=${config.scenario}|iters=${config.iters}|" +
                    "min=${result.minUs}us|p50=${result.p50Us}us|p95=${result.p95Us}us|" +
                    "p99=${result.p99Us}us|max=${result.maxUs}us|" +
                    "throughput=${result.throughput}_init_per_s|failures=${result.failures}"
            )
        }
    }

    private data class PerfConfig(val scenario: String, val iters: Int, val override: String)

    private data class PerfResult(
        val minUs: Long,
        val p50Us: Long,
        val p95Us: Long,
        val p99Us: Long,
        val maxUs: Long,
        val throughput: String,
        val failures: Int
    )

    private fun measureInitializeLatency(config: PerfConfig): PerfResult {
        // 为每次迭代准备带覆盖值的 DataStore（模拟用户已配置覆盖的场景）
        val dataStore = FakePreferenceDataStore(emptyPreferences())
        val configRepository = TierConfigRepository(dataStore)
        if (config.override != TierConfigRepository.OVERRIDE_AUTO) {
            kotlinx.coroutines.runBlocking {
                configRepository.setOverride(config.override)
            }
        }

        val detector = TierManager.TierDetector {
            TierManager.DetectionResult(PerformanceTier.STANDARD, 5L * 1024L * 1024L * 1024L)
        }
        val reader = TierManager.DefaultOverrideReader(configRepository)

        // 预热（5 次，JIT 编译 + DataStore 内部缓存）
        repeat(5) {
            TierManager(detector, reader).initialize()
        }

        // 正式计时（微秒精度，initialize 为内存操作通常 <1ms）
        val latenciesUs = LongArray(config.iters)
        var failures = 0
        repeat(config.iters) { i ->
            val mgr = TierManager(detector, reader)
            val start = System.nanoTime()
            try {
                mgr.initialize()
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000  // ns → us
        }

        latenciesUs.sort()
        val minUs = latenciesUs.first()
        val maxUs = latenciesUs.last()
        val p50Us = latenciesUs[(config.iters * 0.50).toInt().coerceAtMost(config.iters - 1)]
        val p95Us = latenciesUs[(config.iters * 0.95).toInt().coerceAtMost(config.iters - 1)]
        val p99Us = latenciesUs[(config.iters * 0.99).toInt().coerceAtMost(config.iters - 1)]
        val throughput = if (p50Us > 0) {
            String.format("%.1f", 1_000_000.0 / p50Us)
        } else {
            "N/A"
        }

        return PerfResult(minUs, p50Us, p95Us, p99Us, maxUs, throughput, failures)
    }
}
