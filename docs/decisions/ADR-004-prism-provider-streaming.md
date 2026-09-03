# ADR-004: OpenAI 兼容 Provider 流式请求（US-006）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-05（决策）/ 2026-08-06（验收闭环） |
| 决策者 | 主 Agent（基于 tech-selection-researcher 选型报告） |
| 关联文档 | [ADR-001](ADR-001-prism-tech-stack.md) / [ADR-003](ADR-003-prism-provider-config-settings.md) / [prd.json](../../prd.json) |
| 上游调研 | [US-006 流式请求技术选型报告](../reports/2026-08-05-us006-provider-streaming-tech-selection.md) |
| 验收报告 | [US-006 验收报告](../reports/2026-08-06-us006-acceptance.md)（TKN-NETWORK-US006-AC-001，通过） |
| 风险等级 | P3 重大（引入新网络框架 Ktor + 接口扩展 ApiKeyRepository + INTERNET 权限） |

## 背景（Context）

US-006 需要在现有应用上实现 OpenAI 兼容 Provider 的 SSE 流式 `/v1/chat/completions` 请求，
首字延迟 <1s、token 实时更新 UI、端点不可达显示错误不崩溃。当前 `ConversationViewModel` 用
Mock 占位回复（`delay(1400)`），需替换为真实流式请求。

接入前需解决：

1. 引入 HTTP 客户端 + SSE 解析 + JSON 序列化依赖（当前无任何网络依赖）
2. ProviderConfig 如何组装完整端点 URL + Authorization 鉴权头 + 自定义 headers
3. ApiKeyRepository 仅提供 `readApiKey(key): Flow<String?>`，无同步/suspend 单值读取
4. ConversationViewModel 无参构造，需改为 Factory 注入
5. AndroidManifest 缺 INTERNET 权限

源码考古确认：`ProviderConfig.headers` 字段已存在并持久化（US-004 已实现），无需补；

## 决策（Decision）

### 4.1 网络栈：Ktor Client 3.1.3（OkHttp engine）+ Ktor SSE 插件

**决策**：HTTP 客户端用 **Ktor Client 3.1.3**（Android 平台用 OkHttp engine），SSE 用官方
`io.ktor.client.plugins.sse` 插件（Flow 化 `incoming`），JSON 用 **kotlinx.serialization 1.8.1**
（编译时序列化），coroutines 提升至 **1.10.1**。

**理由**：

- **版本硬约束**：项目 ADR-001 已锁定 Kotlin 2.1.0。最新 Ktor 3.5.x 以 Kotlin 2.4 构建，
  其元数据无法被 Kotlin 2.1.0 编译器读取，且强制 coroutines 1.11.0。故必须选用以 Kotlin 2.1.0
  构建的 **Ktor 3.1.x 系列（最新补丁 3.1.3）**。这是决定性约束。
- Ktor 与未来接入的 MCP Kotlin SDK（同用 Ktor 栈）保持一致，避免多 HTTP 栈冲突。
- SSE 插件将 `text/event-stream` 转为 Flow，天然契合协程流式消费。
- kotlinx.serialization 编译时序列化性能优、空安全强，与 Kotlin 生态一致。

**备选否决**：Retrofit（流式弱、与 MCP Ktor 栈冲突）；裸 OkHttp 手写 SSE 解析（重复造轮子，仅兜底）；
Gson（反射性能差、空安全差）；Moshi（需 kapt、栈不一致）。

### 4.2 流式结果模型：密封类 `StreamEvent` + Flow

**决策**：流式结果用密封类 `StreamEvent` 封装，经 `Flow<StreamEvent>` 暴露：

```kotlin
sealed class StreamEvent {
    data class Delta(val content: String) : StreamEvent()   // 增量 token
    data object Done : StreamEvent()                          // [DONE] 终止
    data class Error(val message: String) : StreamEvent()     // 错误
}
```

**理由**：流式场景天然由 Delta/Done/Error 表达，比裸 `Result` 更适合增量消费；密封类保证穷尽
when 分支，测试友好。

### 4.3 请求构造：`OpenAICompatibleProvider` + URL/鉴权组装

**决策**：新建 `OpenAICompatibleProvider`（含 `StreamEvent` 与请求方法），负责：

- 端点：`baseUrl` + `/chat/completions`
- 鉴权头：`Authorization: Bearer <apiKeyRef 对应明文>`
- 自定义头：合并 `ProviderConfig.headers`
- 请求体：`model` / `messages` / `stream=true`（kotlinx.serialization 序列化）

**理由**：考古确认 `headers` 字段已存在，US-006 直接复用；组装逻辑集中在该 Provider，便于 MockEngine 测试。

### 4.4 ApiKeyRepository 新增同步读取

**决策**：`ApiKeyRepository` 新增 `suspend fun readApiKeyOnce(key: String): String?`，
返回单值明文（内部即 `readApiKey(key).first()`），供流式请求在协程内同步取 key。

**理由**：`readApiKey` 仅返回 Flow，流式请求需在协程内一次取用；新增 suspend 单值方法避免在
调用方重复 `.first()`，语义清晰。属 P2 接口扩展，本 ADR 一并记录。

### 4.5 ConversationViewModel 改造为 Factory 注入

**决策**：`ConversationViewModel` 改为构造注入 `ProviderConfigRepository` + `ApiKeyRepository` +
`OpenAICompatibleProvider`，新增 `Factory`（仿 `SettingsViewModel.Factory` 经 `APPLICATION_KEY`
cast `PrismApplication`）。`sendMessage` 由 Mock `delay(1400)` 替换为调流式请求，`isTyping` 在
流开始置 true、`Done`/`Error` 后置 false，`Delta` 增量追加到 `messages` 末尾。

**理由**：考古确认真实设备路径应废弃 ConversationScreen 内 cast 读 activeProvider 的反模式，
统一经 Factory 注入，把 activeProvider 读取移入 VM。

### 4.6 INTERNET 权限与明文流量

**决策**：`AndroidManifest.xml` 新增 `<uses-permission android:name="android.permission.INTERNET"/>`。
Ollama 预设为 `http://localhost:11434` 明文端点，minSdk 26 默认禁明文流量，需在
`network_security_config.xml` 中仅对 localhost 放行明文（`usesCleartextTraffic` 全局开启过宽，
不采用）。

**理由**：INTERNET 权限是网络请求前提（考古 B3 阻断项）；局部明文白名单（仅 localhost）比全局
`usesCleartextTraffic=true` 更安全，符合安全最小面原则。

### 4.7 测试隔离：纯函数 + 真实 Ktor SSE 服务器集成测试

**决策**：因 Ktor SSE 客户端插件要求引擎声明 `SSECapability`，而 `MockEngine` 不支持该能力
（且 `internal constructor` 阻止 kapt 下子类化），采用**两层测试策略**：

1. **纯函数单元层**：将请求组装（[buildEndpoint] / [buildAuthHeader] / [buildRequestBody] /
   [applyCustomHeaders]）与 SSE 解析（[parseChunkData]）抽离为 `internal` 纯函数，直接单测覆盖
   核心逻辑，规避 MockEngine 限制。
2. **真实集成层**：用嵌入式 Ktor Netty HTTPServer 起真实 SSE 端点，结合 OkHttp 客户端端到端验证
   流式路径（多 Delta + [DONE]、401 鉴权失败、取消不吞并）。

**理由**：初始方案（§4.7 旧文，MockEngine 注入 `text/event-stream` 响应）在实施中被证否——
`MockEngine.supportedCapabilities` 不含 `SSECapability`，其 `internal constructor` 亦无法在
kapt 编译下子类化。纯函数 + 真实服务器方案在保证 JVM 单测可运行的同时，覆盖了端到端流式与
取消语义，并经 guardrail（TKN-NETWORK-US006-002）与 ac-verifier（TKN-NETWORK-US006-AC-001）验收。

### 4.8 错误处理修正：SSE 插件抛 `SSEClientException`（US-007 发现）

**发现**：§4.3 原错误处理依赖 `expectSuccess` 使 4xx 抛 `ClientRequestException`。但实测
Ktor 3.1.3 SSE 客户端插件对非 200 响应**一律抛 `io.ktor.client.plugins.sse.SSEClientException`**
（`extends IllegalStateException`，消息形如 `Expected status code 200 but was 4xx`），
无论 `expectSuccess` 值如何，均绕过 `ClientRequestException` 路径。

**决策**：

- 生产 `PrismApplication.httpClient` 仍设 `expectSuccess = true`（语义正确，供未来非 SSE 请求复用）。
- `OpenAICompatibleProvider.streamChat` 新增 `catch (e: SSEClientException)`，从
  `e.response?.status?.value` 读取状态码，经 `mapHttpError(status)` 映射：
  - 401 → `鉴权失败，请检查 API Key`
  - 其他 4xx → `请求被拒绝（<status>），请检查 Provider 配置`
  - 其余 → `网络请求失败，请检查网络连接或 Provider 配置`
- 保留 `catch (e: ClientRequestException)` 作为非 SSE 路径兜底。

**理由**：区分 401 与其他错误是 US-007 的 guardrail LOW 修复项（BR-error-handling-003），
需在真实 SSE 插件异常类型下实现，而非依赖 `expectSuccess` 契约。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| Ktor 3.5.x（最新） | 最新特性 | 以 Kotlin 2.4 构建，元数据与项目 Kotlin 2.1.0 不兼容，强制 coroutines 1.11.0 |
| Retrofit + OkHttp 手动 SSE | 生态成熟 | 流式支持弱，需手写 SSE 解析；与 MCP Ktor 栈冲突 |
| OkHttp SSE 模块 | 无需新框架 | 需额外 okhttp-sse 依赖，仍需自行封装 Flow；不如 Ktor SSE 插件原生 |
| Gson/Moshi 序列化 | 常见 | Gson 反射慢、空安全差；Moshi 需 kapt，栈不一致 |
| 全局 usesCleartextTraffic=true | 配置简单 | 全局放行明文流量过宽，违反安全最小面；仅 localhost 白名单更优 |

---

## 后果（Consequences）

- 正面后果：
  - 真实 OpenAI 兼容 Provider 流式对话可用（首字延迟 p50/p95/p99 可测）
  - Ktor 栈与未来 MCP SDK 一致，避免多 HTTP 栈
  - StreamEvent + Flow 为后续 US-007 Provider 切换、流中断重试提供基础
  - MockEngine 测试覆盖异常场景，验收「不崩溃」有据
- 负面后果 / 代价：
  - 引入 Ktor + serialization 依赖（P3 选型，需锁版本）
  - 扩展 ApiKeyRepository 接口（P2，需同步测试）
  - ConversationViewModel 改造幅度中等（无参 → Factory 注入）
  - 新增 INTERNET 权限与 network_security_config
- 需要同步更新的文档或代码：
  - `libs.versions.toml` / 根 `build.gradle.kts`（serialization 插件）/ `app/build.gradle.kts`
  - `AndroidManifest.xml` / `res/xml/network_security_config.xml`
  - 新增 `OpenAICompatibleProvider` / `StreamEvent` / `MockEngine` 测试
  - `ApiKeyRepository` + 测试
  - `ConversationViewModel` + `ConversationScreen` + 测试
  - `docs/decisions/README.md` / `README.md` 索引

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| Ktor 版本与 Kotlin 2.1.0 不兼容 | 高 | 锁定 Ktor 3.1.3（以 Kotlin 2.1.0 构建），assembleDebug 验证 |
| 结束 chunk 空 choices[] 崩溃 | 中 | 索引前判空 choices，命中验收「不崩溃」 |
| 未知字段（reasoning_content）报错 | 中 | `Json { ignoreUnknownKeys = true }` |
| Ollama 明文流量被禁 | 中 | network_security_config 仅放行 localhost |
| 流中断/超时处理 | 中 | StreamEvent.Error 统一拦截，isTyping 复位，UI 显示错误不崩溃 |
| 真实设备首字延迟未知 | 中 | 真机 SSE PoC 实测 p50/p95/p99，建性能基线 |

---

## 参考

- [US-006 流式请求技术选型报告](../reports/2026-08-05-us006-provider-streaming-tech-selection.md)
- [ADR-001](ADR-001-prism-tech-stack.md)：Kotlin 2.1.0 / compileSdk 34 锁定
- [ADR-003](ADR-003-prism-provider-config-settings.md)：Provider 配置 / ApiKeyRepository 暴露
