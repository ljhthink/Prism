package io.prism.qq

/**
 * 把一次模型输出拆成**多条短消息**（2026-11-15 新增；ADR-076）。
 *
 * **为什么要拆**：真人在 QQ 上回复常常是「一串短句」——先应一声、再补一句、再补一个表情，
 * 而不是每次都端出一整段。合成为一条长文本，是"AI 味"最直观的来源之一。
 *
 * 约定：模型用**单独一行的 `---`** 分隔气泡（与正文里的破折号/减号区分；
 * 只在整行只有 2 个以上连字符时才算分隔）。
 *
 * 约束（防跑飞）：
 * - 最多 [MAX_BUBBLES] 条；
 * - 丢弃空白气泡；
 * - 契约是**二选一**：要么产出的每条都 ≤ [MAX_BUBBLE_CHARS]，要么退回**单条整段**
 *   （模型没按"连发短句"写时，切碎会破坏语义；整段长度由调用方 `MAX_LEN` 与通道上限约束）。
 *   任何分支都**不会**产出"多条且其中某条超长"的混合结果。
 *
 * 纯函数，便于单测。
 */
object QqReplyBubbles {

    /** 单次回复最多拆成几条。 */
    const val MAX_BUBBLES = 3

    /** 单条气泡的字数上限；超过则认为模型未按约定分条，整段发送。 */
    const val MAX_BUBBLE_CHARS = 120

    /** 气泡之间的发送间隔（模拟"正在打字"；最后一条不等待）。 */
    const val BUBBLE_GAP_MS = 700L

    /** 分隔行：整行仅由 3 个及以上连字符（可带空白）组成。 */
    private val SEPARATOR = Regex("""(?m)^[ \t]*-{3,}[ \t]*$""")

    /**
     * 拆分 [text] 为气泡列表。
     *
     * @return 至少 1 条（空输入返回空列表，由调用方按"仅媒体"处理）
     */
    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!SEPARATOR.containsMatchIn(trimmed)) return listOf(trimmed)
        val parts = trimmed.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(trimmed)
        // guardrail L-01：**任何**分支都不得产出超过 [MAX_BUBBLE_CHARS] 的气泡。
        // 原实现在"超过 MAX_BUBBLES"分支直接合并剩余部分，绕过了长度检查
        // （与 PRD AC「单条 ≤120 字」矛盾）。
        val merged = if (parts.size > MAX_BUBBLES) {
            // 超出上限：保留前 MAX_BUBBLES-1 条，其余并进最后一条（不丢内容）
            parts.take(MAX_BUBBLES - 1) + parts.drop(MAX_BUBBLES - 1).joinToString("\n")
        } else {
            parts
        }
        if (merged.any { it.length > MAX_BUBBLE_CHARS }) {
            // 有气泡明显超长 → 说明不是"连发短句"，整段更安全（同样受长度约束：
            // 整段是原文，不会被切碎，由通道侧长度上限兜底）
            return listOf(merged.joinToString("\n\n"))
        }
        return merged
    }
}
