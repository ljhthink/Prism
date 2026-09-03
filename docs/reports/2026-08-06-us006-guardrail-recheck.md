# 安全与质量审计报告（复审）—— US-006 OpenAI 兼容 Provider 流式请求（CR-02 残留修复闭环）

> 由 `guardrail-enforcer` 子 Agent 生成。依 CLAUDE.md 第十节。本报告引用的代码位置使用相对路径（ADR-010）。
> 本报告是对 `2026-08-05-us006-guardrail.md`（TKN-NETWORK-US006-001，**有条件通过**）所列唯一必须修复项「发现 1（CR-02 残留）」修复后的复审。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-NETWORK-US006-002 |
| 审计日期 | 2026-08-06 |
| 关联 ADR | ADR-004-prism-provider-streaming |
| 关联代码变更 | US-006 CR-02 残留修复复审（ConversationViewModel / ConversationViewModelTest / OpenAICompatibleProvider / OpenAICompatibleProviderTest / ChatStreamProvider / StreamEvent / PrismApplication / Gradle 测试依赖） |
| 风险等级 | P2 跨模块（按 P3 深度执行） |

## 0. 上下文重建摘要（CLAUDE.md 零节）

- 项目阶段：US-001~US-005 已验收；US-006 主实现经上一轮 guardrail（TKN-NETWORK-US006-001）判定**有条件通过**，唯一必须修复项为「发现 1（CR-02 残留）」。主 Agent 已修复并新增多轮回归用例，提交本轮复审。
- 本次任务：仅审核 CR-02 残留修复是否彻底闭合、多轮用例是否有效，并复查 CR-01~CR-05 是否仍保持修复状态，确认上轮强建议项不阻断本轮进入验收。
- 证据获取：通读全部变更文件、测试报告 XML、构建输出；运行 `:app:testDebugUnitTest`（过滤两个测试类）实测通过。

---

## 1. CR-02 残留修复核验（本轮核心）

### 1.1 修复对比

上一轮残留（`ConversationViewModel.kt:[83]`，已修复）：

```kotlin
// 仅排除当前占位 —— 历史遗留空 AI 消息未排除
val history = _messages.value.filterNot { it.id == aiId }
```

本轮修复（`app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt:[85]`，现态）：

```kotlin
val history = _messages.value.filterNot { it.role == Role.ASSISTANT && it.content.isEmpty() }
```

### 1.2 修复正确性判定

| 判定维度 | 结论 | 依据 |
|---|---|---|
| 是否闭合并发路径 | ✅ | `content.isEmpty()` 同时覆盖「本次刚追加的空占位」（占位在过滤点始终为 `""`）与「历史遗留的空 AI 消息」（上一轮仅 `[DONE]` 结束残留），与上一轮建议的修复表达式逐字一致 |
| 是否引入副作用 | ✅ | 过滤仅作用于请求历史组装，不触碰 `_messages` 展示态；空 assistant 消息不入请求体符合 CR-02 原始目标（避免严格网关 400） |
| 是否有并发残留 | ✅ | 正在流式、已累积内容的 AI 消息 content 非空，不会被误过滤，正确保留在历史中；该行为与修复前一致，无回归 |
| 占位时序 | ✅ | 占位于 `ConversationViewModel.kt:[74]` 追加且 content 为 `""`，`appendDelta`（`[103,107]`）仅在 collect 中发生，故过滤点（`[85]`）占位必为空，过滤必然命中 |

### 1.3 多轮回归用例有效性

新增用例 `request history excludes stale empty ai message from previous zero-delta round`（`app/src/test/java/io/prism/ui/chat/ConversationViewModelTest.kt:[196,223]`）通过 `MultiRoundRecordingProvider`（`[243,252]`）模拟：

- 第一轮 `sendMessage("first")` → 服务端仅发射 `[Done]`（零增量），AI 消息残留为 `""`。
- 第二轮 `sendMessage("second")` → 断言 `receivedMessages[1]` 无任何空 content 消息、size 恰为 2、内容依次为 `first`/`second`。

该用例精确复现 guardrail 发现 1 的触发路径（turn1 零增量结束 → turn2 历史），断言逐项覆盖「无空消息」「仅用户消息」「顺序正确」。**有效形成回归防线**。

---

## 2. CR-01~CR-05 修复保持性核验（逐项）

| 编号 | 判定 | 现态证据 | 说明 |
|---|---|---|---|
| CR-01 | ✅ 保持 | `OpenAICompatibleProvider.kt:[87,89]` | `catch (e: CancellationException) { throw e }` 置于 `catch (Exception)` 之前；测试 `streamChat rethrows cancellation instead of emitting error`（Test:[222,280]）实测通过（OpenAICompatibleProviderTest 13 用例 0 失败） |
| CR-02 | ✅ 已闭合 | `ConversationViewModel.kt:[85]` | 发现 1 已按上轮建议修复，见 §1 |
| CR-03 | ✅ 保持 | `OpenAICompatibleProvider.kt:[148]` | `choices.isEmpty() && usage == null` 才判 Done；单测 `parseChunkData ignores mid-stream usage snapshot not terminate`（Test:[133,139]）通过 |
| CR-04 | ✅ 保持 | `OpenAICompatibleProviderTest.kt:[195,216]` | 真实 401 集成用例通过 |
| CR-05 | ✅ 保持 | `OpenAICompatibleProvider.kt:[92]` | 统一通用文案，无 `e.message`，无内部细节泄露 |

---

## 3. 会话取消 / 历史过滤 / 测试稳定性专项复查

### 3.1 会话取消时 SSE 连接释放

- 代码路径正确：`catch(CancellationException) throw e` 让结构化并发接管清理，Ktor SSE 插件在 `incoming.collect` 取消时关闭 SSE 会话与底层 OkHttp call。
- 取消回归测试（Test:[222,280]）用 `serverStop` 显式信号规避 `server.stop()` 挂起的非确定性，并断言 `cancelPropagated=true`、`emittedError=false`，实测通过。
- **生产级连接释放的端到端观测仍依赖真机 PoC**（上轮已转交 ac-verifier，非代码缺陷，不构成本轮阻断项）。

### 3.2 请求历史过滤逻辑

- 过滤为纯函数式 `filterNot`，无副作用、无并发写；`_messages.value` 为不可变 List（StateFlow）。
- 过滤仅作用于请求组装，展示态 `_messages` 不受影响，空占位消息仍保留在 UI 供用户看到。

### 3.3 测试稳定性

- 实测 `:app:testDebugUnitTest`（过滤两测试类）**BUILD SUCCESSFUL**：OpenAICompatibleProviderTest 13 用例 0 失败 0 错误；ConversationViewModelTest 9 用例（含新增）0 失败 0 错误。
- 新增多轮用例实测执行（XML 显示 `time="0.021"` 无 failure 标记）。
- 轻微稳健性提示（非阻断）：`MultiRoundRecordingProvider` 用 `call.coerceAtMost(eventSequences.size - 1)` 在调用超限时静默复用末序列而非失败，若未来用例序列数误配可能掩盖遗漏；当前两轮调用与序列数严格一致，无实际影响。

---

## 4. 安全漏洞扫描（TRAE-security-review）

本次 diff 仅变更一处过滤逻辑与新增测试，**未引入新的安全攻击面**：

- 无 SQL / 命令 / 模板 / 表达式注入；请求体仍由 kotlinx.serialization 编译时序列化（`OpenAICompatibleProvider.kt:[109,117]`），无 JSON 字符串拼接。
- 无 `eval` / `exec`、无集合越界、无原始 buffer（JVM 托管内存）。
- 未发现硬编码密钥 / token / 密码 / 内部 IP；API Key 仅经 `Authorization: Bearer` 头发送，不进入 URL；测试 `sk-123`/`openai` 为 fixture，非真实凭证。
- CR-05 保持：错误文案无内部路径 / 异常细节外泄。
- Gradle 新增 `ktor-server-core/netty/sse` 仅 `testImplementation`（build.gradle.kts:[89,91]），不进入运行时 APK；Ktor 锁定 3.1.3（libs.versions.toml:[15]）。

**安全结论：未发现 HIGH / MEDIUM 级可利用漏洞。**

---

## 5. 上轮强建议项（本轮不要求修复）确认

| 编号 | 内容 | 等级 | 是否阻断本轮 |
|---|---|---|---|
| 发现 3 | 错误文案区分 401 鉴权与网络错误 | 低 | 否（功能/诊断价值，非漏洞） |
| 发现 2（CR-06） | `applyCustomHeaders` 头名大小写敏感 | 低 | 否（用户自配置，非受信网络输入） |
| 发现 4 | apiKeyRef 为空时自定义 Authorization 头被丢弃 | 低 | 否（仅非常规配置触发） |

三项均为 LOW 级功能/健壮性项，非安全漏洞，**不阻断本轮进入验收测试**。建议在后续排期修复，不列入本轮门槛。

---

## 6. 主 Agent 盲区专项核验

| 盲区问询 | 结论 | 依据 |
|---|---|---|
| 过滤泛化后是否误伤「正在流式、已有内容」的 AI 消息 | 无误伤 | 过滤条件 `content.isEmpty()`，累积非空内容不命中；并发发送场景行为与修复前一致 |
| 新增多轮用例是否真正覆盖「零增量结束残留」 | 已覆盖 | turn1 仅 `[Done]` → AI 消息留空；turn2 断言历史无空消息（Test:[196,223]） |
| CR-01 取消路径在修复过滤后是否仍不受影响 | 不受影响 | 过滤在 `streamChat` 调用前（`ConversationViewModel.kt:[85,86]`），不触碰 `OpenAICompatibleProvider` 取消逻辑 |

---

## 7. 结论

- [x] **通过**（可进入验收测试阶段 ac-verifier）

### 判定依据

1. CR-02 残留（上轮唯一必须修复项）已彻底闭合：过滤泛化为排除所有空 content 的 assistant 消息，与上轮建议表达式一致，同时覆盖当前占位与历史遗留空消息。
2. 新增多轮回归用例有效形成防线，实测通过。
3. CR-01 / CR-03 / CR-04 / CR-05 保持修复状态，相关测试全部通过（13 + 9 用例 0 失败 0 错误）。
4. 未发现新的必须修复项；上轮三项强建议（发现 2/3/4）均 LOW 非漏洞，不阻断本轮。

### 仍转交 ac-verifier 的验证项（非代码缺陷，沿用上轮）

- **真机 SSE 连接在生产取消场景下正确释放**（主 Agent 盲区 #1）：客户端取消后连接关闭、`server.stop()` 不挂起；建议真机 PoC 观测首字延迟并建性能基线。
- **ObjectBox teardown 警告**：确认属既有问题，另立 task，不阻塞本 US。
- **AndroidManifest INTERNET 权限 + network_security_config localhost 明文白名单**落地确认（ADR-004 §4.6，不在本次 diff）。
- **空 choices 带 usage 中段快照后仍有后续 Delta 的端到端行为**（parseChunkData 已单测，集成层补验）。

---

## 8. 规则提议（accepted review → behavioral-rules）

上轮已提 `BR-interface-003`（请求历史过滤必须排除所有空 content 的 assistant 消息）并已被本轮修复采纳，建议标记为 **active 且已落实**。本轮无新增规则提议。

---

## 9. 自动化建议（CI/CD）

- 将「请求历史过滤空占位」纳入单元门禁：请求体负载不得含空 content 的 assistant 消息（对应 BR-interface-003，防回归）。
- 保留 Semgrep/Ruleguard 规则：`catch (e: Exception)` 后无 `CancellationException` 显式 rethrow 即告警（对应 CR-01）。
- 在 CI 单元测试工作流增加依赖漏洞扫描（`dependencyCheckAnalyze`）为必需状态检查。
