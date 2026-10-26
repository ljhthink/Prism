package io.prism.qq

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [QqUnreadProbe] 纯判定与兜底策略测试（US-3111 AC-4；guardrail H-04）。
 */
class QqUnreadProbeTest {

    private fun probe(snapshot: String?) = QqUnreadProbe(
        snapshotProvider = { snapshot },
        relevance = { it.contains("com.tencent.mobileqq") }
    )

    @Test
    fun `首次探测返回未知（不误判为有变化）`() {
        assertNull(probe("pkg=com.tencent.mobileqq id=1").evaluate("pkg=com.tencent.mobileqq id=1"))
    }

    @Test
    fun `摘要未变化返回 false（调用方不建屏）`() {
        val p = probe(null)
        val text = "pkg=com.tencent.mobileqq id=1"
        assertNull(p.evaluate(text))
        assertEquals(false, p.evaluate(text))
    }

    @Test
    fun `摘要变化返回 true`() {
        val p = probe(null)
        assertNull(p.evaluate("pkg=com.tencent.mobileqq id=1"))
        assertEquals(true, p.evaluate("pkg=com.tencent.mobileqq id=2"))
    }

    @Test
    fun `无 QQ 相关行或摘要为空返回未知`() {
        val p = probe(null)
        assertNull(p.evaluate("pkg=com.other.app id=1"))
        assertNull(p.evaluate(""))
        assertNull(p.evaluate(null))
    }

    @Test
    fun `生产节奏下最长静默仍会到期（每 90s 无变化，M-19）`() = runBlocking {
        var now = 0L
        val p = QqUnreadProbe(
            snapshotProvider = { "pkg=com.tencent.mobileqq id=1" },
            relevance = { true },
            clock = { now },
            maxSilenceMs = 15 * 60 * 1000L
        )
        assertNull(p.hasNewActivity()) // 首次基线
        // 模拟生产：每 90s 调用一次，摘要始终不变
        repeat(9) {
            now += 90_000L
            assertEquals(false, p.hasNewActivity())
        }
        // 第 10 次（累计 900s = 15min）到期 → 强制放行
        now = 15 * 60 * 1000L
        assertEquals(true, p.hasNewActivity()) // 静默到期：强制放行
        // 放行后重新计时：立刻再调用应为无变化
        assertEquals(false, p.hasNewActivity())
    }

    @Test
    fun `首次调用晚于构造时不会立即强制建屏（L-29 惰性初始化）`() = runBlocking {
        // 服务在进程启动 20 分钟后才首次轮询：计时器此时才起算，不得立刻强制放行
        var now = 20 * 60 * 1000L
        val p = QqUnreadProbe(
            snapshotProvider = { "pkg=com.tencent.mobileqq id=1" },
            relevance = { true },
            clock = { now }
        )
        assertNull("首次调用应建立基线而非强制放行", p.hasNewActivity())
        now += 90_000L
        assertEquals(false, p.hasNewActivity())
    }

    @Test
    fun `连续未知达到阈值后强制放行一次`() = runBlocking {
        var calls = 0
        val p = QqUnreadProbe(
            snapshotProvider = { calls++; null },
            relevance = { true }
        )
        repeat(QqUnreadProbe.FORCE_POLL_AFTER_UNKNOWN - 1) {
            assertNull(p.hasNewActivity())
        }
        assertEquals(true, p.hasNewActivity())
        assertNull(p.hasNewActivity()) // 计数已重置
    }
}
