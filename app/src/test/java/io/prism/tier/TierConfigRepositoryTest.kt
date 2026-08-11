package io.prism.tier

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * TierConfigRepository 单元测试（US-007 AC-6，ADR-017 4.4）。
 *
 * 测试覆盖：
 * - 默认覆盖值（AUTO）
 * - 设置/读取覆盖值（四档 + AUTO）
 * - Flow 响应变更
 * - 校验：拒绝无效值
 * - clearOverride 恢复 AUTO
 * - VALID_VALUES 完整性
 */
class TierConfigRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: TierConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = TierConfigRepository(dataStore)
    }

    // ============ 默认值 ============

    @Test
    fun getOverride_returns_auto_when_not_configured() = runBlocking {
        val override = repository.getOverride()
        assertEquals(
            "未配置时应返回 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            override
        )
    }

    @Test
    fun override_flow_emits_auto_initially() = runBlocking {
        val override = repository.override().first()
        assertEquals(
            "Flow 首次发射应返回 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            override
        )
    }

    // ============ 设置与读取 ============

    @Test
    fun setOverride_full_persists_and_reads_back() = runBlocking {
        repository.setOverride(PerformanceTier.FULL.name)
        assertEquals(PerformanceTier.FULL.name, repository.getOverride())
    }

    @Test
    fun setOverride_standard_persists_and_reads_back() = runBlocking {
        repository.setOverride(PerformanceTier.STANDARD.name)
        assertEquals(PerformanceTier.STANDARD.name, repository.getOverride())
    }

    @Test
    fun setOverride_minimal_persists_and_reads_back() = runBlocking {
        repository.setOverride(PerformanceTier.MINIMAL.name)
        assertEquals(PerformanceTier.MINIMAL.name, repository.getOverride())
    }

    @Test
    fun setOverride_chatOnly_persists_and_reads_back() = runBlocking {
        repository.setOverride(PerformanceTier.CHAT_ONLY.name)
        assertEquals(PerformanceTier.CHAT_ONLY.name, repository.getOverride())
    }

    @Test
    fun setOverride_auto_persists_and_reads_back() = runBlocking {
        repository.setOverride(TierConfigRepository.OVERRIDE_AUTO)
        assertEquals(TierConfigRepository.OVERRIDE_AUTO, repository.getOverride())
    }

    // ============ Flow 响应变更 ============

    @Test
    fun override_flow_emits_new_value_after_set() = runBlocking {
        repository.setOverride(PerformanceTier.FULL.name)
        val override = repository.override().first()
        assertEquals("Flow 应反映设置后的新值", PerformanceTier.FULL.name, override)
    }

    @Test
    fun override_flow_emits_changes_multiple_times() = runBlocking {
        repository.setOverride(PerformanceTier.FULL.name)
        assertEquals(PerformanceTier.FULL.name, repository.override().first())

        repository.setOverride(PerformanceTier.MINIMAL.name)
        assertEquals(PerformanceTier.MINIMAL.name, repository.override().first())

        repository.setOverride(TierConfigRepository.OVERRIDE_AUTO)
        assertEquals(TierConfigRepository.OVERRIDE_AUTO, repository.override().first())
    }

    // ============ 覆盖写入 ============

    @Test
    fun setOverride_overwrites_previous_value() = runBlocking {
        repository.setOverride(PerformanceTier.FULL.name)
        repository.setOverride(PerformanceTier.CHAT_ONLY.name)
        assertEquals(
            "后一次设置应覆盖前一次",
            PerformanceTier.CHAT_ONLY.name,
            repository.getOverride()
        )
    }

    // ============ clearOverride ============

    @Test
    fun clearOverride_restores_auto() = runBlocking {
        repository.setOverride(PerformanceTier.FULL.name)
        repository.clearOverride()
        assertEquals(
            "clearOverride 后应恢复为 AUTO",
            TierConfigRepository.OVERRIDE_AUTO,
            repository.getOverride()
        )
    }

    // ============ 校验 ============

    @Test
    fun setOverride_rejects_invalid_lowercase_value() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setOverride("full") }  // 小写无效
        }
    }

    @Test
    fun setOverride_rejects_invalid_unknown_value() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setOverride("ULTRA") }  // 未知档位
        }
    }

    @Test
    fun setOverride_rejects_empty_string() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setOverride("") }
        }
    }

    @Test
    fun setOverride_rejects_blank_string() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setOverride("   ") }
        }
    }

    @Test
    fun setOverride_rejects_null_like_value() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setOverride("null") }
        }
    }

    // ============ VALID_VALUES 完整性 ============

    @Test
    fun valid_values_contains_all_tiers_and_auto() {
        val expected = setOf(
            TierConfigRepository.OVERRIDE_AUTO,
            PerformanceTier.FULL.name,
            PerformanceTier.STANDARD.name,
            PerformanceTier.MINIMAL.name,
            PerformanceTier.CHAT_ONLY.name
        )
        assertEquals(
            "VALID_VALUES 应包含 AUTO + 四档枚举名",
            expected,
            TierConfigRepository.VALID_VALUES
        )
    }

    @Test
    fun valid_values_size_is_5() {
        assertEquals(
            "VALID_VALUES 应有 5 个值（AUTO + 四档）",
            5,
            TierConfigRepository.VALID_VALUES.size
        )
    }

    // ============ 持久化跨实例 ============

    @Test
    fun override_persists_across_repository_instances() = runBlocking {
        repository.setOverride(PerformanceTier.MINIMAL.name)

        // 用同一 dataStore 构造新 repository，模拟 App 重启后读取
        val newRepository = TierConfigRepository(dataStore)
        assertEquals(
            "新 repository 实例应能读到之前持久化的覆盖值",
            PerformanceTier.MINIMAL.name,
            newRepository.getOverride()
        )
    }
}
