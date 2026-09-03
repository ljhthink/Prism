# 开箱即用 API 与 MCP 增强 · 产品需求文档（PRD）

> 针对用户环境"预设远程 MCP 模板海外端点不可达 + 中文实体搜索命中率波动"现状，按 **API 开箱即用、MCP 开箱即用优先（质量高可接受远程配置）** 标准筛选国内可用能力，产出本项目功能优化方案。
> 前置调研：`docs/reports/2026-08-20-mcp-search-api-alternatives-research.md`（6 模板替代对照）+ 2026-08-20 tech-selection-researcher 选型报告（含一手端点/额度核验）。
> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-08-20 |
| 作者 | 主 Agent（方案待用户确认后执行） |
| 关联文档 | 调研报告 docs/reports/2026-08-20-mcp-search-api-alternatives-research.md（一次性工件，不入库）、ADR-029（O2/O3/O5 MCP 模板）、[ADR-001](decisions/ADR-001-prism-tech-stack.md) |
| 风险等级 | P2（跨模块：新增本地工具 + MCP 模板 + 网络能力） |

## 1. 背景

用户真机环境：12 个预设远程 MCP 模板（GitHub/Notion/Context7/Slack/Sentry/Stripe/Asana/Exa/Firecrawl/n8n/TrendsMCP/Bocha）中海外端点不可注册；内置搜索（Bing HTML SERP + Baidu HTML 兜底，零 Key）对中文实体命中率经多轮真机修复（ADR-039/040）仍时有波动。

项目现状（源码探查确认）：

- **网络搜索**：`web_search__search`（Bing RSS + Bing HTML + Baidu HTML，零配置）已内置；Bocha 远程 MCP 模板已加入（US-001，需 Key）。无 **REST 直连**搜索源。
- **网页抓取**：内置 `fetch`（`LocalMcpToolProvider`，SSRF 纵深）+ Jina Reader 可选增强（US-003，默认关）。
- **热榜**：`hotlist__get` 本地工具已内置（tophubdata，需 Key）。
- **缺失能力**：结构化天气、翻译、国内 MCP 模板（GitHub/Notion/Slack/Asana/TrendsMCP 的国内替代）。

## 2. 筛选结论（2026-08-20 时点，一手核验）

### 2.1 判定口径（重要）

- **开箱即用（API）**：国内可直连 + 注册免费 Key 即用（免 SDK/免自建/免远程配置）+ 免费档满足个人使用。
  - 严格免 Key 的搜索通道 = HTML 抓取（已内置 Bing+Baidu），无国内免 Key 搜索 REST API。
  - 故将「注册 Key 即用」的国内 API 列为可接受的"开箱即用"边界，并在 8.待确认 请用户确认。
- **开箱即用（MCP）**：远程 **Streamable HTTP**（Android 可接入，无 stdio 子进程）+ 官方托管端点 + 免费档。
  - 质量高但需远程配置（如飞书 7 天授权）者，按用户口径可接受。

### 2.2 网络搜索 API（用户核心关注）

| 方案 | 端点 | 认证 | 免费额度 | 国内直连 | 档位 |
|---|---|---|---|---|---|
| **博查 Bocha REST**（补直连层） | `https://api.bocha.cn/v1/web-search` | Bearer（open.bocha.cn 注册） | 免费 1000 次 + 口令"博查搜索"兑 1000 次，之后 ¥3.6/千次 | ✅ | **推荐接入**（已接入 MCP，补 REST 增强搜索命中） |
| **智谱 Web Search API** | `https://open.bigmodel.cn/api/paas/v4/web_search` | Bearer（bigmodel.cn 注册） | 新用户赠送额度，search_std ¥0.01/次 | ✅ | **推荐接入**（一 Key 复用 GLM 生态 + 多引擎自研/Bing/搜狗/夸克/Jina） |
| 百度 AI 搜索 | `https://qianfan.baidubce.com/v2/ai_search/web_search` | AK/SK（OAuth） | 每日 100 次 | ✅ | 可考虑（认证略重，与 Baidu HTML 兜底重叠） |
| 聚合 AI 联网 / 小米 MiMo | — | — | 无免费档 | ✅ | 不推荐 |
| **现有零配置通道**（保留） | Bing RSS + Bing HTML + Baidu HTML | 无 | 无限 | ✅ | **开箱即用基线，不替换** |

### 2.3 其他适用 API（个人 AI 助手）

| 类别 | 方案 | 端点 | 认证 | 免费额度 | 档位 |
|---|---|---|---|---|---|
| **天气** | **和风天气 QWeather** | `https://{专属Host}/v7/weather/now` | X-QW-Api-Key | 普通 1000 次/天 | **推荐接入**（结构化替代 HTML 解析；⚠️ 2026 起须用控制台专属 Host，公共域名停用） |
| **翻译** | **百度翻译标准版** | `https://fanyi-api.baidu.com/api/trans/vip/translate` | appid + MD5 签名 | **不限字符免费**（QPS=1） | **推荐接入**（补翻译工具，签名简单） |
| 汇率 | Frankfurter | `https://api.frankfurter.dev/v2/rates` | **免 Key** | 无限（合理使用） | 可考虑（海外端点，需真机实测直连） |
| 汇率 | 聚合数据汇率 | `https://v.juhe.cn/forex/rmbquot` | key | 免费档有限 | 可考虑（国内兜底） |
| 新闻/热榜 | 聚合数据 / 天行数据 | `v.juhe.cn` / `apis.tianapi.com` | key | 聚合 50 次/天；天行会员免费 | 可考虑（与热榜工具部分重叠） |
| OCR | 百度智能云 OCR | `aip.baidubce.com/rest/2.0/ocr/v1/general_basic` | access_token（AK/SK） | 个人认证 1000 次/月 | 可考虑（ML Kit OCR 云端补充） |
| 二维码 | 本地 ZXing | 本地生成 | 无 | 无限 | ✅✅ 推荐（无需 API，离线） |

### 2.4 可替代现有功能的 MCP 工具（远程 Streamable HTTP）

| 海外模板（现预设） | 国内替代 | 端点 | 认证/免费 | 档位 |
|---|---|---|---|---|
| GitHub | **Gitee MCP（官方）** | `https://api.gitee.com/mcp` | Bearer PAT（免费生成，29 工具：仓库/Issue/PR/文件） | **推荐接入** |
| Notion | **飞书 MCP（官方）** | open.feishu.cn 生成专属 URL（Streamable HTTP） | 免费；⚠️ 新服务有效期 7 天 | 按需接入 |
| Slack / Asana | **飞书项目 MCP（官方）** | `https://project.feishu.cn/mcp_server/v1?mcpKey=...` | 免费；需插件体系 | 按需接入 |
| TrendsMCP | **天行数据 MCP**（微博/抖音/头条热搜） | `apis.tianapi.com` 系 | 免费注册 | 推荐接入 |
| TrendsMCP | **聚合数据 MCP** | `https://mcp.juhe.cn/mcp?token=<JWT>` | 免费 50 次/天（天气/新闻/AQI/快递） | 推荐接入 |
| Brave / Exa | **智谱 Web Search MCP** | `https://open.bigmodel.cn/api/mcp-broker/proxy/web-search/mcp?Authorization=...` | 免费额度 | 推荐接入 |
| Firecrawl | 智谱 Web Reader MCP / 本地 Fetch+Jina | `open.bigmodel.cn/api/mcp/web_reader/mcp` | ⚠️ 需 Coding Plan（付费） | 可考虑（付费档）；**现状本地 Fetch 已覆盖** |
| 出行 | **高德地图 MCP（官方）** | `https://mcp.amap.com/mcp?key=<key>` | 个人 5000~150000 次/月 | 可考虑（与手机操控互补） |
| Context7 / Sentry / Stripe | 国内无等价物 | — | — | 不推荐（保留现状/移除） |

## 3. 目标与非目标

- 目标：
  1. 新增**博查 Bocha REST 搜索工具**（注册 Key 即用），作为 WebSearch 的可选增强引擎，直接提升中文实体/时效命中率。
  2. 新增**和风天气本地工具**（结构化天气，替代 HTML 解析）。
  3. 新增**百度翻译本地工具**（标准版不限字符免费，补翻译能力）。
  4. 新增**国内 MCP 远程模板**：Gitee / 聚合数据 / 天行数据 / 智谱 Web Search（含元数据 description+keyHint+networkNote）。
  5. 保留现有零配置搜索主通道（Bing+Baidu 兜底），Bocha/智谱作为**可选增强**（注册 Key 即用）。
- 非目标（明确不做）：
  - 不替换现有 Bing+Baidu 零配置搜索主通道。
  - 不接入付费 MCP（智谱 Web Reader / 聚合 AI 联网）。
  - 不新增飞书 MCP 为默认（7 天授权有效期，不适合低频长期默认），仅登记模板 + 按需说明。
  - 不把任何 Key 硬编码入库（继续 Keystore 加密存储）。
  - 不引入自建服务（SearXNG/Crawl4AI）与 stdio 传输 MCP。

## 4. 用户故事与验收标准

### US-001: 博查 Bocha REST 搜索工具（搜索增强引擎）

- 作为 用户，我希望在「设置」开启 Bocha 并填入 API Key 后，AI 搜索能优先用博查（AI 原生带引用），以便中文实体/时效问题获得比 Bing+Baidu 更准的命中。
- 说明：Bocha 已作为远程 MCP 模板（US-001/v1-B8），本故事补 **REST 直连层**作为 `web_search__search` 的可选引擎（避免依赖 MCP 会话状态），注册 Key 即用（免费 2000 次）。
- 验收标准：
  - [ ] 新增本地工具 `web_search__bocha`（或作为 `web_search__search` 的 engine 分支），端点固定 `https://api.bocha.cn/v1/web-search`，POST JSON（query/summary/count），Bearer Key。
  - [ ] Key 经 `ApiKeyRepository` 加密读取（apiKeyRef = bocha）；未配置时返回"请到 open.bocha.cn 注册获取 Key"引导，不崩溃。
  - [ ] 返回结果仅回灌 LLM 文本（前置【外部内容】不可信边界），不含可执行内容；结果含引用链接（title/url/snippet）供引用来源卡片。
  - [ ] 请求超时 + 预算护栏复用（`hasRequestBudget`）；429/5xx 返回可诊断文案（复用 `isRateLimitError` 识别）。
  - [ ] 单测：MockEngine 解析返回结构化条目；无 Key/失败降级；URL 固定无 SSRF。
  - [ ] Typecheck 通过。

### US-002: 智谱 Web Search REST 工具（多引擎搜索增强）

- 作为 用户，我希望在 Bocha 之外可选用智谱（一 Key 复用 GLM 生态、多引擎可调），以便按需对比不同搜索源。
- 验收标准：
  - [ ] 新增本地工具 `web_search__zhipu`，端点固定 `https://open.bigmodel.cn/api/paas/v4/web_search`，Bearer Key（apiKeyRef = zhipu）。
  - [ ] 支持多引擎参数（engine：auto/web/so/baidu/bing），返回意图分类 + 结构化结果 + references。
  - [ ] 未配置 Key 返回引导文案；429/5xx 可诊断；仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 解析 + 引擎参数透传 + 无 Key 降级。
  - [ ] Typecheck 通过。

### US-003: 和风天气本地工具（结构化天气）

- 作为 用户，我希望在对话中查询天气时获得结构化数据（当前天气 + 未来 3 天），以便不再依赖 HTML 抓取天气页。
- 说明：⚠️ 2026 年起公共域名 `api.qweather.com` 停用，必须使用控制台**专属 API Host**（用户填 Key 时一并配置 Host 或从 Key 配置解析）。
- 验收标准：
  - [ ] 新增本地工具 `weather__now`（或 `weather__forecast`）：入参 `location`（城市名/经纬度）+ `days`，返回实时/逐日天气 JSON（温度/天气现象/湿度/风）。
  - [ ] 端点 Host 由配置提供（默认常量兜底），仅 HTTPS；location 经地理编码或直接 LocID。
  - [ ] Key 加密存储（apiKeyRef = qweather）；未配置返回引导文案；仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 解析响应 + 无 Key 降级 + 异常天气码文案。
  - [ ] Typecheck 通过。

### US-004: 百度翻译本地工具（翻译补缺）

- 作为 用户，我希望让 AI 帮我翻译文本（中/英等），以便跨语言交流。
- 说明：百度翻译标准版**不限字符免费**（QPS=1），appid + MD5 签名（Android 端易实现）。
- 验收标准：
  - [ ] 新增本地工具 `translate__text`：入参 `text`（必需）+ `to`（目标语言，默认 zh，支持 en/ja/ko 等），返回译文。
  - [ ] 端点固定 `https://fanyi-api.baidu.com/api/trans/vip/translate`（HTTPS）；签名按官方 MD5 规范（appid+q+salt+secret），Key 加密存储（apiKeyRef = baidufanyi）。
  - [ ] 未配置 Key 返回引导文案；非 2xx 返回可诊断文案；仅回灌 LLM 文本。
  - [ ] 单测：MockEngine 签名校验（断言请求头/参数）+ 响应解析 + 无 Key 降级。
  - [ ] Typecheck 通过。

### US-005: 新增国内 MCP 远程模板（Gitee / 聚合数据 / 天行数据 / 智谱 Web Search / 高德）

- 作为 用户，我希望在「能力 → MCP → 模板」中一键添加国内可用模板，以便替代海外不可达模板（GitHub→Gitee、TrendsMCP→天行/聚合、Brave/Exa→智谱）。
- 验收标准：
  - [ ] `McpServerPresets` 新增远程模板：Gitee（`https://api.gitee.com/mcp`，Bearer PAT）、聚合数据（`https://mcp.juhe.cn/mcp?token=<JWT>`）、天行数据（`https://apis.tianapi.com/mcp` 或对应端点）、智谱 Web Search（`https://open.bigmodel.cn/api/mcp-broker/proxy/web-search/mcp?Authorization=...`）、高德地图（`https://mcp.amap.com/mcp?key=<key>`）。
  - [ ] 每个模板 `McpPresetMeta` 含 description + keyHint + networkNote（国内直连不标或标注"国内直连"）。
  - [ ] 智谱模板认证方式支持 URL query 参数（`?Authorization=`）与 `McpServerConfig.headers` 兼容。
  - [ ] 单测：新增模板 findByName 命中；元数据含 keyHint；无 Key 时握手失败降级为空列表不崩溃。
  - [ ] Typecheck 通过。

### US-006: 海外模板标注与维护（保留现状）

- 作为 用户，我希望模板列表对海外不可达模板保持明确提示，且不被已移除的 Brave 误导。
- 验收标准：
  - [ ] 现有海外模板（GitHub/Notion/Context7/Slack/Sentry/Stripe/Asana/Exa/Firecrawl/n8n/TrendsMCP）networkNote 保留"海外端点，国内网络可能不可用"。
  - [ ] 单测：海外模板均含 networkNote；Brave 仍 findByName==null。
  - [ ] Typecheck 通过。

## 5. 非功能需求

- 性能：所有新增工具复用 `hasRequestBudget`（30s 总预算）；单次请求超时 ≤5s（和风/翻译/智谱）或按需（博查 0.15s 极速）。
- 安全：
  - API Key 全部经 `ApiKeyRepository`（Tink AEAD + Keystore）加密，不硬编码、不落日志。
  - 所有端点固定常量，用户可控 host 仅限和风专属 Host（白名单校验 https + 非内网）。
  - 结果文本均前置【外部内容】不可信边界，仅回灌 LLM，不进抓取/Intent/WebView sink。
  - 日志脱敏：URL 经 `sanitizeUrlForLog`；Key/签名不落日志。
- 可观测性：新增工具走现有 `SkillExecutionRecord` / 工具卡片；失败输出可诊断文案（缺 Key / 网络 / 限流）。
- 兼容性：Android 8.0+（API 26+），零新增第三方依赖（复用 Ktor/OkHttp/DataStore，二维码用内置 ZXing 无需新依赖）。
- 隐私：Key 不出设备；新增外部服务符合既有 BYOK 数据外发模型。

## 6. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| 和风天气 2026 公共域名停用，需专属 Host | 中 | 默认常量兜底 + 设置页引导填写专属 Host；真机实测 |
| 智谱 MCP 认证经 URL query（?Authorization=）与现 McpServerConfig.headers 需兼容 | 中 | 模板声明自定义 header/query；先嵌入式握手测试 |
| 博查/智谱免费额度 2026 年国内大模型普遍收窄 | 中 | 免费档充足（博查 2000 次/智谱赠送）；超出自动降级 Bing+Baidu |
| 新增工具增加 schema 数量与 token 开销 | 低 | 工具按需注入（能力开关，默认关）；存量开关模式复用 |
| 聚合/天行免费档稳定性 | 低 | 仅作为可选模板，非主路径 |
| 飞书 7 天授权有效期 | 低 | 仅登记模板 + 按需说明，不做默认 |

## 7. 里程碑

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| M1: Bocha REST 搜索工具 | US-001 全部 AC + 回归 | P1 |
| M2: 智谱 REST 搜索工具 | US-002 全部 AC + 回归 | P1 |
| M3: 和风天气工具 | US-003 全部 AC + 回归 | P1 |
| M4: 百度翻译工具 | US-004 全部 AC + 回归 | P1 |
| M5: 国内 MCP 模板 | US-005 全部 AC + 回归 | P2 |
| M6: 海外模板维护 | US-006 全部 AC + 回归 | P0 |

## 8. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| Bocha REST 工具 | 单测（MockEngine） | 结构化解析 + 无 Key 降级 + URL 固定 | US-001 |
| 智谱 REST 工具 | 单测（MockEngine） | 引擎参数透传 + 响应解析 | US-002 |
| 和风天气工具 | 单测（MockEngine） | 天气解析 + 专属 Host 校验 | US-003 |
| 百度翻译工具 | 单测（MockEngine） | MD5 签名 + 响应解析 | US-004 |
| 国内 MCP 模板 | 单测 + 嵌入式握手测试 | findByName 命中 + keyHint + 握手降级 | US-005 |
| 海外模板维护 | 单测 | networkNote + Brave 移除保持 | US-006 |
| 全量回归 | `:app:testDebugUnitTest` | 0 失败 | 全部 |

## 9. 待确认事项

- [ ] **「开箱即用」口径确认**：本项目采用「注册免费 Key 即用 + 国内直连 + 免费档」为开箱即用边界（严格免 Key 搜索 API 国内不存在，HTML 抓取通道已内置）。请确认此口径。
- [ ] **执行范围确认**：是否全部执行 US-001~US-006，或仅先做 US-001（Bocha REST）+ US-005（MCP 模板）两批高优先级项？
- [ ] **智谱 REST + MCP 是否同时接入**：智谱 Web Search 可 REST 工具 + MCP 模板双形态，是否都做？
- [ ] **和风专属 Host 处理**：接受「设置页引导填专属 Host」方案，还是退而用聚合数据天气（50 次/天）？
- [ ] **百度翻译标准版**：确认接受 appid+secret 双字段配置（与现单 Key 存储模型需扩展或复用现有字段）。

> 2026-08-20 方案经用户确认进入实施。实施范围扩展为 v1 批次9（整合真机 8 项问题修复 + 本 PRD US-001~006）。
> 实施结果：guardrail + ac-verifier + 全量回归 + APK 构建 + 模拟器验证后通知真机测试。
