package io.prism.document

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF 文档解析器 —— 基于 pdfbox-android（Apache PDFBox 2.0.27 的 Android 移植）抽取文本。
 *
 * US-012 验收标准 2：PDF 抽取文本（[PDFTextStripper]）。
 * ADR-007 5.3 / ADR-032：以 PDFBox 替代 Android PdfRenderer（后者仅渲染位图、无文本抽取 API），
 * 同时规避 pymupdf 的 AGPL 许可证。
 *
 * **R1（UXR10 真机修复，ADR-032）**：原实现用桌面 `org.apache.pdfbox:pdfbox:3.0.8`，
 * 其内部依赖 `java.awt`（如 `java.awt.Point`），Android 无此包 → 真机解析 PDF 抛
 * `ClassNotFoundException: java.awt.Point` 崩溃（JVM 单测通过是桌面 JVM 有 java.awt）。
 * 生产切换 `com.tom-roush:pdfbox-android:2.0.27.0`（java.awt → android.graphics）。
 *
 * 实现说明：
 * - [PDDocument.load] 从 byte[] 加载（PDF 体积受摄入管线单文件限制约束）。
 * - [PDFTextStripper] 抽取全部页面文本。
 * - **依赖 Android 运行时**：pdfbox-android 使用 android.graphics，且需在
 *   `PrismApplication.onCreate` 调用 `PDFBoxResourceLoader.init(context)` 初始化字体/资源加载器。
 *   JVM 单测通过 Robolectric 提供 android.graphics 运行（ADR-032）。
 * - 解析失败（非 PDF 内容/损坏）抛出 [DocumentParseException]。
 *
 * @param fileName 待解析 PDF 的文件名，用于错误溯源
 */
class PdfDocumentParser(private val fileName: String) : DocumentParser {

    override fun parse(input: InputStream): String {
        try {
            // pdfbox-android 2.x：PDDocument.load(byte[])，不支持 InputStream。
            // 先读入内存字节再加载（PDF 体积受摄入管线单文件限制约束）。
            val bytes = input.readBytes()
            val document = PDDocument.load(bytes)
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