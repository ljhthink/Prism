package io.prism.memory

import android.util.Log
import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.embedding.Embedder
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException

/**
 * L2 跨会话记忆管理器（US-033，ADR-015 5.4）—— 向量化存储 + top-k 检索 + 防污染。
 *
 * **三层记忆架构定位**（ADR-015）：
 * - L1 会话内：[SlidingWindowMemoryManager]（滑动窗口 + 摘要压缩）
 * - L2 跨会话：**本管理器**，向量化存储对话片段 + 语义检索相关历史
 * - L3 用户画像：[UserProfileManager]（US-034）
 *
 * **核心职责**：
 * 1. **会话结束时**（[saveSessionMemories]）：将本会话关键对话向量化存入 [MemoryRepository]，
 *    每条 [MemoryRecord] 存储一个 user+assistant 轮次对，embedding 基于完整轮次文本。
 * 2. **新会话开始时**（[retrieveRelevantMemories]）：将用户首条消息向量化，
 *    [MemoryRepository.searchByVector] top-k 检索相关历史。
 * 3. **防污染**（ADR-015 决策 2）：仅注入 top-k 检索结果作为 context，不加载旧会话全文。
 *    新会话上下文只包含语义相关的历史片段，保持干净。
 * 4. **格式化注入**（[formatMemoriesAsContext]）：将检索结果格式化为 systemPrompt section，
 *    与 RAG 上下文合并注入新会话。
 *
 * **设计决策**（ADR-015 5.4）：
 * - **轮次对存储**：将 user+assistant 连续消息对作为一个 [MemoryRecord] 存储，
 *   而非单独存储每条消息。理由：
 *   (a) embedding 捕获完整 Q&A 语义，比单独 user/assistant 更丰富；
 *   (b) 检索后注入时直接可用（含问题和回答），无需二次拼接；
 *   (c) 记录数减半，HNSW 检索效率更高。
 * - **复用 M3 Embedder**：[embedder.embed] 生成 384 维向量，与 MemoryRecord.embedding 索引对齐。
 * - **无相似度阈值过滤**：数据层 [MemoryRepository.searchByVector] 保留数学语义，
 *   本管理器也不做阈值过滤（与 KnowledgeBaseRepository.search 一致），
 *   让 LLM 自行判断检索结果的相关性。若未来需要阈值，可在调用方添加。
 *
 * **线程安全**：[MemoryRepository] 内部 ObjectBox 保证原子读写；
 * [Embedder] 实现需保证并发安全（BR-concurrency-002）。
 *
 * **错误处理**（BR-error-handling-007）：
 * - [saveSessionMemories] 中 embed 失败时跳过该轮次（不中断整体保存），记录被跳过。
 * - [retrieveRelevantMemories] 中 embed 失败时返回空列表（降级为无跨会话记忆）。
 * - CancellationException 正确重抛。
 *
 * US-033 验收标准：
 * - AC-1：会话结束时向量化存储
 * - AC-2：新会话 top-k 检索
 * - AC-3：防污染（仅注入检索结果）
 * - AC-4：检索结果注入 systemPrompt
 * - AC-5：单元测试通过
 *
 * @param embedder 嵌入引擎（复用 M3 OnnxEmbedder，384 维向量）
 * @param memoryRepository L2 记忆仓库（Phase A 已实现）
 */
class CrossSessionMemoryManager(
    private val embedder: Embedder,
    private val memoryRepository: MemoryRepository
) {

    /**
     * 会话结束时保存关键对话为跨会话记忆（US-033 AC-1）。
     *
     * **流程**：
     * 1. [filterKeyMessages] 过滤出 user+assistant 消息（跳过 tool/empty）
     * 2. [groupIntoTurnPairs] 将连续的 user+assistant 消息分组为轮次对
     * 3. 对每个轮次对 [Embedder.embed] 生成 384 维向量
     * 4. 构造 [MemoryRecord] 并 [MemoryRepository.save] 持久化
     *
     * **轮次对格式**：`[用户] question\n[助手] answer`
     * 该格式与 [formatMemoriesAsContext] 的注入格式一致，检索后可直接注入。
     *
     * **限流**：单会话最多保存 [DEFAULT_MAX_MEMORIES_PER_SESSION] 条记忆（默认 20），
     * 防止超长会话产生过多记录拖慢检索。超过限制时仅保存前 N 个轮次对。
     *
     * **容错**：单个轮次对 embed 失败时跳过（不中断整体保存），返回实际保存数。
     * 这保证即使部分消息嵌入失败，其余记忆仍能保存。
     *
     * @param sessionId 当前会话标识（运行时生成的 UUID）
     * @param messages 本会话完整消息列表（按时间顺序）
     * @param maxMemories 单会话最大记忆条数（默认 20，防止超长会话过载）
     * @return 实际保存的记忆条数（可能因 embed 失败或消息为空而 < 请求条数）
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun saveSessionMemories(
        sessionId: String,
        messages: List<ChatMessage>,
        maxMemories: Int = DEFAULT_MAX_MEMORIES_PER_SESSION
    ): Int {
        if (messages.isEmpty()) return 0

        val turnPairs = groupIntoTurnPairs(filterKeyMessages(messages))
        if (turnPairs.isEmpty()) return 0

        val limitedPairs = turnPairs.take(maxMemories.coerceIn(1, DEFAULT_MAX_MEMORIES_PER_SESSION))
        var savedCount = 0

        for ((index, pair) in limitedPairs.withIndex()) {
            try {
                val content = formatTurnPair(pair)
                val embedding = embedder.embed(content)
                val record = MemoryRecord(
                    sessionId = sessionId,
                    content = content,
                    embedding = embedding,
                    timestamp = pair.first.timestamp,
                    turnCount = index + 1
                )
                memoryRepository.save(record)
                savedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // BR-error-handling-004：embed 或 save 失败时记录日志（不含敏感信息），跳过该轮次
                Log.w(TAG, "saveSessionMemories: 跳过轮次 ${index + 1}（embed/save 失败: ${e.javaClass.simpleName}）")
            }
        }
        return savedCount
    }

    /**
     * 新会话开始时检索相关历史记忆（US-033 AC-2 + AC-3）。
     *
     * **流程**：
     * 1. 将用户首条消息 [Embedder.embed] 生成查询向量
     * 2. [MemoryRepository.searchByVector] top-k 检索（默认 k=3）
     * 3. 返回检索结果（按相似度降序）
     *
     * **防污染**（AC-3）：仅返回 top-k 检索结果，不加载旧会话全文。
     * 调用方通过 [formatMemoriesAsContext] 将结果注入新会话 systemPrompt。
     *
     * **空消息处理**：用户消息为空白时返回空列表（不执行检索）。
     *
     * **容错**：embed 失败时返回空列表（降级为无跨会话记忆），不崩溃。
     * CancellationException 正确重抛。
     *
     * @param userMessage 用户首条消息文本
     * @param topK 返回结果数上限（默认 3，与 [MemoryRepository.DEFAULT_SEARCH_TOP_K] 对齐）
     * @return top-k 检索结果列表（按相似度降序）；空库/embed 失败/空消息返回空 list
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun retrieveRelevantMemories(
        userMessage: String,
        topK: Int = MemoryRepository.DEFAULT_SEARCH_TOP_K
    ): List<MemorySearchResult> {
        if (userMessage.isBlank()) return emptyList()

        return try {
            val queryEmbedding = embedder.embed(userMessage)
            memoryRepository.searchByVector(queryEmbedding, topK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // BR-error-handling-004：embed 或检索失败时记录日志（不含敏感信息），降级为空结果
            Log.w(TAG, "retrieveRelevantMemories: 检索失败（${e.javaClass.simpleName}）")
            emptyList()
        }
    }

    /**
     * 格式化检索结果为 systemPrompt section（US-033 AC-4）。
     *
     * **格式**：
     * ```
     * 相关历史对话：
     * 1. [用户] ... [助手] ...
     * 2. [用户] ... [助手] ...
     * 3. [用户] ... [助手] ...
     * ```
     *
     * 该 section 由调用方（ConversationViewModel，US-035）与 RAG 上下文合并注入
     * systemPrompt，让 LLM 感知跨会话历史但不被污染。
     *
     * @param results 检索结果列表（由 [retrieveRelevantMemories] 返回）
     * @return 格式化文本；空列表返回 null（表示无跨会话记忆需注入）
     */
    fun formatMemoriesAsContext(results: List<MemorySearchResult>): String? {
        if (results.isEmpty()) return null

        val formatted = results.mapIndexed { index, result ->
            "${index + 1}. ${result.content}"
        }.joinToString("\n")

        return "$MEMORY_CONTEXT_PREFIX$formatted"
    }

    /**
     * 过滤关键消息：仅保留 user+assistant 且 content 非空（US-033 AC-1 前置处理）。
     *
     * 跳过 Role.TOOL（工具调用结果不含语义对话内容）和空 content 消息
     * （如流式占位消息，BR-interface-003/004）。
     *
     * 纯函数，可测。
     */
    internal fun filterKeyMessages(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filter { msg ->
            (msg.role == Role.USER || msg.role == Role.ASSISTANT) && msg.content.isNotBlank()
        }

    /**
     * 将消息列表分组为 user+assistant 轮次对（US-033 AC-1 核心逻辑）。
     *
     * **配对规则**：
     * - 遍历消息，遇到 USER 消息时与紧随其后的 ASSISTANT 消息配对
     * - 若 USER 后无 ASSISTANT（如最后一条消息是 user），跳过该 user（不完整轮次不存储）
     * - 连续多个 ASSISTANT（如分片响应）取第一个与 USER 配对
     * - 连续多个 USER（如用户连发）各自尝试配对后续 ASSISTANT
     *
     * **返回**：Pair<userMessage, assistantMessage> 列表
     *
     * 纯函数，可测。
     */
    internal fun groupIntoTurnPairs(messages: List<ChatMessage>): List<Pair<ChatMessage, ChatMessage>> {
        val pairs = mutableListOf<Pair<ChatMessage, ChatMessage>>()
        var i = 0
        while (i < messages.size) {
            if (messages[i].role == Role.USER) {
                // 找到紧随其后的 ASSISTANT
                var j = i + 1
                while (j < messages.size && messages[j].role != Role.ASSISTANT) {
                    if (messages[j].role == Role.USER) {
                        // 遇到下一个 USER，当前 USER 无配对 ASSISTANT，跳过
                        break
                    }
                    j++
                }
                if (j < messages.size && messages[j].role == Role.ASSISTANT) {
                    pairs.add(messages[i] to messages[j])
                    i = j + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return pairs
    }

    /**
     * 格式化轮次对为存储/注入文本（US-033 AC-1 + AC-4 共用格式）。
     *
     * 格式：`[用户] question\n[助手] answer`
     *
     * 该格式同时用于：
     * - 存储到 [MemoryRecord.content]（embed 基于此文本）
     * - 检索后注入 systemPrompt（[formatMemoriesAsContext] 直接引用 result.content）
     *
     * 纯函数，可测。
     */
    internal fun formatTurnPair(pair: Pair<ChatMessage, ChatMessage>): String {
        val (user, assistant) = pair
        return "[用户] ${user.content}\n[助手] ${assistant.content}"
    }

    companion object {
        /** 日志 Tag（BR-error-handling-004：catch 块日志归类）。 */
        private const val TAG = "CrossSessionMemory"

        /**
         * 单会话最大记忆条数（ADR-015 5.4）。
         *
         * 默认 20，防止超长会话产生过多记录拖慢 HNSW 检索。
         * 每条记忆是一个 user+assistant 轮次对，20 条覆盖 ~40 轮对话。
         */
        const val DEFAULT_MAX_MEMORIES_PER_SESSION = 20

        /** 跨会话记忆注入 systemPrompt 的前缀（US-033 AC-4）。 */
        internal const val MEMORY_CONTEXT_PREFIX = "相关历史对话：\n"
    }
}
