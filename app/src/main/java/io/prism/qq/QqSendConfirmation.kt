package io.prism.qq

/**
 * 发送确认判据（PRD US-3118 AC-4；guardrail **G-03** 修正）。
 *
 * **背景**：QQ 改版会让硬编码气泡 id（`mjn`）失配，导致"已送达却报未确认"。
 * 修法是增加判据，但"输入框已清空"这条**方向极危险**：
 * - 判成"已发送"→ 不重试 → 消息**实际没发出**却告知成功（用户丢消息）；
 * - 判成"未确认"→ 只是保守（用户看得到"未确认"，不会误以为发出）。
 *
 * 因此本对象只承认**正向证据**：读到的文本**确实存在且为空**才算清空；
 * `null`（节点已回收 / 文本在 contentDescription / 跨进程读失败）一律**不算**。
 */
object QqSendConfirmation {

    /** 判据来源（用于日志与给用户的"确认依据"文案）。 */
    enum class Evidence(val label: String) {
        /** 聊天记录最后一条气泡是"自己"且含消息片段（最强证据）。 */
        BUBBLE("聊天记录气泡"),

        /** 输入框已清空（次强：只有真正点中发送才会清空）。 */
        INPUT_CLEARED("输入框已清空"),

        /** 页面文本里含消息片段（兜底，最弱）。 */
        PAGE_TEXT("页面文本"),

        /** 无一成立。 */
        NONE("")
    }

    /**
     * 输入框是否**确实**已清空。
     *
     * @param inputNodePresent 发送后输入框节点是否仍存在（`false`=页面已换/读取失败）
     * @param inputText 发送后读到的输入框文本（`null`=该节点聚合文本为空，QQ 空输入框即如此）
     * @param typedFragment 发送前实际输入内容的片段（用于确认"确实输入过"）
     * @param beforeText 发送前读到的输入框文本（可为 null）
     * @return 仅当"节点仍在" 且"读到空/空白（或 null）" 且"发送前确实含片段"时为 true
     */
    fun isInputCleared(
        inputNodePresent: Boolean,
        inputText: String?,
        typedFragment: String,
        beforeText: String?
    ): Boolean {
        // 前置：发送前输入框里必须有内容（含我们输入的片段），否则"空"是初始状态而非清空证据
        if (beforeText.isNullOrBlank()) return false
        if (typedFragment.isNotBlank() && !beforeText.contains(typedFragment)) return false
        // guardrail R2-01：先要求"节点仍在"——节点消失说明页面已变，不能把"读不到"当"清空"
        if (!inputNodePresent) return false
        // 节点仍在时：空输入框在 QQ 里聚合文本为空 → nodeTextOf 返回 null，这**是**清空的证据；
        // 而"节点不在"已在上一行排除，故此处 null 与 blank 等价。
        if (inputText == null) return true
        return inputText.isBlank()
    }

    /**
     * 综合三判据得出最终证据（按强度排序，**先强后弱**）。
     *
     * @param bubbleConfirmed 气泡判据是否成立
     * @param inputCleared 输入框判据是否成立（须经 [isInputCleared] 计算，不要直接传"读到空"）
     * @param pageTextHasFragment 页面文本兜底是否成立
     */
    fun decide(
        bubbleConfirmed: Boolean,
        inputCleared: Boolean,
        pageTextHasFragment: Boolean
    ): Evidence = when {
        bubbleConfirmed -> Evidence.BUBBLE
        inputCleared -> Evidence.INPUT_CLEARED
        pageTextHasFragment -> Evidence.PAGE_TEXT
        else -> Evidence.NONE
    }

    /** 是否应视为"已发送"（只有拿到证据才算）。 */
    fun isSent(evidence: Evidence): Boolean = evidence != Evidence.NONE
}
