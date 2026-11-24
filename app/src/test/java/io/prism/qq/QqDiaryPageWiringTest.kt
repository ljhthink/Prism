package io.prism.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「她的日记」页的**装配回归**（ac-verifier，2026-11-22；US-3139 AC-12 / AC-13 / AC-14）。
 *
 * 为什么单独建文件：`QqPacingToolsDiaryWiringTest` 只覆盖"写日记"链路（AC-7~AC-9），
 * 本轮新增的是"读日记"UI，此前**没有任何自动化守护**——漏接就会静默回到
 * "写得进、看不见"（正是用户问题 4 的原始形态）。
 *
 * 断言只认带实参的调用形态，并剔除注释后再匹配（与 `QqNotifyWiringTest` 同一约定）。
 */
class QqDiaryPageWiringTest {

    private fun source(relPath: String): String {
        val candidates = listOf(File("src/main/java/$relPath"), File("app/src/main/java/$relPath"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relPath（工作目录=${File("").absolutePath}）")
        return file.readText()
    }

    private fun stripComments(text: String): String = text.lineSequence()
        .joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") -> ""
                else -> line.substringBefore("//")
            }
        }

    private fun settings(): String = stripComments(source("io/prism/ui/settings/SettingsScreen.kt"))

    /**
     * 只取 QQ_DIARY 子页的**渲染**区间。
     *
     * 两个坑（都是实测踩到的）：
     * 1. `if (subPage == SettingsSubPage.QQ_DIARY)` 在文件里出现两次——第一次是
     *    `LaunchedEffect(subPage) { if (...) reloadDiary() }`（刷新触发），第二次才是渲染分支。
     *    必须取**最后一次**出现，否则区间会扩到整个文件末尾（会误捕 `表情库` 子页的「编辑」按钮）。
     * 2. 结束标记 `} // subPage == QQ_DIARY` 是**行尾注释**，必须先按原始文本定位再剥注释。
     */
    private fun diaryPage(): String {
        val raw = source("io/prism/ui/settings/SettingsScreen.kt")
        val start = raw.lastIndexOf("if (subPage == SettingsSubPage.QQ_DIARY) {")
        assertTrue("必须存在 QQ_DIARY 子页渲染分支", start >= 0)
        val end = raw.indexOf("} // subPage == QQ_DIARY", start)
        assertTrue("子页必须有显式结束标记（便于区间断言）", end > start)
        return stripComments(raw.substring(start, end))
    }

    // ==================== AC-12：第五子页 + 倒序列表 + 删除 + 清空 ====================

    @Test
    fun `AC-12 QQ 第五子页必须存在且标题为她的日记`() {
        val code = settings()
        assertTrue(
            "SettingsSubPage 必须有 QQ_DIARY(\"她的日记\")",
            Regex("""QQ_DIARY\("她的日记"\)""").containsMatchIn(code)
        )
    }

    @Test
    fun `AC-12 入口必须排在表情库之后并展示条数摘要`() {
        val code = settings()
        val stickersAt = code.indexOf("SettingsSubPage.QQ_STICKERS")
        val diaryEntryAt = code.indexOf("title = SettingsSubPage.QQ_DIARY.title")
        assertTrue("表情库入口必须存在", stickersAt >= 0)
        assertTrue("日记入口必须存在", diaryEntryAt >= 0)
        assertTrue("日记入口必须在表情库之后", diaryEntryAt > stickersAt)
        assertTrue(
            "入口副标题必须绑定真实摘要（AC-13 条数 + 最近日期）",
            code.contains("subtitle = qqDiarySummary")
        )
        assertTrue(
            "摘要必须含「条」与最近日期",
            Regex("""\$\{list\.size\} 条""").containsMatchIn(code) &&
                code.contains("最近 %02d-%02d")
        )
    }

    @Test
    fun `AC-12 列表必须倒序渲染`() {
        assertTrue(
            "必须 asReversed() 渲染（最新在最上面）",
            Regex("""qqDiaryEntries\.value\.asReversed\(\)\.forEach""").containsMatchIn(diaryPage())
        )
    }

    @Test
    fun `AC-12 每条必须渲染日期时间与心情`() {
        val page = diaryPage()
        assertTrue("必须格式化 月-日 时:分", page.contains("%02d-%02d %02d:%02d"))
        assertTrue("必须渲染 mood", page.contains("entry.mood"))
    }

    @Test
    fun `AC-12 单条删除必须走 store 且随后刷新`() {
        val page = diaryPage()
        assertTrue("删除必须调 roleDiaryStore.remove", page.contains("remove(qqDiaryRoleId, entry.at)"))
        assertTrue("删除后必须刷新列表", page.contains("reloadDiary()"))
    }

    @Test
    fun `AC-12 清空全部必须二次确认`() {
        val page = diaryPage()
        assertTrue("必须有二次确认状态", page.contains("qqDiaryClearConfirm = true"))
        assertTrue("必须有确认文案", page.contains("此操作不可恢复"))
        assertTrue("清空必须调 store.clear", page.contains("clear(qqDiaryRoleId)"))
    }

    // ==================== AC-13：只读 / 可删 / 不可代写 ====================

    @Test
    fun `AC-13 页面不得提供任何写入入口（不可代写）`() {
        val page = diaryPage()
        for (forbidden in listOf("roleDiaryStore.write", "roleDiaryStore.add", "roleDiaryStore.append")) {
            assertFalse("日记页不得出现写入调用：$forbidden", page.contains(forbidden))
        }
        assertFalse("不得有「新增」按钮", page.contains("\"新增\""))
        assertFalse("不得有「编辑」按钮", page.contains("\"编辑\""))
    }

    @Test
    fun `AC-13 空态必须给出触发条件说明`() {
        val page = diaryPage()
        assertTrue("空态必须解释她什么时候会写", page.contains("她还没写下什么"))
        assertTrue(page.contains("这部分内容不会发给你"))
    }

    @Test
    fun `AC-13 条数与最近日期来自真实数据而非硬编码`() {
        val code = settings()
        assertTrue("摘要必须用 list.size", code.contains("\${list.size} 条"))
        assertTrue("最近日期必须取最后一条的时间", code.contains("list.last().at"))
        assertTrue("必须显示每日上限", code.contains("RoleDiaryStore.MAX_PER_DAY"))
    }

    // ==================== AC-14：IO 线程 + 正文单独渲染 ====================

    @Test
    fun `AC-14 所有读写盘必须在 IO 线程`() {
        val code = settings()
        // 生产代码里这三处调用跨行书写（`qqApp?.roleDiaryStore` 换行后 `?.remove(...)`），
        // 因此用允许空白的正则定位，而不是字面 `roleDiaryStore.remove(`。
        for (call in listOf("all", "remove", "clear")) {
            val pattern = Regex("""roleDiaryStore\s*\n?\s*\??\.\s*$call\(""")
            val match = pattern.find(code)
            assertTrue("必须有调用：roleDiaryStore?.$call(", match != null)
            // 同一作用域内必须出现 withContext(Dispatchers.IO) 且在调用之前
            val scope = code.substring(maxOf(0, match!!.range.first - 400), match.range.first)
            assertTrue(
                "roleDiaryStore.$call( 必须包在 withContext(Dispatchers.IO) 里",
                scope.contains("Dispatchers.IO")
            )
        }
    }

    @Test
    fun `AC-14 日记正文必须单独渲染而不是塞进 SetRow 的单行 status`() {
        val page = diaryPage()
        val rowAt = page.indexOf("PrismRow(")
        assertTrue("必须用 PrismRow 渲染标题行", rowAt >= 0)
        val textAt = page.indexOf("text = entry.text")
        assertTrue("正文必须单独 Text 渲染", textAt > rowAt)
        val rowBody = page.substring(rowAt, textAt)
        assertFalse("标题行内不得出现 entry.text（会被 statusMaxLines=1 截断）", rowBody.contains("entry.text"))
    }

    @Test
    fun `AC-14 诊断日志只记条数不记正文`() {
        val code = settings()
        val at = code.indexOf("\"日记可读=role=")
        assertTrue("必须有「日记可读」诊断日志（真机取证要求）", at >= 0)
        val logLine = code.substring(at, minOf(code.length, at + 120))
        assertTrue(logLine.contains("条数="))
        assertFalse("日志不得带正文", logLine.contains("entry.text") || logLine.contains("loaded.first"))
    }

    @Test
    fun `AC-14 角色 id 必须来自角色包而非用户输入`() {
        val code = settings()
        assertTrue(
            "roleId 必须优先取 effectiveRolePack()?.id",
            Regex("""effectiveRolePack\(\)\?\.id""").containsMatchIn(code)
        )
    }
}
