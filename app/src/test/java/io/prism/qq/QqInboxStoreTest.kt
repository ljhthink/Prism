package io.prism.qq

import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [QqInboxStore] / [QqInboxPolicy] 单测（2026-11-02；ADR-071 / PRD US-3129）。
 *
 * 覆盖的**真机故障形态**：
 * 1. 断连期间消息必须能落盘、进程重启后仍在（AC-7）；
 * 2. 通道离线/节流跳过必须"挂起"而不是"完成"（否则断线几分钟就把消息丢掉）；
 * 3. 失败按退避重试，次数用尽才放弃；
 * 4. 同一 message_id 不重复入队（防重复回复）；
 * 5. 纯媒体消息（无正文）同样入队（旧实现整条丢弃）。
 */
class QqInboxStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    private fun msg(
        id: String? = "m1",
        text: String = "在吗",
        at: Long = t0,
        incoming: Boolean = true
    ) = QqMessage(messageId = id, incoming = incoming, text = text, timestampMs = at)

    @Test
    fun `入队后可读取且重启后仍在`() {
        val dir = tmp.newFolder()
        val store = QqInboxStore(dir)
        assertTrue(store.enqueue(msg()))
        assertEquals(1, store.due(t0 + 1000).size)

        // 模拟进程重启：重新构造（从磁盘读回）
        val reopened = QqInboxStore(dir)
        val due = reopened.due(t0 + 1000)
        assertEquals(1, due.size)
        assertEquals("m1", due.first().id)
    }

    @Test
    fun `同 id 不重复入队`() {
        val store = QqInboxStore(tmp.newFolder())
        assertTrue(store.enqueue(msg()))
        assertFalse(store.enqueue(msg()))
        assertEquals(1, store.size())
    }

    @Test
    fun `纯媒体消息也要入队`() {
        val store = QqInboxStore(tmp.newFolder())
        val mediaMsg = QqMessage(
            messageId = null,
            incoming = true,
            text = "",
            timestampMs = t0,
            media = listOf(
                io.prism.qq.channel.QqMediaRef(
                    kind = io.prism.qq.channel.QqMediaKind.STICKER,
                    summary = "[动画表情]"
                )
            )
        )
        assertTrue(store.enqueue(mediaMsg))
        val due = store.due(t0 + 1000)
        assertEquals(1, due.size)
        assertEquals("[媒体消息]", due.first().text)
    }

    @Test
    fun `出站消息不入队`() {
        val store = QqInboxStore(tmp.newFolder())
        assertFalse(store.enqueue(msg(incoming = false)))
        assertEquals(0, store.size())
    }

    @Test
    fun `原子取件不会把同一条重复分给两个消费者`() {
        val store = QqInboxStore(tmp.newFolder())
        store.enqueue(msg("a"))
        store.enqueue(msg("b"))
        val first = store.claimDue(t0 + 1000)
        assertEquals(2, first.size)
        // 第二次取件：条目仍在「处理中」租约内，不得再被取走
        assertTrue(store.claimDue(t0 + 1000).isEmpty())
        // 租约过期后可重新取（进程中途被杀不会永久卡住）
        assertEquals(2, store.claimDue(t0 + 1000 + QqInboxStore.CLAIM_TTL_MS).size)
    }

    @Test
    fun `单轮取件有上限且剩余仍能被后续轮次取到`() {
        val store = QqInboxStore(tmp.newFolder())
        val total = QqInboxStore.MAX_PER_ROUND + 2
        repeat(total) { i -> store.enqueue(msg(id = "m$i", at = t0 + i)) }
        val first = store.claimDue(t0 + 1000)
        assertEquals(QqInboxStore.MAX_PER_ROUND, first.size)
        // 同一时刻再取：已被租约占住的 5 条不会重复分出去，仅剩未处理的 2 条
        val second = store.claimDue(t0 + 1000)
        assertEquals(2, second.size)
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
        // 租约到期后，前 5 条重新可取（进程中途被杀不会永久卡住）
        assertEquals(
            QqInboxStore.MAX_PER_ROUND,
            store.claimDue(t0 + 1000 + QqInboxStore.CLAIM_TTL_MS).size
        )
    }

    @Test
    fun `控制字符不破坏 JSON 且水位不丢`() {
        val dir = tmp.newFolder()
        val store = QqInboxStore(dir)
        store.markSeen(t0)
        // 制表符/控制符是真实消息里会出现的（复制粘贴）
        assertTrue(store.enqueue(msg(id = "ctl", text = "a\tb\u0001c\"d")))
        val reopened = QqInboxStore(dir)
        assertEquals(t0, reopened.lastSeenAtMs())
        assertEquals(1, reopened.size())
        assertEquals("a\tb\u0001c\"d", reopened.due(t0 + 1000).first().text)
    }

    @Test
    fun `完成后移除`() {
        val store = QqInboxStore(tmp.newFolder())
        store.enqueue(msg())
        store.done("m1")
        assertEquals(0, store.size())
        assertEquals(0, QqInboxStore(tmp.newFolder()).size())
    }

    @Test
    fun `失败按退避重试且次数用尽后放弃`() {
        val store = QqInboxStore(tmp.newFolder())
        store.enqueue(msg())

        // 第 1 次失败 → 30s 后
        assertFalse(store.retryLater("m1", "端点异常", t0))
        assertTrue(store.due(t0 + 1_000).isEmpty())
        assertEquals(1, store.due(t0 + QqInboxPolicy.backoffMs(1)).size)

        // 第 2 次 → 2min
        assertFalse(store.retryLater("m1", "端点异常", t0))
        // 第 3 次 → 10min
        assertFalse(store.retryLater("m1", "端点异常", t0))
        // 第 4 次超限 → 放弃
        assertTrue(store.retryLater("m1", "端点异常", t0))
        assertEquals(0, store.size())
    }

    @Test
    fun `挂起不改预算`() {
        val store = QqInboxStore(tmp.newFolder())
        store.enqueue(msg())
        store.hold("m1", t0 + 60_000L)
        assertTrue(store.due(t0 + 1_000).isEmpty())
        val due = store.due(t0 + 60_000L)
        assertEquals(1, due.size)
        assertEquals(0, due.first().attempts)
    }

    @Test
    fun `超龄条目被淘汰`() {
        val store = QqInboxStore(tmp.newFolder())
        store.enqueue(msg(at = t0))
        assertTrue(store.due(t0 + QqInboxStore.MAX_AGE_MS + 1).isEmpty())
        assertEquals(0, store.size())
    }

    @Test
    fun `水位只增不减且持久化`() {
        val dir = tmp.newFolder()
        val store = QqInboxStore(dir)
        store.markSeen(t0)
        store.markSeen(t0 - 1000) // 回退无效
        assertEquals(t0, store.lastSeenAtMs())
        assertEquals(t0, QqInboxStore(dir).lastSeenAtMs())
    }

    @Test
    fun `损坏文件不影响启动`() {
        val dir = tmp.newFolder()
        File(dir, QqInboxStore.FILE_NAME).writeText("{ not json")
        val store = QqInboxStore(dir)
        assertEquals(0, store.size())
        assertTrue(store.enqueue(msg()))
    }

    @Test
    fun `超过上限淘汰最旧`() {
        val store = QqInboxStore(tmp.newFolder())
        repeat(QqInboxStore.MAX_ITEMS + 5) { i ->
            store.enqueue(msg(id = "m$i", at = t0 + i))
        }
        assertEquals(QqInboxStore.MAX_ITEMS, store.size())
    }

    // ==================== 结果分流 ====================

    @Test
    fun `已发送视为完成`() {
        assertEquals(
            QqInboxPolicy.Decision.DONE,
            QqInboxPolicy.decide(QqReplyOutcome.Kind.SENT, "")
        )
    }

    @Test
    fun `失败必须重试`() {
        assertEquals(
            QqInboxPolicy.Decision.RETRY,
            QqInboxPolicy.decide(QqReplyOutcome.Kind.FAILED, "回复生成失败（端点/模型异常）")
        )
    }

    @Test
    fun `通道离线与节流都挂起而不是丢弃`() {
        listOf(
            "等待协议端事件",
            "QQ 已离线（需在 PC 上重新登录协议端）",
            "节流：与上次回复间隔过短（防重复发送）",
            "节流：短时间内回复过于频繁",
            "未读取到会话消息"
        ).forEach { reason ->
            assertEquals(
                "原因「$reason」必须挂起",
                QqInboxPolicy.Decision.HOLD,
                QqInboxPolicy.decide(QqReplyOutcome.Kind.SKIPPED, reason)
            )
        }
    }

    @Test
    fun `已回应过与自身消息视为完成`() {
        listOf("该消息已回应过", "最后一条为自己发出").forEach { reason ->
            assertEquals(
                QqInboxPolicy.Decision.DONE,
                QqInboxPolicy.decide(QqReplyOutcome.Kind.SKIPPED, reason)
            )
        }
    }
}
