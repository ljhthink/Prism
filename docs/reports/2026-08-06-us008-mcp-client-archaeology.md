# 源码考古报告：US-008 MCP Client 集成点勘察

| 项目 | 内容 |
|---|---|
| 执行 Agent | code-archaeologist |
| 任务令牌 | TKN-PRISM-ARCHAEOLOGY-US008-001 |
| 考古日期 | 2026-08-06 |
| 考古目标 | d:\s0611\code\Prism（US-008 集成 MCP Kotlin SDK Client） |
| 考古模式 | 完整考古 |

## 1. 建立大图景

- **测试用例分布**：`app/src/test/java/io/prism/` 覆盖数据层（ProviderConfig/OjbectBox）、安全层（ApiKey 加密）、网络层（OpenAICompatibleProvider SSE 流式）。网络层测试采用「internal 纯函数单测 + 真实 Ktor Netty SSE 服务器集成」两层策略（ADR-004 4.7）。
- **配置文件**：`gradle/libs.versions.toml`（Kotlin 2.3.21 / Ktor 3.3.3 / mcp 0.12.0）、`settings.gradle.kts`（阿里云镜像 + content filter）、`app/build.gradle.kts`（已含 `mcp-kotlin-sdk-client` 依赖）。
- **入口链路**：`PrismApplication.onCreate` 构建 `BoxStore` → lazy 装配各 Repository/Provider → ViewModel Factory 经 `APPLICATION_KEY` 注入 → Composable `viewModel(factory=...)`。

## 2. 微观分析

- **接口隔离**：`ChatStreamProvider`（流式对话）与 `OpenAICompatibleProvider`（实现）依赖倒置，为 `McpToolProvider` 提供模板。
- **依赖图**：`PrismApplication(httpClient, boxStore, apiKeyRepository)` → Repository/Provider → ViewModel → Screen。
- **命名校验**：实体/Repository/ViewModel/Factory 命名规范统一（`XxxRepository`/`XxxViewModel`/`companion Class.Factory`）。
- **模式/反模式**：@Entity + @Convert 类型转换、Repository + MutableStateFlow + refreshFlows、Factory 构造注入、stateIn 订阅、internal 纯函数抽离——均在现有代码建立且高度一致。

## 3. 动态逆向与热点分析

- **Git 热点**：`ProviderConfig`/`ProviderConfigRepository`/`SettingsViewModel`/`OpenAICompatibleProvider` 为高频演进模块，是模式源头。
- **假设验证**：
  - 假设「CapabilitiesScreen 为静态数据」→ 证实（`McpServer` data class + `localMcp`/`remoteMcp` 硬编码 + 空 onClick）。
  - 假设「复用 httpClient 可连接 MCP Streamable HTTP」→ 证实（`StreamableHttpClientTransport(httpClient, url)` 官方设计）。
- **双向追溯**：UI 挂载点 `PrismApp.kt:86` 无参注册 `CapabilitiesScreen()`，改造无需改导航。

## 4. 架构图

```
PrismApplication
 ├─ boxStore ─→ McpServerRepository ─→ servers: StateFlow<List<McpServerConfig>>
 ├─ httpClient ─→ McpClientManager ─→ StreamableHttpClientTransport ─→ listTools/callTool
 └─ apiKeyRepository ─┘
                    ↓
        CapabilitiesViewModel（Factory 注入）
                    ↓
        CapabilitiesScreen（替换静态 McpServer 数据）
```

## 5. 风险清单

| 风险 | 等级 | 证据 |
|---|---|---|
| MCP SDK 0.12.0 API 变动 | 中 | SDK 仍在迭代（main 已到 0.15.x），需以 0.12.0 文档为准 |
| Streamable HTTP 在 MockEngine 下不可测 | 中 | 同 SSE（ADR-004 4.7），需真实 Ktor Netty 服务器集成测试 |
| 协程/Flow 集成 | 高 | `connect()` 长连接需生命周期管理，`close()` 释放防泄漏 |
| @Entity 变更对象模型迁移 | 低 | 新增实体向后兼容，ObjectBox 自动分配 UID |
| MCP SDK 依赖的 Ktor 版本 | 中 | 需确认与 Ktor 3.3.3 兼容（已通过 compileDebugKotlin 验证） |
| apiKeyRef 明文安全 | 中 | 沿用 `apiKeyRef` 引用模式，经 `readApiKeyOnce` 解密 |

## 6. 入门路径

- 数据层：读 `ProviderConfig.kt` + `ProviderConfigRepository.kt` → 仿写 `McpServerConfig` + `McpServerRepository`。
- 连接层：读 `OpenAICompatibleProvider.kt` + `ChatStreamProvider.kt` → 仿写 `McpClientManager` + `McpToolProvider`。
- UI 层：读 `SettingsViewModel.kt` + `SettingsScreen.kt` → 仿写 `CapabilitiesViewModel` 并改造 `CapabilitiesScreen`。

## 7. 结论与建议

US-008 是清晰的增量集成，所有范式均已建立。建议新建 `McpServerConfig` 实体 + `McpServerRepository`
（数据层）、`McpClientManager` + `McpToolProvider`（连接层）、`CapabilitiesViewModel`（UI 层），
并在 `PrismApplication` 装配两个 lazy 单例。最大不确定性集中在 MCP SDK 传输层用法与真实服务器
测试，建议开工前先验证 `StreamableHttpClientTransport` 构造签名。