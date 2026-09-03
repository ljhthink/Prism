# Prism 项目可行性调研与汇报

| 元信息 | 内容 |
|---|---|
| 报告类型 | 可行性调研报告（Feasibility Research） |
| 生成日期 | 2026-08-02 |
| 作者 | 主 Agent（GLM-5.2） |
| 调研方法 | 联网搜索（WebSearch / web-access）+ sequential-thinking 推理 + tech-selection-researcher 子 Agent 深度选型 + 用户澄清问答 |
| 任务令牌 | TKN-PRISM-RESEARCH-001 |
| 状态 | 定稿（已整合 tech-selection-researcher 选型结论） |

---

## 0. 执行摘要

Prism 是一款**仅 Android 端**的 AI 聊天 Agent 应用，定位为"个人 AI Agent 平台"而非纯聊天客户端。核心差异化在于 **MCP Client + Skills 系统 + 端侧个人知识库（RAG）+ 三层记忆系统 + BYOK 多 Provider 端点 + 轻量跨 App 调用** 六位一体。

经联网调研与推理，**项目整体技术可行**，关键模块均有成熟开源方案可复用：

- **MCP 移动端客户端**已成熟（`mcp_client` Dart 包 v2.1.1 覆盖 4 个协议版本，支持 SSE/Streamable HTTP/OAuth 2.1）
- **端侧 RAG** 已有移动端向量库（Zvec 0.4.0 Dart SDK、ObjectBox、MobileRAG 论文方案）
- **BYOK 多 Provider** 已是红海，适配器模式有大量开源参考（SpeakGPT、Bring Your Own AI、EchoFlow、LibreChat）
- **Skills 系统**可复用 OpenClaw 的 ClawHub + `SKILL.md` 架构
- **跨 App 调用**走 Deep Link / App Intents / Share Sheet / 系统 Picker 轻量路线，规避无障碍服务审核风险

**重大参考发现**：月之暗面的 **Kimi Claw** 基于 **OpenClaw 开源框架**构建，已上线 Android 客户端，架构思路与 Prism 高度相似——OpenClaw 是 Prism 可直接复用或深度参考的开源基础。同时 **NullClaw**（Zig 实现，678KB 单二进制，1MB RAM，原生 MCP + 22 模型 provider + 混合向量记忆）对移动端极友好，值得评估。

**4 点不切实际之处已对用户提出异议**（详见第 5 节），用户已确认调整方向。

**风险等级**：整体 **P3 重大**（新框架/中间件选型 + 核心规则建立），需走完整 tech-selection-researcher → ADR → guardrail-enforcer → ac-verifier 闭环。

---

## 1. 用户原始需求复盘

用户原始构想（逐字保留语义）：

> 开发一款手机端 AI 聊天软件，命名为 Prism。除去 MCP、Skills、个人知识库功能外，还可以自行配置 AI 请求地址；记忆系统；访问其他手机软件的功能。动机：现有手机 AI 聊天软件缺失这些功能导致输出质量低、多轮对话后上下文污染严重、幻觉率高。

**需求拆解（6 大功能模块）**：

| # | 模块 | 用户原意 | 模糊点 |
|---|---|---|---|
| M1 | MCP 支持 | 接入 Model Context Protocol 生态 | Server 部署位置？Client only？ |
| M2 | Skills 系统 | 类似 Claude Code 的 Skills 可扩展能力 | 格式？市场？本地+远程？ |
| M3 | 个人知识库 | 用户私有知识 RAG | 端侧 vs 云端？数据量？ |
| M4 | 可配置 AI 请求地址 | BYOK，自配 endpoint | OpenAI 兼容？多 Provider？ |
| M5 | 记忆系统 | 解决上下文污染 | 短期/长期/向量？ |
| M6 | 访问其他手机软件 | 跨 App 能力 | 读取数据？调用功能？UI 自动化？ |

**用户原始痛点**：上下文污染 + 幻觉率高 → 记忆系统与知识库是质量救星，非可选功能。

---

## 2. 已确认的关键决策（用户澄清结果）

经 AskUserQuestion 4 问，用户已确认：

| 决策项 | 用户选择 | 对架构的影响 |
|---|---|---|
| **目标平台** | 仅 Android（API 26+，Android 8.0+） | 规避 iOS 沙盒限制；可用 Kotlin/Compose 或 Flutter |
| **AI 算力** | 纯云端 BYOK | 不集成 llama.cpp/MLC LLM；嵌入模型用端侧小模型（MiniLM ~80MB）做 RAG |
| **商业模式** | 个人开源免费 + 自发布 | 无后端或极简后端（仅 MCP/Skills 市场）；走 GitHub Releases / F-Droid / PGY，不走 Google Play |
| **跨 App 能力** | 调用 App 功能（发消息/打开页面） | 走 Deep Link / App Intents / URL Scheme / Share Sheet 轻量路线，**不做无障碍服务全 UI 自动化** |

**附加用户提示**：参考 Kimi Claw（已调研，见第 4.7 节）。

### 2.1 第二轮决策（2026-08-02 第二次澄清）

经后续问答，用户进一步确认以下 4 项决策：

| 决策项 | 最终选择 | 对架构的影响 |
|---|---|---|
| **MCP 预设方案** | 形态 A+B 组合，**零后端** | 内置 6 个本地 Server（Filesystem/Fetch/Memory/SequentialThinking/Time/跨App）+ 预设 9 个远程模板（GitHub/Notion/Slack/Sentry/Stripe/Asana/Brave/Exa/Context7）；无需维护任何后端服务器 |
| **Agent 内核** | 复用 OpenClaw 设计架构（Kotlin 重实现）+ 评估 NullClaw 交叉编译 | OpenClaw 是 MIT 许可证，可自由复用设计；TS 无法直接跑在 Android，需用 Kotlin 重新实现架构；同时评估 NullClaw（Zig 678KB）交叉编译到 Android arm64 的可行性 |
| **开源协议** | **Apache 2.0** | 商业友好 + 专利保护 + 与 OpenClaw(MIT)/NullClaw(MIT)/Jetpack Compose 等依赖兼容；未来商业化不阻塞 |
| **最低设备配置** | Android 8.0 (API 26) + 3GB RAM（最低）/ 4GB（推荐），分档降级 | 因纯云端 BYOK 不跑端侧 LLM，峰值 RAM <700MB，4GB 设备可跑全功能；降级策略：>=6GB 全功能 / 4-6GB 小批次 / 3-4GB 禁 RAG / <3GB 仅聊天 |

**待评估项**：用户提示已有相关项目 `D:\s0611\code\Continuous-learning`（个人知识库相关），已启动 `code-archaeologist` 子 Agent 考古评估可复用性（任务令牌 TKN-PRISM-ARCHAEOLOGY-001），结果将影响个人知识库模块实现决策。

---

## 3. 调研方法与流程

依 CLAUDE.md 第一节规划调度 + 第二节联网调研 + 第四节 sequential-thinking：

1. **万能激励引擎** skill 调用——发散多角度路径
2. **5 路并行 WebSearch**——MCP 移动端 / Skills 系统 / 端侧 RAG / BYOK 开源方案 / 跨 App 能力
3. **sequential-thinking MCP 5 步推理**——模块可行性 / 模糊点与不切实际项 / 架构选型 / 风险与差异化 / 提问优先级
4. **AskUserQuestion 4 问**——锁定平台/算力/商业模式/跨App 范围
5. **Kimi Claw 专项搜索**——核实用户提到的参考产品
6. **tech-selection-researcher 子 Agent**（后台运行）——6 课题深度选型：框架 / MCP 客户端 / 向量库 / 嵌入模型 / 跨 App 方案 / Key 存储

---

## 4. 联网调研结论

### 4.1 MCP 移动端支持（模块 M1）

**核心发现**：移动端作 MCP **Client** 连接远程 MCP **Server** 是务实方案；本地 stdio Server 在手机上不可行（无法 spawn 子进程）。

| 方案 | 来源 | 评估 |
|---|---|---|
| **`mcp_client` Dart 包 v2.1.1** | [pub.dev](https://pub.dev/packages/mcp_client) | ✅ 推荐。Flutter 跨平台，支持 SSE/Streamable HTTP/stdio（native only），OAuth 2.1，覆盖协议版本 2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25，含 Tool/Resource/Prompt/Sampling/Elicitation/Roots 全原语，Deferred Tool Loading（省 60-80% token） |
| **mcp-mobile-interaction** | [npm](https://www.npmjs.com/package/mcp-mobile-interaction) | ⚠️ 用 adb/idb 控制 Android/iOS 设备的 MCP **Server**，定位测试自动化，非手机 App 内部能力 |
| **mobile-mcp** | [gitcode 镜像](https://gitcode.com/gh_mirrors/mo/mobile-mcp) | ⚠️ 跨平台移动自动化 MCP Server，用无障碍服务/视觉识别控制设备，定位自动化非客户端 |
| **Rork × MCP 指南** | [rorklab.net](https://rorklab.net/en/articles/rork-ai/rork-mcp-model-context-protocol-mobile-app-guide) | ✅ 参考。React Native + MCP 实战，MCP Server 部署在 Cloudflare Workers，移动端通过 SSE/HTTP 连接 |
| **MCP in Browser & Mobile** | [mcpserverspot.com](https://www.mcpserverspot.com/learn/integrations/mcp-browser-mobile) | ✅ 参考。明确指出浏览器/移动端不能用 stdio，必须用 SSE/Streamable HTTP/WebSocket，需 MCP Gateway 处理鉴权/CORS/限流 |

**结论**：Prism 作 MCP Client，仅支持用户配置远程 MCP Server URL（SSE/Streamable HTTP），内置官方推荐 Server 市场 + 几个托管兜底 Server。

### 4.2 Skills 系统（模块 M2）

| 方案 | 来源 | 评估 |
|---|---|---|
| **OpenClaw ClawHub + `SKILL.md`** | [pyshine.com OpenClaw 评测](https://pyshine.com/OpenClaw-Personal-AI-Assistant/) | ✅ 强推荐参考。开源（359k+ stars），ClawHub 社区市场 + Workspace 自定义，SKILL.md 定义，可安装/管理/分享，跨 20+ 消息平台，多 Agent 路由 + Docker 沙箱 |
| **maid 移动端 AI 框架** | [CSDN 评测](https://blog.csdn.net/weixin_27230891/article/details/160672257) | ✅ 参考。插件化架构，"大脑+手脚"分离，标准化接口接入功能插件 |
| **Anthropic Skills 规范** | 本环境 CLAUDE.md 即采用 | ✅ 标准。`SKILL.md` + frontmatter + 资源目录，按需加载 |
| **OpenAI Plugin architecture** | [developers.openai.com](https://developers.openai.com/plugins/concepts/plugins) | ✅ 概念参考。Skills 描述何时用工作流，可打包成 Plugin；MCP Server 用于连接外部系统 |

**结论**：复用 OpenClaw 的 `SKILL.md` 格式 + ClawHub 市场模式，本地+远程仓库，manifest 注册，按需加载。

### 4.3 个人知识库 / 端侧 RAG（模块 M3）

**核心约束**：手机 RAM 4-12GB，模型 + 向量库 + 系统 App 抢内存。

| 方案 | 来源 | 评估 |
|---|---|---|
| **MobileRAG 论文** | [arxiv 2507.01079](https://arxiv.org/pdf/2507.01079) | ✅ 理论基础。EcoVector 分区部分加载索引 + SCR 内容过滤，延迟/内存/功耗显著优于 IVF/HNSW，支持离线 |
| **Zvec 0.4.0** | [zvec.org](https://zvec.org/en/blog/2026-06-22-zvec-mobile/) | ✅ 推荐。轻量嵌入式向量库，Dart/Flutter SDK，支持 Android arm64-v8a + iOS arm64，语义向量+标量过滤+FTS+混合召回+融合排序，PocketSearch 产品原型验证 |
| **CSDN 移动端部署指南** | [CSDN](https://blog.csdn.net/renhongxia1/article/details/156568003) | ✅ 实操参考。llama.cpp + GGUF + FAISS-mobile/HNSWLib/SQLite+向量扩展 + MiniLM 嵌入 |
| **EMSOFT 2024 海报** | [hokeun.github.io](https://hokeun.github.io/posters/EMSOFT_2024_Poster.pdf) | ✅ 前沿参考。混合端侧 RAG = RAG + LoRA 微调，KG + VD，MLC LLM 在 Android 跑 Llama2 7B/Gemma 2B |
| **ZettelMancer** | [gist.github.com](https://gist.github.com/danyshs/5477a1468e8810b6e703ee03f20400f2) | ✅ 防幻觉参考。强制引用源（文件名+行号+原文），Phi-3-mini + pgvector，Termux+SSH 远程访问，临时状态保护隐私 |

**结论**：文档摄入 → 切片 → 端侧嵌入（MiniLM/BGE 量化 ONNX）→ 向量库（ObjectBox）→ top-k 检索 → 注入 prompt。需降级策略应对低端机。

#### 4.3.1 Continuous-learning 项目复用评估（code-archaeologist 考古结论）

用户已有项目 `D:\s0611\code\Continuous-learning`（Tauri+Node+Python 桌面端个人知识库），经 `code-archaeologist` 子 Agent 考古（任务令牌 TKN-PRISM-ARCHAEOLOGY-001，报告 [2026-08-02-continuous-learning-archaeology.md](2026-08-02-continuous-learning-archaeology.md)），结论如下：

**核心结论**：Continuous-learning 价值在**架构设计复用**，非代码复用。技术栈完全不同（桌面 TS/Python vs 移动 Kotlin），但设计经验可直接指导 Prism 实现。

**关键差异**：Continuous-learning 检索是 term-overlap + CJK bigram（小规模 <200 页不用向量库），Prism 需要的端侧 RAG（ObjectBox + ONNX MiniLM）**完全未实现，需从零新建**。两者互补——term-overlap 可作向量检索的降级方案。

**直接复用的设计**（零成本）：

| 设计项 | 价值 |
|---|---|
| frontmatter schema（title/domain/type/status/date + tags/use_count/quality_score/related） | 知识库页面元数据结构 |
| 双索引机制（index.md 内容导向 + log.md 时间导向） | 索引设计思想可叠加在 ObjectBox 之上 |
| 持续进化闭环（inbox→两 tier 门禁→promote→老化） | 经验沉淀功能可直接采用 |
| 重复检测（Levenshtein 标题>0.9 或 Sorensen-Dice 内容>0.7） | 算法+阈值校准可参考 |
| auto-xref 复合打分（同域+4、共享tag+2、标题提及+3） | 交叉引用功能可直接采用 |
| Lint 6 项检查（frontmatter/contradictions/orphans/stale/missing_xref/missing_concept） | 健康检查可参考 |
| 17 个 MCP tools 接口契约 | 用 MCP Kotlin SDK 重写时参考 |
| RAG_SYSTEM_PROMPT（约束 LLM 基于资料回答+引用源） | RAG 对话直接采用 |
| parser 解析规则（PDF/DOCX/XLSX→markdown） | 与语言无关，可参考 |

**参考重写的实现**（21-31 人天，TS→Kotlin）：frontmatter 解析、重复检测算法、auto-xref、Lint 引擎、持续进化闭环、知识图谱、MCP tools 注册。

**独立新建**（29-42 人天）：向量检索（ObjectBox，10-15 人天）、嵌入模型（ONNX MiniLM，5-8 人天）、Android 文档解析器（替代 pymupdf，8-12 人天）、Jetpack Compose GUI、LLM 集成。

**许可证风险**：pymupdf 是 **AGPL-3.0**，与 Prism Apache 2.0 **不兼容**，parser Python 实现不可移植；Continuous-learning 项目本身 License "待定"，仅复用设计思想（非代码）较安全。缓解：Prism 用 Android 原生 PdfRenderer 或 pdfplumber(BSD) 替代 pymupdf。

**对 Prism 技术栈影响**：**不冲突**。ObjectBox 与 markdown+git 存储互补；MCP 协议相同，接口契约可直接参考。

**总移植成本**：50-73 人天（含实现移植 + 新建）。

### 4.4 可配置 AI 端点 / BYOK（模块 M4）

**已是红海**，大量开源参考，适配器模式是事实标准。

| 方案 | 来源 | 评估 |
|---|---|---|
| **Bring Your Own AI（iOS 开源）** | [App Store](https://apps.apple.com/ca/app/bring-your-own-ai/id6784853025) | ✅ 强参考。OpenAI/Anthropic/DeepSeek/Groq/OpenRouter/Ollama + Apple Foundation Model，Keychain+Secure Enclave AES-256，本地加密 SQLite，零遥测，开源 |
| **SpeakGPT（Android 开源）** | [CSDN 评测](https://blog.csdn.net/weixin_28676983/article/details/160837391) | ✅ 强参考。适配器模式统一 ChatRequest/ChatResponse，本地 SQLite + Android Keystore，"不碰数据"原则 |
| **EchoFlow（Android 开源）** | [productcool.com](https://www.productcool.com/product/echoflow-2) | ✅ 参考。OpenRouter BYOK，Material 3 Expressive，本地 Room 存储，SSE 流式 |
| **LibreChat 自定义端点** | [librechat.ai](https://www.librechat.ai/docs/quick_start/custom_endpoints) | ✅ 配置参考。`librechat.yaml` 定义 endpoints，`${VAR}` 引用 .env，支持 `provider: "anthropic"` 原生 + OpenAI 兼容 |
| **BYOK 工具大全** | [byoklist.com](https://byoklist.com/?category=chatbot) | ✅ 竞品清单。Oriveo/Daotuan/ModelAtlas/SynquoRum/UnboundChat/LettuceAI 等 |

**结论**：适配器模式，定义统一 `ChatRequest`/`ChatResponse`，每个 Provider 一个 Adapter。优先支持：OpenAI 兼容（覆盖大多数）、Anthropic 原生、Ollama 本地、Moonshot/DeepSeek/Qwen 国产模型、OpenRouter 聚合。

### 4.5 记忆系统（模块 M5）

需分层设计，单一策略无法兼顾上下文污染与长期记忆。

| 层级 | 目标 | 方案 |
|---|---|---|
| **L1 会话内** | 解决多轮上下文污染 | 滑动窗口 + 摘要压缩（每 N 轮摘要前文）+ 重要性评分保留关键消息 |
| **L2 跨会话向量记忆** | 长期记忆检索 | 对话历史向量化存入 Zvec/ObjectBox，新会话按当前话题 top-k 检索相关历史 |
| **L3 用户画像** | 越用越懂你 | 显式偏好（用户设定）+ 隐式偏好（从对话中抽取），结构化存储，注入 system prompt |

**防幻觉配套**（针对用户原始痛点）：

- 强制引用（参考 ZettelMancer）：知识库回答必须标注来源
- 会话隔离：不同话题会话不串味
- 不确定性表达：检索置信度低时主动说明

### 4.6 跨 App 调用（模块 M6，已确认走轻量路线）

| 方案 | 能力 | 双端可行性 | 审核 |
|---|---|---|---|
| **Deep Link / URL Scheme** | 打开指定 App 页面（微信聊天、地图导航、淘宝商品） | Android ✅ / iOS ✅ | 无风险 |
| **App Intents（Android）** | 调用目标 App 暴露的 Intent Action | Android ✅ | 无风险，需目标 App 支持 |
| **Share Sheet（ACTION_SEND）** | 分享内容到其他 App | Android ✅ / iOS ✅ | 无风险 |
| **系统 Picker** | Photo Picker / Document Picker 选取媒体文档 | Android ✅ / iOS ✅ | 无风险 |
| **ContentProvider 读取** | 读取通讯录/日历/媒体等系统数据 | Android ✅（需权限） | 无风险 |
| ~~无障碍服务~~ | 全 UI 自动化（tap/input/scroll） | 仅 Android | ❌ Google Play 审核极严，已确认不走 |

**结论**：以 Deep Link + App Intents + Share Sheet + 系统 Picker 组合实现"调用 App 功能"。AI Agent 自动触发 Deep Link 时需用户确认（防误操作）。

### 4.7 Kimi Claw 专项调研（用户指定参考）

**重大发现**：Kimi Claw 与 Prism 想法高度相似，且基于开源 OpenClaw 框架。

| 维度 | Kimi Claw | Prism（构想） |
|---|---|---|
| 出品方 | 月之暗面（Moonshot AI） | 个人开源 |
| 底层框架 | 基于 [OpenClaw](https://allclaw.org/entry/kimi-claw) 开源框架（359k+ stars） | 待定（可复用 OpenClaw） |
| 部署形态 | 云端一键部署 + Android 客户端 | 手机本地 App |
| 模型 | Kimi K2.5/K2.6/K3 | 用户 BYOK 任意 Provider |
| Skills | ClawHub 5000+ 社区 Skills | 自建市场（复用 SKILL.md） |
| MCP | ✅ 支持（[integrations 列表](https://slashdot.org/software/comparison/Kimi-Claw-vs-NullClaw/)含 MCP） | ✅ 支持 |
| 记忆 | 持久长期记忆 + 个性 | 三层记忆（L1/L2/L3） |
| 多渠道 | Kimi/飞书/微信/Telegram | 手机本地 + 可选消息平台 |
| 存储 | 40GB 云存储 | 端侧本地 + 可选同步 |
| 定价 | 国内 199 元/月 / 海外 99 美元/月 | 免费 |
| Android 客户端 | ✅ [Kimi Claw Android](https://www.kimi.com/help/kimi-claw/kimi-claw-android-guide)，Android 8.0+，5GB 存储，OpenClaw 部署到手机当 24/7 Gateway | ✅ Android 8.0+ |

**关键差异**：

- **Kimi Claw Android 把手机当 24/7 服务器**，通过消息平台远程控制；**Prism 是手机本身就是聊天客户端**，用户直接在手机上用。两者产品形态不同。
- Kimi Claw 绑定 Kimi 模型 + 付费订阅；Prism 是 BYOK + 免费开源。

**对 Prism 的启示**：

1. **OpenClaw 是可直接复用或深度参考的开源基础**——其 ClawHub、SKILL.md、多渠道、记忆、沙箱设计成熟
2. **NullClaw**（[slashdot 对比页](https://slashdot.org/software/comparison/Kimi-Claw-vs-NullClaw/)）是 Zig 实现的超轻量替代（678KB 单二进制，1MB RAM，原生 MCP + 22 模型 provider + 混合向量+FTS5 记忆 + 多级沙箱），对移动端极友好，值得评估作为 Prism 的本地 Agent 内核
3. Prism 差异化：**纯手机本地客户端 + BYOK + 免费**，避开 Kimi Claw 的付费云端定位

---

## 5. 对用户原始构想的 4 点异议（不切实际之处）

> 已向用户提出，用户已确认调整。

### 异议 1：iOS 上"访问其他手机软件的功能"几乎不可行（不越狱）

**事实**：iOS 沙盒严格隔离 App。Shortcuts 无法做 UI 交互（tap/scroll/text input）；App Intents 需目标 App 主动声明支持（WhatsApp/Telegram 等多数未实现）。

**用户决策**：✅ 改为**仅 Android**，规避 iOS 沙盒限制。

### 异议 2：手机本地跑 7B+ 大模型在中端机不切实际

**事实**：7B Q4 量化模型需 ~6GB 内存，中端机（4-8GB RAM）推理仅 1-3 token/s，且与系统其他 App 抢内存。

**用户决策**：✅ 改为**纯云端 BYOK**，端侧仅跑小嵌入模型（MiniLM ~80MB）做 RAG。

### 异议 3：含无障碍服务的 App 上 Google Play 风险极高

**事实**：Google 对无障碍服务滥用审核极严，非辅助残障人士用途的 App 常被拒下架。

**用户决策**：✅ 改为**个人开源自发布**（GitHub Releases / F-Droid / PGY），不走 Google Play；跨 App 走 Deep Link 轻量路线，不用无障碍服务。

### 异议 4：让普通用户自建远程 MCP Server 门槛过高

**事实**：MCP Server 多为 Node/Python 实现，普通手机用户无法自行部署。

**缓解方案**：内置 **MCP Server 市场**（官方推荐 + 一键添加，类似 Claude Desktop 的 `.mcp.json` 模板）+ 提供几个**官方托管的开箱即用 Server**（如网页搜索、文件系统、日历）作为兜底。**此项需用户后续确认是否接受"极简后端仅用于托管兜底 MCP Server"**——若用户坚持纯无后端，则 MCP 功能对普通用户形同虚设。

---

## 6. 推理过程（sequential-thinking 5 步摘要）

| 步 | 推理内容 | 输出 |
|---|---|---|
| 1 | 六大模块技术可行性梳理 | M1/M2/M3/M4 成熟，M5 需分层，M6 平台差异大 |
| 2 | 模糊点与不切实际项识别 | 6 模糊点 + 4 不切实际项 |
| 3 | 架构选型推理 | 倾向 Flutter（mcp_client + Zvec 都有 Dart SDK）；AI 端点适配器模式；记忆三层；MCP 仅远程 |
| 4 | 风险与差异化 | 7 大风险；差异化定位"五位一体 Agent 平台" |
| 5 | 提问优先级 | 锁定 4 关键问题（平台/算力/商业模式/跨App） |

---

## 7. 推荐技术栈（已整合 tech-selection-researcher 深度选型结论）

> 详细对比矩阵、PoC 设计、备选切换触发条件见姊妹报告 [2026-08-02-prism-tech-selection.md](2026-08-02-prism-tech-selection.md)。本节为整合定稿。

### 7.1 重大决策变更说明

`tech-selection-researcher` 子 Agent（任务令牌 TKN-PRISM-TECH-SELECTION-001，执行 Agent：tech-selection-researcher）已完成四阶段法（定标尺 → 广撒网 → 深验证 → 出报告）调研。**本节结论推翻了初版倾向**，关键变更有二：

| 课题 | 初版倾向 | 最终推荐 | 变更理由 |
|---|---|---|---|
| 1. 框架 | Flutter | **原生 Kotlin + Compose** | 仅 Android 场景 Flutter 跨平台优势无法发挥；Flutter +15ms 冷启动、54fps 滚动、+12MB APK、+12-28MB 内存是纯负担 |
| 2. MCP 客户端 | mcp_client Dart 包 | **官方 MCP Kotlin SDK 0.12.0** | 发现 [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)（Kotlin Multiplatform，全原语覆盖，SSE/WebSocket/Streamable HTTP） |
| 3. 向量库 | Zvec Dart SDK | **ObjectBox Java/Kotlin** | 原生方案下 ObjectBox 更成熟（HNSW <10ms 百万级，<8MB binary，800K+ 开发者） |
| 4. 嵌入模型 | all-MiniLM-L6-v2 | **all-MiniLM-L6-v2 ONNX INT8**（不变） | 22MB 模型，5.3ms/句编码，ONNX Runtime Mobile + NNAPI/KleidiAI 加速 |
| 5. 跨 App | Deep Link 组合 | **Deep Link 组合**（不变，补充兼容性数据） | 确认微信/支付宝/淘宝/抖音等 URL Scheme + Android 11+ `<queries>` 约束 |
| 6. Key 存储 | EncryptedSharedPreferences | **Keystore + DataStore (Tink)** | **重大发现**：[EncryptedSharedPreferences 2024 年初已废弃](https://doonprogramming.com/encryptedsharedpreferences-is-deprecated-what-to-use-instead-in-android/)，改用 DataStore + Tink AEAD 加密 |

### 7.2 最终推荐组合

**一句话**：原生 Kotlin + Jetpack Compose + 官方 MCP Kotlin SDK + ObjectBox 向量库 + all-MiniLM-L6-v2 ONNX 量化 + Deep Link/App Intents/Share Sheet/Picker 组合 + Android Keystore + DataStore（Tink 加密）+ 生物识别二次解锁。

| 层 | 推荐方案 | 备选 | 核心理由 |
|---|---|---|---|
| **框架** | 原生 Kotlin + Jetpack Compose | Flutter | 仅 Android 场景，Flutter 性能/内存/包体积开销是纯负担；官方 MCP Kotlin SDK 消除了 Flutter 的库优势 |
| **MCP 客户端** | 官方 MCP Kotlin SDK 0.12.0 | mcp_client Dart 包 | 官方维护，全原语覆盖（Tool/Prompt/Resource/Completion/Logging/Roots/Sampling/Elicitation），移动端传输全支持 |
| **向量库** | ObjectBox Java/Kotlin 6.0.0-beta | Zvec / sqlite-vec | HNSW <10ms 百万级检索，<8MB binary，混合检索（向量+标量+对象关联），800K+ 开发者验证 |
| **嵌入模型** | all-MiniLM-L6-v2 ONNX INT8 量化 | bge-small-zh | 22MB 模型，5.3ms/句编码，22MB 内存，ONNX Runtime Mobile + NNAPI/KleidiAI 加速（Phi-3 加速 2.6x） |
| **跨 App** | Deep Link + App Intents + Share Sheet + Picker | — | 轻量路线，无审核风险，组合覆盖全场景；Android 11+ 需 `<queries>` 声明 |
| **Key 存储** | Android Keystore + DataStore (Tink) + 生物识别 | Keystore + Cipher + DataStore（手动） | EncryptedSharedPreferences 已废弃；DataStore 是现代替代；TEE/StrongBox + 生物识别保障安全 |
| **Agent 内核（可选）** | 复用 OpenClaw 或 NullClaw 设计 / JetBrains Koog 框架 | 自研 | Koog 框架原生支持 MCP 集成 + 多 LLM provider + 知识检索 + 记忆；NullClaw 678KB/1MB RAM 对移动端极友好 |

### 7.3 P0 核心依赖（需写 ADR 严格管控）

依 CLAUDE.md 第十八节依赖分级，以下为 **P0 核心依赖**，必须写入 ADR，严格版本控制，手动审查升级：

| P0 依赖 | 版本 | 管控理由 |
|---|---|---|
| **Jetpack Compose** | BOM 锁定 | UI 框架，深入代码，升级可能破坏 UI |
| **官方 MCP Kotlin SDK** | 0.12.0 锁定 | MCP 协议合规性核心，0.x 版本 API 不稳定，**Tier 3 级别需关注功能完整性** |
| **ObjectBox** | 6.0.0-beta 锁定 | 向量数据持久化，迁移成本高，**向量搜索功能许可证需向厂商确认** |
| **ONNX Runtime Mobile** | 最新稳定版锁定 | 嵌入模型推理引擎，版本影响性能和兼容性 |
| **Android Keystore + DataStore** | 系统级 | API Key 安全核心，不可替换 |

### 7.4 待 PoC 验证的关键风险点

| PoC | 验证目标 | 触发备选切换的条件 |
|---|---|---|
| MCP Kotlin SDK PoC | SSE/Streamable HTTP 连接远程 MCP Server、Tool 调用、OAuth 2.1 补齐 | SDK 长期不更新（>6 个月无 commit）或关键功能无法补齐 → 切 mcp_client Dart（需改 Flutter） |
| ObjectBox 向量库 PoC | Android 上向量插入/检索性能、混合检索、持久化 | 向量搜索需付费且成本不可接受 → 切 sqlite-vec |
| all-MiniLM-L6-v2 ONNX PoC | 中端 Android 设备上模型加载/编码延迟/内存/NNAPI 加速 | 中文检索 recall <70% → 切 bge-small-zh |
| Keystore + DataStore PoC | API Key 加密存储/生物识别解锁/Keystore 崩溃处理 | datastore-tink 长期不稳定 → 切手动 Keystore + Cipher |
| Deep Link 兼容性测试 | 主流国产 App 跳转成功率 + 降级策略 | — |

---

## 8. 风险清单与缓解

| ID | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | ~~iOS 跨 App 能力缺失~~ | — | ✅ 已规避（仅 Android） |
| R2 | ~~Google Play 审核拒绝~~ | — | ✅ 已规避（自发布） |
| R3 | 端侧 RAG 内存占用大，低端机不可用 | 中 | 降级策略：低端机禁用 RAG 或仅用关键词检索；模型按设备 ABI 动态加载 |
| R4 | MCP 生态移动端不成熟，远程 Server 需用户自建门槛高 | 中 | 内置市场 + 官方托管兜底 Server + 一键模板（异议 4 缓解） |
| R5 | BYOK 模式下用户 API Key 安全 | 中 | Android Keystore + 生物识别二次解锁；参考 Bring Your Own AI/SpeakGPT 实践 |
| R6 | 上下文污染与幻觉（用户原始痛点） | 高 | 三层记忆 + 强制引用 + 会话隔离 + 不确定性表达多管齐下 |
| R7 | 法务合规（用户自配端点可能访问未授权服务） | 低 | 免责声明 + 不内置未授权端点 + 用户协议 |
| R8 | 国产 App Deep Link 支持参差 | 中 | 维护兼容性清单；不支持时降级到 Share Sheet |
| R9 | Flutter 平台 channel 调用系统 API 开销 | 中 | 高频路径用 FFI/原生插件；选型阶段实测验证 |
| R10 | OpenClaw/NullClaw 许可证兼容性 | 中 | 选型阶段核实 LICENSE，确认与 Prism 开源协议兼容 |

---

## 9. 差异化定位

### 9.1 竞品对标

| 产品 | MCP | Skills | 本地知识库 | 记忆 | BYOK | 跨App | 手机原生 | 免费 |
|---|---|---|---|---|---|---|---|---|
| ChatGPT/Gemini | ❌ | ❌ | ❌ | 有限 | ❌ | ❌ | ✅ | 部分 |
| Kimi Claw | ✅ | ✅（5000+） | 云端 | ✅ | ❌（绑 Kimi） | ✅ | ✅ | ❌（付费） |
| Bring Your Own AI | ❌ | ❌ | ❌ | 有限 | ✅ | ❌ | ✅（iOS） | ✅ |
| SpeakGPT | ❌ | ❌ | ❌ | 有限 | ✅ | 有限 | ✅（Android） | ✅ |
| OpenClaw | ✅ | ✅ | 本地 | ✅ | ✅ | ✅ | ❌（桌面/服务器） | ✅ |
| **Prism（目标）** | ✅ | ✅ | ✅ 端侧 | ✅ 三层 | ✅ | ✅ 轻量 | ✅ Android | ✅ |

### 9.2 Prism 切入点

**移动端原生 + MCP + 端侧 RAG + BYOK + 免费** 的组合是蓝海：

- Kimi Claw 付费且绑模型，Prism 免费 + BYOK
- Bring Your Own AI / SpeakGPT 只做聊天，Prism 是 Agent 平台
- OpenClaw 不在手机本地，Prism 是手机原生 App

---

## 10. 待确认事项与下一步

### 10.1 待用户确认

1. **异议 4 缓解方案**：是否接受"极简后端仅用于托管兜底 MCP Server / Skills 市场"？还是坚持纯无后端（MCP 功能对普通用户形同虚设）？
2. **Agent 内核选择**：是否倾向复用 OpenClaw（功能全但重）或 NullClaw（超轻量）的设计？还是完全自研？
3. **开源协议**：Prism 计划用何种 LICENSE（MIT / Apache 2.0 / GPL）？影响对 OpenClaw/NullClaw 的复用可行性。
4. **目标设备最低配置**：Android 8.0 + 多少 RAM？决定 RAG 是否默认开启。

### 10.2 下一步流程（依 CLAUDE.md）

1. ✅ 调研与提问（本报告）
2. ⏳ **tech-selection-researcher 子 Agent 返回深度选型报告** → 整合到本报告第 7 节
3. ⏳ 基于选型结论写 **ADR-001 Prism 技术栈选型**（依第十七节 ADR 触发条件）
4. ⏳ 基于确认后的需求写 **PRD**（用 `docs/templates/` 模板）
5. ⏳ 基于选型与 PRD 写 **ARCH**（用模板）
6. ⏳ 启动 `code-archaeologist` 对 OpenClaw/NullClaw 源码考古（若决定复用）
7. ⏳ 进入编码阶段后，每次代码修改走 guardrail-enforcer → ac-verifier 闭环

---

## 11. 参考资料

### MCP 移动端

- [mcp_client Dart 包](https://pub.dev/packages/mcp_client)
- [mcp-mobile-interaction](https://www.npmjs.com/package/mcp-mobile-interaction)
- [Rork × MCP 实现指南](https://rorklab.net/en/articles/rork-ai/rork-mcp-model-context-protocol-mobile-app-guide)
- [MCP in Browser & Mobile](https://www.mcpserverspot.com/learn/integrations/mcp-browser-mobile)

### Skills 系统

- [OpenClaw 评测](https://pyshine.com/OpenClaw-Personal-AI-Assistant/)
- [maid 移动端 AI 框架](https://blog.csdn.net/weixin_27230891/article/details/160672257)
- [OpenAI Plugin architecture](https://developers.openai.com/plugins/concepts/plugins)

### 端侧 RAG

- [MobileRAG 论文 arxiv 2507.01079](https://arxiv.org/pdf/2507.01079)
- [Zvec 0.4.0 Mobile](https://zvec.org/en/blog/2026-06-22-zvec-mobile/)
- [移动端本地知识库+大模型部署](https://blog.csdn.net/renhongxia1/article/details/156568003)
- [EMSOFT 2024 On-device RAG 海报](https://hokeun.github.io/posters/EMSOFT_2024_Poster.pdf)
- [ZettelMancer 个人 Wiki RAG](https://gist.github.com/danyshs/5477a1468e8810b6e703ee03f20400f2)

### BYOK

- [Bring Your Own AI](https://apps.apple.com/ca/app/bring-your-own-ai/id6784853025)
- [SpeakGPT 评测](https://blog.csdn.net/weixin_28676983/article/details/160837391)
- [EchoFlow](https://www.productcool.com/product/echoflow-2)
- [LibreChat Custom Endpoints](https://www.librechat.ai/docs/quick_start/custom_endpoints)
- [BYOK 工具大全](https://byoklist.com/?category=chatbot)

### 跨 App

- [Android 无障碍服务](https://blog.csdn.net/yesemenglongyulong/article/details/148754050)
- [Android 无障碍实战](https://blog.csdn.net/z4a5b6/article/details/153174569)
- [iPhone Shortcuts vs MacroDroid](https://lifetips.alibaba.com/tech-efficiency/iphone-shortcuts-vs-macrodroid-real-control-without-developer-mode/)

### Kimi Claw / OpenClaw / NullClaw

- [Kimi Claw 介绍](https://programb.blog.csdn.net/article/details/159649306)
- [Kimi Claw Android 指南](https://www.kimi.com/help/kimi-claw/kimi-claw-android-guide)
- [Kimi Claw vs NullClaw](https://slashdot.org/software/comparison/Kimi-Claw-vs-NullClaw/)
- [Kimi Claw 官方介绍](https://allclaw.org/entry/kimi-claw)
- [Kimi Claw 多语言评测](https://ko.ai-pedias.com/tools/kimi-claw)
- [Kimi Claw 部署指南](https://www.kimi.com/id/resources/kimi-claw-introduction)
- [华为小艺接入 Kimi K3](http://m.toutiao.com/group/7666359195859567150/)

---

**报告版本**：v1.0 定稿
**姊妹报告**：[2026-08-02-prism-tech-selection.md](2026-08-02-prism-tech-selection.md)（tech-selection-researcher 子 Agent 深度选型对比分析，含 6 课题量化对比矩阵、PoC 设计、备选切换触发条件）
