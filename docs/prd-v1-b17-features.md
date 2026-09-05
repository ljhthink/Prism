# v1 批次17 四项新功能（TODO List / 思维链覆写 / 对话回退 / 对话重试）· 产品需求文档（PRD）

| 项目 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-09-03 |
| 作者 | 主 Agent |
| 关联文档 | ADR-043、调研报告 docs/reports/2026-09-03-todo-list-research.md（用户已批准）、RCA docs/reports/2026-09-03-mcp-tool-call-interruption-rca.md |
| 风险等级 | P2（跨模块：ChatMessage 数据模型变更 + 工具注入 + VM 状态机扩展） |

## 1. 背景

用户批准四项新功能（调研报告 §1~§13 已含业界一手核验与方案选型）：

1. **TODO list**：LLM 对多步任务先建清单、实时更新，用户可視化监督任务进度（业界共识：单工具全量替换 + 三态状态机）。
2. **思维链覆写**：用户可自由覆写 LLM 思维链与输出内容并保存进对话（Open WebUI 同款；thinking 独立字段编辑，不混入 content）。
3. **单次对话回退**：从某条 user 消息处回退，删除其后所有消息（线性硬截断 + 可选另存新会话兜底）。
4. **单次对话重试**：对最后一条 AI 回复重新生成，保留全部历史版本可切换（SillyTavern swipes 变体模型）。

## 2. 目标与非目标

- 目标：四功能全部落地且通过 guardrail + ac-verifier 闭环；零新第三方依赖；既有工具回路协议不变量不被破坏（批次17 净化器兜底）。
- 非目标：消息树（parentId 分支）；thinking 回传普通对话请求（业界共识 + DeepSeek 禁止）；TODO 清单持久化（会话级）；variants 无限膨胀（截断策略）。

## 3. 用户故事与验收标准

### US-1701: TODO 数据模型与 todo_write 工具执行器

- 作为用户，我希望 LLM 对多步任务先建清单并实时更新，以便监督任务进度。
- 验收标准：
  - [ ] `TodoItem(content, activeForm, status)` + `TodoListState(items, version)` 数据模型（会话级内存）
  - [ ] `TodoLocalToolExecutor` 实现 `todo_write` 工具：maxItems=8、恰好 1 个 in_progress、违规回灌错误提示让 LLM 自纠
  - [ ] 成功后结果回灌清单快照（`1.[x] … 2.[→] …`）+ 状态经 `TodoListState` StateFlow 推送 UI（实现偏差说明：StateFlow 直推替代原计划的 StreamEvent.TodoUpdate——避免为 UI 状态新增 StreamEvent 子类波及 handleStreamEvent 穷尽匹配，语义等价且更简洁）
  - [ ] 单元测试覆盖：合法更新 / 超 8 项拒绝 / 多个 in_progress 拒绝 / 空清单清空 / 回灌快照格式
  - [ ] Typecheck passes

### US-1702: todo_write 工具注册与 systemPrompt 指引

- 作为 LLM，我需要感知 todo_write 工具并被引导正确使用。
- 验收标准：
  - [ ] `buildTools` 合并 todo_write（schema 符合 JSON Schema：type:array + items:object，防 400）
  - [ ] TODO_GUIDANCE 注入 mergeSystemPrompt（≥3 步任务先建清单 / 开始前置 in_progress / 完成立即 completed 禁止批量补记）
  - [ ] buildTools 集成测试：webSearchEnabled + todoWrite 开关组合正确
  - [ ] Typecheck passes

### US-1703: TodoCard 聊天流卡片

- 作为用户，我希望在聊天流中实时看到任务清单卡片（进度 n/m、当前步骤高亮）。
- 验收标准：
  - [ ] TodoCard 组件：标题「任务计划 (n/m)」+ 三态图标 ○/◐/✓ + activeForm 高亮
  - [ ] 按 version 原地更新不新增气泡；会话切换清空
  - [ ] Typecheck passes

### US-1704: AI 消息编辑（思维链 + 输出覆写）VM 层

- 作为用户，我希望覆写 LLM 的思维链与输出内容并保存进对话，以便让思维链透明可控。
- 验收标准：
  - [ ] `editAiMessage(messageId, newContent, newThinkingChain)`：CAS 原子更新（不截断后续消息），置落库脏标记
  - [ ] 仅允许 ASSISTANT 角色消息；isTyping 时忽略
  - [ ] thinkingChain 更新在工具回路 reasoning_content 回传中自然生效（协议层已回传）
  - [ ] 单元测试：更新生效 / 非 ASSISTANT 忽略 / isTyping 忽略 / 后续消息不受影响
  - [ ] Typecheck passes

### US-1705: AI 消息编辑 UI

- 作为用户，我希望通过长按/菜单打开双编辑框（思维链 + 输出）。
- 验收标准：
  - [ ] AI 消息操作菜单新增「编辑回复」→ PrismSheet 双编辑框（深度思考 + 正文）预填充原内容
  - [ ] 保存调用 US-1704 方法；空内容保存忽略
  - [ ] Typecheck passes

### US-1706: 单次对话回退

- 作为用户，我希望从某条 user 消息回退（删除其后所有消息），以便修正方向重来。
- 验收标准：
  - [ ] `rollbackFromUserMessage(messageId)`：CAS 删除该消息及其后全部消息
  - [ ] UI：user 消息菜单「从这里重新开始」→ 确认对话框（提示将删除 N 条）→ 截断 → 自动以该消息重发
  - [ ] 「另存为新会话」兜底：回退前可复制当前会话快照为新会话
  - [ ] 单元测试：截断正确 / 非 USER 角色忽略 / isTyping 忽略 / 另存新会话内容完整
  - [ ] Typecheck passes

### US-1707: ChatMessage 变体数据模型（swipes）

- 作为用户，我希望重试时保留全部历史版本，以便对比切换。
- 验收标准：
  - [ ] `ChatMessage` 新增 `variants: List<MessageVariant>?` + `activeVariantIndex: Int`（默认 null/0）
  - [ ] `MessageVariant(content, thinkingChain, searchResults, sources, createdAt)`
  - [ ] ChatMessageSerializer 显式支持：空 variants 不落盘防膨胀；旧 JSON 向后兼容（ignoreUnknownKeys）
  - [ ] 单元测试：序列化 roundtrip / 旧 JSON 反序列化兼容 / 空 variants 不产生字段
  - [ ] Typecheck passes

### US-1708: 重新生成与变体切换

- 作为用户，我希望对最后一条 AI 回复重新生成并左右切换版本。
- 验收标准：
  - [ ] `regenerateLastAiMessage()`：原 content 迁移 variants[0]（仅首次）→ 追加新 variant 置 active → 以前置 user 消息重发
  - [ ] `switchVariant(messageId, index)`：切换 activeVariantIndex，仅改展示与后续请求所用内容
  - [ ] variants 本身不进 LLM 请求（active content 即消息 content）
  - [ ] UI：AI 消息底部「‹ 2/3 ›」切换器（仅最后一条 AI 消息且 variants 非空时显示）
  - [ ] 单元测试：首重试迁移 / 二次重试追加 / 切换生效 / isTyping 忽略 / 非 last 忽略
  - [ ] Typecheck passes

## 4. 非功能需求

- 性能：TodoCard 原地更新零重组开销；variants 仅追加不复制历史；净化器/序列化增量 <1ms 级。
- 安全：todo_write 参数走 JSON Schema 校验（防 400）；编辑内容不进日志明文；无新密钥面。
- 可观测性：todo_write 执行经 ToolCallRecord 记录（复用 US-029）；净化器日志既有口径不变。
- 兼容性：API 26+ 不变；旧会话 JSON 反序列化兼容（新字段可空默认）。
- 隐私：编辑/变体数据仅本地 ObjectBox，不上传。

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| ChatMessage 新增字段导致旧会话反序列化异常 | 高 | ignoreUnknownKeys + 可空默认 + roundtrip 单测（对齐 sourceMessageIds 迁移教训） |
| todo_write 弱模型不主动用 | 中 | 结果回灌兜底 + TODO_GUIDANCE 引导 + phone_control 工具描述引导 |
| variants JSON 膨胀 | 中 | 空 variants 不落盘 + 变体捕获时 thinkingChain 截断（markCompleted 路径，对齐 MAX_REASONING_LEN=2000；迁移/编辑路径未截断，guardrail R2 残留②口径）+ MAX_VARIANTS=10 硬界 |
| 请求历史含 variants 结构污染协议 | 中 | variants 不进请求（active content 即 content）+ 批次17 净化器兜底 |

## 6. 里程碑

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| M-B17-A | US-1701~1703（TODO list 全链路） | P2 |
| M-B17-B | US-1707~1708（变体 + 重试） | P2 |
| M-B17-C | US-1704~1705（编辑覆写）+ US-1706（回退） | P2 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| AC-1 todo_write 执行与校验 | 单元测试 | 合法/超限/多 in_progress/空清单/快照格式全过 | US-1701 |
| AC-2 工具注册与感知 | buildTools 集成测试 | todo_write 出现在 tools 列表、schema 合法 | US-1702 |
| AC-3 TodoCard 渲染 | Compose 单测/编译 | 按 version 更新、三态图标、进度计数 | US-1703 |
| AC-4 编辑覆写 | 单元测试 | CAS 更新、边界忽略、后续消息保留 | US-1704 |
| AC-5 编辑 UI | 编译 + 真机 | 双编辑框预填充、保存生效 | US-1705 |
| AC-6 回退 | 单元测试 | 截断正确、另存完整、边界忽略 | US-1706 |
| AC-7 变体序列化 | 单元测试 | roundtrip、旧 JSON 兼容、空不落盘 | US-1707 |
| AC-8 重试与切换 | 单元测试 | 迁移/追加/切换/边界全过 | US-1708 |
| AC-9 全量回归 | testDebugUnitTest | 0 失败 | 全部 |

## 8. 待确认事项

- [x] 全部待批准项已经用户 2026-09-03 批准（调研报告 §13）。
