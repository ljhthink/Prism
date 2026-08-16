# ADR-022: UX 二次反馈修复（Markdown 渲染稳定性/工具名规范化/换行保留/MCP 状态真实性）

> 解决用户第二轮真机测试反馈的 10 个问题（UX-001 二次反馈）。核心矛盾：**上一轮 ADR-021 修复后，功能「可用但仍有体验缺陷」，且暴露出依赖版本兼容性、MCP 工具名合法性、流式换行丢失等深层根因**。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-15 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-021 UX 修复](ADR-021-ux-issue-fixes.md)、[ADR-020 深度思考 + 联网搜索](ADR-020-thinking-and-web-search.md)、[ADR-014 M4 tool_calling](ADR-014-m4-toolcalling-interface.md)、[ADR-006 Filesystem MCP](ADR-006-filesystem-mcp-server.md) |
| 上游调研 | markdown-renderer 版本与 Compose 1.6/1.7 ABI 兼容性实测（0.28.0 起引入 Compose 1.7 `Composer.startReplaceGroup`/`TextLinkStyles`）；OpenAI/DeepSeek 工具名合法字符规范 |
| 风险等级 | P2（跨模块：依赖版本 + 工具名契约 + Provider delta 解析） |

## 背景（Context）

用户第二轮真机测试反馈 10 个问题，其中 4 个揭示**上一轮修复的深层缺陷**：

1. **Markdown 渲染仍异常**（问题 1）：AI 输出出现裸 `#`/`-`/`|` 符号、标题字号过大、整体局促；且**发送 markdown 回复时应用崩溃**（模拟机复现）。
2. **深度思考开关失效**（问题 2）：所有对话均为深度思考模式，开关无法关闭。
3. **键盘遮挡仍存在**（问题 3）：主页面呼出键盘后输入框位置过高。
4. **知识库 UI 误显示**（问题 4）：无论是否引用知识库，界面均出现知识库 UI。
5. **LLM 请求被拒绝 400**（问题 5）：`Tool names must be unique`——多次对话后重发信息也被拒，伴随白屏切换至能力页。
6. **本地 MCP 工具不可用**（问题 6）：仅 Time 正常，Sequential Thinking 明确不可用。
7. **工具调用可视化不足**（问题 7）：Skills/MCP 调用情况用户无感知。
8. **MCP 连接状态虚假**（问题 8）：无论是否可用均显示「连接成功」。

### 根因考古结论（2026-08-15，模拟机复现 + 字节码实证）

| 问题 | 根因 |
| --- | --- |
| markdown 崩溃（1） | ADR-021 引入 0.15.0 渲染正常但符号残留；升级 0.37.0 后**运行期崩溃**：0.31.0 起字节码引用 Compose 1.7+ ABI（`TextLinkStyles` 类 / `Composer.startReplaceGroup` 方法），项目 Compose BOM 2024.06.00 = **1.6.8**，编译期通过、运行期 `ClassNotFoundException` / `NoSuchMethodError`。0.30.0 仍引用 `startReplaceGroup`；**0.26.0 是兼容 Compose 1.6.8 的最高版本**（逐版本 AAR 字节码扫描实证） |
| 符号残留/标题过大（1） | **流式换行丢失**：`OpenAICompatibleProvider.chunkToEvents` 用 `isNullOrBlank()` 过滤 delta，纯换行 `"\n"` 的 `isBlank()==true` 被丢弃 → markdown 源文本粘连成单行，标题/列表/表格无法被解析。修复为 `isNullOrEmpty()` |
| 深度思考开关（2） | ViewModel init 异步读取 DataStore 与用户点击竞态：init 完成用旧值覆盖用户切换。修复：`thinkingToggledByUser` 标记 + 开关补 `toggleable(role=Switch)` 无障碍语义 |
| 400 工具重名（5）+ Sequential Thinking 不可用（6） | MCP 工具名 `mcp_<server.name>__<tool>` 直接拼接原始 server 名：`Sequential Thinking`（含空格）、`跨 App 调用`（中文）生成**非法工具名**，被 `isLegalToolName`（`[a-zA-Z0-9_-]`）过滤 → LLM 感知不到该工具；且多个同名 server 生成重复工具名 → 400 |
| MCP 连接状态虚假（8） | `testConnection`/`observeConnectionStatus` 仅以「listTools 不抛异常」判定成功，本地未实现工具（Fetch/Memory/跨 App）返回空列表仍显示成功 |
| 键盘遮挡（3） | `imePadding` 作用于整列导致顶栏随键盘上移；改为仅作用于底部输入区，消息列表 `weight(1f)` 自动收缩 |

## 决策（Decision）

### 子决策 A：markdown-renderer 锁定 0.26.0（兼容 Compose 1.6.8）

- **版本约束**：0.28.0 起字节码引用 Compose 1.7+ ABI（`Composer.startReplaceGroup` / `TextLinkStyles`），与项目 Compose BOM 2024.06.00（1.6.8）不兼容，运行期崩溃。**0.26.0 是兼容的最高版本**。
- 依据：逐版本下载 AAR 解包 `classes.jar` 二进制扫描 `TextLinkStyles` / `startReplaceGroup` 字符串，确认 0.26.0 无引用、0.28.0 起有引用。
- **后续升级路径**：若要升级 markdown-renderer ≥ 0.28，必须同步升级 Compose BOM ≥ 2024.09.00（Compose 1.7+），属独立 ADR（P3 变更）。
- `gradle/libs.versions.toml` 注释记录该约束，防止未来盲目升级回归崩溃。

### 子决策 B：流式 delta 保留结构字符（isNullOrEmpty 而非 isNullOrBlank）

- `OpenAICompatibleProvider.chunkToEvents`：`content`/`reasoning_content` 从 `isNullOrBlank()` 改为 `isNullOrEmpty()`。
- **理由**：纯换行 delta（`"\n"`）是 markdown 结构字符，被 `isBlank()` 误判为「空白」丢弃，导致流式文本粘连、块级解析失效。仅过滤 `null` 与空串。
- 测试同步更新：`parseChunk preserves newline delta for markdown structure` 新增。

### 子决策 C：MCP 工具名命名空间规范化

- `SkillExecutor.toMcpNamespace(serverName)`：非 `[a-zA-Z0-9]` 字符替换为 `_`（如 `Sequential Thinking` → `Sequential_Thinking`）。
- 构造侧（`ConversationViewModel` MCP 工具合并）与反查侧（`SkillExecutor.selectMcpServer`）使用同一函数，保证工具名合法且可反解回原始 Server。
- 配合既有 `distinctBy { it.function.name }` 去重 + `isLegalToolName` 过滤 + `McpServerRepository.createFromPreset` 查重，彻底消除「400 Tool names must be unique」。

### 子决策 D：深度思考开关竞态修复 + 无障碍语义

- `ConversationViewModel`：`thinkingToggledByUser` 标记，用户手动切换后 init 异步读取不再覆盖。
- `CapabilityToggleChip`：`toggleable(value, role = Switch)` 暴露选中态语义，UI Automator/读屏可识别开关状态。

### 子决策 E：MCP 连接状态真实性

- `CapabilitiesViewModel.testConnection` / `observeConnectionStatus`：工具列表为空 → `Fail`（「连接成功但无可用工具」/「连接失败」），不再误导为成功。
- `CapabilitiesScreen`：本地内置 Server 也观测连接状态（此前仅远程观测），已启用 Server 显示「已连接·N」或「连接失败」。

### 子决策 F：键盘 IME 作用域修正

- `ConversationScreen`：`imePadding()` 仅作用于底部输入区（能力开关 + 输入栏），消息列表 `weight(1f)` 自动收缩；顶栏/Provider 胶囊不再随键盘上移。

### 子决策 G：工具调用可视化

- `ConversationViewModel`：`activeTool` StateFlow，`ToolCallStart` 置位、`Done`/`Error` 清除。
- `ConversationScreen`：`ToolCallIndicator` 展示「正在调用工具: xxx」（对齐 Claude Code 工具进度模型）。

### 子决策 H：去除头像与气泡容器

- `AiBubble` 移除头像与气泡背景，直接渲染 Markdown 正文 + 可折叠思维链/引用/搜索来源，为 LLM 输出腾出空间（对齐用户诉求）。

**一句话**：以「真机验证驱动的二次修复」为纲，修复 markdown 渲染崩溃（版本锁定 + 换行保留）、MCP 工具名合法性（命名空间规范化）、深度思考开关竞态、MCP 状态真实性、键盘 IME 作用域，并补充工具调用可视化与无障碍语义。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| markdown-renderer 升级 0.37.0 + Compose BOM 升 1.7 | 保留 0.37 渲染能力 | 升级 Compose 1.7 影响全局（material3/foundation ABI），破坏性大、验证成本高；0.26.0 已满足渲染需求 |
| 流式换行：UI 层事后重建 `\n` | 不动 Provider | 无法区分「服务端真实换行」与「语义空白」，会引入错误结构；应在源头保留结构字符 |
| MCP 工具名：改用 server.id 做命名空间 | 天然唯一 | 工具名失去可读性，`selectMcpServer` 需查库；`toMcpNamespace` 规范化更简单且保留语义 |
| 键盘：整列 imePadding | 实现简单 | 顶栏随键盘上移，输入框相对位置过高（问题 3 根因） |

## 后果（Consequences）

### 正面

- Markdown 渲染稳定（无崩溃）+ 正确分层（标题/列表/表格独立节点，无裸符号）。
- MCP 工具名合法化 → Sequential Thinking / 跨 App 调用等工具可被 LLM 感知与路由。
- 深度思考开关可真实切换；MCP 连接状态真实反映可用性。
- 键盘不再遮挡输入框。

### 负面 / 风险

- **markdown-renderer 锁 0.26.0**：无法享受 0.28+ 的渲染改进（表格行内内容等），需后续在升级 Compose BOM 的独立 ADR 中一并处理。
- **`toMcpNamespace` 规范化**：中文 server 名全量替换为 `_`（如 `跨 App 调用` → `__App___`），工具名可读性下降（但对用户不可见，仅协议层）。
- **`isNullOrEmpty` 保留空白 delta**：极端情况下空格 delta 也会进入消息体，体量可控（受 L1 滑动窗口约束）。

### 兼容性

- `chunkToEvents` 行为变更（保留空白 delta）：同步更新 3 个受影响测试，全量回归通过。
- `SkillExecutor` 新增 `toMcpNamespace`（internal，向后兼容），`selectMcpServer` 匹配逻辑扩展（对每个 server.name 规范化后比较）。
- 其余 ViewModel/UI 参数均为增量扩展，向后兼容既有测试。

## 后续（Follow-up）

- release 构建验证 markdown-renderer 0.26.0 R8 keep 规则。
- 升级 Compose BOM ≥ 1.7 的独立 ADR（届时可解锁 markdown-renderer ≥ 0.28）。
- 真机验证全部 10 项修复（用户待执行）。
