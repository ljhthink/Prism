package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqFailureClassifier] 单元测试（ADR-063 修订 2026-10-25；PRD US-3108 AC-6）。
 *
 * 核心回归点：环境类失败（熄屏/执行面/通道）**不得**计入熔断——否则用户睡觉时
 * 功能会被自己关掉，且 UI 只说"已熔断"，无法定位。
 */
class QqFailureClassifierTest {

    @Test
    fun `屏幕熄灭被归类且不计熔断`() {
        val kind = QqFailureClassifier.classify("跳过：屏幕已熄灭，且当前策略为「熄屏跳过」")
        assertEquals(QqFailureClassifier.Kind.SCREEN_OFF_BLOCKED, kind)
        assertFalse(QqFailureClassifier.countsTowardCircuit(kind))
        assertTrue(QqFailureClassifier.isTransientEnvironmentIssue(kind))
    }

    @Test
    fun `执行面不可用被归类且不计熔断`() {
        listOf(
            "错误：后台虚拟屏暂无可用画面（目标 App 可能尚未渲染）",
            "错误：后台执行面画面已冻结（约 12s 无新帧）",
            "错误：后台虚拟屏未创建"
        ).forEach { text ->
            val kind = QqFailureClassifier.classify(text)
            assertEquals("文本=$text", QqFailureClassifier.Kind.DISPLAY_UNAVAILABLE, kind)
            assertFalse(QqFailureClassifier.countsTowardCircuit(kind))
        }
    }

    @Test
    fun `通道不可用被归类且不计熔断`() {
        listOf(
            "错误：未配置 access_token（协议端必须鉴权）",
            "错误：手机操控无障碍服务未开启",
            "跳过：当前无可用 QQ 通道"
        ).forEach { text ->
            val kind = QqFailureClassifier.classify(text)
            assertEquals("文本=$text", QqFailureClassifier.Kind.CHANNEL_UNAVAILABLE, kind)
            assertFalse(QqFailureClassifier.countsTowardCircuit(kind))
        }
    }

    @Test
    fun `模型失败计入熔断`() {
        val kind = QqFailureClassifier.classify("错误：无激活的 LLM Provider，无法生成回复")
        assertEquals(QqFailureClassifier.Kind.LLM_FAILED, kind)
        assertTrue(QqFailureClassifier.countsTowardCircuit(kind))
    }

    @Test
    fun `发送被拒计入熔断`() {
        val kind = QqFailureClassifier.classify("错误：协议端发送失败（风控，retcode=1200）")
        assertEquals(QqFailureClassifier.Kind.SEND_REJECTED, kind)
        assertTrue(QqFailureClassifier.countsTowardCircuit(kind))
    }

    @Test
    fun `目标解析失败不计熔断`() {
        val kind = QqFailureClassifier.classify("错误：协议端未解析到「S***」的 QQ 号。请在小号好友列表中确认备注名")
        assertEquals(QqFailureClassifier.Kind.TARGET_NOT_FOUND, kind)
        assertFalse(QqFailureClassifier.countsTowardCircuit(kind))
    }

    @Test
    fun `未知文本按 UNKNOWN 且不计熔断`() {
        val kind = QqFailureClassifier.classify("")
        assertEquals(QqFailureClassifier.Kind.UNKNOWN, kind)
        assertFalse(QqFailureClassifier.countsTowardCircuit(kind))
    }

    // ==================== guardrail G-08 回归锁 ====================

    @Test
    fun `被动回复因环境跳过时不得累计熔断（G-08）`() {
        // 这些正是被动回复在夜间会遇到的结果文案；它们必须**全部**不计熔断，
        // 否则用户睡觉时"跳过"几次就会把功能熔断掉（且 UI 只会显示"已熔断"）。
        val environmentResults = listOf(
            "跳过：屏幕已熄灭（本次不允许唤醒屏幕）",
            "跳过：缺少「显示在其他应用上层」权限（后台启动执行面所需）",
            "跳过：Shizuku 未就绪（跨用户启动与手势注入所需）",
            "跳过：无障碍服务未开启（QQ 小号助手依赖它操作 QQ 界面）",
            "错误：后台虚拟屏暂无可用画面（目标 App 可能尚未渲染）"
        )
        environmentResults.forEach { text ->
            val kind = QqFailureClassifier.classify(text)
            assertFalse("不应计熔断：$text（classify=$kind）", QqFailureClassifier.countsTowardCircuit(kind))
        }
    }

    @Test
    fun `只有模型与发送被拒两类计入熔断`() {
        QqFailureClassifier.Kind.entries.forEach { kind ->
            val expected = kind == QqFailureClassifier.Kind.LLM_FAILED ||
                kind == QqFailureClassifier.Kind.SEND_REJECTED
            assertEquals("kind=$kind", expected, QqFailureClassifier.countsTowardCircuit(kind))
        }
    }

    // ==================== 2026-10-25 真机实证：未确认送达 ====================

    @Test
    fun `无障碍已点击发送但未能确认时归类为未确认送达`() {
        val text = "⚠️ 已点击发送，但未能从聊天记录确认消息已出现（可能因屏幕刷新延迟）。" +
            "不要盲目重发（重复消息会显得不自然）"
        val kind = QqFailureClassifier.classify(text)
        assertEquals(QqFailureClassifier.Kind.SEND_UNCONFIRMED, kind)
    }

    @Test
    fun `未确认送达既不熔断也不可重试（防重复消息）`() {
        val kind = QqFailureClassifier.Kind.SEND_UNCONFIRMED
        assertFalse("不得熔断", QqFailureClassifier.countsTowardCircuit(kind))
        assertFalse("绝不能重试——消息很可能已送达", QqFailureClassifier.isRetryable(kind))
        assertTrue(QqFailureClassifier.isUnconfirmedDelivery(kind))
    }

    @Test
    fun `语音未确认同样按未确认送达处理`() {
        val kind = QqFailureClassifier.classify(
            "⚠️ 语音发送未确认（timeout）；为避免重复发送，请勿自动重试，可改用 qq__send_message"
        )
        assertEquals(QqFailureClassifier.Kind.SEND_UNCONFIRMED, kind)
        assertFalse(QqFailureClassifier.isRetryable(kind))
    }

    @Test
    fun `环境类可重试而明确拒绝不可重试`() {
        assertTrue(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.SCREEN_OFF_BLOCKED))
        assertTrue(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.DISPLAY_UNAVAILABLE))
        assertTrue(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.CHANNEL_UNAVAILABLE))
        assertFalse(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.SEND_REJECTED))
    }

    // ==================== guardrail G-r3-06 / G-r3-11 ====================

    @Test
    fun `协议端拒绝文案即使内嵌未确认子串也不得降级为未确认（G-r3-06）`() {
        // OneBotQqChannel 会把协议端 message 插值进错误文案；contains 匹配会让"确定拒绝"
        // 变成"未确认"（既不重试也不熔断 → 熔断永不触发）。必须靠固定前缀区分。
        val rejected = "错误：协议端发送失败（已点击发送，但未能从聊天记录确认，retcode=1200）"
        val kind = QqFailureClassifier.classify(rejected)
        assertEquals("前缀不是未确认固定文案 → 必须按确定拒绝", QqFailureClassifier.Kind.SEND_REJECTED, kind)
        assertTrue("确定拒绝必须计入熔断", QqFailureClassifier.countsTowardCircuit(kind))
    }

    @Test
    fun `只有环境类可重试（G-r3-11 收窄）`() {
        // UNKNOWN / TARGET_NOT_FOUND / LLM_FAILED 重试只会再调一次 LLM 生成文案，收益极低
        assertFalse(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.UNKNOWN))
        assertFalse(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.TARGET_NOT_FOUND))
        assertFalse(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.LLM_FAILED))
        assertFalse(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.SEND_UNCONFIRMED))
        QqFailureClassifier.Kind.entries.forEach { k ->
            assertEquals(
                "isRetryable 必须严格等于环境类：$k",
                QqFailureClassifier.isTransientEnvironmentIssue(k),
                QqFailureClassifier.isRetryable(k)
            )
        }
    }
}
