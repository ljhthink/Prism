package io.prism.document

/**
 * 文本切片器 —— 将长文档切分为可检索的片段。
 *
 * US-013 验收标准：
 * 1. 支持可配置 chunkSize 与 overlap
 * 2. 切片保持段落边界优先，避免在句子中间截断
 * 3. 切片单元测试通过（边界、空输入、超长输入）
 *
 * 切片策略（ADR-007 5.4 配套，供 RAG top-k 检索）：
 * - **段落优先**：优先以空行分隔的段落为边界切分，避免把语义无关的独立段落拼进同一块。
 * - **句子边界兜底**：当单段超过 chunkSize 时，在句子结尾（。！？.!?；;…）附近回退切分，
 *   避免把一句炸断在中间。
 * - **overlap**：相邻切片重叠 overlap 字符，保留上下文衔接。**决策**：overlap 跨段落生效
 *   （guardrail G-1）——RAG 中相邻段落属连续上下文，衔接保留可降低检索窗口边界丢失。
 *   此行为在 [chunk] 与测试中显式锁定。
 *
 * 线程安全：本类无状态，可安全跨线程复用。
 *
 * @property chunkSize 目标切片长度（字符数）
 * @property overlap 相邻切片重叠字符数，须 < chunkSize
 */
class Chunker(
    private val chunkSize: Int,
    private val overlap: Int
) {

    init {
        require(chunkSize > 0) { "chunkSize 必须 > 0，收到: $chunkSize" }
        require(overlap in 0 until chunkSize) { "overlap 必须位于 [0, chunkSize)，收到: $overlap" }
    }

    /**
     * 将输入文本切分为片段列表。
     *
     * 空输入/纯空白返回空列表。
     *
     * @param text 待切分文本
     * @return 非空片段列表（可能为空列表）
     */
    fun chunk(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // 按段落（空行或连续换行）初步分组
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val result = mutableListOf<String>()

        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            appendChunk(trimmed, result)
        }

        // 相邻片段应用 overlap：若已按段落切分，追加 overlap 衔接
        return applyOverlap(result)
    }

    /**
     * 将单个段落切为不超过 chunkSize 的块，优先在句子边界回退。
     */
    private fun appendChunk(paragraph: String, out: MutableList<String>) {
        var start = 0
        while (start < paragraph.length) {
            var end = minOf(start + chunkSize, paragraph.length)
            if (end == paragraph.length) {
                out.add(paragraph.substring(start))
                return
            }
            // 在 [start, end] 闭区间内寻找最后一个句子边界（含 end 位置，避免句号孤儿 G-2）
            val sentenceBoundary = findSentenceBoundaryInclusive(paragraph, start, end)
            // 若无句子边界，回退到词边界（空格/空白），避免英文单词/中文词中间截断（G-3）
            val wordBoundary = findWordBoundary(paragraph, start, end)
            val cut = maxOf(sentenceBoundary, wordBoundary, start)
            if (cut > start) {
                out.add(paragraph.substring(start, cut))
                start = cut
            } else {
                // 兜底：整窗口无任何边界，硬切
                out.add(paragraph.substring(start, end))
                start = end
            }
        }
    }

    /**
     * 在 [from, to] 闭区间内从后往前寻找句子结尾边界（。！？…….!?…`）。
     * 含 to 位置，避免句尾标点恰好落在截断位时被遗漏（guardrail G-2）。
     */
    private fun findSentenceBoundaryInclusive(text: String, from: Int, to: Int): Int {
        for (i in to downTo from) {
            if (text[i] in SENTENCE_ENDINGS) return i + 1
        }
        return from
    }

    /**
     * 在 [from, to] 内从后往前寻找词边界（空白字符），返回边界后的位置。
     * 无空白时返回 [from]（即不提供词边界回退点）。
     */
    private fun findWordBoundary(text: String, from: Int, to: Int): Int {
        for (i in to downTo from) {
            if (text[i].isWhitespace()) return i + 1
        }
        return from
    }

    /**
     * 为相邻切片应用 overlap：取前一切片末尾 overlap 字符拼到后一切片开头。
     */
    private fun applyOverlap(chunks: List<String>): List<String> {
        if (overlap == 0 || chunks.size <= 1) return chunks
        val result = mutableListOf<String>()
        for (i in chunks.indices) {
            if (i == 0) {
                result.add(chunks[i])
            } else {
                val prev = chunks[i - 1]
                val tail = prev.takeLast(minOf(overlap, prev.length))
                result.add(tail + chunks[i])
            }
        }
        return result
    }

    private companion object {
        /** 常见的句子结尾标点（中英文） */
        val SENTENCE_ENDINGS = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', '…')
    }
}