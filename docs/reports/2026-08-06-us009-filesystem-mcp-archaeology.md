# US-009 Filesystem MCP Server 源码考古报告

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | code-archaeologist（主 Agent 复核） |
| 任务令牌 | TKN-PRISM-ARCHAEOLOGY-US009-001 |
| 日期 | 2026-08-06 |
| 关联文档 | [ADR-006](../decisions/ADR-006-filesystem-mcp-server.md) |
| 风险等级 | P3 重大 |

## 1. 目标与范围

为 US-009「内置 Filesystem MCP Server」考古，确认：

1. 复用接口与装配模式（`McpToolProvider` / `McpClientManager` / ViewModel Factory）。
2. MCP Kotlin SDK 0.12.0 进程内 Server 的可行性挂载点（Transport 接口 / `Server.createSession` / `Protocol.connect`）。
3. UI 层对本地 Server 的既有约束（guardrail M2 禁启用开关 / canSave URL 校验）。
4. 依赖现状（`mcp-kotlin-sdk-server` 仅在 testImplementation）。

## 2. 关键发现

### 2.1 复用接口（可直接复用，零改动）

- [McpToolProvider.kt](../../app/src/main/java/io/prism/network/McpToolProvider.kt)：`listTools(config)` / `callTool(config, name, arguments)` 依赖倒置接口，本地实现只需实现同一接口。
- [McpClientManager.kt](../../app/src/main/java/io/prism/network/McpClientManager.kt)：远程实现，`isValidBaseUrl` / `resolveHeaders` / `renderResult` 为 internal 纯函数（ADR-005 5.3/5.4）。
- [CapabilitiesViewModel.kt](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)：`Factory` 注入 `app.mcpClientManager` 作为 `McpToolProvider`（L130）。
- [McpServerConfig.kt](../../app/src/main/java/io/prism/data/McpServerConfig.kt)：`serverType`（LOCAL/REMOTE）已存在，是路由依据。

### 2.2 MCP SDK 字节码复核（进程内桥接可行性）

对 `~/.gradle` 缓存的 `kotlin-sdk-core-jvm-0.12.0` / `kotlin-sdk-server-jvm-0.12.0` 反汇编确认：

- `Transport` 接口：`start` / `send(JSONRPCMessage, TransportSendOptions)` / `close` / `onClose` / `onError` / `onMessage`。
- `AbstractTransport` 基类：自带 `onMessage`/`onError`/`onClose` 存取 + `invokeOnCloseCallback()`。
- `Server.createSession(Transport)` → 内部调用 `ServerSession.connect(Transport)`。
- `Client.connect(Transport)` 与 `ServerSession.connect(Transport)` 同源自 `Protocol.connect`，
  后者依次 `onClose` / `onError` / `onMessage` 装配并调用 `transport.start()`。

**结论**：自研 `InProcessTransport`（继承 `AbstractTransport`）只需实现 `start`/`send`/`close`，
`onMessage` 由 SDK 自动装配。配对通道桥接 Client 与 Server 两端完全可行。

SDK 无内置 in-memory Transport，需自研（web-access 复核官方文档确认仅提供 STDIO / SSE / WebSocket / Streamable HTTP）。

### 2.3 UI 层本地 Server 约束

- [CapabilitiesScreen.kt](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) L243-247：
  `McpRow` 的 `PrismSwitch(enabled = server.baseUrl.isNotBlank())` —— 本地 Server（空 baseUrl）被禁用。
- L314 / L418：`McpConfigSheet` 的 `canSave`/`testEnabled` 要求 http(s) URL —— 本地 Server 无法保存/测试。
- L227-240：本地 Server 行已按 `serverType==LOCAL` 使用青色调与「本地内置 · 零配置」标签。

**结论**：US-009 需放行 LOCAL serverType 的启用与保存逻辑。

### 2.4 依赖现状

- [libs.versions.toml](../../gradle/libs.versions.toml)：`mcp = "0.12.0"`，`mcp-kotlin-sdk-server` 已声明。
- [build.gradle.kts](../../app/build.gradle.kts) L100：`testImplementation(libs.mcp.kotlin.sdk.server)` —— 仅测试依赖。

**结论**：提升为 `implementation` 生产依赖即可复用已下载的 SDK。

## 3. 风险与建议

| 风险 | 建议（已纳入 ADR-006） |
|---|---|
| 进程内握手时序 | 字节码确认 createSession 不阻塞等待，client.connect 触发握手；集成测试覆盖 |
| SAF 不可 JVM 单测 | `FileSystemAccess` 接口 + in-memory fake |
| 工具未确认即执行 | 门禁置于每个工具处理器入口，缺省拒绝 |
| 会话/资源泄漏 | 每调用 finally 清理 client/session/transport |

## 4. 结论

架构可行，复用面大。新增 7 个模块（Transport / FileSystemAccess / SafFileAccess / Gate / Server / Provider / Dispatcher），
改动 ViewModel Factory 注入 + UI 放行本地 Server。完整决策见 ADR-006。