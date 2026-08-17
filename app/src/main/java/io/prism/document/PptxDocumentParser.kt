package io.prism.document

import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.io.InputStream

/**
 * PowerPoint 解析器 —— 基于 Apache POI poi-ooxml 的 XSLF 抽取 PPTX 文本（UXR9 US-907 新增）。
 *
 * **背景**：US-907 折叠栏「文件」入口支持 PPTX。知识库既有 PDF/DOCX/XLSX 解析器复用，
 * PPTX 需新增——POI OOXML 模块已含 XSLF（XMLSlideShow），零新增依赖。
 *
 * **抽取规则**：
 * - 遍历全部幻灯片，每页标注 `[第 N 页]` 标题
 * - 每页遍历形状：文本形状（[XSLFTextShape]）取其文本；表格（[XSLFTable]）逐行逐单元格
 *   拼接（列间制表符、行间换行），还原表格结构
 * - 页间用换行分隔，保留大纲结构
 *
 * **线程安全**：无状态（仅持有 fileName），可安全复用。
 * **错误处理**：解析失败抛 [DocumentParseException]（与既有解析器一致）。
 *
 * @param fileName 待解析文档文件名，用于错误溯源
 */
class PptxDocumentParser(
    private val fileName: String
) : DocumentParser {

    override fun parse(input: InputStream): String {
        try {
            XMLSlideShow(input).use { ppt ->
                val sb = StringBuilder()
                ppt.slides.forEachIndexed { index, slide ->
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("[第 ${index + 1} 页]\n")
                    appendSlideText(sb, slide)
                }
                return sb.toString().trimEnd()
            }
        } catch (e: Exception) {
            throw DocumentParseException(fileName, e)
        }
    }

    private fun appendSlideText(sb: StringBuilder, slide: XSLFSlide) {
        for (shape: XSLFShape in slide.shapes) {
            when (shape) {
                is XSLFTextShape -> {
                    val text = shape.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                        sb.append(text)
                    }
                }
                is XSLFTable -> appendTableText(sb, shape)
                // 其余形状（图片/图表/分组等）不抽取文本（RAG 仅需文本内容）
            }
        }
    }

    private fun appendTableText(sb: StringBuilder, table: XSLFTable) {
        for (row in table) {
            val cells = row.mapNotNull { cell -> cell.text?.trim() }
            if (cells.isEmpty()) continue
            sb.append('\n')
            sb.append(cells.joinToString("\t"))
        }
    }
}
