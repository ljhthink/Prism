# 安全与质量审计报告：US-008 MCP Client 真实 MCP Server 集成测试

> 由 guardrail-enforcer 子 Agent 生成。依 CLAUDE.md 第十节。范围：新增集成测试代码 + 测试依赖变更。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-MCP-CLIENT-GUARDRAIL-005 |
| 审计日期 | 2026-08-06 |
| 关联 ADR | [ADR-005](../decisions/ADR-005-mcp-client-integration.md)（5.6 / GAP-001） |
| 关联代码变更 | `app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt`（新增）、`app/build.gradle.kts`（修改）、`gradle/libs.versions.toml`（修改） |
| 审计结论 | **通过**（含非阻断的完善建议） |

---

## 1. 代码质量审查（TRAE-code-review）

### 1.1 变更意图推断

本次变更旨在为 ADR-005 5.6 承诺的「真实 MCP Server 集成测试」（GAP-001）落地：用嵌入式 Ktor Netty 服务器通过 MCP Kotlin SDK 的 `mcpStreamableHttp` 托管真实 Streamable HTTP MCP Server，再用生产连接层 `McpClientManager` 端到端验证 `listTools` / `callTool`，补齐纯函数单测无法覆盖的 MCP 协议握手事务路径。

### 1.2 审查发现

| No. | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| Q1 | 中 | [McpClientManagerIntegrationTest.kt:105](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L105) 及 L116、L131 | **`startMcpServer()` 位于 try 块之外**。若 `server.start(wait=false)` 成功但随后的 `resolvedConnectors().first().port` 取端口抛异常，则已启动的 server 已加入 `teardowns`，但因未进入 try，`finally { stopServers() }` 不会执行，导致 server 泄漏。 | 将 `startMcpServer()` 移入 try 块内，或让 `startMcpServer()` 自身在失败路径自清理（内部 try/catch 兜底 stop）。 |
| Q2 | 低 | [McpClientManagerIntegrationTest.kt:47](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L47) | **测试 HttpClient 与生产配置存在保真度偏差**：生产（[PrismApplication.kt:46-52](app/src/main/java/io/prism/PrismApplication.kt#L46-L52)）为 `HttpClient(OkHttp) { expectSuccess = true; install(SSE) }`，而既有 OpenAI 集成测试范式（[OpenAICompatibleProviderTest.kt:59-61](app/src/test/java/io/prism/network/OpenAICompatibleProviderTest.kt#L59-L61)）也显式设置 `expectSuccess = true`。本测试仅 `install(SSE)`，省略 `expectSuccess`。 | 为对齐生产行为与项目既有测试约定，补上 `expectSuccess = true`。当前覆盖路径均为 200 成功或协议级 isError（HTTP 200），故不影响现有断言，属保真度完善项。 |
| Q3 | 低 | [McpClientManagerIntegrationTest.kt:98-101](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L98-L101) | `stopServers()` 对每个 teardown 未做异常隔离：若某个 `server.stop()` 抛异常，后续 teardown 不会执行、`teardowns.clear()` 也不会执行，可能掩盖原始断言或遗留清理。 | 循环内逐个 try/catch 包裹，保证全部 server 停止并最终 `clear()`；与生产 `closeQuietly` 的容错哲学一致。 |
| Q4 | 低 | [McpClientManagerIntegrationTest.kt:47](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L47) | 类级 `httpClient` 从未 `close()`。JUnit4 每个测试方法新建实例，故每个测试都会创建并泄漏一个 OkHttp 客户端（线程池/连接池）。测试 JVM 退出后由 OS 回收，实际影响极小。 | 可加 `@After` 统一 `httpClient.close()`，或复用 `@BeforeClass` 级客户端。仅当追求严格资源卫生时需处理。 |
| Q5 | 低 | [McpClientManagerIntegrationTest.kt:58](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L58) | `enableDnsRebindingProtection = false` 无解释注释。测试绑定 127.0.0.1 回环、无域名，禁用 DNS 重绑定防护是安全且必要的，但缺乏说明易被误解。 | 添加简短注释说明「bind 回环 IP，无域名可重绑定，故关闭」。 |

### 1.3 优点确认

- **命名**：测试方法用反引号描述性长名，意图清晰（Karpathy 符合）。
- **资源清理主路径可靠**：每个起 server 的测试均有 `try/finally { stopServers() }`。
- **测试独立性良好**：JUnit4 每方法新建实例（`teardowns`/`httpClient`/`manager` 均独立）；`port = 0` 取随机端口，无端口冲突。
- **参数安全**：`request.params.arguments?.get("text")` 用 `as? JsonPrimitive` 安全转换 + `?.content ?: ""` 兜底，无强转崩溃风险。
- **超时保护**：所有网络调用包 `withTimeout(10.seconds)`，避免测试挂死。

---

## 2. 安全漏洞扫描（TRAE-security-review）

> 说明：本次变更整体为测试代码 + testImplementation 作用域依赖，按 TRAE-security-review §8.1「confined to test code」排除项，无报告级（HIGH/MEDIUM）可利用安全漏洞。以下是对任务要求的专项核查结论。

### 2.1 输入与边界审计

- 测试构造的 baseUrl 均为固定字面量（`"http://127.0.0.1:$port/mcp"`、`"not-a-url"`、`"  "`），无外部可控输入。
- 工具调用参数 `mapOf("text" to "hello")` 为测试常量，无注入面。
- **结论**：无输入边界问题。

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

- 无 SQL / 命令 / 代码执行路径。
- 嵌入服务器仅绑定 127.0.0.1 回环随机端口，最小暴露面。
- 未知工具调用经 `callTool` 降级为通用文案，且断言 `!result.contains("require")`（[L155](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L155)）验证不泄露 `IllegalArgumentException` 内部细节（CWE-209 非泄露语义）。
- **结论**：无注入 / 特权 / 编码问题。

### 2.3 密钥与配置安全

- 测试使用 `ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService())`，**无任何真实密钥/令牌/API Key**。
- 无生产端点、无内部域名/路径泄露；仅回环地址。
- **结论**：无敏感信息泄露。

### 2.4 依赖与供应链风险

- [app/build.gradle.kts:100](app/build.gradle.kts#L100)：`testImplementation(libs.mcp.kotlin.sdk.server)` —— **正确限定 `testImplementation` 作用域**，不会进入生产 APK。
- [gradle/libs.versions.toml:48](gradle/libs.versions.toml#L48)：`mcp-kotlin-sdk-server` 复用与已审计客户端相同的 `mcp = "0.12.0"` 版本，同发布方（`io.modelcontextprotocol`）同版本，供应链风险低。
- 建议：CI 中 `npm audit` 类工具对本项目不适用（非 npm 栈）；可建议主 Agent 视需要在 Gradle 侧引入依赖漏洞扫描，但非本次阻断项。

---

## 3. OWASP / CWE 发现

| 编号 | 等级 | 位置 | 修复建议 |
|---|---|---|---|
| CWE-209（异常信息泄露） | 低（已验证防护） | [McpClientManagerIntegrationTest.kt:155](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L155) | 已通过 `!result.contains("require")` 断言验证降级文案不含内部异常细节，防护有效，无需修复。 |
| CWE-404（资源释放） | 低（边缘场景） | [McpClientManagerIntegrationTest.kt:105](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L105) | 见 Q1：`startMcpServer()` 置于 try 外，极端失败路径可能泄漏 server；建议移入 try。 |

---

## 4. 重点核验结论

### 4.1 GAP-001：真实 MCP 协议握手覆盖（✓ 通过）

- [listTools test:104-112](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L104-L112)：对真实嵌入式服务器发起 `initialize → tools/list`，断言返回注册的 `["echo", "ping"]`。
- [callTool test:115-125](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L115-L125)：对真实服务器发起 `initialize → tools/call`，断言返回 `"echo:hello"`。
- [unknown tool test:128-140](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L128-L140)：服务器对未知工具返回协议级 `CallToolResult(isError=true)`，断言命中 `renderResult` 的「工具执行出错」前缀 —— **该断言同时验证了「服务器返回 isError 而非抛异常」这一行为契约**，测试具有真实判别力。
- 三项测试均通过（前置验证 5/5），证明 ADR-005 5.6 / GAP-001 承诺的「真实握手事务路径」已实质覆盖。

### 4.2 服务器资源清理（✓ 主路径可靠 / ⚠️ 边缘场景见 Q1、Q3）

- 正常路径 `finally { stopServers() }` 可靠释放；`teardowns` 在 `stopServers()` 末尾 `clear()`。
- 边缘场景：`startMcpServer()` 在 try 外（Q1）、`stopServers()` 未逐项容错（Q3），均为低概率、低影响，建议但不阻断。

### 4.3 与生产 `McpClientManager` 行为契约一致性（✓ 通过）

| 场景 | 测试断言 | 对应生产契约 | 一致 |
|---|---|---|---|
| listTools 非法 baseUrl | 空列表 | `catch → emptyList()` | ✓ |
| callTool 空白 baseUrl | `"工具调用失败"` 前缀 | `catch → "工具调用失败，请检查网络连接或 Server 配置"` | ✓ |
| callTool 未知工具 | `"工具执行出错"` 前缀 | `renderResult(isError=true)` 前置标记 | ✓ |
| 异常细节非泄露 | `!contains("require")` | `catch` 内不暴露 `e.message`（CR-05） | ✓ |

### 4.4 依赖作用域（✓ 通过）

- `mcp-kotlin-sdk-server` 仅 `testImplementation`，未污染生产 `implementation` 依赖（生产仅 `mcp-kotlin-sdk-client`，[app/build.gradle.kts:90](app/build.gradle.kts#L90)）。确认无生产 APK 污染。

### 4.5 生产客户端保真度（⚠️ SSE 对齐 / expectSuccess 略）

- SSE 插件已对齐（生产 [L51](app/src/main/java/io/prism/PrismApplication.kt#L51) 与测试 [L47](app/src/test/java/io/prism/network/McpClientManagerIntegrationTest.kt#L47) 均安装）。
- `expectSuccess` 未对齐（见 Q2），因覆盖路径均为 200/协议级 isError，不影响断言真实性，属保真度完善项。

### 4.6 主 Agent 自问盲区回应

1. **对 `mcpStreamableHttp` 协议细节不确定（端点 /mcp、SSE、工具错误语义）**：本集成测试自身即承担了该不确定性的实证验证——`config()` 拼 `/mcp` 路径且测试通过，说明端点语义正确；未知工具测试验证了 isError 语义。**盲区已被测试所覆盖**。
2. **「鉴权头注入 / 自定义 headers / 服务器不可达」未覆盖**：鉴权头合并与自定义头逻辑已由纯函数单测 `resolveHeaders`（[McpClientManagerTest.kt:46-103](app/src/test/java/io/prism/network/McpClientManagerTest.kt#L46-L103)）覆盖，含 CRLF 注入剔除、大小写规范化、Authorization 不覆盖等；「服务器不可达」连接拒绝路径在既有 OpenAI 集成测试范式（[OpenAICompatibleProviderTest.kt:454-466](app/src/test/java/io/prism/network/OpenAICompatibleProviderTest.kt#L454-L466)）有先例，但 MCP 连接层集成层未覆盖。**属已知覆盖缺口，非本次阻断项**，建议后续在集成层补充连接拒绝用例。
3. **测试 HttpClient 与生产不一致风险**：SSE 插件一致，但 `expectSuccess` 偏差（Q2），已如实记录。

---

## 5. 结论

- [x] **通过**（可进入测试阶段）
- [ ] 阻断（存在严重缺陷或高危漏洞，回退编码阶段）

**判定依据**：无阻断级或高危问题；GAP-001 真实握手覆盖、契约一致性、资源清理主路径、依赖作用域均验证通过；无真实凭据、无敏感泄露、无注入面。Q1-Q5 及 4.6 中的覆盖缺口均为非阻断的完善建议，可按需在后续迭代处理，不触发回退闭环。

---

## 6. 规则提议（accepted review → behavioral-rules）

以下为本次审查中可转正的规则提议，供主 Agent 审批后追加至 `docs/behavioral-rules.md`：

| ID（建议） | 类别 | 规则 | 反例 | 正例 |
|---|---|---|---|---|
| BR-testing-00X | testing | 集成测试中任何「先启动资源再断言」的辅助函数必须置于 try 块内，或辅助函数自身对失败路径自清理，确保资源获取失败时不留泄漏 | `val port = startMcpServer()` 位于 `try` 之外 | `val port = try { startMcpServer() } finally { ... }` 或 `startMcpServer()` 内部 `try/catch` 兜底 stop |
| BR-testing-00Y | testing | 真实服务器集成测试的 HttpClient 配置必须与生产 `PrismApplication.httpClient` 逐项对齐（含 `expectSuccess`），避免测试环境与生产行为漂移 | 测试仅 `install(SSE)` 而生产含 `expectSuccess = true` | 测试显式 `HttpClient(OkHttp) { expectSuccess = true; install(SSE) }` |

---

## 7. 自动化建议（CI/CD 集成）

- 将本集成测试纳入 CI 测试门禁（`gradle :app:testDebugUnitTest`），因其启动真实服务器，建议标记为稳定用例并纳入每次 PR 必跑。
- 可考虑为 `gradle/libs.versions.toml` 引入依赖漏洞扫描（如 Gradle 侧 `dependency-check` 或对比 SCA 工具），对 `io.modelcontextprotocol:kotlin-sdk-server` 及其传递依赖做 CVE 监控。
- 对 Q1/Q3 资源清理加固后，可加一条静态检查规则（如 Detekt/Ktlint）禁止「资源启动调用位于 try 之外」模式。
