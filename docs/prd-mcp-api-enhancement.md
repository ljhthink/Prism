# MCP/API 能力增强 · 产品需求文档（PRD）

> 针对用户环境"12 个预设远程 MCP 模板因网络问题无法注册"的现状，筛选国内可用/开箱即用的 API 与 MCP 工具，对现有功能做增强优化。
> 前置调研：`docs/reports/2026-08-20-mcp-search-api-alternatives-research.md`（含 6 模板国内替代对照表）。
> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-08-20 |
| 作者 | 主 Agent（方案待用户确认后执行） |
| 关联文档 | 调研报告 docs/reports/2026-08-20-mcp-search-api-alternatives-research.md（一次性工件，不入库）、ADR-029（O2/O3/O5 MCP 模板）、[ADR-001](decisions/ADR-001-prism-tech-stack.md) |
| 风险等级 | P2（跨模块：新增 MCP 模板 + 本地工具 + 网络能力） |

## 1. 背景

用户真机环境网络问题导致 12 个预设远程 MCP 模板中多数（GitHub/Notion/Slack/Asana/Brave/Exa/Firecrawl/n8n/TrendsMCP 等海外端点）无法注册。经两轮调研（continuous-learning-kb → public-apis 仓库 → 网络核验）确认：海外 SERP API 在目标网络下同样不可用，需引入**国内可直连**或**开箱即用**的替代能力。

项目现状（源码探查确认）：

- **网络搜索**：内置 `web_search__search`（Bing HTML SERP + Baidu 兜底，零配置），中文实体命中仍时有不足；无独立 API Key 搜索源。
- **网页抓取**：内置 `fetch`（`LocalMcpToolProvider`，SSRF 纵深防御），但无 JS 渲染/反爬能力；Firecrawl 模板海外不可达。
- **热榜/趋势**：TrendsMCP 模板海外不可达，无本地替代。
- **预设模板**：12 个远程模板海外端点为主，国内环境大部分不可用。

## 2. 目标与非目标

- 目标：
  1. 新增**博查 Bocha 远程 MCP 模板**（国内可直连、DeepSeek 官方搜索、AI 原生带引用），增强搜索命中与引用质量。
  2. 新增**今日热榜本地工具**（聚合微博/知乎/百度/抖音/头条等中文热榜），替代 TrendsMCP 的"跨平台热榜"用途。
  3. 新增**Jina Reader 抓取增强**（`r.jina.ai/<url>` 零配置 URL→Markdown），补 Fetch 的 JS 渲染/反爬短板。
  4. **标注海外远程模板国内不可用**，移除已无免费档的 Brave 模板，降低用户试错成本。
- 非目标（明确不做）：
  - 不引入 mcp-trends-hub（stdio 传输，Android 端无法接入 npx 子进程）。
  - 不替换现有 Bing+Baidu 零配置搜索主通道（作为兜底保留）。
  - 不新增飞书 MCP 模板（需企业级远程配置，超出本批范围，列后续迭代）。
  - 不把任何 Key 硬编码入库（继续 Keystore 加密存储）。

## 3. 用户故事与验收标准

### US-001: 新增博查 Bocha 远程 MCP 模板（搜索增强）

- 作为 用户，我希望在「能力 → MCP → 模板」中一键添加"博查 Bocha"，填入 API Key 即可用 AI 搜索（带引用、语义重排），以便在中文实体/时效问题上获得比 Bing+Baidu 兜底更准的命中。
- 验收标准：
  - [ ] `McpServerPresets` 新增 "Bocha" 远程模板：`baseUrl = https://mcp.bocha.cn/mcp`（Streamable HTTP），`apiKeyRef = bocha`，认证头按博查开放平台规范注入（支持自定义 header，参考 Context7 先例）。
  - [ ] 模板元数据 `McpPresetMeta` 含 description + keyHint（到 open.bocha.cn 获取 API Key，DeepSeek 官方搜索）。
  - [ ] `McpClientManager` 能以 StreamableHttpClientTransport 连接并 `tools/list` 出 `bocha_web_search` / `bocha_ai_search`（新增单测：MockEngine 握手 + 工具列表）。
  - [ ] 连接失败/无 Key 时返回可诊断文案（区分"未填 Key"与"网络不可达"），不崩溃。
  - [ ] Typecheck 通过。

### US-002: 新增今日热榜本地工具（替代 TrendsMCP 热榜用途）

- 作为 用户，我希望在对话中让 AI 查询"今天微博/知乎/抖音热榜"，以便快速掌握全网热点（中文平台），替代海外 TrendsMCP。
- 说明：mcp-trends-hub 为 stdio（npx）传输，Android 端无法运行子进程；改用其数据同源的**今日热榜官方 REST API**（tophubdata.com，国内直连、结构化 JSON）封装为本地工具，复用现有 `compositeLocalToolExecutor` 注入链。
- 验收标准：
  - [ ] 新增本地工具 `hotlist__get`（或 `hotlist__trending`）：入参 `platform`（微博/知乎/百度/抖音/头条等）+ `limit`，返回平台热榜条目（标题/链接/热度）。
  - [ ] 端点固定 `https://api.tophubdata.com/`，Key 经 `ApiKeyRepository` 加密读取并注入 `Authorization` header（不硬编码、不落日志）。
  - [ ] 未配置 Key 时返回"请在设置中填写今日热榜 API Key"引导文案，不崩溃。
  - [ ] 结果仅回灌 LLM 文本（前置【外部内容】不可信边界），不进入抓取/Intent/WebView sink；请求有超时 + 预算护栏。
  - [ ] 单测：MockEngine 解析返回结构化条目；无 Key/网络失败降级；URL 固定无 SSRF。
  - [ ] Typecheck 通过。

### US-003: Jina Reader 抓取增强（补 Fetch JS 渲染/反爬短板）

- 作为 用户，我希望在 Fetch 抓取被 JS 渲染/反爬拦截时，能自动用 Jina Reader（`r.jina.ai/<url>`）转出干净 Markdown，以便读取现代网页内容。
- 说明：Jina Reader 免 Key 开箱即用（20 RPM），`https://r.jina.ai/<url>` 直接返回 Markdown，对标 Firecrawl 的 scrape 核心用途。
- 验收标准：
  - [ ] `LocalMcpToolProvider` 的 Fetch 工具新增可选参数 `useJinaReader`（或独立 `fetch_reader` 工具），`r.jina.ai/<url>` 拼接 URL 编码后 GET。
  - [ ] 默认不开启（保持现有 Fetch 主路径 + SSRF 纵深防御）；开启时仍过 SSRF 校验（仅允许公网 URL，`isPublicHttpUrl` 复用）。
  - [ ] 返回内容截断至 1MB 上限 + 日志不落完整 URL query（复用 `sanitizeUrlForLog`）。
  - [ ] 单测：URL 编码正确、公网校验、超时/失败降级到普通 Fetch。
  - [ ] Typecheck 通过。

### US-004: 海外远程模板标注 + 移除 Brave

- 作为 用户，我希望在模板列表中看到海外模板的"国内网络可能不可用"提示，且不再看到已无免费档的 Brave，以免反复试错。
- 验收标准：
  - [ ] `McpPresetMeta` 对海外远程模板（GitHub/Notion/Slack/Asana/Exa/Firecrawl/n8n/TrendsMCP 等）追加 `networkNote = "海外端点，国内网络可能不可用"`，UI 展示在模板卡片上。
  - [ ] 从 `McpServerPresets` 移除 Brave 模板及元数据（免费档 2025 底已取消）。
  - [ ] 新增单测：移除后 `findByName("Brave")==null`；海外模板元数据含 networkNote。
  - [ ] Typecheck 通过。

## 4. 非功能需求

- 性能：搜索/热榜/抓取均复用现有请求预算护栏（`hasRequestBudget` 30s 总预算）；热榜单次请求 ≤5s 超时。
- 安全：
  - API Key 全部经 `ApiKeyRepository`（Tink AEAD + Keystore）加密，不硬编码、不落日志。
  - 所有外部 URL 走 SSRF 校验（`isPublicHttpUrl` 拒绝内网/回环/云元数据）；热榜端点固定，无用户可控 host。
  - 结果文本均前置【外部内容】不可信边界，仅回灌 LLM，不进抓取/Intent/WebView sink。
  - 日志脱敏：URL 经 `sanitizeUrlForLog`，不落完整 query/userinfo。
- 可观测性：新增工具调用均走现有 `SkillExecutionRecord` / 工具卡片展示；失败输出可诊断文案（区分缺 Key / 网络 / 限流）。
- 兼容性：Android 8.0+（API 26+），零新增第三方依赖（复用 Ktor/OkHttp/DataStore）。
- 隐私：Key 不出设备（仅存 Keystore）；搜索/热榜/抓取为外部服务，符合既有 BYOK 数据外发模型。

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| 博查 MCP 端点认证头格式可能与 `Authorization: Bearer` 默认不同 | 中 | McpServerConfig 已支持自定义 headers（Context7 先例）；先实测握手 |
| 今日热榜 API 需注册 Key（非零配置） | 中 | 属"质量高可接受远程配置"；未填 Key 引导文案 |
| Jina Reader 免 Key 有 20 RPM 速率限制 | 低 | 作为可选增强（默认关），仅用户需要时启用 |
| 新增工具增加工具 schema 数量，潜在 token 开销 | 低 | 工具按需注入（US-002/003 可配置开关） |
| 博查/热榜为外部服务，可用性依赖网络 | 中 | 均有降级：搜索回退 Bing+Baidu，热榜失败返回中性文案 |

## 6. 里程碑

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| M1: 博查模板 | US-001 全部 AC + 回归 | P1 |
| M2: 热榜本地工具 | US-002 全部 AC + 回归 | P1 |
| M3: Jina Reader 增强 | US-003 全部 AC + 回归 | P1 |
| M4: 模板标注+移除 Brave | US-004 全部 AC + 回归 | P0 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| 博查模板可添加/连接 | 单测 + 模拟器 | tools/list 出 bocha 工具 | US-001 |
| 热榜工具可查中文平台热榜 | 单测（MockEngine） | 返回结构化条目 | US-002 |
| Jina Reader 可转 Markdown | 单测（MockEngine） | URL 编码 + 公网校验 | US-003 |
| 模板标注/移除 Brave | 单测 | findByName(Brave)==null + networkNote | US-004 |
| 全量回归 | `:app:testDebugUnitTest` | 0 失败 | 全部 |

## 8. 待确认事项

- [x] 搜索增强选型：博查 Bocha（用户已确认）
- [x] 热榜替代选型：用户选 mcp-trends-hub，但 stdio 无法 Android 接入 → **调整为今日热榜 REST API 封装本地工具**（用户已确认此调整）
- [x] 抓取增强选型：Jina Reader（用户已确认）
- [x] 今日热榜 API 与 mcp-trends-hub 的数据源等价性确认（落地以官方 API 文档为准，两段请求 nodes → nodes/`<hashid>`）
- [x] 博查 MCP 端点认证 header 实测确认（Bearer + Streamable HTTP；落地以嵌入式握手测试验证协议路径，真实端点待真机补测）

> 2026-08-19 用户已确认本 PRD 并进入实施。实施结果：全量回归 2319 用例 0 失败 + APK 构建成功 + guardrail TKN-V1B8-MCP-ENHANCE-001 有条件通过（M-1/M-2/M-3 已修复）。
