package io.prism.document

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ac-verifier 补充边界/极端用例（US-012 文档解析器）。
 *
 * R1（ADR-032）：PDF 用例经 Robolectric 运行（生产解析器为 pdfbox-android，依赖 android.graphics）；
 * 其余格式（docx/xlsx/txt/md）在 Robolectric 下运行不受影响。夹具由桌面 pdfbox 生成。
 *
 * 覆盖现有测试（DocumentTypeTest / DocumentParserRegistryTest / PdfDocumentParserTest /
 * OfficeDocumentParserTest / PlainTextDocumentParserTest）未触及的边界：
 * - 空输入流（各格式）
 * - 超长输入（TXT 1MB）
 * - 超长文件名分发
 * - 空白文件名 / 无扩展名分发
 * - MD 嵌套链接 / 链接内含括号（记录正则实际行为，断言不崩溃）
 * - XLSX 小数单元格 / BLANK 空单元格
 * - PDF 多页抽取
 *
 * **application 指定**：`application = android.app.Application::class` 避免 Robolectric 按
 * AndroidManifest 加载 [io.prism.PrismApplication]（其 onCreate 初始化 ObjectBox native，
 * Windows JVM 无该 native 库 → LinkageError，全量回归时毒化静态状态导致失败）。本测试
 * 仅需基础 Application 作为 Context 初始化 [PDFBoxResourceLoader]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DocumentParserEdgeCaseTest {

    private val registry = DocumentParserRegistry()

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    // ---------- 空输入流（各格式） ----------

    @Test
    fun pdf_empty_input_throws() {
        assertThrows(DocumentParseException::class.java) {
            registry.parserFor("a.pdf").parse(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun docx_empty_input_throws() {
        assertThrows(DocumentParseException::class.java) {
            registry.parserFor("a.docx").parse(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun xlsx_empty_input_throws() {
        assertThrows(DocumentParseException::class.java) {
            registry.parserFor("a.xlsx").parse(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun txt_empty_input_returns_empty() {
        assertEquals("", registry.parserFor("a.txt").parse(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun md_empty_input_returns_empty() {
        assertEquals("", registry.parserFor("a.md").parse(ByteArrayInputStream(ByteArray(0))))
    }

    // ---------- 超长输入 ----------

    @Test
    fun txt_long_input_returns_verbatim() {
        val longText = "x".repeat(1_000_000)
        assertEquals(longText, registry.parserFor("a.txt").parse(longText.byteInputStream()))
    }

    // ---------- 超长文件名 ----------

    @Test
    fun parserFor_long_filename_dispatch() {
        val longName = "a".repeat(255) + ".pdf"
        assertTrue(DocumentParserRegistry().parserFor(longName) is PdfDocumentParser)
    }

    // ---------- 空白文件名 / 无扩展名 ----------

    @Test
    fun parserFor_blank_filename_throws() {
        assertThrows(DocumentParseException::class.java) {
            registry.parserFor("  ")
        }
    }

    @Test
    fun parserFor_no_extension_throws() {
        assertThrows(DocumentParseException::class.java) {
            registry.parserFor("README")
        }
    }

    // ---------- MD 链接边界（记录实际行为，断言不崩溃） ----------

    @Test
    fun md_nested_link_does_not_crash() {
        val text = registry.parserFor("a.md").parse("[text [inner]](url)".byteInputStream())
        // 链接正则 [^]]+ 遇嵌套方括号不匹配，保留原样；断言不崩溃且保留外层文本
        assertTrue(text.contains("text"))
    }

    @Test
    fun md_link_with_paren_does_not_crash() {
        val text = registry.parserFor("a.md").parse("[text (with paren)](url)".byteInputStream())
        // 链接正则 [^]]+ 遇 [text (with paren)] 匹配 group=text (with paren，尾括号被剥离；断言不崩溃
        assertTrue(text.contains("text"))
    }

    // ---------- XLSX 小数 / BLANK 单元格 ----------

    @Test
    fun xlsx_decimal_cell_keeps_decimal() {
        val text = registry.parserFor("a.xlsx").parse(
            xlsxWithRows(listOf(listOf(3.14)))
        )
        assertTrue("小数应保留小数点，实际: [$text]", text.contains("3.14"))
    }

    @Test
    fun xlsx_blank_cell_does_not_crash() {
        val text = registry.parserFor("a.xlsx").parse(
            xlsxWithRows(listOf(listOf("A", null, "B")))
        )
        assertTrue("应保留非空单元格，实际: [$text]", text.contains("A"))
        assertTrue("应保留非空单元格，实际: [$text]", text.contains("B"))
    }

    // ---------- PDF 多页抽取 ----------

    @Test
    fun pdf_multi_page_extracts_all_pages() {
        val parser = registry.parserFor("a.pdf")
        val text = parser.parse(multiPagePdf(listOf("Page One", "Page Two")))
        assertTrue("应抽取第 1 页，实际: [$text]", text.contains("Page One"))
        assertTrue("应抽取第 2 页，实际: [$text]", text.contains("Page Two"))
    }

    // ---------- 辅助构造 ----------

    /** 构造含给定数值/字符串/空的单行 XLSX 流。 */
    private fun xlsxWithRows(rows: List<List<Any?>>): ByteArrayInputStream {
        val workbook = XSSFWorkbook()
        try {
            val sheet = workbook.createSheet("S")
            rows.forEachIndexed { r, rowValues ->
                val row = sheet.createRow(r)
                rowValues.forEachIndexed { c, value ->
                    val cell = row.createCell(c)
                    when (value) {
                        is String -> cell.setCellValue(value)
                        is Double -> cell.setCellValue(value)
                        is Int -> cell.setCellValue(value.toDouble())
                        null -> { /* BLANK */ }
                        else -> { /* 忽略 */ }
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

    /** 构造含多页文本的 PDF 流。 */
    private fun multiPagePdf(texts: List<String>): ByteArrayInputStream {
        val doc = PDDocument()
        try {
            texts.forEach { t ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 750f)
                    cs.showText(t)
                    cs.endText()
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return ByteArrayInputStream(out.toByteArray())
        } finally {
            doc.close()
        }
    }
}