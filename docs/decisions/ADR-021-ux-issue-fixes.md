# ADR-021: 用户体验问题修复（键盘/渲染/能力开关/折叠展示/审批放宽/知识库管理/历史会话）

> 解决用户真机测试反馈的 13 个体验问题（UX-001），核心为「体验从『能用』到『好用』的差距修复」。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-14 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-020 深度思考 + 联网搜索](ADR-020-thinking-and-web-search.md)、[ADR-012 M3 RAG 集成](ADR-012-m3-rag-conversation-integration.md)、[ADR-008 M3 知识库模型](ADR-008-m3-knowledgebase-model.md)、[ADR-014 M4 tool_calling](ADR-014-m4-toolcalling-interface.md)、[ADR-016 M6 跨 App](ADR-016-m6-cross-app-integration.md) |
| 上游调研 | 主流 AI 手机助手（Kimi/DeepSeek/豆包/ChatGPT 手机端）交互设计调研（键盘/渲染/历史/开关/思维链/引用来源 6 维度） |
| 风险等级 | P2（跨模块：UI 重构 + 新数据实体 + 工具审批策略调整） |

## 背景（Context）

用户真机测试反馈 13 个体验问题，核心矛盾：**项目功能完备（RAG/MCP/Skills/记忆/工具调用），但交互层与主流 AI 手机助手差距明显**。按问题归因：

1. **键盘遮挡输入框 / 不自动滚动**（问题 1）：已有 `imePadding` + `adjustResize`，但消息列表不自动滚动到底部（全 `ui/` 无 `scrollToItem` 调用），长对话停留在旧位置。
2. **知识库无法上传 PDF / 文本入库 / 管理**（问题 2）：PDF 解析代码已存在（真机 `LinkageError` 已文档化 Bug-4）；无纯文本入库 API；无文档级删除/移动。
3. **markdown 不渲染**（问题 3）：AI 消息用 `stripMarkdownSymbols` **剥离**符号（保留内容丢弃格式），用户仍看到 `*-` 裸符号。
4. **无历史会话页**（问题 4）：消息纯内存态（无 ObjectBox 实体），`startNewConversation` 仅清空内存。
5. **能力开关缺失**（问题 5）：深度思考开关在设置页，联网搜索恒启用（`webSearchEnabled=true` 硬编码），无聊天页快捷开关。
6. **引用来源与正文重叠**（问题 6）：RAG `SourceChip` 在正文下方平铺，视觉易混淆。
7. **思维链混入正文且逐词换行**（问题 7）：`ReasoningDelta` 以 `\n[思考]` 前缀追加进 `content`，视觉与答案无法区分。
8. **搜索结果为纯文本**（问题 8）：联网搜索 TOOL 结果以玻璃气泡展示原始文本，无折叠、无点击跳转。
9. **工具审批过于严格**（问题 9）：所有工具（含联网搜索）都需逐次人工确认，体验差。

## 决策（Decision）

### 子决策 A：消息列表自动滚动到底部（问题 1）

- `ConversationScreen` 新增 `LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length)`，监听消息数量与流式内容长度变化，调用 `listState.animateScrollToItem(messages.size - 1)` 自动滚底。
- 对齐 ChatGPT/DeepSeek 手机端「新内容始终可见」行为；保留 `imePadding` 键盘适配。

### 子决策 B：AI 消息改用 Markdown 渲染（问题 3）

- 引入 `com.mikepenz:multiplatform-markdown-renderer-m3:0.15.0`（Compose Multiplatform 原生，与 Material3 集成，支持标题/列表/代码块/表格/行内代码；活跃维护至 2026-08，Apache 2.0）。
- 版本 0.15.0 对应 Compose 1.6.x（匹配项目 Compose BOM 2024.06.00），避免依赖冲突。
- `AiBubble` 内 `Markdown(content = message.content, modifier = ...)` 渲染正文。
- **删除** `stripMarkdownSymbols` 及其 10 个测试（转为 `parseSearchResults` 测试，见子决策 F）。

### 子决策 C：能力开关置于输入框上方（问题 5）

- `ConversationScreen` 新增 `CapabilityToggleRow`：输入框上方一横排胶囊（联网搜索 / 深度思考），选中态 `PrismIndigo` 高亮（对齐 DeepSeek/Kimi 手机端开关摆放）。
- `ConversationViewModel` 新增：
  - `thinkingEnabled: StateFlow<Boolean>`（init 从 `ThinkingConfigRepository` 读取，`setThinkingEnabled` 持久化）
  - `webSearchEnabledFlow: StateFlow<Boolean>`（`setWebSearchEnabled` 切换）
- `sendMessage` 改用 `_webSearchEnabled.value` 与 `_thinkingEnabled.value`（替代硬编码/仓库直读）。

### 子决策 D：思维链 / 引用 / 搜索来源折叠展示（问题 6/7/8）

- `ChatMessage` 新增 `thinkingChain: String?` + `searchResults: List<SearchResult>?` 字段。
- **问题 7**：`ReasoningDelta` 不再追加到 `content`，改为 `appendThinkingDelta` 追加到 `thinkingChain`（独立字段）；`AiBubble` 渲染 `CollapsibleThinkingCard`（「深度思考」折叠卡片，默认收起，灰色小字，置于答案上方——对齐 DeepSeek 两段式）。
- **问题 6**：`CollapsibleSourcesCard`（「引用来源（N）」折叠区域）替代平铺 `SourceChip`。
- **问题 8**：`CollapsibleSearchCard`（「参考来源（N）」折叠卡片），每条含编号+标题+域名+摘要，整行 `Intent.ACTION_VIEW` 打开系统浏览器跳转外部网站。
- `syncToolMessages` 解析 `web_search__search` TOOL 结果 → 结构化 `SearchResult` 列表（`Companion.parseSearchResults` 纯函数）附加到 AI 消息。

### 子决策 E：工具审批放宽（问题 9）

- `SkillExecutor` 新增 `isTrustedTool(toolName)` + `TRUSTED_TOOL_WHITELIST`。
- 白名单仅含 `web_search__search`（只读、高频率、无副作用）→ 免审批直接执行。
- **fail-closed**：未知工具 / 跨 App 打开分享 / 文件系统 / MCP 工具一律仍需用户确认（纵深防御，防止无感执行有副作用操作）。

### 子决策 F：知识库文本入库 + 文档管理（问题 2）

- `IngestionPipeline.ingestText(documentTitle, text, knowledgeBaseId)`：跳过文件解析，文本直接切片→嵌入→入库（支持「只输入文字保存」）。
- `KnowledgeBaseRepository` 新增：
  - `listDocuments(kbId)`：按 chunk title 聚合文档标题（去重）
  - `deleteDocument(kbId, title)`：单事务删除文档所有 chunk（原子性）
  - `moveDocument(srcKbId, title, targetKbId)`：单事务移动文档到其他库
- `KnowledgeBaseViewModel` 新增 `startTextIngestion` / `listDocuments` / `deleteDocument` / `moveDocument`。
- UI：`ImportSheet` 增加「文本笔记」入口；`TextNoteSheet`（标题+内容+目标库）；库卡点击进入 `ManageDocumentsSheet`（文档列表 + 删除 + 移动到其他库）。

### 子决策 G：历史会话持久化（问题 4）

- 新增 `Session` ObjectBox 实体（title / messagesJson / createdAt / updatedAt）。
- 新增 `SessionRepository`（CRUD + `sessions` StateFlow 按 updatedAt 倒序）。
- `ChatMessage` / `Citation` / `ToolCallRef` / `SearchResult` / `Role` 标记 `@Serializable`；新增 `ChatMessageSerializer`（`ignoreUnknownKeys=true` + `encodeDefaults=true`）。
- `ConversationViewModel` 注入 `sessionRepository`：`startNewConversation` / `loadSession` / `onCleared` 时 `persistSession()`（保存或更新）。
- 历史页 `ConversationListScreen`（重建）：会话列表（自动标题 + 相对时间 + 删除），点击经 `pendingSessionId` 回调 → 聊天页 `loadSession` 恢复；聊天顶栏新增「历史会话」按钮。

**一句话**：以「对齐主流 AI 手机助手交互」为纲，在保留既有 RAG/MCP/Skills/记忆架构的前提下，补齐键盘滚底、Markdown 渲染、能力开关、折叠展示、审批白名单、知识库文档管理、历史会话持久化 7 项体验修复。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| Markdown 渲染：Android Markwon 库 | 功能全 | 非 Compose 原生需 `AndroidView` 包装，与 Compose 集成差；Compose 原生库更契合项目技术栈 |
| 思维链：继续 `[思考]` 前缀混入正文 | 零改动 | 视觉与答案无法区分（问题 7 根因），无法折叠 |
| 历史会话：仅内存 + 记忆系统复用 | 零新实体 | L2 记忆是摘要非完整对话，无法回溯查看（问题 4 诉求是「查找历史对话记录」） |
| 审批放宽：全部工具免审批 | 体验最顺 | 跨 App 打开/文件写入等有副作用操作无感执行，安全风险不可接受 |
| PDF 上传：强制在线解析 | 真机稳定 | 违背「零后端」定位，引入外部依赖 |
| 知识库管理：仅支持删除 | 实现简单 | 无法满足「改变放在哪个知识库位置」诉求（问题 2 明确要求移动） |

## 后果（Consequences）

### 正面

- 对齐主流 AI 手机助手交互范式：键盘滚底、Markdown 渲染、输入框上方能力开关、思维链/引用/搜索折叠展示、历史会话、搜索来源可点击。
- 工具审批从「全量人工」降为「仅高风险需确认」，体验显著提升。
- 知识库从「只进不出」升级为「可文本入库 + 可删除 + 可移动」完整管理闭环。

### 负面 / 风险

- **新增 ObjectBox 实体（Session）**：ObjectBox 模型变更需生成器重新生成（编译期自动处理），已存在数据库 schema version 1 自动升级，无迁移成本。
- **新增依赖（markdown-renderer）**：R8 混淆需验证 keep 规则（debug 构建已验证，release 需在 ac-verifier 阶段验证）。
- **ChatMessage 序列化**：新增字段带默认值（向后兼容），`ignoreUnknownKeys` 容错未来演进。
- **思维链字段增长**：长思考过程会增大消息体，受 L1 滑动窗口（默认保留近期 N 轮）约束，体量可控。
- **UI 文件增大**：`ConversationScreen` / `KnowledgeBaseScreen` 新增多个 Composable，文件行数上升（可维护性观察项）。

### 兼容性

- 所有新增 ViewModel 参数均带默认值（`sessionRepository=null` 等），向后兼容既有测试。
- `buildTools` / `mergeSystemPrompt` 等纯函数签名未变。
- `Session` 实体新增不影响既有 8 实体。

## 后续（Follow-up）

- release 构建验证 markdown-renderer R8 keep 规则。
- 真机验证 PDF 上传（Bug-4 LinkageError 是否仍触发）。
- 历史会话页「重命名 / 批量删除 / 搜索」增强（对齐 Kimi/DeepSeek，当前为 MVP 版本）。
