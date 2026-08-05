# ADR-005: MCP Kotlin SDK Client 集成（US-008）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-06 |
| 决策者 | 主 Agent（基于 code-archaeologist 考古 + 用户确认 Kotlin 升级至 2.3.x） |
| 关联文档 | [ADR-001](ADR-001-prism-tech-stack.md) / [ADR-004](ADR-004-prism-provider-streaming.md) / [prd.json](../prd.json) |
| 上游调研 | [US-008 MCP Client 集成源码考古](../reports/2026-08-06-us008-mcp-client-archaeology.md) |
| 风险等级 | P3 重大（引入新框架 MCP Kotlin SDK + Kotlin 升级 + 新增数据层/连接层/UI 层模块） |

## 背景（Context）

US-008 需要集成 MCP Kotlin SDK Client，使 AI 能通过 MCP 协议调用远程 Server 的工具
（`listTools` / `callTool`）。当前 `CapabilitiesScreen` 的 MCP 面板是纯静态硬编码数据
（`McpServer` data class + `localMcp`/`remoteMcp` 列表 + 空 onClick 的 `McpConfigSheet`），
未接入任何真实数据层或网络连接。

接入前需解决：

1. **依赖兼容**：MCP Kotlin SDK 0.12.0 要求 Kotlin 2.3.x + Ktor 3.3.x，而项目此前锁定
   Kotlin 2.1.0 / Ktor 3.1.3（ADR-001 环境适配修订）。需升级 Kotlin 至 2.3.21、Ktor 至 3.3.3。
2. **数据层缺失**：无 `McpServerConfig` 实体与对应 Repository，需新建以持久化 MCP Server 配置。
3. **连接层缺失**：无 MCP Client 连接封装，需新建 `McpClientManager` 复用现有 `httpClient`。
4. **UI 静态数据**：`CapabilitiesScreen` 的 MCP 列表与配置弹层需替换为真实数据 + ViewModel。
5. **测试策略**：MCP 的 Streamable HTTP 传输在 `MockEngine` 下可测性存疑，需复用 ADR-004 4.7
   的「纯函数 + 真实服务器」两层测试策略。

源码考古（TKN-PRISM-ARCHAEOLOGY-US008-001）确认：所有范式（@Entity 实体、Repository、
Factory 注入、stateIn 订阅、internal 纯函数）均在现有代码中建立且高度一致，可直接复用。

## 决策（Decision）

### 5.1 依赖升级：Kotlin 2.3.21 + Ktor 3.3.3 + kotlinx-serialization 1.11.0

**决策**：升级 `gradle/libs.versions.toml`：
- Kotlin `2.1.0` → **2.3.21**（MCP SDK 0.12.0 编译目标要求）
- Ktor `3.1.3` → **3.3.3**（MCP SDK 传递依赖对齐）
- kotlinx-serialization `1.8.1` → **1.11.0**（与 Kotlin 2.3.x 对齐）
- 新增 `mcp = "0.12.0"`，`mcp-kotlin-sdk-client` 依赖

`app/build.gradle.kts` 迁移 `kotlinOptions.jvmTarget` → `kotlin { compilerOptions { jvmTarget } }`
（Kotlin 2.3 已废弃旧 DSL）。

**理由**：MCP Kotlin SDK 0.12.0 是 ADR-001 已锁定的 P0 核心依赖，其 Kotlin 2.3.x 编译目标是
硬性约束，无法回避。经用户确认「升级 Kotlin 至 2.3.x」为唯一可行路线。

**已验证**（2026-08-06）：`settings.gradle.kts` 的 `google()` 仓库缺乏 content filter 曾导致
`io.ktor` 依赖被发往不可达的 `dl.google.com` 解析失败。已为 `google()` 补充 content filter
（仅含 `com.android.*` / `com.google.*` / `androidx.*`），强制 `io.ktor` / `io.modelcontextprotocol`
从 mavenCentral / aliyun 解析。`compileDebugKotlin` 通过。

**Ktor SSE artifact 澄清**：Ktor 3.x 中 SSE 客户端插件已内置在 `ktor-client-core`，不存在独立的
`ktor-client-sse` artifact。曾误加的 `ktor-client-sse:3.3.3` 依赖解析失败，已移除。现有代码
（`OpenAICompatibleProvider` / 测试）使用的 `io.ktor.client.plugins.sse` 来自 `ktor-client-core`，
无需额外依赖。

### 5.2 数据层：`McpServerConfig` 实体 + `McpServerRepository`

**决策**：新建 `McpServerConfig`（@Entity，仿 `ProviderConfig` 模式）：

```kotlin
@Entity
data class McpServerConfig(
    @Id var id: Long = 0,
    var name: String,
    var serverType: String,          // "LOCAL" | "REMOTE"（预设/自建）
    var transport: String,           // "STREAMABLE_HTTP"（本期仅支持）| "SSE"
    var baseUrl: String,
    var apiKeyRef: String,           // 存引用，不存明文（复用 ApiKeyRepository）
    @Convert(StringMapConverter) var headers: Map<String, String> = emptyMap(),
    var isEnabled: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
)
```

新建 `McpServerRepository`（仿 `ProviderConfigRepository`）：`BoxStore.boxFor` +
`MutableStateFlow` 暴露 `servers: StateFlow<List<McpServerConfig>>` + CRUD，写后 `refreshFlows()`。
MCP 允许多 Server 并存，**不需要**单激活不变式（与 ProviderConfig 不同）。

**理由**：考古确认所有字段模式均有先例（`ProviderConfig` L32-43 + `StringMapConverter`），
`apiKeyRef` 只存引用符合安全契约（ADR-004 4.4）。新增实体属 ObjectBox 向后兼容增量，无需迁移。

### 5.3 连接层：`McpClientManager`（复用 httpClient + apiKeyRepository）

**决策**：新建 `McpClientManager`，仿 `OpenAICompatibleProvider` 架构：

- 构造注入 `httpClient: HttpClient` + `apiKeyRepository: ApiKeyRepository`
- 用 MCP SDK 的 `StreamableHttpClientTransport(httpClient, url)` 连接
- 暴露 `suspend fun listTools(config): List<String>` / `suspend fun callTool(config, name, arguments): String`
- 抽离 `internal` 纯函数（`buildTransport` / `buildAuthHeaders` / `mapToolResult`）供单元测试
- 错误统一映射，`CancellationException` 重新抛出
- 定义 `McpToolProvider` 接口（对齐 `ChatStreamProvider` 依赖倒置），便于测试 fake 注入

**理由**：MCP SDK 的 `StreamableHttpClientTransport` 正是为复用现有 Ktor client 设计
（`HttpClient.mcpStreamableHttpTransport(url)`）。复用全局 `httpClient`（已装 SSE 插件）避免
多 HTTP 栈冲突，与 ADR-004 4.1 一致。

### 5.4 装配层：PrismApplication 新增两个 lazy 单例

**决策**：在 `PrismApplication` 新增：

```kotlin
val mcpServerRepository: McpServerRepository by lazy { McpServerRepository(boxStore) }
val mcpClientManager: McpClientManager by lazy { McpClientManager(httpClient, apiKeyRepository) }
```

**理由**：与现有 `providerConfigRepository` / `openAICompatibleProvider` 的 lazy 装配模式一致，
供 ViewModel Factory 经 `APPLICATION_KEY` 读取。

### 5.5 UI 层：`CapabilitiesViewModel` + 替换 `CapabilitiesScreen` 静态数据

**决策**：新建 `CapabilitiesViewModel`（仿 `SettingsViewModel`）：
- 构造注入 `McpServerRepository` + `McpClientManager`
- `servers: StateFlow<List<McpServerConfig>>` 用 `stateIn`
- `Factory` 从 `PrismApplication` 读取 `app.mcpServerRepository` / `app.mcpClientManager`
- 提供 `testConnection` / `toggleEnabled` / `deleteServer` 操作

`CapabilitiesScreen` 的 `McpPanel` / `McpConfigSheet` 从静态 `McpServer` 数据 + 空 onClick
改造为订阅 ViewModel 的真实数据，`测试连接` 调用 `McpClientManager.listTools` 并展示结果，
SectionHeader 计数「本地内置」/「远程模板」改为动态。

**理由**：现有 `CapabilitiesScreen()` 在 `PrismApp.kt:86` 无参注册，改为
`CapabilitiesScreen(viewModel = viewModel(factory = CapabilitiesViewModel.Factory))` 无需改导航。

### 5.6 测试策略：纯函数 + 真实 MCP 服务器集成测试

**决策**：复用 ADR-004 4.7 两层策略：
1. **纯函数单元层**：单测 `McpClientManager` 的 internal 纯函数（transport 构造 / 鉴权头 /
   工具结果映射），覆盖核心逻辑。
2. **真实集成层**：用嵌入式 Ktor Netty HTTPServer 起真实 MCP Streamable HTTP 端点，
   结合 OkHttp 客户端端到端验证 `listTools` / `callTool`。

**理由**：MCP 的 Streamable HTTP 传输涉及真实 HTTP 握手（initialize → listTools → callTool），
`MockEngine` 无法完整模拟 MCP 协议状态机；真实服务器集成测试是唯一可靠验证路径。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 保持 Kotlin 2.1.0 / Ktor 3.1.3 | 零升级风险 | MCP SDK 0.12.0 要求 Kotlin 2.3.x，硬性不兼容（用户已确认升级） |
| 用 `ktor-client-sse` artifact | 语义清晰 | Ktor 3.x 中 SSE 已内置 `ktor-client-core`，该 artifact 不存在导致解析失败 |
| 每 Server 独立 HttpClient | 隔离性好 | 连接数膨胀、资源浪费；MCP SDK 设计为复用单个 Ktor client |
| 直接拼接 MCP SDK 到 UI 层 | 减少层级 | 违反现有分层（数据层/连接层/UI 层），不可测、不可维护 |
| 用 `SseClientTransport` 代替 Streamable | ~ | Streamable HTTP 是 MCP 现行推荐传输，SSE 为旧式；本期聚焦 Streamable HTTP |

---

## 后果（Consequences）

- 正面后果：
  - AI 可通过 MCP 协议调用远程 Server 工具（listTools / callTool）
  - `CapabilitiesScreen` MCP 面板从静态数据变为真实可连接、可配置
  - 复用现有 Ktor httpClient，避免多 HTTP 栈
  - 数据层 / 连接层 / UI 层分离，可测、可维护
- 负面后果 / 代价：
  - Kotlin 2.3.21 / Ktor 3.3.3 升级（P3，需全量回归验证现有 US-001~007 功能）
  - 新增 3 个模块（实体 / Repository / ClientManager）+ 1 个 ViewModel
  - lint 的 `CoroutineCreationDuringComposition` 检测器在 Kotlin 2.3 下可能需重新评估
- 需要同步更新的文档或代码：
  - `gradle/libs.versions.toml` / `app/build.gradle.kts` / `settings.gradle.kts`
  - 新增 `McpServerConfig` / `McpServerRepository` / `McpClientManager` / `McpToolProvider` / `CapabilitiesViewModel`
  - 改造 `CapabilitiesScreen` / `PrismApplication`
  - `docs/decisions/README.md` / `README.md` 索引
  - `prd.json`（US-008 passes 置 true）

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| MCP SDK 0.12.0 API 变动 | 中 | 以官方 0.12.0 文档为准；`StreamableHttpClientTransport` 构造签名已通过 web-access 验证 |
| Kotlin 2.3.21 与 AGP 8.13.0 兼容 | 中 | 已通过 web-search 验证 Kotlin 2.3.21 支持 AGP 8.2.2-9.0.0；`compileDebugKotlin` 已通过 |
| Streamable HTTP 在 MockEngine 下不可测 | 中 | 复用 ADR-004 4.7 真实 Ktor Netty 服务器集成测试 |
| @Entity 变更对象模型迁移 | 低 | 新增实体向后兼容，ObjectBox 自动分配 UID；`default.json` 提交 VCS |
| apiKeyRef 明文安全 | 中 | 沿用 `apiKeyRef` 引用模式，`McpClientManager` 经 `readApiKeyOnce` 解密短路明文 |
| 多 Server 并发连接泄漏 | 中 | `McpClientManager` 统一管理连接生命周期，`close()` 释放；ViewModel 取消时清理 |

---

## 参考

- [US-008 MCP Client 集成源码考古](../reports/2026-08-06-us008-mcp-client-archaeology.md)
- [MCP Kotlin SDK 官方文档](https://kotlin.sdk.modelcontextprotocol.io/kotlin-sdk-client/index.html)
- [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
- [ADR-001](ADR-001-prism-tech-stack.md)：技术栈锁定
- [ADR-004](ADR-004-prism-provider-streaming.md)：Ktor 栈 / 测试策略 / apiKeyRef 模式