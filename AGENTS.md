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
- [prd.json](prd.json) —— Ralph 格式任务分解
- [docs/reports/](docs/reports/README.md) —— 调研、考古、审查与验收报告（一次性工件，不入库）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）
