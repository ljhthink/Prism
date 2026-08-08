package io.prism.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.embedding.Embedder
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.rag.RagContextBuilder
import io.prism.rag.RagTarget
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

/**
 * 聊天界面 ViewModel —— 管理消息列表、打字状态、流式回复与 RAG 检索（US-019）。
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
 * [activeProvider] 由仓库 Flow 暴露，替代 ConversationScreen 内 cast 反模式。
 *
 * @param providerRepository Provider 配置仓库
 * @param provider 流式对话 Provider
 * @param embedder 端侧嵌入引擎（BR-concurrency-002 全程持锁串行）
 * @param knowledgeBaseRepository 知识库仓库（search 同步阻塞）
 */
class ConversationViewModel(
    private val providerRepository: ProviderConfigRepository,
    private val provider: ChatStreamProvider,
    private val embedder: Embedder,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
    /** IO 调度器，用于 RAG embed+search 阻塞调用（BR-concurrency-002）。测试中注入 test dispatcher。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private var nextId = 0L

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
     * 发送一条消息（US-019 集成 RAG 检索）。
     *
     * 流程：
     * 1. trim 输入 → 追加用户消息 → 追加空 AI 占位消息 → isTyping=true
     * 2. 取激活 Provider；无则追加错误提示
     * 3. **RAG 注入**（若 [ragTarget] 非 [Off][RagTarget.Off]）：
     *    - IO 协程执行 embed(query) → search → 阈值过滤
     *    - 拼 system prompt + ragContext
     *    - 引用来源 [io.prism.ui.model.Citation] 列表附在 AI 占位消息上
     *    - 失败按 [RagBuildResult] 三态降级（ADR-012 5.5），不阻断对话
     * 4. 订阅流式请求，Delta 增量追加到 AI 消息
     * 5. Done / Error 后 isTyping=false
     *
     * **状态原子性**：所有 [_messages] 写入均通过 [update] CAS（BR-concurrency-004），
     * 避免 RAG 检索协程与 stream collect 协程并发写导致 lost update（修复 R-5）。
     *
     * **降级提示策略**（ADR-012 5.5 + G-02/G-03 修复）：
     * - [RagBuildResult.Success] → 附 citations，注入 systemPrompt + ragContext
     * - [RagBuildResult.EmbedFailed] → appendDelta 简短提示（项目暂无 Toast 基建，ADR-012 5.5 备注）
     * - [RagBuildResult.NormalChat] → 主动关闭 / search 空 / 阈值过滤空 / 整个 RAG 异常，用户无感
     *
     * @param text 用户输入文本
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val now = System.currentTimeMillis()
        _messages.update { it + ChatMessage(nextId++, Role.USER, trimmed, now) }

        viewModelScope.launch {
            _isTyping.value = true
            val aiId = nextId++
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

            // 请求历史构建：
            // 1. 排除当前 AI 占位消息（aiId）—— 它是本轮待生成目标，不应进 history。
            //    G-02 修复配套：embed 失败 appendDelta 后占位消息非空，原「按空 content 过滤」
            //    会漏过此消息，导致 provider 把降级提示当作上一轮 AI 回复（语义错误）。
            // 2. 排除所有空 content 的 AI 消息——既排除上一轮因服务端零增量（仅 [DONE]）
            //    结束而残留的空消息，避免空 content 消息被严格 API 拒绝
            //    （CR-02，guardrail 发现 1，BR-interface-003）
            val history = _messages.value.filterNot { it.id == aiId || (it.role == Role.ASSISTANT && it.content.isEmpty()) }

            val stream = provider.streamChat(
                config = active,
                messages = history,
                systemPrompt = ragPlan?.systemPrompt,
                ragContext = ragPlan?.ragContext
            )

            stream.collect { event ->
                when (event) {
                    is StreamEvent.Delta -> appendDelta(aiId, event.content)
                    StreamEvent.Done -> _isTyping.value = false
                    is StreamEvent.Error -> {
                        appendDelta(aiId, "\n\n⚠️ ${event.message}")
                        _isTyping.value = false
                    }
                    // M4 tool_calling 事件（ADR-014 5.1）：Phase A 不注入 tools，这三分支不会触发。
                    // Phase D（US-026）将实现工具执行回路：ToolCallComplete → 用户确认 → 执行 → 结果回灌。
                    is StreamEvent.ToolCallStart,
                    is StreamEvent.ToolCallDelta,
                    is StreamEvent.ToolCallComplete -> Unit  // no-op，Phase D 接管
                }
            }
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

        /** 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                ConversationViewModel(
                    providerRepository = app.providerConfigRepository,
                    provider = app.openAICompatibleProvider,
                    embedder = app.embedder,
                    knowledgeBaseRepository = app.knowledgeBaseRepository
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
