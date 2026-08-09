package io.prism.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.McpServerRepository
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.embedding.Embedder
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolDefinition
import io.prism.rag.RagContextBuilder
import io.prism.rag.RagTarget
import io.prism.skill.SkillExecutor
import io.prism.skill.SkillRegistry
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 聊天界面 ViewModel —— 管理消息列表、打字状态、流式回复、RAG 检索与 Skill 工具调用回路
 * （US-019 RAG + US-026 Skill 工具执行回路）。
 *
 * 响应式状态：用 [MutableStateFlow] 暴露 [messages] 与 [isTyping]，
 * Compose 通过 `collectAsState()` 订阅渲染（ADR-002 4.2）。
 *
 * **US-006**：构造注入 [ProviderConfigRepository] + [ChatStreamProvider]，
 * [sendMessage] 由 Mock 替换为真实 SSE 流式请求（ADR-004 4.5）。
 *
 * **US-019 RAG 集成**（ADR-012）：
 * - 构造额外注入 [Embedder] + [KnowledgeBaseRepository]
 * - [sendMessage] 在 [RagTarget] 非 [Off][RagTarget.Off] 时执行：embed(query) → search → 阈值过滤 →
 *   RagContextBuilder 拼 system prompt + context → provider.streamChat(..., systemPrompt, ragContext)
 * - 引用来源在检索阶段就附在 AI 占位消息上（用户更早看到引用，无需等 Done）
 * - 失败降级三级（ADR-012 5.5）：embed 失败 / search 失败或空 / 整个 RAG 异常 → 退化为普通对话
 * - [_messages] 改用 [update] 原子 CAS（修复 R-5，BR-concurrency-004）
 *
 * **US-026 Skill 工具执行回路集成**（ADR-014 5.4，M4 Phase D）：
 * - 构造注入 [SkillRegistry] + [SkillExecutor] + [McpServerRepository]（均可空，便于无 Skill 场景降级）
 * - [sendMessage] 构建 tools（[Companion.buildTools]）+ 合并 systemPrompt（[Companion.mergeSystemPrompt]）
 * - **分支策略**：tools 非空且 [skillExecutor] 非空 → [SkillExecutor.executeLoop] + onEvent 回调
 *   （R-1：executeLoop 内部已 collect flow，外层不再 collect）；否则走普通 streamChat 分支
 * - **idGenerator 注入**（R-3）：[nextId] 改为 [AtomicLong]，注入 `getAndIncrement()` 给 executeLoop
 *   保证跨线程可见性与 id 体系一致
 * - **历史过滤器扩展**（R-4）：保留携带 toolCalls 的空 content assistant 占位消息，
 *   避免下次请求丢失 tool_calls 上下文导致 OpenAI 400
 * - **消息同步**（R-2）：[syncToolMessages] 把 executeLoop 返回的新增消息（assistant 占位 + tool result）
 *   追加到 [_messages]，aiId 保留为最终文本回复（Delta 累积），不与协议层占位合并
 *
 * [activeProvider] 由仓库 Flow 暴露，替代 ConversationScreen 内 cast 反模式。
 *
 * @param providerRepository Provider 配置仓库
 * @param provider 流式对话 Provider
 * @param embedder 端侧嵌入引擎（BR-concurrency-002 全程持锁串行）
 * @param knowledgeBaseRepository 知识库仓库（search 同步阻塞）
 * @param skillRegistry Skill 注册中心（M4 Phase D，可空：null 时降级为无 tools 普通对话）
 * @param skillExecutor Skill 工具执行器（M4 Phase D，可空：null 时降级为普通对话）
 * @param mcpServerRepository MCP Server 配置仓库（M4 Phase D，可空：null 时降级为普通对话）
 * @param ioDispatcher IO 调度器，用于 RAG embed+search 阻塞调用（BR-concurrency-002）。测试中注入 test dispatcher。
 */
class ConversationViewModel(
    private val providerRepository: ProviderConfigRepository,
    private val provider: ChatStreamProvider,
    private val embedder: Embedder,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
    /** M4 Phase D：Skill 注册中心，null 时降级为无 tools 普通对话（向后兼容既有测试） */
    private val skillRegistry: SkillRegistry? = null,
    /** M4 Phase D：Skill 工具执行器，null 时降级为普通对话（向后兼容既有测试） */
    private val skillExecutor: SkillExecutor? = null,
    /** M4 Phase D：MCP Server 配置仓库，null 时降级为普通对话（向后兼容既有测试） */
    private val mcpServerRepository: McpServerRepository? = null,
    /** IO 调度器，用于 RAG embed+search 阻塞调用（BR-concurrency-002）。测试中注入 test dispatcher。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    /**
     * 消息 id 生成器（M4 Phase D R-3 修复：AtomicLong 保证跨线程可见性）。
     *
     * 原为 `private var nextId = 0L`，[SkillExecutor.executeLoop] 在
     * `withContext(ioDispatcher)` 中跨线程调用 idGenerator，普通 var 写入
     * 对其他线程不可见（无 happens-before）。改为 [AtomicLong] 后，
     * `getAndIncrement()` 提供原子读改写 + happens-before 保证。
     */
    private val nextId = AtomicLong(0L)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** 消息列表（只读 StateFlow） */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    /** AI 是否正在回复（打字指示） */
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    /**
     * RAG 检索目标模式（US-019，ADR-012 5.2）。
     *
     * 默认 [RagTarget.AllLibraries]（全库检索 + 默认开启）。
     * 用户可在对话页通过 [setRagTarget] 切换三态：全库 / 指定库 / 关闭。
     */
    private val _ragTarget = MutableStateFlow<RagTarget>(RagTarget.AllLibraries)
    val ragTarget: StateFlow<RagTarget> = _ragTarget.asStateFlow()

    /** 当前激活 Provider（订阅仓库，供顶栏副标题展示）。 */
    val activeProvider: StateFlow<ProviderConfig?> = providerRepository.activeProviderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.activeProviderFlow.value)

    /** 全部已配置 Provider（订阅仓库，供切换选择器展示）。 */
    val providers: StateFlow<List<ProviderConfig>> = providerRepository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.providers.value)

    /** 激活指定 Provider（经仓库单激活事务，US-007）。 */
    fun setActiveProvider(id: Long) {
        providerRepository.setActive(id)
    }

    /** 切换 RAG 检索目标模式（US-019）。 */
    fun setRagTarget(target: RagTarget) {
        _ragTarget.value = target
    }

    /**
     * 发送一条消息（US-019 RAG + US-026 Skill 工具执行回路）。
     *
     * **流程**：
     * 1. trim 输入 → 追加用户消息（[nextId] 原子自增）→ 追加空 AI 占位消息 → isTyping=true
     * 2. 取激活 Provider；无则追加错误提示
     * 3. **RAG 注入**（若 [ragTarget] 非 [Off][RagTarget.Off]）：
     *    - IO 协程执行 embed(query) → search → 阈值过滤
     *    - 拼 system prompt + ragContext
     *    - 引用来源 [io.prism.ui.model.Citation] 列表附在 AI 占位消息上
     *    - 失败按 [RagBuildResult] 三态降级（ADR-012 5.5），不阻断对话
     * 4. **M4 Phase D：构建 tools + 合并 systemPrompt**（[Companion.buildTools] + [Companion.mergeSystemPrompt]）
     * 5. **历史过滤器扩展**（R-4）：排除 aiId + 空content且空toolCalls 的 assistant 占位
     * 6. **分支策略**（R-1）：
     *    - tools 非空且 [skillExecutor] 非空 → [SkillExecutor.executeLoop] + onEvent 回调
     *    - 否则 → 普通流式 streamChat + collect
     * 7. **消息同步**（R-2）：executeLoop 返回后 [syncToolMessages] 把新增消息追加到 [_messages]
     * 8. Done / Error 后 isTyping=false
     *
     * **状态原子性**：所有 [_messages] 写入均通过 [update] CAS（BR-concurrency-004），
     * 避免 RAG 检索协程与 stream collect 协程并发写导致 lost update（修复 R-5）。
     *
     * **降级提示策略**（ADR-012 5.5 + G-02/G-03 修复）：
     * - [RagBuildResult.Success] → 附 citations，注入 systemPrompt + ragContext
     * - [RagBuildResult.EmbedFailed] → appendDelta 简短提示（项目暂无 Toast 基建，ADR-012 5.5 备注）
     * - [RagBuildResult.NormalChat] → 主动关闭 / search 空 / 阈值过滤空 / 整个 RAG 异常，用户无感
     *
     * **M4 Phase D 已知限制**（R-5，ADR-014 5.7 偏差）：
     * - Provider 不支持 tools 字段返回 400 时无法精确降级（StreamEvent.Error 不携带状态码）
     * - 标记为已知限制，Phase E 视需要扩展 StreamEvent.Error 携带 statusCode
     *
     * @param text 用户输入文本
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val now = System.currentTimeMillis()
        _messages.update { it + ChatMessage(nextId.getAndIncrement(), Role.USER, trimmed, now) }

        viewModelScope.launch {
            _isTyping.value = true
            val aiId = nextId.getAndIncrement()
            _messages.update { it + ChatMessage(aiId, Role.ASSISTANT, "", now) }

            val active = providerRepository.activeProviderFlow.value
            if (active == null) {
                appendDelta(aiId, "\n\n⚠️ 尚未配置激活的 Provider，请在「设置」中添加并激活")
                _isTyping.value = false
                return@launch
            }

            // RAG 注入（IO 协程，BR-concurrency-002 全程持锁，禁止 Main）
            // G-01 修复：外层 runCatching 重抛 CancellationException（BR-error-handling-007 提议）
            val ragResult = runCatching { buildRagPlan(trimmed) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    // G-03 修复：整个 RAG 注入异常 → 仅日志记录 simpleName，用户无感（ADR-012 5.5）
                    // 项目暂无结构化日志基建，用 android.util.Log.w 记录（不含密钥/请求体/路径）
                    Log.w(TAG, "RAG injection failed: ${e::class.simpleName}, degrading to normal chat")
                    RagBuildResult.NormalChat
                }

            // G-02 修复：按 RagBuildResult 三态差异化处理用户感知
            val ragPlan: RagPlan? = when (ragResult) {
                is RagBuildResult.Success -> {
                    // 引用来源在检索阶段就附在 AI 占位消息上（用户更早看到引用，无需等 Done，ADR-012 5.3）
                    if (ragResult.plan.citations.isNotEmpty()) {
                        _messages.update { msgs ->
                            msgs.map { if (it.id == aiId) it.copy(sources = ragResult.plan.citations) else it }
                        }
                    }
                    ragResult.plan
                }
                RagBuildResult.EmbedFailed -> {
                    // embed 失败 → 提示用户（ADR-012 5.5：项目暂无 Toast，用 appendDelta 简短提示）
                    // 末尾空行分隔后续 AI 流式回复，避免「对话」「回复」直接拼接
                    appendDelta(aiId, "⚠️ 知识库检索失败，已降级为普通对话\n\n")
                    null
                }
                RagBuildResult.NormalChat -> null  // 主动关闭 / search 空 / 阈值过滤空 / RAG 异常，无提示
            }

            // M4 Phase D：构建 tools + 合并 systemPrompt
            val enabledSkills = skillRegistry?.enabledSkills() ?: emptyList()
            val tools = Companion.buildTools(enabledSkills)
            val mergedSystemPrompt = Companion.mergeSystemPrompt(ragPlan?.systemPrompt, enabledSkills)
            val mcpServers = mcpServerRepository?.servers?.value ?: emptyList()

            // 请求历史构建：
            // 1. 排除当前 AI 占位消息（aiId）—— 它是本轮待生成目标，不应进 history。
            //    G-02 修复配套：embed 失败 appendDelta 后占位消息非空，原「按空 content 过滤」
            //    会漏过此消息，导致 provider 把降级提示当作上一轮 AI 回复（语义错误）。
            // 2. 排除所有空 content 的 AI 消息——既排除上一轮因服务端零增量（仅 [DONE]）
            //    结束而残留的空消息，避免空 content 消息被严格 API 拒绝
            //    （CR-02，guardrail 发现 1，BR-interface-003）
            // 3. M4 Phase D R-4 修复：保留携带 toolCalls 的空 content assistant 占位消息，
            //    否则下次请求丢失 tool_calls 上下文，OpenAI 返回 400
            val history = _messages.value.filterNot {
                it.id == aiId ||
                    (it.role == Role.ASSISTANT && it.content.isEmpty() && it.toolCalls.isEmpty())
            }

            // 分支策略（R-1）：tools 非空且 skillExecutor 非空 → executeLoop + onEvent 回调；
            // 否则 → 普通流式 streamChat + collect（保持无 Skill 场景零开销）
            if (tools.isNotEmpty() && skillExecutor != null) {
                executeWithToolLoop(
                    aiId = aiId,
                    active = active,
                    history = history,
                    mergedSystemPrompt = mergedSystemPrompt,
                    ragContext = ragPlan?.ragContext,
                    tools = tools,
                    mcpServers = mcpServers
                )
            } else {
                executePlainStream(
                    aiId = aiId,
                    active = active,
                    history = history,
                    systemPrompt = mergedSystemPrompt,
                    ragContext = ragPlan?.ragContext
                )
            }
        }
    }

    /**
     * M4 Phase D：执行 Skill 工具调用回路（[SkillExecutor.executeLoop] + onEvent 回调）。
     *
     * **R-1 修复**：[SkillExecutor.executeLoop] 内部已 `flow.collect { onEvent(event); ... }`，
     * Flow 是冷流，外层不能再 collect（否则发起第二次 HTTP 请求）。本方法把
     * [handleStreamEvent] 作为 onEvent 回调传入 executeLoop，由其内部统一消费 flow。
     *
     * **R-2 修复**：executeLoop 返回的 updatedMessages（含 assistant 占位 toolCalls + tool result）
     * 与 [_messages] 中的 aiId 是两套序列。aiId 是 UI 展示的 AI 文本回复（Delta 累积），
     * assistant 占位是协议层 toolCalls 回放消息（content=""）。本方法调用 [syncToolMessages]
     * 把新增消息追加到 [_messages]，aiId 保留为最终文本回复，不与协议层占位合并。
     *
     * **R-3 修复**：注入 `idGenerator = { nextId.getAndIncrement() }` 给 executeLoop，
     * 保证 id 体系一致（与 user/aiId 同一 AtomicLong 序列）与跨线程可见性。
     *
     * **异常处理**（BR-error-handling-007）：CancellationException 重抛，其他异常降级为
     * appendDelta 提示 + 日志记录（BR-error-handling-004），不崩溃。
     *
     * **isTyping 置位**：executeLoop 内部 onEvent(Done) 或 onEvent(Error) 会通过
     * [handleStreamEvent] 置 isTyping=false；本方法 finally 兜底保证异常路径也置 false。
     */
    private suspend fun executeWithToolLoop(
        aiId: Long,
        active: ProviderConfig,
        history: List<ChatMessage>,
        mergedSystemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>,
        mcpServers: List<io.prism.data.McpServerConfig>
    ) {
        try {
            val updatedMessages = skillExecutor!!.executeLoop(
                provider = provider,
                config = active,
                messages = history,
                systemPrompt = mergedSystemPrompt,
                ragContext = ragContext,
                tools = tools,
                mcpServers = mcpServers,
                maxRounds = Companion.DEFAULT_MAX_ROUNDS,
                idGenerator = { nextId.getAndIncrement() },
                onEvent = { event -> handleStreamEvent(aiId, event) }
            )
            // R-2：同步 executeLoop 返回的新增消息（assistant 占位 + tool result）到 _messages
            syncToolMessages(updatedMessages, history.size)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // M4 Phase D：结构化日志（BR-error-handling-004），便于定位 executeLoop 异常根因
            Log.w(TAG, "executeLoop failed: ${e::class.simpleName}", e)
            appendDelta(aiId, "\n\n⚠️ 工具执行回路异常: ${e::class.simpleName}")
        } finally {
            _isTyping.value = false
        }
    }

    /**
     * 执行普通流式对话（无 tools 分支，保持原 sendMessage 逻辑）。
     *
     * 无 Skill 或 Skill 未声明 tools 时走此分支，避免 executeLoop 多一层
     * `withContext(ioDispatcher)` 开销 + onEvent 回调间接调用 + 即使无 ToolCallComplete
     * 也跑一轮才 break 的无谓回路（D-2 设计决策，考古报告 2.1 节）。
     */
    private suspend fun executePlainStream(
        aiId: Long,
        active: ProviderConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?
    ) {
        val stream = provider.streamChat(
            config = active,
            messages = history,
            systemPrompt = systemPrompt,
            ragContext = ragContext
        )
        stream.collect { event -> handleStreamEvent(aiId, event) }
    }

    /**
     * 处理 [StreamEvent] 通用回调（M4 Phase D 抽取，覆盖 6 子类穷尽匹配）。
     *
     * 同时被 [executeWithToolLoop]（作为 executeLoop 的 onEvent 回调）与
     * [executePlainStream]（作为 collect 内 when 分发）使用，保证两分支事件处理一致。
     *
     * **事件处理策略**：
     * - [StreamEvent.Delta] → [appendDelta] 累积到 aiId 消息
     * - [StreamEvent.Done] → isTyping=false
     * - [StreamEvent.Error] → appendDelta 错误提示 + isTyping=false
     * - [StreamEvent.ToolCallStart] → appendDelta 工具调用指示（UI 即时反馈，
     *   Phase E US-027 可改为独立消息气泡或卡片）
     * - [StreamEvent.ToolCallDelta] → no-op（参数增量片段，UI 实时展示参数构建为可选优化）
     * - [StreamEvent.ToolCallComplete] → no-op（executeLoop 内部已处理执行，
     *   onEvent 仅做透传，不在此回调中执行工具）
     *
     * **BR-error-handling-007**：本回调内禁止 runCatching（会吞 CancellationException）；
     * 当前实现无异常抛出路径，无需显式 try-catch。
     *
     * **BR-naming-001**：when 对 [StreamEvent] sealed class 穷尽匹配，新增子类时编译器报错。
     */
    private fun handleStreamEvent(aiId: Long, event: StreamEvent) {
        when (event) {
            is StreamEvent.Delta -> appendDelta(aiId, event.content)
            StreamEvent.Done -> _isTyping.value = false
            is StreamEvent.Error -> {
                // M-1 修复（guardrail TKN-M4-PHASED-GUARDRAIL-001）：UI 边界防御性脱敏
                // 第二层防御，覆盖未来 Provider 可能透传原始异常 message 的风险（CWE-209）
                val safeMsg = Companion.sanitizeUiErrorMessage(event.message)
                appendDelta(aiId, "\n\n⚠️ $safeMsg")
                _isTyping.value = false
            }
            is StreamEvent.ToolCallStart -> {
                // UI 即时反馈：工具调用开始（Phase E US-027 可升级为独立气泡/卡片）
                appendDelta(aiId, "\n🔧 ${event.toolName}\n")
            }
            is StreamEvent.ToolCallDelta -> Unit  // no-op：参数增量片段，UI 实时展示为可选优化
            is StreamEvent.ToolCallComplete -> Unit  // no-op：executeLoop 内部已处理执行
        }
    }

    /**
     * 同步 executeLoop 返回的新增消息到 [_messages]（M4 Phase D R-2 修复）。
     *
     * executeLoop 返回的 `updatedMessages` 含完整消息序列（history + assistant 占位 + tool result），
     * 需 drop 前-history.size 条得到新增消息，追加到 [_messages]。
     *
     * **aiId 保留**：aiId 是 UI 展示的 AI 文本回复（Delta 累积），不与协议层占位消息合并。
     * 新增的 assistant 占位（content="", toolCalls 非空）与 tool result（role=TOOL）作为
     * 独立消息追加到 [_messages]，UI 渲染时按 id 顺序展示。
     *
     * **原子性**：通过 [_messages.update] CAS 写入（BR-concurrency-004）。
     *
     * @param updatedMessages executeLoop 返回的完整消息序列
     * @param originalHistorySize 调用 executeLoop 前的 history 大小（用于 drop 计算）
     */
    private fun syncToolMessages(updatedMessages: List<ChatMessage>, originalHistorySize: Int) {
        val newMsgs = updatedMessages.drop(originalHistorySize)
        if (newMsgs.isNotEmpty()) {
            _messages.update { it + newMsgs }
        }
    }

    /**
     * 构建 RAG 注入结果（system prompt + ragContext + citations 或降级标记）。
     *
     * **必须**在 IO 协程调用（[Embedder.embed] 串行持锁 ~100ms + [KnowledgeBaseRepository.search]
     * 同步阻塞，BR-concurrency-002）。
     *
     * **降级策略**（ADR-012 5.5 + G-01/G-02 修复）：
     * - [RagTarget.Off] → [RagBuildResult.NormalChat]（普通对话，无提示）
     * - embed 失败 → [RagBuildResult.EmbedFailed]（普通对话 + appendDelta 提示）
     * - search 失败或结果为空 → [RagBuildResult.NormalChat]（普通对话，无提示，AI 自然回答）
     * - search 有结果但阈值过滤后为空 → [RagBuildResult.NormalChat]（普通对话，无提示）
     * - 成功 → [RagBuildResult.Success]
     *
     * **G-01 修复**（guardrail TKN-US019-RAG-GUARDRAIL-001，BR-error-handling-007 提议）：
     * 内层禁用 `runCatching { }.getOrElse { }`（会吞 CancellationException 破坏结构化并发），
     * 改用显式 try-catch，先 `catch (e: CancellationException) { throw e }` 再 `catch (e: Exception)`。
     *
     * @param queryText 用户查询文本
     * @return RAG 构建结果（成功或降级标记），调用方按 [RagBuildResult] 三态决定用户感知
     */
    private suspend fun buildRagPlan(queryText: String): RagBuildResult {
        val target = _ragTarget.value
        if (target is RagTarget.Off) return RagBuildResult.NormalChat

        // IO 协程执行 embed + search（全程阻塞，禁止 Main）
        return withContext(ioDispatcher) {
            // 1. embed(query) —— BR-concurrency-002 全程持锁 ~100ms
            // G-01 修复：显式 try-catch，重抛 CancellationException（BR-error-handling-007）
            val queryVector = try {
                embedder.embed(queryText)
            } catch (e: CancellationException) {
                throw e  // 协程取消必须传播，禁止吞掉
            } catch (e: Exception) {
                return@withContext RagBuildResult.EmbedFailed  // embed 失败 → 提示降级
            }

            // 2. search —— 同步阻塞，kbId 由 RagTarget 决定
            // G-04 修复：RagTarget.SpecificLibrary init 已校验 kbId > 0，此处无需重复校验
            val kbId = when (target) {
                is RagTarget.AllLibraries -> null
                is RagTarget.SpecificLibrary -> target.kbId
                RagTarget.Off -> return@withContext RagBuildResult.NormalChat  // 理论不可达，防御
            }
            val results = try {
                knowledgeBaseRepository.search(queryVector, k = RAG_TOP_K, knowledgeBaseId = kbId)
            } catch (e: CancellationException) {
                throw e  // G-01 修复：协程取消必须传播
            } catch (e: Exception) {
                return@withContext RagBuildResult.NormalChat  // search 失败 → 自然降级，无提示
            }

            // 3. 相似度阈值过滤（R-6，调用方责任，ADR-012 5.6）
            val filtered = results.filter { it.similarity >= RAG_SIMILARITY_THRESHOLD }
            if (filtered.isEmpty()) return@withContext RagBuildResult.NormalChat  // 无相关结果 → 自然降级

            // 4. 拼接 system prompt + ragContext + citations
            RagBuildResult.Success(
                RagPlan(
                    systemPrompt = RagContextBuilder.SYSTEM_PROMPT,
                    ragContext = RagContextBuilder.buildContext(filtered),
                    citations = RagContextBuilder.buildCitations(filtered)
                )
            )
        }
    }

    /** 将增量 token 追加到指定 AI 消息末尾（原子 CAS，BR-concurrency-004）。 */
    private fun appendDelta(aiId: Long, delta: String) {
        _messages.update { msgs ->
            msgs.map { if (it.id == aiId) it.copy(content = it.content + delta) else it }
        }
    }

    companion object {
        /** Logcat 标签（G-03 修复：结构化日志基建未就绪前用 android.util.Log） */
        private const val TAG = "ConversationViewModel"

        /** RAG top-k（ADR-012 5.6，4GB 低端机约束） */
        private const val RAG_TOP_K = 3

        /** RAG 相似度阈值（ADR-012 5.6，过滤无关结果污染 context） */
        private const val RAG_SIMILARITY_THRESHOLD = 0.3

        /**
         * M4 命名空间分隔符（与 [SkillExecutor.NAMESPACE_SEPARATOR] 对齐）。
         *
         * Skill 声明的工具名带 skill 命名空间前缀以避免跨 Skill 同名冲突，
         * 格式 `skillName__toolName`。
         */
        internal const val NAMESPACE_SEPARATOR = "__"

        /**
         * M4 工具执行回路默认最大轮数（与 [SkillExecutor.DEFAULT_MAX_ROUNDS] 对齐）。
         *
         * 防止 LLM 反复调用工具导致无限循环（ADR-014 5.5）。
         */
        internal const val DEFAULT_MAX_ROUNDS = 10

        /**
         * UI 可见错误信息截断长度上限（M-1 修复，CWE-209 信息泄露纵深防御）。
         *
         * 与 [SkillExecutor.MAX_ERROR_MESSAGE_LEN] 对齐，确保任何路径透传到 UI 的错误信息
         * 都不会过长污染对话历史。
         */
        internal const val MAX_UI_ERROR_LEN = 200

        /**
         * 文件路径正则（M-1 修复，CWE-209）：匹配以 `/` 或 `\` 开头的路径片段，
         * 替换为 `<path>` 占位符，避免内部路径泄露给用户。
         */
        private val uiPathPattern = Regex("""[/\\][^\s"'<>]+""")

        /**
         * 对 UI 可见的错误信息做防御性脱敏（M-1 修复，guardrail TKN-M4-PHASED-GUARDRAIL-001）。
         *
         * **第二层防御**：即使上游 Provider/Executor 已脱敏，UI 边界仍做兜底，
         * 覆盖未来新增 Provider 可能透传原始异常 message 的风险（BR-error-handling-003 纵深防御）。
         *
         * 1. **长度截断**：仅保留前 [MAX_UI_ERROR_LEN] 字符
         * 2. **路径脱敏**：将 `/xxx/yyy` 或 `\xxx\yyy` 替换为 `<path>`
         *
         * @param raw 原始 message（可能为 null 或空）
         * @return 脱敏后的 message；raw 为 null/空时返回通用安全文案
         */
        internal fun sanitizeUiErrorMessage(raw: String?): String {
            if (raw.isNullOrBlank()) return "未知错误"
            val truncated = if (raw.length > MAX_UI_ERROR_LEN) {
                raw.take(MAX_UI_ERROR_LEN) + "..."
            } else {
                raw
            }
            return uiPathPattern.replace(truncated, "<path>")
        }

        /**
         * 从已启用的 Skill 列表构建 [ToolDefinition]（M4 Phase D，命名空间隔离）。
         *
         * **命名空间隔离**（ADR-014 5.5）：tool name 格式 `skillName__toolName`，
         * 避免跨 Skill 同名工具冲突；执行时由 [SkillExecutor.stripNamespace] 剥离前缀。
         *
         * **纯函数**（US-026 可测性，BR-testing-004 模式）：不依赖实例状态，
         * 可在纯 JVM 测试中直接验证，无需 Android Context 或真实 SkillRegistry。
         *
         * @param enabledSkills 已启用的 Skill 列表（来自 [SkillRegistry.enabledSkills]）
         * @return 工具定义列表；无 tools 声明的 Skill 贡献 0 项
         */
        internal fun buildTools(enabledSkills: List<SkillRegistry.SkillEntry>): List<ToolDefinition> {
            return enabledSkills.flatMap { entry ->
                (entry.manifest.tools ?: emptyList()).map { toolDecl ->
                    ToolDefinition(
                        function = ToolDefinition.FunctionDef(
                            name = "${entry.config.name}${NAMESPACE_SEPARATOR}${toolDecl.name}",
                            description = toolDecl.description,
                            parameters = toolDecl.parameters
                        )
                    )
                }
            }
        }

        /**
         * 合并 RAG system prompt + Skill system prompts（M4 Phase D，R-6 膨胀控制）。
         *
         * **合并顺序**（考古报告 2.4 节，R-6 缓解）：
         * 1. RAG grounding rules（最基础，防幻觉约束）
         * 2. Skill systemPrompt（具体指令）
         * 3. Skill 索引描述（可用技能列表，便于 LLM 决策调用）
         *
         * **膨胀控制**：仅合并已启用 Skill 的 systemPrompt，避免未启用 Skill 污染。
         * 当前不做硬性长度截断（依赖 Skill 作者自律），Phase E 视需要加 token 预算控制。
         *
         * **纯函数**（US-026 可测性，BR-testing-004 模式）。
         *
         * @param ragPrompt RAG grounding rules（可能为 null：RAG 关闭或降级）
         * @param enabledSkills 已启用的 Skill 列表
         * @return 合并后的 systemPrompt；若 ragPrompt 为 null/空且所有 Skill 均无 systemPrompt
         *         且无 tools 声明，则返回 null（向后兼容无 Skill 场景）
         */
        internal fun mergeSystemPrompt(
            ragPrompt: String?,
            enabledSkills: List<SkillRegistry.SkillEntry>
        ): String? {
            val hasRag = !ragPrompt.isNullOrBlank()
            val skillPrompts = enabledSkills
                .mapNotNull { it.manifest.systemPrompt }
                .filter { it.isNotBlank() }
            // 仅对声明了 tools 的 Skill 输出索引（无 tools 的 Skill 不参与工具调用决策）
            val toolSkills = enabledSkills.filter { !it.manifest.tools.isNullOrEmpty() }

            if (!hasRag && skillPrompts.isEmpty() && toolSkills.isEmpty()) return null

            return buildString {
                if (hasRag) {
                    append(ragPrompt)
                    append("\n\n")
                }
                skillPrompts.forEach { prompt ->
                    append(prompt)
                    append("\n\n")
                }
                if (toolSkills.isNotEmpty()) {
                    append("可用技能: ")
                    append(toolSkills.joinToString(", ") { it.config.name })
                    append("\n\n")
                }
            }.trimEnd().ifEmpty { null }
        }

        /** 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                ConversationViewModel(
                    providerRepository = app.providerConfigRepository,
                    provider = app.openAICompatibleProvider,
                    embedder = app.embedder,
                    knowledgeBaseRepository = app.knowledgeBaseRepository,
                    skillRegistry = app.skillRegistry,
                    skillExecutor = app.skillExecutor,
                    mcpServerRepository = app.mcpServerRepository
                )
            }
        }
    }
}

/**
 * RAG 构建结果（G-01/G-02 修复，ADR-012 5.5 三级降级差异化感知）。
 *
 * 调用方 [ConversationViewModel.sendMessage] 按 [when] 分支决定是否提示用户：
 * - [Success] → 注入 systemPrompt + ragContext + citations
 * - [EmbedFailed] → appendDelta 简短提示（ADR-012 5.5：项目暂无 Toast 基建）
 * - [NormalChat] → 主动关闭 / search 空 / 阈值过滤空 / 整个 RAG 异常，用户无感
 */
private sealed interface RagBuildResult {
    /** RAG 检索成功，注入 [plan]。 */
    data class Success(val plan: RagPlan) : RagBuildResult

    /** embed 失败，降级为普通对话，调用方应 appendDelta 提示用户。 */
    object EmbedFailed : RagBuildResult

    /**
     * 普通对话（无提示）：RAG 主动关闭 / search 失败或空 / 阈值过滤后空 / 整个 RAG 注入异常。
     * 整个 RAG 异常时，调用方已通过 [Log.w] 记录 simpleName（G-03 修复），用户侧无感。
     */
    object NormalChat : RagBuildResult
}

/** RAG 注入计划（system prompt + context + citations）。 */
private data class RagPlan(
    val systemPrompt: String,
    val ragContext: String,
    val citations: List<io.prism.ui.model.Citation>
)
