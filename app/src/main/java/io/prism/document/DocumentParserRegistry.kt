package io.prism.document

/**
 * 文档解析器注册表 —— 根据文件格式分发到对应解析器。
 *
 * US-012 验收标准 1：按格式分发实现。
 * ADR-007 5.3：PDF→PDFBox，DOCX/XLSX→Apache POI，MD/TXT(CSV)→自研。
 *
 * 用法：
 * ```kotlin
 * val registry = DocumentParserRegistry()
 * val parser = registry.parserFor("report.pdf") // PdfDocumentParser
 * val text = parser.parse(inputStream)
 * ```
 *
 * 线程安全：各解析器均为无状态（仅持有 fileName/type），可安全复用。
 */
class DocumentParserRegistry {

    /**
     * 根据文件名解析出对应格式的解析器。
     *
     * @param fileName 文件名（可含路径），用于识别扩展名与错误溯源
     * @return 对应格式的 [DocumentParser]
     * @throws DocumentParseException 文件扩展名不受支持时抛出
     */
    fun parserFor(fileName: String): DocumentParser {
        val type = DocumentType.fromFileName(fileName)
            ?: throw DocumentParseException(fileName, IllegalArgumentException("不支持的文档格式: $fileName"))
        return when (type) {
            DocumentType.PDF -> PdfDocumentParser(fileName)
            DocumentType.DOCX, DocumentType.XLSX -> OfficeDocumentParser(fileName, type)
            else -> PlainTextDocumentParser(fileName, type)
        }
    }
}