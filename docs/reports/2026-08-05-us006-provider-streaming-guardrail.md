# 安全与质量审计报告 —— US-006 OpenAI 兼容 Provider 流式请求

> 由 `guardrail-enforcer` 子 Agent 生成。依 CLAUDE.md 第十节。本报告引用的代码位置使用相对路径（ADR-010）。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US006-GUARDRAIL-002 |
| 审计日期 | 2026-08-05 |
| 关联 ADR | ADR-004-prism-provider-streaming |
| 关联代码变更 | US-006（ChatStreamProvider / OpenAICompatibleProvider / StreamEvent / ConversationViewModel / PrismApplication / 测试 / Gradle 测试依赖） |
| 风险等级 | P2 跨模块（评审时按 P3 深度执行） |

## 0. 重读与上下文重建摘要（CLAUDE.md 零节）

- 项目阶段：US-001~US-005 已验收，US-006 为流式请求实现，跨网络层与 ui.chat 层。
- 本次任务：对 US-006 变更执行代码质量审查 + 安全审计，输出综合报告。
- 评审范围：主 Agent 提供的 8 处变更 + `AndroidManifest.xml` / `res/xml/network_security_config.xml`（ADR-004 §4.6 要求项，已核对落地）。
- 证据获取：通读全部变更文件、数据模型（ProviderConfig / ChatMessage / ApiKeyRepository）、Gradle 依赖、ADR-004、`behavioral-rules.md` 既有规则。

---

## 1. 代码质量审查（TRAE-code-review）

### 1.1 作者意图

将 `ConversationViewModel` 的 Mock 占位回复替换为真实 OpenAI 兼容 SSE 流式请求：

1. 抽离 `internal` 纯函数（buildEndpoint / buildAuthHeader / buildRequestBody / applyCustomHeaders / parseChunkData）以规避 MockEngine 不支持 `SSECapability` 的测试障碍（ADR-004 §4.7）；
2. 用 `StreamEvent` 密封类 + `Flow` 表达增量流，依赖倒置注入 `ChatStreamProvider` 接口；
3. 通过 `flowOn(Dispatchers.IO)` 将网络 IO 移出主线程。

### 1.2 关键发现（含主 Agent 盲区专项核验）

#### CR-01（高 · 必修复）`catch (Exception)` 吞掉 CancellationException —— 主 Agent 最担心盲区，确认存在

`app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt:[85,88]`

```kotlin
} catch (e: Exception) {
    emit(StreamEvent.Error(e.message ?: "网络请求失败"))
    return@flow
}
```

`CancellationException` 继承自 `IllegalStateException` → `RuntimeException` → `Exception`，故 `catch (Exception)` 会捕获协程取消。**确认这是主 Agent 担心的真实缺陷模式。**

运行时影响说明（中性化评估）：在 UI 取消 / ViewModel 清空场景下，flow 生产协程被取消后，catch 块内的 `emit(Error(...))` 会因 `ensureActive()` 再次抛出 `CancellationException` 并向上传播，因此**多数情况下不会真正向 UI 发射虚假 Error**。但：

- 该行为依赖「取消后 emit 必再抛」这一隐式约定，属脆弱反模式，违反结构化并发取消语义；
- 在部分边缘时序（取消与新异常竞态、`flowOn` Channel 缓冲期间的取消传播间隙）下行为不可预测；
- 与 CLAUDE.md「零容忍侥幸」标准不符，且这是主 Agent 明确标识的最大不确定性，必须显式处理。

修复要求：显式 `catch (e: CancellationException) { throw e }` 置于 `catch (Exception)` 之前，保证协程取消不被吞并、不被转化为 Error 事件。

#### CR-02（高 · 必修复）请求体携带空 content 的 assistant 占位消息 —— 协议兼容风险

`app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt:[73,83]`

```kotlin
val aiId = nextId++
_messages.value += ChatMessage(aiId, Role.ASSISTANT, "", now)  // 空占位
...
provider.streamChat(active, _messages.value)  // 含空 assistant 消息
```

`_messages.value` 在调用 `streamChat` 时已包含刚追加的空 assistant 占位消息，序列化后请求体为 `{"role":"assistant","content":""}`。该占位消息仅用于 UI 打字态展示，不应进入请求体。Anthropic 兼容 API 及部分严格 OpenAI 网关会**拒绝空 content 消息**，导致 400。修复要求：请求体应仅含用户消息 + 既有 AI 消息，排除新追加的空占位（例如在追加占位前快照消息列表，或构造请求时过滤空 content）。

#### CR-03（中）`parseChunkData` 将空 `choices[]` 一律判定为 Done —— 可能提前终止流

`app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt:[130,143]`

```kotlin
if (chunk.choices.isEmpty()) return StreamEvent.Done
```

部分 Provider 在流中段会发射 `{"choices":[],"usage":{...}}` 用量快照（非终止），将其视作 Done 会提前终止流。OpenAI 兼容格式的规范终止信号是 `[DONE]` 标记（已单独处理）。建议：空 `choices[]` 仅在作为「流结束兜底」保留，但应在收到 `[DONE]` 前不将普通 usage 快照误判为结束；或至少为「空 choices 结束」增加注释说明其为兼容性兜底而非规范信号。

#### CR-04（中）测试 `streamChat emits error on unauthorized` 名不符实，未覆盖 401

`app/src/test/java/io/prism/network/OpenAICompatibleProviderTest.kt:[176,194]`

该测试起了一个 Netty 服务器后立即 `server.stop()` 且从未使用，随后请求 `http://127.0.0.1:1`（不可达端口），实际验证的是「连接拒绝 → Error」，而非测试名宣称的「未授权 401」。ADR-004 §4.7 声称覆盖「401 鉴权失败」，但本变更集中**没有真正的 401 验证**。测试名误导 + 死代码服务器。建议移除未用服务器，并新增返回 401 状态的真实 SSE/HTTP handler 用例。

#### CR-05（低）原始异常 message 直出 UI —— 信息泄露面

`app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt:[86]` + `ConversationViewModel.kt:[90]`

`StreamEvent.Error(e.message)` 经 `appendDelta` 拼接进聊天正文。原始网络异常可能携带内部端点、超时细节等，向最终用户展示属信息泄露。建议映射为安全、通用的用户可读错误文案（如「无法连接 Provider，请检查网络与端点」），完整异常详情仅入日志（且日志需脱敏，见 §2.3）。

#### CR-06（低）`applyCustomHeaders` 头名比较大小写敏感

`app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt:[114,120]`

`k != HttpHeaders.Authorization`（字面量 "Authorization"）为大小写敏感比较。用户配置写入小写 `authorization` 时不会命中跳过逻辑，`builder.header` 会追加重复鉴权头。属用户自配置、非受信网络输入，影响有限；建议统一规范化头名后比较。

### 1.3 主 Agent 盲区专项核验结论（已确认**非问题**项）

| 盲区问询 | 结论 | 依据 |
|---|---|---|
| `flow{}` 内 `emit` 在 `flowOn(Dispatchers.IO)` 下的线程安全 | **非问题**。flow 内 emit 串行且限定于 flow 协程上下文；VM 收集端运行于 `viewModelScope`（Main），`appendDelta` 改 `_messages.value` 在主线程，StateFlow 本身线程安全 | OpenAICompatibleProvider.kt:[60,91]；ConversationViewModel.kt:[71,94] |
| 是否还有其他依赖 encode-defaults 序列化字段 | **非问题**。修复正确：`ChatCompletionRequest(model, messages, stream)` 三字段均无默认值，必序列化；解码侧 `ChatCompletionChunk/Choice/Delta` 的默认值仅用于解析，安全 | OpenAICompatibleProvider.kt:[154,174] |
| 请求体是否遗漏 temperature/max_tokens 致协议不兼容 | **非问题**。二者为 OpenAI API 可选参数，缺省由服务端取默认值；`ProviderConfig` 亦无对应字段，省略属合法请求 | ProviderConfig.kt:[33,44] |

### 1.4 跨模块影响识别

- 新增 `ChatStreamProvider` 接口 + `ConversationViewModel` 构造签名变更：ConversationScreen 经 Factory 接线正确，PrismApplication 已装配 `httpClient(OkHttp + SSE)` + `openAICompatibleProvider`，未发现遗漏调用点。
- 依赖变更：`ktor-server-core/netty/sse` 仅 `testImplementation`，不进入运行时 APK。
- `AndroidManifest.xml` 已含 `INTERNET` 权限；`network_security_config.xml` 仅对 localhost/127.0.0.1 放行明文，符合 ADR-004 §4.6 最小明文面。

### 1.5 测试充分性

- `OpenAICompatibleProviderTest`（11 用例）覆盖端点/鉴权头/请求体/自定义头/SSE 解析/DONE/空 choices/未知字段/端到端流式/错误路径，纯函数 + 真实 Netty SSE 服务器双轨，隔离策略合理。
- `ConversationViewModelTest`（7 用例）注入 fake 覆盖追加/trim/空白忽略/id 递增/无激活提示/activeProvider/错误提示。
- **缺口**：无 401 鉴权失败真实用例（见 CR-04）；无协程取消（CancellationException）路径用例（CR-01 无回归防线）；无「流中段异常后 Error + isTyping 复位」用例；无「空 content 占位消息不入请求体」断言（CR-02 无回归防线）。
- 全量 151 单测通过、编译通过，与主 Agent 描述一致。

---

## 2. 安全漏洞扫描（TRAE-security-review）

### 2.1 输入与边界审计

- 外部输入：`ProviderConfig.baseUrl / headers / models`（用户配置）与 `_messages.value`（会话内容）。均非攻击者可控网络输入，无 SQL / 命令 / 表达式拼接，无 `eval`、无 `exec`。
- 边界：`parseChunkData` 对非 JSON / 坏 chunk 返回 null 而非崩溃，符合「不崩溃」验收；`applyCustomHeaders` 为任意 user-config 头，无 CRLF 注入的现实攻击面（来源为用户自有配置）。
- 无集合越界、无原始 buffer 操作（JVM 托管内存，不适用 C/C++ 内存安全项）。

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

- **注入**：无 SQL / OS 命令 / 模板 / 表达式注入面。请求体由 kotlinx.serialization 编译时序列化，无 JSON 字符串拼接（安全）。
- **最小权限**：`INTERNET` + `ACCESS_NETWORK_STATE` 为流式请求最小必要权限；网络安全配置仅放行 localhost 明文，未全局 `usesCleartextTraffic=true`，符合最小明文面（ADR-004 §4.6 已核对落地）。
- **输出编码**：原生 Android Compose 渲染，无 HTML/JS 上下文注入面；`StreamEvent.Delta` 为纯文本拼接，无 XSS 风险。

### 2.3 密钥与配置安全

- API Key 仅经 `Authorization: Bearer` 头发送，**不进入 URL**，故不会随端点/异常 message 泄漏；`ApiKeyRepository` 明文仅在内存短驻，DataStore 仅存密文，无日志输出（与 BR-security-002 一致）。
- **未发现硬编码密钥 / token / 密码 / 内部 IP** 于源码。
- 复核 `.gitignore`：已排除 `.env` / `.env.local` / keystore / 运行时产物，符合 CLAUDE.md §20。
- 低风险项：CR-05 原始异常 message 直出 UI，潜在内部端点信息泄露（§1.2 CR-05）。

### 2.4 依赖与供应链风险

- 变更集仅新增 `testImplementation` 的 `ktor-server-core/netty/sse`（Ktor 3.1.3），不进入运行时 APK，攻击面有限。
- 需主 Agent 执行依赖漏洞扫描确认（非阻断）：`./gradlew :app:dependencyCheckAnalyze` 或等效 `npm/pip` 类审计；Ktor 3.1.3 为锁定版本，符合 ADR-001/004 版本硬约束。

### 2.5 安全结论

未发现 HIGH 级可利用安全漏洞。安全相关项仅 LOW（异常信息泄露 CR-05、自定义头大小写 CR-06）。本报告阻断判定由 §1 质量/正确性缺陷（CR-01/CR-02）驱动，而非安全漏洞。

---

## 3. OWASP / CWE 发现

| 编号 | 等级 | 位置 | 修复建议 |
|---|---|---|---|
| OWASP-A9 / CWE-209 | LOW | `OpenAICompatibleProvider.kt:[86]`、`ConversationViewModel.kt:[90]` | 将原始异常映射为通用安全文案，详情脱敏入日志，不外泄内部端点/堆栈 |
| CWE-754（类） | HIGH（非安全，正确性） | `OpenAICompatibleProvider.kt:[85,88]` | 显式 rethrow `CancellationException`，避免吞掉协程取消（同 CR-01） |
| CWE-670 / 协议正确性 | Medium | `ConversationViewModel.kt:[73,83]` | 排除空 content 占位消息（同 CR-02） |

> 注：CR-01/CR-02/CR-03 归类为正确性/协议兼容缺陷，非漏洞；按 guardrail 质量门禁处理为阻断项。

---

## 4. 结论

- [ ] 通过（可进入测试阶段）
- [x] **阻断（存在必须修复的正确性缺陷，回退编码阶段）**

### 4.1 阻断项（必须修复后重新提交本护栏审查）

1. **CR-01（高）**：`OpenAICompatibleProvider.streamChat` 的 `catch (Exception)` 吞掉 `CancellationException`。修复：显式 `catch (e: CancellationException) { throw e }`。
2. **CR-02（高）**：`ConversationViewModel.sendMessage` 将空 content 的 assistant 占位消息写入请求体，存在协议兼容风险（Anthropic 等拒绝空消息）。修复：请求体排除新追加的空占位。

### 4.2 强建议（本轮或下轮修复）

1. **CR-03（中）**：空 `choices[]` 判定为 Done 的提前终止风险，补充兼容性说明或细化判定。
2. **CR-04（中）**：补齐真正的 401 鉴权失败用例，移除名不符实的不可达端口测试的未用服务器。
3. **CR-05（低）**：异常 message 映射为通用安全文案。

### 4.3 修复后回归防线（ac-verifier 阶段必须补充）

- CancellationException 取消路径用例（收集端取消 → 不发射 Error、流静默终止）。
- 空 content 占位消息不入请求体的断言。

---

## 5. 规则提议（accepted review → behavioral-rules）

以下规则由本次审查接受项提炼，建议追加 `docs/behavioral-rules.md`（error-handling / protocol 类）：

- **BR-error-handling-002（强建议）**：`catch (Exception)` 逐级捕获时，必须先 `catch (e: CancellationException) { throw e }`，禁止用 `Exception` 吞并协程取消。
  - 反例：`catch (e: Exception) { emit(Error(...)) }` —— 吞掉取消，可能把取消误报为错误。
  - 正例：`catch (e: CancellationException) { throw e } catch (e: Exception) { emit(Error(...)) }`。
  - 来源：US-006 流式请求审查（TKN-US006-GUARDRAIL-002，CR-01）。
- **BR-interface-002（强建议）**：UI 层追加的空占位消息不得进入请求负载；请求体必须基于发送时刻的真实消息快照。
  - 反例：追加占位后再取 `_messages.value` 发请求，把空 assistant 消息序列化进 body。
  - 正例：在追加占位前快照消息列表传参，或构造请求时过滤空 content。
  - 来源：US-006 流式请求审查（TKN-US006-GUARDRAIL-002，CR-02）。

---

## 6. 自动化建议（CI/CD）

- 将「SSE 端到端 + 取消路径」纳入 `ac-verifier` 回归门禁。
- 在 CI 增加依赖漏洞扫描步骤（`dependencyCheckAnalyze`），并纳入 `docs.yml`/单元测试工作流必需状态检查。
- 建议在管道中引入 Semgrep/Ruleguard 规则：`catch (e: Exception)` 后无 `CancellationException` 显式 rethrow 即告警（对应 CR-01）。
