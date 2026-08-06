package io.prism.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF 文档解析器 —— 基于 Apache PDFBox 3.0.8 抽取文本。
 *
 * US-012 验收标准 2：PDF 用 Apache PDFBox 抽取文本（[PDFTextStripper]）。
 * ADR-007 5.3：以 PDFBox 替代 Android PdfRenderer（后者仅渲染位图、无文本抽取 API），
 * 同时规避 pymupdf 的 AGPL 许可证。
 *
 * 实现说明：
 * - 使用 [PDFParser] 解析输入流（支持 Android 无文件路径场景），经 [Loader.loadPDF] 加载。
 * - [PDFTextStripper] 抽取全部页面文本，按页面换行分隔。
 * - 纯 JVM 类库，不依赖 Android Context，可 JVM 单元测试。
 * - 解析失败（非 PDF 内容/损坏）抛出 [DocumentParseException]。
 *
 * @param fileName 待解析 PDF 的文件名，用于错误溯源
 */
class PdfDocumentParser(private val fileName: String) : DocumentParser {

    override fun parse(input: InputStream): String {
        try {
            // PDFBox 3.x：Loader.loadPDF 接受 byte[]/File/RandomAccessRead，不支持 InputStream。
            // 先读入内存字节再加载（PDF 体积受摄入管线单文件限制约束）。
            val bytes = input.readBytes()
            val document = Loader.loadPDF(bytes)
            return try {
                val stripper = PDFTextStripper()
                stripper.getText(document)
            } finally {
                document.close()
            }
        } catch (e: Exception) {
            throw DocumentParseException(fileName, e)
        }
    }
}