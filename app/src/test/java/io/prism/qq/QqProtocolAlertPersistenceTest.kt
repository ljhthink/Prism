package io.prism.qq

import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议端管家的**持久化**测试（US-3128 AC-9；2026-11-01 验收补充）。
 *
 * 为什么必须有这一层：纯函数测试（[QqProtocolAlertPolicyTest]）只证明了"状态算得对"，
 * 但 10-31 事故的形态是 **Prism 自己也被杀**——如果「曾经稳定在线过」这个事实没落盘，
 * 重启后协议端一直不回来就**永远不会告警**。这条恰恰只能在这一层验证。
 */
class QqProtocolAlertPersistenceTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun `武装状态在进程重建后仍然有效`() = runBlocking {
        val store = FakePreferenceDataStore()
        val repo = QqRuntimeStateRepository(store)

        // 第一次运行：稳定在线 10 分钟 → 武装
        repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0, enabled = true, loopback = true
        )
        repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0 + 10 * minute, enabled = true, loopback = true
        )
        assertTrue("首次运行后应已武装", repo.get().protocolAlert.armed)

        // 模拟"进程被杀后重启"：换一个 repo 实例，读同一个 store
        val repo2 = QqRuntimeStateRepository(store)
        assertTrue("武装状态必须从磁盘读回", repo2.get().protocolAlert.armed)

        // 重启后连续不可达 → 必须能告警
        var notified = false
        repeat(3) { i ->
            val d = repo2.advanceProtocolAlert(
                probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE,
                nowMs = t0 + 11 * minute + i * minute,
                enabled = true, loopback = true
            )
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified = true
        }
        assertTrue("重启后仍应能告警（否则 10-31 那种场景永远静默）", notified)
    }

    @Test
    fun `重启记账跨进程保留且用尽后停手`() = runBlocking {
        val store = FakePreferenceDataStore()
        val repo = QqRuntimeStateRepository(store)
        repo.recordProtocolRestart(windowStartMs = t0, attempts = 3, lastRestartAtMs = t0 + minute)

        val repo2 = QqRuntimeStateRepository(store)
        val state = repo2.get()
        assertEquals("窗口起点应保留", t0, state.protocolRestartWindowStartMs)
        assertEquals("已用次数应保留", 3, state.protocolRestartAttempts)
        assertEquals("上次重启时刻应保留", t0 + minute, state.protocolLastRestartAtMs)

        // 用尽 3 次 → 停手（交给告警），不得继续重启
        val d = QqProtocolRestartPolicy.decide(
            nowMs = t0 + 10 * minute,
            state = QqProtocolRestartPolicy.State(
                windowStartMs = state.protocolRestartWindowStartMs,
                attempts = state.protocolRestartAttempts,
                lastRestartAtMs = state.protocolLastRestartAtMs
            ),
            alreadyRunning = false,
            enabled = true
        )
        assertEquals(QqProtocolRestartPolicy.Decision.GiveUp, d)
    }

    @Test
    fun `clearProtocolRestart 清零记账`() = runBlocking {
        val store = FakePreferenceDataStore()
        val repo = QqRuntimeStateRepository(store)
        repo.recordProtocolRestart(windowStartMs = t0, attempts = 2, lastRestartAtMs = t0)
        repo.clearProtocolRestart()
        val state = repo.get()
        assertEquals(0L, state.protocolRestartWindowStartMs)
        assertEquals(0, state.protocolRestartAttempts)
        assertEquals(0L, state.protocolLastRestartAtMs)
        // 清零后第一次应立即可拉起（退避从零开始）
        val d = QqProtocolRestartPolicy.decide(
            nowMs = t0 + minute,
            state = QqProtocolRestartPolicy.State(),
            alreadyRunning = false,
            enabled = true
        )
        assertTrue(d is QqProtocolRestartPolicy.Decision.Allow)
    }

    @Test
    fun `总开关关闭时清空告警状态且不落 armed`() = runBlocking {
        val store = FakePreferenceDataStore()
        val repo = QqRuntimeStateRepository(store)
        repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0, enabled = true, loopback = true
        )
        repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0 + 10 * minute, enabled = true, loopback = true
        )
        assertTrue(repo.get().protocolAlert.armed)

        repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE,
            nowMs = t0 + 11 * minute, enabled = false, loopback = true
        )
        val after = repo.get().protocolAlert
        assertFalse("关闭总开关必须清空武装状态", after.armed)
        assertEquals(0L, after.armedAtMs)
        assertEquals(QqProtocolAlertPolicy.State(), after)
    }

    @Test
    fun `重复相同探测不改变持久化状态（写盘短路）`() = runBlocking {
        val store = FakePreferenceDataStore()
        val repo = QqRuntimeStateRepository(store)
        val first = repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0, enabled = true, loopback = true
        )
        // 同一毫秒再来一次：状态应完全相同（含 armed/lastNotify 等全部字段）
        val second = repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0, enabled = true, loopback = true
        )
        assertEquals(first.state, second.state)
        assertEquals(first.action, second.action)
        assertEquals(QqProtocolAlertPolicy.Action.None, second.action)
    }

    @Test
    fun `协议健康记录与告警状态互不干扰`() = runBlocking {
        val store = FakePreferenceDataStore()
        val repo = QqRuntimeStateRepository(store)
        repo.recordProtocolHealth(
            state = QqProtocolAlertPolicy.PROBE_ONLINE,
            detail = "在线",
            atMs = t0,
            offline = false
        )
        repo.advanceProtocolAlert(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = t0, enabled = true, loopback = true
        )
        val s = repo.get()
        assertEquals("在线", s.lastProtocolDetail)
        assertEquals(t0, s.lastProtocolCheckedAtMs)
        // 告警状态独立于协议健康记录
        assertEquals(t0, s.protocolAlert.onlineSinceMs)
    }
}
