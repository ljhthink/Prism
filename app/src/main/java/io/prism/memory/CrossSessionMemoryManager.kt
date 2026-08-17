package io.prism.memory

import android.util.Log
import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.MemorySearchResult
import io.prism.data.ProviderConfig
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
 * @param summarizer 对话摘要生成器（UXR9 US-904 AC-2，可空：null 时保存路径不做 LLM 摘要，
 *   仅做重要性过滤 + 逐对存储；非空且 [saveSessionMemories] 传入 providerConfig 时先尝试
 *   LLM 摘要入库，失败降级为逐对存储）
 * @param retrievalThreshold 检索相似度阈值（UXR9 US-904 AC-3，默认 0.4）。测试注入
 *   [io.prism.embedding.FakeEmbedder]（非语义向量，相似度不可控）时传 0.0 禁用过滤
 */
class CrossSessionMemoryManager(
    private val embedder: Embedder,
    private val memoryRepository: MemoryRepository,
    private val summarizer: ConversationSummarizer? = null,
    private val retrievalThreshold: Double = MEMORY_RETRIEVAL_THRESHOLD
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
     * @param providerConfig 激活的 Provider 配置（UXR9 US-904 AC-2，可空：null 或
     *   [summarizer] 为 null 时跳过 LLM 摘要，仅重要性过滤 + 逐对存储）
     * @return 实际保存的记忆条数（可能因 embed 失败或消息为空而 < 请求条数）
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun saveSessionMemories(
        sessionId: String,
        messages: List<ChatMessage>,
        maxMemories: Int = DEFAULT_MAX_MEMORIES_PER_SESSION,
        providerConfig: ProviderConfig? = null
    ): Int {
        if (messages.isEmpty()) return 0

        // UXR9 Bug4 修复：按重要性过滤轮次对（跳过寒暄/确认/一次性闲聊），
        // 避免无价值内容全量堆积进 L2 跨会话记忆污染后续会话。
        val turnPairs = groupIntoTurnPairs(filterKeyMessages(messages))
            .filter { (user, _) -> isImportantTurnPair(user.content) }
        if (turnPairs.isEmpty()) return 0

        // UXR9 US-904 AC-2：对有价值内容生成摘要（LLM 摘要，失败降级为规则抽取）后入库，
        // 不再原样全量入库。摘要成功时以**单条摘要记录**入库（本身就是压缩结果，
        // maxMemories 语义变为「最多生成 1 条摘要」）；失败/未注入 summarizer 时
        // 降级为「规则抽取」——仅逐对存储已过滤的重要轮次（仍非全量）。
        if (summarizer != null && providerConfig != null && turnPairs.size >= MIN_SUMMARY_TURNS) {
            val importantMessages = turnPairs.flatMap { (user, assistant) -> listOf(user, assistant) }
                .let { msgs ->
                    if (msgs.size <= MAX_SUMMARY_INPUT_MESSAGES) msgs
                    else msgs.takeLast(MAX_SUMMARY_INPUT_MESSAGES)
                }
                .map { msg ->
                    if (msg.content.length <= MAX_SUMMARY_MSG_CHARS) msg
                    else msg.copy(content = msg.content.takeLast(MAX_SUMMARY_MSG_CHARS))
                }
            val summary = try {
                summarizer.summarize(importantMessages, providerConfig)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：协程取消必须重抛
            } catch (e: Exception) {
                // 摘要 LLM 调用失败 → 记录并降级为规则抽取（逐对存储）
                Log.w(TAG, "saveSessionMemories: LLM 摘要失败（${e::class.simpleName}），降级为规则抽取")
                null
            }?.takeIf { it.isNotBlank() }
            if (summary != null) {
                val summarySaved = try {
                    val content = "$MEMORY_SUMMARY_PREFIX$summary"
                    val embedding = embedder.embed(content)
                    memoryRepository.save(
                        MemoryRecord(
                            sessionId = sessionId,
                            content = content,
                            embedding = embedding,
                            timestamp = turnPairs.first().first.timestamp,
                            turnCount = 1
                        )
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // M-2（guardrail TKN-UXR9-GUARDRAIL-001，CWE-754）：摘要记录 embed/save 失败
                    // **不得 return 0 丢弃全部记忆**——须落入下方逐对存储（规则抽取降级），
                    // 保证有价值内容不因摘要入库失败而整体丢失。
                    Log.w(TAG, "saveSessionMemories: 摘要入库失败（${e::class.simpleName}），降级为逐对存储")
                    false
                }
                if (summarySaved) return 1
                // 摘要入库失败 → 落入下方逐对存储（规则抽取降级）
            }
            // summary == null → 落入下方逐对存储（规则抽取降级）
        }

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
            // UXR9 US-904 AC-3：检索侧相似度阈值过滤（此前不过滤，低相关记忆也注入）。
            // 会话隔离：记忆按 sessionId 持久化；新会话启动时 sessionId 为全新 UUID，
            // 天然只命中旧会话记录（本会话记录尚未落库），再叠加阈值收窄语义噪声。
            memoryRepository.searchByVector(queryEmbedding, topK)
                .filter { it.similarity >= retrievalThreshold }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // BR-error-handling-004：embed 或检索失败时记录日志（不含敏感信息），降级为空结果
            Log.w(TAG, "retrieveRelevantMemories: 检索失败（${e::class.simpleName}）")
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
            // M-3（guardrail TKN-UXR9-GUARDRAIL-001，CWE-20）：排除系统提示消息
            //（isSystemNotice，如"图片编码失败"）——此类消息仅供 UI 展示、无记忆价值，
            // 若不排除会经重要性判定存入 L2 污染后续会话。
            !msg.isSystemNotice &&
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

    /**
     * 判断一个轮次对是否有跨会话记忆价值（UXR9 Bug4 修复，纯函数可测）。
     *
     * **背景**（网络调研：MemGPT/LangChain/Agent 记忆最佳实践）：L2 跨会话记忆应
     * **选择性存储**——只记住用户偏好、关键身份、任务结论等有长期价值的内容，
     * 跳过寒暄/确认/一次性闲聊。此前 `saveSessionMemories` **无条件全量入库**，
     * 导致"你好""好的"等大量无关内容堆积进 L2，检索时污染新会话上下文。
     *
     * **判定（零额外 LLM 成本的启发式）**：
     * 1. 用户消息归一化后整句命中 [SKIP_PHRASES]（寒暄/确认/继续）→ 不重要
     * 2. 用户消息含 [IMPORTANCE_KEYWORDS]（偏好/身份/习惯/任务信号词）→ 重要
     * 3. 用户消息为实质问题（长度 ≥ [MIN_IMPORTANT_LEN] 或含疑问词）→ 重要
     * 4. 其余（超短、纯回应）→ 不重要
     *
     * @param userText 轮次对的用户消息文本（未 trim 也可，内部处理）
     * @return true 应保存到 L2；false 跳过
     */
    internal fun isImportantTurnPair(userText: String): Boolean {
        val text = userText.trim()
        if (text.isEmpty()) return false
        val normalized = text.lowercase().filter { it.isLetterOrDigit() }
        // 1. 寒暄/确认/继续 → 不重要
        if (normalized in SKIP_PHRASES) return false
        // 2. 偏好/身份/任务信号词 → 重要
        if (IMPORTANCE_KEYWORDS.any { text.contains(it) }) return true
        // 3. 实质问题（疑问词或足够长）→ 重要
        if (text.length >= MIN_IMPORTANT_LEN) return true
        if (QUESTION_WORDS.any { text.contains(it) }) return true
        return false
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

        /**
         * L2 摘要记忆的内容前缀（UXR9 US-904 AC-2）。
         *
         * 标识该记忆来自会话结束时的 LLM 摘要压缩（区别于逐对存储的原文记录），
         * 检索注入时仍走 [MEMORY_CONTEXT_PREFIX] 统一格式。
         */
        internal const val MEMORY_SUMMARY_PREFIX = "[摘要] "

        /**
         * L2 检索相似度阈值（UXR9 US-904 AC-3）。
         *
         * 记忆记录为轮次对/摘要文本，检索相关性要求低于知识库事实片段（0.5）——
         * 取 0.4 允许语义相近但非逐字匹配的历史记忆命中，同时过滤明显无关噪声。
         * 多语言模型下无关中文句对实测最大 0.322（ChineseSimilarityDiagnosticTest），
         * 0.4 位于分隔区上方，具备区分余量。
         */
        internal const val MEMORY_RETRIEVAL_THRESHOLD = 0.4

        /**
         * 寒暄/确认/继续类短语（归一化后整句命中即跳过 L2 记忆）。
         * 与 RAG 需求预判（BR-interface-017）同源：整句精确匹配，禁止前缀匹配误伤。
         */
        private val SKIP_PHRASES = setOf(
            "你好", "您好", "hi", "hello", "嗨", "哈喽", "在吗", "早上好", "下午好", "晚上好",
            "好", "好的", "好的好的", "好吧", "行", "行吧", "嗯", "嗯嗯", "哦", "哦哦", "啊",
            "明白", "明白了", "知道", "知道了", "了解", "了解了", "收到", "ok", "okay",
            "对", "对的", "没错", "同意", "没问题", "可以", "可以的",
            "谢谢", "谢谢你", "感谢", "辛苦了", "多谢", "thanks", "thankyou",
            "再见", "拜拜", "晚安", "继续", "接着", "然后呢", "好的谢谢", "好谢谢", "嗯嗯好的",
            "好的没问题", "好的继续", "继续吧"
        )

        /**
         * L2 摘要 LLM 调用最小门槛（UXR9 Q-MED-3）。
         *
         * 重要轮次对 < 该值时不触发 LLM 摘要（直接逐对存储），避免每次会话关闭都产生
         * BYOK 额外调用成本。仅"重要轮次足够多、值得压缩"时才调用 LLM 摘要。
         */
        internal const val MIN_SUMMARY_TURNS = 3

        /** L2 摘要 LLM 调用输入上限：最多取最近 N 条消息（≈N/2 个轮次对）。 */
        internal const val MAX_SUMMARY_INPUT_MESSAGES = 12

        /** L2 摘要 LLM 调用输入上限：单条消息最多截取尾部 N 字符（防长文档/大文本撑爆输入）。 */
        internal const val MAX_SUMMARY_MSG_CHARS = 2000

        /**
         * 偏好/身份/习惯/任务信号词（含任一带长期记忆价值 → 保存到 L2）。
         * 覆盖：偏好、身份、习惯、计划、目标、重要事实。
         */
        private val IMPORTANCE_KEYWORDS = listOf(
            "我喜欢", "我不喜欢", "我讨厌", "我爱", "我恨",
            "我是", "我叫", "我住在", "我在", "我工作", "我学", "我的", "我今年",
            "我希望", "我想", "我要", "我打算", "我计划", "我决定",
            "我习惯", "我经常", "我每天", "我周末",
            "请记住", "记住", "记得", "以后", "下次", "别忘了", "重要", "关键",
            "偏好", "喜欢", "讨厌", "最爱",
            "不要", "最好", "务必", "一定",
            "目标", "计划", "任务", "项目", "工作", "公司", "团队"
        )

        /** 疑问词（含任一即视为实质问题，保存到 L2）。 */
        private val QUESTION_WORDS = listOf(
            "什么", "为什么", "怎么", "如何", "多少", "哪些", "是否", "吗", "呢", "谁", "哪里",
            "请问", "介绍", "分析", "总结", "比较", "解释", "说明", "推荐", "建议"
        )

        /** 用户消息达到该长度即视为实质内容（重要，保存到 L2）。 */
        private const val MIN_IMPORTANT_LEN = 8
    }
}
