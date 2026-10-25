package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqReplyOutcome] 三态判定单测（ac-verifier **D-01** 回归锁）。
 *
 * 缺陷：被动应答把「跳过：未读取到会话消息」判成**成功**——真机日志写"成功"，
 * 但 NapCat 侧无任何出站消息，破坏 US-3109 AC-1 的送达证据与 US-3115 AC-6 的状态可见。
 * 修复：应答器返回**结构化三态**，日志按三态输出，不再用字符串前缀反推。
 */
class QqReplyOutcomeTest {

    @Test
    fun `未发送一律是 SKIPPED（不得记成成功）`() {
        listOf(
            "未读取到会话消息",
            "该消息已回应过",
            "最后一条为自己发出",
            "等待协议端事件",
            "节流：未达最小间隔（还需约 3 分钟）",
            "屏幕已熄灭（本次不允许唤醒屏幕）"
        ).forEach { reason ->
            val r = QqReplyOutcome.skipped(reason)
            assertEquals("reason=$reason", QqReplyOutcome.Kind.SKIPPED, r.kind)
            assertTrue("日志必须写明跳过而非成功：${r.logLine}", r.logLine.contains("跳过"))
            assertFalse("跳过的日志不得出现「成功」：${r.logLine}", r.logLine.contains("成功"))
        }
    }

    @Test
    fun `已发送是 SENT（含未确认送达）`() {
        assertEquals(
            QqReplyOutcome.Kind.SENT,
            QqReplyOutcome.of(sent = true, skippedReason = null, errorResult = null)
        )
        // 未确认送达仍算"已产出出站动作"
        assertEquals(
            QqReplyOutcome.Kind.SENT,
            QqReplyOutcome.of(
                sent = true,
                skippedReason = null,
                errorResult = QqLocalToolExecutor.MSG_SEND_UNCONFIRMED_PREFIX + "（可能因屏幕刷新延迟）"
            )
        )
    }

    @Test
    fun `明确失败是 FAILED`() {
        assertEquals(
            QqReplyOutcome.Kind.FAILED,
            QqReplyOutcome.of(
                sent = true,
                skippedReason = null,
                errorResult = "错误：协议端发送失败（retcode=1200）"
            )
        )
    }

    @Test
    fun `没发送时即使带错误文案也只算 SKIPPED`() {
        // 例：无 Provider 时根本没调用 send——这是"没发"，不是"发失败"
        assertEquals(
            QqReplyOutcome.Kind.SKIPPED,
            QqReplyOutcome.of(sent = false, skippedReason = "未配置目标联系人", errorResult = null)
        )
    }

    @Test
    fun `日志行含失败类别但不含正文`() {
        val line = QqReplyOutcome.failed("无激活的 LLM Provider，无法生成回复").logLine
        assertTrue(line.contains("失败"))
        assertTrue("应带可诊断原因", line.contains("LLM Provider"))
        // 三态标签互斥
        assertFalse(line.contains("成功"))
    }

    @Test
    fun `三态标签唯一且可区分`() {
        val labels = QqReplyOutcome.Kind.entries.map { QqReplyOutcome.label(it) }
        assertEquals("标签必须两两不同", labels.size, labels.distinct().size)
    }
}
