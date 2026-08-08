package io.prism.rag

import io.prism.data.RetrievalResult
import io.prism.ui.model.Citation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RagContextBuilder 单元测试（US-019，ADR-012 5.1/5.3）。
 *
 * 覆盖：
 * - SYSTEM_PROMPT 非空且包含关键约束（引用规则、无引用降级话术）
 * - buildContext 空列表返回空串
 * - buildContext 多结果拼接顺序与 [来源N] 编号正确
 * - buildCitations 编号与 buildContext 对齐
 * - chunkIndex 为 null 时省略片段段
 *
 * 验收对应：US-019 AC-3「AI 回答标注引用来源」/ AC-4「无引用时主动说明」
 */
class RagContextBuilderTest {

    @Test
    fun `SYSTEM_PROMPT contains grounding rules`() {
        val prompt = RagContextBuilder.SYSTEM_PROMPT
        assertTrue("system prompt 应非空", prompt.isNotBlank())
        assertTrue("应包含 [来源N] 引用格式约束", prompt.contains("[来源N]"))
        assertTrue("应包含无引用降级话术（AC-4）", prompt.contains("知识库中未找到相关内容"))
        assertTrue("应禁止捏造来源", prompt.contains("不捏造来源"))
    }

    @Test
    fun `buildContext returns empty string for empty results`() {
        assertEquals("", RagContextBuilder.buildContext(emptyList()))
    }

    @Test
    fun `buildContext prepends header and numbers sources correctly`() {
        val results = listOf(
            makeResult("文档A.pdf", 1, "内容A"),
            makeResult("文档B.md", 3, "内容B")
        )

        val context = RagContextBuilder.buildContext(results)

        assertTrue("应以【知识库片段】开头", context.startsWith("【知识库片段】"))
        assertTrue("应以【END 知识库片段】结尾", context.endsWith("【END 知识库片段】"))
        assertTrue("应包含 [来源1]", context.contains("[来源1]"))
        assertTrue("应包含 [来源2]", context.contains("[来源2]"))
        assertTrue("应包含文档A文件信息", context.contains("文件=文档A.pdf"))
        assertTrue("应包含文档A片段信息", context.contains("片段=1"))
        assertTrue("应包含文档B文件信息", context.contains("文件=文档B.md"))
        assertTrue("应包含文档B片段信息", context.contains("片段=3"))
        assertTrue("应包含内容A", context.contains("内容A"))
        assertTrue("应包含内容B", context.contains("内容B"))
    }

    @Test
    fun `buildContext omits chunk part when chunkIndex is null`() {
        // title 不含 # 时 chunkIndex 为 null（RetrievalResult 解析容错）
        val results = listOf(makeResult("无扩展名文档", null, "内容"))

        val context = RagContextBuilder.buildContext(results)

        assertTrue("应包含文档标题", context.contains("文件=无扩展名文档"))
        assertFalse("chunkIndex 为 null 时不应输出 片段=", context.contains("片段="))
    }

    @Test
    fun `buildCitations returns empty list for empty results`() {
        assertEquals(emptyList<Citation>(), RagContextBuilder.buildCitations(emptyList()))
    }

    @Test
    fun `buildCitations numbers indices aligned with buildContext`() {
        val results = listOf(
            makeResult("文档A.pdf", 1, "内容A", similarity = 0.9),
            makeResult("文档B.md", 3, "内容B", similarity = 0.7),
            makeResult("文档C.txt", null, "内容C", similarity = 0.5)
        )

        val citations = RagContextBuilder.buildCitations(results)

        assertEquals(3, citations.size)
        assertEquals(Citation(index = 1, documentTitle = "文档A.pdf", chunkIndex = 1, similarity = 0.9), citations[0])
        assertEquals(Citation(index = 2, documentTitle = "文档B.md", chunkIndex = 3, similarity = 0.7), citations[1])
        assertEquals(Citation(index = 3, documentTitle = "文档C.txt", chunkIndex = null, similarity = 0.5), citations[2])
    }

    @Test
    fun `buildContext and buildCitations produce consistent indices for same input`() {
        val results = listOf(
            makeResult("X.pdf", 1, "x"),
            makeResult("Y.pdf", 2, "y")
        )

        val context = RagContextBuilder.buildContext(results)
        val citations = RagContextBuilder.buildCitations(results)

        // 引用编号 1..N 必须在 context 和 citations 中对齐
        citations.forEach { c ->
            assertTrue("context 应包含 [来源${c.index}] 编号", context.contains("[来源${c.index}]"))
        }
    }

    /** 构造测试用 RetrievalResult。 */
    private fun makeResult(
        documentTitle: String,
        chunkIndex: Int?,
        content: String,
        similarity: Double = 0.5
    ): RetrievalResult {
        val title = if (chunkIndex != null) "$documentTitle#$chunkIndex" else documentTitle
        return RetrievalResult(
            chunkId = 0L,
            content = content,
            title = title,
            similarity = similarity,
            documentTitle = documentTitle,
            chunkIndex = chunkIndex,
            knowledgeBaseId = 0L
        )
    }
}
