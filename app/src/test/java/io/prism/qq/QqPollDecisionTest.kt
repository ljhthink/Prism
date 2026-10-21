package io.prism.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqPollDecision] 建屏决策测试（US-3111 AC-4；guardrail H-05）。
 *
 * 关键回归：`null` **不得**放行，否则"连续未知 5 次才强制放行"的兜底完全失效。
 */
class QqPollDecisionTest {

    @Test
    fun `明确命中才建屏`() {
        assertTrue(QqPollDecision.shouldPoll(true))
    }

    @Test
    fun `无变化不建屏`() {
        assertFalse(QqPollDecision.shouldPoll(false))
    }

    @Test
    fun `未知不建屏（由探针内部的强制放行兜底）`() {
        assertFalse(QqPollDecision.shouldPoll(null))
    }
}
