package io.prism.phonecontrol

/**
 * v1 US-202：手机操控敏感操作拦截器（纯函数可测，代码层硬拦截，不依赖模型自觉）。
 *
 * **拦截分层**（D-3 确认默认硬拦截，同时保证 US-201「打开微信搜索」用例可用）：
 * 1. **金融专用 App 启动黑名单**：[BLOCKED_LAUNCH_PACKAGES]——支付宝/银行/银联等以支付为核心
 *    的 App 直接禁止启动（避免 LLM 误入支付场景）。
 *    - **微信/QQ 不在黑名单**：它们是「通讯 + 支付」双用途 App，禁用启动会破坏 US-201 高频用例
 *      （打开微信搜索）。其内部的支付/转账按钮由 [isSensitiveTargetText] 在节点层硬拦截。
 * 2. **敏感目标节点硬拦截**：[isSensitiveTargetText]——目标节点文本/描述含支付/转账/付款/红包/
 *    余额/充值/密码/验证码/卡号等关键词时，点击/输入直接拒绝（永不执行，即使人工确认也不放行，
 *    这类操作应走 [PhoneControlLocalToolExecutor] 的 take_over 交还用户手动）。
 * 3. **高危动作强制 MANUAL**：[isManualAction]——发送/删除/退出登录/拨号/短信/下单等命中时
 *    触发 ask_user（映射 Open-AutoGLM Take_over + StopAtTools），由用户显式确认/接管后继续。
 * 4. **密码/验证码节点**：[isPasswordNode]——isPassword 或 className 含 Password 或 hint 含
 *    密码/登录/账号/验证码 → 输入动作硬拦截。
 *
 * **设计原则**：黑名单精确匹配（防误拦）；非敏感默认放行（但执行仍受审批模式 MANUAL 约束）。
 */
object PhoneControlSecurity {

    /** 金融专用 App 包名（启动即硬拦截；微信/QQ 除外，见类注释）。 */
    val BLOCKED_LAUNCH_PACKAGES = setOf(
        "com.eg.android.AlipayGphone",      // 支付宝
        "com.unionpay",                     // 云闪付/银联
        "cmb.pb",                           // 招商银行（掌上生活，真机实证包名）
        "com.cmbchina.ccd.pluto.cmbActivity", // 招商银行（历史错误值——Activity 名非包名，保留兜底，不匹配也无害）
        "com.chinamworld.bocmbci",          // 中国银行（主流包名）
        "com.boc.bocmobi",                  // 中国银行（旧版/海外包名，兜底）
        "com.chinamworld.main",             // 建设银行（修正注释：此前误标为"中国银行"）
        "com.ccb.longjiLife",               // 建设银行（备用）
        "com.icbc",                         // 工商银行
        "com.android.bankabc",              // 农业银行
        "com.paytm.app",                    // Paytm（海外）
        "com.applepay"                      // Apple Pay（异常值，保留占位）
    )

    /**
     * 敏感目标关键词（节点文本/描述命中即**硬拦截**——点击/输入永不执行）。
     *
     * 覆盖支付/金融/凭据域：支付/转账/付款/收款/红包/余额/充值/密码/验证码/卡号。
     * 含「密码/验证码/卡号」→ 输入类动作直接拒绝；含支付类 → 点击类动作直接拒绝。
     *
     * **多语言（guardrail M-2 修复）**：含英文变体（pay/transfer/balance/password/verification
     * code/card number 等），英文词边界匹配大小写不敏感（[containsKeyword]）。密码字段本身由
     * [isPasswordNode] 的 `isPassword` 标志（语言无关）兜底。
     */
    val SENSITIVE_TARGET_KEYWORDS = listOf(
        // 中文
        "支付", "转账", "付款", "收款", "红包", "余额", "充值", "付款确认",
        "密码", "验证码", "卡号", "短信验证", "登录密码", "支付密码",
        // 英文（词边界匹配，大小写不敏感；复数自动覆盖）
        "pay", "payment", "transfer", "receive", "red packet",
        "balance", "recharge", "top up", "password", "verification code",
        "card number", "card no", "cvv", "otp", "pin"
    )

    /**
     * 高危动作关键词（命中即强制 MANUAL 审批——触发 ask_user 人工确认/接管）。
     *
     * 覆盖 D-3 清单中「可人工确认后放行」的动作域：发送/发布/提交/下单/购买/删除/退出登录/
     * 注销/拨号/短信。对动作名/参数做包含匹配（参数可能含文本）。
     *
     * **多语言（guardrail M-2 修复）**：含英文变体（send/publish/submit/order/delete/logout/
     * call/sms 等），匹配大小写不敏感。
     */
    val MANUAL_ACTION_KEYWORDS = listOf(
        // 中文
        "发送", "发布", "提交", "下单", "购买", "确认订单",
        "删除", "退出登录", "注销", "解除", "关闭账号",
        "拨号", "打电话", "呼叫", "发短信", "短信",
        // 英文（大小写不敏感）
        "send", "publish", "submit", "order", "buy", "purchase",
        "delete", "remove", "logout", "log out", "sign out", "unsubscribe", "cancel account",
        "call", "dial", "sms", "text message"
    )

    /**
     * 关键词包含匹配（guardrail M-2 多语言）：
     * - **中文关键词**：CJK 字符无词边界，直接子串匹配
     * - **英文关键词**：词边界正则（`(?i)(?<![a-z0-9])kw(?![a-z0-9])`）——防 "pay" 误伤
     *   "display/payment"、防 "pin" 误伤 "pinch/opinion" 等
     */
    private fun containsKeyword(text: String, keyword: String): Boolean {
        if (keyword.any { it in CJK_START..CJK_END }) return text.contains(keyword)
        val regex = cachedRegex(keyword)
        return regex.containsMatchIn(text)
    }

    /** 判断是否命中关键词列表（任一命中即 true）。 */
    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any { containsKeyword(text, it) }

    /** 英文关键词词边界正则缓存（防每调用编译）。 */
    private val keywordRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun cachedRegex(keyword: String): Regex = keywordRegexCache.getOrPut(keyword) {
        // (?:s)? 覆盖英文复数（payment/payments、transfer/transfers）
        Regex("(?i)(?<![a-z0-9])" + java.util.regex.Pattern.quote(keyword) + "(?:s)?(?![a-z0-9])")
    }

    /**
     * 判断包名是否为金融专用 App（启动即拦截）。
     *
     * @param packageName 目标包名
     * @return true 命中金融黑名单
     */
    fun isSensitivePackage(packageName: String?): Boolean =
        packageName?.let { it in BLOCKED_LAUNCH_PACKAGES } ?: false

    /**
     * 判断节点是否为密码/登录/验证码敏感节点（输入/点击均硬拦截）。
     *
     * **语言无关特征**（guardrail M-2）：`isPassword` 标志与 className 含 Password 为系统级
     * 特征（任何语言均命中）；hint 关键词含中英文「密码/登录/账号/验证码/password/login/
     * verification」。
     *
     * @param node UI 节点
     * @return true 命中密码/登录/验证码特征
     */
    fun isPasswordNode(node: UiNode): Boolean {
        if (node.password) return true
        val cls = node.className.orEmpty()
        if (cls.contains("Password", ignoreCase = true)) return true
        val hint = listOfNotNull(node.text, node.contentDescription).joinToString(" ")
        return containsAny(hint, PASSWORD_HINT_KEYWORDS)
    }

    /**
     * 判断目标节点文本/描述是否含敏感关键词（支付/转账/密码/验证码等 → 硬拦截）。
     *
     * @param text 目标节点文本/描述（可为 null——坐标点击时通过回读 UI 树获取）
     * @return true 命中敏感目标关键词（点击/输入直接拒绝）
     */
    fun isSensitiveTargetText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return containsAny(text, SENSITIVE_TARGET_KEYWORDS)
    }

    /**
     * 判断动作/参数是否高危（命中 [MANUAL_ACTION_KEYWORDS] → 强制 MANUAL 人工确认）。
     *
     * @param action 动作名（如 tap/type/swipe）
     * @param params 动作参数拼接（如目标节点文本/输入文本）
     * @return true 高危（需触发 ask_user 人工确认/接管）
     */
    fun isManualAction(action: String, params: String): Boolean {
        val joined = "$action $params"
        return containsAny(joined, MANUAL_ACTION_KEYWORDS)
    }

    /**
     * 判断输入文本是否含凭据类内容（密码/验证码/卡号 → 输入硬拦截）。
     *
     * @param text 待输入文本
     * @return true 命中凭据关键词
     */
    fun isCredentialInput(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return containsAny(text, CREDENTIAL_KEYWORDS)
    }

    /** 密码/登录/验证码 hint 关键词（中英文，guardrail M-2）。 */
    private val PASSWORD_HINT_KEYWORDS = listOf(
        "密码", "登录", "账号", "验证码",
        "password", "login", "account", "verification code", "otp"
    )

    /** 凭据输入关键词（中英文，guardrail M-2）。 */
    private val CREDENTIAL_KEYWORDS = listOf(
        "密码", "验证码", "卡号", "支付密码", "短信验证",
        "password", "verification code", "card number", "cvv", "otp", "pin"
    )

    /** CJK 基本区起点。 */
    private const val CJK_START = '\u4e00'

    /** CJK 基本区终点。 */
    private const val CJK_END = '\u9fff'
}
