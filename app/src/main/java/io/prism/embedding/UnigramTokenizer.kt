package io.prism.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.text.Normalizer

/**
 * XLM-R SentencePiece Unigram 分词器（UXR9 US-901，多语言嵌入模型配套 tokenizer）。
 *
 * 配套 `paraphrase-multilingual-MiniLM-L12-v2`（XLM-RoBERTa 架构，50+ 语言，
 * 含中文/日文/韩文等）。替换英文 BERT WordPiece tokenizer——英文模型对中文语义
 * 区分度差（实测无关中文片段余弦相似度 0.4~0.7，0.5 阈值拦不住，见
 * `ChineseSimilarityDiagnosticTest`），多语言模型是中文 RAG 的治本方案。
 *
 * **算法**（严格对齐 HuggingFace `tokenizers` Rust 库，Python 实测 15/15 样本
 * 与 `transformers` tokenizer 输出完全一致，见开发记录）：
 * 1. [normalize]：NFKC 归一化（XLM-R **不转小写**，大小写保留——与英文 BERT 不同）
 * 2. [preTokenize]：WhitespaceSplit + Metaspace（`▁` U+2581 替换空白 + 前缀空格）
 * 3. [unigramSegment]：对每个 `▁词` 段做 Unigram Viterbi（最大化 piece 分数和）
 * 4. [encode]：`<s>` + 各段 pieces + `</s>`，截断到 [maxLength]
 *
 * **输入**：`input_ids` + `attention_mask`（XLM-R 无 token_type_ids，输出全 0，
 * OnnxEmbedder 按 session 实际输入名跳过）。
 *
 * **词表来源**：`tokenizer.json`（HuggingFace tokenizers 格式，`model.vocab` 为
 * `[piece, score]` 列表，列表索引 = token id；`added_tokens` 含 `<s>`/`</s>`/`<unk>`）。
 *
 * @param tokenizerJson tokenizer.json 输入流（UTF-8）
 * @param maxInputCharsPerSegment 单段最大字符数（Unigram 分段上限，防异常超长）。
 *   默认 100 与 HuggingFace `tokenizers` Rust Unigram `max_input_chars_per_word`
 *   参考默认一致（tokenizer.json 未覆盖该配置；sentencepiece 参考为 128）。
 *   长无空格 CJK 段（>64 字符）仍走正常 Viterbi 子词切分，不逐字符退化。
 */
class UnigramTokenizer(
    tokenizerJson: InputStream,
    private val maxInputCharsPerSegment: Int = 100
) : TokenEncoder {

    /** piece → id（Unigram 词表，~250k 项）。 */
    private val vocab: Map<String, Int>

    /** piece → score（Unigram Viterbi 权重）。 */
    private val scores: Map<String, Double>

    /** 未登录词 id（<unk>）。 */
    private val unkId: Int

    /** 句首 id（<s>）。 */
    private val bosId: Int

    /** 句尾 id（</s>）。 */
    private val eosId: Int

    /** 词表最大 piece 长度（Viterbi 前缀搜索上界）。 */
    private val maxPieceLen: Int

    init {
        val root = Json.parseToJsonElement(tokenizerJson.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
        val model = root["model"]?.jsonObject
            ?: throw EmbeddingException(EmbeddingException.Stage.TOKENIZER_INIT, "tokenizer.json 缺少 model 字段")
        if (model["type"]?.jsonPrimitive?.content != "Unigram") {
            throw EmbeddingException(
                EmbeddingException.Stage.TOKENIZER_INIT,
                "tokenizer model.type 应为 Unigram，实际: ${model["type"]?.jsonPrimitive?.content}"
            )
        }
        val rawVocab = model["vocab"] as? JsonArray
            ?: throw EmbeddingException(EmbeddingException.Stage.TOKENIZER_INIT, "tokenizer.json 缺少 model.vocab")
        val v = HashMap<String, Int>(rawVocab.size)
        val s = HashMap<String, Double>(rawVocab.size)
        var maxLen = 1
        rawVocab.forEachIndexed { id, entry ->
            val arr = entry as? JsonArray
                ?: return@forEachIndexed
            val piece = arr.getOrNull(0)?.jsonPrimitive?.content ?: return@forEachIndexed
            val score = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0
            v[piece] = id
            s[piece] = score
            if (piece.length > maxLen) maxLen = piece.length
        }
        vocab = v
        scores = s
        maxPieceLen = maxLen.coerceAtMost(64)

        unkId = model["unk_id"]?.jsonPrimitive?.longOrNull?.toInt() ?: 3
        var bos = 0
        var eos = 2
        (root["added_tokens"] as? JsonArray)?.forEach { el ->
            val obj = el.jsonObject
            val content = obj["content"]?.jsonPrimitive?.content
            val id = obj["id"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@forEach
            when (content) {
                "<s>" -> bos = id
                "</s>" -> eos = id
            }
        }
        bosId = bos
        eosId = eos
    }

    override fun encode(text: String, maxLength: Int): BertWordPieceTokenizer.TokenizationResult {
        // L-1（guardrail TKN-UXR9-GUARDRAIL-001）：与 BERT 侧对称，fail-fast 校验最小序列长度
        require(maxLength >= 2) { "maxLength 至少容纳 <s>+</s>=2，收到: $maxLength" }
        val normalized = normalize(text)
        val segments = preTokenize(normalized)
        // 预留 <s> + </s>；body 精确填至 maxBody（总长 = maxLength），与 HF
        // truncation=max_length 语义一致（long 输入时精确产出 maxLength token）。
        val maxBody = (maxLength - 2).coerceAtLeast(0)
        val ids = ArrayList<Long>(maxBody + 2)
        ids.add(bosId.toLong())
        for (seg in segments) {
            if (ids.size > maxBody) break
            for (id in unigramSegment(seg)) {
                if (ids.size > maxBody) break
                ids.add(id.toLong())
            }
        }
        ids.add(eosId.toLong())
        val inputIds = ids.toLongArray()
        val attentionMask = LongArray(inputIds.size) { 1L }
        val tokenTypeIds = LongArray(inputIds.size) { 0L } // XLM-R 无 token_type_ids，全 0
        return BertWordPieceTokenizer.TokenizationResult(inputIds, attentionMask, tokenTypeIds)
    }

    /**
     * 完整分词（暴露供测试/调试）：返回含 <s>/</s> 的 token id 列表。
     * 与 transformers `tokenizer(s, add_special_tokens=True)['input_ids']` 对齐。
     */
    fun tokenizeIds(text: String): List<Int> {
        val normalized = normalize(text)
        val segments = preTokenize(normalized)
        return buildList {
            add(bosId)
            segments.forEach { addAll(unigramSegment(it)) }
            add(eosId)
        }
    }

    /** NFKC 归一化（XLM-R 不转小写）。 */
    internal fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /**
     * WhitespaceSplit + Metaspace 预分词（▁ 替换 + 前缀空格）。
     * 每个非空段前缀加 `▁`（U+2581），与 sentencepiece/metaspace 一致。
     */
    internal fun preTokenize(text: String): List<String> =
        text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }.map { "\u2581$it" }

    /**
     * Unigram Viterbi：对单段（含 ▁ 前缀）求最大化 piece 分数和的切分。
     * 算法与 `tokenizers` Rust 库一致（Python 原型 15/15 样本匹配验证）。
     *
     * @param segment 已含 ▁ 前缀的单段（如 "▁你好"）
     * @return 该段的 piece id 列表（按顺序）
     */
    internal fun unigramSegment(segment: String): List<Int> {
        val n = segment.length
        if (n == 0) return emptyList()
        if (n > maxInputCharsPerSegment) {
            // 超长段：逐字符退化（避免极端输入放大 Viterbi）
            return segment.map { vocab[it.toString()] ?: unkId }
        }
        val neg = Double.NEGATIVE_INFINITY
        val dpScore = DoubleArray(n + 1) { neg }
        val dpPrev = IntArray(n + 1) { -1 }
        val dpPiece = arrayOfNulls<String>(n + 1)
        dpScore[0] = 0.0
        val maxLen = minOf(n, maxPieceLen)
        for (i in 1..n) {
            var best = neg
            var bestJ = -1
            var bestPiece: String? = null
            val limit = minOf(i, maxLen)
            for (l in 1..limit) {
                val j = i - l
                val piece = segment.substring(j, i)
                val sc = scores[piece]
                if (sc != null) {
                    val cand = dpScore[j] + sc
                    if (cand > best) {
                        best = cand
                        bestJ = j
                        bestPiece = piece
                    }
                }
            }
            if (bestJ < 0) {
                // 无匹配 piece：单字符退化（在词表则用其 score，否则 unk）
                val piece = segment.substring(i - 1, i)
                val sc = scores[piece]
                best = if (sc != null) dpScore[i - 1] + sc else dpScore[i - 1]
                bestJ = i - 1
                bestPiece = if (sc != null) piece else null
            }
            dpScore[i] = best
            dpPrev[i] = bestJ
            dpPiece[i] = bestPiece
        }
        // 回溯
        val ids = ArrayList<Int>(n)
        var i = n
        while (i > 0) {
            val j = dpPrev[i]
            val piece = dpPiece[i]
            ids.add(piece?.let { vocab[it] } ?: unkId)
            i = j
        }
        ids.reverse()
        return ids
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** 从 assets 加载 tokenizer.json 并构造（EmbedderFactory 便捷入口）。 */
        fun fromJsonStream(stream: InputStream): UnigramTokenizer = UnigramTokenizer(stream)
    }
}
