package io.prism.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * UnigramTokenizer 与 HuggingFace `tokenizers` Rust 参考实现的对齐测试（UXR9 US-901）。
 *
 * **Q-HIGH-1 闭环**（guardrail TKN-UXR9-GUARDRAIL-002）：此前 Viterbi 对齐仅靠注释声明
 * "15/15 样本匹配"，零测试锁定；且 `maxInputCharsPerSegment=64` 与参考默认（100）不一致，
 * 长无空格 CJK 段（65~100 字符）静默退化为逐字符切分。
 *
 * 本测试：
 * 1. 用**真实 tokenizer.json** 加载（与生产 `EmbedderFactory.createMultilingual` 完全一致）
 * 2. 断言 `tokenizeIds` 输出与 [UnigramTokenizerReference]（由 HF `tokenizers` 0.22.2
 *    Rust 库生成，见 tools/gen_unigram_reference.py）逐样本完全一致
 * 3. 覆盖临界长句（(64,100] 区间）——若退化逐字符将与参考不符，测试即失败
 */
class UnigramTokenizerTest {

    private fun tokenizer(maxInputCharsPerSegment: Int = 100): UnigramTokenizer =
        UnigramTokenizer(
            File(TOKENIZER_JSON_PATH).inputStream(),
            maxInputCharsPerSegment = maxInputCharsPerSegment
        )

    @Test
    fun `tokenizeIds matches HF tokenizers reference for all samples`() {
        val tk = tokenizer()
        val expected = UnigramTokenizerReference.SAMPLES
        assertTrue("参考数据不应为空", expected.isNotEmpty())
        expected.forEach { (text, expectedIds) ->
            val actual = tk.tokenizeIds(text)
            assertEquals("样本 [$text] 输出与 HF 参考不一致", expectedIds, actual)
        }
    }

    @Test
    fun `boundary - 88 char CJK segment stays subword, not per-char degradation`() {
        val longCjk = UnigramTokenizerReference.SAMPLES.last().first
        assertTrue("样本应为 >64 字符以覆盖边界", longCjk.length in 65..100)
        val tk = tokenizer()
        val tokens = tk.tokenizeIds(longCjk)
        // 若 maxInputCharsPerSegment < 长句长度且退化为逐字符，token 数会接近字符数
        assertTrue(
            "88 字符 CJK 段应保持子词合并（token 数 ${tokens.size} < 字符数 ${longCjk.length}）",
            tokens.size < longCjk.length
        )
    }

    @Test
    fun `maxInputCharsPerSegment below reference default regresses to per-char`() {
        // 锁定"参考默认必须 ≥ 长句长度"，否则对齐测试失去边界保护意义
        val longCjk = UnigramTokenizerReference.SAMPLES.last().first
        val strict = tokenizer(maxInputCharsPerSegment = 64) // 旧值
        val refDefault = tokenizer(maxInputCharsPerSegment = 100) // 新值
        assertNotEquals(
            "旧上限 64 与参考默认 100 应产生不同切分（暴露静默退化）",
            refDefault.tokenizeIds(longCjk),
            strict.tokenizeIds(longCjk)
        )
    }

    @Test
    fun `empty string yields bos and eos`() {
        assertEquals(listOf(0, 2), tokenizer().tokenizeIds(""))
    }

    @Test
    fun `encode respects maxLength truncation`() {
        val tk = tokenizer()
        val result = tk.encode("这是一段用于测试截断的长文本。" + "补充内容".repeat(200), maxLength = 64)
        assertEquals(64, result.inputIds.size)
        assertEquals(0L, result.inputIds.first())  // <s>
        assertEquals(2L, result.inputIds.last())   // </s>
        assertEquals(64, result.attentionMask.size)
        assertEquals(64, result.tokenTypeIds.size)
        assertEquals(1L, result.attentionMask.first())
    }

    @Test
    fun `encode require maxLength at least 2`() {
        val tk = tokenizer()
        try {
            tk.encode("测试", maxLength = 1)
            throw AssertionError("maxLength=1 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `normalize applies NFKC`() {
        val tk = tokenizer()
        // 全角数字/符号 NFKC 折叠为半角，与 transformers 一致（不转小写）
        val fullWidth = "ＡＢＣ１２３"
        val nfkc = tk.normalize(fullWidth)
        assertEquals("ABC123", nfkc)
    }

    companion object {
        private const val TOKENIZER_JSON_PATH =
            "src/main/assets/models/tokenizer.json"

        @BeforeClass
        @JvmStatic
        fun verifyAssetExists() {
            val f = File(TOKENIZER_JSON_PATH)
            assertTrue("tokenizer.json 必须存在（$TOKENIZER_JSON_PATH）", f.isFile)
        }
    }
}
