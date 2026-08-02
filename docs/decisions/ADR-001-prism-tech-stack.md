# ADR-001: Prism 技术栈与架构选型

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted（US-001 M0 脚手架 + US-002 ObjectBox 数据层已通过 guardrail-enforcer 审查 + ac-verifier 验收，基线已确立） |
| 日期 | 2026-08-02 |
| 决策者 | 主 Agent + 用户 |
| 关联文档 | [PRD](../PRD.md) / [可行性调研汇报](../reports/2026-08-02-prism-feasibility-research.md) |
| 上游调研 | [技术选型对比分析](../reports/2026-08-02-prism-tech-selection.md) / [Continuous-learning 考古报告](../reports/2026-08-02-continuous-learning-archaeology.md) |
| 风险等级 | P3 重大（新框架/中间件选型 + 核心架构确立） |

---

## 背景（Context）

Prism 是一款手机端 AI 聊天 Agent 应用，定位为"个人 AI Agent 平台"。用户原始痛点：现有手机 AI 聊天软件缺失 MCP、Skills、个人知识库、记忆系统等能力，导致多轮对话后上下文污染严重、幻觉率高。

经两轮用户澄清 + `tech-selection-researcher` 子 Agent 深度选型 + `code-archaeologist` 子 Agent 对用户已有项目 Continuous-learning 的源码考古，已形成充分的决策依据。本 ADR 整合 8 项用户确认决策 + 6 课题技术选型结论 + Continuous-learning 复用决策，作为后续编码的唯一架构基线。

依 CLAUDE.md 第十七节，引入新框架/中间件、确立核心架构必须写 ADR。

---

## 决策（Decision）

### 3.1 产品定位与商业模式

**决策**：个人开源免费 + 自发布（GitHub Releases / F-Droid / PGY），**不走 Google Play**。

**理由**：规避 Google Play 对无障碍服务类 App 的审核风险；个人项目无后端或极简后端，降低维护成本。

### 3.2 目标平台与最低配置

**决策**：仅 Android，最低 Android 8.0（API 26）+ 3GB RAM（最低）/ 4GB RAM（推荐），分档降级。

**理由**：
- 仅 Android 规避 iOS 沙盒对跨 App 能力的严格限制
- API 26 覆盖 2026 年 >97% 设备，是现代 Android 实际起点
- 纯云端 BYOK 不跑端侧 LLM，峰值 RAM <700MB，4GB 设备可跑全功能
- 降级策略：≥6GB 全功能 / 4-6GB 小批次 RAG / 3-4GB 禁 RAG 仅关键词 / <3GB 仅聊天

### 3.3 AI 算力策略

**决策**：纯云端 BYOK（用户自配 OpenAI/Claude/Ollama 等端点），App 只做客户端。端侧仅跑小嵌入模型（all-MiniLM-L6-v2 ONNX INT8）做 RAG。

**理由**：手机本地跑 7B+ LLM 在中端机不切实际（6GB 内存、1-3 token/s）；BYOK 给用户最大灵活性。

### 3.4 跨 App 能力

**决策**：走轻量路线——Deep Link / URL Scheme + App Intents + Share Sheet + 系统 Picker 组合，**不做无障碍服务全 UI 自动化**。

**理由**：无障碍服务 Google Play 审核极严；轻量组合覆盖"调用 App 功能（发消息/打开页面/分享/选取媒体）"场景，无审核风险。

### 3.5 技术栈选型（6 课题）

| 层 | 决策 | 锁定版本 |
|---|---|---|
| **框架** | 原生 Kotlin + Jetpack Compose | Compose BOM 锁定 |
| **MCP 客户端** | 官方 MCP Kotlin SDK | 0.12.0 |
| **向量库** | ObjectBox Java/Kotlin | 5.4.2（Context7 验证） |
| **嵌入模型** | all-MiniLM-L6-v2 ONNX INT8 量化 | ONNX Runtime full 包（v1.19+ Mobile 包停发，Context7 验证） |
| **跨 App** | Deep Link + App Intents + Share Sheet + Picker | 系统 API |
| **Key 存储** | Android Keystore + DataStore（Tink AEAD 加密）+ 生物识别 | DataStore 1.3.0-alpha07+ |

**核心理由**：
- **Flutter 被否决**：仅 Android 场景 Flutter 跨平台优势无法发挥，且 +15ms 冷启动、54fps 滚动、+12MB APK、+12-28MB 内存是纯负担
- **官方 MCP Kotlin SDK 发现代替 mcp_client Dart**：使原生方案不再需要"自实现"，全原语覆盖
- **ObjectBox 优于 Zvec**：原生方案下 ObjectBox Java/Kotlin 更成熟（HNSW <10ms 百万级，<8MB binary，800K+ 开发者）
- **EncryptedSharedPreferences 已废弃**：2024 年初废弃，改用 DataStore + Tink AEAD 加密

### 3.6 MCP 预设方案

**决策**：形态 A+B 组合，**零后端**。

- **形态 B（内置本地 Server，Kotlin 实现）**：Filesystem / Fetch / Memory / Sequential Thinking / Time / 跨 App 调用，共 6 个，开箱即用零配置
- **形态 A（预设远程 Server 模板，用户填 Key 一键添加）**：GitHub / Notion / Slack / Sentry / Stripe / Asana / Brave Search / Exa / Context7，共 9 个
- 用户也可自行添加任意远程 MCP Server URL（高级用户）

**理由**：让普通用户装完即有 15 个 MCP 能力可用，避免"让用户自建 MCP Server 门槛过高"的异议。

### 3.7 Agent 内核与复用

**决策**：纯 Kotlin 重实现 OpenClaw 设计架构（NullClaw 交叉编译经考古判定不可行，不采用）。

**理由**：
- OpenClaw 是 MIT 许可证，可自由复用设计；是 Kimi Claw 的基础，架构成熟
- OpenClaw 原版 TS 无法直接跑在 Android，需用 Kotlin 重新实现架构（SKILL.md 格式/Agent 路由/沙箱/记忆引擎）
- **NullClaw 交叉编译经 code-archaeologist 考古（TKN-PRISM-ARCHAEOLOGY-002）判定不可行**：Zig 无法链接 Android libc（上游 ziglang/zig#23906）；沙箱机制（Landlock/Firejail/Bubblewrap/Docker）全不适用 Android；守护进程架构与 Android App 生命周期冲突；集成成本 40-60 人天且高风险

**复用清单**（详见 [OpenClaw 考古报告](../reports/2026-08-02-openclaw-archaeology.md)）：
- 直接采用：SKILL.md 格式、6 层加载优先级、智能体允许列表、{baseDir} 变量、渐进式披露、队列模式、vtable 接口模式
- 参考重写：Agent 路由（简化）、门控机制（Android 化）、沙箱策略（Android 权限模型）、记忆引擎（ObjectBox+FTS5，vector cosine 0.7 + BM25 0.3 混合）、ClawHub（简化为本地+GitHub Releases）
- 不可复用：Docker 沙箱、Node.js 运行时、DM Pairing、Pi Agent RPC

**总成本**：41-65 人天（纯 Kotlin 重实现）

### 3.8 Continuous-learning 复用决策

**决策**：复用 Continuous-learning 的**架构设计**，非代码复用。

- **直接复用设计**（零成本）：frontmatter schema、双索引机制、持续进化闭环、重复检测算法、auto-xref 打分、Lint 检查项、17 个 MCP tools 接口契约、RAG prompt、parser 解析规则
- **参考重写实现**（21-31 人天，TS→Kotlin）：frontmatter 解析、重复检测、auto-xref、Lint 引擎、进化闭环、知识图谱、MCP tools 注册
- **独立新建**（29-42 人天）：向量检索（ObjectBox）、嵌入模型（ONNX MiniLM）、Android 文档解析器、Compose GUI、LLM 集成
- **关键差异**：Continuous-learning 检索是 term-overlap + CJK bigram（<200 页不用向量库），Prism 需向量 RAG（完全未实现，需从零新建）；term-overlap 可作降级方案
- **许可证规避**：pymupdf 是 AGPL-3.0，与 Apache 2.0 不兼容，用 Android 原生 PdfRenderer 或 pdfplumber(BSD) 替代

### 3.9 开源协议

**决策**：Apache 2.0。

**理由**：商业友好 + 专利保护 + 与 OpenClaw(MIT)/NullClaw(MIT)/Jetpack Compose 等依赖兼容；未来商业化不阻塞。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **Flutter 跨平台** | 单代码库、mcp_client Dart 包、Zvec Dart SDK | 仅 Android 场景跨平台优势无用；+15ms 冷启动、54fps 滚动、+12MB APK、+12-28MB 内存纯负担；官方 MCP Kotlin SDK 消除其库优势 |
| **mcp_client Dart 包** | 成熟、4 协议版本覆盖、OAuth 2.1 | 依赖 Flutter；官方 MCP Kotlin SDK 0.12.0 是原生更优选择 |
| **Zvec Dart SDK** | 移动端原生设计、混合检索 | 原生方案下 ObjectBox Java/Kotlin 更成熟、社区更大 |
| **EncryptedSharedPreferences** | 历史标准 | **2024 年初已废弃**，不可用 |
| **bge-m3 嵌入模型** | 中文强、多语言 | 568M 参数，移动端过大 |
| **无障碍服务跨 App** | 全 UI 自动化 | Google Play 审核极严；用户已确认走轻量路线 |
| **GPL 协议** | 强 copyleft 保护 | 传染性限制未来商业化与闭源衍生 |
| **复用 OpenClaw 代码** | 零开发 | TS 无法直接跑 Android，需重写 |
| **复用 Continuous-learning 代码** | 已有实现 | 桌面 TS/Python 与移动 Kotlin 不兼容；pymupdf AGPL 风险 |

---

## 后果（Consequences）

### 正面后果

- 技术栈全部原生 Android，性能最优，无跨平台开销
- MCP 生态官方 SDK 背书，协议合规有保障
- ObjectBox 向量库 + ONNX MiniLM 端侧 RAG 在 4GB 设备可用
- 零后端架构，维护成本极低
- Apache 2.0 协议商业友好，未来灵活
- 复用 OpenClaw + Continuous-learning 设计，避免重复造轮子

### 负面后果 / 代价

- 仅 Android，失去 iOS 用户（用户已接受）
- MCP Kotlin SDK 为 Tier 3，功能完整性低于 TS/Python 版，OAuth 2.1 可能需基于 Ktor 自行补齐
- ~~ObjectBox 向量搜索功能**可能需商业许可证**，需向厂商确认~~ → **已解除**：web-access 调研确认向量搜索免费（2026-08-02 US-002 阶段）
- 复用 Continuous-learning 设计需 21-31 人天移植 + 29-42 人天新建（总 50-73 人天）
- 个人开源自发布，无应用商店流量分发

### 需要同步更新的文档或代码

- README.md 文档索引（已创建）
- PRD（本 ADR 后立即起草）
- ARCH（PRD 后起草）
- docs/templates/ 补充 reports/ 子目录与剩余模板（consistency-audit/error-code-registry/performance-baseline/arch）

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| ~~ObjectBox 向量搜索许可证需商业授权~~ | ~~高~~ → **已解除** | web-access 调研确认（2026-08-02，US-002 阶段）：核心 CRUD 与向量搜索均免费，商业付费仅限 Data Sync/Time Series。备选 sqlite-vec 不再需要 |
| MCP Kotlin SDK Tier 3 功能不完整 | 中 | Context7 已验证 Client/StreamableHttpClientTransport/listTools/callTool API 可用；OAuth 2.1 需手动路由 workaround（mcpStreamableHttp 不能嵌套 authenticate{}）；PoC 验证 |
| NullClaw 交叉编译不可行 | 高 | 考古判定不可行（Zig 无法链接 Android libc，上游 ziglang/zig#23906；沙箱全不适用 Android）；已调整为纯 Kotlin 重实现 OpenClaw 设计 |
| AGPL-3.0 依赖意外引入 | 高 | 依赖审查；CI 集成 license 检查；禁用 pymupdf |
| 端侧 RAG 在 3GB 设备 OOM | 中 | 分档降级策略；嵌入模型按需加载/卸载 |
| 国产 App Deep Link 兼容性差 | 中 | 维护兼容性清单；不支持时降级 Share Sheet；Android 11+ 配置 `<queries>` |
| Prism 无后端导致 Skills 市场分发困难 | 低 | 首期 Skills 本地+GitHub Releases 分发；后续按需加 Cloudflare Workers 极简后端 |

---

## P0 核心依赖清单（依 CLAUDE.md 第十八节严格管控）

| P0 依赖 | 版本 | 管控理由 |
|---|---|---|
| Jetpack Compose | BOM 锁定 | UI 框架，升级可能破坏 UI |
| 官方 MCP Kotlin SDK | 0.12.0 | MCP 协议合规核心，0.x API 不稳定 |
| ObjectBox | 5.4.2 | 向量数据持久化，迁移成本高（Context7 验证版本，见下方 Context7 调研验证节；原 6.0.0-beta 为笔误） |
| ONNX Runtime Mobile | 稳定版锁定 | 嵌入模型推理引擎 |
| Android Keystore + DataStore | 系统级 | API Key 安全核心 |

---

## Context7 调研验证（2026-08-02）

依 CLAUDE.md 第二节 2.3，编码前调用 Context7 MCP 获取 4 个 P0 依赖最新文档，验证 API 可用性：

| 依赖 | 验证结论 | 对实现的影响 |
|---|---|---|
| **MCP Kotlin SDK** | Client + StreamableHttpClientTransport + listTools/callTool API 可用；OAuth 2.1 需手动路由（mcpStreamableHttp 不能嵌套 authenticate{}） | OAuth 2.1 实现需 workaround |
| **Jetpack Compose** | NavHost + rememberNavController + hiltViewModel + StateFlow 标准模式 | 聊天 UI 用 ConversationViewModel + StateFlow |
| **ObjectBox** | 版本 **5.4.2**（非 6.0.0-beta）；@Entity/@Id + boxFor + put/get/remove；AGP 8.13- 用 kotlin.android + kotlin.kapt | 版本号已更正 |
| **ONNX Runtime** | v1.19+ Mobile 包停发，用 full 包；NNAPI via addNnapi(NNAPIFlags.USE_FP16) | 改用 full 包；NNAPI 加速可用 |

关键 API 示例（MCP Client）：

```kotlin
val httpClient = HttpClient { install(SSE) }
val client = Client(clientInfo = Implementation(name = "prism", version = "1.0.0"))
val transport = StreamableHttpClientTransport(client = httpClient, url = url)
client.connect(transport)
val tools = client.listTools().tools
val result = client.callTool(name = "search", arguments = mapOf("q" to "..."))
```

## 环境适配修订（2026-08-02 M0 实施阶段）

> 本节为 ADR-001 的修订补充，记录 M0 脚手架实施阶段因开发环境限制对版本配置的调整。原决策（3.5 节）的架构选型不变，仅版本号适配。

### 修订原因

| 环境限制 | 影响 | 验证方法 |
|---|---|---|
| `dl.google.com` 完全不可达（连接超时/SSL 中断） | Google Maven 和 Android SDK Repository 均无法访问 | `Invoke-WebRequest` 测试返回超时/连接关闭 |
| 仅 `android-34` 平台完整安装；`android-36` 安装中断（仅 `.installer` 空目录）；无 `android-35` | compileSdk 只能用 34 | `Get-ChildItem` 检查 `platforms/` 目录 |
| `sdkmanager` 未安装（cmdline-tools 缺失） | 无法通过命令行安装 SDK 平台 | `Test-Path` 检查 cmdline-tools 目录 |
| AGP 8.13.0 默认要求 Build Tools 35.0.0；已安装 34.0.0 和 36.1.0 | 需显式指定 `buildToolsVersion = "36.1.0"` | `assembleDebug` 构建验证 |
| AndroidX core-ktx 1.15.0+ 要求 compileSdk 35+ | 需降级到 compileSdk 34 兼容版本 | AGP 编译期依赖检查报错 |

### 版本调整清单

| 依赖 | 原版本（3.5 节） | 修订版本 | 修订理由 |
|---|---|---|---|
| compileSdk | 35 | **34** | 仅 android-34 平台完整可用 |
| targetSdk | 35 | **34** | 同上（M0 脚手架阶段；后续升级时同步） |
| buildToolsVersion | 未指定（AGP 默认） | **36.1.0**（显式） | AGP 8.13 默认 35.0.0 未安装；36.1.0 已安装且 ≥ 最低要求 |
| Compose BOM | 2024.12.01 | **2024.06.00** | 2024.09+ 的 Compose 库要求 compileSdk 35+ |
| core-ktx | 1.15.0 | **1.13.1** | 1.14.0+ 要求 compileSdk 35+ |
| lifecycle-runtime-ktx | 2.8.7 | **2.8.3** | 2.8.5+ 可能要求 compileSdk 35+ |
| activity-compose | 1.9.3 | **1.9.0** | 确保 compileSdk 34 兼容 |

### 未变更项

| 项目 | 版本 | 说明 |
|---|---|---|
| AGP | 8.13.0 | 不变，支持 compileSdk 34 |
| Kotlin | 2.1.0 | 不变，Compose Compiler 插件追踪 Kotlin 版本 |
| minSdk | 26 | 不变，ADR-001 确认 |
| Gradle | 8.13 | 不变，满足 AGP 8.13 最低要求 |

### 仓库镜像配置

`settings.gradle.kts` 新增阿里云镜像作为首选仓库，官方源作 fallback：

- `pluginManagement`：阿里云 gradle-plugin + google 镜像 → 官方 google + mavenCentral + gradlePluginPortal
- `dependencyResolutionManagement`：阿里云 google + public 镜像 → 官方 google + mavenCentral

CI/CD（GitHub Actions Linux）自动回退到官方源，不影响国际贡献者。

### 升级计划

当以下任一条件满足时，应将 compileSdk 升级至 35+ 并恢复原版本：

1. 开发环境安装 `cmdline-tools` + 通过 sdkmanager 安装 android-35/36 平台
2. 用户通过 Android Studio SDK Manager 安装 android-35/36 平台
3. CI/CD 环境使用官方 SDK 镜像（Linux 已预装 android-35+）

升级时同步恢复：Compose BOM 2024.12.01 / core-ktx 1.15.0 / lifecycle-runtime-ktx 2.8.7 / activity-compose 1.9.3。

### 构建验证

```
./gradlew assembleDebug
BUILD SUCCESSFUL in 1m 52s
```

APK 产物：`app/build/outputs/apk/debug/app-debug.apk`（8.65 MB，含 classes.dex + AndroidManifest.xml + resources.arsc）。

---

## 参考

- [技术选型对比分析报告](../reports/2026-08-02-prism-tech-selection.md)（tech-selection-researcher 子 Agent）
- [Continuous-learning 考古报告](../reports/2026-08-02-continuous-learning-archaeology.md)（code-archaeologist 子 Agent）
- [Prism 可行性调研汇报](../reports/2026-08-02-prism-feasibility-research.md)
- [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
- [ObjectBox](https://objectbox.io/)
- [OpenClaw](https://allclaw.org/entry/kimi-claw)
- [NullClaw](https://nullclaw.org/)
