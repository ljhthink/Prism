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
 *
 * **v1 记忆深度优化**（US-101~104，参照 TencentDB-Agent-Memory）：
 * - 原子记忆抽取升级：LLM 抽取结构化原子记忆（content/type/priority/sourceMessageIds）
 * - 混合检索（US-102）：FTS5 BM25 + 向量 → RRF(k=60) 融合（[keywordIndex]）
 * - 批量去重（US-103）：[dedupeSessionMemories] 单次 LLM 批量判定 store/update/merge/skip
 * - 软衰减（US-103）：[computeRecallScore] 按「priority × exp(-λ·age) × (1+α·accessCount)」
 *   过滤低分记忆，移出注入集但保留在库
 * - 容量回收（US-103）：超限按「低 priority + 最旧」优先回收
 * - 注入预算（US-104）：条数上限 + 单条字符截断
 * - 命中自增 accessCount（软衰减使用频率信号）
 *
 * **线程安全**：[MemoryRepository] 内部 ObjectBox 保证原子读写；
 * [Embedder] 实现需保证并发安全（BR-concurrency-002）。
 *
 * **错误处理**（BR-error-handling-007）：
 * - [saveSessionMemories] 中 embed 失败时跳过该轮次（不中断整体保存），记录被跳过。
 * - [retrieveRelevantMemories] 中 embed 失败时返回空列表（降级为无跨会话记忆）。
 * - CancellationException 正确重抛。
 *
 * @param embedder 嵌入引擎（复用 M3 OnnxEmbedder，384 维向量）
 * @param memoryRepository L2 记忆仓库（Phase A 已实现）
 * @param summarizer 对话摘要生成器（UXR9 US-904 AC-2，可空：null 时保存路径不做 LLM 摘要，
 *   仅做重要性过滤 + 逐对存储；非空且 [saveSessionMemories] 传入 providerConfig 时先尝试
 *   LLM 摘要入库，失败降级为逐对存储；亦承担去重 LLM 调用）
 * @param retrievalThreshold 检索相似度阈值（UXR9 US-904 AC-3，默认 0.4）。测试注入
 *   [io.prism.embedding.FakeEmbedder]（非语义向量，相似度不可控）时传 0.0 禁用过滤
 * @param keywordIndex v1 US-102 关键词索引（混合检索用）。null 时降级为纯向量检索（向后兼容）；
 *   非空时 [retrieveRelevantMemories] 走「FTS5 BM25 + 向量 → RRF(k=60) 融合」。
 * @param memoryConfig v1 US-103/104 记忆配置仓库（可空：null 时使用默认常量）
 */
class CrossSessionMemoryManager(
    private val embedder: Embedder,
    private val memoryRepository: MemoryRepository,
    private val summarizer: ConversationSummarizer? = null,
    private val retrievalThreshold: Double = MEMORY_RETRIEVAL_THRESHOLD,
    private val keywordIndex: MemoryKeywordIndex? = null,
    private val memoryConfig: MemoryConfigRepository? = null
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
     * **容量回收**（v1 US-103）：入口先执行 [evictIfOverCapacity]，超限按
     * 「低 priority + 最久未访问」优先回收。
     *
     * **容错**：单个轮次对 embed 失败时跳过（不中断整体保存），返回实际保存数。
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

        // v1 US-103：容量上限回收（在新增前执行，保持库不超上限）
        evictIfOverCapacity()

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
            // v1 US-101：记忆溯源——LLM 抽取的每条记忆引用其来源消息 id（逗号分隔）。
            val sourceMessageIds = importantMessages.map { it.id }.joinToString(",")
            val memories = try {
                summarizer.extractMemories(importantMessages, providerConfig)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：协程取消必须重抛
            } catch (e: Exception) {
                // 记忆抽取 LLM 调用失败 → 记录并降级为规则抽取（逐对存储）
                Log.w(TAG, "saveSessionMemories: LLM 记忆抽取失败（${e::class.simpleName}），降级为规则抽取")
                null
            }
            when {
                // LLM 成功但判定无值得记住的记忆 → 不落库（根治"什么都记"）
                memories != null && memories.isEmpty() -> return 0
                // LLM 成功且有原子记忆 → 逐条入库（每条独立向量 + 结构化元数据）
                memories != null -> {
                    var saved = 0
                    for ((index, memory) in memories.withIndex()) {
                        try {
                            val content = "$MEMORY_SUMMARY_PREFIX${memory.content}"
                            val embedding = embedder.embed(content)
                            memoryRepository.save(
                                MemoryRecord(
                                    sessionId = sessionId,
                                    content = content,
                                    embedding = embedding,
                                    timestamp = System.currentTimeMillis(),
                                    turnCount = index + 1,
                                    // v1 US-101：LLM 抽取的重要性评分（0-100）与类型经
                                    // ExtractedMemory 规范化；版本号随新增为 1
                                    priority = memory.priority,
                                    version = 1,
                                    sourceMessageIds = sourceMessageIds
                                )
                            )
                            saved++
                        } catch (e: CancellationException) {
                            throw e // BR-error-handling-007
                        } catch (e: Exception) {
                            // M-2（guardrail TKN-UXR9-GUARDRAIL-001，CWE-754）：单条记忆
                            // embed/save 失败跳过该条，其余继续；全部失败才落入逐对存储兜底。
                            Log.w(TAG, "saveSessionMemories: 记忆第 ${index + 1} 条入库失败（${e::class.simpleName}），跳过")
                        }
                    }
                    if (saved > 0) return saved
                    // 全部入库失败 → 落入下方逐对存储（规则抽取降级，不丢数据）
                }
                // memories == null（LLM 调用失败）→ 落入下方逐对存储（规则抽取降级）
            }
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
                    timestamp = System.currentTimeMillis(),
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
     * v1 US-103：本会话记忆批量去重（会话结束异步，参照 TencentDB-Agent-Memory l1-dedup 两阶段）。
     *
     * **流程**：
     * 1. 取本会话 LLM 抽取路径保存的记忆（带 [MEMORY_SUMMARY_PREFIX] 前缀）
     * 2. **候选召回**：对每条新记忆向量 top-k 检索（[DEDUP_CANDIDATE_TOP_K]，排除自身）
     * 3. **单次 LLM 批量判定**：[ConversationSummarizer.dedupeMemories] 输出 store/update/merge/skip
     * 4. **应用**：skip → 删除新记录；update/merge → 合并入目标候选（版本号 +1）并删除新记录；
     *    store → 保留原记录
     *
     * **降级**：LLM 失败（返回 null）/ 去重关闭 / 无 summarizer / 无 providerConfig → 返回 0 不处理。
     *
     * @param sessionId 会话标识（本会话记忆的 sessionId）
     * @param providerConfig 激活的 Provider 配置（可为 null：null 时不执行去重）
     * @return 变更条数（skip 删除 + update/merge 合并的数量）；未执行返回 0
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun dedupeSessionMemories(sessionId: String, providerConfig: ProviderConfig?): Int {
        if (providerConfig == null || summarizer == null) return 0
        val dedupEnabled = memoryConfig?.isDedupEnabled() ?: MemoryConfigRepository.DEFAULT_DEDUP_ENABLED
        if (!dedupEnabled) return 0

        // 取本会话 LLM 抽取路径的记忆（带 [记忆] 前缀）
        val newMemories = memoryRepository.getBySession(sessionId)
            .filter { it.content.startsWith(MEMORY_SUMMARY_PREFIX) }
        if (newMemories.isEmpty()) return 0

        // 候选召回：每条新记忆向量 top5（排除自身；embed 失败降级为空池）
        val candidatePools = newMemories.map { rec ->
            try {
                val emb = rec.embedding ?: embedder.embed(rec.content)
                memoryRepository.searchByVector(emb, DEDUP_CANDIDATE_TOP_K)
                    .mapNotNull { hit ->
                        memoryRepository.all().firstOrNull { it.id == hit.recordId && it.id != rec.id }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

        val memories = newMemories.map {
            ExtractedMemory(content = it.content.removePrefix(MEMORY_SUMMARY_PREFIX), priority = it.priority)
        }
        val decisions = try {
            summarizer.dedupeMemories(memories, candidatePools, providerConfig)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // LLM 失败 → 降级：不处理（保留原记录，不丢数据）
        if (decisions == null) return 0

        var changed = 0
        for (decision in decisions) {
            val rec = newMemories.getOrNull(decision.memoryIndex) ?: continue
            try {
                when (decision.action) {
                    "skip" -> {
                        memoryRepository.deleteById(rec.id)
                        changed++
                    }
                    "update", "merge" -> {
                        val target = decision.targetId?.let { id ->
                            candidatePools.getOrNull(decision.memoryIndex)?.firstOrNull { it.id == id && it.id != rec.id }
                        }
                        if (target != null) {
                            val mergedContent = if (decision.action == "merge") {
                                "$MEMORY_SUMMARY_PREFIX${target.content.removePrefix(MEMORY_SUMMARY_PREFIX)}；" +
                                    rec.content.removePrefix(MEMORY_SUMMARY_PREFIX)
                            } else {
                                rec.content
                            }
                            memoryRepository.save(
                                target.copy(
                                    content = mergedContent.take(MAX_MERGED_CHARS),
                                    embedding = mergedEmbeddingOf(mergedContent, rec.embedding ?: target.embedding),
                                    priority = maxOf(target.priority, rec.priority),
                                    version = target.version + 1
                                )
                            )
                            memoryRepository.deleteById(rec.id)
                            changed++
                        }
                    }
                    // store：保留原记录（无需处理）
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "dedupeSessionMemories: 决策应用失败（${e::class.simpleName}）")
            }
        }
        return changed
    }

    /**
     * 新会话开始时检索相关历史记忆（US-033 AC-2 + AC-3，v1 US-102 混合检索 + US-103 软衰减 + US-104 预算）。
     *
     * **流程**：
     * 1. 将用户首条消息 [Embedder.embed] 生成查询向量
     * 2. [MemoryRepository.searchByVector] top-k 检索（默认 k=3）+ 相似度阈值过滤
     * 3. **v1 US-102**：若注入 [keywordIndex]，再走关键词 BM25 召回（FTS5/内存倒排），
     *    两路经 [RrfFusion.rrfMerge]（k=60）融合后取 top-k——解决中文精确词句
     *    （如"上次说的 Kotlin 协程"）向量相似度不足但关键词命中的短板
     * 4. **v1 US-103**：软衰减过滤——[computeRecallScore] < 注入阈值的记忆移出注入集（保留在库）
     * 5. **v1 US-104**：注入预算——条数上限（默认 5）+ 单条字符截断
     * 6. 命中记忆 [MemoryRepository.incrementAccessCount]（软衰减使用频率信号）
     *
     * **防污染**（AC-3）：仅返回 top-k 检索结果，不加载旧会话全文。
     *
     * **降级**：无 keywordIndex → 纯向量路径（向后兼容）；embed/keyword 索引不可用 → 返回
     * 向量结果或空列表，不崩溃。
     *
     * @param userMessage 用户首条消息文本
     * @param topK 返回结果数上限（默认 3，与 [MemoryRepository.DEFAULT_SEARCH_TOP_K] 对齐）
     * @return top-k 检索结果列表；空库/embed 失败/空消息返回空 list
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun retrieveRelevantMemories(
        userMessage: String,
        topK: Int = MemoryRepository.DEFAULT_SEARCH_TOP_K
    ): List<MemorySearchResult> {
        if (userMessage.isBlank()) return emptyList()

        // v1 US-103/104：读取软衰减与注入预算配置（未注入配置时用默认常量）
        val decayLambda = memoryConfig?.getDecayLambda() ?: MemoryConfigRepository.DEFAULT_DECAY_LAMBDA
        val decayAlpha = memoryConfig?.getDecayAlpha() ?: MemoryConfigRepository.DEFAULT_DECAY_ALPHA
        val decayThreshold = memoryConfig?.getDecayThreshold() ?: MemoryConfigRepository.DEFAULT_DECAY_THRESHOLD
        val injectionMaxResults = memoryConfig?.getInjectionMaxResults()
            ?: MemoryConfigRepository.DEFAULT_INJECTION_MAX_RESULTS
        val injectionMaxChars = memoryConfig?.getInjectionMaxChars()
            ?: MemoryConfigRepository.DEFAULT_INJECTION_MAX_CHARS

        return try {
            val queryEmbedding = embedder.embed(userMessage)
            // UXR9 US-904 AC-3：检索侧相似度阈值过滤（此前不过滤，低相关记忆也注入）。
            // 会话隔离：记忆按 sessionId 持久化；新会话启动时 sessionId 为全新 UUID，
            // 天然只命中旧会话记录（本会话记录尚未落库），再叠加阈值收窄语义噪声。
            val vectorResults = memoryRepository.searchByVector(queryEmbedding, topK)
                .filter { it.similarity >= retrievalThreshold }

            val keyword = keywordIndex
            val merged: List<MemorySearchResult> = if (keyword == null) {
                // 纯向量路径（未启用混合检索）
                vectorResults
            } else {
                // v1 US-102：关键词路径（版本化增量重建 + BM25 召回）
                val allRecords = memoryRepository.all()
                keyword.reconcile(allRecords, memoryRepository.mutationVersion)
                val keywordHits = keyword.search(userMessage, topK)
                if (keywordHits.isEmpty()) {
                    vectorResults
                } else {
                    // RRF 融合两路排名（纯排名融合，不依赖两路分数可比较）
                    val mergedIds = RrfFusion.rrfMergeTop(
                        lists = listOf(
                            vectorResults.map { it.recordId },
                            keywordHits.map { it.recordId }
                        ),
                        topK = topK
                    )
                    if (mergedIds.isEmpty()) {
                        vectorResults
                    } else {
                        // 回查记录构造 MemorySearchResult（携带元数据供软衰减/溯源）
                        val recordById = allRecords.associateBy { it.id }
                        val vectorById = vectorResults.associateBy { it.recordId }
                        mergedIds.mapNotNull { id ->
                            val record = recordById[id] ?: return@mapNotNull null
                            MemorySearchResult(
                                recordId = record.id,
                                sessionId = record.sessionId,
                                content = record.content,
                                similarity = vectorById[id]?.similarity ?: 0.0,
                                timestamp = record.timestamp,
                                turnCount = record.turnCount,
                                priority = record.priority,
                                accessCount = record.accessCount,
                                version = record.version,
                                sourceMessageIds = record.sourceMessageIds.orEmpty()
                            )
                        }
                    }
                }
            }

            // v1 US-103：软衰减过滤（低于注入阈值的移出注入集但保留在库）
            val now = System.currentTimeMillis()
            val decayed = merged.filter {
                computeRecallScore(
                    priority = it.priority,
                    accessCount = it.accessCount,
                    timestamp = it.timestamp,
                    now = now,
                    lambda = decayLambda,
                    alpha = decayAlpha
                ) >= decayThreshold
            }

            // v1 US-104：注入预算（条数上限 + 单条字符截断；截断为纯展示层，不影响库内容）
            val budgeted = decayed.take(injectionMaxResults.coerceAtLeast(1))
                .map { truncateContent(it, injectionMaxChars) }

            markAccessHits(budgeted)
            budgeted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // BR-error-handling-004：embed 或检索失败时记录日志（不含敏感信息），降级为空结果
            Log.w(TAG, "retrieveRelevantMemories: 检索失败（${e::class.simpleName}）")
            emptyList()
        }
    }

    /**
     * 软衰减评分（v1 US-103，纯函数可测，参照 Mem0 Ebbinghaus / Bjork 检索强度模型）。
     *
     * `recallScore = priority × exp(-λ·age_days) × (1 + α·accessCount)`
     * - priority：记忆重要性（0-100，LLM 抽取时赋值，越大越难遗忘）
     * - exp(-λ·age)：时间衰减（旧记忆降权）
     * - (1 + α·accessCount)：使用频率增强（越常命中越难遗忘）
     *
     * @param priority 记忆重要性
     * @param accessCount 命中次数
     * @param timestamp 记忆创建时间戳（毫秒）
     * @param now 当前时间戳（毫秒）
     * @param lambda 时间衰减系数 λ/天（默认 [MemoryConfigRepository.DEFAULT_DECAY_LAMBDA]）
     * @param alpha 频率增强系数 α（默认 [MemoryConfigRepository.DEFAULT_DECAY_ALPHA]）
     * @return 0~∞ 的召回评分（< 注入阈值时移出注入集）
     */
    internal fun computeRecallScore(
        priority: Int,
        accessCount: Long,
        timestamp: Long,
        now: Long = System.currentTimeMillis(),
        lambda: Double = MemoryConfigRepository.DEFAULT_DECAY_LAMBDA,
        alpha: Double = MemoryConfigRepository.DEFAULT_DECAY_ALPHA
    ): Double {
        val ageDays = (now - timestamp).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY
        val timeDecay = Math.exp(-lambda * ageDays)
        val usageBoost = 1.0 + alpha * accessCount
        return priority * timeDecay * usageBoost
    }

    /**
     * 注入单条字符截断（v1 US-104，纯函数可测）——截断为展示层，不影响库内容。
     */
    internal fun truncateContent(result: MemorySearchResult, maxChars: Int): MemorySearchResult {
        if (maxChars <= 0 || result.content.length <= maxChars) return result
        return result.copy(content = result.content.take(maxChars) + "…")
    }

    /**
     * 为检索命中的记忆自增 accessCount（v1 US-101/103：软衰减使用频率信号）。
     *
     * **设计**：命中即 +1（fire-and-forget，不阻塞检索返回；incrementAccessCount 不递增
     * mutationVersion，不会触发 FTS 全量重建）。
     *
     * @param results 检索命中的结果列表
     */
    private fun markAccessHits(results: List<MemorySearchResult>) {
        for (result in results) {
            try {
                memoryRepository.incrementAccessCount(result.recordId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 命中计数失败不影响检索结果（仅丢失一次频率信号）
                Log.w(TAG, "markAccessHits: 命中计数失败（${e::class.simpleName}）")
            }
        }
    }

    /**
     * v1 US-103：容量上限回收（超限按「低 priority + 最久未访问」优先回收）。
     */
    private suspend fun evictIfOverCapacity() {
        val capacity = memoryConfig?.getMemoryCapacity() ?: MemoryConfigRepository.DEFAULT_MEMORY_CAPACITY
        val evicted = memoryRepository.evictIfOverLimit(capacity)
        if (evicted > 0) {
            Log.i(TAG, "saveSessionMemories: 容量回收 $evicted 条（上限 $capacity）")
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
        // 0. v1 批次19（open-webui-memory 2b / Mem0 模式）：显式记忆指令直通——
        //    用户说"记住 X"是最高优先级信号，绕过一切过滤（含寒暄整句匹配）
        if (EXPLICIT_MEMORY_PATTERN.containsMatchIn(text)) return true
        // 1. 寒暄/确认/继续 → 不重要
        if (normalized in SKIP_PHRASES) return false
        // 1b. v1 批次19：身份类提问黑名单（open-webui-memory 2b：助手总结/身份盘点
        //     不得回写——"我是一个什么样的人？"含"我"信号词，若被存入会造成回音污染）
        if (IDENTITY_QUESTION_KEYWORDS.any { text.contains(it) }) return false
        // 2. 自我指涉偏好/身份/记忆诉求（持久用户属性）→ 重要
        if (ATOM_KEYWORDS.any { text.contains(it) }) {
            if (isPureQuery(text)) return false
            return true
        }
        // 3. 其余（普通问题/一次性任务/超短回应）→ 不重要
        return false
    }

    /**
     * 判断用户消息是否为"一次性对外查询请求"（非持久记忆）。
     */
    private fun isPureQuery(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("？") || t.endsWith("?") || t.endsWith("吗") ||
            t.endsWith("呢") || t.endsWith("呀") || t.endsWith("啊") ||
            t.endsWith("何") || t.endsWith("怎样") || t.endsWith("哪些")
        ) return true
        if (QUERY_VERBS.any { it in t }) return true
        return false
    }

    /**
     * v1 US-103 merge re-embed (guardrail FIX-3): merged text changed,
     * re-embed keeps vector consistent with content (fallback on failure).
     */
    private fun mergedEmbeddingOf(mergedContent: String, fallback: FloatArray?): FloatArray? = try {
        embedder.embed(mergedContent)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
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
        internal const val MEMORY_SUMMARY_PREFIX = "[记忆] "

        /**
         * L2 检索相似度阈值（UXR9 US-904 AC-3）。
         *
         * 记忆记录为轮次对/摘要文本，检索相关性要求低于知识库事实片段（0.5）——
         * 取 0.4 允许语义相近但非逐字匹配的历史记忆命中，同时过滤明显无关噪声。
         * 多语言模型下无关中文句对实测最大 0.322（ChineseSimilarityDiagnosticTest），
         * 0.4 位于分隔区上方，具备区分余量。
         */
        internal const val MEMORY_RETRIEVAL_THRESHOLD = 0.4

        /** v1 US-103：去重候选召回 top-k（参照 TencentDB-Agent-Memory l1-dedup top5）。 */
        internal const val DEDUP_CANDIDATE_TOP_K = 5

        /** v1 US-103：update/merge 后合并记忆的字符上限（防无限膨胀）。 */
        internal const val MAX_MERGED_CHARS = 400

        /** 毫秒/天（软衰减年龄计算）。 */
        internal const val MILLIS_PER_DAY = 86_400_000L

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
         * 可沉淀为 L2 跨会话记忆的用户消息信号词（v1 深度优化，对齐 TencentDB-Agent-Memory L1 Atom）。
         *
         * 仅收录**自我指涉的持久属性**（偏好 / 身份 / 习惯 / 计划决定 / 明确记忆诉求），
         * 因为这些才是未来会话可复用的用户画像；**不收录**在一次性问答中也会出现的
         * 泛化名词（如"项目/任务/计划/公司/团队/工作"）与请求动词（"分析/总结/推荐"）——
         * 它们会被一次性问题触发，造成"什么都记"的污染。
         */
        private val ATOM_KEYWORDS = listOf(
            "我喜欢", "我不喜欢", "我讨厌", "我爱", "我恨",
            "我是", "我叫", "我住在", "我今年", "我工作", "我学", "我的",
            "我习惯", "我经常", "我每天", "我周末",
            "我打算", "我计划", "我决定",
            // 仅显式记忆诉求命令（淡化的"记住/以后/重要/务必"等会被一次性陈述触发，造成 L2 噪声）
            "请记住", "别忘了", "最爱", "偏好"
        )

        /**
         * 一次性对外查询/任务请求的意图动词（进入该句式 → 判定为一次性信息需求，不沉淀记忆）。
         * 用于 [isPureQuery] 识别"帮我查一下 X""介绍一下 X"等请求，与用户自身画像无关。
         */
        private val QUERY_VERBS = listOf(
            "帮我", "帮我查", "帮我搜", "介绍一下", "简单介绍", "查一下", "搜一下",
            "分析一下", "总结一下", "推荐一下", "介绍", "查询", "搜索", "解释一下",
            "比较一下", "讲讲", "说说", "写一个", "写一份", "翻译"
        )

        /**
         * v1 批次19（open-webui-memory / Mem0 模式）：显式记忆指令直通模式——
         * 用户说"记住 X"是最强记忆信号，绕过全部过滤写入 L2。
         */
        private val EXPLICIT_MEMORY_PATTERN = Regex(
            """记住|記住|remember( that| this|:)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * v1 批次19（open-webui-memory 2b）：身份类提问关键词黑名单——
         * 此类提问（含 ATOM 信号词"我"）若被回写 L2，会造成"LLM 总结身份 → 再被检索注入"
         * 的回音污染；助手对身份的总结不得作为记忆回写。
         */
        private val IDENTITY_QUESTION_KEYWORDS = listOf(
            "什么样的人", "你知道我什么", "关于我的记忆", "我的记忆里",
            "你记得我什么", "了解我多少", "介绍一下我", "说说我是谁"
        )
    }
}
