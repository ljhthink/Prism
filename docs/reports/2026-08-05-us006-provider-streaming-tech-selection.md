# US-006 OpenAI 兼容 Provider 流式请求技术选型对比分析报告

| 元信息 | 内容 |
|---|---|
| 报告类型 | 技术选型对比分析报告（Technical Selection Comparative Analysis） |
| 生成日期 | 2026-08-05 |
| 作者 | tech-selection-researcher 子 Agent |
| 调研方法 | 四阶段法：定标尺 → 广撒网 → 深验证 → 出报告（强制 web-access 联网搜索 + mcp_context7 官方文档查询） |
| 调研范围 | US-006「实现 OpenAI 兼容 Provider 流式请求」：HTTP 客户端 / SSE 解析 / OpenAI 流式格式 / JSON 序列化 / 依赖版本 / 错误处理 / 测试隔离 |
| 任务令牌 | TKN-PRISM-US006-STREAMING-001 |
| 执行 Agent | tech-selection-researcher |
| 状态 | 定稿（可作为后续 ADR 的输入） |
| 信息时效提醒 | 本报告基于 2026-08-05 联网搜索结果。Ktor、kotlinx-serialization、coroutines 均快速迭代，若决策时间超过 3 个月建议重新调研版本。 |

---

## 1. 执行摘要

### 1.1 调研目的

为 Prism（仅 Android、Kotlin 2.1.0 + Jetpack Compose，纯云端 BYOK 的 AI 聊天 Agent）的 **US-006「实现 OpenAI 兼容 Provider 流式请求」** 完成 HTTP 客户端、SSE 解析、JSON 序列化、依赖版本、错误处理与测试隔离的技术选型，满足「首字延迟 <1s、token 实时更新、端点不可达显示错误不崩溃、流式中断处理」四项硬性验收要求。

### 1.2 候选清单

| 课题 | 候选方案 |
|---|---|
| 1. HTTP 客户端 | Ktor Client vs OkHttp3 vs Retrofit |
| 2. SSE 解析 | Ktor SSE 插件（`io.ktor.client.plugins.sse`）vs OkHttp 手动解析 vs okhttp-sse 模块 |
| 3. OpenAI 流式格式 | chat/completions SSE chunk 结构 + `[DONE]` 终止 + 兼容 Provider |
| 4. JSON 序列化 | kotlinx.serialization vs Moshi vs Gson |
| 5. 依赖版本 | Ktor / kotlinx-serialization / coroutines 与 Kotlin 2.1.0 兼容 |
| 6. 错误处理 | Result 封装 vs 异常 |
| 7. 测试隔离 | Ktor MockEngine 模拟流式响应 |

### 1.3 最终推荐（一句话）

**HTTP 客户端用 Ktor Client（3.1.3，Android 用 OkHttp engine）+ 官方 SSE 插件（`io.ktor.client.plugins.sse`）+ kotlinx.serialization（1.8.1，Kotlin 2.1.0 兼容）编译时序列化，流式结果用密封类 StreamEvent + Flow 封装，测试用 `ktor-client-mock` 的 MockEngine 以 `ByteReadChannel` 注入 `text/event-stream` 响应。**

> **核心决策约束**：项目 ADR-001 已锁定 **Kotlin 2.1.0 / coroutines 依赖 / compileSdk 34**，而最新 Ktor 3.5.x 以 Kotlin 2.4 构建（元数据不可被 2.1.0 编译器读取），因此**必须选用以 Kotlin 2.1.0 构建的 Ktor 3.1.x 系列（最新补丁 3.1.3）**，而非最新版。详见课题 5。

---

## 2. 需求与约束回顾

### 2.1 量化验收矩阵（Phase 1 产出）

| 指标名称 | 最低要求 | 理想目标 | 测量方法 | 权重(1-10) |
|---|---|---|---|---|
| 首字延迟（首个 token 到 UI） | <1000ms | <500ms | 端侧计时（连接→首个 delta.content） | 10 |
| token 实时更新 | 每 token 到达即消费 | 无帧卡顿（Compose 分窗批量） | Profiler recomposition 帧率 | 9 |
| 端点不可达不崩溃 | 显示错误状态，App 存活 | 降级提示 + 可重试 | 断网/超时注入测试 | 10 |
| 流式中断处理 | 保留已收内容 + 标记中断 | 自动重连（指数退避） | 中途断流注入测试 | 9 |
| 401 / 鉴权失败 | 明确映射为鉴权错误 | 引导重新填 Key | Mock 401 响应测试 | 8 |
| JSON 解析正确性 | chunk 解析零崩溃 | 兼容 role/content/finish_reason | 单元测试覆盖率 | 9 |
| 与 Kotlin 2.1.0 元数据兼容 | 编译通过 | 无 bin-compat 告警 | `assembleDebug` + dependencyInsight | 10 |
| 可测试性（网络隔离） | MockEngine 可 mock 流式 | 不依赖真实网络 | 单元测试 | 8 |
| 维护活跃度 | 近 6 月有 release | 官方/主流维护 | GitHub 活跃度核查 | 7 |

### 2.2 刚性约束（一票否决项）

| # | 约束 | 理由 |
|---|---|---|
| C1 | **Kotlin 编译器锁定 2.1.0**（ADR-001） | 依赖库元数据必须能被 Kotlin 2.1.0 读取（≤2.2 元数据），否则编译失败 |
| C2 | **compileSdk 34 / minSdk 26 / JVM 17** | 环境仅装 android-34 平台，新依赖不得要求更高 compileSdk |
| C3 | **License 必须 Apache 2.0 / MIT / BSD**（商业闭源友好） | 项目 Apache 2.0，避免 GPL 传染 |
| C4 | **与 MCP Kotlin SDK 0.12.0 共享 Ktor 栈** | MCP SDK 依赖 Ktor 3.x（`ktor-server-* 3.0.2` 传递），Prism 需自行声明 Ktor engine，版本须一致 |
| C5 | **协程/Flow 原生支持** | 流式拉取必须用 Flow 表达，避免回调地狱 |
| C6 | **严格版本控制**（CLAUDE.md 十八节 P0 依赖） | 新增 P0 依赖必须写 ADR、锁版本、手动审查升级 |

---

## 3. 候选方案综合对比

### 3.1 课题 1：HTTP 客户端

#### 3.1.1 候选清单与过滤

| 候选 | 语言 | License | 最后更新 | 过滤结果 |
|---|---|---|---|---|
| **Ktor Client** | Kotlin | Apache 2.0 | 活跃（2026-08 3.5.2） | 保留（SSE 原生 + Flow + 与 MCP SDK 同栈） |
| **OkHttp3** | Java/Kotlin | Apache 2.0 | 活跃（5.3.2） | 保留（SSE 需手动解析） |
| **Retrofit** | Java | Apache 2.0 | 活跃 | 否决（流式支持弱，非 Flow 原生） |
| Ktor CIO engine | Kotlin | Apache 2.0 | 活跃 | 否决（Android SSE 有已知问题，见 4.3） |

#### 3.1.2 深度对比矩阵

| 维度 | Ktor Client | OkHttp3 | Retrofit |
|---|---|---|---|
| **SSE 原生支持** | ✅ 官方 `SSE` 插件，Flow 化 `incoming` | ⚠️ 需手动解析 `text/event-stream`（`okhttp-sse` 模块为 Java） | ❌ 无，需 OkHttp StreamingCall + 自定义 converter |
| **协程支持** | ✅ suspend + Flow 一等公民 | ⚠️ 需 `kotlinx-coroutines-okhttp` 或回调 | ✅ 有协程 adapter |
| **流式响应** | ✅ `client.sse{}` / `prepareGet` | ✅ `body().source()` 逐块读 | ❌ `ResponseBody` 需手动流 |
| **与 MCP SDK 同栈** | ✅ MCP SDK 内部即 Ktor | ❌ 引入第二套 HTTP 栈 | ❌ 第二套栈 + 强耦合 converter |
| **体积** | 核心 ~1.5MB（不含 engine） | ~700KB | ~500KB + converter |
| **维护活跃度** | JetBrains 官方，月更 | Square 官方，活跃 | Square 官方，更新放缓 |
| **重连/心跳** | ✅ Ktor 3.1.0 起 SSE 原生（KTOR-6242/7908） | ❌ 需自实现 | ❌ |

#### 3.1.3 推荐方案

**推荐：Ktor Client（Android 用 OkHttp engine，版本 3.1.3）**

**核心理由**：

1. **SSE 原生 Flow 化**——官方 `io.ktor.client.plugins.sse` 插件把 `text/event-stream` 解析为 `incoming: Flow<ServerSentEvent>`，与 Compose `collectAsState`/`LaunchedEffect` 天然契合，满足 C5。
2. **与 MCP Kotlin SDK 同栈**——MCP SDK 0.12.0 内部即用 Ktor（需自行声明 engine），选用 Ktor 避免维护两套 HTTP 栈，满足 C4。
3. **3.1.0 起 SSE 重连/心跳/序列化就绪**——严格命中流式中断处理需求。
4. **引擎可选**——Android 上选 `ktor-client-okhttp`（成熟、HTTP/2、遵循 Android 惯例），退路是 CIO。

**否决方案与理由**：

- **Retrofit**：流式支持弱，需 OkHttp StreamingCall + 自定义 converter，且与 MCP SDK 的 Ktor 栈冲突（违反 C4）。
- **裸 OkHttp**：无 SSE 原生解析，需手写 SSE 行解析器（见 3.2），且引入第二套栈。

### 3.2 课题 2：SSE 解析方案

#### 3.2.1 候选清单与过滤

| 候选 | 方式 | 过滤结果 |
|---|---|---|
| **Ktor SSE 插件**（`io.ktor.client.plugins.sse`） | Flow 化，官方 | 保留（推荐） |
| OkHttp 手动解析 | 手写行解析器 | 保留（备选/兜底） |
| `okhttp-sse` 模块 | Java 回调式 | 否决（Java 风格，非 Flow，且 SSE `[DONE]` 需另处理） |
| launchdarkly/okhttp-eventsource | 第三方 | 否决（新增依赖，非 Kotlin 原生） |

#### 3.2.2 Ktor SSE 插件 API（深验证，Ktor 3.x）

经 mcp_context7 查询 Ktor 官方文档（[client-server-sent-events](https://ktor.io/docs/client-server-sent-events.html)）确认：

```kotlin
// 1. 安装插件（SSE 仅需 ktor-client-core，无需额外依赖）
val client = HttpClient(OkHttp) { install(SSE) }

// 2. 建立 SSE 会话，消费 Flow
client.sse(urlString = "/v1/chat/completions") {
    incoming.collect { event: ServerSentEvent ->
        // event.data 为一行 data: 后的字符串
        // 处理 [DONE] 后 cancel()
    }
}

// 3. 重连配置（Ktor 3.1.0+ 原生支持，KTOR-6242）
install(SSE) {
    maxReconnectionAttempts = 4
    reconnectionTime = 2.seconds
}
```

- 会话接口：[`ClientSSESession`](https://api.ktor.io/ktor-client-core/io.ktor.client.plugins.sse/-client-s-s-e-session/index.html)，暴露 `incoming: Flow<ServerSentEvent>`、`cancel()`、`send()`。
- `ServerSentEvent` 含 `data`、`event`、`id`、`retry` 等字段，`data` 即 `data:` 行载荷。
- **重连**（`maxReconnectionAttempts`/`reconnectionTime`）、**心跳**均自 Ktor 3.1.0 支持（KTOR-6242 / KTOR-7908）。
- *注意*：`SSEBufferPolicy`（诊断缓冲，`LastEvents(n)` 等）是 **Ktor 3.3.0+** 特性（whats-new-330），Ktor 3.1.3 不具备，但不影响 US-006 主流程。

#### 3.2.3 OkHttp 手动解析（备选兜底方案）

若因任何原因弃用 Ktor SSE 插件，OkHttp 需手写 SSE 解析，[okhttp-sse 源码 ServerSentEventReader.kt](https://github.com/square/okhttp/blob/master/okhttp-sse/src/main/kotlin/okhttp3/sse/internal/ServerSentEventReader.kt) 展示了标准解析逻辑，要点：

- 用 `response.body().source()`（okio `BufferedSource`）逐行读，`readUtf8LineStrict()`。
- 事件以空行（`\n\n` / `\r\n\r\n`）分隔。
- 行前缀：`data:`（追加到 data，多行拼接）、`event:`、`id:`、`retry:`；`data:` 行载荷即 JSON。
- `[DONE]` 终止符：`data: [DONE]`，读到即结束。
- 需自行用 `Flow` 包装，且处理 `data:` 多行拼接与 UTF-8 边界。

**结论**：Ktor SSE 插件已封装上述全部逻辑，手写解析是纯重复造轮子，仅作兜底。

### 3.3 课题 3：OpenAI 流式响应格式

**深验证结论**（来源：[OpenAI Streaming Events 官方](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)、[Aivene 文档](https://docs.aivene.com/api-reference/chat/streaming-events)、[apiyi 兼容模式](https://docs.apiyi.com/en/api-capabilities/openai/response-handling)）：

- 请求体设 `"stream": true`，响应 `Content-Type: text/event-stream`。
- 每个事件为一行 `data: {json}`，事件间空行分隔，流以 `data: [DONE]` 终止。
- chunk 结构（`object: "chat.completion.chunk"`）：

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion.chunk",
  "created": 1717500000,
  "model": "gpt-4o-mini",
  "choices": [{
    "index": 0,
    "delta": { "role": "assistant", "content": "你好" },
    "finish_reason": null
  }]
}
```

| 字段 | 出现时机 |
|---|---|
| `delta.role` | 仅首个内容 chunk，恒为 `"assistant"` |
| `delta.content` | 每个文本片段（逐 token） |
| `delta.tool_calls` | 工具调用场景（本 US 可不建模） |
| `finish_reason` | 流结束 chunk 前为 `null`，结束时为 `"stop"`/`"length"`/`"tool_calls"` |
| `choices[]` | **部分 Provider 结束 chunk 可能是空数组**，索引前必须判空（防崩溃，命中验收「不崩溃」） |

**兼容格式核实**：OpenAI 兼容 Provider（**Ollama**、**OpenRouter**、DeepSeek、GLM、Qwen、Gemini 等经兼容网关）基本复用同一 `chat.completion.chunk` SSE 格式与 `[DONE]` 终止。差异点：

- 部分推理模型（DeepSeek 等）会发 `delta.reasoning_content`（可忽略或合并）。
- 结束 chunk 空 `choices[]` 的兼容问题必须判空处理。
- 统一路径：**累积每个 chunk 的 `choices[0].delta.content`** 重建完整消息。

### 3.4 课题 4：JSON 序列化

#### 3.4.1 候选清单与过滤

| 候选 | 解析方式 | License | 过滤结果 |
|---|---|---|---|
| **kotlinx.serialization** | 编译时代码生成 | Apache 2.0 | 保留（推荐） |
| Moshi | 反射 + kapt 代码生成 | Apache 2.0 | 留（备选） |
| Gson | 运行时反射 | Apache 2.0 | 否决（性能/空安全弱） |

#### 3.4.2 深度对比矩阵

| 维度 | kotlinx.serialization | Moshi | Gson |
|---|---|---|---|
| **解析方式** | 编译时（`@Serializable` + 编译器插件） | kapt 代码生成（`moshi-kotlin-codegen`） | 运行时反射 |
| **性能** | 快（无反射） | 快（代码生成） | 慢（反射，[基准](https://github.com/javierpe/Kotlin-JSON-Deserialization-Benchmark/) Gson 明显落后） |
| **空安全** | ✅ 自动（`T?` 表达可选字段） | ⚠️ 需 `@Json(name)` + 非空控制 | ❌ 反射默认给 null，易 NPE |
| **与 Ktor 集成** | ✅ `ktor-serialization-kotlinx-json` 官方 | 需第三方 `ktor-serialization-moshi` | 需第三方 |
| **与 MCP SDK 一致** | ✅ MCP kotlin-sdk 即用 kotlinx.serialization | ❌ 两套序列化 | ❌ |
| **忽略未知字段** | ✅ `ignoreUnknownKeys = true` | ✅ | ⚠️ |
| **生态迁移趋势** | 上升（[sdmaid-se#2350 从 Moshi 迁到 kotlinx](https://github.com/d4rken-org/sdmaid-se/pull/2350)） | 稳定 | 下降 |

#### 3.4.3 推荐方案

**推荐：kotlinx.serialization（版本 1.8.1）**

**核心理由**：

1. **Kotlin 原生 + 编译时**，无运行时反射，性能优、空安全（本就 Kotlin 2.1.0 项目）。
2. **与 Ktor 官方集成**——`ktor-serialization-kotlinx-json` 是 ContentNegotiation 官方序列化器。
3. **与 MCP kotlin-sdk 一致**——避免两套序列化体系。
4. **`ignoreUnknownKeys`** 天然容忍 OpenAI chunk 的 `reasoning_content` 等未知字段，防解析崩溃。

**否决**：**Gson**（运行时反射、性能最差、空安全差）；**Moshi**（可用但需 kapt，且与 Ktor/MCP 栈不一致）。

### 3.5 课题 5：依赖版本（关键决策）

#### 3.5.1 Kotlin 2.1.0 兼容性硬约束

**决定性发现**（深验证）：

| Ktor 版本 | 构建所用 Kotlin | 与项目 Kotlin 2.1.0 元数据兼容性 |
|---|---|---|
| **3.1.0**（2025-02-11） | **Kotlin 2.1.0**（[KTOR-7866 明确更新到 2.1.0](https://github.com/ktorio/ktor/releases/tag/3.1.0)） | ✅ 完全兼容 |
| 3.1.3（最新 3.1.x 补丁） | Kotlin 2.1.x | ✅ 推荐 |
| 3.2.x | Kotlin 2.2.0 | ⚠️ 边界（2.2 元数据，2.1 可读但告警） |
| 3.3.x | Kotlin 2.2.20 | ⚠️ 风险 |
| 3.5.x（2026-06） | Kotlin 2.4.0 | ❌ **不可兼容**（2.4 元数据无法被 2.1.0 编译读取） |

并佐证：Ktor 3.1.0 依赖 `kotlin-stdlib 2.1.10`、`coroutines 1.10.1`、`serialization-core 1.8.0`；最新 Ktor 3.5.x 则要求 coroutines 1.11.0（[idesense#65 实测 3.5.1 在 coroutines 1.10.2 下 `NoSuchMethodError: runBlockingK`](https://github.com/vcth4nh/idesense/pull/65)）。

#### 3.5.2 推荐版本（libs.versions.toml 建议）

| 依赖 | 版本 | 理由 |
|---|---|---|
| **ktor** | **3.1.3** | 以 Kotlin 2.1.0 构建，元数据完全兼容 C1；含 SSE 重连/心跳/序列化；最新可用补丁 |
| ktor-client-okhttp | 3.1.3 | Android engine（HTTP/2、成熟） |
| ktor-client-content-negotiation | 3.1.3 | JSON ContentNegotiation |
| ktor-serialization-kotlinx-json | 3.1.3 | kotlinx.serialization 桥接 |
| ktor-client-logging | 3.1.3 | 可选，结构化日志 |
| ktor-client-mock（test） | 3.1.3 | 测试隔离 |
| **kotlinx-serialization-json** | **1.8.1** | Kotlin 2.1.0 兼容；与 Ktor 3.1.0 传递依赖 1.8.0 一致 |
| **coroutines** | **1.10.1** | Ktor 3.1.0 传递依赖即 1.10.1；Kotlin 2.1.0 兼容；项目现锁 1.8.0 建议同步提升 |

> **版本一致性说明**：项目 `libs.versions.toml` 现 `coroutines = "1.8.0"`。引入 Ktor 3.1.0 后 Gradle 会解析到更高的 1.10.1，为避免「toml 声明与实际解析不一致」，建议将 `coroutines` 与 `kotlinx-serialization` 显式提升。首次引入 `kotlinx-serialization` 需在根 `build.gradle.kts` 增加 `org.jetbrains.kotlin.plugin.serialization` 插件（版本随 Kotlin 2.1.0）。

### 3.6 课题 6：错误处理模式

**推荐：密封类 + Flow（流式场景），而非裸 Result 泛型。**

流式结果天然是时间序列，用 `Flow<StreamEvent>` 表达，事件为密封类：

```kotlin
sealed interface StreamEvent {
    data class Delta(val fragment: String) : StreamEvent
    data class Done(val fullText: String) : StreamEvent
    data class Error(val code: ProviderErrorCode, val message: String, val recovered: Boolean = false) : StreamEvent
}
enum class ProviderErrorCode { UNAUTHORIZED, TIMEOUT, NETWORK, INTERRUPTED, MALFORMED, UNKNOWN }
```

| 错误场景 | 捕获异常 | 映射 |
|---|---|---|
| 端点不可达 / DNS | `HttpRequestTimeoutException` / `ConnectTimeoutException` / `IOException` | `NETWORK` |
| 超时（首包/停滞） | `HttpRequestTimeoutException` | `TIMEOUT` |
| 401 | `ClientRequestException`（HttpResponseValidator 拦截） | `UNAUTHORIZED` |
| 流中断（半途断流） | `StreamResetException` / `IOException` 于 collect 中 | `INTERRUPTED`（保留已收内容） |
| 非 2xx | `ServerResponseException` / `ClientRequestException` | 按状态映射 |
| SSE 状态码异常 | `SSEClientException`（如 524） | `UNKNOWN`/`NETWORK` |

**要点**：

- 在 `client.sse{}` 的 `incoming.collect{}` 外包裹 `try/catch`，把异常转为 `StreamEvent.Error` 而非抛给 UI。
- 用 `catch` + `emit` 把异常注入 Flow，UI 侧 `collect` 见 `Error` 事件即更新状态，不崩溃（命中验收）。
- 流中断时**保留已累积文本**，`Error(recovered=false)` 让 UI 显示「响应中断」+ 已收内容。
- 401 在 `HttpResponseValidator` 统一处理，避免每个请求重复判断。

### 3.7 课题 7：测试隔离（Ktor MockEngine）

**深验证结论**（来源：[Ktor Client Testing 官方](https://ktor.io/docs/client-testing.html) + context7）：

依赖：`testImplementation("io.ktor:ktor-client-mock:3.1.3")`。

**模拟流式 SSE 响应**：用 `MockEngine` 的 `respond(content = ByteReadChannel(sseText), status = OK, headers = text/event-stream)`，客户端安装 `SSE` 插件即可消费：

```kotlin
val mockEngine = MockEngine { request ->
    respond(
        content = ByteReadChannel("""
            data: {"choices":[{"delta":{"role":"assistant","content":"你"}}]}
            data: {"choices":[{"delta":{"content":"好"}}]}
            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
            data: [DONE]
        """.trimIndent()),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
    )
}
val client = HttpClient(mockEngine) { install(SSE) }
```

- **`respondWithStream`**：Ktor 提供 `MockEngine` 的 `respondWithStream` 扩展，可注入字节流并支持延迟（`delay`/`chunkSize`），适合测试 token 分片到达。
- **`addHandler`**：`MockRequestHandleScope` 支持按请求分发（`addHandler { request -> ... }`），用于区分正常流 / 401 / 超时场景。
- **流式真延迟**：如需测「首字延迟」或「中途断流」，可在 `MockEngine` handler 内用协程 `delay` 分次写入 `ByteReadChannel`，或 `withContext(IO)` 控制时序。
- **共享客户端配置**：把 `HttpClient(engine) { install(SSE) }` 抽成 `ProviderClient(engine: HttpClientEngine)`，生产传 `OkHttp.create()`，测试传 `MockEngine`，保证配置一致。

---

## 4. PoC 与关键发现

### 4.1 公开基准 / 证据数据汇总

| 数据点 | 值 | 来源 |
|---|---|---|
| Ktor 3.1.0 以 Kotlin 2.1.0 构建 | `Update to Kotlin 2.1.0 [KTOR-7866]` | [Ktor 3.1.0 release](https://github.com/ktorio/ktor/releases/tag/3.1.0) |
| Ktor 3.1.0 SSE 特性 | 重连(KTOR-6242)/心跳(KTOR-7908)/序列化(KTOR-7435) | 同上 |
| Ktor 3.1.0 传递依赖 | stdlib 2.1.10 / coroutines 1.10.1 / serialization 1.8.0 | [ktor-http 3.1.0](https://mvnrepository.com/artifact/io.ktor/ktor-http/3.1.0/changes) |
| 最新 Ktor 3.5.x 要求 coroutines 1.11.0 | 3.5.1 在 1.10.2 下 `NoSuchMethodError: runBlockingK` | [idesense#65](https://github.com/vcth4nh/idesense/pull/65) |
| MCP kotlin-sdk 传递 Ktor | `ktor-server-* 3.0.2`；不传递 engine 依赖 | [idesense#65](https://github.com/vcth4nh/idesense/pull/65)、[MCP SDK docs](https://deepwiki.com/modelcontextprotocol/kotlin-sdk/3-getting-started) |
| SSE 插件仅需 ktor-client-core | 无额外依赖 | [Ktor SSE 官方文档](https://ktor.io/docs/client-server-sent-events.html) |
| SSEBufferPolicy | Ktor 3.3.0+ 特性 | [whats-new-330](https://ktor.io/docs/whats-new-330.html) |
| kasltx.serialization vs Moshi/Gson | 编译时无反射，Gson 反射最慢 | [Kotlin-JSON-Deserialization-Benchmark](https://github.com/javierpe/Kotlin-JSON-Deserialization-Benchmark/) |
| Moshi→kotlinx 迁移趋势 | sdmaid-se 合入迁移 PR | [sdmaid-se#2350](https://github.com/d4rken-org/sdmaid-se/pull/2350) |

### 4.2 致命否决发现

| 发现 | 影响方案 | 严重度 |
|---|---|---|
| **最新 Ktor 3.5.x 以 Kotlin 2.4 构建，元数据无法被项目 Kotlin 2.1.0 编译读取** | 课题 5「用最新 Ktor」 | **致命**——必须用 Ktor 3.1.x（Kotlin 2.1.0 构建） |
| **Ktor 3.5.x 强制 coroutines 1.11.0**，与项目环境/IDE 捆绑 coroutines 冲突 | 课题 5 版本 | 高——进一步锁定 Ktor 3.1.x |
| CGI/CIO engine Android SSE 有已知问题（`Content-Length: 0` 空 body-less GET、UTF-8 多字节边界） | 课题 1 引擎选择 | 中——Android 用 OkHttp engine 规避 |
| SSE `[DONE]` 终止符与结束 chunk 空 `choices[]` | 课题 3 兼容 | 中——必须判空 + 识别 `[DONE]`，否则崩溃 |

### 4.3 异常场景行为记录

| 场景 | 推荐方案已知行为 | 降级策略 |
|---|---|---|
| 端点不可达 | `NETWORK` 错误事件，App 存活 | UI 显示错误 + 重试按钮 |
| 连接超时（首包） | `TIMEOUT` 错误 | 指数退避重试（SSE `maxReconnectionAttempts`） |
| 流中途断线 | `INTERRUPTED`，保留已收内容 | 提示「响应中断」；可选 SSE 自动重连 |
| 401 | `UNAUTHORIZED` | 引导重新配置 API Key |
| 结束 chunk 空 `choices[]` | 判空跳过，不崩溃 | 忽略该 chunk |
| Provider 兼容差异（reasoning_content） | `ignoreUnknownKeys` 容忍 | 忽略未知字段 |

---

## 5. 风险与缓解措施

### 5.1 推荐方案 Top 3 风险

| # | 风险 | 等级 | 缓解措施 |
|---|---|---|---|
| R1 | **Ktor 3.1.3 相对较旧（2025-02），缺少 3.2+ 的 SSEBufferPolicy 等诊断特性** | 中 | 3.1.3 已满足 US-006 全部需求（重连/心跳/序列化）；诊断缓冲可在后续升级 Kotlin 后获得；抽象 ProviderClient 接口隔离版本差异 |
| R2 | **Kotlin 2.1.0 锁定导致未来无法直接用最新 Ktor** | 中 | 明确记录「升级 Kotlin 编译器（P3）是解锁新 Ktor 的前提」；当前 US-006 不阻塞；升级时走 ADR + guardrail 闭环 |
| R3 | **SSE 首字延迟受 Android 网络栈/引擎影响，公开基准缺失** | 中 | PoC 阶段在真机（含弱网）实测首字延迟 p50/p95/p99，建立[性能基线](https://ktor.io/docs/client-testing.html)；不达标则检查 OkHttp engine 配置 |

### 5.2 备选 / 切换触发条件

| 推荐方案 | 备选 | 切换触发条件 |
|---|---|---|
| Ktor Client + SSE 插件 | OkHttp 手动解析 SSE | 1) Ktor SSE 插件出现无法规避的 Android 兼容性 bug；2) 首字延迟 PoC 不达标且确定为 Ktor 引擎问题 |
| Ktor 3.1.3 | 升级整体 Kotlin 后用更新 Ktor | 1) 团队决定升级 Kotlin 编译器（P3，需 ADR）；2) 需用 Ktor 3.3+ 的 SSEBufferPolicy 等特性 |
| kotlinx.serialization | Moshi | 1) 出现 Kotlin 2.1.0 无法解决的序列化洞；2) 团队强依赖 Moshi 已有适配器（本 US 无此场景） |

---

## 6. 最终推荐与下一步

### 6.1 推荐技术栈组合

| 层 | 推荐方案 | 版本 | 理由 |
|---|---|---|---|
| **HTTP 客户端** | Ktor Client（OkHttp engine） | 3.1.3 | SSE 原生 Flow + 与 MCP SDK 同栈 + Kotlin 2.1.0 兼容 |
| **SSE 解析** | Ktor SSE 插件 `io.ktor.client.plugins.sse` | 随 Ktor 3.1.3 | Flow 化 `incoming` + 重连/心跳/序列化 |
| **JSON 序列化** | kotlinx.serialization | 1.8.1 | 编译时、空安全、Ktor 官方桥接、与 MCP SDK 一致 |
| **协程** | coroutines | 1.10.1 | 与 Ktor 3.1.0 传递依赖一致，Kotlin 2.1.0 兼容 |
| **错误处理** | 密封类 StreamEvent + Flow + try/catch 注入 | — | 流式场景优于裸 Result，天然表达 Delta/Done/Error |
| **测试隔离** | Ktor MockEngine（`respond`/`respondWithStream`/`addHandler`） | 3.1.3(test) | 无需真实网络即可 mock 流式/401/超时 |

### 6.2 libs.versions.toml 建议（新增）

```toml
[versions]
ktor = "3.1.3"
kotlinxSerialization = "1.8.1"
coroutines = "1.10.1"   # 从 1.8.0 提升，与 Ktor 3.1.0 传递依赖一致

[libraries]
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-logging = { group = "io.ktor", name = "ktor-client-logging", version.ref = "ktor" }
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }

[plugins]
# 需在根 build.gradle.kts 增加：
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

### 6.3 后续实施步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | **写一个 ADR**（如 ADR-004：OpenAI 兼容 Provider 流式请求技术栈），基于本报告结论 | ADR 文档 |
| 2 | **依赖落地**：更新 `libs.versions.toml` + 根 build.gradle.kts 加 serialization 插件；`./gradlew assembleDebug` 验证 Kotlin 2.1.0 兼容 | 编译通过 |
| 3 | **SSE 流式 PoC**：真机（含弱网）实测首字延迟 p50/p95/p99、token 更新帧率，建性能基线 | 性能基线报告 |
| 4 | **MockEngine 测试**：覆盖正常流 / 401 / 超时 / 断流 / 空 choices 结束 chunk 五类用例 | 单元测试 |
| 5 | **错误处理落地**：StreamEvent 密封类 + 401 HttpResponseValidator 统一拦截 | 核心代码 |
| 6 | **集成试点**：接入 Provider 配置页（US-004 已存 baseURL/Key），实现最小流式对话 | 可运行聊天流 |

---

## 7. 附录

### 7.1 研究指标文档（Phase 1 产出）

见本报告第 2 节「需求与约束回顾」。

### 7.2 长候选清单与过滤日志（Phase 2 产出）

| 课题 | 候选 | 否决理由 |
|---|---|---|
| HTTP 客户端 | **Retrofit** | 流式支持弱，依赖 OkHttp StreamingCall + 自定义 converter，与 MCP SDK 的 Ktor 栈冲突（C4） |
| HTTP 客户端 | **Ktor CIO engine（Android）** | Android SSE 有已知问题（KTOR-9588 空 `Content-Length`、KTOR-9679 UTF-8 边界），Android 用 OkHttp engine 更稳 |
| SSE 解析 | **裸 OkHttp 手动解析** | Ktor SSE 插件已封装全部逻辑，手写是重复造轮子，仅兜底 |
| SSE 解析 | **okhttp-sse 模块** | Java 回调式，非 Flow 原生，`[DONE]` 需另处理 |
| SSE 解析 | **launchdarkly/okhttp-eventsource** | 新增第三方依赖，非 Kotlin 原生，无必要 |
| JSON | **Gson** | 运行时反射、性能最差、空安全差 |
| JSON | **Moshi** | 可用但需 kapt，与 Ktor/MCP 栈不一致，生态迁移趋势向 kotlinx |
| 版本 | **最新 Ktor 3.5.x** | Kotlin 2.4 构建，元数据无法被项目 Kotlin 2.1.0 读取（致命） |

### 7.3 关键引用链接索引

#### Ktor 官方

- [Ktor SSE Client 文档](https://ktor.io/docs/client-server-sent-events.html)
- [Ktor Client Testing（MockEngine）](https://ktor.io/docs/client-testing.html)
- [Ktor 3.1.0 Release（Kotlin 2.1.0 / SSE 特性）](https://github.com/ktorio/ktor/releases/tag/3.1.0)
- [Ktor Releases 版本表](https://ktor.io/docs/releases.html)
- [Ktor 3.1.0 依赖（mvnrepository）](https://mvnrepository.com/artifact/io.ktor/ktor-http/3.1.0/changes)
- [Ktor 3.5.0 What's new](https://ktor.io/docs/whats-new-350.html)
- [Ktor CHANGELOG（3.5.2 SSE/CIO bugfix）](https://github.com/ktorio/ktor/blob/main/CHANGELOG.md)

#### OpenAI 流式格式

- [OpenAI Streaming Events 官方](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)
- [Aivene Streaming Events 文档](https://docs.aivene.com/api-reference/chat/streaming-events)
- [apiyi OpenAI 兼容模式响应处理](https://docs.apiyi.com/en/api-capabilities/openai/response-handling)

#### OkHttp SSE

- [okhttp-sse ServerSentEventReader 源码](https://github.com/square/okhttp/blob/master/okhttp-sse/src/main/kotlin/okhttp3/sse/internal/ServerSentEventReader.kt)
- [OkHttp 流式响应处理（官方 issue）](https://github.com/square/okhttp/issues/7263)

#### JSON 序列化

- [Kotlin-JSON-Deserialization-Benchmark](https://github.com/javierpe/Kotlin-JSON-Deserialization-Benchmark/)
- [sdmaid-se Moshi→kotlinx 迁移 PR](https://github.com/d4rken-org/sdmaid-se/pull/2350)

#### MCP SDK 与版本佐证

- [MCP Kotlin SDK 官方仓库](https://github.com/modelcontextprotocol/kotlin-sdk/)
- [idesense#65（Ktor 版本与 coroutines 兼容实测）](https://github.com/vcth4nh/idesense/pull/65)
- [MCP Kotlin SDK Getting Started（engine 不传递依赖）](https://deepwiki.com/modelcontextprotocol/kotlin-sdk/3-getting-started)

---

## 8. 声明

- 本报告所有结论基于 2026-08-05 联网搜索 + mcp_context7 Ktor 官方文档证据，引用链接见第 7.3 节。
- 版本兼容性结论（Ktor 3.1.x 兼容 Kotlin 2.1.0、3.5.x 不兼容）为库元数据层面的确定结论；首字延迟性能数据无公开基准，需真机 PoC 实测（第 6.3 节步骤 3）。
- 本报告作为 US-006 后续 ADR 的输入，最终技术决策需经 guardrail-enforcer 审查后写入 ADR。
- 本报告为「完整版」调研（四阶段全部执行），未省略任何阶段。
