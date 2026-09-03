# Prism · 产品需求文档（PRD）

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-08-02 |
| 作者 | 主 Agent + 用户 |
| 关联文档 | [ADR-001](decisions/ADR-001-prism-tech-stack.md) / [可行性调研汇报](reports/2026-08-02-prism-feasibility-research.md) / [技术选型对比](reports/2026-08-02-prism-tech-selection.md) / [Continuous-learning 考古](reports/2026-08-02-continuous-learning-archaeology.md) |
| 风险等级 | P3 重大 |

## 1. 背景

现有手机端 AI 聊天软件普遍缺失 MCP、Skills、个人知识库、记忆系统等 Agent 能力，导致：

- 多轮对话后上下文污染严重
- 幻觉率高（无知识库约束）
- 无法扩展工具能力（无 MCP/Skills）
- 无法跨 App 调用手机能力
- 用户被锁定在单一模型提供商

Prism 定位为**手机端个人 AI Agent 平台**，通过 MCP + Skills + 端侧 RAG 知识库 + 三层记忆 + BYOK 多端点 + 轻量跨 App 调用六位一体能力，解决上述痛点。

## 2. 目标与非目标

### 目标

- 提供手机端原生 AI 聊天体验，支持用户自配任意 OpenAI 兼容/Anthropic/Ollama 端点
- 内置 MCP Client，预设 15 个常用 MCP 能力（6 本地 + 9 远程模板），零后端开箱即用
- 端侧个人知识库（RAG），文档摄入→切片→嵌入→向量检索→注入 prompt，强制引用来源防幻觉
- Skills 系统（复用 OpenClaw SKILL.md 设计），支持本地+远程 Skill 加载
- 三层记忆系统（会话内压缩 + 跨会话向量 + 用户画像），解决上下文污染
- 轻量跨 App 调用（Deep Link/App Intents/Share Sheet/Picker），AI 可调用其他 App 功能
- 复用 Continuous-learning 的知识库架构设计（frontmatter/双索引/进化闭环/Lint）

### 非目标（明确不做的事）

- ❌ 不做 iOS 版本（规避沙盒限制）
- ❌ 不做端侧 LLM 推理（纯云端 BYOK）
- ❌ 不做无障碍服务全 UI 自动化（审核风险）
- ❌ 不做 Google Play 上架（自发布）
- ❌ 不做账号体系/数据同步/订阅计费后端（零后端）
- ❌ 不做实时多用户协作

## 3. 用户故事与验收标准

> **编号体系说明**：本文档（PRD.md）的 US 编号为**产品功能模块**层级，与 [`prd.json`](prd.json)（Ralph 格式）中的**迭代开发任务**编号相互独立、不一一对应。例如 PRD 的 US-001「BYOK 多端点聊天」在 prd.json 中拆解为 US-004（Provider 数据模型）/ US-006（流式请求）/ US-007（Provider 切换）等多个开发故事。开发进度以 prd.json 为准，本文档提供产品级验收全貌。截至 2026-08-06，prd.json 的 US-001~US-007 已完成并通过 guardrail/ac-verifier 闭环。

### US-001: BYOK 多端点聊天

- 作为用户，我希望在 App 内配置多个 AI 服务端点（OpenAI/Claude/Ollama 等），并随时切换，以便灵活使用不同模型
- 验收标准：
  - [ ] 支持配置至少 5 种 Provider：OpenAI 兼容、Anthropic 原生、Ollama、Moonshot、OpenRouter
  - [ ] 每个 Provider 配置含：名称、baseURL、API Key、模型列表、自定义 headers
  - [ ] API Key 通过 Android Keystore + DataStore 加密存储，不落明文
  - [ ] 会话中可一键切换 Provider，切换后保留对话历史
  - [ ] 支持流式响应（SSE），首字延迟 <1s（取决于端点）
  - [ ] 端点不可达时显示明确错误，不崩溃

### US-002: MCP Client 与预设 Server

- 作为用户，我希望 App 内置常用 MCP 能力并支持添加远程 MCP Server，以便 AI 能调用工具（搜索、抓取网页、读文件等）
- 验收标准：
  - [ ] 基于 MCP Kotlin SDK 0.12.0 实现 MCP Client
  - [ ] 支持 SSE 与 Streamable HTTP 两种传输
  - [ ] 内置 6 个本地 Server：Filesystem/Fetch/Memory/SequentialThinking/Time/跨App调用，零配置可用
  - [ ] 预设 9 个远程 Server 模板：GitHub/Notion/Slack/Sentry/Stripe/Asana/Brave/Exa/Context7，用户填 Key 一键添加
  - [ ] 支持用户自定义添加任意远程 MCP Server URL
  - [ ] AI 调用 MCP Tool 前需用户确认（可配置自动批准白名单）
  - [ ] MCP 连接状态可观测（连接中/已连接/错误）

### US-003: 个人知识库（端侧 RAG）

- 作为用户，我希望把文档（PDF/DOCX/XLSX/MD/TXT）导入 App 形成知识库，AI 回答时能检索并引用来源，以便降低幻觉
- 验收标准：
  - [ ] 支持导入格式：PDF/DOCX/XLSX/MD/TXT（PDF 用 PDFBox 3.0.8 Apache 2.0，详见 [ADR-007](docs/decisions/ADR-007-m3-rag-tech-stack.md) 5.3；不用 pymupdf）
  - [ ] 文档解析→切片（可配置 chunk size/overlap）→嵌入（all-MiniLM-L6-v2 ONNX INT8）→存入 ObjectBox 向量库
  - [ ] 对话时自动检索 top-k 相关片段（可配置 k，默认 5）注入 prompt
  - [ ] AI 回答必须标注引用来源（文件名+片段位置），无引用时主动说明
  - [ ] 知识库支持分库管理（如"工作"/"学习"），对话时可指定库或全库检索
  - [ ] 嵌入模型按需加载，闲置 5 分钟后卸载释放内存
  - [ ] 4GB RAM 设备可正常使用 RAG（小批次模式：top-k=3）

### US-004: Skills 系统

- 作为用户，我希望安装/启用 Skill（如"翻译""代码生成""会议纪要"），AI 能根据任务自动调用合适 Skill
- 验收标准：
  - [ ] Skill 格式遵循 SKILL.md 规范（frontmatter + 正文 + 资源目录）
  - [ ] 支持本地 Skill（用户自建）与远程 Skill（URL 下载）
  - [ ] Skill manifest 注册，启动时扫描加载
  - [ ] AI 根据 Skill 描述自动选择调用（用户可手动指定）
  - [ ] Skill 执行结果可观测（执行了哪个 Skill、耗时、输出）
  - [ ] Skill 失败不影响主对话

### US-005: 三层记忆系统

- 作为用户，我希望 App 记住我的偏好和跨会话历史，以便越用越懂我，且不让旧上下文污染新对话
- 验收标准：
  - [ ] L1 会话内：滑动窗口 + 每 N 轮摘要压缩（可配置 N，默认 10），保留关键信息
  - [ ] L2 跨会话：对话历史向量化存入 ObjectBox，新会话按当前话题 top-k 检索（默认 k=3）
  - [ ] L3 用户画像：显式偏好（用户设定）+ 隐式偏好（从对话抽取），结构化存储
  - [ ] 新会话不自动加载旧会话全文（防污染），仅加载检索结果 + 画像
  - [ ] 用户可查看/编辑/删除记忆
  - [ ] 记忆可一键清除

### US-006: 跨 App 调用

- 作为用户，我希望 AI 能调用其他 App 的功能（如打开微信聊天、发导航、分享内容），以便从对话直接行动
- 验收标准：
  - [ ] 支持 Deep Link/URL Scheme 跳转：微信/支付宝/淘宝/抖音/QQ/微博/地图等主流 App
  - [ ] 支持 Share Sheet（ACTION_SEND）分享文本/链接/文件
  - [ ] 支持系统 Picker（Photo Picker/Document Picker）选取媒体文档
  - [ ] Android 11+ 配置 `<queries>` 声明目标 App 包可见性
  - [ ] AI 自动触发跨 App 调用前需用户确认（防误操作）
  - [ ] 目标 App 未安装时降级提示（引导下载或显示操作路径）
  - [ ] 维护 Deep Link 兼容性清单（哪些 App 支持哪些 scheme）

### US-007: 设备适配与降级

- 作为低端机用户，我希望 App 在我的设备上也能基本可用
- 验收标准：
  - [ ] 启动时检测设备 RAM，自动选择功能档位
  - [ ] ≥6GB：全功能（RAG 标准批次 + 嵌入常驻）
  - [ ] 4-6GB：RAG 小批次 + 嵌入按需加载
  - [ ] 3-4GB：禁用 RAG，仅关键词检索
  - [ ] <3GB：仅聊天 + BYOK
  - [ ] 用户可在设置中手动覆盖档位
  - [ ] 最低支持 Android 8.0（API 26）

## 4. 非功能需求

### 性能

- 冷启动 <2s（中端机 8GB RAM）
- 首字响应延迟 <1s（取决于云端端点）
- 端侧嵌入编码：中端机 <10ms/句（all-MiniLM-L6-v2 ONNX INT8）
- 向量检索：top-5 在 <50ms 内返回（ObjectBox，<10万片段）
- 内存峰值：4GB 设备 <1.2GB，6GB 设备 <1.8GB
- 性能基线存于 `docs/reports/perf/`（参考 `docs/templates/performance-baseline-template.md`）

### 安全

- API Key 经 Android Keystore + DataStore（Tink AEAD）加密存储，进 TEE/StrongBox
- 生物识别二次解锁（可选，用户启用）
- MCP Server 鉴权信息独立加密存储
- 不在日志中输出 API Key/Token（依 CLAUDE.md 第十九节日志安全）
- 用户数据不上传云端（零后端，纯本地）
- 依赖审查禁止引入 AGPL-3.0 等不兼容许可证

### 可观测性

- 结构化日志（JSON），含 timestamp/level/request_id/message
- 关键指标：MCP 调用延迟/成功率、RAG 检索延迟、嵌入编码延迟、内存占用
- 崩溃日志本地记录（不自动上传，用户可手动导出）

### 兼容性

- 最低 Android 8.0（API 26）
- 支持 arm64-v8a（主）/ armeabi-v7a（降级）
- 适配 Android 12+ 后台限制、Android 14+ 前台服务要求

### 隐私

- 所有用户数据（知识库/记忆/对话/API Key）纯本地存储
- 不收集任何遥测数据
- 用户协议明确声明数据不出设备（除用户主动调用云端 AI 端点）

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| ObjectBox 向量搜索许可证 | 高 | 编码前向厂商确认；备选 sqlite-vec |
| MCP Kotlin SDK Tier 3 | 中 | PoC 验证；OAuth 2.1 用 Ktor 补齐 |
| NullClaw 交叉编译 Android | 中 | PoC 验证；失败则纯 Kotlin 重实现 |
| AGPL-3.0 依赖误引入 | 高 | 依赖审查 + CI license 检查；禁 pymupdf |
| 国产 App Deep Link 兼容性 | 中 | 兼容性清单 + 降级策略 |
| Continuous-learning 移植成本 50-73 人天 | 中 | 分里程碑渐进交付 |
| 端侧 RAG 低端机 OOM | 中 | 分档降级 + 嵌入按需加载 |

## 6. 里程碑

| 里程碑 | 内容 | 验收标准 | 风险等级 |
|---|---|---|---|
| **M0 脚手架** | 项目骨架 + Gradle + Compose + CI + 模板补全 | App 可编译运行空白界面；CI 通过 | P1 |
| **M1 BYOK 聊天核心** | US-001 + 基础聊天 UI | 多 Provider 切换、流式响应、Key 加密存储 | P2 |
| **M2 MCP Client** | US-002 | 6 本地 + 9 远程模板 MCP Server 可用 | P2 |
| **M3 个人知识库 RAG** | US-003 | 文档导入→检索→引用来源全链路 | P3 |
| **M4 Skills 系统** | US-004 | SKILL.md 加载/调用/自动选择 | P2 |
| **M5 记忆系统** | US-005 | 三层记忆 + 跨会话检索 + 防污染 | P2 |
| **M6 跨 App 调用** | US-006 | Deep Link/Share/Picker 组合 + 用户确认 | P2 |
| **M7 设备适配** | US-007 | 分档降级 + 低端机验证 | P1 |
| **M8 集成与发布** | 全功能集成 + 自发布 | 通过 ac-verifier + functional-validation-auditor；GitHub Releases/F-Droid/PGY 上架 | P3 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| BYOK 多 Provider | 单元测试 + 手工 E2E | 5 Provider 可切换、流式、Key 加密 | US-001 |
| MCP 15 预设 Server | 集成测试 | 6 本地 + 9 远程模板可加载调用 | US-002 |
| RAG 全链路 | E2E + 性能基准 | 导入→检索→引用，<50ms 检索 | US-003 |
| 防幻觉引用 | 安全测试 | 无知识库时主动说明，有库时标注来源 | US-003 |
| Skills 加载调用 | 集成测试 | SKILL.md 解析、自动选择、失败隔离 | US-004 |
| 记忆三层 | 集成测试 + 回归 | L1 压缩/L2 检索/L3 画像，新会话不污染 | US-005 |
| 跨 App 跳转 | E2E（Playwright 不适用，手工+Espresso） | 主流 App 跳转成功率 >80% | US-006 |
| 设备分档降级 | 性能测试 | 3GB 设备可运行（仅聊天），4GB 可 RAG | US-007 |
| 内存峰值 | 性能基准 | 4GB 设备 <1.2GB，6GB <1.8GB | US-007 |
| 无 AGPL 依赖 | license 扫描（CI） | 0 个 AGPL 依赖 | 全局 |
| 无 API Key 泄漏 | 安全扫描 | 日志/错误无 Key | 全局 |

## 8. 待确认事项

- [ ] ObjectBox 向量搜索许可证条款（编码前向厂商确认）
- [ ] NullClaw 是否能交叉编译到 Android arm64（需 PoC）
- [ ] MCP Kotlin SDK 0.12.0 OAuth 2.1 完整度（需 PoC）
- [ ] Skills 市场首期分发方式（本地 + GitHub Releases？）
- [ ] Prism 的 LICENSE 文件需创建（Apache 2.0 全文）
