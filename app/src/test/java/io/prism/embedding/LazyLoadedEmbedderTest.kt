package io.prism.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LazyLoadedEmbedder] 单测（2026-11-15；ADR-074 / guardrail R-01 / R-03 / R2-02）。
 *
 * 这个包装器是"内存优化不引入 ANR"的关键：`by lazy` 必须零 IO，
 * 真正加载推迟到首次 `embed`；同时要能区分"档位不启用（终态）"与"加载失败（可重试）"。
 */
class LazyLoadedEmbedderTest {

    /** 极简桩：只用于验证加载时机与失败语义（不做真实推理）。 */
    private class StubEmbedder(private val dim: Int = 4) : Embedder {
        var closed = false
        override fun embed(text: String) = FloatArray(dim) { 1f }
        override fun isLoaded() = !closed
        override fun checkAndUnload(maxIdleMs: Long): Boolean {
            if (closed) return false
            closed = true
            return true
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `构造时不做任何加载`() {
        var calls = 0
        val e = LazyLoadedEmbedder(factory = { calls++; StubEmbedder() })
        assertEquals("构造必须零 IO（否则主线程 initializer 会 ANR）", 0, calls)
        assertFalse(e.hasDelegate())
    }

    @Test
    fun `首次 embed 才加载且只加载一次`() {
        var calls = 0
        val e = LazyLoadedEmbedder(factory = { calls++; StubEmbedder() })
        e.embed("a")
        e.embed("b")
        e.embedBatch(listOf("c", "d"))
        assertEquals(1, calls)
        assertTrue(e.hasDelegate())
    }

    @Test
    fun `档位不启用是终态且不重试`() {
        var calls = 0
        val e = LazyLoadedEmbedder(factory = { calls++; null })
        repeat(3) {
            runCatching { e.embed("x") }
        }
        assertEquals("null 表示档位不启用，必须终态不重试", 1, calls)
        assertTrue(e.isDisabled())
        assertFalse(e.hasDelegate())
    }

    @Test
    fun `加载失败按退避重试而不是永久缓存失败`() {
        var calls = 0
        var now = 1_000L
        val e = LazyLoadedEmbedder(
            factory = {
                calls++
                if (calls == 1) throw IllegalStateException("首次失败") else StubEmbedder()
            },
            retryBackoffMs = 60_000L,
            clock = { now }
        )
        // 第一次：失败
        assertNull(runCatching { e.embed("a") }.getOrNull())
        assertEquals(1, e.failureCount)
        // 立即重试：应被退避挡住（不重复 IO）
        assertNull(runCatching { e.embed("a") }.getOrNull())
        assertEquals("退避期内不得重试", 1, calls)
        // 退避到期：重试并成功
        now += 60_001L
        assertNotNull(runCatching { e.embed("a") }.getOrNull())
        assertEquals(2, calls)
        assertTrue(e.hasDelegate())
    }

    @Test
    fun `失败后仍能报告可读原因`() {
        val e = LazyLoadedEmbedder(
            factory = { throw IllegalStateException("boom") },
            retryBackoffMs = 0L
        )
        runCatching { e.embed("a") }
        assertTrue("必须给出可诊断原因", e.lastFailure!!.contains("加载失败"))
    }

    @Test
    fun `checkAndUnload 转发给内层`() {
        val stub = StubEmbedder()
        val e = LazyLoadedEmbedder(factory = { stub })
        e.embed("a")
        assertTrue(e.isLoaded())
        assertTrue("必须真正转发（生产调度器依赖它，guardrail R2-01）", e.checkAndUnload(0L))
        assertFalse(e.isLoaded())
        assertTrue(stub.closed)
    }

    @Test
    fun `未加载时 checkAndUnload 返回 false 而不是崩溃`() {
        val e = LazyLoadedEmbedder(factory = { StubEmbedder() })
        assertFalse(e.checkAndUnload(0L))
    }
}
