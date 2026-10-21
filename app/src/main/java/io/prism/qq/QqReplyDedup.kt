package io.prism.qq

/**
 * 双向对话去重判定（PRD US-3111 AC-3 / US-3109 AC-3）。
 *
 * **两种键并存**：
 * - 协议端有稳定 `message_id` → 以 ID 为准（跨重启有效，重复投递只回一次）；
 * - 无障碍通道无 ID → 退回「内容指纹 + 时间窗」（同文消息超出时间窗视为新消息）。
 *
 * 抽成纯函数以便 JVM 单测覆盖（guardrail L-07：幂等跨重启）。
 */
object QqReplyDedup {

    /** 无 message_id 时的去重时间窗（与既有 M-02 语义一致）。 */
    const val HASH_WINDOW_MS = 10 * 60 * 1000L

    /**
     * @param messageId 当前消息的幂等键（协议端；null/空=无障碍通道）
     * @param lastMessageId 上次已回应的幂等键（持久化）
     * @param hash 当前消息内容指纹
     * @param lastHash 上次已回应的内容指纹
     * @param lastRepliedAtMs 上次回应时刻
     * @param nowMs 当前时刻
     * @return true=已回应过，应跳过
     */
    fun isDuplicate(
        messageId: String?,
        lastMessageId: String?,
        hash: Int,
        lastHash: Int,
        lastRepliedAtMs: Long,
        nowMs: Long,
        /** 最近已回应 ID 集合（D-03：A→B→重复 A 也必须判重；默认空兼容旧调用）。 */
        repliedMessageIds: Collection<String> = emptyList()
    ): Boolean {
        if (!messageId.isNullOrBlank()) {
            return messageId == lastMessageId || messageId in repliedMessageIds
        }
        return hash == lastHash && nowMs - lastRepliedAtMs < HASH_WINDOW_MS
    }
}
