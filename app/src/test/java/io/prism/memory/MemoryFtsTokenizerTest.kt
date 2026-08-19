package io.prism.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 US-102：记忆 FTS 分词器单元测试（中文二元组 + 整段 + 字母数字）。
 */
class MemoryFtsTokenizerTest {

    @Test
    fun `tokenize blank input returns empty`() {
        assertTrue(MemoryFtsTokenizer.tokenize("").isEmpty())
        assertTrue(MemoryFtsTokenizer.tokenize("   ").isEmpty())
    }

    @Test
    fun `tokenize chinese run produces bigrams and whole run`() {
        val tokens = MemoryFtsTokenizer.tokenize("Kotlin 协程")
        // CJK "协程" → 整段 + 二元组（协程）
        assertTrue("应含整段'协程'", tokens.contains("协程"))
        assertTrue("应含二元组'协程'", tokens.contains("协程"))
        assertTrue("应含字母 token 'kotlin'", tokens.contains("kotlin"))
    }

    @Test
    fun `tokenize longer chinese run produces overlapping bigrams`() {
        val tokens = MemoryFtsTokenizer.tokenize("机器学习")
        // 整段（4 字 ≤6）保留
        assertTrue("应含整段'机器学习'", tokens.contains("机器学习"))
        // 重叠二元组：机器、器学、学习
        assertTrue("应含二元组'机器'", tokens.contains("机器"))
        assertTrue("应含二元组'器学'", tokens.contains("器学"))
        assertTrue("应含二元组'学习'", tokens.contains("学习"))
    }

    @Test
    fun `tokenize very long chinese run keeps bigrams but no giant whole token`() {
        val long = "这是一个非常长的中文句子没有空格需要切分测试"
        val tokens = MemoryFtsTokenizer.tokenize(long)
        // 整段超 6 字不保留为单一 token
        assertFalse("超长整段不应作为单一 token", tokens.contains(long))
        // 二元组存在
        assertTrue("应含二元组'这是'", tokens.contains("这是"))
    }

    @Test
    fun `tokenize latin digits lowercased`() {
        val tokens = MemoryFtsTokenizer.tokenize("Kotlin Coroutines 384")
        assertTrue(tokens.contains("kotlin"))
        assertTrue(tokens.contains("coroutines"))
        assertTrue(tokens.contains("384"))
    }

    @Test
    fun `tokenize deduplicates tokens`() {
        val tokens = MemoryFtsTokenizer.tokenize("测试 测试 测试")
        assertEquals("重复 token 应去重", tokens.count { it == "测试" }, 1)
    }

    @Test
    fun `tokenizeForFts joins with space`() {
        val fts = MemoryFtsTokenizer.tokenizeForFts("喜欢 Kotlin")
        assertEquals("测试", "喜欢 kotlin", fts)
        assertTrue(MemoryFtsTokenizer.isIndexable(fts))
    }

    @Test
    fun `tokenizeForFts blank returns empty and not indexable`() {
        val fts = MemoryFtsTokenizer.tokenizeForFts("。。。")
        assertFalse("纯标点不可索引", MemoryFtsTokenizer.isIndexable(fts))
    }
}
