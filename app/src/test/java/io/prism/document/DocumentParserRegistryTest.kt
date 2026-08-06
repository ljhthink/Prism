package io.prism.document

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DocumentParserRegistry 分发逻辑单元测试（US-012 验收标准 1）。
 */
class DocumentParserRegistryTest {

    private val registry = DocumentParserRegistry()

    @Test
    fun parserFor_pdf_returns_pdf_parser() {
        assertTrue(registry.parserFor("a.pdf") is PdfDocumentParser)
    }

    @Test
    fun parserFor_docx_returns_office_parser() {
        assertTrue(registry.parserFor("a.docx") is OfficeDocumentParser)
    }

    @Test
    fun parserFor_xlsx_returns_office_parser() {
        assertTrue(registry.parserFor("a.xlsx") is OfficeDocumentParser)
    }

    @Test
    fun parserFor_md_returns_plain_parser() {
        assertTrue(registry.parserFor("a.md") is PlainTextDocumentParser)
    }

    @Test
    fun parserFor_txt_and_csv_returns_plain_parser() {
        assertTrue(registry.parserFor("a.txt") is PlainTextDocumentParser)
        assertTrue(registry.parserFor("a.csv") is PlainTextDocumentParser)
    }

    @Test
    fun parserFor_unknown_extension_throws() {
        assertThrows(DocumentParseException::class.java) {
            registry.parserFor("a.zip")
        }
    }
}