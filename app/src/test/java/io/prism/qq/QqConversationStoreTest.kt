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

    // ==================== ADR-076（2026-11-15）：顺序归一 + 媒体说明 ====================

    @Test
    fun `追加顺序乱序时按时间升序读出（真机错序回归）`() {
        // 真机取证：21:49:12 的消息排在 21:50:08 之后（事件/补发导致追加序 ≠ 会话序）
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append("a", msg("第二条", true, "m2", 200L))
        store.append("a", msg("第一条", true, "m1", 100L))
        store.append("a", msg("第三条", true, "m3", 300L))
        val recent = store.recent("a", 10)
        assertEquals(listOf("第一条", "第二条", "第三条"), recent.map { it.text })
    }

    @Test
    fun `持久化后仍按时间升序（重排结果落盘可复现）`() {
        val first = QqConversationStore(folder.root, clock = { 1L })
        first.append("a", msg("晚", true, "m2", 200L))
        first.append("a", msg("早", true, "m1", 100L))
        val second = QqConversationStore(folder.root, clock = { 2L })
        assertEquals(listOf("早", "晚"), second.recent("a", 10).map { it.text })
    }

    @Test
    fun `淘汰按时间最旧而不是插入最早（trim 与读出语义一致）`() {
        // 先插入"最新"，再插入一堆"更旧"的——若淘汰按插入序，会把刚插入的最新那条删掉。
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append("a", msg("最新", true, "newest", 9_999L))
        repeat(QqConversationStore.MAX_PER_CONTACT) { i ->
            store.append("a", msg("旧$i", true, "old$i", (i + 1).toLong()))
        }
        val texts = store.recent("a", QqConversationStore.MAX_PER_CONTACT + 10).map { it.text }
        assertEquals("总条数受上限约束", QqConversationStore.MAX_PER_CONTACT, texts.size)
        assertTrue("时间最新的那条不得被插入序淘汰掉", "最新" in texts)
        assertTrue("最旧的一条应被淘汰", "旧0" !in texts)
        assertEquals("读出仍是最新的在最后", "最新", texts.last())
    }

    @Test
    fun `同一时间戳保持稳定相对序（稳定排序不得打乱）`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append("a", msg("甲", true, "m1", 500L))
        store.append("a", msg("乙", true, "m2", 500L))
        assertEquals(listOf("甲", "乙"), store.recent("a", 10).map { it.text })
    }

    @Test
    fun `媒体说明随记录持久化并可读回`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append(
            "a",
            QqMessage(null, true, "[表情包]", 10L, mediaNote = "对方发来一个抱抱的表情包")
        )
        val reloaded = QqConversationStore(folder.root, clock = { 2L })
        assertEquals("对方发来一个抱抱的表情包", reloaded.recent("a", 5).single().mediaNote)
    }

    @Test
    fun `纯媒体消息（无正文但有说明）也能写入`() {
        // 旧实现要求 text 非空 → "只发了个表情" 在历史里完全不留痕
        val store = QqConversationStore(folder.root, clock = { 1L })
        assertTrue(
            store.append("a", QqMessage(null, false, "", 10L, mediaNote = "发了一个「抱抱」的表情"))
        )
        assertEquals(1, store.recent("a", 5).size)
    }

    @Test
    fun `正文与说明都为空时不写入`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        assertFalse(store.append("a", QqMessage(null, true, "  ", 10L, mediaNote = "  ")))
    }

    @Test
    fun `超长媒体说明被截断`() {
        val store = QqConversationStore(folder.root, clock = { 1L })
        store.append("a", QqMessage(null, true, "[图片]", 10L, mediaNote = "说".repeat(5_000)))
        assertEquals(
            QqConversationStore.MAX_NOTE_CHARS,
            store.recent("a", 5).single().mediaNote!!.length
        )
    }
}
