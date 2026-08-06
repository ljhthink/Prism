package io.prism.document

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * OfficeDocumentParser（DOCX/XLSX）单元测试（US-012 验收标准 3）。
 */
class OfficeDocumentParserTest {

    @Test
    fun docx_extracts_paragraph_text() {
        val parser = OfficeDocumentParser("a.docx", DocumentType.DOCX)
        val text = parser.parse(
            TestDocumentFactory.docxByteStream(listOf("第一段", "第二段"))
        )
        assertTrue("应抽取 DOCX 段落文本，实际: [$text]", text.contains("第一段"))
        assertTrue("应抽取 DOCX 段落文本，实际: [$text]", text.contains("第二段"))
    }

    @Test
    fun xlsx_extracts_cell_text() {
        val parser = OfficeDocumentParser("a.xlsx", DocumentType.XLSX)
        val text = parser.parse(
            TestDocumentFactory.xlsxByteStream(
                sheetName = "Sheet1",
                rows = listOf(
                    listOf("姓名", "年龄"),
                    listOf("张三", 30),
                    listOf("李四", 25)
                )
            )
        )
        assertTrue("应抽取表头，实际: [$text]", text.contains("姓名"))
        assertTrue("应抽取姓名，实际: [$text]", text.contains("张三"))
        assertTrue("应抽取数值 30，实际: [$text]", text.contains("30"))
    }

    @Test
    fun xlsx_numeric_integer_no_decimal_point() {
        val parser = OfficeDocumentParser("a.xlsx", DocumentType.XLSX)
        val text = parser.parse(
            TestDocumentFactory.xlsxByteStream(
                sheetName = "S",
                rows = listOf(listOf(30))
            )
        )
        assertTrue("整数 30 不应显示为 30.0，实际: [$text]", text.contains("30"))
        assertEquals("整数应无小数点", false, text.contains("30.0"))
    }

    @Test
    fun xlsx_formula_cell_returns_cached_value() {
        // 公式单元格应输出缓存计算值而非公式表达式（guardrail G-01）
        val parser = OfficeDocumentParser("a.xlsx", DocumentType.XLSX)
        val workbook = XSSFWorkbook()
        val text = try {
            val sheet = workbook.createSheet("S")
            val row = sheet.createRow(0)
            row.createCell(0).setCellValue(10.0)
            row.createCell(1).setCellValue(20.0)
            val formulaCell = row.createCell(2)
            formulaCell.setCellFormula("A1+B1")
            // 强制计算并缓存公式结果
            workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            val out = ByteArrayOutputStream()
            workbook.write(out)
            parser.parse(ByteArrayInputStream(out.toByteArray()))
        } finally {
            workbook.close()
        }
        assertTrue("公式应输出缓存计算值 30，实际: [$text]", text.contains("30"))
        assertEquals("不应输出公式表达式 A1+B1", false, text.contains("A1+B1"))
    }

    @Test
    fun docx_invalid_input_throws() {
        val parser = OfficeDocumentParser("a.docx", DocumentType.DOCX)
        assertThrows(DocumentParseException::class.java) {
            parser.parse("not-a-docx".byteInputStream())
        }
    }

    @Test
    fun constructor_rejects_non_office_type() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficeDocumentParser("a.pdf", DocumentType.PDF)
        }
    }
}