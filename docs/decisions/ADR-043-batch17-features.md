# ADR-043: 批次17 四项新功能（TODO List / 思维链覆写 / 对话回退 / 对话重试）方案选型

| 项目 | 内容 |
|---|---|
| 状态 | Accepted |
| 日期 | 2026-09-03 |
| 关联 | PRD docs/prd-v1-b17-features.md、调研 docs/reports/2026-09-03-todo-list-research.md |

## 背景

用户批准四项新功能。调研报告对每个功能做了业界一手核验（Claude Code / Roo Code / Open WebUI / SillyTavern / LibreChat / Cherry Studio / LobeChat / llama.cpp），需将选型决策固化。

## 决策

### D1 TODO list：单工具全量替换（Claude Code TodoWrite 同构）

- **选择**：单工具 `todo_write` 全量替换 + 三态状态机（恰好 1 个 in_progress）+ maxItems=8 + 会话级内存 + TodoCard 按 version 原地更新。
- **否决**：多工具增量更新（Taskmaster 型）——过重；无界列表（BabyAGI）——失控反面教材；持久化清单——会话级足够（Claude Code 同款）。

### D2 思维链覆写：独立字段编辑，不回传普通请求

- **选择**：覆写 = 编辑 `ChatMessage.thinkingChain` 与 `content` 两个既有字段（CAS 原子），零新存储；thinkingChain 在工具回路 reasoning_content 回传中自然生效；普通对话请求不回传 reasoning（业界共识 + DeepSeek 禁止回传）。
- **否决**：`<think>` 序列化混入 content（llama.cpp #23622 双份回传 bug 反例）；thinking 编辑历史版本（JSON 膨胀，无应用支持）。

### D3 对话回退：线性硬截断 + 另存新会话兜底

- **选择**：`rollbackFromUserMessage` CAS 硬截断（删除目标 user 消息及其后全部），回退前可复制会话快照为新会话；复用既有 `replaceAndTruncateMessages` 语义。
- **否决**：parentId 消息树（Open WebUI 型）——ObjectBox 单 JSON 列需全树加载+指针管理，性价比低（SillyTavern chat-tree PR #4573 三年未合入佐证）；纯破坏式无兜底（Cherry Studio #14561 用户诟病反例）。

### D4 对话重试：SillyTavern swipes 变体模型

- **选择**：`ChatMessage` 内嵌 `variants: List<MessageVariant>?` + `activeVariantIndex`；重试=追加变体（首次迁移原内容为 variants[0]）；切换器仅改 active；**variants 不进 LLM 请求**（active content 即消息 content，协议层零感知）。
- **否决**：消息树兄弟分支（Open WebUI/LibreChat 型）——同 D3；切换隐藏后续内容（LobeChat #10508 bug 反例）——始终线性展示。
- **兼容**：新字段可空默认 + `ignoreUnknownKeys` 反序列化兼容旧 JSON；空 variants 序列化时省略防膨胀（对齐批次13 base64 剥离策略）。

## 后果

- 正面：四功能与既有工具回路/净化器/序列化体系正交；零新依赖；真机可独立验证。
- 负面：ChatMessage 模型字段增加（序列化/渲染需同步）；TODO 工具增加每轮 schema 体积（compact 描述缓解）。
- 中性：若未来需要消息树，可从 variants 演进而非推翻（variants 是树节点的单层特例）。
