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
 *
 * **真机二次缺陷（2026-10-26 18:27，已修）**：只看"整页文本里有没有『登录』"会把**正常
 * 消息列表**判成未登录——QQ 自带「我的电脑」会话，预览文案就是"已在电脑登录，试试在手机上
 * 访问电脑文件"。于是发送/读取被整体拦死（`结构证据=false`）。
 * 修正原则：**主界面结构证据优先于文本关键词**——只要页面有 QQ 主界面骨架
 * （会话头 / 消息-联系人-动态 标签栏 / 聊天输入框），一律判 NORMAL；
 * 文本兜底只在"完全没有主界面骨架"时才启用，且要求多个关键词同现。
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
     * 执行面结构信号（由 accessibility 层采集，纯数据，便于单测）。
     *
     * @param dialogTitle `dialogTitle` 节点文本（无对话框传 null）——**最强**证据
     * @param dialogText `dialogText` 节点文本
     * @param pageText 整页文本（可为 null；仅在无主界面骨架时参与判定）
     * @param hasMainUi 页面是否存在 QQ 主界面骨架（会话头 / 三标签栏 / 聊天输入框）
     */
    data class Signals(
        val dialogTitle: String? = null,
        val dialogText: String? = null,
        val pageText: String? = null,
        val hasMainUi: Boolean = false
    )

    /**
     * 判定执行面当前的阻断性页面态。
     *
     * 判定顺序（**优先级不可调换**）：
     * 1. **对话框结构**（`dialogTitle`/`dialogText` 存在）——命中验证关键词即为账号验证态；
     *    这是真机实测的形态，最可靠。
     * 2. **主界面骨架存在** → 直接 NORMAL。QQ 主界面上的任何文本（会话预览、群名、消息内容）
     *    都不能用来判定登录态——"已在电脑登录""异地登录提醒"都是正常聊天内容。
     * 3. 既无对话框、也无主界面骨架 → 才用整页文本兜底，且要求**≥2 个**登录关键词同现
     *    （单个"登录"太容易误判），或存在显式登录控件文案。
     */
    fun detect(signals: Signals): Kind {
        val title = signals.dialogTitle?.trim().orEmpty()
        val text = signals.dialogText?.trim().orEmpty()
        val dialogBlob = "$title $text".trim()

        // 1) 对话框结构：账号验证优先（它出现时常同时含"登录/验证"字样）
        if (dialogBlob.isNotEmpty()) {
            if (VERIFY_KEYWORDS.any { dialogBlob.contains(it) }) {
                return Kind.ACCOUNT_VERIFICATION_REQUIRED
            }
            if (LOGIN_KEYWORDS.any { dialogBlob.contains(it) }) return Kind.NOT_LOGGED_IN
            // 对话框在但内容无关 → 不是阻断页（可能是普通提示，交上层处理）
            return Kind.NORMAL
        }

        // 2) 主界面骨架：压倒一切文本关键词（真机 18:27 误判的修复点）
        if (signals.hasMainUi) return Kind.NORMAL

        // 3) 整页文本兜底：要求多个关键词同现，避免单字误判
        val page = signals.pageText?.trim().orEmpty()
        if (page.isEmpty()) return Kind.NORMAL
        if (VERIFY_KEYWORDS.count { page.contains(it) } >= 2) {
            return Kind.ACCOUNT_VERIFICATION_REQUIRED
        }
        if (LOGIN_STRONG_KEYWORDS.any { page.contains(it) }) return Kind.NOT_LOGGED_IN
        if (LOGIN_KEYWORDS.count { page.contains(it) } >= 2) return Kind.NOT_LOGGED_IN
        return Kind.NORMAL
    }

    /** 兼容旧调用（仅文本框；无结构信号时等同"无主界面骨架"）。 */
    fun detect(dialogTitle: String?, dialogText: String?, pageText: String? = null): Kind =
        detect(Signals(dialogTitle, dialogText, pageText, hasMainUi = false))

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

    /**
     * QQ **主界面骨架**的判定（供 accessibility 层调用；见 [Signals.hasMainUi]）。
     *
     * @param viewIdTails 执行面树里出现的 resource-id 尾段集合
     * @param texts 执行面树里的文本集合
     */
    fun hasMainUiSignals(viewIdTails: Set<String>, texts: Set<String>): Boolean {
        // 会话头是消息列表页的稳定标志（真机实测 `conversation_head`）
        if (viewIdTails.any { it.equals("conversation_head", ignoreCase = true) }) return true
        // 聊天页必有输入框
        if (viewIdTails.any { it.equals("input", ignoreCase = true) }) return true
        // 底部三标签（消息/联系人/动态）同时出现 → 主界面
        val tabs = listOf("消息", "联系人", "动态").count { t -> texts.any { it.trim() == t } }
        return tabs >= 2
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

    /** 登录页**强**文案（单独出现即可判定，登录页才有）。 */
    private val LOGIN_STRONG_KEYWORDS = listOf(
        "扫码登录",
        "密码登录",
        "一键登录",
        "手机号登录",
        "新用户注册",
        "忘记密码"
    )

    /** 登录类弱关键词（需多个同现；单个极易被聊天内容误命中）。 */
    private val LOGIN_KEYWORDS = listOf(
        "登录",
        "扫码",
        "密码"
    )
}
