# v1 批次9 真机问题修复 + 开箱即用 API/MCP 落地 · 产品需求文档（PRD）

> 依据真机手动测试 8 项问题反馈 + 两轮调研（tech-selection-researcher 网络调研 + code-archaeologist 源码考古）整合的本批次需求。
> 前置调研：`docs/reports/2026-08-20-*`（搜索/Fetch 开源方案选型 + 4 链路考古）+ `docs/prd-open-box-api-mcp-enhancement.md`（US-001~006）。
> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-08-20 |
| 作者 | 主 Agent |
| 关联文档 | [prd-open-box](prd-open-box-api-mcp-enhancement.md)、ADR-034~040、调研报告 |
| 风险等级 | P2（跨模块：搜索/Fetch/SSE/MCP 模板/新工具） |

## 1. 背景与根因（真机日志证据 + 考古确认）

用户真机手动测试（2026-08-20 凌晨）报告 8 项问题。经日志分析（prism_20260820_045508.log / 021454.log）+ code-archaeologist 考古 + tech-selection-researcher 调研，逐项根因如下：

| # | 问题 | 根因（证据） | 证据位置 |
|---|---|---|---|
| B1 | Fetch+Jina 不可用 | `r.jina.ai` 域名**国内不可达**（ConnectTimeoutException / ConnectException 连接 108.160.170.44:443 失败），非"响应过大" | 日志 L33553/48381/50143 |
| B2 | 搜索命中能力不足 | `filterRelevantItems`/`isRelevant` 纯**子串匹配**：城市百科页摘要含「梧州市第一中学」子串→判相关→救援链（核心词重试+百度兜底）**短路**；核心词唯一候选==query 时空转 | 日志 L33518-33536；考古 H1/H3 |
| B3 | 热榜工具未出现在 MCP 工具 | 注册链完整但 **systemPrompt 未声明热榜能力**，LLM 感知仅靠工具定义描述；未配 Key 引导文案非 failure 标记 | 考古 H1/H2 |
| B4 | 手机操控偶发「网络请求失败」 | SSE `status=-1`（连接中断/无 response）落 `mapHttpError` else 分支→通用文案；`status=200 空 body`（协议不匹配）也落 else | 日志 L26390/27384；考古 SSE H1 |
| B5 | 移除 6 个海外模板 | 明确需求：Slack/Asana/Brave/Exa/Firecrawl/TrendsMCP（Brave 已移除，实际再移 5 个） | 用户清单 |
| B6 | 搜索失败后截断 LLM 输出 | SSE `status=200` 空流/半截内容被当**成功** markCompleted → 静默空气泡/截断，无提示无重试 | 考古 SSE B1/B2 |
| B7 | Fetch 反爬严重 | OkHttp **TLS/JS 指纹不可伪造**，头伪装仅降 403 不能根治；200 空壳（SPA）被 `isAntiBotOrEmpty` 误判；Jina 不可达 → 无有效兜底 | 考古 Fetch H1/H2 |
| B8 | 按 prd-open-box 规划优化 | US-001~006（Bocha REST / 智谱 REST / 和风天气 / 百度翻译 / 国内 MCP 模板 / 海外模板维护） | prd-open-box |

## 2. 目标与非目标

- 目标：
  1. 搜索：相关性判据升级（title 强相关）+ 核心词空转修复 + 百度兜底强化 + Bocha REST 可配引擎（US-001）
  2. Fetch：本地 HTML→Markdown 提纯（jsoup 或轻量正则）+ Jina 自动降级 + 反爬诊断增强
  3. 热榜：systemPrompt 能力声明 + 未配 Key failure 标记 + 注册集成测试
  4. SSE：mapHttpError 状态分支扩展（-1/200/5xx 可诊断）+ 空流/半截提示
  5. 模板：移除 6 个海外模板 + 元数据
  6. 新工具：智谱 REST（US-002）+ 和风天气（US-003）+ 百度翻译（US-004）
  7. 国内 MCP 模板（US-005）+ 海外模板维护（US-006）
- 非目标：
  - 不引入 stdio MCP / 自建服务器（SearXNG/crawl4ai/jina-ai reader 自托管均为 P3 长期项，本次不做）
  - 不引入 curl_cffi / WebView 渲染兜底（TLS 指纹在 Android 端无等价方案；WebView 留长期）
  - 不替换现有 Bing+Baidu 零配置主通道（作为兜底保留）
  - 不接入付费 MCP / 不硬编码 Key

## 3. 用户故事与验收标准

### US-901: 搜索相关性判据升级（B2 根治）

- 作为 用户，我希望搜索"梧州市第一中学"能命中学校官网而非城市百科，以便获得准确资料。
- 根因：纯子串匹配放行城市页 → 救援链短路。
- 验收标准：
  - [ ] 新增 `isStrongRelevant(items, coreTerms)`：仅当 **title** 含任一核心词才判强相关（城市页 title"梧州市（…）"不含校名 → 判不相关）。
  - [ ] `execute` 主流程：主查询结果**仅弱相关**（title 未命中但 snippet 命中）→ 不再直接返回，触发核心词重试 + 百度兜底。
  - [ ] 核心词重试空转修复：核心词唯一候选 == query 时，不再 `continue` 跳过，直接走百度兜底（或对该候选用完整实体重试）。
  - [ ] `tryBaiduFallback` 判据同步升级为强相关（title 命中才算命中）。
  - [ ] 既有测试兼容：`isRelevant`（集合级，含 snippet）语义保持不变（防止回归），新增强相关单测。
  - [ ] Typecheck 通过。

### US-902: Bocha REST 搜索引擎（US-001 落地 + B2 增强）

- 作为 用户，我希望在「设置」填入博查 Key 后，搜索能优先用 Bocha（AI 原生语义重排）提升中文实体命中率。
- 验收标准：
  - [ ] 新增本地工具 `web_search__bocha`（或作为 `web_search__search` engine 分支）：POST `https://api.bocha.cn/v1/web-search`（query/summary/count），Bearer Key（apiKeyRef=bocha，复用 Bocha MCP 的 Key）。
  - [ ] 未配置 Key → 返回引导文案（前置 `[搜索失败]` 或独立文案）且降级 Bing+Baidu；配置 Key → 优先走 Bocha，失败降级 Bing+Baidu。
  - [ ] 返回结构化结果（title/url/snippet）含引用链接；429/5xx 可诊断文案（复用 isRateLimitError）。
  - [ ] 端点固定 + SSRF 无（用户无可控 host）；结果仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 解析 / 无 Key 降级 / 429 / URL 固定。
  - [ ] Typecheck 通过。

### US-903: Fetch 本地 HTML→Markdown 提纯（B1/B7）

- 作为 用户，我希望 Fetch 抓取被 JS 渲染/反爬拦截时能提取干净正文，而不是返回"抓取失败"。
- 根因：Jina 不可达 + 头伪装不解决指纹 + 200 空壳误判。
- 验收标准：
  - [ ] 移除对 `r.jina.ai` 的强依赖：`useJinaReader=true` 时**先尝试 Jina（有连通性/超时快速失败）**，失败自动降级到**本地 HTML→Markdown 提纯**（jsoup 或内置轻量正文提取：取 `<article>/<main>/<p>` 主干，去 script/style/nav，保留标题/段落）。
  - [ ] 本地提纯作为 `useJinaReader` 之外的**自动兜底**：直抓 200 但 `isAntiBotOrEmpty` 判定空壳时，尝试本地提纯后再判失败。
  - [ ] 1MB 上限 + `sanitizeUrlForLog` 不变；结果仅回灌 LLM 文本。
  - [ ] Jina 域名不可达（ConnectTimeout）日志明确标注"国内不可达"不再误导为反爬。
  - [ ] 单测：MockEngine 直抓 + 本地提纯提取正文 / Jina 失败降级 / 空壳判定。
  - [ ] Typecheck 通过。

### US-904: 热榜工具感知（B3）

- 作为 用户，我希望 AI 知道可以查询"今天微博/知乎热榜"，并能在对话中调用热榜工具。
- 根因：systemPrompt 未声明热榜能力 + 未配 Key 文案非 failure。
- 验收标准：
  - [ ] `mergeSystemPrompt` 新增热榜能力声明段（`hotlist__get` 能力 + 触发词：热搜/热点/热榜/今天大家在看什么）。
  - [ ] 未配置 Key 的引导文案前置失败标记（纳入 `isFailureResult` 可识别，避免 LLM 反复重试）。
  - [ ] 新增 buildTools 注册集成测试（断言 hotListEnabled=true 时返回含 `hotlist__get`）。
  - [ ] Typecheck 通过。

### US-905: SSE 错误处理增强（B4/B6）

- 作为 用户，我希望网络失败/空响应时有明确提示，且不再出现"静默空气泡/内容被截断无感知"。
- 根因：mapHttpError else 覆盖 -1/200/5xx；空流/半截被当成功。
- 验收标准：
  - [ ] `mapHttpError` 扩展：`status=-1` → "网络连接中断，请检查网络后重试"；`status=200`（协议不匹配）→ "服务端返回异常格式"；`5xx` → "服务端错误（xxx）"；保留 401/429/400 既有分支。
  - [ ] `streamChat` 空流/半截处理：SSE 正常结束但未收到任何 content 时，发射 `StreamEvent.Error("服务端未返回内容…")` 而非静默 Done；已收到部分增量但无 DONE 时补发可感知提示（非 markCompleted 静默）。
  - [ ] UI 侧 `handleStreamEvent` 对空 content 的完成不静默（或由 VM 层拦截空气泡）。
  - [ ] 单测：MockEngine 覆盖 status=-1 / 200 空流 / 200 JSON / 5xx 文案。
  - [ ] Typecheck 通过。

### US-906: 移除海外模板 + 元数据清理（B5 + US-006）

- 作为 用户，我希望模板列表不再出现不可用的海外模板（Slack/Asana/Brave/Exa/Firecrawl/TrendsMCP）。
- 验收标准：
  - [ ] `McpServerPresets` 移除 Slack/Asana/Exa/Firecrawl/TrendsMCP（Brave 已移除）配置与 `presetMeta` 元数据。
  - [ ] 保留 GitHub/Notion/Context7/Sentry/Stripe/n8n（标注 networkNote 保留）。
  - [ ] 单测：findByName 对已移除模板返回 null；保留模板 networkNote 存在。
  - [ ] Typecheck 通过。

### US-907: 智谱 Web Search REST（US-002 落地）

- 验收标准：
  - [ ] 本地工具 `web_search__zhipu`：POST `https://open.bigmodel.cn/api/paas/v4/web_search`，Bearer Key（apiKeyRef=zhipu），engine 参数（auto/web/so/baidu/bing）。
  - [ ] 未配置 Key 降级引导；429/5xx 可诊断；仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 解析 + engine 透传 + 无 Key 降级。
  - [ ] Typecheck 通过。

### US-908: 和风天气本地工具（US-003 落地）

- 验收标准：
  - [ ] 本地工具 `weather__now`（或 `weather__forecast`）：location（城市名）+ days，GET 和风专属 Host（默认常量 + 设置可覆盖）。
  - [ ] Key 加密存储（apiKeyRef=qweather）；未配置引导文案；仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 解析 + 无 Key 降级。
  - [ ] Typecheck 通过。

### US-909: 百度翻译本地工具（US-004 落地）

- 验收标准：
  - [ ] 本地工具 `translate__text`：text+to，POST 百度翻译（appid+MD5 签名），Key 加密存储。
  - [ ] 未配置引导；非 2xx 可诊断；仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 签名断言 + 响应解析。
  - [ ] Typecheck 通过。

### US-910: 国内 MCP 远程模板（US-005 落地）

- 验收标准：
  - [ ] 新增模板：Gitee（`https://api.gitee.com/mcp`，Bearer PAT）、聚合数据（`https://mcp.juhe.cn/mcp?token=<JWT>`）、天行数据、智谱 Web Search、高德地图，均含 `McpPresetMeta`（description+keyHint+networkNote）。
  - [ ] 智谱认证支持 URL query 参数；握手失败降级空列表不崩溃。
  - [ ] 单测：findByName + 元数据 + 握手降级。
  - [ ] Typecheck 通过。

## 4. 非功能需求

- 性能：搜索/Fetch/新工具均复用 `hasRequestBudget`；Jina 探测短超时（≤5s）；Bocha/智谱/天气/翻译单次 ≤10s。
- 安全：Key 全部 `ApiKeyRepository`（Tink AEAD+Keystore）；端点固定无 SSRF；结果仅回灌 LLM；日志脱敏。
- 可观测性：新增工具走 SkillExecutionRecord；SSE 状态分支可诊断日志。
- 兼容性：Android 8.0+，零新增第三方依赖（HTML 提纯优先内置轻量实现，jsoup 仅在必要时评估）。
- 隐私：Key 不出设备；外部服务符合 BYOK 模型。

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解 |
|---|---|---|
| 本地 HTML 提纯质量不如 Jina/WebView | 中 | 内置轻量正文提取（article/main/p 主干）+ isAntiBotOrEmpty 优化；真机验证 |
| Bocha/智谱/天气/翻译需注册 Key | 中 | 未配置降级 Bing+Baidu / 引导文案 |
| mapHttpError 状态分支扩展影响既有测试 | 中 | 保留既有分支文案，新增分支不破坏 |
| 搜索强相关判据可能误杀部分相关结果 | 中 | isRelevant 原语义保留（snippet 也算），仅主流程决策用强相关；真机验证 |

## 6. 里程碑

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| M1: 搜索增强（US-901/902） | 单测 + 真机搜索命中 | P1 |
| M2: Fetch 提纯（US-903） | 单测 + 模拟器 | P1 |
| M3: 热榜感知（US-904） | 单测 + 集成测试 | P0 |
| M4: SSE 增强（US-905） | 单测 | P1 |
| M5: 模板清理（US-906） | 单测 | P0 |
| M6: 新工具（US-907~909） | 单测 | P1 |
| M7: 国内 MCP 模板（US-910） | 单测 | P2 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| 搜索强相关 | 单测 + 真机 | 梧州一中命中官网 | US-901 |
| Bocha REST | 单测（MockEngine） | 解析+降级+429 | US-902 |
| Fetch 本地提纯 | 单测（MockEngine） | 正文提取+Jina 降级 | US-903 |
| 热榜感知 | 单测 + systemPrompt 断言 | 热榜声明+注册 | US-904 |
| SSE 分支 | 单测（MockEngine） | -1/200/5xx 文案 | US-905 |
| 模板清理 | 单测 | findByName null | US-906 |
| 新工具 | 单测 | 解析+降级 | US-907~909 |
| 国内模板 | 单测 | findByName+握手 | US-910 |
| 全量回归 | `:app:testDebugUnitTest` | 0 失败 | 全部 |

## 8. 实施状态（2026-08-20 滚动更新）

- **US-901**（搜索强相关 + 空转修复 + Bocha REST）：完成，全量回归含新增用例全绿
- **US-902**（Bocha REST 引擎，prd-open-box US-001）：完成（7 用例）
- **US-903**（Fetch 本地 HTML 提纯 + Jina 降级标注，prd-open-box 增补）：完成（3 用例）
- **US-904**（热榜感知 systemPrompt + 未配 Key failure 标记 + 注册集成测试）：完成
- **US-905**（SSE mapHttpError 扩展 -1/200/5xx + 空流/半截提示）：完成（3 用例）
- **US-906**（移除 5 个海外模板 + 元数据，prd-open-box US-004/006）：完成
- **US-910**（国内 MCP 模板 Gitee/聚合/天行/智谱/高德，prd-open-box US-005）：完成，端点经一手核验（Gitee/高德官方文档复核）
- **US-907~909**（智谱 REST / 和风天气 / 百度翻译）：**本批未实现**（范围取舍，列入后续迭代；prd-open-box 保留）
- **guardrail 闭环**：TKN-V1B9-GUARDRAIL-001 有条件通过 → H-1（Bocha 预算窗口）/ H-2（空流判定误伤工具调用）/ H-3（热榜失败熔断）已修复；M-1（分层判据注释）/ M-2（智谱 keyHint 对齐 Bearer 头）/ M-3（端点核验）已处理；L-3/L-4 已修复
- **待办**：guardrail 复审 → ac-verifier 验收 → 模拟器验证 → 真机测试通知
