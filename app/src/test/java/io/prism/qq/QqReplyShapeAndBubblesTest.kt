package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-076 纯函数单测：回复形状抽样、气泡拆分、节流挂起时长。
 *
 * 背景（真机取证 2026-11-15）：
 * - 每条回复都是「20–40 字单段 + 问句收尾」→ 形状由代码挑，不再让模型"自己注意多样"；
 * - 真人在 QQ 上是一串短句 → 允许 `---` 分条；
 * - `PASSIVE_MIN_GAP_MS=3s` 却被挂起 60s → 节流类改短重试。
 */
class QqReplyShapeAndBubblesTest {

    // ==================== QqReplyShape ====================

    @Test
    fun `形状池非空且抽取值落在池内`() {
        val seen = mutableSetOf<String>()
        repeat(QqReplyShape.size * 5) { seen += QqReplyShape.pick() }
        assertEquals("抽到的形状必须全部来自形状池", QqReplyShape.size, seen.size)
    }

    @Test
    fun `形状提示不得规定字数上限`() {
        // 与 persona「不许一律短」冲突的硬性字数约束是本次修复的核心；形状提示里禁止出现
        repeat(QqReplyShape.size * 3) { attempt ->
            val shape = QqReplyShape.pick()
            assertFalse(
                "形状提示不得出现「字」数硬约束：$shape",
                Regex("""\d+\s*字""").containsMatchIn(shape)
            )
            assertFalse("形状提示不得要求一律简短：$shape", shape.contains("简短"))
        }
    }

    // ==================== QqReplyBubbles ====================

    @Test
    fun `无分隔符时原样返回一条`() {
        assertEquals(listOf("在呀，说吧。"), QqReplyBubbles.split("在呀，说吧。"))
    }

    @Test
    fun `空白输入返回空列表`() {
        assertTrue(QqReplyBubbles.split("   \n  ").isEmpty())
    }

    @Test
    fun `按分隔行拆成多条气泡`() {
        val text = "那先别说话，躺一会儿。\n---\n等你缓过来再讲。"
        assertEquals(
            listOf("那先别说话，躺一会儿。", "等你缓过来再讲。"),
            QqReplyBubbles.split(text)
        )
    }

    @Test
    fun `分隔行允许前后空白与更多连字符`() {
        val text = "A\n  -----  \nB"
        assertEquals(listOf("A", "B"), QqReplyBubbles.split(text))
    }

    @Test
    fun `正文里的破折号与减号不算分隔`() {
        val text = "这个——那个，还有 a-b 和 1 - 2"
        assertEquals(listOf(text), QqReplyBubbles.split(text))
    }

    @Test
    fun `超过上限时保留前N_1条并把其余并入最后一条（不丢内容）`() {
        val text = listOf("一", "二", "三", "四", "五").joinToString("\n---\n")
        val bubbles = QqReplyBubbles.split(text)
        assertEquals(QqReplyBubbles.MAX_BUBBLES, bubbles.size)
        assertTrue("末尾必须合并剩余内容", bubbles.last().contains("四") && bubbles.last().contains("五"))
    }

    @Test
    fun `单个气泡超长时整段发送（说明模型没按约定分条）`() {
        val long = "啊".repeat(QqReplyBubbles.MAX_BUBBLE_CHARS + 1)
        val text = "$long\n---\n短"
        val bubbles = QqReplyBubbles.split(text)
        assertEquals("超长气泡判定为未按约定分条 → 合成一条", 1, bubbles.size)
        assertTrue(bubbles.first().contains("短"))
    }

    @Test
    fun `空分隔段被丢弃`() {
        val text = "A\n---\n\n---\nB"
        assertEquals(listOf("A", "B"), QqReplyBubbles.split(text))
    }

    @Test
    fun `要么多气泡全部合规，要么整段单条（不存在"超长多气泡"）`() {
        // guardrail L-01：合并分支曾绕过长度检查。现在的契约是**二选一**：
        // - 拆分成功 → 每条都 ≤ MAX_BUBBLE_CHARS 且 ≤ MAX_BUBBLES；
        // - 只要有任何一条超长 → 退回**单条整段**（不切碎语义，长度由调用方 MAX_LEN 约束）。
        val seg = "啊".repeat(60)
        val text = (1..6).joinToString("\n---\n") { seg }
        val bubbles = QqReplyBubbles.split(text)
        assertTrue("最多 3 条", bubbles.size <= QqReplyBubbles.MAX_BUBBLES)
        if (bubbles.size > 1) {
            assertTrue(
                "多气泡形态下每条都必须合规（实测 ${bubbles.map { it.length }}）",
                bubbles.all { it.length <= QqReplyBubbles.MAX_BUBBLE_CHARS }
            )
        } else {
            assertTrue("整段兜底必须保留全部内容", bubbles.single().contains(seg))
        }
    }

    // ==================== QqThrottle.availableSendSlots（guardrail M-03） ====================

    @Test
    fun `被动窗口剩余额度按真实外发次数计算`() {
        val now = 1_000_000L
        val empty = io.prism.qq.QqRuntimeState()
        assertEquals(
            QqThrottle.PASSIVE_MAX_IN_WINDOW,
            QqThrottle.availableSendSlots(empty, now)
        )
        val busy = io.prism.qq.QqRuntimeState(
            passiveReplyTimestamps = List(QqThrottle.PASSIVE_MAX_IN_WINDOW - 2) { now - 1_000L }
        )
        assertEquals(
            "窗口内已有 N 条 → 只剩 (上限-N) 条额度（气泡数必须据此截断）",
            2,
            QqThrottle.availableSendSlots(busy, now)
        )
    }

    @Test
    fun `窗口额度用尽时仍至少保留一条（不得因额度为 0 完全不说话）`() {
        val now = 1_000_000L
        val full = io.prism.qq.QqRuntimeState(
            passiveReplyTimestamps = List(QqThrottle.PASSIVE_MAX_IN_WINDOW) { now - 1_000L }
        )
        assertEquals(1, QqThrottle.availableSendSlots(full, now))
    }

    @Test
    fun `窗口外的旧时间戳不计入额度`() {
        val now = 1_000_000L
        val stale = io.prism.qq.QqRuntimeState(
            passiveReplyTimestamps = List(QqThrottle.PASSIVE_MAX_IN_WINDOW) {
                now - QqThrottle.PASSIVE_WINDOW_MS - 1_000L
            }
        )
        assertEquals(
            QqThrottle.PASSIVE_MAX_IN_WINDOW,
            QqThrottle.availableSendSlots(stale, now)
        )
    }

    // ==================== QqInboxPolicy.holdMs ====================

    @Test
    fun `节流类挂起用短重试而非60秒`() {
        val reason = "节流：与上次回复间隔过短（防重复发送）"
        assertEquals(QqInboxPolicy.THROTTLE_HOLD_MS, QqInboxPolicy.holdMs(reason))
        assertTrue(
            "短重试必须略大于 PASSIVE_MIN_GAP_MS(3s)，否则必然再次被拒",
            QqInboxPolicy.THROTTLE_HOLD_MS > QqThrottle.PASSIVE_MIN_GAP_MS
        )
    }

    @Test
    fun `通道类挂起保持60秒`() {
        listOf(
            "QQ 已离线（需在 PC 上重新登录协议端）",
            "等待协议端事件",
            "屏幕已熄灭（本次不允许唤醒屏幕）",
            "未配置目标联系人"
        ).forEach {
            assertEquals(it, QqInboxPolicy.CHANNEL_HOLD_MS, QqInboxPolicy.holdMs(it))
        }
    }

    @Test
    fun `频率类跳过仍是挂起（不得判成完成）`() {
        assertEquals(
            QqInboxPolicy.Decision.HOLD,
            QqInboxPolicy.decide(QqReplyOutcome.Kind.SKIPPED, "节流：与上次回复间隔过短（防重复发送）")
        )
    }
}
