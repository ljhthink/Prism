package io.prism.config

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ToolApprovalConfigRepository 单元测试（UXR3 问题 10，ADR-023）。
 *
 * 覆盖：
 * - 默认模式 MANUAL（无持久化记录时）
 * - setMode 持久化 + getMode 读取回环（三种模式）
 * - mode() Flow 响应新值
 * - 非法持久化字符串兜底为 DEFAULT（纵深防御，BR-security-005）
 */
class ToolApprovalConfigRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: ToolApprovalConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = ToolApprovalConfigRepository(dataStore)
    }

    @Test
    fun default_mode_is_manual_when_not_configured() = runBlocking {
        assertEquals("未配置时应返回默认 MANUAL", ToolApprovalMode.DEFAULT, repository.getMode())
        assertEquals(ToolApprovalMode.MANUAL, ToolApprovalMode.DEFAULT)
    }

    @Test
    fun mode_flow_emits_default_manual_initially() = runBlocking {
        assertEquals("Flow 首次发射应返回默认 MANUAL", ToolApprovalMode.MANUAL, repository.mode().first())
    }

    @Test
    fun setMode_round_trips_all_three_modes() = runBlocking {
        repository.setMode(ToolApprovalMode.AUTO)
        assertEquals("AUTO 应读取回", ToolApprovalMode.AUTO, repository.getMode())
        repository.setMode(ToolApprovalMode.DISABLED)
        assertEquals("DISABLED 应读取回", ToolApprovalMode.DISABLED, repository.getMode())
        repository.setMode(ToolApprovalMode.MANUAL)
        assertEquals("MANUAL 应读取回", ToolApprovalMode.MANUAL, repository.getMode())
    }

    @Test
    fun mode_flow_emits_new_value_after_set() = runBlocking {
        repository.setMode(ToolApprovalMode.AUTO)
        assertEquals("Flow 应反映设置后的新值", ToolApprovalMode.AUTO, repository.mode().first())
    }

    @Test
    fun invalid_persisted_string_falls_back_to_default() = runBlocking {
        // 模拟 DataStore 被外部写入非法模式字符串（纵深防御，BR-security-005）
        val corrupted = FakePreferenceDataStore(
            mutablePreferencesOf(stringPreferencesKey("tool_approval_mode") to "NOT_A_MODE")
        )
        val repo = ToolApprovalConfigRepository(corrupted)
        assertEquals("非法字符串应兜底为 DEFAULT", ToolApprovalMode.DEFAULT, repo.mode().first())
    }

    @Test
    fun modes_list_contains_all_three_modes() {
        assertEquals(3, ToolApprovalConfigRepository.MODES.size)
        assertEquals(
            listOf(ToolApprovalMode.MANUAL, ToolApprovalMode.AUTO, ToolApprovalMode.DISABLED),
            ToolApprovalConfigRepository.MODES
        )
    }
}
