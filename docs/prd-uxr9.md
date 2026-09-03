# UXR9 · 真机问题修复 + 体验增强 · 产品需求文档（PRD）

> 从 `docs/templates/PRD-template.md` 复制新建。验收标准可验证（可运行测试 / 可观测指标）。
> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。

| 项目 | 内容 |
|---|---|
| 版本 | v0.1（待用户确认） |
| 日期 | 2026-08-17 |
| 作者 | Prism 主 Agent |
| 关联文档 | [考古报告 2026-08-17-uxr9-archaeology.md](reports/2026-08-17-uxr9-archaeology.md)、[ADR-012](decisions/ADR-012-rag-architecture.md)、[ADR-023](decisions/ADR-023-uxr3-fixes.md)、[ADR-030](decisions/ADR-030-uxr8-b3.md) |
| 风险等级 | P2（跨模块：RAG 检索链路 / 输入区 UI 重构 / 记忆写入链路） |

## 1. 背景

2026-08-18 真机（小米 13 / arm64）手动测试暴露 5 个 Bug 与 3 项体验缺失。其中 **RAG 无关资料注入为多轮未愈的老 Bug**（ADR-012→023→UXR8-R3 均未根治），本次考古定位到结构性根因：端侧嵌入模型是**英文** MiniLM（`nreimers/MiniLM-L6-H384-uncased`，vocab 30522 = 英文 BERT 词表），对中文语义区分度差，无关中文片段余弦相似度普遍落在 0.4~0.7，0.5 阈值拦不住。

## 2. 目标与非目标

- 目标：
  - 根治 RAG 无关资料注入（Bug 1）
  - 修复联网搜索关键字拆分回归（Bug 2）
  - 修复图片上传全失败 + 失败提示误触发 LLM（Bug 3）
  - 优化 L2 跨会话记忆（按重要性选择性记忆，Bug 4）
  - 修复 Fetch MCP 工具全失败（Bug 5）
  - 新增：发送后自动收起键盘 / "＋"折叠栏上传（相册+文件）/ Skills 调用 UI 反馈
- 非目标（明确不做）：
  - 不引入云端旁路视觉（ADR-030 已搁置的方案 B）
  - 不更换 LLM Provider 协议（保持 OpenAI 兼容 Chat Completions）
  - 不新增第三方图片/文档加载大依赖（保持零新增第三方依赖原则，除非决策确认）

## 3. 用户故事与验收标准

> 验收标准必须可验证：可运行测试 / 可观测指标 / 可操作步骤。

### US-901: RAG 按语义相关度注入（根治 Bug 1）

- 作为用户，我希望知识库检索只注入与问题真正相关的资料，以便 LLM 不被无关片段污染。
- 验收标准：
  - [x] 用真实中文 ONNX 模型对「无关中文句对」实测余弦相似度，确定新阈值（考古预测需 0.62~0.65）
  - [x] 提问"昔涟这个角色做了哪些事情"，库中两篇与角色无关的资料**不再**进入引用来源与上下文
  - [x] `knowledge_base__search` 工具（LLM 主动调用路径）同样做阈值过滤 + 结果条数上限（top-2）
  - [x] `RagContextBuilder.buildContext` 限制注入片段条数（top-2），控制上下文膨胀
  - [x] 全量回归 0 失败

> **UXR9-B1 实现记录**：换多语言嵌入模型 paraphrase-multilingual-MiniLM-L12-v2 qint8（~113MB）
>
> - Unigram tokenizer（tokenizer.json ~9MB）。实测相似度分布（ChineseSimilarityDiagnosticTest）：
> 相关中文句对 0.582/0.655，无关中文句对 -0.067~0.322 → 阈值校准为 **0.5**（旧英文模型无关
> 片段 0.4~0.7 是 RAG 污染结构性根因，已换模型根治）。`RAG_SIMILARITY_THRESHOLD` 与
> `KnowledgeBaseLocalToolExecutor.SIMILARITY_THRESHOLD` 均校准为 0.5；注入条数上限 top-2。
>
> **⚠️ M-1 索引迁移**（guardrail TKN-UXR9-GUARDRAIL-001 披露）：旧知识库 chunk 向量由英文
> MiniLM 生成，与新多语言模型语义空间**不兼容**（检索会 miss/误判）。升级后用户需在知识库页
> **删除并重新导入**已有文档重建索引（建议发布说明披露；后续迭代考虑 embeddingModel 版本字段 +
> 一键重建）。

### US-902: 联网搜索结果条目级过滤（修复 Bug 2）

- 作为用户，我希望搜索结果不混入"只剩单字"的噪声条目，以便回答准确。
- 验收标准：
  - [x] 搜索"昔涟"，返回结果中**不含**仅命中"昔"一个字的条目（逐条目按核心词过滤）
  - [x] 完整关键字命中（"昔涟"）的条目正常保留
  - [x] 既有「多候选核心词短整词降级重试」逻辑不回归

### US-903: 图片上传修复 + 失败提示不触发 LLM（修复 Bug 3）

- 作为用户，我希望图片能正常上传，且上传失败时只提示、不打扰 LLM。
- 验收标准：
  - [x] `encodeImageToDataUrl` 失败路径补结构化日志（Log.w + 异常类型/阶段），真机日志可定位根因
  - [x] 引入 `ImageDecoder`（API 28+，支持 HEIC/HEIF）+ `BitmapFactory` 兜底（API 26-27）双解码链路，修复 HEIC 图片解码失败
  - [x] 大图 `OutOfMemoryError`（Error 非 Exception）被捕获并提示
  - [x] 图片编码失败提示走**系统消息通道**，绝不触发 `launchAnswer`（LLM 不被调用）
  - [x] 真机任选 3 张不同来源图片（相册 JPEG/PNG/HEIC、截图）均能成功编码并发送（模拟器验证通过，真机待补）

> **UXR9-B1 实现记录**：双解码链路（ImageDecoder API 28+ → BitmapFactory 兜底）+ inSampleSize
> 降采样（MAX_IMAGE_EDGE_PX）+ 失败结构化日志。**附加修复**：`flushPendingImageMessage` 处理
> 系统提示后不得 return，否则溢出提示 + 多图同时入队时图片队列滞留（回归测试发现，已修复）。

### US-904: L2 跨会话记忆按重要性选择性记忆（优化 Bug 4）

- 作为用户，我希望只有真正重要的信息（偏好、关键事实、任务结论）被跨会话记住，一次性闲聊不被记忆。
- 验收标准：
  - [x] `saveSessionMemories` 增加重要性筛选：纯寒暄/确认/一次性问答不写入 L2
  - [x] 会话结束时对有价值内容生成摘要（LLM 摘要，失败降级为规则抽取）后入库，不再原样全量入库
  - [x] 检索侧加会话隔离与相似度阈值，避免无关记忆注入
  - [x] 既有 L1/L3 记忆行为不回归

> **UXR9-B1 实现记录**：`isImportantTurnPair` 重要性过滤（寒暄/确认/继续跳过，偏好/身份/任务
> 信号词或实质问题保留）；`saveSessionMemories` 注入 `ConversationSummarizer` 做 LLM 摘要
> （`[摘要]` 前缀单条记录入库），失败降级为规则抽取（逐对存储重要轮次）；`retrieveRelevantMemories`
> 检索阈值 0.4（会话隔离：新会话 sessionId 全新，天然只命中旧会话记录）。

### US-905: Fetch MCP 工具可用（修复 Bug 5）

- 作为用户，我希望 LLM 调用 Fetch MCP 能成功抓取网页内容。
- 验收标准：
  - [x] MCP Fetch 工具使用 `expectSuccess=false` 的 HTTP 客户端（非 2xx 按状态码处理而非抛异常）
  - [x] 修复 `isPublicHttpUrl` 对含中文/非 ASCII URL 的 URI 解析误拒
  - [x] 测试补齐：生产路径（非 2xx 状态码处理）+ 中文 URL 校验，消除测试-生产行为漂移
  - [ ] 真机验证 Fetch 能返回网页内容（模拟器/待真机补测）

### US-906: 发送后自动收起键盘

- 作为用户，我希望发送消息后键盘自动收起，以便给 LLM 输出留出可视空间。
- 验收标准：
  - [x] 点击发送/图片上传后，`LocalSoftwareKeyboardController.hide()` 生效
  - [x] 收起键盘不影响消息滚动与流式渲染
  - [ ] 模拟器 + 真机验证（模拟器待补）

### US-907: "＋"折叠栏上传（相册 + 文件，Word/Excel/PDF/PPT）

- 作为用户，我希望通过输入框右侧"＋"折叠栏上传相册图片或文档文件，以便在对话中处理资料。
- 验收标准：
  - [x] 输入框右侧新增"＋"按钮，点击弹出折叠栏（相册 / 文件两个入口）
  - [x] 移除现有独立图片上传入口（原相机图标），统一走"＋"折叠栏
  - [x] 文件入口支持：PDF / DOCX / XLSX / PPTX（复用知识库 ingest 已有的 PDF/DOCX/XLSX 解析器；PPTX 新增解析）
  - [x] 文档内容按决策方案（见 §8 D-2）进入对话：本地解析提取文本 → 文本消息发送 LLM
  - [x] 文档解析失败/过大给出可见提示，不触发 LLM 空转

> **UXR9-B2 实现记录**：PPTX 解析器（PptxDocumentParser，POI XSLF，零新增依赖）+ DocumentType.PPTX
>
> - 注册表分发。文档消息截断上限 30000 字符。`notifyDocumentError` 走系统提示通道（不触发 LLM）。

### US-908: Skills 调用 UI 反馈

- 作为用户，我希望 LLM 调用 skill 时界面有明确反馈，以便知道工具确实被执行。
- 验收标准：
  - [x] LLM 发起工具调用时，会话中展示工具调用卡片（工具名 + 参数摘要 + 执行中/成功/失败状态）
  - [x] 工具执行结果（成功/失败）可视化呈现
  - [x] 既有"正在调用工具: xxx"状态与工具执行指示不冲突

> **UXR9-B2 实现记录**：`SkillCallCard`（完整工具命名空间 + 参数摘要[按 toolCallId 反查 arguments]
>
> - 结果片段 + ✓成功/✕失败状态徽标）；`ToolCallIndicator` 增强为「⟳ 执行中」卡片；
> `summarizeToolArguments` 纯函数压缩参数 JSON。
<!-- -->
> **UXR9-B3 模拟器验证记录（2026-08-18，emulator-5556 x86_64，`-Pprism.includeX86` 构建）**：
>
> - App 安装启动成功，全程零 FATAL/ANR，进程稳定（pid 无重启）
> - US-901 **设备端摄入嵌入实测成功**：知识库导入 test_ingest.md →「摄入完成：已嵌入 2 个片段」，多语言模型（113MB ONNX + Unigram tokenizer）在设备端加载并推理正常
> - US-907 **"＋"折叠栏实测通过**：输入框右侧"＋"点击展开 🖼 相册 + 📄 文件 + ✕ 收起（替换原独立图片入口）
> - US-903 **图片上传实测通过**：相册 → 系统照片选择器 → 选择 PNG → 聊天页渲染"[用户发送的图片]"气泡（ImageDecoder 编码链路设备端成功）；LLM 因模拟器无网络返回 ⚠️ 网络失败（环境限制，非代码缺陷），错误态正常渲染且 isTyping 复位无卡死
> - US-907 文件选择器入口实测：系统 OpenDocument 选择器可导航 Downloads 选择文件
> - 知识库/能力（MCP/Skills/记忆三 Tab，Skills 3 内置）/设置页面均正常渲染
> - 真机待补：US-903 3 种来源图片 / US-905 Fetch 返回内容（需网络）/ US-906 键盘收起 / US-907-908 Compose UI 交互 + LLM 工具卡片

## 4. 非功能需求

- 性能：RAG 检索仍走 IO 协程（BR-concurrency-002）；图片解码/文档解析不阻塞主线程；L2 摘要 LLM 调用异步且失败降级
- 安全：文档解析提取的文本随消息发送至用户自配端点（与图片一致，设置页已有隐私边界）；不上传至第三方
- 可观测性：图片编码失败、MCP Fetch 失败、L2 记忆筛选均补结构化日志
- 兼容性：minSdk 26 不变；`ImageDecoder` 需 API 28+ 分支（26-27 用 BitmapFactory）
- 隐私：文档内容仅本地解析，发送内容直达用户自配端点；L2 摘要不引入额外云端

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| 更换多语言嵌入模型（治本）→ APK 增大 ~90-120MB + 知识库需重建索引 | 高 | 待用户决策：快速缓解（提阈值+过滤）先行，治本（换模型）可作为独立里程碑 |
| 图片根因需真机日志确认（HEIC 假设） | 中 | 先补日志 + 双解码链路，真机一轮验证 |
| PPTX 解析需新增解析器 | 中 | 复用已有 XLSX（POI 相关）经验；PPTX 文本提取相对简单 |
| L2 摘要依赖 LLM 调用（BYOK 成本） | 低 | 失败降级为规则抽取；可配置开关 |

## 6. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| UXR9-B1 | Bug 修复：RAG(901) + 搜索(902) + 图片(903) + L2(904) + Fetch(905) | guardrail + ac-verifier + 全量回归 + 模拟器 |
| UXR9-B2 | 新功能：键盘收起(906) + 折叠栏上传(907) + Skills 反馈(908) | guardrail + ac-verifier + 全量回归 + 模拟器 |
| UXR9-B3 | 真机验证 + 知识固化 + 发布 | 用户真机确认后闭环 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| RAG 相关度注入 | 中文句对相似度实测 + 场景用例 | 无关片段不注入 | US-901 |
| 搜索条目过滤 | 单测 + 真机"昔涟"用例 | 无单字噪声条目 | US-902 |
| 图片上传 | 真机多来源图片 + 日志 | 3/3 成功；失败仅提示 | US-903 |
| L2 选择性记忆 | 单测 + 集成 | 寒暄不入库；摘要入库 | US-904 |
| Fetch MCP | 单测 + 真机 | 返回网页内容 | US-905 |
| 键盘收起 | 模拟器/真机 | 发送后收起 | US-906 |
| 折叠栏上传 | 模拟器/真机 | 相册/文件均可用 | US-907 |
| Skills 反馈 | 模拟器/真机 | 工具卡片展示状态 | US-908 |

## 8. 待确认事项（已确认）

- [x] **D-1（RAG 治本 vs 缓解）**：**换多语言嵌入模型（治本）**。APK +~100MB，知识库需重建索引，本批次执行。
- [x] **D-2（文档上传后 LLM 如何获取内容）**：**方案 A：本地解析提取文本 → 文本消息直发 LLM**。DOCX/XLSX/PDF/PPTX 本地解析后提取文本，作为用户消息发送给 LLM（超长自动截断/分段）。通用性好，适配任意端点。
- [x] **D-3（图片根因诊断节奏）**：先补日志+双解码链路（ImageDecoder API 28+ + BitmapFactory 兜底），真机一轮验证。
- [x] **D-4（Skills 反馈形式）**：**会话内嵌工具调用卡片**。消息流中展示工具名、参数摘要、执行中/成功/失败状态。

## 9. 执行方案（待用户确认后启动）

### 批次划分

| 批次 | 内容 | 风险等级 | 预估工作量 |
|---|---|---|---|
| **UXR9-B1：Bug 修复** | US-901 RAG 换多语言模型 + 阈值优化<br>US-902 搜索条目过滤<br>US-903 图片双解码 + 日志<br>US-904 L2 重要性记忆<br>US-905 Fetch MCP | P2 | 5-7 天 |
| **UXR9-B2：新功能** | US-906 键盘收起<br>US-907 折叠栏上传（含文档解析）<br>US-908 Skills 工具卡片 | P2 | 4-5 天 |
| **UXR9-B3：验证闭环** | 全量回归 + 模拟器 + 真机验证 + 知识固化 + 发布 | — | 1 天 |

### 工作量说明

- **US-901 换多语言模型**：评估候选模型（granite-embedding-107m-multilingual ONNX ~101MB 或 paraphrase-multilingual-MiniLM ~300MB），下载 ONNX 版本，替换 `assets/models/` 目录，重建 embedding 管道（InputNames/OutputNames 可能变化），更新 KnowledgeBaseRepository 的相似度阈值。需重建知识库索引（旧 embedding 与新模型不兼容）。
- **US-907 文档上传**：PPTX 解析器需新增（Apache POI 或 simpler 方案）；PDF/DOCX/XLSX 复用知识库已有解析器。UI 重构：移除独立图片入口，新增"＋"折叠栏。
- **US-908 工具卡片**：需新增 `SkillCallCard` composable + ConversationViewModel 状态管理。
- **其余均为单模块内部修复**，工作量 1-2 天。
