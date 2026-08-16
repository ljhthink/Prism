# ADR-020: 深度思考 + 联网搜索能力（问题 8 修复）

> 解决用户真机测试反馈问题 8：「LLM 缺失联网搜索和深度思考的功能」。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-14 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-014 M4 LLM tool_calling 接口扩展](ADR-014-m4-toolcalling-interface.md)、[ADR-016 M6 跨 App 调用架构](ADR-016-m6-cross-app-integration.md)、[ADR-018 M8 集成与发布架构](ADR-018-m8-release-architecture.md) |
| 上游调研 | WebSearch 调研（Bing RSS 无 Key 端点实测 + DuckDuckGo 被墙确认）、DeepSeek V4 官方文档（thinking / reasoning_effort 参数） |
| 风险等级 | P2（跨模块：ChatStreamProvider 接口扩展 + 新本地工具 + 新 DataStore 配置） |

## 背景（Context）

用户真机测试反馈：LLM 无法联网搜索、无法深度思考。要求补充实现。

两个能力均受制于现有架构约束：

1. **深度思考（Deep Thinking）**：Prism 是 BYOK 多端点（OpenAI / Claude / Ollama / DeepSeek 等）。
   DeepSeek V4 支持 `thinking={"type":"enabled"}` + 平级 `reasoning_effort`（low/high/max）开启思考模式，
   流式响应先输出 `delta.reasoning_content`（思考过程）后输出 `delta.content`（最终答案）。
   但 `thinking`/`reasoning_effort` 是 DeepSeek 专有参数，发到不兼容端点会返回 400。
   因此必须**默认关闭、用户显式开启**，开启时才注入这两个字段。

2. **联网搜索（Web Search）**：要求「本地内置、零配置」（用户对 MCP 工具的核心诉求）。
   - DuckDuckGo 实测国内不可达（被墙），否决
   - 需 Key 的 Bing/Google/Brave API 违背零配置，否决
   - **Bing RSS 端点** `https://cn.bing.com/search?q=<q>&format=rss` 实测国内可访问、无需 Key、
     返回结构化 RSS XML（title/link/description），是最佳方案

## 决策（Decision）

### 深度思考（子决策 A）

- 新建 `ThinkingConfigRepository`（独立 DataStore `prism_thinking_config`），持久化 `enabled`（默认 false）+ `reasoning_effort`（默认 high）
- `ChatStreamProvider.streamChat` / `ChatCompletionProvider.chatCompletion` 新增可选参数 `thinkingEnabled: Boolean?` + `reasoningEffort: String?`（默认 null，向后兼容）
- `ChatCompletionRequest` 新增 `thinking: JsonElement?` + `reasoning_effort: String?`（null 不序列化）
- `Delta` 新增 `reasoning_content` 解析，`chunkToEvents` 发射独立 `StreamEvent.ReasoningDelta`（思考过程与最终答案区分）
- UI：SettingsScreen「模型与端点」分组新增「深度思考」开关 + 思考强度选择弹层（low/high/max）
- 后台任务（摘要 / 偏好抽取）默认不开启（`chatCompletion` 不带 thinking），控制成本

### 联网搜索（子决策 B）

- 新建 `WebSearchLocalToolExecutor`（实现 `LocalToolExecutor` 接口），工具名 `web_search__search`（`web_search__` 命名空间）
- 参数：`query`（必需）+ `maxResults`（1..8，默认 5）；通过 Ktor GET Bing RSS 端点 + 正则解析 + HTML 实体解码
- 新建 `CompositeLocalToolExecutor`（Composite 模式），组合 M6 跨 App（`cross_app__*`）+ 联网搜索（`web_search__*`），注入 `SkillExecutor.localToolExecutor`
- `ConversationViewModel.buildTools` 新增 `webSearchEnabled` 参数（默认 false 向后兼容既有测试），生产 `Factory` 传 true 启用
- 专属 HTTP client `searchHttpClient`（无 SSE 插件 + HttpTimeout 10s），复用 `downloadHttpClient` 模式

**一句话**：深度思考作为 Provider 请求参数（默认关闭、按 Provider 能力显式开启），联网搜索作为本地内置工具（Bing RSS 零配置），两者复用既有 M4/M6 工具与流式链路。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 深度思考：`ProviderConfig` 实体加字段按 Provider 配置 | 按端点灵活 | 改动 ObjectBox 实体 + 迁移 + 编辑 UI，影响面大（P2/P3）；全局开关语义更简单 |
| 深度思考：模型名自动检测（含 reasoner/thinking） | 零配置 | 模型名无统一约定，不可靠；无法覆盖用户切换模型场景 |
| 联网搜索：DuckDuckGo Instant Answer API | JSON 解析简单 | 国内被墙实测不可达；中文/长尾查询结果差（非完整 SERP） |
| 联网搜索：DuckDuckGo HTML 端点 | 完整 SERP | 国内被墙；HTML 解析复杂且反爬 |
| 联网搜索：Brave/Google/Bing 官方 API | 质量高 | 需 API Key / 计费，违背「本地内置零配置」定位 |

## 后果（Consequences）

- 正面：
  - 深度思考默认关闭，完全不向不兼容端点发送 thinking 参数（向后兼容所有 Provider）
  - 联网搜索零配置，LLM 始终可感知并调用 `web_search__search`
  - 思考过程通过 `ReasoningDelta` 独立展示，与最终答案区分（UI 以 `[思考]` 前缀累积）
  - 复用 M4 tool_calling / M6 本地工具链路，无新第三方依赖
- 负面 / 代价：
  - Bing RSS 非完整 SERP，长尾/中文查询可能结果有限（已知局限，工具 description 已说明）
  - 深度思考开启后思考 token 占比高（DeepSeek V4 特性），可能压缩正文空间（弹层 UI 已明示）
  - `ChatStreamProvider` / `ChatCompletionProvider` 接口新增可选参数，所有测试 fake 需同步签名
- 需要同步更新的文档或代码：
  - `docs/decisions/README.md` 索引新增 ADR-020
  - `README.md` 文档索引新增 ADR-020 引用
  - `docs/behavioral-rules.md` 沉淀（如 ReasoningDelta 密封类穷尽匹配规则）
  - 4 个新文件 + 8 个修改文件（Provider / SkillExecutor / ConversationViewModel / Settings / PrismApplication）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 深度思考开启后不兼容端点返回 400 | 中 | 默认关闭 + UI 明示兼容性限制；错误回灌给 LLM 提示检查 Provider |
| Bing RSS 反爬（403/429）或网络不可达 | 中 | 降级为描述性错误文案回灌 LLM；10s 超时 + 30s 外层兜底 |
| reasoning_content 混入工具调用解析 | 低 | `chunkToEvents` 先发射 ReasoningDelta 再处理 content/tool_calls，两者独立 |
| 新增接口参数破坏测试 fake | 低 | 所有参数带默认值（null/false），既有调用零改动；测试 fake 同步签名 |
| Bing 返回 HTML 实体/脚本注入 | 低 | `decodeHtmlEntities` 仅白名单实体 + `stripHtmlTags` 去标签 + 200 字符截断 |

## 参考

- DeepSeek V4 思考模式官方文档：`thinking={"type":"enabled"}` + `reasoning_effort`（low/high/max）
- Bing RSS 端点实测：`https://cn.bing.com/search?q=<q>&format=rss`（国内可访问、无需 Key）
- DuckDuckGo 国内不可达实测（WebFetch 失败 + 网络调研确认被墙）
- 既有复用：M4 tool_calling 状态机（ADR-014）、M6 本地工具分支（ADR-016）、downloadHttpClient 独立 client 模式（ADR-013）
