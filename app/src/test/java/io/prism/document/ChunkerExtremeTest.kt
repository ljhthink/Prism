package io.prism.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunker 极端/边界补充测试（ac-verifier 补充，US-013 验收标准 3）。
 *
 * 覆盖：chunkSize=1、overlap=chunkSize-1、单句超长、全空白输入、
 * 重复标点、emoji、混合中英文、纯无边界长串、无 overlap 拼接还原。
 */
class ChunkerExtremeTest {

    @Test
    fun chunk_size_one_no_infinite_loop() {
        // chunkSize=1 是最小合法值，必须不崩溃不死循环
        val chunker = Chunker(chunkSize = 1, overlap = 0)
        val chunks = chunker.chunk("abc")
        assertEquals(listOf("a", "b", "c"), chunks)
    }

    @Test
    fun chunk_size_one_paragraph_with_boundaries() {
        val chunker = Chunker(chunkSize = 1, overlap = 0)
        val chunks = chunker.chunk("a。b。")
        // chunkSize=1 时句号边界优先，每句成一块（非逐字符硬切）
        assertEquals(listOf("a。", "b。"), chunks)
    }

    @Test
    fun chunk_overlap_max_chunk_size_minus_one() {
        // overlap = chunkSize - 1 是合法上界
        val chunker = Chunker(chunkSize = 5, overlap = 4)
        val chunks = chunker.chunk("abcdefghij")
        assertEquals(2, chunks.size)
        assertEquals("abcde", chunks[0])
        // 第二块 = prev 末尾4字符 + 本块 = "bcde" + "fghij"
        assertEquals("bcdefghij".length, chunks[1].length)
        assertTrue(chunks[1].endsWith("fghij"))
    }

    @Test
    fun chunk_very_long_single_sentence_reassembles() {
        // 单句超长：无标点无空白，硬切但需无字符丢失
        val chunker = Chunker(chunkSize = 10, overlap = 0)
        val text = "a".repeat(1000)
        val chunks = chunker.chunk(text)
        assertTrue(chunks.size > 1)
        val reassembled = chunks.joinToString("")
        assertEquals("硬切后拼接应还原原文", text, reassembled)
        chunks.forEach { assertTrue(it.length <= 10) }
    }

    @Test
    fun chunk_all_whitespace_returns_empty() {
        val chunker = Chunker(chunkSize = 10, overlap = 2)
        assertEquals(emptyList<String>(), chunker.chunk("   \n\n  \t \n"))
        assertEquals(emptyList<String>(), chunker.chunk("\n\n\n"))
    }

    @Test
    fun chunk_repeated_punctuation_no_crash() {
        val chunker = Chunker(chunkSize = 5, overlap = 0)
        // 连续大量句号，验证句子边界搜索无异常且每块非空白
        val chunks = chunker.chunk("。".repeat(20))
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun chunk_emoji_no_crash() {
        val chunker = Chunker(chunkSize = 3, overlap = 0)
        // emoji 为 UTF-16 代理对，按 char 切分可能落在代理对中间，但必须不崩溃
        val chunks = chunker.chunk("🎉🎉🎉🎉🎉🎉")
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun chunk_mixed_chinese_english_at_boundaries() {
        val chunker = Chunker(chunkSize = 8, overlap = 0)
        val text = "Hello World 测试中文。more text 继续。"
        val chunks = chunker.chunk(text)
        assertTrue(chunks.isNotEmpty())
        // 正常情况下应按句子/词边界切，不硬切
        chunks.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun chunk_no_boundary_long_string_hard_split() {
        // 纯无边界长串（无标点无空白），应硬切且拼接还原
        val chunker = Chunker(chunkSize = 4, overlap = 0)
        val text = "abcdabcdabcd"
        val chunks = chunker.chunk(text)
        assertTrue(chunks.size >= 3)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun chunk_single_paragraph_overlap_zero_reassembles() {
        // 单段落 overlap=0 时，切片拼接应还原 trim 后原文
        val chunker = Chunker(chunkSize = 7, overlap = 0)
        val text = "这是一个测试句子用来验证切片还原。第二句内容继续延伸。"
        val chunks = chunker.chunk(text)
        assertEquals(chunks.joinToString(""), text.trim())
    }
}