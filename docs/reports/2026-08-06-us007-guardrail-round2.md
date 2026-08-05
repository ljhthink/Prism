# 安全与质量审计报告（US-007 Provider 切换 · 一轮复审）

> 由 guardrail-enforcer 子 Agent 生成。依 CLAUDE.md 第十节。本报告为对一轮报告（TKN-US007-GUARDRAIL-001，结论「有条件通过」）所附修复项的复审。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US007-GUARDRAIL-002 |
| 审计日期 | 2026-08-06 |
| 关联 ADR | ADR-002 / ADR-003 / ADR-004 |
| 关联代码变更 | US-007 Provider 切换（SettingsScreen.kt / SettingsViewModel.kt 修复复审） |
| 风险等级 | P2（跨模块） |
| 复审范围 | 一轮 Q2（MEDIUM）、Q4/Q5/S1（LOW）修复验证 + Q3 处置评估 + 单激活不变式复核 |

## 0. 上下文重建摘要

- 本轮复审对象为一轮报告（`docs/reports/2026-08-06-us007-guardrail.md`）所列「需修复 MEDIUM（Q2）」及其随带修复的 LOW 项（Q4/Q5/S1）。
- 主 Agent 已声明修复并运行 `testDebugUnitTest`（49 用例 0 失败），本护栏独立复核构建产物：`compileDebugKotlin` 与 `testDebugUnitTest` 均 UP-TO-DATE，`BUILD SUCCESSFUL in 3s`，**无编译错误**。
- 主 Agent 自问盲区核实：本轮重点核实「自定义 headers 编辑器快照语义」与「单激活不变式未被绕过」。

## 1. 修复项逐项验证

### Q2（MEDIUM）—— 自定义 headers 编辑器快照语义

**原问题**：`mutableStateOf(MutableList)` 且原地改值 `headers[index] = it to v` 不触发 Compose 重组合，与删除/新增（重建列表）行为不一致，存在重组合时丢输入/回退风险。

**修复核对**（`app/src/main/java/io/prism/ui/settings/SettingsScreen.kt`）：

| 项 | 位置 | 状态 |
|---|---|---|
| import `androidx.compose.runtime.mutableStateListOf` | L24 | ✅ 已添加 |
| `val headers = remember(config.id) { mutableStateListOf<Pair<String,String>>().apply { addAll(config.headers.entries.map { it.key to it.value }) } }` | L280-284 | ✅ 声明正确，`remember(config.id)` 保证随所选 Provider 重建 |
| 原地改值 `headers[index] = it to v`（key 输入） | L349 | ✅ 现触发 SnapshotStateList 快照写入 → 重组 |
| 原地改值 `headers[index] = k to it`（value 输入） | L356 | ✅ 同上 |
| 删除 `headers.removeAt(index)` | L369 | ✅ 结构变更触发重组 |
| 新增 `headers.add("" to "")` | L381 | ✅ 结构变更触发重组 |
| 读取 `headers.map { ... }`（组成 `validHeaders`） | L303-305 | ✅ 快照读取注册依赖，headers 变化即重算 |

**并发安全复核**：`forEachIndexed`（L340）迭代期间应无结构性写入。经核查，`removeAt`/`add` 均位于点击回调（L369/L381），`headers[index]=` 位于文本输入回调（L349/L356），均在事件处理阶段（迭代结束后）执行，**不在 forEach 迭代同步路径内**，无 `ConcurrentModificationException` 风险。快照 set 语义正确。

**结论**：Q2 修复**正确**，原地改值与删除/新增语义已统一，丢输入/回退风险消除。

### Q4（LOW）—— apiKeyRef 唯一化

**修复核对**（`app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt`）：

| 项 | 位置 | 状态 |
|---|---|---|
| `import java.util.UUID` | L20 | ✅ 已添加 |
| `apiKeyRef = "custom-${UUID.randomUUID()}"` | L82 | ✅ 已由时间戳改为 UUID，碰撞概率降至可忽略 |

**结论**：Q4 修复**正确**。

### Q5 / S1（LOW）—— 纵深防御校验

**修复核对**（`app/src/main/java/io/prism/ui/settings/SettingsScreen.kt`）：

| 项 | 位置 | 状态 |
|---|---|---|
| `urlSafe = urlValid && !baseUrlTrimmed.contains('\r') && !baseUrlTrimmed.contains('\n')` | L301 | ✅ 拒绝含 CRLF 的 baseUrl |
| `canSave = nameValid && urlValid && urlSafe` | L302 | ✅ 已纳入 urlSafe（`urlValid && urlSafe` 冗余但无害） |
| `validHeaders`：key/value 均 `trim()`，过滤空 key，拒绝含 `\r`/`\n` 的 key/value | L303-305 | ✅ 保存点显式拒绝控制字符，满足纵深防御意图 |
| 保存时 `headers = validHeaders.toMap()` | L402 | ✅ 仅持久化合法 header，注入载荷不会到达引擎 |

**说明**：含 CRLF 的 header 被**静默过滤**（不阻塞保存、不持久化），而非使保存失败。此实现满足「保存点拒绝控制字符」的纵深防御目标——注入载荷无法到达 Ktor/OkHttp。静默丢弃属 UX 提示缺失（LOW 级建议），非安全缺陷，可接受。

**结论**：Q5/S1 修复**正确**。

## 2. 单激活不变式复核（BR-concurrency-001）

`ProviderEditSheet` 保存路径（L397-408）：

- `config.copy(..., isActive = false)` —— **保存时强制关闭激活**，绝不直写真实 `isActive`（L403）；
- 依 `enabled` 走 `viewModel.setActive(savedId)`（L407）—— 激活统一经仓库 `setActive`（`runInTx` 事务）处理；
- 独立「激活」按钮（L420）同样经 `viewModel.setActive(config.id)`。

**无绕过路径**，单激活不变式保持。✅

## 3. Q3 处置评估（LOW，未修改）

- 现状：`OpenAICompatibleProvider.kt` 兜底 `catch (e: Exception)`（L105-108）仍 emit 通用 `StreamEvent.Error("网络请求失败…")`，**非静默吞异常**（UI 有明确反馈），仅缺结构化日志以辅助排障。
- 主 Agent 决策：项目无日志基建，为避免过度工程未引入日志库，拟在行为规则中记录。
- 护栏评估：**可接受**。该问题为 LOW 级可维护性/可诊断性缺口，非安全漏洞；引入整套日志框架的改动面大于收益。通用错误文案不泄露内部路径/密钥（CR-05 满足）。
- **待办（非阻塞）**：CLAUDE.md §23.4 要求任务启动前主 Agent 必读相关类别规则、`guardrail-enforcer` 审查时检查是否违反已有规则。本轮复审确认：一轮报告 §5 提议的 **BR-ui-001**（可变列表原地改值）、**BR-error-handling-004**（兜底 catch 结构化日志）、**BR-security-001（CRLF 编辑器层校验）** 三条规则，以及 Q3 处置决策，**尚未写入 `docs/behavioral-rules.md`**。此为文档收尾项，须在 PR 合并前完成，不阻断本轮进入 `ac-verifier`。

## 4. OWASP / CWE 发现（本轮复审）

| 编号 | 等级 | 位置 | 说明 / 修复建议 |
|---|---|---|---|
| 无新发现 | — | — | 一轮 Q2 已修复；Q4/Q5/S1 修复经验证正确且未引入新问题；无阻断级、无高危漏洞。 |

**说明**：security-review 原则（§7 confidence ≥ 0.80 且可实证利用才上报；§8 排除项）下，本轮修复均为防御性/工程质量改进，无新增可利用面。唯一残留为 Q3 的日志缺失（LOW，已评估可接受）。

## 5. 结论

- [x] **通过（PASS）** —— 一轮所附 MEDIUM（Q2）已正确修复并经独立复核；LOW（Q4/Q5/S1）一并修复正确；单激活不变式未绕过；构建无编译错误，`testDebugUnitTest` 全部通过（BUILD SUCCESSFUL）。
- [x] **可进入 `ac-verifier`**。
- [ ] 阻断项：无。
- [ ] 有条件项：无（Q3 处置已评估可接受；行为规则文档收尾项不阻断本轮）。

**收尾提醒（供主 Agent，PR 合并前完成）**：

1. 将一轮报告 §5 提议的 BR-ui-001 / BR-error-handling-004 / BR-security-001（CRLF）三条规则追加至 `docs/behavioral-rules.md`，并在审计记录表登记。
2. 将 Q3「兜底 catch 不引入日志库、以通用文案 + 行为规则记录」的决策作为 error-handling 类规则/注记固化，避免后续迭代重复评估。

## 6. 规则确认（accepted review → behavioral-rules）

本护栏确认一轮报告 §5 三条规则提议**非重复且可执行**，建议主 Agent 按 §5 收尾提醒落地到 `docs/behavioral-rules.md`：

| ID | 类别 | 规则（摘要） | 来源 |
|---|---|---|---|
| BR-ui-001 | testing | 可变列表持有于 Compose 状态时禁止原地改值；须重建列表或用 `mutableStateListOf` | US-007 guardrail Q2 |
| BR-error-handling-004 | error-handling | 兜底 catch 除通用文案外须记录结构化日志（不含密钥/请求体/完整 URL），禁止静默吞异常 | US-007 guardrail Q3 |
| BR-security-001 | security | 用户可配置 header 值/名称在保存点须校验并拒绝控制字符（CRLF） | US-007 guardrail S1 |

## 7. 自动化建议（CI/CD 集成参考）

沿用一轮报告 §6 建议，补充一条可由 CI 校验的门禁：`consistency-check.js` 增加对 `docs/behavioral-rules.md` 的校验——当 guardrail 报告 §6 含「规则确认」时，PR 须同步包含对应规则条目，防止「审后未固化为规则」的流程漏洞。

---

> 本报告使用相对路径（ADR-010）。任务令牌 TKN-US007-GUARDRAIL-002，执行 Agent guardrail-enforcer。
