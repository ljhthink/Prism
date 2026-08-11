package io.prism.skill

import android.util.Log
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
    private val localToolExecutor: LocalToolExecutor? = null
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
        val approved = try {
            confirmationGate.confirm(toolCall.toolName, toolCall.arguments)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // M4：结构化日志（BR-error-handling-004），便于生产环境定位用户确认失败根因
            Log.w(TAG, "tool confirm failed: ${toolCall.toolName}", e)
            return@withContext formatConfirmError(toolCall.toolName, e)
        }
        if (!approved) return@withContext formatRejection(toolCall.toolName)

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

        // 3. MCP 工具路径（原有逻辑不变）
        val mcpServer = selectMcpServer(mcpServers)
            ?: return@withContext formatNoServer(toolCall.toolName)

        // 4. 调用工具（超时防护 + 命名空间剥离）
        val physicalName = stripNamespace(toolCall.toolName)
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
        onEvent: (StreamEvent) -> Unit
    ): List<ChatMessage> = withContext(ioDispatcher) {
        // US-029 执行可观测：记录 startedAt / toolCalls / status / errorMessage
        val startedAt = System.currentTimeMillis()
        val toolCallRecords = mutableListOf<ToolCallRecord>()
        var finalStatus = ExecutionStatus.SUCCESS
        var errorMessage: String? = null

        try {
            var currentMessages = messages
            var rounds = 0
            var lastRoundHadToolCall = false

            while (rounds < maxRounds) {
                rounds++
                lastRoundHadToolCall = false
                val completedToolCalls = mutableListOf<StreamEvent.ToolCallComplete>()

                // 1. 流式请求 + 收集 ToolCallComplete
                val flow: Flow<StreamEvent> = try {
                    provider.streamChat(
                        config = config,
                        messages = currentMessages,
                        systemPrompt = systemPrompt,
                        ragContext = ragContext,
                        tools = tools,
                        toolChoice = ToolChoice.Auto
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
                        onEvent(event)
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

                // 2. 无工具调用 → 回路自然结束（纯文本响应）
                if (completedToolCalls.isEmpty()) break
                lastRoundHadToolCall = true

                // 3. 追加 assistant 占位消息（携带 toolCalls 引用，OpenAI 要求下次请求回放）
                val assistantPlaceholder = buildAssistantToolCallMessage(completedToolCalls, idGenerator)
                currentMessages = currentMessages + assistantPlaceholder

                // 4. 串行执行所有 tool_call + 回灌结果
                //    每个工具的失败/超时/拒绝均降级为描述性字符串回灌（ADR-014 5.7）
                //    US-029：同时记录 ToolCallRecord 用于执行可观测
                for (toolCall in completedToolCalls) {
                    val toolStart = System.currentTimeMillis()
                    var toolStatus = ExecutionStatus.SUCCESS
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
                    if (isFailureResult(result)) {
                        toolStatus = ExecutionStatus.FAIL
                    }
                    val toolDuration = System.currentTimeMillis() - toolStart
                    toolCallRecords.add(
                        ToolCallRecord(
                            toolName = toolCall.toolName,
                            arguments = encodeArguments(toolCall.arguments),
                            result = result.take(MAX_RESULT_PREVIEW_LEN),
                            durationMs = toolDuration,
                            status = toolStatus
                        )
                    )
                    val toolResultMessage = buildToolResultMessage(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        result = result,
                        idGenerator = idGenerator
                    )
                    currentMessages = currentMessages + toolResultMessage
                }
                // 5. 继续下一轮（LLM 基于 tool result 继续生成）
            }

            // 6. maxRounds 超限提示（仅当最后一轮有工具调用却已达上限时）
            if (shouldEmitMaxRoundsError(lastRoundHadToolCall, rounds, maxRounds)) {
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

        /** 工具执行回路默认最大轮数（防止无限循环，ADR-014 5.5）。 */
        internal const val DEFAULT_MAX_ROUNDS = 10

        /** 命名空间分隔符（`skillName__toolName`）。 */
        internal const val NAMESPACE_SEPARATOR = "__"

        /** 日志 TAG（M4 结构化日志，BR-error-handling-004）。 */
        private const val TAG = "SkillExecutor"

        /** 异常 message 截断长度上限（M3，CWE-209 信息泄露纵深防御）。 */
        internal const val MAX_ERROR_MESSAGE_LEN = 200

        /** 工具调用结果预览长度上限（US-029，ToolCallRecord.result 截断）。 */
        internal const val MAX_RESULT_PREVIEW_LEN = 200

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

        /**
         * 剥离工具名命名空间前缀（`skillName__toolName` → `toolName`）。
         *
         * Skill 声明的工具名带 skill 命名空间前缀以避免跨 Skill 同名冲突，
         * 调用 MCP Server 时需剥离前缀（MCP Server 不感知 Skill 层命名空间）。
         *
         * 无前缀时原样返回（向后兼容）。
         */
        internal fun stripNamespace(toolName: String): String =
            toolName.substringAfter(NAMESPACE_SEPARATOR)

        /**
         * 选择第一个启用的 MCP Server（按列表顺序优先）。
         *
         * @return 第一个 isEnabled 的 Server；无则 null
         */
        internal fun selectMcpServer(mcpServers: List<McpServerConfig>): McpServerConfig? =
            mcpServers.firstOrNull { it.isEnabled }

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
                result.startsWith("未找到应用配置") ||
                result.startsWith("未安装") ||
                result.startsWith("跨 App 调用超时") ||
                result.startsWith("缺少必需参数") ||
                result.startsWith("不支持的媒体类型") ||
                result.startsWith("未知跨 App 工具")

        /**
         * 构造 assistant 占位消息（携带 toolCalls 引用，OpenAI 要求下次请求回放）。
         *
         * content 为空字符串（OpenAI 允许 assistant 空 content + tool_calls）。
         * toolCalls 字段携带所有完成的 tool_call 引用（id/name/arguments JSON string）。
         *
         * @param toolCalls 本轮 LLM 返回的所有 ToolCallComplete 事件
         * @param idGenerator ChatMessage id 生成器
         */
        internal fun buildAssistantToolCallMessage(
            toolCalls: List<StreamEvent.ToolCallComplete>,
            idGenerator: () -> Long
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
                toolCalls = refs
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
