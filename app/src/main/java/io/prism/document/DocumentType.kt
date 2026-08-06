package io.prism.document

/**
 * 文档格式枚举 —— US-012 文档解析器支持的输入格式。
 *
 * 以文件扩展名（小写，不含点）标识格式，供 [DocumentParserRegistry] 分发解析器。
 * - PDF / DOCX / XLSX 由第三方库解析（ADR-007 5.3）
 * - MD / TXT / CSV 由自研轻量解析器处理
 *
 * @see <a href="../docs/decisions/ADR-007-m3-rag-tech-stack.md">ADR-007 5.3</a>
 */
enum class DocumentType(val extension: String) {
    /** Adobe PDF，Apache PDFBox 抽取文本 */
    PDF("pdf"),
    /** Microsoft Word 2007+，Apache POI XWPF 抽取文本 */
    DOCX("docx"),
    /** Microsoft Excel 2007+，Apache POI XSSF 抽取文本 */
    XLSX("xlsx"),
    /** Markdown，自研解析器（剥离标记符号） */
    MD("md"),
    /** 纯文本，自研解析器（原样返回） */
    TXT("txt"),
    /** 逗号分隔值，自研解析器 */
    CSV("csv");

    companion object {
        /**
         * 根据文件扩展名解析格式。
         *
         * @param fileName 文件名（可含路径）
         * @return 匹配的 [DocumentType]，未知扩展名返回 null
         */
        fun fromFileName(fileName: String): DocumentType? {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot == fileName.lastIndex) return null
            val ext = fileName.substring(dot + 1).trim().lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}