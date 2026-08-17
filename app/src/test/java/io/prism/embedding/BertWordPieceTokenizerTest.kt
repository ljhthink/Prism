package io.prism.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * BERT WordPiece 分词器单元测试。
 *
 * **验证基准**：与 HuggingFace `BertTokenizer`（do_lower_case=true）对齐，
 * golden 值由 Python `transformers.BertTokenizer` 生成（见 assets_models 调研记录）：
 * - 'hello world' → [CLS] hello world [SEP] → [101, 7592, 2088, 102]
 *
 * US-014 AC-4：嵌入单元测试通过（维度正确、向量一致）—— tokenizer 是嵌入的前置依赖。
 */
class BertWordPieceTokenizerTest {

    @Test
    fun empty_text_produces_cls_sep_only() {
        val result = tokenizer.encode("")
        assertEquals(2, result.length)
        assertEquals(CLS_ID, result.inputIds[0])
        assertEquals(SEP_ID, result.inputIds[1])
        assertArrayEquals(longArrayOf(1L, 1L), result.attentionMask)
        assertArrayEquals(longArrayOf(0L, 0L), result.tokenTypeIds)
    }

    @Test
    fun hello_world_matches_python_bert_tokenizer() {
        // Python: BertTokenizer('hello world') → [101, 7592, 2088, 102]
        val result = tokenizer.encode("hello world")
        assertArrayEquals(longArrayOf(101L, 7592L, 2088L, 102L), result.inputIds)
        assertEquals(4, result.length)
        assertArrayEquals(longArrayOf(1L, 1L, 1L, 1L), result.attentionMask)
        assertArrayEquals(longArrayOf(0L, 0L, 0L, 0L), result.tokenTypeIds)
    }

    @Test
    fun uppercase_lowercased_to_match_uncased_vocab() {
        // do_lower_case=true：Hello World → hello world → 同小写结果
        val lower = tokenizer.encode("hello world")
        val upper = tokenizer.encode("Hello World")
        assertArrayEquals(lower.inputIds, upper.inputIds)
    }

    @Test
    fun chinese_chars_split_per_character() {
        // 中文分字：每个中文字符单独成 token（经 ## 或直接 vocab 命中）
        // '知' '识' '库' 在 BERT uncased vocab 中可能命中或为 [UNK]
        val result = tokenizer.encode("知识库")
        // 至少 [CLS] + 3 个中文字符 + [SEP] = 5
        assertTrue("中文应按字切分，实际长度: ${result.length}", result.length >= 5)
        assertEquals(CLS_ID, result.inputIds[0])
        assertEquals(SEP_ID, result.inputIds[result.length - 1])
    }

    @Test
    fun punctuation_split_as_individual_token() {
        // 'hello, world' → hello / , / world
        val tokens = tokenizer.tokenize("hello, world")
        assertEquals(listOf("hello", ",", "world"), tokens)
    }

    @Test
    fun wordpiece_subword_split() {
        // 'tokenization' 在 BERT vocab 中拆分为 token + ##ization
        val tokens = tokenizer.tokenize("tokenization")
        assertEquals(listOf("token", "##ization"), tokens)
    }

    @Test
    fun unknown_word_returns_unk() {
        // G-10（guardrail）：构造确定不在 vocab 且无子词命中的输入。
        // 用 BERT uncased English vocab 不收录的 Runic 字母（U+16A0~，OTHER_LETTER 类别），
        // 不会被 cleanText 当控制字符过滤（私用区字符会被过滤，故不用），
        // 也不属于 isChineseChar 范围（不会被中文分字拆开），整体与子词均无 vocab 命中 → [UNK]。
        val fakeWord = "\u16A0\u16A1\u16A2" // ᚠ ᚡ ᚢ
        val tokens = tokenizer.tokenize(fakeWord)
        assertEquals(listOf("[UNK]"), tokens)
    }

    @Test
    fun truncation_respects_max_length() {
        val longText = "hello ".repeat(1000)
        val result = tokenizer.encode(longText, maxLength = 10)
        // maxLength=10 → [CLS] + 8 body + [SEP] = 10
        assertEquals(10, result.length)
        assertEquals(CLS_ID, result.inputIds[0])
        assertEquals(SEP_ID, result.inputIds[9])
    }

    @Test
    fun max_length_minimum_2_rejected() {
        try {
            tokenizer.encode("hello", maxLength = 1)
            org.junit.Assert.fail("maxLength < 2 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxLength"))
        }
    }

    @Test
    fun attention_mask_all_ones_no_padding() {
        // 本实现不 padding：attention_mask 全 1，长度 = 实际 token 数
        val result = tokenizer.encode("hello world foo bar")
        val expected = LongArray(result.length) { 1L }
        assertArrayEquals(expected, result.attentionMask)
    }

    @Test
    fun token_type_ids_all_zeros_for_single_sentence() {
        val result = tokenizer.encode("hello world")
        val expected = LongArray(result.length) { 0L }
        assertArrayEquals(expected, result.tokenTypeIds)
    }

    @Test
    fun whitespace_only_text_produces_cls_sep() {
        val result = tokenizer.encode("   \t\n  ")
        assertEquals(2, result.length)
    }

    @Test
    fun accents_stripped_when_do_lower_case() {
        // café → cafe（NFD 归一化去重音后小写）
        val withAccent = tokenizer.encode("café")
        val withoutAccent = tokenizer.encode("cafe")
        assertArrayEquals(withoutAccent.inputIds, withAccent.inputIds)
    }

    companion object {
        private const val CLS_ID = 101L
        private const val SEP_ID = 102L
        private lateinit var tokenizer: BertWordPieceTokenizer

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val vocabStream = java.io.File("src/test/resources/models/vocab.txt").inputStream()
            val vocab = BertWordPieceTokenizer.loadVocab(vocabStream)
            tokenizer = BertWordPieceTokenizer(vocab)
        }
    }
}
