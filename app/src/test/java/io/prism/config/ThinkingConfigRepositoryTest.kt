package io.prism.config

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ThinkingConfigRepository 单元测试（问题 8a，ADR-020）。
 *
 * 测试覆盖：
 * - 默认开关状态（false，避免向不兼容端点发送 thinking 参数）
 * - 默认思考强度（high）
 * - 设置/读取开关与强度（DataStore 持久化 + Flow 响应）
 * - 强度校验：拒绝非法值（fail-fast）
 */
class ThinkingConfigRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: ThinkingConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = ThinkingConfigRepository(dataStore)
    }

    @Test
    fun default_thinking_enabled_is_false() {
        assertFalse("默认关闭深度思考（ADR-020：避免向不兼容端点发送 thinking 参数）", ThinkingConfigRepository.DEFAULT_ENABLED)
    }

    @Test
    fun default_reasoning_effort_is_high() {
        assertEquals("默认思考强度为 high", "high", ThinkingConfigRepository.DEFAULT_EFFORT)
    }

    @Test
    fun getThinkingEnabled_returns_default_false_when_not_configured() = runBlocking {
        assertFalse("未配置时应返回默认关闭", repository.getThinkingEnabled())
    }

    @Test
    fun thinkingEnabled_flow_emits_default_false_initially() = runBlocking {
        assertFalse("Flow 首次发射应返回默认 false", repository.thinkingEnabled().first())
    }

    @Test
    fun setThinkingEnabled_persists_and_reads_back() = runBlocking {
        repository.setThinkingEnabled(true)
        assertTrue("开启后应读取到 true", repository.getThinkingEnabled())
        repository.setThinkingEnabled(false)
        assertFalse("关闭后应读取到 false", repository.getThinkingEnabled())
    }

    @Test
    fun getReasoningEffort_returns_default_high_when_not_configured() = runBlocking {
        assertEquals("未配置时应返回默认 high", "high", repository.getReasoningEffort())
    }

    @Test
    fun setReasoningEffort_persists_and_reads_back() = runBlocking {
        repository.setReasoningEffort("max")
        assertEquals("设置 max 后应读取到 max", "max", repository.getReasoningEffort())
    }

    @Test
    fun reasoningEffort_flow_emits_new_value_after_set() = runBlocking {
        repository.setReasoningEffort("low")
        assertEquals("Flow 应反映设置后的新值", "low", repository.reasoningEffort().first())
    }

    @Test
    fun setReasoningEffort_accepts_all_valid_values() = runBlocking {
        ThinkingConfigRepository.EFFORT_VALUES.forEach { effort ->
            repository.setReasoningEffort(effort)
            assertEquals("合法值 $effort 应被接受", effort, repository.getReasoningEffort())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun setReasoningEffort_throws_for_invalid_value() = runBlocking {
        repository.setReasoningEffort("ultra")
    }

    @Test(expected = IllegalArgumentException::class)
    fun setReasoningEffort_throws_for_empty_string() = runBlocking {
        repository.setReasoningEffort("")
    }

    @Test
    fun setReasoningEffort_throws_with_descriptive_message() = runBlocking {
        try {
            repository.setReasoningEffort("extreme")
            assertTrue("应抛出 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue("异常消息应包含收到的值", e.message?.contains("extreme") == true)
        }
    }
}
