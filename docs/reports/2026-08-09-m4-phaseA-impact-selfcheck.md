# M4 Phase A 变更影响自检报告

> CLAUDE.md 第九节强制产物。Phase A 编码完成后、启动 guardrail-enforcer 前的自检清单。
> 范围：US-020 SkillConfig/Manifest/Repository + US-023 StreamEvent/ChatStreamProvider/ChatMessage 接口扩展。

| 项目 | 内容 |
|---|---|
| 自检 Agent | 主 Agent |
| 自检日期 | 2026-08-09 |
| 关联 ADR | [ADR-013](../decisions/ADR-013-m4-skills-system-architecture.md)、[ADR-014](../decisions/ADR-014-m4-toolcalling-interface.md) |
| 关联用户故事 | US-020、US-023 |
| 风险等级 | P2 跨模块（接口契约变更：ChatStreamProvider / StreamEvent / ChatMessage / Role） |

## 1. 接口/契约变更自问

### 1.1 ChatStreamProvider.streamChat 签名扩展（P2 接口变更）

**变更前**（US-019）：

```kotlin
fun streamChat(
    config: ProviderConfig,
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    ragContext: String? = null
): Flow<StreamEvent>
```

**变更后**（M4 Phase A，ADR-014 5.2）：

```kotlin
fun streamChat(
    config: ProviderConfig,
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    ragContext: String? = null,
    tools: List<ToolDefinition>? = null,
    toolChoice: ToolChoice? = null
): Flow<StreamEvent>
```

**向后兼容性**：新增参数均带默认值 `null`。既有调用方（ConversationViewModel.sendMessage）未传 tools/toolChoice，行为与 US-019 完全一致。

**实现侧影响**：

- `OpenAICompatibleProvider.streamChat` override 签名同步新增 `tools`/`toolChoice`（override 不可带默认值，故为必填参数）。Phase A **仅对齐签名，不序列化 tools 到请求体**——实际 tool_calling 请求序列化与 delta 状态机解析属 Phase C（US-024）。当前非 null tools 会被忽略，已在 KDoc 明确标注。
- 测试侧 3 个 fake provider（FakeChatStreamProvider / RecordingChatStreamProvider / MultiRoundRecordingProvider）override 签名同步更新。

### 1.2 StreamEvent 密封类扩展（P2 契约变更）

新增 3 个子类型（ADR-014 5.1）：`ToolCallStart` / `ToolCallDelta` / `ToolCallComplete`。

**穷尽性影响**：所有 `when (event: StreamEvent)` 表达式必须新增分支方可编译。受影响位置：

- `ConversationViewModel.sendMessage` 的 `stream.collect { when (event) }` → 已新增 no-op 分支（Phase D US-026 接管工具执行回路）
- `OpenAICompatibleProviderTest` 第 356 行 `when (ev)` → 已新增空分支

### 1.3 ChatMessage / Role 扩展（P2 数据结构变更）

- `Role` 枚举新增 `TOOL`（tool_calling 结果回灌角色）
- `ChatMessage` 新增 `toolCallId: String?` / `toolName: String?` / `toolCalls: List<ToolCallRef>` 字段，均带默认值，既有消息零改动
- 新增 `ToolCallRef` data class（assistant 消息携带的 tool_calls 引用，用于下次请求回放）

**向后兼容性**：ChatMessage 仅内存态（不持久化为 ObjectBox 实体，ADR-002 4.6），故无 schema 迁移负担。

### 1.4 新增数据模型（P1 单模块，无契约变更）

- `SkillConfig` @Entity（ObjectBox）+ `SkillSource` 常量对象 → 新增第 5 个实体，ObjectBox schema 版本不变（自动生成 MyObjectBox）
- `SkillManifest` / `SkillToolDecl`（io.prism.skill 包，纯内存 data class）
- `ToolDefinition` / `ToolChoice`（io.prism.network 包，Provider 中立工具定义）
- `SkillRepository`（仿 McpServerRepository 模式，CRUD + StateFlow）

### 1.5 函数签名/通用工具函数变更

无。StringListConverter 复用既有实现（US-004，BR-data-001），未修改。

## 2. 依赖与环境变更检查

**Phase A 未引入新依赖**。snakeyaml-engine-kmp（SKILL.md frontmatter 解析）属 Phase B（US-021），将在 Phase B 启动时通过 tech-selection-researcher 评估后引入并更新 `gradle/libs.versions.toml` + 锁文件。

- `gradle/libs.versions.toml`：未修改 ✅
- `app/build.gradle.kts`：未修改 ✅
- 锁文件：未修改 ✅
- `.env.example` / `Dockerfile`：不适用（Android 项目）✅

## 3. 依赖模块扫描

搜索所有调用本次修改模块的其他范围：

### 3.1 ChatStreamProvider 调用方

| 调用方 | 文件 | 影响类型 | 处理 |
|---|---|---|---|
| ConversationViewModel.sendMessage | [ConversationViewModel.kt](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) | `when` 穷尽性 | 已新增 no-op 分支（Phase D 接管） |
| FakeChatStreamProvider | ConversationViewModelTest.kt | override 签名 | 已同步 |
| RecordingChatStreamProvider | ConversationViewModelTest.kt | override 签名 | 已同步 |
| MultiRoundRecordingProvider | ConversationViewModelTest.kt | override 签名 | 已同步 |

### 3.2 StreamEvent when 消费方

| 消费方 | 文件 | 处理 |
|---|---|---|
| ConversationViewModel | ConversationViewModel.kt:193 | 已新增 3 分支 no-op |
| OpenAICompatibleProviderTest | OpenAICompatibleProviderTest.kt:356 | 已新增 3 分支空 |

### 3.3 SkillConfig / SkillRepository 调用方

Phase A 阶段无外部调用方。Phase B（SkillRegistry）与 Phase D（ConversationViewModel 注入）将消费，届时再扫描。

### 3.4 ChatMessage / Role 调用方

`Role.TOOL` 新增但 Phase A 无生产代码使用（Phase D tool 结果回灌时使用）。既有 `Role.USER`/`ASSISTANT` 消费方零影响。

## 4. 跨模块影响表达

提交信息（Phase A 合并时）将使用 Conventional Commits footer 表达跨模块影响：

```
feat(skills): M4 Phase A 基础层 (US-020 + US-023)

- SkillConfig @Entity + SkillRepository CRUD + StateFlow (US-020)
- SkillManifest / SkillToolDecl 内存模型 (ADR-013 5.1)
- ToolDefinition / ToolChoice Provider 中立工具定义 (ADR-014 5.2)
- StreamEvent 扩展 ToolCallStart/Delta/Complete (ADR-014 5.1)
- ChatStreamProvider.streamChat 扩展 tools/toolChoice 参数 (ADR-014 5.2)
- ChatMessage 扩展 Role.TOOL + toolCallId/toolName/toolCalls (ADR-014 5.6)
- OpenAICompatibleProvider override 签名对齐 (Phase C US-024 实现 tool_calling 序列化)
- ConversationViewModel/OpenAICompatibleProviderTest when 穷尽性修复

Relates-to: m4-skills
Refs: ADR-013, ADR-014
```

无 `BREAKING CHANGE`（所有接口扩展均带默认值，向后兼容）。

## 5. README.md 索引更新

本次新增文档：

- `docs/reports/2026-08-09-m4-phaseA-impact-selfcheck.md`（本文件）
- Phase A guardrail 报告（待生成）
- Phase A acceptance 报告（待生成）

将在 guardrail + ac-verifier 完成后统一更新 README.md 索引（避免中间态索引不一致）。

## 6. 编译与回归验证

- `./gradlew :app:compileDebugKotlin`：BUILD SUCCESSFUL（仅预存 deprecation 警告）✅
- `./gradlew :app:testDebugUnitTest`：**556 测试，0 失败，25 跳过** ✅
  - 跳过项为既有性能基线 / 需真实 MCP 服务器的集成测试（预存，非回归）
  - SkillRepositoryTest 11 测试全部通过 ✅
  - ConversationViewModelTest（US-019 RAG 集成）全部通过 ✅（验证接口扩展未破坏 RAG 回路）
  - OpenAICompatibleProviderTest 全部通过 ✅（验证 override 签名对齐未破坏流式请求）

## 7. 自检结论

| 检查项 | 结果 |
|---|---|
| 1. 接口/契约变更已识别 | ✅ 5 项变更全部列出 |
| 2. 依赖与环境变更 | ✅ Phase A 无新依赖 |
| 3. 依赖模块扫描 | ✅ 6 个调用方全部处理 |
| 4. 跨模块影响表达 | ✅ 提交 footer 已准备 |
| 5. README 索引更新 | ⏳ 闭环后统一更新 |
| 6. 编译通过 | ✅ |
| 7. 回归测试通过 | ✅ 556/0/25 |

**自检通过，可启动 guardrail-enforcer。**

### 主 Agent 自问（CLAUDE.md 7.3）

1. **眼下最没有把握的事情是什么？**
   Phase A 的 OpenAICompatibleProvider 接受了 `tools`/`toolChoice` 参数但未实现序列化。这种「签名对齐但行为未实现」的中间态是否会被 guardrail-enforcer 视为「死参数 / 误导性接口」而阻断？我的判断是：这是分阶段开发的合理中间态，KDoc 已明确标注 Phase C 实现，且当前无调用方传非 null 值。但需 guardrail-enforcer 确认此设计选择可接受。

2. **关于当前情况，最大的遗憾 / 没有意识到什么？**
   - 没有意识到：StreamEvent 新增子类型会触发**测试源码**的 `when` 穷尽性错误（OpenAICompatibleProviderTest.kt:356），首轮编译只发现 main 源码的错误。已在自检中补齐。
   - 潜在盲区：`Role.TOOL` 新增后，`OpenAICompatibleProvider.buildRequestBody` 中 `it.role.toRequestRole()` 是否能正确映射 TOOL 角色？Phase A 未启用 TOOL 角色（Phase D 才用），但若 LLM 历史 message 含 TOOL 角色会怎样？需 guardrail-enforcer 检查 `toRequestRole()` 映射。让我先自查。

让我先自查 `Role.toRequestRole()` 映射，确认 TOOL 角色是否会产生问题：续表见下方。

### 8. 自查发现：Role.TOOL 静默映射 bug（已修复）

**问题**：`Role.toRequestRole()` 原实现 `if (this == Role.USER) "user" else "assistant"`，新增的 `Role.TOOL` 会被静默映射为 `"assistant"`。OpenAI API 要求 tool 结果消息用 `role="tool"` + `tool_call_id` 字段，错误映射会导致请求语义错误（把工具结果当作 AI 回复）。

**根因**：新增 `Role.TOOL` 枚举值时，未同步更新 `toRequestRole()` 的穷尽性匹配（原 `if-else` 二分逻辑无法感知新角色）。

**修复**：改为 `when (this)` 穷尽匹配，对 `Role.TOOL` **Fail Fast** 抛 `IllegalStateException`。Phase A 不产生 TOOL 消息，此分支为纯安全网；Phase C/D 重构 buildRequestBody 时实现完整 TOOL 序列化（含 `tool_call_id`）。

**教训**：枚举新增值时，所有 `if-else` 二分匹配都应改为 `when` 穷尽匹配，让编译器强制覆盖新分支（BR 提议：enum-naming-001 / enum-exhaustive-match）。

## 9. 最终自检结论

| 检查项 | 结果 |
|---|---|
| 1. 接口/契约变更已识别 | ✅ 5 项变更全部列出 |
| 2. 依赖与环境变更 | ✅ Phase A 无新依赖 |
| 3. 依赖模块扫描 | ✅ 6 个调用方全部处理 |
| 4. 跨模块影响表达 | ✅ 提交 footer 已准备 |
| 5. README 索引更新 | ⏳ 闭环后统一更新 |
| 6. 编译通过 | ✅ |
| 7. 回归测试通过 | ✅ 556/0/25 |
| 8. Role.TOOL 静默映射 bug | ✅ 已修复（Fail Fast） |

**自检通过，可启动 guardrail-enforcer。**
