package io.prism.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PdfDocumentParser 单元测试（US-012 验收标准 2）。
 */
class PdfDocumentParserTest {

    private val parser = PdfDocumentParser("sample.pdf")

    @Test
    fun parse_extracts_text_from_pdf() {
        val text = parser.parse(TestDocumentFactory.pdfByteStream("Hello Prism PDF"))
        assertTrue("应能抽取 PDF 文本，实际: [$text]", text.contains("Hello Prism PDF"))
    }

    @Test
    fun parse_invalid_pdf_throws() {
        val invalid = "not-a-pdf".byteInputStream()
        assertThrows(DocumentParseException::class.java) {
            parser.parse(invalid)
        }
    }

    @Test
    fun parse_empty_pdf_returns_empty_or_whitespace() {
        val text = parser.parse(TestDocumentFactory.pdfByteStream(""))
        assertEquals("空 PDF 应产生空文本", "", text.trim())
    }
}