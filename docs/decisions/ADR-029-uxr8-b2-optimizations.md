# ADR-029: UXR8 批次2 优化（L3 画像自然语言化 + MCP 模板增强 + Skills 文档工具 + 搜索扩容）

> 实现 UXR8 的 5 项优化（O1-O5）：L3 用户画像自然语言输入 / MCP 功能描述与 Key 指引 / 新增 3 个 MCP 模板 / Skills 文档生成工具与 3 个新内置 Skill / 联网搜索结果扩容与多查询合并。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-16 |
| 决策者 | 主 Agent + 用户确认（D-2/D-3/D-9/D-10/D-11） |
| 关联文档 | [PRD UXR8](../prd-uxr8.md)、[ADR-015 M5 记忆系统](ADR-015-m5-memory-system-architecture.md)、[ADR-007 M3 文档解析](ADR-007-m3-document-ingestion-rag.md)、[ADR-020 深度思考+联网搜索](ADR-020-thinking-and-web-search.md) |
| 风险等级 | P2（跨模块：记忆层 + MCP 数据层 + 文档工具 + 搜索执行器） |

## 背景（Context）

UXR8 批次2 提出 5 项优化（docs/prd-uxr8.md 第 3 节），用户已确认全部决策点：

1. **O1（L3 画像自然语言化）**：L3 画像偏好需用户填「key + value」双字段，key 语义晦涩（如 `tone`/`pref_1a2b3c4d`），普通用户不理解；且自然语言意图与 key 的映射不稳定。
2. **O2（MCP 功能描述 + Key 指引）**：MCP 工具/模板列表无功能说明，用户不知道每个工具干什么；远程模板需 Key 但无「去哪获取 Key」指引。
3. **O3（新增 MCP 模板）**：经 D-2/D-3/D-9 确认新增 Firecrawl + n8n + TrendsMCP（TrendRadar 无法做填 Key 模板，以 TrendsMCP 托管化替代）。
4. **O4（Skills 增强）**：经 D-11 确认——Humanizer-zh/web-research 内置 Skill；docx/xlsx 新增 POI 工具；Firecrawl Skill 复用 MCP 模板；gstack 借鉴评审模板。
5. **O5（搜索扩容）**：Bing RSS 单次恒返 10 条，`MAX_RESULTS=8` 白丢 2 条；需多查询合并去重扩容至 12-16 条。

## 决策（Decision）

### 子决策 A：O1 L3 画像自然语言化

- 新增 `ProfileNaturalLanguageParser`（纯函数）：
  - `deriveKey(description)`：关键词白名单（语气/语言/长度/格式等 4 类 + 高频中文词）命中 → 稳定短 key（如 "简洁"→`tone`、"中文"→`language`）；未命中 → `pref_` + 8 位稳定 hash 兜底（同句同 key，异句不同 key）。
  - `extractCoreTerms` 式候选核心词复用思路：多候选按出现顺序，跳过停用词。
- `MemoryManagementViewModel.saveProfile` 改造（**G-01 冲突防护**）：
  - UI 单字段自然语言输入（key 对用户隐藏，`ProfileEditSheet` 只留「偏好描述」）。
  - 派生 key 冲突时：同 key 同 value → 幂等提示「该偏好已存在」；同 key 异 value → `nextAvailableKey` 追加 `_2`/`_3`… 唯一 key（上限 100，禁止静默覆盖用户显式输入）。
  - `nextAvailableKey` 实现含 base 可用直接返回 + 长 base 截断防御（G2-02）。
- LLM 侧抽取逻辑不变（`UserProfileManager` 仍存 key/value，value 为原句）。

### 子决策 B：O2 MCP 模板元数据（description + keyHint）

- 新增 `McpPresetMeta` 数据类（非持久化，随 `McpServerPresets` 版本更新）：
  - `description`：一句话功能描述（「做什么用」，非技术参数）。
  - `keyHint`：API Key 获取指引（远程模板必填；本地内置为空）。
- `findMetaByName` 忽略大小写；UI 三处展示：
  - 本地 Server 行展示描述；
  - 预设模板行展示描述；
  - 添加远程 Server 弹层展示「Key 获取：$keyHint」。
- 全部 18 个预设补齐元数据。

### 子决策 C：O3 新增 3 个 MCP 模板

- **Firecrawl**：`https://mcp.firecrawl.dev/v2/mcp` + Bearer（官方托管，免费 1000 页/月）。
- **n8n**：实例级端点占位 `https://your-instance.n8n.co/mcp` + MCP Access Token（keyHint 明确提示改实例地址）。
- **TrendsMCP**：`https://api.trendsmcp.ai/mcp` + Bearer（免费 100 请求/月，跨 25+ 平台趋势）。
- 端点经官方文档核实（2026-08-16）；复用既有「从预设添加」一键链路（US-010）。

### 子决策 D：O4 Skills 文档工具 + 新内置 Skill

- 新增 `DocumentLocalToolExecutor`（本地工具，POI 零新增依赖）：
  - `document__create_docx`：Markdown 子集（标题/列表/段落）→ XWPFDocument。
  - `document__create_xlsx`：sheets 二维数组 → XSSFWorkbook。
  - **安全加固**（guardrail TKN-UXR8-GUARDRAIL-PRECOMMIT-001 + TKN-UXR8-B2-GUARDRAIL-001）：
    - 文件名清洗（路径穿越八类向量回归锚点 + 80 字符截断）；
    - 资源上限：docx `MAX_CONTENT_LEN=100k` / xlsx `MAX_TOTAL_CELLS=5000` / `MAX_SHEETS=20`（G-04，防空 rows 绕过）；
    - 公式注入防御 `sanitizeCellText`（G-09，`= + - @ \t \r` 前缀 `'`，OWASP 基线）；
    - sheet 名清洗 `sanitizeSheetName`（非法字符替换 + 31 字符 + 首尾引号修剪 G2-04）。
- 3 个新内置 Skill（SKILL.md frontmatter 合规，`SkillRegistry` 自动扫描）：
  - **firecrawl**（max-rounds 6）：Firecrawl MCP 抓取工作流（G-05 修复：工具名以实际注册名为准）。
  - **humanizer-zh**（max-rounds 2）：中文人性化改写（24 种 AI 痕迹清单）。
  - **web-research**（max-rounds 8）：联网深度调研策略（多轮搜索 + 来源交叉验证 + 结构化报告）。
- gstack 借鉴（D-5）：融入 `code-reviewer` 内置 Skill 的评审模板结构。

### 子决策 E：O5 搜索扩容 + 多查询合并

- `MAX_RESULTS` 8→10（Bing RSS 恒返 10 条补回）。
- **多查询合并**：`maxResults ≥ 8` 时触发互补变体查询（≤2 个），URL 归一化（fragment/utm_*/www/大小写/尾斜杠）去重合并，总上限 `MERGE_MAX_RESULTS=16`。
- 引用要求头：结果头部追加「【引用要求】回答时必须用内联 [N] 编号引用所用来源」。
- **预算感知**（G-03，guardrail TKN-UXR8-B2-GUARDRAIL-001）：`hasRequestBudget(elapsed)` 判据 `elapsed + 10s ≤ 30s − 3s`，核心词重试与合并变体循环每次发起请求前检查，不足即 break 保留已成功结果——防串行子请求拖穿 SkillExecutor 30s withTimeout 导致已成功结果被整体丢弃。
- 常量耦合守护（G2-03）：单测断言 `TOTAL_TOOL_BUDGET_MS == SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS` 且 `SEARCH_REQUEST_TIMEOUT_MS == 10_000L`，防漂移。

**一句话**：L3 画像自然语言化 + key 冲突保护（O1）；MCP 模板 description/keyHint 元数据（O2）；Firecrawl/n8n/TrendsMCP 新模板（O3）；文档生成工具 + 3 新 Skill（O4）；搜索 10 条上限 + 多查询合并 16 条 + 预算感知（O5）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| O1：保留双字段 UI + 自动填 key | 改动小 | key 语义晦涩无法根除；用户仍被暴露在抽象层 |
| O1：LLM 抽取 key（自然语言 → 语义 key） | key 更可读 | 需额外 LLM 调用（成本+延迟）；无网络/无 Key 时不可用；确定性差 |
| O2：description 持久化进 McpServerConfig | 自定义 Server 也有描述 | 实体加字段需 ObjectBox 迁移；模板描述随版本更新更合理（非用户数据） |
| O3：TrendRadar 自建 | 中文热榜更全 | 无法做填 Key 模板（D-3 调研结论）；需自建常驻服务，手机端不可行 |
| O5：搜索分页（second=10） | 单查询更多结果 | Bing RSS 实测忽略 second/count 参数，无分页（TKN-UXR8-FEATURE-RESEARCH-001） |
| O5：无条件合并变体 | 结果更全 | 日常快查多耗网络请求；仅 maxResults≥8（明确要求全面覆盖）时触发 |
| O4：文档工具用独立库 | 功能更全 | 引入新依赖违背零依赖约束；POI 已覆盖 docx/xlsx 需求 |

## 后果（Consequences）

- 正面：
  - L3 画像「一句话添加」，普通用户零学习成本；同类别多条偏好并存不覆盖（G-01）；
  - MCP 工具/模板列表可读性提升；远程模板 Key 获取路径清晰（O2）；
  - 3 个新模板一键添加即用（O3）；AI 可生成 docx/xlsx 文档（O4）；搜索覆盖多来源 12-16 条（O5）；
  - 安全加固闭环：路径穿越/公式注入/资源上限三重防线 + 预算感知防结果丢失。
- 负面 / 代价：
  - `deriveKey` 为确定性启发式（非语义），极少数意图映射不精确时用户可显式编辑偏好值；
  - 合并变体查询仅在 maxResults≥8 时触发，日常快查保持单查询（省请求）；
  - n8n 模板 Base URL 为占位符，需用户按 keyHint 修改（文档已明示）。
- 需要同步更新的文档或代码：`docs/prd-uxr8.md` 批次2 状态、`AGENTS.md` 用户故事、ADR 索引（docs/decisions/README.md）、`docs/behavioral-rules.md`（BR-interface-015 / BR-performance-002）。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 派生 key 冲突静默覆盖 | 低（已根治） | G-01：同值幂等 + 异值 nextAvailableKey 序号唯一（上限 100 + 截断防御） |
| 搜索串行子请求拖穿总超时 | 低（已根治） | G-03：hasRequestBudget 预算检查两处 break；常量耦合守护测试 |
| 文档生成路径穿越/公式注入 | 低（已根治） | 文件名清洗八类向量锚点 + sanitizeCellText OWASP 基线 + 三重资源上限 |
| 新模板端点连通性 | 中 | 端点经官方文档核实；付费/托管服务真机+真 Key 验证合理豁免（接入时用户自验） |
| 超时常量三处独立定义漂移 | 低（G2-03 已闭环） | 单测断言常量对齐；未来建议收敛单一来源（批次3 技术债） |

## 参考

- [PRD UXR8](../prd-uxr8.md)
- [护栏报告 TKN-UXR8-B2-GUARDRAIL-001/002（docs/reports/）](../reports/)
- [验收报告 TKN-UXR8-B2-ACCEPTANCE-001（docs/reports/）](../reports/)
- OWASP CSV/Formula Injection 防护基线
