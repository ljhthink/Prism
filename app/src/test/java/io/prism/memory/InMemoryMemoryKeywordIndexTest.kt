package io.prism.memory

import io.prism.data.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 US-102：内存 BM25 倒排索引单元测试（JVM 主用 + 生产降级路径）。
 */
class InMemoryMemoryKeywordIndexTest {

    private fun record(id: Long, content: String) = MemoryRecord(
        id = id,
        sessionId = "s1",
        content = content,
        timestamp = id * 1000L
    )

    @Test
    fun `reconcile skips rebuild when version unchanged`() {
        val index = InMemoryMemoryKeywordIndex()
        val records = listOf(record(1, "用户喜欢 Kotlin 协程"))
        index.reconcile(records, version = 1L)
        val hits = index.search("Kotlin", topK = 5)
        assertEquals(1, hits.size)

        // 版本不变：数据变化不反映（按设计跳过重建）
        index.reconcile(emptyList(), version = 1L)
        assertEquals("版本未变应跳过重建", 1, index.search("Kotlin", topK = 5).size)
    }

    @Test
    fun `reconcile rebuilds when version changes`() {
        val index = InMemoryMemoryKeywordIndex()
        index.reconcile(listOf(record(1, "用户喜欢 Kotlin")), version = 1L)
        index.reconcile(emptyList(), version = 2L)
        assertTrue("版本变化应重建", index.search("Kotlin", topK = 5).isEmpty())
    }

    @Test
    fun `search finds exact chinese keyword`() {
        val index = InMemoryMemoryKeywordIndex()
        index.reconcile(
            listOf(
                record(1, "用户偏好使用简体中文交流"),
                record(2, "用户是 Android 开发者")
            ),
            version = 1L
        )
        val hits = index.search("简体中文", topK = 5)
        assertEquals("应命中含简体中文的记忆", 1, hits.size)
        assertEquals(1L, hits[0].recordId)
    }

    @Test
    fun `search returns empty for no matching token`() {
        val index = InMemoryMemoryKeywordIndex()
        index.reconcile(listOf(record(1, "用户喜欢钓鱼")), version = 1L)
        assertTrue(index.search("不存在的词xyz", topK = 5).isEmpty())
    }

    @Test
    fun `search ranks more relevant doc higher`() {
        val index = InMemoryMemoryKeywordIndex()
        index.reconcile(
            listOf(
                // 包含多个查询 token（kotlin 与 协程）
                record(1, "用户学习 Kotlin 协程和 Flow"),
                // 仅包含一个查询 token
                record(2, "用户最近学习了 Kotlin"),
                // 不相关
                record(3, "用户喜欢跑步")
            ),
            version = 1L
        )
        val hits = index.search("Kotlin 协程", topK = 5)
        assertEquals(2, hits.size)
        assertEquals("多 token 命中的文档应排前", 1L, hits[0].recordId)
    }

    @Test
    fun `search respects topK limit`() {
        val index = InMemoryMemoryKeywordIndex()
        index.reconcile(
            (1..10).map { record(it.toLong(), "记忆 $it Kotlin") },
            version = 1L
        )
        val hits = index.search("Kotlin", topK = 3)
        assertEquals(3, hits.size)
    }

    @Test
    fun `search blank query returns empty`() {
        val index = InMemoryMemoryKeywordIndex()
        index.reconcile(listOf(record(1, "Kotlin")), version = 1L)
        assertTrue(index.search("   ", topK = 5).isEmpty())
    }
}
