# 搜索命中与 Fetch 反爬优化 PRD（v1 批次15）

> 承接已批准调研报告：`docs/reports/2026-09-02-search-fetch-enhancement-research.md`（用户已审批，含两项决策：搜索侧引擎 = 智谱 + 博查 + Tavily + SearXNG 自建；open-webSearch/火山方舟/腾讯云 wsa 不做）。
> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-09-02 |
| 作者 | 主 Agent |
| 关联文档 | [调研报告](reports/2026-09-02-search-fetch-enhancement-research.md)、ADR-020/023/032/033/038~041、docs/prd-open-box-api-mcp-enhancement.md（US-002 蓝图承接） |
| 风险等级 | P2（跨模块：本地工具引擎扩展 + 新依赖 + WebView + MCP 模板） |

## 1. 背景与目标

用户痛点：① web_search 命中率不高；② Fetch 常被反爬限制（CF 盾/JS 渲染/登录墙 → 403/429/503/空壳）；③ 无法访问外网；④ 引用来源混乱。

现有根因（考古结论）：Bing/Baidu 服务端分词/消歧不可控、HTML SERP 正则解析脆弱、正则提纯漏提误提、无 JS 执行能力、引用对齐缺强约束。

## 2. 执行范围（已批准）

### 批次 A（P0，搜索引擎收敛 + 端侧收益）

| # | 改造 | 内容 |
|---|---|---|
| A1 | 智谱 web_search REST 引擎 | `web_search__search` engine 分支 `web_search__zhipu`（POST `https://open.bigmodel.cn/api/paas/v4/web_search`，Bearer，apiKeyRef=zhipu）；request_id/search_source/搜索结果集 `search_result[][title/link/content/media_name]` 解析；engine 参数（auto/std 等按用户配置默认 std） |
| A2 | Tavily REST 引擎 | `web_search__tavily`（POST `https://api.tavily.com/search`，Bearer，apiKeyRef=tavily）；`results[][title/url/content]` 解析；海外引擎仅配 Key 启用 |
| A3 | Bocha 现状维持 | 已集成不动；429 分支复用 isRateLimitError；无代码变更（配额真机核实项） |
| A4 | Readability4J 提纯换库 | 依赖 Jsoup + Readability4J（Apache-2.0）；`extractReadableText` 改 Readability 算法，正则版保留为解析失败降级兜底 |
| A5 | 引用来源强约束 | 全引擎结果带编号注入（`[N] title — url`）；systemPrompt 增「引用必须使用编号来源的原始 URL，禁止拼凑/改写/编造 URL」 |
| A6 | 搜索失败语义增强 | 无 Key 且引擎全不相关/失败时返回可诊断引导（建议配置智谱/博查/Tavily Key），前置 `错误：` failure 标记 |

### 批次 B（P1，端侧降级链增强）

| # | 改造 | 内容 |
|---|---|---|
| B1 | WebView 渲染抓取（fetch 第三级降级） | Ktor 直抓判空壳/403/503 → offscreen WebView（自定义 Chrome UA + JS 渲染 + 15s 超时）→ evaluateJavascript 取 DOM → Readability4J 提纯回灌；仅 https 公网 URL（复用 isPublicHttpUrl）；默认关闭 + 设置页开关；失败静默降级返回原诊断文案 |
| B2 | SearXNG 自定义搜索引擎 | 设置页「自定义搜索端点」（URL + 可选 Basic Auth，DataStore 持久化）；GET `<endpoint>?q=&format=json&language=zh-CN` 解析 `results[][title/url/content]`；优先级：Bocha → 智谱 → SearXNG → Tavily → Bing/Baidu 兜底；附自建教程 runbook（Docker + settings.yml search.formats 加 json + 大陆引擎清单） |

### 批次 C（P1/P2，进阶用户可选）

| # | 改造 | 内容 |
|---|---|---|
| C1 | Scrapling / crawl4ai 远程 MCP 模板 | `McpServerPresets` 新增两模板（Streamable HTTP，端点 `http://<PC-IP>:<port>/mcp` 留待用户填写）；meta 含 description/keyHint/networkNote（本地局域网）；架构红线：中转返回渲染后正文/Markdown，禁止 cookie 回传复放 |

### 非目标（明确不做）

- open-webSearch、火山方舟联网插件、腾讯云 wsa、Jina s.jina.ai、Exa/Serper/SerpAPI（用户决策/否决）
- FlareSolverr/Byparr、firecrawl 自建、Scrapegraph-ai、maxun（抓取侧否决）
- 不替换 Bing/Baidu 零配置兜底；不把任何 Key 硬编码入库
- AGPL/GPL 项目（SearXNG 等）仅独立服务网络调用，不链接进分发产物

## 3. 用户故事与验收标准

### US-1501: 智谱 web_search REST 引擎（A1）

- 验收标准：
  - [ ] 新增 `web_search__zhipu` 本地工具（或 engine 分支），端点固定 `https://open.bigmodel.cn/api/paas/v4/web_search`，POST JSON（search_query/search_engine/search_result_count ≤50/recency），Bearer Key（apiKeyRef=zhipu）。
  - [ ] 未配置 Key 返回「请到 bigmodel.cn 注册获取 Key」引导（前置 `错误：`），不崩溃、不阻断其余引擎。
  - [ ] 响应解析：`search_result[]` → SearchItem(title/link/content)；非 2xx/解析失败/网络异常 → 降级下一引擎；429 可诊断（复用 isRateLimitError）。
  - [ ] 结果仅回灌 LLM 文本（【外部内容】不可信边界）；Key 不落日志。
  - [ ] 单测：MockEngine 解析 / 无 Key 降级 / URL 固定无 SSRF。
  - [ ] Typecheck 通过。

### US-1502: Tavily REST 引擎（A2）

- 验收标准：
  - [ ] 新增 `web_search__tavily`，端点固定 `https://api.tavily.com/search`，POST JSON（query/max_results/search_depth），Bearer Key（apiKeyRef=tavily）。
  - [ ] 未配置 Key 返回引导文案；解析 `results[][title/url/content]`；失败降级；429 可诊断。
  - [ ] 单测：MockEngine 解析 / 无 Key 降级 / URL 固定。
  - [ ] Typecheck 通过。

### US-1503: 引用编号强约束（A5）

- 验收标准：
  - [ ] `formatSearchResult`（及各引擎格式化路径）输出 `[N] title — url` 编号来源清单，编号与条目一一对应。
  - [ ] systemPrompt（搜索工具描述或 mergeSystemPrompt 联网指引段）增约束：「引用外部信息必须使用编号来源中的原始 URL，禁止拼凑/改写/编造 URL；未列出的 URL 不得出现在引用中」。
  - [ ] 单测：格式化输出含编号且 URL 与条目对齐。
  - [ ] Typecheck 通过。

### US-1504: 搜索失败语义增强（A6）

- 验收标准：
  - [ ] 所有结构化引擎均未配置 Key 且 Bing/Baidu 兜底无强相关结果时，返回 `错误：` 前缀引导文案（列出可配置引擎与配置入口），替代「搜索失败：未找到相关网页结果」死胡同；部分引擎失败仍按既有降级链。
  - [ ] 单测：无 Key 全不相关 → 引导文案；有 Key 引擎失败 → 降级不引导误导。
  - [ ] Typecheck 通过。

### US-1505: Readability4J 提纯换库（A4）

- 验收标准：
  - [ ] `libs.versions.toml`/build.gradle 新增 jsoup + readability4j（Apache-2.0），R8 keep 规则如需则补。
  - [ ] `LocalMcpToolProvider.extractReadableText` 改为 Jsoup 解析 + Readability4J 正文提取；Readability 失败/空结果 → 回退现正则版（保留为降级）。
  - [ ] `isAntiBotOrEmpty` 判定链保持：提纯后非空才判有效。
  - [ ] 单测：article 型/论坛型/含 script 噪声页样本提纯非空且含正文关键词；正则降级兜底触发。
  - [ ] Typecheck 通过 + APK 体积增量记录。

### US-1506: WebView 渲染抓取第三级降级（B1）

- 验收标准：
  - [ ] Fetch 直抓返回 403/503/空壳时（且设置开关 `webviewFetchEnabled` 默认 false 已开启），用 offscreen WebView 加载目标 URL：仅 https 公网（复用 isPublicHttpUrl）、自定义 Chrome UA、15s 超时、主线程外调度。
  - [ ] 渲染后取 `documentElement.outerHTML` → Readability4J 提纯 → 非空才回灌（前置【外部内容】）；失败/超时 → 返回原可诊断文案（行为向后兼容）。
  - [ ] WebView 进程隔离崩溃不影响主进程（默认渲染进程崩溃策略）；无 cookie 外泄到日志。
  - [ ] 单测：开关关闭不触发；URL 校验拒绝非公网；MockWebServer 渲染链路至少集成 1 例（Robolectric）。
  - [ ] Typecheck 通过。

### US-1507: SearXNG 自定义搜索引擎（B2）

- 验收标准：
  - [ ] 设置页新增「自定义搜索端点」配置（URL + 可选 Basic Auth 用户名/密码，DataStore 持久化）；URL 校验 http(s) 且非内网 IP 字面量（用户显式配置口径，允许局域网地址但 UI 提示）。
  - [ ] 引擎链插入：Bocha → 智谱 → SearXNG → Tavily → Bing/Baidu；SearXNG GET `<endpoint>/search?q=&format=json&language=zh-CN`，解析 `results[][title/url/content]`；失败降级下一引擎。
  - [ ] 未配置端点完全跳过该引擎（零行为变化）。
  - [ ] 单测：MockEngine 解析 / 未配置跳过 / 失败降级 / Basic Auth 头注入。
  - [ ] 新增 `docs/runbooks/searxng-selfhost.md`：Docker Compose + settings.yml search.formats 加 json（默认关闭否则 403）+ 大陆可达引擎启用清单（bing/baidu/sogou/360/chinaso）。
  - [ ] Typecheck 通过。

### US-1508: Scrapling / crawl4ai 远程 MCP 模板（C1）

- 验收标准：
  - [ ] `McpServerPresets` 新增：Scrapling（端点占位 `http://<PC-IP>:11235/mcp`，keyHint「无需 Key；Scrapling 默认端口 11235，端点为用户自建 Scrapling 服务地址」）、crawl4ai（`http://<PC-IP>:11235/mcp` 占位 + keyHint 说明 crawl4ai 默认 8000 端口/Docker 部署/可选 JWT）；networkNote 标注「本地局域网自建服务」。
  - [ ] 模板 description 说明架构红线语义（服务端返回渲染后正文/Markdown）。
  - [ ] 单测：findByName 命中 + 元数据完整。
  - [ ] Typecheck 通过。

## 4. 非功能需求

- 性能：新增引擎复用 30s 预算护栏（hasRequestBudget）与请求超时；引擎链短路（首个成功即返回，不强刷全部）。
- 安全：Key 全部 ApiKeyRepository 加密、不落日志；端点固定常量（SearXNG 端点为用户显式配置）；结果前置【外部内容】仅回灌文本；WebView 仅公网 https + 默认关；日志 sanitizeUrlForLog。
- 兼容：API 26+；引擎链向后兼容（未配任何 Key/端点时行为与现状完全一致）。
- 回归：`:app:testDebugUnitTest` 0 失败 + lint 0 errors。

## 5. 验收标准汇总（供 ac-verifier）

| 验收项 | 方法 | 通过标准 | US |
|---|---|---|---|
| 智谱引擎 | 单测 | 解析/降级/无 Key 引导/URL 固定 | 1501 |
| Tavily 引擎 | 单测 | 解析/降级/无 Key 引导 | 1502 |
| 引用编号 | 单测 | 编号与 URL 对齐 + systemPrompt 约束存在 | 1503 |
| 失败引导 | 单测 | 无 Key 全不相关 → 引导；有 Key 失败 → 降级 | 1504 |
| 提纯换库 | 单测 | 样本提纯非空 + 正则兜底 + 体积增量记录 | 1505 |
| WebView 降级 | 单测/Robolectric | 开关关不触发/公网校验/失败向后兼容 | 1506 |
| SearXNG 引擎 | 单测 + runbook | 解析/跳过/降级/Basic Auth + 教程文档 | 1507 |
| MCP 模板 | 单测 | findByName + 元数据 | 1508 |
| 全量回归 | testDebugUnitTest + lint | 0 失败 / 0 errors | 全部 |

## 6. 风险与依赖

| 风险 | 等级 | 缓解 |
|---|---|---|
| 智谱/Tavily 响应结构与文档漂移 | 中 | 解析容错（缺字段跳过）+ 真机补测 |
| WebView 指纹被 CF 识别/Turnstile 交互 | 中 | 定位为「尽力而为第三级」，失败返回原诊断文案；真机 PoC（3 CF + 3 JS 渲染站） |
| Readability4J 老版本兼容 | 低 | 保留正则兜底；样本单测 |
| SearXNG 上游引擎风控（云 IP） | 低 | runbook 标注家宽部署建议 |
| 新依赖 APK 增量 | 低 | R8 评估 + 记录 |
