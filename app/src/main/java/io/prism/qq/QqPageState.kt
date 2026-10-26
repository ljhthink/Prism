package io.prism.qq

/**
 * QQ 执行面**页面态**判定（PRD US-3118；2026-10-26 真机反馈）。
 *
 * **缺陷背景**：真机取证时小号 QQ 停在「安全提醒：你的账号身份验证信息已失效」对话框上，
 * 于是 `openConversation` 找不到会话、发送失败——但错误被归成"目标找不到/可重试"，
 * 既反复重试又反复调用 LLM，用户只看到"发不出去"。
 *
 * **为什么做成纯函数**：页面态判定是"错误归因"的入口，一旦判错就会走错重试/熔断策略；
 * 用纯函数把关键词、优先级、空输入全部穷举，便于 JVM 单测（真机只验证端到端）。
 */
object QqPageState {

    /** 页面态。 */
    enum class Kind {
        /** 正常（消息列表 / 聊天页）。 */
        NORMAL,

        /** 需要账号验证（安全提醒 / 身份验证失效 / 前往验证）。 */
        ACCOUNT_VERIFICATION_REQUIRED,

        /** 未登录（登录页 / 扫码页 / 一键登录）。 */
        NOT_LOGGED_IN
    }

    /**
     * 判定执行面当前的阻断性页面态。
     *
     * 判定顺序（**优先级不可调换**）：
     * 1. 账号验证类优先——它出现时往往同时有"登录/验证"字样，先判它才能给出正确引导；
     * 2. 未登录次之；
     * 3. 其余为正常。
     *
     * @param dialogTitle 执行面树的 `dialogTitle` 文本（无对话框传 null）
     * @param dialogText `dialogText` 文本
     * @param pageText 兜底：整页关键文本（可为空；用于无语义 id 的自绘弹窗）
     */
    fun detect(dialogTitle: String?, dialogText: String?, pageText: String? = null): Kind {
        val blob = listOfNotNull(dialogTitle, dialogText, pageText)
            .joinToString(" ")
            .trim()
        if (blob.isEmpty()) return Kind.NORMAL

        if (VERIFY_KEYWORDS.any { blob.contains(it) }) return Kind.ACCOUNT_VERIFICATION_REQUIRED
        if (LOGIN_KEYWORDS.any { blob.contains(it) }) return Kind.NOT_LOGGED_IN
        return Kind.NORMAL
    }

    /**
     * 面向用户/LLM 的可读原因（不含任何账号标识或聊天内容）。
     *
     * @return null=正常，无需拦截
     */
    fun blockMessage(kind: Kind): String? = when (kind) {
        Kind.NORMAL -> null
        Kind.ACCOUNT_VERIFICATION_REQUIRED ->
            "小号登录态失效：QQ 要求重新验证身份（安全提醒）。请在手机上的小号 QQ 中完成验证后再试" +
                "（本次不会重试、不消耗配额，也不计入熔断）"
        Kind.NOT_LOGGED_IN ->
            "小号未登录：QQ 停在登录页。请先在小号 QQ 中完成登录后再试" +
                "（本次不会重试、不消耗配额，也不计入熔断）"
    }

    /** 账号验证类关键词（真机实测文案 + 常见变体）。 */
    private val VERIFY_KEYWORDS = listOf(
        "安全提醒",
        "身份验证",
        "验证信息已失效",
        "账号身份验证",
        "前往验证",
        "重新验证"
    )

    /** 未登录类关键词。 */
    private val LOGIN_KEYWORDS = listOf(
        "登录",
        "扫码",
        "密码",
        "一键登录",
        "手机号登录"
    )
}
