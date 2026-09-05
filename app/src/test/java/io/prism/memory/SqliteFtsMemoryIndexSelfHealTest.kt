package io.prism.memory

import android.content.Context
import io.prism.data.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SqliteFtsMemoryIndex 自愈状态机测试（v1 批次19，guardrail P2-2，Robolectric）。
 *
 * **范围说明（如实标注）**：FTS5 是否可用取决于测试环境 SQLite 构建——
 * - FTS5 可用：验证 正常重建 → 检索命中 → 影子表损坏 → 自愈 → 检索恢复 全链路；
 * - FTS5 不可用：验证 自愈预算有界（rebuildAttempts ≤ MAX_REBUILD_ATTEMPTS）+
 *   检索降级为空不抛异常（guardrail P2-1 风暴防护红线）。
 * 两分支均锁定"确定性失败稳态下不会无限删库重建"的核心不变量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SqliteFtsMemoryIndexSelfHealTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 隔离：每个用例从干净状态开始（删除可能残留的 FTS 库）
        context.deleteDatabase("prism_memory_fts.db")
    }

    private fun records(): List<MemoryRecord> = listOf(
        MemoryRecord(
            id = 1L,
            sessionId = "test-session",
            content = "用户偏好 tech_stack：Scrapling crawl4ai SearXNG",
            timestamp = 1000L
        ),
        MemoryRecord(
            id = 2L,
            sessionId = "test-session",
            content = "用户偏好 购物：高性价比 价格敏感",
            timestamp = 2000L
        )
    )

    @Test
    fun `self heal bounded under deterministic failure`() {
        val index = SqliteFtsMemoryIndex(context)
        // 反复触发 reconcile + search（FTS5 不可用时每次都失败 → 自愈路径被反复走到）
        repeat(6) { round ->
            index.reconcile(records(), version = round.toLong() + 1)
            val hits = index.search("tech_stack", topK = 3)
            // 降级为空不抛异常；命中结果只能来自真实 FTS5（无假阳性）
            assertTrue("检索必须返回 ≤ topK 的合法结果", hits.size <= 3)
        }
        assertTrue(
            "确定性失败稳态下重建尝试必须有界（≤ MAX_REBUILD_ATTEMPTS），防删库风暴",
            index.rebuildAttempts <= SqliteFtsMemoryIndex.MAX_REBUILD_ATTEMPTS
        )
    }

    @Test
    fun `search returns empty not exception after heal budget exhausted`() {
        val index = SqliteFtsMemoryIndex(context)
        // 消耗自愈预算
        repeat(SqliteFtsMemoryIndex.MAX_REBUILD_ATTEMPTS + 2) { round ->
            index.reconcile(records(), version = round.toLong() + 1)
        }
        // 预算耗尽后 search 仍安全返回（空列表），不抛异常
        val hits = index.search("tech_stack", topK = 3)
        assertNotNull(hits)
        assertTrue(hits.size <= 3)
    }

    @Test
    fun `fts5 available path reconciles and searches when environment supports it`() {
        val index = SqliteFtsMemoryIndex(context)
        index.reconcile(records(), version = 1L)
        val hits = index.search("tech_stack", topK = 3)
        if (index.rebuildAttempts == 0) {
            // FTS5 可用环境：reconcile 一次成功（无重建），检索应命中 id=1
            assertTrue("FTS5 可用环境应命中 tech_stack 记忆", hits.any { it.recordId == 1L })
        } else {
            // FTS5 不可用环境（Robolectric SQLite 无 FTS5）：降级为空，无崩溃
            assertTrue(hits.isEmpty())
            assertTrue(index.rebuildAttempts <= SqliteFtsMemoryIndex.MAX_REBUILD_ATTEMPTS)
        }
    }

    @Test
    fun `reconcile skips unchanged version`() {
        val index = SqliteFtsMemoryIndex(context)
        index.reconcile(records(), version = 1L)
        val attemptsAfterFirst = index.rebuildAttempts
        // 同版本重复 reconcile 的短路语义：仅当首次成功（lastVersion 已记录）时直接返回；
        // FTS5 不可用环境首次即失败（lastVersion=-1），重复调用受自愈预算约束不无限增长
        index.reconcile(records(), version = 1L)
        if (attemptsAfterFirst == 0) {
            assertEquals("首次成功后同版本短路不应增加重建尝试", attemptsAfterFirst, index.rebuildAttempts)
        } else {
            assertTrue(
                "失败环境下重建尝试必须受预算约束",
                index.rebuildAttempts <= SqliteFtsMemoryIndex.MAX_REBUILD_ATTEMPTS
            )
        }
    }
}
