package io.prism.rag

import io.prism.data.RetrievalResult
import io.prism.ui.model.Citation

/**
 * RAG 上下文构建器（US-019，ADR-012 5.1 / 5.3）。
 *
 * 职责单一：把 [RetrievalResult] 列表转换为三件套：
 * 1. [systemPrompt]：RAG grounding rules（固定模板，约束 AI 引用规则与无引用降级话术）
 * 2. [buildContext]：检索片段拼接的 user context 文本（含 `[来源N]` 编号 + 文件/片段元信息）
 * 3. [buildCitations]：[Citation] 列表（供 UI 渲染引用胶囊）
 *
 * **不负责** embed / search（那是 [io.prism.ui.chat.ConversationViewModel] 的职责）。
 *
 * **线程安全**：object 单例 + 纯函数，无共享状态，可被任意协程并发调用。
 *
 * **citation 编号一致性**：[buildContext] 与 [buildCitations] 必须基于同一份 `results` 列表
 * 调用，且 [RetrievalResult] 列表顺序不变。两者均使用 `index = i + 1`（1-based），
 * 保证 prompt 中「[来源N]」与 UI 引用胶囊的编号严格对齐。
 */
object RagContextBuilder {

    /**
     * RAG grounding rules（ADR-012 5.3 模板）。
     *
     * 约束 AI：
     * - 优先使用知识库片段回答
     * - 引用使用 [来源N] 格式
     * - 无引用时主动说明「知识库中未找到相关内容，以下回答基于模型自身知识」（US-019 AC-4）
     * - 不捏造来源
     */
    const val SYSTEM_PROMPT: String = """你是 Prism AI 助手。当提供【知识库片段】时，请遵循：
1. 优先使用【知识库片段】中的信息回答问题
2. 引用知识库片段时使用 [来源N] 格式，N 为片段编号
3. 若知识库片段未提供答案，明确说明「知识库中未找到相关内容，以下回答基于模型自身知识」
4. 不捏造来源，不引用未提供的片段编号"""

    /**
     * 把检索结果列表拼接为 RAG context 文本。
     *
     * 输出格式：
     * ```
     * 【知识库片段】
     * [来源1] 文件=文档A.pdf 片段=1
     * 内容...
     *
     * [来源2] 文件=文档B.md 片段=3
     * 内容...
     * 【END 知识库片段】
     * ```
     *
     * @param results 检索结果列表（按相似度降序，调用方已做阈值过滤）
     * @return 拼接后的 context 文本；results 为空时返回空串（调用方应据此跳过 ragContext 注入）
     */
    fun buildContext(results: List<RetrievalResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【知识库片段】\n")
        results.forEachIndexed { i, r ->
            val index = i + 1
            sb.append("[来源$index] ")
            sb.append(buildChunkInfo(r))
            sb.append('\n')
            sb.append(r.content)
            sb.append("\n\n")
        }
        sb.append("【END 知识库片段】")
        return sb.toString()
    }

    /**
     * 把检索结果列表转换为 [Citation] UI 列表。
     *
     * @param results 检索结果列表（必须与 [buildContext] 同一份，保证编号对齐）
     * @return Citation 列表；results 为空时返回空列表
     */
    fun buildCitations(results: List<RetrievalResult>): List<Citation> =
        results.mapIndexed { i, r ->
            Citation(
                index = i + 1,
                documentTitle = r.documentTitle,
                chunkIndex = r.chunkIndex,
                similarity = r.similarity
            )
        }

    /** 构建单条检索结果的元信息行（`文件=xxx 片段=N`）。chunkIndex 为 null 时省略片段段。 */
    private fun buildChunkInfo(r: RetrievalResult): String {
        val chunkPart = r.chunkIndex?.let { " 片段=$it" } ?: ""
        return "文件=${r.documentTitle}$chunkPart"
    }
}
