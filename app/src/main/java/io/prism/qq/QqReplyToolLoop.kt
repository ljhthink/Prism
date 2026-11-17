package io.prism.qq

import io.prism.network.StreamEvent
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role

/**
 * QQ 应答的**有界工具回路**（2026-11-16 新增；ADR-077）。
 *
 * ## 为什么单独抽一层
 *
 * 真正的循环复用既有 [io.prism.skill.SkillExecutor.executeLoop]（含 `maxRounds` 防护、
 * 外科式单工具熔断、429 重试、`sanitizeToolCallPairing`、tool result 回灌）。
 * 但 QQ 场景与主聊天有三点不同，必须在外面包一层，且**必须可单测**：
 *
 * 1. **预算**：QQ 是被动回复，用户在看天；超时必须收口，不能像主聊天那样跑 50 轮。
 * 2. **回落**：工具回路出任何问题（端点不支持 tools / 超时 / 异常）都要回落到
 *    ADR-076 的单次补全——**宁可少一个能力，不能丢一条回复**。
 * 3. **输出**：回路返回的是消息列表，需要稳定地抽出"最后一条 assistant 文本"。
 *
 * ## 设计取舍
 *
 * - 只做**纯决策 + 文本抽取**，不碰网络（便于 JVM 单测）。
 * - 助手占位消息（`role=ASSISTANT` 且 `content` 为空、带 toolCalls）必须被**跳过**，
 *   否则会把空串当回复发出去（ADR-076 的 M-02 同类陷阱）。
 */
object QqReplyToolLoop {

    /**
     * 从回路产出的消息列表里抽取**最终回复正文**。
     *
     * 规则（按优先级）：
     * 1. 从后往前找 `role=ASSISTANT` 且 `content` 非空的消息；
     * 2. 跳过 `role=TOOL`（工具结果不是要说的话）；
     * 3. 跳过带 `toolCalls` 的占位消息（它没有正文）；
     * 4. 都找不到 → 返回 null（调用方走回落）。
     */
    fun extractFinalText(messages: List<ChatMessage>): String? =
        messages.asReversed()
            .firstOrNull { message ->
                message.role == Role.ASSISTANT &&
                    message.content.isNotBlank() &&
                    message.toolCalls.isEmpty()
            }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * **强制作答用的工具结果摘要**（2026-11-17 真机取证后新增）。
     *
     * 真机实测：模型会连续多轮搜索（"再换个词查一次"），把 `maxRounds` 用尽后**没有产出
     * 任何正文**，于是整次工具调用被丢弃并回落成"没查过"的单次补全——
     * 用户看到的就是"她说去查了，但答得像没查"。
     *
     * 这里把回路里已经拿到的工具结果抽出来，交给调用方做一次**禁用工具的强制作答**，
     * 保证"查到的结果真的被用上"。
     *
     * @param maxResults 最多取最后几条工具结果（更早的通常已被后续查询取代）
     * @param maxCharsPerResult 单条结果截断（控 token）
     * @return 空列表表示没有任何工具结果（此时应直接回落，强制作答无意义）
     */
    fun extractToolDigest(
        messages: List<ChatMessage>,
        maxResults: Int = MAX_DIGEST_RESULTS,
        maxCharsPerResult: Int = MAX_DIGEST_CHARS
    ): List<String> = messages.asReversed()
        .filter { it.role == Role.TOOL && it.content.isNotBlank() }
        .take(maxResults)
        .map { it.content.trim().take(maxCharsPerResult) }
        .asReversed()

    /**
     * 拼接"已有结果 → 请据此作答"的上下文段（供单次补全注入）。
     *
     * 语义要点：**明确要求"只能依据这些结果"**，否则模型会继续凭记忆编；
     * 同时说明"结果可能不全"，允许它如实说"只查到这些"。
     */
    fun digestContext(digest: List<String>): String? {
        if (digest.isEmpty()) return null
        return buildString {
            append("【你已经查到的结果（不用再查了，直接据此回答他）】\n")
            digest.forEach { append(it).append('\n') }
            append("（如果这些结果不足以回答，就照实说你只查到这些；不要编造没查到的内容。）")
        }
    }

    /** 默认最多取几条工具结果。 */
    const val MAX_DIGEST_RESULTS = 2

    /** 单条工具结果的最大字符数（防 prompt 撑爆；工具结果常含列表）。 */
    const val MAX_DIGEST_CHARS = 1200

    /**
     * 是否值得走工具回路。
     *
     * 只有"有工具 + 开关打开 + 本批不是纯媒体消息"才走；否则直接单次补全，
     * 避免为一句话多花一次请求（延迟与费用都会翻倍）。
     *
     * @param tools 已按 [QqReplyToolPolicy] 过滤后的工具
     * @param enabled 用户开关
     * @param hasVisibleText 本轮入站是否有可读文本（纯表情/图片消息不该触发工具搜索）
     */
    fun shouldUseTools(
        tools: List<io.prism.network.ToolDefinition>,
        enabled: Boolean,
        hasVisibleText: Boolean
    ): Boolean = enabled && tools.isNotEmpty() && hasVisibleText

    /**
     * 把一条 QQ 入站消息 + 历史转成回路所需的 [ChatMessage] 列表。
     *
     * **为什么只用最后一条 user 消息**：ADR-076 已把历史渲染进 user 文本（`buildReplyUserContext`），
     * 若这里再把历史做成多条 messages，同一段历史会出现两次，模型会当成两件事。
     * 因此回路只承接"本轮这一条"——历史仍然由 prompt 文本承担。
     */
    fun buildMessages(
        userContent: String,
        idGenerator: () -> Long = ::defaultIdGenerator
    ): List<ChatMessage> = listOf(
        ChatMessage(
            id = idGenerator(),
            role = Role.USER,
            content = userContent,
            timestamp = System.currentTimeMillis()
        )
    )

    /** 默认 id 生成器（与 `SkillExecutor.defaultIdGenerator` 同口径：时间戳起点自增）。 */
    private var counter = 0L
    private fun defaultIdGenerator(): Long = System.currentTimeMillis() * 1000 + (counter++ % 1000)

    /** 预算是否已耗尽（供调用方在每轮之间检查）。 */
    fun isBudgetExhausted(startedAtMs: Long, budgetMs: Long, nowMs: Long): Boolean =
        budgetMs > 0L && nowMs - startedAtMs >= budgetMs

    /** 流事件 → 是否属于"本轮回合结束"（用于日志与诊断）。 */
    fun isTerminal(event: StreamEvent): Boolean =
        event is StreamEvent.Done || event is StreamEvent.Error

    /** 工具调用事件 → 工具名（诊断日志用；不含参数）。 */
    fun toolNameOf(event: StreamEvent): String? =
        (event as? StreamEvent.ToolCallStart)?.toolName
}
