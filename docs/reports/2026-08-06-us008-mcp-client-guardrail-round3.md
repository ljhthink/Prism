# 安全与质量审计报告（US-008 MCP Client 集成 · 第三轮复审）

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-MCP-CLIENT-003 |
| 轮次 | 第三轮（复审第二轮"有条件通过"要求的 S2/S3/M1-残 修复） |
| 审计日期 | 2026-08-06 |
| 关联 ADR | [ADR-005](../decisions/ADR-005-mcp-client-integration.md) |
| 上轮报告 | [2026-08-06-us008-mcp-client-guardrail-round2.md](2026-08-06-us008-mcp-client-guardrail-round2.md) |
| 审查依据 | TRAE-code-review + TRAE-security-review full passes |

## 0. 审查范围与证据

- 复审文件：`app/src/main/java/io/prism/network/McpClientManager.kt` / `app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt`
- 新增测试：`app/src/test/java/io/prism/network/McpClientManagerTest.kt`（isValidBaseUrl 5 条用例）
- 契约：`McpToolProvider.kt`（listTools/callTool 降级契约）
- 数据：`McpServerConfig.kt`
- git 状态：四个目标文件均为未跟踪新文件（US-008 集成尚未提交），权威变更内容即完整源码，已逐行读取。
- 编译诊断：三个文件 GetDiagnostics 均为空，无语法/类型/lint 错误。

**本轮核验结论**：S2 / S3 / M1-残 三项修复均已正确落地且无残留；在新增的 `isValidBaseUrl` 中发现 1 处 LOW 纵深防御缺陷（尾部 CRLF 因 `trim()` 在 CRLF 检查前被移除而绕过校验，且校验用 trim 值、连接用未 trim 值不一致）。详见第 3 节。

---

## 1. 代码质量审查（TRAE-code-review）

### 意图推断
本轮为修复驱动的第三轮复审。作者意图是为前两轮确认的三处缺陷落地修复：S2 连接失败资源释放、S3 testConnection 去信息泄露 + 结构化并发、M1-残 baseUrl 连接层白名单校验。架构未变，纯函数抽取延续 ADR-005 5.4 可测性模式。

### 变更概览（mermaid）
```mermaid
flowchart LR
    CLIENT[McpClientManager.connect] -->|S2: try/catch close+rethrow| CONN[client.connect(transport)]
    CLIENT -->|M1-残: require isValidBaseUrl| BC[baseUrl 白名单]
    VM[CapabilitiesViewModel.testConnection] -->|S3: CancellationException 重抛| CANCEL
    VM -->|S3: 通用文案| FAIL[TestState.Fail 通用]
    subgraph 新增纯函数
        BC -->|内部| VAL[isValidBaseUrl: trim+scheme+CRLF]
    end
    style CONN fill:#c8e6c9,color:#1a5e20
    style CANCEL fill:#c8e6c9,color:#1a5e20
    style FAIL fill:#c8e6c9,color:#1a5e20
    style VAL fill:#fff3e0,color:#e65100
```

### Karpathy Guidelines 符合性
- 命名 / 设计 / 可维护性：`isValidBaseUrl` / `closeQuietly` 命名清晰，注释准确说明纵深防御意图。✔
- 错误处理：S2 的 `connect()` 内 try/catch 释放后重抛，符合"资源构建与使用分离时构建失败路径必须显式释放"规则；调用方 finally 兜底。✔
- 交叉核验：`isValidBaseUrl` 与 `resolveHeaders` 的 CRLF 过滤形成 baseUrl/头值的双层 CRLF 纵深防御，方向一致。✔
- **一致性缺陷（LOW）**：`isValidBaseUrl` 先 `trim()` 再检查 CRLF，而 `connect()` 实际用未 trim 的 `config.baseUrl` 构造传输 —— 校验目标与使用目标不一致，详见第 3 节 R3-1。

---

## 2. 安全漏洞扫描（TRAE-security-review）：上轮修复核验

### 2.1 S2（CWE-404/772 连接生命周期泄漏）—— 已修复 ✔
`McpClientManager.kt:120-126`：`client.connect(transport)` 用 try/catch 包裹，失败时 `closeQuietly(client)` 后 `throw e` 重抛。

**泄漏路径核验（全路径穷举）**：
| 失败点 | 是否在 client 创建前 | 处理 | 结果 |
|---|---|---|---|
| `require(isValidBaseUrl)` L107 | 是 | 直接抛，无 client | 无泄漏 ✔ |
| `readApiKeyOnce` L111 | 是 | 直接抛，无 client | 无泄漏 ✔ |
| `StreamableHttpClientTransport` 构造 L116 | 是 | 直接抛，无 client | 无泄漏 ✔ |
| `client.connect(transport)` L121 | 否 | catch 内 closeQuietly(client) + throw | 无泄漏 ✔ |

**双重释放核验**：Kotlin 赋值语义 —— `client = connect(config)`（listTools L55 / callTool L81）的 RHS 抛异常时赋值不执行，`client` 保持 `null`；调用方 finally `closeQuietly(null)` 为 no-op。故失败路径仅由 `connect()` 内部释放一次，成功路径由调用方 finally 释放一次，**无泄漏、无双重释放**。`closeQuietly` 内部 try/catch 亦兜底。✔

### 2.2 S3（CWE-248/209 testConnection）—— 已修复 ✔
`CapabilitiesViewModel.kt:107-123`：
- `catch (e: CancellationException) { throw e }`（L113-115）优先于 `catch (e: Exception)`，保持结构化并发 CR-01。✔
- Fail 分支 `TestState.Fail("连接失败，请检查网络或 Server 配置")`（L120），不再拼接 `e.message`，对齐 CR-05。✔
- `import kotlinx.coroutines.CancellationException`（L14）已补充。✔

**防御分支评估（主 Agent 自问 2）**：`catch (e: Exception)` 在 `McpClientManager.listTools` 已对业务异常降级返回（返回空列表）的前提下，仅对"未来 provider 替换或意外异常"触发，属合法纵深防御。L118 注释明确标注"此处为第二道防线"，**非误导、非冗余**。该分支吞掉意外异常换取 UI 不崩溃，符合降级契约，可接受。

### 2.3 M1-残（baseUrl 连接层白名单，CWE-113 纵深防御）—— 主体已修复，发现 1 处 LOW 边缘缺陷
新增 `internal fun isValidBaseUrl`（L138-142）并在 `connect()` 入口 `require(isValidBaseUrl(config.baseUrl))`（L107）校验。主体设计正确：非空 + http(s) 前缀 + 无 CRLF。**但存在 trim 顺序缺陷，详见第 3 节 R3-1。**

### 2.4 密钥与配置 / 依赖供应链 —— 无新问题
- 无硬编码密钥；测试用 `"sk-abc"` 为测试桩值，非真实凭证。✔
- 无新增依赖。✔

---

## 3. OWASP / CWE 发现（本轮新发现）

| 编号 | 等级 | 位置 | CWE | 说明 |
|---|---|---|---|---|
| R3-1 | LOW | `app/src/main/java/io/prism/network/McpClientManager.kt:138-142`（isValidBaseUrl） | CWE-113（纵深防御） | `isValidBaseUrl` 先 `trim()` 再检查 CRLF。`trim()` 会移除首尾空白字符（含 `\r`/`\n`），导致**仅含尾部 CRLF 的 baseUrl**（如 `"https://good.com\r\n"`）经 trim 后 CRLF 被剥离、校验通过；而 `connect()` 实际使用未 trim 的 `config.baseUrl` 构造传输（L116），尾部 CRLF 仍会进入传输层。校验目标（trim 后）与使用目标（未 trim）不一致 |

**R3-1 可利用性评估**：该缺陷仅影响"尾部 CRLF"这一子集；中部 CRLF（如 `"https://good.com\r\nX-Evil: 1"`）不受 trim 影响仍被正确拦截（现有测试 `isValidBaseUrl_crlf_returnsFalse` 已固化）。且 Ktor URL 解析对控制字符会编码/抛异常（第二轮已论证），UI 层 `urlSafe` 亦为第一道防线，故实际可利用性低。**非阻断级**，但作为本轮新增防御的核心逻辑，存在校验旁路 + trim 不一致，建议修复。

---

## 4. 主 Agent 自问两题评估

### 4.1 `require(isValidBaseUrl)` 在 listTools/callTool 的降级是否安全、`throw e` 后资源是否一定释放？
**确认安全。** 已穷举全部失败点（见 2.1 表）：`require` 失败在 client 创建前抛出，无泄漏；`client.connect` 失败由 `connect()` 内部释放后重抛。调用方因赋值 RHS 抛异常时 `client` 保持 null，finally 为 no-op。**成功路径与失败路径均恰好释放一次，无泄漏、无双重释放。**

### 4.2 S3 的 Fail 分支作为"第二道防线"是否引入误导/冗余、是否影响测试覆盖？
**不引入误导/冗余。** `catch (e: Exception)` 是合法的防御性纵深编程，注释（L118）明确标注"第二道防线"，与项目"零侥幸"原则一致。**测试覆盖提示**：该防御分支无直接单测（ViewModel 协程测试成本高），属可接受缺口；但建议在 `McpClientManagerTest` 中补充 `isValidBaseUrl` 的尾部 CRLF 与空白包围 URL 用例（见第 5 节），以覆盖 R3-1 与边界。

---

## 5. 修复建议

### R3-1（LOW，建议修复）
`isValidBaseUrl` 的 CRLF 检查应作用于**原始值**（trim 前），而非 trim 后；或使连接层使用与校验一致的 trim 值。

建议（示意，非补丁）：
- 对原始 `baseUrl` 先做 `!baseUrl.contains('\r') && !baseUrl.contains('\n')` 检查（trim 前），再对 `trimmed` 做非空 + 前缀检查；
- 或 `connect()` 中统一使用 `config.baseUrl.trim()` 构造传输，保证校验与使用一致。

### R3-1 补充测试建议
在 `McpClientManagerTest` 新增：
- 负向：`isValidBaseUrl("https://good.com\r\n")` 应拒绝（若按"trim 前检查 CRLF"语义）；
- 正向：`isValidBaseUrl("  https://good.com/mcp  ")`（空白包围合法 URL）边界行为。

---

## 6. 结论

- [ ] 通过（可进入测试阶段）
- [x] **有条件通过**（S2/S3/M1-残 三项要求修复均已正确落地且无残留；本轮新增 1 处 LOW 纵深防御缺陷 R3-1，建议修复后复审通过）

**判定依据**：未发现阻断级漏洞（无注入、无硬编码密钥、无权限绕过）。第二轮要求的 S2（连接泄漏）、S3（CancellationException 误捕 + e.message 泄露）、M1-残（baseUrl 白名单）均已正确实现，且经全路径穷举确认 S2 无泄漏/无双重释放、S3 符合 CR-01/CR-05。唯一新增项为 `isValidBaseUrl` 的尾部 CRLF trim 旁路（LOW，CWE-113 纵深防御）。

**修复前置条件（回退至编码阶段处理）**：
1. **R3-1（LOW）**：`isValidBaseUrl` 的 CRLF 检查改为作用于原始值（trim 前），保证校验与 `connect()` 实际使用的 URL 一致；建议补充尾部 CRLF 负向用例与空白包围 URL 正向用例。

修复完成后按第七节闭环重新提交 guardrail-enforcer 复审；通过后启动 ac-verifier。该 LOW 项不构成阻断，但按"零侥幸"原则建议在本周期内修复。

---

## 7. 规则提议（accepted review → behavioral-rules）

| 类别 | 规则 | 反例（本次） | 正例 | 来源 |
|---|---|---|---|---|
| security | 输入校验若含 trim/规范化，安全敏感检查（如 CRLF/注入字符）必须作用于**规范化前**的原始值，且校验目标与实际使用值必须一致 | `isValidBaseUrl` 先 trim 再查 CRLF，导致尾部 CRLF 旁路；连接用未 trim 值 | 先在原始值上拒绝 CRLF，再对 trim 值做前缀/非空检查；连接层使用与校验一致的 trim 值 | R3-1 |

---

## 8. 自动化建议（CI/CD 集成）

- 复用前两轮建议：在 `.github/workflows/` 增加 `security.yml`，对 `app/src/**/*.kt` 运行 **Semgrep** 规则集（`cwe-113-crlf-injection`、`cwe-209-information-exposure`、`cwe-248-uncatchedexception`），并补充检测"先调用 trim/strip/lowercase 后做敏感子串检查"的模式（R3-1 类）。
- 将 S2 的 `connect()` 失败释放、S3 的 CancellationException 重抛、R3-1 的 trim 前 CRLF 检查封装为正例用例纳入单测回归，防复发。