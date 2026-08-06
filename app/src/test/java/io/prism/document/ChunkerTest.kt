package io.prism.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunker 文本切片器单元测试（US-013 验收标准 3）。
 */
class ChunkerTest {

    @Test
    fun chunk_empty_input_returns_empty() {
        val chunker = Chunker(chunkSize = 10, overlap = 2)
        assertEquals(emptyList<String>(), chunker.chunk(""))
        assertEquals(emptyList<String>(), chunker.chunk("   \n  "))
    }

    @Test
    fun chunk_short_text_returns_single_chunk() {
        val chunker = Chunker(chunkSize = 100, overlap = 0)
        val chunks = chunker.chunk("这是一段较短的文本")
        assertEquals(1, chunks.size)
        assertEquals("这是一段较短的文本", chunks[0])
    }

    @Test
    fun chunk_long_sentence_without_boundary_splits_at_chunkSize() {
        // 无句子边界时按 chunkSize 硬切
        val chunker = Chunker(chunkSize = 5, overlap = 0)
        val chunks = chunker.chunk("abcdefghij")
        assertEquals(2, chunks.size)
        assertEquals("abcde", chunks[0])
        assertEquals("fghij", chunks[1])
    }

    @Test
    fun chunk_prefers_paragraph_boundary() {
        val chunker = Chunker(chunkSize = 100, overlap = 0)
        val text = "第一段内容。\n\n第二段内容。"
        val chunks = chunker.chunk(text)
        assertEquals("段落边界应优先切分", 2, chunks.size)
        assertTrue(chunks[0].contains("第一段"))
        assertTrue(chunks[1].contains("第二段"))
    }

    @Test
    fun chunk_avoids_splitting_sentence_at_boundary() {
        // chunkSize=10 会把第一个句号后切断，应在句号边界回退
        val chunker = Chunker(chunkSize = 10, overlap = 0)
        val text = "第一句话。第二句话很长需要切分。第三句话。"
        val chunks = chunker.chunk(text)
        // 第一块应止于句号，而非在句中硬切
        assertTrue("第一块应在句号边界，实际: [${chunks[0]}]", chunks[0].endsWith("。"))
    }

    @Test
    fun chunk_applies_overlap_between_chunks() {
        val chunker = Chunker(chunkSize = 6, overlap = 2)
        val text = "一二三四五六七八九十"
        val chunks = chunker.chunk(text)
        assertTrue(chunks.size >= 2)
        if (chunks.size >= 2) {
            // 相邻块存在 overlap 重叠
            assertTrue("第二块应包含第一块末尾 overlap 字符", chunks[1].startsWith(chunks[0].takeLast(2)))
        }
    }

    @Test
    fun chunk_overlap_zero_no_repeat() {
        val chunker = Chunker(chunkSize = 5, overlap = 0)
        val chunks = chunker.chunk("一二三四五六七八九十")
        assertEquals(2, chunks.size)
        assertEquals("一二三四五", chunks[0])
        assertEquals("六七八九十", chunks[1])
    }

    @Test
    fun chunk_very_long_input_no_crash() {
        val chunker = Chunker(chunkSize = 50, overlap = 10)
        val longText = "这是一个测试句子。".repeat(1000) // 8000 字符
        val chunks = chunker.chunk(longText)
        assertTrue("超长输入应产出多块", chunks.size > 1)
        chunks.forEach { assertTrue("每块长度 ≤ chunkSize+overlap", it.length <= 50 + 10) }
    }

    @Test
    fun chunk_all_chunks_non_blank() {
        val chunker = Chunker(chunkSize = 20, overlap = 5)
        val text = "第一段。\n\n第二段很长，包含很多句子。\n\n第三段。"
        chunker.chunk(text).forEach { assertTrue("切片不应为空白", it.isNotBlank()) }
    }

    @Test
    fun constructor_rejects_chunk_size_zero_or_negative() {
        assertThrows(IllegalArgumentException::class.java) { Chunker(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Chunker(-5, 0) }
    }

    @Test
    fun constructor_rejects_overlap_not_less_than_chunk_size() {
        assertThrows(IllegalArgumentException::class.java) { Chunker(10, 10) }
        assertThrows(IllegalArgumentException::class.java) { Chunker(10, 15) }
        assertThrows(IllegalArgumentException::class.java) { Chunker(10, -1) }
    }

    @Test
    fun chunk_paragraphs_within_chunk_have_overlap_continuity() {
        // 多段落，overlap 应作用于相邻产出块
        val chunker = Chunker(chunkSize = 30, overlap = 4)
        val text = "段落A。\n\n段落B。"
        val chunks = chunker.chunk(text)
        if (chunks.size >= 2) {
            assertTrue("相邻块应带 overlap 衔接", chunks[1].startsWith(chunks[0].takeLast(4)))
        }
    }

    @Test
    fun chunk_sentence_boundary_at_exact_split_position_not_orphaned() {
        // G-2：句号恰好落在截断位时不应成为"句号孤儿"遗留到下一块
        val chunker = Chunker(chunkSize = 5, overlap = 0)
        val text = "aaa。bbb。"
        // chunkSize=5: 第一窗口 [0,5)="aaa。"，句号在 index 3，闭区间 [0,5] 能找到
        val chunks = chunker.chunk(text)
        assertTrue("第一块应含句号", chunks[0].contains("。"))
    }

    @Test
    fun chunk_english_word_not_split_at_boundary() {
        // G-3：英文单词不应在空格边界被硬切
        val chunker = Chunker(chunkSize = 8, overlap = 0)
        val text = "hello world this is"
        val chunks = chunker.chunk(text)
        // 第一块应在空格边界回退，不含被截断的单词
        assertTrue("第一块应止于词边界，实际: [${chunks[0]}]", chunks[0] == "hello" || chunks[0].endsWith(" "))
    }

    @Test
    fun chunk_no_boundary_splits_hard() {
        // 整窗口无任何边界时兜底硬切
        val chunker = Chunker(chunkSize = 4, overlap = 0)
        val text = "abcd"
        val chunks = chunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals("abcd", chunks[0])
    }
}