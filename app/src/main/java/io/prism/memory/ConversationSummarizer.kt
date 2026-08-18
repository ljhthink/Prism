package io.prism.memory

import io.prism.data.ProviderConfig
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import kotlinx.coroutines.CancellationException

/**
 * 对话摘要生成器（ADR-015 5.3 / US-032 AC-1）—— 使用 LLM 非流式请求对旧消息生成摘要。
 *
 * **职责**：将超出滑动窗口的旧消息列表压缩为简洁摘要，保留关键信息（事实、决策、用户需求）。
 * 摘要由 [SlidingWindowMemoryManager] 作为 system message 注入上下文，实现 L1 会话内记忆压缩。
 *
 * **设计**（参考 mem0/CALMem 最佳实践）：
 * - 摘要 prompt 指示 LLM 用第三人称叙述，保留关键事实/决策/需求，控制在 200 字以内
 * - 使用 [ChatCompletionProvider] 非流式接口（一次性完整结果，无需流式增量）
 * - [ProviderConfig] 作为 [summarize] 参数传入，支持用户运行时切换 Provider（BYOK 场景）
 * - 失败降级：返回 null，由 [SlidingWindowMemoryManager] 决定降级策略（截断）
 *
 * **错误处理**（BR-error-handling-007）：
 * - CancellationException 重抛（不吞协程取消）
 * - 其他异常返回 null（[ChatCompletionProvider] 内部已捕获，此处为防御性兜底）
 *
 * **可测性**（BR-testing-004）：依赖 [ChatCompletionProvider] 抽象，测试注入 fake provider
 * 即可验证摘要逻辑，无需真实网络请求。
 *
 * @param completionProvider 非流式对话 Provider（依赖倒置，便于测试注入 fake）
 */
class ConversationSummarizer(
    private val completionProvider: ChatCompletionProvider
) {

    /**
     * 对旧消息列表生成摘要。
     *
     * **流程**：
     * 1. 空消息列表 → 返回 null（无需摘要）
     * 2. 构建摘要 prompt（[buildSummarizationPrompt]）
     * 3. 调用 [ChatCompletionProvider.chatCompletion]（非流式单次请求）
     * 4. 返回 LLM 生成的摘要文本；失败返回 null
     *
     * **消息转换**：[ChatMessage] 原样传递（role + content），由 [ChatCompletionProvider]
     * 内部转换为 OpenAI 请求体。systemPrompt 使用 [buildSummarizationPrompt]。
     *
     * @param messages 待摘要的旧消息列表（已超出滑动窗口的消息）
     * @param config 目标 Provider 配置（支持运行时切换 Provider）
     * @return 摘要文本；空列表或失败时返回 null
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun summarize(messages: List<ChatMessage>, config: ProviderConfig): String? {
        if (messages.isEmpty()) return null

        val summarizationPrompt = buildSummarizationPrompt()

        return try {
            completionProvider.chatCompletion(
                config = config,
                messages = messages,
                systemPrompt = summarizationPrompt
            )
        } catch (e: CancellationException) {
            // 协程取消必须重抛，不得吞掉（BR-error-handling-007）
            throw e
        } catch (e: Exception) {
            // 防御性兜底：ChatCompletionProvider 内部已捕获异常返回 null，
            // 此处捕获其他未预期异常，降级为 null 让 SlidingWindowMemoryManager 截断
            null
        }
    }

    /**
     * 构建摘要 prompt（纯函数，可测）。
     *
     * **prompt 设计**（参考 mem0/CALMem 最佳实践）：
     * - 指示 LLM 作为对话摘要助手
     * - 保留：关键事实和决策、用户核心需求、重要上下文
     * - 约束：200 字以内、第三人称叙述
     * - 输出格式：纯文本摘要（无 JSON 包装，便于直接注入 system message）
     *
     * @return 摘要 prompt 文本
     */
    internal fun buildSummarizationPrompt(): String = SUMMARY_PROMPT_TEMPLATE

    /**
     * UXR11 U5（ADR-033）：从对话中抽取**可跨会话复用的原子记忆**（L2 跨会话记忆）。
     *
     * 参考 TencentDB-Agent-Memory 的 Chat Memory 理念（"记忆资产，而非聊天日志仓库"）：
     * L2 只应保留**关于用户**的可复用信息（偏好/事实/决策），而**非**对话过程或一次性
     * 信息查询。此前用 [summarize]（对话摘要）作为 L2 内容，把"用户问过 X、我回答了 Y"
     * 这类一次性查询也摘要入库（真机实测：L2 基本全是无效信息）。
     *
     * **与 [summarize] 的区别**：
     * - [summarize]：压缩**对话过程**（L1 滑动窗口用，会话内上下文）
     * - [extractMemories]：抽取**关于用户的原子记忆**（L2 跨会话用，可复用资产）
     *
     * **返回值语义**：
     * - `null`：LLM 调用失败（调用方降级为规则抽取，不丢数据）
     * - `emptyList()`：LLM 成功但判定**无值得跨会话记住的记忆**（调用方应跳过，不落库）
     * - 非空：原子记忆列表（每条一行，独立完整）
     *
     * @param messages 本会话重要轮次消息（已过滤寒暄/确认）
     * @param config 目标 Provider 配置
     * @return 原子记忆列表；失败返回 null
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun extractMemories(messages: List<ChatMessage>, config: ProviderConfig): List<String>? {
        if (messages.isEmpty()) return emptyList()
        return try {
            val raw = completionProvider.chatCompletion(
                config = config,
                messages = messages,
                systemPrompt = buildMemoryExtractionPrompt()
            )
            if (raw.isNullOrBlank()) return emptyList()
            // 解析：每行一条记忆，剥离序号/列表符号，去空，截断上限
            // guardrail F6：仅剥离完整序号格式（`1.` / `1、` / `1）` 等），不裸剥数字——
            // 否则以数字开头的真实记忆（如 "用户有 2 个孩子"）会被破坏；LLM 未按
            // "每行一条"约定输出时整段视为一条（不强制结构化）。
            raw.lineSequence()
                .map { line ->
                    line.trim()
                        .replaceFirst(NUMBERED_LIST_PREFIX_REGEX, "")
                        .trim()
                        .removePrefix("-").removePrefix("•").removePrefix("·").removePrefix("*")
                        .trim()
                        // guardrail M-1（第二轮复审）：单条原子记忆截断上限，防病态/幻觉超长行
                        // 无界写入 L2 库并在后续会话注入 systemPrompt 时膨胀上下文。
                        .take(MAX_MEMORY_ITEM_CHARS)
                }
                .filter { it.isNotBlank() && it != "无" && it.length > 1 }
                .take(MEMORY_EXTRACT_MAX)
                .toList()
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // 防御性兜底：ChatCompletionProvider 内部已捕获异常返回 null
            null
        }
    }

    /**
     * 构建记忆抽取 prompt（纯函数，可测）。
     *
     * **prompt 设计**（参考 TencentDB-Agent-Memory Chat Memory：preferences/facts/decisions）：
     * - 明确"原子记忆"定义：关于用户的偏好、个人信息事实、长期决策/立场
     * - **显式排除一次性信息查询**（"搜索X""查Y背景"）——这是用户反馈"L2 什么都记"的根因：
     *   单次查询只在当次会话有用，不构成跨会话记忆
     * - 输出：每条一行、第三人称"用户…"、最多 5 条、无值得记录输出"无"
     */
    internal fun buildMemoryExtractionPrompt(): String = MEMORY_EXTRACTION_PROMPT_TEMPLATE

    companion object {
        /**
         * 摘要 prompt 模板（参考 mem0/CALMem 对话压缩最佳实践）。
         *
         * 设计要点：
         * - 明确角色（对话摘要助手）
         * - 明确保留内容（事实/决策/需求/上下文）
         * - 明确约束（200 字、第三人称、纯文本）
         * - 明确禁止（不生成新内容、不评价、不对话）
         */
        internal val SUMMARY_PROMPT_TEMPLATE = """
你是对话摘要助手。请将以下对话历史压缩为简洁的摘要，保留以下信息：
1. 关键事实和决策（用户做了什么决定、选择了什么方案）
2. 用户的核心需求（用户想要解决什么问题）
3. 重要的上下文信息（技术栈、环境、约束条件）

约束：
- 摘要控制在 200 字以内
- 用第三人称叙述（如"用户询问了..."，不要用"你"或"我"）
- 只总结已有内容，不生成新内容、不评价、不与用户对话
- 输出纯文本摘要，不要 JSON、Markdown 标题或列表符号
        """.trimIndent()

        /**
         * UXR11 U5（ADR-033）：L2 跨会话记忆抽取 prompt 模板。
         *
         * 参考 TencentDB-Agent-Memory Chat Memory 核心（L0→L1→L2→L3 分层蒸馏，
         * Chat Memory = preferences + facts + decisions，**不是聊天日志仓库**）。
         * 与 [SUMMARY_PROMPT_TEMPLATE]（L1 对话摘要）语义区分：本 prompt 抽"关于用户
         * 的可复用记忆"，显式排除一次性信息查询与对话过程。
         */
        internal val MEMORY_EXTRACTION_PROMPT_TEMPLATE = """
你是记忆抽取助手。从以下对话中，抽取**值得跨会话长期记住的原子记忆**。

原子记忆 = 关于用户的偏好、个人信息事实、长期决策/立场，且对未来对话有帮助的信息。例如：
- 用户偏好使用简体中文交流
- 用户是 Android 开发者，使用 Kotlin 和 Jetpack Compose
- 用户决定项目采用方案 A（不采用方案 B）

**不要记录**（这些不是长期记忆）：
- 一次性信息查询（如"搜索某角色的背景""查一下最新价格"）——只在当次会话有用，不应跨会话记住
- 对话过程、寒暄、确认、闲聊
- 临时性任务内容

约束：
- 每条记忆一行，独立完整，第三人称叙述（以"用户"开头）
- 只抽取对话中明确表达的信息，不臆测、不脑补
- 没有值得跨会话记住的记忆时，只输出"无"
- 最多 5 条
        """.trimIndent()

        /** UXR11 U5：单次抽取记忆条数上限（防 token 溢出 + 控制记忆库膨胀）。 */
        internal const val MEMORY_EXTRACT_MAX = 5

        /** guardrail M-1（第二轮复审）：单条原子记忆字符上限（防病态/幻觉超长行无界入库）。 */
        internal const val MAX_MEMORY_ITEM_CHARS = 200

        /** guardrail F6：完整序号前缀（`1.` / `1、` / `1）` / `1:` 等），仅剥此类格式不剥裸数字。 */
        internal val NUMBERED_LIST_PREFIX_REGEX = Regex("""^\d+[.、:：)）]\s*""")
    }
}
