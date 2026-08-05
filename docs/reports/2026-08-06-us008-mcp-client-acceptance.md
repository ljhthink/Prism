# US-008 MCP Client 集成 — 验收测试报告

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-MCP-CLIENT-AC-001 |
| 验收日期 | 2026-08-06 |
| 验收范围 | 数据层（McpServerConfig/McpServerRepository/McpServerPresets）、连接层（McpClientManager/McpToolProvider）、UI 层（CapabilitiesViewModel/CapabilitiesScreen）、依赖升级（Kotlin 2.3.21/Ktor 3.3.3/serialization 1.11.0/mcp 0.12.0） |
| 前置条件 | guardrail 四轮全部通过（TKN-MCP-CLIENT-004 通过） |
| 关联文档 | ADR-005、docs/PRD.md、prd.json（US-008）、docs/reports/2026-08-06-us008-mcp-client-guardrail{,-round2,-round3,-round4}.md |
| 测试方法 | test-architect skill（PRD 驱动分层测试） |

---

## 一、总体结论

**有条件通过（Conditional Pass）** — 核心功能已实现、编译与全量单元测试通过、安全门禁通过、无回归；但存在 1 项 lint 门禁缺陷 + 2 项验收覆盖缺口，需要在闭合开发周期前处理。

| 维度 | 结论 | 说明 |
| --- | --- | --- |
| 功能验收标准 | 2/5 完全通过，3/5 部分通过（结构已实现，协议级未自动化验证） | AC-1/AC-5 通过；AC-2/3/4 结构实现但缺少真实 MCP 协议集成测试 |
| 单元测试 | 通过 | 200 用例，0 失败 0 错误；US-008 关联 28 用例（McpClientManager=19、McpServerRepository=9） |
| 编译验证 | 通过 | `compileDebugKotlin` BUILD SUCCESSFUL |
| lint 静态分析 | 失败 | `lintAnalyzeDebug` 因工具链元数据兼容崩溃（非业务缺陷） |
| 安全专项验证 | 通过 | 无硬编码密钥、错误信息不泄露 e.message、CRLF 纵深防御、baseUrl 白名单校验 |
| 回归测试 | 通过 | 全量单测套件 0 失败 |

---

## 二、验收标准覆盖矩阵

US-008 prd.json 验收标准（Ralph 开发任务级）与验证结果：

| AC ID | 验收标准 | 测试用例 ID | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-1 | 引入 MCP Kotlin SDK 0.12.0 依赖 | TC-01 | **通过** | [libs.versions.toml](../../gradle/libs.versions.toml#L18) `mcp = "0.12.0"`；[app/build.gradle.kts](../../app/build.gradle.kts#L89) `implementation(libs.mcp.kotlin.sdk.client)`；`compileDebugKotlin` BUILD SUCCESSFUL |
| AC-2 | Client + StreamableHttpClientTransport 可连接远程 MCP Server | TC-02 | **部分通过（结构实现，协议级未自动化验证）** | [McpClientManager.connect](../../app/src/main/java/io/prism/network/McpClientManager.kt#L103-L129) 使用 `StreamableHttpClientTransport(httpClient, baseUrl)`；【缺口 GAP-001】无真实 MCP 服务器集成测试 |
| AC-3 | client.listTools() 返回工具列表 | TC-03 | **部分通过（结构实现，协议级未自动化验证）** | [listTools](../../app/src/main/java/io/prism/network/McpClientManager.kt#L52-L64) 实现对工具名映射；单测仅覆盖 renderResult 纯函数，未覆盖 listTools 真实协议事务 |
| AC-4 | client.callTool(name, arguments) 可调用并返回结果 | TC-04 | **部分通过（结构实现，协议级未自动化验证）** | [callTool](../../app/src/main/java/io/prism/network/McpClientManager.kt#L74-L93) 实现调用 + renderResult 渲染；单测覆盖 renderResult 纯函数，未覆盖 callTool 真实协议事务 |
| AC-5 | Typecheck passes | TC-05 | **通过** | `:app:compileDebugKotlin --rerun-tasks` BUILD SUCCESSFUL；`build.gradle.kts` 已迁移 `kotlin { compilerOptions { jvmTarget } }` |

### 需人工澄清/无法自动验证的项

| 验收项 | 无法自动验证原因 | 建议 |
| --- | --- | --- |
| AC-2/3/4 真实 MCP 协议事务（initialize → listTools → callTool） | ADR-005 5.6 承诺的「嵌入式 Ktor Netty 真实 MCP Server 集成测试」**未在测试套件中实现**；`connect()` 为 private，`listTools()`/`callTool()` 未接真实或 mock MCP 服务器测试。单元测试仅覆盖 `resolveHeaders`/`renderResult`/`isValidBaseUrl` 纯函数 | 需补充 ADR-005 5.6 所述真实 MCP Server 集成测试，或在报告「未覆盖项」中接受该风险 |

---

## 三、测试用例设计（等价类/边界值/异常路径/极端场景）

### 3.1 数据层 — McpServerRepository / McpServerConfig

| TC ID | AC | 技术 | 输入/前置 | 动作 | 期望行为 | 执行结果 |
| --- | --- | --- | --- | --- | --- | --- |
| TC-11 | — | 等价类 | 新建 config | `save` | id>0，持久化 | 通过（`save_assigns_positive_id`） |
| TC-12 | — | 等价类 | Context7 全字段（apiKeyRef/headers） | `save`→`get` | 字段完整、headers 经 StringMapConverter 往返一致 | 通过（`get_returns_persisted_config`） |
| TC-13 | — | 等价类 | 更新已有 config | `save`(id>0) | 覆盖 baseUrl | 通过（`save_update_existing_config`） |
| TC-14 | — | 边界 | 多个 config createdAt 排序 | `getAll` | 按 createdAt 升序 | 通过（`getAll_returns_sorted_by_createdAt`） |
| TC-15 | — | 异常路径 | 已存在 id | `remove` | get 返回 null | 通过（`remove_deletes_config`） |
| TC-16 | — | 状态迁移 | 默认→启用→停用 | `setEnabled` | isEnabled 三态翻转 | 通过（`setEnabled_toggles_flag`） |
| TC-17 | — | 等价类 | GitHub 预设 | `createFromPreset` | 新 config 保留 name/serverType | 通过（`createFromPreset_creates_new_config`） |
| TC-18 | — | 边界 | 本地+远程模板 | `localPresets`/`remotePresets`/`all` | 6 本地 + 3 远程，all=local+remote | 通过（`presets_contain_local_and_remote`） |
| TC-19 | — | 异常路径 | 精确名/不存在名 | `findByName` | 命中/返回 null | 通过（`findByName_matches_exact_name`） |
| TC-20 | — | 边界 | servers StateFlow | CRUD 后订阅 | 写后 `refreshFlows()` 刷新 | 通过（代码审查 [refreshFlows](../../app/src/main/java/io/prism/data/McpServerRepository.kt#L123-L125)）+ CRUD 用例 |

### 3.2 连接层 — McpClientManager 纯函数

| TC ID | AC | 技术 | 输入 | 动作 | 期望行为 | 执行结果 |
| --- | --- | --- | --- | --- | --- | --- |
| TC-21 | AC-3 | 等价类 | 空 headers + 无 key | `resolveHeaders` | 返回空 map | 通过（`resolveHeaders_noKey_noCustom_returnsEmpty`） |
| TC-22 | AC-4 | 等价类 | 空 headers + key | `resolveHeaders` | 注入 `Bearer <key>` | 通过（`resolveHeaders_withKey_injectsBearerAuth`） |
| TC-23 | AC-4 | 边界 | 空白 key | `resolveHeaders` | 不注入 | 通过（`resolveHeaders_blankKey_doesNotInject`） |
| TC-24 | AC-4 | 等价类 | 自定义 Authorization 头 + key | `resolveHeaders` | 保留自定义，不覆盖 | 通过（`resolveHeaders_customAuthHeader_preservedNotOverridden`） |
| TC-25 | AC-4 | 大小写规范化 | 小写 `authorization` + key | `resolveHeaders` | 不重复注入 Bearer | 通过（`resolveHeaders_lowercaseAuthHeader_preservedNotOverridden`） |
| TC-26 | AC-4 | 等价类 | 自定义头 + key | `resolveHeaders` | 自定义头保留 + Bearer 注入 | 通过（`resolveHeaders_customHeaders_preserved`） |
| TC-27 | AC-4 | 异常(CRLF) | 值含 CRLF | `resolveHeaders` | 剔除注入头，保留合法头 | 通过（`resolveHeaders_crlfValues_stripped`） |
| TC-28 | AC-4 | 异常(CRLF) | 键含 CRLF | `resolveHeaders` | 剔除注入键 | 通过（`resolveHeaders_crlfKeys_stripped`） |
| TC-29 | AC-2 | 等价类 | 合法 https URL | `isValidBaseUrl` | true | 通过（`isValidBaseUrl_validHttps_returnsTrue`） |
| TC-30 | AC-2 | 等价类 | 合法 http URL | `isValidBaseUrl` | true | 通过（`isValidBaseUrl_validHttp_returnsTrue`） |
| TC-31 | AC-2 | 边界 | 空/空白 URL | `isValidBaseUrl` | false | 通过（`isValidBaseUrl_blank_returnsFalse`） |
| TC-32 | AC-2 | 边界 | 无 http(s) 前缀 | `isValidBaseUrl` | false | 通过（`isValidBaseUrl_missingScheme_returnsFalse`） |
| TC-33 | AC-2 | 异常(CRLF) | 中部 CRLF | `isValidBaseUrl` | false | 通过（`isValidBaseUrl_crlf_returnsFalse`） |
| TC-34 | AC-2 | 极端(尾部CRLF) | 尾部 `\r\n` | `isValidBaseUrl` | false（R3-1 回归点） | 通过（`isValidBaseUrl_trailingCrlf_returnsFalse`） |
| TC-35 | AC-2 | 边界 | 空白包围合法 URL | `isValidBaseUrl` | true | 通过（`isValidBaseUrl_whitespaceSurrounded_returnsTrue`） |
| TC-36 | AC-4 | 等价类 | 多 TextContent | `renderResult` | 换行连接 | 通过（`renderResult_textContents_joinedByNewline`） |
| TC-37 | AC-4 | 边界 | 空内容 | `renderResult` | 空串 | 通过（`renderResult_emptyContent_returnsEmpty`） |
| TC-38 | AC-4 | 边界 | 空白文本 | `renderResult` | 过滤后 join | 通过（`renderResult_blankText_filtered`） |
| TC-39 | AC-4 | 异常路径 | isError=true | `renderResult` | 前缀「工具执行出错：」 | 通过（`renderResult_isError_prefixed`） |

### 3.3 极端/边缘补充评估

| 场景 | 评估 | 结论 |
| --- | --- | --- |
| 空 baseUrl | `isValidBaseUrl` 拒绝；UI `canSave` 阻止保存；`McpRow` 禁用启用开关 | 已覆盖 |
| CRLF 注入（键/值/URL 尾部/中部） | `resolveHeaders` 剔除 + `isValidBaseUrl` trim 前校验 + UI 层校验，三层纵深 | 已覆盖 |
| 非法 URL（非 http(s)） | `isValidBaseUrl` 拒绝 + UI 校验 | 已覆盖 |
| 并发连接 | McpClientManager 每次 listTools/callTool 建临时 Client，finally close（[closeQuietly](../../app/src/main/java/io/prism/network/McpClientManager.kt#L154-L160)）；连接失败释放（guardrail S2） | 结构已实现，无并发压测 |
| 协程取消 | `CancellationException` 在 listTools/callTool/connect/testConnection 重新抛出（CR-01） | 代码审查确认，无 JVM 取消测试 |
| 资源泄漏 | connect 失败 close + 调用完成 finally close | 代码审查确认，无资源泄漏测试 |
| 多 Server 并存 | McpServerRepository 无单激活不变式，独立 isEnabled | 已覆盖 |

> 注：并发/取消/资源泄漏为运行时行为，当前 JVM 单测未覆盖，依赖代码路径审查 + guardrail 确认。建议后续补充异步/取消测试。

---

## 四、分层测试详情

### 4.1 静态分析

| 工具 | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| Android Lint | `:app:lintDebug` | **失败** | `lintAnalyzeDebug` 崩溃：`ComposableFlowOperatorDetector` 读取 Kotlin 2.3.21 元数据（v2.1.0）超出 lint 内置 kotlinx-metadata-jvm 最高支持 v2.0.0，分析 `McpServerRepository.kt` 时抛 `throwIfNotCompatible`。**属工具链兼容问题，非业务代码缺陷**。见缺陷 DEF-001 |

> 背景：这是 ADR-005 P3 风险「lint 检测器在 Kotlin 2.3 下需重新评估」的具体表现。项目已在 [build.gradle.kts](../../app/build.gradle.kts#L58-L66) 按此模式禁用 `CoroutineCreationDuringComposition` 与 `StateFlowValueCalledInComposition`，但第三个崩溃检测器 `FlowOperatorInvokedInComposition` 未纳入禁用列表。

### 4.2 单元测试

| 框架 | 用例数 | 通过 | 失败 | 错误 | 跳过 | 覆盖率估算 | 结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| JUnit 4 | 200 | 200 | 0 | 0 | 15（性能基准默认跳过） | 未配置 JaCoCo，无法给出语句/分支精确值 | 通过 |

US-008 关联用例明细：

| 测试类 | 用例数 | 通过 | 说明 |
| --- | --- | --- | --- |
| `io.prism.network.McpClientManagerTest` | 19 | 19 | resolveHeaders(8)/renderResult(4)/isValidBaseUrl(7) |
| `io.prism.data.McpServerRepositoryTest` | 9 | 9 | CRUD + headers 转换 + 启用切换 + createFromPreset + 预设 |

US-008 未覆盖的测试类：**无 `CapabilitiesViewModelTest`**（见缺口 GAP-002）。

证据：`app/build/test-results/testDebugUnitTest/*.xml` 全部 `failures="0" errors="0"`；`testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

### 4.3 集成测试评估

| 场景 | 结果 | 说明 |
| --- | --- | --- |
| McpServerRepository ↔ ObjectBox 持久化 | 通过 | McpServerRepositoryTest 用真实 ObjectBox BoxStore（temp dir）验证含 headers @Convert 转换 |
| McpClientManager ↔ MockEngine 构造 | 通过 | McpClientManagerTest 用 MockEngine 构造，验证纯函数 |
| Client + StreamableHttpClientTransport 真实握手 | **未实现** | 无真实 MCP Server 集成测试（GAP-001） |

### 4.4 端到端测试

本期 Android 原生 UI（Compose），无 E2E 自动化（Playwright 不适用）。CapabilitiesScreen 的 UI 交互依赖人工/设备级验证，未纳入本 JVM 验收。

---

## 五、安全专项验证

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 无硬编码密钥/令牌/内部地址 | **通过** | grep `(api[_-]?key|token|secret|password|sk-|Bearer)\s*=["'][^"']+["']` 于 `app/src/main/java/io/prism` 无匹配；`apiKeyRef` 仅存引用（如 "context7"），明文经 `ApiKeyRepository.readApiKeyOnce` 只读即用 |
| 错误信息不泄露 e.message | **通过** | [McpClientManager.callTool](../../app/src/main/java/io/prism/network/McpClientManager.kt#L86-L90) 返回通用文案「工具调用失败，请检查网络连接或 Server 配置」，不拼接 e.message；[CapabilitiesViewModel.testConnection](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt#L116-L120) 同；e.message 仅存在于注释（L87） |
| CRLF 纵深防御（CWE-113/93） | **通过** | `resolveHeaders` 剔除含 CR/LF 键值（[L177-190](../../app/src/main/java/io/prism/network/McpClientManager.kt#L177-L190)）；`isValidBaseUrl` 对原始值（trim 前）校验 CRLF（[L141-146](../../app/src/main/java/io/prism/network/McpClientManager.kt#L141-L146)）；UI 层 `validHeaders`/`urlSafe` 第一道防线 |
| baseUrl 校验（白名单） | **通过** | `isValidBaseUrl` 要求非空 + http(s) 前缀 + 无 CRLF；connect 用 trim 后局部变量统一校验与传输构造（R3-1） |
| SQL 注入面 | **通过** | 无 SQL 查询（ObjectBox ORM，非手写 SQL） |
| XSS 面 | **通过（不适用）** | 原生 Compose UI，非 WebView/HTML 渲染，无 XSS 注入面 |
| 明文 apiKey 不落盘 | **通过** | McpServerConfig 仅存 `apiKeyRef` 引用；明文经 Keystore 加密存储（ApiKeyRepository） |

---

## 六、回归测试结果

| 套件 | 总数 | 通过 | 失败 | 结果 |
| --- | --- | --- | --- | --- |
| 全量 `testDebugUnitTest` | 200 | 200 | 0 | **通过，无回归** |

US-001~007 既有套件（ProviderConfig/OpenAICompatibleProvider/ApiKey/Chat/Knowledge/Settings 等）全部通过，确认 Kotlin 2.3.21/Ktor 3.3.3/serialization 1.11.0 升级未破坏既有功能。`compileDebugKotlin` 仅出现既有 deprecation 警告（PrismGlassCard/Icons 等，非 US-008 引入）。

---

## 七、性能回退检查

| 项 | 结果 | 说明 |
| --- | --- | --- |
| US-008 MCP 函数性能基线 | **无基线** | `perf/baselines/` 与 `docs/reports/perf/` 无 US-008 相关基线文件 |
| 定性评估 | 无显著风险 | `resolveHeaders`（O(n) 遍历+过滤）、`renderResult`（mapNotNull+joinToString）、`isValidBaseUrl`（contains+trim）均为轻量纯函数，无性能敏感路径/网络热点 |
| 既有性能基准 | 通过 | `OpenAICompatibleProviderPerformanceBenchmark`/`ApiKeyPerformanceBenchmark` 等默认跳过（`ignorePerformanceTests` 门禁），未受 Kotlin 升级影响 |

---

## 八、缺陷与缺口清单

| ID | 严重度 | 类别 | 描述 | 复现/证据 | 修复建议 |
| --- | --- | --- | --- | --- | --- |
| DEF-001 | B1（一般，工具链） | lint 门禁 | `lintAnalyzeDebug` 崩溃：`ComposableFlowOperatorDetector` 读取 Kotlin 2.3.21 元数据（v2.1.0 > 最高支持 v2.0.0）抛 `throwIfNotCompatible`，分析 `McpServerRepository.kt` 时触发。非业务代码缺陷 | `:app:lintDebug` 输出「Provided Metadata instance has version 2.1.0, while maximum supported version is 2.0.0」+ 崩溃栈 | 在 `app/build.gradle.kts` 的 `lint { disable += ... }` 追加 `"FlowOperatorInvokedInComposition"`（与既有 `CoroutineCreationDuringComposition`/`StateFlowValueCalledInComposition` 禁用模式一致），并同步更新 L58-66 注释说明 |
| GAP-001 | B2（严重，覆盖缺口） | 验收覆盖 | AC-2/3/4 真实 MCP 协议事务（initialize→listTools→callTool）无自动化测试；`connect()` private、`listTools()`/`callTool()` 未接真实/mock MCP 服务器。ADR-005 5.6 承诺的「嵌入式 Ktor Netty 真实 MCP Server 集成测试」未实现 | 测试套件 grep 无 MCP 集成测试；`app/src/androidTest` 不存在 | 按 ADR-005 5.6 实现嵌入式 Ktor Netty 真实 MCP Streamable HTTP 端点集成测试，覆盖 listTools/callTool 成功与失败路径；或在主 Agent 确认后接受该风险并记录 |
| GAP-002 | B1（一般，覆盖缺口） | 验收覆盖 | `CapabilitiesViewModel`（saveServer/createFromPreset/newCustomServer/setEnabled/deleteServer/testConnection）无 JVM 单元测试 | 测试目录无 `CapabilitiesViewModelTest.kt` | 补充 ViewModel 单元测试（复用 fake McpToolProvider/McpServerRepository 或抽象仓库），覆盖状态流与操作分支 |

> 严重度说明：DEF-001 不影响运行时功能，但阻断 lint CI 门禁；GAP-001 使 3/5 验收标准无法协议级自动验证；GAP-002 使 UI 层状态机无自动回归保护。三者均非 US-008 运行时功能缺陷。

---

## 九、未覆盖项与风险

| 项 | 原因 | 风险 |
| --- | --- | --- |
| 真实 MCP 协议握手 | 无嵌入式 MCP Server 集成测试 | 连接/调用失败路径（超时、协议错误、鉴权失败）未自动验证，依赖真实远端 Server 人工验证 |
| CapabilitiesViewModel 状态机 | 无 JVM 测试 + Android ViewModel 依赖 `APPLICATION_KEY` | 增删改/启用/测试连接的状态迁移无自动回归保护 |
| 并发/协程取消/资源泄漏 | JVM 单测未覆盖运行时行为 | 依赖代码审查 + guardrail 确认，无并发压测证据 |
| Compose UI 交互（CapabilitiesScreen） | 原生 Android，无 E2E 工具 | 弹层/表单/动态列表交互依赖人工验证 |
| 精确覆盖率（语句/分支） | 项目未配置 JaCoCo | 无法量化 ≥90%/≥80% 门禁达成度；以用例设计与代码审查作为替代证据 |

---

## 十、结论与放行建议

**验收结论：有条件通过（Conditional Pass）。**

- **通过项**：AC-1/AC-5 完全通过；全量 200 单测无失败、无回归；安全专项验证全部通过；编译验证通过。
- **需处理项（闭合前）**：
  1. DEF-001：补 `"FlowOperatorInvokedInComposition"` 至 lint 禁用列表，恢复 lint 门禁绿色。
  2. GAP-001：实现 ADR-005 5.6 真实 MCP Server 集成测试，或由主 Agent 评估后接受风险并记录。
  3. GAP-002：补充 `CapabilitiesViewModelTest`。

处理上述 2 项覆盖缺口 + 1 项 lint 门禁后，US-008 可判定为完全通过。若主 Agent 判定 GAP-001 真实协议测试超出本期范围，须在 prd.json US-008 notes 中明确记录该风险并置 passes 判定依据。

---

## 参考

- [ADR-005](docs/decisions/ADR-005-mcp-client-integration.md)
- [prd.json](docs/prd.json)（US-008）
- [docs/PRD.md](docs/PRD.md)（US-002 MCP 产品级验收全貌）
- [guardrail 四轮报告](docs/reports/2026-08-06-us008-mcp-client-guardrail-round4.md)
- 源码：[McpClientManager.kt](app/src/main/java/io/prism/network/McpClientManager.kt)、[McpServerRepository.kt](app/src/main/java/io/prism/data/McpServerRepository.kt)、[CapabilitiesViewModel.kt](app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)、[CapabilitiesScreen.kt](app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt)
- 测试：[McpClientManagerTest.kt](app/src/test/java/io/prism/network/McpClientManagerTest.kt)、[McpServerRepositoryTest.kt](app/src/test/java/io/prism/data/McpServerRepositoryTest.kt)