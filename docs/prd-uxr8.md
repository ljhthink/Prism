# UXR8 · 产品需求与执行方案（遗留 Bug 修复 + 优化 + 新功能）

> 依 CLAUDE.md 流程：本 PRD 记录用户实际使用中发现的问题与需求，含代码考古根因、网络调研结论与分项执行方案。
> **本阶段为"调研 + 方案确认"**：用户确认执行方案后进入开发。所有验收标准须可验证。

| 项目 | 内容 |
|---|---|
| 版本 | v0.2（规划） |
| 日期 | 2026-08-16 |
| 作者 | 主 Agent + 用户 |
| 关联文档 | [考古报告 2026-08-16-uxr8-archaeology.md](../reports/2026-08-16-uxr8-archaeology.md)、MCP/Skills/视觉/功能四份网络调研 |
| 风险等级 | P2（跨模块：搜索 + 记忆 + UI + MCP + Skills + 新功能） |

---

## 1. 背景

用户经长期真实使用后反馈 **3 个遗留 Bug、5 个优化项、3 个新功能**。主 Agent 已完成源码考古（3 Bug 根因）与网络调研（4 份独立报告），本 PRD 汇总并给出执行方案。**其中部分需求经调研判定不切实际或需调整，已列为"决策点"待用户确认**（见第 8 节）。

---

## 2. 遗留 Bug 与根因（考古结论）

### Bug 1：知识库内容被"系统主动注入"

- **现象**：无检索需求时，知识库资料仍被注入 LLM 上下文。
- **根因**（[考古报告](../reports/2026-08-16-uxr8-archaeology.md)）：默认开启 + 开关语义问题 + 阈值非需求判断，三者叠加。
  - `_ragTarget` 默认 `AllLibraries` 且每次"新对话"强制重置回全库（[ConversationViewModel.kt](app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) L569），RagTarget 不持久化——用户关掉 RAG 后新会话又自动打开。
  - 自动检索对每条消息无条件执行，无"检索需求"意图判断。
  - 绝对阈值 0.5 只能滤低相关，MiniLM 相似度常落 0.5-0.7，部分相关内容仍注入。
- **修复方向**：RagTarget 持久化 + 新对话不再强制重置 + 可选意图门控。

### Bug 2：跨会话记忆 L2 未生效

- **现象**：多次会话后 L2 无任何记录。
- **根因**：**接线但触发条件不满足**——L2 保存唯一挂在 `onCleared`（[ConversationViewModel.kt](app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) L1037-1041）。单 Activity + NavHost 下 Chat 是 start destination 永不被 pop，`onCleared` 仅在进程销毁时触发；用户点"新对话"只调 `persistSession()`，**不调 `persistSessionMemories()`**，随后直接清空 sessionId/messages → 上一会话 L2 在保存前被丢弃。检索端接线正确但库为空恒空。次要：MINIMAL/CHAT_ONLY 档 L2 被静默禁用。
- **测试盲区**：集成测试直接调 `persistSessionMemories()` 验证保存，绕过生产触发条件——测试通过但生产不触发。
- **修复方向**：`startNewConversation`/`loadSession` 清空状态前调用 `persistSessionMemories()`。

### Bug 3：能力界面配置弹层被键盘顶出屏幕

- **现象**：配置 MCP / L3 画像时，点击文本框整个界面上移至屏幕外。
- **根因**：高度计算错误——弹层上限按**全屏** 90% 计算，叠加 IME 后总高超屏。[PrismSheetHost.kt](app/src/main/java/io/prism/ui/components/PrismSheetHost.kt) L44 `maxSheetHeight = screenHeightDp * 0.9f`，L68-69 同一容器 `heightIn(max=全屏90%) + imePadding()` 叠加 → 弹层+键盘超屏，底部对齐弹层顶部出屏。`adjustResize` 在 edge-to-edge + API 30+ 不生效。
- **修复方向**：`maxSheetHeight` 扣除 IME 高度（或重组修饰符顺序让 imePadding 约束可用空间）。

---

## 3. 优化项与方案

### O1：L3 用户画像设置过于复杂（"偏好键/偏好值"晦涩）

- **现状**：L3 画像用 key/value/category 三字段，用户不明白含义。
- **方案**：UI 简化为"自然语言描述式"——把"偏好键=value"改为"一句描述你的偏好"（如"我喜欢简洁的回答风格"）。存储层保留 key/value 兼容（description 作为 value），LLM 抽取逻辑不变。用户只需看"这条偏好描述的是什么"。
- **调研依据**：业界（ChatGPT Custom Instructions）用"关于我 / 如何回答"双字段自然语言，非技术键值。

### O2：MCP 工具预设缺失功能描述 + 远程模板不知去哪找 API Key

- **现状**：本地/远程 MCP 工具列表无功能说明；远程模板只有名称。
- **方案**：
  - 本地 MCP 工具增加"一句话功能描述"（工具列表 UI 展示）。
  - 远程模板增加"用途说明 + API Key 获取指引"（模板元数据含 `description` + `keyHint`，如"到 https://platform.openai.com/api-keys 获取"）。
  - UI 层在添加远程 Server 时展示该说明。

### O3：新增 MCP 模板（Firecrawl / n8n / TrendsMCP）

> **落地差异（TKN-UXR8-B2-ACCEPTANCE-001 遗留项4）**：经 D-2/D-3/D-9 确认，最终新增 3 个模板 =
> **Firecrawl + n8n + TrendsMCP**（TrendRadar 无法做填 Key 模板，以 TrendsMCP 托管化替代）；
> **不含 Draw.io**（MCP Apps 协议兼容性未实测，暂不纳入）。下表调研结论保留历史分析供追溯。

网络调研结论（TKN-UXR8-MCP-RESEARCH-001）：

| 工具 | 官方 MCP | 托管端点 | 认证 | 适配"填 Key 模板" | 优先级 |
|---|---|---|---|---|---|
| **Firecrawl** | ✅ | ✅ `https://mcp.firecrawl.dev/v2/mcp` | API Key | ✅ 完美契合 | ⭐⭐⭐ |
| **n8n** | ✅ 内置 | ✅ 实例级远程 HTTP | MCP Access Token | ✅ 契合（URL+Token 双字段） | ⭐⭐⭐ |
| **Draw.io** | ✅ JGraph 官方 | ✅ `https://mcp.draw.io/mcp` | 无 Key | ⚠️ 无认证模板；依赖 MCP Apps 协议需实测 | ⭐⭐ |
| **TrendRadar** | 社区 | ❌ 仅本地自建（FastMCP） | 无 Key | ❌ 无法做填 Key 模板 | ⭐（可选项） |

**TrendRadar 无法做"填 Key 模板"的确切原因**（TKN-UXR8-TRENDRADAR-RESEARCH-001）：

1. **无官方托管端点**：只有源码仓库，无 `.com/mcp` 公网端点；
2. **无 API Key 认证机制**：其 Key 是给爬虫平台/推送渠道用的，不是给 MCP 客户端远程认证用；
3. **必须自建常驻运行**：跑在用户自己的 Docker/Python 里（stdio 连接），手机端无法拉起；
4. **数据依赖自带爬虫**：需定时抓取 35+ 平台热榜持续入库，属"你要运行的服务"而非"你要调用的服务"；
5. **传输不兼容**：自建后也是 localhost stdio/HTTP，非公网 Streamable HTTP，Prism 无法直连。

**同类功能托管 MCP 候选（可做填 Key 模板）**：

- **TrendsMCP（trendsmcp.ai）—— 最契合**：跨 25+ 平台趋势/热榜聚合（Google/YouTube/TikTok/Reddit/X Trending/GitHub 等），托管端点 `https://api.trendsmcp.ai/mcp`（SHTTP）+ Bearer API Key，免费 100 请求/月，是 TrendRadar 的托管化等价物。弱项：偏全球英文生态，无微博/抖音/知乎中文热榜。
- **Search1API（superagents-lab）—— 次选**：搜索/新闻/抓取/trending 全能，托管端点 `https://mcp.search1api.com/mcp` + Bearer，免费 100 credits，支持 B 站/微信等中文源。弱项：trending 目前仅 GitHub/HN。
- **中文热榜（微博/抖音/知乎）正解（中期）**：腾讯云 MCP 广场 Hosted 托管（mcp-trends-hub 20+ 中文热榜、微博 MCP 热搜），但输出为 **SSE 传输**——Prism 当前仅支持 STREAMABLE_HTTP（SSE 预留未实现），需补 SSE 传输支持后才可接入，国内可达性最佳。

- **建议**：新增 **Firecrawl + n8n** 两个高质量模板（填 Key 形态）。Draw.io 作无认证远程模板（需先实测兼容性）。**TrendRadar 需自建实例，无法做"填 Key 一键添加"，列为决策点 D-3**。

### O4：新增 Skills 模板

网络调研结论（TKN-UXR8-SKILL-RESEARCH-001）：

| Skill | 可行性 | 方式 |
|---|---|---|
| **Humanizer-zh** | ✅ 直接可用（纯提示词，MIT） | 改写为内置/远程 Skill（24 种 AI 痕迹清单） |
| **web-access** | ✅ 部分可行（纯提示词部分） | 改写为"联网调研策略"Skill（实现名 **web-research**，D-11 确认），绑定现有 web_search__search + web_fetch 工具（CDP 部分不可行） |
| **Firecrawl** | ✅ | 以远程 MCP 模板形式落地（与 O3 复用），SKILL.md 工具化改写 |
| **docx** | ✅ 需新增 Kotlin 工具 | 新增 `document__create_docx`（LLM 输出 Markdown → POI XWPFDocument 生成，零新依赖） |
| **xlsx** | ✅ 需新增 Kotlin 工具 | 新增 `document__create_xlsx`（LLM 输出表格数据 → POI XSSFWorkbook 生成，零新依赖） |
| **Agent Reach** | ❌ 直接复用不可行（Python CLI） | **列为决策点 D-4**：建议暂不实现（理念借鉴） |
| **gstack** | ⚠️ 是 27 个 command 集合非单 skill | **列为决策点 D-5**：建议仅借鉴 1-2 个评审模板融入现有内置 Skill |

### O5：增大网络搜索结果数量上限

- **现状**：`MAX_RESULTS = 8`（实际 Bing RSS 单次只返回 10 条）。
- **调研结论**（TKN-UXR8-FEATURE-RESEARCH-001，本机实测）：Bing RSS **单次恒返 10 条**，count/first 参数被忽略，无分页。
- **方案**：
  1. `MAX_RESULTS` 8→10（白丢 2 条补回）。
  2. **多查询合并去重**：复用 `extractCoreTerms`，主查询成功后对互补变体查询各取 10 条合并，归一化 URL 去重，总上限 12–16 条（防 token 溢出）。串行预算沿用 `MAX_CORE_TERM_RETRIES=3` 思路。
  3. 可选进阶：设置页提供"自定义 SearXNG 实例 URL"（默认关闭，`format=json` 换更多结果）。
- **引用策略**：搜索结果头部追加"回答时必须以内联 [N] 编号引用所用来源，尽量覆盖全部相关来源"；把 UXR7-R2 引用池反向映射扩展到联网搜索。

---

## 4. 新功能与方案

### N1：类 CLAUDE.md 规则文件约束 LLM 输出

- **方案**（调研 TKN-UXR8-FEATURE-RESEARCH-001）：
  - 仿 ChatGPT Custom Instructions 双字段：「关于我」+「如何回答」。
  - 新建 `UserRulesRepository`（独立 DataStore）+ 设置页文本编辑器（长度上限，防 token 膨胀）。
  - `mergeSystemPrompt` 新增可空参数 `userRules: String? = null`，注入在 persona 之后、RAG 之前，**声明最高优先级（除安全限制外）**——语义对齐 Claude Code 分层记忆（用户显式规则 > 自动记忆 > 通用 persona）。
  - 完全向后兼容（null/空跳过，既有回归测试不破坏）。

### N2：LLM 反问/澄清提问功能

- **方案**（调研 TKN-UXR8-FEATURE-RESEARCH-001）：
  - **Phase 1（低风险）**：prompt 注入澄清策略——"当需求存在实体/版本/标准歧义、且缺失信息会实质改变答案时，先用文本向用户追问（一次一问，给出建议选项），不要反复用同义词重试搜索"。（DiscoBench 四类歧义清单 + OpenAI "materially change the answer" 措辞防偷懒）
  - **Phase 2（结构化）**：新增本地工具 `ask_user__ask`（schema 照抄 Anthropic `AskUserQuestion`：`questions[].question/options[].label+description/multiSelect`），工具"执行"= 触发提问卡片 UI + 中断当前 executeLoop 轮次（StopAtTools 语义），用户答复作为下一条 user 消息进入下一轮。
- **决策点 D-6**：Phase 1 与 Phase 2 是否都做（Phase 2 工作量更大）。

### N3：纯文本模型视觉能力

- **方案**（调研 TKN-UXR8-VISION-RESEARCH-001）：
  - **核心路径（推荐）**：用户发送图片 → 以 OpenAI 兼容 `image_url` 消息直接传给**用户已配置的端点**。若端点支持视觉（换 VL 模型名）原生支持；纯文本端点（DeepSeek）返回 400 可用该信号降级。
  - **降级路径**：可选视觉 Provider 云端旁路（用视觉 API 生成描述注入文本模型）+ 本地端侧兜底（Phase B，按 PerformanceTier 门槛）+ ML Kit OCR/标签零配置兜底。
  - **隐私提示**：云端旁路会把图片发往用户自配端点，设置页需明示。
- **决策点 D-7**：视觉功能范围——仅"图片→文字描述注入"（最低成本）还是"多模态直传 + 降级"完整方案。

---

## 5. 非功能需求

- 性能：多查询合并搜索保持串行预算 ≤3 次请求；L2 记忆保存为 fire-and-forget 不阻塞对话。
- 安全：AskUserQuestion 工具选项来自 LLM 生成需白名单校验；用户规则文件长度上限；远程 MCP 模板 Key 加密存储（复用 Keystore）。
- 可观测性：L2 保存触发点增加结构化日志（修复后可 RCA）；搜索结果合并日志。
- 兼容性：minSdk 26 不变；视觉功能按 PerformanceTier 分级。
- 隐私：图片上传需用户明示；用户规则本地存储。

## 6. 风险与依赖

| 风险/依赖 | 等级 | 缓解 |
|---|---|---|
| Draw.io MCP Apps 协议兼容性未知 | 中 | 实现前先用 MCP Client 实测 create_diagram 连通性 |
| 视觉功能云端旁路图片外发 | 中 | 设置页明示隐私边界 + 用户授权 |
| 多查询合并触发 Bing 限流 | 低 | 限频 + 复用现有超时配置 |
| Bug 2 修复触发点变更影响既有测试 | 中 | 更新集成测试覆盖生产触发路径（startNewConversation） |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联 |
|---|---|---|---|
| RagTarget 持久化 | 单元测试 | 关闭后新对话不再重置为全库 | Bug1 |
| L2 保存生产触发 | 集成测试 | startNewConversation 清空前调用 persistSessionMemories，库非空 | Bug2 |
| 弹层键盘适配 | 模拟器 + 单元测试 | 配置弹层点击文本框不超出屏幕，可滚动 | Bug3 |
| L3 画像自然语言化 | 单元测试 + UI | 偏好可编辑为自然语言描述 | O1 |
| MCP 功能描述/Key 指引 | UI 测试 | 工具列表显示描述；远程模板显示 Key 获取指引 | O2 |
| 新增 MCP 模板 | 集成测试 | Firecrawl/n8n/TrendsMCP 模板可一键添加（D-2/D-9） | O3 |
| 新增 Skills | 集成测试 + UI | Humanizer-zh/web-research/docx/xlsx 可用 | O4 |
| 搜索结果扩容 | 单元测试 | maxResults≤10；多查询合并去重 12-16 条 | O5 |
| 用户规则文件 | 单元测试 | mergeSystemPrompt 含 userRules 层且优先 | N1 |
| 反问功能 | 集成测试 | ask_user 工具触发提问卡片 + 中断本轮 | N2 |
| 视觉功能 | 集成测试 | 图片消息按 image_url 协议发送，纯文本端点降级 | N3 |

## 8. 待确认事项（决策点）

> 以下为需用户决策的异议/方案选择项。用户确认后主 Agent 按确认范围执行。
> **已确认（2026-08-16）**：
>
> - **D-1**：仅修复开关语义（RagTarget 持久化 + 新对话不重置），不做意图门控 ✅
> - **D-2**：MCP 新模板 = n8n + Firecrawl + **TrendsMCP（TrendRadar 替代）** ✅
> - **D-3（TrendRadar）**：调研确认无法做填 Key 模板，**改用 TrendsMCP（trendsmcp.ai）** ✅
> - **D-4（Agent Reach）**：暂不实现 ✅
> - **D-5（gstack）**：仅借鉴 1-2 个评审模板融入现有内置 Skill ✅
> - **D-6（N2 反问）**：Phase 1 + Phase 2 全做 ✅
> - **D-7（N3 视觉）**：**方案 A（多模态直传 + 降级）**，先做低风险 ✅
> - **D-8（执行批次）**：Bug → 优化 → 新功能 分批执行，每批独立闭环 ✅
> - **D-9（TrendRadar 替代）**：**TrendsMCP（trendsmcp.ai）**，跨 25+ 平台热榜 ✅
> - **D-10（MCP 功能描述）**：模板元数据（description+keyHint）+ UI 展示 ✅
> - **D-11（Skills 落地）**：Humanizer-zh/web-research（实现名）内置 Skill；docx/xlsx 新增 POI 工具；Firecrawl Skill 复用 MCP 模板；gstack 借鉴评审模板 ✅

**全部决策已确认，可进入开发阶段。**

---

## 9. 执行状态追踪

> D-8 分批执行，每批独立闭环（guardrail + ac-verifier + 模拟器验证）。

| 批次 | 内容 | 状态 | 验收证据 |
|---|---|---|---|
| 批次1 | Bug1（RagTarget 持久化）/ Bug2（L2 触发）/ Bug3（弹层 IME 双模式，含 OBS-2 终版） | ✅ 完成（2026-08-16） | ac-verifier 19/19 AC PASS；全量 1810 用例 0 失败；模拟器键盘场景验证（[debug 报告](../reports/2026-08-16-uxr8-b1-bug3-obs2-debug.md)）；ADR-028 |
| 批次2 | O1（L3 画像自然语言）/ O2（MCP 描述+KeyHint）/ O3（Firecrawl+n8n+TrendsMCP 模板）/ O4（Skills：Humanizer-zh/web-research/docx/xlsx）/ O5（搜索扩容 10+合并 12-16） | ✅ 完成（2026-08-16） | ac-verifier 17/17 AC PASS（TKN-UXR8-B2-ACCEPTANCE-001）；guardrail PASS-with-notes（G2-01~04 即时闭环，G2-05 列入批次3）；全量 1873 用例 0 失败；模拟器验证 O1/O2/O3/O4 UI 全部通过；ADR-029 |
| 批次3 | N1（用户规则文件）/ N2（反问 Phase1+2）/ N3（视觉方案 A） | ✅ 完成（2026-08-17） | ac-verifier 3/3 AC PASS（TKN-UXR8-B3-ACCEPTANCE-001）；guardrail 两轮（TKN-UXR8-B3-GUARDRAIL-001 有条件通过 → 002 复审通过）；全量 1948 用例 0 失败；lintDebug 0 errors；BR-ops-002 新增；G2-05 技术债闭环；ADR-030 |

---

### D-7 视觉功能两种方案区别（供决策）

| 维度 | 方案 A：多模态直传 + 降级 | 方案 B：直传 + 云端旁路 + OCR 兜底 |
|---|---|---|
| 原理 | 图片以 OpenAI 兼容 `image_url` 消息直接发给**用户已配置的端点** | 在 A 基础上，纯文本端点拒绝时用"视觉 Provider"生成图片描述注入文本模型；再兜底 ML Kit OCR |
| 依赖 | 零新增依赖；依赖用户端点是否支持视觉 | 需新增视觉 Provider 配置（额外 Key）或内置 OCR（ML Kit ~10MB） |
| 体验 | 支持视觉的模型（如 VL 模型）原生看图；纯文本端点降级为提示"当前模型不支持图片" | 纯文本模型也能"看图"（得到文字描述）；OCR 处理截图/票据 |
| 隐私 | 图片直达用户自配端点 | 旁路会额外把图片发给视觉 Provider（需明示授权） |
| 工作量 | 低（聊天输入 + 协议层） | 中-高（新增视觉 Provider + 注入链路 + OCR） |
| 推荐 | 最务实首选 | 完整能力，含兜底 |

**建议**：按"分步走"——先做方案 A（低风险，覆盖支持视觉的模型），方案 B 作为后续迭代（当用户端点不支持视觉时才有价值）。用户可在 D-7 选择"仅 A"或"A + B 全做"或"暂不做"。
