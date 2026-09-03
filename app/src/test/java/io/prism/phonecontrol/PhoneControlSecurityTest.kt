package io.prism.phonecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhoneControlSecurity] 单元测试（v1 US-202，纯函数可测）。
 *
 * 覆盖拦截分层：金融 App 启动黑名单 / 敏感目标关键词硬拦截 / 高危动作强制 MANUAL /
 * 密码/验证码节点 / 凭据输入拦截。
 */
class PhoneControlSecurityTest {

    // ==================== 金融 App 启动黑名单 ====================

    @Test
    fun `financial apps are blocked for launch`() {
        assertTrue(PhoneControlSecurity.isSensitivePackage("com.eg.android.AlipayGphone"))
        assertTrue(PhoneControlSecurity.isSensitivePackage("com.unionpay"))
        assertTrue(PhoneControlSecurity.isSensitivePackage("com.cmbchina.ccd.pluto.cmbActivity"))
        assertTrue(PhoneControlSecurity.isSensitivePackage("com.paytm.app"))
        // guardrail P0（TKN-V1B12-GUARDRAIL-001）：包名映射库纠正后仍须拦截（真机实证包名）
        assertTrue("招商银行 cmb.pb 必须拦截", PhoneControlSecurity.isSensitivePackage("cmb.pb"))
        assertTrue("中国银行主流包名必须拦截", PhoneControlSecurity.isSensitivePackage("com.chinamworld.bocmbci"))
        assertTrue("建设银行必须拦截", PhoneControlSecurity.isSensitivePackage("com.chinamworld.main"))
        assertTrue("工商银行必须拦截", PhoneControlSecurity.isSensitivePackage("com.icbc"))
    }

    @Test
    fun `wechat and qq are not blocked for launch`() {
        // US-201 关键用例：打开微信搜索 —— 微信/QQ 是通讯+支付双用途 App，启动不拦截，
        // 其内部支付/转账按钮在节点层由 isSensitiveTargetText 硬拦截。
        assertFalse(PhoneControlSecurity.isSensitivePackage("com.tencent.mm"))
        assertFalse(PhoneControlSecurity.isSensitivePackage("com.tencent.mobileqq"))
    }

    @Test
    fun `non-sensitive and null package are allowed`() {
        assertFalse(PhoneControlSecurity.isSensitivePackage("com.example.app"))
        assertFalse(PhoneControlSecurity.isSensitivePackage(null))
    }

    // ==================== 密码/验证码节点 ====================

    @Test
    fun `password node detected via flag`() {
        val node = UiNode(
            nid = 0, viewId = null, text = null, contentDescription = null,
            className = "android.widget.EditText", bounds = null,
            clickable = false, longClickable = false, scrollable = false,
            editable = true, password = true, children = emptyList()
        )
        assertTrue(PhoneControlSecurity.isPasswordNode(node))
    }

    @Test
    fun `password node detected via class name`() {
        val node = UiNode(
            nid = 0, viewId = null, text = null, contentDescription = null,
            className = "com.example.PasswordInput", bounds = null,
            clickable = false, longClickable = false, scrollable = false,
            editable = true, password = false, children = emptyList()
        )
        assertTrue(PhoneControlSecurity.isPasswordNode(node))
    }

    @Test
    fun `verification code hint detected as sensitive node`() {
        val node = UiNode(
            nid = 0, viewId = null, text = "请输入验证码", contentDescription = null,
            className = "android.widget.EditText", bounds = null,
            clickable = false, longClickable = false, scrollable = false,
            editable = true, password = false, children = emptyList()
        )
        assertTrue(PhoneControlSecurity.isPasswordNode(node))
    }

    @Test
    fun `login and account hints detected as sensitive node`() {
        val node = UiNode(
            nid = 0, viewId = null, text = null, contentDescription = "登录账号",
            className = "android.widget.EditText", bounds = null,
            clickable = false, longClickable = false, scrollable = false,
            editable = true, password = false, children = emptyList()
        )
        assertTrue(PhoneControlSecurity.isPasswordNode(node))
    }

    @Test
    fun `normal edit text is not a password node`() {
        val node = UiNode(
            nid = 0, viewId = null, text = "搜索", contentDescription = null,
            className = "android.widget.EditText", bounds = null,
            clickable = false, longClickable = false, scrollable = false,
            editable = true, password = false, children = emptyList()
        )
        assertFalse(PhoneControlSecurity.isPasswordNode(node))
    }

    // ==================== 敏感目标文本硬拦截 ====================

    @Test
    fun `sensitive target keywords detected`() {
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("确认转账给张三"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("微信支付"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("余额 100 元"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("请输入支付密码"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("红包"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("充值中心"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("银行卡号"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("短信验证码"))
    }

    @Test
    fun `normal target text is not sensitive`() {
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("搜索"))
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("聊天"))
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("返回"))
        assertFalse(PhoneControlSecurity.isSensitiveTargetText(null))
        assertFalse(PhoneControlSecurity.isSensitiveTargetText(""))
    }

    // ==================== 多语言敏感目标（guardrail M-2） ====================

    @Test
    fun `english sensitive target keywords detected`() {
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Pay"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Confirm Payment"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Transfer 100 to John"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Balance: $100"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Enter Password"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Verification Code"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Card Number 6222"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Red Packet"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Top Up"))
    }

    @Test
    fun `english plural forms detected`() {
        // (?:s)? 复数覆盖
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Payments"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Transfers"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Card Numbers"))
    }

    @Test
    fun `english word boundary avoids false positives`() {
        // "pay" 词边界：display/payment/repay 不应因 "pay" 被误拦截（payment 由独立关键词覆盖）
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("Display"))
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("Repay"))
        assertFalse(PhoneControlSecurity.isSensitiveTargetText("Pinch to zoom"))
        // 但独立 "Pay" 按钮应拦截
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("Pay"))
    }

    @Test
    fun `english manual action keywords detected`() {
        assertTrue(PhoneControlSecurity.isManualAction("tap", "Send"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "Delete Message"))
        assertTrue(PhoneControlSecurity.isManualAction("type", "Submit Order"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "Log Out"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "Dial 10086"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "Send SMS"))
    }

    @Test
    fun `english password node hint detected`() {
        val node = UiNode(
            nid = 0, viewId = null, text = null, contentDescription = "Enter Password",
            className = "android.widget.EditText", bounds = null,
            clickable = false, longClickable = false, scrollable = false,
            editable = true, password = false, children = emptyList()
        )
        assertTrue(PhoneControlSecurity.isPasswordNode(node))
    }

    @Test
    fun `english credential input detected`() {
        assertTrue(PhoneControlSecurity.isCredentialInput("password 123456"))
        assertTrue(PhoneControlSecurity.isCredentialInput("verification code 123456"))
        assertTrue(PhoneControlSecurity.isCredentialInput("card number 6222"))
        assertTrue(PhoneControlSecurity.isCredentialInput("OTP 123456"))
        // 词边界：opinion 不应被 "pin" 误判为凭据
        assertFalse(PhoneControlSecurity.isCredentialInput("opinion"))
    }

    // ==================== 高危动作强制 MANUAL ====================

    @Test
    fun `manual action keywords detected`() {
        assertTrue(PhoneControlSecurity.isManualAction("tap", "发送"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "删除"))
        assertTrue(PhoneControlSecurity.isManualAction("type", "提交订单"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "退出登录"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "拨号 10086"))
        assertTrue(PhoneControlSecurity.isManualAction("tap", "发短信"))
    }

    @Test
    fun `normal action is not manual`() {
        assertFalse(PhoneControlSecurity.isManualAction("tap", "搜索"))
        assertFalse(PhoneControlSecurity.isManualAction("tap", "聊天记录"))
        assertFalse(PhoneControlSecurity.isManualAction("swipe", ""))
    }

    // ==================== 凭据输入拦截 ====================

    @Test
    fun `credential input detected`() {
        assertTrue(PhoneControlSecurity.isCredentialInput("密码 123456"))
        assertTrue(PhoneControlSecurity.isCredentialInput("验证码 123456"))
        assertTrue(PhoneControlSecurity.isCredentialInput("银行卡号 6222"))
        assertTrue(PhoneControlSecurity.isCredentialInput("支付密码"))
    }

    @Test
    fun `normal input is not credential`() {
        assertFalse(PhoneControlSecurity.isCredentialInput("你好"))
        assertFalse(PhoneControlSecurity.isCredentialInput("张三"))
        assertFalse(PhoneControlSecurity.isCredentialInput(null))
        assertFalse(PhoneControlSecurity.isCredentialInput(""))
    }
}
