package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqPageState] 单测（PRD US-3118；2026-10-26 真机反馈）。
 *
 * 真机取证：小号 QQ 停在「安全提醒：你的账号身份验证信息已失效」对话框，
 * 导致 `openConversation` 失败并被误判成"目标找不到/可重试"。
 */
class QqPageStateTest {

    @Test
    fun `真机原文命中账号验证态`() {
        val kind = QqPageState.detect(
            dialogTitle = "安全提醒",
            dialogText = "你的账号身份验证信息已失效，为了保证你的账号及资"
        )
        assertEquals(QqPageState.Kind.ACCOUNT_VERIFICATION_REQUIRED, kind)
        val msg = QqPageState.blockMessage(kind)!!
        assertTrue(msg.contains("重新验证"))
        assertTrue("必须说明不重试不占配额", msg.contains("不会重试"))
    }

    @Test
    fun `只有标题也足以判定（正文可能被截断）`() {
        assertEquals(
            QqPageState.Kind.ACCOUNT_VERIFICATION_REQUIRED,
            QqPageState.detect("安全提醒", null)
        )
        assertEquals(
            QqPageState.Kind.ACCOUNT_VERIFICATION_REQUIRED,
            QqPageState.detect(null, "请前往验证后继续")
        )
    }

    @Test
    fun `登录页判定为未登录`() {
        val kind = QqPageState.detect(null, "请输入QQ密码登录")
        assertEquals(QqPageState.Kind.NOT_LOGGED_IN, kind)
        assertTrue(QqPageState.blockMessage(kind)!!.contains("未登录"))
    }

    @Test
    fun `账号验证优先于登录（两者关键词同时出现时）`() {
        // 现实文案常同时含"验证"与"登录"，必须先判账号验证才能给出正确引导
        val kind = QqPageState.detect("安全提醒", "身份验证已失效，请重新登录")
        assertEquals(QqPageState.Kind.ACCOUNT_VERIFICATION_REQUIRED, kind)
    }

    @Test
    fun `正常页面不误报`() {
        assertEquals(QqPageState.Kind.NORMAL, QqPageState.detect(null, null))
        assertEquals(QqPageState.Kind.NORMAL, QqPageState.detect("", "  "))
        assertEquals(QqPageState.Kind.NORMAL, QqPageState.detect("Sisyphus", "晚上吃什么"))
        assertNull(QqPageState.blockMessage(QqPageState.Kind.NORMAL))
    }

    @Test
    fun `页面文本兜底可用`() {
        val kind = QqPageState.detect(dialogTitle = null, dialogText = null, pageText = "账号身份验证信息已失效")
        assertEquals(QqPageState.Kind.ACCOUNT_VERIFICATION_REQUIRED, kind)
    }

    @Test
    fun `阻断文案不含账号标识或聊天内容`() {
        listOf(QqPageState.Kind.ACCOUNT_VERIFICATION_REQUIRED, QqPageState.Kind.NOT_LOGGED_IN)
            .forEach { kind ->
                val msg = QqPageState.blockMessage(kind)
                assertNotNull(msg)
                assertFalse("不得出现 QQ 号：$msg", Regex("\\d{6,}").containsMatchIn(msg!!))
            }
    }
}
