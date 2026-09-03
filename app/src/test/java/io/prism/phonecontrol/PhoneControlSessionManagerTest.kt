package io.prism.phonecontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [PhoneControlSessionManager] 任务期动态保活状态机单元测试
 * （v1 批次14 TKN-V1B14-KEEPALIVE-BUG-001，ADR-041）。
 *
 * **application 指定**：`application = android.app.Application::class` 避免 Robolectric 按
 * AndroidManifest 加载 [io.prism.PrismApplication]（ObjectBox native 在 Windows JVM 不可用）。
 *
 * 覆盖：
 * - 首个工具调用启动保活一次，后续调用幂等不重复启动
 * - 空闲满 IDLE_TIMEOUT_MS 自动停止保活
 * - 超时前的新调用刷新时间戳并重排检查（任务不被误停）
 * - 无障碍未连接时静默跳过
 * - 会话停止后再次调用重新启动（跨会话）
 * - 并发调用只产生一次启动
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneControlSessionManagerTest {

    private class FakeScheduler {
        data class Pending(val delayMs: Long, val action: Runnable)

        val pending = mutableListOf<Pending>()

        fun fireAll() {
            val snapshot = pending.toList()
            pending.clear()
            snapshot.forEach { it.action.run() }
        }

        fun size(): Int = pending.size
    }

    private lateinit var scheduler: FakeScheduler
    private var startedCount = 0
    private var stoppedCount = 0
    private var virtualNow = 1_000_000L

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        startedCount = 0
        stoppedCount = 0
        virtualNow = 1_000_000L
        val ctx = RuntimeEnvironment.getApplication()
        PhoneControlSessionManager.reset()
        PhoneControlSessionManager.nowMillis = { virtualNow }
        PhoneControlSessionManager.appContextProvider = { ctx }
        PhoneControlSessionManager.keepAliveStarter = { startedCount++ }
        PhoneControlSessionManager.keepAliveStopper = { stoppedCount++ }
        PhoneControlSessionManager.scheduler = { delayMs, action ->
            scheduler.pending.add(FakeScheduler.Pending(delayMs, action))
        }
    }

    @After
    fun tearDown() {
        PhoneControlSessionManager.reset()
    }

    @Test
    fun `first tool call starts keepalive once and subsequent calls are idempotent`() {
        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("首个调用应恰好启动一次", 1, startedCount)
        assertEquals(0, stoppedCount)
        assertEquals("应排定一个空闲检查", 1, scheduler.size())

        PhoneControlSessionManager.onPhoneToolInvoked()
        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("后续调用不应重复启动服务", 1, startedCount)
    }

    @Test
    fun `idle timeout stops keepalive`() {
        PhoneControlSessionManager.onPhoneToolInvoked()
        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS
        scheduler.fireAll()
        assertEquals("空闲超时应停止保活", 1, stoppedCount)
    }

    @Test
    fun `refresh before timeout reschedules instead of stopping`() {
        PhoneControlSessionManager.onPhoneToolInvoked()
        // 第一轮检查到期前有新工具调用刷新了时间戳
        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS - 10_000L
        PhoneControlSessionManager.onPhoneToolInvoked()
        scheduler.fireAll()
        assertEquals("未真正超时不应停止", 0, stoppedCount)
        assertEquals("应重排下一轮检查", 1, scheduler.size())

        // 新一轮检查到期时已超时
        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS
        scheduler.fireAll()
        assertEquals(1, stoppedCount)
    }

    @Test
    fun `skips when accessibility not connected`() {
        PhoneControlSessionManager.appContextProvider = { null }
        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("无障碍未连接不应启动保活", 0, startedCount)
        assertEquals(0, scheduler.size())
    }

    @Test
    fun `guardrail M-1 - null provider leaves no stale session latch`() {
        // guardrail TKN-V1B14-GUARDRAIL-001 M-1 回归防护：null 路径必须整体跳过且不留
        // sessionActive 残留闩锁（旧实现锁外判空 + 锁内重复取值 !! 的 TOCTOU 会置位闩锁
        // 而未排定 idle 检查，导致本次连接内保活静默失效）。
        val ctx = RuntimeEnvironment.getApplication()
        PhoneControlSessionManager.appContextProvider = { null }
        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("无障碍未连接应整体跳过", 0, startedCount)

        // 竞态恢复后：下一次调用必须能正常开启新会话
        PhoneControlSessionManager.appContextProvider = { ctx }
        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("不应有残留闩锁阻止新会话", 1, startedCount)
        assertEquals(1, scheduler.size())
    }

    @Test
    fun `checkIdle without active session is a no-op and disabled scheduler never starts chain`() {
        PhoneControlSessionManager.checkIdle()
        assertEquals("无活跃会话时 checkIdle 不应停止任何服务", 0, stoppedCount)

        PhoneControlSessionManager.scheduler = null
        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("禁用调度时仍应启动服务", 1, startedCount)
        PhoneControlSessionManager.reset()
        PhoneControlSessionManager.checkIdle()
        assertEquals("reset 后 checkIdle 不应误停", 0, stoppedCount)
    }

    @Test
    fun `new call after session stop starts a fresh session`() {
        PhoneControlSessionManager.onPhoneToolInvoked()
        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS
        scheduler.fireAll()
        assertEquals(1, stoppedCount)

        PhoneControlSessionManager.onPhoneToolInvoked()
        assertEquals("会话结束后新调用应重新启动", 2, startedCount)

        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS
        scheduler.fireAll()
        assertEquals(2, stoppedCount)
    }

    @Test
    fun `concurrent tool calls produce single start`() = runBlocking {
        val jobs = (1..20).map {
            async(Dispatchers.Default) { PhoneControlSessionManager.onPhoneToolInvoked() }
        }
        jobs.awaitAll()
        assertEquals("并发调用应仅启动一次", 1, startedCount)
        assertFalse(stoppedCount > 0)
    }

    @Test
    fun `ac supplement - tool call at exact idle boundary keeps single lifecycle`() {
        // ac-verifier TKN-V1B14-ACCEPTANCE-001 §四.3：恰好 120s 边界，checkIdle 与新调用同刻竞争。
        // 锁序化裁决下两种先后都合法，不变量：无双停、无双启。本序：idle 检查先执行 → 停止，
        // 同刻新调用视为新会话重新启动（恰等边界按 >= 判定停止，与 `idle timeout stops keepalive` 一致）。
        PhoneControlSessionManager.onPhoneToolInvoked()
        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS
        scheduler.fireAll()
        PhoneControlSessionManager.onPhoneToolInvoked() // 与 checkIdle 同刻到达
        assertEquals(1, stoppedCount)
        assertEquals("同刻新调用应为新会话启动", 2, startedCount)
    }

    @Test
    fun `ac supplement - stale chain after rapid disable-reenable is self-terminating and stops exactly once`() {
        // ac-verifier §四.4 / guardrail L-1：快速关→开（reset 不清 pending）后陈旧检查链与新链共存。
        // 不变量：未超时不得停止；多条链最终收敛为恰好一次停止（第二条链见 sessionActive=false 早退）。
        PhoneControlSessionManager.onPhoneToolInvoked()   // 会话1：排链 A
        PhoneControlSessionManager.reset()                // 无障碍快速断开
        PhoneControlSessionManager.onPhoneToolInvoked()   // 重连后会话2：start + 排链 B
        assertEquals(2, startedCount)
        assertEquals(2, scheduler.size())                 // A、B 共存

        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS - 1_000
        scheduler.fireAll()
        assertEquals("未到真实空闲点不得停止", 0, stoppedCount)

        virtualNow += PhoneControlSessionManager.IDLE_TIMEOUT_MS + 2_000
        while (scheduler.size() > 0) scheduler.fireAll()  // 驱动全部续排链至收敛
        assertEquals("多条链最终恰好停止一次", 1, stoppedCount)
    }
}
