# US-008 MCP Client 集成 — guardrail 第四轮复审报告

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-MCP-CLIENT-004 |
| 审查日期 | 2026-08-06 |
| 审查范围 | `app/src/main/java/io/prism/network/McpClientManager.kt`<br>`app/src/test/java/io/prism/network/McpClientManagerTest.kt` |
| 本轮焦点 | R3-1 修复有效性与新增测试充分性 |
| 前置结论 | 第一轮 S1/M1-CRLF/M2/L1、第二轮 S2/S3/M1-残(R3-1 发现)、第三轮 LOW(R3-1) 均已处理 |

---

## 一、总体结论

**通过** — R3-1 修复有效，无残留、无新引入问题，新增测试充分。主 Agent 可据此启动 `ac-verifier`。

| 维度 | 结论 | 说明 |
| --- | --- | --- |
| 阻断级 | 0 | 无 |
| 高危 | 0 | 无 |
| 中危 | 0 | 无 |
| 低危/建议 | 0 | 无 |

---

## 二、审查范围与核验

### 2.1 R3-1 修复点 1：CRLF 检查移至 trim 前

`isValidBaseUrl`（`McpClientManager.kt` 141-146 行）：

```kotlin
internal fun isValidBaseUrl(baseUrl: String): Boolean {
    val hasCrlf = baseUrl.contains('\r') || baseUrl.contains('\n')   // 作用于原始值（trim 前）
    val trimmed = baseUrl.trim()
    val hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    return trimmed.isNotEmpty() && hasScheme && !hasCrlf
}
```

- `hasCrlf` 在 `trim()` 之前对原始 `baseUrl` 计算，尾部 `\r\n` 可被 `contains` 捕获，`!hasCrlf` 为 `false` → 返回 `false`。
- **结论：已修复**。尾部 CRLF 校验绕过向量（第三轮 R3-1）已消除。

### 2.2 R3-1 修复点 2：connect() 统一 trim 值

`connect()`（`McpClientManager.kt` 107-119 行）：

```kotlin
val baseUrl = config.baseUrl.trim()
require(isValidBaseUrl(baseUrl)) { "非法 MCP Server baseUrl" }
...
val transport = StreamableHttpClientTransport(httpClient, baseUrl) { ... }
```

- 校验入参与传输构造均使用同一 `baseUrl`（trim 后局部变量），彻底消除此前「校验用 trim 值、传输用原始值」的比对不一致。
- **结论：已修复**。

### 2.3 上下文安全推演（无残留向量）

| 输入形态 | connect() 路径 | isValidBaseUrl 独立调用 | 是否安全 |
| --- | --- | --- | --- |
| 尾部含 CRLF（`...\r\n`） | trim 剥离尾部 → 传输值无 CRLF → 安全 | 原始值 hasCrlf=true → 拒绝 | 安全 |
| 中部含 CRLF（`...\r\nX-Evil`） | trim 无法剥离中部 → hasCrlf=true → 拒绝 | hasCrlf=true → 拒绝 | 安全 |
| 空白包围合法 URL | trim 后合法 → 通过 | trim 后合法 → 通过 | 安全 |

两路径（connect 主动 trim 消除尾部风险 + isValidBaseUrl 对原始 CRLF 严格拒绝）互为纵深，无绕过。

### 2.4 其余安全面复查（无回归）

- `resolveHeaders`（177-190 行）仍正确剔除含 CR/LF 的键值（CWE-113/CWE-93），Authorization 大小写规范化与覆盖逻辑未变。
- 无 SQL/OS 命令/代码/模板注入面。
- 无硬编码密钥；API Key 仍经 `readApiKeyOnce` 明文只读即用路径。
- 连接失败 `closeQuietly` 资源释放（S2）逻辑未受本次改动影响。

---

## 三、新增测试充分性核验

`McpClientManagerTest.kt`：

| 用例 | 位置 | 断言 | 覆盖点 | 有效性 |
| --- | --- | --- | --- | --- |
| `isValidBaseUrl_trailingCrlf_returnsFalse` | 134-137 | `"https://mcp.context7.com\r\n"` → false | 尾部 CRLF 不被 trim 剥离绕过 | 有效，直接覆盖 R3-1 核心回归点 |
| `isValidBaseUrl_whitespaceSurrounded_returnsTrue` | 140-143 | `"  https://mcp.context7.com  "` → true | trim 后合法 URL 通过，与 CRLF 拒绝路径区分 | 有效 |

- 两用例与既有 `crlf_returnsFalse`（中部 CRLF）、`blank_returnsFalse`、`missingScheme_returnsFalse`、`validHttps/validHttp` 互补，形成完整边界矩阵。
- 均使用 `setUp()` 中同一 `manager` 实例（MockEngine handler 已配置，构造不抛异常），可独立运行。
- **结论：测试充分，足以防回归。**

---

## 四、结论与放行

- R3-1 双修复点均已有效落实，无残留、无新引入问题。
- 新增测试准确覆盖两处边界场景。
- 无阻断级、高危、中危及低危发现。

**guardrail 第四轮结论：通过。**

主 Agent 可据此启动 `ac-verifier`，基于 PRD 验收标准执行分层验证与安全专项测试。

---

## 五、自动化建议（供 CI 集成参考）

建议在 CI 中补充针对该类 HTTP 首部注入的静态规则，防止 R3-1 类问题回归：

```yaml
# .github/workflows/security.yml 片段（Semgrep 规则示例）
- id: kotlin-crlf-url-validation
  patterns:
    - pattern: |
        $URL.contains('\r') || $URL.contains('\n')
  message: baseUrl 的 CRLF 检查必须作用于 trim 前原始值
  languages: [kotlin]
  severity: WARNING
```

同时建议将 `validIsBaseUrl` 的 CRLF-于-trim-前契约写入 `docs/behavioral-rules.md` 的 `security` 类别，作为跨会话自检规则（BR-security-*）。