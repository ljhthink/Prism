# ADR-030: UXR8 批次3 新功能（用户规则文件 + LLM 反问 + 文本模型视觉）

> 实现 UXR8 的 3 项新功能（N1-N3）：类 CLAUDE.md 规则文件约束 LLM 输出 / LLM 反问澄清提问 / 纯文本模型视觉能力（方案 A 多模态直传 + 降级）。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-17 |
| 决策者 | 主 Agent + 用户确认（D-6：N2 Phase 1+2 全做；D-7：N3 方案 A） |
| 关联文档 | [PRD UXR8](../prd-uxr8.md)、[ADR-015 M5 记忆系统](ADR-015-m5-memory-system-architecture.md)、[ADR-004 Provider 流式](ADR-004-prism-provider-streaming.md)、[ADR-014 tool_calling](ADR-014-m4-toolcalling-interface.md) |
| 风险等级 | P2（跨模块：配置层 + 工具执行层 + 协议层 + UI 层） |

## 背景（Context）

UXR8 批次3 提出 3 项新功能（docs/prd-uxr8.md 第 4 节），用户已确认全部决策点：

1. **N1（用户规则文件）**：用户需以显式规则约束 LLM 行为（身份/语气/格式/禁忌），
   高于自动记忆（RAG/L1/L2/L3）与通用 persona，但不得凌驾安全限制。
2. **N2（LLM 反问/澄清）**：LLM 面对需求歧义（实体/版本/标准不明确）时，与其反复用
   同义词重试工具直至 maxRounds 硬终止，不如**主动向用户澄清**（Anthropic AskUserQuestion
   模式）。D-6 确认 Phase 1（prompt 注入）+ Phase 2（结构化工具）全做。
3. **N3（文本模型视觉）**：用户发送图片 → 以 OpenAI 兼容 `image_url` 消息直传用户已配置
   端点。支持视觉的模型原生看图；纯文本端点（DeepSeek）返回 400 时降级提示。D-7 确认
   方案 A（多模态直传 + 降级），零新增依赖，方案 B（云端旁路 + OCR）留作后续迭代。

## 决策（Decision）

### 子决策 A：N1 用户规则文件（UserRulesRepository）

- 新建 `UserRulesRepository`（独立 DataStore `prism_user_rules`），与 API Key/思考/记忆/
  RAG/审批 DataStore 隔离。双字段「关于我」+「如何回答」（类 ChatGPT Custom Instructions）。
- 长度上限 `MAX_RULE_LEN=500`（每字段，防 token 膨胀）：仓库层 fail-fast 拒绝（BR-security-005），
  UI 层先截断（纵深防御）。
- `UserRules.toSystemPromptSection()` 纯函数：全空返回 null（调用方跳过注入）；非空输出
  `[用户规则 · 除安全限制外最高优先级] 关于我：… 如何回答：…`。
- `ConversationViewModel.mergeSystemPrompt` 新增可空参数 `userRules`，注入顺序：
  **persona → 用户规则 → RAG → L1 摘要 → L2 跨会话 → L3 画像 → Skill 索引**（ADR-015 决策4
  顺序基础上，用户显式规则置顶，语义对齐 Claude Code 分层记忆）。null/空跳过（向后兼容）。
- 设置页新增「AI 行为偏好」编辑器（PrismSheetHost + PrismField 双字段，运行时即时生效，
  下一轮对话生效）。

### 子决策 B：N2 LLM 反问/澄清（Phase 1 prompt + Phase 2 结构化工具）

**Phase 1（prompt 注入）**：`DEFAULT_PERSONA` 追加第 5 条澄清策略——"当用户需求存在
实体/版本/标准等歧义、且缺失信息会实质改变答案时，先向用户澄清追问（一次一问、给出建议
选项），不要反复用同义词重试搜索"（DiscoBench 四类歧义清单 + OpenAI "materially change
the answer" 措辞防偷懒）。

**Phase 2（结构化工具 `ask_user__ask`）**：

- 新建 `AskUserLocalToolExecutor`（本地工具，无 Android 依赖）：
  - schema 照抄 Anthropic `AskUserQuestion`：`questions[].question/options[].label+description/multiSelect`。
  - `parseQuestions` 校验（纯函数可测）：question 非空；option label 非空；multiSelect 缺省 false；
    截断上限 `QUESTION_MAX_LEN=200 / OPTION_LABEL_MAX_LEN=60 / OPTION_DESC_MAX_LEN=120 /
    MAX_QUESTIONS=3 / MAX_OPTIONS_PER_QUESTION=8`（防 LLM 生成海量问题/选项撑爆 UI）。
  - `execute` 返回**特殊标记前缀** `【需要用户回答】` + AskUserPayload JSON。
- `SkillExecutor.executeLoop`（StopAtTools 语义）：检测结果标记前缀 → 解析载荷 →
  发射 `StreamEvent.AskUser` + `askUserPending=true` + break（中断当前回路，不再请求 LLM
  第 2 轮）。工具结果仍回灌历史（协议一致）。解析失败降级（不发射事件、不中断）。
- `ConversationViewModel`：新增 `pendingAskUser` StateFlow；`handleStreamEvent` 收到
  AskUser 时设置；`buildTools` 注入 `ask_user__ask` 工具定义（实例路径恒注入）。
- UI：`ConversationScreen` 在 pendingAskUser 非空时展示提问卡片（问题 + 选项单选/多选 +
  自由文本 + 提交/跳过）。用户答复作为下一条 user 消息进入下一轮（sendMessage + clearAskUser）；
  跳过则发送跳过消息让 LLM 基于已有信息直接回答。
- **审批模式**：ask_user 是纯 UI 交互工具（无副作用、无外部调用），AUTO 模式免确认直接
  执行；MANUAL 模式下走白名单/用户确认门禁（不新增白名单项，保守）。

### 子决策 C：N3 文本模型视觉（方案 A：多模态直传 + 降级）

- `ChatMessage` 新增可空字段 `imageUrl`（data URL 或公网 URL，默认 null 向后兼容）。
- `OpenAICompatibleProvider.toMessageBody`：user 消息带图时 content 改为 OpenAI 兼容多模态
  数组 `[{"type":"text","text":...},{"type":"image_url","image_url":{"url":...}}]`；
  无图保持字符串（向后兼容）。`MessageBody.content` 类型 `String?` → `JsonElement?`。
- **降级信号**：请求含图 + 端点返回 400 → `mapHttpError` 映射为「当前模型端点不支持图片
  （多模态）。请在 Provider 配置中切换到支持视觉的模型，或移除图片后重发」；无图 400 保持
  通用文案（不误报）。
- **图片处理**（UI 层）：系统图片选择器（GetContent）→ 最长边缩放 1024px + JPEG 质量 80 →
  base64 data URL。零新增依赖（BitmapFactory + Base64）。
- **隐私**：data URL 仅内存持有 + 发送到用户自配端点，不落盘、不入库；设置页明示
  「图片直传你配置的模型端点（无云端旁路）」。
- 用户气泡渲染：data URL 解码为 Bitmap 展示（解码失败降级不渲染）。

**一句话**：用户显式规则注入 systemPrompt 最高优先级层（N1）；`ask_user__ask` 本地工具 +
提问卡片 + StopAtTools 中断回路（N2）；图片以 image_url 直传 + 含图 400 降级提示（N3）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| N1：规则存入既有 DataStore | 改动小 | 与思考/RAG/记忆配置混合，职责不清；独立文件便于单独清理/迁移 |
| N1：规则作为单独 user 消息注入 | 无需改 mergeSystemPrompt | 会污染对话历史（UI 可见、可编辑），与系统层 persona 语义冲突 |
| N2：仅 prompt 注入（Phase 1） | 零工具改动 | 无 UI 交互，LLM 只能"文字追问"无法结构化收集选项；D-6 已确认全做 |
| N2：工具执行不中断回路 | 实现简单 | LLM 会继续用工具空转直至 maxRounds；必须 StopAtTools 语义 |
| N3：图片压缩后存文件再引用 | 请求体小 | 需文件持久化（隐私 + 清理复杂度）；data URL 一次请求足够 |
| N3：方案 B（云端旁路 + OCR） | 纯文本模型也能看图 | 额外视觉 Provider 配置/Key + ML Kit ~10MB；D-7 已确认先做方案 A |

## 后果（Consequences）

- 正面：
  - 用户可声明最高优先级行为约束（N1）；LLM 面对歧义主动澄清而非空转（N2）；
    支持视觉模型原生看图、纯文本端点友好降级（N3）。
  - 完全向后兼容：`userRules`/`imageUrl`/`askUserExecutor` 均默认 null/空，既有回归不破坏。
  - 零新增第三方依赖（N2 本地工具 + N3 原生图片处理）。
- 负面 / 代价：
  - 用户规则最长 500 字符（长规则会被拒绝，需用户精简）；
  - ask_user 打断当前回答流程（用户需显式提交或跳过，多一次交互）；
  - data URL 单张图经 1024px + q80 压缩后约 200-500KB，超长对话历史 JSON 可能膨胀
    （会话持久化含 imageUrl 字段，历史回放会重复携带 base64；当前接受，后续可考虑历史
    存储降采样）。
- 需要同步更新的文档或代码：`docs/prd-uxr8.md` 批次3 状态、`AGENTS.md` 用户故事、
  ADR 索引（docs/decisions/README.md）。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| ask_user 选项来自 LLM 生成（注入/撑爆 UI） | 中 | 长度截断 + 数量上限 + 选项白名单结构校验（PRD UXR8 §5） |
| 图片 base64 撑爆请求体 / 历史 JSON | 中 | 客户端 1024px + q80 压缩；data URL 仅内存持有 |
| 用户规则超长拖垮每轮请求 | 低 | MAX_RULE_LEN=500 fail-fast + UI 截断 |
| 纯文本端点收到多模态数组 400 误报 | 低 | 仅「含图 + 400」映射视觉降级文案，无图 400 保持通用文案 |
| 并行 Edit 写竞争导致文件状态不一致 | 低（流程） | 同一文件多处修改必须串行单条 Edit（批次3 已踩坑并修复） |

## 参考

- [PRD UXR8](../prd-uxr8.md)
- [ADR-015 M5 三层记忆（systemPrompt 六层合并）](ADR-015-m5-memory-system-architecture.md)
- [ADR-014 M4 tool_calling 接口](ADR-014-m4-toolcalling-interface.md)
- [ADR-004 Provider 流式请求](ADR-004-prism-provider-streaming.md)
