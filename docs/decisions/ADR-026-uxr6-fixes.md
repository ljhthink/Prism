# ADR-026: UXR6 真机反馈修复（搜索质量/工具熔断 + markdown 渲染 + RAG UI 状态 + 引用覆盖 + TTFT + 日志诊断）

> 解决 UXR6 真机测试反馈的 6 个问题（"昔涟"→"昔"搜索 + 工具循环上限 / MCP 后 markdown 井号 / RAG UI 无条件检索 + 引用只标第一篇 / 非流式输出 / TTFT 过慢 / 业务日志缺失）。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-16 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-025 UXR5 修复](ADR-025-uxr5-fixes.md)、[ADR-024 UXR4 修复](ADR-024-uxr4-fixes.md)、[ADR-023 UXR3 修复](ADR-023-ux-r3-fixes.md) |
| 上游调研 | [考古报告 2026-08-16-uxr6-archaeology.md](../reports/2026-08-16-uxr6-archaeology.md)、Bing RSS 实测（`mkt=zh-CN` 返回完整"昔涟"）、Bing 中文 URL 编码相似案例 |
| 风险等级 | P2（跨模块：搜索协议 + 渲染 + RAG 状态 + 性能链路） |

## 背景（Context）

UXR6 真机测试暴露 6 个问题（其中问题 1/2/6 为 UXR5 已修复项的复发）：

1. **搜索"昔涟"仍返回"昔" + 工具循环达上限 10**：UXR5 添加的 `language=zh-cn` 参数**非 Bing 认可参数**（实测确认 Bing 本地化参数为 `mkt`/`setlang`）；搜索失败文案"请稍后重试"**诱导 LLM 反复重试**；`SkillExecutor.executeLoop` 无重复工具熔断，直至 maxRounds=10 硬终止 → 用户无答案。
2. **MCP 后 markdown 井号残留（复发）**：`handleStreamEvent` 的 `StreamEvent.Error` 分支**无条件** `_isTyping=false`（无 `toolLoopActive` 守卫，与 Done 分支不对称）→ 工具回路中任一回合 Error 破坏 isStreaming → 第 2 回合最终文本流式期间被误判"完成" → Markdown 渲染不完整中间态 → 井号残留。叠加 `isStreaming = isTyping && lastOrNull()` 全局推断脆弱。
3. **RAG UI 两个问题**：
   - 3a：无论问题是否相关均显示"检索知识库"——`isRagOn = ragTarget 非 Off && !ragDone` 中 `ragDone = messages.any{sources 非空}` 为跨全部历史的全局判定，首条消息恒显示检索画面。
   - 3b：LLM 引用两篇资料但引用来源只标第一篇——sources 仅来自自动 RAG 注入（topK=3 + 阈值 0.5 + HNSW <k 三重收窄常只 1 条），`knowledge_base__search` 工具主动检索出的片段**不进 sources**。
4. **非流式输出（几秒无显示后突然一大段）**：`appendDelta` 对每个 token 执行 `msgs.map{...}` O(N) 全量复制（长对话每 token N 次分配）→ 高频 sticky GC + StateFlow 合流跳帧；首 token 前串行阻塞链（RAG + L2 + **L1 非流式摘要** + MCP describeTools）放大感知。
5. **TTFT 过慢**：MCP `describeTools` 每轮网络连接 listTools（无缓存）；共享 httpClient 无 HttpTimeout（连接挂起无限等待）。
6. **业务日志缺失**：WebSearch 失败/空结果、maxRounds 达上限等用户可见路径**无任何日志**（Log.w 仅异常分支触发）→ 无法在真机 RCA。

## 决策（Decision）

### 子决策 A：搜索质量 + 工具熔断（问题 1）

- **`WebSearchLocalToolExecutor`**：`language=zh-cn` → `mkt=zh-CN` + `setlang=zh-hans`（Bing 官方参数，实测返回完整"昔涟" 8 条结果）；失败文案删除"请稍后重试"改为中性 `"搜索失败：联网搜索暂不可用，请基于已有信息回答"`；空结果文案前置 `[搜索失败]` 标记；新增 `Log.i` 记录实际 query 与结果摘要（RCA 证据）。
- **`SkillExecutor.executeLoop`**：新增**重复工具熔断**——同一 toolName 连续失败达 `MAX_CONSECUTIVE_TOOL_FAILURES=2` 时，置空 tools + systemPrompt 追加"不要再调用工具"，`continue` 用空工具再跑一轮让 LLM 直接回答（防 maxRounds 死循环且用户仍有答案）；`isFailureResult` 纳入「搜索失败」前缀。
- **工具描述**：`query` 参数说明补"一次搜索未命中不要反复用同义词重试"。

### 子决策 B：markdown 渲染（问题 2）

- **每消息独立流式标记**：新增 `streamingIds: StateFlow<Set<Long>>`，`launchAnswer` 创建占位时 `markStreaming(aiId)`，Done/Error/finally 时 `markCompleted(aiId)`；`ConversationScreen` 的 `isStreaming = message.id in streamingIds`（替代全局 isTyping + lastOrNull 推断）。
- **Error 守卫**：`StreamEvent.Error` 分支补 `if (!toolLoopActive)`（与 Done 对称），工具回路中途 Error 不清 isTyping/streamingIds，统一由 `executeWithToolLoop` finally 复位。

### 子决策 C：RAG UI 状态（问题 3a）

- 新增 `ragRetrieving: StateFlow<Boolean>`：`launchAnswer` 的 `buildRagPlan` 前置 true、完成/降级后置 false。UI 的 `TypingIndicator(isRagOn = ragRetrieving)` 由真实检索状态驱动，删除跨历史全局 `ragDone` 判定。

### 子决策 D：引用来源覆盖知识库工具（问题 3b）

- `syncToolMessages` 解析 `knowledge_base__search` TOOL 结果（新增纯函数 `parseKnowledgeBaseCitations`），合并进 `aiId.sources`（按 documentTitle 去重，保留自动 RAG 引用）。

### 子决策 E：性能（问题 4/5）

- `appendDelta`/`appendThinkingDelta`：`msgs.map{}` 全量复制 → 仅重建目标消息（toMutableList + 局部替换），消除每 token N 次分配（对应 sticky GC）。
- MCP `describeTools` 缓存：按 enabled server 集合签名（name@baseUrl）缓存工具定义，签名变化清缓存重取。
- 共享 httpClient 安装 `HttpTimeout`（仅 connect/socket 超时，**不配 requestTimeout**——流式 SSE 长连接会被整请求超时误杀）。

### 子决策 F：业务日志诊断（问题 6）

- 用户可见失败路径补 `Log`：WebSearch 查询/结果数、SkillExecutor 每轮工具调用 + maxRounds 达上限。配合 debug 构建 + `adb logcat --uid=<uid>` 采集真机证据。

**一句话**：Bing 参数规范化 + 重复工具熔断（搜索）+ 每消息流式标记 + Error 守卫（渲染）+ 真实检索状态（RAG UI）+ 知识库工具引用并入 sources + 分配/缓存/超时优化（性能）+ 用户可见路径日志。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| markdown：升级 0.42+（StreamingMarkdownState） | 原生增量解析 | 依赖 Compose 1.7+ ABI，项目 BOM 2024.06.00 运行期崩溃（ADR-025 已确认），不可行 |
| 搜索：切换 DuckDuckGo/Google | 质量可能更好 | 国内不可达（ADR-020 已确认） |
| 渲染：isTyping 全局推断修复（仅加 Error 守卫） | 改动小 | 未解决多消息并发误判；每消息标记更彻底 |
| RAG UI：按 ragTarget 开关显示 | 简单 | 开关开启 ≠ 正在检索，寒暄也显示检索画面（用户主诉） |
| L1 摘要全异步化 | TTFT 收益最大 | 触及记忆系统协议（DeepSeek 不允许中途插消息），本轮风险过高，留待后续 |
| 引用：放宽 topK/阈值 | 增加 sources 条数 | 会重新引入 UXR3"首篇必塞"问题；知识库工具结果并入 sources 是结构性修复 |

## 后果（Consequences）

- 正面：
  - Bing 中文搜索参数规范化 + 失败不诱导重试 + 重复工具熔断（问题 1 根治）
  - 每消息流式标记 + Error 守卫，工具回路后 markdown 井号消失（问题 2）
  - RAG UI 由真实检索状态驱动（问题 3a）
  - 知识库工具检索结果进引用来源（问题 3b）
  - appendDelta 分配优化 + MCP 缓存 + HttpTimeout（问题 4/5）
  - 用户可见路径日志（问题 6 RCA 可行）
- 负面 / 代价：
  - streamingIds 每消息标记增加 ViewModel 状态（成本低）
  - MCP 工具缓存需签名失效（server 增删改后首个请求重建）
  - describeTools 缓存键基于 server.name（同名 server 缓存冲突，签名含 baseUrl 缓解）
- 需要同步更新的文档或代码：
  - `docs/decisions/README.md` + `README.md` 索引新增 ADR-026
  - `WebSearchLocalToolExecutorTest` 断言更新（language→mkt）
  - 新增 `ConversationViewModelUxR6Test`（parseKnowledgeBaseCitations / isFailureResult 搜索失败 / streamingIds 状态机 / ragRetrieving）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| Bing 真机网络环境分词仍收窄（开发机无法复现） | 中 | 参数规范化尽力 + Log 记录实际 query/结果（真机 RCA 依据）；熔断保证不无答案 |
| streamingIds 标记与既有消息操作（syncToolMessages 插入）竞态 | 低 | 原子 StateFlow update + finally 兜底 |
| MCP 工具缓存陈旧 | 低 | enabled server 签名失效 + 缓存仅存于单次 ViewModel 生命周期 |
| HttpTimeout socket 超时误杀慢流式 | 低 | 60s socket 空闲超时（SSE 心跳远小于此），不配 requestTimeout |
| executeLoop 熔断 continue 消耗轮次 | 低 | 熔断后有效工具为空，LLM 纯文本回答自然结束 |

## 参考

- [考古报告 2026-08-16-uxr6-archaeology.md](../reports/2026-08-16-uxr6-archaeology.md)（6 问题证据链 + 11 假设验证）
- Bing 中文搜索 URL 编码相似案例（`mkt`/`setlang` 市场参数、UTF-8 百分号编码）
- Bing RSS 实测：`curl 'https://cn.bing.com/search?q=昔涟&format=rss&mkt=zh-CN'` → 返回 8 条完整"昔涟"结果
