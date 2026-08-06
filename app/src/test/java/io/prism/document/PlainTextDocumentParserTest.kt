package io.prism.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlainTextDocumentParser（MD/TXT/CSV）单元测试（US-012 验收标准 4）。
 */
class PlainTextDocumentParserTest {

    @Test
    fun txt_returns_content_verbatim() {
        val parser = PlainTextDocumentParser("a.txt", DocumentType.TXT)
        val text = parser.parse("Hello\nWorld".byteInputStream())
        assertEquals("TXT 应原样返回", "Hello\nWorld", text)
    }

    @Test
    fun csv_returns_content_verbatim() {
        val parser = PlainTextDocumentParser("a.csv", DocumentType.CSV)
        val text = parser.parse("a,b,c\n1,2,3".byteInputStream())
        assertEquals("CSV 应原样返回", "a,b,c\n1,2,3", text)
    }

    @Test
    fun md_strips_heading_markers() {
        val parser = PlainTextDocumentParser("a.md", DocumentType.MD)
        val text = parser.parse("# 一级标题\n## 二级标题".byteInputStream())
        assertTrue("应剥离 #，实际: [$text]", text.contains("一级标题"))
        assertTrue("应剥离 ##，实际: [$text]", text.contains("二级标题"))
        assertEquals("标题标记应被移除", false, text.contains("#"))
    }

    @Test
    fun md_strips_link_to_text() {
        val parser = PlainTextDocumentParser("a.md", DocumentType.MD)
        val text = parser.parse("[点击这里](https://example.com)".byteInputStream())
        assertEquals("链接应简化为文本", "点击这里", text.trim())
    }

    @Test
    fun md_supports_chinese_text() {
        val parser = PlainTextDocumentParser("a.md", DocumentType.MD)
        val text = parser.parse("## 知识库说明".byteInputStream())
        assertTrue("应保留中文内容，实际: [$text]", text.contains("知识库说明"))
    }

    @Test
    fun constructor_rejects_pdf_type() {
        assertThrows(IllegalArgumentException::class.java) {
            PlainTextDocumentParser("a.pdf", DocumentType.PDF)
        }
    }
}