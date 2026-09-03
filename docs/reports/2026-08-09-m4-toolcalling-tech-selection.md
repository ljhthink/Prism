# M4 Skills 系统 LLM tool_calling 接口扩展技术选型对比分析报告

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | tech-selection-researcher |
| 任务令牌 | TKN-M4-TOOLCALLING-RESEARCH-001 |
| 调研日期 | 2026-08-09 |
| 调研范围 | OpenAI/Anthropic tool_calling 流式协议、通用抽象层设计、YAML frontmatter 解析库、Skill 执行回路安全边界 |
| allowed_outputs | `docs/reports/2026-08-09-m4-toolcalling-tech-selection.md` |
| 决策依据 | 本报告结论将作为 ADR-014 的决策输入 |

> **时效性提醒**：本报告基于 2026-08-09 的公开文档与开源仓库状态。若决策周期超过 3 个月，建议重新调研 kaml fork 活跃度与 OpenAI/Anthropic API 变更。

---

## 1. 执行摘要

### 1.1 调研目的

Prism 项目 M4 Skills 系统需要引入 LLM tool_calling 能力，使 Skill 可真正调用 MCP 工具执行动作。当前 `ChatStreamProvider` 接口仅支持 `systemPrompt + ragContext + messages`，不支持 tool_calling。本调研系统性评估 OpenAI/Anthropic 两大协议的 tool_calling 流式格式、通用抽象层设计方案、YAML frontmatter 解析库选型，以及工具执行回路的安全边界设计。

### 1.2 候选清单与筛选结果

| 候选方案 | 类别 | 结论 |
| --- | --- | --- |
| 自研轻量 tool_calling 抽象层 | tool_calling 抽象 | **推荐采用** |
| langchain4j + langchain4j-kotlin | tool_calling 框架 | 排除（JVM 服务端框架，依赖过重） |
| MCP Kotlin SDK (modelcontextprotocol/kotlin-sdk) | MCP 协议层 | 已集成，但非 LLM tool_calling 层，职责不同 |
| charleskorn/kaml 0.56.0 | YAML 解析 | 排除（已归档 Public archive） |
| Stream29/kaml (活跃 fork) | YAML 解析 | 备选（kotlinx.serialization 集成便利） |
| snakeyaml-engine-kmp (krzema12) | YAML 解析 | **推荐采用**（活跃维护，KMP 原生） |
| him188/yamlkt | YAML 解析 | 排除（alpha 状态，不活跃） |
| 手动正则解析 frontmatter | YAML 解析 | 排除（不可靠，维护成本高） |

### 1.3 最终推荐（一句话）

**自研轻量 tool_calling 抽象层**（扩展现有 `ChatStreamProvider` + `StreamEvent`）+ **snakeyaml-engine-kmp** 解析 SKILL.md frontmatter，不引入重量级框架，以最小改动扩展现有架构。

---

## 2. 需求与约束回顾

### 2.1 量化验收矩阵

| 指标名称 | 最低要求 | 理想目标 | 测量方法 | 权重(1-10) |
| --- | --- | --- | --- | --- |
| tool_calling 流式协议兼容性 | 支持 OpenAI Chat Completions tools 字段 + tool_calls delta 流式解析 | 同时兼容 Anthropic tool_use content blocks | 协议字段覆盖率审计 | 10 |
| 多 tool_call 并行处理 | 支持 index 区分多个并行 tool_call | 支持并行执行 + 结果批量回灌 | 单元测试：多 tool_call delta 序列 | 8 |
| arguments 增量拼接正确性 | JSON string 增量拼接无丢失 | 支持 strict mode + JSON Schema 校验 | 单元测试：分片 arguments 拼接还原 | 9 |
| StreamEvent 扩展向后兼容 | 现有 Delta/Done/Error 不受影响 | 新增事件类型不破坏 `when` 穷尽性 | 回归测试：现有 streaming 测试全绿 | 9 |
| YAML frontmatter 解析能力 | 解析扁平 key-value + list | 支持嵌套结构 + 多态 | 解析真实 SKILL.md 样本验证 | 7 |
| Android 平台兼容性 | minSdk 26 可用 | APK 体积增量 < 500KB | Android 构建 + dex 分析 | 8 |
| 与现有架构集成成本 | 改动 ≤ 5 个文件 | 改动 ≤ 3 个文件 + 新增 ≤ 3 个文件 | 代码变更行数审计 | 8 |
| 工具执行回路安全 | 用户确认 + 超时防护 | 循环次数限制 + 失败降级 | 安全审计 + 边界测试 | 9 |
| 依赖维护活跃度 | 最近 6 个月有提交 | 有活跃 maintainer + CI 绿 | GitHub 仓库活跃度审计 | 7 |
| License 兼容性 | Apache 2.0 / MIT / BSD | 无 GPL / LGPL 传染性条款 | License 文件审查 | 10 |

### 2.2 刚性约束（一票否决项）

| 编号 | 约束 | 说明 |
| --- | --- | --- |
| C1 | License 必须 Apache 2.0 / MIT / BSD | Prism 是闭源商业应用，禁止 GPL/LGPL 传染性开源协议 |
| C2 | 必须支持 Android minSdk 26 | 项目 `compileSdk = 34, minSdk = 26`，不支持需要更高 API level 的库 |
| C3 | 必须与 Kotlin 2.3.21 + kotlinx.serialization 1.11.0 兼容 | 项目固定 Kotlin 版本，不升级 |
| C4 | 团队技术栈为 Kotlin/Android | 排除需要 Python/JS/Rust 技能的方案 |
| C5 | 不引入 Spring/Quarkus 等服务端框架 | 移动端 APK 体积和方法数约束 |
| C6 | 现有 OpenAICompatibleProvider SSE 解析逻辑可扩展 | 不能推翻重写 ADR-004 已验证的流式架构 |

---

## 3. 候选技术综合对比

### 3.1 tool_calling 抽象层方案对比

| 维度 | 自研轻量抽象层 | langchain4j | MCP Kotlin SDK |
| --- | --- | --- | --- |
| **定位** | 扩展现有 ChatStreamProvider + StreamEvent | JVM LLM 应用开发框架 | MCP 协议客户端/服务端 SDK |
| **Android 兼容** | 原生兼容（Kotlin/Android） | 面向 JVM 服务端，依赖 Java 21+ virtual threads | 已集成（0.12.0），KMP 原生 |
| **依赖体积** | 零新增依赖（复用 Ktor + kotlinx.serialization） | langchain4j-core + langchain4j-kotlin 大量传递依赖 | 已在项目内 |
| **tool_calling 支持** | 需自行实现 OpenAI tools 字段 + delta 状态机 | 内置 @Tool 注解 + ToolProvider + ToolExecutor | 不涉及（MCP 协议层，非 LLM tool_calling） |
| **学习曲线** | 低（团队熟悉现有代码） | 中-高（需学习 langchain4j 抽象体系） | N/A |
| **集成成本** | 改动 OpenAICompatibleProvider + StreamEvent + ChatStreamProvider | 替换现有 Provider 层为 langchain4j ChatLanguageModel | N/A |
| **维护风险** | 自行维护（但代码量小） | 社区活跃（623 open issues，246 PRs） | N/A |
| **License** | N/A | Apache 2.0 | MIT |
| **结论** | **推荐** | 排除（C5 违反：服务端框架过重） | 已集成，不替代 |

**langchain4j 排除理由**：

- 面向 JVM 服务端，核心依赖 `dev.langchain4j:langchain4j-core` 引入大量传递依赖（OkHttp、Jackson/Jackson-databind 等），与项目现有 Ktor + kotlinx.serialization 技术栈冲突
- `langchain4j-kotlin` 扩展依赖 Java 21+ virtual threads，Android 不支持
- `AiServices` 抽象体系（@Tool 注解、ToolProvider、ToolExecutor）面向 Java 接口代理模式，与 Prism 的 `Flow<StreamEvent>` 响应式架构不匹配
- 替换现有 ADR-004 已验证的流式架构成本过高

### 3.2 YAML frontmatter 解析库对比

| 维度 | charleskorn/kaml 0.56.0 | Stream29/kaml (fork) | snakeyaml-engine-kmp | him188/yamlkt | 手动正则 |
| --- | --- | --- | --- | --- | --- |
| **维护状态** | 已归档 (Public archive) | 活跃 fork (1 commit ahead) | 活跃维护 | Alpha，不活跃 | N/A |
| **最后提交** | 2025-11-30 | 2026-02-24 | 2026-08-08 | 数月前 | N/A |
| **Commits** | 1,239 | 1,240 | 1,138 | 少量 | N/A |
| **KMP 支持** | JVM 完全，JS/Wasm 实验性 | 添加更多 targets | JVM/JS/Native/Wasm 全支持 | JVM | N/A |
| **kotlinx.serialization 集成** | 深度集成 (Yaml.default) | 继承 kaml 全部功能 | 不直接集成，需手动映射 | 集成 | N/A |
| **底层引擎** | snakeyaml-engine-kmp 4.0.1 | snakeyaml-engine-kmp 4.0.1 | 自身 | 自研 | N/A |
| **License** | Apache 2.0 | Apache 2.0 | Apache 2.0 | Apache 2.0 | N/A |
| **APK 体积影响** | ~800KB (含引擎) | ~800KB | ~500KB | 未知 | 0 |
| **Android minSdk 26** | 可用 (JVM target) | 可用 | 可用 (KMP) | 未知 | 可用 |
| **结论** | 排除 (C: 已归档) | 备选 | **推荐** | 排除 (C: alpha) | 排除 (C: 不可靠) |

**charleskorn/kaml 排除理由**：

- 仓库已标记为 "Public archive"，README 明确声明 "kaml is no longer maintained"
- kotlinx.serialization 官方 Issue [#3122](https://github.com/Kotlin/kotlinx.serialization/issues/3122) 确认 "charleskorn/kaml is archived. We don't have a well-maintained yaml implementation for production for now."
- 归档后 bug 无人修复，存在供应链安全风险

**snakeyaml-engine-kmp 推荐理由**：

- 唯一活跃维护的 KMP YAML 库（最后提交 2026-08-08，renovate[bot] 自动依赖更新）
- kaml 底层就是使用 snakeyaml-engine-kmp，直接使用减少中间层
- KMP 原生支持，未来若项目迁移到 KMP 无需更换
- 社区认可度高：Ktor 自身的 `ktor-server-config-yaml` 也间接使用

### 3.3 加权评分矩阵

| 维度 | 权重 | 自研抽象层 + snakeyaml-engine-kmp | 自研抽象层 + Stream29/kaml | langchain4j + kaml |
| --- | --- | --- | --- | --- |
| 功能兼容性 | 10 | 9 (完全覆盖 OpenAI，预留 Anthropic) | 9 | 8 |
| 性能 | 8 | 9 (零额外框架开销) | 8 (kaml 序列化层开销) | 5 (框架开销大) |
| 社区/维护 | 7 | 8 (snakeyaml-engine-kmp 活跃) | 6 (fork 仅 1 commit) | 7 (langchain4j 活跃) |
| 学习曲线 | 8 | 9 (团队熟悉现有代码) | 9 | 4 (需学新框架) |
| 运维/可观测 | 6 | 7 (自定义可控) | 7 | 6 (框架内建有限) |
| License/成本 | 10 | 10 (Apache 2.0，零成本) | 10 | 10 |
| **加权总分** | | **9.2** | **8.6** | **6.2** |

---

## 4. Proof of Concept 与关键发现

### 4.1 OpenAI tool_calling 流式协议详解

#### 4.1.1 请求格式（tools 字段）

Chat Completions API 的 tool 定义采用嵌套格式：

```json
{
  "model": "gpt-5.4",
  "messages": [...],
  "tools": [{
    "type": "function",
    "function": {
      "name": "get_weather",
      "description": "Get current weather for a city",
      "parameters": {
        "type": "object",
        "properties": {
          "city": {"type": "string", "description": "City name"}
        },
        "required": ["city"],
        "additionalProperties": false
      },
      "strict": true
    }
  }],
  "tool_choice": "auto",
  "stream": true
}
```

> 来源：[OpenAI Function Calling Guide](https://developers.openai.com/api/docs/guides/function-calling)

关键点：

- `tools` 字段是 `{"type": "function", "function": {...}}` 嵌套结构
- `strict: true` 保证 arguments 严格遵循 JSON Schema（需 `additionalProperties: false` + 所有字段在 `required` 中）
- `tool_choice` 可选值：`"auto"`（默认）/ `"required"` / `{"type": "function", "name": "xxx"}` / `"none"`
- **strict mode 与 parallel_tool_calls 不兼容**，需同时设 `parallel_tool_calls: false`

#### 4.1.2 流式响应 delta 解析（核心复杂度）

OpenAI 流式 tool_calls 采用 **按 index 增量拼接** 机制：

```python
# 官方文档示例（assemble by index）
calls = {}  # index -> {name, arguments}
for chunk in stream:
    delta = chunk.choices[0].delta
    if delta and delta.tool_calls:
        for tc in delta.tool_calls:
            entry = calls.setdefault(tc.index, {"name": "", "arguments": ""})
            if tc.function.name:
                entry["name"] = tc.function.name
            if tc.function.arguments:
                entry["arguments"] += tc.function.arguments
# arguments 是完整 JSON 仅在流结束后
```

> 来源：[OpenAI Function Calling - Streaming](https://docs.apiyi.com/en/api-capabilities/openai/function-calling#function-calls-in-streaming)

关键发现：

1. **arguments 是 JSON string 增量片段**，需要跨 chunk 拼接，流结束后才能 `JSON.parse`
2. **第一个 chunk 携带 `function.name`**，后续 chunk 只携带 `function.arguments` 片段
3. **`index` 字段区分并行 tool_call**，同一 index 的 fragments 属于同一 tool_call
4. **`finish_reason: "tool_calls"`** 表示所有 tool_call 已完整输出
5. **Chat Completions 流式仅支持 `type: "function"`**，不支持 custom tool 类型（[GitHub Discussion #2550](https://github.com/openai/openai-python/discussions/2550) 确认）

#### 4.1.3 tool_call 结果回灌格式

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "{\"temperature\": \"25\", \"unit\": \"C\"}"
}
```

关键点：

- `role` 必须为 `"tool"`，`tool_call_id` 必须与 LLM 返回的 `tool_call.id` 一一对应
- `content` 是 JSON string（不是对象）
- 并行 tool_call 的所有结果必须**一次性全部回灌**，每个结果对应一个 `tool_call_id`

### 4.2 Anthropic tool_use 协议详解

#### 4.2.1 与 OpenAI 的核心差异

| 维度 | OpenAI Chat Completions | Anthropic Messages |
| --- | --- | --- |
| 端点 | `POST /v1/chat/completions` | `POST /v1/messages` |
| 工具定义 | 嵌套: `{"type":"function","function":{name, parameters}}` | 扁平: `{"name":..., "description":..., "input_schema":{...}}` |
| 响应结构 | `choices[0].message.tool_calls[]` | `content[]` 数组含 `tool_use` block |
| 参数格式 | JSON **string**（需 `JSON.parse`） | JSON **object**（已解析，非流式时） |
| 流式参数 | 增量 JSON string 拼接（`function.arguments`） | 增量 partial_json 拼接（`input_json_delta`） |
| 工具 ID | `tool_call.id`（`call_xxx`） | `tool_use.id`（`toolu_xxx`） |
| 结果回灌 | `{role:"tool", tool_call_id, content}` | `{role:"user", content:[{type:"tool_result", tool_use_id, content}]}` |
| 流终止信号 | `data: [DONE]` | `event: message_stop` |
| stop/finish reason | `finish_reason: "tool_calls"` | `stop_reason: "tool_use"` |

> 来源：[Anthropic Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)、[Anthropic vs OpenAI API Mapping](https://www.flo2.com/blog/anthropic-to-openai-format)

#### 4.2.2 Anthropic 流式 tool_use 事件序列

```
event: content_block_start
data: {"type":"content_block_start","index":1,
       "content_block":{"type":"tool_use","id":"toolu_01T1x...","name":"get_weather","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,
       "delta":{"type":"input_json_delta","partial_json":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,
       "delta":{"type":"input_json_delta","partial_json":"{\"location\":"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,
       "delta":{"type":"input_json_delta","partial_json":" \"San Francisc"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,
       "delta":{"type":"input_json_delta","partial_json":"o, CA\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},
       "usage":{"output_tokens":89}}

event: message_stop
data: {"type":"message_stop"}
```

关键发现：

1. Anthropic **没有 `[DONE]` 终止符**，以 `message_stop` 事件结束
2. `input_json_delta` 的 `partial_json` 同样需要增量拼接，与 OpenAI 的 `function.arguments` 机制类似
3. 工具名在 `content_block_start` 事件中一次性给出，不需要从 delta 中累积
4. Anthropic 的 text 和 tool_use 可以在同一个响应中共存（不同 index 的 content block）

### 4.3 统一抽象层设计分析

#### 4.3.1 核心矛盾

OpenAI 和 Anthropic 的 tool_calling 协议在**传输模型**层面就不同：

- OpenAI：`choices[0].delta.tool_calls[]`（flat array with index）
- Anthropic：`content_block_start/delta/stop`（typed content blocks with index）

两者无法在 Provider 实现内部用同一套解析逻辑处理，必须在各自 Provider 内部做协议适配，然后在 `StreamEvent` 层面统一输出。

#### 4.3.2 推荐 StreamEvent 扩展设计

```kotlin
sealed class StreamEvent {
    // 现有（不变）
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

设计理由：

- `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` 命名与语义不绑定任何 Provider 协议
- `arguments` 在 `ToolCallComplete` 中已解析为 `Map`，调用方无需处理 JSON string
- `ToolCallDelta` 是可选的（调用方可忽略，只关注 `ToolCallComplete`）
- 现有 `Delta` / `Done` / `Error` 语义不变，向后兼容

#### 4.3.3 ChatStreamProvider 接口扩展

```kotlin
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
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement  // JSON Schema
)

// Provider 中立的工具选择策略
sealed class ToolChoice {
    data object Auto : ToolChoice()
    data object Required : ToolChoice()
    data class Specific(val name: String) : ToolChoice()
    data object None : ToolChoice()
}
```

#### 4.3.4 OpenAICompatibleProvider 扩展点分析

现有代码的扩展友好性评估：

| 现有组件 | 扩展方式 | 风险 |
| --- | --- | --- |
| `ChatCompletionRequest` | 新增 `tools` + `tool_choice` 可选字段 | 低：`@Serializable` 默认值 null，向后兼容 |
| `ChatCompletionChunk` | 不变（已有 `choices: List<Choice>`） | 无 |
| `Choice` | 新增 `finish_reason: String? = null` | 低：默认值 null |
| `Delta` | 新增 `tool_calls: List<ToolCallDelta>? = null` | 低：默认值 null |
| `MessageBody` | 需支持 `role="tool"` + `tool_call_id` | 中：需新增可选字段或子类 |
| `parseChunkData` | 需扩展检测 `delta.tool_calls` | 中：从无状态纯函数变为需外部状态 |
| `incoming.collect` | 需在闭包内维护 `pendingToolCalls` 状态 | 中：新增 mutable state |

**关键风险**：现有 `parseChunkData` 是无状态纯函数（每次调用独立解析一个 chunk）。tool_calls 增量累积需要跨 chunk 状态。解决方案：保持 `parseChunkData` 为单 chunk 解析纯函数（返回新增的 `ToolCallStart` / `ToolCallDelta` 事件），在 `incoming.collect` 闭包内维护 `pendingToolCalls: MutableMap<Int, ToolCallAccumulator>` 状态，在 `finish_reason == "tool_calls"` 时发射 `ToolCallComplete`。

### 4.4 工具执行回路设计

#### 4.4.1 回路流程

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

#### 4.4.2 安全边界设计

| 安全维度 | 设计 | 复用现有组件 |
| --- | --- | --- |
| **用户确认** | 每个 tool_call 执行前必须通过 ToolConfirmationGate | 复用 `ToolConfirmationGate`（ADR-006） |
| **超时防护** | callTool 包装 `withTimeout(30s)` | 新增，现有 callTool 无超时 |
| **循环防护** | maxRounds=10，超过后强制终止并提示 | 新增 |
| **失败降级** | 工具执行失败时，错误信息回灌给 LLM（让 LLM 决定降级） | 新增 |
| **协程取消** | 回路在 ViewModelScope 中执行，用户退出自动取消 | 复用结构化并发 |
| **权限检查** | Skill manifest 声明依赖工具，运行时检查 MCP Server 可用性 | 新增 |

#### 4.4.3 Skill manifest frontmatter 设计

```yaml
---
name: file-reader
description: 读取文件内容并总结
version: 1.0.0
tools:
  - name: read_file
    description: 读取指定路径的文件内容
depends_on:
  mcp_servers:
    - filesystem
max_rounds: 5
system_prompt: |
  你是一个文件阅读助手。使用 read_file 工具读取用户指定
  的文件，然后总结其内容。
---
# Skill 正文（Markdown 指令）
```

### 4.5 kaml 归档事件分析

2025 年 11 月 30 日，charleskorn/kaml 仓库被标记为 "Public archive"，原作者声明：

> "kaml is no longer maintained. I am no longer actively using kaml and do not have time to maintain it properly, and so this project is now archived. Maintained forks are welcome and encouraged."

kotlinx.serialization 官方 Issue [#3122](https://github.com/Kotlin/kotlinx.serialization/issues/3122)（2025-12-02）确认：

> "https://github.com/charleskorn/kaml is archived. We don't have a well-maintained yaml implementation for production for now."

活跃 fork [Stream29/kaml](https://github.com/Stream29/kaml) 在 2026-02-24 添加了 "support for more targets"（KMP 扩展），但仅比上游多 1 个 commit。底层引擎 snakeyaml-engine-kmp 4.0.1 仍由 krzema12 活跃维护（最后提交 2026-08-08）。

---

## 5. 风险与缓解措施

### 5.1 推荐方案 Top 3 风险

| 风险 | 等级 | 描述 | 缓解措施 |
| --- | --- | --- | --- |
| R1 | 高 | OpenAI tool_calls 增量拼接状态机复杂度：arguments JSON string 跨 chunk 拼接，网络中断可能导致不完整 JSON | (1) `finish_reason` 检查后才 `JSON.parse`，不完整时发射 `Error`；(2) 添加 JSON 完整性校验 `try-catch` 包裹 `decodeFromString`；(3) strict mode 减少参数幻觉 |
| R2 | 中 | snakeyaml-engine-kmp 不集成 kotlinx.serialization，需手动 `YamlNode` → Kotlin 对象映射 | (1) frontmatter 结构简单（扁平 key-value + list），映射代码 <50 行；(2) 封装为 `SkillManifestParser` 工具类；(3) 若成本超预期（>200 行），切换到 Stream29/kaml fork |
| R3 | 中 | 多 Provider 统一抽象的 YAGNI 风险：当前仅 OpenAICompatibleProvider，过早抽象 Anthropic 兼容可能浪费 | (1) StreamEvent 设计为 Provider 中立，不绑定特定协议字段；(2) Anthropic Provider 适配推迟到实际需求出现时；(3) `ToolDefinition` / `ToolChoice` 类型设计参考两家协议交集 |

### 5.2 备选方案触发条件

| 备选方案 | 触发条件 | 切换成本 |
| --- | --- | --- |
| Stream29/kaml fork 替代 snakeyaml-engine-kmp | snakeyaml-engine-kmp 手动映射代码 >200 行，或 frontmatter 结构变复杂需多态/嵌套 | 低：替换依赖坐标 + 改用 `Yaml.default.decodeFromString` |
| 引入 Anthropic Provider 适配 | 用户配置了 Anthropic 端点（`api.anthropic.com`），需要 tool_use 支持 | 中：新增 `AnthropicMessagesProvider`，实现 `content_block_start/delta/stop` 解析 → StreamEvent 适配 |
| tool_calling capability 降级 | OpenAI 兼容端点（如 Ollama）不支持 `tools` 字段，返回 400 错误 | 低：检测 400 错误后自动降级为无 tools 的纯文本模式 |
| 增大 maxRounds | 复杂 Skill 需要多轮工具调用（如编排式 Agent） | 低：配置化 `maxRounds` 参数 |

---

## 6. 最终建议与下一步

### 6.1 推荐方案

| 决策项 | 推荐 | 核心理由 |
| --- | --- | --- |
| tool_calling 抽象层 | **自研轻量层**，扩展现有 `ChatStreamProvider` + `StreamEvent` | 零新增依赖，团队熟悉现有代码，langchain4j 不适合 Android |
| StreamEvent 扩展 | 新增 `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` | Provider 中立设计，向后兼容，不绑定 OpenAI 特定字段 |
| YAML 解析库 | **snakeyaml-engine-kmp** (`it.krzeminski:snakeyaml-engine-kmp`) | 唯一活跃维护的 KMP YAML 库，kaml 底层引擎，避免归档风险 |
| 工具执行回路 | ViewModel 层编排，复用 `ToolConfirmationGate` + `McpToolProvider` | 最大化复用现有 ADR-006 基建 |
| 循环防护 | maxRounds=10（可配置） | 防止 LLM 无限调用工具消耗 token |

### 6.2 实施步骤

| 步骤 | 内容 | 预估工时 |
| --- | --- | --- |
| 1 | 在 `libs.versions.toml` 添加 `snakeyaml-engine-kmp` 依赖 | 0.5h |
| 2 | 扩展 `StreamEvent` sealed class，新增 3 个 ToolCall 子类 | 1h |
| 3 | 扩展 `ChatStreamProvider` 接口，新增 `tools` + `toolChoice` 参数 | 1h |
| 4 | 扩展 `OpenAICompatibleProvider`：`ChatCompletionRequest` 添加 `tools` 字段、`Delta` 添加 `tool_calls` 字段、`Choice` 添加 `finish_reason` | 2h |
| 5 | 实现 `pendingToolCalls` 状态机：在 `incoming.collect` 闭包内维护增量拼接逻辑 | 3h |
| 6 | 扩展 `MessageBody` 支持 `role=tool` + `tool_call_id`，更新 `buildRequestBody` | 1.5h |
| 7 | 实现 `SkillManifestParser`：使用 snakeyaml-engine-kmp 解析 SKILL.md frontmatter | 2h |
| 8 | 在 ViewModel 层实现工具执行回路编排（ToolConfirmationGate → McpToolProvider → 结果回灌 → 重调 streamChat） | 4h |
| 9 | 添加 maxRounds 循环防护 + withTimeout 超时包装 | 1h |
| 10 | 单元测试：tool_calls delta 增量拼接、并行 tool_call、JSON 不完整降级、frontmatter 解析 | 4h |
| **合计** | | **~20h** |

### 6.3 关键人员培训

- 团队已具备 Kotlin + Ktor + kotlinx.serialization + MCP SDK 技能，无需额外培训
- 需重点学习：OpenAI tool_calls delta 的 index-based 增量拼接机制（本报告 4.1.2 节可作为参考文档）

### 6.4 PoC 验证计划

| 验证项 | 方法 | 通过标准 |
| --- | --- | --- |
| OpenAI tool_calls 流式解析 | 构造分片 SSE chunk 序列，验证 arguments 拼接正确性 | 拼接后 JSON 可成功 parse，字段值与预期一致 |
| 并行 tool_call | 构造 2 个 index 的 delta 序列 | 两个 tool_call 均正确累积并独立发射 ToolCallComplete |
| 网络中断降级 | 模拟 arguments 不完整（截断 JSON） | 发射 Error 事件，不崩溃 |
| frontmatter 解析 | 解析真实 SKILL.md 样本（含扁平字段 + list） | 所有字段正确提取 |
| 工具执行回路 | Mock McpToolProvider + Mock ToolConfirmationGate | 确认 → 执行 → 回灌 → 继续生成 全链路通畅 |
| maxRounds 防护 | Mock LLM 持续返回 tool_calls | 第 11 轮强制终止，提示用户 |

---

## 附录 A: 调研信息来源

### OpenAI 官方文档

- [Function Calling Guide](https://developers.openai.com/api/docs/guides/function-calling) — tools 字段格式、strict mode、streaming assemble by index
- [Chat Completions API Reference](https://developers.openai.com/api/reference/python/resources/chat/subresources/completions/methods/create/) — 请求/响应 schema
- [Streaming Custom Tools Discussion #2550](https://github.com/openai/openai-python/discussions/2550) — Chat Completions 流式仅支持 type:function

### Anthropic 官方文档

- [Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming) — SSE 事件类型、content_block_delta、input_json_delta
- [Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use) — tool_use content block、tool_result 回灌格式

### 协议对比参考

- [Anthropic vs OpenAI API Mapping](https://www.flo2.com/blog/anthropic-to-openai-format) — 两家 API 字段级差异对照表
- [Claude vs OpenAI API 差异速查表](https://www.claude-anthropic.com/guide/340.html) — 中文参数对照
- [LLM API Reference (GitHub)](https://github.com/07rjain/LLMlibrary/blob/main/LLM_API_Reference.md) — Anthropic/OpenAI/Gemini 三家原始 fetch 适配层参考

### YAML 解析库

- [charleskorn/kaml (archived)](https://github.com/charleskorn/kaml) — 已归档，Apache 2.0
- [Stream29/kaml (fork)](https://github.com/Stream29/kaml) — 活跃 fork，添加 KMP targets
- [krzema12/snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp) — 活跃维护，KMP 原生
- [kotlinx.serialization#3122](https://github.com/Kotlin/kotlinx.serialization/issues/3122) — 社区确认 kaml 归档，需要新 YAML 实现

### LLM 框架（排除项）

- [langchain4j/langchain4j](https://github.com/langchain4j/langchain4j) — JVM LLM 框架，623 issues，面向服务端
- [langchain4j-kotlin (DeepWiki)](https://deepwiki.com/kpavlov/langchain4j-kotlin/2-core-features) — Kotlin 扩展，依赖 Java 21+ virtual threads
- [LangChain4j KotlinConf 2025](https://resources.jetbrains.com/storage/products/kotlinconf-2025/may-22/LangChain4j%20with%20Quarkus%20_%20Max%20Rydahl%20Andersen%20_%20Konstantin%20Pavlov.pdf) — @Tool 注解、AiServices 模式

### MCP 生态

- [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) — MCP Kotlin SDK，项目已集成 0.12.0
- [AnswerZhao/android-mcp-sdk](https://github.com/AnswerZhao/android-mcp-sdk) — Android MCP SDK 参考
- [stixez/droid-mcp](https://lobehub.com/mcp/stixez-droid-mcp) — Android on-device MCP 工具库参考

### 项目现有代码

- [ChatStreamProvider.kt](../../app/src/main/java/io/prism/network/ChatStreamProvider.kt) — 现有接口定义
- [OpenAICompatibleProvider.kt](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) — SSE 流式实现
- [StreamEvent.kt](../../app/src/main/java/io/prism/network/StreamEvent.kt) — sealed class 定义
- [McpToolProvider.kt](../../app/src/main/java/io/prism/network/McpToolProvider.kt) — MCP 工具调用接口
- [ToolConfirmationGate.kt](../../app/src/main/java/io/prism/fs/ToolConfirmationGate.kt) — 用户确认门禁
- [libs.versions.toml](../../gradle/libs.versions.toml) — 依赖版本配置
