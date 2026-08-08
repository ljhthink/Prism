# M4 Phase A 基础层安全与质量审计报告

> 从 `docs/templates/reports/guardrail-template.md` 模板结构生成，由 guardrail-enforcer 子 Agent 执行。
> 依 CLAUDE.md 第七节 7.2、第十节强制闭环。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-M4-PHASEA-GUARDRAIL-001 |
| 审计日期 | 2026-08-09 |
| 关联 ADR | [ADR-013](../decisions/ADR-013-m4-skills-system-architecture.md)（Proposed）、[ADR-014](../decisions/ADR-014-m4-toolcalling-interface.md)（Proposed） |
| 关联代码变更 | SkillConfig.kt / SkillRepository.kt / ToolDefinition.kt / SkillManifest.kt（新增）；StreamEvent.kt / ChatStreamProvider.kt / OpenAICompatibleProvider.kt / ChatMessage.kt / ConversationViewModel.kt + 对应测试（修改） |
| 关联上游产出 | [影响自检报告](2026-08-09-m4-phaseA-impact-selfcheck.md)、[源码考古报告](2026-08-09-m4-skills-archaeology.md)、[技术选型报告](2026-08-09-m4-toolcalling-tech-selection.md)、[behavioral-rules.md](../behavioral-rules.md) |
| 风险等级 | P2 跨模块（接口契约变更：ChatStreamProvider / StreamEvent / ChatMessage / Role） |
| 审查方法 | sequential-thinking 6 判断点 + TRAE-code-review + TRAE-security-review（三遍审计） + 独立搜索验证 |
| 验证状态 | `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL；`./gradlew :app:testDebugUnitTest` 556 测试 0 失败 25 跳过；SkillRepositoryTest 11 测试通过 |

## 0. 审查范围与方法

### 0.1 审查范围

**新增文件**（untracked）：

- [SkillConfig.kt](../../app/src/main/java/io/prism/data/SkillConfig.kt) —— SkillConfig @Entity + SkillSource 常量
- [SkillRepository.kt](../../app/src/main/java/io/prism/data/SkillRepository.kt) —— CRUD + StateFlow
- [ToolDefinition.kt](../../app/src/main/java/io/prism/network/ToolDefinition.kt) —— Provider 中立 ToolDefinition + ToolChoice
- [SkillManifest.kt](../../app/src/main/java/io/prism/skill/SkillManifest.kt) —— 内存层 SkillManifest + SkillToolDecl
- [SkillRepositoryTest.kt](../../app/src/test/java/io/prism/data/SkillRepositoryTest.kt) —— 11 个单元测试

**修改文件**（tracked，git diff HEAD）：

- [StreamEvent.kt](../../app/src/main/java/io/prism/network/StreamEvent.kt) —— 新增 ToolCallStart/Delta/Complete 3 个密封子类
- [ChatStreamProvider.kt](../../app/src/main/java/io/prism/network/ChatStreamProvider.kt) —— streamChat 扩展 tools/toolChoice 参数（默认 null）
- [OpenAICompatibleProvider.kt](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) —— override 签名对齐（Phase A 不序列化）；Role.toRequestRole 改 when 穷尽 + Role.TOOL Fail Fast
- [ChatMessage.kt](../../app/src/main/java/io/prism/ui/model/ChatMessage.kt) —— 新增 Role.TOOL + ToolCallRef + toolCallId/toolName/toolCalls 字段
- [ConversationViewModel.kt](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) —— when 增 no-op 分支
- OpenAICompatibleProviderTest.kt / ConversationViewModelTest.kt —— when 穷尽性 + fake provider 签名同步

### 0.2 审查方法

依 CLAUDE.md 第十节，按顺序执行：

1. **代码质量审查**：调用 TRAE-code-review skill（Karpathy Guidelines、逻辑/性能/可维护性、跨模块影响、测试充分性）
2. **安全漏洞扫描**：调用 TRAE-security-review skill（OWASP Top 10、CWE，三遍审计：项目安全基线 → 偏差映射 → 源到汇追踪）
3. **辅助推理**：sequential-thinking MCP 梳理 6 个关键判断点
4. **独立验证**：搜索 StreamEvent 所有 when 消费点 + 核对 McpServerRepository 事务模式对照基准

## 1. 代码质量审查（TRAE-code-review）

### 1.1 Karpathy Guidelines 符合性

| 原则 | 评估 | 证据 |
|---|---|---|
| 显式暴露假设 | 良好，但有改进点 | OpenAICompatibleProvider KDoc 明确标注「Phase A 仅对齐签名，不序列化」；Role.TOOL Fail Fast 显式暴露未实现假设。但 tools 非空时静默忽略而非显式失败，见 G-01 |
| Surgical changes | 优秀 | 所有接口扩展均带默认值（null/emptyList），既有调用零改动；when 新增 no-op 分支最小化影响 |
| 命名 | 优秀 | ToolCallStart/Delta/Complete 命名 Provider 中立，不绑定 OpenAI/Anthropic；SkillSource 常量语义清晰 |
| 错误处理 | 良好 | Role.TOOL Fail Fast 符合 CLAUDE.md 19.4；SkillRepository 无空 catch；异常消息含 US 编号见 G-05 |
| 可验证成功标准 | 良好 | SkillRepositoryTest 11 测试覆盖 CRUD + 边界（空列表、安装状态、时间戳刷新） |

### 1.2 逻辑错误 / 性能隐患 / 可维护性

**无逻辑错误**。Role.TOOL 静默映射 bug 已由主 Agent 自查发现并修复（if-else → when 穷尽 + Fail Fast），修复正确。

**性能**：SkillRepository.refreshFlows 每次写操作后全量 `box.all.sortedBy`，与 McpServerRepository 一致（既有模式，Phase A 数据量小无瓶颈）。StreamEvent 新增 3 子类为纯数据类，无性能影响。

**可维护性**：双层模型（SkillConfig 持久化 / SkillManifest 内存）分层清晰；ToolDefinition 采用 OpenAI 嵌套结构，KDoc 标注未来 Anthropic 适配时在 Provider 内部转换。

### 1.3 跨模块影响识别

主 Agent 影响自检报告列出 6 个调用方，经独立核实：

| 调用方 | 影响类型 | 处理状态 |
|---|---|---|
| ConversationViewModel.sendMessage | when 穷尽性 | 已新增 3 no-op 分支 |
| OpenAICompatibleProviderTest:357 | when 穷尽性 | 已新增 3 空分支 |
| FakeChatStreamProvider / RecordingChatStreamProvider / MultiRoundRecordingProvider | override 签名 | 已同步 |

**独立搜索验证**（判断点 4）：用 `Select-String` 搜索全部 `is StreamEvent.` 引用，发现 OpenAICompatibleProviderPerformanceBenchmark.kt:91,130 与 OpenAICompatibleProviderTest.kt 多处（225/226/314/509/550/578/614）也引用 StreamEvent。经逐一核实，这些均为 `filterIsInstance<StreamEvent.Delta>` / `events.any { it is StreamEvent.Done }` / `if (ev is StreamEvent.Delta)` 等**非穷尽 when 判断**（只关心特定子类型），不触发 sealed class 穷尽性检查，不构成遗漏。真正的穷尽 `when (event: StreamEvent)` 仅 2 处，均已处理。**主 Agent 自检结论准确**。

### 1.4 测试框架与基础用例充分性

SkillRepositoryTest 11 测试覆盖：

- CRUD 基础：save 分配正 id、get 取回、findByName、getAll 排序、remove、removeAll
- 状态变更：setEnabled 持久化 + StateFlow 反映、setInstalled 标记
- 过滤：getEnabled 仅返回 enabled && installed
- 边界：空 dependsOnMcpServers 往返、更新现有 config 时间戳刷新
- 类型转换：dependsOnMcpServers 三元素往返

**充分性评估**：覆盖了 CRUD 主路径 + 边界 + 类型转换。未覆盖项（Phase A 可接受）：并发写（Phase B 才有多协程调用）、超长 name、null name（Kotlin 非空类型编译期保证）。建议 ac-verifier 补充：slug 非法格式拒绝测试（若采纳 G-03 建议）。

### 1.5 「签名对齐但行为未实现」中间态设计评估（主 Agent 自问 1）

**评估对象**：OpenAICompatibleProvider.streamChat override 新增 tools/toolChoice 参数，Phase A 不序列化到请求体。

**结论**：此中间态设计**可接受**（KDoc 已明确标注 + ADR-014 已记录分阶段计划 + 当前无调用方传非 null + 编译/测试全通过），但存在改进点见 G-01。不构成阻断。

**主 Agent 自问 1 回应**：分阶段开发合理中间态判断正确。建议在 Phase C（US-024）实现前，于 streamChat 入口添加可观测警告（tools 非空时 Log.w），避免 Phase B/D 误传非 null tools 时静默成功。详见 G-01。

### 1.6 StreamEvent / ChatMessage 向后兼容性

**StreamEvent**：新增 3 子类为 sealed class 新分支，现有 Delta/Done/Error 语义不变。所有穷尽 when 已补分支，编译通过。向后兼容无损。

**ChatMessage**：新增 toolCallId/toolName/toolCalls 字段均带默认值（null/emptyList），既有消息零改动。Role.TOOL 新增但 Phase A 无生产代码使用。ChatMessage 仅内存态（不持久化为 ObjectBox 实体，ADR-002 4.6），无 schema 迁移负担。向后兼容无损。

### 1.7 SkillConfig 实体设计

- 扁平 Long 外键模式（不引入 @Relation，遵循 ADR-008 5.2 + 考古 R-9）：正确
- StringListConverter 复用（dependsOnMcpServers，BR-data-001 已验证转义）：正确
- updatedAt 自动刷新（save/setEnabled/setInstalled 均刷新）：正确
- isEnabled 落库（修复考古 R-6 RagTarget 仅内存态教训）：正确
- source 用 String 常量而非 enum（与 ADR-013 设计稿不一致，见 G-04）：可接受但建议 ADR 备注

## 2. 安全漏洞扫描（TRAE-security-review）

### 2.1 输入与边界审计

**SkillConfig.name slug 格式**（主 Agent 自问 2）：

- 现状：SkillConfig.name 注释要求 slug（`^[a-z0-9-]{1,64}$`），但 SkillRepository.save 未做运行时校验。Phase B SkillManifestParser.validate 会在解析时校验。
- 安全评估：ObjectBox 是 NoSQL，findByName 用 `box.all.find { it.name == name }` 全量扫描匹配，非字符串拼接 query，**无 SQL/NoSQL 注入风险**。name 含特殊字符不会导致注入，但可能导致 display 异常。
- 结论：按 TRAE-security-review §8.1「Missing hardening 不算漏洞」+ 置信度 < 0.80，**不构成安全漏洞**。作为纵深防御建议见 G-03。

**SkillRepository 各方法边界**：

- get(id): Long → box.get(id)，ObjectBox 处理不存在 id 返回 null，安全
- findByName(name): String → box.all.find，空字符串/超长字符串无注入风险（全量扫描）
- remove(id): Long → box.remove(id)，安全
- setEnabled/setInstalled(id, ...): get-then-put，非原子见 G-02（质量非安全）

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

**注入防护**：

- SQL/NoSQL 注入：ObjectBox NoSQL，无字符串拼接 query，**无风险**
- OS 命令注入：Phase A 无 system/exec 调用，**无风险**
- 代码/表达式注入：无 eval/Function/exec，**无风险**
- 模板引擎注入：Phase A 无模板引擎，**无风险**
- YAML 注入：Phase A 未引入 snakeyaml（Phase B US-021），**无风险**

**Role.TOOL Fail Fast**（主 Agent 自问）：

- toRequestRole 对 Role.TOOL 抛 IllegalStateException，避免静默映射为 "assistant" 导致请求语义错误。Fail Fast 策略恰当，符合 CLAUDE.md 19.4。
- 异常消息含内部 US 编号见 G-05（低危，非安全漏洞——IllegalStateException 是编程错误，不暴露给终端用户，不含密钥/路径/堆栈）。

**OpenAICompatibleProvider 接受 tools 但忽略**：

- 安全评估：这不是安全漏洞（不导致注入/RCE/数据泄露），而是功能缺陷（静默降级）。按 TRAE-security-review §8.1「Missing hardening 不算漏洞」。质量维度见 G-01。
- 不会构成「静默降级安全风险」：Phase A 不产生 ToolCall 事件，tools 非空时忽略不影响安全边界（不泄露信息、不提权）。

**最小权限**：Phase A 无权限控制逻辑（Phase D 工具执行回路才涉及 ToolConfirmationGate）。

**输出编码**：Phase A 无 HTML/JS/CSS/URL 输出（Android Compose UI 由框架处理转义）。

### 2.3 密钥与配置安全

- 扫描所有新增/修改文件：**无硬编码密钥、密码、token、API key、内部 IP/域名**
- SkillConfig 不含敏感字段（name/displayName/skillDir/source/version 均为元数据）
- SkillRepository 不处理密钥
- ToolDefinition/SkillManifest/StreamEvent/ChatMessage 均无敏感字段
- `.gitignore` 已含 `.env`、ObjectBox JNI 本地库等（BR-build-004，预存）
- **通过**

### 2.4 依赖与供应链风险

- Phase A **未引入新依赖**（snakeyaml-engine-kmp 属 Phase B US-021）
- `gradle/libs.versions.toml`：未修改
- `app/build.gradle.kts`：未修改
- 锁文件：未修改
- 无隐藏依赖引入（import 语句均为既有 Kotlin/kotlinx.serialization/ObjectBox）
- **通过**

## 3. 详细发现（分级）

### 阻断级（Blocking）

无。本次 Phase A 代码变更**无阻断级安全漏洞**（无 SQL 注入、无硬编码密钥、无命令注入、无 eval/RCE、无不安全反序列化）。

### 中危（Medium）

#### G-01: OpenAICompatibleProvider 静默忽略非 null tools 参数

- **位置**：[OpenAICompatibleProvider.kt:75-83](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt)（streamChat override）
- **问题**：override streamChat 接受 `tools: List<ToolDefinition>?` / `toolChoice: ToolChoice?` 参数，但 Phase A 不序列化到请求体。非 null tools 会被**静默忽略**，LLM 收不到工具定义，但调用方不得到任何错误信号。违反 Karpathy「显式暴露假设」+ CLAUDE.md 19.4 Fail Fast。
- **证据**：diff KDoc 标注「Phase A 仅对齐签名，不序列化 tools」。当前无调用方传非 null（ConversationViewModel.sendMessage 用默认 null）。ADR-014 已记录 Phase C US-024 实现。
- **风险**：若 Phase B（SkillRegistry）或 Phase D（ConversationViewModel 工具执行回路）在 Phase C 实现前误传非 null tools，会得到**静默成功**（不报错但不生效），构成误导性接口，难以排查。
- **严重度**：中危（质量缺陷，非安全漏洞；Phase A 范围内无实际影响）
- **建议**：在 streamChat 入口添加可观测警告，使非 null tools 不再静默：

```kotlin
override fun streamChat(
    config: ProviderConfig,
    messages: List<ChatMessage>,
    systemPrompt: String?,
    ragContext: String?,
    tools: List<ToolDefinition>?,
    toolChoice: ToolChoice?
): Flow<StreamEvent> = flow {
    // Phase A 防御：tools 非空但序列化未实现，记录警告避免静默降级（Phase C US-024 实现）
    if (tools != null) {
        android.util.Log.w("OpenAICompatibleProvider",
            "tools 非空但 Phase A 未实现序列化，将被忽略（Phase C US-024 实现）")
    }
    // ... 既有逻辑
}
```

- **主 Agent 自问 1 回应**：中间态设计可接受，但强建议加此警告。若不修复，需在 PR 描述中明确记录此中间态风险。

### 低危（Low）

#### G-02: SkillRepository.setEnabled/setInstalled get-put 非原子（既有技术债）

- **位置**：[SkillRepository.kt:93-115](../../app/src/main/java/io/prism/data/SkillRepository.kt)
- **问题**：`setEnabled`/`setInstalled` 实现 `box.get(id) → 修改字段 → box.put(config)`，非原子读改写。并发场景下存在 lost update 风险（与 save 更新其他字段并发时，setEnabled 基于过期 config 覆盖 save 的字段）。
- **证据**：照搬 [McpServerRepository.kt:94-100](../../app/src/main/java/io/prism/data/McpServerRepository.kt)（同构模式，无 runInTx，已通过 US-008 验收）。BR-concurrency-001 针对多记录不变式（单记录 get-put 不直接违反）；BR-concurrency-004 针对 StateFlow RMW（refreshFlows 是全量读不依赖当前值，不违反）。
- **风险**：理论 lost update，但 Phase A 无并发调用方（Phase B SkillRegistry.scanAndSync 在 IO 协程串行）。
- **建议**：未来统一用 `boxStore.runInTx { }` 包裹 get-put 序列（同时适用于 McpServerRepository 既有技术债）。非阻断，可在后续迭代处理。

#### G-03: SkillConfig.name slug 未在 SkillRepository.save 加运行时校验

- **位置**：[SkillRepository.kt:41-46](../../app/src/main/java/io/prism/data/SkillRepository.kt)（save）+ [SkillConfig.kt:16](../../app/src/main/java/io/prism/data/SkillConfig.kt)（注释要求 slug）
- **问题**：SkillConfig.name 注释要求 slug 格式（`^[a-z0-9-]{1,64}$`），但 save 未校验。Phase B SkillManifestParser.validate 有前置校验，但 SkillRepository.save 是直接入库入口，纵深防御不足。
- **证据**：ADR-013 5.2 SkillManifestParser.validate 在 Phase B 解析时校验。Phase A 无外部调用方。ObjectBox NoSQL + box.all.find 全量匹配，无注入风险。
- **风险**：若未来 UI 直接调 save 传入非法 name（含空格/大写/换行），不会导致注入，但可能导致 display 异常或日志可读性问题。
- **建议**：在 save 入口加防御性校验（纵深防御）：

```kotlin
fun save(config: SkillConfig): Long {
    require(config.name.matches(Regex("^[a-z0-9-]{1,64}$"))) {
        "SkillConfig.name must be slug format (1-64 lowercase letters, digits, or hyphens)"
    }
    config.updatedAt = System.currentTimeMillis()
    val id = box.put(config)
    refreshFlows()
    return id
}
```

- **主 Agent 自问 2 回应**：建议加，作为纵深防御，但非阻断（Phase B 有前置校验，Phase A 无外部调用方）。

#### G-04: ADR-013 设计稿 enum class SkillSource vs 实现 object SkillSource 不一致

- **位置**：[ADR-013 5.1](../decisions/ADR-013-m4-skills-system-architecture.md) 代码示例 `enum class SkillSource { LOCAL_BUILTIN, LOCAL_USER, REMOTE }` vs [SkillConfig.kt:61-65](../../app/src/main/java/io/prism/data/SkillConfig.kt) `object SkillSource { const val LOCAL_BUILTIN = "LOCAL_BUILTIN" ... }`
- **问题**：设计稿用 enum，实现用 String 常量。实现选择更简单（避免 ObjectBox @Convert enum 转换器，与 McpServerConfig.serverType 一致），但与 ADR 设计稿不一致。
- **建议**：在 ADR-013 5.1 备注「实现用 String 常量替代 enum 以避免 ObjectBox @Convert，与 McpServerConfig.serverType 一致」。非阻断（文档一致性问题）。

#### G-05: Role.TOOL Fail Fast 异常消息含内部 US 编号

- **位置**：[OpenAICompatibleProvider.kt:289-292](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt)
- **问题**：IllegalStateException 消息「Role.TOOL 序列化未在 Phase A 实现，将在 Phase C/D (US-024/US-026) 支持」含内部 US 编号，属实现细节泄露。
- **风险**：低。IllegalStateException 是编程错误（非用户输入触发），不会被直接展示给终端用户（会被 catch 兜底或日志）。不含密钥/路径/堆栈。US 编号非 PII。按 TRAE-security-review §8.4 不构成安全漏洞。
- **建议**：消息简化为「Role.TOOL 序列化尚未实现」。非阻断。

### 低危/建议（Recommendation）

#### R-01: SkillRepositoryTest 未覆盖并发写场景

- Phase A 无并发调用方，Phase B 才有多协程调用。建议 ac-verifier 在 Phase B 集成测试时补充并发 setEnabled 与 save 的 lost update 测试（若采纳 G-02 runInTx 建议）。

#### R-02: SkillConfig 无 equals/hashCode 数组字段问题

- SkillConfig 无 FloatArray/IntArray 字段（dependsOnMcpServers 是 List<String>，data class 自动 equals 对 List 用内容比较，正确）。BR-security-001 不适用。记录确认无问题。

## 4. OWASP / CWE 发现

| 编号 | 等级 | CWE | 位置 | 说明 | 修复建议 |
|---|---|---|---|---|---|
| - | - | - | - | 按 TRAE-security-review 三遍审计（项目基线 → 偏差映射 → 源到汇追踪），置信度 ≥ 0.80 的可利用漏洞：**无** | - |

**审计覆盖的 CWE 类别**（均未发现可利用问题）：

- CWE-89 SQL 注入：ObjectBox NoSQL 无字符串拼接 query
- CWE-78 OS 命令注入：Phase A 无 system/exec
- CWE-94 代码注入：无 eval/Function/exec
- CWE-79 XSS：Android Compose 框架处理转义
- CWE-200 信息暴露：异常消息含 US 编号（G-05，低危，非 CWE-200 可利用级别）
- CWE-732 不安全权限：Phase A 无权限逻辑
- CWE-319 明文传输敏感信息：无密钥处理
- CWE-502 不安全反序列化：Phase A 无反序列化（Phase B YAML 沙箱解析待审）
- CWE-918 SSRF：Phase A 无 URL 处理（Phase E 远程下载待审）

## 5. 保护机制验证

| 保护机制 | 状态 | 证据 |
|---|---|---|
| sealed class 穷尽性 | 有效 | StreamEvent 新增 3 子类后，所有穷尽 when 编译通过（2 处已补分支） |
| Fail Fast（CLAUDE.md 19.4） | 有效 | Role.TOOL 抛 IllegalStateException，不静默映射 |
| 向后兼容（默认值） | 有效 | tools/toolChoice/toolCallId/toolName/toolCalls 均默认 null/emptyList |
| ObjectBox 事务（BR-concurrency-001） | N/A | SkillRepository 无多记录不变式（多 Skill 并存启用，无单激活） |
| StateFlow 原子 CAS（BR-concurrency-004） | N/A | refreshFlows 全量读赋值，非 RMW，不违反 |
| StringListConverter 转义（BR-data-001） | 复用 | dependsOnMcpServers 复用已验证转换器 |
| 扁平 Long 外键（ADR-008 5.2） | 遵循 | SkillConfig 无 @Relation |
| 无硬编码密钥（CLAUDE.md 20.3） | 通过 | 全文件扫描无密钥 |

## 6. 修复建议汇总

| 编号 | 等级 | 建议动作 | 时机 |
|---|---|---|---|
| G-01 | 中危 | streamChat 入口加 tools 非空警告（Log.w） | **强建议**进入 ac-verifier 前修复；若不修复需 PR 说明 |
| G-02 | 低危 | setEnabled/setInstalled 用 runInTx 包裹 | 后续迭代（含 McpServerRepository 既有技术债） |
| G-03 | 低危 | save 入口加 slug 格式 require 校验 | 建议修复（纵深防御） |
| G-04 | 低危 | ADR-013 备注 String 常量替代 enum | 文档更新 |
| G-05 | 低危 | 异常消息简化，去 US 编号 | 建议修复 |
| R-01 | 建议 | ac-verifier 补充并发写测试 | Phase B 集成测试 |

## 7. 规则提议（accepted review → behavioral-rules）

依 CLAUDE.md 23.3，将本次审查接受的 review comment 转为规则提议，追加到 [behavioral-rules.md](../behavioral-rules.md)。

### BR-naming-001（提议）: enum 新增值时所有 if-else 二分匹配必须改为 when 穷尽 + 新值 Fail Fast

- **类别**：naming / error-handling
- **规则**：Kotlin enum 新增枚举值时，所有对该 enum 的 `if-else` 二分匹配（如 `if (this == X) ... else ...`）必须改为 `when (this)` 穷尽匹配，且新值分支必须显式处理（实现或 Fail Fast 抛异常），让编译器强制覆盖新分支。禁止用 `else` 兜底掩盖新值未处理。这样新增枚举值时编译器会立即在所有消费点报错，避免静默映射到错误分支。
- **反例**：`fun Role.toRequestRole() = if (this == Role.USER) "user" else "assistant"` —— 新增 Role.TOOL 静默映射为 "assistant"，请求语义错误
- **正例**：`fun Role.toRequestRole() = when (this) { Role.USER -> "user"; Role.ASSISTANT -> "assistant"; Role.TOOL -> throw IllegalStateException("Role.TOOL 序列化尚未实现") }`
- **来源**：M4 Phase A Role.TOOL 静默映射 bug 修复（TKN-M4-PHASEA-GUARDRAIL-001，主 Agent 自查发现 + guardrail 确认修复正确）
- **添加日期**：2026-08-09
- **适用场景**：dev
- **状态**：proposed（待主 Agent 确认 + ac-verifier 验证后转 active）

## 8. 结论

- [x] **通过**（可进入测试阶段）
- [ ] 阻断

**综合结论**：本次 M4 Phase A 基础层代码变更**无阻断级安全漏洞**（无 SQL 注入、无硬编码密钥、无命令注入、无 eval/RCE、无不安全反序列化），无阻断级质量缺陷。代码遵循 Karpathy Guidelines（显式暴露假设、surgical changes、Fail Fast），接口扩展向后兼容无损，测试覆盖充分（556 测试 0 失败）。

**附带项**：

- **强建议** G-01（tools 静默忽略）：建议进入 ac-verifier 前修复（加 Log.w 警告），或 PR 描述明确记录中间态风险
- **建议项** G-02~G-05 + R-01：可在后续迭代处理，不阻断
- **BR 提议** BR-naming-001：enum 穷尽匹配规则，待主 Agent 确认

**主 Agent 可启动 ac-verifier**（CLAUDE.md 第七节 7.2 第 3 步）。若选择修复 G-01，修复后需重新执行第九节影响自检 + 重新提交 guardrail-enforcer 审查（CLAUDE.md 7.2 第 5 步）；若选择接受 G-01 中间态（KDoc + ADR 已标注），可直接进入 ac-verifier，但需在 PR 描述中记录。

## 9. 自动化建议（CI/CD 集成）

建议在 CI 中集成以下自动化检查，防范本次发现的模式复发：

1. **enum 穷尽性 lint**（防范 G-01/BR-naming-001 反例）：
   - 使用 detekt 自定义规则 `ExhaustiveWhenInsteadOfElse`，禁止对 sealed class/enum 使用 `else` 分支
   - 或使用 ktlint `when-must-be-exhaustive` 规则

2. **硬编码密钥扫描**（CLAUDE.md 20.3）：
   - GitHub Actions 集成 gitleaks / trufflehog，扫描 commit 中的密钥

3. **依赖漏洞扫描**（CLAUDE.md 18.4）：
   - Phase B 引入 snakeyaml-engine-kmp 后，CI 集成 `dependencyCheck` 或 Dependabot

4. **Phase B 预检**（snakeyaml 沙箱）：
   - Phase B 引入 snakeyaml-engine-kmp 时，guardrail-enforcer 须重点审计 YAML 沙箱解析（禁用任意类构造）+ 远程下载安全（URL 白名单 + 内容大小限制 + zip slip 防护）
