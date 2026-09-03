# ADR-039: v1 真机四次修复（Fetch 明文被拦 / 搜索 RSS→HTML / 视觉旁路 Provider 回退 + 可观测）

> 落实 v1（v1.0.0）真机手动测试暴露的 3 项问题的根因修复（第三次复测仍存在）。
> 依据 CLAUDE.md「后期 Bug 修复闭环」，先拉取真机 logcat 证据定位，再以实质不同方案修复。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户确认（真机复测 3 项仍未解决） |
| 关联文档 | [PRD](../PRD.md)、[ADR-033](ADR-033-uxr11-real-device-fixes.md)、[ADR-037](ADR-037-v1-real-device-round2.md)、[ADR-038](ADR-038-v1-real-device-round3.md) |
| 风险等级 | P2（跨模块：Fetch 网络 / 搜索解析 / 视觉旁路） |

## 背景（Context）

用户再次真机手动测试，确认 3 项问题**依旧存在**，并按要求先用 `capture-prism-log.ps1` 捕获 logcat 提交分析。对 `prism_20260819_215452.log`（1.7MB）逐类提取**原始证据**，推翻上一轮（ADR-037/038）"尽力而为"假设：

1. **Fetch**：日志为 `LocalMcpToolProvider: fetch failed: UnknownServiceException`。调研确认这是 Android 9+（targetSdk>28）**明文 http 被 `network_security_config`（仅放行 localhost/127.0.0.1）拦截**的典型异常——**不是反爬 403/429**。此前 Add 的浏览器指纹/Referer 头方向对明文拦截无效，LLM 反复重试。
2. **搜索**：日志显示 Bing `format=rss` 对"梧州市第一中学"**连精确校名都只返回市级百科**（`first=梧州市（中国广西…）_百度百科`），且上一轮的 `stripTrailingQuerySuffix` 把"梧州市第一中学"**误剥成"梧州市第一"**（后缀表误含"中学/大学/学校/公司"等实体词）。两者叠加 → "参考来源大概相关、与内容无直接联系"。改用 Bing **HTML SERP** 实测（WebFetch）同查询能直接命中学校官网。
3. **视觉**：日志只有 `OCR process succeeded via visionkit pipeline`，云端旁路从未触发且失败被 orchestrator **静默吞掉**（try/catch 返回 null → 落 OCR），真机无法定位是"没进 Cloud"还是"Cloud 调用了但失败"。

## 决策（Decision）

### 子决策 A：Fetch 明文拦截 —— http→https 升级 + 可诊断日志（问题 1）

- **根因**：公网明文 `http://` 被 Android 网络安全策略拦截（OkHttp 抛 `UnknownServiceException: CLEARTEXT ... not permitted`），被误判为反爬失败。
- **修复**：`LocalMcpToolProvider.fetchUrl` 对公网 `http://` URL **先升级为 `https://`** 再请求（同 host，`isPublicHttpUrl` SSRF 复检；绝大多数站点支持 https）。**刻意不**放宽 `network_security_config` 全局明文（ADR-004 安全边界不变）；http-only 的极少数站点返回可诊断文案。
- **日志脱敏**：失败日志补 `sanitizeUrlForLog`（丢弃 query/fragment/**userinfo**，仅留 host+path，CWE-532），此前只记异常类型无法定位是哪一层失败。新增 `fetch upgrades public http url to https` / `sanitizeUrlForLog strips query fragment userinfo` 单测。
- guardrail LOW-1：sanitizeUrlForLog 剥离 userinfo（`substringAfterLast('@')`）已修复。

### 子决策 B：搜索命中 —— Bing RSS 改 HTML SERP + 修正后缀误剥（问题 2）

- **根因**：Bing `format=rss` 中文实体排名坍缩（校名返回市级）；后缀表误剥实体词。
- **修复 1（解析）**：`fetchSearch` 改请求 `https://cn.bing.com/search`（`mkt=zh-CN`+`setlang=zh-hans`+`count`+浏览器 UA/Accept-Language/Referer），`parseBingHtml` 提取 `li.b_algo` 块（title/href/snippet），`decodeBingUrl` 处理直链与 `//cn.bing.com/ck/a?...u=a1<base64url>` 跳转解码。不引入 HTML 解析依赖（Karpathy 简洁 + 避免 R8 风险）。
- **修复 2（后缀）**：`QUERY_SUFFIXES` 移除实体词后缀（中学/大学/学校/公司/功能/详情…），仅保留疑问/泛化后缀，"梧州市第一中学"保持完整实体。
- 既有「多候选核心词重试 + 条目级过滤 + 多查询合并 + 预算感知」逻辑原样保留。新增 `WebSearchLocalToolExecutorHtmlBingParsingTest`（解析/解码/校名命中 golden）。
- **安全**：解码出的 URL 仅作为搜索**结果链接**回灌 LLM（前置【外部内容】不可信边界），不进入本 App 抓取/Intent/WebView sink；渲染侧 scheme 白名单双保险（guardrail 复核通过）。

### 子决策 C：视觉旁路可启用 + 端到端可观测（问题 3）

- **Provider 解析升级**：`handleVisionUnsupportedError` 视觉配置改为 `findVisionFallback() ?: providerRepository.activeProviderFlow.value`——未配置独立 `isVisionFallback` Provider 时**回退到当前激活主 Provider** 充当图像描述端点，对齐用户"激活了视觉模型却只能 OCR"的预期。是否真具视觉能力交给远端判断，失败自然落 OCR。
- **隐私不破坏**：图像外发仍受 orchestrator `isBypassAvailable()`（consent && auto && failures<3）闸门管控；未授权不进云→OCR。主 Provider 即聊天主端点，用户本就会向其发图/消息，不构成新增外发面。
- **可观测（根治静默吞）**：`cloudDescriber`（PrismApplication）显式记录 `cloud bypass ok/failed provider=... err=...`；VM 记录 `vision bypass: dedicated=... cloudConfig=...`。修复后真机可见到底走没走 Cloud、为何失败。
- **待真机确认**：若回退的主 Provider 确为纯文本，Cloud 会再次失败→熔断→OCR（符合降级设计）；是否真正 Cloud 成功依赖远端多模态兼容性。

## 结果（Consequences）

- 全量回归 ~2295 用例 **0 失败**（新增 HTML 解析 7 例 + Fetch 2 例）。
- guardrail-enforcer：**通过**（0 阻断/0 高危；LOW-1 日志 userinfo 脱敏已修复）。
- ac-verifier：**3/3 通过**；GAP-1（回退 activeProvider 分支的 VM 级专门用例缺失，低严重度，后续补充）。
- 测试：`WebSearchLocalToolExecutorHtmlBingParsingTest`（parseBingHtml/decodeBingUrl/校名 golden/后缀不误剥）、`LocalMcpToolProviderFetchTest`（https 升级/sanitizeUrlForLog）、既有 WebSearch 测试 fixture 由 RSS 改 HTML。
- 已知待真机补测：https 升级在真实明文 URL 的成功、真实 Bing SERP 命中、视觉旁路真实 Cloud 链路（三条新增日志 `fetch failed url=` / `cloud bypass ... provider=` / `vision bypass: ... cloudConfig=`）。

## 后续跟踪

- 若回退主 Provider 作视觉端点的成功依赖远端多模态，需在设置页明确提示"未配置专用视觉 Provider 时用主模型尝试解析，纯文本主模型会回落 OCR"。
- Bing HTML SERP 是 HTML 解析，若 Bing 改版结构，`li.b_algo` 选择器需同步维护；`parseBingHtml` 对无 `b_algo` 的挑战/空壳页返回空→"搜索失败"（graceful）。
