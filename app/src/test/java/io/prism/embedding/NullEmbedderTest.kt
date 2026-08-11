package io.prism.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * NullEmbedder 单元测试（ADR-017 4.5）。
 *
 * 测试覆盖：
 * - embed 返回空向量（长度 0）
 * - embedBatch 返回等长空向量列表
 * - isLoaded 永远返回 false
 * - checkAndUnload 永远返回 false
 * - close 无操作（无异常）
 * - 多次调用幂等
 */
class NullEmbedderTest {

    private val embedder = NullEmbedder()

    // ============ embed ============

    @Test
    fun embed_returns_empty_array() {
        val result = embedder.embed("test text")
        assertEquals("embed 应返回空向量", 0, result.size)
    }

    @Test
    fun embed_returns_empty_array_for_empty_input() {
        val result = embedder.embed("")
        assertEquals("空输入也应返回空向量", 0, result.size)
    }

    @Test
    fun embed_returns_empty_array_for_long_input() {
        val longText = "a".repeat(10000)
        val result = embedder.embed(longText)
        assertEquals("长输入也应返回空向量", 0, result.size)
    }

    @Test
    fun embed_returns_empty_array_for_unicode_input() {
        val result = embedder.embed("你好世界 🌍 Привет")
        assertEquals("Unicode 输入也应返回空向量", 0, result.size)
    }

    // ============ embedBatch ============

    @Test
    fun embedBatch_returns_empty_list_for_empty_input() {
        val result = embedder.embedBatch(emptyList())
        assertEquals("空列表输入应返回空列表", 0, result.size)
    }

    @Test
    fun embedBatch_returns_equal_length_list() {
        val texts = listOf("text1", "text2", "text3")
        val result = embedder.embedBatch(texts)
        assertEquals("应返回与输入等长的列表", 3, result.size)
    }

    @Test
    fun embedBatch_each_element_is_empty_array() {
        val texts = listOf("text1", "text2", "text3")
        val result = embedder.embedBatch(texts)
        result.forEach { vector ->
            assertEquals("每个元素应为空向量", 0, vector.size)
        }
    }

    // ============ isLoaded ============

    @Test
    fun isLoaded_returns_false() {
        assertFalse("isLoaded 应返回 false", embedder.isLoaded())
    }

    @Test
    fun isLoaded_returns_false_after_embed_call() {
        embedder.embed("test")
        assertFalse("embed 调用后 isLoaded 仍应返回 false", embedder.isLoaded())
    }

    // ============ checkAndUnload ============

    @Test
    fun checkAndUnload_returns_false() {
        val result = embedder.checkAndUnload(1000L)
        assertFalse("checkAndUnload 应返回 false", result)
    }

    @Test
    fun checkAndUnload_returns_false_for_zero_threshold() {
        val result = embedder.checkAndUnload(0L)
        assertFalse("阈值为 0 时 checkAndUnload 也应返回 false", result)
    }

    @Test
    fun checkAndUnload_returns_false_for_negative_threshold() {
        val result = embedder.checkAndUnload(-1L)
        assertFalse("负阈值时 checkAndUnload 也应返回 false", result)
    }

    // ============ close ============

    @Test
    fun close_does_not_throw() {
        // close 应无操作，不抛异常
        embedder.close()
    }

    @Test
    fun close_multiple_calls_do_not_throw() {
        embedder.close()
        embedder.close()
        embedder.close()
    }

    @Test
    fun close_then_embed_still_returns_empty() {
        embedder.close()
        val result = embedder.embed("test")
        assertEquals("close 后 embed 仍应返回空向量（NullEmbedder 无状态）", 0, result.size)
    }

    // ============ 幂等性 ============

    @Test
    fun embed_multiple_calls_return_empty_arrays() {
        repeat(10) {
            val result = embedder.embed("call $it")
            assertEquals("多次调用 embed 应始终返回空向量", 0, result.size)
        }
    }

    @Test
    fun checkAndUnload_multiple_calls_return_false() {
        repeat(10) {
            val result = embedder.checkAndUnload(1000L)
            assertFalse("多次调用 checkAndUnload 应始终返回 false", result)
        }
    }
}
