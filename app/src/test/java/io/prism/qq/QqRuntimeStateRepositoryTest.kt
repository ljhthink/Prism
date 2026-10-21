package io.prism.qq

import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqRuntimeStateRepository] 幂等 ID 集合持久化测试（guardrail M-01，D-03）。
 *
 * 覆盖：A→B→A 判重集合、超过上限淘汰最旧、空白 ID 清键语义。
 */
class QqRuntimeStateRepositoryTest {

    @Test
    fun `A-B-重复A 的 ID 集合持久化后可判重`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())

        repo.setLastRepliedMessageId("A")
        repo.setLastRepliedMessageId("B")

        val state = repo.get()
        assertEquals("最后一条应为 B", "B", state.lastRepliedMessageId)
        assertEquals(listOf("A", "B"), state.repliedMessageIds)
        // 重复 A 必须命中去重集合
        assertTrue(
            QqReplyDedup.isDuplicate(
                messageId = "A",
                lastMessageId = state.lastRepliedMessageId,
                hash = 0, lastHash = 0, lastRepliedAtMs = 0, nowMs = 0,
                repliedMessageIds = state.repliedMessageIds
            )
        )
    }

    @Test
    fun `超过上限时淘汰最旧 ID`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val total = QqRuntimeState.MAX_REPLIED_IDS + 1
        repeat(total) { i -> repo.setLastRepliedMessageId("id-$i") }

        val ids = repo.get().repliedMessageIds
        assertEquals("集合应钳制在上限", QqRuntimeState.MAX_REPLIED_IDS, ids.size)
        assertTrue("最旧的 id-0 应被淘汰", "id-0" !in ids)
        assertEquals("最新的应保留", "id-${total - 1}", ids.last())
    }

    @Test
    fun `空白 ID 清除最后一条但保留集合（语义对称）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        repo.setLastRepliedMessageId("A")

        repo.setLastRepliedMessageId("  ")

        val state = repo.get()
        assertEquals("最后一条应被清除", "", state.lastRepliedMessageId)
        assertEquals("集合不应被空白值污染", listOf("A"), state.repliedMessageIds)
    }
}
