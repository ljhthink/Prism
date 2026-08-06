package io.prism.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DocumentType 扩展名识别单元测试（US-012 验收标准 1）。
 */
class DocumentTypeTest {

    @Test
    fun fromFileName_recognizes_all_formats() {
        assertEquals(DocumentType.PDF, DocumentType.fromFileName("report.pdf"))
        assertEquals(DocumentType.DOCX, DocumentType.fromFileName("notes.docx"))
        assertEquals(DocumentType.XLSX, DocumentType.fromFileName("data.xlsx"))
        assertEquals(DocumentType.MD, DocumentType.fromFileName("README.md"))
        assertEquals(DocumentType.TXT, DocumentType.fromFileName("junk.txt"))
        assertEquals(DocumentType.CSV, DocumentType.fromFileName("table.csv"))
    }

    @Test
    fun fromFileName_is_case_insensitive() {
        assertEquals(DocumentType.PDF, DocumentType.fromFileName("REPORT.PDF"))
        assertEquals(DocumentType.MD, DocumentType.fromFileName("Readme.Md"))
    }

    @Test
    fun fromFileName_ignores_path_prefix() {
        assertEquals(DocumentType.DOCX, DocumentType.fromFileName("docs/2026/notes.docx"))
    }

    @Test
    fun fromFileName_returns_null_for_unknown_extension() {
        assertNull(DocumentType.fromFileName("archive.zip"))
        assertNull(DocumentType.fromFileName("image.png"))
    }

    @Test
    fun fromFileName_returns_null_for_no_extension() {
        assertNull(DocumentType.fromFileName("README"))
        assertNull(DocumentType.fromFileName("archive."))
    }

    @Test
    fun fromFileName_returns_null_for_empty_or_blank() {
        assertNull(DocumentType.fromFileName(""))
        assertNull(DocumentType.fromFileName("  "))
    }
}