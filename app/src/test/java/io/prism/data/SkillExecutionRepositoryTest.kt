package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * SkillExecutionRepository CRUD 单元测试（US-029 执行可观测，ADR-013 5.7）。
 *
 * **验证内容**：
 * 1. SkillExecutionRecord 持久化到 ObjectBox（含 toolCalls @Convert JSON 序列化）
 * 2. save / get / getBySkill / getRecentBySkill / removeBySkill / remove / removeAll 全方法覆盖
 * 3. toolCalls 列表经 ToolCallListConverter 正确往返（含空列表 / 多元素 / 嵌套 JSON 字符串）
 * 4. getBySkill 按 startedAt 降序返回（最近的在前）
 * 5. getRecentBySkill limit 截断正确（默认 10，自定义 limit）
 * 6. removeBySkill 按 skillConfigId 级联清理，不影响其他 Skill 的记录
 *
 * **测试策略**（BR-testing-004）：
 * - 真实 ObjectBox（`MyObjectBox.builder().directory(tempDir).build()`），无 mock
 * - 仿 [SkillRepositoryTest] 模式，保持测试一致性
 * - 不依赖 Android Context（ObjectBox directory 模式可在纯 JVM 运行）
 */
class SkillExecutionRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: SkillExecutionRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "skill-exec-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = SkillExecutionRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== 基础 CRUD ====================

    @Test
    fun save_assigns_positive_id() {
        val record = makeRecord(skillConfigId = 1L, skillName = "translator")
        val id = repository.save(record)
        assertTrue("应分配正数 id", id > 0)
    }

    @Test
    fun get_returns_persisted_record() {
        val record = SkillExecutionRecord(
            skillConfigId = 42L,
            skillName = "meeting-notes",
            startedAt = 1_000L,
            finishedAt = 1_500L,
            durationMs = 500L,
            status = ExecutionStatus.SUCCESS,
            toolCalls = listOf(
                ToolCallRecord(
                    toolName = "meeting-notes__read_file",
                    arguments = """{"path":"/a/b.md"}""",
                    result = "文件内容…",
                    durationMs = 120L,
                    status = ExecutionStatus.SUCCESS
                )
            ),
            errorMessage = null,
            outputPreview = "会议纪要摘要…"
        )
        val id = repository.save(record)

        val retrieved = repository.get(id)
        assertNotNull("应能取回已保存记录", retrieved)
        retrieved!!
        assertEquals(42L, retrieved.skillConfigId)
        assertEquals("meeting-notes", retrieved.skillName)
        assertEquals(1_000L, retrieved.startedAt)
        assertEquals(1_500L, retrieved.finishedAt)
        assertEquals(500L, retrieved.durationMs)
        assertEquals(ExecutionStatus.SUCCESS, retrieved.status)
        assertEquals(1, retrieved.toolCalls.size)
        assertEquals("meeting-notes__read_file", retrieved.toolCalls[0].toolName)
        assertEquals("""{"path":"/a/b.md"}""", retrieved.toolCalls[0].arguments)
        assertEquals("文件内容…", retrieved.toolCalls[0].result)
        assertEquals(120L, retrieved.toolCalls[0].durationMs)
        assertEquals(ExecutionStatus.SUCCESS, retrieved.toolCalls[0].status)
        assertEquals("会议纪要摘要…", retrieved.outputPreview)
        assertNull("成功路径 errorMessage 应为 null", retrieved.errorMessage)
    }

    @Test
    fun get_returns_null_for_nonexistent_id() {
        assertNull(repository.get(99999L))
    }

    // ==================== getBySkill ====================

    @Test
    fun getBySkill_returns_only_matching_skill_records() {
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 100L))
        repository.save(makeRecord(skillConfigId = 2L, skillName = "b", startedAt = 200L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 300L))

        val result = repository.getBySkill(1L)
        assertEquals("应只返回 skillConfigId=1 的记录", 2, result.size)
        assertTrue(result.all { it.skillConfigId == 1L })
    }

    @Test
    fun getBySkill_returns_empty_for_no_records() {
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a"))

        val result = repository.getBySkill(999L)
        assertTrue("无匹配记录应返回空列表", result.isEmpty())
    }

    @Test
    fun getBySkill_returns_sorted_by_startedAt_descending() {
        // 故意乱序插入，验证返回时按 startedAt 降序
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 200L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 100L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 300L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 150L))

        val result = repository.getBySkill(1L)
        assertEquals(4, result.size)
        assertEquals(300L, result[0].startedAt)
        assertEquals(200L, result[1].startedAt)
        assertEquals(150L, result[2].startedAt)
        assertEquals(100L, result[3].startedAt)
    }

    // ==================== getRecentBySkill ====================

    @Test
    fun getRecentBySkill_returns_all_when_under_limit() {
        for (i in 1..5) {
            repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = i.toLong() * 100))
        }

        val recent = repository.getRecentBySkill(1L)
        assertEquals("5 条记录 < 默认 limit 10，应全部返回", 5, recent.size)
        // 验证降序：最近的在前
        assertEquals(500L, recent[0].startedAt)
        assertEquals(100L, recent[4].startedAt)
    }

    @Test
    fun getRecentBySkill_truncates_to_default_10() {
        for (i in 1..15) {
            repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = i.toLong() * 100))
        }

        val recent = repository.getRecentBySkill(1L)
        assertEquals("15 条记录应截断为默认 limit 10", 10, recent.size)
        // 验证返回的是最近的 10 条（startedAt 最大的 10 个）
        assertEquals(1500L, recent[0].startedAt)
        assertEquals(600L, recent[9].startedAt)
    }

    @Test
    fun getRecentBySkill_respects_custom_limit() {
        for (i in 1..20) {
            repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = i.toLong() * 100))
        }

        val recent = repository.getRecentBySkill(1L, limit = 3)
        assertEquals("应尊重自定义 limit", 3, recent.size)
        assertEquals(2000L, recent[0].startedAt)
        assertEquals(1800L, recent[2].startedAt)
    }

    @Test
    fun getRecentBySkill_with_zero_limit_returns_empty() {
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 100L))

        val recent = repository.getRecentBySkill(1L, limit = 0)
        assertTrue("limit=0 应返回空列表", recent.isEmpty())
    }

    // ==================== removeBySkill（级联清理） ====================

    @Test
    fun removeBySkill_deletes_all_records_for_skill() {
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 100L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 200L))
        repository.save(makeRecord(skillConfigId = 2L, skillName = "b", startedAt = 300L))

        val deletedCount = repository.removeBySkill(1L)
        assertEquals("应删除 2 条记录", 2L, deletedCount)
        assertTrue("skillConfigId=1 应无记录", repository.getBySkill(1L).isEmpty())
        assertEquals("skillConfigId=2 不应受影响", 1, repository.getBySkill(2L).size)
    }

    @Test
    fun removeBySkill_returns_zero_for_no_records() {
        val deletedCount = repository.removeBySkill(999L)
        assertEquals("无记录时应返回 0", 0L, deletedCount)
    }

    // ==================== remove / removeAll ====================

    @Test
    fun remove_deletes_single_record() {
        val id = repository.save(makeRecord(skillConfigId = 1L, skillName = "a"))

        repository.remove(id)

        assertNull(repository.get(id))
    }

    @Test
    fun removeAll_clears_all_records() {
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a"))
        repository.save(makeRecord(skillConfigId = 2L, skillName = "b"))
        repository.save(makeRecord(skillConfigId = 3L, skillName = "c"))

        repository.removeAll()

        assertTrue(repository.getBySkill(1L).isEmpty())
        assertTrue(repository.getBySkill(2L).isEmpty())
        assertTrue(repository.getBySkill(3L).isEmpty())
    }

    // ==================== toolCalls 序列化往返（ToolCallListConverter） ====================

    @Test
    fun toolCalls_empty_list_roundtrips() {
        val id = repository.save(
            makeRecord(skillConfigId = 1L, skillName = "a").copy(toolCalls = emptyList())
        )

        val retrieved = repository.get(id)
        assertTrue("空 toolCalls 列表应正确往返", retrieved!!.toolCalls.isEmpty())
    }

    @Test
    fun toolCalls_multiple_elements_roundtrip() {
        val toolCalls = listOf(
            ToolCallRecord("skill1__tool_a", """{"x":1}""", "result-a", 50L, ExecutionStatus.SUCCESS),
            ToolCallRecord("skill1__tool_b", """{"y":"hello"}""", "result-b", 80L, ExecutionStatus.FAIL),
            ToolCallRecord("skill1__tool_c", """{"z":true}""", "result-c", 120L, ExecutionStatus.CANCELLED)
        )
        val id = repository.save(makeRecord(skillConfigId = 1L, skillName = "a").copy(toolCalls = toolCalls))

        val retrieved = repository.get(id)!!
        assertEquals(3, retrieved.toolCalls.size)
        assertEquals("skill1__tool_a", retrieved.toolCalls[0].toolName)
        assertEquals(ExecutionStatus.SUCCESS, retrieved.toolCalls[0].status)
        assertEquals("skill1__tool_b", retrieved.toolCalls[1].toolName)
        assertEquals(ExecutionStatus.FAIL, retrieved.toolCalls[1].status)
        assertEquals("skill1__tool_c", retrieved.toolCalls[2].toolName)
        assertEquals(ExecutionStatus.CANCELLED, retrieved.toolCalls[2].status)
    }

    @Test
    fun toolCalls_with_special_characters_in_arguments_roundtrips() {
        // JSON 字符串中含引号、反斜杠、中文、换行（验证 JSON 序列化正确转义）
        val arguments = """{"path":"C:\\Users\\test\\文档.md","content":"line1\nline2\ttab"}"""
        val toolCalls = listOf(
            ToolCallRecord("fs__read", arguments, "内容", 100L, ExecutionStatus.SUCCESS)
        )
        val id = repository.save(makeRecord(skillConfigId = 1L, skillName = "a").copy(toolCalls = toolCalls))

        val retrieved = repository.get(id)!!
        assertEquals(1, retrieved.toolCalls.size)
        assertEquals(arguments, retrieved.toolCalls[0].arguments)
    }

    @Test
    fun toolCalls_with_empty_strings_roundtrips() {
        val toolCalls = listOf(
            ToolCallRecord("", "", "", 0L, ExecutionStatus.FAIL)
        )
        val id = repository.save(makeRecord(skillConfigId = 1L, skillName = "a").copy(toolCalls = toolCalls))

        val retrieved = repository.get(id)!!
        assertEquals(1, retrieved.toolCalls.size)
        assertEquals("", retrieved.toolCalls[0].toolName)
        assertEquals("", retrieved.toolCalls[0].arguments)
        assertEquals("", retrieved.toolCalls[0].result)
    }

    // ==================== errorMessage 持久化 ====================

    @Test
    fun errorMessage_null_for_success_status() {
        val id = repository.save(
            makeRecord(skillConfigId = 1L, skillName = "a", status = ExecutionStatus.SUCCESS)
        )

        assertNull(repository.get(id)!!.errorMessage)
    }

    @Test
    fun errorMessage_persisted_for_fail_status() {
        val id = repository.save(
            makeRecord(
                skillConfigId = 1L,
                skillName = "a",
                status = ExecutionStatus.FAIL,
                errorMessage = "工具执行失败: fs__read（<path>）"
            )
        )

        assertEquals(
            "工具执行失败: fs__read（<path>）",
            repository.get(id)!!.errorMessage
        )
    }

    @Test
    fun errorMessage_persisted_for_cancelled_status() {
        val id = repository.save(
            makeRecord(
                skillConfigId = 1L,
                skillName = "a",
                status = ExecutionStatus.CANCELLED,
                errorMessage = "协程取消"
            )
        )

        assertEquals("协程取消", repository.get(id)!!.errorMessage)
    }

    // ==================== 多 Skill 混合场景 ====================

    @Test
    fun mixed_skills_getRecentBySkill_isolates_per_skill() {
        // skill A：3 条记录
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 100L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 200L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 300L))
        // skill B：2 条记录
        repository.save(makeRecord(skillConfigId = 2L, skillName = "b", startedAt = 400L))
        repository.save(makeRecord(skillConfigId = 2L, skillName = "b", startedAt = 500L))

        val recentA = repository.getRecentBySkill(1L)
        val recentB = repository.getRecentBySkill(2L)

        assertEquals("skill A 应有 3 条记录", 3, recentA.size)
        assertEquals("skill B 应有 2 条记录", 2, recentB.size)
        assertTrue(recentA.all { it.skillConfigId == 1L })
        assertTrue(recentB.all { it.skillConfigId == 2L })
        // 验证各自降序
        assertEquals(300L, recentA[0].startedAt)
        assertEquals(500L, recentB[0].startedAt)
    }

    @Test
    fun removeBySkill_does_not_affect_other_skills() {
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 100L))
        repository.save(makeRecord(skillConfigId = 1L, skillName = "a", startedAt = 200L))
        repository.save(makeRecord(skillConfigId = 2L, skillName = "b", startedAt = 300L))
        repository.save(makeRecord(skillConfigId = 3L, skillName = "c", startedAt = 400L))

        repository.removeBySkill(1L)

        assertEquals("skill B 不受影响", 1, repository.getBySkill(2L).size)
        assertEquals("skill C 不受影响", 1, repository.getBySkill(3L).size)
        assertTrue("skill A 已清空", repository.getBySkill(1L).isEmpty())
    }

    // ==================== 辅助函数 ====================

    /**
     * 构造测试用 [SkillExecutionRecord]（默认值便于快速创建）。
     */
    private fun makeRecord(
        skillConfigId: Long,
        skillName: String,
        startedAt: Long = System.currentTimeMillis(),
        finishedAt: Long = startedAt + 500L,
        status: String = ExecutionStatus.SUCCESS,
        errorMessage: String? = null
    ): SkillExecutionRecord = SkillExecutionRecord(
        skillConfigId = skillConfigId,
        skillName = skillName,
        startedAt = startedAt,
        finishedAt = finishedAt,
        durationMs = finishedAt - startedAt,
        status = status,
        toolCalls = emptyList(),
        errorMessage = errorMessage,
        outputPreview = null
    )
}
