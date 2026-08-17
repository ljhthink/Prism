package io.prism.document

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 测试样例文档工厂 —— 在 JVM 单测中动态生成 PDF / DOCX / XLSX / PPTX 样例。
 *
 * 避免在仓库维护二进制测试资源文件（PDF/Office 文件体积大且不可 diff），
 * 用解析器同源库（PDFBox/POI）在内存中构造样例，再经解析器抽取文本并断言。
 */
object TestDocumentFactory {

    /** 生成含指定文本的 PDF 字节流。 */
    fun pdfByteStream(text: String): ByteArrayInputStream {
        val doc = PDDocument()
        try {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText(text)
                cs.endText()
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return ByteArrayInputStream(out.toByteArray())
        } finally {
            doc.close()
        }
    }

    /** 生成含指定段落文本的 DOCX 字节流。 */
    fun docxByteStream(paragraphs: List<String>): ByteArrayInputStream {
        val doc = XWPFDocument()
        try {
            paragraphs.forEach { doc.createParagraph().createRun().setText(it) }
            val out = ByteArrayOutputStream()
            doc.write(out)
            return ByteArrayInputStream(out.toByteArray())
        } finally {
            doc.close()
        }
    }

    /** 生成含行列数据的 XLSX 字节流。 */
    fun xlsxByteStream(sheetName: String, rows: List<List<Any?>>): ByteArrayInputStream {
        val workbook = XSSFWorkbook()
        try {
            val sheet = workbook.createSheet(sheetName)
            rows.forEachIndexed { r, rowValues ->
                val row = sheet.createRow(r)
                rowValues.forEachIndexed { c, value ->
                    val cell = row.createCell(c)
                    when (value) {
                        is String -> cell.setCellValue(value)
                        is Int -> cell.setCellValue(value.toDouble())
                        is Double -> cell.setCellValue(value)
                        is Boolean -> cell.setCellValue(value)
                        null -> { /* 空单元格 */ }
                    }
                }
            }
            val out = ByteArrayOutputStream()
            workbook.write(out)
            return ByteArrayInputStream(out.toByteArray())
        } finally {
            workbook.close()
        }
    }

    /** 生成含多页文本的 PPTX 字节流（UXR9 US-907 新增）。 */
    fun pptxByteStream(slides: List<List<String>>): ByteArrayInputStream {
        val ppt = XMLSlideShow()
        try {
            slides.forEach { texts ->
                val slide = ppt.createSlide()
                texts.forEach { text ->
                    val shape = slide.createTextBox()
                    shape.setText(text)
                }
            }
            val out = ByteArrayOutputStream()
            ppt.write(out)
            return ByteArrayInputStream(out.toByteArray())
        } finally {
            ppt.close()
        }
    }
}
