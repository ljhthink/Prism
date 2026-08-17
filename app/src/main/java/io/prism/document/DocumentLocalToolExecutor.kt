package io.prism.document

import android.util.Log
import io.prism.skill.LocalToolExecutor
import io.prism.network.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 文档生成本地工具执行器（O4/PRD UXR8）—— 实现 [LocalToolExecutor] 接口。
 *
 * **背景**：用户需求 O4 要求 docx（制作 Word 文档）与 xlsx（制作表格）能力。
 * 选型结论（D-11）：复用 M3 已引入的 Apache POI（poi-ooxml，零新依赖），
 * 以本地工具形态落地：LLM 输出 Markdown / 表格数据 → POI 生成文件 → 返回保存路径。
 *
 * **工具**（`document__` 命名空间，与 `cross_app__` / `web_search__` 平行）：
 * - `document__create_docx`：参数 filename + markdown → XWPFDocument。
 *   支持 Markdown 子集：`#`/`##`/`###` 标题、`-`/`*` 无序列表、`1.` 有序列表、普通段落。
 *   行内格式（粗体/斜体/代码）不解析（控制实现边界，Karpathy 简洁原则）。
 * - `document__create_xlsx`：参数 filename + sheets（[{name, rows: [[cell,...]]}]）→
 *   XSSFWorkbook。cell 支持 string / number / boolean。
 *
 * **保存位置**：注入的 [baseDir]（PrismApplication 传
 * `getExternalFilesDir(DIRECTORY_DOCUMENTS)`，降级 `filesDir`）。
 * 应用外部私有目录：无需存储权限、卸载即清理、用户可通过文件管理器访问。
 *
 * **降级策略**（与 MCP callTool / WebSearchLocalToolExecutor 一致）：所有失败场景
 * 返回描述性字符串（而非抛异常），由 SkillExecutor 作为 tool result 回灌给 LLM。
 *
 * **协程取消**（BR-error-handling-007）：CancellationException 重抛，不吞。
 *
 * **安全边界**：
 * - 文件名清洗 [sanitizeFilename]（防路径穿越 `..` / 分隔符 / Windows 非法字符）
 * - 文件内容长度上限 [MAX_CONTENT_LEN]（防 token 溢出式超长输入拖垮内存）
 * - 生成在 Dispatchers.IO（POI 同步 IO 不阻塞主线程）
 *
 * **可测性**（BR-testing-004）：baseDir 注入解耦，纯 JVM 测试传临时目录即可验证。
 *
 * @param baseDir 文档保存根目录（测试注入临时目录）
 */
class DocumentLocalToolExecutor(
    private val baseDir: File
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean =
        toolName == TOOL_CREATE_DOCX || toolName == TOOL_CREATE_XLSX

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String =
        withContext(Dispatchers.IO) {
            try {
                when (toolName) {
                    TOOL_CREATE_DOCX -> executeCreateDocx(arguments)
                    TOOL_CREATE_XLSX -> executeCreateXlsx(arguments)
                    else -> "未知文档工具: $toolName"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // BR-error-handling-004：失败降级为描述性字符串（回灌 LLM），不抛异常。
                // 文案不含 e.message（POI/IO 异常 message 可能含内部路径，
                // 对齐 SkillExecutor sanitizeErrorMessage 脱敏基线，CWE-209）
                Log.w(TAG, "document tool failed: ${e.javaClass.simpleName}")
                "文档生成失败（${e.javaClass.simpleName}），请检查参数后重试"
            }
        }

    // ==================== docx ====================

    /**
     * 执行 `document__create_docx`：Markdown 子集 → XWPFDocument → 保存 .docx。
     *
     * @return 成功文案（含保存路径）；失败降级文案
     */
    private fun executeCreateDocx(arguments: Map<String, Any?>): String {
        val filename = sanitizeFilename(arguments["filename"]?.toString())
            ?: return "缺少必需参数 filename（或文件名仅含非法字符）"
        val markdown = arguments["markdown"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return "缺少必需参数 markdown（文档正文，Markdown 格式）"
        if (markdown.length > MAX_CONTENT_LEN) {
            return "文档内容过长（>${MAX_CONTENT_LEN} 字符），请分多次生成"
        }

        val blocks = parseMarkdownBlocks(markdown)
        if (blocks.isEmpty()) return "文档内容为空，未生成文件"

        val file = targetFile(filename, "docx")
        file.parentFile?.mkdirs()
        org.apache.poi.xwpf.usermodel.XWPFDocument().use { doc ->
            for (block in blocks) {
                val paragraph = doc.createParagraph()
                when (block) {
                    is MdBlock.Heading -> {
                        paragraph.style = when (block.level) {
                            1 -> "Heading1"; 2 -> "Heading2"; else -> "Heading3"
                        }
                        paragraph.createRun().setText(block.text)
                    }
                    is MdBlock.ListItem -> {
                        // 缩进模拟列表层级（POI 内建编号配置复杂，简化为符号前缀，Karpathy 简洁原则）
                        paragraph.indentationLeft = 360
                        paragraph.createRun().setText("${if (block.ordered) "" else "• "}${block.text}")
                    }
                    is MdBlock.Paragraph -> paragraph.createRun().setText(block.text)
                }
            }
            file.outputStream().use { doc.write(it) }
        }
        return "已生成 Word 文档：${file.absolutePath}（共 ${blocks.size} 个段落块）"
    }

    // ==================== xlsx ====================

    /**
     * 执行 `document__create_xlsx`：sheets 数据 → XSSFWorkbook → 保存 .xlsx。
     *
     * **参数结构**：`sheets: [{name: "Sheet1", rows: [[cell, ...], ...]}, ...]`
     * （cell 为 string / number / boolean，null 跳过）。
     */
    private fun executeCreateXlsx(arguments: Map<String, Any?>): String {
        val filename = sanitizeFilename(arguments["filename"]?.toString())
            ?: return "缺少必需参数 filename（或文件名仅含非法字符）"
        @Suppress("UNCHECKED_CAST")
        val sheets = arguments["sheets"] as? List<Map<String, Any?>>
            ?: return "缺少必需参数 sheets（数组，每项含 name 与 rows 二维数组）"
        if (sheets.isEmpty()) return "sheets 不能为空"
        // G-04 修复（guardrail TKN-UXR8-B2-GUARDRAIL-001）：工作表数量上限。
        // MAX_TOTAL_CELLS 只约束单元格数，空 rows 的 sheet 可绕过（LLM 传 N 个
        // `rows: []` 时 totalCells=0 全通过，POI 每 sheet 一个 XML part 全内存构建）。
        if (sheets.size > MAX_SHEETS) {
            return "工作表过多（${sheets.size} 个 > 上限 $MAX_SHEETS），请拆分为多个文件生成"
        }
        // guardrail TKN-UXR8-GUARDRAIL-PRECOMMIT-001 建议#1：总单元格数上限
        //（与 docx MAX_CONTENT_LEN 对等的资源边界，防 LLM 超大二维数组触发 OOM）
        val totalCells = sheets.sumOf { sheet ->
            @Suppress("UNCHECKED_CAST")
            (sheet["rows"] as? List<List<Any?>>)?.sumOf { it.size } ?: 0
        }
        if (totalCells > MAX_TOTAL_CELLS) {
            return "表格过大（${totalCells} 单元格 > 上限 $MAX_TOTAL_CELLS），请拆分为多个文件或精简数据后分次生成"
        }

        val file = targetFile(filename, "xlsx")
        file.parentFile?.mkdirs()
        org.apache.poi.xssf.usermodel.XSSFWorkbook().use { workbook ->
            for ((sheetIndex, sheet) in sheets.withIndex()) {
                val sheetName = sheet["name"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Sheet${sheetIndex + 1}"
                val sheetOut = workbook.createSheet(
                    // G2-04：全引号名清洗后可能为空串（POI 拒绝），兜底默认名
                    sanitizeSheetName(sheetName).ifEmpty { "Sheet${sheetIndex + 1}" }
                )
                @Suppress("UNCHECKED_CAST")
                val rows = sheet["rows"] as? List<List<Any?>> ?: emptyList()
                for ((rowIdx, row) in rows.withIndex()) {
                    val rowOut = sheetOut.createRow(rowIdx)
                    for ((colIdx, cell) in row.withIndex()) {
                        val cellOut = rowOut.createCell(colIdx)
                        when (cell) {
                            is Number -> cellOut.setCellValue(cell.toDouble())
                            is Boolean -> cellOut.setCellValue(cell)
                            null -> Unit // 空单元格
                            // G-09 加固（guardrail TKN-UXR8-B2-GUARDRAIL-001，OWASP
                            // 生成文件基线）：以公式触发字符开头的字符串加 `'` 前缀，
                            // 消除复制/导出 CSV 后再次进入公式上下文的残余注入面
                            else -> cellOut.setCellValue(sanitizeCellText(cell.toString()))
                        }
                    }
                }
            }
            file.outputStream().use { workbook.write(it) }
        }
        return "已生成 Excel 表格：${file.absolutePath}（共 ${sheets.size} 个工作表）"
    }

    // ==================== 纯函数（可测） ====================

    /**
     * 目标文件：baseDir + 清洗后文件名 + 后缀（已存在时自动追加序号，不覆盖）。
     */
    internal fun targetFile(filename: String, ext: String): File {
        val direct = File(baseDir, "$filename.$ext")
        if (!direct.exists()) return direct
        var seq = 1
        while (true) {
            val candidate = File(baseDir, "$filename($seq).$ext")
            if (!candidate.exists()) return candidate
            seq++
        }
    }

    /**
     * 文件名清洗（纯函数，可测）。
     *
     * 规则：trim → 替换路径分隔符 / Windows 非法字符（`\ / : * ? " < > |`）为 `_` →
     * 替换 `..` 为 `_`（路径穿越防御）→ 长度截断 80 → 空 / `.` 返回 null。
     *
     * @return 合法文件名（不含后缀）；非法返回 null
     */
    internal fun sanitizeFilename(name: String?): String? {
        if (name == null) return null
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("..", "_")
            .take(MAX_FILENAME_LEN)
            .trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "." }
    }

    /**
     * 工作表名称清洗（纯函数，可测，G-04 配套 + G2-04 加固）。
     *
     * 规则：替换 Excel sheet 名非法字符（`: \ / ? * [ ]`）为 `_` → 截断 31 字符
     * （Excel 硬限制）→ 修剪首尾单引号（POI 禁止 sheet 名以 `'` 开头/结尾，
     * G2-04 guardrail TKN-UXR8-B2-GUARDRAIL-002）。空名不可达：调用点 blank
     * 已兜底 SheetN，字符替换不减少长度。
     */
    internal fun sanitizeSheetName(name: String): String =
        name.replace(Regex("[:\\\\/?*\\[\\]]"), "_")
            .take(31)
            .trim('\'')

    /**
     * 单元格文本公式注入防御（纯函数，可测，G-09）。
     *
     * OWASP 生成文件基线：以公式触发字符（`= + - @` 及制表/回车）开头的字符串
     * 加 `'` 前缀，消除复制/导出 CSV 后再次进入公式上下文的注入面。
     * 普通文本（含中部含 `=` 的）原样返回。
     */
    internal fun sanitizeCellText(text: String): String =
        if (text.isNotEmpty() && text[0] in FORMULA_TRIGGER_CHARS) "'$text" else text

    /**
     * 解析 Markdown 子集为文档块序列（纯函数，可测）。
     *
     * 支持块类型：标题（# ## ###）、无序列表（- *）、有序列表（1. 2. ...）、普通段落。
     * 不支持的行（表格/代码块/引用等）按普通段落文本保留（信息不丢失）。
     */
    internal fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        for (rawLine in markdown.lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed.startsWith("### ") -> blocks.add(MdBlock.Heading(3, trimmed.removePrefix("### ").trim()))
                trimmed.startsWith("## ") -> blocks.add(MdBlock.Heading(2, trimmed.removePrefix("## ").trim()))
                trimmed.startsWith("# ") -> blocks.add(MdBlock.Heading(1, trimmed.removePrefix("# ").trim()))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                    blocks.add(MdBlock.ListItem(false, trimmed.substring(2).trim()))
                ORDERED_LIST_REGEX.containsMatchIn(trimmed) ->
                    blocks.add(MdBlock.ListItem(true, ORDERED_LIST_REGEX.replace(trimmed, "")))
                else -> blocks.add(MdBlock.Paragraph(trimmed))
            }
        }
        return blocks
    }

    /** Markdown 解析块模型（internal 供测试断言）。 */
    internal sealed class MdBlock {
        data class Heading(val level: Int, val text: String) : MdBlock()
        data class ListItem(val ordered: Boolean, val text: String) : MdBlock()
        data class Paragraph(val text: String) : MdBlock()
    }

    companion object {
        /** 文档工具命名空间前缀。 */
        const val NAMESPACE_PREFIX = "document__"

        /** Word 文档生成工具名。 */
        const val TOOL_CREATE_DOCX = "${NAMESPACE_PREFIX}create_docx"

        /** Excel 表格生成工具名。 */
        const val TOOL_CREATE_XLSX = "${NAMESPACE_PREFIX}create_xlsx"

        /** 文件名最大长度（清洗后）。 */
        internal const val MAX_FILENAME_LEN = 80

        /** 文档内容最大长度（防超长输入拖垮内存 / token 溢出）。 */
        internal const val MAX_CONTENT_LEN = 100_000

        /**
         * xlsx 总单元格数上限（guardrail TKN-UXR8-GUARDRAIL-PRECOMMIT-001 建议#1）：
         * 与 docx [MAX_CONTENT_LEN] 对等的资源边界，防超大二维数组全内存构建触发 OOM。
         */
        internal const val MAX_TOTAL_CELLS = 5_000

        /**
         * xlsx 工作表数量上限（G-04，guardrail TKN-UXR8-B2-GUARDRAIL-001）：
         * 补齐 MAX_TOTAL_CELLS 的资源边界缺口——空 `rows` 的 sheet 不消耗单元格预算，
         * 无数量上限时 LLM 可生成任意多 sheet（POI 每 sheet 一个 XML part 全内存构建）。
         */
        internal const val MAX_SHEETS = 20

        /** G-09：公式触发字符（OWASP 生成文件基线：`= + - @` 及制表/回车首字符）。 */
        private val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t', '\r')

        /** 有序列表行（`1. ` / `23. `）。 */
        private val ORDERED_LIST_REGEX = Regex("^\\d+\\.\\s+")

        private const val TAG = "DocumentTool"

        /**
         * 构建文档工具定义（供 ConversationViewModel.buildTools 合并）。
         *
         * additionalProperties=false（严格参数，避免 LLM 传未知字段）。
         */
        fun buildToolDefinitions(): List<ToolDefinition> = listOf(
            ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_CREATE_DOCX,
                    description = "将 Markdown 内容生成为 Word 文档（.docx）并保存到本机，返回保存路径。" +
                        "支持标题（#/##/###）、列表、段落。当用户要求制作 Word 文档、报告、方案时调用。" +
                        "生成后告知用户保存路径。",
                    parameters = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(
                                mapOf(
                                    "filename" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("string"),
                                            "description" to JsonPrimitive("文件名（不含 .docx 后缀）")
                                        )
                                    ),
                                    "markdown" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("string"),
                                            "description" to JsonPrimitive("文档正文，Markdown 格式（支持 #/##/### 标题、- 列表、1. 有序列表、段落）")
                                        )
                                    )
                                )
                            ),
                            "required" to JsonArray(listOf(JsonPrimitive("filename"), JsonPrimitive("markdown"))),
                            "additionalProperties" to JsonPrimitive(false)
                        )
                    )
                )
            ),
            ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_CREATE_XLSX,
                    description = "将表格数据生成为 Excel 文件（.xlsx）并保存到本机，返回保存路径。" +
                        "支持多工作表（sheets 数组），单元格支持文本/数字/布尔。当用户要求制作表格、" +
                        "报表、数据清单时调用。生成后告知用户保存路径。",
                    parameters = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(
                                mapOf(
                                    "filename" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("string"),
                                            "description" to JsonPrimitive("文件名（不含 .xlsx 后缀）"
                                            )
                                        )
                                    ),
                                    "sheets" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("array"),
                                            "description" to JsonPrimitive("工作表数组，每项含 name（表名）与 rows（二维数组，首行建议为表头）"),
                                            "items" to JsonObject(
                                                mapOf(
                                                    "type" to JsonPrimitive("object"),
                                                    "properties" to JsonObject(
                                                        mapOf(
                                                            "name" to JsonObject(
                                                                mapOf("type" to JsonPrimitive("string"))
                                                            ),
                                                            "rows" to JsonObject(
                                                                mapOf(
                                                                    "type" to JsonPrimitive("array"),
                                                                    "description" to JsonPrimitive("行数组，每行为单元格数组（string/number/boolean）"
                                                                    )
                                                                )
                                                            )
                                                        )
                                                    ),
                                                    "required" to JsonArray(
                                                        listOf(JsonPrimitive("name"), JsonPrimitive("rows"))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            "required" to JsonArray(listOf(JsonPrimitive("filename"), JsonPrimitive("sheets"))),
                            "additionalProperties" to JsonPrimitive(false)
                        )
                    )
                )
            )
        )
    }
}
