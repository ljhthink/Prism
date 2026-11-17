package io.prism.qq

import io.prism.qq.channel.QqMessage

/**
 * 会话上下文组装与**字符预算裁剪**（2026-10-26 新增；PRD US-3112 修订）。
 *
 * **为什么需要预算**：用户要求历史条数可自由设置（1~200），但把 200 条长文直接塞进 prompt
 * 会撑爆上下文窗口、显著增加费用与延迟。OneBot 生态的通行做法是
 * **"按会话持久化 + 按预算裁剪"**，条数只是便利参数。
 *
 * 裁剪顺序：从**最新**往旧累加，超预算即停——保证最近若干轮完整，丢掉的总是最旧的内容。
 * 纯函数，便于 JVM 单测。
 */
object QqContextBuilder {

    /**
     * 裁剪历史，使总字符数不超过 [charBudget]。
     *
     * @param messages 按时间升序的历史（最后一条最新）
     * @param charBudget 字符预算（<=0 表示不限制）
     * @return 裁剪后的历史（仍为升序）；为空表示预算只够当前一条（调用方自行处理）
     */
    fun trimByBudget(messages: List<QqMessage>, charBudget: Int): List<QqMessage> {
        if (messages.isEmpty()) return emptyList()
        if (charBudget <= 0) return messages
        val kept = ArrayDeque<QqMessage>()
        var used = 0
        // 从最新往旧取：最新的一条**必须**保留（即使它单独就超预算）
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            val cost = estimateCost(message.text)
            if (kept.isEmpty()) {
                kept.addFirst(message)
                used += cost
                continue
            }
            if (used + cost > charBudget) break
            kept.addFirst(message)
            used += cost
        }
        return kept.toList()
    }

    /**
     * 单条消息的预算成本。
     *
     * 用「字符数 + 固定开销」近似 token：中文 1 字符 ≈ 1 token，英文 ≈ 0.3 token，
     * 这里取偏保守的字符数即可（宁少勿爆）。固定开销覆盖角色标记与换行。
     */
    fun estimateCost(text: String): Int = text.length + PER_MESSAGE_OVERHEAD

    /**
     * 把历史转成进入 prompt 的文本块（`我` / `对方`）。
     *
     * 2026-11-15（ADR-076）：媒体消息**优先用可读说明**（[QqMessage.mediaNote]）。
     * 只把 `[表情包]` 这类占位符交给模型时，它要么假装知道、要么接一个不存在的话题——
     * 这是真机"说得莫名其妙"的直接来源之一。
     */
    fun render(messages: List<QqMessage>): String = messages.joinToString("\n") { message ->
        val who = if (message.incoming) "对方" else "我"
        val body = message.mediaNote?.takeIf { it.isNotBlank() } ?: message.text
        "$who：$body"
    }

    private const val PER_MESSAGE_OVERHEAD = 8
}
