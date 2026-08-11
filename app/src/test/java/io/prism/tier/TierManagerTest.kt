package io.prism.tier

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TierManager 单元测试（US-007 AC-1 + AC-6，ADR-017 4.1）。
 *
 * 测试覆盖：
 * - initialize 后状态正确（detectedTier / currentTier / totalRamBytes / currentOverride）
 * - 覆盖优先于检测（AUTO → 用检测值；具体档位 → 用覆盖值）
 * - 无效覆盖降级为检测值（不阻断启动）
 * - isInitialized 防御检查
 * - 未初始化访问 currentTier 抛异常
 * - 注入式检测器/读取器（可测性）
 * - DefaultOverrideReader 集成（从真实 DataStore 读取）
 */
class TierManagerTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var configRepository: TierConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        configRepository = TierConfigRepository(dataStore)
    }

    // ============ 辅助构造 ============

    /**
     * 构造 TierManager，注入假检测器与假读取器，避免 Android Context 与 runBlocking。
     */
    private fun createManager(
        detectedTier: PerformanceTier,
        totalRamBytes: Long,
        override: String = TierConfigRepository.OVERRIDE_AUTO
    ): TierManager {
        val detector = TierManager.TierDetector {
            TierManager.DetectionResult(detectedTier, totalRamBytes)
        }
        val reader = TierManager.OverrideReader { override }
        return TierManager(
            tierDetector = detector,
            overrideReader = reader
        )
    }

    // ============ initialize 后状态 ============

    @Test
    fun initialize_sets_detected_tier_from_detector() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        manager.initialize()
        assertEquals(
            "initialize 后 detectedTier 应来自检测器",
            PerformanceTier.FULL,
            manager.detectedTier
        )
    }

    @Test
    fun initialize_sets_total_ram_bytes_from_detector() {
        val eightGb = 8L * 1024L * 1024L * 1024L
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = eightGb
        )
        manager.initialize()
        assertEquals(
            "initialize 后 totalRamBytes 应来自检测器",
            eightGb,
            manager.totalRamBytes
        )
    }

    @Test
    fun initialize_sets_current_override_from_reader() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            override = PerformanceTier.MINIMAL.name
        )
        manager.initialize()
        assertEquals(
            "initialize 后 currentOverride 应来自读取器",
            PerformanceTier.MINIMAL.name,
            manager.currentOverride
        )
    }

    // ============ AUTO 覆盖：用检测值 ============

    @Test
    fun initialize_auto_override_uses_detected_tier() {
        val manager = createManager(
            detectedTier = PerformanceTier.STANDARD,
            totalRamBytes = 5L * 1024L * 1024L * 1024L,
            override = TierConfigRepository.OVERRIDE_AUTO
        )
        manager.initialize()
        assertEquals(
            "AUTO 覆盖时 currentTier 应等于 detectedTier",
            PerformanceTier.STANDARD,
            manager.currentTier
        )
    }

    @Test
    fun initialize_auto_override_full_detected_returns_full() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            override = TierConfigRepository.OVERRIDE_AUTO
        )
        manager.initialize()
        assertEquals(PerformanceTier.FULL, manager.currentTier)
    }

    @Test
    fun initialize_auto_override_chatOnly_detected_returns_chatOnly() {
        val manager = createManager(
            detectedTier = PerformanceTier.CHAT_ONLY,
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            override = TierConfigRepository.OVERRIDE_AUTO
        )
        manager.initialize()
        assertEquals(PerformanceTier.CHAT_ONLY, manager.currentTier)
    }

    // ============ 具体档位覆盖：用覆盖值 ============

    @Test
    fun initialize_full_override_uses_full_even_if_detected_lower() {
        val manager = createManager(
            detectedTier = PerformanceTier.CHAT_ONLY,
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            override = PerformanceTier.FULL.name
        )
        manager.initialize()
        assertEquals(
            "FULL 覆盖应优先于 CHAT_ONLY 检测值",
            PerformanceTier.FULL,
            manager.currentTier
        )
    }

    @Test
    fun initialize_chatOnly_override_uses_chatOnly_even_if_detected_higher() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            override = PerformanceTier.CHAT_ONLY.name
        )
        manager.initialize()
        assertEquals(
            "CHAT_ONLY 覆盖应优先于 FULL 检测值（用户可在高端机选低档省电）",
            PerformanceTier.CHAT_ONLY,
            manager.currentTier
        )
    }

    @Test
    fun initialize_minimal_override_uses_minimal() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            override = PerformanceTier.MINIMAL.name
        )
        manager.initialize()
        assertEquals(PerformanceTier.MINIMAL, manager.currentTier)
    }

    @Test
    fun initialize_standard_override_uses_standard() {
        val manager = createManager(
            detectedTier = PerformanceTier.CHAT_ONLY,
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            override = PerformanceTier.STANDARD.name
        )
        manager.initialize()
        assertEquals(PerformanceTier.STANDARD, manager.currentTier)
    }

    // ============ 无效覆盖降级 ============

    @Test
    fun initialize_invalid_override_falls_back_to_detected_tier() {
        val manager = createManager(
            detectedTier = PerformanceTier.STANDARD,
            totalRamBytes = 5L * 1024L * 1024L * 1024L,
            override = "ULTRA"  // 无效值
        )
        manager.initialize()
        assertEquals(
            "无效覆盖值应降级为检测结果（不阻断启动）",
            PerformanceTier.STANDARD,
            manager.currentTier
        )
    }

    @Test
    fun initialize_empty_override_falls_back_to_detected_tier() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            override = ""  // 空字符串
        )
        manager.initialize()
        assertEquals(
            "空字符串覆盖应降级为检测结果",
            PerformanceTier.FULL,
            manager.currentTier
        )
    }

    @Test
    fun initialize_lowercase_override_falls_back_to_detected_tier() {
        val manager = createManager(
            detectedTier = PerformanceTier.STANDARD,
            totalRamBytes = 5L * 1024L * 1024L * 1024L,
            override = "full"  // 小写，非枚举名
        )
        manager.initialize()
        assertEquals(
            "小写覆盖值应降级为检测结果（枚举名大小写敏感）",
            PerformanceTier.STANDARD,
            manager.currentTier
        )
    }

    // ============ isInitialized 防御 ============

    @Test
    fun isInitialized_returns_false_before_initialize() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        assertFalse("initialize 前应返回 false", manager.isInitialized)
    }

    @Test
    fun isInitialized_returns_true_after_initialize() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        manager.initialize()
        assertTrue("initialize 后应返回 true", manager.isInitialized)
    }

    // ============ 未初始化访问抛异常 ============

    @Test
    fun currentTier_access_before_initialize_throws() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        assertThrows(IllegalStateException::class.java) {
            manager.currentTier
        }
    }

    @Test
    fun detectedTier_access_before_initialize_throws() {
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        assertThrows(IllegalStateException::class.java) {
            manager.detectedTier
        }
    }

    // ============ currentOverride 默认值 ============

    @Test
    fun currentOverride_before_initialize_returns_auto() {
        // currentOverride 有默认值 AUTO，不抛异常（与其他字段不同）
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        assertEquals(
            "currentOverride 在 initialize 前应返回默认值 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            manager.currentOverride
        )
    }

    @Test
    fun totalRamBytes_before_initialize_returns_zero() {
        // totalRamBytes 有默认值 0，不抛异常
        val manager = createManager(
            detectedTier = PerformanceTier.FULL,
            totalRamBytes = 8L * 1024L * 1024L * 1024L
        )
        assertEquals(
            "totalRamBytes 在 initialize 前应返回默认值 0",
            0L,
            manager.totalRamBytes
        )
    }

    // ============ DefaultOverrideReader 集成（通过 FakePreferenceDataStore）============

    @Test
    fun default_override_reader_reads_from_dataStore() {
        // 不注入 overrideReader，使用 DefaultOverrideReader（runBlocking 包 suspend）
        kotlinx.coroutines.runBlocking {
            configRepository.setOverride(PerformanceTier.MINIMAL.name)
        }
        val detector = TierManager.TierDetector {
            TierManager.DetectionResult(PerformanceTier.FULL, 8L * 1024L * 1024L * 1024L)
        }
        val manager = TierManager(
            tierDetector = detector,
            overrideReader = TierManager.DefaultOverrideReader(configRepository)
        )
        manager.initialize()
        assertEquals(
            "DefaultOverrideReader 应从 DataStore 读取覆盖值",
            PerformanceTier.MINIMAL.name,
            manager.currentOverride
        )
        assertEquals(
            "覆盖值应优先生效",
            PerformanceTier.MINIMAL,
            manager.currentTier
        )
    }

    @Test
    fun default_override_reader_reads_auto_when_not_configured() {
        val detector = TierManager.TierDetector {
            TierManager.DetectionResult(PerformanceTier.STANDARD, 5L * 1024L * 1024L * 1024L)
        }
        val manager = TierManager(
            tierDetector = detector,
            overrideReader = TierManager.DefaultOverrideReader(configRepository)
        )
        manager.initialize()
        assertEquals(
            "DataStore 未配置时 DefaultOverrideReader 应返回 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            manager.currentOverride
        )
        assertEquals(
            "AUTO 时应用检测结果",
            PerformanceTier.STANDARD,
            manager.currentTier
        )
    }

    @Test
    fun default_override_reader_picks_up_changes_between_instances() {
        // 模拟用户修改覆盖后 App 重启，新 DefaultOverrideReader 读到新值
        kotlinx.coroutines.runBlocking {
            configRepository.setOverride(PerformanceTier.CHAT_ONLY.name)
        }
        val detector = TierManager.TierDetector {
            TierManager.DetectionResult(PerformanceTier.FULL, 8L * 1024L * 1024L * 1024L)
        }
        val manager1 = TierManager(
            tierDetector = detector,
            overrideReader = TierManager.DefaultOverrideReader(configRepository)
        )
        manager1.initialize()
        assertEquals(PerformanceTier.CHAT_ONLY, manager1.currentTier)

        // 用户在 UI 修改覆盖为 STANDARD
        kotlinx.coroutines.runBlocking {
            configRepository.setOverride(PerformanceTier.STANDARD.name)
        }

        // 模拟 App 重启：新 TierManager 实例
        val manager2 = TierManager(
            tierDetector = detector,
            overrideReader = TierManager.DefaultOverrideReader(configRepository)
        )
        manager2.initialize()
        assertEquals(
            "重启后新 TierManager 应读到更新后的覆盖值",
            PerformanceTier.STANDARD,
            manager2.currentTier
        )
    }

    // ============ H-01 修复验证（guardrail TKN-M7-GUARDRAIL-001，CWE-754）============

    @Test
    fun default_override_reader_degrades_to_auto_when_dataStore_throws() {
        // H-01 修复验证：DataStore 文件损坏或读取异常时，DefaultOverrideReader 应降级为 AUTO，
        // 避免 onCreate 未捕获异常导致 App 启动崩溃。
        val failingDataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> =
                kotlinx.coroutines.flow.flow { throw java.io.IOException("DataStore corrupted") }
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
            ): androidx.datastore.preferences.core.Preferences {
                throw java.io.IOException("DataStore corrupted")
            }
        }
        val failingRepo = TierConfigRepository(failingDataStore)
        val detector = TierManager.TierDetector {
            TierManager.DetectionResult(PerformanceTier.STANDARD, 5L * 1024L * 1024L * 1024L)
        }
        val manager = TierManager(
            tierDetector = detector,
            overrideReader = TierManager.DefaultOverrideReader(failingRepo)
        )
        // initialize 不应抛异常（H-01 修复：try-catch 兜底）
        manager.initialize()
        assertEquals(
            "DataStore 读取失败时应降级为 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            manager.currentOverride
        )
        assertEquals(
            "降级为 AUTO 后应使用检测结果",
            PerformanceTier.STANDARD,
            manager.currentTier
        )
    }
}
