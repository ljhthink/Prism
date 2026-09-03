# AGENTS.md —— 项目进度记录（面向 AI Agent 与开发者）

> 本文件记录 Prism 项目的开发进度、里程碑、用户故事清单与文档索引，供 AI Agent 与协作开发者快速了解项目状态。
> **产品介绍请阅读 [README.md](README.md)。** 本文件是进度与治理记录，不是产品文档。

## 项目状态

> 进度记录随开发持续更新。里程碑/用户故事均需通过 guardrail-enforcer 审查 + ac-verifier 验收方可标记完成。

- **v1 批次15.1 真机搜索链路诊断修复（US-1509 首选引擎 + engine 参数 + MCP 可诊断日志；全量回归 0 失败 + lint 0 errors）**（2026-09-03）—— 依据真机手动测试反馈（LLM 只见 Bocha/智谱MCP/Bing 工具、Scrapling/crawl4ai「无法保存」、SearXNG「不被识别」）+ run-as 拉取设备端 DataStore/ObjectBox 取证：
  - **诊断结论**：① SearXNG 端点/WebView 开关**均已保存成功**（prism_search_enhancement.preferences_pb 实证）、「无法保存」的 Scrapling/crawl4ai **均已落库**（data.mdb 实证 `127.0.0.1:8000|8001/mcp`）——根因是 **MCP `listTools/describeTools` 连接失败静默吞掉返回空列表（零日志）** + **引擎链短路设计缺陷**（Bocha Key 已配且成功 → 日志 `bocha search items=8` 即返回，SearXNG 永不被尝试）
  - **修复**：[McpClientManager] listTools/describeTools/callTool 失败补脱敏可诊断日志（url 经 sanitize、CWE-209/532 口径不变）；[WebSearchLocalToolExecutor] 新增 `engine` 参数（enum auto/bocha/zhipu/searxng/tavily，LLM 可显式指定）与 `preferredEngineProvider` 首选引擎（设置页「首选引擎」分段选择器，命中者首个尝试、失败落回默认链，done 标记防重复；显式引擎工具语义回归锁定）
  - **真机已验证**：adb reverse 三端口已建、SearXNG JSON API 200（PC 侧）、Scrapling/crawl4ai Streamable HTTP 握手 200（crawl4ai 0.9.3 仅 SSE/WS，经 supergateway 链 :8001 转 Streamable HTTP，已入开机自启）
  - 新增单测 5 个（首选引擎优先/参数覆盖/失败回退/显式工具回归/schema enum）；版本号仍 v1.0.0

- **v1 批次15 搜索命中与 Fetch 反爬优化完成（PRD docs/prd-search-fetch-enhancement.md，guardrail 两轮通过（M-1 WebView SSRF/M-2 明文可诊断/L-2 边界前缀全部修复）+ ac-verifier 8/8 PASS（US-1501~1508）+ 全量回归 2522 用例 0 失败 + lint 0 errors）**（2026-09-02）—— 依据用户报告（web_search 命中率不高、Fetch 常被反爬限制、无法访问外网致信息不全、引用来源混乱）+ GitHub 一手调研（docs/reports/2026-09-02-search-fetch-enhancement-research.md，用户决策：搜索侧 = 智谱+博查+Tavily+SearXNG 自建，open-webSearch/火山方舟/腾讯云 wsa 不做）落地：
  - **A（搜索引擎收敛）**：[WebSearchLocalToolExecutor] 引擎链升级 **Bocha→智谱（web_search REST，apiKeyRef=zhipu）→SearXNG（用户自建端点+可选 Basic Auth）→Tavily（apiKeyRef=tavily）→Bing/Baidu HTML 兜底**，首个成功即返回（预算护栏不变）；设置页新增「搜索增强」区块（智谱/Tavily Key + SearXNG 端点三字段）；显式引擎 `web_search__zhipu`/`web_search__tavily` 可路由（未注册进 buildTools，经 web_search__search 自动路由）
  - **B（引用治理）**：全引擎结果统一【编号来源】`[N] title — url` 注入 + 工具描述/【引用要求】硬约束「引用必须使用编号来源原始 URL，禁止拼凑/改写/编造」；无 Key 且全不相关 → `错误：` 搜索增强引导文案（US-1504，替代旧死胡同）；Fetch 成功回灌（直抓/Jina/WebView）统一前置【外部内容】不可信边界（L-2 修复）
  - **C（抓取增强）**：`extractReadableText` 换 **jsoup 1.18.3 + Readability4J 1.0.8**（均 Maven Central，Apache-2.0/MIT，APK 估算增量 debug ~0.5MB/release ~0.3MB），小文档（<500 字符）正则优先策略、正则版保留为降级兜底；新增 **WebView 渲染抓取第三级降级**（[WebViewFetchRenderer]：403/503/空壳触发，offscreen WebView + Chrome UA + 15s 超时 + evaluateJavascript 取 DOM → Readability 提纯；默认关 `settings_webview_fetch_enabled`，PrismApplication 生产接线）
  - **D（批次C）**：McpServerPresets 新增 **Scrapling / crawl4ai 局域网自建抓取中转远程 MCP 模板**（`http://<PC-IP>:<PORT>/mcp` 占位不虚构端口；架构红线语义入 description：服务端返回渲染后正文/Markdown，禁止 cookie 回传复放——JA4 指纹复检实证）
  - **guardrail/ac-verifier 闭环**：TKN-V1B15-GUARDRAIL-001 第一轮 0 阻断/0 高危（M-1 WebView 页内导航/302 终态无 SSRF 复验 → `shouldOverrideUrlLoading` 拦截 + `isFinalUrlAllowed` 终态复验（字符串级无 DNS，主线程安全）+ 20 组红线断言；M-2 局域网明文 http 被 network_security_config 拦截不可诊断 → fetchViaSearxng 独立 catch UnknownServiceException 专属日志 + runbook 第 6 节 adb reverse/https 反代/Tailscale 三解法 + 模板 keyHint 标注（不放宽明文基线）；L-2 边界前缀修复）→ 002 复审通过；TKN-V1B15-ACCEPTANCE-001 8/8 PASS。新增行为规则 BR-network-003（WebView 主框架导航/终态 URL 公网 https 校验）/ BR-network-004（自建明文端点可诊断分支+绕行文档）。新增 runbook docs/runbooks/searxng-selfhost.md（json 格式默认关闭否则 403 + 大陆引擎清单）。版本号仍 v1.0.0（versionCode 2 未提版）
  - **已知限制（后续迭代/真机补测）**：SearXNG Basic Auth 密码 DataStore 明文（后续迁 Keystore）；`SearchEnhancementConfigRepository` 无独立单测；WebView 端到端渲染链路 Robolectric 不可测（真机 PoC：3 CF 站 + 3 JS 渲染站）；智谱/Tavily/SearXNG 真实端点解析、Bocha 免费配额、cleartext 日志实际输出待真机；APK 增量数值待 assembleRelease 实测

- **v1 批次14 手机操控保活策略修订完成（ADR-041，guardrail 两轮通过（M-1 TOCTOU 修复/M-2 豁免落定）+ ac-verifier 7/7 AC PASS + 全量回归 2475 用例 0 失败 + lint 0 errors + debug APK 构建安装成功 + 真机预验证通过）**（2026-08-23）—— 依据真机用户报告（不使用软件时仍持续弹出「Prism 正在操控手机」常驻通知、后台不间断运行致整机卡顿）+ 真机 dumpsys 取证（docs/reports/2026-08-23-keepalive-bug-debug.md：保活前台服务常驻 **1d8h10m**、通知 ONGOING|NO_CLEAR 不间断、约 1h49m 前被杀经 START_STICKY 自动重启、进程永驻 BFGS）根治批次11 F2 的实现偏差（设计注释「操控期间才启用」vs 实现「无障碍连上即常驻」）：
  - **A（任务期动态保活）**：新增 [PhoneControlSessionManager] 进程级状态机——[PhoneControlLocalToolExecutor.execute] 入口每次调用刷新活跃时间戳，首个 `phone_control__*` 调用启动保活 FGS 并排定空闲检查，空闲满 **120s**（用户确认值）自动停止；`onServiceConnected` 移除无条件启动（根因消除点）；onDestroy reset + stop 双保险。任务进行中防 MIUI 回收能力保持不变（批次10/11 目标不受损）
  - **B（消除「杀不死」循环）**：KeepAliveService `START_STICKY → START_NOT_STICKY`；start() catch 区分 `ForegroundServiceStartNotAllowedException`（Android 12+ 后台启动限制）专属可诊断日志，降级为无保活继续任务不中断
  - **C（卡顿根治）**：`phone_control_accessibility.xml` 事件订阅收窄为 `typeWindowStateChanged`（处理器唯一消费类型；被裁掉的 typeWindowContentChanged/typeWindowsChanged 从未被消费，纯 binder IPC 开销——XDA 对 LastPass 类卡顿的同类根因分析）+ notificationTimeout 100→300ms；UI 树读取走 rootInActiveWindow 实时查询 + currentRoot 三级兜底均不依赖事件流，功能零损失
  - **guardrail/ac-verifier 闭环**：TKN-V1B14-GUARDRAIL-001 第一轮有条件通过（M-1 中危 TOCTOU 锁外预检+锁内 `!!` → 锁内单次取值判空修复 + 竞态回归用例；M-2 中危 429 退避窗口≈180s>120s 保活真空 → 方案(c) ADR 已知限制豁免记录；L-2 补测 8/8）→ 第二轮独立复审通过；TKN-V1B14-ACCEPTANCE-001 7/7 PASS（闲置零占用/任务期保护/自动释放/敏感拦截链零损伤/可诊断降级/XML 收窄零损失/工程门禁）。新增行为规则 BR-ops-005（常驻资源绑定任务活跃期而非能力开关期）。版本号仍 v1.0.0（versionCode 2 未提版）
  - **真机预验证**（小米 HyperOS V816）：debug APK 覆盖安装重绑无障碍后 dumpsys 复查——KeepAlive ServiceRecord 消失、id=2001 常驻通知消失（修复前对照：常驻 32h+/isForeground=true）。已知限制（ADR-041）：429 长退避期保活真空自愈拉起、极端场景首工具调用超豁免窗口降级无保活、陈旧 idle 检查链自终止良性。待真机补测见交付清单

- **v1 批次13 真机 ANR 崩溃根治 + 多模态 + 提速 + E 强化完成（PRD v1 批次11 §6.15 扩展，guardrail 有条件通过（M-2/M-3/L-3 已修复）+ ac-verifier 6 AC PASS + 全量回归 2466 功能用例 0 失败（1 个既有性能基线抖动复跑通过，非回归）+ lint 0 errors + APK 构建成功）**（2026-08-21）—— 依据真机反馈（glm-4.6v-flash 打开拼多多后后续任务无法执行、失败后崩溃闪退、重开历史界面卡顿再崩溃）+ 真机 ANR 日志证据（prism_20260821_054307.log：400KB base64 截图单行渲染阻塞主线程 >5s）+ 用户三问（是否仍走 OCR / 能否提速 / 继续 E）根治：
  - **A（崩溃根治，F1）**：`runScreenshot` 不再内嵌 base64——视觉模型返回「【手机截图图片】+dataUrl」标记（SkillExecutor `extractScreenshotImage` 提取后以 image_url 注入会话，base64 从回灌文本剥离）；纯文本模型返回 OCR 文字+坐标（无 base64）。新增 [ChatMessage.transientImage] 瞬态标记——持久化由 [ChatMessageSerializer] 剥离 imageUrl（防历史 JSON 膨胀 + 切纯文本模型后历史每轮 400）+ UI 跳过瞬态截图主线程解码渲染（M-3）
  - **B（多模态最大化）**：[ProviderConfig.supportsVision] + 设置页开关 + 保存时按模型名自动启用（[detectVisionSupport]，开箱即用，落 supportsVisionSet）+ [PrismApplication] 运行时判定（显式设置优先，否则模型名自动检测）+ 截图免 OCR 直接看真图。**隐私铁门（guardrail BR-security-011）**：supportsVisionSet=true（用户显式关闭）不被自动检测覆盖，防截图静默外发
  - **B2（400 降级链）**：工具回路收到 `visionUnsupported` 错误 → 剥离瞬态图片 + `LocalToolExecutor.onVisionUnsupported()`（新增默认方法，Composite 转发）→ 手机操控截图转 OCR/UI 树（visionDegraded）+ rounds-- 重试本轮（任务继续而非中断）
  - **C（提速）**：视觉模型截图免 OCR；长工具链路只保留最近 1 张瞬态截图参与请求（M-2，防 400KB×N 请求体膨胀）；OCR 40/图标 15 条目上限；上下文去 base64
  - **D（E 强化）**：type 接入 `performWithStateCheck`（输入未生效软提示）+ 软提示附「建议换动作/复验」纠偏引导（不再盲目重复）
  - **guardrail 闭环**：TKN-V1B13-GUARDRAIL-001 有条件通过（0 阻断/高危；M-1 首次自动启用无授权提示——已豁免记录为已知限制；M-2 保留最近 1 张截图、M-3 UI 跳过瞬态渲染、L-3 extractScreenshotImage 仅对 __screenshot 工具检测标记 全部修复）+ ac-verifier 6 AC PASS（196 专项用例 0 失败）。新增行为规则 BR-vision-005 / BR-security-011 / BR-interface-021。版本号仍为 v1.0.0（versionCode 2，未提版）。已知限制（后续迭代）：M-1 首次自动启用无「截图将发送到模型端点」可见提示（隐私知情权待补）、visionDegraded 进程级持久切换模型需重启恢复、visionUnsupported 错误先转发 UI 再降级重试（用户先见错误）。待真机补测：glm-4.6v-flash 真实端点截图图片注入 → 看真图操作、400 降级实际触发、type 软提示正向、历史重开不卡顿

- **v1 批次9 真机 8 项问题修复 + 开箱即用 API/MCP 落地完成（PRD v1 批次9，guardrail 两轮通过 + ac-verifier 7/7 PASS + 全量回归 2361 用例 0 失败 + APK 构建成功 + 模拟器安装验证通过）**（2026-08-20）—— 依据真机日志证据（prism_20260820_045508.log）+ code-archaeologist 考古 + tech-selection-researcher 调研根治 8 项问题：
  - **B1/B7 Fetch+Jina/反爬**：确认 `r.jina.ai` 国内不可达（非响应过大）；新增 **本地 HTML 主干提纯**（`extractReadableText`：article/main/h1-p 提取 + script/nav/footer 剥离，零新依赖，Jina Reader 本地等价物）+ Jina 失败日志标注"国内不可达"区分反爬
  - **B2 搜索命中根治**：新增 **title 强相关判据 `isStrongRelevant`**（主查询/百度兜底用 title 命中，城市页 snippet 含校名不再短路救援链）+ 核心词唯一候选==query 空转修复 + **Bocha REST 优先引擎**（配 Key 即用，AI 原生语义重排，失败静默降级 Bing+Baidu，guardrail H-1 预算窗口修复）
  - **B3 热榜感知**：`mergeSystemPrompt` 新增 `HOTLIST_GUIDANCE` 能力声明（热搜/热点触发词）+ 未配 Key 文案前置 `错误：` failure 标记 + `isFailureResult` 纳入「热榜获取失败」（H-3 熔断闭环）+ buildTools 注册集成测试
  - **B4/B6 SSE 错误处理**：`mapHttpError` 补齐 -1（网络连接中断）/200（协议不匹配）/5xx（服务端错误）可诊断分支；空流（零有意义事件无 DONE）补发"服务端未返回内容"提示不再静默空气泡（H-2 修复：ToolCall*/ReasoningDelta 均视为有意义事件，纯工具流不误判）
  - **B5 模板清理**：移除 Slack/Asana/Exa/Firecrawl/TrendsMCP（Brave 已移除）配置与元数据
  - **US-910 国内 MCP 模板**：新增 Gitee（api.gitee.com/mcp）/ 聚合数据（mcp.juhe.cn/mcp）/ 天行数据 / 智谱 Web Search（Bearer 头注入，guardrail M-2 规避 CWE-598）/ 高德地图（mcp.amap.com/mcp），端点一手核验（Gitee/高德官方文档复核）
  - **guardrail/ac-verifier 闭环**：TKN-V1B9-GUARDRAIL-001/002 两轮（H-1/H-2/H-3/M-1/M-2/M-3/L-3/L-4 全部修复）+ TKN-V1B9-ACCEPTANCE-001 7/7 PASS（新增 20 边界用例）。版本号仍为 v1.0.0（versionCode 2，未提版）。已知限制（后续迭代）：US-907~909（智谱 REST/和风天气/百度翻译）未实现；智谱模板 Bearer 头认证待真机实测；Bocha/热榜/Fetch 真实端点待真机补测

- **v1 批次12 glm 文本工具调用支持完成（PRD v1 批次12，guardrail 两轮（001 阻断→修复→002 通过）+ ac-verifier 6 AC + 4 安全红线 PASS + 全量回归 2451 用例 0 失败 + lint 0 errors）**（2026-08-21）—— 依据真机日志分析（prism_20260821_035600.log：DeepSeek 已流畅用 OCR/UI 树锚点完成任务）+ 用户反馈（glm-4.6v-flash 连工具都无法使用，异常打断）+ prd-v1-b10 §8 增强候选核对，落地 A+B+C+D+E：
  - **A（核心）文本工具调用解析**：glm 等模型不产生原生 tool_calls，把工具调用写成 `<tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value></tool_call>` 文本块（HTML 围栏内）。新增 [TextToolCallParser]（纯函数：围栏/裸块/多块/JSON 参数解析 + stripTextToolCalls）；[SkillExecutor] 无原生 tool_calls 轮次解析执行（复用 executeToolCall 确认/手机操控安全链），结果以【工具执行结果】user 消息回灌（模型无关，不依赖 OpenAI tool 协议）→ 继续回路
  - **B 渲染净化**：[sanitizeToolCallSyntax] 预剥离文本型 `<tool_call>` 块（含围栏），UI 不再显示原始 XML
  - **C（D14）包名映射库**：新增 [PhoneControlPackageMap]（120+ 常用 App 中/英/拼音名 + 别名/错包名纠正，拼多多 com.pinduoduo.pinduoduo→com.xunmeng.pinduoduo）
  - **D（D15）Stuck 检测**（prd §8#1）：getUiState 连续 3 步屏幕签名无变化附恢复引导；launch_app 成功重置
  - **E（D15）before/after 校验**（prd §8#3）：tap/long_press/double_tap/swipe 动作后屏幕签名一致附软提示
  - **guardrail 闭环**：TKN-V1B12-GUARDRAIL-001 **阻断**（P0：包名映射绕过金融 App 启动硬拦截——招商银行 cmb.pb 不在黑名单，prompt 注入可经文本工具调用启动银行 App）→ 修复（黑名单补真实包名 + rawPkg||pkg 双重判定 + 文本结果剥离 + 文本路径熔断）→ 002 通过。ac-verifier 6 AC + 4 安全红线 PASS（新增边界用例 28 个）。新增行为规则 BR-security-009/010。版本号仍为 v1.0.0（versionCode 2，未提版）。已知限制（后续迭代）：glm 真实端点文本工具调用待真机补测、银行黑名单扩充（交行/邮储/浦发等）、isSensitivePackage 大小写归一。待真机补测：glm 打开拼多多搜索下载、DeepSeek 回归、Stuck 恢复引导实际触发
- **v1 批次11 真机 OCR 定位 + 429 限流增强完成（PRD v1 批次11，guardrail 三轮通过（001/002/003）+ ac-verifier 7/7 PASS + 全量回归 2408 用例 0 失败 + lint 通过 + APK 构建成功）**（2026-08-21）—— 依据真机手动测试反馈（① OCR 可用但 LLM 不主动调用且识别不准、无法告诉 LLM 点击位置；② glm-4.6v-flash 429）+ 深度联网调研（GitHub 检索 OmniParser/Open-AutoGLM/MobileAgent-Android/Agent-S/adb-mcp/DroidRun/Appium ocr-click-plugin 等 12+ 项目 + Set-of-Mark/UI-TARS/GUI-Actor 学术）根治：
  - **A 致命：OCR 坐标空间错位**：截图降采样（最长边 ≤1024px）后 OCR 在其上跑，返回坐标是降采样空间，而 tap 在全屏空间执行（差约 2.3 倍 → 全部点错位）。`captureScreenshot` 降采样前记录原始尺寸 + `extractElements` 传 screenWidth/Height + 纯函数 `scaledOcrElement` 按比例还原
  - **B 质量**：OCR 改行级聚合（line 整行 + 行包围盒）+ 置信度过滤（<0.15）+ `[N] 文本（坐标 x,y）` 编号列表（Set-of-Mark 文本版）
  - **C 核心：tap 文本锚点**：tap/long_press/double_tap 新增 `text` 参数，UI 树 `findNodeByTextNid`（BFS 聚合 + `textSimilarity` 模糊匹配）→ OCR `resolveTextAnchor` 兜底 → tap 坐标吸附，LLM 不再猜像素坐标（业界共识：编号/文本命中率远高于裸坐标）
  - **D 引导**：screenshot 工具描述移除"纯文本模型请勿调用"（与空树引导自相矛盾）+ PHONE_CONTROL_GUIDANCE 增 OCR 兜底工作流
  - **F（D12）图标区域检测**：新增 `IconRegionDetector` 纯像素启发式（灰度→Sobel→排除 OCR 文字框→4-连通域→尺寸/面积过滤→前 20），零新依赖/零模型
  - **E（D11）429 增强**：StreamEvent.Error 增 retryAfterSeconds + OpenAICompatibleProvider 解析 Retry-After 头（秒数/HTTP-date，上限 60s）+ SkillExecutor 退避优先 Retry-After、重试 4→6 + 非工具流 executePlainStream 自动退避重试（仅无内容时，幂等安全）+ 耗尽文案明确
  - **guardrail 闭环**：TKN-V1B11-GUARDRAIL-001（1 高危 H-1 文本锚点敏感绕过）/002（残余 R-1 node_id+text 双传）/003（终审通过）——敏感拦截始终用命中目标真实文本（`effectiveTargetText` 优先级链）+ 查询词附加防御 + snapToClickableCenter 过滤敏感候选 + `maskLogText` 日志脱敏（CWE-532）+ 红线测试。ac-verifier 7/7 + 新增边界用例 25 个。新增行为规则 BR-vision-004 / BR-security-008。版本号仍为 v1.0.0（versionCode 2，未提版）。已知限制（后续迭代）：图标检测无模型不识别图标含义、R-2 集成红线待补齐、nodeTextAt 聚合后代待处理、429 最长重试窗口约 3 分钟无进度提示。待真机补测：tap(text="确认") 命中「确认支付」→ ⚠️、node_id+text 双传敏感拦截、runScreenshot 编号列表实际输出、OCR 坐标命中、glm-4.6v 429 自动重试实际触发
- **v1 批次10 真机手机操控修复完成（PRD v1 批次10，guardrail 审查通过 + 手机操控单测通过 + 全量回归 0 失败 + 编译通过）**（2026-08-20）—— 依据真机手动测试反馈（打开应用商店搜索崩坏星穹铁道 → 执行至打开商店超时；打开微信给联系人回复 → 打开后无法感知状态）+ OpenAI/tech-selection-researcher 调研（zai-org/Open-AutoGLM 及 12+ GitHub 项目）根治：
  - **Bug A1 跨 App 无法感知状态（主）**：`getRootInActiveWindow()` 在窗口切换过渡期/新 App 未首绘/FLAG_SECURE 时返回 null → LLM 在 round 级反复 get_ui_state（真机 round=38）→ 轮次耗尽/表现为超时。新增 `currentRoot()`（`rootInActiveWindow` → **lastKnownRoot 缓存** → `getWindows()` 遍历 active 有根窗口 三级兜底）+ `onAccessibilityEvent` 在 `TYPE_WINDOW_STATE_CHANGED` 维护 lastKnownRoot（Android 官方 codelab + Open-AutoGLM 方案）
  - **Bug B 截图 NoSuchMethodException（真机）**：旧实现反射调 `ScreenshotResult.asBitmap()`（@SystemApi 隐藏 API）国产 ROM 未暴露 public → 改为公开 API `Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)` + 复制软位图（API29+ 公开，无 ROM 差异）
  - **Tool 层自愈（纵深）**：`get_ui_state` 瞬时空树/重连中**工具内部重试 4×300ms**（参考 iot-book「判空重试」，杜绝 LLM round 级重试放大）+ `launch_app` 成功后 **settle 700ms** 等目标 App 渲染（参考 Open-AutoGLM `wait_after`，仅成功分支等待）
  - **资源泄漏根治（guardrail）**：HardwareBuffer 双重 try/finally 关闭 + `bitmap.copy` 空判；`lastKnownRoot` 改用 `AccessibilityNodeInfo.obtain(src)` 自持副本（不误回收框架 event.source）+ 替换/销毁/未命中窗口 root 时统一 recycle；`currentRoot` 只对共享缓存返回 obtain 副本 + `getUiTreeText` 用毕 recycle（杜绝 AccessibilityNodeInfo 句柄泄漏 / 并发 UAF）
  - **guardrail 闭环**：TKN-V1B10-GUARDRAIL-001 通过（0 阻断/高/中，1 低风险已顺手修复）；新增单测（重连中重试返回可诊断失败）。版本号仍为 v1.0.0（versionCode 2，未提版）。已知限制（后续迭代，纳入 PRD §8）：Stuck 检测（3 步无变化恢复）/ 前台 App 判定（getWindows root.packageName）/ 动作后 before-after 校验 / 树空视觉兜底 / 120+ 包名映射库 / A11y 文本零信任强化，待真机补测

- **M0 脚手架 + M1 数据层 + 安全层 + BYOK Provider 配置 + 聊天 UI + 流式请求 + Provider 切换 + M2 MCP Client + 内置 Filesystem MCP Server + 预设远程 MCP Server 模板加载已完成（US-001~US-010）**（2026-08-06）
- **M3 个人知识库 RAG 全部完成并通过里程碑交付审计（US-011~US-019，ADR-007~012 全部 Accepted）**（2026-08-09）
- **M4 Skills 系统全部完成（US-020~US-029，ADR-013/014 Accepted，Phase A~E 全部通过 guardrail + ac-verifier，912 回归 0 失败）**（2026-08-10）—— Skill 数据模型 / SKILL.md 解析 / 注册中心 / tool_calling 接口 / 工具执行回路 / Skills 管理 UI / 远程下载 / 执行可观测。已知限制：M-3 GAP（生产路径执行记录未接入，US-029 基础设施已就绪）
- **M5 三层记忆系统全部完成（US-030~US-036，ADR-015 Accepted，1237 全量回归 0 失败）**（2026-08-11）—— L1 滑动窗口压缩 + L2 跨会话向量记忆 + L3 用户画像，systemPrompt 六层合并（RAG → L1 → L2 → L3 → Skill）
- **M6 跨 App 调用集成全部完成（US-037~US-039，ADR-016 Accepted，1380 全量回归 0 失败）**（2026-08-11）—— 7 个目标 App（微信/支付宝/淘宝/抖音/QQ/微博/百度地图），零新增第三方依赖。已知受限：UNC-1 真机 E2E 7 App Deep Link 兼容性待补测
- **M7 设备适配与降级全部完成（US-040~US-043，ADR-017 Accepted，1497 全量回归 0 失败）**（2026-08-11）—— 四档 PerformanceTier（FULL/STANDARD/MINIMAL/CHAT_ONLY）按 RAM 自动降级 + 手动覆盖
- **M8 集成与发布全部完成（US-044~US-047，ADR-018 Accepted，v0.1.0 发布）**（2026-08-12）—— release 签名 + R8 全量启用 + APK 体积分析（78.44MB）+ GitHub Release v0.1.0 + functional-validation-auditor 全面审计
- **P8 深度思考 + 联网搜索完成（ADR-020，1559 回归 0 失败）**（2026-08-14）—— DeepSeek thinking/reasoning_effort 参数 + Bing RSS 零配置联网搜索（WebSearchLocalToolExecutor）
- **UXR1~7 真机迭代修复完成（ADR-021~027，全量回归 1792 用例 0 失败）**（2026-08-16）—— 搜索质量（Bing 冷词分词坍缩 → 多候选核心词短整词降级重试）/ markdown 渲染（0.26.0 无表格组件 → 预处理列表）/ 引用来源（工具调用参数反向映射引用池）/ 工具回路熔断 / 流式渲染 / 会话持久化 / 工具审批模式等
- **UXR8 批次1 修复完成（ADR-028，1810 回归 0 失败）**（2026-08-16）—— RagTarget 持久化（关闭后新对话不再重置）/ L2 跨会话记忆保存生产触发 / 配置弹层键盘顶出修复（OBS-2 双模式终版）
- **UXR8 批次2 优化完成（ADR-029，1873 回归 0 失败）**（2026-08-16）—— O1 L3 画像自然语言化（key 冲突保护）/ O2 MCP 模板 description+keyHint / O3 新模板（Firecrawl/n8n/TrendsMCP）/ O4 Skills（document 工具 + firecrawl/humanizer-zh/web-research 3 新 Skill）/ O5 搜索扩容（10 条 + 多查询合并 16 条 + 预算感知）。G2-05（saveProfile VM 级集成测试）已由批次3 闭环（MemoryManagementViewModelSaveProfileIntegrationTest，6 用例）
- **UXR8 批次3 新功能完成（ADR-030，guardrail 两轮通过 + ac-verifier 3/3，1948 回归 0 失败）**（2026-08-17）—— N1 用户规则文件（UserRulesRepository「关于我+如何回答」双字段，systemPrompt 最高优先级层）/ N2 LLM 反问（Phase1 persona 澄清策略 + Phase2 ask_user__ask 本地工具 + 提问卡片 + StopAtTools 中断回路）/ N3 文本模型视觉（image_url 多模态直传 + 含图 400 降级 + inSampleSize 降采样防 OOM）。G2-05 技术债闭环。已知技术债：图片 base64 随会话 JSON 膨胀（后续可降采样存储）；N3 方案 B（云端旁路+OCR）留作后续迭代
- **UXR9 真机问题修复 + 体验增强完成（ADR-031，guardrail 三论 + ac-verifier 31/33 PASS + 2052 回归 0 失败 + 模拟器验证通过）**（2026-08-18）—— 5 Bug 根治 + 3 体验增强：US-901 RAG 换多语言嵌入模型（paraphrase-multilingual-MiniLM-L12-v2 qint8 113MB + Unigram tokenizer，阈值 0.5 + top-2，**设备端实测摄入嵌入成功**）/ US-902 搜索条目级过滤 / US-903 图片双解码链路（ImageDecoder→BitmapFactory）+ flush 队列 + 失败不触发 LLM（**模拟器实测图片气泡渲染成功**）/ US-904 L2 重要性过滤 + LLM 摘要 + MIN_SUMMARY_TURNS=3 门槛 / US-905 Fetch MCP（expectSuccess + SSRF userinfo/IPv6/IDN + 响应体 1MB 硬上限）/ US-906 发送后收起键盘 / US-907 "＋"折叠栏（相册+文件）+ PPTX 解析 + 文本直发（**模拟器实测折叠栏展开 + 摄入 2 分片**）/ US-908 SkillCallCard 工具卡片（复用 isFailureResult）。已知限制：真机待补（US-903 3 图 / US-905 Fetch / US-906 键盘 / US-907-908 UI 交互）；M-1 旧知识库索引需重建（换模型不兼容）；Q-MED-2 应用内重建提示推迟
- **UXR10 真机二次修复完成（ADR-032，guardrail 审查通过 + ac-verifier 5/5 + 2031 回归 0 失败 + 模拟器验证通过）**（2026-08-18）—— 5 项问题根治：R1 PDF 上传崩溃（桌面 pdfbox3 java.awt → **pdfbox-android 2.0.27.0** + PDFBoxResourceLoader.init + Robolectric 测试，Robolectric 测试指定基础 Application 避免加载 PrismApplication 触发 ObjectBox native 毒化）/ R2 多模态误判（mapHttpError 不再「400+图 一律报不支持图片」，改按服务端错误详情关键词 `isVisionUnsupportedError` 判断 + **429 限流专属文案**）/ R3 Fetch 反爬优化（403/404/429 按状态码可诊断文案 + 显式「勿反复重试」，避免 LLM 无脑重试放大请求频率叠加 kimi RPM=3 限流）/ R4 Skills 感知（内置 Skill 首次安装**默认启用**，用户/远程仍默认禁用，LLM 开箱即感知 web-research 等）/ R5 上传交互（图片/文件选择后**暂存附件草稿** + 预览卡片 + 输入需求后统一发送，图片与文件互斥）。**模拟器实测**：图片选入后草稿预览 + 输入需求发送（气泡 + AI 回复，未误报不支持图片）；PDF 文件选入后草稿预览 + logcat 显示 PdfBox-Android 正常解析、无崩溃。已知限制：R5 附件草稿屏幕旋转丢失（dataUrl 过大不宜 rememberSaveable）；内置 Skill 仅新安装默认启用，已在 UI 禁用过的需手动再启用；真机待补（kimi-k2.6 多模态真实 400 文案 / 429 限流真实触发 / Fetch 真实反爬站点）
- **UXR11 真机问题修复完成（ADR-033，guardrail 审查通过 + ac-verifier 验收 + 全量回归 0 失败 + 模拟器验证通过）**（2026-08-18）—— 7 项问题根治：U1 RAG 误注入第 3 轮（文件上传文本直发 `【文档：` 前缀跳过 RAG 自动注入，needsRagRetrieval 前缀检查）/ U2 搜索限流 429（工具回路轮间退避 2s + `isRateLimitError` 识别 + 429 自动退避重试 3s/6s×2 + 幂等守卫，Kimi RPM=3 缓解）/ U3 Fetch 反爬深度优化（**发现并根治 Ktor 3.x 默认跟随重定向的真实 SSRF Bug**：生产 fetchHttpClient 显式 `followRedirects=false`，浏览器典型请求头 + 手动跟随 3xx 每跳重过 SSRF 校验 + 3 跳上限）/ U4 Fetch 失败后乱码（`sanitizeToolCallSyntax` 缓冲式深度状态机剥离幻觉 `<tool_calls>`/`<invoke>` 块 + 代码围栏感知 + 残余标签转义）/ U5 L2 记忆深度优化（参考 TencentDB-Agent-Memory：`extractMemories` 原子记忆抽取三态——偏好/事实/决策、成功空→不落库、失败→降级逐对存储，`[记忆]` 前缀）/ U6 Skills 调用 UI 反馈（SkillCallCard 不再跳过 web_search，明确「工具被调用 ✓/✕」）/ U7 真机「正在思考」动画（TypingIndicator 加 LaunchedEffect 动态省略号，低帧率设备文字轮换）。**模拟器实测**：APK 安装 + 启动无崩溃 + 聊天 UI 完整渲染。已知限制：真机待补（429 自动重试实际触发 / Fetch 真实反爬站点 / L2 原子记忆实际抽取 / U6-U7 UI 交互）
- **v1 批次1 记忆深度优化完成（ADR-034，guardrail + ac-verifier + 全量回归 0 失败 + 模拟器验证）**（2026-08-19）—— 参照 TencentDB-Agent-Memory 四项深度优化：US-101 原子记忆抽取升级（JSON 结构化 type/priority/sourceMessageIds + MemoryRecord 字段扩展 + ObjectBox 迁移）/ US-102 混合检索（SQLite FTS5 BM25 + 向量 HNSW + RRF(k=60) 融合，系统内置零新依赖）/ US-103 批量去重（store/update/merge/skip 四态 + 失败降级）+ 软衰减（priority×exp(-λ·age)×(1+α·access)）+ 容量回收（上限 10k）/ US-104 注入预算（条数 5 + 字符截断 + 静默降级）。已知技术债：FTS5 中文预分词（CJK 二元组）MRR PoC 门禁待标注集评估
- **v1 批次2 纯文本模型识图完成（ADR-035，guardrail + ac-verifier + 全量回归 0 失败 + 模拟器验证）**（2026-08-19）—— 补全 prd-uxr8 方案 B：US-301 云端视觉旁路 Provider（ProviderConfig.isVisionFallback 角色 + 400+isVisionUnsupportedError 触发链 + 降采样 ≤2048px/JPEG + 熔断 3 次 + 隐私授权设置页常驻 + 首次二次确认）/ US-302 ML Kit OCR 兜底（text-recognition-chinese bundled 离线 + 【图片文字】前缀 + 非空才注入）。已知限制：旁路外发图片需用户明示授权；OCR 仅含文字图片有效；真机待补（kimi 真实 400 文案 / 旁路端到端链路）
- **v1 批次3 LLM 操控手机完成（ADR-036，guardrail 审查 + ac-verifier 验收 + functional-validation-auditor + 全量回归 0 失败 + 模拟器验证）**（2026-08-19）—— 参照 Open-AutoGLM 以 Android 本地能力重建（D-1 无障碍方案非 ADB 移植）：US-201 AccessibilityService UI 树 + 12 工具集（phone_control__*，经 compositeLocalToolExecutor 注入 SkillExecutor）+ 设置页引导开启/用途声明 + Factory 按档位接线 / US-202 敏感拦截三层（金融专用 App 启动硬拦截 + 支付/密码/验证码节点硬拦截 + 发送/删除/拨号等高危强制 MANUAL ask_user 接管）+ UI 文本不可信数据源 systemPrompt 声明 + take_over 人工接管（复用 UXR8 N2 AskUser 协议）/ US-203 截图增强（API30+ 无障碍截图 + N3 降采样链路 + base64 体积上限）/ US-204 性能档位（FULL/STANDARD 启用，MINIMAL/CHAT_ONLY 禁用）+ SkillExecutor.isFailureResult 纳入 `错误：`/`⚠️` 前缀（手机控制失败熔断识别）。**guardrail/ac-verifier 闭环**：I-1 工具接线缺口（运行时 buildTools 未传 phoneControlEnabled）、M-1 node_id 点击绕过（nodeTextOf 子树文本聚合）、M-2 英文关键词+词边界正则、L-1 swipe 起点敏感校验；D1 前台保活 N/A（无障碍系统 BIND 保活）、D2 档位矩阵补测、D3 节点上限 80。**版本号已提升 v1.0.0（versionCode 2）**。已知限制：无障碍服务需系统手动开启；通用模型复杂长链路成功率中；真机待补（无障碍开启后 UI 树/手势实操 / 敏感拦截 / take_over 接管 / 截图 / 10 条高频指令成功率≥70%）
- **v1 批次4 真机二次修复完成（ADR-037，guardrail 通过 + 全量回归 0 失败 + 模拟器验证）**（2026-08-19）—— 6 项问题根治：U1 搜索乱码 + 质量（`sanitizeToolCallSyntax` 开/闭标签正则放宽为 `<[^>\n]{0,8}?(?:tool_calls|invoke)`，容忍词字符分隔，杜绝 `<1tool_calls>` 变体漏配残留乱码）+ 沿用核心词整词降级重试 / U2 Fetch 反爬优化（UA 升级 Chrome/126 + 新增 `Sec-CH-UA` 系列 + 503/200 挑战壳可诊断文案 + `isAntiBotOrEmpty` 内容纯度分级判定，零新依赖，SSRF 逐跳复检保留）/ U3 L2 记忆原子化（参照 TencentDB-Agent-Memory L1-Atom：`isImportantTurnPair` 只放行自我指涉偏好/身份/记忆诉求**且非一次性查询**，剔除"长度≥8/问题即重要"宽泛判定，一次性问答不再沉淀）/ U4 云端视觉旁路可用（`handleVisionUnsupportedError` 放宽 `visionConfig==null` 早退 → 无视觉 Provider 也走本地 OCR 兜底；云端外发仍受授权闸门；设置页明示需配置并标记视觉 Provider）/ U5 手机操控设置界面精简（超长副标题收敛，消除行高膨胀）/ U6 工具循环上限（全局 10→50，手机操控 `phone_control__*` 按工具类别分层放行至 200，`resolveToolLoopMaxRounds`；重复失败熔断保留）。随附修复真机启动崩溃 MemoryRecord.sourceMessageIds 迁移 NULL→可空（ObjectBox 自动迁移新增非空 String 列为 NULL 触发 NPE）。已知限制：真机待补（Bing 关键词命中 / Fetch 真实反爬站点 / 视觉旁路端到端 / 手机操控长链路成功率）。版本号仍为 v1.0.0（versionCode 2，未提版）
- **v1 批次5 真机三次修复完成（ADR-038，guardrail 通过 + ac-verifier 5/5 + 全量回归 0 失败（2286 用例）+ APK 构建成功）**（2026-08-19）—— 5 项问题根治：U1 Fetch 反爬增强（`FETCH_HTTP_HEADERS` 新增 `Referer: https://cn.bing.com/` 贴近"搜索→点进结果"正常来源，降 Referer 校验型 403；SSRF 逐跳复检 + 内容纯度判定原样保留）/ U2 搜索直接命中（无空格中文整句 `stripTrailingQuerySuffix` 剥疑问/泛化后缀 → 剥出实体候选"梧州一中是什么学校"→"梧州一中"，主查询命中 + 实体短整词降级重试，条目过滤按实体保留直接命中）/ U3 视觉旁路 Cloud 启用 + 熔断可恢复（把 Provider 标记 `isVisionFallback` 即同步 `setConsent(true)+setAutoBypassEnabled(true)+resetFailures()` 清熔断——重激活后 Cloud 可重试，不再只剩 OCR）/ U4a 无障碍误报根治（`isEnabledInSystem` 读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 判定系统真实启用，设置页 + 工具执行器均改用，区分"重连中（稍后重试）"与"未启用（引导开启）"，消除微信等打开时误报）+ U4b 高危动作三态（新增 `HighRiskApprovalMode` BLOCK 全拦截/ALLOW 全放行/ASK 逐次确认 + DataStore 持久化 + 设置页三态循环行 + 工具层按策略处置发送/删除/拨号/短信）/ U5 后台确认系统通知（`PhoneControlAskUserNotifier` 高优先级通知+允许/拒绝按钮 + `ConfirmActionReceiver`（exported=false + FLAG_IMMUTABLE）+ 发新先撤旧防陈旧按钮误批 + ConversationViewModel 消费 answers 白名单映射"允许/取消"回灌工具回路 + MainActivity 请求 POST_NOTIFICATIONS + 15s isTyping 等待）。新增测试：搜索实体提取×2 / 高危三态×4 / 通知链 Robolectric×3。版本号仍为 v1.0.0（versionCode 2，未提版）
- **v1 批次7 真机五次修复完成（ADR-040，guardrail 增补通过 + 全量回归 0 失败（2270 用例））**（2026-08-19）—— 针对真机复测仍存的 2 项问题（多次修复仍存在）：U1 **搜索仍无法命中正确网址**（真机证据：单 Bing 源对中文实体排名坍缩，HTML SERP 也只是提高 recall；**新增 Baidu HTML SERP 多引擎兜底**——`fetchBaiduSearch`/`parseBaiduHtml`/`tryBaiduFallback`，Bing 主查询+核心词多候选全不相关/空结果后回退 `https://www.baidu.com/s`，先主查询再逐核心词短整词命中即停，仍复用 `filterRelevantItems` + `hasRequestBudget` 护栏，结果仅回灌 LLM 不进抓取/Intent sink）/ U2 **视觉旁路仍只 OCR**（真机日志 dedicated=true 仍只 `OCR succeeded`——Cloud 被 `isBypassAvailable()` 的 consent 默认 false + 熔断双重锁死；`VisionBypassOrchestrator.resolve` 新增 `isDedicated`：`ConversationViewModel` 以 `findVisionFallback()!=null` 传入 → 专用 Provider **跳过熔断**（激活即每次可重试 Cloud），但 **仍守 `isConsentGiven()` 隐私铁门**（guardrail B-1 阻断项修复：用户设置页撤销授权后不再外发，落 OCR））。新增测试：Baidu 解析/回退场景 golden + 视觉"专用+熔断仍走 Cloud"/"专用+授权撤销→OCR"红线。新增规则 BR-search-003/BR-vision-003。已知限制（后续迭代）：专用端点无退避可能放大限流、Baidu 跳转链接未解码、简称↔全称精确匹配鸿沟、主模型=视觉未打标无授权引导。真机待补：真实"梧州一中"搜索命中校名 / 视觉 `cloud bypass ok provider=` 日志 / 撤销授权后不再外发。版本号仍为 v1.0.0（versionCode 2，未提版）
- **v1 批次8 MCP/API 能力增强完成（PRD MCP/API 增强，guardrail 有条件通过 + 全量回归 0 失败（2319 用例）+ APK 构建成功）**（2026-08-19）—— 针对真机环境海外 MCP 模板不可达，落地 4 项国内替代/增强（docs/prd-mcp-api-enhancement.md）：US-001 **博查 Bocha 远程 MCP 模板**（mcp.bocha.cn/mcp + Bearer，DeepSeek 官方搜索、AI 原生带引用，国内直连；嵌入式 Streamable HTTP 握手 + tools/list + callTool 测试闭环）/ US-002 **今日热榜本地工具**（hotlist__get，tophubdata 两段请求 nodes→nodes/`<hashid>` 解析，独立 expectSuccess=false + 5s client 按状态码输出诊断文案（401 Key 无效/429 限流/5xx），替代海外 TrendsMCP；mcp-trends-hub 因 stdio 无法 Android 接入弃用）/ US-003 **Jina Reader 抓取增强**（fetch useJinaReader 参数走 r.jina.ai/<url> 转 Markdown 补 JS 渲染/反爬短板，目标 URL 仍过 SSRF，失败降级直抓）/ US-004 **海外模板标注 + 移除 Brave**（网络不可用提示 PrismWarning 展示，Brave 免费档 2025 底已取消移除）。**guardrail 闭环**：M-1 热榜 expectSuccess 漂移（ADR-032 R2 同款，独立 client 修复 + 新增 BR-network-002）、M-2 Jina 失败降级直抓、M-3 Bocha 握手测试补齐。已知限制：博查/热榜真实端点待真机补测（需填 Key）。版本号仍为 v1.0.0（versionCode 2，未提版）
- **v1 批次6 真机四次修复完成（ADR-039，guardrail 通过 + ac-verifier 3/3 + 全量回归 0 失败（约 2295 用例）+ APK 构建成功）**（2026-08-19）—— 依据真机 logcat 证据（prism_20260819_215452.log）推翻上轮"尽力而为"假设后根治：U1 **Fetch 明文被拦**（真机 `UnknownServiceException` 为 Android 明文 http 被网络安全策略拦截**而非反爬**；`fetchUrl` 公网 http 先升级 https 再 SSRF 复检抓取，**不**放宽全局明文；失败日志补 `sanitizeUrlForLog`（剥 query/fragment/userinfo 凭证，CWE-532））/ U2 **搜索仍无法直接命中**（证据：Bing `format=rss` 对"梧州市第一中学"连精确校名都只返回市级百科，且上一轮 `stripTrailingQuerySuffix` 误把校名剥成"梧州市第一"——后缀表误含"中学/大学/学校/公司"等实体词已移除；**改解析 Bing HTML SERP**（`parseBingHtml` 提取 `li.b_algo` title/href/snippet + `decodeBingUrl` 解码 ck 跳转直链，实测校名命中学校官网），保持完整实体）/ U3 **视觉旁路仍只 OCR**（`findVisionFallback() ?: activeProviderFlow.value` 无独立视觉 Provider 时回退主 Provider 作图像描述端点，对齐"激活视觉模型"预期；**根治静默吞失败**——`cloudDescriber` 与 VM 补 `cloud bypass ok/failed provider=`/`vision bypass: dedicated= cloudConfig=` 诊断日志，真机可定位；图像外发仍受 consent 闸门）。新增测试：HTML 解析/decodeBingUrl/校名 golden/后缀不误剥×7 + Fetch https 升级/sanitize×2。已知待真机补测：三条新增日志（fetch failed url= / cloud bypass provider= / vision bypass cloudConfig=）实际输出。版本号仍为 v1.0.0（versionCode 2，未提版）

### 里程碑明细

| 里程碑 | 内容 | 验收 | 日期 |
|---|---|---|---|
| M0-M2 | 脚手架 / 数据层 / 安全层 / BYOK / 聊天 UI / 流式 / MCP Client / Filesystem MCP / 远程模板 | US-001~US-010 guardrail + ac-verifier | 2026-08-06 |
| M3 | 个人知识库 RAG（文档解析→切片→嵌入→向量检索→引用） | US-011~US-019，ADR-007~012 Accepted | 2026-08-09 |
| M4 | Skills 系统（SKILL.md 解析 / 注册 / tool_calling / 执行回路 / UI / 远程下载 / 可观测） | US-020~US-029，912 回归 0 失败 | 2026-08-10 |
| M5 | 三层记忆系统（L1 滑动窗口 + L2 跨会话 + L3 画像 + 管理 UI） | US-030~US-036，1237 回归 0 失败 | 2026-08-11 |
| M6 | 跨 App 调用（Deep Link / Share Sheet / Picker / 用户确认） | US-037~US-039，1380 回归 0 失败 | 2026-08-11 |
| M7 | 设备适配与降级（四档 PerformanceTier） | US-040~US-043，1497 回归 0 失败 | 2026-08-11 |
| M8 | 集成与发布（release 签名 / R8 / GitHub Release v0.1.0） | US-044~US-047，functional-validation-auditor | 2026-08-12 |
| P8 | 深度思考 + 联网搜索 | ADR-020，1559 回归 0 失败 | 2026-08-14 |
| UXR1-7 | 真机迭代修复（搜索/渲染/引用/工具回路/UI） | ADR-021~027，1792 回归 0 失败 | 2026-08-16 |
| UXR8-B1 | 批次1 修复（RagTarget 持久化 / L2 触发 / 弹层键盘） | ADR-028，ac-verifier 19/19，1810 回归 0 失败 | 2026-08-16 |
| UXR8-B2 | 批次2 优化（L3 画像自然语言 / MCP 模板增强 / Skills / 搜索扩容） | ADR-029，ac-verifier 17/17，1873 回归 0 失败 | 2026-08-16 |
| UXR8-B3 | 批次3 新功能（N1 用户规则文件 / N2 LLM 反问 / N3 文本模型视觉） | ADR-030，guardrail 两轮通过 + ac-verifier 3/3，1942 回归 0 失败 | 2026-08-17 |
| UXR9 | 真机问题修复（RAG 换多语言模型 / 搜索过滤 / 图片修复 / L2 记忆 / Fetch）+ 体验增强（键盘收起 / ＋折叠栏上传 / Skills 反馈） | ADR-031，guardrail 三轮 + ac-verifier 31/33 + 2052 回归 0 失败 + 模拟器验证 | 2026-08-18 |
| UXR10 | 真机二次修复（PDF 崩溃换 pdfbox-android / 多模态误判 / Fetch 反爬 + 429 限流 / Skills 默认启用 / 上传附件草稿） | ADR-032，guardrail 审查通过 + ac-verifier 5/5 + 2031 回归 0 失败 + 模拟器验证 | 2026-08-18 |
| UXR11 | 真机三次修复（RAG 误注入 / 搜索 429 自动重试 / Fetch 反爬+重定向 SSRF / 乱码净化 / L2 原子记忆 / Skills 反馈 / 思考动画） | ADR-033，guardrail 审查通过 + ac-verifier 验收 + 全量回归 0 失败 + 模拟器验证 | 2026-08-18 |
| v1-B1 | 记忆深度优化（原子抽取 / 混合检索 / 去重 / 软衰减 / 预算） | ADR-034，guardrail + ac-verifier + 全量回归 0 失败 + 模拟器验证 | 2026-08-19 |
| v1-B2 | 纯文本模型识图（方案 B：云端视觉旁路 + OCR 兜底） | ADR-035，guardrail + ac-verifier + 全量回归 0 失败 + 模拟器验证 | 2026-08-19 |
| v1-B3 | LLM 操控手机（无障碍服务 + 工具集 + 敏感拦截 + 截图增强 + 档位适配） | ADR-036，guardrail 审查 + ac-verifier 验收 + functional-validation-auditor + 全量回归 0 失败 + 模拟器验证 | 2026-08-19 |
| v1-B4 | 真机二次修复（搜索乱码+质量 / Fetch 反爬 / L2 记忆原子化 / 视觉旁路 / 工具循环上限） | ADR-037，guardrail 通过 + 全量回归 0 失败 + 模拟器验证 | 2026-08-19 |
| v1-B5 | 真机三次修复（Fetch Referer / 搜索实体提取 / 视觉旁路熔断恢复 / 无障碍系统判定+高危三态 / 后台确认通知） | ADR-038，guardrail 通过 + ac-verifier 5/5 + 全量回归 0 失败（2286）+ APK 构建成功 | 2026-08-19 |
| v1-B6 | 真机四次修复（Fetch 明文拦截 http→https / 搜索 RSS→HTML SERP+后缀误剥 / 视觉旁路 Provider 回退+可观测日志） | ADR-039，guardrail 通过 + ac-verifier 3/3 + 全量回归 0 失败（约2295）+ APK 构建成功 | 2026-08-19 |
| v1-B7 | 真机五次修复（搜索 Bing→Baidu 多引擎回退 / 视觉专用 Provider 跳过熔断但守 consent 隐私铁门） | ADR-040，guardrail 增补通过 + 全量回归 0 失败（2270） | 2026-08-19 |
| v1-B8 | MCP/API 能力增强（博查 Bocha 模板 / 今日热榜本地工具 / Jina Reader 抓取增强 / 海外模板标注+移除 Brave） | PRD MCP/API 增强，guardrail 有条件通过（M-1/M-2/M-3 已修复）+ 全量回归 0 失败（2319） | 2026-08-19 |
| v1-B9 | 真机 8 项问题修复 + 开箱即用 API/MCP 落地（搜索 title 强相关+Bocha REST / Fetch 本地 HTML 提纯 / 热榜感知 / SSE 错误处理 / 移除 5 海外模板+新增 5 国内模板） | PRD v1 批次9，guardrail 两轮通过 + ac-verifier 7/7 PASS + 全量回归 0 失败（2361）+ APK 构建 + 模拟器验证 | 2026-08-20 |
| v1-B10 | 真机手机操控修复（跨 App 无法感知状态 / 截图 NoSuchMethodException / 资源泄漏 + get_ui_state 自愈重试 + launch_app settle） | PRD v1 批次10，guardrail 审查通过 + 手机操控单测通过 + 全量回归 0 失败 + 编译通过 | 2026-08-20 |
| v1-B11 | 真机 OCR 定位 + 429 限流增强（OCR 坐标空间还原 / 行级聚合+编号 / tap 文本锚点 / 主动调用引导 / 图标区域检测 / 429 Retry-After+重试6+非工具流重试） | PRD v1 批次11，guardrail 三轮通过 + ac-verifier 7/7 PASS + 全量回归 2408 用例 0 失败 + lint 通过 + APK 构建成功 | 2026-08-21 |
| v1-B12 | glm 文本工具调用支持 + 包名映射库 + Stuck 检测 + before/after（文本型 <tool_call> 解析执行 / 渲染净化 / 120+ 包名纠正 / 连续无变化恢复 / 动作后校验） | PRD v1 批次12，guardrail 两轮（001 阻断→修复→002 通过）+ ac-verifier 6 AC+4 安全红线 PASS + 全量回归 2451 用例 0 失败 + lint 0 errors | 2026-08-21 |
| v1-B13 | 真机 ANR 崩溃根治 + 多模态 + 提速 + E 强化（runScreenshot 去 base64 / transientImage 持久化剥离 / supportsVision 多模态 image_url 注入+隐私铁门 / 400 visionUnsupported 降级链 / 保留最近 1 张截图 / type before-after+纠偏引导） | PRD v1 批次11 §6.15 扩展，guardrail 有条件通过（M-2/M-3/L-3 已修复）+ ac-verifier 6 AC PASS + 全量回归 2466 功能用例 0 失败 + lint 0 errors + APK 构建成功 | 2026-08-21 |
| v1-B14 | 手机操控保活策略修订（任务期动态保活 PhoneControlSessionManager / START_NOT_STICKY / FGS 后台启动可诊断降级 / 无障碍事件订阅收窄 typeWindowStateChanged+300ms） | ADR-041，guardrail 两轮通过（M-1 TOCTOU/M-2 豁免）+ ac-verifier 7/7 PASS + 全量回归 2475 用例 0 失败 + lint 0 errors + debug APK 安装 + 真机预验证通过 | 2026-08-23 |
| v1-B15 | 搜索命中与 Fetch 反爬优化（智谱/Tavily/SearXNG 引擎链 + 引用编号强约束 + Readability4J 提纯换库 + WebView 渲染第三级降级 + Scrapling/crawl4ai 自建中转模板） | PRD docs/prd-search-fetch-enhancement.md，guardrail 两轮通过（M-1/M-2/L-2 修复）+ ac-verifier 8/8 PASS + 全量回归 2522 用例 0 失败 + lint 0 errors | 2026-09-02 |

## 用户故事清单

### M4 Skills（US-020~US-029）

- US-020 Skill 数据模型（SkillConfig 实体 + SkillRepository CRUD）✅
- US-021 SKILL.md 解析器（snakeyaml-engine-kmp 4.0.1 + 安全 LoadSettings）✅（BR-security-004 转 active）
- US-022 SkillRegistry（扫描/去重/同步/过滤 + 5 内置 Skill + PrismApplication 集成）✅（BR-testing-004 转 active）
- US-023 StreamEvent/ChatStreamProvider 接口扩展预留 ✅（BR-naming-001 转 active）
- US-024 OpenAICompatibleProvider tool_calling 协议 ✅
- US-025 SkillExecutor 工具执行回路（maxRounds 10 + 用户确认 + 30s 超时 + 错误回灌）✅
- US-026 ConversationViewModel Skill 注入与工具执行回路集成 ✅
- US-027 Skills 管理 UI 重构 ✅
- US-028 远程 Skill 下载（HTTPS + 9 层安全校验 + zip slip 防护）✅
- US-029 Skill 执行可观测（SkillExecutionRecord + 详情页）✅（已知限制：M-3 GAP 生产路径未接入）

### M5 三层记忆（US-030~US-036）

- US-030 MemoryRecord + MemoryRepository CRUD/向量检索（L2）✅
- US-031 UserProfile + UserProfileRepository CRUD/upsert（L3）✅
- US-032 L1 滑动窗口记忆（ConversationSummarizer + SlidingWindowMemoryManager）✅
- US-033 L2 跨会话记忆检索（CrossSessionMemoryManager）✅
- US-034 L3 用户画像管理（显式偏好 + 隐式抽取）✅
- US-035 ConversationViewModel 三层记忆集成 ✅
- US-036 记忆管理 UI ✅

### M6 跨 App 调用（US-037~US-039）

- US-037 M6 Phase A CrossAppLauncher 核心模块 ✅
- US-038 M6 Phase B LocalToolExecutor AI 集成层 ✅
- US-039 M6 Phase C UI 集成层 ✅

### M7 设备适配（US-040~US-043）

- US-040 Phase A 核心适配层（PerformanceTier + TierManager）✅
- US-041 Phase B 集成层（PrismApplication 注入 + OnnxEmbedder 闲置卸载）✅
- US-042 Phase C UI 层（SettingsScreen 档位 UI）✅
- US-043 Phase D 构建层（abiFilters arm64 + armeabi-v7a，APK 减约 40%）✅

### M8 集成与发布（US-044~US-047）

- US-044 Phase A release keystore + R8 全量启用 + ProGuard 15 章节 keep 规则 ✅
- US-045 Phase B assembleRelease + 签名验证 + 全量回归 ✅
- US-046 Phase C git tag v0.1.0 + GitHub Release ✅
- US-047 Phase D functional-validation-auditor 全面审计 ✅

### M3 知识库 RAG（US-011~US-019）

- US-011 依赖落地 + KnowledgeChunk 向量索引 ✅
- US-012 文档解析器（PDF/DOCX/XLSX/MD/TXT）✅
- US-013 文本切片器（段落边界优先 + overlap）✅
- US-014 端侧嵌入引擎（onnxruntime-android + all-MiniLM-L6-v2 INT8）✅
- US-015 知识库分库数据模型 ✅
- US-016 摄入管线（解析→切片→嵌入→入库 + Flow 进度观察）✅
- US-017 向量检索（HNSW top-k + 分库过滤）✅
- US-018 知识库管理 UI ✅
- US-019 RAG 对话集成（RagContextBuilder + Citation 引用标注 + 三级降级）✅

### v1 新功能（US-101~104 / US-301~302 / US-201~204）

- US-101 记忆原子抽取升级（JSON 结构化 type/priority + MemoryRecord 字段扩展 + ObjectBox 迁移）✅
- US-102 记忆混合检索（SQLite FTS5 BM25 + 向量 HNSW + RRF(k=60) 融合）✅
- US-103 批量去重（四态 + 失败降级）+ 软衰减 + 容量回收 ✅
- US-104 记忆注入预算（条数 5 + 字符截断 + 静默降级）✅
- US-301 云端视觉旁路 Provider（isVisionFallback 角色 + 400 触发链 + 熔断 + 隐私授权）✅
- US-302 ML Kit OCR 兜底（bundled 离线 + 【图片文字】前缀 + 非空才注入）✅
- US-201 无障碍服务 + 手机控制工具集（AccessibilityService + phone_control__* 12 工具 + 设置页引导）✅
- US-202 敏感操作硬拦截 + 人工接管（金融 App/支付/密码/验证码硬拦截 + 高危强制 MANUAL + take_over + UI 文本不可信声明）✅
- US-203 截图增强（API30+ 无障碍截图 + N3 降采样 + base64 上限 + 工具描述引导）✅
- US-204 性能档位适配（FULL/STANDARD 启用，MINIMAL/CHAT_ONLY 禁用 + Factory 接线）✅

## 平台与产品定位

- 平台：仅 Android（API 26+，Android 8.0+）
- 算力：纯云端 BYOK（用户自配 OpenAI/Claude/Ollama 等端点）
- 商业模式：个人开源免费 + 自发布（GitHub Releases / F-Droid / PGY）
- 协议：Apache 2.0
- 技术栈：见 [ADR-001](docs/decisions/ADR-001-prism-tech-stack.md)

## 文档索引（Diátaxis）

### Tutorial（教程）

- [README.md](README.md) —— 产品介绍与快速开始（新人入门入口）

### How-to Guide（操作指南）

- [docs/templates/](docs/templates/README.md) —— PRD / ARCH / ADR / Task 等模板

### Explanation（解释说明 / ADR）

- [docs/decisions/](docs/decisions/README.md) —— 架构决策记录（ADR-001~ADR-027，状态与摘要见 [docs/decisions/README.md](docs/decisions/README.md)）

### Reference（参考 / 报告）

- [docs/PRD.md](docs/PRD.md) —— 产品需求文档 v0.1
- [docs/prd-uxr8.md](docs/prd-uxr8.md) —— UXR8 需求与执行方案（遗留 Bug + 优化 + 新功能）
- [docs/prd-v1-features.md](docs/prd-v1-features.md) —— v1 新功能 PRD（记忆深度优化 / LLM 操控手机 / 纯文本识图方案 B，调研+方案确认中）
- [docs/prd-mcp-api-enhancement.md](docs/prd-mcp-api-enhancement.md) —— MCP/API 能力增强 PRD（v1 批次8：Bocha 模板 / 热榜工具 / Jina Reader / 海外模板标注）
- [docs/prd-open-box-api-mcp-enhancement.md](docs/prd-open-box-api-mcp-enhancement.md) —— 开箱即用 API 与 MCP 增强 PRD（筛选国内可用 API/MCP 优化现有功能，方案待确认）
- [docs/prd-v1-b9-fixes.md](docs/prd-v1-b9-fixes.md) —— v1 批次9 真机问题修复 + 开箱即用 API/MCP 落地 PRD（8 项真机问题 + US-001~006）
- [docs/prd-v1-b10-phone.md](docs/prd-v1-b10-phone.md) —— v1 批次10 手机操控修复 PRD
- [docs/prd-v1-b11-phone.md](docs/prd-v1-b11-phone.md) —— v1 批次11 OCR 定位 + 429 限流增强 PRD
- [docs/prd-search-fetch-enhancement.md](docs/prd-search-fetch-enhancement.md) —— 搜索命中与 Fetch 反爬优化 PRD（v1 批次15：智谱/Tavily/SearXNG 引擎链 + Readability4J + WebView 降级 + 抓取中转模板）
- [prd.json](prd.json) —— Ralph 格式任务分解
- [docs/reports/](docs/reports/README.md) —— 调研、考古、审查与验收报告（一次性工件，不入库）

### Runbook（运维知识库）

- [docs/runbooks/searxng-selfhost.md](docs/runbooks/searxng-selfhost.md) —— SearXNG 自建搜索部署与 Prism 接入（Docker + json 格式 + 大陆引擎清单 + adb reverse 绕明文限制）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）
