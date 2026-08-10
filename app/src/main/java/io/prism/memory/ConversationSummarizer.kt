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
    }
}
