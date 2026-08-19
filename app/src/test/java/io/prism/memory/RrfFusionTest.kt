package io.prism.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 US-102：RRF 融合单元测试（参照 TencentDB-Agent-Memory RRF_K=60）。
 */
class RrfFusionTest {

    @Test
    fun `rrfMerge empty lists returns empty`() {
        assertTrue(RrfFusion.rrfMerge(emptyList()).isEmpty())
        assertTrue(RrfFusion.rrfMerge(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `rrfMerge single list preserves order`() {
        val merged = RrfFusion.rrfMerge(listOf(listOf(3L, 1L, 2L)))
        assertEquals(listOf(3L, 1L, 2L), merged)
    }

    @Test
    fun `rrfMerge boosts docs present in both lists`() {
        // 文档 1 在两路都排名靠前 → 融合后应排第一
        // 文档 2 仅第一路第 2 → 分数 1/62
        // 文档 3 仅第二路第 2 → 分数 1/62
        val listA = listOf(1L, 2L)
        val listB = listOf(1L, 3L)
        val merged = RrfFusion.rrfMerge(listOf(listA, listB))
        assertEquals("两路都命中的文档应排第一", 1L, merged[0])
        assertEquals(3, merged.size)
    }

    @Test
    fun `rrfMergeTop caps at topK`() {
        val merged = RrfFusion.rrfMergeTop(
            lists = listOf(listOf(1L, 2L, 3L), listOf(4L, 5L)),
            topK = 2
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `rrfMerge with custom k produces same relative order`() {
        val listA = listOf(1L, 2L)
        val listB = listOf(1L, 3L)
        val merged = RrfFusion.rrfMerge(listOf(listA, listB), k = 10)
        assertEquals(1L, merged[0])
    }
}
