# ADR-040: v1 真机五次修复（搜索 Bing→Baidu 多引擎回退 / 视觉专用 Provider 跳过熔断但守 consent 铁门）

> 落实 v1（v1.0.0）真机手动测试暴露的 2 项**多次修复仍存在**问题的根因修复（第三次复测仍存在）：
> ① 搜索无法命中正确网址（中文专有名词只回"大概相关"）；② 即使激活视觉模型，视觉旁路仍只走 OCR。
> 依据 CLAUDE.md「后期 Bug 修复闭环」，先拉取真机 logcat 证据定位，再以**实质不同方案**修复。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户确认（真机复测 2 项仍未解决） |
| 关联文档 | [ADR-035](ADR-035-v1-vision-plan-b.md)、[ADR-037](ADR-037-v1-real-device-round2.md)、[ADR-038](ADR-038-v1-real-device-round3.md)、[ADR-039](ADR-039-v1-real-device-round4.md) |
| 风险等级 | P2（跨模块：搜索多引擎网络 / 视觉旁路授权语义） |

## 背景（Context）

用户再次真机手动测试，确认 2 项问题**依旧存在**（此前 ADR-037/038/039 已多轮修复）：

1. **搜索**：提问"梧州一中是一个什么样的学校？"参考来源只有几条关于"梧州"的信息，剩余全为无关资料——核心**专有名词（校名）命中不了正确网址**。前轮已采取「HTML SERP 解析 + 核心词短整词重试 + 后缀不误剥」（ADR-039），但**单一 Bing 源对中文长实体的排名坍缩仍无法根治**。
2. **视觉**：即使激活了视觉 Provider（真机日志可见 `vision bypass: dedicated=true cloudConfig=...`），旁路仍只走 `OCR process succeeded`——**云端从未成功执行**。前轮已补 `cloud bypass ok/failed` 观测日志（ADR-039），真机证据显示 Cloud 被 `isBypassAvailable()` 的 **consent（默认 false）+ 熔断（连续失败≥3 自停）双重闸门**锁死。

方案调研（tech-selection-researcher / web-access 网络调研）：中文实体搜索优化业界普遍采用**多引擎横向扩容**（如 SearXNG 聚合、Baidu 兜底提高中文 recall）；视觉 Provider 对"用户显式选择的专用端点"应更高可用，但**不得因此突破图片外发隐私授权**——两者需在实现上解耦。

## 决策（Decision）

### 子决策 A：搜索 —— Bing 不中后触发 Baidu HTML SERP 兜底（问题 1）

- **根因**：单 Bing 源对中文实体（尤其长校名）排名坍缩，HTML SERP 也只是提高命中率，仍可能整体返回市级/无关结果。
- **修复**：`WebSearchLocalToolExecutor` 新增 `fetchBaiduSearch` / `parseBaiduHtml` / `tryBaiduFallback`——当 Bing 主查询 + 核心词多候选重试**全部判定不相关或空结果**时，回退请求 `https://www.baidu.com/s`（`wd=<query>` URL 编码 + 浏览器 UA/Accept-Language/Referer），解析百度 HTML 结果（`h3` 标题 / `a.href` / `c-abstract` 摘要），**先主查询再逐核心词短整词**命中即停。
- **复用既有护栏**：兜底结果同样过 `filterRelevantItems`（条目级相关性）+ `hasRequestBudget`（预算感知，任一 hit 即停），不破坏 UXR9 的精确匹配防噪声修复；结果仅回灌 LLM 文本（前置【外部内容】不可信边界），不进入抓取/Intent/WebView sink。
- **安全**：端点固定 `https://www.baidu.com/s`，query 经 Ktor parameters 编码，无 SSRF/注入面（guardrail 复核通过）。
- 新增 golden：`WebSearchLocalToolExecutorHtmlBingParsingTest`（Baidu 解析 + "Bing 回市级→Baidu 兜底命中校名"场景 + 预算耗尽不炸预算）。

### 子决策 B：视觉 —— 专用 Provider 跳过熔断，但守 consent 隐私铁门（问题 2）

- **根因**：`isBypassAvailable()` 需 consent && auto && failures<3 三者同时。即便激活视觉 Provider（saveProvider 会 `setConsent(true)`），一旦云端连续失败 3 次进入熔断，或某条路径 consent 未授，Cloud 即被短接、只剩 OCR。
- **修复**：`VisionBypassOrchestrator.resolve` 新增 `isDedicated` 参数——`ConversationViewModel` 以 `findVisionFallback()!=null` 判定并传入。`isDedicated=true` 的专用 Provider 分支 `cloudAllowed = auto && config.isConsentGiven()`：
  - **跳过熔断**（不再检查 `failures<MAX`）→ 激活的视觉 Provider 每次可重试 Cloud，修"激活了却永远只 OCR"；
  - **仍校验 `isConsentGiven()`** → 用户到设置页**显式撤销图片外发授权**后，专用 Provider 也不得再外发（ADR-035 隐私铁门），修上一版"isDedicated 只查 auto"引入的隐私回归（guardrail B-1 阻断项）。
  - consent 的授予链路：把 Provider 标记为 `isVisionFallback` 时 `SettingsViewModel.saveProvider` 自动 `setConsent(true)+setAutoBypassEnabled(true)+resetFailures()`，故正常激活路径 consent 恒为 true，不影响"激活即用"。
- **保持**：非专用（回退到主 Provider）场景维持严格 `consent && auto && failures<3`，防纯文本主模型反复打空枪。

## 结果（Consequences）

- 全量回归 **2270 用例 0 失败**（新增 Baidu 解析/回退场景 + 视觉 consent 铁门红线用例）。
- guardrail-enforcer：**有条件通过**——服务层无阻断/注入/SSRF/密钥；**B-1 阻断项**（MEDIUM 隐私回归）已修复闭环；A-No.2/3/4/5 列为后续迭代已知限制。
- 测试：`WebSearchLocalToolExecutorHtmlBingParsingTest`（Baidu 兜底 golden）、`VisionBypassSupplementTest`（"专用 Provider+熔断仍走 Cloud" / "专用 Provider+授权撤销→OCR" / "auto 关闭仍尊重"）、`ConversationViewModelVisionSupplementTest`（Unavailable→错误提示改走非专用场景）。
- 新增行为规则：**BR-search-003**（单引擎命中不佳须多引擎回退，回退仍须相关性过滤+预算感知）、**BR-vision-003**（专用 Provider 可跳过熔断但不得绕过 consent 隐私铁门）。

## 已知限制（后续迭代）

- **A-No.2**：专用 Provider 跳过熔断后，对极端限流端点（如 kimi RPM=3）可能无退避重打每张图——待真机验证是否实际放大请求。
- **A-No.3**：Baidu 结果 `http://www.baidu.com/link?url=<opaque>` 跳转未解码为真实 URL，引用可溯源性弱于 Bing 直链。
- **A-No.4**：相关性过滤仍是**字面子串**匹配，查询"梧州一中"对全称 SERP"梧州市第一中学"可能漏判——待真机验证 Baidu 兜底实际 recall 后再决定是否加"实体二字前缀放宽落笔"。
- **A-No.5**：把视觉模型选为**主 Provider** 但未打 `isVisionFallback` 标、且未授权时，仍只 OCR（有隐私合理性，但 UI 无授权引导）——后续可加 consent 弹窗引导而非静默降级。

## 待真机补测

- 搜索：真实"梧州一中"提问 → 参考来源直接命中校名/学校官网（非市级）。
- 视觉：激活视觉 Provider 后发图 → logcat 见 `cloud bypass ok provider=…`（而非只有 OCR）；若先撤销授权 → 不再外发、落 OCR。
