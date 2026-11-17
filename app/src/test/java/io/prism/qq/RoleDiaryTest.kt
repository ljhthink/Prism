package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

/**
 * ADR-077：角色日记（[RoleDiaryStore]）与日记工具（[DiaryLocalToolExecutor]）。
 *
 * 与 `SillyTavern-Plugin-HCDiary` 对齐的三条语义：
 * 第一人称私密随笔 / 每日限流 / 只注入最近若干条。
 */
class RoleDiaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(clock: Long = 1_000L) = RoleDiaryStore(folder.root, clock = { clock })

    // ==================== RoleDiaryStore ====================

    @Test
    fun `写入后可按时间升序读回`() {
        val s = store()
        s.append("cyrene", "第一句", "软", nowMs = 10L)
        s.append("cyrene", "第二句", "", nowMs = 20L)
        assertEquals(listOf("第一句", "第二句"), s.all("cyrene").map { it.text })
    }

    @Test
    fun `持久化后可重新读取（换实例）`() {
        val first = store()
        first.append("cyrene", "记住今天", "暖", nowMs = 5L)
        val second = RoleDiaryStore(folder.root, clock = { 6L })
        assertEquals("记住今天", second.recent("cyrene", 5).single().text)
        assertEquals("暖", second.recent("cyrene", 5).single().mood)
    }

    @Test
    fun `不同角色互不干扰`() {
        val s = store()
        s.append("a", "给 a", nowMs = 1L)
        s.append("b", "给 b", nowMs = 1L)
        assertEquals(1, s.all("a").size)
        assertEquals("给 b", s.all("b").single().text)
    }

    @Test
    fun `每日上限 3 条（超出拒绝）`() {
        val s = store()
        val day = 1_000_000L
        repeat(3) { assertTrue("第 ${it + 1} 条应写入", s.append("cyrene", "第 $it 条", nowMs = day + it)) }
        assertFalse("第 4 条应被拒绝", s.append("cyrene", "第 4 条", nowMs = day + 10))
        assertEquals(RoleDiaryStore.MAX_PER_DAY, s.countToday("cyrene", day + 10))
        // 次日恢复额度
        assertTrue("跨天应恢复额度", s.append("cyrene", "第二天", nowMs = day + 86_400_000L))
    }

    @Test
    fun `每日额度按本地日期而非 UTC 日界（回归：早八点重置）`() {
        val s = store()
        // 北京时间 2026-11-16 00:30（UTC 仍是 11-15 16:30）——三者必须算同一天
        val zone = java.time.ZoneId.systemDefault()
        val base = java.time.LocalDateTime.of(2026, 11, 16, 0, 30)
            .atZone(zone).toInstant().toEpochMilli()
        repeat(RoleDiaryStore.MAX_PER_DAY) { i ->
            assertTrue("本地当天第 ${i + 1} 条应写入", s.append("cyrene", "第 $i 条", nowMs = base + i * 60_000L))
        }
        assertFalse(
            "同一本地日期内不得因 UTC 跨天而恢复额度",
            s.append("cyrene", "第 4 条", nowMs = base + 3 * 60_000L)
        )
    }

    @Test
    fun `空正文与超长正文`() {
        val s = store()
        assertFalse(s.append("cyrene", "   ", nowMs = 1L))
        assertTrue(s.append("cyrene", "长".repeat(RoleDiaryStore.MAX_TEXT_CHARS + 100), nowMs = 2L))
        assertEquals(
            RoleDiaryStore.MAX_TEXT_CHARS,
            s.all("cyrene").single().text.length
        )
    }

    @Test
    fun `超过每角色上限时淘汰最旧`() {
        val s = store()
        val day = 1L
        // 每天 3 条，跨足够多天填满上限
        repeat(RoleDiaryStore.MAX_PER_ROLE + 6) { i ->
            val dayIndex = i / RoleDiaryStore.MAX_PER_DAY
            s.append("cyrene", "第 $i 条", nowMs = day + dayIndex * 86_400_000L + (i % 3))
        }
        assertEquals(RoleDiaryStore.MAX_PER_ROLE, s.all("cyrene").size)
        assertTrue("最旧应被淘汰", s.all("cyrene").none { it.text == "第 0 条" })
    }

    @Test
    fun `删除指定条目与清空`() {
        val s = store()
        s.append("cyrene", "甲", nowMs = 1L)
        s.append("cyrene", "乙", nowMs = 2L)
        assertTrue(s.remove("cyrene", 1L))
        assertEquals(listOf("乙"), s.all("cyrene").map { it.text })
        assertTrue(s.clear("cyrene"))
        assertTrue(s.all("cyrene").isEmpty())
    }

    @Test
    fun `注入片段只取最近 N 条且带心情`() {
        val s = store()
        repeat(3) { i -> s.append("cyrene", "第 $i 条", "心情$i", nowMs = i * 86_400_000L + 1) }
        val section = s.promptSection("cyrene", limit = 2)
        assertEquals(2, section.size)
        assertTrue("应含心情标记", section.last().contains("心情2"))
        assertTrue("应是最后两条", section.none { it.contains("第 0 条") })
    }

    @Test
    fun `落盘失败必须如实返回 false 而不是报成功（M-05 回归）`() {
        // guardrail M-05：原实现 delete 目标后 rename，且忽略返回值、末尾无条件 true →
        // 重命名失败时旧文件已删、cache 已更新、append 返 true、日志打"已写入" →
        // **整本日记静默丢失还报成功**。
        // 失败注入：目标是**非空目录**（ATOMIC_MOVE/REPLACE_EXISTING 都无法替换非空目录）。
        val dir = java.io.File(folder.root, "diaries").apply { mkdirs() }
        val blocker = java.io.File(dir, "cyrene.json").apply { mkdirs() }
        java.io.File(blocker, "keep.txt").writeText("占位，保证目录非空")
        val s = RoleDiaryStore(folder.root, clock = { 1L })
        assertFalse("落盘失败必须返回 false", s.append("cyrene", "写不进去", nowMs = 1L))
        assertTrue("失败时不得污染内存缓存", s.all("cyrene").isEmpty())
    }

    @Test
    fun `损坏文件不崩溃且可继续写入`() {
        val dir = java.io.File(folder.root, "diaries").apply { mkdirs() }
        java.io.File(dir, "cyrene.json").writeText("{ broken")
        val s = RoleDiaryStore(folder.root, clock = { 1L })
        assertTrue(s.all("cyrene").isEmpty())
        assertTrue(s.append("cyrene", "恢复", nowMs = 1L))
    }

    // ==================== DiaryLocalToolExecutor ====================

    @Test
    fun `工具写入成功并回灌明确结果`() = runBlocking<Unit> {
        val s = store()
        val exec = DiaryLocalToolExecutor(s, roleIdProvider = { "cyrene" }, clock = { 100L })
        val result = exec.execute("diary__write", mapOf("text" to "今天他说会一直在", "mood" to "软"))
        assertTrue("回灌应说明已写入：$result", result.contains("已写到你的日记里"))
        assertEquals("今天他说会一直在", s.all("cyrene").single().text)
        assertEquals("软", s.all("cyrene").single().mood)
    }

    @Test
    fun `工具拒绝空正文与超长正文`() = runBlocking<Unit> {
        val s = store()
        val exec = DiaryLocalToolExecutor(s, roleIdProvider = { "cyrene" }, clock = { 100L })
        assertTrue(exec.execute("diary__write", mapOf("text" to "  ")).startsWith("错误"))
        assertTrue(
            exec.execute(
                "diary__write",
                mapOf("text" to "长".repeat(RoleDiaryStore.MAX_TEXT_CHARS + 1))
            ).startsWith("错误")
        )
        assertTrue(s.all("cyrene").isEmpty())
    }

    @Test
    fun `工具达到每日上限后拒绝并给出可执行提示`() = runBlocking<Unit> {
        val s = store()
        val exec = DiaryLocalToolExecutor(s, roleIdProvider = { "cyrene" }, clock = { 100L })
        repeat(RoleDiaryStore.MAX_PER_DAY) { i ->
            exec.execute("diary__write", mapOf("text" to "第 $i 条"))
        }
        val overflow = exec.execute("diary__write", mapOf("text" to "多余的一条"))
        assertTrue("应拒绝：$overflow", overflow.startsWith("错误"))
        assertTrue("应给出可执行提示", overflow.contains("明天"))
    }

    @Test
    fun `角色 id 缺失时落到 default 分组`() = runBlocking<Unit> {
        val s = store()
        val exec = DiaryLocalToolExecutor(s, roleIdProvider = { null }, clock = { 100L })
        exec.execute("diary__write", mapOf("text" to "没有角色也能写"))
        assertEquals(
            1,
            s.all(DiaryLocalToolExecutor.DEFAULT_ROLE_ID).size
        )
    }

    @Test
    fun `handles 只认 own tool`() {
        val exec = DiaryLocalToolExecutor(store(), roleIdProvider = { "cyrene" })
        assertTrue(exec.handles(DiaryLocalToolExecutor.TOOL_NAME))
        assertFalse(exec.handles("todo_write"))
    }

    @Test
    fun `工具定义可注入且参数必填 text`() {
        val def = DiaryLocalToolExecutor.buildToolDefinition()
        assertEquals(DiaryLocalToolExecutor.TOOL_NAME, def.function.name)
        assertTrue("schema 必须声明 required text", def.function.parameters.toString().contains("required"))
        assertTrue("触发判据必须可读", DiaryLocalToolExecutor.TRIGGER_HINT.contains("日记"))
    }

    // ==================== 日记保底沉淀（2026-11-17；ADR-077 未决问题 2 落地） ====================

    @Test
    fun `保底闸门 - 情绪化长消息值得考虑`() {
        assertTrue(
            QqDiaryFallback.shouldConsider(
                incoming = "今天其实挺难过的，忙了一整天还是被否了",
                reply = "唔……那先别急着分析什么，人家陪你把这件事放一放。",
                todayCount = 0
            )
        )
    }

    @Test
    fun `保底闸门 - 纯媒体与过短消息跳过`() {
        // 纯表情：不该为「对方发了个表情」写日记
        assertTrue(
            !QqDiaryFallback.shouldConsider("[表情]", "这个表情是舒服到不想说话啦？", 0)
        )
        assertTrue(!QqDiaryFallback.shouldConsider("[图片]", "拍得真好看呀♪", 0))
        // 过短
        assertTrue(!QqDiaryFallback.shouldConsider("嗯", "嗯。", 0))
        // 空
        assertTrue(!QqDiaryFallback.shouldConsider("", "在呀", 0))
    }

    @Test
    fun `保底闸门 - 每日额度用尽即跳过（不再调 LLM）`() {
        assertTrue(
            !QqDiaryFallback.shouldConsider(
                incoming = "今天其实挺难过的，忙了一整天还是被否了",
                reply = "唔……那先别急着分析什么，人家陪你把这件事放一放。",
                todayCount = RoleDiaryStore.MAX_PER_DAY
            )
        )
    }

    @Test
    fun `保底闸门 - 占位符识别`() {
        assertTrue(QqDiaryFallback.isPlaceholderOnly("[表情]"))
        assertTrue(QqDiaryFallback.isPlaceholderOnly("[表情包] [图片]"))
        assertTrue(QqDiaryFallback.isPlaceholderOnly("……[表情]！"))
        assertTrue(!QqDiaryFallback.isPlaceholderOnly("[表情]你猜～"))
        assertTrue(!QqDiaryFallback.isPlaceholderOnly("难过"))
    }

    @Test
    fun `NONE 识别覆盖常见变体（模型会带引号与句号）`() {
        listOf("NONE", "none", " NONE ", "\"NONE\"", "NONE。", "无", "不写", "")
            .forEach { assertTrue("应判为不写：[$it]", QqDiaryFallback.isNoneResult(it)) }
        listOf("今天他说会一直在，我把这句收好了。", "有点软", "NONE 但其实是有的")
            .forEach {
                // 「NONE 但其实是有的」这种前缀 NONE 会被判为不写（保守：宁可漏记，不写错）
                if (!it.startsWith("NONE")) assertTrue("应判为要写：[$it]", !QqDiaryFallback.isNoneResult(it))
            }
    }

    @Test
    fun `保底 prompt 必须含二选一与反编造约束`() {
        val p = QqDiaryFallback.buildPrompt(
            personaSystemPrompt = "你是昔涟",
            incoming = "今天被否了",
            reply = "先歇一会儿"
        )
        assertTrue("必须带人设", p.contains("你是昔涟"))
        assertTrue("必须带本轮入站", p.contains("今天被否了"))
        assertTrue("必须带本轮回复", p.contains("先歇一会儿"))
        assertTrue("必须给出 NONE 出口", p.contains("NONE"))
        assertTrue("必须禁止旁白", p.contains("不要旁白"))
    }
}
