package io.prism.qq

import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [QqConversationStore] 本地会话记录（2026-10-26；US-3112 修订）。 */
class QqConversationStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun msg(text: String, incoming: Boolean, id: String? = null, at: Long = 0L) =
        QqMessage(messageId = id, incoming = incoming, text = text, timestampMs = at)

    @Test
    fun `追加后可按时间顺序读取`() {
        val store = QqConversationStore(folder.root, clock = { 1000L })
        store.append("Sisyphus", msg("在吗", true, "m1", 1L))
        store.append("Sisyphus", msg("在的", false, null, 2L))
        val recent = store.recent("Sisyphus", 10)
        assertEquals(2, recent.size)
        assertEquals("在吗", recent[0].text)
        assertEquals("在的", recent[1].text)
        assertTrue(recent[0].incoming)
        assertFalse(recent[1].incoming)
    }

    @Test
    fun `同 messageId 不重复写入`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        assertTrue(store.append("a", msg("hi", true, "m1", 1L)))
        assertFalse(store.append("a", msg("hi", true, "m1", 2L)))
        assertEquals(1, store.recent("a", 10).size)
    }

    @Test
    fun `不同联系人互不干扰`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append("a", msg("给 a", true, "m1", 1L))
        store.append("b", msg("给 b", true, "m2", 1L))
        assertEquals(1, store.recent("a", 10).size)
        assertEquals("给 b", store.recent("b", 10).single().text)
    }

    @Test
    fun `超过上限时淘汰最旧`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        repeat(QqConversationStore.MAX_PER_CONTACT + 5) { i ->
            store.append("a", msg("m$i", true, "id$i", (i + 1).toLong()))
        }
        val recent = store.recent("a", QqConversationStore.MAX_PER_CONTACT + 10)
        assertEquals(QqConversationStore.MAX_PER_CONTACT, recent.size)
        assertEquals("m${QqConversationStore.MAX_PER_CONTACT + 4}", recent.last().text)
    }

    @Test
    fun `持久化后可重新读取`() {
        val first = QqConversationStore(folder.root, clock = { 1L })
        first.append("a", msg("记住我", true, "m1", 5L))
        val second = QqConversationStore(folder.root, clock = { 2L })
        assertEquals("记住我", second.recent("a", 5).single().text)
    }

    @Test
    fun `清空后无记录`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append("a", msg("x", true, "m1", 1L))
        store.clear()
        assertTrue(store.recent("a", 10).isEmpty())
        assertEquals(0, store.totalCount())
    }

    @Test
    fun `空文本不写入`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        assertFalse(store.append("a", msg("   ", true, null, 1L)))
        assertTrue(store.recent("a", 10).isEmpty())
    }

    @Test
    fun `损坏的存储文件不导致崩溃`() {
        val file = java.io.File(folder.root, "qq_conversations.json")
        file.writeText("{ not json ")
        val store = QqConversationStore(folder.root, clock = { 1L })
        assertTrue(store.recent("a", 10).isEmpty())
        assertTrue(store.append("a", msg("ok", true, "m1", 1L)))
        assertNotNull(store.recent("a", 10).firstOrNull())
    }
}
