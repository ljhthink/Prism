package io.prism.memory

/**
 * 记忆关键词索引分词器（v1 记忆深度优化 US-102，纯函数可测）。
 *
 * **目的**：为 SQLite FTS5 / 内存倒排索引提供中文友好的预分词。FTS5 默认 unicode61
 * tokenizer 按空格/标点切词，中文整句会被当成一个 token（无法命中关键词）。本分词器
 * 在写入索引与查询时对文本做统一预分词（空格 join），使 BM25 能命中中文关键词。
 *
 * **分词策略**（轻量、零依赖，不引入 jieba/HanLP）：
 * 1. **CJK 连续段**（`[\u4e00-\u9fff]`）：生成**重叠二元组**（bigram）+ 长度 2-6 的
 *    整段（提高精确短语命中率）。二元组是 CJK FTS 的经典方案，无需外部分词词典，
 *    对"冷词坍缩"（UXR1 教训）鲁棒——查询与文档走同一 tokenizer，任意子串均可命中。
 * 2. **字母/数字连续段**（`[A-Za-z0-9]`）：整体作为 token（转小写），支持
 *    "Kotlin"、"384" 等精确关键词。
 * 3. 其他标点/空白直接跳过。
 * 4. 单字符 CJK（如孤立"我"）不单独成 token（噪声大），但会作为二元组的一部分出现。
 *
 * **停止词**：不含中文停用词表（避免误删实体词）。对记忆检索场景，BM25 的 IDF 天然
 * 给高频常见词低权重，且混合检索（FTS + 向量 RRF）能互补，无需激进停用。
 *
 * @see <a href="https://sqlite.org/fts5.html">SQLite FTS5</a>
 */
object MemoryFtsTokenizer {

    /** CJK 连续段（含扩展区常用中文）。 */
    private val CJK_RUN = Regex("""[\u4e00-\u9fff\u3400-\u4dbf]+""")

    /** 字母/数字连续段。 */
    private val ALNUM_RUN = Regex("""[A-Za-z0-9]+""")

    /** 整段保留的 CJK 最大长度（超过则只取二元组，避免把长句当单一 token）。 */
    private const val MAX_WHOLE_CJK_LEN = 6

    /**
     * 将文本预分词为 token 列表（纯函数，可测）。
     *
     * @param text 原始文本（记忆内容或查询）
     * @return 去重后的 token 列表（顺序稳定）；空白输入返回空列表
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = LinkedHashSet<String>()

        // 1. CJK 连续段：重叠二元组 + 2-6 字整段
        for (match in CJK_RUN.findAll(text)) {
            val run = match.value
            if (run.length >= 2) {
                // 整段（2-6 字）——提高精确短语命中
                if (run.length <= MAX_WHOLE_CJK_LEN) {
                    tokens.add(run)
                }
                // 重叠二元组
                for (i in 0 until run.length - 1) {
                    tokens.add(run.substring(i, i + 2))
                }
            }
        }

        // 2. 字母/数字连续段（小写）
        for (match in ALNUM_RUN.findAll(text)) {
            tokens.add(match.value.lowercase())
        }

        return tokens.toList()
    }

    /**
     * 将文本预分词为空格分隔的 FTS 索引串（供 FTS5 写入 content 列 / MATCH 查询）。
     *
     * @param text 原始文本
     * @return 空格 join 的 token 串；无 token 返回空串
     */
    fun tokenizeForFts(text: String): String = tokenize(text).joinToString(" ")

    /**
     * 判断 token 串是否非空（FTS 写入/查询前防御）。
     *
     * @param ftsText [tokenizeForFts] 的输出
     * @return true 表示有可索引 token
     */
    fun isIndexable(ftsText: String): Boolean = ftsText.isNotBlank()
}
