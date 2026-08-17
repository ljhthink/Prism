package io.prism.embedding

import java.io.InputStream
import java.text.Normalizer

/**
 * BERT WordPiece 分词器 —— all-MiniLM-L6-v2 端侧嵌入的配套 tokenizer。
 *
 * **背景**：all-MiniLM-L6-v2 基于 BERT（uncased），ONNX 模型仅接受
 * `input_ids` / `attention_mask` / `token_type_ids` 三个 int64 张量，
 * 必须在端侧完成「文本 → 子词 id」序列化。本类严格对齐 HuggingFace
 * `BertTokenizer`（do_lower_case=true / WordPiece / 中文分字 / 标点分割）
 * 以保证与模型预训练时一致的输入分布。
 *
 * **算法分层**（与 transformers `BertTokenizer` 完全一致）：
 * 1. [cleanText]：控制字符 → 空格（保留 \t\n\r → 空格），删除其他 C* 类别字符
 * 2. [tokenizeChineseChars]：每个中文字符前后插入空格（使中文按字切分）
 * 3. [lowercaseAndStripAccents]：do_lower_case=true 时，NFD 归一化后小写并去除 Mn 类重音
 * 4. [splitOnPunct]：标点字符单独成 token
 * 5. [splitOnWhitespace]：按空白分割为 basic token 列表
 * 6. [wordpieceTokenize]：对每个 basic token 贪婪最长前缀匹配 vocab，未匹配则 [UNK]
 * 7. [encode]：[CLS] + 子词 + [SEP]，截断到 [maxLength]，生成三张量
 *
 * **不变式**：
 * - [encode] 返回的三张量长度相同且等于 `min(tokenCount+2, maxLength)`
 * - 不做 padding（ONNX 模型 sequence_length 为动态维度，按实际长度推理最高效）
 * - attention_mask 全 1（无 pad），token_type_ids 全 0（单句）
 *
 * US-014 验收标准 2：embed(text) 编码为 384 维向量，前置依赖为本 tokenizer。
 *
 * @param vocab 词表：token → id（由 [loadVocab] 从 vocab.txt 加载）
 * @param doLowerCase 是否小写化并去除重音（all-MiniLM-L6-v2 为 true）
 * @param maxInputCharsPerWord 单个 basic token 最大字符数，超长直接 [UNK]
 * @param unkToken 未知词标记
 * @param clsToken 句首标记
 * @param sepToken 句尾标记
 * @param padToken 填充标记（本实现不使用 padding，仅保留用于索引查询）
 */
class BertWordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val doLowerCase: Boolean = true,
    private val maxInputCharsPerWord: Int = 100,
    private val unkToken: String = "[UNK]",
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
    private val padToken: String = "[PAD]"
) : TokenEncoder {

    /** 单次分词结果 —— 对齐 BERT 模型三个输入张量。 */
    data class TokenizationResult(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    ) {
        val length: Int get() = inputIds.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TokenizationResult) return false
            return inputIds.contentEquals(other.inputIds) &&
                attentionMask.contentEquals(other.attentionMask) &&
                tokenTypeIds.contentEquals(other.tokenTypeIds)
        }

        override fun hashCode(): Int {
            var r = inputIds.contentHashCode()
            r = 31 * r + attentionMask.contentHashCode()
            r = 31 * r + tokenTypeIds.contentHashCode()
            return r
        }
    }

    /**
     * 编码文本为模型输入三张量。
     *
     * 流程：basic_tokenize → wordpiece_tokenize → 加 [CLS]/[SEP] → 截断 → 转 id。
     *
     * @param text 原始文本
     * @param maxLength 最大序列长度（含 [CLS]/[SEP]），all-MiniLM-L6-v2 上限 512
     * @return [TokenizationResult]，三张量等长且等于实际 token 数（不 padding）
     */
    override fun encode(text: String, maxLength: Int): TokenizationResult {
        require(maxLength >= 2) { "maxLength 至少容纳 [CLS]+[SEP]=2，收到: $maxLength" }
        val tokens = tokenize(text)
        // 预留 [CLS] + [SEP] 两个位置
        val maxBody = maxLength - 2
        val truncated = if (tokens.size > maxBody) tokens.subList(0, maxBody) else tokens
        val full = ArrayList<String>(truncated.size + 2).apply {
            add(clsToken)
            addAll(truncated)
            add(sepToken)
        }
        val unkId = vocab[unkToken] ?: throw EmbeddingException(
            EmbeddingException.Stage.TOKENIZER_INIT,
            "vocab 缺失 unk_token: $unkToken"
        )
        val inputIds = LongArray(full.size) { i -> vocab[full[i]]?.toLong() ?: unkId.toLong() }
        val attentionMask = LongArray(full.size) { 1L }
        val tokenTypeIds = LongArray(full.size) { 0L }
        return TokenizationResult(inputIds, attentionMask, tokenTypeIds)
    }

    /**
     * 便捷重载（UXR9 US-901 引入）：以默认最大序列长度 512 编码。
     *
     * 背景：Kotlin 禁止 override 声明默认参数值（默认值仅接口声明），
     * 但具体类型调用方（如 BertWordPieceTokenizerTest 的 `tokenizer.encode("")`）
     * 无法继承接口默认值。故增加本单参重载保持具体类型 API 兼容。
     */
    fun encode(text: String): TokenizationResult = encode(text, DEFAULT_MAX_LENGTH)

    /** 完整分词：basic_tokenize → wordpiece_tokenize（暴露用于测试与调试）。 */
    fun tokenize(text: String): List<String> {
        val basicTokens = basicTokenize(text)
        return basicTokens.flatMap { wordpieceTokenize(it) }
    }

    /**
     * 基础分词：清洗 → 中文分字 → 小写化 → 标点分割 → 空白分割。
     * 对齐 transformers `BasicTokenizer.tokenize`。
     */
    internal fun basicTokenize(text: String): List<String> {
        var cleaned = cleanText(text)
        cleaned = tokenizeChineseChars(cleaned)
        val tokens = cleaned.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        return tokens.flatMap { token ->
            val lowered = if (doLowerCase) lowercaseAndStripAccents(token) else token
            splitOnPunct(lowered)
        }.filter { it.isNotEmpty() }
    }

    /** 清洗：控制字符 → 空格（\t\n\r 除外，它们先转空格），删除其他控制字符。 */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                isControl(ch) -> { /* 丢弃控制字符 */ }
                isWhitespaceBert(ch) -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 中文分字：每个中文字符前后插入空格，使后续空白分割能按字切分。 */
    private fun tokenizeChineseChars(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            if (isChineseChar(ch.code)) {
                sb.append(' ').append(ch).append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** do_lower_case=true 时：NFD 归一化 → 去重音 → 小写。 */
    private fun lowercaseAndStripAccents(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.filter { Character.getType(it.code).toByte() != Character.NON_SPACING_MARK }
            .lowercase()
    }

    /** 标点分割：标点字符单独成 token，非标点连续字符成 token。 */
    private fun splitOnPunct(text: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            if (isPunctuation(ch)) {
                if (buf.isNotEmpty()) { result.add(buf.toString()); buf.setLength(0) }
                result.add(ch.toString())
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) result.add(buf.toString())
        return result
    }

    /**
     * WordPiece 子词分词：贪婪最长前缀匹配。
     *
     * 对每个 basic token：
     * - 字符数超 [maxInputCharsPerWord] → [unkToken]
     * - 从 start=0 起，end 从末尾向左收缩找最长 vocab 命中子串
     *   （start>0 时子串加 `##` 前缀表示续接前一词片段）
     * - 任一位置无命中 → 整个 token 标记 [unkToken]
     */
    internal fun wordpieceTokenize(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        if (token.length > maxInputCharsPerWord) return listOf(unkToken)
        val chars = token.toCharArray()
        val subTokens = mutableListOf<String>()
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var curSubstr: String? = null
            while (start < end) {
                val substr = String(chars, start, end - start)
                val candidate = if (start > 0) "##$substr" else substr
                if (vocab.containsKey(candidate)) {
                    curSubstr = candidate
                    break
                }
                end--
            }
            if (curSubstr == null) return listOf(unkToken)
            subTokens.add(curSubstr)
            start = end
        }
        return subTokens
    }

    // —— Unicode 类别判定（严格对齐 transformers `_is_*` 系列） ——

    private fun isControl(c: Char): Boolean {
        if (c == '\t' || c == '\n' || c == '\r') return false
        val t = Character.getType(c.code).toByte()
        // C* 类别：CONTROL(15) / FORMAT(16) / SURROGATE(19) / PRIVATE_USE(18) / UNASSIGNED(0)
        return t == Character.CONTROL || t == Character.FORMAT ||
            t == Character.SURROGATE || t == Character.PRIVATE_USE ||
            t == 0.toByte()
    }

    private fun isWhitespaceBert(c: Char): Boolean {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return true
        return Character.getType(c.code).toByte() == Character.SPACE_SEPARATOR
    }

    private fun isPunctuation(c: Char): Boolean {
        val cp = c.code
        // ASCII 标点范围（与 transformers _is_punctuation 一致）
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        val t = Character.getType(cp).toByte()
        // Unicode P* 类别
        return t == Character.DASH_PUNCTUATION || t == Character.START_PUNCTUATION ||
            t == Character.END_PUNCTUATION || t == Character.CONNECTOR_PUNCTUATION ||
            t == Character.OTHER_PUNCTUATION || t == Character.INITIAL_QUOTE_PUNCTUATION ||
            t == Character.FINAL_QUOTE_PUNCTUATION
    }

    private fun isChineseChar(cp: Int): Boolean {
        // 严格对齐 transformers `_is_chinese_char` 全部 8 个区间
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
            cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
            cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
            cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** 默认最大序列长度（含 [CLS]/[SEP]，与 TokenEncoder 接口默认值对齐）。 */
        private const val DEFAULT_MAX_LENGTH = 512

        /**
         * 从 vocab.txt 加载词表：每行一个 token，行号 = id。
         *
         * @param input vocab.txt 输入流（UTF-8，按行读取）
         * @return token → id 映射
         */
        fun loadVocab(input: InputStream): Map<String, Int> {
            val vocab = HashMap<String, Int>(32000)
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                var id = 0
                var line = reader.readLine()
                while (line != null) {
                    // 去掉行尾可能存在的 \r（Windows 换行），不 trim 行内空白
                    val token = if (line.endsWith('\r')) line.dropLast(1) else line
                    // G-06（guardrail）：空行 fail-fast，防止 id 错位导致语义错乱
                    require(token.isNotEmpty()) {
                        "vocab 第 $id 行为空，词表文件可能损坏"
                    }
                    vocab[token] = id
                    id++
                    line = reader.readLine()
                }
            }
            return vocab
        }
    }
}
