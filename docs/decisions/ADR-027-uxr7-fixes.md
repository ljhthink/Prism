# ADR-027: UXR7 真机反馈修复（搜索核心词重试 + markdown 表格预处理 + 引用覆盖读全文 + 熔断扩展）

> 解决 UXR7 真机测试反馈的 3 个问题（均为 UXR6 修复后仍存在，"多次修复依然存在"）：搜索"昔涟"仍返回"昔" + 循环达上限 / MCP 后 markdown 表格逐单元格拆行 / 引用来源仍只标第一篇。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-16 |
| 决策者 | 主 Agent |
| 关联文档 | [ADR-026 UXR6 修复](ADR-026-uxr6-fixes.md)、[ADR-025 UXR5 修复](ADR-025-uxr5-fixes.md) |
| 上游调研 | [考古报告 2026-08-16-uxr7-archaeology.md](../reports/2026-08-16-uxr7-archaeology.md)、Bing 分词实测、mikepenz 0.26.0 sources jar 实证（无表格渲染组件）、mikepenz issue #285/#480（表格换行） |
| 风险等级 | P2（跨模块：搜索协议 + 渲染 + 引用） |

## 背景（Context）

UXR7 真机测试暴露 3 个问题，均经 UXR5/UXR6 两轮修复后仍存在。**本次通过真机日志（WebSearchTool/SkillExecutor 诊断日志，UXR6 新增）+ 网络调研 + Bing 实测 + 0.26.0 源码实证找到根本性根因**：

1. **搜索"昔涟"仍返回"昔" + 循环达上限 10**：真机日志证明 LLM query 正确（"昔涟 是谁"等），Bing 返回 `items=10 first=昔_百度百科`——**Bing 服务端对冷门中文新词在长 query 中分词失败**（"昔涟"被拆为"昔"）。开发机实测：单个"昔涟"可整词匹配返回 8 条正确结果，但"昔涟 是谁"等**长 query** 返回"昔"相关（"昔_百度百科""昔 xī - 汉典"）；`mkt/setlang` 参数与引号包裹均无法影响服务端分词。且搜索"成功返回但不相关"不触发熔断（`isFailureResult` 只认失败前缀），Fetch 工具失败文案"抓取失败"**不在前缀列表**——LLM 反复换 query / 用 Fetch 抓取直至 maxRounds=10。
2. **MCP 后 markdown 表格逐单元格拆行**：GitHub MCP 返回后，LLM 输出 markdown 表格（`# 项目 简介 语言 Stars`）被渲染为**每个单元格垂直一行**（用户示例无管道符 → 排除纯文本残留）。**根本根因（考古实证）**：下载 `multiplatform-markdown-renderer-0.26.0-sources.jar`，commonMain 32 个 .kt 文件**没有任何 Table 渲染文件**；`Markdown.kt` 的 `when(node.type)` 无 TABLE 分支 → `else` → `custom(null)` → **递归 children 逐个平铺** → 每个单元格一行。0.28+ 受 Compose 1.6.8 ABI 限制无法升级（ADR-025 已确认）。
3. **引用来源仍只标第一篇**：真机日志证明 LLM 用 `knowledge_base__get_document_content`（读全文，round=1 并行读 2 篇）而非 `search`，而 UXR6 的 `parseKnowledgeBaseCitations` **只处理 search 的 `[来源N] 文件=X` 格式**，`get_document_content` 的 `【知识库文档：X】` 格式不被识别 → 读取的文档不进 sources。

## 决策（Decision）

### 子决策 A：搜索核心词提取 + 相关性检查 + 降级重试（问题 1）

- **`WebSearchLocalToolExecutor`**：
  - 新增 `extractCoreTerm(query)`：提取 query 中第一个 ≥2 字连续中文片段（LLM 通常把实体放 query 最前，如"昔涟 是谁"→"昔涟"）。
  - 新增 `isRelevant(items, coreTerm)`：结果 title/snippet 是否含核心词（分词失败判据）。
  - execute 重构：按原 query 搜索 → 若核心词非空且结果不相关（分词失败）→ **用核心词单独降级重试 1 次**；重试结果相关则返回；仍不相关返回"搜索失败"（触发熔断）。
  - 抽取 `fetchSearch` / `formatSearchResult`（复用 + 保留日志）。
- **`SkillExecutor.isFailureResult`**：补全 `抓取失败` / `Fetch 工具不可用` 前缀（真机日志 round=5-9 连续 Fetch 失败未触发熔断）。

### 子决策 B：markdown 表格预处理为列表（问题 2）

- 新增 `sanitizeMarkdownTables(content)`（纯函数）：检测 GFM 表格块（`|...|` 连续行 + 分隔行），转换为 markdown 列表（表头加粗 + 每数据行一个列表项 + 字段 ` | ` 分隔）。
- `AiBubble` 的 Markdown 渲染前应用 `sanitizeMarkdownLinks(sanitizeMarkdownTables(content))`。
- **为什么不升级渲染器**：0.28+ 依赖 Compose 1.7+ ABI，项目 BOM 2024.06.00（Compose 1.6.8）运行期崩溃（ADR-025 已确认）。0.26.0 无表格渲染代码（考古实证），预处理是唯一可行路径。
- 修正 AiBubble 注释漂移（"升级 0.37.0" → 实际 0.26.0）。

### 子决策 C：引用来源覆盖 get_document_content（问题 3）

- `syncToolMessages` 的知识库工具过滤从 `TOOL_SEARCH` 扩展到 `TOOL_SEARCH + TOOL_GET_DOCUMENT_CONTENT`。
- `parseKnowledgeBaseCitations` 新增识别 `【知识库文档：X】` 格式（index 自增，按 documentTitle 去重），与 search 格式合并解析。

**一句话**：Bing 分词失败时用核心词降级重试 + 失败前缀补全（搜索）+ 表格预处理为列表（0.26.0 无表格组件）+ 引用解析覆盖读全文工具（引用）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| markdown：升级 0.28+（原生表格组件） | 表格正确渲染 | 依赖 Compose 1.7+ ABI，项目 Compose 1.6.8 运行期崩溃（ADR-025 已确认），不可行 |
| markdown：MarkdownComponents.custom 自绘表格 | 保留表格视觉 | custom 一旦设置接管所有未知节点，需自绘 + 兜底渲染；0.26.0 无表格节点结构文档，复杂度高风险大；预处理列表更稳妥 |
| 搜索：切换 DuckDuckGo/Google/Baidu | 可能分词更好 | 国内不可达（ADR-020）；百度无 RSS API |
| 搜索：对 query 加引号强制整词 | 简单 | 实测 `"昔涟" 是谁` 仍返回"昔"（引号无法影响长 query 分词） |
| 引用：放宽 topK/阈值 | 增加自动 RAG 条数 | 会重新引入 UXR3"首篇必塞"问题；LLM 主要用 get_document_content，需从工具结果入手 |

## 后果（Consequences）

- 正面：
  - Bing 分词失败时自动用核心词降级重试（问题 1 搜索质量）；失败前缀补全 + 相关性熔断（问题 1 循环根治）
  - 表格转换为可读列表（问题 2 根治，0.26.0 限制下最优）
  - get_document_content 读取的文档进引用来源（问题 3）
- 负面 / 代价：
  - 核心词重试可能丢失 query 上下文词（如"昔涟 星穹铁道 角色"→ 单独"昔涟"查询结果偏泛），但比"返回昔"好
  - 表格→列表丢失表格视觉（接受，0.26.0 无法渲染表格）
  - `isRelevant` 对"结果恰好不含核心词但语义相关"的误判（如英文专名），已用 coreTerm 非空守卫（仅中文触发）
- 需要同步更新的文档或代码：
  - `docs/decisions/README.md` + `README.md` 索引新增 ADR-027
  - 测试：`WebSearchLocalToolExecutorTest`（extractCoreTerm/isRelevant/降级重试）、`ConversationScreenMarkdownTest`（sanitizeMarkdownTables）、`ConversationViewModelUxR6Test`（get_document_content 解析 + 抓取失败前缀）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 核心词重试过度触发（正常查询被误判不相关） | 中 | 仅中文核心词非空时触发；isRelevant 检查任一结果含核心词 |
| 表格预处理破坏非表格文本 | 低 | 仅识别"`|` 行 + 分隔行"的表格块；无分隔行不转换（纯函数测试覆盖） |
| 熔断扩展误伤（"抓取失败"正常内容被熔断） | 低 | isFailureResult 前缀匹配，正常抓取结果不以该前缀开头 |
| Bing 分词行为随出口变化 | 中 | 核心词重试是代码侧最佳努力；重试失败触发熔断让 LLM 直接回答（用户仍有答案） |

## 参考

- [考古报告 2026-08-16-uxr7-archaeology.md](../reports/2026-08-16-uxr7-archaeology.md)（三问题根本性根因 + 0.26.0 源码实证）
- mikepenz/multiplatform-markdown-renderer 0.26.0 sources jar（commonMain 无 Table 文件，Markdown.kt L107-109 fallback 平铺）
- mikepenz issue #285/#480（表格换行/自定义组件）
- Bing 实测：`"昔涟"` 整词匹配返回正确；`"昔涟 是谁"` 长 query 分词失败返回"昔"

---

## 修订记录：UXR7-R2（2026-08-16，第二轮真机反馈）

> 首轮修复后用户再次真机反馈三问题仍存在。**考古 + dex 验证确认首轮修复代码从未进入真机 APK**
> （APK 构建 01:37 早于源码修改 04:42-04:58，dex 无 `extractCoreTerm`/`sanitizeMarkdownTables`/`抓取失败` 等
> 任何新字符串）——"多次修复依然存在"的直接根因是**交付链断裂（APK 未重建/未验证）**，而非代码缺陷。
> 同时按用户要求补充网络调研（Bing 分词 OOV、mikepenz 0.26.0 表格、RAG 引用池）与深度推理，
> 识别并修正首轮方案的三处真实缺陷。

### 网络调研结论（三份独立子 Agent 报告）

1. **Bing 分词**：服务端 query-understanding 对 OOV 冷词在"实体+疑问词"长 query 必然坍缩（SearXNG
   #4964 同机制实证；`"昔涟"` 单独整词 100% 命中，`"昔涟 是谁"` 必然坍缩）。任何客户端参数
   （mkt/setlang/cc/count/引号）均无效。业界正解：**客户端核心词提取 + 短整词降级重试**
   （阿里云 OpenSearch re_search 官方机制、jieba/HanLP 核心词提取、LMC-5 issue #10 literal fallback）。
2. **mikepenz 0.26.0**：0.28.0 才引入表格（PR #257）；0.27.0 起依赖 Compose 1.7.0 → Compose 1.6.8 约束下
   无任何"支持表格且兼容"版本，升级被彻底堵死。官方唯一推荐 custom component 自绘；预处理列表
   "技术上成立但无社区验证"，需自测且要覆盖**无分隔行紧凑表格**（LLM 常输出）。
3. **RAG 引用**：文本正则解析是最脆弱方案；业界最推荐**工具调用参数反向映射（引用池）**——拦截
   agent 循环中实际调用的工具参数，把读过的文档收进引用池（ChatPDF-Pro、Microsoft FunctionMiddleware、
   AgenticRAG reference id、微信知识助理范式），与 LLM 正文是否标引用无关。

### 三处方案缺陷与修正（UXR7-R2）

| 问题 | 首轮缺陷 | UXR7-R2 修正 |
|---|---|---|
| 搜索"昔涟" | 单候选核心词（仅取第一个中文片段），多实体 query 可能取错；停用词表不全（"角色/游戏/百科"未被过滤） | `extractCoreTerm` → `extractCoreTerms`（多候选，按出现顺序）；停用词表扩充"角色/游戏/大全/百科"等；execute 对**全部候选**依次短整词降级重试，任一命中即返回 |
| markdown 表格 | 仅识别"含分隔行的标准表格"；LLM 常输出**无分隔行紧凑表格**（`\| a \| b \|` 后紧跟数据行）→ 漏检平铺 | `sanitizeMarkdownTables` 支持紧凑表格（连续 ≥2 行 `\|` 行且每行 ≥2 个 `\|`，无分隔行也转换）；`convertTableToLines` 兼容无分隔行输入 |
| 引用只标第一篇 | 仅从 TOOL 返回文本正则解析，依赖格式可识别 | 新增**工具调用参数反向映射**：`parseKnowledgeBaseCitationsFromToolCalls` 从 assistant 占位消息的 toolCalls 提取 `get_document_content` 的 `documentTitle` 参数（JSON 容错解析，白名单工具+字段），与文本解析结果合并去重 → 引用池语义 |
| 熔断覆盖 | 缺"仅支持抓取/仅支持公网地址/工具调用失败"前缀 | `isFailureResult` 补全 LocalMcpToolProvider 全量降级文案 |

### 变更影响与验证

- **接口变更（P2）**：`extractCoreTerm(query): String?` → `extractCoreTerms(query): List<String>`（internal，
  调用点已全量更新，测试同步）。
- 新增 internal 纯函数：`parseKnowledgeBaseCitationsFromToolCalls` / `parseToolCallDocumentTitle`（可测）。
- 测试更新：`WebSearchLocalToolExecutorTest`（多候选/紧凑表格防御/全量熔断前缀）、
  `ConversationScreenMarkdownTest`（紧凑表格转换 + 单管道行防御）、`ConversationViewModelUxR6Test`
  （toolCalls 反向映射 + 容错）、`SkillExecutorTest`（全量失败前缀）。
- **全量单元测试 BUILD SUCCESSFUL**（186 用例）。
- **交付修复**：重建 debug APK 后 dex 字符串验证（`extractCoreTerms`/`sanitizeMarkdownTables`/
  `抓取失败`/`知识库文档` 均须命中），再安装模拟器/真机。
