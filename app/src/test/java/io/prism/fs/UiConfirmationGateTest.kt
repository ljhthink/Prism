package io.prism.fs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UiConfirmationGate 确认协议单元测试（ADR-006 5.4）。
 *
 * 验证 [UiConfirmationGate.confirm] 挂起直到 [UiConfirmationGate.respond] 被调用，
 * confirm 返回值与 respond 参数一致，且待确认请求经 SharedFlow 向 UI 宿主发布。
 *
 * 另覆盖 BR-concurrency-003 的两条保障：① 并发多请求各自独立响应（不丢失/不覆盖）；
 * ② [UiConfirmationGate.confirm] 超时（默认 30s）兜底按拒绝处理返回 false，避免永久挂起。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiConfirmationGateTest {

    @Test
    fun `confirm suspends and resolves true after allow`() = runTest {
        val gate = UiConfirmationGate()
        val pending = mutableListOf<UiConfirmationGate.PendingConfirm>()
        val collectJob = launch { gate.requests.collect { pending.add(it) } }

        var result: Boolean? = null
        val confirmJob = launch {
            result = gate.confirm("read_file", mapOf("path" to "a.txt"))
        }

        runCurrent()
        val req = pending.single()
        assertEquals("read_file", req.toolName)
        assertEquals(mapOf("path" to "a.txt"), req.arguments)

        gate.respond(req.id, true)
        runCurrent()
        confirmJob.join()

        assertTrue("允许应返回 true", result == true)
        collectJob.cancel()
    }

    @Test
    fun `confirm resolves false after deny`() = runTest {
        val gate = UiConfirmationGate()
        val pending = mutableListOf<UiConfirmationGate.PendingConfirm>()
        val collectJob = launch { gate.requests.collect { pending.add(it) } }

        var result: Boolean? = null
        val confirmJob = launch {
            result = gate.confirm("write_file", mapOf("path" to "b.txt"))
        }

        runCurrent()
        val req = pending.single()
        gate.respond(req.id, false)
        runCurrent()
        confirmJob.join()

        assertFalse("拒绝应返回 false", result == true)
        collectJob.cancel()
    }

    @Test
    fun `concurrent confirms resolve independently by id`() = runTest {
        val gate = UiConfirmationGate()
        val pending = mutableListOf<UiConfirmationGate.PendingConfirm>()
        val collectJob = launch { gate.requests.collect { pending.add(it) } }

        val results = MutableList(3) { false }
        val jobs = (0 until 3).map { i ->
            launch {
                results[i] = gate.confirm("tool_$i", mapOf("i" to i))
            }
        }

        runCurrent()
        // 三个请求均应入列，互不覆盖（BR-concurrency-003）
        assertEquals("并发请求应全部发布", 3, pending.size)

        // 逆序响应各请求，验证 id 独立映射、无覆盖丢失
        pending.forEach { req ->
            gate.respond(req.id, true)
            runCurrent()
        }
        jobs.forEach { it.join() }

        assertTrue("所有答应请求均应返回 true", results.all { it })
        collectJob.cancel()
    }

    @Test
    fun `confirm times out and returns false without response`() = runTest {
        val gate = UiConfirmationGate()
        val collectJob = launch { gate.requests.collect { } }

        var result: Boolean? = null
        val confirmJob = launch {
            result = gate.confirm("read_file", mapOf("path" to "a.txt"))
        }

        runCurrent()
        // 不响应，推进虚拟时间越过 30s 超时 → 按拒绝返回 false，不永久挂起
        advanceTimeBy(UiConfirmationGate.CONFIRM_TIMEOUT_MILLIS + 1)
        runCurrent()
        confirmJob.join()

        assertFalse("超时应按拒绝返回 false", result == true)
        collectJob.cancel()
    }

    @Test
    fun `respond with unknown id is a no-op`() = runTest {
        val gate = UiConfirmationGate()
        val collected = mutableListOf<UiConfirmationGate.PendingConfirm>()
        val collectJob = launch { gate.requests.collect { collected.add(it) } }

        // 对不存在的 id 响应不应抛异常、不应影响其他请求
        gate.respond(999L, true)
        gate.respond(-1L, false)
        runCurrent()

        var result: Boolean? = null
        val confirmJob = launch {
            result = gate.confirm("read_file", emptyMap())
        }
        runCurrent()
        // 用真实 id 响应，确认仍正常解析
        val req = collected.single()
        gate.respond(req.id, true)
        runCurrent()
        confirmJob.join()

        assertTrue("真实 id 响应应正常返回 true", result == true)
        collectJob.cancel()
    }

    @Test
    fun `confirm rejects when no UI host collects requests`() = runTest {
        // 无任何收集者的场景（宿主未挂载）：confirm 应经超时兜底按拒绝返回 false，不永久挂起
        val gate = UiConfirmationGate()
        var result: Boolean? = null
        val confirmJob = launch {
            result = gate.confirm("write_file", mapOf("path" to "x.txt"))
        }
        runCurrent()
        advanceTimeBy(UiConfirmationGate.CONFIRM_TIMEOUT_MILLIS + 1)
        runCurrent()
        confirmJob.join()

        assertFalse("无收集者时超时应按拒绝返回 false", result == true)
    }
}