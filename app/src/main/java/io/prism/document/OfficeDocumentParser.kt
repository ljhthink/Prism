package io.prism.document

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream

/**
 * Office 文档解析器 —— 基于 Apache POI poi-ooxml 5.5.1 抽取 DOCX / XLSX 文本。
 *
 * US-012 验收标准 3：DOCX/XLSX 用 Apache POI 抽取文本。
 * ADR-007 5.3：用 [XWPFWordExtractor]（Word）/ [XSSFWorkbook]（Excel）抽取文本，
 * RAG 仅需文本，不依赖 POI 缺失的 java.awt 功能。
 *
 * 实现说明：
 * - 按 [DocumentType] 分发：DOCX 用 XWPF，XLSX 用 XSSF。
 * - XLSX 遍历所有工作表的所有单元格，行间用换行、列间用制表符分隔，还原表格结构。
 * - POI 会自动关闭输入流；解析失败抛出 [DocumentParseException]。
 * - 纯 JVM 类库，可 JVM 单元测试。
 *
 * @param fileName 待解析文档的文件名，用于错误溯源
 * @param type 文档类型（仅支持 DOCX / XLSX）
 */
class OfficeDocumentParser(
    private val fileName: String,
    private val type: DocumentType
) : DocumentParser {

    init {
        require(type == DocumentType.DOCX || type == DocumentType.XLSX) {
            "OfficeDocumentParser 仅支持 DOCX/XLSX，收到: $type"
        }
    }

    override fun parse(input: InputStream): String {
        return when (type) {
            DocumentType.DOCX -> parseDocx(input)
            DocumentType.XLSX -> parseXlsx(input)
            else -> error("Unreachable: $type") // init 已约束
        }
    }

    private fun parseDocx(input: InputStream): String {
        try {
            XWPFDocument(input).use { doc ->
                return XWPFWordExtractor(doc).text
            }
        } catch (e: Exception) {
            throw DocumentParseException(fileName, e)
        }
    }

    private fun parseXlsx(input: InputStream): String {
        try {
            XSSFWorkbook(input).use { workbook ->
                val sb = StringBuilder()
                for (sheet in workbook) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    for (row in sheet) {
                        val cells = row.map { cell ->
                            // 单元格取值：字符串/数值/布尔/公式缓存值，统一转字符串。
                            // 公式单元格读取缓存计算结果（cachedFormulaResultType），避免输出公式表达式
                            // 造成 RAG 数据失真（guardrail G-01）。
                            val type =
                                if (cell.cellType == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                                    cell.cachedFormulaResultType
                                } else {
                                    cell.cellType
                                }
                            when (type) {
                                org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
                                org.apache.poi.ss.usermodel.CellType.NUMERIC -> formatNumeric(cell.numericCellValue)
                                org.apache.poi.ss.usermodel.CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                else -> ""
                            }
                        }
                        sb.append(cells.joinToString("\t"))
                        sb.append('\n')
                    }
                }
                return sb.toString()
            }
        } catch (e: Exception) {
            throw DocumentParseException(fileName, e)
        }
    }

    private fun formatNumeric(value: Double): String {
        // 整数值去掉小数点，避免 1.0 显示为 1.0
        return if (value == Math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }
}