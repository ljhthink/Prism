# ADR-025: UXR5 真机反馈修复（markdown 流式渲染 + 工具 UI 时序 + 搜索中文 + tool_calls 完整性）

> 解决 UXR5 真机测试反馈的 5 个问题（markdown 井号残留 / 工具 UI 顺序 / 搜索"昔涟"→"昔" / skills 400 / 日志收集）。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-15 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-024 UXR4 修复](ADR-024-uxr4-fixes.md)、[ADR-020 深度思考 + 联网搜索](ADR-020-thinking-and-web-search.md)、[ADR-021 UX 修复](ADR-021-ux-issue-fixes.md) |
| 上游调研 | [考古报告 2026-08-15-uxr5-archaeology.md](../reports/2026-08-15-uxr5-archaeology.md)、mikepenz markdown issue #315（不支持增量解析）、Bing 中文编码调研、openclaw#90597 + semantic-kernel#8419（tool_calls 关联 400 同类） |
| 风险等级 | P2（跨模块：渲染 + 消息序列 + 搜索 + 协议完整性） |

## 背景（Context）

UXR5 真机测试暴露 5 个问题：

1. **markdown 井号残留 + 逐字**：调用 MCP 工具后，AI 回答渲染出现 `#` 残留 + 一行一词。考古确认 markdown-renderer **0.26.0 不支持增量解析**（mikepenz issue #315，每次 content 变化全量重解析），流式中间态（未完成标题/无换行结尾段落）被渲染为字面符号。项目注释宣称"增量重组"与库官方行为矛盾（注释漂移）。
2. **工具/思考 UI 顺序错乱**：所有工具调用与思考过程出现在 LLM 最终文本**下方**，而非按调用顺序。根因：executeLoop 最终文本回答不进 currentMessages（只累积到 aiId），占位/tool 经 syncToolMessages 追加到**末尾**。
3. **搜索"昔涟"只返回"昔"**：Bing RSS 对中文 query 无 language 参数限定 + 响应编码未按 charset 解码（cn.bing.com 历史可能返回 GBK）。
4. **Skills 调用 400 `Messages with role 'tool' must be a response to a preceding message with 'tool_calls'`**：三条确定路径——L1 滑动窗口按条数切分切断 assistant(tool_calls)/tool 对、会话恢复丢失 toolCalls、aiId 文本插在 tool_calls 对之前（架构性乱序）。
5. **日志收集**：真机 logcat 未按 PID/TAG 过滤，业务日志缺失。

## 决策（Decision）

### 子决策 A：markdown 流式渲染（问题 1）

- **流式期间用纯文本渲染**：`AiBubble` 新增 `isStreaming` 参数（该消息是否为当前正在生成的 AI 回复，由 `isTyping && message.id == lastOrNull().id` 判定）。流式期间用 `Text` 渲染（无 markdown 解析开销），回答完成（isTyping=false）后一次性 `Markdown` 渲染完整内容。
- 根治中间态闪烁（0.26.0 全量重解析），无需升级依赖。

### 子决策 B：工具 UI 按调用顺序展示（问题 2）

- `syncToolMessages` 将 assistant 占位 + tool result **插入到 aiId 之前**（而非追加末尾），使 `_messages = [user, assistant占位, tool, aiId(最终文本)]`。
- 同时修复协议结构：filteredHistory 中 tool 前必是带 tool_calls 的 assistant（解决问题 4 架构性乱序）。

### 子决策 C：搜索中文质量（问题 3）

- Bing RSS 请求新增 `language=zh-cn` + `cc=cn` 参数（限定中文搜索，避免分词收窄）。
- 新增 `decodeCharset`：按响应 Content-Type 声明的 charset 解码响应体（缺省 UTF-8），避免 GBK 乱码。

### 子决策 D：tool_calls 完整性保护（问题 4）

- **L1 滑动窗口边界保护**：`SlidingWindowMemoryManager.adjustWindowBoundary` 若窗口首条为 TOOL（无前置 tool_calls），向前扩展窗口纳入前置 assistant。
- **孤儿 tool 防御**：`ConversationViewModel.dropOrphanToolMessages`（companion 纯函数）丢弃所有无法配对到前置 assistant(tool_calls) 的 TOOL 消息（防御会话恢复丢失 toolCalls）。

**一句话**：流式期间纯文本渲染 + 工具消息插入 aiId 前（UI 与协议双层修复）+ 搜索中文限定 + tool_calls 配对完整性保护。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| markdown：升级到 0.42+（StreamingMarkdownState） | 原生增量解析 | 0.28+ 依赖 Compose 1.7+ ABI，项目 BOM 2024.06.00=Compose 1.6.8 运行期崩溃（libs.versions.toml 已记录约束），不可行 |
| markdown：渲染节流（buffer 累积 + debounce） | 降低重组频率 | 延迟展示文本，交互违和；流式期间纯文本方案更直接 |
| 工具 UI：按 id 排序调整 | 简单 | id 序列本身已错乱（aiId 先于占位创建），需重构消息序列而非排序 |
| 搜索：切换 DuckDuckGo/Google | 质量可能更好 | 国内不可达（ADR-020 已确认），否决 |
| tool_calls：仅清理孤儿 tool | 简单 | 未覆盖 L1 窗口切分路径，400 仍可触发 |

## 后果（Consequences）

- 正面：
  - 流式期间无 markdown 中间态闪烁（问题 1 根治）
  - 工具调用/思考按真实时序展示（问题 2）
  - 中文搜索质量提升 + 编码防御（问题 3）
  - tool_calls 配对完整，三条 400 路径均被拦截（问题 4）
- 负面 / 代价：
  - 流式期间纯文本渲染，markdown 语法（如代码块、表格）在回答完成前不渲染（可接受，完成即切换）
  - `syncToolMessages` 插入逻辑增加消息操作复杂度（subList 拼接）
  - `adjustWindowBoundary` 可能使窗口略超 windowSize（最多 +1，防御成本）
- 需要同步更新的文档或代码：
  - `docs/decisions/README.md` + `README.md` 索引新增 ADR-025
  - `docs/behavioral-rules.md` 沉淀（tool_calls 配对完整性 + 流式渲染策略）
  - 测试：PhaseDTest 消息顺序断言更新、SlidingWindowMemoryManagerTest 边界用例、WebSearchLocalToolExecutorTest 中文用例

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 流式纯文本渲染改变 markdown 体验 | 低 | 仅流式期间临时纯文本；完成即切换 markdown，用户感知为"先出文本后美化" |
| syncToolMessages 插入改变既有测试断言 | 低 | 同步更新 PhaseDTest 等消息顺序断言 |
| adjustWindowBoundary 死循环 | 低 | `expanded.size <= recent.size` break 防死循环 + 纯函数可测 |
| dropOrphanToolMessages 误删配对 tool | 低 | 状态机严格按 assistant(tool_calls)→tool 配对；user 边界重置 |
| Bing 中文搜索仍可能分词不佳 | 低 | language/cc 参数已尽力；code 侧无更多可控项 |

## 参考

- mikepenz/multiplatform-markdown-renderer issue #315/#420/#501（不支持增量解析）
- openclaw#90597 + semantic-kernel#8419 + agentscope-java#1042（tool_calls 关联 400 同类根因与修复）
- Bing URL 编码调研（language/cc 参数 + charset 处理）
- [考古报告 2026-08-15-uxr5-archaeology.md](../reports/2026-08-15-uxr5-archaeology.md)
