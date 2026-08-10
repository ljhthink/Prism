package io.prism.memory

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MemoryConfigRepository 单元测试（US-032 AC-4）。
 *
 * 测试覆盖：
 * - 默认窗口大小（10）
 * - 设置/读取窗口大小
 * - 窗口大小 Flow 响应变更
 * - 校验：拒绝 ≤0 的值
 * - 边界值：1（最小正数）、50（最大推荐）
 */
class MemoryConfigRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: MemoryConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = MemoryConfigRepository(dataStore)
    }

    @Test
    fun getWindowSize_returns_default_10_when_not_configured() = runBlocking {
        val size = repository.getWindowSize()
        assertEquals("未配置时应返回默认值 10", MemoryConfigRepository.DEFAULT_WINDOW_SIZE, size)
    }

    @Test
    fun windowSize_flow_emits_default_10_initially() = runBlocking {
        val size = repository.windowSize().first()
        assertEquals("Flow 首次发射应返回默认值 10", MemoryConfigRepository.DEFAULT_WINDOW_SIZE, size)
    }

    @Test
    fun setWindowSize_persists_and_getWindowSize_reads_back() = runBlocking {
        repository.setWindowSize(5)
        assertEquals(5, repository.getWindowSize())
    }

    @Test
    fun windowSize_flow_emits_new_value_after_set() = runBlocking {
        repository.setWindowSize(15)
        val size = repository.windowSize().first()
        assertEquals("Flow 应反映设置后的新值", 15, size)
    }

    @Test
    fun setWindowSize_overwrites_previous_value() = runBlocking {
        repository.setWindowSize(5)
        repository.setWindowSize(20)
        assertEquals("后一次设置应覆盖前一次", 20, repository.getWindowSize())
    }

    @Test
    fun setWindowSize_accepts_minimum_value_1() = runBlocking {
        repository.setWindowSize(1)
        assertEquals("N=1 为最小合法值，应被接受", 1, repository.getWindowSize())
    }

    @Test
    fun setWindowSize_accepts_max_recommended_value_50() = runBlocking {
        repository.setWindowSize(MemoryConfigRepository.MAX_WINDOW_SIZE)
        assertEquals(
            "N=50 为最大推荐值，应被接受",
            MemoryConfigRepository.MAX_WINDOW_SIZE,
            repository.getWindowSize()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun setWindowSize_throws_for_zero() = runBlocking {
        repository.setWindowSize(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setWindowSize_throws_for_negative() = runBlocking {
        repository.setWindowSize(-1)
    }

    // ==================== M-1 上界校验（guardrail-enforcer 纵深防御） ====================

    @Test(expected = IllegalArgumentException::class)
    fun setWindowSize_throws_for_value_above_max() = runBlocking {
        repository.setWindowSize(MemoryConfigRepository.MAX_WINDOW_SIZE + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setWindowSize_throws_for_large_value() = runBlocking {
        repository.setWindowSize(1000)
    }

    @Test
    fun setWindowSize_throws_with_descriptive_message_for_above_max() = runBlocking {
        try {
            repository.setWindowSize(MemoryConfigRepository.MAX_WINDOW_SIZE + 1)
            assertTrue("应抛出 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue("异常消息应包含收到的值", e.message?.contains("51") == true)
            assertTrue("异常消息应说明 token 溢出风险", e.message?.contains("token") == true)
        }
    }

    @Test
    fun setWindowSize_throws_with_descriptive_message_for_zero() = runBlocking {
        try {
            repository.setWindowSize(0)
            assertTrue("应抛出 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue("异常消息应包含收到的值", e.message?.contains("0") == true)
            assertTrue("异常消息应说明下界约束", e.message?.contains("≥") == true)
        }
    }

    @Test
    fun setWindowSize_throws_with_descriptive_message_for_negative() = runBlocking {
        try {
            repository.setWindowSize(-5)
            assertTrue("应抛出 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue("异常消息应包含收到的值", e.message?.contains("-5") == true)
        }
    }

    @Test
    fun default_window_size_is_10() {
        assertEquals("US-032 AC-2 默认 N=10", 10, MemoryConfigRepository.DEFAULT_WINDOW_SIZE)
    }

    @Test
    fun min_window_size_is_1() {
        assertEquals("最小窗口大小为 1", 1, MemoryConfigRepository.MIN_WINDOW_SIZE)
    }

    @Test
    fun max_window_size_is_50() {
        assertEquals("最大推荐窗口大小为 50", 50, MemoryConfigRepository.MAX_WINDOW_SIZE)
    }
}
