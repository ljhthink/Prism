package io.prism.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXR9 US-907 补充测试（ac-verifier，TKN-UXR9-ACCEPTANCE-001）。
 *
 * 主 Agent 的 [DocumentParserRegistryTest] 覆盖 PDF/DOCX/XLSX/MD/TXT/CSV 分发与未知扩展名，
 * 但 **未覆盖 UXR9 新增的 PPTX 分发**（registry → PptxDocumentParser）。本文件补充：
 *
 * - AC-3：`parserFor("a.pptx")` 返回 [PptxDocumentParser]（PPTX 进入折叠栏文件入口支持）
 * - DocumentType.fromFileName 对 PPTX 大小写扩展名识别
 */
class DocumentParserRegistryUxr9Test {

    private val registry = DocumentParserRegistry()

    @Test
    fun parserFor_pptx_returns_pptx_parser() {
        assertTrue("pptx 应分发到 PptxDocumentParser", registry.parserFor("presentation.pptx") is PptxDocumentParser)
    }

    @Test
    fun parserFor_pptx_uppercase_extension_returns_pptx_parser() {
        assertTrue("大写 .PPTX 扩展名也应识别", registry.parserFor("演示.PPTX") is PptxDocumentParser)
    }

    @Test
    fun documentType_fromFileName_recognizes_pptx() {
        assertEquals(DocumentType.PPTX, DocumentType.fromFileName("slides.pptx"))
        assertEquals(DocumentType.PPTX, DocumentType.fromFileName("a/b/c.pptx"))
        assertEquals(DocumentType.PPTX, DocumentType.fromFileName("演示.PPTX"))
    }

    @Test
    fun documentType_pptx_extension_literal() {
        assertEquals("pptx", DocumentType.PPTX.extension)
    }
}
