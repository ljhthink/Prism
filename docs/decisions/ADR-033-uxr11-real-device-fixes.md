# ADR-033: UXR11 真机问题修复（RAG 误注入 / 搜索限流 / Fetch 反爬 / 乱码 / L2 记忆 / Skills 反馈 / 思考动画）

> 实现 UXR11（2026-08-18 真机手动测试 7 项问题）架构决策：根治 RAG 知识库误注入、连续搜索 429 限流、
> Fetch 反爬不可用、Fetch 失败后乱码、L2 记忆"什么都记"、LLM 调 Skills 无 UI 反馈、真机"正在思考"动画不动。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-18 |
| 决策者 | 主 Agent + 用户确认（真机测试反馈 7 项，逐一根治） |
| 关联文档 | [PRD](../PRD.md)、[ADR-028 UXR8-R1](ADR-028-uxr8-b1-fixes.md)、[ADR-030 UXR8-B3](ADR-030-uxr8-b3-new-features.md)、[ADR-031 UXR9](ADR-031-uxr9-multilingual-embedding-and-l2-memory.md)、[ADR-032 UXR10](ADR-032-uxr10-real-device-fixes.md)、[ADR-014 M4 工具回路](ADR-014-m4-skills-system.md) |
| 风险等级 | P1（跨模块：RAG 注入 / 工具回路 / Fetch 网络 / Markdown 渲染 / L2 记忆 / 聊天 UI） |

## 背景（Context）

2026-08-18 真机手动测试暴露 7 项问题（多轮优化后依然存在的顽固 Bug 或体验缺陷）：

1. **RAG 知识库误注入（复现第 3 轮，触发条件变化）**：即使没有任何检索知识库的需求，知识库资料仍被
   注入 LLM 上下文。前两轮（ADR-028 需求预判 / ADR-031 多语言嵌入阈值）只修了「寒暄/闲聊」类触发，
   本轮**仅在文件上传功能触发**：`【文档：xxx.pdf】正文…` 被当作 query 做 embed+search，长文档查询
   在多语言嵌入模型（paraphrase-multilingual-MiniLM-L12-v2）下检索出无关片段注入上下文。
2. **连续搜索限流（429）**：第二次对话即触发「请求过于频繁，触发服务端限流（429）…request reached
   organization max RPM: 3」。根因：LLM 端点（Moonshot Kimi 组织级 **RPM=3**）配额极低，工具回路
   每轮工具执行后都要重新调 LLM（ADR-014），一次深度调研（2-4 轮）数秒内打满 RPM。
3. **Fetch 抓取不可用**：均被反爬网站（Cloudflare/Paywall）限制。此前（ADR-023/032）已做 UA + 状态码
   诊断文案，但缺浏览器典型请求头、3xx 重定向跟随（http→https / 短链 / CMS 跳转会直接落 3xx 失败）。
4. **Fetch 失败后乱码**：LLM（如 kimi-k2.6）在回复正文输出幻觉的工具调用 XML 块
   （`<tool_calls><invoke…>…</invoke></tool_calls>`，真机渲染为 `<｜｜DSML｜｜tool_calls>` 乱码），
   mikepenz markdown-renderer **0.26.0** 把 `<...>` 当 HTML/XML 标签解析错乱。
5. **L2 跨会话记忆"什么都记"**：一次性信息查询（如「搜某角色背景」）也被当作记忆入库，检索时污染
   后续会话。根因：`saveSessionMemories` 对重要轮次做「对话摘要」，摘要含一次性任务内容。
6. **LLM 调 Skills 无 UI 反馈**：web_search 等工具结果卡片被跳过（ADR-031 US-908 原本刻意跳过搜索
   卡片），用户看不到「LLM 是否真的调用了对应工具」。
7. **真机"正在思考"动画不动**：`rememberInfiniteTransition` 圆点呼吸动画在真机（MIUI 低帧率/省电
   模式）下不推进，模拟器正常。

## 决策（Decision）

### 子决策 A：RAG 误注入根治 —— 文档内容直发跳过 RAG 自动注入（修复 1）

- `needsRagRetrieval` 增加前缀检查：以 `【文档：`（[DOCUMENT_MESSAGE_PREFIX]）开头的用户消息
  （文件上传文本直发，与 ConversationScreen.extractDocumentText 的包装一致）**跳过 RAG 自动注入**。
  - 文档本身即本轮上下文，无需知识库检索；
  - 长文档作为 query 在多语言嵌入模型下检索出无关片段注入上下文（真机实测根因）。
- 用户如需知识库关联，可显式用 `knowledge_base__search` 工具（RAG 开启时已注入）。
- 纯函数 `needsRagRetrieval` 语义保持保守：仅拦截明显无需求的文档前缀消息，其余照常走相似度阈值。

### 子决策 B：搜索限流缓解 —— 工具回路轮间退避 + 429 自动退避重试（修复 2）

- **轮间退避**：`SkillExecutor` 新增构造参数 `interRoundDelayMs`（生产由 PrismApplication 注入
  `TOOL_LOOP_INTER_ROUND_DELAY_MS = 2s`；构造器默认 0 供 58 处既有单测不等待）。工具回路第 2 轮起
  每轮延迟 2s，把多轮 LLM 请求摊开、降低瞬时 RPM 峰值。
- **429 自动重试**：工具回路 `flow.collect` 中检测 `StreamEvent.Error` 的限流信号
  （新增 `isRateLimitError`：429 / rate_limit / rate limit / 限流 / 请求过于频繁 / max rpm / rpm:），
  重试耗尽前**不转发给用户**，等待递增退避（`RATE_LIMIT_BACKOFF_MS` 3s / 6s，上限
  `MAX_RATE_LIMIT_RETRIES = 2`）后重发同一轮（本轮未执行工具，幂等安全）。
- **重试耗尽**：补发限流提示给用户（说明稍等重试），回路自然结束（completedToolCalls 为空 → break），
  不无限重试放大请求频率。
- 说明：RPM=3 是**账号级配额**，客户端无法提升；轮间退避 + 自动重试是客户端侧最优缓解。

### 子决策 C：Fetch 反爬深度优化 —— 浏览器请求头 + 手动跟随重定向（修复 3）

- **关键根因（ac-verifier 验收实证）**：Ktor 3.x **HttpRedirect 插件默认安装且默认跟随重定向**
  （`maxJumps=20`，Ktor 文档："By default, Ktor HTTP client does follow redirections"）。
  此前 `fetchHttpClient` 的 Q-LOW-3 注释假设「OkHttp engine 默认不跟随 3xx」在 Ktor 3.3.3 下**不成立**
  → `client.get` 会在内部跟随重定向（绕过 `fetchWithRedirects` 的逐跳 SSRF 复检与 3 跳上限；
  重定向到内网地址可被跟随 = SSRF 纵深防御被突破；超 20 跳抛 `SendCountExceedException`）。
  **修复：生产 `fetchHttpClient` 显式 `followRedirects = false`**，使 3xx 原样返回给
  `fetchWithRedirects` 做 SSRF 校验后手动跟随（唯一重定向路径）。
- **浏览器典型请求头**（`FETCH_HTTP_HEADERS`）：User-Agent（Android Chrome UA）+ Accept +
  Accept-Language + Cache-Control + Sec-Fetch-Dest/Mode/Site/User + Upgrade-Insecure-Requests；
  刻意**不设 Accept-Encoding**（Ktor OkHttp 透明 gzip 解压，避免乱码）。
- **手动跟随 3xx 重定向**（`fetchWithRedirects`，上限 3 跳）：用 `URI.resolve` 拼接相对 Location，
  **每次重定向目标重新过 `isPublicHttpUrl` SSRF 校验**（fail-closed：非公网/解析失败不跟随），
  跟随前取消 3xx body 释放连接。
- 保留 ADR-032 的状态码可诊断文案（403/404/429 + 勿重试），反爬 403 时引导 LLM 换来源。

### 子决策 D：Fetch 失败后乱码净化 —— 剥离幻觉工具调用块（修复 4）

- 新增纯函数 `sanitizeToolCallSyntax(markdown)`，在 Markdown 渲染前：
  1. **剥离完整工具调用块**：`<tool_calls>…</tool_calls>` / `<invoke…>…</invoke>` 及其 `|`/`｜`（U+FF5C）
     分隔变体（正则容忍 `<` 与关键词间 0~4 个非单词字符，真机 `<｜｜tool_calls｜｜>` 乱码可命中）。
  2. **代码围栏感知**（``` / ~~~ 状态机）：围栏内的 `<` 不转义（交由 renderer 按 code block 渲染）。
  3. **围栏外转义标签起始 `<`**（`<( ?=[a-zA-Z/|｜!?])` → `&lt;`），残余孤立标签显示为可见文本。
- 不触碰：普通比较符（`a < b`，`<` 后空格）、数字比较（`温度<30`）、markdown 链接语法。

### 子决策 E：L2 记忆深度优化 —— 原子记忆抽取（修复 5，参考 TencentDB-Agent-Memory）

- 参考 [TencentCloud/TencentDB-Agent-Memory](https://github.com/TencentCloud/TencentDB-Agent-Memory)
  Chat Memory 设计（L0→L1→L2→L3 分层蒸馏，Chat Memory = preferences + facts + decisions，
  **不是聊天日志仓库**）：
  - `ConversationSummarizer` 新增 `extractMemories(messages, config)`：LLM 抽取**关于用户的原子记忆**
    （偏好 / 个人信息事实 / 长期决策立场），prompt 显式排除一次性信息查询、对话过程、寒暄确认，
    第三人称、每行一条、最多 5 条、无值得记录输出「无」。
  - `CrossSessionMemoryManager.saveSessionMemories`：LLM 抽取成功且非空 → 逐条入库；
    **LLM 成功但空（判定无值得记忆）→ 直接 return 0 不落库**（根治"什么都记"）；
    LLM 失败（异常）→ 降级为既有规则抽取（逐对存储，不丢数据兜底）。
  - 保留 ADR-031 的重要性过滤（isImportantTurnPair）+ MIN_SUMMARY_TURNS 门槛 + 输入上限。

### 子决策 F：LLM 调 Skills 的 UI 反馈（修复 6）

- `SkillCallCard` **不再跳过 web_search 工具**：联网搜索也是 Skill 能力（web-research 等 Skill 依赖
  `web_search__search`），卡片给出明确的「工具被调用 ✓/✕ 成功/失败」反馈；搜索结果正文仍由
  `CollapsibleSearchCard` 完整展示（卡片只给确认文本，不重复摘要）。
- 复用既有 `isFailureResult` 单一事实来源判定成功/失败；空 content 协议占位不渲染。

### 子决策 G：真机"正在思考"动画增强（修复 7）

- `TypingIndicator` 在 `rememberInfiniteTransition` 圆点呼吸之外，新增 `LaunchedEffect` 驱动的
  **动态省略号**（`…` / `。。` / `..` 轮换，500ms）：即使真机低帧率/省电模式下圆点动画不推进，
  文字本身的变化也提供明确「进行中」反馈（模拟器/真机行为一致）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| RAG：文档消息也走相似度阈值（维持现状） | 实现简单 | 长文档 query 在多语言嵌入模型下必然检索出无关片段，阈值拦不住（真机实测复现） |
| 限流：单点全局 RPM 调度器 | 理论最优 | 需跨模块协调 + 账号配额未知；轮间退避 + 自动重试已覆盖主要触发场景，改动面小 |
| 限流：增大轮间退避到 20s | 更贴合 RPM=3 | 一次深度调研串行等 60s+，体验不可接受；自动重试按需等待更优 |
| Fetch：接入无头浏览器/渲染服务 | 反爬成功率最高 | 纯端侧零配置定位不允许外部依赖（ADR-001）；仅 Cloudflare 硬 JS 挑战无法端侧突破 |
| 乱码：仅转义不剥离 | 改动小 | 完整的工具调用块剥离后答案更干净（幻觉计划不属于正文）；两者结合最佳 |
| L2：直接删除摘要改规则抽取 | 无 LLM 成本 | 规则抽取无法识别「偏好/事实/决策」语义，仍需 LLM 蒸馏；LLM 失败才降级规则 |
| 记忆：逐字存储全部 | 零丢失 | 正是"什么都记"根源；原子记忆 + 显式排除一次性查询是 TencentDB 验证过的设计 |

## 后果（Consequences）

**正面**：
- 文件上传后知识库资料不再无条件注入 LLM 上下文（U1 根治）。
- 连续搜索不再因瞬时 RPM 峰值频繁弹 429；偶发 429 自动退避重试后继续（U2 缓解）。
- Fetch 带浏览器请求头 + 跟随重定向，显著降低反爬拦截率；仍被拦截时给出可诊断文案（U3）。
- Fetch 失败后回复不再出现 `<｜｜tool_calls｜｜>` 乱码（U4）。
- L2 记忆只存「关于用户的原子记忆」，一次性查询不再污染后续会话（U5）。
- LLM 调 Skills 时界面明确显示「工具被调用 ✓/✕」反馈（U6）。
- 真机"正在思考"动画文字轮换，进行中状态明确（U7）。

**负面 / 需注意**：
- 429 自动重试最多等 3s+6s=9s；连续 3 次限流才提示用户（限流极端场景有感知延迟，属预期）。
- 原子记忆抽取是 LLM 调用（BYOK 成本）；已有 MIN_SUMMARY_TURNS + 输入截断门槛，失败降级规则抽取。
- `sanitizeToolCallSyntax` 无法识别内联代码（行内反引号）内的 `<`（仅围栏感知），行内代码含标签
  时仍会转义（可接受，渲染成可见文本而非乱码）。
- 真机待补测：429 自动重试实际触发、Fetch 反爬站点成功率、思考动画真机表现。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| RAG 误注入判断误伤（文档前缀误拦截真实查询） | P2 | 仅拦截 `【文档：` 前缀（与 UI 包装严格一致）；其余消息照常走阈值过滤 |
| 429 误判（非限流错误被重试） | P2 | `isRateLimitError` 关键词限定（429/rate_limit/限流/RPM 等），其余错误走原逻辑 |
| 自动重试放大请求频率 | P2 | 上限 2 次 + 递增退避 + 重试耗尽即停；回路自然结束不无限重试 |
| 原子记忆抽取漏存重要信息 | P3 | LLM 失败降级规则抽取（不丢数据）；prompt 明确偏好/事实/决策三类 |
| 代码围栏内 `<` 转义 | P3 | 围栏状态机已豁免 ``` / ~~~ 内转义；内联代码属已知局限 |
| 文件上传 + 知识库检索需求并存 | P3 | 文档直发跳过自动注入，用户可显式 `knowledge_base__search` 工具检索 |

## 参考

- [ADR-028 RAG 需求预判（UXR8-R1）](ADR-028-uxr8-b1-fixes.md)
- [ADR-031 多语言嵌入 + L2 记忆（UXR9）](ADR-031-uxr9-multilingual-embedding-and-l2-memory.md)
- [ADR-032 Fetch/限流文案（UXR10）](ADR-032-uxr10-real-device-fixes.md)
- [ADR-014 工具执行回路（M4）](ADR-014-m4-skills-system.md)
- [ADR-023 Fetch MCP 工具（UXR3 问题 11）](ADR-023-ux-r3-fixes.md)
- 参考开源项目：[TencentCloud/TencentDB-Agent-Memory（Chat Memory：preferences + facts + decisions）](https://github.com/TencentCloud/TencentDB-Agent-Memory)
- 网络调研（Fetch MCP 反爬优化）：[webfetch-mcp](https://lobehub.com/mcp/simonediroma-webfetch_mcp)、
  [Fetch MCP Server](https://llmversus.com/mcp/mcp-server-fetch)
