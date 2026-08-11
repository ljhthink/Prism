# Prism

> 手机端 AI 聊天 Agent 应用 —— MCP + Skills + 个人知识库 + 记忆系统 + BYOK 多端点 + 轻量跨 App 调用，六位一体的个人 AI Agent 平台。

## 项目状态

🚧 **M0 脚手架 + M1 数据层 + 安全层 + BYOK Provider 配置 + 聊天 UI + 流式请求 + Provider 切换 + M2 MCP Client + M2 内置 Filesystem MCP Server + 预设远程 MCP Server 模板加载已完成（US-001~US-010 通过 guardrail 审查 + ac-verifier 验收）**（2026-08-06）

**M3 个人知识库 RAG 全部完成并通过里程碑交付审计（US-011~US-019，ADR-007~012 全部 Accepted，有条件通过，M3-001 打包修复已验证）**（2026-08-09）

**M4 Skills 系统全部完成（US-020~US-029，ADR-013/014 Accepted，Phase A~E 全部通过 guardrail + ac-verifier，912 回归 0 失败）**（2026-08-10）—— Skill 数据模型 / SKILL.md 解析 / 注册中心 / tool_calling 接口 / 工具执行回路 / Skills 管理 UI / 远程下载 / 执行可观测。已知限制：M-3 GAP（生产路径执行记录未接入，US-029 基础设施已就绪，待后续 Phase D 接入）

**M5 三层记忆系统 Phase A 完成（US-030 + US-031，ADR-015 Proposed，guardrail 通过 + ac-verifier 通过，971 回归 0 失败）**（2026-08-10）—— MemoryRecord @Entity + MemoryRepository CRUD/向量检索（L2 跨会话记忆）/ UserProfile @Entity + UserProfileRepository CRUD/upsert（L3 用户画像）。BR-security-001-amendment 转 active（nullable 数组字段 equals 覆盖须用 nullable 扩展函数）。性能基线建立（searchByVector p50=62us）

**M5 三层记忆系统 Phase B 完成（US-032，guardrail 通过 + ac-verifier 通过，6/6 AC，61 专项测试 0 失败）**（2026-08-11）—— ConversationSummarizer（非流式 LLM 摘要生成）+ SlidingWindowMemoryManager（L1 滑动窗口 N 轮 + 摘要压缩注入）+ MemoryConfigRepository（DataStore 持久化 N，默认 10）+ OpenAICompatibleProvider.chatCompletion 非流式扩展。摘要失败降级为截断（不阻断对话）

**M5 三层记忆系统 Phase C 完成（US-033，guardrail 三轮 + ac-verifier 通过，6/6 AC，58 专项测试 0 失败）**（2026-08-11）—— CrossSessionMemoryManager（L2 跨会话记忆：对话结束向量化存储 + 新会话 top-k 检索，默认 k=3）+ 防污染设计（仅注入检索结果片段，不加载旧会话全文）+ formatMemoriesAsContext 系统提示合并。性能基线建立（searchByVector p50=62us 复用 M3 基建）

**M5 三层记忆系统 Phase D 完成（US-034，guardrail 通过 + ac-verifier 通过，7/7 AC，80 专项测试 0 失败）**（2026-08-11）—— UserProfileManager（L3 用户画像：显式偏好 UI 设定 + 隐式偏好 LLM 抽取）+ parsePreferencesJson 结构化 JSON 解析（markdown 剥离 + 非字符串 JsonElement 防御）+ formatProfilesAsContext 系统提示合并 + 显式偏好不被隐式覆盖（优先级语义）+ 抽取失败降级为跳过。性能基线建立（formatProfilesAsContext p50=12us）

**M5 三层记忆系统 Phase E 完成（US-035 + US-036，ADR-015 Accepted，guardrail 两轮 + ac-verifier 通过，US-035 6/6 AC + US-036 5/5 AC（AC-1 受限通过：Compose UI 需 instrumented test），1237 全量回归 0 失败，35 新增测试）**（2026-08-11）—— ConversationViewModel 三层记忆集成（会话边界 sessionId UUID + L2 检索/L3 画像缓存 + onCleared fire-and-forget 持久化 + appScope SupervisorJob）+ systemPrompt 六层合并（RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill，ADR-015 决策4）+ 记忆管理 UI（L1 窗口配置 + L2 记忆单条删除 + L3 画像编辑/删除 + 一键清除二次确认）+ MemoryManagementViewModel 纯函数校验（MAX_PROFILE_KEY_LEN=50 / MAX_PROFILE_VALUE_LEN=500 防 token 溢出）。降级策略：L1/L2/L3 任一失败降级为 null，不阻断对话

**M6 跨 App 调用集成全部完成（US-037 + US-038 + US-039，ADR-016 Accepted，P2 跨模块，三阶段全部通过 guardrail + ac-verifier + 里程碑审计，1380 全量回归 0 失败）**（2026-08-11）—— 技术选型完成（方案 A 纯 Android 原生 + 方案 D 精简版复用 M4 SkillExecutor/ToolConfirmationGate，零新增第三方依赖）+ 源码考古完成（13 复用基建 + 10 项风险清单 + sequential-thinking 6 步推演 LocalToolExecutor vs IntentToolExecutor 决策）。三 Phase 拆分：Phase A CrossAppLauncher 核心模块（scheme 清单 + Deep Link + Share Sheet + Picker + Bridge）→ Phase B LocalToolExecutor AI 集成层（接口 + CrossAppLocalToolExecutor + SkillExecutor 扩展支持本地工具分支）→ Phase C UI 集成层（AndroidManifest <queries> + ConversationViewModel 工具注册 + ConversationScreen launcher + 用户确认 UI）。7 个目标 App：微信/支付宝/淘宝/抖音/QQ/微博/百度地图。关键缺陷修复：DEF-01 B2 严重（PrismApplication 注入遗漏）+ M-1 B1 一般（双重超时竞态 BR-concurrency-005 转 active）。已知受限：UNC-1 真机 E2E 7 App Deep Link 兼容性待补测

- US-020 Skill 数据模型（SkillConfig 实体 + SkillRepository CRUD）✅（Phase A，guardrail + ac-verifier 通过）
- US-023 StreamEvent/ChatStreamProvider 接口扩展预留 ✅（Phase A，guardrail + ac-verifier 通过，BR-naming-001 转 active）
- US-021 SKILL.md 解析器（snakeyaml-engine-kmp 4.0.1 + 安全 LoadSettings + frontmatter 校验）✅（Phase B，guardrail 三轮 + ac-verifier 两轮通过，BR-security-004 转 active）
- US-022 SkillRegistry（扫描/去重/同步/过滤 + 5 内置 Skill + PrismApplication 集成）✅（Phase B，guardrail 三轮 + ac-verifier 两轮通过，AC-5 受限→完全通过，BR-testing-004 转 active，629 回归 0 失败）
- US-024 OpenAICompatibleProvider tool_calling 协议（tool_calls delta 状态机 + ToolCallAccumulator + StreamEvent 6 子类）✅（Phase C，guardrail 两轮 + ac-verifier 通过，6/6 AC，726 回归 0 失败，性能基线建立）
- US-025 SkillExecutor 工具执行回路（maxRounds 10 + 用户确认 + 30s 超时 + namespace 前缀剥离 + 错误回灌）✅（Phase C，guardrail 两轮 + ac-verifier 通过，6/6 AC，M-1 sanitizeErrorMessage 信息脱敏 + 6 处 Log.w 结构化日志）
- US-026 ConversationViewModel Skill 注入与工具执行回路集成（buildTools + mergeSystemPrompt + executeWithToolLoop + handleStreamEvent + syncToolMessages + AtomicLong idGenerator + R-4 历史过滤器）✅（Phase D，guardrail 两轮 + ac-verifier 通过，7/7 AC，M-1/M-3 修复有效，M-2 异常路径补 3 测试验证通过，757 回归 0 失败，BR-error-handling-008 转 active）
- US-027 Skills 管理 UI 重构（CapabilitiesScreen Skill 列表 + 详情 + 启用开关 + SkillsViewModel）✅（Phase E，guardrail 两轮 + ac-verifier 通过，5/5 AC，813 回归 0 失败）
- US-028 远程 Skill 下载（SkillDownloader HTTPS 下载 + 9 层安全校验 + zip slip 防护 + backup-then-swap 原子安装）✅（Phase E，guardrail 两轮 + ac-verifier 通过，6/6 AC，39 MockEngine 集成测试 + 42 纯函数测试，862 回归 0 失败）
- US-029 Skill 执行可观测（SkillExecutionRecord @Entity + SkillExecutionRepository CRUD + Skill 详情页展示执行记录）✅（Phase E，guardrail 两轮 + ac-verifier 通过，6/6 AC，39 专项测试 + 10 边缘场景，912 回归 0 失败。已知限制：M-3 GAP 生产路径未接入 skillConfigId/skillName）

- US-030 MemoryRecord @Entity + MemoryRepository CRUD/向量检索（L2 跨会话记忆存储 + HNSW 索引 + session_id 隔离 + count/deleteAll/deleteBySession）✅（M5 Phase A，guardrail + ac-verifier 通过，5/5 AC，59 专项测试 + 971 全量回归 0 失败）
- US-031 UserProfile @Entity + UserProfileRepository CRUD/upsert（L3 用户画像 key/value/category + category 索引 + upsert 语义）✅（M5 Phase A，guardrail + ac-verifier 通过，5/5 AC，BR-security-001-amendment 转 active）
- US-032 L1 滑动窗口记忆（ConversationSummarizer 非流式 LLM 摘要 + SlidingWindowMemoryManager N 轮窗口 + 摘要压缩注入 + MemoryConfigRepository DataStore 持久化 N + 摘要失败降级截断）✅（M5 Phase B，guardrail + ac-verifier 通过，6/6 AC，61 专项测试 0 失败，性能基线建立）
- US-033 L2 跨会话记忆检索（CrossSessionMemoryManager 对话结束向量化存储 + 新会话 top-k 检索默认 k=3 + 防污染仅注入检索片段 + formatMemoriesAsContext 系统提示合并）✅（M5 Phase C，guardrail 三轮 + ac-verifier 通过，6/6 AC，58 专项测试 0 失败）
- US-034 L3 用户画像管理（UserProfileManager 显式偏好 UI 设定 + 隐式偏好 LLM 抽取 + parsePreferencesJson 结构化解析 + formatProfilesAsContext 系统提示合并 + 显式不被隐式覆盖 + 抽取失败降级跳过）✅（M5 Phase D，guardrail + ac-verifier 通过，7/7 AC，80 专项测试 0 失败，性能基线建立 formatProfilesAsContext p50=12us）
- US-035 ConversationViewModel 三层记忆系统集成（会话边界 sessionId UUID + L2 检索/L3 画像首条消息加载缓存 + onCleared fire-and-forget 持久化 appScope SupervisorJob + systemPrompt 六层合并 RAG→L1→L2→L3→Skill ADR-015 决策4 + L1/L2/L3 独立降级为 null 不阻断对话）✅（M5 Phase E，guardrail 两轮 + ac-verifier 通过，6/6 AC，17 集成测试 + 1237 全量回归 0 失败）
- US-036 记忆管理 UI（CapabilitiesScreen MemoryPanel + L1 窗口大小配置 + L2 记忆列表单条删除 + L3 画像列表编辑/删除 + 一键清除二次确认 + MemoryManagementViewModel 纯函数校验防 token 溢出）✅（M5 Phase E，guardrail 两轮 + ac-verifier 通过，5/5 AC（AC-1 受限通过 Compose UI 需 instrumented test），29 纯函数单元测试 + 6 deleteById 测试）
- US-037 M6 Phase A CrossAppLauncher 核心模块（app_schemes.json 7 App 配置 + SchemeRegistry + AppAvailabilityChecker + DeepLinkLauncher + ShareSheetLauncher + MediaPicker + AppLauncherBridge + CrossAppLauncher + CrossAppConfirmationRequest）✅（M6 Phase A，guardrail + ac-verifier 通过，10/10 AC，SchemeRegistryTest 14 + CrossAppLauncherTemplateTest 12 + AppAvailabilityCheckerTest 9 全部通过，零新增第三方依赖）
- US-038 M6 Phase B LocalToolExecutor AI 集成层（LocalToolExecutor 接口 + CrossAppLocalToolExecutor 实现 + SkillExecutor 扩展本地工具分支默认 null 向后兼容）✅（M6 Phase B，guardrail + ac-verifier 通过，10/10 AC，DEF-01 B2 严重缺陷（PrismApplication 注入遗漏）由 Phase C 闭合，CrossAppLocalToolExecutorTest 24 + SkillExecutorLocalToolTest 11 + M6PhaseBAcceptanceSupplementTest 26 用例全部通过）
- US-039 M6 Phase C UI 集成层（AndroidManifest queries 7+7+2 + PrismApplication 注入 + ConversationViewModel.buildTools 合并 + ConversationScreen ActivityResult launcher + 用户确认 UI + M-1 双重超时竞态修复 BR-concurrency-005 转 active）✅（M6 Phase C，guardrail 两轮 + ac-verifier 通过，10/10 AC，M6 里程碑审计通过 TKN-M6-MILESTONE-AUDIT-001，1380 全量回归 0 失败。已知受限：UNC-1 真机 E2E 7 App Deep Link 兼容性待补测）

- US-011 依赖落地 + KnowledgeChunk 向量索引 ✅（guardrail + ac-verifier 通过）
- US-012 文档解析器（PDF/DOCX/XLSX/MD/TXT）✅（guardrail 有条件通过 + ac-verifier 通过）
- US-013 文本切片器（段落边界优先 + overlap）✅（guardrail + ac-verifier 通过）
- US-014 端侧嵌入引擎（onnxruntime-android + all-MiniLM-L6-v2 INT8）✅（guardrial 两轮 + ac-verifier 通过，G-01 并发竞态阻断已修复）
- US-015 知识库分库数据模型（KnowledgeBase 实体 + Repository CRUD + 级联删除）✅（guardrail 两轮 + ac-verifier 通过，G-01 HNSW Query.remove bug #1209 已规避）
- US-016 摄入管线（解析→切片→嵌入→入库 + Flow 进度观察 + 嵌入失败降级）✅（guardrail 两轮 + ac-verifier 通过，M1 InputStream 泄漏阻断已修复，BR-error-handling-006 转 active）
- US-017 向量检索（HNSW nearestNeighbors top-k + 分库过滤 + 相似度转换 + 来源解析）✅（guardrail 一轮 Pass + ac-verifier 通过，48 测试 0 失败 + JVM 性能基线 p50<200us，5 低危建议 L1~L5 已处理）
- US-018 知识库管理 UI（KnowledgeBaseViewModel UiState + SAF OpenDocument 导入 + IngestionEvent 收集 + 状态原子性 + 错误安全映射）✅（guardrail 两轮 + ac-verifier 通过，G-01~G-05 修复有效，35 单元测试 + 524 全量回归 0 失败）
- US-019 RAG 对话集成（RagContextBuilder + RagTarget 三态 + ChatStreamProvider 接口扩展 systemPrompt/ragContext + Citation 多引用 inline 标注 + 三级降级 RagBuildResult sealed + 历史过滤器排除 aiId）✅（guardrail 两轮 + ac-verifier 通过，5/6 AC 完全通过 AC-2 UI 入口已知 GAP 不阻断，57 单元测试 + 519 全量回归 0 失败，BR-error-handling-007 / BR-interface-004 转 active）

- 平台：仅 Android（API 26+，Android 8.0+）
- 算力：纯云端 BYOK（用户自配 OpenAI/Claude/Ollama 等端点）
- 商业模式：个人开源免费 + 自发布（GitHub Releases / F-Droid / PGY）
- 协议：Apache 2.0
- 技术栈：见 [ADR-001](docs/decisions/ADR-001-prism-tech-stack.md)

## 文档索引（Diátaxis）

### Tutorial（教程）

- 本 README（新人入门入口）

### How-to Guide（操作指南）

- [docs/templates/](docs/templates/README.md) —— PRD / ARCH / ADR / Task 等模板

### Explanation（解释说明 / ADR）

- [docs/decisions/](docs/decisions/README.md) —— 架构决策记录
  - [ADR-001 Prism 技术栈与架构选型](docs/decisions/ADR-001-prism-tech-stack.md)（Accepted）
  - [ADR-002 Prism 聊天 UI 架构（US-005）](docs/decisions/ADR-002-prism-chat-ui-architecture.md)（Proposed）
  - [ADR-003 Provider 配置详情页接入（设置模块）](docs/decisions/ADR-003-prism-provider-config-settings.md)（Accepted）
  - [ADR-004 Prism Provider 流式请求（US-006/US-007）](docs/decisions/ADR-004-prism-provider-streaming.md)（Accepted）
  - [ADR-005 MCP Kotlin SDK Client 集成（US-008）](docs/decisions/ADR-005-mcp-client-integration.md)（Accepted）
  - [ADR-006 内置 Filesystem MCP Server（US-009）](docs/decisions/ADR-006-filesystem-mcp-server.md)（Accepted）
  - [ADR-007 M3 个人知识库 RAG 技术栈（US-003）](docs/decisions/ADR-007-m3-rag-tech-stack.md)（Accepted）
  - [ADR-008 M3 知识库分库数据模型（US-015）](docs/decisions/ADR-008-m3-knowledgebase-model.md)（Accepted）
  - [ADR-009 M3 摄入管线编排（US-016）](docs/decisions/ADR-009-m3-ingestion-pipeline.md)（Accepted）
  - [ADR-010 M3 向量检索（US-017）](docs/decisions/ADR-010-m3-vector-retrieval.md)（Accepted）
  - [ADR-011 M3 知识库管理 UI 架构（US-018）](docs/decisions/ADR-011-m3-knowledgebase-ui.md)（Accepted）
  - [ADR-012 M3 RAG 对话集成架构（US-019）](docs/decisions/ADR-012-m3-rag-conversation-integration.md)（Accepted）
  - [ADR-013 M4 Skills 系统架构（US-004）](docs/decisions/ADR-013-m4-skills-system-architecture.md)（Accepted）
  - [ADR-014 M4 LLM tool_calling 接口扩展（US-023~US-025）](docs/decisions/ADR-014-m4-toolcalling-interface.md)（Accepted）
  - [ADR-015 M5 三层记忆系统架构（US-005）](docs/decisions/ADR-015-m5-memory-system-architecture.md)（Accepted）
  - [ADR-016 M6 跨 App 调用架构（US-037）](docs/decisions/ADR-016-m6-cross-app-integration.md)（Accepted）

### Reference（参考 / 报告）

- [docs/PRD.md](docs/PRD.md) —— 产品需求文档 v0.1
- [prd.json](prd.json) —— Ralph 格式任务分解（M0-M2 首期 10 个用户故事）
- [docs/reports/](docs/reports/) —— 调研与考古报告
  - [2026-08-02-prism-feasibility-research.md](docs/reports/2026-08-02-prism-feasibility-research.md) —— 可行性调研汇报 v1.0
  - [2026-08-02-prism-tech-selection.md](docs/reports/2026-08-02-prism-tech-selection.md) —— 技术选型对比分析（tech-selection-researcher）
  - [2026-08-02-continuous-learning-archaeology.md](docs/reports/2026-08-02-continuous-learning-archaeology.md) —— Continuous-learning 考古（code-archaeologist）
  - [2026-08-02-openclaw-archaeology.md](docs/reports/2026-08-02-openclaw-archaeology.md) —— OpenClaw/NullClaw 考古（code-archaeologist）
  - [2026-08-02-us002-objectbox-archaeology.md](docs/reports/2026-08-02-us002-objectbox-archaeology.md) —— US-002 ObjectBox 集成源码考古（code-archaeologist）
  - [2026-08-02-us003-apikey-archaeology.md](docs/reports/2026-08-02-us003-apikey-archaeology.md) —— US-003 API Key 加密存储源码考古（code-archaeologist）
  - [2026-08-02-us001-m0-scaffold-guardrail.md](docs/reports/2026-08-02-us001-m0-scaffold-guardrail.md) —— US-001 M0 脚手架安全与质量审计（guardrail-enforcer，三轮）
  - [2026-08-02-us002-objectbox-guardrail.md](docs/reports/2026-08-02-us002-objectbox-guardrail.md) —— US-002 ObjectBox 安全与质量审计（guardrail-enforcer）
  - [2026-08-02-us003-apikey-guardrail.md](docs/reports/2026-08-02-us003-apikey-guardrail.md) —— US-003 API Key 加密存储安全与质量审计（guardrail-enforcer）
  - [2026-08-02-us004-provider-config-guardrail.md](docs/reports/2026-08-02-us004-provider-config-guardrail.md) —— US-004 Provider 配置数据模型安全与质量审计（guardrail-enforcer）
  - [2026-08-02-us001-m0-scaffold-acceptance.md](docs/reports/2026-08-02-us001-m0-scaffold-acceptance.md) —— US-001 M0 脚手架验收测试（ac-verifier）
  - [2026-08-02-us002-objectbox-acceptance.md](docs/reports/2026-08-02-us002-objectbox-acceptance.md) —— US-002 ObjectBox 数据库基础验收测试（ac-verifier）
  - [2026-08-02-us003-apikey-acceptance.md](docs/reports/2026-08-02-us003-apikey-acceptance.md) —— US-003 API Key 加密存储验收测试（ac-verifier）
  - [2026-08-02-us004-provider-config-acceptance.md](docs/reports/2026-08-02-us004-provider-config-acceptance.md) —— US-004 Provider 配置数据模型验收测试（ac-verifier）
  - [2026-08-05-ui-config-guardrail.md](docs/reports/2026-08-05-ui-config-guardrail.md) —— v0.4 UI 配置弹层安全与质量审计（guardrail-enforcer，两轮）
  - [2026-08-05-ui-config-acceptance.md](docs/reports/2026-08-05-ui-config-acceptance.md) —— v0.4 UI 配置弹层验收测试（ac-verifier）
  - [2026-08-05-settings-provider-guardrail.md](docs/reports/2026-08-05-settings-provider-guardrail.md) —— Provider 配置详情页接入安全与质量审计（guardrail-enforcer，两轮）
  - [2026-08-05-settings-provider-acceptance.md](docs/reports/2026-08-05-settings-provider-acceptance.md) —— Provider 配置详情页接入验收测试（ac-verifier）
  - [2026-08-05-settings-provider-guardrail-round2.md](docs/reports/2026-08-05-settings-provider-guardrail-round2.md) —— Provider 配置详情页接入增量安全与质量审计（guardrail-enforcer，R2）
  - [2026-08-05-settings-provider-acceptance-round2.md](docs/reports/2026-08-05-settings-provider-acceptance-round2.md) —— Provider 配置详情页接入增量验收测试（ac-verifier，R2）
  - [2026-08-05-us006-provider-streaming-tech-selection.md](docs/reports/2026-08-05-us006-provider-streaming-tech-selection.md) —— US-006 流式请求技术选型对比（tech-selection-researcher）
  - [2026-08-05-us006-provider-streaming-guardrail.md](docs/reports/2026-08-05-us006-provider-streaming-guardrail.md) —— US-006 流式请求安全与质量审计（guardrail-enforcer，阻断）
  - [2026-08-05-us006-guardrail.md](docs/reports/2026-08-05-us006-guardrail.md) —— US-006 流式请求 CR-01~CR-05 修复复审（guardrail-enforcer，条件通过）
  - [2026-08-06-us006-guardrail-recheck.md](docs/reports/2026-08-06-us006-guardrail-recheck.md) —— US-006 流式请求 CR-02 残留修复复审（guardrail-enforcer，通过）
  - [2026-08-06-us006-acceptance.md](docs/reports/2026-08-06-us006-acceptance.md) —— US-006 流式请求验收测试（ac-verifier，通过）
  - [2026-08-06-us007-guardrail.md](docs/reports/2026-08-06-us007-guardrail.md) —— US-007 Provider 切换安全与质量审计（guardrail-enforcer，有条件通过）
  - [2026-08-06-us007-guardrail-round2.md](docs/reports/2026-08-06-us007-guardrail-round2.md) —— US-007 Provider 切换修复复审（guardrail-enforcer，通过）
  - [2026-08-06-us007-acceptance.md](docs/reports/2026-08-06-us007-acceptance.md) —— US-007 Provider 切换验收测试（ac-verifier，通过）
  - [2026-08-06-us008-mcp-client-archaeology.md](docs/reports/2026-08-06-us008-mcp-client-archaeology.md) —— US-008 MCP Client 集成源码考古（code-archaeologist）
  - [2026-08-06-us008-mcp-client-guardrail.md](docs/reports/2026-08-06-us008-mcp-client-guardrail.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第一轮）
  - [2026-08-06-us008-mcp-client-guardrail-round2.md](docs/reports/2026-08-06-us008-mcp-client-guardrail-round2.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第二轮）
  - [2026-08-06-us008-mcp-client-guardrail-round3.md](docs/reports/2026-08-06-us008-mcp-client-guardrail-round3.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第三轮）
  - [2026-08-06-us008-mcp-client-guardrail-round4.md](docs/reports/2026-08-06-us008-mcp-client-guardrail-round4.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第四轮，通过）
  - [2026-08-06-us008-mcp-integrationtest-guardrail.md](docs/reports/2026-08-06-us008-mcp-integrationtest-guardrail.md) —— US-008 真实 MCP Server 集成测试安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-06-us008-mcp-client-acceptance.md](docs/reports/2026-08-06-us008-mcp-client-acceptance.md) —— US-008 MCP Client 集成验收测试（ac-verifier，有条件通过）
  - [2026-08-06-us008-mcp-client-acceptance-r2.md](docs/reports/2026-08-06-us008-mcp-client-acceptance-r2.md) —— US-008 MCP Client 集成验收复验（ac-verifier，通过）
  - [2026-08-06-us009-filesystem-mcp-archaeology.md](docs/reports/2026-08-06-us009-filesystem-mcp-archaeology.md) —— US-009 Filesystem MCP Server 源码考古与 SDK 复核（code-archaeologist）
  - [2026-08-06-us009-filesystem-mcp-guardrail.md](docs/reports/2026-08-06-us009-filesystem-mcp-guardrail.md) —— US-009 Filesystem MCP Server 安全与质量审计（guardrail-enforcer，两轮，通过）
  - [2026-08-06-us009-filesystem-mcp-acceptance.md](docs/reports/2026-08-06-us009-filesystem-mcp-acceptance.md) —— US-009 Filesystem MCP Server 验收测试（ac-verifier，通过）
  - [2026-08-06-us010-remote-templates-guardrail.md](docs/reports/2026-08-06-us010-remote-templates-guardrail.md) —— US-010 预设远程 MCP Server 模板安全与质量审计（guardrail-enforcer，条件通过 → 复审通过）
  - [2026-08-06-us010-remote-templates-acceptance.md](docs/reports/2026-08-06-us010-remote-templates-acceptance.md) —— US-010 预设远程 MCP Server 模板验收测试（ac-verifier，通过）
  - [2026-08-06-m0m2-milestone-audit.md](docs/reports/2026-08-06-m0m2-milestone-audit.md) —— M0-M2 首期里程碑交付审计（functional-validation-auditor，通过）
  - [2026-08-06-m3-rag-tech-selection.md](docs/reports/2026-08-06-m3-rag-tech-selection.md) —— M3 个人知识库 RAG 技术选型对比（tech-selection-researcher）
  - [2026-08-06-us011-deps-vectorindex-guardrail.md](docs/reports/2026-08-06-us011-deps-vectorindex-guardrail.md) —— US-011 依赖落地 + 向量索引安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-06-us011-deps-vectorindex-acceptance.md](docs/reports/2026-08-06-us011-deps-vectorindex-acceptance.md) —— US-011 依赖落地 + 向量索引验收测试（ac-verifier，通过）
  - [2026-08-06-us012-document-parser-guardrail.md](docs/reports/2026-08-06-us012-document-parser-guardrail.md) —— US-012 文档解析器安全与质量审计（guardrail-enforcer，有条件通过）
  - [2026-08-06-us012-document-parser-acceptance.md](docs/reports/2026-08-06-us012-document-parser-acceptance.md) —— US-012 文档解析器验收测试（ac-verifier，通过）
  - [2026-08-06-us013-chunker-guardrail.md](docs/reports/2026-08-06-us013-chunker-guardrail.md) —— US-013 文本切片器安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-06-us013-chunker-acceptance.md](docs/reports/2026-08-06-us013-chunker-acceptance.md) —— US-013 文本切片器验收测试（ac-verifier，通过）
  - [2026-08-07-us014-embedding-guardrail.md](docs/reports/2026-08-07-us014-embedding-guardrail.md) —— US-014 端侧嵌入引擎安全与质量审计（guardrail-enforcer，第一轮阻断，G-01 并发竞态）
  - [2026-08-07-us014-embedding-guardrail-round2.md](docs/reports/2026-08-07-us014-embedding-guardrail-round2.md) —— US-014 端侧嵌入引擎修复复审（guardrail-enforcer，第二轮通过，G-01~G-15 修复）
  - [2026-08-07-us014-embedding-acceptance.md](docs/reports/2026-08-07-us014-embedding-acceptance.md) —— US-014 端侧嵌入引擎验收测试（ac-verifier，通过，5/5 AC）
  - [2026-08-07-us015-data-archaeology.md](docs/reports/2026-08-07-us015-data-archaeology.md) —— US-015 知识库分库数据模型源码考古（code-archaeologist，简化版）
  - [2026-08-07-us015-knowledgebase-model-guardrail.md](docs/reports/2026-08-07-us015-knowledgebase-model-guardrail.md) —— US-015 知识库分库数据模型安全与质量审计（guardrail-enforcer，有条件通过，G-01 HNSW Query.remove 已知 bug 风险）
  - [2026-08-07-us015-knowledgebase-model-guardrail-round2.md](docs/reports/2026-08-07-us015-knowledgebase-model-guardrail-round2.md) —— US-015 知识库分库数据模型修复复审（guardrail-enforcer，第二轮通过，G-01~G-05 修复）
  - [2026-08-07-us015-knowledgebase-model-acceptance.md](docs/reports/2026-08-07-us015-knowledgebase-model-acceptance.md) —— US-015 知识库分库数据模型验收测试（ac-verifier，通过，5/5 AC，31 单元测试 + 全量 410 回归 0 失败）
  - [2026-08-07-us016-ingestion-archaeology.md](docs/reports/2026-08-07-us016-ingestion-archaeology.md) —— US-016 摄入管线源码考古（code-archaeologist，4 组件接口契约 + 8 项风险清单）
  - [2026-08-07-us016-ingestion-pipeline-guardrail.md](docs/reports/2026-08-07-us016-ingestion-pipeline-guardrail.md) —— US-016 摄入管线安全与质量审计（guardrail-enforcer，第一轮有条件通过，M1 InputStream 泄漏阻断）
  - [2026-08-07-us016-ingestion-pipeline-guardrail-round2.md](docs/reports/2026-08-07-us016-ingestion-pipeline-guardrail-round2.md) —— US-016 摄入管线修复复审（guardrail-enforcer，第二轮通过，M1 修复有效 + catch(IllegalArgumentException) 设计合理）
  - [2026-08-07-us016-ingestion-pipeline-acceptance.md](docs/reports/2026-08-07-us016-ingestion-pipeline-acceptance.md) —— US-016 摄入管线验收测试（ac-verifier，通过，5/5 AC，28 测试 + 438 全量回归 0 失败，BR-error-handling-006 转 active）
  - [2026-08-07-us017-retrieval-archaeology.md](docs/reports/2026-08-07-us017-retrieval-archaeology.md) —— US-017 向量检索源码考古（code-archaeologist，9 项风险清单 + nearestNeighbors+equal 组合零先例预警）
  - [2026-08-07-us017-retrieval-guardrail.md](docs/reports/2026-08-07-us017-retrieval-guardrail.md) —— US-017 向量检索安全与质量审计（guardrail-enforcer，通过，0 阻断/0 高危/5 低危建议）
  - [2026-08-07-us017-retrieval-acceptance.md](docs/reports/2026-08-07-us017-retrieval-acceptance.md) —— US-017 向量检索验收测试（ac-verifier，通过，5/5 AC，48 测试 0 失败 + JVM 性能基线 p50<200us）
  - [2026-08-07-us018-kb-ui-archaeology.md](docs/reports/2026-08-07-us018-kb-ui-archaeology.md) —— US-018 知识库管理 UI 源码考古（code-archaeologist，12 项风险清单 R-1~R-12）
  - [2026-08-07-us018-kb-ui-guardrail.md](docs/reports/2026-08-07-us018-kb-ui-guardrail.md) —— US-018 知识库管理 UI 安全与质量审计（guardrail-enforcer，第一轮有条件通过，G-01~G-04 须修复）
  - [2026-08-07-us018-kb-ui-guardrail-round2.md](docs/reports/2026-08-07-us018-kb-ui-guardrail-round2.md) —— US-018 知识库管理 UI 修复复审（guardrail-enforcer，第二轮通过，G-01~G-05 全部修复有效）
  - [2026-08-07-us018-kb-ui-acceptance.md](docs/reports/2026-08-07-us018-kb-ui-acceptance.md) —— US-018 知识库管理 UI 验收测试（ac-verifier，通过，5/5 AC，35 单元测试 + 524 全量回归 0 失败，R2-1 日志措辞修复有效）
  - [2026-08-07-us019-rag-integration-archaeology.md](docs/reports/2026-08-07-us019-rag-integration-archaeology.md) —— US-019 RAG 对话集成源码考古（code-archaeologist，7 项风险清单 + 9 文件接口契约分析）
  - [2026-08-07-us019-rag-integration-impact-selfcheck.md](docs/reports/2026-08-07-us019-rag-integration-impact-selfcheck.md) —— US-019 RAG 对话集成变更影响自检 v1+v2（含 G-01~G-05 修复后二次自检，CLAUDE.md 7.2.5）
  - [2026-08-07-us019-rag-integration-guardrail.md](docs/reports/2026-08-07-us019-rag-integration-guardrail.md) —— US-019 RAG 对话集成安全与质量审计 round 1（guardrail-enforcer，有条件通过，1 HIGH + 4 MEDIUM）
  - [2026-08-07-us019-rag-integration-guardrail-round2.md](docs/reports/2026-08-07-us019-rag-integration-guardrail-round2.md) —— US-019 RAG 对话集成修复复审（guardrail-enforcer，第二轮通过，G-01~G-05 全部修复有效）
  - [2026-08-07-us019-rag-integration-acceptance.md](docs/reports/2026-08-07-us019-rag-integration-acceptance.md) —— US-019 RAG 对话集成验收测试（ac-verifier，通过，5/6 AC 完全通过 AC-2 UI 入口已知 GAP 不阻断，57 单元测试 + 519 全量回归 0 失败，BR-error-handling-007 / BR-interface-004 转 active）
  - [2026-08-07-m3-milestone-audit.md](docs/reports/2026-08-07-m3-milestone-audit.md) —— M3 个人知识库 RAG 里程碑交付审计（functional-validation-auditor，有条件通过，M3-001 打包修复已验证，限期项已同步）
  - [2026-08-09-m4-skills-archaeology.md](docs/reports/2026-08-09-m4-skills-archaeology.md) —— M4 Skills 系统集成点源码考古（code-archaeologist，6 集成点 + 10 项风险清单）
  - [2026-08-09-m4-toolcalling-tech-selection.md](docs/reports/2026-08-09-m4-toolcalling-tech-selection.md) —— M4 Skills 系统 LLM tool_calling 接口扩展技术选型对比（tech-selection-researcher）
  - [2026-08-09-m4-phaseA-impact-selfcheck.md](docs/reports/2026-08-09-m4-phaseA-impact-selfcheck.md) —— M4 Phase A 基础层变更影响自检（主 Agent，5 项契约变更 + 6 调用方 + Role.TOOL bug 修复）
  - [2026-08-09-m4-phaseA-guardrail.md](docs/reports/2026-08-09-m4-phaseA-guardrail.md) —— M4 Phase A 基础层安全与质量审计（guardrail-enforcer，通过，G-01 Log.w 修复 + BR-naming-001 提议）
  - [2026-08-09-m4-phaseA-acceptance.md](docs/reports/2026-08-09-m4-phaseA-acceptance.md) —— M4 Phase A 基础层验收测试（ac-verifier，通过，US-020 6/6 + US-023 5/6+1 有条件，556 回归 0 失败）
  - [2026-08-09-m4-phaseB-impact-selfcheck.md](docs/reports/2026-08-09-m4-phaseB-impact-selfcheck.md) —— M4 Phase B 变更影响自检（主 Agent，含四轮自检：初检 + G 项修复 + R2-1 修复 + 回退补 SkillRegistryTest）
  - [2026-08-09-m4-phaseB-guardrail.md](docs/reports/2026-08-09-m4-phaseB-guardrail.md) —— M4 Phase B 安全与质量审计 round 1（guardrail-enforcer，通过 7G）
  - [2026-08-09-m4-phaseB-guardrail-round2.md](docs/reports/2026-08-09-m4-phaseB-guardrail-round2.md) —— M4 Phase B 安全与质量审计 round 2（guardrail-enforcer，通过 R2-1 深度限制补强）
  - [2026-08-09-m4-phaseB-guardrail-round3.md](docs/reports/2026-08-09-m4-phaseB-guardrail-round3.md) —— M4 Phase B 安全与质量审计 round 3（guardrail-enforcer，回退修复复审通过）
  - [2026-08-09-m4-phaseB-acceptance.md](docs/reports/2026-08-09-m4-phaseB-acceptance.md) —— M4 Phase B 验收测试 round 1（ac-verifier，受限通过，AC-5 受限根因：SkillRegistryTest 缺失）
  - [2026-08-09-m4-phaseB-acceptance-round2.md](docs/reports/2026-08-09-m4-phaseB-acceptance-round2.md) —— M4 Phase B 验收测试 round 2（ac-verifier，完全通过，AC-5 升级 + BR-testing-004 转 active + 629 回归 0 失败）
  - [2026-08-09-m4-phaseC-archaeology.md](docs/reports/2026-08-09-m4-phaseC-archaeology.md) —— M4 Phase C 源码考古（code-archaeologist，OpenAICompatibleProvider + SkillExecutor 集成点分析）
  - [2026-08-09-m4-phaseC-impact-selfcheck.md](docs/reports/2026-08-09-m4-phaseC-impact-selfcheck.md) —— M4 Phase C 变更影响自检（首轮，US-024+US-025 blast-radius）
  - [2026-08-09-m4-phaseC-guardrail.md](docs/reports/2026-08-09-m4-phaseC-guardrail.md) —— M4 Phase C 安全与质量审计 round 1（guardrail-enforcer，有条件通过，4 中风险 M1-M4）
  - [2026-08-09-m4-phaseC-impact-selfcheck-v2.md](docs/reports/2026-08-09-m4-phaseC-impact-selfcheck-v2.md) —— M4 Phase C 修复二次自检（M2/M3/M4 修复 + M1 测试补齐，713 回归 0 失败）
  - [2026-08-09-m4-phaseC-guardrail-v2.md](docs/reports/2026-08-09-m4-phaseC-guardrail-v2.md) —— M4 Phase C 安全与质量审计 round 2（guardrail-enforcer，通过，0 阻断/0 高危/0 中风险）
  - [2026-08-09-m4-phaseC-acceptance.md](docs/reports/2026-08-09-m4-phaseC-acceptance.md) —— M4 Phase C 验收测试（ac-verifier，US-024 6/6 + US-025 6/6 AC 通过，726 回归 0 失败，性能基线建立）
  - [2026-08-09-m4-phaseD-archaeology.md](docs/reports/2026-08-09-m4-phaseD-archaeology.md) —— M4 Phase D 集成模式源码考古（code-archaeologist，简化版，executeLoop onEvent 融合 + idGenerator 冲突 2 项关键结论）
  - [2026-08-09-m4-phaseD-impact-selfcheck.md](docs/reports/2026-08-09-m4-phaseD-impact-selfcheck.md) —— M4 Phase D 变更影响自检（主 Agent，P2 跨模块，构造器向后兼容 + open 可测性补强 + 750 回归 0 失败）
  - [2026-08-09-m4-phaseD-guardrail.md](docs/reports/2026-08-09-m4-phaseD-guardrail.md) —— M4 Phase D 安全与质量审计 round 1（guardrail-enforcer，通过，3 中危 M-1/M-2/M-3）
  - [2026-08-09-m4-phaseD-guardrail-round2.md](docs/reports/2026-08-09-m4-phaseD-guardrail-round2.md) —— M4 Phase D 安全与质量审计 round 2（guardrail-enforcer，通过，M-1/M-3 修复有效）
  - [2026-08-09-m4-phaseD-acceptance.md](docs/reports/2026-08-09-m4-phaseD-acceptance.md) —— M4 Phase D 验收测试（ac-verifier，通过，7/7 AC，M-2 异常路径补 3 测试，757 回归 0 失败）
  - [2026-08-09-m4-phaseE-archaeology.md](docs/reports/2026-08-09-m4-phaseE-archaeology.md) —— M4 Phase E 集成模式源码考古（code-archaeologist，SkillsViewModel + CapabilitiesScreen 集成点）
  - [2026-08-09-m4-phaseE-us027-impact-selfcheck.md](docs/reports/2026-08-09-m4-phaseE-us027-impact-selfcheck.md) —— M4 Phase E US-027 变更影响自检（主 Agent）
  - [2026-08-09-m4-phaseE-us027-guardrail.md](docs/reports/2026-08-09-m4-phaseE-us027-guardrail.md) —— M4 Phase E US-027 安全与质量审计 round 1（guardrail-enforcer）
  - [2026-08-09-m4-phaseE-us027-guardrail-round2.md](docs/reports/2026-08-09-m4-phaseE-us027-guardrail-round2.md) —— M4 Phase E US-027 安全与质量审计 round 2（guardrail-enforcer）
  - [2026-08-09-m4-phaseE-us027-acceptance.md](docs/reports/2026-08-09-m4-phaseE-us027-acceptance.md) —— M4 Phase E US-027 验收测试（ac-verifier）
  - [2026-08-09-m4-phaseE-us028-impact-selfcheck.md](docs/reports/2026-08-09-m4-phaseE-us028-impact-selfcheck.md) —— M4 Phase E US-028 变更影响自检（主 Agent）
  - [2026-08-09-m4-phaseE-us028-guardrail.md](docs/reports/2026-08-09-m4-phaseE-us028-guardrail.md) —— M4 Phase E US-028 安全与质量审计 round 1（guardrail-enforcer，10 项问题 P1×1/P2×3/P3×6）
  - [2026-08-09-m4-phaseE-us028-guardrail-r2.md](docs/reports/2026-08-09-m4-phaseE-us028-guardrail-r2.md) —— M4 Phase E US-028 安全与质量审计 round 2（guardrail-enforcer，通过，P1-01 修复 + P2-01/02/03 修复）
  - [2026-08-09-m4-phaseE-us028-acceptance.md](docs/reports/2026-08-09-m4-phaseE-us028-acceptance.md) —— M4 Phase E US-028 验收测试（ac-verifier，通过，6/6 AC，39 MockEngine 集成测试 + 42 纯函数，862 回归 0 失败）
  - [2026-08-10-m4-phaseE-us029-impact-selfcheck.md](docs/reports/2026-08-10-m4-phaseE-us029-impact-selfcheck.md) —— M4 Phase E US-029 变更影响自检（主 Agent）
  - [2026-08-10-m4-phaseE-us029-guardrail.md](docs/reports/2026-08-10-m4-phaseE-us029-guardrail.md) —— M4 Phase E US-029 安全与质量审计 round 1（guardrail-enforcer，有条件通过，M-1 须修复）
  - [2026-08-10-m4-phaseE-us029-guardrail-r2.md](docs/reports/2026-08-10-m4-phaseE-us029-guardrail-r2.md) —— M4 Phase E US-029 安全与质量审计 round 2（guardrail-enforcer，通过，M-1 修复有效）
  - [2026-08-10-m4-phaseE-us029-acceptance.md](docs/reports/2026-08-10-m4-phaseE-us029-acceptance.md) —— M4 Phase E US-029 验收测试（ac-verifier，通过，6/6 AC，39 US-029 专项测试 + 10 边缘场景，912 回归 0 失败）
  - [2026-08-10-m5-archaeology.md](docs/reports/2026-08-10-m5-archaeology.md) —— M5 记忆系统基建源码考古（code-archaeologist，6 集成点 + R-1~R-15 风险清单）
  - [2026-08-10-m5-phaseA-impact-selfcheck.md](docs/reports/2026-08-10-m5-phaseA-impact-selfcheck.md) —— M5 Phase A 变更影响自检（主 Agent，P1 常规，4 项设计决策偏离说明）
  - [2026-08-10-m5-phaseA-guardrail.md](docs/reports/2026-08-10-m5-phaseA-guardrail.md) —— M5 Phase A 安全与质量审计（guardrail-enforcer，通过，0 阻断/0 高危/0 中危/5 低危建议，L-01 已修复）
  - [2026-08-10-m5-phaseA-acceptance.md](docs/reports/2026-08-10-m5-phaseA-acceptance.md) —— M5 Phase A 验收测试（ac-verifier，通过，US-030 5/5 + US-031 5/5 AC，59 专项测试 + 971 全量回归 0 失败）
  - [性能基线](docs/reports/perf/) —— 性能基线报告目录
    - [2026-08-02-us002-objectbox-crud-baseline.md](docs/reports/perf/2026-08-02-us002-objectbox-crud-baseline.md) —— US-002 ObjectBox CRUD 性能基线
    - [2026-08-02-us003-apikey-baseline.md](docs/reports/perf/2026-08-02-us003-apikey-baseline.md) —— US-003 API Key 加密存储性能基线
    - [2026-08-02-us004-provider-config-baseline.md](docs/reports/perf/2026-08-02-us004-provider-config-baseline.md) —— US-004 Provider 配置数据模型性能基线
    - [2026-08-07-us014-embedding-baseline.md](docs/reports/perf/2026-08-07-us014-embedding-baseline.md) —— US-014 端侧嵌入引擎性能基线（初版，JVM）
    - [2026-08-07-us016-ingestion-pipeline-baseline.md](docs/reports/perf/2026-08-07-us016-ingestion-pipeline-baseline.md) —— US-016 摄入管线编排性能基线（初版，FakeEmbedder 100 chunk p99 735ms）
    - [2026-08-07-us017-retrieval-baseline.md](docs/reports/perf/2026-08-07-us017-retrieval-baseline.md) —— US-017 向量检索性能基线（JVM，p50<200us，10K chunk 规模验证）
    - [2026-08-10-m5-phaseA-memory-baseline.md](docs/reports/perf/2026-08-10-m5-phaseA-memory-baseline.md) —— M5 Phase A 记忆系统性能基线（JVM，searchByVector p50=62us / save p50=1311us / getBySession p50=92us）
    - [2026-08-11-m5-phaseB-memory-baseline.md](docs/reports/perf/2026-08-11-m5-phaseB-memory-baseline.md) —— M5 Phase B 滑动窗口记忆性能基线（JVM，processMessages p50 / summarize 调用 LLM 非流式）
    - [2026-08-11-m5-phaseC-memory-baseline.md](docs/reports/perf/2026-08-11-m5-phaseC-memory-baseline.md) —— M5 Phase C 跨会话记忆检索性能基线（JVM，retrieveRelevantMemories p50 / saveSessionMemories p50）
    - [2026-08-11-m5-phaseD-profile-baseline.md](docs/reports/perf/2026-08-11-m5-phaseD-profile-baseline.md) —— M5 Phase D 用户画像性能基线（JVM，formatProfilesAsContext p50=12us / extractImplicitPreferences 调用 LLM 非流式）
    - [2026-08-11-m6-phase-b-baseline.md](docs/reports/perf/2026-08-11-m6-phase-b-baseline.md) —— M6 Phase B 跨 App 调用性能基线（JVM，handles/resolveTemplates/executeToolCall/isFailureResult）
  - [2026-08-11-m6-archaeology.md](docs/reports/2026-08-11-m6-archaeology.md) —— M6 跨 App 调用源码考古（code-archaeologist，13 复用基建 + 10 项风险清单 + 方案 A 推荐）
  - [2026-08-11-m6-tech-selection.md](docs/reports/2026-08-11-m6-tech-selection.md) —— M6 跨 App 调用技术选型对比（tech-selection-researcher，方案 A 纯原生 + 方案 D 精简版复用 M4）
  - [2026-08-11-m6-phase-a-guardrail.md](docs/reports/2026-08-11-m6-phase-a-guardrail.md) —— M6 Phase A 安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-11-m6-phase-a-acceptance.md](docs/reports/2026-08-11-m6-phase-a-acceptance.md) —— M6 Phase A 验收测试（ac-verifier，通过，10/10 AC）
  - [2026-08-11-m6-phase-b-guardrail.md](docs/reports/2026-08-11-m6-phase-b-guardrail.md) —— M6 Phase B 安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-11-m6-phase-b-acceptance.md](docs/reports/2026-08-11-m6-phase-b-acceptance.md) —— M6 Phase B 验收测试（ac-verifier，通过，10/10 AC，DEF-01 B2 缺陷由 Phase C 闭合）
  - [2026-08-11-m6-phase-c-archaeology.md](docs/reports/2026-08-11-m6-phase-c-archaeology.md) —— M6 Phase C 源码考古（code-archaeologist，ConversationScreen 集成点分析）
  - [2026-08-11-m6-phase-c-impact-selfcheck.md](docs/reports/2026-08-11-m6-phase-c-impact-selfcheck.md) —— M6 Phase C 变更影响自检（主 Agent，含 blast-radius 二次自检）
  - [2026-08-11-m6-phase-c-guardrail.md](docs/reports/2026-08-11-m6-phase-c-guardrail.md) —— M6 Phase C 安全与质量审计（guardrail-enforcer，两轮，M-1 双重超时竞态修复）
  - [2026-08-11-m6-phase-c-acceptance.md](docs/reports/2026-08-11-m6-phase-c-acceptance.md) —— M6 Phase C 验收测试（ac-verifier，通过，10/10 AC，DEF-01 闭合 + BR-concurrency-005 转 active）
  - [2026-08-11-m6-milestone-audit.md](docs/reports/2026-08-11-m6-milestone-audit.md) —— M6 里程碑交付审计（functional-validation-auditor，有条件交付→已闭合）

### 运维

- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
