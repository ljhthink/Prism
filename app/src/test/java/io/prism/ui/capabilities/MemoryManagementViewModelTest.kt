package io.prism.ui.capabilities

import io.prism.memory.MemoryConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryManagementViewModel 纯函数单元测试（M-1 补齐，US-036 AC-5）。
 *
 * **覆盖目标**（guardrail TKN-M5-PHASEE-GUARDRAIL-001 M-1）：
 * - [MemoryManagementViewModel.Companion.validateWindowSize] 边界值分析（MIN-1 / MIN / MIN+1 / MAX-1 / MAX / MAX+1 / 0 / 负数 / 超大值 / 中间值）
 * - [MemoryManagementViewModel.Companion.validateProfile] 等价类+边界值（空 key / 空 value / 纯空白 / key 边界 / value 边界 / 合法 / trim 后合法）
 * - [MemoryManagementViewModel.Companion.buildClearResultMessage] 决策表四分支（0+0 / N+0 / 0+N / N+M）
 * - 常量值验证（MAX_PROFILE_KEY_LEN / MAX_PROFILE_VALUE_LEN）
 *
 * **测试层级**：纯 JVM 单元测试（BR-testing-004），不依赖 Android Context / ObjectBox / 协程。
 * 所有被测函数均为 companion object internal 纯函数，输入决定输出，无副作用。
 *
 * **关联**：
 * - guardrail round 1 M-1：纯函数缺少独立单元测试（违反 BR-testing-004）
 * - guardrail round 2：M-1 维持，待 ac-verifier 补齐
 * - 任务令牌：TKN-M5-PHASEE-ACCEPTANCE-001
 */
class MemoryManagementViewModelTest {

    // ==================== 常量值验证 ====================

    @Test
    fun `MAX_PROFILE_KEY_LEN is 50`() {
        assertEquals(50, MemoryManagementViewModel.MAX_PROFILE_KEY_LEN)
    }

    @Test
    fun `MAX_PROFILE_VALUE_LEN is 500`() {
        assertEquals(500, MemoryManagementViewModel.MAX_PROFILE_VALUE_LEN)
    }

    // ==================== validateWindowSize 边界值分析 ====================
    // MIN_WINDOW_SIZE=1, MAX_WINDOW_SIZE=50

    @Test
    fun `validateWindowSize - below min (0) returns invalid`() {
        val result = MemoryManagementViewModel.validateWindowSize(0)
        assertFalse("0 应低于下界，返回 invalid", result.valid)
        assertTrue("应包含下界提示", result.message.contains("小于"))
    }

    @Test
    fun `validateWindowSize - min (1) returns valid`() {
        val result = MemoryManagementViewModel.validateWindowSize(MemoryConfigRepository.MIN_WINDOW_SIZE)
        assertTrue("MIN_WINDOW_SIZE 应返回 valid", result.valid)
        assertEquals("valid 时 message 应为空", "", result.message)
    }

    @Test
    fun `validateWindowSize - min plus 1 (2) returns valid`() {
        val result = MemoryManagementViewModel.validateWindowSize(2)
        assertTrue("2 应返回 valid", result.valid)
    }

    @Test
    fun `validateWindowSize - max minus 1 (49) returns valid`() {
        val result = MemoryManagementViewModel.validateWindowSize(49)
        assertTrue("49 应返回 valid", result.valid)
    }

    @Test
    fun `validateWindowSize - max (50) returns valid`() {
        val result = MemoryManagementViewModel.validateWindowSize(MemoryConfigRepository.MAX_WINDOW_SIZE)
        assertTrue("MAX_WINDOW_SIZE 应返回 valid", result.valid)
        assertEquals("valid 时 message 应为空", "", result.message)
    }

    @Test
    fun `validateWindowSize - above max (51) returns invalid`() {
        val result = MemoryManagementViewModel.validateWindowSize(51)
        assertFalse("51 应超过上界，返回 invalid", result.valid)
        assertTrue("应包含上界提示", result.message.contains("大于"))
        assertTrue("应包含 token 溢出提示", result.message.contains("token"))
    }

    @Test
    fun `validateWindowSize - negative returns invalid`() {
        val result = MemoryManagementViewModel.validateWindowSize(-1)
        assertFalse("负数应返回 invalid", result.valid)
        assertTrue("应包含下界提示", result.message.contains("小于"))
    }

    @Test
    fun `validateWindowSize - very large value (1000) returns invalid`() {
        val result = MemoryManagementViewModel.validateWindowSize(1000)
        assertFalse("1000 应超过上界，返回 invalid", result.valid)
        assertTrue("应包含上界提示", result.message.contains("大于"))
    }

    @Test
    fun `validateWindowSize - nominal value (10) returns valid`() {
        val result = MemoryManagementViewModel.validateWindowSize(10)
        assertTrue("10（默认值）应返回 valid", result.valid)
    }

    // ==================== validateProfile 等价类 + 边界值分析 ====================
    // MAX_PROFILE_KEY_LEN=50, MAX_PROFILE_VALUE_LEN=500

    @Test
    fun `validateProfile - empty key returns invalid`() {
        val result = MemoryManagementViewModel.validateProfile("", "val")
        assertFalse("空 key 应返回 invalid", result.valid)
        assertTrue("应提示不能为空", result.message.contains("空"))
    }

    @Test
    fun `validateProfile - empty value returns invalid`() {
        val result = MemoryManagementViewModel.validateProfile("key", "")
        assertFalse("空 value 应返回 invalid", result.valid)
        assertTrue("应提示不能为空", result.message.contains("空"))
    }

    @Test
    fun `validateProfile - whitespace only key returns invalid after trim`() {
        val result = MemoryManagementViewModel.validateProfile("   ", "val")
        assertFalse("纯空白 key trim 后为空，应返回 invalid", result.valid)
    }

    @Test
    fun `validateProfile - whitespace only value returns invalid after trim`() {
        val result = MemoryManagementViewModel.validateProfile("key", "   ")
        assertFalse("纯空白 value trim 后为空，应返回 invalid", result.valid)
    }

    @Test
    fun `validateProfile - key at max length (50) returns valid`() {
        val key = "a".repeat(MemoryManagementViewModel.MAX_PROFILE_KEY_LEN)
        val result = MemoryManagementViewModel.validateProfile(key, "val")
        assertTrue("key 恰好 MAX_PROFILE_KEY_LEN 应返回 valid", result.valid)
    }

    @Test
    fun `validateProfile - key exceeds max length (51) returns invalid`() {
        val key = "a".repeat(MemoryManagementViewModel.MAX_PROFILE_KEY_LEN + 1)
        val result = MemoryManagementViewModel.validateProfile(key, "val")
        assertFalse("key 超过 MAX_PROFILE_KEY_LEN 应返回 invalid", result.valid)
        assertTrue("应提示 key 过长", result.message.contains("偏好键"))
        assertTrue("应包含长度上限", result.message.contains("50"))
    }

    @Test
    fun `validateProfile - value at max length (500) returns valid`() {
        val value = "a".repeat(MemoryManagementViewModel.MAX_PROFILE_VALUE_LEN)
        val result = MemoryManagementViewModel.validateProfile("key", value)
        assertTrue("value 恰好 MAX_PROFILE_VALUE_LEN 应返回 valid", result.valid)
    }

    @Test
    fun `validateProfile - value exceeds max length (501) returns invalid`() {
        val value = "a".repeat(MemoryManagementViewModel.MAX_PROFILE_VALUE_LEN + 1)
        val result = MemoryManagementViewModel.validateProfile("key", value)
        assertFalse("value 超过 MAX_PROFILE_VALUE_LEN 应返回 invalid", result.valid)
        assertTrue("应提示 value 过长", result.message.contains("偏好值"))
        assertTrue("应包含长度上限", result.message.contains("500"))
    }

    @Test
    fun `validateProfile - valid key and value returns valid`() {
        val result = MemoryManagementViewModel.validateProfile("tone", "简洁")
        assertTrue("合法 key+value 应返回 valid", result.valid)
        assertEquals("valid 时 message 应为空", "", result.message)
    }

    @Test
    fun `validateProfile - valid after trim returns valid`() {
        val result = MemoryManagementViewModel.validateProfile("  tone  ", "  简洁  ")
        assertTrue("trim 后合法的 key+value 应返回 valid", result.valid)
    }

    @Test
    fun `validateProfile - both empty returns invalid`() {
        val result = MemoryManagementViewModel.validateProfile("", "")
        assertFalse("key 和 value 均空应返回 invalid", result.valid)
    }

    @Test
    fun `validateProfile - key at boundary and value exceeds returns invalid for value`() {
        val key = "a".repeat(MemoryManagementViewModel.MAX_PROFILE_KEY_LEN)
        val value = "a".repeat(MemoryManagementViewModel.MAX_PROFILE_VALUE_LEN + 1)
        val result = MemoryManagementViewModel.validateProfile(key, value)
        assertFalse("key 合法但 value 超长应返回 invalid", result.valid)
        assertTrue("应提示 value 过长（非 key）", result.message.contains("偏好值"))
    }

    // ==================== buildClearResultMessage 决策表四分支 ====================

    @Test
    fun `buildClearResultMessage - both zero returns no memory message`() {
        val result = MemoryManagementViewModel.buildClearResultMessage(0L, 0L)
        assertEquals("无记忆需要清除", result)
    }

    @Test
    fun `buildClearResultMessage - only memories returns memory count message`() {
        val result = MemoryManagementViewModel.buildClearResultMessage(5L, 0L)
        assertEquals("已清除 5 条跨会话记忆", result)
    }

    @Test
    fun `buildClearResultMessage - only profiles returns profile count message`() {
        val result = MemoryManagementViewModel.buildClearResultMessage(0L, 3L)
        assertEquals("已清除 3 条用户画像", result)
    }

    @Test
    fun `buildClearResultMessage - both non-zero returns combined message`() {
        val result = MemoryManagementViewModel.buildClearResultMessage(5L, 3L)
        assertEquals("已清除 5 条跨会话记忆 · 3 条用户画像", result)
    }

    @Test
    fun `buildClearResultMessage - single memory and single profile`() {
        val result = MemoryManagementViewModel.buildClearResultMessage(1L, 1L)
        assertEquals("已清除 1 条跨会话记忆 · 1 条用户画像", result)
    }

    @Test
    fun `buildClearResultMessage - large counts`() {
        val result = MemoryManagementViewModel.buildClearResultMessage(999L, 888L)
        assertEquals("已清除 999 条跨会话记忆 · 888 条用户画像", result)
    }

    // ==================== nextAvailableKey（G-01，BR-interface-015） ====================

    @Test
    fun `nextAvailableKey - no conflict returns base unchanged`() {
        assertEquals("tone", MemoryManagementViewModel.nextAvailableKey("tone", emptySet()))
    }

    @Test
    fun `nextAvailableKey - base occupied appends _2`() {
        assertEquals(
            "tone_2",
            MemoryManagementViewModel.nextAvailableKey("tone", setOf("tone"))
        )
    }

    @Test
    fun `nextAvailableKey - consecutive occupation finds next gap`() {
        // tone 与 tone_2 已被占用 → 落 tone_3（同类别第三条偏好并存）
        assertEquals(
            "tone_3",
            MemoryManagementViewModel.nextAvailableKey("tone", setOf("tone", "tone_2"))
        )
    }

    @Test
    fun `nextAvailableKey - gap in sequence fills the gap`() {
        // tone_2 被删后重添同类别偏好 → 复用 tone_2 空位
        assertEquals(
            "tone_2",
            MemoryManagementViewModel.nextAvailableKey("tone", setOf("tone", "tone_3"))
        )
    }

    @Test
    fun `nextAvailableKey - exhausted suffixes falls back to last candidate`() {
        // 防御性路径：2..100 全占用（实践不可达）→ 返回最后一个尝试值
        val occupied = (2..100).map { "tone_$it" }.toSet() + setOf("tone")
        assertEquals("tone_100", MemoryManagementViewModel.nextAvailableKey("tone", occupied))
    }

    @Test
    fun `nextAvailableKey - derived hash key follows same dedupe semantics`() {
        // pref_hash 形式派生 key 同样受冲突保护（异句同类别兜底路径）
        assertEquals(
            "pref_1a2b3c4d_2",
            MemoryManagementViewModel.nextAvailableKey("pref_1a2b3c4d", setOf("pref_1a2b3c4d"))
        )
    }

    @Test
    fun `nextAvailableKey - G2-02 long base truncated to keep candidate within key limit`() {
        // 纵深防御：base 已达 50 字符上限时，追加后缀须先截断 base 保证候选 ≤50
        val longBase = "k".repeat(MemoryManagementViewModel.MAX_PROFILE_KEY_LEN)
        val candidate = MemoryManagementViewModel.nextAvailableKey(longBase, setOf(longBase))
        assertTrue(
            "候选 key 应 ≤ MAX_PROFILE_KEY_LEN：${candidate.length}",
            candidate.length <= MemoryManagementViewModel.MAX_PROFILE_KEY_LEN
        )
        // 50 - 2("_2") = 48 个 k + "_2"
        assertEquals("k".repeat(48) + "_2", candidate)
    }
}
