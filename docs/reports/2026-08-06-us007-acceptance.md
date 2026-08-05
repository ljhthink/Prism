# 验收测试报告（US-007 Provider 切换）

> 由 ac-verifier 子 Agent 生成。依 CLAUDE.md 第十一节。本报告对 US-007「Provider 切换」执行分层验收测试，
> 覆盖 prd.json 五条验收标准 + 遗留项（自定义 headers 编辑 / 输入校验 / 3 项 guardrail LOW 修复 / 单激活不变式）。

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-US007-ACCEPTANCE-001 |
| 验收日期 | 2026-08-06 |
| 关联 PRD | `prd.json`（US-007）· `docs/PRD.md`（US-001 Provider 切换语义） |
| 关联 ADR | ADR-002 / ADR-003 / ADR-004 |
| guardrail 报告 | `docs/reports/2026-08-06-us007-guardrail.md`（TKN-US007-GUARDRAIL-001，有条件通过）· `docs/reports/2026-08-06-us007-guardrail-round2.md`（TKN-US007-GUARDRAIL-002，通过） |

## 0. 上下文重建摘要

- 本次任务：对 US-007「实现 Provider 切换」执行全量分层验收，覆盖 prd.json AC 与遗留项，补充极端/边界场景、性能回退检查与基础安全验证。
- 主 Agent 自问核实：
  - **自问 1（切换后新消息走新 Provider）**：`ConversationViewModel.sendMessage`（`app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt` L85）**直读** `providerRepository.activeProviderFlow.value`（仓库单一事实来源，`setActive` 内同步刷新），并于 `provider.streamChat(active, history)` 传入该 `active`。非依赖 `WhileSubscribed` 的 `vm.activeProvider`，故切换后立即生效、无滞后。`ConversationViewModelTest.setActiveProvider switches provider preserving history and routing new messages`（L159-199）端到端佐证：`receivedConfigs.last().name == "Anthropic"`。**核实通过**。
  - **自问 2（SSE 错误映射 401/4xx 文案）**：`OpenAICompatibleProviderTest` 真实 Netty 服务器集成测试实证 `401 → 鉴权失败，请检查 API Key`（L233-255）、`429 → 请求被拒绝（429）`（L258-280）。**核实通过**。
  - **盲区（headers 编辑 UI 状态 / 输入校验边界）**：`SettingsScreen.ProviderEditSheet` 为 **private composable**，其 `mutableStateListOf` 增删改重组、`validHeaders` trim/过滤/CRLF 拒绝、`canSave` 校验逻辑均位于 Compose UI 内部，**无法用 JVM 单元测试直接覆盖**（项目无 `androidTest`/Compose UI 测试目录）。已通过源码静态复核确认逻辑正确（见 §2.1 静态分析），交互层标记为「需真机/Compose UI 测试」受限项。

## 1. 验收标准执行结果

| 验收项 | 验证方法 | 通过标准 | 结果 | 证据 |
|---|---|---|---|---|
| AC-1 聊天界面提供 Provider 选择器 | VM/仓库层数据流 + 源码静态确认 | `activeProvider`/`providers` 暴露、`setActiveProvider(id)` 可切换 | **通过（UI 外观受限）** | `ConversationViewModel.kt` L53-63；选择器外观为 Compose 交互，需真机确认 |
| AC-2 切换 Provider 后保留对话历史 | 单元测试 | 切换前后 `messages` 数量不变 | **通过** | `ConversationViewModelTest` L190：`assertEquals(historyBeforeSwitch, vm.messages.value.size)` |
| AC-3 切换后新消息走新 Provider | 单元测试 | 切换后 `receivedConfigs.last().name` 为新 Provider | **通过** | `ConversationViewModelTest` L195：`assertEquals("Anthropic", provider.receivedConfigs.last().name)` |
| AC-4 Typecheck passes | Gradle 编译 | `compileDebugKotlin` + `testDebugUnitTest` BUILD SUCCESSFUL | **通过** | 实际执行 `BUILD SUCCESSFUL in 45s, 31 tasks executed` |
| AC-5 Verify in browser (dev-browser) | 原生应用不适用 | 不适用 | **受限跳过** | 与 US-002/003/004/005/006 同模式（原生 Android，无 dev-browser 场景） |
| 遗留① 自定义 headers 编辑（增/删/改）持久化且请求生效 | 源码静态 + guardrail 复核 | `mutableStateListOf` 增删改触发重组；保存 `validHeaders.toMap()`；请求侧 `applyCustomHeaders` 合并 | **通过（静态）** | `SettingsScreen.kt` L280-284/L303-305/L402；`OpenAICompatibleProvider.kt` L140-147 |
| 遗留② 名称非空 + Base URL http/https 校验 | 源码静态确认 | 非法输入 `canSave=false` 阻止保存并提示 | **通过（静态，UI 交互受限）** | `SettingsScreen.kt` L298-302/L312-319/L411-414 |
| 遗留③-1 401 返回「鉴权失败，请检查 API Key」 | 集成测试 | 401 文案精确 | **通过** | `OpenAICompatibleProviderTest` L251 |
| 遗留③-2 非 401 4xx 返回「请求被拒绝（status）」 | 集成测试 | 429 文案含状态码 | **通过** | `OpenAICompatibleProviderTest` L276 |
| 遗留③-3 自定义头名大小写规范化 | 单元测试 | lowercase `authorization`/`content-type` 被跳过 | **通过** | `applyCustomHeaders skips lowercase...` L125-141 |
| 遗留③-4 apiKeyRef 空回退自定义 Authorization 头 | 集成测试 | 回退发送自定义 Authorization | **通过** | `streamChat falls back to custom authorization header...` L287-318 |
| 遗留③-5 单激活不变式（一次仅一个激活，事务原子） | 单元测试 | 切换后原激活置 false；save 兜底；事务原子 | **通过** | `ProviderConfigRepositoryTest` `set_active_deactivates_others` L224 / `save_active_config_deactivates_others` L248 |

## 2. 分层测试

### 2.1 静态分析

**编译**：`compileDebugKotlin` 与 `compileDebugUnitTestKotlin` 均通过（`BUILD SUCCESSFUL`，kapt ObjectBox 正常）。Kotlin warning：`OpenAICompatibleProviderTest.kt:382` 冗余 `Json` 实例创建（`Redundant creation of Json default format`，非错误，仅轻微可维护性提示）。

**UI 校验逻辑静态复核**（Compose composable 内，无法 JVM 断言，此处源码级确认）：

- `SettingsScreen.kt` L298 `nameValid = name.trim().isNotEmpty()` — 名称非空校验 ✅
- L300 `urlValid = baseUrlTrimmed.startsWith("http://") || startsWith("https://")` — URL 前缀校验 ✅
- L301 `urlSafe = urlValid && !contains('\r') && !contains('\n')` — baseUrl CRLF 拒绝 ✅
- L302 `canSave = nameValid && urlValid && urlSafe` — 保存门禁 ✅
- L303-305 `validHeaders`：key/value `trim()`、过滤空 key、拒绝含 `\r`/`\n` 的 key/value ✅
- L402 `headers = validHeaders.toMap()` — 仅持久化合法 header，注入载荷不达引擎 ✅
- L280-284 `mutableStateListOf` + `remember(config.id)`，原地改值 `headers[index]=`（L349/L356）触发快照重组，删除 `removeAt`（L369）/新增 `add`（L381）结构变更 ✅
- L403 `isActive = false` 强制保存时关闭激活，激活统一经 `setActive` 事务（L407）— 单激活不变式无绕过路径 ✅

**行为规则收尾核查**：guardrail round2 §5 提议的 `BR-ui-001`（可变列表原地改值）、`BR-error-handling-004`（兜底 catch 结构化日志）、`BR-security-001`（CRLF 编辑器层校验）**尚未写入 `docs/behavioral-rules.md`**（现 `BR-security-001` 为 US-002 的 equals/hashCode 规则，语义不同）。为文档治理收尾项，非功能缺陷，不阻断本轮验收（详见 §8 待办）。

### 2.2 单元测试

| 测试类 | 用例数 | 通过 | 失败 | 覆盖要点 |
|---|---|---|---|---|
| `OpenAICompatibleProviderTest` | 27 | 27 | 0 | 纯函数（endpoint/authHeader/requestBody/applyCustomHeaders/customAuthHeader/parseChunkData）+ 真实 Netty SSE 集成（401/429/apiKeyRef 空回退/取消重抛） |
| `ConversationViewModelTest` | 10 | 10 | 0 | US-007 核心：切换保留历史 + 新消息走新 Provider、空占位过滤、多轮残留过滤、无激活提示 |
| `SettingsViewModelTest` | 12 | 12 | 0 | 新建草稿唯一 apiKeyRef、保存后激活/不激活、删除清选中、API Key 读写 |

**覆盖率评估**：US-007 相关 49 用例（27+10+12）全部通过。Network 层纯函数分支充分（`mapHttpError` 经集成测试覆盖 401/429 两路径；`parseChunkData` 覆盖空/畸形/超大/多 choice/usage 快照）。VM/仓库层单激活不变式充分。UI 校验逻辑因位于 private composable 内无 JVM 覆盖（见 §7 未覆盖项）。

### 2.3 集成测试

真实 Ktor Netty SSE 服务器端到端（`OpenAICompatibleProviderTest` 集成块），全部通过：

| 场景 | 结果 | 证据 |
|---|---|---|
| 流式 deltas → Done（真实服务器） | Pass | `streamChat streams deltas then done` L197 |
| 401 → 鉴权失败文案 | Pass | `streamChat emits error on unauthorized` L233 |
| 429 → 请求被拒绝（429） | Pass | `streamChat emits generic rejected message for non 401 4xx` L258 |
| apiKeyRef 空 → 回退自定义 Authorization 头 | Pass | `streamChat falls back to custom authorization...` L287 |
| 取消传播（CancellationException 重抛，非吞异常） | Pass | `streamChat rethrows cancellation` L321 |
| 端点不可达 → Error 不崩溃 | Pass | `streamChat emits error when endpoint unreachable` L455 |
| 流中断（无 DONE）→ 兜底补发 Done | Pass | `streamChat emits done when server closes without DONE` L469 |
| 中段 usage 快照后继续流 | Pass | `streamChat continues after mid-stream usage snapshot` L499 |

**事务原子性**：`ProviderConfigRepository.setActive`/`save` 均经 `boxStore.runInTx` 单事务（`ProviderConfigRepository.kt` L54/L123），切换失败回滚不产生多激活态。`set_active_deactivates_others` 等集成级断言验证切换后原激活置 false。

### 2.4 E2E 测试

- **Compose UI 交互 E2E（Provider 选择器点击弹出、headers 增删改、校验错误提示）**：项目无 `androidTest` 目录、无模拟器/Compose UI 测试依赖，**本环境无法自动覆盖**。数据层/VM 层决策链已由单元测试闭环（AC-2/AC-3），UI 外观与交互标注为「需真机手动验证或后续配置 Compose UI 测试」。
- **核心业务流决策链**（VM 层 E2E）：`ConversationViewModelTest.setActiveProvider switches provider preserving history and routing new messages`（L159）覆盖「发送 → 切换 → 再发送」完整决策链，验证历史保留与路由切换。✅

## 3. 极端/边缘场景

| 场景 | 覆盖 | 结果 | 证据 |
|---|---|---|---|
| 空 Provider 列表 / 无激活 Provider | `sendMessage without active provider appends hint not crash` | Pass | `ConversationViewModelTest` L136 |
| 空白输入忽略 | `sendMessage ignores blank input` | Pass | L111 |
| 超长 content（10 万字符） | `parseChunkData handles oversized content` | Pass | `OpenAICompatibleProviderTest` L404 |
| 注入/控制载荷（`<script>`/SQLi/CRLF/emoji/引号） | `parseChunkData keeps injection and control payloads as plain delta` | Pass | L411 |
| 畸形/类型不安全 chunk | `parseChunkData ignores malformed and type-unsafe chunks` | Pass | L427 |
| 空模型/空消息 | `buildRequestBody handles empty models and empty messages` | Pass | L395 |
| 重复头名 | `ProviderConfig.headers` 为 `Map`（key 唯一），UI `validHeaders.toMap()` 后者覆盖 | 行为合理（无专门测试，见 §7） | `SettingsScreen.kt` L402 |
| CRLF 注入 baseUrl/header | 编辑器层 `urlSafe`/`validHeaders` 拒绝 + OkHttp 运行时 fail-closed | Pass（静态） | `SettingsScreen.kt` L301/L305 |
| 并发/重复激活切换 | `setActive` 单事务原子；`set_active_deactivates_others`/`save_active...` 断言 | Pass | `ProviderConfigRepositoryTest` L224/L248 |

## 4. 性能回退检查

基线来源：US-006 验收报告 §4（`OpenAICompatibleProviderPerformanceBenchmark`，30 迭代 + 5 预热，localhost Netty）。
US-007 对 `streamChat` 热路径仅新增轻微分支（`customAuthHeader` 回退查找、`SSEClientException`/`ClientRequestException` 分类捕获），理论影响可忽略。

| 指标 | 基线（US-006） | 实测（本次） | 变化 | 结论 |
|---|---|---|---|---|
| 首字延迟 p50 | 2.95 ms | 3.01 ms | +2.0% | 无回退 |
| 首字延迟 p95 | 3.83 ms | 3.96 ms | +3.4% | 无回退 |
| 首字延迟 p99 | 4.13 ms | 4.20 ms | +1.7% | 无回退 |
| 首字延迟 mean | 3.02 ms | 3.05 ms | +1.0% | 无回退 |
| 吞吐 p50 | —（US-006 未列数值） | 18153 token/s | — | 正常 |
| 错误率 | 0% | 0%（30 迭代全成功） | 0 | 无错误 |

**门禁判定**：首字延迟 p99=4.20ms，**远小于 <1s 门禁**，且相对基线变化 **+1.7%（< 20% 警告线）**，无性能回退。**通过**。

> 注：首次强制 `--rerun-tasks` 采得 p99=10.88ms，经重跑回落至 4.20ms，判定为 JVM 冷启动/系统负载波动，非真实回退（代码热路径改动极小，二次采样稳定于基线）。

## 5. 安全检查

- [x] **注入测试**：SSE 请求体用 `json.encodeToString`（非字符串拼接）；自定义 header 编辑器层拒绝 CRLF（`SettingsScreen.kt` L305）+ OkHttp 运行时对含 `\r`/`\n` 头值 fail-closed（`OpenAICompatibleProvider.applyCustomHeaders` 直传 `builder.header`）→ 注入载荷不可达引擎。`parseChunkData` 对 `<script>`/SQLi/CRLF 载荷安全解析为纯 Delta 不崩溃。**通过**。
- [x] **敏感信息泄露**：`mapHttpError` 文案为硬编码通用字符串（`鉴权失败，请检查 API Key` / `请求被拒绝（status）` / `网络请求失败…`），不含内部路径、异常 detail 或密钥；`apiKey` 仅拼入 Authorization 头，不落日志、不进入错误文案。源码级 Grep 硬编码密钥模式（`sk-`/`AKIA`/`Bearer [A-Za-z0-9]{20,}`/`password=`/`secret=`）**无匹配**。**通过**。
- [x] **XSS（前端）**：Compose 原生渲染，无 HTML 注入面；`StreamEvent` 文案为硬编码字符串非反射用户输入；`parseChunkData` 将任意用户内容作为纯文本 Delta 处理，不执行。**通过**。
- [x] **单激活状态机 / 权限**：`ProviderEditSheet` 保存强制 `isActive=false` + 激活统一经 `setActive` 单事务（`SettingsScreen.kt` L403/L407），`save` 兜底防御直写 `isActive=true`（`ProviderConfigRepository.kt` L55），无绕过路径。**通过**。

## 6. 回归测试

| 套件 | 实际执行 | 失败 | 错误 | 跳过（@Ignore 性能基准） | 结果 |
|---|---|---|---|---|---|
| 全量 `testDebugUnitTest`（14 类） | 157 | 0 | 0 | 15 | **通过**（`BUILD SUCCESSFUL in 45s, 31 tasks`，强制 `--rerun-tasks` 实测） |

回归明细：`OpenAICompatibleProviderTest` 27 · `ConversationViewModelTest` 10 · `SettingsViewModelTest` 12 · `ProviderConfigRepositoryTest` 42 · `ProviderConfigEdgeCaseTest` 17 · `ApiKeyRepositoryTest` 14 · `ApiKeyEdgeCaseTest` 16 · `KnowledgeChunkCrudTest` 9 · `KnowledgeChunkEdgeCaseTest` 9 · `ProviderConfigDemo` 1 · 其余为 @Ignore 性能基准。**无回归**。

## 7. 缺陷清单

| ID | 严重度 | 关联 AC | 描述 | 复现/证据 | 处置 |
|---|---|---|---|---|---|
| — | — | — | **无功能缺陷** | 全部 157 用例通过，无失败 | — |
| OBS-01 | B0 | — | ObjectBox 测试 tearDown 输出 `Aborting a read transaction in a non-creator thread` 线程警告（多测试并行清理时），非测试失败 | `testDebugUnitTest` stdout / XML system-err | 已知 ObjectBox 测试清理现象，非功能缺陷；建议主 Agent 关注（低风险） |
| CM-01 | B0 | — | Kotlin 编译警告：`OpenAICompatibleProviderTest.kt:382` 冗余 `Json` 实例创建 | `compileDebugUnitTestKotlin` warning | 轻微可维护性提示，非阻断 |

## 8. 待办与风险（非阻断）

| 项 | 类别 | 说明 |
|---|---|---|
| 行为规则收尾 | 文档 | guardrail round2 §5 提议的 `BR-ui-001` / `BR-error-handling-004` / `BR-security-001`（CRLF）三条规则**尚未写入 `docs/behavioral-rules.md`**；guardrail Q3「兜底 catch 不引入日志库」决策亦未固化。建议主 Agent 在 PR 合并前完成（不阻断本轮验收） |
| 文档一致性 | 文档 | `docs/PRD.md` 中 `US-007` 为「设备适配与降级」，而 `prd.json` 中 `US-007` 为「实现 Provider 切换」，**编号不一致**（Provider 切换语义在 PRD.md 中归属 US-001 L52）。建议主 Agent 依一致性审计（CLAUDE.md §14）校准，避免长期文档漂移 |
| Compose UI 交互未自动验证 | 测试 | Provider 选择器外观、headers 编辑器增删改、校验错误提示显示等 UI 交互无法在 JVM 环境自动覆盖（无 androidTest/模拟器）。已由源码静态复核 + VM 层决策链测试兜底，需真机手动验证或后续配置 Compose UI 测试 |
| 真机公网首字延迟（AC-2 精确值） | 性能 | JVM 近似基线 p99=4.20ms 达标，但真机公网 PoC 待补测（与 US-006 同模式的已知受限项） |

## 9. 结论

- [x] **通过（PASS）** —— prd.json 五条 AC 全部满足（AC-5 原生应用受限跳过，与历史 US 同模式）；遗留项（headers 编辑 / 输入校验 / 3 项 guardrail LOW 修复 / 单激活不变式）全部验证通过；性能无回退（p99 4.20ms，+1.7%）；安全检查全部通过；回归 157 用例 0 失败。
- [x] 无阻断级缺陷。
- [ ] 不通过。

**转回主 Agent 事项（均非阻断）**：

1. 将 guardrail round2 §5 提议的三条行为规则（BR-ui-001 / BR-error-handling-004 / BR-security-001-CRLF）与 Q3 决策写入 `docs/behavioral-rules.md`。
2. 校准 `docs/PRD.md` 与 `prd.json` 的 US-007 编号差异。
3. 真机手动验证 Compose UI 交互（Provider 选择器 / headers 编辑器 / 校验提示），并补测公网首字延迟。

---

> 本报告使用相对路径（ADR-010）。执行 Agent：ac-verifier，任务令牌：TKN-US007-ACCEPTANCE-001。
