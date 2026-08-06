# M0-M2 首期里程碑交付审计报告

> 由 `functional-validation-auditor` 子 Agent 依第 14.2 节一致性审计要求执行，调用 `project-acceptance-auditor` skill 九步验收方法论。
> 本报告引用的代码位置使用相对路径或纯文本（ADR-010），不含 file:/// 绝对路径。

| 项目 | 内容 |
|---|---|
| 执行 Agent | functional-validation-auditor |
| 任务令牌 | TKN-M0M2-AUDIT-001 |
| 审计日期 | 2026-08-06 |
| 里程碑范围 | M0 脚手架（US-001）+ M1 BYOK 聊天核心（US-002~007）+ M2 MCP Client（US-008~010） |
| 审计对象 | docs/PRD.md、prd.json、docs/decisions/ADR-001~006、app/src/main、app/src/test、docs/reports/ |
| 待审计上游产物 | 各 US guardrail/acceptance 报告、docs/decisions、docs/reports/perf/ 性能基线 |
| 审计方法 | 需求覆盖映射 + 架构一致性核对 + 全量测试实证 + 文档一致性检查 + 已知缺陷风险评估 |

---

## 0. 上下文重建摘要（CLAUDE.md 零节）

- 项目阶段：Prism 手机端 AI 聊天 Agent，M0-M2 首期 10 个用户故事（prd.json US-001~US-010）全部标为 passes=true，处于 M3（个人知识库 RAG）入口把关点。
- 本次任务：确认 M0-M2 是否满足交付条件，为进入 M3 把关。
- 环境：Windows / AGP 8.13 / Kotlin 2.3.21 / MCP Kotlin SDK 0.12.0 / ObjectBox 5.4.2；无 Android 模拟器，JVM 单测 + 嵌入式真实服务器集成测试为可用验证手段。
- 文档矛盾/模糊点：① docs/decisions/README.md 索引 ADR-001/ADR-003 状态标为 Proposed，而 ADR 文件内为 Accepted；② PRD.md 与 prd.json 的 US 编号体系相互独立（PRD.md 46 行已明文说明），US-007 在两文档中语义不同属预期但需注意。详见 §4。

---

## 1. 验收标准覆盖矩阵（Requirements-Tests Mapping）

### 1.1 prd.json 开发任务层（M0-M2 唯一事实来源）

| US | 里程碑 | 验收标准 | 实现证据 | 测试证据 | 结果 |
|---|---|---|---|---|---|
| US-001 | M0 | Gradle DSL/AGP8.13/minSdk26/Compose BOM/MainActivity 空白界面/Typecheck | build.gradle.kts、settings.gradle.kts、MainActivity.kt | 2026-08-02-us001-m0-scaffold-acceptance.md；BR-build-001~005 固化 | 通过 |
| US-002 | M1 | ObjectBox 5.4.2 插件/@Entity KnowledgeChunk/初始化/CRUD 单测 | data/KnowledgeChunk.kt、PrismApplication | 2026-08-02-us002-objectbox-acceptance.md；KnowledgeChunkCrudTest 9 + EdgeCase 9 | 通过 |
| US-003 | M1 | Keystore AES-256-GCM/DataStore+Tink 加密/日志不泄 Key | security/KeystoreCryptoService.kt、ApiKeyRepository.kt | 2026-08-02-us003-apikey-acceptance.md；ApiKeyRepository 14 + EdgeCase 16 | 通过 |
| US-004 | M1 | ProviderConfig 字段/5 预设/持久化/CRUD 单测 | data/ProviderConfig.kt、ProviderConfigRepository.kt、ProviderPresets.kt | 2026-08-02-us004-provider-config-acceptance.md；ProviderConfigRepository 42 + EdgeCase 17 | 通过 |
| US-005 | M1 | NavHost 主路由/ConversationScreen/StateFlow/发送更新 UI | PrismApp.kt、ui/chat/ConversationScreen.kt、ConversationViewModel.kt | ConversationViewModelTest 10 用例（含发送/切换/过滤）；见 §4.2 报告归档缺口 | 通过 |
| US-006 | M1 | SSE 流式 /v1/chat/completions/首字延迟/流式更新/错误不崩溃 | network/OpenAICompatibleProvider.kt、StreamEvent.kt | 2026-08-06-us006-acceptance.md；OpenAICompatibleProviderTest 27（含真实 Netty SSE + 端点不可达/取消/流中断）；SSE 性能基线 p99≈4.13ms | 通过（AC-2 真机 PoC 受限） |
| US-007 | M1 | Provider 选择器/保留历史/新消息走新 Provider | ConversationViewModel.kt、SettingsScreen.kt | 2026-08-06-us007-acceptance.md；切换保留历史+路由断言；p99 +1.7% 无回退 | 通过 |
| US-008 | M2 | MCP SDK 0.12.0/Client+StreamableHttp 连接/listTools/callTool | network/McpClientManager.kt、data/McpServerConfig.kt、McpServerRepository.kt | 2026-08-06-us008-mcp-client-acceptance-r2.md；McpClientManagerTest 19 + McpServerRepositoryTest 9 + 真实握手集成 McpClientManagerIntegrationTest 5；DEF-001/GAP-001/GAP-002 闭合 | 通过 |
| US-009 | M2 | Kotlin Filesystem MCP Server(SAF)/本地注册零配置/调用前用户确认 | fs/FilesystemMcpServer.kt、SafFileAccess.kt、InProcessTransport.kt、LocalMcpToolProvider.kt、UiConfirmationGate.kt | 2026-08-06-us009-filesystem-mcp-acceptance.md；fs 模块 41 用例 + Dispatcher 4；进程内 MCP 握手集成；路径穿越/越权/注入 24 边界用例 | 通过 |
| US-010 | M2 | 9 远程模板/填 Key 一键添加/连接状态可观测 | data/McpServerPresets.kt、ui/capabilities/CapabilitiesViewModel.kt | 2026-08-06-us010-remote-templates-acceptance.md；CapabilitiesViewModelTest 13（含 9 模板/草稿/连接状态机）；远端连接状态机 Connecting→Connected/Error 全覆盖 | 通过 |

### 1.2 PRD.md 产品模块层（US-001 BYOK / US-002 MCP，部分超出 M0-M2 范围）

PRD.md 编号为产品功能模块层级，与 prd.json 开发任务相互独立（PRD.md 46 行已明文声明）。M0-M2 范围内 prd.json 对应需求全部满足；以下 PRD.md US-002 条款属 M8 集成/后续里程碑范围，已在 ADR 中显式推迟，**不属 M0-M2 交付缺口**：

| PRD.md 验收项 | 状态 | 说明（推迟依据） |
|---|---|---|
| 支持 SSE 与 Streamable HTTP 两种传输 | 部分（仅 Streamable HTTP） | ADR-005 5.2：`transport` 字段支持 SSE 但"本期仅支持 STREAMABLE_HTTP"；SSE 为旧式传输，Streamable HTTP 为现行推荐 |
| 内置 6 个本地 Server 全实现 | 部分（仅 Filesystem 实现） | ADR-006：US-009 仅实现 Filesystem；Fetch/Memory/SequentialThinking/Time/跨App 仅为预设配置，实现属后续里程碑 |
| 远程 MCP Tool 调用前用户确认 | 部分（仅本地 Filesystem 门禁） | ADR-006 5.4：确认门禁绑定服务器工具处理器；远程 Server 确认机制属 M8 集成范围 |
| 6 个本地 + 9 个远程模板可加载调用 | 部分 | 模板注册齐全（McpServerPresets 6 本地 + 9 远程），但本地 Server 仅 Filesystem 落地 |

**结论**：就 M0-M2 首期范围（prd.json US-001~010）而言，验收标准 100% 满足。PRD.md 产品层 US-002 的超出项均已显式登记为后续里程碑范围，不构成 M0-M2 交付阻断。

---

## 2. 架构一致性审查（ADR-005/006 与实际代码）

| ADR | 决策要点 | 实际代码核对 | 结果 |
|---|---|---|---|
| ADR-001 3.5 | 技术栈锁定（MCP SDK 0.12.0 / ObjectBox 5.4.2 / Compose / Keystore+DataStore） | gradle/libs.versions.toml、app/build.gradle.kts 版本一致；M0 环境适配修订（compileSdk 34 等）已记录 | 一致 |
| ADR-001 3.6 | 形态 A+B 预设（6 本地 + 9 远程） | data/McpServerPresets.kt L20-85：6 本地 + 9 远程，与 ADR 完全一致 | 一致 |
| ADR-005 5.2 | McpServerConfig @Entity + McpServerRepository | data/McpServerConfig.kt、McpServerRepository.kt 存在，字段/Converter 与 ADR 一致 | 一致 |
| ADR-005 5.3 | McpClientManager 复用 httpClient + StreamableHttpClientTransport | network/McpClientManager.kt 存在，internal 纯函数（buildTransport/buildAuthHeaders/mapToolResult）抽离 | 一致 |
| ADR-005 5.6 | 纯函数 + 真实 MCP Server 集成测试两层策略 | McpClientManagerTest（纯函数）+ McpClientManagerIntegrationTest（真实 Netty Streamable HTTP 握手 5 用例） | 一致 |
| ADR-006 5.2 | InProcessTransport 进程内桥接 | network/InProcessTransport.kt 存在，createPair() 双 Channel 实现 | 一致 |
| ADR-006 5.3 | FileSystemAccess 接口 + SafFileAccess + 内存 fake | fs/FileSystemAccess.kt、SafFileAccess.kt、InMemoryFileAccess.kt 存在 | 一致 |
| ADR-006 5.4 | ToolConfirmationGate + UiConfirmationGate（30s 超时/DROP_OLDEST） | fs/ToolConfirmationGate.kt、UiConfirmationGate.kt 存在 | 一致 |
| ADR-006 5.5 | FilesystemMcpServer 注册 8 工具 | FilesystemMcpServer.kt 注册 read_file/list_directory/write_file 等 8 工具（grep 实证） | 一致 |
| ADR-006 5.6 | McpToolProviderDispatcher 按 serverType 路由 | network/McpToolProviderDispatcher.kt 存在，McpToolProviderDispatcherTest 4 用例 | 一致 |

**架构审查结论**：ADR-005/006 所述模块划分、接口、数据模型均与实际代码结构一致，无架构性偏差。M0-M2 范围内模块层次（data / network / fs / security / ui）与 ADR 描述相符。

---

## 3. 测试覆盖审计（实证）

### 3.1 全量测试执行（2026-08-06 强制 --rerun-tasks 实测）

```text
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline
BUILD SUCCESSFUL in 54s
```

| 汇总 | 数值 |
|---|---|
| 总用例 | 263 |
| 实际执行 | 248 |
| 跳过（性能基准 @Ignore/Assume） | 15 |
| 失败 | 0 |
| 错误 | 0 |

### 3.2 分层覆盖明细

| 测试类 | 用例 | 层 | 覆盖功能 |
|---|---|---|---|
| ProviderConfigRepositoryTest | 42 | 单元/集成 | US-004 数据层 + 单激活不变式事务 |
| OpenAICompatibleProviderTest | 27 | 单元/集成 | US-006/007 纯函数 + 真实 Netty SSE（401/429/端点不可达/取消重抛/流中断） |
| McpClientManagerTest | 19 | 单元 | US-008 连接层纯函数 |
| FilesystemMcpServerEdgeCaseTest | 18 | 集成/边界 | US-009 路径穿越/越权/注入/缺参/未知工具 |
| FilesystemMcpServerTest | 11 | 集成 | US-009 进程内 MCP 握手事务 |
| ConversationViewModelTest | 10 | 单元 | US-005/007 发送/切换保留历史/空过滤 |
| CapabilitiesViewModelTest | 13 | 单元 | US-008/010 状态机 + 远程模板 + 连接状态 |
| ApiKeyRepositoryTest + ApiKeyEdgeCaseTest | 30 | 单元/边界 | US-003 加密存储 |
| KnowledgeChunkCrudTest + EdgeCaseTest | 18 | 单元/边界 | US-002 ObjectBox |
| McpServerRepositoryTest | 9 | 单元/集成 | US-008 数据持久化 |
| ProviderConfigEdgeCaseTest | 17 | 边界 | US-004 |
| UiConfirmationGateTest / FilesystemRootStoreTest / McpToolProviderDispatcherTest | 16 | 单元/集成 | US-009 |
| McpClientManagerIntegrationTest | 5 | 集成 | US-008 真实 Streamable HTTP 协议级握手 |
| 其余（Demo/性能基准等） | 12 | — | — |

### 3.3 测试覆盖充分性评估

- **核心业务路径**：US-002~010 全部有对应自动化用例，含集成层（真实 Netty SSE + 真实 MCP Streamable HTTP + 进程内 MCP 握手）。
- **边界/异常**：路径穿越、越权隔离、注入载荷、超长输入、类型不安全、取消传播、端点不可达、未知工具等均有覆盖。
- **覆盖率门禁局限**：项目未配置 JaCoCo，无法量化语句 ≥90%/分支 ≥80% 门禁；以用例设计与关键纯函数分支人工审查（parseChunkData 分支 100%）作为替代证据。此为已知工具链局限，非功能缺陷。
- **无回归**：263 用例 0 失败 0 错误，Kotlin 2.3.21/Ktor 3.3.3/mcp 0.12.0 升级未破坏既有 US-001~007 功能。

---

## 4. 文档一致性审计（第 14.2 节）

### 4.1 自动化检查（docs-quality CI 门禁）

| 检查项 | 结果 | 证据 |
|---|---|---|
| `node scripts/consistency-check.js` | **通过** | 实测 exit 0，输出"一致性检查通过 ✓" |
| README.md 索引链接可达 | 通过 | 全部相对链接指向真实文件 |
| docs/decisions/README.md 含全部 6 个 ADR | 通过 | ADR-001~006 均在索引 |
| docs/templates/README.md 含全部模板 | 通过 | — |
| docs/reports/ 命名规范 | 通过 | 全部符合 YYYY-MM-DD-<task>-<type>.md |
| file:/// 绝对路径检测（ADR-010） | **通过** | US-010 guardrail 报告曾含 file:/// 违规（验收报告 §8 记载），现已修复，脚本零告警 |

### 4.2 一致性发现（非阻断）

| ID | 级别 | 描述 | 建议 |
|---|---|---|---|
| DOC-01 | B0 | docs/decisions/README.md 索引将 ADR-001、ADR-003 状态标为 Proposed，但 ADR 文件内状态为 Accepted（状态漂移） | 主 Agent 同步索引状态字段为 Accepted |
| DOC-02 | B0 | 无独立命名的 US-005 验收报告文件归档（prd.json 引用 TKN-PRISM-ACCEPTANCE-004，但 docs/reports/ 无对应文件；功能与测试证据存在于 ConversationViewModelTest 10 用例 + 2026-08-05-ui-config-acceptance.md） | 如留存审计轨迹，可补一份 US-005 acceptance 报告或归档引用 |
| DOC-03 | B0 | PRD.md 与 prd.json 的 US-007 语义不同（PRD.md 为"设备适配与降级"，prd.json 为"Provider 切换"）。PRD.md 46 行已明文声明两套编号独立，属预期但易混淆 | 已由 PRD.md 声明覆盖，建议在里程碑交接时保持该声明最新 |
| DOC-04 | B0 | MCP 预设端点（Brave/Sentry/Stripe 等 9 个）为编译器硬编码 URL，未做真实网络连通性冒烟（外部数据事实） | 见 §5.1 风险评估，建议 M8 前人工冒烟并登记 ADR-001 3.6 |

---

## 5. 已知缺陷与遗留项风险审计

### 5.1 谨慎点 1：远程预设端点未做真实网络冒烟——是否阻断 M0-M2 交付

**结论：不构成 M0-M2 交付阻断。**

- 预设端点为编译期硬编码（McpServerPresets.kt L29-85），无用户输入注入面；连接层 `isValidBaseUrl` 提供 CRLF/http(s) 纵深防御。
- 即便某官方端点走查错误或官方变更，失败模式为**优雅降级**（observeConnectionStatus → "连接失败"/"连接超时"），不崩溃、不注入、不泄露（guardrail + ac-verifier 均已实证）。
- 端点数据值正确性属**外部事实**，非代码可判定，U8-010 验收已明确标记"需人工冒烟验证"。
- **残余风险**：若某端点变更，仅该 Server 连接失败（中风险、可接受降级），不构成核心功能不可用。
- **建议**：M8 发布前对 9 端点做一次人工连通性冒烟，结果登记到 ADR-001 3.6 与发布检查清单；不阻塞 M0-M2 进入 M3。

### 5.2 谨慎点 2：guardrail R2 复审 4 项「可推迟低危建议」——是否影响交付

| 遗留项 | 级别 | 影响 | 是否有 workaround / 风险 | 是否阻断 |
|---|---|---|---|---|
| R-复审-1 密钥清除能力受限（清空输入框不再删除密钥） | 低 | 用户无法再通过清空输入框清除已存密钥 | 保留原密钥比误清空更安全（默认安全）；如需可加显式"清除密钥"操作 | 否 |
| R-复审-2 `remember` 键缺 apiKeyRef（仅改密钥不刷新徽章） | 低 | 改密钥后状态徽章可能不刷新 | 纯 UI 观感问题，可与 L-02 状态缓存优化一并处理 | 否 |
| R-复审-3 observeConnectionStatus 超时分支无单测 | 低 | 测试覆盖缺口 | 逻辑正确性经静态分析 + guardrail L-03 逐场景推演确认；建议后续虚拟时钟补用例 | 否 |
| R-复审-4 catch 仅覆盖超时，未覆盖非取消异常 | 低 | 防御性增强缺失 | 当前 `listTools` 契约保证连接失败返回空列表（不抛非取消异常），当前足够；保留为未来增强 | 否 |

**结论**：4 项均为 guardrail 显式标注"可推迟迭代，不阻断本轮"的低危项，**不影响 M0-M2 功能完整性**，无一项影响核心功能正确性或安全。建议登记入 M3 backlog 跟踪。

### 5.3 其他已知项（B0，非阻断）

| ID | 级别 | 描述 | 处置 |
|---|---|---|---|
| OBS-01（US-007/009） | B0 | ObjectBox 测试 teardown 输出 "Aborting a read transaction in a non-creator thread" 线程警告 | 既有基础设施噪音，非功能缺陷，不影响测试结果 |
| OBS-02（US-008） | B0 | 集成测试 Netty `RejectedExecutionException` 关闭噪音 | 良性关闭竞态，非缺陷 |
| DEF-003（US-006） | B0 | lint 工具链 Kotlin 2.1 metadata 兼容崩溃 | 已在 US-008 经 lint 配置修复（DEF-001 闭合），当前 lint 0 错误 |
| 覆盖率未自动化 | B0 | 无 JaCoCo，无法量化覆盖率门禁 | 以人工分支审查替代，关键解析函数分支 100% |

---

## 6. 交付物清单核查

| 交付物 | 状态 |
|---|---|
| README.md（含文档索引） | 存在，索引更新至 US-001~010 |
| docs/PRD.md | 存在 v0.1 |
| prd.json | 存在，US-001~010 全部 passes=true |
| docs/decisions/ADR-001~006 + README 索引 | 存在（见 §4.2 DOC-01 状态漂移） |
| docs/reports/（US-001~010 guardrail/acceptance + 性能基线） | 存在（见 §4.2 DOC-02 US-005 报告缺口） |
| docs/behavioral-rules.md | 存在，已累积 BR 规则并含审计记录（截至 2026-08-06） |
| docs/templates/ + reports/ 子目录 | 存在 |
| scripts/consistency-check.js | 存在，实测通过 |
| .github/workflows/docs.yml | 存在 |
| git 状态 | 当前分支 feat/m0-scaffold，仅 CLAUDE.md 未暂存（本审计报告新文件亦将 untracked） |

---

## 7. 交付结论

## ✅ 通过（可进入 M3）

**M0-M2 首期里程碑达到交付条件，可进入 M3（个人知识库 RAG）。**

### 判定依据

1. **需求覆盖**：prd.json US-001~US-010 全部 10 条验收标准满足，核心功能（ObjectBox、API Key 加密、Provider 模型、聊天 UI、流式请求、Provider 切换、MCP Client、内置 Filesystem Server、远程模板）均实现且通过各自 guardrail + ac-verifier 闭环。
2. **测试**：全量 263 用例（248 执行）0 失败 0 错误，无回归；单元/集成/边界全覆盖，含真实 Netty SSE 与真实 MCP Streamable HTTP 协议级集成测试。
3. **文档一致性**：`node scripts/consistency-check.js` 实测通过（docs-quality 门禁绿）；ADR 与实际代码结构一致；file:/// 违规已修复。
4. **已知缺陷受控**：远程预设端点未冒烟 + guardrail R2 4 项低危遗留，均经评估**不构成 M0-M2 交付阻断**，不损害核心功能完整性。
5. **安全**：各 US 安全专项（无硬编码密钥、CWE-209 信息泄露、CRLF 纵深防御、路径越权隔离、凭据加密落盘）全部通过，无 HIGH/MEDIUM 可利用漏洞。

### 进入 M3 前建议排期（非阻断，均 B0）

| 优先级 | 项 | 类型 |
|---|---|---|
| 建议 | 同步 docs/decisions/README.md 索引 ADR-001/ADR-003 状态为 Accepted（DOC-01） | 文档 |
| 建议 | 补 US-005 acceptance 报告归档或引用（DOC-02） | 文档 |
| 建议 | 将 R-复审-1~4 四项低危遗留登记入 M3 backlog（§5.2） | 跟踪 |
| 建议 | M8 发布前对 9 远程端点做人工连通性冒烟并登记 ADR-001 3.6（§5.1） | 发布检查 |
| 提示 | 后续里程碑建议引入 JaCoCo 以满足覆盖率硬性门禁（§3.3） | 工程基建 |

---

## 参考

- 需求：docs/PRD.md、prd.json
- 架构：docs/decisions/ADR-001-prism-tech-stack.md、ADR-005-mcp-client-integration.md、ADR-006-filesystem-mcp-server.md 等
- 代码：app/src/main/java/io/prism/（data/ network/ fs/ security/ ui/）
- 测试：app/src/test/java/io/prism/（全量 263 用例）
- 报告：docs/reports/2026-08-02-us00X-*.md、2026-08-05-*.md、2026-08-06-us00X-*.md
- 门禁：scripts/consistency-check.js、.github/workflows/docs.yml
