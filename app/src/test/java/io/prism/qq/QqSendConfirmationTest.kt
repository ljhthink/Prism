package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqSendConfirmation] 单测（PRD US-3118 AC-4；guardrail **G-03 / R2-01 / R3-01**）。
 *
 * 这一条判据的方向是"漏发会被报成已发送且不重试"——比误报未确认严重得多，
 * 因此必须把 `null` / 未输入 / 读到空白 / 节点消失 / 仍留原文 全部钉住。
 */
class QqSendConfirmationTest {

    private val fragment = "晚上一起吃饭吗"

    @Test
    fun `节点已消失时绝不算已清空`() {
        assertFalse(
            QqSendConfirmation.isInputCleared(
                inputNodePresent = false,
                inputText = null,
                typedFragment = fragment,
                beforeText = fragment
            )
        )
        assertFalse(
            QqSendConfirmation.isInputCleared(
                inputNodePresent = false,
                inputText = fragment,
                typedFragment = fragment,
                beforeText = fragment
            )
        )
    }

    @Test
    fun `节点仍在但文本为 null 视为已清空（QQ 空输入框聚合文本为空，R2-01）`() {
        assertTrue(
            QqSendConfirmation.isInputCleared(
                inputNodePresent = true,
                inputText = null,
                typedFragment = fragment,
                beforeText = fragment
            )
        )
    }

    @Test
    fun `发送前没内容时不能算清空（空是初始状态而非证据）`() {
        assertFalse(
            QqSendConfirmation.isInputCleared(true, "", fragment, beforeText = null)
        )
        assertFalse(
            QqSendConfirmation.isInputCleared(true, "", fragment, beforeText = "   ")
        )
    }

    @Test
    fun `发送前内容不含我们输入的片段时不算清空`() {
        assertFalse(
            QqSendConfirmation.isInputCleared(true, "", fragment, beforeText = "别的草稿内容")
        )
    }

    @Test
    fun `正向：发送前含片段、发送后确实为空`() {
        assertTrue(
            QqSendConfirmation.isInputCleared(true, "", fragment, beforeText = fragment)
        )
        assertTrue(
            QqSendConfirmation.isInputCleared(true, "   \n ", fragment, beforeText = "前缀$fragment")
        )
    }

    @Test
    fun `输入框仍保留原文必须报未确认（R2-01 反向证据）`() {
        assertFalse(
            QqSendConfirmation.isInputCleared(true, fragment, fragment, beforeText = fragment)
        )
        assertFalse(
            QqSendConfirmation.isInputCleared(true, "前缀$fragment", fragment, beforeText = fragment)
        )
    }

    @Test
    fun `判据强度顺序：气泡优先于输入框清空优先于页面文本`() {
        assertEquals(
            QqSendConfirmation.Evidence.BUBBLE,
            QqSendConfirmation.decide(bubbleConfirmed = true, inputCleared = true, pageTextHasFragment = true)
        )
        assertEquals(
            QqSendConfirmation.Evidence.INPUT_CLEARED,
            QqSendConfirmation.decide(bubbleConfirmed = false, inputCleared = true, pageTextHasFragment = true)
        )
        assertEquals(
            QqSendConfirmation.Evidence.PAGE_TEXT,
            QqSendConfirmation.decide(bubbleConfirmed = false, inputCleared = false, pageTextHasFragment = true)
        )
        assertEquals(
            QqSendConfirmation.Evidence.NONE,
            QqSendConfirmation.decide(false, false, false)
        )
    }

    @Test
    fun `只有拿到证据才算已发送`() {
        QqSendConfirmation.Evidence.entries.forEach { e ->
            assertEquals(e != QqSendConfirmation.Evidence.NONE, QqSendConfirmation.isSent(e))
        }
    }

    @Test
    fun `R3-01 场景：寻址基准不一致会让"仍有原文"被误判为已发送（用例文档化）`() {
        // 若用"发送前 BFS 序号"读文本，树形变后可能落到无文本子树 → 文本为 null。
        // 生产代码已改为 textOfViewId 同一次遍历取 (存在性,文本)，故 null 只在
        // "节点仍在且聚合文本为空"时出现，那时确实代表输入框已空。
        // 反面：若存在性来自 viewId、文本来自旧序号，就会出现 present=true + null 的错配。
        // 这里用"present=true,text=原文"钉住正确语义：
        assertFalse(
            "输入框仍有原文时绝不能判为已发送",
            QqSendConfirmation.isInputCleared(true, fragment, fragment, beforeText = fragment)
        )
    }
}
