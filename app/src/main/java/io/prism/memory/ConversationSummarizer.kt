package io.prism.memory

import io.prism.data.MemoryRecord
import io.prism.data.ProviderConfig
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
     * UXR11 U5（ADR-033）+ v1 US-101（记忆深度优化）：从对话中抽取**可跨会话复用的原子记忆**。
     *
     * 参考 TencentDB-Agent-Memory 的 Chat Memory 理念（"记忆资产，而非聊天日志仓库"）：
     * L2 只应保留**关于用户**的可复用信息（偏好/事实/决策），而**非**对话过程或一次性
     * 信息查询。此前用 [summarize]（对话摘要）作为 L2 内容，把"用户问过 X、我回答了 Y"
     * 这类一次性查询也摘要入库（真机实测：L2 基本全是无效信息）。
     *
     * **v1 US-101 升级**（参照 TencentDB-Agent-Memory L1 提取管线）：
     * - 单次 LLM 调用同时完成「场景切分 + 记忆提取」，输出 JSON 数组（结构化）
     * - 每条记忆携带 `content` / `type`（persona/episodic/instruction）/ `priority`（0-100）
     * - [type] 经 [ExtractedMemory.normalizeType] 规范化；[priority] 经
     *   [ExtractedMemory.normalizePriority] 兜底（非数字→50）
     * - 解析失败（非 JSON）→ 降级为行式解析（每条 = content，type=general，priority=50）
     *
     * **与 [summarize] 的区别**：
     * - [summarize]：压缩**对话过程**（L1 滑动窗口用，会话内上下文）
     * - [extractMemories]：抽取**关于用户的原子记忆**（L2 跨会话用，可复用资产）
     *
     * **返回值语义**：
     * - `null`：LLM 调用失败（调用方降级为规则抽取，不丢数据）
     * - `emptyList()`：LLM 成功但判定**无值得跨会话记住的记忆**（调用方应跳过，不落库）
     * - 非空：结构化原子记忆列表
     *
     * @param messages 本会话重要轮次消息（已过滤寒暄/确认）
     * @param config 目标 Provider 配置
     * @return 结构化原子记忆列表；失败返回 null
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun extractMemories(messages: List<ChatMessage>, config: ProviderConfig): List<ExtractedMemory>? {
        if (messages.isEmpty()) return emptyList()
        return try {
            val raw = completionProvider.chatCompletion(
                config = config,
                messages = messages,
                systemPrompt = buildMemoryExtractionPrompt()
            )
            if (raw.isNullOrBlank()) return emptyList()
            parseMemories(raw)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // 防御性兜底：ChatCompletionProvider 内部已捕获异常返回 null
            null
        }
    }

    /**
     * 解析记忆抽取 LLM 输出（v1 US-101，纯函数可测）。
     *
     * **解析顺序**（两级降级）：
     * 1. 尝试 JSON 数组解析（首选，LLM 按新 prompt 输出 `[{content,type,priority}]`）
     * 2. 失败 → 行式解析兜底（兼容旧格式：每行一条记忆文本）
     *
     * **JSON 防御**：
     * - 剥离可能的 markdown 代码围栏（```json ... ```）
     * - `ignoreUnknownKeys` 容忍多余字段
     * - 类型经 [ExtractedMemory.normalizeType] 规范化、优先级经
     *   [ExtractedMemory.normalizePriority] 兜底、单条内容截断上限 [MAX_MEMORY_ITEM_CHARS]
     * - JSON 合法但数组为空 → 返回空列表（调用方据此判定"无值得记录"，不落库）
     *
     * @param raw LLM 原始输出文本
     * @return 解析后的原子记忆列表（可能为空）
     */
    internal fun parseMemories(raw: String): List<ExtractedMemory> {
        val json = tryParseJsonArray(raw)
        if (json != null) return json
        return parseLineBased(raw)
    }

    /**
     * JSON 数组解析（v1 US-101，纯函数可测）。
     *
     * @param raw LLM 原始输出
     * @return 解析成功返回记忆列表（可为空）；非 JSON 或格式不符返回 null（触发行式降级）
     */
    internal fun tryParseJsonArray(raw: String): List<ExtractedMemory>? {
        val cleaned = raw.trim()
            // 剥离 markdown 代码围栏（```json ... ``` / ``` ... ```）
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonArray = try {
            MEMORY_JSON.parseToJsonElement(cleaned) as? JsonArray
        } catch (e: Exception) {
            return null
        } ?: return null
        if (jsonArray.isEmpty()) return emptyList()
        val result = ArrayList<ExtractedMemory>(jsonArray.size)
        for (el in jsonArray) {
            val obj = el as? JsonObject ?: continue
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull
                ?.take(MAX_MEMORY_ITEM_CHARS)
                ?.trim()
            if (content.isNullOrBlank() || content == "无" || content.length <= 1) continue
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: ""
            val priority = obj["priority"]?.let {
                if (it is JsonPrimitive) it.contentOrNull else it.toString()
            }
            result.add(
                ExtractedMemory(
                    content = content,
                    type = ExtractedMemory.normalizeType(type),
                    priority = ExtractedMemory.normalizePriority(priority)
                )
            )
        }
        return result
    }

    /**
     * 行式解析兜底（兼容旧格式，纯函数可测）。
     *
     * 每行一条记忆：剥离序号/列表符号，去空，截断上限。与 UXR11 U5 旧逻辑一致，
     * 类型/优先级用默认值（general / 50）。
     */
    internal fun parseLineBased(raw: String): List<ExtractedMemory> {
        return raw.lineSequence()
            .map { line ->
                line.trim()
                    .replaceFirst(NUMBERED_LIST_PREFIX_REGEX, "")
                    .trim()
                    .removePrefix("-").removePrefix("•").removePrefix("·").removePrefix("*")
                    .trim()
                    // guardrail M-1（第二轮复审）：单条原子记忆截断上限，防病态/幻觉超长行
                    .take(MAX_MEMORY_ITEM_CHARS)
            }
            .filter { it.isNotBlank() && it != "无" && it.length > 1 }
            .take(MEMORY_EXTRACT_MAX)
            .map { ExtractedMemory(content = it) }
            .toList()
    }

    /**
     * 构建记忆抽取 prompt（纯函数，可测）。
     *
     * **prompt 设计**（v1 US-101，参照 TencentDB-Agent-Memory L1 提取管线）：
     * - 明确"原子记忆"定义：关于用户的偏好、个人信息事实、长期决策/立场
     * - **显式排除一次性信息查询**（"搜索X""查Y背景"）——这是用户反馈"L2 什么都记"的根因
     * - 要求 JSON 结构化输出（content/type/priority），并给出类型枚举与示例
     * - 单次调用同时完成场景切分与提取（"判断记忆所属场景，只抽取可复用信息"）
     */
    internal fun buildMemoryExtractionPrompt(): String = MEMORY_EXTRACTION_PROMPT_TEMPLATE

    /**
     * v1 US-103：批量去重判定（参照 TencentDB-Agent-Memory `l1-dedup.ts` 两阶段去重）。
     *
     * 对「新提取的原子记忆 + 各自候选池（向量 top5 已存在的记忆）」做**单次 LLM 批量判定**，
     * 输出每条的决策：`store`（新增）/ `update`（更新候选内容）/ `merge`（合并）/ `skip`（跳过）。
     *
     * **返回值语义**：
     * - `null`：LLM 调用失败（调用方降级为全部 store，不丢数据）
     * - 非空：决策列表（[DedupDecision.memoryIndex] 关联新记忆批次下标；空批次返回空列表）
     *
     * **JSON 防御**：解析失败/决策缺失 → 调用方按 store 兜底；单条决策 content 截断上限
     * [MAX_MEMORY_ITEM_CHARS]。
     *
     * @param memories 新提取的原子记忆批次（已去空，≤ [MEMORY_EXTRACT_MAX]）
     * @param candidatePools 每条新记忆的候选池（已存在的记忆 top5；与 memories 同序，可为空列表）
     * @param config 目标 Provider 配置
     * @return 决策列表；LLM 失败返回 null
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun dedupeMemories(
        memories: List<ExtractedMemory>,
        candidatePools: List<List<MemoryRecord>>,
        config: ProviderConfig
    ): List<DedupDecision>? {
        if (memories.isEmpty()) return emptyList()
        return try {
            val raw = completionProvider.chatCompletion(
                config = config,
                messages = listOf(ChatMessage(0, Role.USER, buildDedupInputText(memories, candidatePools), 0L)),
                systemPrompt = buildDedupPrompt()
            )
            if (raw.isNullOrBlank()) return null
            parseDedupDecisions(raw, memories.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构建去重输入的 user 消息文本（纯函数可测）：新记忆 + 候选池的结构化描述。
     */
    internal fun buildDedupInputText(
        memories: List<ExtractedMemory>,
        candidatePools: List<List<MemoryRecord>>
    ): String {
        val sb = StringBuilder()
        sb.append("新增记忆：\n")
        memories.forEachIndexed { index, memory ->
            sb.append("[$index] ${memory.content}\n")
        }
        sb.append("候选已有记忆（与新增记忆同序，可能为空）：\n")
        candidatePools.forEachIndexed { index, pool ->
            sb.append("[$index] 候选: ")
            if (pool.isEmpty()) {
                sb.append("（无）")
            } else {
                sb.append(pool.joinToString(" | ") { "id=${it.id}:${it.content.take(80)}" })
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 构建去重判定 prompt（纯函数可测）。
     *
     * **prompt 设计**（参照 TencentDB-Agent-Memory l1-dedup）：
     * - 四动作：store（新增，无候选/候选不相关）/ update（更新候选内容）/ merge（合并候选与新增）/
     *   skip（新增与候选重复且无新信息）
     * - 输出 JSON 数组（每元素 memoryIndex/action/targetId）
     */
    internal fun buildDedupPrompt(): String = DEDUP_PROMPT_TEMPLATE

    /**
     * 解析去重判定 LLM 输出（纯函数可测）。
     *
     * 剥离代码围栏后按 JSON 数组解析；任一元素非法（缺 memoryIndex / 动作不在四态）→ 丢弃该条
     * （调用方按 store 兜底）。空数组 → 返回空列表（无决策，调用方全部 store）。
     *
     * @param raw LLM 原始输出
     * @param batchSize 新记忆批次大小（用于丢弃越界 memoryIndex 的幻觉决策）
     * @return 决策列表（可为空）；JSON 整体解析失败返回 null
     */
    internal fun parseDedupDecisions(raw: String, batchSize: Int): List<DedupDecision>? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonArray = try {
            MEMORY_JSON.parseToJsonElement(cleaned) as? JsonArray
        } catch (e: Exception) {
            return null
        } ?: return null
        val decisions = ArrayList<DedupDecision>(jsonArray.size)
        for (el in jsonArray) {
            val obj = el as? JsonObject ?: continue
            val index = (obj["memoryIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: continue
            if (index !in 0 until batchSize) continue
            val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.lowercase() ?: continue
            if (action !in DEDUP_ACTIONS) continue
            val targetId = (obj["targetId"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            decisions.add(DedupDecision(index, action, targetId))
        }
        return decisions
    }

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
         * v1 US-101（ADR 记忆深度优化）：L2 跨会话记忆抽取 prompt 模板（JSON 结构化输出）。
         *
         * 参照 TencentDB-Agent-Memory Chat Memory 核心（L0→L1→L2→L3 分层蒸馏，
         * Chat Memory = preferences + facts + decisions，**不是聊天日志仓库**），
         * 单次调用完成「场景切分 + 提取」，输出 JSON 数组（content/type/priority）。
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

输出要求（**只输出 JSON，不要输出其他文字**）：
- 输出一个 JSON 数组，每个元素格式为：
  {"content": "记忆内容（第三人称，以"用户"开头，独立完整）", "type": "persona|episodic|instruction", "priority": 1-100 的整数}
- type 含义：persona=用户偏好/画像；episodic=用户经历/事实；instruction=用户长期决策/指令
- priority 表示该记忆的重要性（越大越重要，对用户越有价值）
- 只抽取对话中明确表达的信息，不臆测、不脑补
- 没有值得跨会话记住的记忆时，输出空数组 []
- 最多 5 条
        """.trimIndent()

        /** v1 US-101：记忆抽取 JSON 解析器（容忍未知字段、宽松解析）。 */
        internal val MEMORY_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * v1 US-103：去重判定 prompt 模板。
         *
         * 参照 TencentDB-Agent-Memory `l1-dedup.ts`：把「所有新记忆 + 各自候选池」塞进一次
         * LLM 调用，输出 JSON 决策数组（memoryIndex/action/targetId）。动作四态：
         * store（新增）/ update（更新候选内容）/ merge（合并）/ skip（重复且无新信息）。
         */
        internal val DEDUP_PROMPT_TEMPLATE = """
你是记忆去重助手。下面是"新增记忆"列表和每条新增记忆对应的"候选已有记忆"池。
请判断每条新增记忆应如何处理，输出 JSON 数组，每个元素：{"memoryIndex": 数字, "action": "store|update|merge|skip", "targetId": 数字或null}

动作含义：
- store：新增记忆无相关候选（或候选不相关），应作为新记忆保存
- update：新增记忆与候选 id=targetId 重复但内容更完整/更新，应更新该候选（替换其内容）
- merge：新增记忆与候选 id=targetId 语义相关但各有信息，应合并（内容拼接）
- skip：新增记忆与候选 id=targetId 完全重复且无新信息，跳过不保存

约束：
- 只输出 JSON，不要输出其他文字
- 每条新增记忆都要给出决策（memoryIndex 从 0 开始）
- 无相关候选时用 store（targetId 为 null）
- 不臆测：候选池为"（无）"时只能 store
        """.trimIndent()

        /** 去重四态动作（白名单，解析时校验，防 LLM 幻觉动作）。 */
        internal val DEDUP_ACTIONS = setOf("store", "update", "merge", "skip")

        /** UXR11 U5：单次抽取记忆条数上限（防 token 溢出 + 控制记忆库膨胀）。 */
        internal const val MEMORY_EXTRACT_MAX = 5

        /** guardrail M-1（第二轮复审）：单条原子记忆字符上限（防病态/幻觉超长行无界入库）。 */
        internal const val MAX_MEMORY_ITEM_CHARS = 200

        /** guardrail F6：完整序号前缀（`1.` / `1、` / `1）` / `1:` 等），仅剥此类格式不剥裸数字。 */
        internal val NUMBERED_LIST_PREFIX_REGEX = Regex("""^\d+[.、:：)）]\s*""")
    }
}

/**
 * 记忆去重决策（v1 US-103，参照 TencentDB-Agent-Memory l1-dedup 四态）。
 *
 * @property memoryIndex 关联新记忆批次的下标（0-based，对应 [ConversationSummarizer.dedupeMemories]
 *   的 [ExtractedMemory] 列表下标）
 * @property action 决策动作：store / update / merge / skip（见 [ConversationSummarizer.DEDUP_ACTIONS]）
 * @property targetId 目标候选记忆 id（update/merge/skip 时需指向候选；store 为 null）
 */
data class DedupDecision(
    val memoryIndex: Int,
    val action: String,
    val targetId: Long?
)
