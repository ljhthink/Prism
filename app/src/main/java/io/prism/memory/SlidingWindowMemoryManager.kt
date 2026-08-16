package io.prism.memory

import io.prism.data.ProviderConfig
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException

/**
 * L1 会话内滑动窗口记忆管理器（ADR-015 5.3 / US-032 AC-2 + AC-3）。
 *
 * **职责**：管理会话内上下文窗口，保留最近 N 轮原始消息，超出 N 轮时触发摘要压缩，
 * 将旧消息压缩为 summary 注入上下文。解决长对话因上下文窗口限制丢失关键信息的问题。
 *
 * **工作流程**：
 * 1. 接收完整对话历史 `messages` + 当前 Provider 配置 `config`
 * 2. 若 `messages.size <= N`：直接返回全部消息，无需摘要
 * 3. 若 `messages.size > N`：
 *    - 分割：旧消息（`dropLast(N)`）+ 近期消息（`takeLast(N)`）
 *    - 调用 [ConversationSummarizer.summarize] 对旧消息生成摘要
 *    - 摘要成功：返回 summary + 近期 N 条消息
 *    - 摘要失败（null）：降级为截断（[truncateMessages]），返回截断文本 + 近期 N 条消息
 *
 * **失败降级**（US-032 AC-1 + AC-5）：
 * - 摘要 LLM 请求失败 → 截断旧消息（取每条前 100 字，总长 ≤500 字）
 * - 截断保证不丢失全部上下文，且不阻断对话
 *
 * **可测性**（BR-testing-004）：
 * - 依赖 [ConversationSummarizer] 和 [MemoryConfigRepository] 抽象
 * - 测试注入 fake summarizer（控制返回值）+ fake repository（控制 N 值）
 * - [truncateMessages] 为 internal 纯函数，可直接测试
 *
 * **线程安全**：方法为 suspend，由调用方（ConversationViewModel）在协程内调用。
 * 无可变内部状态，多次调用幂等。
 *
 * @param summarizer 对话摘要生成器
 * @param memoryConfigRepository 记忆配置仓库（提供 N 值）
 */
class SlidingWindowMemoryManager(
    private val summarizer: ConversationSummarizer,
    private val memoryConfigRepository: MemoryConfigRepository
) {

    /**
     * 处理对话历史，应用滑动窗口 + 摘要压缩。
     *
     * @param messages 完整对话历史（按时间顺序，user/assistant/tool 交替）
     * @param config 当前活跃 Provider 配置（用于摘要 LLM 请求，支持运行时切换 Provider）
     * @return [SlidingWindowResult] 含 summary（null 表示无需摘要）和近期消息列表
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun processMessages(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): SlidingWindowResult {
        if (messages.isEmpty()) {
            return SlidingWindowResult(summary = null, recentMessages = emptyList())
        }

        // 纵深防御（BR-security-005，guardrail-enforcer M-2 修复）：
        // DataStore 损坏或外部写入时，将 N 强制限制在 [MIN_WINDOW_SIZE]..[MAX_WINDOW_SIZE] 范围内，
        // 防止 N=0 导致 dropLast(0)=全部旧消息或 N=过大导致全部消息被视为"近期"而 token 溢出。
        val windowSize = memoryConfigRepository.getWindowSize().coerceIn(
            MemoryConfigRepository.MIN_WINDOW_SIZE,
            MemoryConfigRepository.MAX_WINDOW_SIZE
        )

        // AC-2：保留最近 N 轮原始消息，未超 N 时不触发摘要
        if (messages.size <= windowSize) {
            return SlidingWindowResult(summary = null, recentMessages = messages)
        }

        // AC-2 + AC-3：超出 N 轮触发摘要压缩
        val oldMessages = messages.dropLast(windowSize)
        val rawRecent = messages.takeLast(windowSize)

        // UXR5 问题 4（tool_calls 完整性保护）：按条数切分可能切断 assistant(tool_calls)
        // 与其后 tool result 的对，导致 history 中 TOOL 消息无前置 tool_calls → DeepSeek 400
        // "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
        // 若窗口第一条是 TOOL（无前置 assistant），向前扩展窗口把前置 assistant 一并纳入。
        val recentMessages = adjustWindowBoundary(messages, rawRecent, windowSize)

        // AC-1 + AC-5：摘要失败降级为截断
        val summary = summarizer.summarize(oldMessages, config) ?: truncateMessages(oldMessages)

        return SlidingWindowResult(
            summary = summary,
            recentMessages = recentMessages
        )
    }

    /**
     * UXR5 问题 4（tool_calls 完整性保护，纯函数可测）：调整滑动窗口下边界。
     *
     * `takeLast(windowSize)` 按条数切分，可能使窗口第一条消息是 [Role.TOOL]（tool result），
     * 而其前置 assistant(tool_calls) 落在窗口外 → 发送给 LLM 的 history 中 TOOL 消息无
     * 前置 tool_calls，DeepSeek 严格校验返回 400。
     *
     * **规则**：若窗口首条为 [Role.TOOL]，则向前扩展窗口把紧随其前的消息纳入
     * （若窗口前仍有消息）。扩展后若仍为 TOOL（连续 tool 罕见，防御），继续向前扩展
     * 直到窗口首条非 TOOL 或到达列表头。
     *
     * @param messages 完整历史
     * @param rawRecent 原始 takeLast 结果（大小 ≤ windowSize）
     * @param windowSize 窗口大小（用于确定可向前扩展的最大量）
     * @return 调整后的近期消息（保证首条非 TOOL，除非整个历史都是 TOOL）
     */
    internal fun adjustWindowBoundary(
        messages: List<ChatMessage>,
        rawRecent: List<ChatMessage>,
        windowSize: Int
    ): List<ChatMessage> {
        var recent = rawRecent
        // F-02（guardrail TKN-UXR5-GUARDRAIL-001）修复：终止条件为「窗口首条非 TOOL」，
        // 而非「无进展即停」。原 `end = indexInAll + windowSize` 上限 + `expanded.size <= recent.size`
        // break 在窗口首条为连续 TOOL 时提前停止，违反「保证首条非 TOOL」契约。
        while (recent.isNotEmpty() && recent.first().role == Role.TOOL) {
            val firstInRecent = recent.first()
            val indexInAll = messages.indexOfLast { it.id == firstInRecent.id }
            if (indexInAll <= 0) break // 已到列表头，无法再扩展（整个历史都以 TOOL 结尾）
            // 向前扩展一条（含其前置），窗口终点放宽到列表末尾（不再被 windowSize 卡住）
            val start = maxOf(0, indexInAll - 1)
            val expanded = messages.subList(start, messages.size)
            if (expanded.size <= recent.size) break // 无进展，防死循环
            recent = expanded
        }
        return recent
    }

    /**
     * 截断旧消息作为摘要失败的降级策略（纯函数，可测）。
     *
     * **截断规则**（US-032 AC-1 降级策略）：
     * - 每条消息取前 [MAX_MESSAGE_TRUNCATE_LENGTH] 字符（100 字）
     * - 格式：`[role] content`，每条一行
     * - 总长度限制 [MAX_TRUNCATED_SUMMARY_LENGTH]（500 字），超出截断
     * - 前缀标注「（截断）」以便调用方区分 LLM 摘要与截断降级
     *
     * @param messages 待截断的旧消息列表
     * @return 截断后的文本（非空，即使输入为空也返回占位文本）
     */
    internal fun truncateMessages(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return FALLBACK_EMPTY_SUMMARY

        val parts = messages.map { msg ->
            val roleLabel = when (msg.role) {
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
                Role.TOOL -> "tool"
            }
            val truncatedContent = msg.content.take(MAX_MESSAGE_TRUNCATE_LENGTH)
            "[$roleLabel] $truncatedContent"
        }

        val builder = StringBuilder(TRUNCATION_PREFIX)
        for (part in parts) {
            if (builder.length + part.length + 1 > MAX_TRUNCATED_SUMMARY_LENGTH) {
                // 超出总长度限制，截断并标注
                val remaining = MAX_TRUNCATED_SUMMARY_LENGTH - builder.length - 1
                if (remaining > 0) {
                    builder.append('\n').append(part.take(remaining))
                }
                builder.append(TRUNCATION_SUFFIX)
                break
            }
            builder.append('\n').append(part)
        }

        return builder.toString()
    }

    companion object {
        /** 单条消息截断长度（字符数）。 */
        internal const val MAX_MESSAGE_TRUNCATE_LENGTH = 100

        /** 截断摘要总长度上限（字符数）。 */
        internal const val MAX_TRUNCATED_SUMMARY_LENGTH = 500

        /** 截断摘要前缀（标注来源为截断降级，非 LLM 摘要）。 */
        internal const val TRUNCATION_PREFIX = "（截断）早期对话摘要："

        /** 截断摘要超出总长度限制时的后缀标注。 */
        internal const val TRUNCATION_SUFFIX = "...（已截断）"

        /** 空消息列表时的降级占位文本。 */
        internal const val FALLBACK_EMPTY_SUMMARY = "（无早期对话记录）"
    }
}

/**
 * 滑动窗口处理结果（ADR-015 5.3）。
 *
 * @property summary 旧消息的摘要文本（LLM 摘要或截断降级）；null 表示无需摘要（消息数 ≤ N）
 * @property recentMessages 近期 N 条原始消息（发送给 LLM 的消息列表）
 */
data class SlidingWindowResult(
    val summary: String?,
    val recentMessages: List<ChatMessage>
) {
    /**
     * 将摘要格式化为 system prompt 片段（供 ConversationViewModel 注入 systemPrompt）。
     *
     * **注入格式**（US-032 AC-3）：
     * - summary 非空：`"[早期对话摘要] {summary}"`
     * - summary 为空：返回 null（不注入）
     *
     * @return 格式化后的 system prompt 片段；无摘要时返回 null
     */
    fun toSummarySystemPromptSection(): String? = summary?.let { "[早期对话摘要] $it" }
}
