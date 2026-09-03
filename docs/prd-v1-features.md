# v1 新功能 · 产品需求文档（PRD）—— 记忆深度优化 + LLM 操控手机 + 纯文本模型识图（方案 B）

> 依 CLAUDE.md 流程：本 PRD 记录三项新功能的网络调研结论、源码考古现状与分项执行方案。
> **本阶段为「调研 + 方案确认」**：用户确认执行方案与决策点后进入开发。所有验收标准须可验证。

| 项目 | 内容 |
|---|---|
| 版本 | v1.0（规划；当前 build.gradle.kts versionName=0.1.0，用户口径 1.0） |
| 日期 | 2026-08-19 |
| 作者 | 主 Agent + 用户 |
| 关联文档 | 三份技术选型调研报告（记忆/手机操控/识图）、考古报告 2026-08-19 源码考古（docs/reports/）、[ADR-015](../decisions/ADR-015-m5-memory-system-architecture.md)、[ADR-031](../decisions/ADR-031-uxr9-multilingual-embedding-and-l2-memory.md)、[ADR-033](../decisions/ADR-033-uxr11-real-device-fixes.md) |
| 风险等级 | P3（重大：新增系统权限 + 新依赖 + 跨模块数据模型/接口变更） |

---

## 1. 背景

用户希望将 Prism 提升到新版本，新增三项能力：

1. **记忆深度优化**：参照腾讯开源项目 [TencentDB-Agent-Memory](https://github.com/TencentCloud/TencentDB-Agent-Memory)，对现有三层记忆系统深度优化，获得更好的记忆能力。
2. **LLM 操控手机**：参照智谱开源项目 [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)，让"用户通过 API 配置的通用 LLM"可以直接操控手机，达到 Phone Agent 效果。
3. **纯文本模型识图（方案 B）**：补全 [prd-uxr8.md](prd-uxr8.md) 第 4.3 节的方案 B（直传 + 云端旁路 + OCR 兜底），让纯文本 LLM 也能识图。

主 Agent 已完成：源码考古（记忆/工具回路/视觉三模块）+ 三项独立网络调研（tech-selection-researcher × 3）+ 两个参照仓库源码实证（GitHub MCP）。本 PRD 汇总结论并给出执行方案，其中**任务 2 经调研判定与 Open-AutoGLM 存在根本架构差异，需用户决策替代方案**（见第 8 节）。

---

## 2. 目标与非目标

### 2.1 目标

- **T1 记忆系统深度优化**（P2）：参照 TencentDB-Agent-Memory 的分层蒸馏与混合检索思想，落地 4 大核心机制——① 原子记忆抽取升级（场景切分 + 提取单次 LLM 调用 + 类型/重要性标注）；② 混合检索（SQLite FTS5 BM25 + 端侧向量 HNSW + RRF(k=60) 融合）；③ 批量去重（store/update/merge/skip 四动作 + 失败降级）；④ 记忆生命周期管理（软衰减 + 容量上限 + 注入预算）。
- **T2 LLM 操控手机**（P3）：新增 `phone_control__` 本地工具集，通过 AccessibilityService 读取 UI 树（文本模型即可感知）+ 执行点击/滑动/输入/返回/启动应用；截图作增强（API30+ 无障碍截图）；敏感操作硬拦截 + 人工接管。
- **T3 纯文本模型识图（方案 B）**（P2）：云端视觉旁路 Provider（复用现有 OpenAI 兼容协议）+ ML Kit 端侧 OCR 兜底 + 降级触发链。

### 2.2 非目标（明确不做）

- **不做团队共享 / ACL 租户隔离 / Memory Proxy / Wiki / CodeGraph / credit 计费**（TencentDB-Agent-Memory 面向企业多租户的机制，单机个人 Agent 无意义，见调研报告 §7）。
- **不做 Shizuku / Root 依赖**（任务 2 仅作可选高级模式预留，默认不引入第三方依赖）。
- **不做自定义 IME 中文输入**（任务 2 第一阶段，工程量大，P1 用 ACTION_SET_TEXT + 剪贴板粘贴降级）。
- **不做非文字图的高精度理解**（任务 3 OCR 仅对含文字图片有效，物体/场景照片 OCR 为空时落到原提示）。
- **不引入 Google Play Services / Firebase 依赖**（F-Droid/PGY 发布渠道无 GMS）。

---

## 3. 用户故事与验收标准

### 3.1 T1 记忆系统深度优化

#### US-101: 记忆原子抽取升级（场景切分 + 提取 + 类型/重要性标注）

- 作为用户，我希望对话结束后能被自动蒸馏为「偏好/事实/决策」原子记忆并标注重要性，以便跨会话精准回忆。
- 验收标准：
  - [ ] `extractMemories` 升级为「场景切分 + 提取」单次 LLM 调用，输出含 `content/type(persona/episodic/instruction)/priority(0-100)/sourceMessageIds` 的原子记忆
  - [ ] LLM 失败 → 降级为规则抽取逐对存储；LLM 成功但空 → 不落库（三态保留）
  - [ ] MemoryRecord 新增 `priority`、`accessCount`、`version`、`sourceMessageIds` 字段（ObjectBox 迁移）
  - [ ] 单元测试覆盖 type 规范化（episode→episodic 等）与 priority 兜底（非数字→50）

#### US-102: 混合检索（FTS5 BM25 + 向量 + RRF）

- 作为用户，我希望记忆检索能同时命中关键词与语义相关的内容，以便"我记得你上次说过 XXX"这类精确词句也能被召回。
- 验收标准：
  - [ ] 基于 MemoryRepository 数据并建 SQLite FTS5 索引（复用 RAG Unigram 分词器预分词，中文整词预拼接），增量维护
  - [ ] 检索路径改为「FTS5 BM25 + HNSW 向量 → RRF(k=60) 合并 → 阈值/条数过滤」
  - [ ] 混合检索相比纯向量在标注集上 MRR@5 有提升（PoC 门禁）
  - [ ] P95 检索延迟 ≤50ms（千条记忆规模）
  - [ ] 全量回归 0 失败；APK 体积增量 ≤1MB（FTS5 系统内置 + RRF 纯 Kotlin 约 30 行）

#### US-103: 批量去重与记忆生命周期管理

- 作为用户，我希望重复的记忆不会被反复保存、过时记忆会被降权或回收，以便记忆库保持干净不膨胀。
- 验收标准：
  - [ ] 新记忆保存前做候选召回（向量 top5 / FTS5 降级），单次 LLM 批量判定 store/update/merge/skip，失败降级 store
  - [ ] 去重仅在会话结束异步触发，不阻塞对话；单批 ≤10 条
  - [ ] 软衰减：`recallScore = priority × exp(-λ·age) × (1+α·accessCount)`，低于阈值移出注入集但保留在库
  - [ ] 容量上限（默认 10,000 条），超限按「低 priority + 最久未访问」优先回收
  - [ ] MemoryConfigRepository 新增可配置项（去重开关/上限/衰减参数）

#### US-104: 记忆注入预算控制

- 作为用户，我希望记忆注入不挤占过多上下文，以便长会话不被历史记忆淹没。
- 验收标准：
  - [ ] L2 注入条数上限（默认 5）+ 单条字符截断 + 注入失败静默降级
  - [ ] systemPrompt 六层合并中 L2 层受预算约束（纯函数可测）

### 3.2 T2 LLM 操控手机

#### US-201: 无障碍服务 + 手机控制工具集（MVP）

- 作为用户，我希望对 LLM 说"打开微信搜索张三"这类指令时，LLM 能自动读取屏幕、点击/滑动/输入并完成任务。
- 验收标准：
  - [ ] 新增 AccessibilityService（`BIND_ACCESSIBILITY_SERVICE` + canRetrieveWindowContent + canPerformGestures + flagIncludeNotImportantViews + flagReportViewIds），设置页引导开启 + 用途声明
  - [ ] 新增 `PhoneControlLocalToolExecutor`，命名空间 `phone_control__`，至少提供：`get_ui_state` / `tap` / `long_press` / `double_tap` / `swipe` / `type` / `back` / `home` / `launch_app` / `wait`
  - [ ] UI 树序列化为结构化文本（id/文本/contentDescription/bounds/clickable/scrollable/editable/isPassword），节点上限 **80**（较草案 ~800 更保守，防 token 膨胀；敏感拦截对子树文本聚合），采集延迟 ≤500ms
  - [ ] 文本模型（DeepSeek 等纯文本 BYOK）可完成「打开 XX + 搜索 + 返回」高频任务；真机 10 条高频指令成功率 ≥70%
  - [ ] 与现有 SkillExecutor 回路集成：审批门控（MANUAL/AUTO/DISABLED）+ maxRounds + 超时 + 熔断 + 错误回灌

#### US-202: 敏感操作硬拦截与人工接管

- 作为用户，我希望支付/登录/验证码/发送消息等敏感场景不会被 LLM 擅自操作，且能随时人工接管。
- 验收标准：
  - [ ] 敏感域拦截器（代码层，不依赖模型自觉）：支付包名黑名单 + `isPassword()` 节点 + 验证码识别 + 高危动作（发送/删除/转账/拨号/短信）强制 MANUAL
  - [ ] 拦截时触发 `ask_user__ask`（映射 Open-AutoGLM 的 Take_over）+ StopAtTools 中断回路，等待用户确认或接管
  - [ ] UI 文本作为不可信数据源在 system prompt 声明（防 prompt injection）
  - [ ] 每次动作前回读 UI 树校验节点可点击性，防误点

#### US-203: 截图增强（API30+ 无障碍截图）

- 作为用户，我希望在 WebView/Flutter 等 UI 树不足的场景，LLM 能截图辅助理解。
- 验收标准：
  - [ ] `canTakeScreenshot`（API30+）截图，复用 N3 降采样链路，返回 data URL
  - [ ] LLM 判断 UI 树不足时自动触发 `phone_control__screenshot`，走视觉模式（需用户已配置多模态端点）
  - [ ] 未配置视觉端点时提示跳过，不阻塞

#### US-204: 性能档位适配

- 作为用户，我希望低端机不会被该功能拖垮。
- 验收标准：
  - [ ] 按 PerformanceTier：CHAT_ONLY / MINIMAL 档默认禁用手机控制工具集；FULL / STANDARD 可用
  - [ ] 后台前台服务保活开关（对齐 M7 框架）—— **不适用**：无障碍服务由系统绑定保活（BIND_ACCESSIBILITY_SERVICE），无需新增前台服务；M7 亦无前台服务机制，本项按 N/A 豁免（ac-verifier D1 确认）

### 3.3 T3 纯文本模型识图（方案 B）

#### US-301: 云端视觉旁路 Provider

- 作为用户，我希望当主模型不支持图片时，图片能被一个视觉模型自动描述后交给主模型回答。
- 验收标准：
  - [ ] ProviderConfig 新增视觉旁路角色（`isVisionFallback`，不抢占 `isActive`），配置 baseUrl/models/apiKeyRef（复用 Keystore 加密）
  - [ ] 触发链：主端点 400 + `isVisionUnsupportedError` → 旁路调用 `chatCompletion`（非流式）→ 生成描述 → 改写最后一条 user 消息（imageUrl=null + `【图片内容】D` 前缀）→ 重发主端点
  - [ ] 旁路前降采样（≤2048px / ≤1MB / JPEG）；隐私：设置页明示 + 首次触发二次确认
  - [ ] 熔断：同一会话连续 3 次旁路失败自动停用自动旁路，提示手动换模型
  - [ ] `StreamEvent.Error` 新增 `visionUnsupported` 默认值字段（向后兼容，现有穷尽 when 不受影响）

#### US-302: ML Kit OCR 兜底

- 作为用户，我希望截图/票据/文档照片中的文字在没有视觉模型时也能被提取。
- 验收标准：
  - [ ] 引入 `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled，离线，不依赖 GMS/Firebase）
  - [ ] OCR 结果非空才注入（`【图片文字】T` 前缀），空结果落到原提示
  - [ ] APK 体积增量可接受（预计 +8MB，2 ABI）
  - [ ] 中文识别准确率实测 ≥90%（20 张中文截图/票据样本）

---

## 4. 非功能需求

- **性能**：检索 P95 ≤50ms；手机控制采集 ≤500ms；旁路端到端 ≤45s（理想 ≤25s）；多查询/多轮不放大限流（复用 429 退避 + 熔断）
- **安全**：手机控制敏感域硬拦截（代码层）；UI 文本视为不可信输入（prompt injection 防护）；旁路外发图片需用户明示；新增权限（无障碍）用途 prominent disclosure
- **可观测性**：手机控制动作执行记录（复用 SkillExecutionRecord 基建）；旁路/OCR 结构化日志；记忆去重决策分布日志
- **兼容性**：minSdk 26 不变；ML Kit bundled 需 minSdk 23（满足）；无障碍截图仅 API30+（低版本降级）
- **隐私**：图片本地优先（OCR 完全离线）；云端旁路图片外发需设置页明示 + 首次二次确认；用户可一键关闭
- **APK 体积**：T1 ≤1MB；T2 ≈0（AccessibilityService 无体积增量）；T3 ≤8MB（ML Kit bundled）
- **零新增重型依赖约束**：除 ML Kit（T3 必需）外，不新增第三方依赖（FTS5 系统内置、RRF 纯 Kotlin）

---

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| T2 无障碍服务被厂商/系统收紧（Android 17 高级保护模式；厂商 ROM 后台限制） | 中/高 | 自发布渠道影响可控；UI 层显著披露用途；DISABLED 审批档兜底；执行器接口抽象可插拔（Shizuku 后置） |
| T2 通用文本模型对 UI 树规划能力波动（复杂长链路成功率中） | 中/中 | 分阶段：先高频短任务，再长链路；关键路径结果回读校验；可降级视觉模型 |
| T2 安全边界被绕过（UI 注入/误触敏感操作） | 低/高 | 敏感域代码层硬拦截；高危动作强制 MANUAL；UI 文本标注不可信；每步动作可见进度卡片 |
| T1 FTS5 中文分词质量（预分词依赖 Unigram 可能切碎） | 中/中 | 复用「多候选核心词短整词降级重试」（UXR1 教训）；离线小样本 MRR 评估后再全量 |
| T1 批量去重 LLM 成本/限流（BYOK + kimi RPM=3） | 中/中 | 仅会话结束异步触发；失败降级 store；单批 ≤10 条 |
| T1 FTS5 与 ObjectBox 数据一致性（双写漂移） | 低/高 | ObjectBox 为 source of truth，FTS5 为可重建派生索引；启动一致性校验/重建 |
| T3 隐私（旁路外发图片给第三方视觉 Provider） | 高 | 设置页常驻明示 + 首次弹窗二次确认 + 一键关闭；OCR 完全本地 |
| T3 限流放大（每次 400 旁路 = 2 次 LLM 调用） | 中 | 熔断（连续 3 次停用）+ 复用 429 退避 |
| T3 APK +8MB | 中 | bundled 是 F-Droid 刚需，接受；后续可评估 product flavors（Play Store 用 GMS unbundled） |
| T3 视觉 Provider 兼容性（GLM-4V-Flash 不支持 base64） | 中 | 配置 keyHint 标注；仅支持 base64 的端点入模板 |

---

## 6. 里程碑

> D-9 分批执行，每批独立闭环（guardrail + ac-verifier + 模拟器验证）。

| 批次 | 内容 | 风险 | 验收证据 |
|---|---|---|---|
| 批次1 | T1 记忆深度优化（US-101~104） | P2 | guardrail + ac-verifier + 模拟器；全量回归 0 失败 |
| 批次2 | T3 纯文本识图方案 B（US-301~302） | P2 | guardrail + ac-verifier + 模拟器；全量回归 0 失败 |
| 批次3 | T2 LLM 操控手机（US-201~204） | P3 | tech-selection + guardrail + ac-verifier + functional-validation-auditor；全量回归 0 失败 |
| 收尾 | 版本号提升 + 发布（ADR + Release） | P3 | 版本号确认（D-10）后执行 |

---

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| 原子记忆抽取升级 | 单元测试 | extractMemories 含 type/priority/sourceMessageIds；三态保留；失败降级 | US-101 |
| MemoryRecord 字段扩展 | ObjectBox 迁移 + 单测 | 新字段可读写，旧数据兼容 | US-101 |
| 混合检索 | 单测 + PoC MRR 门禁 | FTS5+向量+RRF 命中率 ≥ 纯向量；P95 ≤50ms；APK ≤1MB | US-102 |
| 批量去重 | 集成测试 | 四动作正确；失败降级 store；会话结束异步 | US-103 |
| 软衰减 + 容量回收 | 单元测试 | 低于阈值移出注入集；超限按低分最旧回收 | US-103 |
| 注入预算 | 单测 | 条数上限 5 + 字符截断 + 失败静默 | US-104 |
| 手机控制工具集 | 模拟器 + 集成测试 | 8+ 工具可用；文本模型完成高频任务 | US-201 |
| 敏感拦截 + 接管 | 集成测试 | 支付/密码/验证码/发送 100% 拦截 + AskUser 接管 | US-202 |
| 截图增强 | 模拟器（API30+） | canTakeScreenshot 可用；视觉模式降级不阻塞 | US-203 |
| 性能档位 | 单元测试 | CHAT_ONLY/MINIMAL 禁用；FULL/STANDARD 可用 | US-204 |
| 云端视觉旁路 | 集成测试（mock 400） | 旁路→描述→改写→重发成功；熔断生效 | US-301 |
| ML Kit OCR | 单元测试 + 实测 | 中文准确率 ≥90%；非空才注入；体积 ≤8MB | US-302 |

---

## 8. 待确认事项（决策点）

> 以下为需用户决策的异议/方案选择项。用户确认后主 Agent 按确认范围执行。
> **已确认（2026-08-19）**：
>
> - **D-1**：接受无障碍重建方案——AccessibilityService UI 树喂通用 LLM 为主 + 截图增强为辅，非 ADB 移植 ✅
> - **D-2**：手机操控本版本 **P1+P2+P3 全做**（UI 树 MVP + 截图增强 + 视觉 Agent 模式 AutoGLM-Phone/GLM-4.5V 可配）；P4 Shizuku 仍留后续 ✅
> - **D-3**：敏感拦截清单按建议默认——支付/转账、登录/密码、验证码、发送消息、删除/退出登录、拨号/发送短信 ✅
> - **D-4**：中文输入 P1 用 ACTION_SET_TEXT + 剪贴板粘贴降级，自定义 IME 留后续 ✅
> - **D-5**：纯文本识图**云端旁路 + OCR 都做**（接受 ML Kit +8MB）✅
> - **D-6**：旁路隐私=设置页常驻明示 + 首次触发二次确认 + 一键关闭 ✅
> - **D-7**：记忆优化全部采纳（混合检索 + 批量去重 + 软衰减/容量 + 原子记忆升级 + 注入预算）✅
> - **D-8**：执行批次=批次1 记忆 → 批次2 识图 → 批次3 手机操控，每批独立闭环 ✅
> - **D-9**：目标版本 **v1.0.0**（build.gradle.kts versionName=0.1.0 → 1.0.0）✅

### D-1【任务 2 核心异议 · 必须确认】Open-AutoGLM 是 PC 端 ADB 方案，Prism 无法照搬

- **调研事实**：Open-AutoGLM 是 **PC 端 Python 程序通过 ADB 控制手机** + 专用视觉 GUI 模型（AutoGLM-Phone-9B）理解截图并输出坐标动作。而 Prism 是**运行在手机本地的 App**，无法用 ADB 控制自身所在手机。
- **替代方案**：必须用 Android 本地能力重建——**AccessibilityService（无障碍服务）读取 UI 树 + 执行动作** 为主路径（纯文本模型如 DeepSeek 即可感知，成本最低、原生 App 鲁棒性高）；**无障碍截图**（API30+ canTakeScreenshot，避开 MediaProjection 在 Android 14+ 每次授权限制）作增强。
- **预期效果差异**（需用户接受）：原生 App 高频短任务（打开/搜索/点击/返回）可行且鲁棒；复杂长链路（跨 App 点外卖）成功率中等（SOTA 视觉模型在复杂基准也仅 35-47%，社区实测常见 App 任务 85-95%）。
- **❓ 请确认**：接受该替代架构方案（手机端无障碍重建，非 ADB 移植）？

### D-2【任务 2 范围分级】手机操控做到哪个阶段

- P1 MVP（UI 树 + 文本模型，8+ 工具 + 敏感拦截）→ P2 截图增强 → P3 视觉 Agent 模式（AutoGLM-Phone/GLM-4.5V）→ P4 Shizuku（可选）。
- **建议**：本版本做 **P1 + P2**（文本模型主路径 + 截图增强），P3/P4 留待后续迭代。
- **❓ 请确认**：本版本范围？

### D-3【任务 2 敏感拦截边界】硬拦截动作清单

- **建议默认硬拦截**：支付/转账、登录/密码输入、验证码、发送消息、删除/退出登录、拨号/发送短信。
- **❓ 请确认**：是否按此清单，是否需可配置放行？

### D-4【任务 2 中文输入】P1 输入策略

- **建议**：ACTION_SET_TEXT + 剪贴板粘贴降级（快）；自定义 IME 工程量大，留待后续。
- **❓ 请确认**：接受该策略？

### D-5【任务 3 OCR 体积】是否接受 ML Kit bundled +8MB

- **建议**：分两步——Step1 云端视觉旁路（零体积增量，先做）；Step2 ML Kit OCR 兜底（+8MB，需权衡）。
- **❓ 请确认**：本版本是否同时做 Step1+Step2，还是仅 Step1？

### D-6【任务 3 云端旁路隐私】外发授权模式

- **建议**：设置页常驻明示 + 首次触发二次确认 + 一键关闭。
- **❓ 请确认**：接受该授权模式？

### D-7【任务 1 记忆优化范围】采纳机制清单

- **建议全部采纳**：混合检索（FTS5+RRF）、批量去重、软衰减+容量、原子记忆升级+字段扩展、注入预算。
- **❓ 请确认**：是否全部采纳，或剔除某些（如软衰减改硬 TTL 删除）？

### D-8【执行批次顺序】批次1 记忆 → 批次2 识图 → 批次3 手机操控

- **建议**：按风险递增分批，每批独立闭环。
- **❓ 请确认**：顺序与分批？

### D-9【版本号】目标版本号

- 当前 build.gradle.kts `versionName=0.1.0`（用户口径 1.0）。建议提升为 **v1.0.0**（对齐用户 1.0 口径，功能量级支撑 Major 提升）或 **v1.1.0**。
- **❓ 请确认**：目标版本号？

---

## 9. 执行状态追踪

> 用户确认决策点后更新本表。

| 批次 | 内容 | 状态 | 验收证据 |
|---|---|---|---|
| 批次1 | T1 记忆深度优化 | ✅ 完成 | guardrail + ac-verifier + 模拟器；全量回归 0 失败；[ADR-034](../decisions/ADR-034-v1-memory-deep-optimization.md)；[验收报告](reports/2026-08-19-v1-b1-memory-acceptance.md) |
| 批次2 | T3 纯文本识图方案 B | ✅ 完成 | guardrail + ac-verifier + 模拟器；全量回归 0 失败；[ADR-035](../decisions/ADR-035-v1-vision-plan-b.md)；[验收报告](reports/2026-08-19-v1-b2-vision-acceptance.md) |
| 批次3 | T2 LLM 操控手机 | ✅ 完成 | guardrail + ac-verifier + functional-validation-auditor；全量回归 2268 用例 0 失败；[ADR-036](../decisions/ADR-036-v1-phone-control.md)；[验收报告](reports/2026-08-19-v1-b3-phone-control-acceptance.md)；模拟器验证 |
| 收尾 | 版本号 v1.0.0（versionCode 2）+ ADR/文档 + 通知真机测试 | ✅ 版本号已提升；发布待真机验证 | 模拟器验证通过；待用户真机手动测试后 commit + tag v1.0.0 |
