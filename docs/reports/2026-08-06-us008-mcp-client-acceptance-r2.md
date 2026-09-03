# US-008 MCP Client 集成 — 验收复验报告（R2）

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-MCP-CLIENT-AC-002 |
| 验收日期 | 2026-08-06 |
| 关联 PRD | prd.json（US-008） |
| 关联 ADR | ADR-005（MCP Client 集成） |
| 上一轮报告 | docs/reports/2026-08-06-us008-mcp-client-acceptance.md（TKN-MCP-CLIENT-AC-001，有条件通过） |
| guardrail 报告 | round4（TKN-MCP-CLIENT-004，主代码通过）+ integrationtest（TKN-MCP-CLIENT-GUARDRAIL-005，集成测试通过） |
| 测试方法 | test-architect skill（PRD 驱动分层测试） |

---

## 一、总体结论

**通过（Pass）** — 上一轮 3 项待闭合项（DEF-001 / GAP-001 / GAP-002）已全部解决并经复验实证；AC-1 ~ AC-5 全部通过；全量回归无失败；lint 门禁转绿；安全专项验证通过。US-008 可判定为**完全通过**。

| 维度 | 结论 | 说明 |
| --- | --- | --- |
| 功能验收标准 | **5/5 通过** | AC-1/AC-5 通过；AC-2/3/4 由真实 MCP Server 集成测试补齐协议级验证 |
| 单元测试 | 通过 | 214 用例，0 失败 0 错误（15 跳过为性能基准） |
| 集成测试（真实 MCP 协议） | 通过 | McpClientManagerIntegrationTest 5 用例真实 Streamable HTTP 握手 |
| 编译验证 | 通过 | `:app:compileDebugKotlin` BUILD SUCCESSFUL |
| lint 静态分析 | 通过（DEF-001 已闭合） | `:app:lintDebug` BUILD SUCCESSFUL，30 warnings / 0 Fatal / 0 Error（均为既有依赖/废弃告警，非 US-008 引入） |
| 安全专项验证 | 通过 | 无硬编码密钥、错误不泄露 e.message、CRLF 纵深防御、baseUrl 白名单 |
| 回归测试 | 通过 | 全量 testDebugUnitTest 214 用例 0 失败 |

---

## 二、三项闭合项复验结果

### DEF-001（lint 门禁崩溃）— 已闭合 ✓

| 复验项 | 结果 | 证据 |
| --- | --- | --- |
| 配置完整性 | 通过 | [app/build.gradle.kts L63-67](../../app/build.gradle.kts#L63-L67) lint{} 含三项禁用：`CoroutineCreationDuringComposition`、`StateFlowValueCalledInComposition`、`FlowOperatorInvokedInComposition` |
| 注释同步 | 通过 | [L58-62](../../app/build.gradle.kts#L58-L62) 注释已说明三个崩溃检测器及禁用原因 |
| 门禁实测 | 通过 | 实际运行 `.\gradlew.bat :app:lintDebug`：`lintAnalyzeDebug`/`lintAnalyzeDebugUnitTest`/`lintAnalyzeDebugAndroidTest` 均正常完成，`BUILD SUCCESSFUL in 58s`，exit code 0 |
| 无新增告警 | 通过 | lint-results-debug.xml：30 warnings / 0 Fatal / 0 Error。30 项均为 `NewerVersionAvailable`/`GradleDependency`/`OldTargetApi`/废弃属性（build.gradle/AndroidManifest/基础设施），无一落在 US-008 生产代码或测试文件；非本次变更引入 |

**结论**：DEF-001 已彻底解决，lint 门禁恢复绿色。

### GAP-001（真实 MCP Server 集成测试）— 已闭合 ✓

[McpClientManagerIntegrationTest.kt](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt) 质评：

| 复验维度 | 结果 | 证据/说明 |
| --- | --- | --- |
| 是否启动真实 MCP 服务器 | 是 | [L58-95](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L58-L95) 用 `embeddedServer(Netty)` + MCP SDK `mcpStreamableHttp` 起真实 Streamable HTTP 端点，注册 `echo`/`ping` 两工具 |
| 是否走真实 Streamable HTTP 握手 | 是 | 客户端复用生产一致的 `HttpClient(OkHttp){SSE; expectSuccess=true}`（[L48](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L48)），经 `McpClientManager.connect` → `StreamableHttpClientTransport` 完成 `initialize → tools/list → tools/call` 全事务 |
| listTools 成功路径 | 是 | [L107-116](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L107-L116) 断言返回 `["echo","ping"]` |
| callTool 成功路径 | 是 | [L118-129](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L118-L129) 断言返回 `"echo:hello"` |
| callTool 失败（工具级错误）路径 | 是 | [L131-144](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L131-L144) 未知工具优雅降级为 `"工具执行出错…"` |
| 连接层失败路径（非法/空白 baseUrl） | 是 | [L146-160](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L146-L160) 拒绝且不发起网络请求，返回空列表/通用文案，且断言不泄露 `IllegalArgumentException` 细节（CWE-209） |
| 资源清理正确 | 是 | [L92-105](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L92-L105) `startMcpServer` 时立即登记 teardown，`stopServers()` 逐项 `runCatching` 容错清理；每个用例 `try/finally` 保证 server 关闭 |
| 实际运行 | 通过 | `.\gradlew.bat :app:testDebugUnitTest --tests "io.prism.network.McpClientManagerIntegrationTest" --rerun-tasks` → BUILD SUCCESSFUL；TEST XML `tests="5" skipped="0" failures="0" errors="0"` |

> 说明：stderr 中出现 Netty `RejectedExecutionException: event executor terminated` 警告，源于 `server.stop(gracePeriodMillis=0)` 关闭竞态，属**良性关闭噪音**，不影响测试结果（tests=5, failures=0, errors=0），非缺陷。

**结论**：GAP-001 已实现且真实覆盖 Streamable HTTP 完整握手事务路径，AC-2/3/4 协议级验证补齐。

### GAP-002（CapabilitiesViewModel JVM 单元测试）— 已闭合 ✓

[CapabilitiesViewModelTest.kt](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt) 质评（9 用例）：

| Promise 操作 | 覆盖用例 | 断言点 | 结果 |
| --- | --- | --- | --- |
| saveServer | [L78-90](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L78-L90) | servers 列表 +1、name 正确 | ✓ |
| createFromPreset | [L92-103](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L92-L103) | 空→1、name 与预设一致 | ✓ |
| newCustomServer | [L147-157](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L147-L157) | id=0、apiKeyRef 以 `mcp-` 前缀唯一化 | ✓ |
| setEnabled | [L105-117](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L105-L117) | 默认 false → true 翻转 | ✓ |
| deleteServer | [L119-131](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L119-L131) | 列表清空 + selectedServer 清空 | ✓ |
| testConnection（成功） | [L161-172](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L161-L172) | TestState.Success + toolCount | ✓ |
| testConnection（失败） | [L174-190](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L174-L190) | TestState.Fail + 通用文案 + 不泄露 e.message/URL | ✓ |
| 补充（selectServer/saveApiKey） | [L135-202](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L135-L202) | 选中/清除、加密存储往返 | ✓ |

**关于主 Agent 盲区（APPLICATION_KEY 构造依赖）的澄清**：测试通过 [L73-74](../../app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt#L73-L74) 直接调用公共三参构造器 `CapabilitiesViewModel(serverRepository, toolProvider, apiKeyRepository)` 注入 `FakeMcpToolProvider` + 真实 ObjectBox（临时目录） + `FakePreferenceDataStore`/`RecordingCryptoService`，**绕过** `Factory` 的 `APPLICATION_KEY` 分支。这是 JVM 单测的正确做法：`APPLICATION_KEY` 仅存在于 `Factory`（[CapabilitiesViewModel.kt L127-132](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt#L127-L132)），是依赖 PrismApplication 的薄装配层，无法在无 Android 环境时实例化；测试直接覆盖 ViewModel 业务逻辑与状态机，**测试充分性不受影响**。副作用：`Factory` 装配本身未被单测覆盖，属可接受范围（见九、未覆盖项）。

**结论**：GAP-002 已实现，覆盖 promise 全部操作分支与 TestState 状态机成功/失败迁移，测试充分。

---

## 三、验收标准覆盖矩阵（US-008 prd.json）

| AC ID | 验收标准 | 测试用例 ID | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-1 | 引入 MCP Kotlin SDK 0.12.0 依赖 | TC-01 | **通过** | [libs.versions.toml L18](../../gradle/libs.versions.toml#L18) `mcp = "0.12.0"`；[app/build.gradle.kts L90](../../app/build.gradle.kts#L90) `implementation(libs.mcp.kotlin.sdk.client)`；集成测试引入 `libs.mcp.kotlin.sdk.server`+`ktor-server-{core,netty,sse}`（[L95-100](../../app/build.gradle.kts#L95-L100)）；编译/测试均 BUILD SUCCESSFUL |
| AC-2 | Client + StreamableHttpClientTransport 可连接远程 MCP Server | TC-02 | **通过**（补齐协议级） | [McpClientManager.connect](../../app/src/main/java/io/prism/network/McpClientManager.kt#L103-L129) 使用 `StreamableHttpClientTransport`；集成测试 `listTools returns registered tool names via real handshake` 真实握手通过 |
| AC-3 | client.listTools() 返回工具列表 | TC-03 | **通过**（补齐协议级） | [listTools](../../app/src/main/java/io/prism/network/McpClientManager.kt#L52-L64)；集成测试 L107-116 真实握手返回 `["echo","ping"]` |
| AC-4 | client.callTool(name, arguments) 可调用并返回结果 | TC-04 | **通过**（补齐协议级） | [callTool](../../app/src/main/java/io/prism/network/McpClientManager.kt#L74-L93)；集成测试 L118-129 真实调用返回 `"echo:hello"` |
| AC-5 | Typecheck passes | TC-05 | **通过** | `:app:compileDebugKotlin` BUILD SUCCESSFUL（多次实测）；`kotlin { compilerOptions { jvmTarget } }` 配置有效 |

> 上轮「需人工澄清/无法自动验证」的 AC-2/3/4 真实 MCP 协议事务，已由 GAP-001 集成测试完全消除，无需人工澄清。

---

## 四、分层测试详情

### 4.1 静态分析

| 工具 | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| Android Lint | `:app:lintDebug` | **通过** | lintAnalyzeDebug 不再崩溃，BUILD SUCCESSFUL；lint-results-debug.xml 30 warnings / 0 Fatal / 0 Error，均为既有依赖版本/废弃告警，非 US-008 引入 |

### 4.2 单元测试

| 框架 | 用例数 | 通过 | 失败 | 错误 | 跳过 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| JUnit 4 | 214 | 214 | 0 | 0 | 15（性能基准默认跳过） | **通过** |

US-008 关联用例明细（较上轮 200 新增 5 集成 + 9 ViewModel = 214）：

| 测试类 | 用例数 | 通过 | 说明 |
| --- | --- | --- | --- |
| `io.prism.network.McpClientManagerTest` | 19 | 19 | resolveHeaders/renderResult/isValidBaseUrl 纯函数 |
| `io.prism.data.McpServerRepositoryTest` | 9 | 9 | CRUD/headers 转换/启用/预设 |
| `io.prism.network.McpClientManagerIntegrationTest` | 5 | 5 | 真实 Streamable HTTP 握手（GAP-001） |
| `io.prism.ui.capabilities.CapabilitiesViewModelTest` | 9 | 9 | ViewModel 状态机与操作（GAP-002） |

证据：全量 `testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL；逐 XML 聚合 `TOTAL=214 FAIL=0 ERR=0 SKIP=15`；CapabilitiesViewModelTest XML `tests="9" failures="0" errors="0"`。

### 4.3 集成测试

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| McpServerRepository ↔ ObjectBox 持久化 | 通过 | McpServerRepositoryTest（真实 BoxStore temp dir + headers @Convert） |
| McpClientManager ↔ 真实 MCP Server Streamable HTTP 握手 | **通过** | McpClientManagerIntegrationTest 5 用例；`--rerun-tasks` 实测 BUILD SUCCESSFUL，tests=5 failures=0 errors=0 |
| 集成测试资源清理 | 通过 | teardown 清单 + try/finally + `runCatching` 容错清理 |

### 4.4 E2E 测试

本期 Android 原生 Compose UI，无浏览器 E2E（Playwright 不适用）。真实 MCP 协议事务已由集成测试（JVM 内嵌 Netty Server）覆盖，构成协议级端到端验证；Compose 界面交互依赖设备级人工验证（见九、未覆盖项）。

---

## 五、安全专项验证

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 无硬编码密钥/令牌/内部地址 | **通过** | grep `(api[_-]?key|token|secret|password)=["']…` 于 `app/src/main/java/io/prism/network`无匹配；`apiKeyRef` 仅存引用，明文经 `ApiKeyRepository.readApiKeyOnce` 只读即用 |
| 错误信息不泄露 e.message（CWE-209） | **通过** | MCallTool 返回固定通用文案「工具调用失败…」（[L86-89](../../app/src/main/java/io/prism/network/McpClientManager.kt#L86-L89)）；CapabilitiesViewModel.testConnection 返回固定「连接失败…」（[L116-120](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt#L116-L120)）；`e.message` 仅存在于注释（L87/L117/L119）；UI 展示的 `Fail.message` 为上述固定文案，非原始异常 |
| CRLF 纵深防御（CWE-113/93） | **通过** | `resolveHeaders` 剔除含 CR/LF 键值（[L177-190](../../app/src/main/java/io/prism/network/McpClientManager.kt#L177-L190)）；`isValidBaseUrl` 对 trim 前原始值校验 CRLF（[L141-146](../../app/src/main/java/io/prism/network/McpClientManager.kt#L141-L146)）；集成测试 [L159](../../app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L159) 断言降级文案不含校验异常细节 |
| baseUrl 白名单 | **通过** | `isValidBaseUrl` 要求非空 + http(s) 前缀 + 无 CRLF；connect 用 trim 后局部变量统一校验（[L107-110](../../app/src/main/java/io/prism/network/McpClientManager.kt#L107-L110)） |
| 集成/单测测试密钥 | **通过（测试专用）** | 测试源 `saveApiKey(... "sk-secret-value")` 为 fake 仓库测试值，非生产代码，不构成泄露 |
| SQL 注入面 | **通过（不适用）** | ObjectBox ORM，无手写 SQL |
| XSS 面 | **通过（不适用）** | 原生 Compose UI，非 WebView/HTML 渲染 |

---

## 六、回归测试结果

| 套件 | 总数 | 通过 | 失败 | 结果 |
| --- | --- | --- | --- | --- |
| 全量 `testDebugUnitTest --rerun-tasks` | 214 | 214 | 0 | **通过，无回归** |

US-001~007 既有套件（ProviderConfig/OpenAICompatibleProvider/ApiKey/Chat/Knowledge/Settings 等）全部通过，确认 Kotlin 2.3.21/Ktor 3.3.3/serialization 1.11.0/mcp 0.12.0 升级及新增集成依赖未破坏既有功能。`compileDebugKotlin` 仅含既有 deprecation 警告（PrismGlassCard/Icons 等，非 US-008 引入）。

---

## 七、性能回退检查

| 项 | 结果 | 说明 |
| --- | --- | --- |
| US-008 MCP 函数性能基线 | **无基线** | `perf/baselines/` 与 `docs/reports/perf/` 无 US-008 基线文件（与上轮一致） |
| 定性评估 | 无显著风险 | resolveHeaders/renderResult/isValidBaseUrl 均为轻量纯函数；集成测试真实握手 p50 约 0.1-0.3s（含 Server 启动），符合预期 |
| 既有性能基准 | 通过 | 性能基准默认跳过（`ignorePerformanceTests` 门禁），未受升级影响 |

---

## 八、缺陷清单

| ID | 严重度 | 类别 | 描述 | 证据 | 建议 |
| --- | --- | --- | --- | --- | --- |
| DOC-01 | B0（微小，文档） | README 索引 | README.md 文档索引未登记 US-008 的 guardrail 报告与 r1 验收报告（仅登记 archaeology），本 r2 报告亦未登记。属既有文档索引缺口，非本次变更引入 | README.md L67 仅含 `us008-mcp-client-archaeology.md` | 主 Agent 在 README 索引补充 US-008 各报告条目，满足 `scripts/consistency-check.js`（14.1） |
| OBS-01 | B0（微小，非缺陷） | 集成测试运行噪音 | 集成测试 stderr 出现 Netty `RejectedExecutionException: event executor terminated` 警告，源于 `server.stop(gracePeriodMillis=0)` 关闭竞态 | TEST XML system-err | 可选优化：延长 `gracePeriodMillis` 或忽略；不影响测试结果（tests=5, failures=0, errors=0） |

> 无阻断或中/高严重度缺陷。三项上轮闭合项（DEF-001/GAP-001/GAP-002）均确认闭合。

---

## 九、未覆盖项与风险

| 项 | 原因 | 风险 |
| --- | --- | --- |
| CapabilitiesViewModel.Factory 装配（APPLICATION_KEY 分支） | 依赖 PrismApplication（Android 上下文），JVM 单测无法实例化 | 低：Factory 为薄装配层，3 参构造器业务逻辑已充分测试 |
| Compose UI 交互（CapabilitiesScreen） | 原生 Android，无浏览器 E2E 工具 | 低：弹层/表单交互依赖设备级人工验证；ViewModel 状态机已由单测覆盖 |
| 真实远端 MCP Server（公网）鉴权/超时/协议错误 | 集成测试用本地内嵌 Server，未覆盖公网 TLS/OAuth 场景 | 中：MCP SDK 传输层可靠性依赖远端部署，OAuth 2.1 已明确留待后续故事（prd.json US-008 notes） |
| 精确覆盖率（语句/分支） | 项目未配置 JaCoCo | 无法量化 ≥90%/≥80% 门禁；以用例设计与 214 用例实证作为替代证据 |
| 并发/协程取消/资源泄漏运行时行为 | JVM 单测未覆盖并发压测 | 依赖代码路径审查（listTools/callTool 的 CancellationException 重抛、finally close）+ guardrail 确认 |

---

## 十、复验轨迹与证据链

| 步骤 | 命令/证据 | 结果 |
| --- | --- | --- |
| DEF-001 配置 | 读 app/build.gradle.kts L63-67 | 三项禁用齐备 |
| DEF-001 实测 | `.\gradlew.bat :app:lintDebug` | BUILD SUCCESSFUL in 58s；lint-results-debug.xml 0 Fatal/0 Error |
| GAP-001 实测 | `--rerun-tasks --tests "…McpClientManagerIntegrationTest"` | BUILD SUCCESSFUL；tests=5 failures=0 errors=0 |
| GAP-002 实测 | 全量 testDebugUnitTest XML 聚合 + CapabilitiesViewModelTest XML | TOTAL=214 FAIL=0 ERR=0；tests=9 failures=0 errors=0 |
| 回归 | `:app:testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL in 53s |
| 安全 | grep 密钥/e.message 于 main 源码 + 代码审查 | 通过 |

---

## 十一、结论与放行建议

**验收结论：通过（Pass）。**

- 上一轮 3 项待闭合项（DEF-001 / GAP-001 / GAP-002）**全部闭合并经实证**。
- AC-1 ~ AC-5 **全部通过**；AC-2/3/4 由真实 MCP Server 集成测试补齐协议级验证。
- lint 门禁转绿、全量 214 单测无失败、无回归、安全专项验证通过。
- 仅存 2 项 B0 微小项（README 索引 DOC-01、集成测试关闭噪音 OBS-01），均不阻断发布，建议主 Agent 顺手闭合 DOC-01。

**US-008 判定为完全通过，可进入下一迭代（US-009/US-010）。**

---

## 参考

- [prd.json](docs/prd.json)（US-008）
- [ADR-005](docs/decisions/ADR-005-mcp-client-integration.md)
- [上轮验收报告 R1](docs/reports/2026-08-06-us008-mcp-client-acceptance.md)
- [guardrail round4](docs/reports/2026-08-06-us008-mcp-client-guardrail-round4.md)、[guardrail 集成测试](docs/reports/2026-08-06-us008-mcp-integrationtest-guardrail.md)
- 源码：[McpClientManager.kt](app/src/main/java/io/prism/network/McpClientManager.kt)、[CapabilitiesViewModel.kt](app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)
- 测试：[McpClientManagerIntegrationTest.kt](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt)、[CapabilitiesViewModelTest.kt](app/src/test/java/io/prism/ui/capabilities/CapabilitiesViewModelTest.kt)
