package io.prism.document

import io.prism.document.DocumentLocalToolExecutor.MdBlock
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DocumentLocalToolExecutor 单元测试（O4/PRD UXR8，BR-testing-004 纯 JVM）。
 *
 * **覆盖目标**：
 * - [DocumentLocalToolExecutor.parseMarkdownBlocks]：标题/列表/有序列表/段落/空行/未知语法保留
 * - [DocumentLocalToolExecutor.sanitizeFilename]：非法字符替换 / 路径穿越 / 空 / 长度截断
 * - [DocumentLocalToolExecutor.targetFile]：同名不覆盖，追加序号
 * - [DocumentLocalToolExecutor.handles] / [DocumentLocalToolExecutor.execute]：
 *   docx / xlsx 真实生成（POI 写入临时目录 + 读回验证）/ 参数缺失降级
 * - [DocumentLocalToolExecutor.buildToolDefinitions]：工具名与 schema 基本结构
 */
class DocumentLocalToolExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newExecutor(): DocumentLocalToolExecutor =
        DocumentLocalToolExecutor(tempFolder.newFolder("docs"))

    // ==================== parseMarkdownBlocks ====================

    @Test
    fun `parseMarkdownBlocks parses headings levels 1 to 3`() {
        val blocks = newExecutor().parseMarkdownBlocks(
            "# 一级\n## 二级\n### 三级"
        )
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(1, "一级"), blocks[0])
        assertEquals(MdBlock.Heading(2, "二级"), blocks[1])
        assertEquals(MdBlock.Heading(3, "三级"), blocks[2])
    }

    @Test
    fun `parseMarkdownBlocks parses unordered and ordered lists`() {
        val blocks = newExecutor().parseMarkdownBlocks(
            "- 苹果\n* 香蕉\n1. 第一步\n2. 第二步"
        )
        assertEquals(4, blocks.size)
        assertEquals(MdBlock.ListItem(false, "苹果"), blocks[0])
        assertEquals(MdBlock.ListItem(false, "香蕉"), blocks[1])
        assertEquals(MdBlock.ListItem(true, "第一步"), blocks[2])
        assertEquals(MdBlock.ListItem(true, "第二步"), blocks[3])
    }

    @Test
    fun `parseMarkdownBlocks skips blank lines and keeps unknown syntax as paragraph`() {
        val blocks = newExecutor().parseMarkdownBlocks(
            "第一段\n\n> 引用内容\n| 表格 | 行 |"
        )
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Paragraph("第一段"), blocks[0])
        // 不支持的语法（引用/表格）按普通段落保留，信息不丢失
        assertEquals(MdBlock.Paragraph("> 引用内容"), blocks[1])
        assertEquals(MdBlock.Paragraph("| 表格 | 行 |"), blocks[2])
    }

    // ==================== sanitizeFilename ====================

    @Test
    fun `sanitizeFilename replaces illegal chars and path traversal`() {
        val executor = newExecutor()
        assertEquals("a_b", executor.sanitizeFilename("a/b"))
        assertEquals("a_b", executor.sanitizeFilename("a\\b"))
        assertEquals("_", executor.sanitizeFilename(".."))
        assertEquals("报告_2026", executor.sanitizeFilename("报告:2026"))
    }

    @Test
    fun `sanitizeFilename returns null for blank or dot only`() {
        val executor = newExecutor()
        assertNull(executor.sanitizeFilename(null))
        assertNull(executor.sanitizeFilename("   "))
        assertNull(executor.sanitizeFilename("."))
        assertNull(executor.sanitizeFilename(""))
    }

    @Test
    fun `sanitizeFilename truncates to max length`() {
        val result = newExecutor().sanitizeFilename("x".repeat(200))
        assertNotNull(result)
        assertTrue(
            "清洗后长度应 ≤ ${DocumentLocalToolExecutor.MAX_FILENAME_LEN}",
            result!!.length <= DocumentLocalToolExecutor.MAX_FILENAME_LEN
        )
    }

    // ==================== targetFile ====================

    @Test
    fun `targetFile appends sequence when file exists`() {
        val dir = tempFolder.newFolder("seq")
        val executor = DocumentLocalToolExecutor(dir)
        val first = executor.targetFile("报告", "docx")
        first.parentFile?.mkdirs()
        first.writeText("x")
        val second = executor.targetFile("报告", "docx")
        assertTrue("同名时应追加序号：${second.name}", second.name.startsWith("报告(1)."))
        assertFalse(second.exists())
    }

    // ==================== handles / execute：docx ====================

    @Test
    fun `handles matches document tools only`() {
        val executor = newExecutor()
        assertTrue(executor.handles("document__create_docx"))
        assertTrue(executor.handles("document__create_xlsx"))
        assertFalse(executor.handles("web_search__search"))
        assertFalse(executor.handles("document__other"))
    }

    @Test
    fun `execute create_docx generates readable file`() = runBlocking {
        val executor = newExecutor()
        val result = executor.execute(
            "document__create_docx",
            mapOf(
                "filename" to "测试报告",
                "markdown" to "# 标题\n\n正文第一段\n\n- 要点一\n- 要点二\n\n1. 步骤一\n2. 步骤二"
            )
        )
        assertTrue("应返回成功文案：$result", result.startsWith("已生成 Word 文档"))
        val docxFile = executor.targetFile("测试报告", "docx")
        // targetFile 对已存在文件会返回 (1) 后缀，直接扫描目录
        val generated = docxFile.parentFile!!.listFiles()!!.filter { it.name.startsWith("测试报告") }
        assertEquals(1, generated.size)
        // 读回验证段落结构
        XWPFDocument(generated[0].inputStream()).use { doc ->
            assertEquals(6, doc.paragraphs.size) // 6 个块
            assertTrue(doc.paragraphs[0].text.contains("标题"))
        }
    }

    @Test
    fun `execute create_docx degrades on missing params`() = runBlocking {
        val executor = newExecutor()
        assertTrue(
            executor.execute("document__create_docx", mapOf("filename" to "a")).contains("缺少必需参数")
        )
        assertTrue(
            executor.execute("document__create_docx", mapOf("markdown" to "# x")).contains("缺少必需参数")
        )
    }

    // ==================== handles / execute：xlsx ====================

    @Test
    fun `execute create_xlsx generates readable workbook`() = runBlocking {
        val executor = newExecutor()
        val result = executor.execute(
            "document__create_xlsx",
            mapOf(
                "filename" to "数据表",
                "sheets" to listOf(
                    mapOf(
                        "name" to "Sheet1",
                        "rows" to listOf(
                            listOf("姓名", "年龄"),
                            listOf("张三", 25),
                            listOf("李四", 30.5)
                        )
                    )
                )
            )
        )
        assertTrue("应返回成功文案：$result", result.startsWith("已生成 Excel 表格"))
        val generated = executor.targetFile("数据表", "xlsx")
            .parentFile!!.listFiles()!!.filter { it.name.startsWith("数据表") }
        assertEquals(1, generated.size)
        // 读回验证单元格
        XSSFWorkbook(generated[0].inputStream()).use { wb ->
            assertEquals(1, wb.numberOfSheets)
            val sheet = wb.getSheet("Sheet1")
            assertEquals("姓名", sheet.getRow(0).getCell(0).stringCellValue)
            assertEquals(25.0, sheet.getRow(1).getCell(1).numericCellValue, 0.001)
            assertEquals(30.5, sheet.getRow(2).getCell(1).numericCellValue, 0.001)
        }
    }

    @Test
    fun `execute create_xlsx degrades on missing sheets`() = runBlocking {
        val executor = newExecutor()
        assertTrue(
            executor.execute(
                "document__create_xlsx",
                mapOf("filename" to "a", "sheets" to emptyList<Any>())
            ).contains("不能为空")
        )
    }

    @Test
    fun `execute create_xlsx rejects oversized cell budget`() = runBlocking {
        // guardrail TKN-UXR8-GUARDRAIL-PRECOMMIT-001 建议#1：总单元格数超限降级
        val executor = newExecutor()
        val bigRows = (1..600).map { listOf("v$it") } // 600 行 × 1 列 × 10 sheet = 6000 > 5000
        val sheets = (1..10).map { mapOf("name" to "S$it", "rows" to bigRows) }
        val result = executor.execute(
            "document__create_xlsx",
            mapOf("filename" to "big", "sheets" to sheets)
        )
        assertTrue("应拒绝超大表格：$result", result.contains("表格过大"))
        val generated = executor.targetFile("big", "xlsx")
            .parentFile!!.listFiles()!!.filter { it.name.startsWith("big") }
        assertTrue("不应生成文件", generated.isEmpty())
    }

    // ==================== G-04/G-07/G-09（guardrail TKN-UXR8-B2-GUARDRAIL-001） ====================

    @Test
    fun `sanitizeFilename blocks traversal vector variants`() = runBlocking {
        // guardrail §2.1 八类攻击向量的回归锚点：未来重构弱化替换顺序时此测试必须失败
        val executor = newExecutor()
        // 相对穿越：分隔符先清（2 个 / → 2 个 _），残余 .. 再替换（2 组 → 2 个 _）
        assertEquals("____secret", executor.sanitizeFilename("../../secret"))
        assertEquals("__evil", executor.sanitizeFilename("..\\evil"))
        // `...` 变体：非重叠替换后无路径语义
        assertEquals("_.", executor.sanitizeFilename("..."))
        assertEquals("a_b", executor.sanitizeFilename("a..b"))
        // 绝对路径：首字符分隔符替换为相对段
        assertEquals("_etc_passwd", executor.sanitizeFilename("/etc/passwd"))
        // Windows 盘符 / ADS 冒号
        assertEquals("C__evil", executor.sanitizeFilename("C:\\evil"))
        // 清洗结果不含分隔符与 ..（不变式断言）
        listOf("../../secret", "..\\evil", "...", "a..b", "/etc/passwd", "C:\\evil").forEach { input ->
            val cleaned = executor.sanitizeFilename(input)!!
            assertFalse("清洗后不应含路径分隔符：$cleaned", cleaned.contains('/') || cleaned.contains('\\'))
            assertFalse("清洗后不应含 ..：$cleaned", cleaned.contains(".."))
        }
    }

    @Test
    fun `execute create_docx rejects oversized content`() = runBlocking {
        // G-07 缺口1：docx MAX_CONTENT_LEN 拒绝路径（与 xlsx cell 预算对称）
        val executor = newExecutor()
        val huge = "x".repeat(DocumentLocalToolExecutor.MAX_CONTENT_LEN + 1)
        val result = executor.execute(
            "document__create_docx",
            mapOf("filename" to "huge", "markdown" to huge)
        )
        assertTrue("应拒绝超长内容：$result", result.contains("文档内容过长"))
        val generated = executor.targetFile("huge", "docx")
            .parentFile!!.listFiles()!!.filter { it.name.startsWith("huge") }
        assertTrue("不应生成文件", generated.isEmpty())
    }

    @Test
    fun `execute create_xlsx rejects too many sheets`() = runBlocking {
        // G-04：空 rows 的 sheet 不消耗 cell 预算，数量上限独立拦截
        val executor = newExecutor()
        val sheets = (1..DocumentLocalToolExecutor.MAX_SHEETS + 1).map {
            mapOf("name" to "S$it", "rows" to emptyList<Any>())
        }
        val result = executor.execute(
            "document__create_xlsx",
            mapOf("filename" to "many", "sheets" to sheets)
        )
        assertTrue("应拒绝过多工作表：$result", result.contains("工作表过多"))
        val generated = executor.targetFile("many", "xlsx")
            .parentFile!!.listFiles()!!.filter { it.name.startsWith("many") }
        assertTrue("不应生成文件", generated.isEmpty())
    }

    @Test
    fun `sanitizeSheetName replaces illegal chars and truncates to 31`() {
        // G-07 缺口3：sheet 名清洗锚点
        val executor = newExecutor()
        assertEquals("a_b_c", executor.sanitizeSheetName("a:b/c"))
        assertEquals("d_e_f", executor.sanitizeSheetName("d?e*f"))
        assertEquals("g_h", executor.sanitizeSheetName("g\\h"))
        assertEquals("i_j_k", executor.sanitizeSheetName("i[j]k"))
        assertEquals(31, executor.sanitizeSheetName("长".repeat(50)).length)
        // G2-04：POI 禁止首尾单引号，修剪（全引号 → 空串，由调用点兜底默认名）
        assertEquals("abc", executor.sanitizeSheetName("'abc'"))
        assertEquals("a'b", executor.sanitizeSheetName("a'b"))
        assertEquals("", executor.sanitizeSheetName("'''"))
    }

    @Test
    fun `sanitizeCellText prefixes formula trigger characters`() {
        // G-09：OWASP 生成文件基线——公式触发字符开头加 ' 前缀
        val executor = newExecutor()
        assertEquals("'=1+1", executor.sanitizeCellText("=1+1"))
        assertEquals("'+cmd", executor.sanitizeCellText("+cmd"))
        assertEquals("'-SUM(A1)", executor.sanitizeCellText("-SUM(A1)"))
        assertEquals("'@HYPERLINK(x)", executor.sanitizeCellText("@HYPERLINK(x)"))
        assertEquals("'\tTabStart", executor.sanitizeCellText("\tTabStart"))
        assertEquals("'\rCrStart", executor.sanitizeCellText("\rCrStart"))
        // 非触发字符不变
        assertEquals("普通文本", executor.sanitizeCellText("普通文本"))
        assertEquals("123", executor.sanitizeCellText("123"))
        assertEquals("a=b", executor.sanitizeCellText("a=b"))
        // 空串不变（无首字符可判）
        assertEquals("", executor.sanitizeCellText(""))
    }

    @Test
    fun `execute create_xlsx sanitizes formula-prefixed cell strings`() = runBlocking {
        // G-09 端到端锚点：生成文件读回验证 ' 前缀落盘
        val executor = newExecutor()
        val result = executor.execute(
            "document__create_xlsx",
            mapOf(
                "filename" to "公式防护",
                "sheets" to listOf(
                    mapOf(
                        "name" to "S1",
                        "rows" to listOf(
                            listOf("=HYPERLINK(\"http://evil\")", "=cmd|'/c calc'!A0", "正常值")
                        )
                    )
                )
            )
        )
        assertTrue(result.startsWith("已生成 Excel 表格"))
        val generated = executor.targetFile("公式防护", "xlsx")
            .parentFile!!.listFiles()!!.filter { it.name.startsWith("公式防护") }
        assertEquals(1, generated.size)
        XSSFWorkbook(generated[0].inputStream()).use { wb ->
            val row = wb.getSheet("S1").getRow(0)
            assertTrue("公式载荷应有 ' 前缀", row.getCell(0).stringCellValue.startsWith("'="))
            assertTrue("DDE 载荷应有 ' 前缀", row.getCell(1).stringCellValue.startsWith("'="))
            assertEquals("正常值", row.getCell(2).stringCellValue)
        }
    }

    @Test
    fun `execute unknown document tool degrades`() = runBlocking {
        // G-07 缺口4：未知工具名分支
        val result = newExecutor().execute("document__unknown", emptyMap())
        assertTrue("应返回未知工具文案：$result", result.startsWith("未知文档工具"))
    }

    // ==================== buildToolDefinitions ====================

    @Test
    fun `buildToolDefinitions exposes docx and xlsx with strict schema`() {
        val defs = DocumentLocalToolExecutor.buildToolDefinitions()
        assertEquals(2, defs.size)
        assertEquals(
            setOf("document__create_docx", "document__create_xlsx"),
            defs.map { it.function.name }.toSet()
        )
        defs.forEach { def ->
            val params = def.function.parameters as? kotlinx.serialization.json.JsonObject
            assertNotNull("parameters 应为 JsonObject", params)
            assertEquals("object", params!!["type"].toString().trim('"'))
        }
    }

    /**
     * BR-testing-008 / BR-interface-016（TKN-UXR8-FIX-ACVERIFY-001 建议 #1）：
     * 工具 schema 数组属性结构断言 —— 防 Provider（DeepSeek 等）对全请求返回 400
     * `Invalid schema for function 'xxx'` 复发。
     *
     * 根因回顾：document__create_xlsx 的 `sheets` 参数曾写成裸 JsonArray 字面量
     * （非法 JSON Schema），导致真机上包括图片消息在内的**所有请求** 400。
     * 常规 execute 测试无法发现，只有 schema 结构断言能捕获。
     */
    @Test
    fun `xlsx sheets schema uses type array with items object structure`() {
        val defs = DocumentLocalToolExecutor.buildToolDefinitions()
        val xlsx = defs.first { it.function.name == DocumentLocalToolExecutor.TOOL_CREATE_XLSX }
        val params = xlsx.function.parameters as kotlinx.serialization.json.JsonObject
        assertEquals("object", params["type"].toString().trim('"'))

        val props = params["properties"] as? kotlinx.serialization.json.JsonObject
        assertNotNull("properties 应存在", props)
        val sheets = props?.get("sheets") as? kotlinx.serialization.json.JsonObject
        assertNotNull("sheets 应为 JsonObject（禁止裸 JsonArray 字面量）", sheets)
        assertEquals("array", sheets!!["type"].toString().trim('"'))
        assertNotNull("sheets.items 必须存在（合法 JSON Schema）", sheets["items"])

        // items 应为对象结构（含 type/properties/required），而非空占位
        val items = sheets["items"] as? kotlinx.serialization.json.JsonObject
        assertEquals("object", items!!["type"].toString().trim('"'))
        assertNotNull("items.properties 应存在", items["properties"])
        assertNotNull("items.required 应存在", items["required"])
    }

    @Test
    fun `all array properties across tool schemas have items definition`() {
        // 全量结构断言：任何 type:array 属性必须带 items（BR-testing-008 纵深防御）
        val defs = DocumentLocalToolExecutor.buildToolDefinitions()
        defs.forEach { def ->
            val params = def.function.parameters as? kotlinx.serialization.json.JsonObject
                ?: return@forEach
            val props = params["properties"] as? kotlinx.serialization.json.JsonObject
                ?: return@forEach
            props.values.forEach { prop ->
                val propObj = prop as? kotlinx.serialization.json.JsonObject
                    ?: return@forEach
                if (propObj["type"].toString().trim('"') == "array") {
                    assertNotNull(
                        "array 属性必须带 items（def=${def.function.name}, prop=$propObj）",
                        propObj["items"]
                    )
                }
            }
        }
    }
}
