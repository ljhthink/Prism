package io.prism.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.crossapp.AppLauncherBridge
import io.prism.crossapp.CrossAppLauncher
import io.prism.crossapp.CrossAppLocalToolExecutor
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.McpServerRepository
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.embedding.Embedder
import io.prism.fs.UiConfirmationGate
import io.prism.memory.CrossSessionMemoryManager
import io.prism.memory.SlidingWindowMemoryManager
import io.prism.memory.SlidingWindowResult
import io.prism.memory.UserProfileManager
import io.prism.network.ChatStreamProvider
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolDefinition
import io.prism.network.WebSearchLocalToolExecutor
import io.prism.network.KnowledgeBaseLocalToolExecutor
import io.prism.rag.RagContextBuilder
import io.prism.rag.RagTarget
import io.prism.skill.SkillExecutor
import io.prism.skill.SkillRegistry
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Citation
import io.prism.ui.model.Role
import io.prism.ui.model.SearchResult
import io.prism.ui.model.ToolCallRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 聊天界面 ViewModel —— 管理消息列表、打字状态、流式回复、RAG 检索与 Skill 工具调用回路
 * （US-019 RAG + US-026 Skill 工具执行回路 + US-035 三层记忆系统集成）。
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
 * **US-035 三层记忆系统集成**（ADR-015 5.6，M5 Phase E）：
 * - 构造注入 [SlidingWindowMemoryManager] + [CrossSessionMemoryManager] + [UserProfileManager]
 *   + [applicationScope]（均可空，null 时降级为无记忆场景，向后兼容）
 * - **会话边界**：首条 [sendMessage] 生成 sessionId（UUID）+ 触发 L2 检索 + L3 画像加载；
 *   [onCleared] 中 fire-and-forget 触发 L2 保存 + L3 隐式偏好抽取（用 [applicationScope]）
 * - **L1 集成**（每轮）：[buildMemoryContext] 调用 [SlidingWindowMemoryManager.processMessages]
 *   处理 history，返回 summary + recentMessages；recentMessages 替换原始 history 发给 provider
 * - **L2 集成**（首条消息）：[startSessionIfNeeded] 调用 [CrossSessionMemoryManager.retrieveRelevantMemories]
 *   + [formatMemoriesAsContext]，结果缓存到 [l2MemoryContext]，后续消息复用
 * - **L3 集成**（首条消息）：[startSessionIfNeeded] 调用 [UserProfileManager.formatProfilesAsContext]，
 *   结果缓存到 [l3ProfileContext]，后续消息复用
 * - **systemPrompt 合并顺序**（ADR-015 决策4）：RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill
 * - **降级策略**：L1/L2/L3 任一失败（或管理器为 null）降级为 null（用户无感），不阻断对话
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
 * @param slidingWindowMemoryManager L1 滑动窗口管理器（M5 Phase E，可空：null 时降级为无 L1 摘要）
 * @param crossSessionMemoryManager L2 跨会话记忆管理器（M5 Phase E，可空：null 时降级为无 L2 检索）
 * @param userProfileManager L3 用户画像管理器（M5 Phase E，可空：null 时降级为无 L3 画像注入）
 * @param applicationScope 应用级协程作用域（M5 Phase E，用于 onCleared 中 fire-and-forget 记忆持久化）
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
    /** M5 Phase E：L1 滑动窗口记忆管理器，null 时降级为无 L1 摘要（向后兼容既有测试） */
    private val slidingWindowMemoryManager: SlidingWindowMemoryManager? = null,
    /** M5 Phase E：L2 跨会话记忆管理器，null 时降级为无 L2 检索（向后兼容既有测试） */
    private val crossSessionMemoryManager: CrossSessionMemoryManager? = null,
    /** M5 Phase E：L3 用户画像管理器，null 时降级为无 L3 画像注入（向后兼容既有测试） */
    private val userProfileManager: UserProfileManager? = null,
    /**
     * M5 Phase E：应用级协程作用域，用于 [onCleared] 中 fire-and-forget 记忆持久化。
     * 必须使用 [SupervisorJob]（不随 ViewModel 销毁取消），生产环境由 [PrismApplication.appScope] 注入。
     * 测试中可注入 [kotlinx.coroutines.test.TestScope] 或自定义 scope。
     */
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** IO 调度器，用于 RAG embed+search 阻塞调用（BR-concurrency-002）。测试中注入 test dispatcher。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * M6 Phase C：用户确认门禁，UI 收集其 [UiConfirmationGate.requests] 流展示确认对话框。
     *
     * null 时降级为无确认 UI（SkillExecutor.confirm() 30s 超时返回 false，工具被静默拒绝）。
     * 生产环境由 [PrismApplication.confirmationGate] 注入；测试可注入 fake 或 null。
     */
    val confirmationGate: UiConfirmationGate? = null,
    /**
     * M6 Phase C：跨 App 调用 ActivityResult 桥接器，UI 收集其 [AppLauncherBridge.requests] 流
     * 触发 `launcher.launch(intent)`，回调结果回灌 `bridge.respond(id, result)`。
     *
     * null 时降级为无 launcher 注册（跨 App 工具调用 25s 超时返回失败描述，BR-concurrency-005）。
     * 生产环境由 [PrismApplication.appLauncherBridge] 注入；测试可注入 fake 或 null。
     */
    val appLauncherBridge: AppLauncherBridge? = null,
    /**
     * M6 Phase C：跨 App 调用核心入口，用于 [buildTools] 合并跨 App 工具定义。
     *
     * null 时降级为不注册跨 App 工具（LLM 无法感知跨 App 能力，但已注册的本地工具分支
     * 仍可通过 SkillExecutor 调用——只是 LLM 不会主动触发）。
     * 生产环境由 [PrismApplication.crossAppLauncher] 注入；测试可注入 fake 或 null。
     */
    val crossAppLauncher: CrossAppLauncher? = null,
    /**
     * DEF-008（Bug-3）：MCP 工具提供者，用于注入已启用 MCP Server 的工具到 LLM `tools` 列表。
     *
     * null 时降级为不注入 MCP 工具（LLM 无法感知 MCP 能力）。
     * 生产环境由 [PrismApplication.mcpToolProviderDispatcher] 注入；测试可注入 fake 或 null。
     */
    private val mcpToolProvider: McpToolProvider? = null,
    /**
     * M7 Phase B（ADR-017 4.7）：RAG 检索 top-k，按档位动态传入。
     *
     * - FULL 档：5（标准批次）
     * - STANDARD 档：3（小批次，4-6GB 设备约束）
     * - MINIMAL / CHAT_ONLY 档：0（RAG 禁用，值不使用，因 [buildRagPlan] 在 RagTarget.Off 时短路）
     *
     * 默认 [DEFAULT_RAG_TOP_K]=3（向后兼容既有测试，未注入时按 STANDARD 行为）。
     * 生产环境由 [Factory] 从 [io.prism.tier.TierManager.currentTier.ragTopK] 注入。
     */
    private val ragTopK: Int = DEFAULT_RAG_TOP_K,
    /**
     * 问题 8a（ADR-020）：深度思考配置仓库（DataStore 持久化开关 + 思考强度）。
     *
     * null 时降级为深度思考关闭（不发送 thinking 字段，向后兼容所有 Provider 端点）。
     * 生产环境由 [Factory] 从 [PrismApplication.thinkingConfigRepository] 注入。
     */
    private val thinkingConfigRepository: io.prism.config.ThinkingConfigRepository? = null,
    /**
     * 问题 8b（ADR-020）：是否启用联网搜索工具（`web_search__search`）。
     *
     * 默认 false（向后兼容既有测试：纯函数 [Companion.buildTools] 默认不合并搜索工具，
     * 空 Skill 列表返回空 tools）。生产环境由 [Factory] 显式传 true（联网搜索零配置免费，
     * LLM 始终可感知并调用）。
     */
    private val webSearchEnabled: Boolean = false,
    /**
     * UX-001 问题 4（ADR-021）：会话仓库（历史对话记录持久化）。
     *
     * null 时降级为无会话持久化（向后兼容既有测试，消息仅内存态）。
     * 生产环境由 [Factory] 从 [PrismApplication.sessionRepository] 注入。
     */
    private val sessionRepository: io.prism.data.SessionRepository? = null,
    /**
     * UXR3 问题 10（ADR-023）：工具审批模式配置仓库。
     *
     * 用于构建 tools 前判断是否注入工具定义：
     * - DISABLED 模式：不向 LLM 注入任何工具（LLM 无法感知与调用工具）
     * - MANUAL / AUTO 模式：正常注入（执行时的审批由 [SkillExecutor] 按模式处理）
     *
     * null 时降级为不启用该功能（视为 MANUAL，正常注入工具，向后兼容既有测试）。
     * 生产环境由 [Factory] 从 [PrismApplication.toolApprovalConfigRepository] 注入。
     */
    private val toolApprovalConfigRepository: io.prism.config.ToolApprovalConfigRepository? = null
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

    /** UX-001 问题 2（ADR-022）：用户是否手动切换过深度思考开关（防 init 竞态覆盖）。 */
    private var thinkingToggledByUser = false

    init {
        // UX-001 问题 5（ADR-021）+ 问题 2（ADR-022 二次反馈）：初始化深度思考开关状态。
        // 竞态修复：异步读 DataStore 期间用户可能已点击开关（setThinkingEnabled 已写内存），
        // 若 init 完成后用 DataStore 旧值覆盖会"点了没反应"。用 [thinkingToggledByUser] 标记：
        // 用户手动切换过则不再应用 init 读取值。
        viewModelScope.launch {
            try {
                val stored = thinkingConfigRepository?.getThinkingEnabled()
                if (!thinkingToggledByUser) {
                    stored?.let { _thinkingEnabled.value = it }
                }
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007
            } catch (e: Exception) {
                Log.w(TAG, "init thinkingEnabled failed: ${e::class.simpleName}")
            }
        }
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** 消息列表（只读 StateFlow） */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    /** AI 是否正在回复（打字指示） */
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    /**
     * 当前正在调用的工具名（UX-001 问题 7，ADR-022）。
     *
     * 非 null 表示 LLM 正在调用该工具（UI 展示「正在调用工具: xxx」），
     * null 表示无活动工具调用。由 [handleStreamEvent] 的 ToolCallStart 置位。
     *
     * **UXR4 问题 7/10（ADR-024）生命周期修复**：此前在 [StreamEvent.Done]（紧跟
     * ToolCallComplete）即清除，工具**执行阶段** activeTool=null 且 isTyping=false，
     * UI 指示一闪而过。现改为：工具回路（[executeWithToolLoop]）期间 activeTool 保持，
     * 由回路结束（finally）统一清除；无工具的普通流式分支（[executePlainStream]）
     * 保持"Done 清除"原行为。
     */
    private val _activeTool = MutableStateFlow<String?>(null)
    val activeTool: StateFlow<String?> = _activeTool.asStateFlow()

    /**
     * UXR6 问题 2：当前正在流式生成的 AI 消息 id 集合。
     *
     * 替代「全局 isTyping + lastOrNull() 推断」的每消息独立标记：
     * - [launchAnswer] 创建 aiId 占位消息时加入（流式期间渲染为纯文本，避免 markdown 中间态井号残留）
     * - [handleStreamEvent] Done/Error 或 [executeWithToolLoop] finally 时移除（切换 Markdown 完整渲染）
     * - 多消息并发时仅当前消息为 true，判定准确（修复全局推断在工具占位/tool 消息插入后失效）
     */
    private val _streamingIds = MutableStateFlow<Set<Long>>(emptySet())
    val streamingIds: StateFlow<Set<Long>> = _streamingIds.asStateFlow()

    /**
     * UXR6 问题 3a：当前是否正在执行 RAG 检索（反映「本消息」的检索活动）。
     *
     * [launchAnswer] 的 [buildRagPlan] 前置 true、完成/降级后置 false。
     * UI 据此决定是否显示「正在检索知识库…」指示，替代旧「ragTarget 开关 + 全局 ragDone」
     * 推断（旧逻辑对每条消息无条件显示检索画面）。
     */
    private val _ragRetrieving = MutableStateFlow(false)
    val ragRetrieving: StateFlow<Boolean> = _ragRetrieving.asStateFlow()

    /**
     * UXR4 问题 7/10（ADR-024）：是否处于工具执行回路中。
     *
     * true 时 [handleStreamEvent] 的 Done 事件**不**清除 activeTool/isTyping
     * （工具执行阶段保持「正在调用工具」指示），由 [executeWithToolLoop] 的 finally
     * 统一复位。false（普通流式分支）时 Done 清除原行为不变。
     */
    private var toolLoopActive = false

    /**
     * UXR6 问题 5（TTFT）：MCP 工具定义缓存。
     *
     * [launchAnswer] 每轮都会对每个 enabled 远程 MCP Server 调用 `describeTools`
     * （网络连接 + listTools），是不可达 Server 时首 token 前的重阻塞项。工具定义低频变化，
     * 故按 enabled server 集合签名做失效：server 增删改/启停导致签名变化 → 清缓存重取。
     */
    private val mcpToolsCache = mutableMapOf<String, List<ToolDefinition>>()
    private var mcpToolsSignature: String? = null

    /**
     * RAG 检索目标模式（US-019，ADR-012 5.2）。
     *
     * 默认 [RagTarget.AllLibraries]（全库检索 + 默认开启）。
     * 用户可在对话页通过 [setRagTarget] 切换三态：全库 / 指定库 / 关闭。
     */
    private val _ragTarget = MutableStateFlow<RagTarget>(RagTarget.AllLibraries)
    val ragTarget: StateFlow<RagTarget> = _ragTarget.asStateFlow()

    /**
     * 深度思考开关（UX-001 问题 5，ADR-021）。
     *
     * 初始值从 [thinkingConfigRepository] 读取；切换时经 [setThinkingEnabled] 持久化到
     * DataStore，并同步内存 StateFlow（UI 即时响应）。null 仓库降级为默认关闭。
     */
    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    /**
     * 联网搜索开关（UX-001 问题 5，ADR-021）。
     *
     * 初始值由构造函数 [webSearchEnabled] 决定（生产默认 true，向后兼容既有测试默认 false）。
     * 切换时经 [setWebSearchEnabled] 更新，[sendMessage] 按当前值决定是否合并搜索工具。
     */
    private val _webSearchEnabled = MutableStateFlow(webSearchEnabled)
    val webSearchEnabledFlow: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    /**
     * 当前会话 ID（M5 Phase E，US-035）。
     *
     * 首条 [sendMessage] 时生成（UUID），用于 L2 跨会话记忆存储/检索的会话隔离。
     * null 表示尚未开始会话（无消息发送过），[onCleared] 检查此字段决定是否触发持久化。
     *
     * **非 StateFlow**：会话 ID 是内部状态，UI 不需要订阅其变化。
     */
    private var sessionId: String? = null
    /** UX-001 问题 4（ADR-021）：当前会话的 ObjectBox id（null 表示尚未持久化）。 */
    private var sessionObjId: Long = 0L

    /**
     * UXR4 问题 8/9（ADR-024）：会话脏标记 —— 仅当有新消息（sendMessage/编辑重发）时置位。
     *
     * `persistSession` 仅在脏标记为 true 时写库，避免"只读打开历史会话再退出"刷新 updatedAt。
     * 回答完成（Done/Error）时落库并清位；`loadSession` 后清位。
     */
    private var messagesDirty = false

    /**
     * L2 跨会话记忆上下文缓存（M5 Phase E，US-035）。
     *
     * 首条 [sendMessage] 时通过 [CrossSessionMemoryManager.retrieveRelevantMemories] +
     * [CrossSessionMemoryManager.formatMemoriesAsContext] 生成，后续消息复用（避免每轮 embed+search，
     * ADR-015 H-2 Embedder 串行锁瓶颈缓解）。null 表示无跨会话记忆（首条消息未触发检索或检索结果为空）。
     */
    private var l2MemoryContext: String? = null

    /**
     * L3 用户画像上下文缓存（M5 Phase E，US-035）。
     *
     * 首条 [sendMessage] 时通过 [UserProfileManager.formatProfilesAsContext] 生成，后续消息复用
     * （画像变更在会话期间不反映，需新会话才生效；与显式偏好 UI 设定解耦）。null 表示无用户画像
     * （用户从未设定偏好，或 [userProfileManager] 为 null 降级）。
     */
    private var l3ProfileContext: String? = null

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
     * 切换深度思考开关（UX-001 问题 5，ADR-021）。
     *
     * 同步更新内存 StateFlow（UI 即时响应）并持久化到 DataStore（[ThinkingConfigRepository]）。
     * 仓库为 null 时仅更新内存状态（降级场景）。
     */
    fun setThinkingEnabled(enabled: Boolean) {
        // UX-001 问题 2（ADR-022）：标记用户已手动切换（防 init 异步读取覆盖竞态）
        thinkingToggledByUser = true
        _thinkingEnabled.value = enabled
        val repo = thinkingConfigRepository
        if (repo != null) {
            viewModelScope.launch {
                try {
                    repo.setThinkingEnabled(enabled)
                } catch (e: CancellationException) {
                    throw e // BR-error-handling-007：协程取消必须重抛
                } catch (e: Exception) {
                    Log.w(TAG, "persist thinkingEnabled failed: ${e::class.simpleName}")
                }
            }
        }
    }

    /**
     * 切换联网搜索开关（UX-001 问题 5，ADR-021）。
     *
     * [sendMessage] 按当前值决定是否合并 `web_search__search` 工具到 LLM tools 列表。
     */
    fun setWebSearchEnabled(enabled: Boolean) {
        _webSearchEnabled.value = enabled
    }

    /**
     * 判断当前是否处于「工具禁用」审批模式（UXR3 问题 10，ADR-023）。
     *
     * DISABLED 模式下不向 LLM 注入任何工具定义（[sendMessage] 构建 tools 前调用）。
     * 仓库为 null 时视为 MANUAL（正常注入，向后兼容既有测试）。
     *
     * **BR-error-handling-007**（guardrail M-3 修复）：禁用 `runCatching`（会吞
     * CancellationException 破坏取消即时传播），改用显式 try-catch 重抛。
     */
    private suspend fun isToolsDisabled(): Boolean {
        val repo = toolApprovalConfigRepository ?: return false
        return try {
            repo.getMode() == io.prism.config.ToolApprovalMode.DISABLED
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            Log.w(TAG, "read toolApprovalMode failed: ${e::class.simpleName}")
            false
        }
    }

    /**
     * 编辑用户消息并重新发送（UXR3 问题 13，ADR-023）。
     *
     * 将指定用户消息的内容替换为 [newText]，并**删除该消息之后的所有消息**（含原 AI 回复），
     * 然后触发一次新的 [sendMessage] 请求（携带替换后的完整历史，AI 基于编辑后的问题重新回答）。
     *
     * **实现策略**（复用 sendMessage 主流程）：
     * 1. 找到目标用户消息 index（找不到则忽略）
     * 2. 替换内容 + 截断其后所有消息（[replaceAndTruncateMessages] 原子 CAS）
     * 3. 调用 [sendMessage] 追加编辑后的新消息并请求 AI —— 由于截断后列表已不含旧 AI 回复，
     *    新消息将作为会话末尾正确触发完整请求
     *
     * **边界**：
     * - 仅允许编辑 USER 角色消息（AI/TOOL 消息编辑语义不明确，忽略）
     * - newText 空白时忽略（无意义编辑）
     * - 编辑时若正在生成（isTyping=true），视为忽略（避免并发状态撕裂）
     *
     * @param messageId 目标用户消息 id
     * @param newText 编辑后的新内容
     */
    fun editUserMessageAndResend(messageId: Long, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        if (_isTyping.value) return

        val msgs = _messages.value
        val index = msgs.indexOfFirst { it.id == messageId && it.role == Role.USER }
        if (index < 0) return

        replaceAndTruncateMessages(messageId, trimmed, index)
        // UXR4 问题 8/9（ADR-024）：编辑修改了消息内容，置脏标记（回答完成后落库）
        messagesDirty = true
        // 编辑后直接发起回答请求（不重复追加用户消息 —— 原消息已被替换为编辑内容）
        launchAnswer(trimmed)
    }

    /**
     * 原子替换用户消息内容并截断其后的所有消息（UXR3 问题 13，ADR-023）。
     *
     * 通过 [_messages.update] CAS 一次性完成「替换 + 截断」，避免两步分开导致的中间状态
     * 被 UI 订阅者观察到（BR-concurrency-004 状态原子性）。
     *
     * @param messageId 目标消息 id
     * @param newContent 新内容
     * @param index 目标消息在列表中的下标（调用方已校验）
     */
    private fun replaceAndTruncateMessages(messageId: Long, newContent: String, index: Int) {
        _messages.update { msgs ->
            val updated = msgs.toMutableList()
            updated[index] = updated[index].copy(content = newContent)
            updated.subList(index + 1, updated.size).clear()
            updated
        }
    }

    /**
     * 发送一条消息（US-019 RAG + US-026 Skill 工具执行回路 + US-035 三层记忆集成）。
     *
     * **流程**：
     * 1. trim 输入 → 追加用户消息（[nextId] 原子自增）→ 追加空 AI 占位消息 → isTyping=true
     * 2. 取激活 Provider；无则追加错误提示
     * 3. **RAG 注入**（若 [ragTarget] 非 [Off][RagTarget.Off]）：
     *    - IO 协程执行 embed(query) → search → 阈值过滤
     *    - 拼 system prompt + ragContext
     *    - 引用来源 [io.prism.ui.model.Citation] 列表附在 AI 占位消息上
     *    - 失败按 [RagBuildResult] 三态降级（ADR-012 5.5），不阻断对话
     * 4. **M5 Phase E：会话启动**（[startSessionIfNeeded]）—— 首条消息生成 sessionId +
     *    L2 检索（retrieveRelevantMemories）+ L3 画像加载（formatProfilesAsContext）
     * 5. **M4 Phase D：构建 tools**（[Companion.buildTools]）
     * 6. **历史过滤器扩展**（R-4）：排除 aiId + 空content且空toolCalls 的 assistant 占位
     * 7. **M5 Phase E：构建三层记忆上下文**（[buildMemoryContext]）—— L1 processMessages 处理 history
     *    返回 summary + recentMessages；recentHistory 替换原始 history 发给 provider
     * 8. **合并 systemPrompt**（[Companion.mergeSystemPrompt]）—— ADR-015 决策4 顺序：
     *    RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill
     * 9. **分支策略**（R-1）：
     *    - tools 非空且 [skillExecutor] 非空 → [SkillExecutor.executeLoop] + onEvent 回调
     *    - 否则 → 普通流式 streamChat + collect
     * 10. **消息同步**（R-2）：executeLoop 返回后 [syncToolMessages] 把新增消息追加到 [_messages]
     * 11. Done / Error 后 isTyping=false
     *
     * **状态原子性**：所有 [_messages] 写入均通过 [update] CAS（BR-concurrency-004），
     * 避免 RAG 检索协程与 stream collect 协程并发写导致 lost update（修复 R-5）。
     *
     * **降级提示策略**（ADR-012 5.5 + G-02/G-03 修复 + M5 Phase E 记忆降级）：
     * - [RagBuildResult.Success] → 附 citations，注入 systemPrompt + ragContext
     * - [RagBuildResult.EmbedFailed] → appendDelta 简短提示（项目暂无 Toast 基建，ADR-012 5.5 备注）
     * - [RagBuildResult.NormalChat] → 主动关闭 / search 空 / 阈值过滤空 / RAG 异常，用户无感
     * - L1/L2/L3 任一失败 → 降级为 null（用户无感，不阻断对话）
     *
     * **M4 Phase D 已知限制**（R-5，ADR-014 5.7 偏差）：
     * - Provider 不支持 tools 字段返回 400 时无法精确降级（StreamEvent.Error 不携带状态码）
     * - 标记为已知限制，Phase E 视需要扩展 StreamEvent.Error 携带 statusCode
     *
     * @param text 用户输入文本
     */
    /**
     * 开启新对话（DEF-009，Bug-1）。
     *
     * 清空当前消息列表并重置会话边界状态，使下一条消息走全新的会话初始化。
     *
     * **会话边界**：重置 [sessionId] / [l2MemoryContext] / [l3ProfileContext]，
     * 使 [startSessionIfNeeded] 在下一轮重新生成 sessionId 并检索 L2/L3。
     * 保留 [nextId] 递增（消息 id 全局唯一，避免跨会话冲突）。
     *
     * **注意**：当前消息仅内存态（无持久化），新对话会丢弃旧会话消息。
     * 会话持久化属后续 US（记忆/会话历史），本方法聚焦"开启新对话"的最小语义。
     */
    fun startNewConversation() {
        // UX-001 问题 4（ADR-021）：切换前持久化当前会话（若有消息）
        persistSession()
        _messages.value = emptyList()
        _isTyping.value = false
        sessionId = null
        sessionObjId = 0L
        // UXR4 问题 8/9（ADR-024）：新会话无未落库变更，清脏标记
        messagesDirty = false
        l2MemoryContext = null
        l3ProfileContext = null
        // UX-001 问题 4（ADR-022 二次反馈）：新对话重置 RAG 检索目标为默认全库，
        // 避免打开新对话后仍残留上一个会话的「RAG 全库」状态 / 引用来源 UI。
        _ragTarget.value = RagTarget.AllLibraries
    }

    /**
     * 加载指定会话（UX-001 问题 4，ADR-021）—— 从 [SessionRepository] 恢复历史对话。
     *
     * 先持久化当前会话（若有消息），再加载目标会话的 JSON 消息列表。
     *
     * @param sessionId 要加载的会话的 ObjectBox id
     */
    fun loadSession(sessionId: Long) {
        val repo = sessionRepository ?: return
        val session = repo.get(sessionId) ?: return
        // 持久化当前会话（切换前保存）
        persistSession()
        // 反序列化消息列表
        val json = session.messagesJson
        val msgs = try {
            io.prism.util.ChatMessageSerializer.decodeList(json)
        } catch (e: Exception) {
            emptyList()
        }
        _messages.value = msgs
        sessionObjId = sessionId
        // UXR4 问题 8/9（ADR-024）：加载的会话是"只读查看"，清脏标记 ——
        // 退出时不刷新 updatedAt（避免打开即顶到「刚刚」）。
        messagesDirty = false
        // 重置 L2/L3 缓存（新会话上下文）
        this.sessionId = null
        l2MemoryContext = null
        l3ProfileContext = null
    }

    /**
     * 持久化当前会话到 [SessionRepository]（UX-001 问题 4，ADR-021）。
     *
     * 将当前消息列表序列化为 JSON，保存或更新 [Session] 实体。
     * 消息为空时跳过（无内容可存的空会话）。
     * 降级场景（仓库为 null / 序列化失败）静默降级，不阻断对话。
     *
     * **UXR4 S1 隐私边界（ADR-024 / guardrail TKN-UXR4-GUARDRAIL-001）**：
     * 深度思考开关**关闭**时，思考链（thinkingChain）在内存中仍被累积（供协议层
     * reasoning_content 回传，DeepSeek 硬性要求），但**不持久化进会话 JSON**
     * （`encodeDefaults=true` 会写入 thinkingChain）。协议回传与本地留存解耦：
     * - 回传：内存 thinkingChain 保留（第 2 轮工具回路仍携带）
     * - 留存：开关关闭 → 序列化前剥离 thinkingChain（用户"关闭=不产生思考痕迹"预期）
     */
    private fun persistSession() {
        val repo = sessionRepository ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        // UXR4 问题 8/9（ADR-024）：无脏标记（只读打开历史会话、或上次已落库）时跳过写库，
        // 避免"打开→退出"刷新 updatedAt（会话被错误顶到「刚刚」）。
        if (!messagesDirty) return
        // 生成标题（首条用户消息截断）
        val title = msgs.firstOrNull { it.role == Role.USER }?.content?.take(50)?.trim()
            ?: "新会话"
        // S1（ADR-024）：开关关闭时剥离 thinkingChain 再序列化（隐私边界，协议回传不受影响）
        val toPersist = if (_thinkingEnabled.value) msgs else stripThinkingChain(msgs)
        val json = try {
            io.prism.util.ChatMessageSerializer.encodeList(toPersist)
        } catch (e: Exception) {
            return // 序列化失败静默降级
        }
        if (sessionObjId == 0L) {
            // 新建会话
            sessionObjId = repo.save(
                io.prism.data.Session(
                    title = title,
                    messagesJson = json,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            // 更新已有会话
            repo.get(sessionObjId)?.let { existing ->
                repo.save(
                    existing.copy(
                        title = title,
                        messagesJson = json,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        // 落库成功清位（同一内容不再重复写库）
        messagesDirty = false
    }

    /**
     * 剥离所有消息的 thinkingChain（UXR4 S1 隐私边界，ADR-024）。
     *
     * 深度思考开关关闭时调用，避免思考链被 `encodeDefaults=true` 持久化进会话 JSON。
     * 仅影响持久化视图；内存中 [ChatMessage.thinkingChain] 保持不变（供协议回传）。
     *
     * @param msgs 原始消息列表
     * @return 剥离 thinkingChain 后的消息列表
     */
    private fun stripThinkingChain(msgs: List<ChatMessage>): List<ChatMessage> =
        msgs.map { if (it.thinkingChain != null) it.copy(thinkingChain = null) else it }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Q5（guardrail TKN-UXR4-GUARDRAIL-001）：AI 正在回复时忽略新发送，
        // 防止并发回路（旧回路 finally 清除新回路 isTyping）导致状态撕裂。
        if (_isTyping.value) return

        val now = System.currentTimeMillis()
        _messages.update { it + ChatMessage(nextId.getAndIncrement(), Role.USER, trimmed, now) }
        // UXR4 问题 8/9（ADR-024）：新消息置脏标记，回答完成后落库并刷新 updatedAt
        messagesDirty = true

        // UXR3 问题 13（ADR-023）：发起回答请求逻辑提取为独立方法，
        // 供普通发送与编辑重发（[editUserMessageAndResend]）共用 —— 编辑重发不追加新用户消息。
        launchAnswer(trimmed)
    }

    /**
     * 发起一轮 AI 回答请求（UXR3 问题 13，ADR-023 重构提取）。
     *
     * 从 [sendMessage] 提取：追加 AI 占位消息 + RAG 注入 + 会话启动 + 构建 tools +
     * 三层记忆上下文 + 合并 systemPrompt + 分支执行（executeLoop / 普通流式）。
     *
     * **调用方**：
     * - [sendMessage]：追加用户消息后调用（标准发送）
     * - [editUserMessageAndResend]：替换 + 截断原消息后调用（编辑重发，不重复追加用户消息）
     *
     * @param userText 用户消息文本（已 trim，编辑重发时与替换后的消息内容一致）
     */
    private fun launchAnswer(userText: String) {
        viewModelScope.launch {
            _isTyping.value = true
            val aiId = nextId.getAndIncrement()
            // UXR6 问题 2：创建占位即标记为流式生成中（流式期间 UI 渲染纯文本，完成后再切 Markdown）
            markStreaming(aiId)
            _messages.update { it + ChatMessage(aiId, Role.ASSISTANT, "", System.currentTimeMillis()) }

            val active = providerRepository.activeProviderFlow.value
            if (active == null) {
                appendDelta(aiId, "\n\n⚠️ 尚未配置激活的 Provider，请在「设置」中添加并激活")
                markCompleted(aiId)
                _isTyping.value = false
                return@launch
            }

            // UXR6 问题 3a：RAG 检索开始 —— UI 显示「正在检索知识库…」（仅真实检索期间）
            _ragRetrieving.value = true
            // RAG 注入（IO 协程，BR-concurrency-002 全程持锁，禁止 Main）
            // G-01 修复：外层 runCatching 重抛 CancellationException（BR-error-handling-007 提议）
            val ragResult = runCatching { buildRagPlan(userText) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    // G-03 修复：整个 RAG 注入异常 → 仅日志记录 simpleName，用户无感（ADR-012 5.5）
                    // 项目暂无结构化日志基建，用 android.util.Log.w 记录（不含密钥/请求体/路径）
                    Log.w(TAG, "RAG injection failed: ${e::class.simpleName}, degrading to normal chat")
                    RagBuildResult.NormalChat
                }
            // UXR6 问题 3a：RAG 检索结束（无论成功/降级，避免「检索知识库」指示残留）
            _ragRetrieving.value = false

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

            // M5 Phase E（US-035）：会话启动 —— 首条消息生成 sessionId + L2 检索 + L3 画像加载
            // 降级策略：L2/L3 任一失败降级为 null（用户无感），不阻断对话
            startSessionIfNeeded(userText)

            // M4 Phase D：构建 tools（M6 Phase C 扩展：合并跨 App 本地工具；DEF-008 合并 MCP 工具；
            // 问题 8b（ADR-020）+ UX-001 问题 5（ADR-021）：按联网搜索开关合并搜索工具；
            // UXR4 问题 2/3（ADR-024）：合并知识库工具（knowledge_base__search/list_documents/get_document_content）
            val enabledSkills = skillRegistry?.enabledSkills() ?: emptyList()
            val baseTools = Companion.buildTools(enabledSkills, crossAppLauncher, _webSearchEnabled.value)
            // UXR4 问题 2/3（ADR-024）：知识库工具在 **RAG 开启 + 嵌入可用** 时注入
            //（LLM 可主动枚举/检索/读取知识库，解决 RAG 仅自动注入、LLM 无知识库感知能力的问题）。
            // 语义对齐：RAG 关闭（RagTarget.Off）或低端档（ragTopK<=0，NullEmbedder）时
            // LLM 不应感知知识库能力（与 buildRagPlan 短路一致 + guardrail Q2 能力对齐）。
            val kbTools = if (_ragTarget.value is RagTarget.Off || ragTopK <= 0) {
                emptyList()
            } else {
                io.prism.network.KnowledgeBaseLocalToolExecutor.buildToolDefinitions()
            }
            val mcpServers = mcpServerRepository?.servers?.value ?: emptyList()
            // DEF-008（Bug-3）：注入已启用 MCP Server 的工具（带 mcp_ 命名空间前缀，支持多 server 精确路由）。
            // describeTools 失败降级为空（不阻断对话），命名空间由 SkillExecutor.stripNamespace/selectMcpServer 处理。
            // M-2 修复（guardrail TKN-P17-GUARDRAIL-001）：原 runCatching 会吞 CancellationException，
            // 违反 BR-error-handling-007。改为显式 try-catch 重抛 CancellationException。
            // UXR6 问题 5（TTFT）：describeTools 结果按 enabled server 集合签名缓存，避免每轮
            // 网络连接 + listTools 阻塞首 token；签名变化（server 增删改/启停）时清缓存重取。
            val enabledMcpServers = mcpServers.filter { it.isEnabled }
            val mcpSignature = enabledMcpServers.joinToString("|") { "${it.name}@${it.baseUrl}" }
            if (mcpSignature != mcpToolsSignature) {
                mcpToolsCache.clear()
                mcpToolsSignature = mcpSignature
            }
            val mcpTools = mcpToolProvider?.let { provider ->
                enabledMcpServers.flatMap { server ->
                    // guardrail Medium-1（TKN-UXR6-GUARDRAIL-001）：缓存键用 name@baseUrl，
                    // 避免同名 server 不同 baseUrl 时 getOrPut 键冲突静默遮蔽。
                    val cacheKey = "${server.name}@${server.baseUrl}"
                    val toolDefs = mcpToolsCache.getOrPut(cacheKey) {
                        try {
                            provider.describeTools(server)
                        } catch (e: CancellationException) {
                            throw e // BR-error-handling-007：协程取消必须重抛
                        } catch (e: Exception) {
                            // BR-error-handling-004：记录日志（不含敏感信息），降级为空工具列表
                            Log.w(TAG, "describeTools failed: ${e::class.simpleName}")
                            emptyList()
                        }
                    }
                    toolDefs.map { toolDef ->
                        // UX-001 问题 5/6（ADR-022 二次修复）：server 名经 [SkillExecutor.toMcpNamespace]
                        // 规范化后再拼工具名（空格/中文 → `_`），否则含空格/中文的 server 名会生成
                        // 非法工具名，被 OpenAI/DeepSeek 400 拒绝或本地 isLegalToolName 过滤。
                        toolDef.copy(
                            function = toolDef.function.copy(
                                name = "${SkillExecutor.MCP_NAMESPACE_PREFIX}${SkillExecutor.toMcpNamespace(server.name)}${SkillExecutor.NAMESPACE_SEPARATOR}${toolDef.function.name}"
                            )
                        )
                    }
                }
            } ?: emptyList()
            // UX-001 问题 5（二次反馈，ADR-022）：工具名唯一性保障。
            // 根因：MCP 工具名前缀仅依赖 server.name，用户重复添加同名预设 / 自定义同名 server /
            // 空名 server 时产生完全相同工具名 → OpenAI/DeepSeek 400 "Tool names must be unique"。
            // 修复：合并后按工具名去重（保留首个），并过滤非法工具名（OpenAI 仅允许 [a-zA-Z0-9_-]）。
            // 同时避免 Skill 工具（skillName__tool）与 MCP 工具（mcp_server__tool）跨域重名（理论上前缀
            // 命名空间已隔离，防御性再校验）。
            val tools = if (isToolsDisabled()) {
                // UXR3 问题 10（ADR-023）：DISABLED 审批模式 —— 不向 LLM 注入任何工具定义，
                // LLM 无法感知与调用工具（tools 为空走普通流式对话分支，行为等同无 Skill 场景）。
                emptyList()
            } else {
                (baseTools + kbTools + mcpTools)
                    .distinctBy { it.function.name }
                    .filter { isLegalToolName(it.function.name) }
            }

            // 问题 8a（ADR-020）+ UX-001 问题 5（ADR-021）：深度思考开关由 UI 状态驱动。
            // 关闭时 thinkingEnabled=false，不发送 thinking/reasoning_effort 字段（向后兼容所有端点）。
            val thinkingConfig = thinkingConfigRepository
            val thinkingEnabled = _thinkingEnabled.value
            val reasoningEffort = thinkingConfig
                ?.takeIf { thinkingEnabled }
                ?.getReasoningEffort()

            // 请求历史构建：
            // 1. 排除当前 AI 占位消息（aiId）—— 它是本轮待生成目标，不应进 history。
            //    G-02 修复配套：embed 失败 appendDelta 后占位消息非空，原「按空 content 过滤」
            //    会漏过此消息，导致 provider 把降级提示当作上一轮 AI 回复（语义错误）。
            // 2. 排除所有空 content 的 AI 消息——既排除上一轮因服务端零增量（仅 [DONE]）
            //    结束而残留的空消息，避免空 content 消息被严格 API 拒绝
            //    （CR-02，guardrail 发现 1，BR-interface-003）
            // 3. M4 Phase D R-4 修复：保留携带 toolCalls 的空 content assistant 占位消息，
            //    否则下次请求丢失 tool_calls 上下文，OpenAI 返回 400
            val baseHistory = _messages.value.filterNot {
                it.id == aiId ||
                    (it.role == Role.ASSISTANT && it.content.isEmpty() && it.toolCalls.isEmpty())
            }
            // UXR5 问题 4（tool_calls 完整性保护，候选 3 防御）：会话恢复/旧数据可能丢失
            // assistant(tool_calls) 占位的 toolCalls 字段（ChatMessageSerializer 反序列化），
            // 导致 history 中出现无前置 tool_calls 的孤儿 TOOL 消息 → DeepSeek 400
            // "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
            // 防御：丢弃所有无法配对到前置 assistant(tool_calls) 的 TOOL 消息。
            val filteredHistory = Companion.dropOrphanToolMessages(baseHistory)

            // M5 Phase E（US-035）：构建三层记忆上下文（L1 每轮处理 + L2/L3 缓存复用）
            // L1 processMessages 返回 summary + recentMessages；recentMessages 替换原始 history
            // 降级策略：L1 失败降级为 null summary + 原始 history（不阻断对话）
            val memoryContext = buildMemoryContext(filteredHistory, active)

            // 合并 systemPrompt（ADR-015 决策4 顺序：RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill）
            val mergedSystemPrompt = Companion.mergeSystemPrompt(
                ragPrompt = ragPlan?.systemPrompt,
                l1Summary = memoryContext.l1Summary,
                l2Memories = memoryContext.l2Memories,
                l3Profiles = memoryContext.l3Profiles,
                enabledSkills = enabledSkills
            )

            // 分支策略（R-1）：tools 非空且 skillExecutor 非空 → executeLoop + onEvent 回调；
            // 否则 → 普通流式 streamChat + collect（保持无 Skill 场景零开销）
            // M5 Phase E：history 替换为 memoryContext.recentHistory（L1 滑动窗口处理后的近期消息）
            // 问题 8a（ADR-020）：深度思考参数透传给两条分支
            if (tools.isNotEmpty() && skillExecutor != null) {
                executeWithToolLoop(
                    aiId = aiId,
                    active = active,
                    history = memoryContext.recentHistory,
                    mergedSystemPrompt = mergedSystemPrompt,
                    ragContext = ragPlan?.ragContext,
                    tools = tools,
                    mcpServers = mcpServers,
                    thinkingEnabled = thinkingEnabled,
                    reasoningEffort = reasoningEffort
                )
            } else {
                executePlainStream(
                    aiId = aiId,
                    active = active,
                    history = memoryContext.recentHistory,
                    systemPrompt = mergedSystemPrompt,
                    ragContext = ragPlan?.ragContext,
                    thinkingEnabled = thinkingEnabled,
                    reasoningEffort = reasoningEffort
                )
            }
        }
    }

    /**
     * 会话启动初始化（M5 Phase E，US-035）。
     *
     * 首条 [sendMessage] 时调用，完成：
     * 1. 生成 [sessionId]（UUID），用于 L2 跨会话记忆存储/检索的会话隔离
     * 2. L2 跨会话记忆检索：[CrossSessionMemoryManager.retrieveRelevantMemories] +
     *    [CrossSessionMemoryManager.formatMemoriesAsContext]，结果缓存到 [l2MemoryContext]
     * 3. L3 用户画像加载：[UserProfileManager.formatProfilesAsContext]，结果缓存到 [l3ProfileContext]
     *
     * **幂等**：[sessionId] 非空时直接返回，不重复初始化。
     *
     * **降级策略**（ADR-015 5.6）：L2/L3 任一失败（或管理器为 null）降级为 null（用户无感），
     * 不阻断对话。L2 检索 embed 失败、L3 画像仓库空均属正常降级场景。
     *
     * **线程安全**：在 viewModelScope.launch 内调用，L2 检索的 embed 在 [ioDispatcher] 协程执行
     * （BR-concurrency-002 全程持锁）。L3 画像加载是同步 ObjectBox 查询，无 IO 阻塞。
     *
     * **BR-error-handling-007**：显式 try-catch，CancellationException 重抛，其他异常降级为 null。
     *
     * @param firstUserMessage 首条用户消息文本（用于 L2 检索查询向量）
     */
    private suspend fun startSessionIfNeeded(firstUserMessage: String) {
        if (sessionId != null) return  // 已启动会话，幂等返回

        sessionId = UUID.randomUUID().toString()

        // L2 跨会话记忆检索（IO 协程，BR-concurrency-002 全程持锁）
        // 失败降级为 null（用户无感），不阻断对话
        l2MemoryContext = crossSessionMemoryManager?.let { manager ->
            try {
                val results = withContext(ioDispatcher) {
                    manager.retrieveRelevantMemories(firstUserMessage)
                }
                manager.formatMemoriesAsContext(results)
            } catch (e: CancellationException) {
                throw e  // BR-error-handling-007：协程取消必须重抛
            } catch (e: Exception) {
                // BR-error-handling-004：记录日志（不含敏感信息），降级为无跨会话记忆
                Log.w(TAG, "L2 retrieveRelevantMemories failed: ${e::class.simpleName}")
                null
            }
        }

        // L3 用户画像加载（同步 ObjectBox 查询，无 IO 阻塞）
        // 失败降级为 null（用户无感），不阻断对话
        l3ProfileContext = userProfileManager?.let { manager ->
            try {
                manager.formatProfilesAsContext()
            } catch (e: CancellationException) {
                throw e  // BR-error-handling-007：协程取消必须重抛（与 L2 一致，L-1 修复）
            } catch (e: Exception) {
                // BR-error-handling-004：记录日志，降级为无用户画像
                Log.w(TAG, "L3 formatProfilesAsContext failed: ${e::class.simpleName}")
                null
            }
        }
    }

    /**
     * 统一收集三层记忆上下文（M5 Phase E，US-035，R-5 缓解）。
     *
     * 抽取自 [sendMessage]，避免主流程复杂度爆炸。所有记忆层失败统一降级为 null（用户无感）。
     *
     * **L1 滑动窗口**（每轮调用）：
     * - [SlidingWindowMemoryManager.processMessages] 处理 history，返回 summary + recentMessages
     * - summary 注入 systemPrompt（[SlidingWindowResult.toSummarySystemPromptSection]）
     * - recentMessages 替换原始 history 发给 provider（滑动窗口只保留近期 N 条）
     * - 失败降级为 null summary + 原始 history（不阻断对话）
     *
     * **L2/L3**：复用 [startSessionIfNeeded] 缓存的 [l2MemoryContext] / [l3ProfileContext]
     * （首条消息已检索/加载，后续消息复用，避免每轮 embed+search 缓解 ADR-015 H-2 瓶颈）
     *
     * **BR-error-handling-007**：显式 try-catch，CancellationException 重抛。
     *
     * @param history 过滤后的对话历史（排除当前 aiId + 空占位）
     * @param active 当前激活 Provider 配置（用于 L1 摘要 LLM 请求，支持运行时切换 Provider）
     * @return 三层记忆上下文聚合（[MemoryContext]）
     */
    private suspend fun buildMemoryContext(
        history: List<ChatMessage>,
        active: ProviderConfig
    ): MemoryContext {
        // L2/L3 上下文已由 startSessionIfNeeded 缓存，直接复用
        val l2Memories = l2MemoryContext
        val l3Profiles = l3ProfileContext

        // L1 滑动窗口处理（每轮调用，内部判断是否需要摘要）
        // 失败降级为 null summary + 原始 history（不阻断对话）
        val slidingResult: SlidingWindowResult? = slidingWindowMemoryManager?.let { manager ->
            try {
                manager.processMessages(history, active)
            } catch (e: CancellationException) {
                throw e  // BR-error-handling-007：协程取消必须重抛
            } catch (e: Exception) {
                // BR-error-handling-004：记录日志，降级为无 L1 摘要
                Log.w(TAG, "L1 processMessages failed: ${e::class.simpleName}")
                null
            }
        }

        val l1Summary = slidingResult?.toSummarySystemPromptSection()
        val recentHistory = slidingResult?.recentMessages ?: history

        return MemoryContext(
            l1Summary = l1Summary,
            recentHistory = recentHistory,
            l2Memories = l2Memories,
            l3Profiles = l3Profiles
        )
    }

    /**
     * 三层记忆上下文聚合（M5 Phase E，US-035）。
     *
     * @property l1Summary L1 摘要 systemPrompt section（null 表示无摘要/降级/管理器为 null）
     * @property recentHistory L1 处理后的近期消息列表（替换原始 history 发给 provider；
     *           无 L1 时等于原始 history）
     * @property l2Memories L2 跨会话记忆 systemPrompt section（null 表示无/降级/管理器为 null）
     * @property l3Profiles L3 用户画像 systemPrompt section（null 表示无/降级/管理器为 null）
     */
    private data class MemoryContext(
        val l1Summary: String?,
        val recentHistory: List<ChatMessage>,
        val l2Memories: String?,
        val l3Profiles: String?
    )

    /**
     * ViewModel 销毁时的清理钩子（M5 Phase E，US-035）。
     *
     * **UX-001 问题 4（ADR-021）**：先调用 [persistSession] 把当前对话持久化为会话历史，
     * 再委托 [persistSessionMemories]（internal 可测）持久化 L2/L3 记忆。
     * 顺序保证：会话 JSON 先落库，记忆向量化 / 画像抽取后执行，互不冲突。
     *
     * [onCleared] 是 `protected` 无法从测试直接调用，提取持久化逻辑至 internal 函数
     * 便于单元测试覆盖（BR-testing-004 可测性模式）。
     *
     * @see persistSession
     * @see persistSessionMemories
     */
    override fun onCleared() {
        super.onCleared()
        persistSession()
        persistSessionMemories()
    }

    /**
     * 会话结束持久化（M5 Phase E，US-035）—— fire-and-forget 触发 L2 保存 + L3 隐式偏好抽取。
     *
     * **从 [onCleared] 提取为 internal**：[ViewModel.onCleared] 是 `protected` 无法从测试直接调用，
     * 提取后测试可调用此函数验证持久化逻辑（BR-testing-004 可测性）。
     *
     * **会话结束持久化**（fire-and-forget）：
     * - L2 保存跨会话记忆：[CrossSessionMemoryManager.saveSessionMemories] 向量化本会话关键对话
     * - L3 抽取隐式偏好：[UserProfileManager.extractImplicitPreferences] LLM 从对话抽取偏好
     *
     * **使用 [applicationScope] 而非 [viewModelScope]**：[viewModelScope] 在 [onCleared] 调用时
     * 已被取消，无法启动新协程。[applicationScope] 使用 [SupervisorJob]，不随 ViewModel 销毁取消，
     * 保证记忆持久化在 ViewModel 销毁后继续执行。
     *
     * **降级策略**：L2/L3 任一失败静默降级（BR-error-handling-004 已在组件内部处理日志），
     * 不影响应用其他部分。无 sessionId（未发送过消息）或消息为空时跳过持久化。
     *
     * **线程安全**：[applicationScope] 默认 [Dispatchers.IO]，L2 embed 与 L3 chatCompletion
     * 均在 IO 线程执行。L2/L3 串行执行（避免并发 LLM 请求）。
     */
    internal fun persistSessionMemories() {
        // 未开始会话（无消息发送过）或消息为空 → 跳过持久化
        val sid = sessionId ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return

        val active = providerRepository.activeProviderFlow.value

        // fire-and-forget：使用 applicationScope（SupervisorJob），不随 ViewModel 销毁取消
        applicationScope.launch {
            // L2 保存跨会话记忆（失败静默，BR-error-handling-004 已在组件内部处理日志）
            try {
                crossSessionMemoryManager?.saveSessionMemories(sid, msgs)
            } catch (e: CancellationException) {
                throw e  // BR-error-handling-007：协程取消必须重抛
            } catch (e: Exception) {
                Log.w(TAG, "onCleared: L2 saveSessionMemories failed: ${e::class.simpleName}")
            }

            // L3 抽取隐式偏好（需要 active Provider 配置，失败静默）
            if (active != null) {
                try {
                    userProfileManager?.extractImplicitPreferences(msgs, active)
                } catch (e: CancellationException) {
                    throw e  // BR-error-handling-007：协程取消必须重抛
                } catch (e: Exception) {
                    Log.w(TAG, "onCleared: L3 extractImplicitPreferences failed: ${e::class.simpleName}")
                }
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
        mcpServers: List<io.prism.data.McpServerConfig>,
        thinkingEnabled: Boolean,
        reasoningEffort: String?
    ) {
        // UXR4 问题 7/10（ADR-024）：进入工具回路 —— 期间 handleStreamEvent 的 Done 不清除
        // activeTool/isTyping，保证工具执行阶段持续显示「正在调用工具」。finally 统一复位。
        toolLoopActive = true
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
                thinkingEnabled = thinkingEnabled,
                reasoningEffort = reasoningEffort,
                onEvent = { event -> handleStreamEvent(aiId, event) }
            )
            // R-2：同步 executeLoop 返回的新增消息（assistant 占位 + tool result）到 _messages
            syncToolMessages(updatedMessages, history.size, aiId)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // M4 Phase D：结构化日志（BR-error-handling-004），便于定位 executeLoop 异常根因
            Log.w(TAG, "executeLoop failed: ${e::class.simpleName}", e)
            appendDelta(aiId, "\n\n⚠️ 工具执行回路异常: ${e::class.simpleName}")
        } finally {
            toolLoopActive = false
            // UXR6 问题 2：工具回路结束时标记 aiId 完成（切换 Markdown 完整渲染）。
            // 最终文本 Delta 在最后一行 Done 前已累积到 aiId；若途中 Error 未在此标记，
            // 本 finally 兜底确保 isStreaming 标记清除，避免残留"流式中"状态。
            markCompleted(aiId)
            _activeTool.value = null
            _isTyping.value = false
            // UXR4 问题 8/9（ADR-024）：工具回路结束（含最终文本回答完成）落库，
            // updatedAt=最后消息结束时刻；脏标记检查由 persistSession 内部处理。
            persistSession()
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
        ragContext: String?,
        thinkingEnabled: Boolean,
        reasoningEffort: String?
    ) {
        try {
            val stream = provider.streamChat(
                config = active,
                messages = history,
                systemPrompt = systemPrompt,
                ragContext = ragContext,
                thinkingEnabled = thinkingEnabled,
                reasoningEffort = reasoningEffort
            )
            stream.collect { event -> handleStreamEvent(aiId, event) }
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // R2-NEW-4（guardrail TKN-UXR4-GUARDRAIL-R2）：防御纵深 ——
            // 若未来 Provider 直接抛异常（而非发射 StreamEvent.Error），此处兜底复位状态，
            // 避免 isTyping 卡死导致 sendMessage 守卫永久屏蔽用户发送。
            Log.w(TAG, "plain stream failed: ${e::class.simpleName}", e)
            markCompleted(aiId)
            _isTyping.value = false
            _activeTool.value = null
            persistSession()
        } finally {
            // guardrail Low-4 / R2-1（TKN-UXR6-GUARDRAIL-R2）：流若空事件结束（无 Done/Error），
            // streamingIds 与 isTyping 会残留"流式中"标记（UI 永远纯文本渲染 + sendMessage 守卫
            // 永久屏蔽用户发送）。markCompleted 与 isTyping=false 均为幂等操作，无论 Done/catch
            // 分支是否已清，此处兜底确保复位（与 catch 分支对称）。
            markCompleted(aiId)
            _isTyping.value = false
        }
    }

    /**
     * 处理 [StreamEvent] 通用回调（M4 Phase D 抽取，覆盖 7 子类穷尽匹配）。
     *
     * 同时被 [executeWithToolLoop]（作为 executeLoop 的 onEvent 回调）与
     * [executePlainStream]（作为 collect 内 when 分发）使用，保证两分支事件处理一致。
     *
     * **事件处理策略**：
     * - [StreamEvent.Delta] → [appendDelta] 累积到 aiId 消息
     * - [StreamEvent.ReasoningDelta] → [appendDelta] 以 `[思考]` 前缀累积（问题 8a，
     *   深度思考推理过程，与最终答案区分）
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
            // UX-001 问题 7（ADR-021）：深度思考推理过程改为独立 thinkingChain 字段（可折叠展示），
            // 不再混入最终答案 content（避免「[思考]」前缀污染正文）。
            // UXR4 问题 1/4/6（ADR-024）：thinkingChain 始终累积（供协议层 reasoning_content 回传），
            // UI 展示由 ConversationScreen 的 showThinking（深度思考开关）控制。
            is StreamEvent.ReasoningDelta -> appendThinkingDelta(aiId, event.content)
            StreamEvent.Done -> {
                // UXR4 问题 7/10（ADR-024）：工具回路（executeLoop 第 1 轮）的 Done 紧跟
                // ToolCallComplete 之后到达，此时工具**尚未执行**。若在此清除 activeTool/isTyping，
                // 工具执行阶段 UI 呈空白（指示一闪而过）。故仅在非工具回路（executePlainStream）
                // 时清除；工具回路由 executeWithToolLoop finally 统一复位。
                if (!toolLoopActive) {
                    // UXR6 问题 2：Done 时标记该消息完成（切换 Markdown 完整渲染）
                    markCompleted(aiId)
                    _isTyping.value = false
                    _activeTool.value = null
                    // UXR4 问题 8/9（ADR-024）：回答完成落库（updatedAt=最后消息结束时刻）。
                    // 仅非工具回路（普通流式完成）在此落库；工具回路由 executeWithToolLoop
                    // 的 finally 落库（其内部最后一个 Done 同样会走到 executeLoop 返回后）。
                    persistSession()
                }
            }
            is StreamEvent.Error -> {
                // M-1 修复（guardrail TKN-M4-PHASED-GUARDRAIL-001）：UI 边界防御性脱敏
                // 第二层防御，覆盖未来 Provider 可能透传原始异常 message 的风险（CWE-209）
                val safeMsg = Companion.sanitizeUiErrorMessage(event.message)
                appendDelta(aiId, "\n\n⚠️ $safeMsg")
                // UXR6 问题 2（核心修复）：与 Done 分支保持对称 —— 工具回路（executeLoop）
                // **中途**的 Error 事件（网络抖动/SSE 中断/某轮失败）不得无条件清 isTyping，
                // 否则破坏 isStreaming（第 2 回合最终文本流式期间被误判为"完成"，Markdown 直接
                // 渲染不完整中间态 → 井号残留）。Error 统一由 executeWithToolLoop finally 复位；
                // 仅非工具回路（executePlainStream）在此直接复位。
                if (!toolLoopActive) {
                    markCompleted(aiId)
                    _isTyping.value = false
                    _activeTool.value = null
                    // UXR4 问题 8/9（ADR-024）：错误结束也落库（保留已生成内容）
                    persistSession()
                }
            }
            is StreamEvent.ToolCallStart -> {
                // UX-001 问题 7（ADR-022）：工具调用状态可视化 —— 记录活动工具名，
                // UI 展示「正在调用工具: xxx」（对齐 Claude Code 工具进度模型）。
                // 工具名去命名空间前缀展示（如 web_search__search → search）。
                _activeTool.value = event.toolName.substringAfterLast(SkillExecutor.NAMESPACE_SEPARATOR)
            }
            is StreamEvent.ToolCallDelta -> Unit  // no-op：参数增量片段，UI 实时展示为可选优化
            is StreamEvent.ToolCallComplete -> {
                // UXR4 问题 7/10（ADR-024）：工具调用完成（即将执行）—— 保持 activeTool
                //（继续展示「正在调用工具: xxx」）+ 置 isTyping=true，使工具执行阶段有进行中指示，
                // 而非 Done 后立即清除导致执行期空白。
                _isTyping.value = true
                _activeTool.value = event.toolName.substringAfterLast(SkillExecutor.NAMESPACE_SEPARATOR)
            }
        }
    }

    /**
     * 将深度思考推理增量追加到消息的 [ChatMessage.thinkingChain] 字段（UX-001 问题 7，ADR-021）。
     *
     * 与 [appendDelta] 分离：thinkingChain 独立于 content，UI 层渲染为可折叠「深度思考」区域，
     * 与最终答案区分展示（对齐 DeepSeek 手机端「深度思考区域 + 生成回答」两段式）。
     *
     * @param aiId 目标 AI 消息 id
     * @param delta 思考过程增量片段
     */
    private fun appendThinkingDelta(aiId: Long, delta: String) {
        _messages.update { msgs ->
            // UXR6 问题 4/5：与 appendDelta 相同的分配优化（仅重建目标消息，避免全量 map）
            val index = msgs.indexOfFirst { it.id == aiId }
            if (index < 0) return@update msgs
            val updated = msgs.toMutableList()
            updated[index] = updated[index].copy(thinkingChain = (updated[index].thinkingChain ?: "") + delta)
            updated
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
     * **UX-001 问题 8（ADR-021）**：联网搜索（`web_search__search`）的 TOOL 结果
     * 解析为结构化 [SearchResult] 列表，附加到 AI 消息 [ChatMessage.searchResults]，
     * UI 渲染为可折叠来源卡片（可点击跳转外部网站）。
     *
     * **原子性**：通过 [_messages.update] CAS 写入（BR-concurrency-004）。
     *
     * @param updatedMessages executeLoop 返回的完整消息序列
     * @param originalHistorySize 调用 executeLoop 前的 history 大小（用于 drop 计算）
     * @param aiId 当前 AI 文本回复消息 id（用于附加解析出的 searchResults）
     */
    private fun syncToolMessages(
        updatedMessages: List<ChatMessage>,
        originalHistorySize: Int,
        aiId: Long
    ) {
        val newMsgs = updatedMessages.drop(originalHistorySize)
        if (newMsgs.isEmpty()) return
        // 从新增消息中提取联网搜索 TOOL 结果并解析为结构化 SearchResult 列表
        val searchResults = newMsgs
            .filter { it.role == Role.TOOL && it.toolName == WebSearchLocalToolExecutor.TOOL_SEARCH }
            .flatMap { Companion.parseSearchResults(it.content) }
            .distinctBy { it.link }
        // UXR6 问题 3b / UXR7 问题 3（根本性根因）：从知识库工具 TOOL 结果解析引用来源。
        // UXR6 只覆盖 knowledge_base__search（`[来源N] 文件=X` 格式），但真机日志证明 LLM
        // 主要用 knowledge_base__get_document_content（读全文，`【知识库文档：X】` 格式）——
        // 这些读取的文档此前不进 sources，导致「LLM 引用多篇但引用来源只标第一篇」。
        // 修复：过滤覆盖 search + get_document_content，统一解析合并到 AI 消息 sources。
        val kbToolNames = setOf(
            KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
            KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT
        )
        val kbCitations = newMsgs
            .filter { it.role == Role.TOOL && it.toolName in kbToolNames }
            .flatMap { Companion.parseKnowledgeBaseCitations(it.content) }
            .distinctBy { it.documentTitle }
        // UXR7-R2 问题 3（引用池，网络调研业界最推荐方案）：工具调用参数反向映射。
        // 仅解析 TOOL 文本依赖 LLM 输出的格式恰好可识别；若 LLM 读了文档但正文/工具结果
        // 格式变化，引用会丢失。业界（ChatPDF-Pro "最终引用只能来自本轮工具实际返回的
        // 证据"、Microsoft Teams FunctionMiddleware 拦截 tool result 分配稳定索引）最推荐
        // **在 agent 循环里拦截工具调用，把实际调用的文档参数收进引用池**。
        // 实现：从 assistant 占位消息的 toolCalls 反查 get_document_content 的 documentTitle
        // 参数（不依赖工具返回文本格式），与 TOOL 文本解析结果合并去重。
        // MED-01（guardrail TKN-UXR7R2-GUARDRAIL-001）：仅收录**成功读取**的调用
        //（按 toolCallId 关联 TOOL 结果，文档不存在/读取失败不产生 `【知识库文档：】` 标记，
        // 避免把实际未读到的文档计入引用池产生"假引用"）。
        val successKbReadIds = Companion.successfulKbReadToolCallIds(newMsgs)
        val argCitations = newMsgs
            .filter { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            .flatMap { msg ->
                msg.toolCalls
                    .filter { it.id in successKbReadIds }
                    .let { Companion.parseKnowledgeBaseCitationsFromToolCalls(it) }
            }
            .distinctBy { it.documentTitle }
        val mergedCitations = (kbCitations + argCitations).distinctBy { it.documentTitle }
        _messages.update { msgs ->
            // 1. 附加 searchResults 到 AI 消息（若解析出结果）
            val withSearch = if (searchResults.isNotEmpty()) {
                msgs.map { if (it.id == aiId) it.copy(searchResults = searchResults) else it }
            } else {
                msgs
            }
            // 1b. UXR6 问题 3b：合并知识库工具引用到 sources（去重，保留既有自动 RAG 引用）
            val withKbSources = if (mergedCitations.isNotEmpty()) {
                withSearch.map { msg ->
                    if (msg.id == aiId) {
                        msg.copy(sources = (msg.sources + mergedCitations).distinctBy { it.documentTitle })
                    } else {
                        msg
                    }
                }
            } else {
                withSearch
            }
            // 2. 插入新增消息（assistant 占位 + tool result）到 aiId **之前**。
            // UXR5 问题 2（ADR-024 遗留）：此前追加到末尾导致 UI 顺序 [user, aiId文本, 占位, tool]，
            // 工具调用/思考全出现在最终文本**下方**。改为按真实时序插入 aiId 前，
            // 使 _messages = [user, assistant占位, tool, aiId(最终文本)]，工具过程按调用顺序展示。
            // 同时保证协议层 filteredHistory 结构正确（tool 前是带 tool_calls 的 assistant）。
            val aiIndex = withKbSources.indexOfFirst { it.id == aiId }
            if (aiIndex >= 0) {
                val before = withKbSources.subList(0, aiIndex)
                val after = withKbSources.subList(aiIndex, withKbSources.size)
                before + newMsgs + after
            } else {
                withKbSources + newMsgs
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
        // M-02 修复（guardrail TKN-M7-GUARDRAIL-001，BR-error-handling-004）：
        // MINIMAL/CHAT_ONLY 档 ragTopK=0，NullEmbedder 返回空 FloatArray(0) 会导致下游
        // KnowledgeBaseRepository.search 的 require(query.size == 384) 抛 IllegalArgumentException。
        // 虽被外层 catch 兜住降级为 NormalChat，但异常路径成为热路径且无 Log.w 违反
        // BR-error-handling-004（不吞异常）。此处短路返回 NormalChat，避免异常路径。
        if (ragTopK <= 0) return RagBuildResult.NormalChat

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
                knowledgeBaseRepository.search(queryVector, k = ragTopK, knowledgeBaseId = kbId)
            } catch (e: CancellationException) {
                throw e  // G-01 修复：协程取消必须传播
            } catch (e: Exception) {
                // M-02 修复（BR-error-handling-004）：search 失败记录警告日志，不静默吞异常
                Log.w("ConversationViewModel", "RAG search 失败，降级为普通对话", e)
                return@withContext RagBuildResult.NormalChat  // search 失败 → 自然降级
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
            // UXR6 问题 4/5：避免 `msgs.map { ... }` 为**每条**消息新建对象（长对话 N≥50 时
            // 每 token 产生 N 次分配 → logcat 高频 "This is sticky GC"）。改为仅重建目标消息：
            // toMutableList 仅复制一次列表引用，其余消息对象复用。
            val index = msgs.indexOfFirst { it.id == aiId }
            if (index < 0) return@update msgs
            val updated = msgs.toMutableList()
            updated[index] = updated[index].copy(content = updated[index].content + delta)
            updated
        }
    }

    /**
     * UXR6 问题 2：标记指定 AI 消息为「流式生成中」（UI 渲染为纯文本，避免 markdown 中间态）。
     */
    private fun markStreaming(aiId: Long) {
        _streamingIds.update { it + aiId }
    }

    /**
     * UXR6 问题 2：标记指定 AI 消息为「生成完成」（UI 切换 Markdown 完整渲染）。
     */
    private fun markCompleted(aiId: Long) {
        _streamingIds.update { it - aiId }
    }

    companion object {
        /** Logcat 标签（G-03 修复：结构化日志基建未就绪前用 android.util.Log） */
        private const val TAG = "ConversationViewModel"

        /** RAG top-k 默认值（ADR-012 5.6，4GB 低端机约束；M7 ADR-017 4.7 改为按档位动态） */
        internal const val DEFAULT_RAG_TOP_K = 3

        /** RAG 相似度阈值（ADR-012 5.6，过滤无关结果污染 context） */
        // UXR3 问题 6（ADR-023）：0.3 → 0.5。用户反馈「打开知识库检索功能后第一份资料必被塞入」，
        // 根因是阈值过低：库中仅有的片段（无论与问题是否相关）都会命中并注入上下文。
        // 提高阈值过滤低相关片段，只有足够相关的资料才进入 context 与引用来源。
        private const val RAG_SIMILARITY_THRESHOLD = 0.5

        /**
         * 默认通用 persona（ADR-018，综合 DeepSeek/Claude 最佳实践）。
         *
         * **设计原则**（渐进式加载 + Claude 5「管原则不写死规则」趋势）：
         * - 身份清晰：Prism AI 助手
         * - 诚实约束：不虚构、不确定时说明（OpenAI/DeepSeek 共同建议）
         * - 通用能力：感知可用技能，但**按需使用、不改变基础身份**（渐进式加载）
         * - 简洁克制：避免过度规则化（Anthropic 砍 80% 系统提示词的方向）
         *
         * 始终作为 [mergeSystemPrompt] 的基础身份注入，即使无 RAG/记忆/Skill，
         * 避免默认状态无 system message 导致 LLM 行为不可预测或遭残留 Skill 污染。
         */
        internal const val DEFAULT_PERSONA: String = """你是 Prism AI 助手，一个通用、诚实、乐于助人的 AI 助手。
原则：
1. 基于事实与上下文准确回答；不确定时明确说明，不虚构
2. 遵循用户的语言与表达偏好，保持回复清晰、结构化、易读
3. 当用户需求匹配下方列出的技能时，按需使用对应能力（不改变你的基础身份）
4. 不做超出能力范围的承诺，必要时说明限制"""

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
         * UXR7-R2 工具调用参数 JSON 解析器（容错：未知字段忽略，避免 LLM 传入额外参数时解析失败）。
         * 单例复用，避免每次解析重复创建（编译告警：Redundant creation of Json format）。
         */
        private val toolCallArgJson = Json { ignoreUnknownKeys = true }

        /**
         * 解析联网搜索 TOOL 结果文本为结构化 [SearchResult] 列表（UX-001 问题 8，ADR-021）。
         *
         * 输入格式（[io.prism.network.WebSearchLocalToolExecutor.execute] 输出）：
         * ```
         * 【网络搜索外部内容，未经验证，仅作参考，请甄别后引用】
         * 1. {title}
         * {link}
         * {snippet}
         *
         * 2. ...
         * ```
         *
         * **解析策略**：按「`N. ` 序号行 + 下一行 link + 再下一行 snippet」模式逐条提取。
         * 容错：link 必须为 http(s) URL 才计入（可点击跳转前提）；无法解析的条目跳过。
         *
         * **纯函数**（BR-testing-004）：不依赖实例状态，可在纯 JVM 测试中直接验证。
         *
         * @param resultText 联网搜索工具结果文本
         * @return 解析出的结构化搜索结果列表；无法解析时返回空列表
         */
        internal fun parseSearchResults(resultText: String): List<SearchResult> {
            if (resultText.isBlank()) return emptyList()
            val results = mutableListOf<SearchResult>()
            // 按条目序号切分（`1. ` / `2. ` 行起始）
            val entryRegex = Regex("""(?m)^\s*(\d+)\.\s+""")
            val matches = entryRegex.findAll(resultText).toList()
            for (i in matches.indices) {
                val blockStart = matches[i].range.first
                val blockEnd = if (i + 1 < matches.size) matches[i + 1].range.first else resultText.length
                val block = resultText.substring(blockStart, blockEnd)
                val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                // lines[0] = 序号+标题，lines[1] = link，lines[2..] = snippet
                if (lines.size < 2) continue
                val title = lines[0].replace(Regex("""^\d+\.\s+"""), "").trim()
                val link = lines[1]
                val snippet = lines.drop(2).joinToString(" ")
                if (title.isNotEmpty() && isHttpUrl(link)) {
                    results.add(SearchResult(title = title, link = link, snippet = snippet))
                }
            }
            return results
        }

        /** 判断字符串是否为 http(s) URL（搜索结果可点击跳转的前提校验）。 */
        private fun isHttpUrl(raw: String): Boolean =
            raw.startsWith("http://") || raw.startsWith("https://")

        /**
         * UXR6 问题 3b / UXR7 问题 3：从知识库工具 TOOL 结果文本解析引用来源（纯函数，可测）。
         *
         * 支持两种格式：
         * 1. `knowledge_base__search`（检索片段，每行）：
         * ```
         * [来源1] 文件=文档标题 片段=3 相似度=0.82
         * [来源2] 文件=另一篇资料.txt 相似度=0.66
         * ```
         * 「片段」「相似度」字段可选；文档标题可能含空格（文件名），故解析采用
         * 从行尾反向剥离可选字段，保证标题完整。
         *
         * 2. `knowledge_base__get_document_content`（读全文，UXR7 问题 3 新覆盖）：
         * ```
         * 【知识库文档：文档标题】
         * content...
         * 【END】
         * ```
         * 读全文的文档也计入引用来源（真机日志证明 LLM 主要用此工具读取多篇文档，
         * 此前不进 sources 导致「引用多篇只标第一篇」）。
         *
         * @param content TOOL 消息内容
         * @return 解析出的 [Citation] 列表（index/documentTitle/chunkIndex/similarity）
         */
        internal fun parseKnowledgeBaseCitations(content: String): List<Citation> {
            if (content.isBlank()) return emptyList()
            val citations = mutableListOf<Citation>()
            // 格式 1：search 片段行 `[来源N] 文件=X ...`
            val markerRegex = Regex("""\[来源(\d+)\]\s*文件=(.+)""")
            val simRegex = Regex("""(.*?)\s+相似度=([\d.]+)\s*$""")
            val chunkRegex = Regex("""(.*?)\s+片段=(\d+)\s*$""")
            content.lineSequence().forEach { line ->
                val m = markerRegex.find(line)
                if (m != null) {
                    val index = m.groupValues[1].toIntOrNull()
                    if (index != null) {
                        var rest = m.groupValues[2].trim()
                        var chunk: Int? = null
                        var sim = 0.0
                        val simMatch = simRegex.find(rest)
                        if (simMatch != null) {
                            rest = simMatch.groupValues[1].trim()
                            sim = simMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                        }
                        val chunkMatch = chunkRegex.find(rest)
                        if (chunkMatch != null) {
                            rest = chunkMatch.groupValues[1].trim()
                            chunk = chunkMatch.groupValues[2].toIntOrNull()
                        }
                        val title = rest.takeIf { it.isNotBlank() }
                        if (title != null) {
                            citations.add(
                                Citation(index = index, documentTitle = title, chunkIndex = chunk, similarity = sim)
                            )
                        }
                    }
                }
            }
            // 格式 2：get_document_content 的 `【知识库文档：X】` 标记（UXR7 问题 3）
            // 行首 `【知识库文档：` 到行尾 `】` 之间为文档标题；index 按文档出现顺序自增
            val docRegex = Regex("""【知识库文档：([^】]+)】""")
            var docIndex = citations.size + 1
            docRegex.findAll(content).forEach { match ->
                val title = match.groupValues[1].trim()
                if (title.isNotEmpty() && citations.none { it.documentTitle == title }) {
                    citations.add(Citation(index = docIndex, documentTitle = title))
                    docIndex++
                }
            }
            return citations
        }

        /**
         * UXR7-R2（MED-01 修复，guardrail TKN-UXR7R2-GUARDRAIL-001）：提取"知识库文档**成功读取**"
         * 的 toolCallId 集合（纯函数，可测）。
         *
         * **为何需要**：[parseKnowledgeBaseCitationsFromToolCalls] 从 assistant 占位消息的 toolCalls
         * 反查 documentTitle，但 assistant 占位是全量回放（含失败调用）。若文档不存在，
         * `get_document_content` 返回 "知识库中未找到文档「$title」"（无 `【知识库文档：】` 标记），
         * 直接反查会把**实际未读到**的文档计入引用池（假引用）。
         *
         * **判据**：TOOL 消息 `toolName == get_document_content` 且 content **含 `【知识库文档：】`
         * 标记**（仅成功读取才输出该标记）。调用方按返回的 toolCallId 集合过滤 toolCalls，
         * 仅对成功读取的调用提取 documentTitle。
         *
         * @param messages 消息列表（含 assistant 占位 + TOOL 结果）
         * @return 成功读取文档的 get_document_content 调用的 toolCallId 集合
         */
        internal fun successfulKbReadToolCallIds(messages: List<ChatMessage>): Set<String> =
            messages
                .filter {
                    it.role == Role.TOOL &&
                        it.toolName == KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT &&
                        it.content.contains(KB_DOCUMENT_MARKER)
                }
                .mapNotNull { it.toolCallId }
                .toSet()

        /** UXR7-R2：get_document_content 成功返回时输出的文档标记前缀（与工具实现对齐）。 */
        private const val KB_DOCUMENT_MARKER = "【知识库文档："

        /**
         * UXR7-R2 问题 3（引用池，网络调研业界最推荐）：从 assistant 占位消息的 toolCalls
         * **参数**反向解析知识库引用（纯函数，可测）。
         *
         * 与 [parseKnowledgeBaseCitations]（解析 TOOL 返回文本）互补：本函数不依赖 LLM 输出
         * 的格式是否可识别，只要 LLM **实际调用了** `knowledge_base__get_document_content`
         * 并传入 `documentTitle`，即可把该文档计入引用来源。
         *
         * **为何需要**：业界最推荐"运行时工具调用反向映射"（ChatPDF-Pro "最终引用只能来自
         * 本轮工具实际返回的证据"、Microsoft Teams FunctionMiddleware 拦截 tool result 分配
         * 稳定索引、AgenticRAG search/find/open 用 reference id 贯穿）。仅解析 TOOL 文本在
         * 格式变化（如文档读取失败返回"未找到文档"，或 LLM 未把标题写进正文）时引用会丢。
         *
         * **安全边界**：仅解析 `knowledge_base__get_document_content` 工具的 documentTitle
         * 参数（白名单工具 + 白名单字段），不从任意文本提取（避免误把其他工具参数当引用）。
         * 参数是 LLM 生成的 JSON string（[ToolCallRef.arguments]），用 [Json] 容错解析，
         * 解析失败/字段缺失的调用跳过（不抛异常）。
         *
         * **MED-01（guardrail TKN-UXR7R2-GUARDRAIL-001）**：调用方必须先用
         * [successfulKbReadToolCallIds] 过滤**成功读取**的调用，本函数仅负责从参数提取标题，
         * 不判断工具是否成功（避免假引用——把实际未读到的文档计入引用池）。
         *
         * @param toolCalls assistant 消息携带的 tool_calls 引用列表
         * @return 解析出的 [Citation] 列表（index 按出现顺序自增）
         */
        internal fun parseKnowledgeBaseCitationsFromToolCalls(
            toolCalls: List<ToolCallRef>
        ): List<Citation> {
            if (toolCalls.isEmpty()) return emptyList()
            val citations = mutableListOf<Citation>()
            var index = 0
            toolCalls.forEach { call ->
                if (call.functionName != KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT) {
                    return@forEach
                }
                val title = parseToolCallDocumentTitle(call.arguments) ?: return@forEach
                index++
                citations.add(Citation(index = index, documentTitle = title))
            }
            return citations
        }

        /** 从 `get_document_content` 的 arguments JSON 中提取 `documentTitle`（容错，null 表示缺失）。 */
        internal fun parseToolCallDocumentTitle(arguments: String): String? {
            if (arguments.isBlank()) return null
            return try {
                val obj = toolCallArgJson.parseToJsonElement(arguments).jsonObject
                // DEF-001（ac-verifier TKN-UXR7R2-ACCEPTANCE-001）：`{"documentTitle": null}` 时
                // JsonNull.content == "null"（字面量），若直接取 content 会产生假引用 "null"。
                // 显式校验值非 null 且 trim 后非空；"null" 字面量也应拒绝（视为缺失）。
                val raw = obj["documentTitle"]
                if (raw == null || raw is kotlinx.serialization.json.JsonNull) return null
                raw.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() && it != "null" }
            } catch (e: Exception) {
                // 参数 JSON 解析失败（非标准 JSON）→ 无法提取，跳过该调用
                null
            }
        }

        /**
         * 校验工具名是否符合 OpenAI/DeepSeek 规范（UX-001 问题 5，ADR-022）。
         *
         * OpenAI 工具名仅允许 `[a-zA-Z0-9_-]` 字符（不含空格/中文/特殊字符）。
         * server.name 含空格（如 "Sequential Thinking"）或中文时，`mcp_<serverName>__<tool>`
         * 会含非法字符，即使不重名也可能被 API 拒绝。发送前过滤非法工具名（防御性，避免请求 400）。
         *
         * @param name 工具名
         * @return 是否合法（非空 + 仅 [a-zA-Z0-9_-]）
         */
        internal fun isLegalToolName(name: String): Boolean =
            name.isNotEmpty() && LEGAL_TOOL_NAME_PATTERN.matches(name)

        /** OpenAI 工具名合法字符模式（[a-zA-Z0-9_-]）。 */
        internal val LEGAL_TOOL_NAME_PATTERN = Regex("""^[a-zA-Z0-9_-]+$""")

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
         * UXR5 问题 4（tool_calls 完整性保护，纯函数可测）：丢弃无前置 tool_calls 的孤儿 TOOL 消息。
         *
         * 协议要求 role=tool 消息必须是前置 role=assistant 消息（携带 tool_calls）的响应。
         * 会话恢复/旧数据可能丢失 assistant 占位的 toolCalls 字段（[io.prism.util.ChatMessageSerializer]
         * `ignoreUnknownKeys=true` 反序列化），导致 history 中出现孤儿 TOOL → DeepSeek 400
         * "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
         *
         * **规则（F-01 修复：计数器而非布尔，支持并行工具调用）**：遍历 history，
         * 维护"待配对的 tool_calls 数量"：
         * - 遇 assistant 且 toolCalls 非空 → 待配对数量 = toolCalls.size（一轮可含多个并行工具）
         * - 遇 TOOL 且待配对 > 0 → 保留并递减（配对成功一个工具结果）
         * - 遇 TOOL 且待配对 = 0 → 丢弃（孤儿，防御 400）
         * - 遇 user 消息 → 重置待配对数量（新一轮对话边界）
         *
         * @param msgs 完整历史
         * @return 过滤掉孤儿 TOOL 后的消息列表
         */
        internal fun dropOrphanToolMessages(msgs: List<ChatMessage>): List<ChatMessage> {
            // 计数器而非布尔：支持一轮内多个工具并行调用（assistant(toolCalls=[c1,c2]) → tool(c1) → tool(c2)）。
            // 参见 SkillExecutor 并行工具调用（SkillExecutorTest 并行用例验证）。
            var pendingToolCalls = 0
            return msgs.filter { msg ->
                when (msg.role) {
                    Role.ASSISTANT -> {
                        if (msg.toolCalls.isNotEmpty()) pendingToolCalls = msg.toolCalls.size
                        true
                    }
                    Role.TOOL -> {
                        if (pendingToolCalls > 0) {
                            pendingToolCalls--
                            true
                        } else {
                            false // 孤儿 TOOL（无前置 tool_calls），丢弃
                        }
                    }
                    Role.USER -> {
                        pendingToolCalls = 0 // 新对话边界，重置
                        true
                    }
                }
            }
        }

        /**
         * 从已启用的 Skill 列表构建 [ToolDefinition]（M4 Phase D，命名空间隔离）。
         *
         * **命名空间隔离**（ADR-014 5.5）：tool name 格式 `skillName__toolName`，
         * 避免跨 Skill 同名工具冲突；执行时由 [SkillExecutor.stripNamespace] 剥离前缀。
         *
         * **M6 Phase C 扩展**（ADR-016 5.4）：合并跨 App 本地工具（`cross_app__` 命名空间），
         * 由 [CrossAppLocalToolExecutor.buildToolDefinitions] 静态生成 3 个工具定义
         * （open_app / share_content / pick_media）。crossAppLauncher 为 null 时跳过（向后兼容）。
         *
         * **问题 8b 扩展**（ADR-020）：合并联网搜索工具（`web_search__search`），
         * 由 [WebSearchLocalToolExecutor.buildToolDefinition] 静态生成。webSearchEnabled 为
         * true 时追加；false（默认，向后兼容既有测试）时跳过（LLM 无法感知联网能力）。
         *
         * **纯函数**（US-026 可测性，BR-testing-004 模式）：不依赖实例状态，
         * 可在纯 JVM 测试中直接验证，无需 Android Context 或真实 SkillRegistry。
         *
         * @param enabledSkills 已启用的 Skill 列表（来自 [SkillRegistry.enabledSkills]）
         * @param crossAppLauncher 跨 App 调用核心入口（M6 Phase C，可空：null 时不合并跨 App 工具）
         * @param webSearchEnabled 是否启用联网搜索工具（问题 8b，默认 false 向后兼容）
         * @return 工具定义列表；无 tools 声明的 Skill 贡献 0 项；crossAppLauncher 非 null 时
         *         追加 3 项跨 App 工具；webSearchEnabled 为 true 时追加 1 项联网搜索工具
         */
        internal fun buildTools(
            enabledSkills: List<SkillRegistry.SkillEntry>,
            crossAppLauncher: CrossAppLauncher? = null,
            webSearchEnabled: Boolean = false
        ): List<ToolDefinition> {
            val skillTools = enabledSkills.flatMap { entry ->
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
            val crossAppTools = crossAppLauncher?.let {
                CrossAppLocalToolExecutor.buildToolDefinitions(it)
            } ?: emptyList()
            val webSearchTools = if (webSearchEnabled) {
                listOf(WebSearchLocalToolExecutor.buildToolDefinition())
            } else {
                emptyList()
            }
            return skillTools + crossAppTools + webSearchTools
        }

        /**
         * 合并多层 system prompt（M4 Phase D R-6 膨胀控制 + M5 Phase E ADR-015 决策4 六层合并 + ADR-018 渐进式加载）。
         *
         * **合并顺序**（ADR-015 决策4 + ADR-018）：
         * 0. [DEFAULT_PERSONA] 默认 persona（始终作为基础身份，ADR-018）
         * 1. RAG grounding rules（防幻觉约束）
         * 2. L1 早期对话摘要（[SlidingWindowResult.toSummarySystemPromptSection]，格式 `[早期对话摘要] ...`）
         * 3. L2 跨会话记忆（[CrossSessionMemoryManager.formatMemoriesAsContext]，格式 `相关历史对话：...`）
         * 4. L3 用户画像（[UserProfileManager.formatProfilesAsContext]，格式 `用户偏好：...`）
         * 5. Skill 轻量索引（`name（description）`，ADR-018：**不注入完整 systemPrompt**，避免身份污染）
         *
         * **ADR-018 修复**（P3 提示词污染）：启用 Skill 不再注入完整 systemPrompt
         * （如"你是文本改写助手"），避免 LLM 被强制角色污染；改为注入轻量索引，
         * 让 LLM 感知可用能力、按需使用（渐进式加载，Claude 5 上下文工程新法则）。
         *
         * **降级策略**：L1/L2/L3 为 null/空时跳过对应层（向后兼容无记忆场景）。
         *
         * **纯函数**（US-026 / US-035 可测性，BR-testing-004 模式）。l1Summary/l2Memories/l3Profiles
         * 带默认值 null，向后兼容 M4 Phase D 既有测试（仅传 ragPrompt + enabledSkills）。
         *
         * @param ragPrompt RAG grounding rules（可能为 null：RAG 关闭或降级）
         * @param l1Summary L1 早期对话摘要 section（M5 Phase E，默认 null 向后兼容）
         * @param l2Memories L2 跨会话记忆 section（M5 Phase E，默认 null 向后兼容）
         * @param l3Profiles L3 用户画像 section（M5 Phase E，默认 null 向后兼容）
         * @param enabledSkills 已启用的 Skill 列表（已过滤 isEnabled && isInstalled）
         * @return 合并后的 systemPrompt；**始终非空**（至少含 [DEFAULT_PERSONA]）
         */
        internal fun mergeSystemPrompt(
            ragPrompt: String?,
            l1Summary: String? = null,
            l2Memories: String? = null,
            l3Profiles: String? = null,
            enabledSkills: List<SkillRegistry.SkillEntry>
        ): String {
            val hasRag = !ragPrompt.isNullOrBlank()
            val hasL1 = !l1Summary.isNullOrBlank()
            val hasL2 = !l2Memories.isNullOrBlank()
            val hasL3 = !l3Profiles.isNullOrBlank()
            // ADR-018：轻量 Skill 索引（name + description），不注入完整 systemPrompt。
            // description 缺失时回退为 name（仍可感知能力存在）。
            val skillIndex = enabledSkills.mapNotNull { entry ->
                val name = entry.config.name
                val desc = entry.manifest.description.takeIf { it.isNotBlank() }
                desc?.let { "$name（$it）" } ?: name
            }

            return buildString {
                // ADR-018：默认 persona 始终作为基础身份
                append(DEFAULT_PERSONA)
                // ADR-015 决策4 合并顺序：RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill 索引
                if (hasRag) {
                    append("\n\n")
                    append(ragPrompt)
                }
                if (hasL1) {
                    append("\n\n")
                    append(l1Summary)
                }
                if (hasL2) {
                    append("\n\n")
                    append(l2Memories)
                }
                if (hasL3) {
                    append("\n\n")
                    append(l3Profiles)
                }
                if (skillIndex.isNotEmpty()) {
                    append("\n\n")
                    // DEF-007（Bug-5 次根因）：强化措辞 —— 用户明确点名技能名/能力时必须执行，
                    // 否则按需使用、不改变基础身份。降低 LLM 忽略索引的概率。
                    append("可用技能（用户明确提到技能名或对应能力时必须按对应规则执行；否则按需使用，不改变你的基础身份）：")
                    append(skillIndex.joinToString("、"))
                }
            }.trim()
        }

        /**
         * 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。
         *
         * M5 Phase E（US-035）：注入三层记忆组件 + [PrismApplication.appScope]
         * （用于 [onCleared] 中 fire-and-forget 记忆持久化）。
         *
         * M6 Phase C（US-039）：注入跨 App 调用组件
         * （[PrismApplication.confirmationGate] / [PrismApplication.appLauncherBridge] / [PrismApplication.crossAppLauncher]），
         * 供 ConversationScreen 收集 SharedFlow 流并展示确认对话框 + 注册 ActivityResult launcher。
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                // M7 设备适配（ADR-017 4.6）：按档位降级传参
                // - MINIMAL/CHAT_ONLY 档：crossSessionMemoryManager 传 null（L2 禁用，依赖 embedder）
                // - ragTopK 按档位动态传入（FULL=5, STANDARD=3, MINIMAL/CHAT_ONLY=0）
                val tier = app.tierManager.currentTier
                ConversationViewModel(
                    providerRepository = app.providerConfigRepository,
                    provider = app.openAICompatibleProvider,
                    embedder = app.embedder,
                    knowledgeBaseRepository = app.knowledgeBaseRepository,
                    skillRegistry = app.skillRegistry,
                    skillExecutor = app.skillExecutor,
                    mcpServerRepository = app.mcpServerRepository,
                    slidingWindowMemoryManager = app.slidingWindowMemoryManager,
                    crossSessionMemoryManager = if (tier.isMemoryL2Enabled) app.crossSessionMemoryManager else null,
                    userProfileManager = app.userProfileManager,
                    applicationScope = app.appScope,
                    confirmationGate = app.confirmationGate,
                    appLauncherBridge = app.appLauncherBridge,
                    crossAppLauncher = app.crossAppLauncher,
                    mcpToolProvider = app.mcpToolProviderDispatcher,
                    ragTopK = tier.ragTopK,
                    // 问题 8（ADR-020）：深度思考配置 + 联网搜索工具（默认启用）
                    thinkingConfigRepository = app.thinkingConfigRepository,
                    webSearchEnabled = true,
                    // UX-001 问题 4（ADR-021）：会话历史仓库（会话持久化 / 历史恢复）
                    sessionRepository = app.sessionRepository,
                    // UXR3 问题 10（ADR-023）：工具审批模式（DISABLED 时不再注入工具）
                    toolApprovalConfigRepository = app.toolApprovalConfigRepository
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
