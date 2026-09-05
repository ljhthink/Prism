package io.prism.skill

import android.util.Log
import io.prism.config.ToolApprovalMode
import io.prism.data.ExecutionStatus
import io.prism.data.McpServerConfig
import io.prism.data.ProviderConfig
import io.prism.data.SkillExecutionRecord
import io.prism.data.SkillExecutionRepository
import io.prism.data.ToolCallRecord
import io.prism.fs.ToolConfirmationGate
import io.prism.network.ChatStreamProvider
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.ToolCallRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Skill 工具执行器（US-025，ADR-014 5.4）。
 *
 * 编排「LLM 调工具 → 用户确认 → MCP 调用 → 结果回灌 → 再调 LLM」回路，
 * 核心职责：
 * 1. [executeToolCall]：执行单个 tool_call（用户确认 + MCP 调用 + 超时防护 + 命名空间隔离）
 * 2. [executeLoop]：编排多轮工具调用回路（maxRounds 防护 + assistant 占位消息 + tool result 回灌）
 *
 * **设计修正**（per phaseC 考古报告 R5，不照搬 ADR-014 5.4 伪代码）：
 * - 依赖接口 [McpToolProvider] 而非具体类 McpToolProviderDispatcher
 * - [ToolConfirmationGate.confirm] 返回 Boolean 非 Deferred，直接调用
 * - [McpToolProvider.callTool] 返回 String 非 `.content` 对象
 * - 不依赖 SkillRepository/SkillRegistry（per R8：单一职责，`tools` + `mcpServers` 由调用方传入）
 *
 * **安全边界**（ADR-014 5.5）：
 * - 用户确认：每个 tool_call 执行前必须通过 [ToolConfirmationGate]
 * - 超时防护：[withTimeout] 包装 callTool（默认 30s）
 * - 循环防护：maxRounds=10 强制终止
 * - 失败降级：错误/超时/拒绝信息回灌给 LLM（让 LLM 决定降级，ADR-014 5.7）
 * - 协程取消：CancellationException 重抛（BR-error-handling-007）
 * - 命名空间隔离：tool name 格式 `skillName__toolName`，执行时去前缀
 *
 * **可测性**（BR-testing-004）：纯逻辑提取到 companion object internal 函数，
 * 单元测试直接覆盖命名空间剥离、MCP Server 选择、消息构造、循环判定、错误格式化、
 * 参数序列化，不依赖 Android Context 或真实 MCP Server。集成路径用 fake
 * McpToolProvider + fake ToolConfirmationGate + fake ChatStreamProvider 验证。
 *
 * **M4 Phase D 可测性补强**：`class` 标记 `open` + [executeLoop] 标记 `open`，
 * 使 ConversationViewModel 集成测试可注入 fake 子类（覆写 [executeLoop] 返回 canned
 * 消息序列），无需 McpToolProvider/ToolConfirmationGate/真实 SkillExecutor 协作。
 *
 * **M4 Phase E US-029 执行可观测**：构造器新增可选 [skillExecutionRepository]，
 * 非空时 [executeLoop] 自动记录 [SkillExecutionRecord]（startedAt/finishedAt/status/
 * toolCalls/errorMessage），用于跨会话审计与 UI 详情页展示。为 null 时跳过记录（向后兼容）。
 * [executeLoop] 新增可选 [skillConfigId] / [skillName] 参数，两者均非空且 repository 非空时才记录。
 *
 * **M6 Phase B 本地工具分支**（ADR-016）：构造器新增可选 [localToolExecutor]，
 * 非空时 [executeToolCall] 在用户确认后先查询 [LocalToolExecutor.handles]：
 * 若返回 true 走本地执行路径（[LocalToolExecutor.execute]），否则走 MCP 路径。
 * 为 null 时行为与 M4 完全一致（仅走 MCP 路径，向后兼容）。
 * 本地工具（如 `cross_app__open_app`）不走 MCP 协议，由 [CrossAppLocalToolExecutor]
 * 直接调用 Android 原生 API（Intent + ActivityResult）。
 */
open class SkillExecutor(
    private val mcpToolProvider: McpToolProvider,
    private val confirmationGate: ToolConfirmationGate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val skillExecutionRepository: SkillExecutionRepository? = null,
    private val localToolExecutor: LocalToolExecutor? = null,
    /** UXR3 问题 10（ADR-023）：工具审批模式提供者（null 时降级为 [ToolApprovalMode.MANUAL]，向后兼容既有测试）。 */
    private val approvalModeProvider: (suspend () -> ToolApprovalMode)? = null,
    /**
     * UXR11 U2（ADR-033）：工具回路轮间最小间隔（毫秒，默认 0 = 不等待）。
     *
     * **生产默认由 [io.prism.PrismApplication] 显式注入 [TOOL_LOOP_INTER_ROUND_DELAY_MS]（2s）**：
     * 把工具回路的多轮 LLM 请求摊开，降低瞬时 RPM 峰值触发 429 概率。构造器默认 0 是为
     * 了既有单测（58 处直接构造）不因真实延时拖慢；生产路径唯一构造点已注入 2s。
     */
    private val interRoundDelayMs: Long = 0L,
    /**
     * v1 批次11（E，D11）：429 限流退避基础时长（毫秒）。默认 [RATE_LIMIT_BACKOFF_MS]（3s）。
     *
     * 测试注入小值避免真实指数退避拖慢单测（重试耗尽路径 6 次最长达 189s）；生产由
     * 默认值生效（调用方无需传参）。
     */
    private val rateLimitBackoffMs: Long = RATE_LIMIT_BACKOFF_MS
) {

    /**
     * 执行单个 tool_call：用户确认 → （本地工具 | MCP 调用） → 返回结果字符串。
     *
     * **降级策略**（ADR-014 5.7）：所有失败场景返回描述性字符串（而非抛异常），
     * 由调用方作为 tool result 回灌给 LLM，让 LLM 决定如何降级。
     *
     * **流程**（M6 Phase B 新增本地工具分支）：
     * 1. [ToolConfirmationGate.confirm] 请求用户确认（超时由 UiConfirmationGate 内部 30s 兜底）
     * 2. **M6 新增**：若 [localToolExecutor] 非空且 [LocalToolExecutor.handles] 返回 true，
     *    走本地执行路径（[LocalToolExecutor.execute]），跳过 MCP
     * 3. 否则走 MCP 路径：[selectMcpServer] → [stripNamespace] → [withTimeout] 包装
     *    [McpToolProvider.callTool]（默认 30s）
     *
     * @param toolCall LLM 返回的完整 tool_call（含 id/name/arguments）
     * @param mcpServers 可用 MCP Server 列表（按优先级，取第一个 enabled）
     * @param maxTimeoutMs 单次工具调用超时（默认 30s，对齐 UiConfirmationGate）
     * @return 工具执行结果文本（成功结果 / 失败描述，均回灌给 LLM）
     */
    suspend fun executeToolCall(
        toolCall: StreamEvent.ToolCallComplete,
        mcpServers: List<McpServerConfig>,
        maxTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS
    ): String = withContext(ioDispatcher) {
        // 1. 用户确认（复用 ToolConfirmationGate，超时兜底由 UiConfirmationGate 内部处理）
        // UX-001 问题 9（ADR-021）：低风险工具走白名单免审批（[isTrustedTool]），
        // 避免「联网搜索」等高频率只读操作造成确认弹窗轰炸，影响使用体验。
        // UXR3 问题 10（ADR-023）：按审批模式分派 ——
        // - MANUAL（默认）：非白名单工具每次调用询问用户；白名单只读工具免审批
        // - AUTO：所有工具直接放行（不询问用户）
        // - DISABLED：工具调用被禁用，直接拒绝（纵深防御，即使 LLM 硬编码调用）
        val mode = approvalModeProvider?.invoke() ?: ToolApprovalMode.MANUAL
        when (mode) {
            ToolApprovalMode.DISABLED -> return@withContext formatDisabled(toolCall.toolName)
            ToolApprovalMode.AUTO -> { /* 直接放行，跳过确认 */ }
            ToolApprovalMode.MANUAL -> {
                val approved = if (isTrustedTool(toolCall.toolName)) {
                    true
                } else {
                    try {
                        confirmationGate.confirm(toolCall.toolName, toolCall.arguments)
                    } catch (e: CancellationException) {
                        throw e // BR-error-handling-007：协程取消必须重抛
                    } catch (e: Exception) {
                        // M4：结构化日志（BR-error-handling-004），便于生产环境定位用户确认失败根因
                        Log.w(TAG, "tool confirm failed: ${toolCall.toolName}", e)
                        return@withContext formatConfirmError(toolCall.toolName, e)
                    }
                }
                if (!approved) return@withContext formatRejection(toolCall.toolName)
            }
        }

        // 2. M6 新增：本地工具分支（LocalToolExecutor 路径，ADR-016）
        //    本地工具（如 cross_app__open_app）不走 MCP 协议，由 LocalToolExecutor 直接执行
        if (localToolExecutor != null && localToolExecutor.handles(toolCall.toolName)) {
            return@withContext try {
                withTimeout(maxTimeoutMs) {
                    localToolExecutor.execute(toolCall.toolName, toolCall.arguments)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "local tool timeout: ${toolCall.toolName} (${maxTimeoutMs}ms)")
                formatTimeout(toolCall.toolName, maxTimeoutMs)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007
            } catch (e: Exception) {
                Log.w(TAG, "local tool execution failed: ${toolCall.toolName}", e)
                formatToolError(toolCall.toolName, e)
            }
        }

        // 3. MCP 工具路径（原有逻辑不变，DEF-008 支持按工具名路由到正确 Server）
        val mcpServer = selectMcpServer(mcpServers, toolCall.toolName)
            ?: return@withContext formatNoServer(toolCall.toolName)

        // 4. 调用工具（超时防护 + 命名空间剥离）
        // v1 批次19：剥离与路由同源——按选中 Server 的前缀精确剥离（含下划线/空格/中文名
        // 的 Server 名在旧 substringAfter("__") 反解下会剥出错误工具名）
        val physicalName = stripNamespace(toolCall.toolName, mcpServer.name)
        try {
            withTimeout(maxTimeoutMs) {
                mcpToolProvider.callTool(mcpServer, physicalName, toolCall.arguments)
            }
        } catch (e: TimeoutCancellationException) {
            // M4：超时也记录日志（便于运维定位慢工具）
            Log.w(TAG, "tool timeout: ${toolCall.toolName} (${maxTimeoutMs}ms)")
            formatTimeout(toolCall.toolName, maxTimeoutMs)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            // M4：结构化日志（BR-error-handling-004），便于生产环境定位工具执行失败根因
            Log.w(TAG, "tool execution failed: ${toolCall.toolName}", e)
            formatToolError(toolCall.toolName, e)
        }
    }

    /**
     * 编排完整工具执行回路（maxRounds 防护）。
     *
     * **回路流程**（ADR-014 5.4）：
     * ```
     * rounds = 0
     * while (rounds < maxRounds):
     *   rounds++
     *   streamChat(messages, tools) → collect events → onEvent 回调
     *   if (无 ToolCallComplete): break  // 纯文本响应，回路结束
     *   追加 assistant 占位消息（携带 toolCalls 引用，OpenAI 要求下次请求回放）
     *   对每个 ToolCallComplete：executeToolCall → 追加 tool result 消息
     * if (最后一轮有工具调用 且 rounds >= maxRounds):
     *   onEvent(Error("循环达上限"))
     * ```
     *
     * **assistant 占位消息**（OpenAI 协议要求）：当 LLM 返回 tool_calls 时，
     * 必须在 messages 中追加一条 role=assistant 消息携带 tool_calls 字段，
     * 否则下次请求 LLM 无法关联 tool result 与 tool_call。content 为空字符串
     * （OpenAI 允许 assistant 空 content + tool_calls）。
     *
     * **串行执行**：当前对同一轮的多个 tool_call 串行执行（简化实现，正确性优先）。
     * 并行执行需 coroutineScope + async，推迟到性能需求出现。
     *
     * **M4 Phase D 可测性**：`open` 标记允许 ConversationViewModel 集成测试
     * 注入 fake 子类覆写本方法（返回 canned 消息序列 + 触发 onEvent），
     * 避免 McpToolProvider/ToolConfirmationGate 协作复杂度。
     *
     * @param provider 流式对话 Provider
     * @param config Provider 配置
     * @param messages 初始对话历史（调用方负责构建，含 user 消息）
     * @param systemPrompt system 消息（可选）
     * @param ragContext RAG context（可选）
     * @param tools 工具定义列表（调用方从 SkillRegistry 转换而来）
     * @param mcpServers 可用 MCP Server 列表
     * @param maxRounds 最大循环轮数（默认 10，防止无限循环）
     * @param idGenerator ChatMessage id 生成器（默认时间戳自增，可注入用于测试）
     * @param skillConfigId 关联的 SkillConfig id（US-029，非空时记录执行记录）
     * @param skillName 关联的 Skill slug（US-029，非空时记录执行记录）
     * @param thinkingEnabled 是否开启深度思考（问题 8a，ADR-020；null 不开启，透传给 provider）
     * @param reasoningEffort 思考强度（问题 8a；仅 thinkingEnabled 为 true 时透传）
     * @param onEvent 事件回调（Delta/ToolCallStart/Delta/Complete/Done/Error 全部透传给上层 UI）
     * @return 更新后的消息列表（含 assistant 占位 + tool result）
     */
    open suspend fun executeLoop(
        provider: ChatStreamProvider,
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>,
        mcpServers: List<McpServerConfig>,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        idGenerator: () -> Long = ::defaultIdGenerator,
        skillConfigId: Long? = null,
        skillName: String? = null,
        thinkingEnabled: Boolean? = null,
        reasoningEffort: String? = null,
        onEvent: (StreamEvent) -> Unit
    ): List<ChatMessage> = withContext(ioDispatcher) {
        // US-029 执行可观测：记录 startedAt / toolCalls / status / errorMessage
        val startedAt = System.currentTimeMillis()
        val toolCallRecords = mutableListOf<ToolCallRecord>()
        var finalStatus = ExecutionStatus.SUCCESS
        var errorMessage: String? = null

        try {
            // v1 批次17（真机 RCA 2026-09-03）：入口净化 tool 配对——调用方传入的 history
            // 可能经 L1 滑窗切片 / 会话恢复 / 编辑重发截断，存在「tool_calls 缺 tool 结果」
            // 或「孤儿 TOOL」→ DeepSeek 400 "insufficient tool messages"。净化后保证整条
            // 回路所有轮次请求的协议不变量（幂等，对已配对序列零变化）。
            var currentMessages = sanitizeToolCallPairing(messages, idGenerator)
            var rounds = 0
            var lastRoundHadToolCall = false
            // UXR8 N2 Phase 2（ADR-030）：反问/澄清触发标记 —— 检测到 ask_user 结果标记
            // 前缀后置 true，中断当前工具回路（用户需先答复，不能继续自动执行）。
            var askUserPending = false
            // UXR6 问题 1：重复工具熔断状态。
            // - effectiveTools：可被熔断置空（熔断后 LLM 无工具可用，只能纯文本回答）
            // - effectiveSystemPrompt：熔断时追加"不要再调用工具"提示
            // - consecutiveToolFailures：同一工具连续失败计数（键 = toolName）
            // v1 批次18（真机 RCA 2026-09-03）：熔断改为**外科式单工具禁用**——
            // 旧语义（清空全部工具）在长任务中 2 次网络抓取失败即终止整个任务（TODO 清单
            // 仍在进行中却被强制中断），且 phone_control 软失败（输入未生效）2 次即触发。
            // 新语义：连续失败达分类别阈值（resolveToolFailureThreshold）→ 仅禁用该工具，
            // 其余工具（其他搜索引擎/本地兜底）继续可用。
            var effectiveTools = tools
            val disabledTools = mutableSetOf<String>()
            var effectiveSystemPrompt: String? = systemPrompt
            val consecutiveToolFailures = mutableMapOf<String, Int>()

            // UXR11 U2（ADR-033）：429 限流自动重试剩余次数（RPM 限流在重试耗尽前不中断工具回路）。
            var rateLimitRetriesLeft = MAX_RATE_LIMIT_RETRIES
            // v1 批次11（E，D11）：本轮 429 错误携带的服务端 Retry-After 秒数（优先采纳）。
            var roundRetryAfterSeconds: Long? = null
            // v1 批次13（B/D16c，多模态降级）：本轮是否收到「模型不支持图片」400（visionUnsupported）。
            // true 时剥离瞬态截图图片 + 通知手机操控截图降级 OCR/UI 树后重试本轮（见下方处理块）。
            var visionUnsupportedSeen = false
            while (rounds < maxRounds) {
                rounds++
                // UXR11 U2（ADR-033）：工具回路轮间退避 —— LLM 端点（如 Moonshot Kimi 组织级
                // RPM=3）对连续请求限流严格，而工具回路每轮工具执行后都要重新调 LLM
                //（ADR-014 协议要求），一次深度调研（2-4 轮）会在数秒内打满 RPM 触发 429。
                // 轮间加最小间隔，把请求摊开、显著降低瞬时 RPM 峰值（真机实测第二次对话即 429）。
                // 首轮立即发（rounds==1 不等待，保首 token 延迟），后续轮次在工具结果回灌后等待。
                // CancellationException 由 delay 自动传播（BR-error-handling-007）。
                if (rounds > 1) delay(interRoundDelayMs)
                lastRoundHadToolCall = false
                // v1 批次13（M-2，guardrail TKN-V1B13-GUARDRAIL-001）：长工具链路只保留最近 1 张
                // 瞬态截图参与本轮请求——否则每轮 screenshot 的 400KB base64 在后续每一轮都携带，
                // 10 张 ≈ 4MB 请求体（触发端点 413/400 + 显著拖慢响应，与 ANR 同根因的不同形态）。
                // 手机操控上下文只需最近一张（当前屏幕状态），旧截图已是过时状态。
                val transientIndexes = currentMessages.mapIndexedNotNull { i, m ->
                    if (m.transientImage && m.imageUrl != null) i else null
                }
                if (transientIndexes.size > 1) {
                    val keepIndex = transientIndexes.last()
                    currentMessages = currentMessages.mapIndexed { i, m ->
                        if (m.transientImage && i != keepIndex && m.imageUrl != null) m.copy(imageUrl = null) else m
                    }
                }
                val completedToolCalls = mutableListOf<StreamEvent.ToolCallComplete>()
                // Q-LOW-2（guardrail TKN-UXR8-B3-GUARDRAIL-001）：本轮已实际执行的 tool_call id。
                // ask_user 中断时若本轮有未执行 tool_call，assistant 占位须裁剪为已执行子集。
                val executedToolCallIds = mutableListOf<String>()
                // UXR4 问题 1/4/6（ADR-024）：累积本轮流式响应中的 reasoning_content。
                // DeepSeek 要求携带 tool_calls 的 assistant 消息必须含 reasoning_content，
                // 否则工具回路第 2 轮请求返回 400。此处收集后传给
                // [buildAssistantToolCallMessage] 构造占位消息时回传。
                val roundReasoning = StringBuilder()
                // v1 批次12（A，D13）：累积本轮流式响应正文 content（Delta），供无原生 tool_calls 时
                // 解析文本型 <tool_call> 工具调用（glm-4.6v-flash 等文本工具型模型）。
                val roundContent = StringBuilder()
                // UXR11 U2（ADR-033）：本轮是否触发 429 限流（重试耗尽前不转发给用户）。
                var roundRateLimited = false
                // 1. 流式请求 + 收集 ToolCallComplete
                val flow: Flow<StreamEvent> = try {
                    provider.streamChat(
                        config = config,
                        messages = currentMessages,
                        systemPrompt = effectiveSystemPrompt,
                        ragContext = ragContext,
                        tools = effectiveTools,
                        toolChoice = ToolChoice.Auto,
                        // 问题 8a（ADR-020）：深度思考参数透传（null 不开启，向后兼容）
                        thinkingEnabled = thinkingEnabled,
                        reasoningEffort = reasoningEffort
                    )
                } catch (e: CancellationException) {
                    throw e // BR-error-handling-007
                } catch (e: Exception) {
                    // M4：结构化日志（BR-error-handling-004），便于定位 streamChat 初始化失败根因
                    Log.w(TAG, "streamChat init failed at round $rounds", e)
                    // Provider 构造 Flow 失败（罕见）：兜底发射 Error 并终止回路
                    // M-1 修复（guardrail TKN-M4-PHASED-GUARDRAIL-001）：sanitizeErrorMessage 脱敏 + 长度截断（CWE-209）
                    val safeMsg = sanitizeErrorMessage(e.message) ?: e.javaClass.simpleName
                    finalStatus = ExecutionStatus.FAIL
                    errorMessage = safeMsg
                    onEvent(StreamEvent.Error("流式请求初始化失败: $safeMsg"))
                    break
                }

                try {
                    flow.collect { event ->
                        // UXR11 U2（ADR-033）：429 限流错误不转发给用户（避免每次对话都弹限流提示），
                        // 交由下方自动退避重试；重试耗尽后才原样转发给用户提示稍等。
                        if (event is StreamEvent.Error && isRateLimitError(event.message)) {
                            roundRateLimited = true
                            // v1 批次11（E，D11）：优先采纳服务端 Retry-After 建议秒数（行业标准）
                            roundRetryAfterSeconds = event.retryAfterSeconds ?: roundRetryAfterSeconds
                            return@collect
                        }
                        // v1 批次13（B/D16c，多模态降级）：视觉模型端点不支持图片（400 visionUnsupported）
                        // → 标记降级（截图转 OCR/UI 树），下方剥离瞬态截图图片后重试本轮（模型以文本模式继续）。
                        if (event is StreamEvent.Error && event.visionUnsupported) {
                            visionUnsupportedSeen = true
                            localToolExecutor?.onVisionUnsupported()
                        }
                        onEvent(event)
                        if (event is StreamEvent.ReasoningDelta) {
                            // UXR4 问题 1/4/6（ADR-024）：累积 reasoning 供 assistant 占位消息回传
                            roundReasoning.append(event.content)
                        }
                        if (event is StreamEvent.Delta) {
                            // v1 批次12（A，D13）：累积正文供文本工具调用解析
                            roundContent.append(event.content)
                        }
                        if (event is StreamEvent.ToolCallComplete) {
                            completedToolCalls.add(event)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // BR-error-handling-007
                } catch (e: Exception) {
                    // M4：结构化日志（BR-error-handling-004），便于定位 Flow 收集异常根因
                    Log.w(TAG, "flow collect failed at round $rounds", e)
                    // Flow 收集异常：onEvent(Error) 通常已由 Provider 内部发射，此处兜底
                    // M-1 修复（guardrail TKN-M4-PHASED-GUARDRAIL-001）：sanitizeErrorMessage 脱敏 + 长度截断（CWE-209）
                    val safeMsg = sanitizeErrorMessage(e.message) ?: e.javaClass.simpleName
                    finalStatus = ExecutionStatus.FAIL
                    errorMessage = safeMsg
                    onEvent(StreamEvent.Error("流式请求失败: $safeMsg"))
                    break
                }

                // UXR11 U2（ADR-033）+ v1 批次11（E，D11）：429 限流自动退避重试。
                // guardrail F1 幂等守卫：仅当本轮**未执行任何工具**（completedToolCalls 为空）才重试——
                // 限流错误通常在响应头即判定（首个事件即 Error），此条件恒真；若极端场景下已收集到
                // 工具调用（限流错误在中流到达），不清空、不回退轮号，直接按正常流程回灌结果后结束
                // 回路，避免丢弃已执行结果或重复执行非幂等工具。
                // 退避策略：**优先服务端 Retry-After**（[roundRetryAfterSeconds]，行业标准）；
                // 无则指数退避 3s × 2^(N-1)（3s/6s/12s/24s…）。重试耗尽仍限流 → 落回正常逻辑
                // （completedToolCalls 为空 → 回路自然结束），补发明确文案提示用户。
                if (roundRateLimited && completedToolCalls.isEmpty() && rateLimitRetriesLeft > 0) {
                    rateLimitRetriesLeft--
                    val attemptIndex = (MAX_RATE_LIMIT_RETRIES - 1) - rateLimitRetriesLeft
                    val expBackoffMs = rateLimitBackoffMs * (1L shl attemptIndex)
                    val retryAfterMs = roundRetryAfterSeconds?.let { it * 1000L }
                    val backoffMs = maxOf(expBackoffMs, retryAfterMs ?: 0L)
                    Log.w(TAG, "round $rounds 429 rate limit, retry in ${backoffMs}ms (left=$rateLimitRetriesLeft, retryAfter=$retryAfterMs)")
                    roundReasoning.clear()
                    roundRetryAfterSeconds = null // 退避已采纳，防止下次重复放大
                    delay(backoffMs)
                    rounds-- // 重试同一轮（rounds 自增前 --，保持轮号不变）
                    continue
                }
                if (roundRateLimited) {
                    // 重试耗尽：补发限流错误事件给用户（说明已自动重试仍失败，请稍后重试）
                    Log.w(TAG, "round $rounds 429 rate limit retries exhausted (${MAX_RATE_LIMIT_RETRIES})")
                    onEvent(StreamEvent.Error("服务端持续限流（429）：已自动重试 ${MAX_RATE_LIMIT_RETRIES} 次仍失败，请稍等一两分钟后重试"))
                }

                // v1 批次13（B/D16c，多模态降级）：视觉不支持（400 visionUnsupported）处理。
                // 本轮回灌消息中含瞬态截图图片（图片注入对当前端点失败）→ 剥离其 base64 后重试本轮：
                // 模型不再收到图片（不再重复 400），转用 get_ui_state UI 树 / runScreenshot OCR 文本
                //（截图路径已被 onVisionUnsupported 降级）继续任务，而非中断。
                if (visionUnsupportedSeen && currentMessages.any { it.transientImage && it.imageUrl != null }) {
                    Log.w(TAG, "round $rounds vision unsupported: 剥离瞬态截图图片，截图转 OCR/UI 树，重试本轮")
                    currentMessages = currentMessages.map { msg ->
                        if (msg.transientImage) msg.copy(imageUrl = null) else msg
                    }
                    roundReasoning.clear()
                    roundContent.clear()
                    rounds-- // 重试同一轮（rounds 自增前 --，保持轮号不变）
                    continue
                }

                // 2. 无原生 tool_calls → 尝试文本型 <tool_call> 解析（v1 批次12，A/D13）。
                //    glm-4.6v-flash 等模型把工具调用写成文本 XML 块（常包裹 ```html 围栏内），
                //    应用此前只认原生 tool_calls → 工具从不执行（真机"连工具都用不了"）。
                //    解析到文本工具调用后：执行（复用 executeToolCall 的用户确认/手机操控安全链），
                //    结果以【工具执行结果】user 消息回灌（模型无关，不依赖 OpenAI tool 协议），继续回路。
                if (completedToolCalls.isEmpty()) {
                    val textCalls = TextToolCallParser.parse(roundContent.toString())
                    if (textCalls.isNotEmpty()) {
                        Log.i(TAG, "round $rounds 解析到 ${textCalls.size} 个文本工具调用: ${textCalls.map { it.name }}")
                        val results = StringBuilder()
                        // v1 批次13（B）：文本路径的截图图片（如有）挂到本轮回灌的 user 消息上
                        var pendingScreenshotImage: String? = null
                        textCalls.forEachIndexed { index, tc ->
                            // v1 批次18（guardrail P2-2）：已熔断禁用的文本工具被幻觉重调时，
                            // 直接回灌禁用文案（与原生路径 disabledTools 防护同语义），不再真实执行
                            if (tc.name in disabledTools) {
                                val disabledResult =
                                    "错误：工具「${tc.name}」已因连续多次调用失败被禁用。" +
                                        "请改用其他可用工具或基于已有信息回答。"
                                Log.w(TAG, "disabled text tool recall blocked: ${tc.name}")
                                results.append("[工具执行结果 ${tc.name}] ").append(disabledResult).append('\n')
                                toolCallRecords.add(
                                    ToolCallRecord(
                                        toolName = tc.name,
                                        arguments = encodeArguments(tc.arguments),
                                        result = disabledResult.take(MAX_RESULT_PREVIEW_LEN),
                                        durationMs = 0L,
                                        status = ExecutionStatus.FAIL
                                    )
                                )
                                return@forEachIndexed
                            }
                            val synthetic = StreamEvent.ToolCallComplete(
                                "textcall_${rounds}_$index",
                                tc.name,
                                tc.arguments
                            )
                            val result: String = try {
                                executeToolCall(synthetic, mcpServers)
                            } catch (e: CancellationException) {
                                throw e // BR-error-handling-007
                            } catch (e: Exception) {
                                Log.w(TAG, "text tool execution failed: ${tc.name}", e)
                                formatToolError(tc.name, e)
                            }
                            // v1 批次13（A/B）：提取截图图片标记，base64 从回灌文本剥离（防 ANR/膨胀）
                            // v1 批次17（guardrail P1-1）：结果截断与原生路径同口径——glm 等文本工具
                            // 模型调用 crawl4ai 抓大页面时 275KB 结果同样会撑爆请求体（RCA 同型根因），
                            // 且该 USER 回灌消息会持久化进会话 JSON。
                            val (rawResultText, screenshotImageUrl) = extractScreenshotImage(result, tc.name)
                            val resultText = truncateToolResult(rawResultText)
                            if (screenshotImageUrl != null) pendingScreenshotImage = screenshotImageUrl
                            // 可观测：记录 ToolCallRecord（与原生路径一致）
                            toolCallRecords.add(
                                ToolCallRecord(
                                    toolName = tc.name,
                                    arguments = encodeArguments(tc.arguments),
                                    result = resultText.take(MAX_RESULT_PREVIEW_LEN),
                                    durationMs = 0L,
                                    status = if (isFailureResult(resultText)) ExecutionStatus.FAIL else ExecutionStatus.SUCCESS
                                )
                            )
                            // 重复工具熔断计数（与原生路径一致）
                            consecutiveToolFailures[tc.name] =
                                if (isFailureResult(resultText)) (consecutiveToolFailures[tc.name] ?: 0) + 1 else 0
                            // 结果以 user 消息注入（文本工具型模型不认 tool role）；guardrail P3：
                            // 结果（可能含 fetch/搜索注入的 <tool_call> 块）须先剥离，防跨轮再解析放大
                            results.append("[工具执行结果 ${tc.name}] ")
                                .append(TextToolCallParser.stripTextToolCalls(resultText))
                                .append('\n')
                            // ask_user 接管（如手机操控强制 MANUAL）：中断回路等待用户答复
                            if (result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER)) {
                                val payload = parseAskUserPayload(
                                    result.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER)
                                )
                                if (payload != null && payload.questions.isNotEmpty()) {
                                    Log.i(TAG, "text tool ask_user triggered, stopping loop for user input")
                                    onEvent(StreamEvent.AskUser(payload.questions))
                                    askUserPending = true
                                    lastRoundHadToolCall = false
                                }
                            }
                        }
                        // 把助手正文（剥离工具块后）加入历史，保持上下文连贯；结果以 user 消息注入
                        currentMessages = currentMessages + ChatMessage(
                            id = idGenerator(),
                            role = Role.ASSISTANT,
                            content = TextToolCallParser.stripTextToolCalls(roundContent.toString()),
                            timestamp = System.currentTimeMillis()
                        )
                        currentMessages = currentMessages + ChatMessage(
                            id = idGenerator(),
                            role = Role.USER,
                            content = results.toString().trim(),
                            // v1 批次13（B）：文本路径截图图片附在 user 消息上（image_url，模型看真图）
                            imageUrl = pendingScreenshotImage,
                            // v1 批次13（F1）：瞬态截图图片标记——持久化时剥离 base64（防历史膨胀/渲染 ANR）
                            transientImage = true,
                            timestamp = System.currentTimeMillis()
                        )
                        // guardrail P2（TKN-V1B12-GUARDRAIL-001）：文本路径 continue 前补重复工具熔断
                        // 检查（与原生路径一致）——否则文本工具连续失败永不熔断，只能靠 maxRounds=50 硬顶。
                        // v1 批次18：外科式单工具禁用（与原生路径同语义，分类别阈值）。
                        val failedTextTool = consecutiveToolFailures.entries
                            .firstOrNull { it.value >= resolveToolFailureThreshold(it.key) }
                            ?.key
                        if (failedTextTool != null) {
                            val textThreshold = resolveToolFailureThreshold(failedTextTool)
                            Log.w(TAG, "text tool circuit breaker: $failedTextTool failed $textThreshold times consecutively, disabling this tool only")
                            disabledTools.add(failedTextTool)
                            effectiveTools = effectiveTools.filter { it.function.name != failedTextTool }
                            effectiveSystemPrompt = (effectiveSystemPrompt ?: "") +
                                "\n\n注意：工具「$failedTextTool」连续 $textThreshold 次调用失败，已被禁用。" +
                                "请改用其他可用工具完成剩余任务，或基于已有信息回答用户问题；不要再尝试调用该工具。"
                            consecutiveToolFailures.remove(failedTextTool)
                            lastRoundHadToolCall = false
                        }
                        if (askUserPending) break else continue
                    }
                }
                // 2b. 无工具调用（原生或文本均无）→ 回路自然结束（纯文本响应）
                if (completedToolCalls.isEmpty()) break
                lastRoundHadToolCall = true
                // UXR6 问题 6（诊断工具循环行为）：记录每轮工具调用明细，
                // 真机 logcat 可见 LLM 是否反复调用同一工具（配合重复工具熔断做 RCA）。
                Log.i(
                    TAG,
                    "round=$rounds toolCalls=${completedToolCalls.map { it.toolName }}"
                )

                // UXR3 问题 2（ADR-023，400 Tool names must be unique）：
                // LLM 一轮内可能并行调用同名工具多次（不同 call id，deepseek-reasoner 常见）。
                // 若原样回放 assistant.tool_calls，出现重复 function name，DeepSeek 严格校验
                // 返回 400 "Tool names must be unique"。此处按 toolName 去重（保留首个），
                // 后续 tool result 回灌也只针对保留的调用，保证 assistant.tool_calls 与
                // tool result 一一对应（符合 OpenAI 协议）。
                val uniqueToolCalls = completedToolCalls.distinctBy { it.toolName }

                // 3. 追加 assistant 占位消息（携带 toolCalls 引用，OpenAI 要求下次请求回放）
                // UXR4 问题 1/4/6（ADR-024）：同时携带本轮 reasoning_content（thinkingChain），
                // 满足 DeepSeek「带 tool_calls 的 assistant 消息必须含 reasoning_content」要求。
                val assistantPlaceholder = buildAssistantToolCallMessage(
                    uniqueToolCalls,
                    idGenerator,
                    roundReasoning.toString()
                )
                currentMessages = currentMessages + assistantPlaceholder

                // 4. 串行执行所有 tool_call + 回灌结果
                //    每个工具的失败/超时/拒绝均降级为描述性字符串回灌（ADR-014 5.7）
                //    US-029：同时记录 ToolCallRecord 用于执行可观测
                for (toolCall in uniqueToolCalls) {
                    val toolStart = System.currentTimeMillis()
                    var toolStatus = ExecutionStatus.SUCCESS
                    // v1 批次18：已熔断禁用的工具被 LLM 幻觉重调时，直接回灌禁用文案（不执行）
                    if (toolCall.toolName in disabledTools) {
                        val disabledResult =
                            "错误：工具「${toolCall.toolName}」已因连续多次调用失败被禁用。" +
                                "请改用其他可用工具或基于已有信息回答。"
                        Log.w(TAG, "disabled tool recall blocked: ${toolCall.toolName}")
                        executedToolCallIds.add(toolCall.toolCallId)
                        toolCallRecords.add(
                            ToolCallRecord(
                                toolName = toolCall.toolName,
                                arguments = encodeArguments(toolCall.arguments),
                                result = disabledResult.take(MAX_RESULT_PREVIEW_LEN),
                                durationMs = 0L,
                                status = ExecutionStatus.FAIL
                            )
                        )
                        currentMessages = currentMessages + buildToolResultMessage(
                            toolCallId = toolCall.toolCallId,
                            toolName = toolCall.toolName,
                            result = disabledResult,
                            idGenerator = idGenerator
                        )
                        continue
                    }
                    val result: String = try {
                        executeToolCall(toolCall, mcpServers)
                    } catch (e: CancellationException) {
                        // US-029：记录取消的工具调用
                        toolCallRecords.add(
                            ToolCallRecord(
                                toolName = toolCall.toolName,
                                arguments = encodeArguments(toolCall.arguments),
                                result = "",
                                durationMs = System.currentTimeMillis() - toolStart,
                                status = ExecutionStatus.CANCELLED
                            )
                        )
                        throw e // BR-error-handling-007
                    } catch (e: Exception) {
                        // M4：结构化日志（BR-error-handling-004），executeToolCall 兜底异常
                        Log.w(TAG, "executeToolCall unexpected exception: ${toolCall.toolName}", e)
                        toolStatus = ExecutionStatus.FAIL
                        formatToolError(toolCall.toolName, e)
                    }
                    // 从结果文本推断状态（executeToolCall 内部降级文案以特定前缀标识失败）
                    // v1 批次13（A/B）：截图图片标记提取——视觉模型截图结果含【手机截图图片】+dataUrl，
                    // 提取后图片以 image_url 注入会话（模型看真图），base64 从持久化结果剥离（防 ANR）。
                    // v1 批次17：超长结果截断（crawl4ai 275KB 完整 Markdown 曾撑爆请求体 → 400 打断）
                    val (rawResultText, screenshotImageUrl) = extractScreenshotImage(result, toolCall.toolName)
                    val resultText = truncateToolResult(rawResultText)
                    if (isFailureResult(resultText)) {
                        toolStatus = ExecutionStatus.FAIL
                    }
                    // Q-LOW-2：记录已执行（拿到结果）的 tool_call id，供 ask_user 中断后裁剪占位
                    executedToolCallIds.add(toolCall.toolCallId)
                    // UXR6 问题 1：重复工具熔断计数（同一工具连续失败累加，成功则清零）。
                    // 记录在 executeToolCall 返回之后、tool result 回灌之前，供循环末尾熔断判断。
                    consecutiveToolFailures[toolCall.toolName] =
                        if (toolStatus == ExecutionStatus.FAIL) {
                            (consecutiveToolFailures[toolCall.toolName] ?: 0) + 1
                        } else {
                            0
                        }
                    val toolDuration = System.currentTimeMillis() - toolStart
                    toolCallRecords.add(
                        ToolCallRecord(
                            toolName = toolCall.toolName,
                            arguments = encodeArguments(toolCall.arguments),
                            result = resultText.take(MAX_RESULT_PREVIEW_LEN),
                            durationMs = toolDuration,
                            status = toolStatus
                        )
                    )
                    val toolResultMessage = buildToolResultMessage(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        result = resultText,
                        idGenerator = idGenerator
                    )
                    currentMessages = currentMessages + toolResultMessage
                    // v1 批次13（B）：截图图片以 user 消息 image_url 注入会话（模型直接查看屏幕）。
                    // base64 不进任何持久化消息（防历史膨胀/渲染 ANR）。
                    if (screenshotImageUrl != null) {
                        currentMessages = currentMessages + ChatMessage(
                            id = idGenerator(),
                            role = Role.USER,
                            content = "（手机截图，请直接查看屏幕内容）",
                            imageUrl = screenshotImageUrl,
                            // v1 批次13（F1）：瞬态截图图片标记——持久化时剥离 base64（防历史膨胀/渲染 ANR）
                            transientImage = true,
                            timestamp = System.currentTimeMillis()
                        )
                    }

                    // UXR8 N2 Phase 2（ADR-030）：反问/澄清 —— ask_user 结果带标记前缀 →
                    // 发射 AskUser 事件 + 中断当前回路（StopAtTools 语义）。
                    // 工具结果仍回灌历史（协议一致），UI 收到 AskUser 展示提问卡片，
                    // 用户答复作为下一条 user 消息进入下一轮。
                    if (result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER)) {
                        val payload = parseAskUserPayload(
                            result.removePrefix(AskUserLocalToolExecutor.RESULT_MARKER)
                        )
                        if (payload != null && payload.questions.isNotEmpty()) {
                            Log.i(TAG, "ask_user tool triggered, stopping loop to collect user input")
                            onEvent(StreamEvent.AskUser(payload.questions))
                            // Q-MED-2（guardrail TKN-UXR8-B3-GUARDRAIL-001）：中断前复位
                            // lastRoundHadToolCall —— ask_user 为主动终止工具循环（等价熔断语义），
                            // 若发生在第 maxRounds 轮，shouldEmitMaxRoundsError 不应误发「循环达上限」，
                            // 执行记录也不应误标 FAIL。
                            lastRoundHadToolCall = false
                            askUserPending = true
                            break
                        } else {
                            // Q-LOW-1（guardrail TKN-UXR8-B3-GUARDRAIL-001）：解析失败/空问题 →
                            // 降级为 Error 事件 + 不中断回路（LLM 可基于已回灌的 ask_user 结果文本
                            // 恢复；避免 UI 无提问卡片且回路静默中断导致无响应）。
                            Log.w(TAG, "ask_user payload invalid, treating as tool result")
                            onEvent(StreamEvent.Error("反问内容解析失败，请重新提问"))
                        }
                    }
                }
                // 反问触发：中断外层 while 回路（后续不再请求 LLM）。
                // Q-LOW-2（guardrail TKN-UXR8-B3-GUARDRAIL-001）：ask_user 中断时若本轮尚有
                // 未执行的 tool_call（LLM 偶发一轮多 tool_call 且 ask_user 非末尾），assistant
                // 占位消息仍携带其 tool_calls 引用但无对应 TOOL 结果 → 下一轮协议 400
                // （"Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"）。
                // 将占位消息裁剪为「已执行 tool_calls」子集（未执行引用剔除），保证配对一致。
                if (askUserPending) {
                    val executedIds = executedToolCallIds.toSet()
                    if (executedIds.size < uniqueToolCalls.size) {
                        val trimmed = buildAssistantToolCallMessage(
                            uniqueToolCalls.filter { it.toolCallId in executedIds },
                            idGenerator,
                            roundReasoning.toString()
                        )
                        val placeholderIndex = currentMessages.indexOfLast {
                            it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty()
                        }
                        if (placeholderIndex >= 0) {
                            currentMessages = currentMessages.toMutableList().also { list ->
                                list[placeholderIndex] = trimmed
                            }
                        }
                    }
                    break
                }

                // 5. 重复工具熔断（UXR6 问题 1）：同一工具连续失败达阈值时，
                //    置空工具 + 追加提示，让 LLM 直接基于已有信息回答，
                //    避免"失败文案诱导重试 → maxRounds=10 硬终止 → 用户无答案"的死循环。
                // v1 批次18：**外科式单工具禁用**——仅禁用连续失败的该工具（分类别阈值），
                // 其余工具继续可用；TODO 清单任务不再因单个工具 2 次失败被整体终止。
                val failedToolName = consecutiveToolFailures.entries
                    .firstOrNull { it.value >= resolveToolFailureThreshold(it.key) }
                    ?.key
                if (failedToolName != null) {
                    val threshold = resolveToolFailureThreshold(failedToolName)
                    Log.w(TAG, "tool circuit breaker: $failedToolName failed $threshold times consecutively, disabling this tool only")
                    disabledTools.add(failedToolName)
                    effectiveTools = effectiveTools.filter { it.function.name != failedToolName }
                    effectiveSystemPrompt = (effectiveSystemPrompt ?: "") +
                        "\n\n注意：工具「$failedToolName」连续 $threshold 次调用失败，已被禁用。" +
                        "请改用其他可用工具完成剩余任务，或基于已有信息回答用户问题；不要再尝试调用该工具。"
                    consecutiveToolFailures.remove(failedToolName)
                    // guardrail Low-3（TKN-UXR6-GUARDRAIL-001）：熔断在最后一轮（round==maxRounds）
                    // 触发时 continue 后 while 立即退出，lastRoundHadToolCall 仍为 true →
                    // shouldEmitMaxRoundsError 误发"循环达上限"。熔断即主动终止工具循环，
                    // 置 false 使 shouldEmitMaxRoundsError 不触发（熔断目标是给用户答案而非报错）。
                    lastRoundHadToolCall = false
                    // 仅当禁用后已无任何可用工具时才强制纯文本收尾；否则继续用剩余工具执行
                    if (effectiveTools.isEmpty()) {
                        lastRoundHadToolCall = false
                        continue
                    }
                    // 仍有剩余工具：不额外跑一轮，直接进入下一轮请求（LLM 基于禁用提示换工具）
                    continue
                }
                // 6. 继续下一轮（LLM 基于 tool result 继续生成）
            }

            // 6. maxRounds 超限提示（仅当最后一轮有工具调用却已达上限时）
            if (shouldEmitMaxRoundsError(lastRoundHadToolCall, rounds, maxRounds)) {
                // UXR6 问题 6：记录循环达上限根因（真机 RCA：确认是重复工具调用导致）
                Log.w(TAG, "maxRounds reached: rounds=$rounds maxRounds=$maxRounds")
                finalStatus = ExecutionStatus.FAIL
                errorMessage = "工具调用循环达上限 $maxRounds"
                onEvent(StreamEvent.Error("工具调用循环达上限 $maxRounds，已终止"))
            }

            currentMessages
        } catch (e: CancellationException) {
            // US-029：协程取消记录为 CANCELLED 状态，重抛保证取消传播（BR-error-handling-007）
            finalStatus = ExecutionStatus.CANCELLED
            errorMessage = "协程取消"
            throw e
        } finally {
            // US-029：持久化执行记录（仅当 skillConfigId + skillName + repository 均非空时）
            saveExecutionRecordIfNeeded(
                skillConfigId = skillConfigId,
                skillName = skillName,
                startedAt = startedAt,
                toolCallRecords = toolCallRecords,
                finalStatus = finalStatus,
                errorMessage = errorMessage
            )
        }
    }

    companion object {
        /** 单次工具调用默认超时（30s，对齐 UiConfirmationGate 确认超时）。 */
        internal const val DEFAULT_TOOL_TIMEOUT_MS = 30_000L

        /**
         * 工具执行回路默认最大轮数（防止无限循环，ADR-014 5.5）。
         *
         * v1 真机反馈（2026-08-19）：普通工具在复杂深度调研时 10 轮常被截断、手机操控
         * （每步 UI 操作消耗 1 轮）更是几乎无法完成长链路。故全局上限由 10 提升至 50，
         * 手机操控场景由 [PHONE_CONTROL_MAX_ROUNDS]=200 另行放开（见 [isPhoneControlTool]）。
         */
        internal const val DEFAULT_MAX_ROUNDS = 50

        /**
         * 手机操控（`phone_control__*`）工具回路的轮数上限。
         *
         * 用户要求对该功能「解除工具循环调用上限」。一次手机操控 = LLM 逐轮
         * `get_ui_state → click → get_ui_state…`，每步消耗 1 个 round，复杂长链路远超
         * 普通上限。但完全无限循环有失控风险（轮间退避 + 每轮 LLM 调用延迟累积），
         * 故取一个对"完成一次任务"而言实际上限极高的值（200 轮 ≈ 数分钟任务），
         * 同时保留 [MAX_CONSECUTIVE_TOOL_FAILURES]=2 重复失败熔断兜底。
         */
        internal const val PHONE_CONTROL_MAX_ROUNDS = 200

        /** 手机操控工具名前缀（与 [io.prism.phonecontrol.PhoneControlLocalToolExecutor.NAMESPACE] 对齐）。 */
        internal const val PHONE_CONTROL_TOOL_PREFIX = "phone_control__"

        /** 判断工具名是否为手机操控工具（用于分层轮数上限）。 */
        internal fun isPhoneControlTool(name: String): Boolean =
            name.startsWith(PHONE_CONTROL_TOOL_PREFIX)

        /**
         * 重复工具熔断阈值（UXR6 问题 1）：同一工具连续失败达到该次数即熔断，
         * 置空工具让 LLM 直接基于已有信息回答，避免"失败文案诱导重试 → maxRounds 硬终止"死循环。
         */
        internal const val MAX_CONSECUTIVE_TOOL_FAILURES = 2

        /**
         * v1 批次18（真机 RCA 2026-09-03）：工具熔断**分类别阈值**——只针对单个工具禁用，
         * 不再清空全部工具（旧语义在长任务中 2 次网络失败即杀掉整个任务）。
         *
         * - phone_control__*（UI 操作类）：4 次——软失败（输入未生效/屏幕无变化）在真机 UI
         *   差异下常见，2 次即熔断导致操控任务中途中断（真机 19:37 type 连续 2 次软失败 →
         *   全部工具被清 → 任务终止）
         * - 其余（fetch/search 等网络类）：3 次——抓取失败（反爬）在长任务中属预期波动，
         *   3 次连续失败才禁用该工具；其余工具（其他引擎/本地搜索）继续可用
         */
        internal const val PHONE_CONTROL_TOOL_FAILURE_THRESHOLD = 4
        internal const val DEFAULT_TOOL_FAILURE_THRESHOLD = 3

        /**
         * 解析指定工具的熔断阈值（纯函数，v1 批次18）。
         *
         * @param toolName 工具名（含命名空间前缀）
         * @return 连续失败达到该次数即禁用该工具
         */
        internal fun resolveToolFailureThreshold(toolName: String): Int =
            if (toolName.startsWith("phone_control__")) {
                PHONE_CONTROL_TOOL_FAILURE_THRESHOLD
            } else {
                DEFAULT_TOOL_FAILURE_THRESHOLD
            }

        /**
         * UXR11 U2（ADR-033）：工具回路轮间最小间隔（毫秒）。
         *
         * 背景：LLM 端点（如 Moonshot Kimi 组织级 RPM=3）对连续请求限流严格，工具回路
         * 每轮工具执行后都要重新调 LLM。真机实测一次深度调研数秒内多次 LLM 调用即触发
         * 429（"request reached organization max RPM"）。轮间加 2s 间隔把请求摊开。
         *
         * 说明：RPM=3 是**账号级配额**，客户端无法提升；轮间退避只降低瞬时峰值、减少触发
         * 概率。若账号配额极低（RPM<2），一次多轮调研仍可能触发 429——此时由既有
         * 429 专属文案（R2/ADR-032）提示用户稍等，且工具回路自然停止不重试。
         */
        internal const val TOOL_LOOP_INTER_ROUND_DELAY_MS = 2_000L

        /**
         * UXR11 U2（ADR-033）+ v1 批次11（E，D11）：429 限流自动重试次数上限。
         *
         * 限流（RPM/模型过载）时按退避重发同一轮请求（本轮未执行工具，幂等安全）。
         * v1 批次11：4→6 次，退避策略 = 优先服务端 Retry-After 头，否则指数退避
         * （3s/6s/12s/24s/48s/96s；Retry-After 上限 60s/次），提高 glm code1305 / sensenova
         * 并发等长限流窗口下的自愈率；仍设上限避免无限重试放大请求（D11：宁可多等也不失败）。
         */
        internal const val MAX_RATE_LIMIT_RETRIES = 6

        /**
         * UXR11 U2（ADR-033）+ v1 批次11：429 限流重试基础退避时长（毫秒）。
         *
         * 服务端 "retry after" 通常仅 1s；取 3s 保证越过限流窗口。v1 批次11 改为**指数退避**：
         * 第 N 次重试等待 `3s × 2^(N-1)`（3s/6s/12s/24s），对模型过载等长限流窗口更有效。
         */
        internal const val RATE_LIMIT_BACKOFF_MS = 3_000L

        /** guardrail F2：`429` 词边界匹配（避免 "错误码 4299" 等误判）。 */
        private val RATE_LIMIT_429_REGEX = Regex("""\b429\b""")

        /** guardrail F2：`rpm` 词边界匹配（Kimi "max RPM: 3" 类文案）。 */
        private val RATE_LIMIT_RPM_REGEX = Regex("""\brpm\b""")

        /** 命名空间分隔符（`skillName__toolName`）。 */
        internal const val NAMESPACE_SEPARATOR = "__"

        /**
         * MCP 工具命名空间前缀（DEF-008，Bug-3）。
         *
         * 注入到 LLM 的 MCP 工具名格式：`mcp_<serverName>__<toolName>`。
         * 前缀避免与 Skill 工具（`skillName__toolName`）及跨 App 工具（`cross_app__*`）冲突，
         * 并使 [selectMcpServer] 能从工具名解析出目标 MCP Server（多 server 时精确路由）。
         */
        internal const val MCP_NAMESPACE_PREFIX = "mcp_"

        /** 日志 TAG（M4 结构化日志，BR-error-handling-004）。 */
        private const val TAG = "SkillExecutor"

        /** 异常 message 截断长度上限（M3，CWE-209 信息泄露纵深防御）。 */
        internal const val MAX_ERROR_MESSAGE_LEN = 200

        /** 工具调用结果预览长度上限（US-029，ToolCallRecord.result 截断）。 */
        internal const val MAX_RESULT_PREVIEW_LEN = 200

        /**
         * Q1（guardrail TKN-UXR4-GUARDRAIL-001）：assistant 占位消息携带的 reasoning_content
         * 长度上限。多轮工具回路中思考链会随轮次累积，需截断防 token 溢出与会话 JSON 膨胀。
         */
        internal const val MAX_REASONING_LEN = 2000

        /**
         * v1 批次17（真机 RCA 2026-09-03）：单条 TOOL 结果消息内容长度上限（字符）。
         *
         * **根因（真机日志 + ObjectBox 取证）**：crawl4ai__md 抓取 moegirl.org.cn 返回
         * **275,661 字符**完整 Markdown，原样进会话历史 → 后续每轮请求携带 500KB+ 工具内容
         *（token 爆炸 + 请求体过大），是「调用异常打断（DeepSeek 400）」的诱因之一。
         * 截断上限取 20,000 字符（约 1~2 万 token，兼顾抓取类工具的可用性与请求体积）。
         */
        internal const val MAX_TOOL_RESULT_CHARS = 20_000

        /**
         * 截断尾注稳定标记（v1 批次17 guardrail P3-1）：含该标记的内容视为已截断，
         * 重复应用 [truncateToolResult] 时直接原样返回——保证严格幂等
         *（否则尾注本身超限导致二次截断、「原文 N 字符」数字逐轮失真）。
         */
        private const val TRUNCATION_NOTICE_MARKER = "…（工具结果过长，已截断"

        /**
         * 工具结果截断（v1 批次17）：超 [MAX_TOOL_RESULT_CHARS] 的 TOOL 结果截断并附标注，
         * 防止单条抓取结果（如完整网页 Markdown）撑爆请求体 / 会话 JSON。
         *
         * @param raw 工具执行原始结果文本
         * @return 原文本（未超限或已截断过）或截断文本（附截断标注尾注）
         */
        internal fun truncateToolResult(raw: String): String {
            if (raw.length <= MAX_TOOL_RESULT_CHARS) return raw
            if (raw.contains(TRUNCATION_NOTICE_MARKER)) return raw // 幂等：已截断内容不重复处理
            Log.w(TAG, "tool result truncated: ${raw.length} chars -> $MAX_TOOL_RESULT_CHARS")
            return raw.take(MAX_TOOL_RESULT_CHARS) +
                "\n\n$TRUNCATION_NOTICE_MARKER：原文 ${raw.length} 字符，仅保留前 $MAX_TOOL_RESULT_CHARS 字符）"
        }

        /**
         * 工具调用配对净化器（v1 批次17，真机 RCA 2026-09-03）：**双向**保证 OpenAI tool 协议
         * 配对不变量，替代单向的孤儿 TOOL 过滤（原 [ConversationViewModel.dropOrphanToolMessages]
         * 仅丢弃「无前置 tool_calls 的 TOOL」，未处理反向「tool_calls 缺 tool 结果」——DeepSeek 400
         * "insufficient tool messages following tool_calls message"）。
         *
         * **不变量**：请求消息序列中，每条 assistant(tool_calls=[K 个]) 之后、下一条非 TOOL 消息
         * 之前，必须恰好有 K 条 TOOL 消息，且每条 TOOL 的 tool_call_id 能与某个 call id 关联。
         *
         * **规则**（按序遍历，维护待配对 id 队列）：
         * - ASSISTANT 且 toolCalls 非空 → 先为上一组未配对完的 id 补合成 TOOL 消息，再设新待配对队列
         * - ASSISTANT 无 toolCalls / USER → 先补合成再清空队列（USER 为对话边界）
         * - TOOL → 队列空 = 孤儿，丢弃；toolCallId 命中队列 = 保留并移除该 id；
         *   toolCallId 空白或未命中 = 保留内容但**重写** toolCallId 为队首 id（内容保留、协议对齐）
         * - 序列结束 → 为剩余 id 补合成 TOOL 消息
         *
         * 合成 TOOL 内容为可诊断中文提示（LLM 可据此得知该调用结果缺失，按已有信息继续）。
         * 同时对 TOOL content 超限者应用 [truncateToolResult]（净化历史中已存在的超长结果）。
         *
         * 纯函数（id 生成器注入），幂等：对已净化的序列重复应用不产生变化。
         *
         * **应用位置**（纵深防御，两处幂等）：
         * 1. ConversationViewModel.sendMessage：L1 窗口切片**之后**定形为 sanitizedHistory
         *    （调用契约：传入 [executeLoop] 的 history 必须已净化——syncToolMessages 以
         *    history.size 切片回灌新增消息，入口净化若再改变长度会错位）
         * 2. [executeLoop] 入口：幂等复验兜底其余调用入口（对已净化序列恒 no-op，
         *    不改变列表长度，故不影响 syncToolMessages 切片契约）
         *
         * @param msgs 待净化消息序列
         * @param idGenerator ChatMessage id 生成器（合成 TOOL 消息用）
         * @return 配对不变的净化序列
         */
        internal fun sanitizeToolCallPairing(
            msgs: List<ChatMessage>,
            idGenerator: () -> Long
        ): List<ChatMessage> {
            val out = mutableListOf<ChatMessage>()
            val pendingIds = ArrayDeque<String>()

            fun flushMissing() {
                for (missingId in pendingIds) {
                    out.add(
                        ChatMessage(
                            id = idGenerator(),
                            role = Role.TOOL,
                            content = "（工具结果缺失：该调用的结果因历史截断或执行中断丢失，请基于已有信息继续）",
                            timestamp = System.currentTimeMillis(),
                            toolCallId = missingId
                        )
                    )
                }
                pendingIds.clear()
            }

            for (msg in msgs) {
                when (msg.role) {
                    Role.ASSISTANT -> {
                        flushMissing()
                        out.add(msg)
                        for (call in msg.toolCalls) pendingIds.addLast(call.id)
                    }
                    Role.TOOL -> {
                        val tid = msg.toolCallId
                        val kept: ChatMessage? = when {
                            pendingIds.isEmpty() -> null
                                // 孤儿 TOOL（无前置 tool_calls）：丢弃（沿用 UXR5 问题 4 语义）
                            !tid.isNullOrBlank() && pendingIds.contains(tid) -> {
                                pendingIds.remove(tid)
                                msg
                            }
                            else -> {
                                // toolCallId 空白或未命中待配对队列：保留内容，重写为队首 id（协议对齐）
                                val realigned = pendingIds.removeFirst()
                                if (tid == realigned) msg else msg.copy(toolCallId = realigned)
                            }
                        }
                        if (kept != null) {
                            // 超长结果截断（净化历史中已存在的超长消息，幂等）
                            out.add(kept.copy(content = truncateToolResult(kept.content)))
                        }
                    }
                    Role.USER -> {
                        flushMissing()
                        out.add(msg)
                    }
                }
            }
            flushMissing()
            return out
        }

        /**
         * 文件路径正则（M3 脱敏）：匹配以 `/` 或 `\` 开头的路径片段，
         * 替换为 `<path>` 占位符，避免内部路径泄露给 LLM 再间接暴露给用户。
         */
        private val pathPattern = Regex("""[/\\][^\s"'<>]+""")

        /**
         * 对异常 message 做脱敏处理（M3，CWE-209 信息泄露纵深防御）。
         *
         * 1. **长度截断**：仅保留前 [MAX_ERROR_MESSAGE_LEN] 字符，防止超长 message 污染回灌
         * 2. **路径脱敏**：将 `/xxx/yyy` 或 `\xxx\yyy` 替换为 `<path>`
         *
         * @param raw 原始 message（可能为 null）
         * @return 脱敏后的 message；raw 为 null 时返回 null（由调用方回退 simpleName）
         */
        internal fun sanitizeErrorMessage(raw: String?): String? {
            if (raw == null) return null
            val truncated = if (raw.length > MAX_ERROR_MESSAGE_LEN) {
                raw.take(MAX_ERROR_MESSAGE_LEN) + "..."
            } else {
                raw
            }
            return pathPattern.replace(truncated, "<path>")
        }

        /** 参数序列化用 Json 实例（紧凑输出，无默认值编码）。 */
        private val argumentsJson = Json {
            encodeDefaults = true
            prettyPrint = false
        }

        /** ask_user 载荷解析用 Json 实例（UXR8 N2，ADR-030，容错未知字段）。 */
        private val askUserJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 将 MCP Server 名称规范化为合法工具命名空间（UX-001 问题 5/6，ADR-022 二次修复）。
         *
         * **根因**：server 名含空格（如 `Sequential Thinking`）或中文（如 `跨 App 调用`）时，
         * 拼出的工具名 `mcp_<serverName>__<tool>` 含非法字符，被 OpenAI/DeepSeek 拒绝
         * （400 invalid_request_error）或本地 `isLegalToolName` 过滤导致 LLM 感知不到该工具。
         *
         * **修复**：将非 `[a-zA-Z0-9]` 字符替换为 `_`，保证工具名合法（OpenAI 仅允许 `[a-zA-Z0-9_-]`）。
         * 该函数是**双向一致的**：构造工具名（[ConversationViewModel.buildTools 合并]）与反查
         * [selectMcpServer] 必须使用同一规范化逻辑，否则无法从工具名反解回原始 Server。
         *
         * 示例：`Sequential Thinking` → `Sequential_Thinking`；`跨 App 调用` → `______`（中文全替换）。
         *
         * @param serverName MCP Server 原始名称
         * @return 规范化后的合法命名空间（仅含 `[a-zA-Z0-9_]`，不含 `__`）
         */
        internal fun toMcpNamespace(serverName: String): String =
            serverName.replace(NON_ALNUM_PATTERN, "_")

        /** 非字母数字字符模式（用于 [toMcpNamespace]，MCP 工具命名空间规范化）。 */
        internal val NON_ALNUM_PATTERN = Regex("""[^a-zA-Z0-9]""")

        /**
         * 剥离工具名命名空间前缀（DEF-008，Bug-3 扩展；v1 批次19 与路由同源）。
         *
         * - MCP 工具（`mcp_<serverName>__<toolName>`）：剥离 `mcp_<serverName>__`，剩 `<toolName>`
         * - Skill 工具（`skillName__toolName`）：剥离 `skillName__`，剩 `<toolName>`
         *
         * v1 批次19（真机 RCA：含下划线 Server 名反解歧义）：提供 [serverName] 时按
         * [mcpToolPrefix] 精确剥离（removePrefix）；前缀不匹配时回退旧反解逻辑并告警
         * （兼容历史调用点未传 serverName 的场景）。
         *
         * MCP Server 不感知 Prism 层命名空间，调用前必须剥离。
         */
        internal fun stripNamespace(toolName: String, serverName: String? = null): String {
            if (serverName != null) {
                val prefix = mcpToolPrefix(serverName)
                if (toolName.startsWith(prefix, ignoreCase = true)) {
                    return toolName.substring(prefix.length)
                }
                Log.w(TAG, "stripNamespace: tool '$toolName' does not match prefix of server '$serverName', fallback to legacy parse")
            }
            if (toolName.startsWith(MCP_NAMESPACE_PREFIX)) {
                return toolName.substringAfter(MCP_NAMESPACE_PREFIX).substringAfter(NAMESPACE_SEPARATOR)
            }
            return toolName.substringAfter(NAMESPACE_SEPARATOR)
        }

        /**
         * Server 的全局工具名前缀（v1 批次19）：`mcp_<规范化名>__`。
         *
         * 路由与剥离共用此函数，保证二者永远同源（消除 substringBefore/After("__")
         * 对含下划线 Server 名的反解歧义——`mcp____Web_Search__webSearchPro` 曾被
         * 反解出命名空间 `_` 而静默回退到第一个启用 Server）。
         */
        internal fun mcpToolPrefix(serverName: String): String =
            MCP_NAMESPACE_PREFIX + toMcpNamespace(serverName) + NAMESPACE_SEPARATOR

        /**
         * 选择 MCP Server（DEF-008，Bug-3：支持按工具名精确路由）。
         *
         * - 工具名带 MCP 前缀（`mcp_<serverName>__...`）：按 **[mcpToolPrefix] 前缀精确匹配**
         *   （最长前缀优先，防嵌套歧义），匹配不到返回 **null**（调用方报"无可用 MCP Server"）。
         *   **v1 批次19 语义收紧**：不再静默回退到第一个启用 Server——旧反解
         *   `substringBefore("__")` 对含下划线的 Server 名（如规范化后 `___Web_Search`）
         *   会解析出错误命名空间并幻影路由（真机 RCA：webSearchSogou/Quark/Pro 全部
         *   路由到 Time Server 报"暂未实现"），回退只会放大错误。
         * - 无 MCP 前缀（Skill/跨 App 工具）：取第一个启用的 Server（原逻辑不变）
         *
         * @return 匹配的 Server；无启用 Server 或命名空间无匹配则 null
         */
        internal fun selectMcpServer(
            mcpServers: List<McpServerConfig>,
            toolName: String? = null
        ): McpServerConfig? {
            if (toolName != null && toolName.startsWith(MCP_NAMESPACE_PREFIX)) {
                var best: McpServerConfig? = null
                var bestPrefixLen = -1
                for (server in mcpServers) {
                    if (!server.isEnabled) continue
                    val prefix = mcpToolPrefix(server.name)
                    if (toolName.startsWith(prefix, ignoreCase = true) && prefix.length > bestPrefixLen) {
                        best = server
                        bestPrefixLen = prefix.length
                    }
                }
                return best
            }
            return mcpServers.firstOrNull { it.isEnabled }
        }

        /**
         * 判定是否应发射 maxRounds 超限 Error 事件。
         *
         * 只有当最后一轮**仍有工具调用**（即回路未自然结束）且已达 maxRounds 上限时才提示。
         * 若最后一轮无工具调用（纯文本响应），回路自然结束，不提示超限。
         */
        internal fun shouldEmitMaxRoundsError(
            lastRoundHadToolCall: Boolean,
            rounds: Int,
            maxRounds: Int
        ): Boolean = lastRoundHadToolCall && rounds >= maxRounds

        /**
         * 从工具执行结果文本推断是否为失败（US-029，用于 ToolCallRecord.status）。
         *
         * [executeToolCall] 内部对各种失败场景返回固定前缀的降级文案：
         * - [formatRejection]：`"用户拒绝执行工具: ..."`
         * - [formatTimeout]：`"工具执行超时（...）: ..."`
         * - [formatToolError]：`"工具执行失败: ..."`
         * - [formatNoServer]：`"无可用 MCP Server，无法执行工具: ..."`
         * - [formatConfirmError]：`"用户确认失败: ..."`
         *
         * M6 Phase B 新增本地工具失败前缀（CrossAppLocalToolExecutor 返回）：
         * - `"未找到应用配置: ..."`
         * - `"未安装..."`
         * - `"跨 App 调用超时..."`
         * - `"缺少必需参数 ..."`
         * - `"不支持的媒体类型: ..."`
         * - `"未知跨 App 工具: ..."`
         *
         * 通过前缀匹配判定失败，避免修改 [executeToolCall] 返回类型（保持向后兼容）。
         *
         * **已知局限**：若 MCP Server 返回的正常结果恰好以这些前缀开头，会被误判为失败。
         * 未来重构可考虑将 [executeToolCall] 返回类型改为 sealed class（成功/失败携带信息）。
         *
         * @param result [executeToolCall] 返回的结果文本
         * @return true 表示失败，false 表示成功
         */
        internal fun isFailureResult(result: String): Boolean =
            result.startsWith("用户拒绝执行工具") ||
                result.startsWith("工具执行超时") ||
                result.startsWith("工具执行失败") ||
                result.startsWith("无可用 MCP Server") ||
                result.startsWith("用户确认失败") ||
                result.startsWith("工具调用已禁用") ||
                result.startsWith("未找到应用配置") ||
                result.startsWith("未安装") ||
                result.startsWith("跨 App 调用超时") ||
                result.startsWith("缺少必需参数") ||
                result.startsWith("不支持的媒体类型") ||
                result.startsWith("未知跨 App 工具") ||
                // UXR6 问题 1：联网搜索失败/空结果前缀（WebSearchLocalToolExecutor 返回），
                // 纳入失败识别，使重复工具熔断能识别搜索失败并提前终止。
                result.startsWith("搜索失败") ||
                // UXR7 问题 1（新发现）：Fetch 工具失败文案（LocalMcpToolProvider 返回
                // "抓取失败：..."），此前不在前缀列表 → LLM 反复用 Fetch 抓取直至 maxRounds。
                result.startsWith("抓取失败") ||
                result.startsWith("Fetch 工具不可用") ||
                // UXR7-R2（网络调研：LocalMcpToolProvider 全量降级文案审计）：补全剩余
                // 失败前缀——"仅支持抓取"/"仅支持公网地址"（URL 非法/SSRF 拒绝）与
                // "工具调用失败"（Filesystem 桥接兜底），避免 LLM 反复用同参数重试直至熔断。
                result.startsWith("仅支持抓取") ||
                result.startsWith("仅支持抓取公网地址") ||
                result.startsWith("工具调用失败") ||
                // v1 US-201/202（LLM 操控手机）：phone_control__* 工具失败/硬拦截文案统一
                // 以"错误："（执行失败）或"⚠️"（敏感硬拦截）前缀标注。此前不在前缀列表 →
                // 熔断计数无法识别手机控制失败，LLM 会用同参数反复重试直至 maxRounds。
                // 注意：AskUser 接管结果以【需要用户回答】开头（已在 executeLoop 单独处理，
                // 不进失败识别）；⚠️ 前缀用于手机控制硬拦截结果。
                result.startsWith("错误：") ||
                result.startsWith("⚠️ ") ||
                // v1 批次9（US-904，H-3，guardrail TKN-V1B9-GUARDRAIL-001）：热榜工具失败文案
                //（HotListLocalToolExecutor 的 401/403/429/5xx/网络分支返回"热榜获取失败：…"）。
                // 此前不在前缀清单 → 持续失败（Key 无效/限流）时熔断不触发，LLM 反复调用至
                // maxRounds=50，放大请求频率叠加热榜 API 限流（与"抓取失败"同族回归）。
                result.startsWith("热榜获取失败") ||
                // UXR8 N2 Phase 2（ADR-030）：ask_user 反问结果不在失败识别之列
                //（其为"等待用户输入"语义，非失败；由 executeLoop 检测标记前缀单独处理）。
                false

        /**
         * UXR11 U2（ADR-033）：判断流式错误事件是否为服务端限流（429，纯函数可测）。
         *
         * 覆盖 [io.prism.network.OpenAICompatibleProvider.mapHttpError] 的 429 专属文案
         * （"请求过于频繁，触发服务端限流（429）"）及其它 Provider 的 rate_limit 变体
         * （OpenAI "rate_limit_exceeded" / Kimi "reached organization max RPM" / 状态码 429）。
         *
         * @param message [StreamEvent.Error] 的 message（可能为 null）
         * @return true 表示限流（应退避重试），false 表示其它错误
         */
        internal fun isRateLimitError(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            val lower = message.lowercase()
            // guardrail F2：`\b429\b` 词边界匹配避免把 "错误码 4299" 误判为 429 限流
            return RATE_LIMIT_429_REGEX.containsMatchIn(lower) ||
                lower.contains("rate_limit") ||
                lower.contains("rate limit") ||
                lower.contains("限流") ||
                lower.contains("请求过于频繁") ||
                // Kimi/Moonshot： "reached organization max RPM: 3"（账号级 RPM 配额）
                lower.contains("max rpm") ||
                RATE_LIMIT_RPM_REGEX.containsMatchIn(lower)
        }

        /**
         * v1 批次13（A/B，D16）：从工具结果提取「手机截图图片」标记。
         *
         * 视觉模型截图时 [io.prism.phonecontrol.PhoneControlLocalToolExecutor.runScreenshot]
         * 返回 `【手机截图图片】+dataUrl`。本函数：
         * - 命中标记：返回 (简短说明文本, dataUrl)——**base64 从持久化工具结果中剥离**
         *   （真机 ANR 根因：400KB base64 单行渲染阻塞主线程 >5s），dataUrl 供调用方以
         *   image_url 注入会话让模型直接看图；
         * - 未命中：原样返回，dataUrl=null。
         *
         * @param result 工具执行结果
         * @return (处理后的文本, 截图 dataUrl?)
         */
        internal fun extractScreenshotImage(result: String, toolName: String? = null): Pair<String, String?> {
            // v1 批次13（L-3，guardrail）：仅对手机操控截图工具检测标记——防止 fetch/搜索等其它
            // 工具结果恰好以「【手机截图图片】」开头时被误提取为 image_url 注入请求体（私有权重极低
            // 但零成本防御）。toolName 缺省/null 时按旧行为检测（向后兼容既有测试）。
            if (toolName != null && !toolName.endsWith("__screenshot")) return (result to null)
            if (result.startsWith(SCREENSHOT_IMAGE_MARKER)) {
                val dataUrl = result.removePrefix(SCREENSHOT_IMAGE_MARKER).trim()
                return ("已截取屏幕（图片已作为视觉输入发送给模型查看）" to dataUrl)
            }
            return (result to null)
        }

        /** [extractScreenshotImage] 用的手机截图图片标记（与 [io.prism.phonecontrol.PhoneControlLocalToolExecutor.SCREENSHOT_IMAGE_MARKER] 对齐）。 */
        private const val SCREENSHOT_IMAGE_MARKER = "【手机截图图片】"

        /**
         * 解析 ask_user 工具结果载荷（UXR8 N2 Phase 2，ADR-030，纯函数可测）。
         *
         * [AskUserLocalToolExecutor.execute] 返回 `【需要用户回答】` + AskUserPayload JSON。
         * 本函数解析 JSON 为 [AskUserQuestion] 列表；解析失败返回 null（调用方降级，
         * 不崩溃、不发射事件）。
         */
        internal fun parseAskUserPayload(payloadJson: String): AskUserPayload? = try {
            askUserJson.decodeFromString(AskUserPayload.serializer(), payloadJson)
        } catch (e: Exception) {
            Log.w(TAG, "ask_user payload parse failed: ${e::class.simpleName}")
            null
        }

        /**
         * 构造 assistant 占位消息（携带 toolCalls 引用，OpenAI 要求下次请求回放）。
         *
         * content 为空字符串（OpenAI 允许 assistant 空 content + tool_calls）。
         * toolCalls 字段携带所有完成的 tool_call 引用（id/name/arguments JSON string）。
         *
         * **UXR4 问题 1/4/6（ADR-024）**：新增 [reasoningContent] 参数 —— 携带本轮流式响应
         * 累积的 reasoning_content（思考链）。DeepSeek 官方要求：携带 tool_calls 的 assistant
         * 消息必须含 reasoning_content，否则后续请求返回 400。thinkingChain 为空白时置 null
         * （经 [ChatMessage.toMessageBody] 输出 null，无思考的端点零影响）。
         *
         * @param toolCalls 本轮 LLM 返回的所有 ToolCallComplete 事件
         * @param idGenerator ChatMessage id 生成器
         * @param reasoningContent 本轮流式响应累积的 reasoning_content（可为空串/空白）
         */
        internal fun buildAssistantToolCallMessage(
            toolCalls: List<StreamEvent.ToolCallComplete>,
            idGenerator: () -> Long,
            reasoningContent: String? = null
        ): ChatMessage {
            val refs = toolCalls.map { tc ->
                ToolCallRef(
                    id = tc.toolCallId,
                    functionName = tc.toolName,
                    arguments = encodeArguments(tc.arguments)
                )
            }
            return ChatMessage(
                id = idGenerator(),
                role = Role.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                toolCalls = refs,
                // Q1（guardrail TKN-UXR4-GUARDRAIL-001）：reasoningContent 长度上限，
                // 防多轮回路思考链无限膨胀（token 溢出 + 会话 JSON 重复存储）
                thinkingChain = reasoningContent
                    ?.takeIf { it.isNotBlank() }
                    ?.take(MAX_REASONING_LEN)
            )
        }

        /**
         * 构造 tool result 消息（role=TOOL，关联 tool_call_id）。
         *
         * @param toolCallId 关联的 LLM tool_call id（`call_xxx`）
         * @param toolName 工具名（带命名空间前缀，UI 展示用）
         * @param result 工具执行结果文本（成功/失败描述）
         * @param idGenerator ChatMessage id 生成器
         */
        internal fun buildToolResultMessage(
            toolCallId: String,
            toolName: String,
            result: String,
            idGenerator: () -> Long
        ): ChatMessage = ChatMessage(
            id = idGenerator(),
            role = Role.TOOL,
            content = result,
            timestamp = System.currentTimeMillis(),
            toolCallId = toolCallId,
            toolName = toolName
        )

        /**
         * 将参数 Map 序列化为 JSON string（用于 [ToolCallRef.arguments] 字段）。
         *
         * 使用 kotlinx.serialization 递归转换 [Map] → [JsonElement] → JSON string，
         * 支持 String/Number/Boolean/null/嵌套 Map/List。
         *
         * 空参数返回 `"{}"`。
         */
        internal fun encodeArguments(args: Map<String, Any?>): String {
            val element = mapToJsonElement(args)
            return argumentsJson.encodeToString(JsonElement.serializer(), element)
        }

        /**
         * 将 Kotlin 原生值递归转换为 [JsonElement]（[encodeArguments] 的辅助函数）。
         *
         * - null → [JsonNull]
         * - Map → [JsonObject]（递归）
         * - List → [JsonArray]（递归）
         * - String/Number/Boolean → [JsonPrimitive]
         * - 其他 → [JsonPrimitive]（toString 兜底）
         */
        internal fun mapToJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                JsonObject((value as Map<String, Any?>).mapValues { mapToJsonElement(it.value) })
            }
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

        // ==================== 错误格式化（统一文案，便于测试断言） ====================

        /** 用户拒绝执行工具的回灌文案。 */
        internal fun formatRejection(toolName: String): String =
            "用户拒绝执行工具: $toolName"

        /** 工具被禁用（DISABLED 模式）的回灌文案（UXR3 问题 10，ADR-023）。 */
        internal fun formatDisabled(toolName: String): String =
            "工具调用已禁用（请在设置中开启工具审批模式）: $toolName"

        /**
         * 判定工具是否属于低风险白名单（UX-001 问题 9，ADR-021）—— 免审批执行。
         *
         * **设计原则**：仅豁免「只读、高频率、无副作用」的本地工具，
         * 避免确认弹窗轰炸影响体验；任何有副作用（跨 App 打开/分享/选取、
         * 文件系统写入、MCP 自定义工具）的工具仍强制用户确认。
         *
         * **当前白名单**：
         * - `web_search__search`（联网搜索，只读检索）
         *
         * **安全性**：白名单是显式枚举，新增工具需人工评估其副作用后加入；
         * 未知工具名一律返回 false（fail-closed，纵深防御）。
         *
         * **v1 批次11（手机操控豁免）**：`phone_control__*` 虽不在只读白名单，但已在
         * `PhoneControlLocalToolExecutor` 内置**专属安全层**（HighRiskApprovalMode 高危三态 +
         * 敏感节点硬拦截 + 后台确认通知 + take_over 人工接管），不应再经 SkillExecutor 通用
         * 逐次 UI 确认——否则 Prism 切至目标 App 后台时确认框不可见→工具被 UiConfirmationGate
         * 挂起 30s（真机 launch_app 超时根因之一）。
         */
        internal fun isTrustedTool(toolName: String): Boolean =
            toolName in TRUSTED_TOOL_WHITELIST || toolName.startsWith(PHONE_CONTROL_TOOL_PREFIX)

        /** 免审批工具白名单（只读无副作用工具，显式枚举）。 */
        internal val TRUSTED_TOOL_WHITELIST: Set<String> = setOf(
            "web_search__search"
        )

        /** 工具执行超时的回灌文案。 */
        internal fun formatTimeout(toolName: String, ms: Long): String =
            "工具执行超时（${ms}ms）: $toolName"

        /**
         * 工具执行异常的回灌文案（M3：信息脱敏，CWE-209）。
         *
         * - message 非空：经 [sanitizeErrorMessage] 截断 + 路径脱敏
         * - message 为空：回退异常类 simpleName（不泄露堆栈）
         */
        internal fun formatToolError(toolName: String, e: Throwable): String {
            val detail = sanitizeErrorMessage(e.message) ?: e.javaClass.simpleName
            return "工具执行失败: $toolName（$detail）"
        }

        /** 无可用 MCP Server 的回灌文案。 */
        internal fun formatNoServer(toolName: String): String =
            "无可用 MCP Server，无法执行工具: $toolName"

        /**
         * 用户确认环节异常的回灌文案（M3：信息脱敏，CWE-209）。
         *
         * - message 非空：经 [sanitizeErrorMessage] 截断 + 路径脱敏
         * - message 为空：回退异常类 simpleName
         */
        internal fun formatConfirmError(toolName: String, e: Throwable): String {
            val detail = sanitizeErrorMessage(e.message) ?: e.javaClass.simpleName
            return "用户确认失败: $toolName（$detail）"
        }

        /** 默认 id 生成器（时间戳 + 自增序号，避免跨消息 id 冲突）。 */
        private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
        internal fun defaultIdGenerator(): Long =
            System.currentTimeMillis() * 1000 + idCounter.incrementAndGet()
    }

    /**
     * 持久化执行记录（US-029，仅在 [skillExecutionRepository] + skillConfigId + skillName 均非空时）。
     *
     * **设计**：
     * - 调用方在 [executeLoop] 的 finally 块中调用，保证成功/失败/取消路径均记录
     * - repository 为 null 时跳过（向后兼容，测试场景可用 null 关闭记录）
     * - 保存失败不影响主流程（仅记录日志，BR-error-handling-004）
     * - errorMessage 已由调用方经 [Companion.sanitizeErrorMessage] 脱敏（CWE-209）
     *
     * **协程取消安全**（BR-error-handling-007）：
     * 本方法在 finally 块中同步调用（非 suspend），不涉及协程取消传播。
     * repository.save 是同步 ObjectBox put 操作，不会抛 CancellationException。
     */
    private fun saveExecutionRecordIfNeeded(
        skillConfigId: Long?,
        skillName: String?,
        startedAt: Long,
        toolCallRecords: List<ToolCallRecord>,
        finalStatus: String,
        errorMessage: String?
    ) {
        val repo = skillExecutionRepository ?: return
        if (skillConfigId == null || skillName == null) return
        val finishedAt = System.currentTimeMillis()
        val record = SkillExecutionRecord(
            skillConfigId = skillConfigId,
            skillName = skillName,
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = finishedAt - startedAt,
            status = finalStatus,
            toolCalls = toolCallRecords.toList(),
            errorMessage = errorMessage,
            outputPreview = null
        )
        try {
            repo.save(record)
        } catch (e: Exception) {
            // 保存失败不影响主流程，仅记录日志（BR-error-handling-004）
            Log.w(TAG, "save execution record failed: skill=$skillName", e)
        }
    }
}
