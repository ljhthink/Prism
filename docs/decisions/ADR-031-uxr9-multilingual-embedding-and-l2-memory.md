# ADR-031: UXR9 多语言嵌入模型 + L2 记忆选择性增强

> 实现 UXR9（真机问题修复 + 体验增强）核心架构决策：根治 RAG 无关资料注入（换多语言嵌入模型）与 L2 跨会话记忆选择性记忆。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-18 |
| 决策者 | 主 Agent + 用户确认（D-1：RAG 治本=换多语言模型；D-2：文档上传方案 A 文本直发；D-4：Skills 反馈=会话内嵌工具卡片） |
| 关联文档 | [PRD UXR9](../prd-uxr9.md)、[ADR-007 M3 RAG 技术栈](ADR-007-m3-rag-tech-stack.md)、[ADR-010 向量检索](ADR-010-m3-vector-retrieval.md)、[ADR-015 M5 记忆系统](ADR-015-m5-memory-system-architecture.md)、[ADR-023 UXR3 修复](ADR-023-ux-r3-fixes.md) |
| 风险等级 | P2（跨模块：嵌入引擎 + RAG 检索链路 + 记忆写入链路 + 文档解析） |

## 背景（Context）

2026-08-18 真机手动测试暴露 5 Bug + 3 体验缺失。其中 **RAG 无关资料注入为多轮未愈的老 Bug**（ADR-012→023→UXR8-R3 均未根治）。考古定位结构性根因：端侧嵌入模型是**英文** MiniLM（`nreimers/MiniLM-L6-H384-uncased`，英文 BERT 词表 30522），对中文语义区分度差——无关中文片段余弦相似度普遍 0.4~0.7，任何单一阈值（0.3/0.5）都无法干净分隔相关/无关，调阈值屡修无效。

L2 跨会话记忆同样存在结构缺陷：`saveSessionMemories` 无条件全量入库（寒暄/确认/一次性闲聊也记），检索无阈值过滤，污染新会话上下文。

## 决策（Decision）

### 子决策 A：换多语言嵌入模型（治本 RAG，D-1）

- 生产切换到 **paraphrase-multilingual-MiniLM-L12-v2 qint8**（~113MB ONNX）+ **Unigram tokenizer**（tokenizer.json ~9MB，XLM-R SentencePiece）。
- 新增 [TokenEncoder](../app/src/main/java/io/prism/embedding/TokenEncoder.kt) 抽象接口，`BertWordPieceTokenizer` 与 `UnigramTokenizer` 共用同一协议，`OnnxEmbedder` 与具体 tokenizer 解耦（切换模型不动推理核心）。
- **实测校准阈值**（`ChineseSimilarityDiagnosticTest` 用真实多语言模型测中文句对）：相关 0.582/0.655，无关 -0.067~0.322 → `RAG_SIMILARITY_THRESHOLD` 与 `knowledge_base__search` 阈值均校准为 **0.5**（考古预测 0.62~0.65 基于旧英文模型分布，偏保守）。
- RAG 注入条数上限 top-2（`RAG_MAX_INJECTED` / `MAX_INJECTED`），控制上下文膨胀；citation 编号在截断后构建，保证 [来源N] 与 UI 引用一致。
- **M-1 索引迁移**：旧知识库 chunk 向量与新模型语义空间不兼容，需用户删除并重新导入文档重建索引（发布说明披露；后续迭代加 embeddingModel 版本字段 + 一键重建）。
- **备选否决**：仅调阈值缓解（多轮证明无效）、换更大多语言模型（paraphrase-multilingual-MiniLM 全量 ~300MB 过大）、云端嵌入（违反纯云端 BYOK 定位）。

### 子决策 B：L2 跨会话记忆选择性记忆（Bug 4）

- `isImportantTurnPair` 重要性过滤（纯函数可测）：寒暄/确认/继续整句归一化精确匹配跳过（与 RAG 预判 BR-interface-017 同源）；偏好/身份/任务信号词或实质问题（≥8 字或含疑问词）保留。
- `saveSessionMemories` 注入 `ConversationSummarizer`（复用 L1）做 LLM 摘要（`[摘要] ` 前缀单条记录入库），失败降级为规则抽取（逐对存储重要轮次）。摘要入库再失败 → 落入逐对存储（guardrail M-2 CWE-754 修复，不丢记忆）。
- 检索侧 `retrievalThreshold=0.4`（可构造注入，测试可禁用）；会话隔离天然成立（新会话 sessionId 全新）。
- `filterKeyMessages` 排除 `isSystemNotice`（guardrail M-3 CWE-20，系统提示不流入记忆库）。

### 子决策 C：体验增强（US-906/907/908，D-2/D-4）

- **US-906**：发送后 `LocalSoftwareKeyboardController.hide()` 自动收起键盘。
- **US-907**：输入框右侧"＋"折叠栏（相册/文件两入口，替换原独立图片按钮）。文件上传**方案 A 文本直发**（D-2）：本地解析（PDF/DOCX/XLSX/PPTX/MD/TXT/CSV）提取文本 → 作为用户消息发送 LLM（截断 30000 字符），失败走系统提示通道不触发 LLM。新增 PPTX 解析器（POI XSLF，零新增依赖）。
- **US-908**：`SkillCallCard` 会话内嵌工具卡片（完整工具命名空间 + 参数摘要[按 toolCallId 反查 arguments] + 结果片段 + ✓成功/✕失败状态徽标）；`ToolCallIndicator` 增强为「⟳ 执行中」。

## 后果（Consequences）

**正面**：
- RAG 污染根治：多语言模型相关/无关中文片段干净分隔（实测），阈值 0.5 稳定。
- L2 记忆质量提升：只存有价值内容（偏好/事实/结论），寒暄/系统提示不入库。
- 文档上传/工具反馈体验补全。

**负面/需注意**：
- APK 增大 ~90-120MB（多语言模型 113MB + tokenizer 9MB vs 旧 22MB）。
- 旧知识库索引需重建（M-1，已披露）。
- 首次加载 113MB 模型内存/耗时增加（OnnxEmbedder 按需加载 + 闲置卸载缓解）。
- 图片 base64 随会话 JSON 膨胀（已有技术债，后续降采样存储）。

## 关联修复（不改变设计意图，不单独成 ADR）

US-902 搜索条目过滤 / US-903 图片双解码 + flush 队列 / US-905 Fetch MCP（expectSuccess + URL 校验）——均为 bug 修复（§17.2 豁免）。
