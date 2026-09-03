# ADR-019: Skill 渐进式加载 + 默认 persona（修复提示词污染）

> 从 `docs/templates/adr-template.md` 复制。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-12 |
| 决策者 | 主 Agent + 用户确认 |
| 关联文档 | ADR-013-m4-skills-system-architecture、ADR-014-m4-toolcalling-interface、ADR-015-m5-memory-system-architecture |
| 上游调研 | docs/reports/ 考古报告 TKN-PROMPT-POLLUTION-001；网络搜索（OpenAI/DeepSeek/Anthropic 系统提示词最佳实践 + Agent Skill 渐进式加载） |
| 风险等级 | P2（跨模块，改变所有对话 system prompt 组装） |

## 背景（Context）

用户配置 DeepSeek Provider 后对话可用，但发现 LLM 自称"文本改写助手"（提示词污染），即使用户未主动要求调用任何 MCP/Skills/知识库。

**根因（考古确认）**：

1. **直接原因**：内置 `rewriter` Skill 处于**启用状态**（`isEnabled=true` 跨会话持久化），其 `SKILL.md` 的 `system-prompt` 为"你是灵活的文本改写助手"，经 `mergeSystemPrompt` 无条件合并到 `messages[0]` system 消息，强制 LLM 身份为"改写助手"。
2. **根本设计缺陷**：`mergeSystemPrompt` 默认状态（无 RAG/记忆/Skill）返回 `null`，LLM **无任何 system message 引导**；一旦某 Skill 启用，其完整 `systemPrompt` 全权接管身份，导致"启用即被强制角色"。

**用户诉求**：启用 Skill 后 LLM 应**识别到能力存在**，但**具体是否调用由 AI 按任务类型判断**，或由用户显式发出调用指令。即"按需调用"，而非"启用即强制身份"。

**调研结论**（OpenAI/DeepSeek/Anthropic 2026 最佳实践）：

- 系统提示词应管**长期稳定原则**（角色边界、诚实、输出风格），Skill 管特定任务流程，Resources 管模板细节（Anthropic 甚至砍掉 80% 系统提示词）。
- Skill 采用**渐进式加载（Progressive Disclosure）**：轻量 metadata（name + description + when_to_use）常驻让 LLM 感知，完整 systemPrompt/instructions/tools **按需加载/调用**，避免"启用即全量注入"导致的 Prompt 膨胀与注意力稀释。

## 决策（Decision）

1. **新增默认 persona `DEFAULT_PERSONA`**：综合 DeepSeek/Claude 最佳实践，简洁、管原则（身份、诚实、通用能力、按需使用技能、限制声明），**始终**作为基础身份注入，即使无 RAG/记忆/Skill（`mergeSystemPrompt` 不再返回 `null`）。
2. **Skill 轻量索引化**：`mergeSystemPrompt` 对启用 Skill 改为注入**轻量索引**（`name（description）`，格式 `可用技能（按需使用，不改变你的基础身份）：...`），**不再注入完整 `systemPrompt`**，避免"你是XX助手"身份污染。
3. **保留 tools 机制**：有 tools 的 Skill 继续通过 function calling 按需调用（ADR-014 已有机制）。
4. **纯指令型 Skill**（rewriter/translator/code-reviewer 等，无 tools）：通过索引感知 + LLM 通用能力执行，用户需求匹配时按需使用。

选择理由：渐进式加载是 2026 年 Agent Skill 的主流设计（Claude Code / LangChain Deep Agents / OpenAI Skills），既解决身份污染，又符合"识别但按需调用"的用户诉求，且改动聚焦（集中 `mergeSystemPrompt`）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| A. 仅关闭 rewriter（用户操作） | 改动最小 | 未解决根本缺陷：默认状态仍无 persona，其他 Skill 启用仍会污染身份 |
| B. 保留 skill systemPrompt 但改为"能力说明"措辞 | 保留精细规范 | 只要启用就注入，token 膨胀 + 仍可能影响默认对话行为，未实现"按需" |
| C. 完整渐进式加载（load_skill/unload_skill 元工具） | 最彻底 | 改动大（P3），需新增 skill 激活机制与状态管理，本次聚焦身份污染修复 + 默认 persona 作为第一步 |

## 后果（Consequences）

- 正面后果：
  - 默认状态（无 Skill/RAG/记忆）LLM 有清晰通用身份，行为可预测
  - 启用 Skill 后 LLM 保持 Prism 助手身份，感知能力但按需使用，不再被强制角色
  - 减少 Skill systemPrompt 全量注入带来的 token 膨胀
- 负面后果 / 代价：
  - 纯指令型 Skill（无 tools）的精细规范（如 code-reviewer 输出格式）不再预载，LLM 用通用能力执行，可能不如精细规范精确（渐进式加载的第一步取舍）
  - `mergeSystemPrompt` 返回类型从 `String?` 改为 `String`（非空）
- 需要同步更新的文档或代码：
  - `ConversationViewModel.kt`（DEFAULT_PERSONA + mergeSystemPrompt）
  - 相关单测（PhaseD / MemoryIntegration）
  - 本 ADR + README 索引

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 纯指令型 Skill 功能退化（精细规范丢失） | 中 | 索引含 description（核心能力提示），LLM 通用能力可基本替代；后续可引入完整渐进式加载（load_skill）恢复精细规范 |
| 默认 persona 与 RAG SYSTEM_PROMPT 身份重复（均为"你是 Prism AI 助手"） | 低 | 功能正确（模型合并理解），可接受；后续可精简 RAG prompt 身份声明 |
| 行为改变影响现有对话 | 中 | 回归测试全覆盖 + 用户真机验证 |

## 参考

- [OpenAI: Best practices for prompt engineering](https://help.openai.com/en/articles/6654000-how-to-prompt-the-models)
- [Anthropic: Claude 5 上下文工程新法则](https://claude.com/blog/the-new-rules-of-context-engineering-for-claude-5-generation-models)
- [FlowHunt: How AI Agents Actually Implement Skills](https://www.flowhunt.io/blog/how-ai-agents-inject-skills-into-context/)
- [Agent Skill 渐进式加载](https://juejin.cn/post/7659703165706354698)
- 考古报告：docs/reports/ 下 TKN-PROMPT-POLLUTION-001
