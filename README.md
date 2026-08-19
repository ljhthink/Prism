# Prism

> 手机端个人 AI Agent 平台 —— 让 AI 真正"用"起来。

Prism 是一款 Android 原生 AI 聊天应用，核心定位是**个人 Agent 平台**。它不只是一个聊天界面，而是一个让 AI 可以调用工具、读取知识库、记住你、甚至操作手机其他 App 的开放平台。

## 特性

### 联网搜索（零配置，开箱即用）

内置 **Bing RSS 联网搜索**，无需 API Key、无需注册，在 App 内开启联网开关即可使用。AI 可实时搜索互联网获取最新信息并引用来源。

- 搜索工具名：`web_search__search`
- 参数：`query`（搜索关键词）+ `maxResults`（1-8 条，默认 5）
- 返回：标题 + 链接 + 摘要，标注「外部内容，未经验证」
- 智能降级：对中文新词/冷门词自动用核心词短整词重试，避免 Bing 长 query 分词失败
- 失败熔断：同一工具连续失败 2 次后自动熔断，避免 LLM 空转

### BYOK 多端点（Bring Your Own Key）

支持用户自配任意 LLM 端点：

- OpenAI 兼容（DeepSeek、Moonshot、OpenRouter 等）
- Anthropic 原生
- Ollama（本地模型）
- API Key 通过 Android Keystore + DataStore 加密存储
- 会话中可一键切换 Provider，保留对话历史

### MCP 工具系统（Model Context Protocol）

内置 **MCP Client**，支持 AI 调用各类工具扩展能力。预设 15 个常用 Server：

- **本地 6 个**：文件系统读写、网页抓取、记忆管理、Sequential Thinking、时间查询、跨 App 调用
- **远程 9 个模板**：GitHub、Notion、Slack、Sentry、Stripe、Asana、Brave Search、Exa、Context7
- 支持用户添加任意远程 MCP Server
- 工具调用前需用户确认（可配置自动批准白名单）

### Skills 系统（可扩展工具包）

复用 OpenClaw SKILL.md 设计，支持本地与远程加载：

- 内置 3 个 Skill：中文人性化改写、联网深度调研、网页数据抓取
- 通过 `SKILL.md` 声明工具定义，snakeyaml 安全解析
- 远程下载 HTTPS + 9 层安全校验 + zip slip 防护
- 渐进式加载：仅启用时加载，不污染 system prompt

### 个人知识库（端侧 RAG）

端侧本地知识库，降低 AI 幻觉：

- 导入格式：PDF / DOCX / XLSX / MD / TXT
- 文档解析 → 切片（可配 chunk size/overlap）→ 嵌入（all-MiniLM-L6-v2 ONNX INT8）→ 向量检索
- 对话时自动检索 top-k 相关片段注入 prompt
- AI 回答标注引用来源（文件名 + 片段位置）
- 支持分库管理（如"工作"/"学习"）
- 嵌入模型按需加载，闲置 5 分钟卸载

### 三层记忆系统

解决多轮对话上下文污染：

- **L1 滑动窗口**：最近 N 轮对话（默认 10 轮）+ LLM 摘要压缩，超窗口时压缩注入
- **L2 跨会话记忆**：对话结束自动向量化存储，新会话 top-k 检索相关记忆（默认 k=3）
- **L3 用户画像**：显式偏好（UI 设定）+ 隐式偏好（LLM 从对话中抽取）
- 降级设计：L1/L2/L3 任一失败降级为 null，不阻断对话

### 跨 App 调用

AI 可调用手机其他 App 的能力：

- Deep Link 跳转（微信、支付宝、淘宝、抖音、QQ、微博、百度地图）
- Share Sheet 发送内容
- Media Picker 选择文件
- 用户确认弹窗，安全可控

### 深度思考

支持 DeepSeek 的 `thinking` / `reasoning_effort` 参数，按开关开启/关闭，AI 推理过程以可折叠卡片展示。

### 设备适配

四档性能模式（FULL / STANDARD / MINIMAL / CHAT_ONLY）按 RAM 自动降级：

- FULL（≥8GB）：全部功能
- STANDARD（≥6GB）：关闭端侧嵌入
- MINIMAL（≥4GB）：关闭嵌入 + 记忆系统
- CHAT_ONLY（<4GB）：纯聊天

支持手动覆盖，**重启生效**。

## 快速开始

### 前置要求

- Android Studio Hedgehog (2023.1.1+) 或更高版本
- JDK 17+
- Android SDK 34
- 真机（Android 8.0+ / API 26+）或 arm64 模拟器

### 构建

```bash
git clone git@github.com:ljhthink/Prism.git
cd Prism
./gradlew :app:assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 配置

首次启动后，进入设置页配置你的 LLM Provider：

1. **添加 Provider**：点击右上角设置 → Provider 管理 → 添加
2. **配置端点**：填入名称（如 DeepSeek）、baseURL、API Key
3. **选择模型**：输入模型名称（如 `deepseek-chat`）
4. **保存**：API Key 经 Android Keystore 加密存储

### 功能开关

- 设置页可开启/关闭：联网搜索、深度思考、知识库检索、工具审批模式
- 联网搜索开关位于输入框上方，快捷切换

## 架构概览

```text
Prism/
├── app/
│   └── src/main/java/io/prism/
│       ├── network/           # 网络层：Provider / MCP Client / 流式请求
│       │   ├── ChatStreamProvider.kt       # 流式 SSE 请求
│       │   ├── OpenAICompatibleProvider.kt # OpenAI 兼容协议（含 tool_calling）
│       │   ├── WebSearchLocalToolExecutor.kt  # Bing RSS 联网搜索
│       │   ├── KnowledgeBaseLocalToolExecutor.kt # 知识库工具
│       │   ├── McpClientManager.kt         # MCP Kotlin SDK Client
│       │   └── LocalMcpToolProvider.kt     # 本地 MCP 工具（Fetch/Filesystem）
│       ├── skill/              # Skills 系统
│       │   ├── SkillExecutor.kt            # 工具执行回路（maxRounds 10 + 熔断）
│       │   └── CompositeLocalToolExecutor.kt # 本地工具组合
│       ├── ui/                 # UI 层 Compose
│       │   ├── chat/           # 聊天界面 + ViewModel
│       │   ├── knowledge/      # 知识库管理 UI
│       │   ├── settings/       # 设置页
│       │   └── capabilities/   # 能力管理（Skills/记忆/跨 App）
│       ├── data/               # 数据层（ObjectBox 实体 + Repository）
│       ├── memory/             # 三层记忆系统
│       ├── rag/                # RAG 对话集成
│       ├── embedding/          # 端侧嵌入引擎（ONNX Runtime）
│       ├── ingestion/          # 文档摄入管线
│       ├── crossapp/           # 跨 App 调用
│       ├── security/           # 安全层（KeyStore 加密）
│       └── config/             # 配置仓库
├── docs/                       # 文档
│   ├── decisions/              # 架构决策记录（ADR）
│   └── templates/              # PRD/ARCH/ADR/Task 模板
└── AGENTS.md                   # 项目进度与治理记录
```

## 技术栈

| 领域 | 选型 |
|---|---|
| 语言 | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| 数据库 | ObjectBox 4.0 |
| 网络 | Ktor 3.x（OkHttp） |
| 序列化 | kotlinx.serialization |
| MCP | MCP Kotlin SDK 0.12.0 |
| 嵌入 | ONNX Runtime Android + all-MiniLM-L6-v2 INT8 |
| 文档解析 | Apache PDFBox 3.0.8 + POI 5.4.0 |
| 安全 | Android Keystore + Tink 1.16.0 |
| 构建 | Gradle 8.7 + AGP 8.7 |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 14 (API 34) |

## 协议

本项目基于 [Apache 2.0](LICENSE) 许可证开源。

## 获取

- GitHub Releases：[v0.1.0](https://github.com/ljhthink/Prism/releases/tag/v0.1.0)
- 自行构建：[构建指南](#快速开始)

## 相关文档

- [AGENTS.md](AGENTS.md) —— 项目进度与治理记录（面向 AI Agent 与开发者）
- [docs/decisions/](docs/decisions/README.md) —— 架构决策记录（ADR-001~ADR-040）
- [docs/PRD.md](docs/PRD.md) —— 产品需求文档 v0.1
- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则
