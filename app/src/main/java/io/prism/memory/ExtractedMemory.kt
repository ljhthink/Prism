package io.prism.memory

/**
 * 原子记忆抽取结果（v1 记忆深度优化 US-101，参照 TencentDB-Agent-Memory L1 Atom）。
 *
 * 由 [ConversationSummarizer.extractMemories] 从对话中抽取的**关于用户的可复用记忆**，
 * 附带类型与重要性标注，供 [CrossSessionMemoryManager] 落库时写入 [io.prism.data.MemoryRecord]
 * 的 `priority` / `sourceMessageIds` 字段，并供未来按类型/重要性过滤使用。
 *
 * **字段语义**：
 * - [content] 原子记忆正文（第三人称"用户…"，单条独立完整）
 * - [type] 记忆类型，取自 [TYPE_PERSONA] / [TYPE_EPISODIC] / [TYPE_INSTRUCTION] /
 *   [TYPE_GENERAL]（LLM 未给出合法类型时的兜底）。解析时做同义词规范化
 *   （preference→persona、fact→episodic、decision→instruction 等，见
 *   [normalizeType]）。
 * - [priority] 重要性评分 0-100（越大越重要），LLM 赋值，解析失败/缺失兜底 [DEFAULT_PRIORITY]
 *
 * **类型语义**（参照 TencentDB-Agent-Memory L1 记忆类型精简为 3 类）：
 * - `persona`：用户偏好/画像类（如"用户偏好简洁回答"）
 * - `episodic`：关于用户的经历/事实类（如"用户最近在学习 Kotlin"）
 * - `instruction`：用户的长期决策/指令/规则类（如"用户决定项目采用方案 A"）
 *
 * @property content 原子记忆正文
 * @property type 记忆类型（persona / episodic / instruction / general）
 * @property priority 重要性 0-100
 */
data class ExtractedMemory(
    val content: String,
    val type: String = TYPE_GENERAL,
    val priority: Int = DEFAULT_PRIORITY
) {
    companion object {
        /** 画像/偏好类记忆。 */
        const val TYPE_PERSONA = "persona"

        /** 经历/事实类记忆。 */
        const val TYPE_EPISODIC = "episodic"

        /** 长期决策/指令类记忆。 */
        const val TYPE_INSTRUCTION = "instruction"

        /** 兜底类型（LLM 未给出合法类型时）。 */
        const val TYPE_GENERAL = "general"

        /** 重要性兜底值（解析失败/缺失时）。 */
        const val DEFAULT_PRIORITY = 50

        /**
         * 类型规范化（US-101 AC-4，纯函数可测）。
         *
         * 将 LLM 输出的原始类型同义词映射为三态标准类型；无法识别时返回 [TYPE_GENERAL]。
         * 参照 TencentDB-Agent-Memory 的 type 规范化（episode→episodic、instruct→instruction、
         * preference→persona）。
         *
         * @param raw LLM 输出的原始类型字符串
         * @return 规范化后的标准类型（persona / episodic / instruction / general）
         */
        fun normalizeType(raw: String): String = when (raw.trim().lowercase()) {
            "persona", "preference", "preferences", "profile", "偏好", "画像" -> TYPE_PERSONA
            "episodic", "episode", "episodes", "fact", "facts", "event", "events",
            "事实", "经历" -> TYPE_EPISODIC
            "instruction", "instruct", "decision", "decisions", "rule", "rules", "method",
            "指令", "决策" -> TYPE_INSTRUCTION
            else -> TYPE_GENERAL
        }

        /**
         * 优先级规范化（US-101 AC-4，纯函数可测）。
         *
         * 将任意输入解析为 0-100 整数：可解析数字时 clamp 到 [io.prism.data.MemoryRecord.MIN_PRIORITY]
         * .. [io.prism.data.MemoryRecord.MAX_PRIORITY]；否则（非数字/空）兜底 [DEFAULT_PRIORITY]。
         *
         * @param raw LLM 输出的原始优先级
         * @return 规范化后的 0-100 整数
         */
        fun normalizePriority(raw: Any?): Int {
            val parsed = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            } ?: return DEFAULT_PRIORITY
            return parsed.coerceIn(
                io.prism.data.MemoryRecord.MIN_PRIORITY,
                io.prism.data.MemoryRecord.MAX_PRIORITY
            )
        }
    }
}
