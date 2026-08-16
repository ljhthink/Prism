# ADR-024: UXR4 真机反馈修复（reasoning_content 回传 + 知识库工具 + 状态机 + 持久化）

> 解决 UXR4 真机测试反馈的 10 个问题（400 reasoning_content 错误 / 知识库工具路由 / 输出逐字 / 工具 UI 闪烁 / 历史会话时间）。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-15 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-020 深度思考 + 联网搜索](ADR-020-thinking-and-web-search.md)、[ADR-021 UX 修复](ADR-021-ux-issue-fixes.md)、[ADR-022 UX R2 修复](ADR-022-ux-r2-markdown-tool-mcp-fixes.md)、[ADR-023 UX R3 修复](ADR-023-ux-r3-fixes.md)、[ADR-014 tool_calling 接口](ADR-014-m4-toolcalling-interface.md)、[ADR-012 RAG 集成](ADR-012-m3-rag-conversation-integration.md) |
| 上游调研 | [考古报告 2026-08-15-uxr4-archaeology.md](../reports/2026-08-15-uxr4-archaeology.md)、DeepSeek 官方 thinking_mode 文档（reasoning_content 回传要求）、GitHub issue openclaw#71037（同类 400 根因） |
| 风险等级 | P2（跨模块：Provider 协议 + SkillExecutor + 知识库工具 + UI 状态机 + 持久化） |

## 背景（Context）

UXR4 真机测试暴露 10 个问题，其中 1/4/6 为同一根因、7/10 为同一根因：

1. **400 reasoning_content 错误（问题 1/4/6，B3 致命）**：联网搜索 / GitHub MCP / Sequential Thinking 工具调用时报
   `The reasoning_content in the thinking mode must be passed back to the API`。考古 + DeepSeek 官方文档双重确认：
   **携带 `tools` 参数的请求，后续所有请求必须完整回传 `reasoning_content`**。而 `MessageBody` 无该字段、
   `toMessageBody()` / `SkillExecutor.buildAssistantToolCallMessage` 均不回传。
2. **知识库工具路由不当（问题 2/3）**：知识库**没有任何面向 LLM 的 MCP/工具接口**（仅 RAG 自动注入），
   LLM 感知不到知识库能力，才会把"知识库里有什么"误路由到 Filesystem；RAG 单次 top-k + 阈值 0.5 双重收窄导致"只见第一篇"。
3. **输出逐字（问题 5）**：`AiBubble` 注释宣称用 `rememberMarkdownState` 但实际未用（0.26.0 该 API 不存在，注释漂移），
   每 delta 全量 Markdown 重渲染 + 自动滚动动画高频触发，弱设备上表现为"一行一词"式浮现。
4. **工具调用 UI 一闪而过（问题 7/10）**：`activeTool` 在 `Done`（紧跟 ToolCallComplete）即被清除，工具**执行阶段**
   `activeTool=null` 且 `isTyping=false` → 指示器一闪而过 + 执行期空白。
5. **历史会话时间错误（问题 8/9）**：`persistSession` 仅在 `onCleared/startNewConversation/loadSession` 三个时机调用，
   `updatedAt` 被写为"最后离开时刻"，且"只读打开再退出"也会刷新时间；回答完成不落库。

## 决策（Decision）

### 子决策 A：reasoning_content 多轮回传（问题 1/4/6）

- `MessageBody` 新增 `@SerialName("reasoning_content") val reasoningContent: String? = null`
- `ChatMessage.toMessageBody()` 的 ASSISTANT 分支携带 `msg.thinkingChain`（作为 reasoning_content 回传）
- `SkillExecutor.executeLoop` 收集流式 `ReasoningDelta` 累积，`buildAssistantToolCallMessage` 构造的
  assistant 占位消息携带该 reasoning（DeepSeek 要求带 tool_calls 的 assistant 消息必须含 reasoning_content）
- 非流式 `chatCompletion` 请求回传路径复用同一 `toMessageBody()`，天然覆盖

### 子决策 B：知识库 MCP 工具（问题 2/3）

- 新增本地工具 `knowledge_base__search` / `knowledge_base__list_documents` / `knowledge_base__get_document_content`
  （实现 `LocalToolExecutor` 接口），注入 `CompositeLocalToolExecutor` 与 `buildTools`
- LLM 可主动枚举 / 检索 / 读取知识库（对齐 karpathy-LLM.md 的 index + 主动查询思想，见考古报告 §5 建议）
- Filesystem 工具描述显式声明"该工具仅访问用户授权的本地文件，不代表 Prism 知识库"，切断语义混淆

### 子决策 C：activeTool/isTyping 状态机（问题 7/10）

- `activeTool` 生命周期从"Done 即清除"改为"工具执行完毕/回路结束清除"：
  - `ToolCallStart` → 置位（LLM 声明将调用）
  - `ToolCallComplete` → 保持 + `isTyping=true`（执行阶段仍显示）
  - `Done`（无工具）→ 清除；`executeWithToolLoop` 的 finally 兜底清除
- `executeLoop` 每轮间 `isTyping` 保持 true（工具执行期有进行中指示）

### 子决策 D：会话持久化时机（问题 8/9）

- `persistSession` 在回答完成（`handleStreamEvent` 的 Done/Error）时调用，保证会话落库 + `updatedAt` 反映
  "最后消息结束时刻"
- 引入 `messagesDirty` 脏标记：仅当有新消息（sendMessage/编辑重发）时置位；`loadSession` 后清位，
  "只读打开再退出"不刷新 `updatedAt`（避免打开即置顶）

### 子决策 E：流式渲染优化（问题 5）

- 修正 `AiBubble` 注释漂移（0.26.0 无 `rememberMarkdownState`，改用实际可用 API 优化）
- 自动滚动 `LaunchedEffect` 从"content 长度变化即滚动"改为"仅消息数变化滚动 + 滚动位置节流"，降低弱设备渲染抖动

**一句话**：reasoning_content 结构性回传修复（B3）+ 知识库工具化（B1）+ 工具执行期状态机 + 持久化时机 + 渲染节流。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| reasoning_content：升级 markdown-renderer 到 0.42+（StreamingMarkdownState） | 原生流式增量解析 | 0.28+ 依赖 Compose 1.7+ ABI，项目 BOM 2024.06.00=Compose 1.6.8 运行期崩溃（libs.versions.toml 已记录约束），不可行 |
| reasoning_content：仅在 UI 展示层处理、不回传 | 改动小 | 违反 DeepSeek 协议，工具回路第 2 轮必 400（B3 未解决），否决 |
| 知识库工具：扩展 Filesystem 工具描述声明 | 改动最小 | 无知识库入口，LLM 仍找不到能力（考古确认根因是无工具），否决 |
| 知识库工具：RAG 只调阈值/top-k | 改动小 | 治标不治本：LLM 无法主动枚举/检索，问题 2/3 复发，否决 |
| 状态机：activeTool 延迟清除（固定延时） | 简单 | 延时不确定，多轮回路仍可能闪断，否决（改为事件驱动） |

## 后果（Consequences）

- 正面：
  - 深度思考 + 任意工具回路不再 400（B3 致命闭合）
  - LLM 可主动枚举/检索知识库，Filesystem 语义清晰，问题 2/3 解决
  - 工具执行期 UI 有持续进行中指示
  - 历史会话时间=最后消息结束时刻，回答完成即落库（崩溃不丢）
  - 流式渲染抖动缓解
- 负面 / 代价：
  - `MessageBody`/`ChatMessage.toMessageBody` 新增字段，序列化体积微增（reasoning_content 可能较长，受 L1 滑动窗口约束）
  - `buildAssistantToolCallMessage` 签名变更，相关测试需同步
  - 新增 3 个知识库工具，工具列表增长（`buildTools` 合并），LLM 上下文工具描述增大
  - 状态机语义变更，`ConversationViewModelUxR2Test` 中固化的"Done 清除 activeTool"断言需更新
- 需要同步更新的文档或代码：
  - `docs/decisions/README.md` 索引 + `README.md` 文档索引新增 ADR-024
  - `docs/behavioral-rules.md` 沉淀（reasoning_content 回传规则）
  - 测试：OpenAICompatibleProviderTest / SkillExecutorTest / ConversationViewModelUxR2Test / SessionRepositoryTest 等

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| reasoning_content 回传破坏其他端点兼容 | 中 | 仅在 assistant 消息 thinkingChain 非空时输出该字段；无思考的端点零影响；OpenAI 兼容端点忽略未知字段 |
| 知识库工具参数/语义 LLM 误用 | 中 | 工具 description 明确参数与返回格式；`additionalProperties=false` 严格校验 |
| 状态机变更引入回归 | 中 | 补充工具执行期用例（activeTool 保持 + isTyping true），回退按 guardrail/ac-verifier 闭环 |
| persistSession 每轮落库性能 | 低 | 消息体受 L1 窗口约束（默认 N=10），JSON 体量可控；ObjectBox put 毫秒级 |
| 流式渲染仍无法完全消除逐字（弱设备） | 低 | 滚动节流缓解主因；完整修复需 0.42+（受 Compose 版本约束暂缓，记录为已知限制） |

## 参考

- DeepSeek 官方 thinking_mode 文档：https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/（工具调用章节明确 reasoning_content 回传要求）
- GitHub openclaw#71037：DeepSeek reasoning 多轮 tool-calling 400 同类根因
- [考古报告 2026-08-15-uxr4-archaeology.md](../reports/2026-08-15-uxr4-archaeology.md)
- [karpathy-LLM.md](../../../Continuous-learning/karpathy-LLM.md)（知识库 index + 主动查询思想）
