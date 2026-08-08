# ADR-014: M4 LLM tool_calling 接口扩展（US-023/US-024/US-025）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M4「Skills 系统」中 LLM tool_calling 接口扩展的架构决策：StreamEvent 扩展、ChatStreamProvider 接口扩展、OpenAICompatibleProvider 协议实现、工具执行回路、安全边界。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-09 |
| 决策者 | 主 Agent（基于 tech-selection-researcher 选型报告 TKN-M4-TOOLCALLING-RESEARCH-001 + code-archaeologist 考古报告 TKN-M4-SKILLS-ARCH-001 + 用户决策「策略 C 引入 tool_calling」） |
| 关联文档 | [ADR-013](ADR-013-m4-skills-system-architecture.md) / [ADR-004](ADR-004-prism-provider-streaming.md) / [ADR-005](ADR-005-mcp-client-integration.md) / [ADR-006](ADR-006-filesystem-mcp-server.md) / [PRD.md](../PRD.md) US-004 / [prd.json](../../prd.json) US-023~US-025 |
| 上游调研 | [M4 tool_calling 技术选型对比分析报告](../reports/2026-08-09-m4-toolcalling-tech-selection.md) |
| 风险等级 | P2 跨模块（改动 ChatStreamProvider 接口 + StreamEvent sealed + OpenAICompatibleProvider 实现 + ConversationViewModel 工具执行回路） |
| 审查闭环 | 待 guardrail-enforcer + ac-verifier |

## 背景（Context）

用户决策（2026-08-09）：M4 引入 LLM tool_calling，使 Skill 可真正调用 MCP 工具执行动作（策略 C，非纯 prompt 注入）。

code-archaeologist 考古报告（TKN-M4-SKILLS-ARCH-001）揭示：

- **R-2 [高] ChatStreamProvider 不支持 tool_calling**：[ChatStreamProvider.kt](../../app/src/main/java/io/prism/network/ChatStreamProvider.kt) `streamChat(config, messages, systemPrompt, ragContext)` 无 tools 参数；[OpenAICompatibleProvider.kt:250-254](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) `ChatCompletionRequest` 只有 model/messages/stream 三字段；[StreamEvent.kt](../../app/src/main/java/io/prism/network/StreamEvent.kt) 只有 Delta/Done/Error 三子类。
- **R-3 [中] MCP 工具调用基础设施已完整但未接入对话流**：`McpToolProvider.callTool` + `McpToolProviderDispatcher` + `LocalMcpToolProvider` + `McpClientManager` 已实现，仅在 CapabilitiesViewModel「测试连接」使用。

tech-selection-researcher 选型报告（TKN-M4-TOOLCALLING-RESEARCH-001）关键结论：

- **自研轻量 tool_calling 抽象层**（langchain4j 面向 JVM 服务端，依赖 Java 21+ virtual threads，违反 C5 刚性约束）
- **StreamEvent 扩展**：新增 `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete`（Provider 中立，不绑定 OpenAI/Anthropic 特定字段）
- **OpenAI tool_calls 流式协议**：arguments 是 JSON string 增量片段，需跨 chunk 按 index 拼接，`finish_reason=="tool_calls"` 时才可 `JSON.parse`
- **Anthropic tool_use 协议**：content_block_start/delta/stop 事件序列，与 OpenAI 传输模型层面不同，无法用同一套解析逻辑
- **YAGNI 策略**：当前仅 OpenAICompatibleProvider，先为 OpenAI 完整实现 tool_calling，Anthropic 适配推迟到实际需求出现

## 决策（Decision）

### 5.1 StreamEvent 扩展：新增 3 个 ToolCall 子类（Provider 中立）

```kotlin
// StreamEvent.kt
sealed class StreamEvent {
    // 现有（不变，向后兼容）
    data class Delta(val content: String) : StreamEvent()
    data object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()

    // M4 新增（Provider 中立，不绑定 OpenAI/Anthropic 特定字段）
    /** 检测到新 tool_call 开始（第一个 delta 携带工具名时发射） */
    data class ToolCallStart(
        val toolCallId: String,
        val toolName: String,
        val index: Int
    ) : StreamEvent()

    /** tool_call arguments 增量片段（可选，用于 UI 实时展示参数构建过程） */
    data class ToolCallDelta(
        val toolCallId: String,
        val argumentsFragment: String
    ) : StreamEvent()

    /** tool_call 完整可执行（arguments 已解析为 Map，finish_reason=tool_calls 时发射） */
    data class ToolCallComplete(
        val toolCallId: String,
        val toolName: String,
        val arguments: Map<String, Any?>
    ) : StreamEvent()
}
```

**设计理由**：

- 命名与语义不绑定任何 Provider 协议（`ToolCallStart/Delta/Complete` 而非 `OpenAIToolCallDelta`）
- `arguments` 在 `ToolCallComplete` 中已解析为 `Map<String, Any?>`，调用方无需处理 JSON string
- `ToolCallDelta` 是可选的（调用方可忽略，只关注 `ToolCallComplete`）
- 现有 `Delta` / `Done` / `Error` 语义不变，向后兼容（BR-interface-004 历史过滤器不受影响）
- `when` 穷尽性：调用方需显式处理 6 个分支（或用 `else`），避免遗漏新事件

### 5.2 ChatStreamProvider 接口扩展：新增 tools + toolChoice 参数

```kotlin
// ChatStreamProvider.kt
interface ChatStreamProvider {
    fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        ragContext: String? = null,
        // M4 新增
        tools: List<ToolDefinition>? = null,
        toolChoice: ToolChoice? = null
    ): Flow<StreamEvent>
}

// Provider 中立的工具定义
@Serializable
data class ToolDefinition(
    val type: String = "function",  // OpenAI 固定 "function"
    val function: FunctionDef
) {
    @Serializable
    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: JsonElement  // JSON Schema
    )
}

// Provider 中立的工具选择策略
sealed class ToolChoice {
    data object Auto : ToolChoice()        // LLM 自主决定
    data object Required : ToolChoice()    // 强制调用工具
    data class Specific(val name: String) : ToolChoice()  // 指定工具
    data object None : ToolChoice()        // 禁止调用
}
```

**设计理由**：

- `tools` 和 `toolChoice` 默认 null，向后兼容（既有调用零改动，BR-interface-004 历史过滤器不受影响）
- `ToolDefinition` 采用 OpenAI 嵌套结构（`type + function`），因当前仅 OpenAICompatibleProvider；Anthropic 适配时在 Provider 内部转换
- `ToolChoice` 用 sealed class 表达穷尽分支（仿 RagTarget 模式）
- `parameters` 用 `JsonElement` 而非具体数据类，因 JSON Schema 结构灵活

### 5.3 OpenAICompatibleProvider tool_calling 协议实现

#### 5.3.1 ChatCompletionRequest 扩展

```kotlin
// OpenAICompatibleProvider.kt
@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<MessageBody>,
    val stream: Boolean,
    // M4 新增（可选，默认 null）
    val tools: List<ToolDefinition>? = null,
    val toolChoice: ToolChoiceSerial? = null,
    val parallelToolCalls: Boolean? = null  // strict mode 时需 false
)

// ToolChoice 序列化形式
@Serializable
private sealed class ToolChoiceSerial {
    @Serializable(with = ToolChoiceSerializer::class)
    data class Wrapper(val choice: ToolChoice) : ToolChoiceSerial()
}

// MessageBody 扩展支持 role=tool
@Serializable
private data class MessageBody(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,         // role=tool 时必填
    val toolCalls: List<ToolCallRef>? = null  // assistant 消息携带 tool_calls 引用
)

@Serializable
private data class ToolCallRef(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
private data class FunctionCall(
    val name: String,
    val arguments: String  // JSON string
)
```

#### 5.3.2 流式响应解析：delta 状态机

**核心复杂度**：OpenAI tool_calls delta 按 index 增量拼接，需跨 chunk 维护状态。

```kotlin
// OpenAICompatibleProvider.kt - streamChat 内 incoming.collect 闭包
flow {
    val pendingToolCalls = mutableMapOf<Int, ToolCallAccumulator>()
    var sawDoneMarker = false

    httpClient.sse(endpoint, { /* headers */ }) { incoming ->
        incoming.collect { event ->
            when (event) {
                is ServerSentEvent -> {
                    val data = event.data
                    if (data == "[DONE]") {
                        sawDoneMarker = true
                        return@collect
                    }
                    val chunk = parseChunkData(data) ?: return@collect
                    val choice = chunk.choices.firstOrNull() ?: return@collect

                    // 1. 处理文本 delta
                    choice.delta?.content?.let { emit(StreamEvent.Delta(it)) }

                    // 2. 处理 tool_calls delta（增量拼接）
                    choice.delta?.toolCalls?.forEach { tc ->
                        val acc = pendingToolCalls.getOrPut(tc.index) {
                            ToolCallAccumulator(id = tc.id ?: "", name = "", arguments = "")
                        }
                        tc.function?.name?.let { acc.name = it }
                        tc.function?.arguments?.let { acc.arguments += it }
                        // 首次见到该 index 且有 name，发射 ToolCallStart
                        if (acc.name.isNotEmpty() && !acc.startEmitted) {
                            emit(StreamEvent.ToolCallStart(acc.id, acc.name, tc.index))
                            acc.startEmitted = true
                        }
                        // 发射 ToolCallDelta（可选，UI 实时展示）
                        tc.function?.arguments?.let {
                            emit(StreamEvent.ToolCallDelta(acc.id, it))
                        }
                    }

                    // 3. finish_reason == "tool_calls" 时发射 ToolCallComplete
                    if (choice.finishReason == "tool_calls") {
                        pendingToolCalls.forEach { (index, acc) ->
                            val args = try {
                                Json.decodeFromString<Map<String, Any?>>(acc.arguments)
                            } catch (e: Exception) {
                                // R1 缓解：不完整 JSON 降级为 Error
                                emit(StreamEvent.Error("工具参数解析失败: ${acc.name}"))
                                return@forEach
                            }
                            emit(StreamEvent.ToolCallComplete(acc.id, acc.name, args))
                        }
                        pendingToolCalls.clear()
                    }
                }
                else -> { /* ignore */ }
            }
        }
    }
    if (!sawDoneMarker) emit(StreamEvent.Done)
    else emit(StreamEvent.Done)
}.flowOn(Dispatchers.IO)

private data class ToolCallAccumulator(
    var id: String,
    var name: String,
    var arguments: String,
    var startEmitted: Boolean = false
)
```

**关键设计**：

- `pendingToolCalls: MutableMap<Int, ToolCallAccumulator>` 在 collect 闭包内维护跨 chunk 状态
- `parseChunkData` 保持纯函数（单 chunk 解析），状态累积在闭包内
- `finish_reason == "tool_calls"` 时才 `JSON.parse` arguments（避免不完整 JSON）
- JSON 解析失败降级为 `Error` 事件（R1 缓解），不崩溃
- `ToolCallStart` 在首次见到 name 时发射（UI 可立即展示"正在调用 X"）
- `ToolCallDelta` 每个 arguments 片段都发射（UI 可实时展示参数构建，可选）

#### 5.3.3 tool result 回灌

```kotlin
// ConversationViewModel 工具执行回路内
fun appendToolResult(toolCallId: String, toolName: String, result: String) {
    _messages.update { current ->
        current + ChatMessage(
            id = nextId++,
            role = Role.TOOL,  // 新增 Role.TOOL
            content = result,
            toolCallId = toolCallId,
            toolName = toolName,
            timestamp = System.currentTimeMillis()
        )
    }
}

// 构建下次请求的 history 时，tool 消息转换为 MessageBody(role="tool", toolCallId=...)
// assistant 消息若含 tool_calls，需携带 toolCalls 字段
```

### 5.4 工具执行回路：ViewModel 层编排

**回路流程**（详见选型报告 4.4.1）：

```text
用户发送消息
  │
  ▼
ViewModel 调用 streamChat(messages, tools=[Skill声明的工具])
  │
  ▼
Flow<StreamEvent> 消费
  ├─ Delta → 正常文本流式展示
  ├─ ToolCallStart → UI 显示"正在调用工具: {toolName}"
  ├─ ToolCallDelta → (可选) UI 实时展示参数构建
  ├─ ToolCallComplete → 触发工具执行回路 ──┐
  ├─ Done → 流结束                          │
  └─ Error → 错误处理                       │
                                            ▼
                                   ┌─ ToolConfirmationGate.confirm(name, args)
                                   │   ├─ 用户确认 → McpToolProvider.callTool(config, name, args)
                                   │   │              ├─ 成功 → 结果作为 tool message 回灌
                                   │   │              └─ 失败 → 错误信息作为 tool result 回灌
                                   │   └─ 用户拒绝 → 拒绝信息作为 tool result 回灌
                                   │
                                   ▼
                           将 tool result 追加到 messages
                           (role=tool, tool_call_id=id, content=result)
                                   │
                                   ▼
                           再次调用 streamChat(messages=更新后, tools=[...])
                           (LLM 基于工具结果继续生成)
                                   │
                                   ├─ 可能再次 ToolCallComplete → 回到回路顶部
                                   └─ 最终 Delta → 正常文本展示 → Done
```

**SkillExecutor 设计**：

```kotlin
// SkillExecutor.kt
class SkillExecutor(
    private val mcpToolProvider: McpToolProviderDispatcher,
    private val confirmationGate: UiConfirmationGate,
    private val skillRepository: SkillRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * 执行单个 tool_call：用户确认 → MCP 调用 → 结果回灌
     * @return 工具执行结果（成功或失败的字符串）
     */
    suspend fun executeToolCall(
        toolCall: StreamEvent.ToolCallComplete,
        mcpServers: List<McpServerConfig>,
        maxTimeoutMs: Long = 30_000
    ): String = withContext(ioDispatcher) {
        // 1. 用户确认（复用 ToolConfirmationGate）
        val approved = try {
            confirmationGate.confirm(toolCall.toolName, toolCall.arguments).await()
        } catch (e: CancellationException) {
            throw e  // BR-error-handling-007：重抛 CancellationException
        } catch (e: Exception) {
            return@withContext "用户确认失败: ${e.message}"
        }
        if (!approved) return@withContext "用户拒绝执行工具 $${toolCall.toolName}"

        // 2. 查找可用 MCP Server
        val mcpServer = mcpServers.firstOrNull { it.isEnabled }
            ?: return@withContext "无可用 MCP Server"

        // 3. 调用工具（超时防护）
        return@withContext try {
            withTimeout(maxTimeoutMs) {
                val result = mcpToolProvider.callTool(
                    config = mcpServer,
                    name = toolCall.toolName.substringAfter("__"),  // 去命名空间前缀
                    arguments = toolCall.arguments
                )
                result.content
            }
        } catch (e: TimeoutCancellationException) {
            "工具执行超时（${maxTimeoutMs}ms）"
        } catch (e: CancellationException) {
            throw e  // BR-error-handling-007
        } catch (e: Exception) {
            "工具执行失败: ${e.message}"
        }
    }

    /**
     * 编排完整工具执行回路（maxRounds 防护）
     */
    suspend fun executeLoop(
        provider: ChatStreamProvider,
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        ragContext: String?,
        tools: List<ToolDefinition>,
        mcpServers: List<McpServerConfig>,
        maxRounds: Int = 10,
        onEvent: (StreamEvent) -> Unit
    ): List<ChatMessage> = withContext(ioDispatcher) {
        var currentMessages = messages
        var rounds = 0
        while (rounds < maxRounds) {
            rounds++
            var hasToolCall = false
            val toolResults = mutableListOf<Pair<String, String>>()

            provider.streamChat(config, currentMessages, systemPrompt, ragContext, tools, ToolChoice.Auto)
                .collect { event ->
                    onEvent(event)
                    when (event) {
                        is StreamEvent.ToolCallComplete -> {
                            hasToolCall = true
                            val result = executeToolCall(event, mcpServers)
                            toolResults.add(event.toolCallId to result)
                        }
                        else -> { /* 其他事件已通过 onEvent 传递 */ }
                    }
                }

            if (!hasToolCall) break  // 无工具调用，回路结束

            // 回灌 tool results
            toolResults.forEach { (toolCallId, result) ->
                currentMessages = currentMessages + ChatMessage(
                    id = 0L,  // 由调用方分配
                    role = Role.TOOL,
                    content = result,
                    toolCallId = toolCallId,
                    timestamp = System.currentTimeMillis()
                )
            }
        }
        if (rounds >= maxRounds) {
            onEvent(StreamEvent.Error("工具调用循环达上限 $maxRounds，已终止"))
        }
        currentMessages
    }
}
```

### 5.5 安全边界设计

| 安全维度 | 设计 | 复用现有组件 |
| --- | --- | --- |
| **用户确认** | 每个 tool_call 执行前必须通过 ToolConfirmationGate | 复用 `ToolConfirmationGate`（ADR-006） |
| **超时防护** | callTool 包装 `withTimeout(30s)` | 新增，现有 callTool 无超时 |
| **循环防护** | maxRounds=10（per-Skill 可配置），超过后强制终止并提示 | 新增 |
| **失败降级** | 工具执行失败时，错误信息回灌给 LLM（让 LLM 决定降级） | 新增 |
| **协程取消** | 回路在 ViewModelScope 中执行，用户退出自动取消；CancellationException 重抛（BR-error-handling-007） | 复用结构化并发 |
| **权限检查** | Skill manifest 声明 `dependsOnMcpServers`，运行时检查 MCP Server 可用性 | 新增 |
| **命名空间隔离** | tool name 格式 `skillName__toolName`，执行时去前缀 | 新增 |
| **strict mode** | OpenAI tools 设 `strict: true` + `parallel_tool_calls: false`，减少参数幻觉 | 新增 |

### 5.6 ChatMessage 扩展：新增 Role.TOOL + toolCallId

```kotlin
// ChatMessage.kt
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }  // 新增 TOOL

data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val sources: List<Citation> = emptyList(),  // RAG 引用（ADR-012）
    // M4 新增
    val toolCallId: String? = null,             // role=TOOL 时必填
    val toolName: String? = null,               // role=TOOL 时的工具名
    val toolCalls: List<ToolCallRef>? = null    // role=ASSISTANT 携带的 tool_calls 引用
)
```

**历史过滤器扩展**（BR-interface-004）：

```kotlin
// ConversationViewModel 构建请求 history 时
val history = _messages.value.filterNot {
    it.id == aiId || (it.role == Role.ASSISTANT && it.content.isEmpty())
}
// tool 消息保留（role=TOOL），不排除
```

### 5.7 降级策略：tool_calling 失败不影响主对话

| 失败场景 | 降级行为 | 用户感知 |
|---|---|---|
| OpenAI 兼容端点不支持 tools 字段（返回 400） | 检测 400 错误，自动降级为无 tools 的纯文本模式重试 | UI 提示"当前 Provider 不支持工具调用，已降级为普通对话" |
| 工具执行失败 | 错误信息回灌给 LLM，LLM 决定降级 | AI 自然说明"工具调用失败，无法完成" |
| 工具执行超时 | 超时信息回灌 | AI 说明"工具执行超时" |
| 用户拒绝工具调用 | 拒绝信息回灌 | AI 说明"用户拒绝执行" |
| maxRounds 超限 | 强制终止，提示用户 | UI 提示"工具调用循环达上限" |
| tool_calls delta 解析失败 | 发射 Error 事件，不崩溃 | UI 显示错误 |
| 整个 tool_calling 异常 | try-catch 兜底，退化为普通对话 | 日志记录，用户无感 |

**核心原则**：tool_calling 失败不影响基础对话，用户始终能得到 AI 回复（与 RAG 降级策略一致，ADR-012 5.5）

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **langchain4j + langchain4j-kotlin** | 内置 @Tool 注解 + ToolProvider + ToolExecutor | 面向 JVM 服务端，依赖 Java 21+ virtual threads（Android 不支持），与 Ktor + kotlinx.serialization 技术栈冲突，违反 C5 刚性约束 |
| **MCP Kotlin SDK 作为 tool_calling 层** | 已集成 | MCP 是协议层（client-server），非 LLM tool_calling 层，职责不同 |
| **StreamEvent 单一 ToolCall 事件（非 Start/Delta/Complete 三态）** | 简化 | 无法支持 UI 实时展示工具调用进度；arguments 需在事件外累积，职责不清 |
| **ToolChoice 用枚举而非 sealed class** | 简化 | 无法表达 `Specific(name)` 携带参数的分支 |
| **Anthropic 立即适配** | 多 Provider 支持 | YAGNI：当前仅 OpenAICompatibleProvider，过早抽象浪费；StreamEvent 已设计中立，适配可推迟 |
| **tool result 不回灌，仅展示** | 简化回路 | LLM 无法基于工具结果继续生成，失去 tool_calling 核心价值 |

## 后果（Consequences）

- **正面后果**：
  - ChatStreamProvider 接口支持 tool_calling，Skill 可真正调用 MCP 工具
  - StreamEvent 扩展为 6 子类，覆盖文本流 + 工具调用全生命周期
  - 工具执行回路复用 ToolConfirmationGate + McpToolProvider，最大化复用现有基建
  - 降级策略保证 tool_calling 失败不影响基础对话
  - Provider 中立设计，未来 Anthropic 适配无需改 StreamEvent

- **负面后果 / 代价**：
  - ChatStreamProvider 接口变更（新增 tools + toolChoice 参数，默认 null 向后兼容）
  - OpenAICompatibleProvider 实现复杂度增加（delta 状态机 + 并行 tool_call + 结果回灌）
  - ConversationViewModel 新增工具执行回路编排（maxRounds 循环 + 超时防护）
  - tool_calling 协议实现依赖 OpenAI 兼容端点支持（Ollama 等可能不支持，需降级）
  - tool result 回灌增加 token 消耗（每轮工具结果多 ~200 token）

- **需要同步更新的文档或代码**：
  - `StreamEvent.kt`（新增 3 子类）
  - `ChatStreamProvider.kt`（接口扩展）
  - `OpenAICompatibleProvider.kt`（tool_calling 协议实现）
  - `ChatMessage.kt`（新增 Role.TOOL + toolCallId + toolCalls）
  - `ConversationViewModel.kt`（工具执行回路编排）
  - `SkillExecutor.kt`（新增组件）
  - `PrismApplication.kt`（新增 skillExecutor lazy 依赖）
  - [behavioral-rules.md](../behavioral-rules.md)（新增 BR-error-handling-008 tool_calling 降级规则）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| OpenAI tool_calls 增量拼接状态机复杂度（R1） | 高 | finish_reason 检查后才 JSON.parse；不完整时发射 Error；strict mode 减少幻觉；单元测试覆盖分片序列 |
| 并行 tool_call 的 index 竞态 | 中 | pendingToolCalls 用 MutableMap<Int, Acc>，按 index 隔离；finish_reason=="tool_calls" 时统一发射 |
| 网络中断导致不完整 JSON | 中 | try-catch 包裹 decodeFromString，降级为 Error 事件 |
| maxRounds 无限循环 | 中 | maxRounds=10 强制终止；per-Skill 可配置；UI 提示用户 |
| Ollama 等端点不支持 tools 字段 | 中 | 检测 400 错误自动降级为无 tools 模式重试 |
| tool result 回灌 token 消耗 | 低 | 监控 token 消耗；超长 result 截断（前 1000 字符 + "..."） |
| Anthropic 适配推迟风险 | 低 | StreamEvent 已 Provider 中立；ToolDefinition 可在 Provider 内部转换；YAGNI 策略 |

## 参考

- [M4 tool_calling 技术选型对比分析报告](../reports/2026-08-09-m4-toolcalling-tech-selection.md)
- [M4 Skills 集成点源码考古报告](../reports/2026-08-09-m4-skills-archaeology.md)
- [OpenAI Function Calling Guide](https://developers.openai.com/api/docs/guides/function-calling)
- [OpenAI Function Calling - Streaming](https://docs.apiyi.com/en/api-capabilities/openai/function-calling#function-calls-in-streaming)
- [Anthropic Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [Anthropic Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use)
- [Anthropic vs OpenAI API Mapping](https://www.flo2.com/blog/anthropic-to-openai-format)
- [Streaming Custom Tools Discussion #2550](https://github.com/openai/openai-python/discussions/2550)
- [ADR-004 OpenAI 兼容 Provider 流式请求](ADR-004-prism-provider-streaming.md)
- [ADR-005 MCP Client 集成](ADR-005-mcp-client-integration.md)
- [ADR-006 内置 Filesystem MCP Server](ADR-006-filesystem-mcp-server.md)
- [ADR-012 M3 RAG 对话集成](ADR-012-m3-rag-conversation-integration.md)
- [ADR-013 M4 Skills 系统架构](ADR-013-m4-skills-system-architecture.md)
- [behavioral-rules.md](../behavioral-rules.md) BR-error-handling-007 / BR-interface-004 / BR-concurrency-004
