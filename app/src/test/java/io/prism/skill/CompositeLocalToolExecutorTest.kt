package io.prism.skill

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CompositeLocalToolExecutor 单元测试（问题 8b，ADR-020）。
 *
 * 验证多个 [LocalToolExecutor] 委托组合：
 * - [handles]：任一 delegate 命中即返回 true
 * - [execute]：委托给第一个命中的 delegate
 * - 空 delegates / 无命中：安全降级
 */
class CompositeLocalToolExecutorTest {

    /** 测试用本地工具执行器（按前缀匹配，返回固定结果）。 */
    private class FakeLocalToolExecutor(
        private val prefix: String,
        private val result: String = "executed:$prefix"
    ) : LocalToolExecutor {
        override fun handles(toolName: String): Boolean = toolName.startsWith(prefix)
        override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String = result
    }

    @Test
    fun handles_returns_true_when_any_delegate_matches() {
        val composite = CompositeLocalToolExecutor(
            listOf(FakeLocalToolExecutor("cross_app__"), FakeLocalToolExecutor("web_search__"))
        )
        assertTrue(composite.handles("cross_app__open_app"))
        assertTrue(composite.handles("web_search__search"))
        assertFalse(composite.handles("skill__tool"))
        assertFalse(composite.handles(""))
    }

    @Test
    fun execute_delegates_to_first_matching_delegate() = runBlocking {
        val crossApp = FakeLocalToolExecutor("cross_app__", "cross-app-result")
        val webSearch = FakeLocalToolExecutor("web_search__", "web-search-result")
        val composite = CompositeLocalToolExecutor(listOf(crossApp, webSearch))

        assertEquals("cross-app-result", composite.execute("cross_app__open_app", emptyMap()))
        assertEquals("web-search-result", composite.execute("web_search__search", mapOf("query" to "x")))
    }

    @Test
    fun execute_returns_unknown_when_no_delegate_matches() = runBlocking {
        val composite = CompositeLocalToolExecutor(listOf(FakeLocalToolExecutor("cross_app__")))
        val result = composite.execute("unknown__tool", emptyMap())
        assertEquals("未知本地工具: unknown__tool", result)
    }

    @Test
    fun handles_returns_false_for_empty_delegates() {
        val composite = CompositeLocalToolExecutor(emptyList())
        assertFalse(composite.handles("anything"))
    }

    @Test
    fun execute_returns_unknown_for_empty_delegates() = runBlocking {
        val composite = CompositeLocalToolExecutor(emptyList())
        assertEquals("未知本地工具: x", composite.execute("x", emptyMap()))
    }

    @Test
    fun execute_prefers_earlier_delegate_on_overlap() = runBlocking {
        // 两个 delegate 都命中时，应委托给列表顺序靠前者
        val first = FakeLocalToolExecutor("web_", "first")
        val second = FakeLocalToolExecutor("web_search__", "second")
        val composite = CompositeLocalToolExecutor(listOf(first, second))
        assertEquals("first", composite.execute("web_search__search", emptyMap()))
    }
}
