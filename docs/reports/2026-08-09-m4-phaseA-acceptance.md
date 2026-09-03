# M4 Phase A 基础层验收测试报告

> 从 `docs/templates/reports/acceptance-template.md` 模板结构生成，由 ac-verifier 子 Agent 执行。
> 依 CLAUDE.md 第十一节验收测试与分层验证（含硬性门禁）。

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-M4-PHASEA-ACCEPTANCE-001 |
| 验收日期 | 2026-08-09 |
| 关联 PRD | [prd.json](../../prd.json) US-020（第 291 行）、US-023（第 338 行） |
| 关联 ADR | [ADR-013](../decisions/ADR-013-m4-skills-system-architecture.md)（Proposed）、[ADR-014](../decisions/ADR-014-m4-toolcalling-interface.md)（Proposed） |
| guardrail 报告 | [2026-08-09-m4-phaseA-guardrail.md](2026-08-09-m4-phaseA-guardrail.md)（TKN-M4-PHASEA-GUARDRAIL-001，通过） |
| 影响自检报告 | [2026-08-09-m4-phaseA-impact-selfcheck.md](2026-08-09-m4-phaseA-impact-selfcheck.md) |
| 源码考古报告 | [2026-08-09-m4-skills-archaeology.md](2026-08-09-m4-skills-archaeology.md) |
| 行为规则 | [behavioral-rules.md](../behavioral-rules.md)（BR-naming-001 提议，本次验证转 active） |
| 测试方法 | test-architect skill 分层测试方法论（Phase 1-4） |
| 风险等级 | P2 跨模块（接口契约变更：ChatStreamProvider / StreamEvent / ChatMessage / Role） |

## 0. 上下文重建摘要（CLAUDE.md 第零节）

1. **项目当前阶段**：M4 Skills 系统 Phase A 基础层（US-020 数据模型 + US-023 接口扩展）。前置 M3 RAG 对话集成已完成（US-019，519 测试）。
2. **本次任务目标**：对 Phase A 代码变更执行分层验收测试，逐条验证 US-020（6 条 AC）+ US-023（6 条 AC），确认 guardrail G-01 修复有效，裁定 AC-4 偏离。
3. **文档间矛盾/模糊点**：US-023 AC-4 字面要求「Role.toRequestRole 扩展 TOOL→tool」，但实现为 Fail Fast。此偏离需独立裁定（见 §1.4）。

## 1. 验收标准执行结果

### 1.1 US-020 Skill 数据模型与 Repository（6 条 AC）

| AC | 验收标准 | 验证方法 | 通过标准 | 结果 | 证据 |
|---|---|---|---|---|---|
| US-020-1 | 定义 SkillConfig @Entity（id/name/displayName/source/sourceUri/skillDir/isEnabled/isInstalled/version/dependsOnMcpServers/createdAt/updatedAt），仿 McpServerConfig 模式 | 源码审查 [SkillConfig.kt](../../app/src/main/java/io/prism/data/SkillConfig.kt) | 12 个字段全部定义 + @Entity + @Id + @Convert | ✅ 通过 | `@Entity data class SkillConfig` 含全部 12 字段；`@Id var id: Long = 0`；`@Convert(converter = StringListConverter::class, dbType = String::class) var dependsOnMcpServers: List<String>`；扁平 Long 外键无 @Relation（遵循 ADR-008 5.2） |
| US-020-2 | 定义 SkillSource 常量（LOCAL_BUILTIN/LOCAL_USER/REMOTE） | 源码审查 [SkillConfig.kt:61-65](../../app/src/main/java/io/prism/data/SkillConfig.kt) | 3 个常量定义 | ✅ 通过 | `object SkillSource { const val LOCAL_BUILTIN = "LOCAL_BUILTIN"; const val LOCAL_USER = "LOCAL_USER"; const val REMOTE = "REMOTE" }`。注：用 String 常量而非 enum（G-04 低危，与 McpServerConfig.serverType 一致，避免 ObjectBox @Convert） |
| US-020-3 | 定义 SkillManifest 内存数据类（name/description/version/userInvocable/disableModelInvocation/homepage/os/tools/systemPrompt/maxRounds/body）+ SkillToolDecl | 源码审查 [SkillManifest.kt](../../app/src/main/java/io/prism/skill/SkillManifest.kt) | 11 个字段 + SkillToolDecl 3 字段 | ✅ 通过 | `data class SkillManifest` 含全部 11 字段；`data class SkillToolDecl(val name, val description, val parameters: JsonElement)`；纯内存 data class 不持久化 |
| US-020-4 | SkillRepository CRUD（save/get/getAll/findByName/remove/removeAll/setEnabled）仿 McpServerRepository，dependsOnMcpServers 复用 StringListConverter | 源码审查 [SkillRepository.kt](../../app/src/main/java/io/prism/data/SkillRepository.kt) | 7 个核心方法 + StateFlow | ✅ 通过 | `save`/`get`/`getAll`/`findByName`/`remove`/`removeAll`/`setEnabled` 全部实现 + 额外 `setInstalled`/`getEnabled`；`skills: StateFlow<List<SkillConfig>>` 暴露；`refreshFlows()` 写后刷新；照搬 McpServerRepository 模式（多实例并存，无单激活不变式） |
| US-020-5 | SkillRepository 单元测试通过（CRUD + setEnabled + findByName + removeAll） | 运行 `./gradlew :app:testDebugUnitTest --tests "io.prism.data.SkillRepositoryTest"` | 全部通过 | ✅ 通过 | 12 测试 0 失败 0 跳过（XML: `tests="12" skipped="0" failures="0" errors="0"`）。覆盖：save 分配 id / get 往返 / findByName 匹配+不存在 / getAll 排序 / setEnabled 持久化+StateFlow / setInstalled / getEnabled 过滤 / remove / removeAll / save 更新+时间戳 / dependsOnMcpServers 三元素往返 / emptyList 往返 |
| US-020-6 | Typecheck passes | `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL | ✅ 通过 | BUILD SUCCESSFUL in 10s，25 actionable tasks |

### 1.2 US-023 StreamEvent 与 ChatStreamProvider 接口扩展（6 条 AC）

| AC | 验收标准 | 验证方法 | 通过标准 | 结果 | 证据 |
|---|---|---|---|---|---|
| US-023-1 | StreamEvent 新增 ToolCallStart/ToolCallDelta/ToolCallComplete 三个子类（Provider 中立，arguments 已解析为 Map） | 源码审查 [StreamEvent.kt](../../app/src/main/java/io/prism/network/StreamEvent.kt) | 3 子类 + Provider 中立命名 + arguments Map | ✅ 通过 | `data class ToolCallStart(toolCallId, toolName, index)` / `data class ToolCallDelta(toolCallId, argumentsFragment)` / `data class ToolCallComplete(toolCallId, toolName, arguments: Map<String, Any?>)`。命名不绑定 OpenAI/Anthropic 协议字段 |
| US-023-2 | 定义 ToolDefinition（type+function{name,description,parameters}）+ ToolChoice sealed（Auto/Required/Specific/None） | 源码审查 [ToolDefinition.kt](../../app/src/main/java/io/prism/network/ToolDefinition.kt) | ToolDefinition 嵌套结构 + ToolChoice 4 态 sealed | ✅ 通过 | `data class ToolDefinition(type=TYPE_FUNCTION, function: FunctionDef)` + `data class FunctionDef(name, description, parameters: JsonElement, strict: Boolean?)` + `sealed class ToolChoice { Auto; Required; Specific(name); None }` |
| US-023-3 | ChatStreamProvider.streamChat 新增 tools + toolChoice 可选参数（默认 null 向后兼容） | 源码审查 [ChatStreamProvider.kt:36-43](../../app/src/main/java/io/prism/network/ChatStreamProvider.kt) | 新增参数默认 null | ✅ 通过 | `fun streamChat(config, messages, systemPrompt: String? = null, ragContext: String? = null, tools: List<ToolDefinition>? = null, toolChoice: ToolChoice? = null): Flow<StreamEvent>`。既有调用方（ConversationViewModel.sendMessage）未传 tools/toolChoice，行为不变 |
| US-023-4 | ChatMessage 新增 Role.TOOL + toolCallId + toolName + toolCalls 字段，Role.toRequestRole 扩展 TOOL→tool | 源码审查 [ChatMessage.kt](../../app/src/main/java/io/prism/ui/model/ChatMessage.kt) + [OpenAICompatibleProvider.kt:297-303](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) | 字段定义 + TOOL 角色处理 | ⚠️ 有条件通过 | 见 §1.4 裁定 |
| US-023-5 | 现有调用零改动（向后兼容），全量回归测试通过 | 运行 `./gradlew :app:testDebugUnitTest` | 0 失败 | ✅ 通过 | 556 测试 0 失败 25 跳过（预存性能基线 + 需真实 MCP 服务器集成测试，非回归）。3 fake provider 签名已同步（FakeChatStreamProvider/RecordingChatStreamProvider/MultiRoundRecordingProvider 均新增 tools/toolChoice override 参数） |
| US-023-6 | Typecheck passes | `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL | ✅ 通过 | BUILD SUCCESSFUL in 10s |

### 1.3 验收标准覆盖矩阵汇总

| 用户故事 | AC 总数 | 通过 | 有条件通过 | 不通过 | 无法验证 |
|---|---|---|---|---|---|
| US-020 | 6 | 6 | 0 | 0 | 0 |
| US-023 | 6 | 5 | 1（AC-4） | 0 | 0 |
| **合计** | **12** | **11** | **1** | **0** | **0** |

### 1.4 US-023 AC-4 偏离裁定（Fail Fast vs 字面 TOOL→tool 映射）

**AC-4 原文**：「ChatMessage 新增 Role.TOOL + toolCallId + toolName + toolCalls 字段，Role.toRequestRole 扩展 TOOL→tool」

**实现状态**：

- ✅ ChatMessage 新增 `Role.TOOL`（enum 值）
- ✅ ChatMessage 新增 `toolCallId: String? = null`
- ✅ ChatMessage 新增 `toolName: String? = null`
- ✅ ChatMessage 新增 `toolCalls: List<ToolCallRef> = emptyList()`
- ✅ 新增 `ToolCallRef` data class（id/type/functionName/arguments）
- ⚠️ `Role.toRequestRole()` 对 `Role.TOOL` 采用 **Fail Fast**（抛 `IllegalStateException`）而非字面映射为 `"tool"`

**裁定结论：有条件通过（偏离可接受）**

**裁定理由**（基于 sequential-thinking 多步推理）：

1. **技术必要性**：OpenAI tool 结果消息要求 `role="tool"` + `tool_call_id` 字段**同时存在**。当前 `MessageBody` 仅有 `role: String` + `content: String`（无 `tool_call_id` 字段）。若 Phase A 映射 TOOL→"tool" 但不携带 `tool_call_id`，会产生 OpenAI 拒绝的无效请求（400 错误，错误信息令人困惑）。Fail Fast 给出清晰的本地错误，优于服务端返回的模糊错误。

2. **AC 意图分析**：AC-4 的核心意图是让 `Role.TOOL` 被**显式处理**而非静默错误。Fail Fast 实现确实显式处理了 `Role.TOOL`（抛异常而非静默映射为 "assistant"）。主 Agent 自查发现的原始 bug 正是 `if-else` 二分导致 `Role.TOOL` 静默映射为 "assistant"，修复为 `when` 穷尽 + Fail Fast 后，AC-4 意图「TOOL 被显式处理」已满足。

3. **分阶段决策依据**：ADR-014 5.6 明确记录「完整 TOOL 序列化（含 `tool_call_id` + "tool" 角色映射）属 Phase C US-024 AC-1（"MessageBody 扩展 toolCallId/toolCalls"）」。Phase A 的 `MessageBody` 尚未扩展 `toolCallId` 字段，故无法完成字面映射。

4. **行为影响为零**：Phase A 不产生任何 `Role.TOOL` 消息（Phase D US-026 才回灌工具结果），故此分支为纯安全网，不影响 Phase A 行为。若 Phase A 意外产生 TOOL 消息（编程错误），Fail Fast 会立即暴露问题。

5. **规则符合性**：BR-naming-001（本次提议转 active）明确支持「新值分支必须显式处理（实现或 Fail Fast 抛异常）」。Karpathy Guidelines「显式暴露假设」+ CLAUDE.md 19.4「Fail Fast」均支持此实现。

**附加条件**（必须在后续 Phase 完成）：

- Phase C（US-024 AC-1）必须完成完整 TOOL→"tool" 映射（含 `MessageBody` 扩展 `toolCallId` 字段 + `ToolCallRef` 序列化）
- 届时 `toRequestRole` 的 `Role.TOOL` 分支应改为 `Role.TOOL -> "tool"`，并在 `buildRequestBody` 中注入 `tool_call_id` 字段

**G-05 低危遗留**：异常消息「Role.TOOL 序列化未在 Phase A 实现，将在 Phase C/D (US-024/US-026) 支持」含内部 US 编号。低危（IllegalStateException 是编程错误，不直接展示给终端用户，不含密钥/路径/堆栈）。建议 Phase C 简化为「Role.TOOL 序列化尚未实现」。不阻断。

## 2. 分层测试

### 2.1 静态分析

| 工具 | 命令 | 新告警 | 基线告警 | 结果 |
|---|---|---|---|---|
| Kotlin 编译器（main） | `./gradlew :app:compileDebugKotlin` | 0 | 0（仅预存 deprecation 警告） | ✅ 通过 |
| Kotlin 编译器（test） | `./gradlew :app:compileDebugUnitTestKotlin` | 0 | 0 | ✅ 通过 |
| 硬编码密钥扫描 | PowerShell `Select-String` 正则匹配 8 个新增/修改文件 | 0 | 0 | ✅ 通过 |
| StreamEvent when 穷尽性 | 搜索全部 `when (.*event)` + 逐一核实 | 0 遗漏 | 0 | ✅ 通过（2 处穷尽 when 均已补分支：ConversationViewModel.kt:193 + OpenAICompatibleProviderTest.kt:356） |

**静态分析说明**：项目未配置 detekt/ktlint CI 门禁（guardrail §9 建议 Phase B 引入），本次以编译器 sealed class 穷尽性检查 + 手动正则扫描替代。编译器对 `when (event: StreamEvent)` 的穷尽性检查是最强保障——新增 3 子类后，所有穷尽 when 必须补分支方可编译，已验证通过。

### 2.2 单元测试

| 测试套件 | 用例数 | 通过 | 失败 | 跳过 | 结果 |
|---|---|---|---|---|---|
| SkillRepositoryTest（US-020 AC-5） | 12 | 12 | 0 | 0 | ✅ 通过 |
| OpenAICompatibleProviderTest（US-023 回归） | 全量 | 全量 | 0 | 0 | ✅ 通过 |
| ConversationViewModelTest（US-019 RAG 回归） | 全量 | 全量 | 0 | 0 | ✅ 通过 |
| **全量测试套件** | **556** | **531** | **0** | **25** | ✅ 通过 |

**覆盖率评估**（项目无 JaCoCo 配置，按测试用例覆盖度评估）：

| 模块 | 语句覆盖估计 | 分支覆盖估计 | 评估依据 |
|---|---|---|---|
| SkillRepository | ~95% | ~90% | 12 测试覆盖全部 9 个公共方法（save/get/getAll/findByName/remove/removeAll/setEnabled/setInstalled/getEnabled）+ init refreshFlows + 边界（空列表/不存在 id/更新时间戳/过滤逻辑） |
| SkillConfig | 100% | N/A | 纯数据类，无逻辑分支 |
| SkillManifest / SkillToolDecl | 100% | N/A | 纯数据类，无逻辑分支 |
| ToolDefinition / ToolChoice | 100% | N/A | 纯数据类 + sealed class，无逻辑分支 |
| StreamEvent | 100% | N/A | sealed class 定义，无逻辑分支 |
| ChatMessage / Role / ToolCallRef | ~90% | ~85% | Role.toRequestRole 的 TOOL 分支未测试（private 函数 + Phase A 不执行）。USER/ASSISTANT 分支由 OpenAICompatibleProviderTest buildRequestBody 测试覆盖 |
| OpenAICompatibleProvider（G-01 修复） | ~90% | ~85% | G-01 Log.w 分支（tools != null）未测试，但逻辑简单（单行 Log.w）。既有 buildRequestBody/parseChunkData 测试覆盖完整 |

**SkillRepositoryTest 用例覆盖矩阵**：

| 测试用例 | 技术 | 覆盖场景 |
|---|---|---|
| save_assigns_positive_id | 等价类（正常） | 新建 config 分配正 id |
| get_returns_persisted_config | 等价类（正常） | 全字段往返（name/displayName/source/skillDir/version/dependsOnMcpServers/isEnabled/isInstalled） |
| findByName_returns_matching_config | 等价类（正常+异常） | 精确匹配 + 不存在返回 null |
| getAll_returns_sorted_by_createdAt | 路径覆盖 | createdAt 升序排序 |
| setEnabled_persists_and_updates_flow | 状态迁移 | false→true 持久化 + StateFlow 反映 + updatedAt 刷新 |
| setInstalled_marks_installation_status | 状态迁移 | true→false 标记 |
| getEnabled_returns_only_enabled_and_installed | 决策表 | enabled && installed / enabled && !installed / !enabled && installed 三组合 |
| remove_deletes_config | 等价类（正常） | 删除后 get 返回 null + getAll 为空 |
| removeAll_clears_all | 边界 | 全清后 getAll 空 + StateFlow 空 |
| save_updates_existing_config | 路径覆盖 | 更新现有 config + updatedAt 刷新 |
| dependsOnMcpServers_roundtrips_through_converter | 等价类（正常） | 3 元素 StringListConverter 往返 |
| empty_dependsOnMcpServers_roundtrips | 边界 | emptyList 往返 |

### 2.3 集成测试

Phase A 为数据层 + 接口层，无跨模块集成测试需求。ObjectBox 持久化已由 SkillRepositoryTest 覆盖（使用真实 `MyObjectBox.builder().directory(tempDir).build()` 临时目录 BoxStore，非 Mock，验证真实持久化 + 类型转换器往返）。

**接口契约验证**：

- ChatStreamProvider → OpenAICompatibleProvider：override 签名对齐已通过编译验证
- 3 fake provider（FakeChatStreamProvider / RecordingChatStreamProvider / MultiRoundRecordingProvider）：override 签名同步已通过 ConversationViewModelTest 全量通过验证

### 2.4 E2E 测试

Phase A 无 UI 交互（Phase E US-027 才有 Skills UI），不适用。OpenAICompatibleProvider 的 SSE 流式端到端路径由既有 OpenAICompatibleProviderTest（嵌入式 Ktor Netty SSE 服务器）+ OpenAICompatibleProviderPerformanceBenchmark 覆盖。

## 3. 极端/边缘场景

| 场景 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| 空 dependsOnMcpServers 往返 | SkillRepositoryTest.empty_dependsOnMcpServers_roundtrips | ✅ 通过 | emptyList 经 StringListConverter 序列化/反序列化后仍为 emptyList |
| StringListConverter 换行符转义（BR-data-001） | dependsOnMcpServers_roundtrips + BR-data-001 既有验证 | ✅ 通过 | StringListConverter 复用 US-004 已验证实现（单次扫描转义/反转义），3 元素往返正确 |
| getEnabled 过滤决策表（enabled×installed 四组合） | SkillRepositoryTest.getEnabled_returns_only_enabled_and_installed | ✅ 通过 | 3 组合测试：enabled&&installed 返回 / enabled&&!installed 不返回 / !enabled&&installed 不返回 |
| 不存在 id 的 get/findByName | SkillRepositoryTest.findByName_returns_matching_config | ✅ 通过 | findByName("nonexistent") 返回 null；box.get(不存在id) 返回 null（ObjectBox 行为） |
| removeAll 后 StateFlow 清空 | SkillRepositoryTest.removeAll_clears_all | ✅ 通过 | removeAll 后 skills.value.isEmpty() == true |
| 更新 config 时间戳刷新 | SkillRepositoryTest.save_updates_existing_config | ✅ 通过 | Thread.sleep(5) 后 save，updatedAt > originalUpdatedAt |
| StreamEvent 三新子类型穷尽 when | 编译检查 + OpenAICompatibleProviderTest:356-363 | ✅ 通过 | when (ev) 补 3 空分支（ToolCallStart/Delta/Complete -> {}），编译通过 |
| ToolChoice 四态完整性 | 源码审查 | ✅ 通过 | sealed class 含 Auto/Required/Specific(name)/None 四分支 |
| Role.TOOL 触发 Fail Fast | 源码审查 [OpenAICompatibleProvider.kt:297-303](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) | ✅ 代码正确 | `Role.TOOL -> throw IllegalStateException(...)`。注：无专门单元测试（private 函数，见 §7 未覆盖项） |
| G-01 Log.w 警告（tools 非空时） | 源码审查 [OpenAICompatibleProvider.kt:88-92](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) | ✅ 修复有效 | `if (tools != null) { Log.w(TAG, "tools 非空但 Phase A 未实现 tool_calling 序列化，已忽略（Phase C US-024 实现）") }`。guardrail G-01 修复已应用 |
| BR-naming-001 规则验证（when 穷尽 + Fail Fast） | 源码审查 toRequestRole | ✅ 符合 | `when (this) { Role.USER -> "user"; Role.ASSISTANT -> "assistant"; Role.TOOL -> throw IllegalStateException(...) }`。无 else 兜底，编译器强制覆盖所有分支 |

## 4. 性能回退检查

### 4.1 OpenAICompatibleProvider 流式性能（接口扩展影响评估）

| 指标 | 基线（无独立 US-006 基线文件） | Phase A 实测 | 变化 | 结论 |
|---|---|---|---|---|
| SSE 首字延迟 p50 | 无基线（benchmark 本身为基线） | 5.23 ms | N/A（首次基线） | ✅ 无回退 |
| SSE 首字延迟 p95 | 无基线 | 6.85 ms | N/A | ✅ 无回退 |
| SSE 首字延迟 p99 | 无基线 | 6.87 ms | N/A | ✅ 无回退 |
| SSE 吞吐 p50 | 无基线 | 11459.5 token/s | N/A | ✅ 无回退 |
| SSE 吞吐 p95 | 无基线 | 12787.1 token/s | N/A | ✅ 无回退 |

**性能回退分析**：

Phase A 接口扩展（新增 `tools`/`toolChoice` 参数默认 null）**不改变请求体**：

- `buildRequestBody(config, messages, systemPrompt, ragContext)` 签名未变，输出完全相同（`ChatCompletionRequest(model, messages, stream=true)`，无 tools 字段）
- `parseChunkData(data)` 解析逻辑未变（只解析 `choices[0].delta.content`，不解析 tool_calls）
- streamChat 中新增的 `if (tools != null) Log.w(...)` 仅在非 null 时执行（当前所有调用方传 null），不影响性能

**结论**：接口扩展对流式请求性能零影响。benchmark 2 测试通过（0 失败），验证功能未破坏。

### 4.2 SkillRepository CRUD 性能（初版基线参考）

| 指标 | US-004 ProviderConfig 同构基线 | SkillRepository 预期 | 评估依据 |
|---|---|---|---|
| SAVE p50 | 279.8 us | ~280 us | SkillConfig 与 ProviderConfig 同构（@Entity + @Convert(StringListConverter) + 12 字段），照搬 McpServerRepository 模式 |
| GET p50 | 1.9 us | ~2 us | ObjectBox mmap 读取，id 索引查询 |
| setEnabled p50 | 291.3 us（US-004 setActive 含 runInTx） | ~280 us | setEnabled 为 get-put（无 runInTx），比 setActive 更轻量 |

**说明**：Phase A 无独立 SkillRepository benchmark（避免引入不必要测试代码）。SkillRepository 照搬 McpServerRepository 模式，与 US-004 ProviderConfigRepository 同构，参考 US-004 基线（[2026-08-02-us004-provider-config-baseline.md](perf/2026-08-02-us004-provider-config-baseline.md)）作为同类性能参考。如需精确基线，可在 Phase B SkillRegistry 集成测试时建立。

**性能门禁结论**：无性能回退（接口扩展零影响请求体），SkillRepository 预期同量级（同构模式）。

## 5. 安全检查

### 5.1 注入类检查

| 检查项 | 结果 | 证据 |
|---|---|---|
| SQL/NoSQL 注入 | ✅ 通过 | ObjectBox 是 NoSQL，`findByName` 用 `box.all.find { it.name == name }` 全量扫描匹配，非字符串拼接 query。无注入风险 |
| OS 命令注入 | ✅ 通过 | Phase A 无 system/exec 调用 |
| 代码/表达式注入 | ✅ 通过 | 无 eval/Function/exec |
| YAML 注入 | ✅ 通过 | Phase A 未引入 snakeyaml（Phase B US-021） |

### 5.2 敏感信息泄露检查

| 检查项 | 结果 | 证据 |
|---|---|---|
| 硬编码密钥/密码/token | ✅ 通过 | PowerShell `Select-String` 正则扫描 8 个新增/修改文件，0 匹配 |
| SkillConfig 敏感字段 | ✅ 通过 | SkillConfig 字段均为元数据（name/displayName/source/skillDir/version），无密钥/密码/PII |
| G-01 Log.w 消息审计 | ✅ 通过 | `Log.w(TAG, "tools 非空但 Phase A 未实现 tool_calling 序列化，已忽略（Phase C US-024 实现）")` — TAG 为类名，消息为技术性描述，不含密钥/路径/用户数据/请求体 |
| 异常消息泄露（G-05） | ⚠️ 低危 | `IllegalStateException("Role.TOOL 序列化未在 Phase A 实现，将在 Phase C/D (US-024/US-026) 支持")` 含内部 US 编号。低危：IllegalStateException 是编程错误（非用户输入触发），不直接展示给终端用户，不含密钥/路径/堆栈。建议 Phase C 简化 |
| 日志脱敏（CLAUDE.md 19.3） | ✅ 通过 | 全文件扫描无密钥/密码/令牌/完整 SQL 输出 |

### 5.3 XSS / 前端安全

Phase A 无 HTML/JS/CSS 输出（Android Compose UI 由框架处理转义）。不适用。

### 5.4 权限验证

Phase A 无权限控制逻辑（Phase D 工具执行回路才涉及 ToolConfirmationGate）。不适用。

## 6. 回归测试

| 套件 | 总数 | 通过 | 失败 | 跳过 | 结果 |
|---|---|---|---|---|---|
| 全量 testDebugUnitTest | 556 | 531 | 0 | 25 | ✅ 通过 |

**跳过项说明**（25 个，均为预存非回归）：

- 7 个性能 benchmark（默认跳过，需 `-PignorePerformanceTests=false` 手动运行）——本次手动运行了 OpenAICompatibleProviderPerformanceBenchmark（2 测试通过）
- 18 个需真实 MCP 服务器/嵌入式服务器的集成测试（环境限制）

**回归结论**：0 失败。US-019 RAG 对话集成（ConversationViewModelTest）全部通过，验证接口扩展（StreamEvent 3 新子类 + ChatStreamProvider 签名变更 + ChatMessage Role.TOOL）未破坏 RAG 回路。OpenAICompatibleProviderTest 全部通过，验证 override 签名对齐未破坏流式请求。

## 7. 未覆盖项和风险

| 未覆盖项 | 原因 | 风险评估 | 计划 |
|---|---|---|---|
| Role.TOOL Fail Fast 无专门单元测试 | `toRequestRole` 是 private 函数，需通过 buildRequestBody 间接测试；Phase A 不产生 TOOL 消息（此分支不执行） | 低：Fail Fast 逻辑简单（单行 throw），编译器 when 穷尽性已保证分支存在；Phase A 行为零影响 | Phase C US-024 实现 TOOL 序列化时补测试 |
| SkillRepository 并发写测试（G-02/R-01） | Phase A 无并发调用方（Phase B SkillRegistry.scanAndSync 在 IO 协程串行） | 低：照搬 McpServerRepository 已验证模式；setEnabled/setInstalled 的 get-put 非原子是既有技术债（G-02 低危） | Phase B 集成测试时补充并发 setEnabled 与 save 的 lost update 测试 |
| SkillConfig.name slug 运行时校验（G-03） | Phase B SkillManifestParser.validate 有前置校验；Phase A 无外部调用方 | 低：ObjectBox NoSQL + box.all.find 全量匹配，无注入风险；非法 name 仅导致 display 异常 | Phase B 加 save 入口 require 校验（纵深防御） |
| SkillRepository 独立性能 benchmark | Phase A 无外部调用方；SkillRepository 与 US-004 ProviderConfigRepository 同构 | 低：同构模式性能同量级（参考 US-004 基线 SAVE p50 280us） | Phase B 集成测试时建立精确基线 |
| 真实 Android 设备性能基线 | JVM 测试环境限制（无模拟器） | 中：JVM native 库与设备闪存 I/O 不同；绝对延迟可能不同，相对差异应一致 | 后续 androidTest 仪器测试建立设备基线 |
| ADR-013 设计稿 enum vs 实现 object 不一致（G-04） | 文档一致性问题 | 低：实现选择更简单（避免 @Convert），与 McpServerConfig.serverType 一致 | 主 Agent 在 ADR-013 5.1 备注 |

## 8. 缺陷列表

| ID | 严重度 | 关联 AC | 描述 | 状态 | 证据 |
|---|---|---|---|---|---|
| G-01 | 中危（已修复） | US-023-3 | OpenAICompatibleProvider 静默忽略非 null tools 参数 | ✅ 已修复 | [OpenAICompatibleProvider.kt:88-92](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt)：`if (tools != null) Log.w(TAG, ...)` |
| G-02 | 低危（既有技术债） | US-020-4 | SkillRepository.setEnabled/setInstalled get-put 非原子 | 📋 后续迭代 | 照搬 McpServerRepository 模式，Phase A 无并发调用方 |
| G-03 | 低危（建议） | US-020-4 | SkillConfig.name slug 未在 save 加运行时校验 | 📋 Phase B | Phase B SkillManifestParser.validate 有前置校验 |
| G-04 | 低危（文档） | US-020-2 | ADR-013 设计稿 enum vs 实现 object 不一致 | 📋 文档更新 | 实现用 String 常量，与 McpServerConfig 一致 |
| G-05 | 低危（建议） | US-023-4 | Role.TOOL 异常消息含内部 US 编号 | 📋 Phase C | IllegalStateException 不展示给终端用户 |
| AC-4 偏离 | 低危（分阶段决策） | US-023-4 | Role.toRequestRole 对 TOOL 用 Fail Fast 而非字面映射 | ⚠️ 有条件通过 | Phase C US-024 必须完成完整 TOOL→"tool" 映射 |

**无阻断级缺陷。无高危缺陷。**

## 9. BR-naming-001 规则验证

guardrail 提议将 BR-naming-001 从 `proposed` 转 `active`，需 ac-verifier 确认。

| 验证项 | 结果 | 证据 |
|---|---|---|
| 规则可执行 | ✅ | 明确要求 enum 新增值时 if-else 二分匹配改为 when 穷尽 + 新值 Fail Fast，可操作 |
| 反例准确 | ✅ | `if (this == Role.USER) "user" else "assistant"` 确实导致 Role.TOOL 静默映射为 "assistant" |
| 正例准确 | ✅ | `when (this) { Role.USER -> "user"; Role.ASSISTANT -> "assistant"; Role.TOOL -> throw IllegalStateException(...) }` 是当前实现 |
| 来源准确 | ✅ | M4 Phase A Role.TOOL 静默映射 bug 修复（TKN-M4-PHASEA-GUARDRAIL-001） |
| 无重复 | ✅ | 现有规则中无关于 enum 穷尽匹配的规则 |
| 修复有效 | ✅ | 编译通过（when 穷尽性检查）+ 556 测试 0 失败（无回归） |

**确认：BR-naming-001 proposed → active**。主 Agent 可更新 [behavioral-rules.md](../behavioral-rules.md) 状态字段。

## 10. 结论

- [x] **通过**
- [ ] 不通过（回退至 guardrail-enforcer 阶段）

### 10.1 综合结论

本次 M4 Phase A 基础层验收测试**通过**。

**US-020（6/6 AC 通过）**：SkillConfig @Entity + SkillSource 常量 + SkillManifest/SkillToolDecl 内存模型 + SkillRepository CRUD（仿 McpServerRepository）+ 12 单元测试通过 + Typecheck 通过。

**US-023（5/6 AC 通过 + 1 有条件通过）**：StreamEvent 3 新子类（Provider 中立）+ ToolDefinition/ToolChoice sealed + ChatStreamProvider 接口扩展（默认 null 向后兼容）+ ChatMessage Role.TOOL + 字段扩展 + 556 回归 0 失败 + Typecheck 通过。AC-4 偏离（Fail Fast vs 字面映射）作为 ADR-014 5.6 分阶段决策可接受，Phase C US-024 必须完成完整映射。

**安全门禁**：无阻断级安全漏洞（无注入/密钥/RCE），G-01 Log.w 修复有效，敏感信息泄露检查通过。

**性能门禁**：无性能回退（接口扩展零影响请求体），SkillRepository 预期同量级（同构模式参考 US-004 基线）。

**BR-naming-001**：规则验证通过，proposed → active。

### 10.2 遗留项（不阻断，需后续 Phase 处理）

| 遗留项 | 严重度 | 责任 Phase | 说明 |
|---|---|---|---|
| AC-4 完整 TOOL→"tool" 映射 | 低危 | Phase C（US-024 AC-1） | MessageBody 扩展 toolCallId + ToolCallRef 序列化 + toRequestRole TOOL→"tool" |
| G-02 setEnabled/setInstalled 原子性 | 低危 | 后续迭代 | runInTx 包裹 get-put（含 McpServerRepository 既有技术债） |
| G-03 slug 运行时校验 | 低危 | Phase B | save 入口加 require 校验（纵深防御） |
| G-04 ADR-013 文档一致性 | 低危 | 文档更新 | 备注 String 常量替代 enum |
| G-05 异常消息简化 | 低危 | Phase C | 去 US 编号 |
| BR-naming-001 状态更新 | 信息 | 主 Agent | behavioral-rules.md proposed → active |
| README.md 索引更新 | 信息 | 主 Agent | 闭环后统一更新文档索引 |

### 10.3 主 Agent 自问回应

1. **US-023 AC-4 偏离裁定**：偏离**可接受**（有条件通过）。AC-4 核心意图「TOOL 被显式处理而非静默错误」已满足（when 穷尽 + Fail Fast）。字面映射需 MessageBody 扩展 toolCallId（US-024 AC-1 范围），Phase A 强行映射会产生无效请求。Phase C 必须完成完整映射。

2. **并发测试补充需求**：Phase A 不需要补并发测试（无并发调用方）。Phase B SkillRegistry.scanAndSync 在 IO 协程串行，届时再评估是否需要并发测试。

3. **slug 校验补充需求**：Phase A 不需要在 SkillRepository.save 加运行时校验（Phase B SkillManifestParser.validate 有前置校验，Phase A 无外部调用方）。建议 Phase B 加 require 校验作为纵深防御（G-03）。
