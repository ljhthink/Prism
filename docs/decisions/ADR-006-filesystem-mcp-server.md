# ADR-006: 内置 Filesystem MCP Server（US-009）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-06 |
| 决策者 | 主 Agent（基于 SDK 字节码复核 + SAF 调研 + 用户确认进程内桥接方案） |
| 关联文档 | [ADR-001](ADR-001-prism-tech-stack.md) / [ADR-005](ADR-005-mcp-client-integration.md) / [prd.json](../prd.json) |
| 上游调研 | [US-009 Filesystem MCP Server 源码考古](../reports/2026-08-06-us009-filesystem-mcp-archaeology.md) |
| 风险等级 | P3 重大（引入 mcp-kotlin-sdk-server 生产依赖 + 新增进程内 Transport / SAF 访问层 / 用户确认机制） |

## 背景（Context）

US-009 需要让 AI **读取本地文件**，实现形态 B「内置本地 Server，零配置可用」
（ADR-001 3.6）。`McpServerPresets` 已声明 `Filesystem` 本地预设，但 `CapabilitiesScreen`
对无 `baseUrl` 的本地 Server 禁用启用开关（guardrail M2），且无任何本地 Server 实现。

实现前需解决：

1. **进程内桥接**：MCP 协议天然面向网络/进程间传输（Streamable HTTP / STDIO）。
   本地 Filesystem Server 与 Client 运行在同一进程，需一条不依赖网络/端口的内存传输通道，
   复用 `McpToolProvider` / `McpClientManager` 现有调用路径。
2. **Server 依赖**：`mcp-kotlin-sdk-server` 目前在 `testImplementation`（US-008 集成测试用），
   需提升为 `implementation` 生产依赖以承载本地 Server。
3. **文件访问模型**：Android 无统一文件系统路径，需基于 Storage Access Framework（SAF）
   访问用户授权目录，且 SAF 只按 URI 操作、不提供任意路径，需抽象访问层。
4. **用户确认**：AC-3「AI 调用前需用户确认（防误操作）」——文件操作不可静默执行，
   需确认门禁，防止 AI 误读/误写敏感文件。
5. **路由**：现有 `CapabilitiesViewModel.Factory` 注入 `app.mcpClientManager`（远程实现），
   需按 `serverType` 路由 LOCAL → 本地实现、REMOTE → `McpClientManager`。
6. **可测性**：SAF 依赖 Android Context，JVM 单测不可用，需抽象 `FileSystemAccess` 接口
   + 内存 fake，复用 ADR-004 4.7 / ADR-005 5.6 的两层测试策略。

源码考古（TKN-PRISM-ARCHAEOLOGY-US009-001）确认：

+ `McpToolProvider` 接口（listTools / callTool）与 `McpClientManager` 实现均可直接复用，
  本地实现只需实现同一接口。
+ MCP SDK 的 `Transport` 接口（start / send / close / onClose / onError / onMessage）+ `AbstractTransport`
  基类（自带 onMessage/onError/onClose 存取与 `invokeOnCloseCallback`），为自研进程内 Transport 提供挂载点。
+ `Server.createSession(transport)` 内部调用 `ServerSession.connect(transport)`，
  与 `Client.connect(transport)` 同源 `Protocol.connect`——后者依次设置 onClose/onError/onMessage
  并调用 `transport.start()`。因此**自研 Transport 只需实现 start/send/close，onMessage 由 SDK 自动装配**。
+ `CapabilitiesScreen` 的 `McpRow`（enabled = baseUrl.isNotBlank）与 `McpConfigSheet`
  （canSave 要求 http(s) URL）需为 LOCAL serverType 放行。

## 决策（Decision）

### 5.1 依赖：`mcp-kotlin-sdk-server` 从 testImplementation 提升为 implementation

**决策**：`app/build.gradle.kts` 将 `mcp-kotlin-sdk-server` 从 testImplementation 移至 implementation，
作为生产依赖承载内置 Filesystem Server。

**理由**：本地 Server 是生产功能（US-009），非测试专用；ADR-005 5.1 已锁定 mcp=0.12.0。

### 5.2 进程内 Transport：`InProcessTransport`（内存通道桥接）

**决策**：新建 `InProcessTransport : AbstractTransport`，用两个配对的协程 `Channel<JSONRPCMessage>`
桥接 Client 与 Server 两端：

```kotlin
object InProcessTransport {
    fun createPair(): Pair<InProcessTransport, InProcessTransport> {
        val clientToServer = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        val serverToClient = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        val clientEnd = InProcessTransport(sendTo = clientToServer, receiveFrom = serverToClient)
        val serverEnd = InProcessTransport(sendTo = serverToClient, receiveFrom = clientToServer)
        return clientEnd to serverEnd
    }
}
```

+ `start()`：启动接收协程，从 `receiveFrom` 读取消息并派发给 `onMessage`；通道关闭时回调 `onClose`。
+ `send(message, options)`：写入 `sendTo` 通道。
+ `close()`：取消接收协程并关闭两个通道。

**理由**：`Protocol.connect` 会自动装配 onMessage 并调用 `start()`，
故本实现只需处理消息搬运。`Channel.UNLIMITED` 避免握手阶段背压死锁（初始化/通知并发）。

### 5.3 文件访问层：`FileSystemAccess` 接口 + `SafFileAccess` + 测试 fake

**决策**：抽象 `FileSystemAccess` 接口，屏蔽 SAF 与 in-memory 差异：

```kotlin
interface FileSystemAccess {
    suspend fun readFile(uri: String): String
    suspend fun readMultipleFiles(uris: List<String>): Map<String, String>
    suspend fun listDirectory(uri: String): List<FileEntry>
    suspend fun getFileInfo(uri: String): FileEntry
    suspend fun writeFile(uri: String, content: String): Boolean
    // 可扩展：directoryTree / searchFiles / moveFile 等
}
```

+ `SafFileAccess`：基于 `DocumentFile.fromTreeUri` / `DocumentContract` + `ContentResolver` 的空闲目录授权访问。
+ `InMemoryFileAccess`（test）：内存 Map 树，供 JVM 单测验证工具逻辑。

**理由**：SAF 依赖 Android Context 不可 JVM 单测，抽象接口后工具处理器可独立测试；
生产端 `SafFileAccess` 由 DI 注入，与远程 Provider 模式一致。

> SAF 授权目录：用户经 `ACTION_OPEN_DOCUMENT_TREE` 选择目录，`takePersistableUriPermission`
> 持久化授权；工具以逻辑目录名→SAF URI 的「根目录注册表」映射，`read_file` 参数用逻辑路径。

### 5.4 用户确认门禁：`ToolConfirmationGate`

**决策**：定义确认门禁接口，挂载于每个工具处理器入口，**所有文件工具调用前都需确认**：

```kotlin
interface ToolConfirmationGate {
    suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean
}
```

+ 生产实现 `UiConfirmationGate`：经 `MutableSharedFlow` 暴露确认请求，UI 弹「AI 请求执行 <tool>」对话框，
  用户「允许/拒绝」，`confirm` 挂起直至响应。
+ 测试 fake `AutoConfirmGate`：直接返回 true / false。

**理由**：AC-3 要求执行前确认。将门禁置于服务器工具处理器（而非客户端），
使确认逻辑与传输解耦、对任何调用方（含远程 Client）生效；SAF 初授权只控「可见范围」，不控单次操作。

### 5.5 本地 Server：`FilesystemMcpServer` + `LocalMcpToolProvider`

**决策**：

+ `FilesystemMcpServer`：构建 MCP `Server`（单例，工具注册一次），
  注册 8 个工具（7 只读 + 1 写 `write_file`），每个处理器先经 `ToolConfirmationGate.confirm`
  再调用 `FileSystemAccess`，返回 `CallToolResult`（错误时 `isError=true`）。
+ `LocalMcpToolProvider : McpToolProvider`：`listTools` / `callTool` 每次调用建立
  一对 `InProcessTransport` + `server.createSession` + `client.connect`，完成调用后统一清理。

**工具集**（对齐 MCP Filesystem Server 子集，SAF 可映射）：
`read_file` / `read_multiple_files` / `list_directory` / `directory_tree` /
`search_files` / `get_file_info` / `list_allowed_directories`（只读）+ `write_file`（写，确认）。

**理由**：进程内完整走 MCP 握手（initialize → tools/list → tools/call），复用 `McpClientManager`
相同调用路径，天然验证 MCP 协议事务；单例 Server + 每次调用新会话，避免会话状态泄漏。

### 5.6 路由：`McpToolProviderDispatcher`

**决策**：新建 `McpToolProviderDispatcher : McpToolProvider`，按 `config.serverType` 分发：

```kotlin
override suspend fun listTools(config) =
    if (config.serverType == McpServerType.LOCAL) localProvider.listTools(config)
    else remoteManager.listTools(config)
```

`CapabilitiesViewModel.Factory` 改注入 dispatcher（持有 local + remote 两个实现）。

**理由**：`McpToolProvider` 是单一依赖倒置接口，UI 层无需感知本地/远程差异；
新增 dispatcher 使路由集中、可单测。

### 5.7 UI 放行本地 Server

**决策**：`CapabilitiesScreen`：

+ `McpRow`：`enabled = server.baseUrl.isNotBlank()` → `= server.serverType == McpServerType.LOCAL || server.baseUrl.isNotBlank()`。
+ `McpConfigSheet`：`canSave`/`testEnabled` 对 LOCAL 跳过 http(s) URL 约束；
  本地 Server 的 Base URL 字段隐藏或置灰，显示「本地内置 · 零配置」。

**理由**：本地 Server 无需 baseUrl，guardrail M2 的约束仅针对远程；US-009 起本地 Server 可启用。

### 5.8 安全与并发加固（guardrail S1/C1/C2/C3/S2，TKN-US009-GUARDRAIL-001）

**决策**：针对 guardrail 审查「有条件通过」必改项的修复：

+ **S1 权限对称释放**：`removeFilesystemRoot` 先经 `SafFileAccess.uriFor` 取回 URI，
  调用 `releasePersistableUriPermission` 释放系统级持久化授权，再移除逻辑根并持久化（BR-security-004）。
+ **C1 线程安全**：`SafFileAccess.roots` 由普通 `LinkedHashMap` 改为 `MutableStateFlow<Map<String, Uri>>`
  原子快照作为唯一事实源，`addRoot`/`removeRoot` 经 `update {}` 原子更新，读路径读 `.value`（BR-concurrency-002）。
+ **C2 确认协议兜底**：① `ToolConfirmationHost` 提升为全局宿主（`PrismApp` NavHost 外层），
  任意 Tab 均有收集者；② UI 侧改为 FIFO 队列逐条确认，避免并发请求被单值状态覆盖；
  ③ `confirm` 经 `withTimeoutOrNull(30s)` 超时按拒绝处理，`MutableSharedFlow` 用 `DROP_OLDEST`
  （BR-concurrency-003）。
+ **C3 初始化竞态**：`onCreate` 持久化根加载与 `registerFilesystemRoot` 经 `rootsMutex` 串行化。
+ **S2 路径防御**：`resolveFile`/`writeFile` 逐段白名单校验（非空、非 `.`/`..`）。
+ **C4/C5/C6/C7**：`LocalMcpToolProvider` catch 补结构化日志；根目录名清洗拒绝 `/` 与控制字符；
  `search_files.limit` 上限 100；确认对话框参数长字符串截断展示。

**理由**：最小权限、跨线程共享容器、一次一请求确认协议均为既定规则（BR-security-004 /
BR-concurrency-002 / BR-concurrency-003），修复后 fs 模块 25 用例全部通过。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 本地 Server 直接调用文件操作（不经 MCP 协议） | 实现简单、无握手开销 | 不复用 `McpToolProvider` 契约，绕过 MCP 协议验证，无法复用现有调用路径与测试策略 |
| 用 Streamable HTTP 走 localhost 端口桥接 | 复用现成 HTTP Transport | 引入本地端口/网络栈，资源开销大、无收益；进程内通道更轻量 |
| 用 STDIO 子进程桥接 | 完全复用 STDIO Transport | 需启动子进程/管道，Android 环境不适用，复杂度高 |
| 用户确认放在客户端 `callTool` 拦截 | 拦截点集中 | 本地与远程语义不一致（远程无确认）；确认逻辑应绑定工具而非传输方 |
| SAF 直接暴露某一路径 | 简单 | Android 无统一文件系统路径，SAF 只按 URI 授权访问，必须抽象访问层 |

---

## 后果（Consequences）

+ 正面后果：
  + AI 可通过本地 Filesystem MCP Server 读取用户授权目录内的文件
  + 本地 Server 复用 `McpToolProvider` 契约，UI 层零感知，路由集中
  + 进程内 Transport 复用 MCP SDK 自动装配，实现最小、可测
  + 文件操作经确认门禁，满足 AC-3「防误操作」安全要求
+ 负面后果 / 代价：
  + `mcp-kotlin-sdk-server` 成为生产依赖，APK 体积增加
  + 新增 7 个模块（Transport / FileSystemAccess / SafFileAccess / Gate / Server / Provider / Dispatcher）
  + SAF 生产实现依赖真机验证，JVM 单测仅覆盖 in-memory 路径
+ 需要同步更新的文档或代码：
  + `app/build.gradle.kts`（依赖移动）
  + 新增 `InProcessTransport` / `FileSystemAccess` / `SafFileAccess` / `ToolConfirmationGate` /
    `UiConfirmationGate` / `FilesystemMcpServer` / `LocalMcpToolProvider` / `McpToolProviderDispatcher`
  + 改造 `CapabilitiesViewModel` / `CapabilitiesScreen` / `PrismApplication`
  + `docs/decisions/README.md` / `README.md` 索引
  + `prd.json`（US-009 passes 置 true）

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 进程内握手时序（createSession 先于 client.connect） | 中 | 已通过字节码确认 createSession 内部 connect 不阻塞等待，client.connect 再触发 initialize；集成测试覆盖 |
| SAF 生产实现不可 JVM 单测 | 中 | `FileSystemAccess` 接口 + in-memory fake 覆盖工具逻辑；SAF 实现保持薄封装，真机验证 |
| 工具处理器未确认即执行 | 高 | 门禁置于每个处理器入口，缺省拒绝（confirm=false 返回 isError）；测试断言未确认不执行 |
| 会话/资源泄漏 | 中 | 每次调用在 finally 中 close client / session / transport，对齐 `McpClientManager` closeQuietly |
| 多会话并发 | 低 | 单例 Server + 每调用独立会话，`ServerSessionRegistry` 管理；调用结束清理会话 |
| 确认对话框与调用线程同步 | 中 | `UiConfirmationGate` 经 SharedFlow 挂起等待 UI 响应，结构化并发安全 |

---

## 参考

+ [US-009 Filesystem MCP Server 源码考古](../reports/2026-08-06-us009-filesystem-mcp-archaeology.md)
+ [MCP Kotlin SDK Server 文档](https://kotlin.sdk.modelcontextprotocol.io/kotlin-sdk-server/index.html)
+ [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
+ [ADR-001](ADR-001-prism-tech-stack.md)：技术栈锁定 / 形态 A+B 预设
+ [ADR-005](ADR-005-mcp-client-integration.md)：McpToolProvider 契约 / 测试策略
