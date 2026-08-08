# ADR-013: M4 Skills 系统架构（US-004）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M4「Skills 系统」整体架构决策：Skill 数据模型、SKILL.md 解析、注册中心与加载器、执行模型、管理 UI、远程下载安全、执行可观测、预设模板。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-09 |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 TKN-M4-SKILLS-ARCH-001 + tech-selection-researcher 选型报告 TKN-M4-TOOLCALLING-RESEARCH-001 + web-access 调研 + 用户决策「策略 C 引入 tool_calling」+「标准校验」） |
| 关联文档 | [ADR-001](ADR-001-prism-tech-stack.md) / [ADR-005](ADR-005-mcp-client-integration.md) / [ADR-006](ADR-006-filesystem-mcp-server.md) / [ADR-012](ADR-012-m3-rag-conversation-integration.md) / [ADR-014](ADR-014-m4-toolcalling-interface.md) / [PRD.md](../PRD.md) US-004 / [prd.json](../../prd.json) US-020~US-029 |
| 上游调研 | [M4 Skills 集成点源码考古](../reports/2026-08-09-m4-skills-archaeology.md) / [M4 tool_calling 技术选型](../reports/2026-08-09-m4-toolcalling-tech-selection.md) / [OpenClaw 考古](../reports/2026-08-02-openclaw-archaeology.md) §2.1 |
| 风险等级 | P3 重大（引入新依赖 snakeyaml-engine-kmp + 扩展 ChatStreamProvider 接口 + 新增工具执行回路 + 核心对话流改造） |
| 审查闭环 | 待 guardrail-enforcer + ac-verifier |

## 背景（Context）

PRD US-004 要求：Skill 格式遵循 SKILL.md 规范（frontmatter + 正文 + 资源目录）、支持本地与远程 Skill、manifest 注册与启动扫描加载、AI 根据 Skill 描述自动选择调用（用户可手动指定）、Skill 执行结果可观测、Skill 失败不影响主对话。

用户决策（2026-08-09）：
1. **策略 C：引入 tool_calling** —— M4 同步扩展 ChatStreamProvider 接口，Skill 可真正调用 MCP 工具执行动作（非纯 prompt 注入）
2. **标准校验** —— 远程 Skill 下载安全策略：URL 协议白名单（https）+ 内容大小限制（≤10MB）+ YAML 沙箱解析 + 必填字段校验 + slug 格式校验

code-archaeologist 考古报告（TKN-M4-SKILLS-ARCH-001）揭示 10 项风险，关键三项：

1. **R-1 [中] Skills 面板完全静态硬编码**：[CapabilitiesScreen.kt:89-94](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) `skills` 列表硬编码 4 条，`SectionHeader("已安装 · 5")` 与实际不符，`onSkillClick` 空回调，`enabled` 不落库。M4 必须从零重建数据流。
2. **R-2 [高] ChatStreamProvider 不支持 tool_calling**：`ChatCompletionRequest` 无 `tools` 字段，`StreamEvent` 无 `ToolCall` 事件。需 P2 级接口扩展（详见 ADR-014）。
3. **R-3 [中] MCP 工具调用基础设施已完整但未接入对话流**：`McpToolProvider.callTool` 仅在 CapabilitiesViewModel「测试连接」使用，ConversationViewModel 无 McpToolProvider 依赖。

tech-selection-researcher 选型报告（TKN-M4-TOOLCALLING-RESEARCH-001）关键结论：

- **YAML 解析库**：charleskorn/kaml 已于 2025-11-30 归档（Public archive），kotlinx.serialization 官方 Issue #3122 确认无生产级维护。改用 **snakeyaml-engine-kmp**（`it.krzeminski:snakeyaml-engine-kmp`，活跃维护，最后提交 2026-08-08，KMP 原生，Apache 2.0）。
- **tool_calling 抽象层**：自研轻量层，扩展现有 ChatStreamProvider + StreamEvent（langchain4j 面向 JVM 服务端，依赖 Java 21+ virtual threads，违反 C5 刚性约束）。
- **StreamEvent 扩展**：新增 `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete`（Provider 中立，不绑定 OpenAI/Anthropic 特定字段）。
- **工具执行回路**：ViewModel 层编排，复用 `ToolConfirmationGate` + `McpToolProvider`。

OpenClaw SKILL.md 规范（2026-08-09 web-access 验证最新版）：

- **必填字段**：`name`（slug, 1-64 字符 lowercase + 数字 + 连字符）、`description`（短描述，prompt 注入用）
- **可选字段**：`version`、`user-invocable`、`disable-model-invocation`、`homepage`、`os`、`metadata.openclaw.*`（requires.env/bins、primaryEnv、install、emoji、always、skillKey）
- **执行模型**：prompt 注入式——description 用于路由决策，body 作为指令注入 system prompt，LLM 自主决定调用工具
- **关键约束**：嵌入式解析器仅支持单行 frontmatter 键，`metadata` 应为单行 JSON 对象（保守写法）
- **加载优先级**：6 层（workspace > project-agent > personal-agent > managed > bundled > extra）

## 决策（Decision）

### 5.1 Skill 数据模型：SkillConfig 实体 + SkillManifest 数据类

**双层模型**：

- `SkillConfig`（ObjectBox `@Entity`，持久化启用状态与来源元数据）—— 仿 `McpServerConfig` 模式
- `SkillManifest`（内存数据类，SKILL.md frontmatter 解析结果）—— 纯数据，不持久化

```kotlin
// SkillConfig.kt —— 持久化层
@Entity
data class SkillConfig(
    @Id var id: Long = 0,
    var name: String,                    // slug，唯一，与 frontmatter name 一致
    var displayName: String,             // 展示名（frontmatter description 首行）
    var source: SkillSource,             // LOCAL_BUILTIN / LOCAL_USER / REMOTE
    var sourceUri: String? = null,       // REMOTE 时的下载 URL
    var skillDir: String,                // Skill 目录绝对路径（含 SKILL.md + 资源）
    var isEnabled: Boolean = false,      // 启用开关（落库，跨会话保留，修复 R-6）
    var isInstalled: Boolean = true,     // 安装状态（远程下载失败可标记 false）
    var version: String = "0.0.0",
    var dependsOnMcpServers: List<String> = emptyList(),  // 依赖的 MCP Server name 列表
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

enum class SkillSource { LOCAL_BUILTIN, LOCAL_USER, REMOTE }

// SkillManifest.kt —— 内存层（SKILL.md frontmatter 解析结果）
data class SkillManifest(
    val name: String,                    // 必填，slug
    val description: String,             // 必填，prompt 注入用
    val version: String?,                // 可选
    val userInvocable: Boolean = true,   // 可选，默认 true
    val disableModelInvocation: Boolean = false,  // 可选，默认 false
    val homepage: String? = null,
    val os: List<String>? = null,        // 平台筛选（android 固定允许）
    val tools: List<SkillToolDecl>? = null,  // Skill 声明的工具（M4 扩展，非 OpenClaw 标准）
    val systemPrompt: String? = null,    // Skill 专用 system prompt 片段
    val maxRounds: Int = 10,             // 工具调用循环上限（默认 10）
    val body: String                     // Markdown 正文（指令）
)

data class SkillToolDecl(
    val name: String,
    val description: String,
    val parameters: JsonElement          // JSON Schema
)
```

**理由**：

- 双层分离：持久化层（SkillConfig）稳定，内存层（SkillManifest）随 SKILL.md 规范演进
- `isEnabled` 落库（修复 R-6：RagTarget 仅内存态未持久化的教训）
- `dependsOnMcpServers` 声明依赖，运行时检查 MCP Server 可用性
- `tools` 字段是 Prism 对 OpenClaw 规范的扩展（OpenClaw 靠 LLM 自主决定，Prism 显式声明便于权限控制与可观测）
- 扁平 Long 外键模式（不引入 `@Relation`，遵循 ADR-008 5.2 + R-9）

### 5.2 SKILL.md 解析器：snakeyaml-engine-kmp + SkillManifestParser

**依赖**：

```toml
# libs.versions.toml
[versions]
snakeyaml-engine-kmp = "3.1"

[libraries]
snakeyaml-engine-kmp = { group = "it.krzeminski", name = "snakeyaml-engine-kmp", version.ref = "snakeyaml-engine-kmp" }
```

**解析器设计**：

```kotlin
// SkillManifestParser.kt
object SkillManifestParser {
    private val yaml = Yaml(defaultToNull = false)

    /**
     * 解析 SKILL.md 文件：分离 YAML frontmatter 与 Markdown body。
     * frontmatter 用 snakeyaml-engine-kmp 解析为 YamlMap，再映射到 SkillManifest。
     * body 保持原始 Markdown 文本。
     *
     * @param content SKILL.md 文件全文
     * @return 解析结果，含 SkillManifest + body
     * @throws SkillParseException 格式错误或必填字段缺失时抛出
     */
    fun parse(content: String): ParseResult {
        val (frontmatterText, body) = splitFrontmatter(content)
            ?: throw SkillParseException("Missing YAML frontmatter (---...---)")
        val yamlNode = yaml.parseToJson(frontmatterText) // 解析为 YamlNode
        val manifest = mapNodeToManifest(yamlNode)
        validate(manifest)
        return ParseResult(manifest, body)
    }

    private fun validate(manifest: SkillManifest) {
        require(manifest.name.matches(Regex("^[a-z0-9-]{1,64}$"))) {
            "name must be 1-64 lowercase letters, digits, or hyphens"
        }
        require(manifest.description.isNotBlank()) { "description must not be blank" }
        require(manifest.description.length <= 160) { "description must be <= 160 chars" }
    }
}
```

**理由**：

- snakeyaml-engine-kmp 是 kaml 底层引擎，活跃维护，KMP 原生（避免 kaml 归档风险）
- 不集成 kotlinx.serialization（snakeyaml-engine-kmp 提供 `YamlNode` 树，手动映射 <50 行，frontmatter 结构简单）
- 保守解析：仅支持扁平 key-value + list + 单行 metadata（OpenClaw 嵌入式解析器约束）
- 校验在解析时完成（fail-fast），避免运行时错误

### 5.3 Skill 注册中心与加载器：SkillRegistry + 启动扫描

**加载源**（优先级从高到低，对齐 OpenClaw 6 层）：

| 优先级 | 来源 | 路径 | M4 实现 |
| --- | --- | --- | --- |
| 1（最高） | 用户自建 | `<app-internal>/skills/user/` | 文件系统扫描 |
| 2 | 远程下载 | `<app-internal>/skills/remote/` | 文件系统扫描 |
| 3（最低） | 内置预设 | `assets/skills/builtin/` | AssetManager 读取 |

**SkillRegistry 设计**：

```kotlin
// SkillRegistry.kt
class SkillRegistry(
    private val context: Context,
    private val skillRepository: SkillRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _skills = MutableStateFlow<List<SkillEntry>>(emptyList())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    data class SkillEntry(
        val config: SkillConfig,
        val manifest: SkillManifest,
        val body: String
    )

    /** 启动时扫描所有加载源，同步 SkillConfig 表 */
    suspend fun scanAndSync() = withContext(ioDispatcher) {
        val discovered = mutableListOf<SkillEntry>()
        // 1. 扫描内置预设（assets）
        discovered += scanBuiltin()
        // 2. 扫描用户自建（filesDir/skills/user）
        discovered += scanDirectory(File(context.filesDir, "skills/user"), SkillSource.LOCAL_USER)
        // 3. 扫描远程下载（filesDir/skills/remote）
        discovered += scanDirectory(File(context.filesDir, "skills/remote"), SkillSource.REMOTE)
        // 4. 同步到 SkillConfig 表（新增/更新/标记缺失）
        syncToRepository(discovered)
        _skills.value = discovered
    }

    /** 获取所有已启用的 Skill（供 ConversationViewModel 注入） */
    fun enabledSkills(): List<SkillEntry> = _skills.value.filter { it.config.isEnabled }
}
```

**理由**：

- 启动扫描在 `PrismApplication.onCreate` 触发（IO 协程，不阻塞 UI）
- 扫描结果同步到 SkillConfig 表：新增 Skill 自动入库（`isInstalled=true`），已删除 Skill 标记 `isInstalled=false`
- StateFlow 暴露，UI 经 `collectAsState` 订阅
- 内置预设从 assets 读取（APK 内置，不可修改），用户/远程从 filesDir 读取（可增删）

### 5.4 Skill 执行模型：prompt 注入 + tool_calling 混合

**混合策略**（用户决策「策略 C 引入 tool_calling」）：

1. **prompt 注入层**：启用 Skill 的 `systemPrompt` + `body` 拼接到 ConversationViewModel 的 systemPrompt（与 RAG grounding rules 合并）
2. **tool_calling 层**：启用 Skill 的 `tools` 声明转换为 `ToolDefinition`，传给 `streamChat(tools=...)`，LLM 自主决定调用
3. **工具执行回路**：LLM 返回 `ToolCallComplete` → `ToolConfirmationGate` 确认 → `McpToolProvider.callTool` 执行 → 结果回灌 → 继续生成

**systemPrompt 合并策略**（修复 R-4 prompt 膨胀风险）：

```kotlin
// ConversationViewModel.sendMessage 内
val mergedSystemPrompt = buildString {
    // 1. RAG grounding rules（若 RAG 开启）
    ragPlan?.systemPrompt?.let { append(it); append("\n\n") }
    // 2. Skill system prompts（按 priority 顺序）
    enabledSkills.forEach { entry ->
        entry.manifest.systemPrompt?.let { append(it); append("\n\n") }
    }
    // 3. Skill 指令 body（精简版，仅 description + 关键步骤）
    if (enabledSkills.isNotEmpty()) {
        append("【可用 Skills】\n")
        enabledSkills.forEach { entry ->
            append("- ${entry.manifest.name}: ${entry.manifest.description}\n")
        }
    }
}.ifBlank { null }

// tool_definition 构建
val tools = enabledSkills.flatMap { entry ->
    entry.manifest.tools?.map { decl ->
        ToolDefinition(
            name = "${entry.manifest.name}__${decl.name}",  // 命名空间隔离
            description = decl.description,
            parameters = decl.parameters
        )
    } ?: emptyList()
}
```

**理由**：

- 混合模型兼顾「指令模板」（prompt 注入）与「工具执行」（tool_calling）
- systemPrompt 合并有序：RAG rules → Skill prompts → Skill 索引（避免 prompt 膨胀，只注入 systemPrompt + description，不注入完整 body 除非 LLM 主动请求）
- tool 命名空间隔离（`skillName__toolName`）避免不同 Skill 的同名工具冲突
- maxRounds 循环防护（默认 10，可 per-Skill 配置）

### 5.5 Skills 管理 UI：重构 CapabilitiesScreen Skills 段

**重构范围**（修复 R-1 静态硬编码）：

- 删除 `CapabilitiesScreen.kt:89-94` 硬编码 `skills` 列表
- `SkillsPanel` 改为从 `SkillsViewModel.skills.collectAsState()` 取数据
- `SkillRow` 的 `enabled` 开关改为调用 `viewModel.setEnabled(id, enabled)` 落库
- `onSkillClick` 改为打开 Skill 详情 `PrismSheet`（展示 manifest + 执行历史）
- `SectionHeader("已安装 · 5")` 改为动态计数 `"已安装 · ${skills.size}"`

**新增 SkillsViewModel**（仿 CapabilitiesViewModel）：

```kotlin
class SkillsViewModel(
    private val skillRegistry: SkillRegistry,
    private val skillRepository: SkillRepository,
    private val skillExecutor: SkillExecutor,
    private val mcpServerRepository: McpServerRepository
) : ViewModel() {
    val skills: StateFlow<List<SkillRegistry.SkillEntry>> = skillRegistry.skills
    private val _selectedSkill = MutableStateFlow<SkillRegistry.SkillEntry?>(null)
    val selectedSkill: StateFlow<SkillRegistry.SkillEntry?> = _selectedSkill.asStateFlow()
    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    sealed interface ExecutionState {
        data object Idle : ExecutionState
        data class Running(val skillName: String) : ExecutionState
        data class Success(val result: String, val durationMs: Long) : ExecutionState
        data class Fail(val message: String) : ExecutionState
    }

    fun setEnabled(id: Long, enabled: Boolean) = skillRepository.setEnabled(id, enabled)
    fun deleteSkill(id: Long) = viewModelScope.launch { /* 删除 Skill 文件 + 表记录 */ }
    fun installFromUrl(url: String) = viewModelScope.launch { /* 远程下载，见 5.6 */ }
}
```

**路由**：无需新增（Skills 在 `CAPABILITIES` Tab 内 `CapSegment.SKILLS` 段，考古报告集成点 6 确认）

### 5.6 远程 Skill 下载：标准校验策略

**用户决策**：标准校验（URL 协议白名单 + 内容大小限制 + YAML 沙箱解析 + 必填字段校验 + slug 校验）

**下载流程**：

```text
用户输入 URL
    │
    ▼
[1] URL 校验
    ├─ 协议必须 https（拒绝 http）
    ├─ 主机名非空
    └─ 路径以 .skill.md 或 .zip 结尾（或无扩展名，按目录处理）
    │
    ▼
[2] HTTP 下载（Ktor HttpClient）
    ├─ 响应 Content-Length 校验：≤ 10MB（拒绝过大）
    ├─ 响应 Content-Type 校验：text/markdown 或 application/zip（拒绝可执行）
    └─ 超时 30s
    │
    ▼
[3] 内容校验
    ├─ 若为 .zip：解压到临时目录，校验含 SKILL.md
    ├─ 若为 .skill.md：直接作为 SKILL.md
    └─ SkillManifestParser.parse() 解析（沙箱，禁用任意类构造）
    │
    ▼
[4] 入库
    ├─ 复制文件到 filesDir/skills/remote/{name}/
    ├─ SkillConfig 写入 ObjectBox（source=REMOTE, sourceUri=url, isInstalled=true）
    └─ SkillRegistry.scanAndSync() 刷新
```

**安全措施**：

| 安全维度 | 实现 |
| --- | --- |
| URL 协议白名单 | 仅 https（拒绝 http/file/ftp） |
| 内容大小限制 | ≤ 10MB（Content-Length + 流式计数双校验） |
| YAML 沙箱解析 | snakeyaml-engine-kmp 默认禁用任意类构造（无 `Constructor` 反射） |
| 必填字段校验 | name（slug 格式）+ description（非空，≤160 字符） |
| slug 格式校验 | `^[a-z0-9-]{1,64}$` |
| 解压安全 | zip slip 防护（校验解压路径不超出目标目录） |
| 超时防护 | 下载 30s 超时 |
| 不要求签名验证 | 零后端，用户主动输入 URL，签名验证用户体验差 |

### 5.7 Skill 执行可观测：SkillExecutionRecord + UI 展示

**执行记录数据类**：

```kotlin
// SkillExecutionRecord.kt
data class SkillExecutionRecord(
    val id: Long,                        // 自增
    val skillConfigId: Long,             // 关联 SkillConfig
    val skillName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    val status: ExecutionStatus,         // SUCCESS / FAIL / CANCELLED
    val toolCalls: List<ToolCallRecord>, // 工具调用明细
    val errorMessage: String? = null,
    val outputPreview: String? = null    // 输出预览（前 200 字符）
)

data class ToolCallRecord(
    val toolName: String,
    val arguments: String,               // JSON string
    val result: String,                  // 工具返回
    val durationMs: Long,
    val status: ExecutionStatus
)
```

**可观测 UI**：

- Skill 详情页展示最近 10 次执行记录
- 每条记录显示：开始时间、耗时、状态、工具调用链
- 工具调用链可展开查看 arguments + result
- 执行记录持久化到 ObjectBox（`SkillExecutionRecord` @Entity），用于跨会话审计

### 5.8 预设 Skill 模板：5 个内置示例

**内置 Skill**（`assets/skills/builtin/`）：

| Skill name | description | 用途 | tools |
| --- | --- | --- | --- |
| `translator` | 中英互译，支持术语表 | 翻译文本 | 无（纯 prompt） |
| `code-reviewer` | 代码审查，输出结构化报告 | 审查代码片段 | 无（纯 prompt） |
| `meeting-notes` | 会议纪要提取，结构化输出 | 整理会议文本 | `read_file`（Filesystem MCP） |
| `summarizer` | 长文总结，分层摘要 | 总结文档 | `read_file`（Filesystem MCP） |
| `rewriter` | 文本改写，支持风格切换 | 改写文本 | 无（纯 prompt） |

**理由**：

- 3 个纯 prompt Skill（translator/code-reviewer/rewriter）验证 prompt 注入路径
- 2 个工具调用 Skill（meeting-notes/summarizer）验证 tool_calling 回路
- 内置 Skill 在 `SkillRegistry.scanAndSync()` 时自动注册（source=LOCAL_BUILTIN）

### 5.9 PrismApplication 依赖注入扩展

新增 lazy 依赖（考古报告集成点 1 确认 `by lazy` 模式）：

```kotlin
// PrismApplication.kt 新增
val skillRepository: SkillRepository by lazy { SkillRepository(boxStore) }
val skillRegistry: SkillRegistry by lazy { SkillRegistry(this, skillRepository) }
val skillExecutor: SkillExecutor by lazy {
    SkillExecutor(mcpToolProviderDispatcher, confirmationGate, skillRepository)
}
```

ConversationViewModel.Factory 扩展（新增 skillRegistry + skillExecutor 注入）：

```kotlin
val Factory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
        ConversationViewModel(
            providerRepository = app.providerConfigRepository,
            provider = app.openAICompatibleProvider,
            embedder = app.embedder,
            knowledgeBaseRepository = app.knowledgeBaseRepository,
            skillRegistry = app.skillRegistry,           // 新增
            skillExecutor = app.skillExecutor,           // 新增
            ioDispatcher = Dispatchers.IO
        )
    }
}
```

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **Skill 执行模型：纯 prompt 注入（策略 A）** | 零接口改动，最小风险 | Skill 无法真正调用工具，只能输出建议；不满足用户「策略 C」决策 |
| **Skill 执行模型：Kotlin DSL 编译执行** | 性能高，类型安全 | 需引入 Kotlin Scripting 引擎，APK 体积暴增，安全风险高（任意代码执行） |
| **YAML 解析：charleskorn/kaml** | kotlinx.serialization 集成便利 | 已归档（2025-11-30），无维护，供应链风险 |
| **YAML 解析：Stream29/kaml fork** | kaml 活跃 fork | 仅比上游多 1 commit，底层仍依赖 snakeyaml-engine-kmp，多一层无意义 |
| **YAML 解析：手动正则** | 零依赖 | 不可靠，frontmatter 嵌套结构解析失败，维护成本高 |
| **远程 Skill：严格校验（SHA-256 + 域名白名单）** | 安全性最高 | 用户体验差（需手动输入哈希），零后端不适合 |
| **远程 Skill：基础校验（仅 URL + 必填字段）** | 最简实现 | 安全风险高（可能下载恶意 Skill） |
| **Skill 数据模型：单层 SkillConfig（含 manifest 字段）** | 简单 | frontmatter 字段演进时需数据库迁移，不灵活 |
| **Skill 数据模型：@Relation 关联** | 关系完整 | ObjectBox @Relation 已知副作用（ADR-008 5.2 规避） |

## 后果（Consequences）

- **正面后果**：
  - M4 Skills 系统闭环完成，满足 PRD US-004 全部 6 条 AC
  - Skill 可真正调用 MCP 工具执行动作（策略 C）
  - snakeyaml-engine-kmp 避免归档风险，KMP 原生
  - 复用现有基建（PrismApplication DI / ObjectBox Repository / McpToolProvider / ToolConfirmationGate）
  - Skill 执行可观测，便于调试与审计

- **负面后果 / 代价**：
  - 引入新依赖 snakeyaml-engine-kmp（APK 体积 +~500KB）
  - ChatStreamProvider 接口扩展（tools + toolChoice），需更新所有实现（详见 ADR-014）
  - ConversationViewModel 改造较大（新增 skillRegistry + skillExecutor + 工具执行回路）
  - tool_calling 协议实现复杂（OpenAI delta 状态机 + 并行 tool_call + 结果回灌）
  - 远程 Skill 下载存在安全风险（标准校验不要求签名，依赖用户判断）

- **需要同步更新的文档或代码**：
  - `libs.versions.toml` + `app/build.gradle.kts`（新增 snakeyaml-engine-kmp 依赖）
  - `ChatStreamProvider` 接口 + `StreamEvent` sealed class（详见 ADR-014）
  - `OpenAICompatibleProvider`（tool_calling 协议实现，详见 ADR-014）
  - `ConversationViewModel`（Skill 注入 + 工具执行回路）
  - `CapabilitiesScreen`（Skills 段重构为动态数据）
  - `PrismApplication`（新增 skillRepository/skillRegistry/skillExecutor lazy 依赖）
  - `assets/skills/builtin/`（5 个预设 Skill 目录）
  - `README.md`（文档索引更新）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| tool_calling 流式 delta 解析状态机复杂度（R1 from 选型报告） | 高 | finish_reason 检查后才 JSON.parse，不完整时发射 Error；strict mode 减少参数幻觉；详见 ADR-014 |
| snakeyaml-engine-kmp 不集成 kotlinx.serialization，需手动映射 | 中 | frontmatter 结构简单，映射代码 <50 行；封装 SkillManifestParser；若超 200 行切换 Stream29/kaml |
| systemPrompt 膨胀超模型上下文窗口（R-4） | 中 | 仅注入 systemPrompt + description，不注入完整 body；按 priority 顺序合并；监控 token 消耗 |
| 远程 Skill 下载恶意内容 | 中 | 标准校验（https + ≤10MB + 沙箱解析 + slug 校验）；用户确认弹窗展示 manifest 摘要 |
| 工具执行回路无限循环 | 中 | maxRounds=10（per-Skill 可配置）；超过后强制终止并提示用户 |
| 内置预设 Skill 从 assets 读取的性能 | 低 | 启动扫描在 IO 协程，不阻塞 UI；首次扫描后缓存到 SkillConfig 表 |
| Skill 命名空间冲突（不同 Skill 同名工具） | 低 | tool 命名空间隔离 `skillName__toolName` |
| Skill 失败影响主对话（PRD AC-6） | 中 | SkillBuildResult sealed 降级模式（Success/Fail/NormalChat），失败时降级为普通对话 |

## 参考

- [M4 Skills 集成点源码考古报告](../reports/2026-08-09-m4-skills-archaeology.md)
- [M4 tool_calling 技术选型对比分析报告](../reports/2026-08-09-m4-toolcalling-tech-selection.md)
- [OpenClaw/NullClaw 源码考古报告](../reports/2026-08-02-openclaw-archaeology.md) §2.1 SKILL.md 规范
- [OpenClaw Skills 官方文档](https://docs.openclaw.ai/tools/skills)
- [OpenClaw Skill Format](https://docs2.openclaw.ai/clawhub/skill-format)
- [snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp)
- [kotlinx.serialization#3122 kaml 归档确认](https://github.com/Kotlin/kotlinx.serialization/issues/3122)
- [ADR-001 Prism 技术栈](ADR-001-prism-tech-stack.md)
- [ADR-005 MCP Client 集成](ADR-005-mcp-client-integration.md)
- [ADR-006 内置 Filesystem MCP Server](ADR-006-filesystem-mcp-server.md)
- [ADR-012 M3 RAG 对话集成](ADR-012-m3-rag-conversation-integration.md)
- [ADR-014 M4 tool_calling 接口扩展](ADR-014-m4-toolcalling-interface.md)
- [behavioral-rules.md](../behavioral-rules.md) BR-concurrency-001~004 / BR-error-handling-003~007 / BR-interface-004
