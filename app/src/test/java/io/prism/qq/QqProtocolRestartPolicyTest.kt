package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqProtocolRestartPolicy] 边界测试（US-3128）。
 *
 * 重点防两件事：①崩溃循环（无限重启烧电）；②自踢账号（已有实例还去拉起，ADR-069 踩过）。
 */
class QqProtocolRestartPolicyTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun `首次不可达立即允许重启`() {
        val d = QqProtocolRestartPolicy.decide(
            nowMs = t0,
            state = QqProtocolRestartPolicy.State(),
            alreadyRunning = false,
            enabled = true
        )
        assertTrue(d is QqProtocolRestartPolicy.Decision.Allow)
        assertEquals(1, (d as QqProtocolRestartPolicy.Decision.Allow).state.attempts)
    }

    @Test
    fun `退避间隔未到则等待`() {
        val first = QqProtocolRestartPolicy.decide(
            nowMs = t0, state = QqProtocolRestartPolicy.State(),
            alreadyRunning = false, enabled = true
        ) as QqProtocolRestartPolicy.Decision.Allow
        val wait = QqProtocolRestartPolicy.decide(
            nowMs = t0 + 30_000L, state = first.state,
            alreadyRunning = false, enabled = true
        )
        assertTrue(wait is QqProtocolRestartPolicy.Decision.Wait)
        assertEquals(30_000L, (wait as QqProtocolRestartPolicy.Decision.Wait).waitMs)
    }

    @Test
    fun `退避阶梯是 1 分钟 5 分钟`() {
        var s = QqProtocolRestartPolicy.State()
        var now = t0
        val gaps = mutableListOf<Long>()
        // 只在"还会继续重启"的两次之间测量间隔（第 3 次之后停手，没有下一段间隔）
        repeat(QqProtocolRestartPolicy.MAX_ATTEMPTS - 1) {
            val d = QqProtocolRestartPolicy.decide(
                nowMs = now, state = s, alreadyRunning = false, enabled = true
            ) as QqProtocolRestartPolicy.Decision.Allow
            val next = d.state
            val w = QqProtocolRestartPolicy.decide(
                nowMs = now + 1_000L, state = next, alreadyRunning = false, enabled = true
            ) as QqProtocolRestartPolicy.Decision.Wait
            gaps += w.waitMs + 1_000L
            s = next
            now += 20 * minute
        }
        assertEquals(listOf(minute, 5 * minute), gaps)
    }

    @Test
    fun `窗口内三次用尽后停手`() {
        var s = QqProtocolRestartPolicy.State()
        var now = t0
        repeat(QqProtocolRestartPolicy.MAX_ATTEMPTS) {
            val d = QqProtocolRestartPolicy.decide(
                nowMs = now, state = s, alreadyRunning = false, enabled = true
            )
            assertTrue(d is QqProtocolRestartPolicy.Decision.Allow)
            s = (d as QqProtocolRestartPolicy.Decision.Allow).state
            now += 10 * minute
        }
        val giveUp = QqProtocolRestartPolicy.decide(
            nowMs = now, state = s, alreadyRunning = false, enabled = true
        )
        assertEquals(QqProtocolRestartPolicy.Decision.GiveUp, giveUp)
    }

    @Test
    fun `窗口过期后重新计数`() {
        var s = QqProtocolRestartPolicy.State()
        var now = t0
        repeat(QqProtocolRestartPolicy.MAX_ATTEMPTS) {
            val d = QqProtocolRestartPolicy.decide(
                nowMs = now, state = s, alreadyRunning = false, enabled = true
            ) as QqProtocolRestartPolicy.Decision.Allow
            s = d.state
            now += 10 * minute
        }
        assertEquals(
            QqProtocolRestartPolicy.Decision.GiveUp,
            QqProtocolRestartPolicy.decide(
                nowMs = now, state = s, alreadyRunning = false, enabled = true
            )
        )
        // 超出统计窗口（1h）后重新允许
        val later = now + QqProtocolRestartPolicy.WINDOW_MS
        val again = QqProtocolRestartPolicy.decide(
            nowMs = later, state = s, alreadyRunning = false, enabled = true
        )
        assertTrue(again is QqProtocolRestartPolicy.Decision.Allow)
        assertEquals(1, (again as QqProtocolRestartPolicy.Decision.Allow).state.attempts)
    }

    @Test
    fun `已在运行时绝不重复拉起（防自踢）`() {
        val d = QqProtocolRestartPolicy.decide(
            nowMs = t0,
            state = QqProtocolRestartPolicy.State(),
            alreadyRunning = true,
            enabled = true
        )
        assertEquals(QqProtocolRestartPolicy.Decision.GiveUp, d)
    }

    @Test
    fun `总开关关闭时不重启`() {
        val d = QqProtocolRestartPolicy.decide(
            nowMs = t0,
            state = QqProtocolRestartPolicy.State(),
            alreadyRunning = false,
            enabled = false
        )
        assertEquals(QqProtocolRestartPolicy.Decision.GiveUp, d)
    }
}
