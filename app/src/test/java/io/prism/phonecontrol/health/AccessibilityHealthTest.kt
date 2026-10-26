package io.prism.phonecontrol.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessibilityHealth] 单测（ADR-046 修订 2026-10-26；PRD US-3116 AC-1/AC-5）。
 *
 * 关键点：`REBINDING`（系统已注册、实例未连）**不能**触发写 settings——
 * 那种情况写回是无效动作，只会多一次无谓的 shell 调用与日志噪音。
 */
class AccessibilityHealthTest {

    @Test
    fun `三态判定`() {
        assertEquals(
            AccessibilityHealth.State.OK,
            AccessibilityHealth.state(enabledInSystem = true, serviceConnected = true)
        )
        assertEquals(
            AccessibilityHealth.State.REBINDING,
            AccessibilityHealth.state(enabledInSystem = true, serviceConnected = false)
        )
        assertEquals(
            AccessibilityHealth.State.DISABLED,
            AccessibilityHealth.state(enabledInSystem = false, serviceConnected = false)
        )
    }

    @Test
    fun `只有 DISABLED 且开关开且 Shizuku 就绪且未超限才恢复`() {
        assertTrue(
            AccessibilityHealth.shouldAttemptRestore(
                AccessibilityHealth.State.DISABLED, true, true, 0
            )
        )
        // REBINDING 不写 settings
        assertFalse(
            AccessibilityHealth.shouldAttemptRestore(
                AccessibilityHealth.State.REBINDING, true, true, 0
            )
        )
        // 开关关
        assertFalse(
            AccessibilityHealth.shouldAttemptRestore(
                AccessibilityHealth.State.DISABLED, false, true, 0
            )
        )
        // Shizuku 未就绪
        assertFalse(
            AccessibilityHealth.shouldAttemptRestore(
                AccessibilityHealth.State.DISABLED, true, false, 0
            )
        )
        // 超过窗口上限
        assertFalse(
            AccessibilityHealth.shouldAttemptRestore(
                AccessibilityHealth.State.DISABLED, true, true, AccessibilityHealth.DEFAULT_ATTEMPT_LIMIT
            )
        )
    }

    @Test
    fun `阻塞原因必须可读且区分三种情况`() {
        assertNull(
            AccessibilityHealth.blockReason(AccessibilityHealth.State.DISABLED, true, true, 0)
        )
        val noSwitch = AccessibilityHealth.blockReason(AccessibilityHealth.State.DISABLED, false, true, 0)
        assertTrue(noSwitch!!.contains("自动恢复已关闭"))

        val noShizuku = AccessibilityHealth.blockReason(AccessibilityHealth.State.DISABLED, true, false, 0)
        assertTrue(noShizuku!!.contains("Shizuku"))

        val exhausted = AccessibilityHealth.blockReason(
            AccessibilityHealth.State.DISABLED, true, true, AccessibilityHealth.DEFAULT_ATTEMPT_LIMIT
        )
        assertTrue(exhausted!!.contains("已尝试"))
        // 正常状态不产生阻塞原因
        assertNull(AccessibilityHealth.blockReason(AccessibilityHealth.State.OK, false, false, 99))
    }

    @Test
    fun `限频常量满足耗电约束（≥30s 检查、≥10 分钟窗口）`() {
        assertTrue(AccessibilityHealth.CHECK_INTERVAL_MS >= 30_000L)
        assertTrue(AccessibilityHealth.ATTEMPT_WINDOW_MS >= 10 * 60 * 1000L)
        assertTrue(AccessibilityHealth.DEFAULT_ATTEMPT_LIMIT in 1..5)
    }
}
