# AGENTS.md —— 项目进度记录（面向 AI Agent 与开发者）

> 本文件记录 Prism 项目的开发进度、里程碑、用户故事清单与文档索引，供 AI Agent 与协作开发者快速了解项目状态。
> **产品介绍请阅读 [README.md](README.md)。** 本文件是进度与治理记录，不是产品文档。

## 项目状态

> 进度记录随开发持续更新。里程碑/用户故事均需通过 guardrail-enforcer 审查 + ac-verifier 验收方可标记完成。

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
- **v1 批次3 LLM 操控手机完成（ADR-036，guardrail 审查 + ac-verifier 验收 + functional-validation-auditor + 全量回归 0 失败 + 模拟器验证）**（2026-08-19）—— 参照 Open-AutoGLM 以 Android 本地能力重建（D-1 无障碍方案非 ADB 移植）：US-201 AccessibilityService UI 树 + 12 工具集（phone_control__*，经 compositeLocalToolExecutor 注入 SkillExecutor）+ 设置页引导开启/用途声明 + Factory 按档位接线 / US-202 敏感拦截三层（金融专用 App 启动硬拦截 + 支付/密码/验证码节点硬拦截 + 发送/删除/拨号等高危强制 MANUAL ask_user 接管）+ UI 文本不可信数据源 systemPrompt 声明 + take_over 人工接管（复用 UXR8 N2 AskUser 协议）/ US-203 截图增强（API30+ 无障碍截图 + N3 降采样链路 + base64 体积上限）/ US-204 性能档位（FULL/STANDARD 启用，MINIMAL/CHAT_ONLY 禁用）+ SkillExecutor.isFailureResult 纳入 `错误：`/`⚠️ ` 前缀（手机控制失败熔断识别）。**guardrail/ac-verifier 闭环**：I-1 工具接线缺口（运行时 buildTools 未传 phoneControlEnabled）、M-1 node_id 点击绕过（nodeTextOf 子树文本聚合）、M-2 英文关键词+词边界正则、L-1 swipe 起点敏感校验；D1 前台保活 N/A（无障碍系统 BIND 保活）、D2 档位矩阵补测、D3 节点上限 80。**版本号已提升 v1.0.0（versionCode 2）**。已知限制：无障碍服务需系统手动开启；通用模型复杂长链路成功率中；真机待补（无障碍开启后 UI 树/手势实操 / 敏感拦截 / take_over 接管 / 截图 / 10 条高频指令成功率≥70%）
- **v1 批次4 真机二次修复完成（ADR-037，guardrail 通过 + 全量回归 0 失败 + 模拟器验证）**（2026-08-19）—— 6 项问题根治：U1 搜索乱码 + 质量（`sanitizeToolCallSyntax` 开/闭标签正则放宽为 `<[^>\n]{0,8}?(?:tool_calls|invoke)`，容忍词字符分隔，杜绝 `<1tool_calls>` 变体漏配残留乱码）+ 沿用核心词整词降级重试 / U2 Fetch 反爬优化（UA 升级 Chrome/126 + 新增 `Sec-CH-UA` 系列 + 503/200 挑战壳可诊断文案 + `isAntiBotOrEmpty` 内容纯度分级判定，零新依赖，SSRF 逐跳复检保留）/ U3 L2 记忆原子化（参照 TencentDB-Agent-Memory L1-Atom：`isImportantTurnPair` 只放行自我指涉偏好/身份/记忆诉求**且非一次性查询**，剔除"长度≥8/问题即重要"宽泛判定，一次性问答不再沉淀）/ U4 云端视觉旁路可用（`handleVisionUnsupportedError` 放宽 `visionConfig==null` 早退 → 无视觉 Provider 也走本地 OCR 兜底；云端外发仍受授权闸门；设置页明示需配置并标记视觉 Provider）/ U5 手机操控设置界面精简（超长副标题收敛，消除行高膨胀）/ U6 工具循环上限（全局 10→50，手机操控 `phone_control__*` 按工具类别分层放行至 200，`resolveToolLoopMaxRounds`；重复失败熔断保留）。随附修复真机启动崩溃 MemoryRecord.sourceMessageIds 迁移 NULL→可空（ObjectBox 自动迁移新增非空 String 列为 NULL 触发 NPE）。已知限制：真机待补（Bing 关键词命中 / Fetch 真实反爬站点 / 视觉旁路端到端 / 手机操控长链路成功率）。版本号仍为 v1.0.0（versionCode 2，未提版）
- **v1 批次5 真机三次修复完成（ADR-038，guardrail 通过 + ac-verifier 5/5 + 全量回归 0 失败（2286 用例）+ APK 构建成功）**（2026-08-19）—— 5 项问题根治：U1 Fetch 反爬增强（`FETCH_HTTP_HEADERS` 新增 `Referer: https://cn.bing.com/` 贴近"搜索→点进结果"正常来源，降 Referer 校验型 403；SSRF 逐跳复检 + 内容纯度判定原样保留）/ U2 搜索直接命中（无空格中文整句 `stripTrailingQuerySuffix` 剥疑问/泛化后缀 → 剥出实体候选"梧州一中是什么学校"→"梧州一中"，主查询命中 + 实体短整词降级重试，条目过滤按实体保留直接命中）/ U3 视觉旁路 Cloud 启用 + 熔断可恢复（把 Provider 标记 `isVisionFallback` 即同步 `setConsent(true)+setAutoBypassEnabled(true)+resetFailures()` 清熔断——重激活后 Cloud 可重试，不再只剩 OCR）/ U4a 无障碍误报根治（`isEnabledInSystem` 读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 判定系统真实启用，设置页 + 工具执行器均改用，区分"重连中（稍后重试）"与"未启用（引导开启）"，消除微信等打开时误报）+ U4b 高危动作三态（新增 `HighRiskApprovalMode` BLOCK 全拦截/ALLOW 全放行/ASK 逐次确认 + DataStore 持久化 + 设置页三态循环行 + 工具层按策略处置发送/删除/拨号/短信）/ U5 后台确认系统通知（`PhoneControlAskUserNotifier` 高优先级通知+允许/拒绝按钮 + `ConfirmActionReceiver`（exported=false + FLAG_IMMUTABLE）+ 发新先撤旧防陈旧按钮误批 + ConversationViewModel 消费 answers 白名单映射"允许/取消"回灌工具回路 + MainActivity 请求 POST_NOTIFICATIONS + 15s isTyping 等待）。新增测试：搜索实体提取×2 / 高危三态×4 / 通知链 Robolectric×3。版本号仍为 v1.0.0（versionCode 2，未提版）
- **v1 批次7 真机五次修复完成（ADR-040，guardrail 增补通过 + 全量回归 0 失败（2270 用例））**（2026-08-19）—— 针对真机复测仍存的 2 项问题（多次修复仍存在）：U1 **搜索仍无法命中正确网址**（真机证据：单 Bing 源对中文实体排名坍缩，HTML SERP 也只是提高 recall；**新增 Baidu HTML SERP 多引擎兜底**——`fetchBaiduSearch`/`parseBaiduHtml`/`tryBaiduFallback`，Bing 主查询+核心词多候选全不相关/空结果后回退 `https://www.baidu.com/s`，先主查询再逐核心词短整词命中即停，仍复用 `filterRelevantItems` + `hasRequestBudget` 护栏，结果仅回灌 LLM 不进抓取/Intent sink）/ U2 **视觉旁路仍只 OCR**（真机日志 dedicated=true 仍只 `OCR succeeded`——Cloud 被 `isBypassAvailable()` 的 consent 默认 false + 熔断双重锁死；`VisionBypassOrchestrator.resolve` 新增 `isDedicated`：`ConversationViewModel` 以 `findVisionFallback()!=null` 传入 → 专用 Provider **跳过熔断**（激活即每次可重试 Cloud），但 **仍守 `isConsentGiven()` 隐私铁门**（guardrail B-1 阻断项修复：用户设置页撤销授权后不再外发，落 OCR））。新增测试：Baidu 解析/回退场景 golden + 视觉"专用+熔断仍走 Cloud"/"专用+授权撤销→OCR"红线。新增规则 BR-search-003/BR-vision-003。已知限制（后续迭代）：专用端点无退避可能放大限流、Baidu 跳转链接未解码、简称↔全称精确匹配鸿沟、主模型=视觉未打标无授权引导。真机待补：真实"梧州一中"搜索命中校名 / 视觉 `cloud bypass ok provider=` 日志 / 撤销授权后不再外发。版本号仍为 v1.0.0（versionCode 2，未提版）
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
- [prd.json](prd.json) —— Ralph 格式任务分解
- [docs/reports/](docs/reports/README.md) —— 调研、考古、审查与验收报告（一次性工件，不入库）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）
