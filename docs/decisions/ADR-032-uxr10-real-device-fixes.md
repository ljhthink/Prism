# ADR-032: UXR10 真机问题修复（上传崩溃 / 多模态误判 / Fetch 限流 / Skills 感知 / 上传交互）

> 实现 UXR10（2026-08-18 真机二次手动测试 5 项问题）架构决策：根治文件上传崩溃、多模态能力误判、
> Fetch 工具在 LLM 端点限流下的可用性、内置 Skills 默认不可感知、以及上传交互不符合预期。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-18 |
| 决策者 | 主 Agent + 用户确认（真机测试反馈 5 项，逐一根治） |
| 关联文档 | [PRD UXR9](../prd-uxr9.md)、[ADR-030 UXR8-B3](ADR-030-uxr8-b3-new-features.md)、[ADR-031 UXR9](ADR-031-uxr9-multilingual-embedding-and-l2-memory.md)、[ADR-013 M4 Skills](ADR-013-m4-skills-system.md)、[ADR-023 UXR3 修复](ADR-023-ux-r3-fixes.md) |
| 风险等级 | P2（跨模块：文档解析 / 协议错误映射 / 工具回路 / Skills 注册 / 聊天 UI） |

## 背景（Context）

2026-08-18 真机二次手动测试暴露 5 项问题，前一轮 UXR9 修复（ADR-031）未覆盖：

1. **上传文件后直接崩溃闪退**：PdfDocumentParser 生产依赖桌面 `org.apache.pdfbox:pdfbox:3.0.8`，
   其内部引用 `java.awt.Point` 等，Android 无 `java.awt` 包 → 真机解析 PDF 抛
   `ClassNotFoundException: java.awt.Point` 崩溃。JVM 单测通过是因为桌面 JVM 自带 java.awt
   （测试-生产运行时漂移，同类根因见 ADR-031 US-905 的 expectSuccess 漂移）。
2. **多模态误判**：调用多模态模型 kimi-k2.6 发图，仍被提示「当前模型端点不支持图片（多模态）」。
   根因：`mapHttpError` 对「400 + 请求含图」**一律**降级为不支持图片文案（ADR-030 N3 决策），
   但多模态模型的 400 可能由图片格式/大小/URL 等其它原因触发，被误报误导用户。
3. **Fetch 工具依旧不可用**：显示「请求被拒绝（429）：…request reached organization max RPM: 3」。
   根因：429 来自 **LLM 端点（Moonshot Kimi 组织级 RPM=3）**，非 Fetch 目标站。工具回路每轮
   工具执行后都要重新调用 LLM（ADR-014 协议要求），一次「深度调研」（2-4 轮搜索/抓取）瞬间打满
   RPM=3。客户端无法改变账号配额，但应给出限流专属文案 + 不诱导 LLM 反复重试。
4. **Skills 反馈完全无效**：LLM 感知不到 Skills 系统，要求调用「联网深度调研」时所有联网工具崩溃。
   根因：`SkillRegistry.computeSyncDiff` 对首次安装的 Skill（含内置预设）默认 `isEnabled=false`，
   用户未在 UI 手动启用时 `enabledSkills()` 为空 → LLM 工具列表完全没有 skill 工具，且收不到
   web-research 等内置 Skill 的 system-prompt。
5. **上传交互不符合预期**：图片/文件选择后**立即发送**（ADR-031 US-907 方案 A 文本直发），
   用户期望「上传后由用户打字添加需求再发送」。

## 决策（Decision）

### 子决策 A：PDF 解析切 pdfbox-android（修复 1）

- 生产依赖从桌面 `org.apache.pdfbox:pdfbox:3.0.8` 切换为 **`com.tom-roush:pdfbox-android:2.0.27.0`**
  （Apache PDFBox 2.0.27 的官方 Android 移植，Apache 2.0，Maven Central，将 java.awt 替换为
  android.graphics）。
- `PrismApplication.onCreate` 调用 `PDFBoxResourceLoader.init(context)` 初始化字体/资源加载器。
- PDF 相关 JVM 单测改经 **Robolectric** 运行（pdfbox-android 依赖 android.graphics，纯 JVM 无法运行）；
  测试夹具仍由桌面 pdfbox（仅 `testImplementation`）生成。桌面 pdfbox 不再进入生产 APK。
- 桌面 pdfbox 保留为 `testImplementation`（生成测试 PDF 夹具），避免生产包体积增长。

### 子决策 B：多模态误判修复（修复 2）

- `mapHttpError` 对「400 + 请求含图」**不再盲目**报「不支持图片」：新增 `isVisionUnsupportedError`
  检查服务端错误详情（`VISION_UNSUPPORTED_KEYWORDS`：does not support image / images are not
  supported / image_url / multimodal / not support vision 等，小写匹配）。
- 仅当错误详情明确含图片不支持信号时才降级视觉文案（保留 ADR-030 N3 原始意图：纯文本端点
  如 DeepSeek 收到多模态数组 400 仍提示换模型）；否则展示服务端具体错误 + 「请确认图片格式/大小」。
- 新增 **429 限流专属文案**（此前落入通用「请求被拒绝，请检查 Provider 配置」误导）：
  「请求过于频繁，触发服务端限流（429）。请稍等几秒后重试」。

### 子决策 C：Fetch/搜索在限流下的可用性（修复 3）

- Fetch 工具按状态码输出**可诊断文案 + 显式标注勿重试**（网络调研：webfetch-mcp / 官方
  server-fetch 等 MCP 实现确认 Fetch 被 Cloudflare/Paywall 反爬拦截 403 / 目标站限流 429 是常态）：
  - 403 → 「目标站点拒绝访问（403，可能反爬或需登录）。请勿反复重试同一 URL，改用其他来源」
  - 404 → 「目标页面不存在（404）。请勿反复重试，改用其他来源」
  - 429 → 「目标站点限流（429）。请稍后再试或改用其他来源，勿连续抓取」
  - 其它 → 「HTTP xxx + 请勿反复重试同一 URL」
- **不诱导 LLM 反复重试**：此前统一「抓取失败：HTTP xxx」会让 LLM 误以为 URL 写错而反复抓取，
  放大请求频率 → 叠加 LLM 端点（kimi RPM=3）限流。配合既有重复工具熔断（ADR-024，连续失败 2 次
  熔断置空工具）与 R2 429 文案形成闭环。
- LLM 端点 429 时工具回路**自然停止**（429 → StreamEvent.Error → 无 ToolCallComplete → break），
  不重试、不继续调 LLM（尊重服务端限流，避免进一步打满）。

### 子决策 D：内置 Skill 默认启用（修复 4）

- `SkillRegistry.computeSyncDiff`：**内置 Skill（LOCAL_BUILTIN）首次安装默认 `isEnabled=true`**
  （开箱即用，LLM 首次即感知内置预设能力——web-research 深度调研 / firecrawl / humanizer-zh）。
  用户自建 / 远程下载 Skill 仍默认不启用（由用户主动启用）。
- 用户手动禁用内置 Skill 后，`toUpdate` 分支保留 `isEnabled=false`（copy 自 existingConfig），
  重启不会被重新启用（幂等）。
- 搜索工具失败不拖垮技能：既有「搜索失败」失败前缀（ADR-024/031）+ 重复工具熔断已保证
  LLM 换词重试 ≤2 次即熔断，不会无限循环拖垮回路。

### 子决策 E：上传交互改版 —— 附件草稿暂存（修复 5）

- 图片/文件选择后**不再立即发送**，改为暂存**待发送附件草稿**（ConversationScreen 局部状态：
  `pendingImageDataUrl` / `pendingFileName` / `pendingFileText`），输入框上方显示预览卡片
  （图片缩略图 / 文件名 + 「输入需求后发送」提示 + ✕ 移除）。
- 图片与文件**互斥**（后选替换前选），点击发送统一发出：
  - 文件附件 → 文档文本与用户需求合并为一条用户消息（LLM 同轮收到内容 + 指令）
  - 图片附件 → `sendMessage(text, imageUrl)` 多模态直传（含用户需求文本）
  - 无附件 → 普通文本消息
- 发送按钮启用条件：输入框非空 **或** 存在待发送附件（保留「只发图/文件不配字」场景，
  兼容 ADR-030 N3 允许空文本发图）。
- 发送 / 移除后清空草稿。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 继续用桌面 pdfbox + 在 ProGuard keep `java.awt` | 改动小 | Android 8.0+ 运行时**无 java.awt 类**，R8 keep 也无法凭空造类；必须换 Android 兼容库 |
| 换 Android PdfRenderer 抽取 PDF 文本 | 系统内置零依赖 | PdfRenderer 仅渲染位图、**无文本抽取 API**（ADR-007 5.3 已否决）；无法满足「提取文本发送 LLM」 |
| 400 + 含图一律报不支持图片（维持 ADR-030） | 实现简单 | 真机实测 kimi-k2.6（支持视觉）发图 400 被误报，误导用户；需按服务端错误详情区分 |
| 上传后弹窗强制用户输入再发 | 交互明确 | 打断流程、约束过死；草稿预览 + 统一发送更自然，且兼容只发附件 |
| 所有 Skill 一律默认启用 | 更省事 | 用户/远程 Skill 可能含不可信指令，默认启用扩大 prompt 注入面；仅内置（受信）默认启用 |

## 后果（Consequences）

**正面**：
- 真机上传 PDF/DOCX/XLSX/PPTX 不再崩溃（pdfbox-android 与 android.graphics 兼容）。
- 多模态模型发图不再被误报「不支持图片」；429 限流给出可理解文案并停止回路。
- Fetch 被反爬拦截时给出可诊断文案，LLM 不再无脑重试（缓解 RPM 打满）。
- 内置 Skills 开箱即用，LLM 首次即感知「联网深度调研」等能力。
- 上传交互符合预期：选图/选文件 → 输入需求 → 统一发送。

**负面 / 需注意**：
- pdfbox-android 需 `PDFBoxResourceLoader.init`（已在 Application.onCreate 注入，低风险）。
- 内置 Skill 默认启用后，未启用的旧数据（DB 中 isEnabled=false 且非 hidden）不会自动翻转为启用
  （toUpdate 保留 isEnabled）——仅**新安装**的首次扫描生效；已在 UI 禁用过的用户需手动再启用。
- 多模态 400 判断依赖服务端错误详情关键词，极端新错误文案可能漏判（回落展示服务端原文，仍可诊断）。
- 附件草稿仅支持单个附件（图片或文件互斥）；多附件混排留作后续迭代。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| pdfbox-android 字体资源未初始化导致解析失败 | P2 | `PDFBoxResourceLoader.init` 在 Application.onCreate 最前执行；测试经 Robolectric 验证 |
| 429 判断误伤（LLM 端点限流被当工具失败回灌） | P2 | 429 走 StreamEvent.Error 直接 break（非工具失败回灌），文案明确提示稍等 |
| 内置 Skill 默认启用引入 prompt 注入面 | P2 | 仅 `LOCAL_BUILTIN`（assets 受信来源）默认启用；用户/远程仍手动启用 |
| 附件草稿图片解码消耗内存 | P3 | 复用既有 `decodeImageDataUrl` 缩略图（remember 缓存）；与消息气泡同链路 |
| R5 修改破坏既有「只发图不配字」 | P3 | 发送按钮 `value.isNotBlank() \|\| hasAttachment` 保留该场景 |

## 参考

- [ADR-030 多模态视觉直传（N3）](ADR-030-uxr8-b3-new-features.md)
- [ADR-013 Skills 系统](ADR-013-m4-skills-system.md)
- [ADR-023 Fetch MCP 工具（UXR3 问题 11）](ADR-023-ux-r3-fixes.md)
- [ADR-024 工具回路与重复工具熔断](ADR-024-ux-r4-fixes.md)
- 网络调研（Fetch MCP 反爬优化）：[webfetch-mcp](https://lobehub.com/mcp/simonediroma-webfetch_mcp)、
  [Fetch MCP Server](https://llmversus.com/mcp/mcp-server-fetch)、
  [60-Line MCP Web-Fetch](https://readerfi.com/discover/35867)
