# ADR-023: UX 三次反馈修复（键盘真机 IME/工具审批三模式/知识库内容查看/消息编辑复制/工具禁用）

> 解决用户第三轮真机测试反馈的 14 个问题（UXR3 反馈）。核心矛盾：**前两轮 ADR-021/022 修复后，功能「基本可用」但仍有若干深层缺陷在真机上显现**：键盘遮挡三次修复未果（真机 IME 叠加 padding）、DeepSeek 原生思考模型开关不生效、RAG 首份资料必入上下文、工具权限无分级等。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-15 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-021 UX 修复](ADR-021-ux-issue-fixes.md)、[ADR-022 UX 二次修复](ADR-022-ux-r2-markdown-tool-mcp-fixes.md)、[ADR-020 深度思考 + 联网搜索](ADR-020-thinking-and-web-search.md)、[ADR-012 RAG 对话集成](ADR-012-m3-rag-conversation-integration.md)、[ADR-011 知识库管理 UI](ADR-011-m3-knowledgebase-ui.md)、[ADR-014 M4 tool_calling](ADR-014-m4-toolcalling-interface.md) |
| 上游调研 | MIUI 键盘 IME 与 edge-to-edge insets 叠加问题（多次真机复现）；DeepSeek reasoner 思考模式参数语义；RAG 相似度阈值业务调优 |
| 风险等级 | P2（跨模块：IME 布局 + 工具执行链 + 配置仓库 + UI 交互） |

## 背景（Context）

用户第三轮真机测试反馈 14 个问题，其中若干为**前两轮修复后的残留缺陷**，且多数仅在真机 / 特定模型 / 特定数据状态下复现：

1. **键盘遮挡仍存在**（问题 1）：主页面呼出键盘后输入文本框位置过高，遮挡部分聊天内容；相较上次修复位置无变化，三次修复未果 —— 真机（MIUI）与模拟器行为差异。
2. **400 Tool names must be unique 再次出现**（问题 2）：提示调用文本改写 skill 后请求被拒；新对话后正常 —— 同轮并行调用同名工具导致 assistant.tool_calls 重名。
3. **深度思考开关不生效**（问题 3）：所有对话均为深度思考模式，即使关闭开关仍深度思考 —— deepseek-reasoner 原生思考模型固定返回 reasoning_content。
4. **知识库 UI 残留**（问题 4）：新对话后知识库 UI 仍有残留。
5. **联网搜索不可用 + 性能回退**（问题 5）：提示「联网搜索识别，请检查网络连接或 Provider 配置」。
6. **RAG 首份资料必入上下文**（问题 6）：打开知识库检索功能后，无论是否需要，第一份资料必定被塞入对话上下文 —— 相似度阈值过低。
7. **UI 状态矛盾**（问题 7）：已贴出引用来源但下方仍显示「知识库检索中」。
8. **list_directory / directory_tree 工具出错**（问题 8）：向 LLM 提问知识库资料时，文件系统工具执行出错。
9. **LLM 输出疑似截断**（问题 9）：某些提问输出不完整，出现一行一个词/字 —— 需核查是否存在截断。
10. **工具权限未分级**（问题 10）：应分三模式（手动审批 / 自动审批 / 禁用），切换按钮放设置。
11. **Fetch MCP 工具无工具可用**（问题 11）：界面测试连接显示无任何可用工具。
12. **知识库资料无法直接查看**（问题 12）：管理不便，需补充内容查看功能。
13. **消息编辑/复制缺失**（问题 13）：LLM 输出与用户提问均需编辑与复制功能。
14. **按 CLAUDE.md 流程修复 + 审查测试**（问题 14）。

### 根因考古结论（2026-08-15，真机 + 模拟器复现 + 源码分析）

| 问题 | 根因 |
| --- | --- |
| 键盘遮挡（1） | edge-to-edge + Scaffold 默认 `contentWindowInsets(systemBars)` 时，键盘弹出后 Scaffold 的 innerPadding 会错误叠加 navigationBar/ime padding 到 content，而聊天页底部又有 `imePadding()` → **双重 padding** 导致输入框被顶得过高（模拟器不触发、MIUI 真机触发）。修复：Scaffold 关闭自动 insets（`WindowInsets(0,0,0,0)`），由 PrismNavBar 底部 `imePadding()` 单一来源处理，ConversationScreen 底部输入区不再单独 imePadding |
| 400 工具重名（2） | LLM（deepseek-reasoner）一轮内可能并行声明同名工具多次（不同 call id）。原样回放 assistant.tool_calls 出现重复 function name → DeepSeek 严格校验返回 400。修复：`completedToolCalls.distinctBy { it.toolName }` 去重（保留首个），tool result 回灌仅针对保留调用 |
| 深度思考开关（3） | `deepseek-reasoner` 原生思考模型无论是否传 thinking 参数都返回 `reasoning_content`。此前仅在请求侧控制参数，未在**解析侧**过滤。修复：`chunkToEvents` 增加 `collectReasoning` 参数，开关关闭时丢弃 reasoning delta |
| 知识库 UI 残留（4） | `startNewConversation` 未重置 RAG 检索目标状态；新对话沿用旧会话的 RAG 状态/引用来源。修复：新对话重置 `_ragTarget = AllLibraries` |
| 联网搜索不可用（5） | 与问题 2 的 400 关联（工具名重复导致整个请求被拒，联网搜索随之失败）；另有 `isNullOrBlank` 换行过滤引入的性能/解析影响。修复：跟随 2/9 的根因修复；联网搜索执行器独立降级 |
| RAG 首份资料必入（6） | 相似度阈值 0.3 过低：库中仅有的片段（无论与问题是否相关）都会命中并注入上下文。修复：阈值 0.3 → 0.5，过滤低相关片段 |
| UI 状态矛盾（7） | `TypingIndicator` 仅按 RAG 是否开启显示「检索中」，未感知检索已完成。修复：AI 消息已携带引用来源（RAG 完成）时显示「正在生成回答…」 |
| 文件系统工具出错（8） | 未授权任何目录时，目录/文件工具因 roots 为空抛 IOException → 泛化「工具执行出错」。修复：未授权根目录时返回明确引导文案（`hasAuthorizedRoots` 前置检查） |
| LLM 输出截断（9） | 流式 delta 过滤用 `isNullOrBlank()`，纯换行 `"\n"` 被丢弃导致 markdown 源文本粘连（标题/列表/表格无法解析）；排查确认输出本身无截断，属解析渲染层问题。修复：改用 `isNullOrEmpty()` 保留结构字符 |
| 工具权限未分级（10） | 此前无明确三模式；工具权限「没有实际划分」。修复：新增 `ToolApprovalMode`（MANUAL/AUTO/DISABLED）+ DataStore 配置仓库 + 设置 UI 三选一 + SkillExecutor 按模式分派 |
| Fetch 无工具（11） | `LocalMcpToolProvider` 未实现 Fetch 工具（此前宣称本地内置零配置但无实际实现）。修复：实现本地 Fetch 工具（URL 校验/HTML 剥离/长度截断），复用 searchHttpClient |
| 知识库资料无法查看（12） | 知识库管理 UI 仅支持删除/移动，无内容查看。修复：`KnowledgeBaseRepository.getDocumentContent` + ViewModel 委托 + 管理弹层「查看」按钮 + 内容展示弹层 |
| 消息编辑/复制缺失（13） | 消息气泡无操作能力。修复：用户/AI 消息增加「复制」（剪贴板）；用户消息增加「编辑」（回填输入框 + 替换重发） |

## 决策（Decision）

### 子决策 A：键盘 IME 单一来源（关闭 Scaffold 自动 insets + NavBar imePadding）

- `PrismApp`：Scaffold `contentWindowInsets = WindowInsets(0,0,0,0)`，关闭自动 content insets。
- `PrismNavBar`：底部 `imePadding()` + `windowInsetsPadding(navigationBars)`，键盘弹出时导航栏上移。
- `ConversationScreen`：底部输入区**不再**单独 `imePadding()`（避免与 NavBar 叠加）。
- **理由**：IME padding 必须单一来源，双重 padding 是 MIUI 真机输入框过高的根因；模拟器因 IME 行为差异未复现。

### 子决策 B：工具名去重（400 Tool names must be unique）

- `SkillExecutor.executeLoop`：`completedToolCalls.distinctBy { it.toolName }` 去重（保留首个）。
- 后续 assistant 占位消息与 tool result 回灌仅针对保留的调用，保证与 OpenAI 协议一一对应。

### 子决策 C：深度思考开关解析侧过滤

- `OpenAICompatibleProvider.chunkToEvents(collectReasoning)`：开关关闭时丢弃 reasoning delta。
- 请求侧仍按 ADR-020 控制 thinking 参数；解析侧兜底过滤 `deepseek-reasoner` 原生思考模型固定返回的 reasoning_content。

### 子决策 D：新对话重置 RAG 状态

- `ConversationViewModel.startNewConversation`：`_ragTarget = RagTarget.AllLibraries`，清除残留的 RAG 状态/引用来源 UI。

### 子决策 E：RAG 相似度阈值 0.3 → 0.5

- `ConversationViewModel.RAG_SIMILARITY_THRESHOLD` 由 0.3 提至 0.5。
- **理由**：all-MiniLM-L6-v2 COSINE 相似度分布中，0.3 过低导致无关片段也命中；0.5 过滤低相关片段，只有足够相关的资料才进入 context 与引用来源。

### 子决策 F：工具审批三模式（MANUAL / AUTO / DISABLED）

- 新增 `ToolApprovalMode` 枚举 + `ToolApprovalConfigRepository`（DataStore，`prism_tool_approval`）。
- `SkillExecutor` 增加 `approvalModeProvider`（默认 null → MANUAL，向后兼容）：
  - MANUAL：非白名单工具每次调用询问（白名单 `web_search__search` 免审批，ADR-021 既有优化保留）
  - AUTO：所有工具直接放行
  - DISABLED：返回「工具调用已禁用」文案（纵深防御）
- `ConversationViewModel`：DISABLED 模式不向 LLM 注入任何工具定义（tools 为空走普通对话分支）。
- `SettingsScreen`：新增「工具权限」设置行 + 三选一弹层，运行时即时生效。

### 子决策 G：文件系统工具未授权引导

- `FilesystemMcpServer.execute`：未授权根目录（`!hasAuthorizedRoots()`）且工具非 `list_allowed_directories` 时返回明确文案「未授权任何目录，请先在能力页选择授权目录」，替代泛化错误。

### 子决策 H：流式 delta 保留结构字符（isNullOrEmpty）

- `OpenAICompatibleProvider.chunkToEvents`：`isNullOrBlank()` → `isNullOrEmpty()`，保留 `"\n"` 等结构字符。

### 子决策 I：Fetch MCP 工具实现

- `LocalMcpToolProvider`：实现 `fetch` 工具（URL http/https 校验 + HTML 标签剥离 + maxLength 截断 100..10000），复用 `searchHttpClient`。

### 子决策 J：知识库文档内容查看

- `KnowledgeBaseRepository.getDocumentContent(kbId, docTitle)`：按 chunkIndex 升序拼接文档全文。
- `KnowledgeBaseViewModel.getDocumentContent`：委托。
- `KnowledgeBaseScreen.ManageDocumentsSheet`：文档行增加「查看」按钮 + `DocumentContentSheet` 内容展示弹层。

### 子决策 K：消息编辑与复制

- `ConversationScreen`：用户/AI 消息气泡下方增加「复制」（ClipboardManager）；用户消息额外「编辑」（回填输入框 + 标记待编辑 id）。
- `ConversationViewModel.editUserMessageAndResend`：替换用户消息内容 + 截断其后消息 + 重新发起回答（提取 `launchAnswer` 复用主流程，不重复追加用户消息）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 键盘：ConversationScreen 继续单独 imePadding | 改动最小 | 与 Scaffold innerPadding 叠加导致真机输入框过高（三次修复均失败），否决 |
| 键盘：整列 imePadding | 键盘弹出整体上移 | 顶栏/Provider 胶囊随之移动，输入框相对位置仍过高（ADR-022 已否决） |
| RAG：完全关闭时注入空 context | 逻辑简单 | 用户明确要求「打开检索但无关时不注入」，阈值过滤更符合语义，否决 |
| 工具权限：仅加自动审批开关 | 改动小 | 用户明确要求三模式（手动/自动/禁用），缺「禁用」不满足，否决 |
| 消息编辑：仅复制不编辑 | 实现简单 | 用户明确要求「编辑与复制」两者都提供，否决 |

## 后果（Consequences）

- 正面后果：
  - 键盘 IME padding 单一来源，真机输入框位置回归正常（待真机验证）。
  - 400 工具重名、深度思考开关、RAG 首份资料、UI 状态矛盾、文件工具错误、Fetch 缺失等问题根治。
  - 工具权限三模式满足用户安全/便利分级诉求。
  - 知识库内容可查看、消息可编辑/复制，管理体验提升。
- 负面后果 / 代价：
  - 新增 3 个配置文件（ToolApprovalMode / ToolApprovalConfigRepository / 测试），维护面略增。
  - RAG 阈值 0.5 可能过滤部分中低相似度但有价值的片段（权衡：宁缺毋滥，防污染优先）。
  - 键盘修复依赖真机验证（模拟器无法完全复现 IME 行为）。
- 需要同步更新的文档或代码：
  - `docs/decisions/README.md` ADR 索引
  - `README.md` 文档索引
  - `docs/behavioral-rules.md`（如适用）
  - `app/objectbox-models/default.json`（无实体变更，无需更新）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 键盘修复在 MIUI 真机仍不理想 | 高 | 待真机验证；若仍异常，需进一步分析 MIUI 导航栏叠加（可能需 windowSoftInputMode 调整） |
| RAG 阈值 0.5 过滤过度 | 中 | 阈值可配置化（预留），按真机测试反馈调优 |
| AUTO 审批模式安全风险（工具自动执行） | 中 | 默认 MANUAL；AUTO 仅在用户显式开启时生效；白名单机制保留；设置弹层含风险提示（L-2） |
| DISABLED 模式仍被 LLM 硬编码调用 | 低 | SkillExecutor + FilesystemMcpServer 双纵深防御返回禁用文案 |
| Fetch SSRF（CWE-918） | 中 | `isPublicHttpUrl` 拒绝回环/私有/链路本地/解析失败地址 + Content-Length 预检（guardrail M-1 补强） |
| 消息编辑重发历史一致性 | 低 | 替换 + 截断原子 CAS（BR-concurrency-004）；编辑时禁止并发生成 |

## 参考

- 本会话全部修复源码：`app/src/main/java/io/prism/**`（PrismApp / PrismNavBar / ConversationScreen / ConversationViewModel / SkillExecutor / OpenAICompatibleProvider / FilesystemMcpServer / LocalMcpToolProvider / KnowledgeBaseRepository / SettingsScreen / ToolApprovalMode / ToolApprovalConfigRepository）
- 测试：`app/src/test/java/io/prism/**`（ToolApprovalConfigRepositoryTest / SkillExecutorTest / ConversationViewModelPhaseDTest / ConversationViewModelTest / KnowledgeBaseRepositoryTest / FilesystemMcpServerEdgeCaseTest / LocalMcpToolProviderFetchTest / OpenAICompatibleProviderTest）

## guardrail 复审记录（2026-08-15，第二轮）

首轮 guardrail 结论「有条件通过」，强制项与关键项已全部修复：

| 项 | 修复内容 |
|---|---|
| M-3（强制） | `isToolsDisabled` 改显式 try-catch 重抛 CancellationException（BR-error-handling-007） |
| M-2（强制） | `FilesystemMcpServer` 增加 `approvalModeProvider`：AUTO 跳过确认、DISABLED 拒绝、MANUAL/null 走门禁，AUTO 语义「所有工具直放」在文件工具上也成立；PrismApplication 注入 |
| M-4 | `PrismApp` Scaffold content Box 补 `statusBarsPadding()`（单一来源），防 5 页顶栏被状态栏遮挡 |
| M-5 | `PrismNavBar` 改 `windowInsetsPadding(ime.union(navigationBars))`，避免键盘弹出时导航栏高度双重计数 |
| M-1 | Fetch `isPublicHttpUrl`：拒绝回环/私有/链路本地/解析失败地址（fail-closed）+ Content-Length 预检 |
| T-1 | 新增 `LocalMcpToolProviderFetchTest`（9 用例：scheme/回环/私有网段/公网放行/解析失败/集成） |
| T-2 | PhaseDTest 补「编辑重发含 toolCalls 历史」用例，断言第二轮历史无过期 toolCalls |
| T-3 | ConversationViewModelTest 补 similarity=0.4（≥旧0.3 <新0.5）边界用例，断言被过滤 |
| T-4 | OpenAICompatibleProviderTest 补 collectReasoning=false/true 对照用例 |
| T-5 | SkillExecutorTest 补 executeLoop 同名工具去重 + 不同名保留用例 |
| T-6 | FilesystemMcpServerEdgeCaseTest 补未授权根目录引导文案 4 用例 |
| L-2 | SettingsScreen AUTO 选项追加风险提示 |
| L-4 | ADR 记录编辑重发 L2/L3 缓存边界（会话级缓存既有语义，影响有限） |
| L-7 | ToolApprovalConfigRepository KDoc 修正（非法字符串防御在读取侧） |

修复后全量回归 **1678 测试 0 失败 0 错误**。待重新提交 guardrail 复审确认。M-4/M-5 仍需真机验证（状态栏遮挡 + 3 键导航键盘空隙）。
