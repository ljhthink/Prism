package io.prism.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqReplyDedup] 幂等判定测试（US-3111 AC-3；guardrail L-07）。
 */
class QqReplyDedupTest {

    @Test
    fun `协议端相同 message_id 判为重复（跨重启有效）`() {
        assertTrue(
            QqReplyDedup.isDuplicate(
                messageId = "42", lastMessageId = "42",
                hash = 1, lastHash = 0, lastRepliedAtMs = 0, nowMs = 0
            )
        )
    }

    @Test
    fun `协议端不同 message_id 不判重复（即使正文相同）`() {
        assertFalse(
            QqReplyDedup.isDuplicate(
                messageId = "43", lastMessageId = "42",
                hash = 1, lastHash = 1, lastRepliedAtMs = 0, nowMs = 0
            )
        )
    }

    @Test
    fun `A-B-重复A 也必须判重（D-03 最近 ID 集合）`() {
        // 最近已回应 A 与 B；重复投递 A 时不得因"最后一条是 B"而再次回应
        assertTrue(
            QqReplyDedup.isDuplicate(
                messageId = "A", lastMessageId = "B",
                hash = 0, lastHash = 0, lastRepliedAtMs = 0, nowMs = 0,
                repliedMessageIds = listOf("A", "B")
            )
        )
        assertFalse(
            QqReplyDedup.isDuplicate(
                messageId = "C", lastMessageId = "B",
                hash = 0, lastHash = 0, lastRepliedAtMs = 0, nowMs = 0,
                repliedMessageIds = listOf("A", "B")
            )
        )
    }

    @Test
    fun `无障碍通道同文在时间窗内判为重复`() {
        assertTrue(
            QqReplyDedup.isDuplicate(
                messageId = null, lastMessageId = "",
                hash = 7, lastHash = 7,
                lastRepliedAtMs = 1_000, nowMs = 1_000 + QqReplyDedup.HASH_WINDOW_MS - 1
            )
        )
    }

    @Test
    fun `无障碍通道同文超出时间窗视为新消息`() {
        assertFalse(
            QqReplyDedup.isDuplicate(
                messageId = null, lastMessageId = "",
                hash = 7, lastHash = 7,
                lastRepliedAtMs = 1_000, nowMs = 1_000 + QqReplyDedup.HASH_WINDOW_MS
            )
        )
    }

    @Test
    fun `不同文本不判重复`() {
        assertFalse(
            QqReplyDedup.isDuplicate(
                messageId = null, lastMessageId = "",
                hash = 8, lastHash = 7, lastRepliedAtMs = 1_000, nowMs = 1_001
            )
        )
    }
}
