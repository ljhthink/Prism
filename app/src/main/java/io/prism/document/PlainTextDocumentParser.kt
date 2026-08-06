package io.prism.document

import java.io.InputStream

/**
 * 纯文本/Markdown/CSV 文档解析器 —— 自研轻量实现，无第三方依赖。
 *
 * US-012 验收标准 4：MD/TXT 用自研解析器。同时覆盖 CSV。
 * ADR-007 5.3：MD/TXT 自研轻量解析器，无第三方依赖。
 *
 * 实现说明：
 * - [DocumentType.TXT] / [DocumentType.CSV]：按 UTF-8 原样读入文本（保持内容不丢失）。
 * - [DocumentType.MD]：按 UTF-8 读入后剥离 Markdown 标记符号（标题 # / 强调星号
 *   与下划线 / 行内代码反引号 / 链接 [text](url) / 无序列表 -），保留纯文本语义，
 *   便于后续切片与检索。
 * - 仅处理 UTF-8 编码；其他编码由调用方在摄入前转换（BR-data-001）。
 * - 解析失败抛出 [DocumentParseException]。
 *
 * @param fileName 待解析文档的文件名，用于错误溯源
 * @param type 文档类型（仅支持 MD / TXT / CSV）
 */
class PlainTextDocumentParser(
    private val fileName: String,
    private val type: DocumentType
) : DocumentParser {

    init {
        require(
            type == DocumentType.MD ||
                type == DocumentType.TXT ||
                type == DocumentType.CSV
        ) {
            "PlainTextDocumentParser 仅支持 MD/TXT/CSV，收到: $type"
        }
    }

    override fun parse(input: InputStream): String {
        return try {
            val text = input.reader(Charsets.UTF_8).use { it.readText() }
            when (type) {
                DocumentType.MD -> stripMarkdown(text)
                else -> text
            }
        } catch (e: Exception) {
            throw DocumentParseException(fileName, e)
        }
    }

    /**
     * 剥离 Markdown 标记符号，保留纯文本。
     *
     * 逐行处理，仅移除不影响语义的装饰性标记，不做富文本渲染。
     */
    private fun stripMarkdown(text: String): String {
        return text.lineSequence().joinToString("\n") { line ->
            var l = line
            // 标题：移除行首 1-6 个 # 及随后的空格
            l = l.replaceFirst(Regex("^#{1,6}\\s+"), "")
            // 无序列表/引用：移除行首 - * + 或 >
            l = l.replaceFirst(Regex("^\\s*[-*+>]\\s+"), "")
            // 行内代码/强调标记：移除成对的反引号、*、_
            l = l.replace("`", "")
            l = l.replace(Regex("\\*+"), "")
            l = l.replace(Regex("_+"), "")
            // 链接 [text](url) -> text
            l = l.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            l
        }
    }
}