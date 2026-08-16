package io.prism.memory

/**
 * L3 用户画像自然语言解析器（O1/PRD UXR8）。
 *
 * **背景**：原 UI 要求用户填写"偏好键"（英文 snake_case）+ "偏好值"，用户不理解键值语义
 * （PRD UXR8 需求 O1）。本解析器将输入模型简化为单字段自然语言句子
 * （如「我喜欢简洁的回复」），由启发式规则自动推导结构化 key，value 保留原句。
 *
 * **设计决策**：
 * - **纯函数零依赖**：本地启发式匹配，不调用 LLM（保存操作同步完成，无网络延迟与失败降级）。
 * - **key 推导两级策略**：
 *   1. 类别关键词命中 → 标准 key（tone/language_pref/tech_stack/expertise），
 *      与 [UserProfileManager.EXTRACTION_PROMPT_TEMPLATE] 隐式抽取的常见 key 对齐，
 *      保证显式/隐式同 key 时 upsert 与「显式 > 隐式」优先级逻辑可复用；
 *   2. 未命中 → `pref_` + 句子规范化 hash（同句同 key 实现 upsert 去重，异句互不覆盖）。
 * - **value 保留原句**：自然语言句子直接作为 value 存储，注入 systemPrompt 时比
 *   碎片化短语更完整（对 LLM 语义更友好）。
 * - **hash 稳定性**：[String.hashCode] 由 JVM 规范保证跨版本稳定，可安全持久化语义。
 *
 * **匹配规则**（[CATEGORY_RULES] 顺序即优先级，先命中先返回）：
 * - tone：回复风格（简洁/详细/正式/幽默…）
 * - language_pref：自然语言偏好（用中文/英文回复…）
 * - tech_stack：技术栈（Python/Kotlin/前端/后端…）
 * - expertise：专业程度（初级/资深/新手…）
 *
 * 纯函数可测（BR-testing-004）。
 */
object ProfileNaturalLanguageParser {

    /**
     * 类别关键词 → 标准 key 映射表（顺序即匹配优先级）。
     *
     * 关键词统一小写匹配（[deriveKey] 已 normalize），中文关键词无大小写问题。
     * tech_stack 含 "java" 会同时命中 "javascript"，均映射同一 key，无冲突。
     */
    internal val CATEGORY_RULES: List<Pair<List<String>, String>> = listOf(
        // 回复风格偏好
        listOf(
            "简洁", "简短", "详细", "啰嗦", "正式", "口语", "幽默",
            "轻松", "严肃", "直白", "委婉"
        ) to "tone",
        // 自然语言偏好（中文/英文回复）
        listOf(
            "用中文", "中文回答", "中文回复", "中文交流", "中文表达",
            "用英文", "英文回答", "英文回复", "英文交流", "母语"
        ) to "language_pref",
        // 技术栈偏好
        listOf(
            "python", "kotlin", "java", "golang", "rust", "javascript",
            "typescript", "c++", "c#", "swift", "php", "ruby",
            "前端", "后端", "全栈", "移动端"
        ) to "tech_stack",
        // 专业程度偏好
        listOf(
            "初级", "资深", "专家", "新手", "小白", "入门", "高手", "初学者"
        ) to "expertise"
    )

    /**
     * 从自然语言句子推导偏好 key（纯函数）。
     *
     * **流程**：
     * 1. normalize（trim + lowercase）
     * 2. 按 [CATEGORY_RULES] 顺序匹配关键词，命中返回标准 key
     * 3. 未命中返回 `pref_` + 稳定 hash（同句去重、异句新增）
     *
     * @param sentence 用户输入的自然语言偏好句子
     * @return 偏好 key（标准 key 或 `pref_` + 8 位 hex；空输入返回 `pref_` + 空串 hash）
     */
    fun deriveKey(sentence: String): String {
        val normalized = sentence.trim().lowercase()
        for ((keywords, key) in CATEGORY_RULES) {
            if (keywords.any { normalized.contains(it) }) return key
        }
        return "pref_" + stableHash(normalized)
    }

    /**
     * 句子稳定 hash（纯函数，可测）。
     *
     * 将 [String.hashCode] 的 32 位结果转为无符号 8 位 hex，
     * 保证：同句同 hash（upsert 去重）、异句极大概率不同 hash。
     *
     * @param text 已 normalize 的句子文本
     * @return 8 位小写 hex 字符串
     */
    internal fun stableHash(text: String): String =
        java.lang.Long.toString(text.hashCode().toLong() and 0xffffffffL, 16)
            .padStart(8, '0')
            .takeLast(8)
}
