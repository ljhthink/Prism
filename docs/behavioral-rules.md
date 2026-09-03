# Prism 行为规则累积（Behavioral Rules）

> 本文件是 CLAUDE.md 的动态累积层（第二十三节）。从 Bug 修复、PR 审查、运维 postmortem 中提炼可执行规则。
> 初始结构从 `docs/templates/behavioral-rules-template.md` 复制。试点期顺向累积，不回溯历史。

## 规则分类

### naming

#### BR-naming-001: enum 新增值时所有 if-else 二分匹配必须改为 when 穷尽 + 新值 Fail Fast

- 类别：naming / error-handling
- 规则：Kotlin enum 新增枚举值时，所有对该 enum 的 `if-else` 二分匹配（如 `if (this == X) ... else ...`）必须改为 `when (this)` 穷尽匹配，且新值分支必须显式处理（实现或 Fail Fast 抛异常），让编译器强制覆盖新分支。禁止用 `else` 兜底掩盖新值未处理。这样新增枚举值时编译器会立即在所有消费点报错，避免静默映射到错误分支。
- 反例：`fun Role.toRequestRole() = if (this == Role.USER) "user" else "assistant"` —— 新增 Role.TOOL 静默映射为 "assistant"，请求语义错误
- 正例：`fun Role.toRequestRole() = when (this) { Role.USER -> "user"; Role.ASSISTANT -> "assistant"; Role.TOOL -> throw IllegalStateException("Role.TOOL 序列化尚未实现") }`
- 来源：M4 Phase A Role.TOOL 静默映射 bug 修复（TKN-M4-PHASEA-GUARDRAIL-001，主 Agent 自查发现 + guardrail 确认修复正确）
- 添加日期：2026-08-09
- 适用场景：dev
- 状态：active（ac-verifier TKN-M4-PHASEA-ACCEPTANCE-001 验证通过，2026-08-09 转 active）

#### BR-naming-002: 局部变量名禁止与同作用域组件参数名同名导致语义混淆

- 类别：naming
- 规则：在 Composable 函数内部声明的局部 `var`/`val`，若其语义与同作用域内使用的组件参数名相同（如局部变量 `enabled` 表示"开关状态"，同作用域内 PrismButton 也有 `enabled` 参数表示"按钮可点击性"），必须重命名为语义更明确的名称。同名会导致：(1) `enabled = !isNew && !enabled` 一行内左值是参数名、右值是局部变量，需多次回读才能理解；(2) onClick 闭包内 `if (enabled)` 易被误读为"如果按钮启用"而非"如果开关已开启"；(3) 未来若重命名组件参数，可能误改局部变量。命名应表达业务意图（如 `activateAfterSave` 表达"保存后是否激活"），而非复用通用修饰词。
- 反例：`var enabled by remember { mutableStateOf(config.isActive) }` + `PrismButton(enabled = !isNew && !enabled, ...)` + `if (enabled) { viewModel.setActive(...) }` —— `enabled` 既指开关状态又出现在按钮参数位置，语义混淆
- 正例：`var activateAfterSave by remember { mutableStateOf(config.isActive) }` + `PrismButton(enabled = !isNew && !activateAfterSave, ...)` + `if (activateAfterSave) { viewModel.setActive(...) }` —— 明确表达"保存后是否激活"
- 来源：DEF-001 Provider 配置保存功能故障定位（TKN-DEF001-ROOTCAUSE-002，考古报告 §3 变量名 enabled 语义混淆影响评估）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active（guardrail TKN-DEF001-GUARDRAIL-001 确认非重复 + ac-verifier TKN-DEF001-ACCEPTANCE-001 验证通过，2026-08-12 转 active）

### error-handling

#### BR-error-handling-003: 错误文案安全映射时应保留业务语义区分

- 类别：error-handling
- 规则：对异常做安全映射（隐藏内部细节）时，应保留业务语义区分（如 401 鉴权失败 vs 网络断开），避免为隐藏细节而将诊断价值一并抹除。映射结果不得泄露内部路径/堆栈，但应能区分可诊断类别。
- 反例：所有异常统一为「网络请求失败」—— 区分不了「API Key 无效」与「断网」
- 正例：`if (isUnauthorized) "鉴权失败，请检查 API Key" else "网络请求失败，请检查网络连接或 Provider 配置"`（均不泄露内部细节）
- 来源：US-006 流式请求修复复审（TKN-NETWORK-US006-001，发现 3）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-error-handling-004: catch 兜底异常须输出结构日志并保留可诊断类别

- 类别：error-handling
- 规则：`catch (e: Exception)` 兜底时，除向用户提供通用安全文案外，应记录结构化日志（含异常类型与可诊断信息，不含密钥/请求体/完整 URL），禁止静默吞异常。若项目暂未引入结构化日志基建，应在该分支保留注释说明异常被归一化处理，且不得将内部异常细节（路径/堆栈）暴露给用户。
- 反例：`catch (e: Exception) { emit(StreamEvent.Error("网络请求失败")) }` —— 无任何日志，异常被静默吞掉，难定位
- 正例：`catch (e: Exception) { logger.error("chat stream failed", e); emit(StreamEvent.Error("网络请求失败…")) }`（日志不含密钥/请求体）
- 来源：US-007 流式请求审查（TKN-US007-GUARDRAIL-001，Q3）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-error-handling-005: 显式关闭资源的异常处理须保证状态置位

- 类别：error-handling
- 规则：`close()`/`unload()` 等显式关闭原生资源的方法，若 `close` 抛异常被捕获并重新抛出，其后的「置 null/标记已关闭」语句不会执行，导致对象残留已关闭引用、下次重复关闭。必须将「置 null」移入 `finally`，或在 `try` 之前/之内先置 null，保证无论 close 成功与否状态一致。
- 反例：`try { s.close() } catch (e: Exception) { throw Wrapped(e) }; session = null` —— 抛异常时 session 不置 null
- 正例：`session = null; try { s.close() } catch (e: Exception) { throw Wrapped(e) }`，或 `try { s.close() } finally { session = null }`
- 来源：US-014 嵌入引擎审查（TKN-US014-EMBEDDING-001，G-02 高危发现）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active（ac-verifier TKN-US014-EMBEDDING-AC-001 确认 G-02 修复有效，2026-08-07 转 active）

#### BR-error-handling-006: 参数校验须在资源保护块内或之前先释放资源

- 类别：error-handling
- 规则：当方法接收需显式关闭的资源（如 `InputStream`/`OutputStream`/`Cursor`）并声明「由本方法负责关闭」时，若方法入口有 `require`/`check` 等参数校验，且该校验位于 `use {}`/`try-finally` 资源保护块**之前**，则校验失败抛异常时资源不会被关闭，违反关闭契约并导致句柄泄漏。必须满足以下之一：(1) 将参数校验移入资源保护块内（`use {}` 内部首行）；(2) 校验失败前显式 `close()` 资源再抛异常；(3) 用 `try-finally` 包裹整个方法体（含校验），`finally` 中关闭资源。仅依赖「调用方不应传入非法参数」不构成豁免——纵深防御要求资源所有者覆盖所有早退路径。
- 反例：`fun ingest(input: InputStream, kbId: Long) = flow { require(kbId >= 0) { ... }; input.use { ... } }` —— `kbId < 0` 时 `require` 抛异常，`input` 未进入 `use {}`，泄漏
- 正例：`fun ingest(input: InputStream, kbId: Long) = flow { if (kbId < 0) { input.close(); throw IllegalArgumentException(...) }; input.use { ... } }`，或 `input.use { require(kbId >= 0) { ... }; ... }`
- 来源：US-016 摄入管线审查（TKN-US016-GUARDRAIL-001，M1 中高危发现）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active（ac-verifier TKN-US016-AC-001 确认 M1 修复有效，2026-08-07 转 active；M3 里程碑审计 TKN-M3-MILESTONE-AUDIT-001 同步状态字段）

#### BR-error-handling-007: 协程代码中禁止用 runCatching 吞 CancellationException

- 类别：error-handling / concurrency
- 规则：在 Kotlin 协程代码（suspend 函数 / `withContext` 块 / Flow collect / `viewModelScope.launch` 内）中，`runCatching { }` 会捕获所有 `Throwable`（含 `CancellationException`），破坏结构化并发的取消传播，导致协程取消不传播、资源泄漏、测试假阳性。必须改用显式 `try-catch`，且 `catch (e: CancellationException) { throw e }` 必须在 `catch (e: Exception)` 之前（CancellationException 继承自 IllegalStateException 而非 Exception 之外的类，但 JVM 异常匹配按声明顺序，先匹配 CancellationException 才能正确重抛）。若必须用 `runCatching`，须在 `getOrElse` / `onFailure` 中先检查并重抛 `CancellationException`。**例外**：外层 `runCatching { suspendFn() }.getOrElse { e -> if (e is CancellationException) throw e; ... }` 形式可接受，但建议优先用 try-catch 仅 `catch (e: Exception)` 避免 `Error` 被吞。
- 反例：`val v = runCatching { suspendingApi.call() }.getOrElse { return null }` —— 协程取消时 `CancellationException` 被吞，取消不传播；亦违反 BR-error-handling-004 静默吞异常
- 正例：`val v = try { suspendingApi.call() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }`，或外层 `runCatching { ... }.getOrElse { e -> if (e is CancellationException) throw e; null }`
- 来源：US-019 RAG 对话集成审查（TKN-US019-RAG-GUARDRAIL-001，G-01 HIGH 发现）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active（ac-verifier TKN-US019-RAG-ACCEPTANCE-001 确认 G-01 修复有效，2026-08-07 转 active）

#### BR-error-handling-008: 上游 Error 事件 message 透传给用户可见 UI 时必须经过脱敏

- 类别：error-handling / security
- 规则：当上游组件（Provider、Executor、Pipeline 等）通过 `StreamEvent.Error(message)` 或类似机制向上层传递错误信息，且该 message 最终会展示给用户（如追加到聊天消息 content、显示在 Toast/Dialog）时，**必须**经过脱敏处理（CWE-209 信息泄露纵深防御）。脱敏须包含：(1) 长度截断（如 ≤200 字符，防止超长错误污染对话历史）；(2) 路径脱敏（将 `/xxx/yyy` 或 `\xxx\yyy` 替换为 `<path>`，避免内部文件系统路径泄露）。推荐**双层防御**：第一层在错误产生点（如 SkillExecutor catch 块）应用 `sanitizeErrorMessage`，第二层在 UI 边界（如 ViewModel handleStreamEvent）应用 `sanitizeUiErrorMessage`，覆盖未来新增 Provider 可能透传原始异常 message 的风险。**例外**：上游已使用固定文案（非 `e.message` 拼接）的 Error 事件可免第二层脱敏，但第一层仍建议保留。空白字符串 message 应回退为通用安全文案（如"未知错误"）。
- 反例 1：`catch (e: Exception) { onEvent(StreamEvent.Error("failed: ${e.message}")) }` —— `e.message` 可能含 HTTP 响应体/内部 URL/文件路径，直接透传给用户
- 反例 2：`is StreamEvent.Error -> appendDelta(aiId, "\n\n⚠️ ${event.message}")` —— UI 边界未做脱敏，依赖上游"应该已经脱敏"的假设
- 正例 1：`catch (e: Exception) { val safeMsg = sanitizeErrorMessage(e.message) ?: e.javaClass.simpleName; onEvent(StreamEvent.Error("failed: $safeMsg")) }` —— 错误产生点脱敏
- 正例 2：`is StreamEvent.Error -> { val safeMsg = sanitizeUiErrorMessage(event.message); appendDelta(aiId, "\n\n⚠️ $safeMsg") }` —— UI 边界第二层防御
- 来源：M4 Phase D 审查（TKN-M4-PHASED-GUARDRAIL-001，M-1 中危发现 CWE-209；TKN-M4-PHASED-GUARDRAIL-002 确认修复有效；TKN-M4-PHASED-ACCEPTANCE-001 确认集成测试中双层脱敏有效）
- 添加日期：2026-08-09
- 适用场景：dev
- 状态：active（ac-verifier TKN-M4-PHASED-ACCEPTANCE-001 确认 M-1 双层脱敏在集成测试中有效，2026-08-09 转 active）

#### BR-error-handling-009: kotlinx.serialization Json 实例必须设置 encodeDefaults=true + explicitNulls=false

- 类别：error-handling / testing
- 规则：当使用 kotlinx.serialization 的 `Json` 实例序列化含默认值字段的数据类（如 `data class ToolDefinition(val type: String = "function", ...)`）用于 API 请求体时，**必须**设置 `encodeDefaults = true`，确保值等于默认值的字段被序列化输出。kotlinx.serialization 默认 `encodeDefaults = false`，会省略值等于默认值的字段，导致 OpenAI 兼容 API 规范要求的必填字段（如 `type: "function"`）被剥离，严格校验的服务端（如 DeepSeek）返回 400。配套设置 `explicitNulls = false` 确保 null 字段被完全省略，保持 `field = null` 时不输出该字段的向后兼容行为。项目内所有 `Json` 实例应统一此配置（对齐 `SkillExecutor` / `ToolCallListConverter`）。**测试要求**：序列化测试必须断言带默认值的字段在实际输出中存在，即使构造对象时未显式传入该字段值。
- 反例 1：`private val json = Json { ignoreUnknownKeys = true }` —— `ToolDefinition.type`（默认值 "function"）被省略，DeepSeek 返回 400 "missing field `type`"
- 反例 2：`Json { encodeDefaults = true }` —— 不设 `explicitNulls = false`，null 字段被序列化为 `"field":null`，破坏 `tools = null` 时不输出 tools 字段的向后兼容
- 正例：`Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }` —— 带默认值的字段被输出，null 字段被省略
- 来源：DEF-002 Bug 修复（DeepSeek API 400 "tools[0]: missing field `type`"；TKN-DEEPSEEK-TOOLS-BUG-001 考古报告；TKN-DEF002-GUARDRAIL-001/002 审查；TKN-DEF002-ACCEPTANCE-002 验收）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active

#### BR-error-handling-010: 修改错误处理函数从固定文案改为拼接外部输入时必须重新评估 BR-error-handling-008 适用性

- 类别：error-handling / security
- 规则：当修改错误处理函数（如 `mapHttpError`）从"固定文案"变更为"拼接外部输入"（如 HTTP 响应体 `errorBody`、异常 `e.message`）时，**必须**重新评估 BR-error-handling-008 例外条款适用性。固定文案适用例外（免脱敏），但拼接外部输入后不再适用例外，必须应用路径脱敏 + 长度截断 + 换行符替换。评估要点：(1) 输入源是否不可信（服务器响应、异常 message）；(2) 输出汇聚点是否用户可见（UI 文案、日志）；(3) 是否已调用脱敏函数（如 `sanitizeErrorBody` / `sanitizeErrorMessage`）。
- 反例：`fun mapHttpError(status: Int): StreamEvent.Error` 改为 `fun mapHttpError(status: Int, errorBody: String?)` 后直接拼接 `errorBody` 到错误文案，未应用路径脱敏 —— 违反 BR-error-handling-008
- 正例：修改后调用 `sanitizeErrorBody(errorBody)` 进行路径脱敏 + 长度截断 + 换行符替换后再拼接
- 来源：DEF-002 Bug 修复（TKN-DEF002-GUARDRAIL-001 发现 B-1 阻断；TKN-DEF002-GUARDRAIL-002 确认修复有效）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active

### security

#### BR-security-001: data class 含数组字段必须覆盖 equals/hashCode

- 类别：security
- 规则：Kotlin `data class` 自动生成的 `equals()`/`hashCode()` 对数组类型（`IntArray`/`FloatArray`/`ByteArray` 等）使用引用相等而非内容比较。若 data class 包含数组字段，必须手动覆盖 `equals()`/`hashCode()` 使用 `contentEquals()`/`contentHashCode()`，或添加注释明确说明该类不依赖 equals 语义。
- 反例：`data class Entity(val data: FloatArray)` —— 两个内容相同的实例因数组引用不同被判不等
- 正例：覆盖 equals 使用 `data.contentEquals(other.data)`，覆盖 hashCode 使用 `data.contentHashCode()`
- 来源：US-002 ObjectBox 集成审查（TKN-PRISM-GUARDRAIL-004，G-02/CR-01 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-security-001-amendment: nullable 数组字段 equals 覆盖须使用 nullable 扩展函数

- 类别：security
- 规则：当 data class 含有 **nullable 数组字段**（如 `FloatArray?`、`IntArray?`）并覆盖 equals/hashCode 时，**禁止使用 `array?.contentEquals(other.array) == true` 模式**。`?.` 安全调用在接收者为 null 时短路返回 null，`null == true` 求值为 false，导致两条均含 null 数组的相同记录被判为不相等（违反 equals 语义一致性）。**必须使用 nullable 扩展函数 `array.contentEquals(other.array)`**（Kotlin 标准库提供 `infix fun FloatArray?.contentEquals(other: FloatArray?): Boolean`），该函数双 null 返回 true、单 null 返回 false、双非 null 做内容比较。hashCode 同理须用 `array?.contentHashCode() ?: 0` 或直接 `array.contentHashCode()`（nullable 扩展函数，null 返回 0）。
- 反例：`embedding?.contentEquals(other.embedding) == true` —— 双 null embedding 时 `null?.contentEquals(...)` 短路为 null，`null == true` 为 false，两条相同 null embedding 记录判为不等
- 正例：`embedding.contentEquals(other.embedding)` —— 调用 `FloatArray?.contentEquals(FloatArray?)` nullable 扩展函数，双 null 返回 true
- 来源：M5 Phase A 审查（TKN-M5-PHASEA-GUARDRAIL-001，L-01 低危发现；主 Agent 修复 + 4 边界测试验证；ac-verifier TKN-M5-PHASEA-ACCEPTANCE-001 确认修复有效）
- 添加日期：2026-08-10
- 适用场景：dev
- 状态：active（ac-verifier TKN-M5-PHASEA-ACCEPTANCE-001 确认转 active，2026-08-10。L-01 修复后 4 边界测试通过：双 null 相等 / 双非 null 内容相等 / 单 null 不等 / 双非 null 内容不等）

#### BR-security-002: Android Keystore StrongBox 密钥生成必须捕获通用异常回退 TEE

- 类别：security
- 规则：Android Keystore 使用 StrongBox 生成密钥时，不能仅捕获 `StrongBoxUnavailableException`。厂商 StrongBox 实现碎片化可能导致其他异常（如 `ProviderException`、`IllegalStateException`），必须额外捕获通用 `Exception` 回退到 TEE，保证密钥生成可用性。仅捕获 `StrongBoxUnavailableException` 会在部分厂商设备上导致密钥生成失败、应用不可用。
- 反例：`try { generateStrongBoxKey() } catch (e: StrongBoxUnavailableException) { fallbackTee() }` —— 厂商抛出 `ProviderException` 时未回退，密钥生成失败
- 正例：`try { generateStrongBoxKey() } catch (e: StrongBoxUnavailableException) { fallbackTee() } catch (e: Exception) { fallbackTee() }` —— 任何异常都回退 TEE
- 来源：US-003 API Key 加密存储审查（TKN-PRISM-GUARDRAIL-005，G-01 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-data-001: 自定义序列化转换器必须对所有分隔符与特殊字符做转义

- 类别：security
- 规则：为 ORM 类型转换器（如 ObjectBox `PropertyConverter`）实现自定义序列化时，必须对所用分隔符（换行、等号、逗号等）和转义字符（反斜杠）做完整转义。若序列化格式用某字符做分隔符，该字符在数据中出现时必须转义，否则会导致数据损坏（元素数量变化/键值错位）。同一项目内多个转换器应保持一致的转义策略。
- 反例：`StringListConverter` 用 `\n` 分隔但不对元素中的 `\n` 转义 —— 模型名含换行时 `split("\n")` 产生多余元素
- 正例：`StringMapConverter` 对 `\`/`\n`/`=` 全部转义，单次扫描反转义 —— 无歧义
- 来源：US-004 ProviderConfig 审查（TKN-PRISM-GUARDRAIL-006，G-02 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-security-003: 用户可配置 header/URL 在保存点须拒绝控制字符（CRLF）

- 类别：security
- 规则：用户可配置的 HTTP header 名称/值、base URL 等，在保存点（UI 校验层）即须拒绝控制字符（`\r`/`\n`/`\u0000`），不宜仅依赖运行时引擎的 fail-closed 行为作为唯一防线。CRLF 注入可导致请求头拆分/污染，纵深防御应在入口校验。
- 反例：直接把用户输入的 header 值写入请求构造函数，仅依赖 OkHttp 对含 `\r`/`\n` 值抛异常
- 正例：保存时过滤 `it.contains('\r') || it.contains('\n')`，非法输入阻止保存并提示
- 来源：US-007 自定义 headers 审查（TKN-US007-GUARDRAIL-001，S1）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-security-004: YAML 解析必须显式配置 LoadSettings 安全参数 + 递归遍历须有深度限制

- 类别：security
- 规则：使用 snakeyaml-engine-kmp `Load` 解析 YAML 时,必须通过 `LoadSettings(...)` 命名参数**显式**配置安全参数(不依赖默认值,以文档化安全意图并防御未来默认值变更),至少包含:`allowRecursiveKeys = false`(禁止循环引用,防下游递归遍历 StackOverflowError)、`maxAliasesForCollections = 50`(限制别名展开,防 billion laughs 攻击)、`codePointLimit`(限制单文档大小)。注:snakeyaml-engine-kmp 4.0.1 的 `LoadSettings` 是 **immutable data class**(非 builder 模式),`allowRecursiveKeys` 默认值即 `false`(本规则要求**显式**设置以纵深防御)。此外,任何递归遍历解析后 Java 对象图的函数(如 `toJsonElement`)必须实现深度限制(如 `require(depth < MAX_DEPTH)`),作为二级防护防止深层嵌套(非循环)导致栈溢出。
- 反例 1：`Load(LoadSettings()).loadOne(yaml)` —— 依赖默认值,未文档化安全意图
- 反例 2：`fun toJsonElement(v: Any?): JsonElement = when(v) { is Map<*,*> -> buildJsonObject { v.forEach { (k,v) -> put(k as String, toJsonElement(v)) } }; ... }` —— 递归无深度限制,深层嵌套 YAML 导致 StackOverflowError
- 正例 1：`Load(LoadSettings(allowRecursiveKeys = false, maxAliasesForCollections = 50, codePointLimit = 1024*1024)).loadOne(yaml)` —— 显式配置安全参数
- 正例 2：`fun toJsonElement(v: Any?, depth: Int = 0): JsonElement { require(depth < MAX_DEPTH) { "..." }; return when(v) { is Map<*,*> -> buildJsonObject { v.forEach { (k,v) -> put(k as String, toJsonElement(v, depth+1)) } }; ... } }` —— 递归有深度限制
- 来源：M4 Phase B 审查(TKN-M4-PHASEB-GUARDRAIL-001,G-02/G-07 中危发现 + R2-1 测试补强)
- 添加日期：2026-08-09
- 适用场景：dev
- 状态：active（ac-verifier TKN-M4-PHASEB-ACCEPTANCE-002 确认转 active，2026-08-09。规则文本已修订纠正 3 处事实错误 + 实现符合正例 + 4 测试验证防护有效）

#### BR-security-005: 可配置数值参数须在 repository 层和消费层双重强制合法范围

- 类别：security
- 规则：可配置的数值参数（如滑动窗口大小 N、分页 size、重试次数等）必须在 **repository 层**（`set` 方法）和 **消费层**（读取使用处）双重强制合法范围（min + max），不能仅依赖 UI 层校验或仅定义 `MAX`/`MIN` 常量而不强制。repository 层用 `require(value in MIN..MAX)` fail-fast 拒绝越界值；消费层用 `coerceIn(MIN, MAX)` 防御 DataStore/数据库被外部直接写入越界值（绕过 repository 校验）的场景。双层防御确保即使某一层被绕过，另一层仍能拦截，防止过大值导致下游 token 溢出或过小值导致逻辑异常。
- 反例 1：`const val MAX_WINDOW_SIZE = 50` + `suspend fun setWindowSize(size: Int) { require(size > 0); ... }` —— `MAX` 仅作常量定义不强制，repository 层不拒绝 size=1000
- 反例 2：`val windowSize = memoryConfigRepository.getWindowSize().coerceAtLeast(1)` —— 消费层仅有下界防御，无上界；DataStore 被写入 N=100000 时全部消息被视为"近期"，token 溢出
- 正例：`suspend fun setWindowSize(size: Int) { require(size in MIN_WINDOW_SIZE..MAX_WINDOW_SIZE); ... }` + `val windowSize = memoryConfigRepository.getWindowSize().coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)` —— repository 层 require + 消费层 coerceIn 双层防御
- 来源：M5 Phase B 审查（TKN-M5-PHASEB-GUARDRAIL-001，M-1/M-2 中危发现；主 Agent 修复 + 5 边界测试验证：拒绝 51/1000 + coerceIn 1000→50 + coerceIn 0→1）
- 添加日期：2026-08-11
- 适用场景：dev
- 状态：active（ac-verifier TKN-M5-PHASEB-ACCEPTANCE-001 确认转 active，2026-08-11。M-1 setWindowSize require 双界校验 + M-2 processMessages coerceIn 双层防御均验证有效，5 边界测试通过：拒绝 51/1000 + coerceIn 1000→50 + coerceIn 0→1）

#### BR-security-006: Tink AEAD 调用须将 null AAD 转空数组 + ApiKeyRepository 空值跳过 + 清空删除

- 类别：security
- 规则：(1) 使用 Tink `AndroidKeystoreAesGcm` 时，`encrypt`/`decrypt` 的 `associatedData` 参数为 null 须转为空字节数组 `ByteArray(0)`，因为 Tink 内部直接调用 `Cipher.updateAAD(null)` 抛出 `IllegalArgumentException: src buffer is null`。(2) `ApiKeyRepository.saveApiKey` 当 value 为空字符串时须直接 return（不加密、不落盘、不覆盖已有密钥）。(3) UI 层（如 ApiKeySheet）当用户清空输入框后保存，须调用 `removeApiKey` 删除已存密钥，而非调用 `saveApiKey` 静默跳过导致旧密钥残留。
- 反例 1：`override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = aead.encrypt(plaintext, associatedData)` —— associatedData=null 时闪退
- 反例 2：`suspend fun saveApiKey(key: String, value: String) { val encrypted = cryptoService.encrypt(value.toByteArray()); dataStore.edit { it[byteArrayPreferencesKey(key)] = encrypted } }` —— 空值覆盖已有密钥
- 反例 3：`onClick = { viewModel.saveApiKey(config.apiKeyRef, key) }` —— key="" 时 saveApiKey 静默跳过，已存密钥不被清除
- 正例 1：`override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = aead.encrypt(plaintext, associatedData ?: ByteArray(0))`
- 正例 2：`suspend fun saveApiKey(key: String, value: String) { if (value.isEmpty()) return; ... }`
- 正例 3：`onClick = { if (key.isEmpty()) viewModel.removeApiKey(config.apiKeyRef) else viewModel.saveApiKey(config.apiKeyRef, key) }`
- 来源：DEF-001 Provider 配置保存闪退修复（TKN-DEF001-GUARDRAIL-001 B-1/B-2 阻断级发现 + 运行时验证 logcat 证据）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active

### concurrency

#### BR-concurrency-001: 多步骤数据库状态变更必须使用事务保证原子性

- 类别：concurrency
- 规则：当一个数据库操作方法需要修改多条记录以维护业务不变式（如"同一时间仅一个激活"、"唯一默认值"等）时，必须使用数据库事务（如 ObjectBox `runInTx`、Room `@Transaction`）将所有修改包装为原子操作。逐条 put/update 在异常场景下可能破坏不变式，留下不一致状态。
- 反例：`fun setActive(id: Long) { box.all.forEach { if (it.id == id) { it.isActive = true; box.put(it) } else if (it.isActive) { it.isActive = false; box.put(it) } } }` —— 遍历中途异常留下多个 isActive=true
- 正例：`fun setActive(id: Long) { boxStore.runInTx { box.all.forEach { ... box.put(it) } }; refresh() }` —— 事务保证全成功或全回滚
- 来源：US-004 ProviderConfig 审查（TKN-PRISM-GUARDRAIL-006，G-01 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-concurrency-002: 生命周期资源的并发访问须覆盖 close 路径

- 类别：concurrency
- 规则：当类持有需显式关闭的原生/重型资源（如 ONNX `OrtSession`、数据库连接、IO 句柄）并以锁保护生命周期时，若 `embed`/`run` 等使用方法在 `ensureLoaded` 返回后释放锁再使用资源引用，则 `close()` 可在并发窗口内关闭资源，导致 use-after-close。必须：要么将使用方法整体纳入锁（串行化可接受时），要么用引用计数/读写锁使 `close` 等待活跃使用完成。仅在注释中声明线程安全而实现未覆盖 close 并发路径视为契约违反。
- 反例：`fun embed() { val s = ensureLoaded() /* 锁内返回后释放 */; s.run(inputs) /* 锁外 */ }`，`fun close() = lock.withLock { session?.close(); session=null }` —— close 可在 s.run 前关闭 session
- 正例：`fun embed() = lock.withLock { val s = ensureLoadedLocked(); s.run(inputs) }`，或引用计数保证 close 等待 activeCount==0
- 来源：US-014 嵌入引擎审查（TKN-US014-EMBEDDING-001，G-01 阻断级发现）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active（ac-verifier TKN-US014-EMBEDDING-AC-001 确认 G-01 修复有效，2 并发测试通过，2026-08-07 转 active）

#### BR-concurrency-003: HNSW 向量索引实体的批量删除禁用 Query.remove()

- 类别：concurrency
- 规则：对带 `@HnswIndex` 向量索引的 ObjectBox 实体（如 `KnowledgeChunk`），批量删除时**禁用 `Query.remove()`**（nativeRemove 路径），因其命中 objectbox-java#1209（`IllegalStateException: Vector is missing for neighbor to repair`，截至 5.4.2 未确认修复）。必须改用 `Query.findIds()` 查 id + `Box.remove(*ids)`（vararg Long）走 Box native 路径删除。同时 `findIds()` 后用 `use {}` 关闭 Query 释放 native 句柄。级联删除仍须在 `runInTx` 事务内保证原子性。
- 反例：`chunkBox.query().equal(KnowledgeChunk_.knowledgeBaseId, id).build().remove()` —— 走 Query.nativeRemove，HNSW 索引下可能抛 IllegalStateException
- 正例：`val ids = chunkBox.query().equal(KnowledgeChunk_.knowledgeBaseId, id).build().use { it.findIds() }; if (ids.isNotEmpty()) chunkBox.remove(*ids)` —— 走 Box native 路径，规避 #1209
- 来源：US-015 知识库分库数据模型审查（TKN-US015-GUARDRAIL-001，G-01 HIGH 发现；TKN-US015-GUARDRAIL-002 修复验证通过；ac-verifier TKN-US015-AC-001 确认 500 chunk 规模不触发 #1209）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active

#### BR-concurrency-004: MutableStateFlow 跨协程并发写必须用 update 原子 CAS，禁止 `value = value.copy(...)`

- 类别：concurrency
- 规则：`MutableStateFlow.value` 的 setter 本身是原子的，但 `_state.value = _state.value.copy(...)` 是「读 → 改 → 写」三步**非原子**序列。当存在多个协程并发写同一 `MutableStateFlow`（如 ViewModel 的 init 块在 Main 协程 `collect { ... }` 与 `viewModelScope.launch(Dispatchers.IO) { ... }` 的 IO 协程同时写 `_uiState`）时，后写者会覆盖先写者基于已过期值的修改，导致 lost update（库列表/chunkCounts/ingestionState 短暂回退到旧值，UI 不一致）。必须改用 `MutableStateFlow.update { current -> current.copy(...) }`，其内部为 CAS 自旋循环，保证 read-modify-write 原子性。
- 反例：`_uiState.value = _uiState.value.copy(ingestionState = Running(...))` —— Main 协程与 IO 协程并发写时，IO 协程基于过期的 `_uiState.value` copy 后覆盖 Main 协程刚写入的 libraries/chunkCounts，导致状态回退
- 正例：`_uiState.update { it.copy(ingestionState = Running(...)) }` —— CAS 自旋，读到最新值再修改，无 lost update
- 来源：US-018 知识库管理 UI 审查（TKN-US018-GUARDRAIL-001，G-01 中危发现；TKN-US018-GUARDRAIL-002 修复验证通过；ac-verifier TKN-US018-AC-001 确认 35 测试 + 524 全量回归 0 失败）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active（ac-verifier TKN-US018-AC-001 确认 G-01 修复有效，2026-08-07 转 active）

#### BR-concurrency-005: 嵌套 withTimeout 超时层级必须内层短于外层

- 类别：concurrency / error-handling
- 规则：当使用嵌套的 `withTimeout` / `withTimeoutOrNull` 作用域时（如外层 SkillExecutor `withTimeout(30s)` 包裹内层 bridge `withTimeoutOrNull(35s)`），内层超时**必须短于**外层超时。若内层超时更长，外层超时会先触发取消传播，导致：(1) 内层 `withTimeoutOrNull` 的超时返回值（null）不会被生成，语义化超时文案丢失；(2) 内层超时后的清理代码（如 `pending.remove(id)`）不执行，资源残留。`withTimeoutOrNull` 仅捕获自身作用域的 `TimeoutCancellationException`，外部取消会正常传播。正确做法：内层超时 < 外层超时（如 25s < 30s），确保内层先超时、返回语义消息、执行清理，外层超时作为不可达兜底。
- 反例：`withTimeout(30_000) { bridge.requestIntent(intent, timeoutMs = 35_000) }` —— 外层 30s 先超时，bridge 的 35s 超时永不可达，pending.remove 不执行，语义文案 "跨 App 调用超时" 永不返回
- 正例：`withTimeout(30_000) { bridge.requestIntent(intent, timeoutMs = 25_000) }` —— 内层 25s 先超时，返回 null → pending.remove(id) 执行 → 返回 "跨 App 调用超时" → 外层 30s 永不触发
- 来源：M6 Phase C 审查（TKN-M6-PHASEC-GUARDRAIL-001，M-1 中危发现：AppLauncherBridge 超时从 30s 改为 35s 方向错误）
- 添加日期：2026-08-11
- 适用场景：dev
- 状态：active（ac-verifier TKN-M6-PHASEC-ACCEPTANCE-001 验证通过，2026-08-11 转 active。M-1 修复正确（25s < 30s）+ 端到端测试验证 bridge 先超时返回语义化文案 + pending 清理 + ac-verifier 补充测试断言超时层级关系）

#### BR-concurrency-006: ViewModel init 块中访问实例属性的协程必须位于全部属性声明之后

- 类别：concurrency
- 规则：`viewModelScope` 默认使用 `Dispatchers.Main.immediate`，在主线程构造 ViewModel 期间 `init` 块内的 `launch` 会**同步执行**协程体。若该协程体访问的属性（如 `MutableStateFlow` 字段）声明在 init 块**之后**，协程执行时属性尚未初始化 → 抛 NPE；若 NPE 被 `catch (e: Exception)` 兜底吞掉，表现为"init 恢复静默失效"——功能不生效且无可见错误。因此 init 块中访问实例属性的协程必须置于**全部相关属性声明之后**，并在 init 块注释中显式声明该顺序契约。
- 反例：`init { viewModelScope.launch { try { val v = repo.read(); _state.value = v } catch (e: Exception) { Log.w(...) } } }; private val _state = MutableStateFlow(default)` —— launch 体同步执行时 `_state` 未初始化，NPE 被兜底 catch 吞掉，恢复静默失效
- 正例：`private val _state = MutableStateFlow(default); init { viewModelScope.launch { try { val v = repo.read(); _state.value = v } catch ... } } }` —— 属性先声明，init 后执行
- 来源：UXR8 批次1 guardrail 复审规则提议（TKN-UXR8B1-GUARDRAIL-002；原始发现：TKN-UXR8B1-GUARDRAIL-001 期间 ac-verifier 补强发现 init 块声明顺序 NPE 风险，ConversationViewModel.kt init 块已含顺序契约注释）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active（guardrail TKN-UXR8B1-GUARDRAIL-002 确认非重复 + ConversationViewModel 实证）

### interface

#### BR-interface-001: UI 设计必须用户审核通过后方可实现

- 类别：interface
- 规则：任何涉及视觉 UI 设计的任务（聊天界面、设置界面、主题、布局、配色、字体等），主 Agent 必须先产出设计方案提交用户审核，审核通过后方可进入实现阶段。脚手架阶段的空白界面（仅显示标题）不在此规则范围内。
- 反例：直接编写 Compose UI 代码而不先获取用户确认
- 正例：先输出 UI 设计方案描述/线框 → 用户审核通过 → 再编写 UI 代码
- 来源：用户 2026-08-02 明确要求
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-interface-003: 请求历史过滤空占位消息必须排除所有空 content 的 assistant 消息

- 类别：interface
- 规则：构造对话请求历史时，过滤空占位消息必须排除**所有**空 content 的 assistant 消息，而非仅排除当前刚追加的占位。否则上一轮因服务端零增量（仅 `[DONE]`）结束而残留的空 AI 消息仍会进入请求体，被严格 API 拒绝（400）。
- 反例：`_messages.value.filterNot { it.id == aiId }` —— 只排除当前占位，历史遗留空 AI 消息仍入请求体
- 正例：`_messages.value.filterNot { it.role == Role.ASSISTANT && it.content.isEmpty() }`
- 来源：US-006 流式请求修复复审（TKN-NETWORK-US006-001，发现 1）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-interface-004: 请求历史过滤必须同时排除当前 aiId 与所有空 content 的 assistant 消息

- 类别：interface
- 规则：构造对话请求历史时，过滤条件必须同时满足：(1) 排除当前正在生成的 AI 消息（按 `id` 精确匹配），即使其 content 因降级提示（如「⚠️ 知识库检索失败，已降级为普通对话」）已非空——本轮待生成目标不应进 history，且降级提示不应被 Provider 当作上一轮 AI 回复；(2) 排除所有空 content 的 assistant 消息（BR-interface-003）。两者用 `||` 互补，不可仅用其一。仅排除当前 aiId 会漏过历史空 AI 消息；仅排除空 content 会漏过当前非空降级提示消息。
- 反例：`_messages.value.filterNot { it.id == aiId }` —— 历史遗留空 AI 消息仍入请求体；或 `filterNot { it.role == Role.ASSISTANT && it.content.isEmpty() }` —— embed 失败降级提示非空，仍入请求体被 Provider 当作上一轮 AI 回复（语义错误）
- 正例：`_messages.value.filterNot { it.id == aiId || (it.role == Role.ASSISTANT && it.content.isEmpty()) }`
- 来源：US-019 RAG 对话集成审查（TKN-US019-RAG-GUARDRAIL-001，G-02 配套修复 + ac-verifier TKN-US019-RAG-ACCEPTANCE-001 确认；guardrail 倒逼发现的 v1 潜在 bug）
- 添加日期：2026-08-07
- 适用场景：dev
- 状态：active（ac-verifier TKN-US019-RAG-ACCEPTANCE-001 确认修复有效，2026-08-07 转 active）

#### BR-interface-005: Skill systemPrompt 不得作为全局身份注入，须用轻量索引 + 渐进式加载

- 类别：interface / prompt
- 规则：Agent 框架中，启用 Skill 的**完整 `systemPrompt` 不得无条件注入到全局 system message**。若 Skill 的 systemPrompt 含身份声明（如"你是文本改写助手"），注入后会强制 LLM 身份，导致"启用即被角色污染"（即使用户未主动调用该 Skill）。正确做法（渐进式加载）：(1) 提供**默认通用 persona** 作为基础身份，始终注入；(2) 启用 Skill 只注入**轻量索引**（`name（description）`），让 LLM 感知能力存在；(3) 具体是否调用由 LLM 按任务类型判断或用户显式请求，需要时再加载完整指令/工具。
- 反例：`mergeSystemPrompt` 把 `enabledSkills.mapNotNull { it.manifest.systemPrompt }` 全部注入——rewriter 的"你是文本改写助手"污染全局身份
- 正例：`mergeSystemPrompt` 恒以 `DEFAULT_PERSONA` 开头 + 启用 Skill 注入 `可用技能：name（description）`
- 来源：提示词污染 Bug 修复（ADR-019；TKN-PROMPT-POLLUTION-001 考古；TKN-PROMPT-POLLUTION-GUARDRAIL-001 审查；TKN-PROMPT-POLLUTION-ACCEPTANCE-001 验收）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active

#### BR-interface-006: 不可信外部内容渲染前必须做链接 scheme 白名单净化

- 类别：interface / security
- 规则：对**不可信外部内容**（LLM 输出、第三方网页摘要、工具结果回灌等）做富文本/Markdown 渲染时，必须在渲染前净化链接：仅保留 `http://` / `https://` scheme 的链接为可点击，其他 scheme（`intent://`、`file://`、`javascript:`、`data:` 等）降级为纯文本。渲染组件若无法配置受限 uriHandler（或版本 API 缺失），应用纯函数在渲染前改写不可信内容（CWE-116/CWE-601）。同时，对"从外部内容解析出的可点击链接"应在点击跳转处再做一次 scheme 校验（纵深防御）。
- 反例：`Markdown(content = message.content)` 直接渲染 LLM 输出——输出含 `[x](intent://...)` 时点击触发非预期 Intent
- 正例：`Markdown(content = sanitizeMarkdownLinks(message.content))`，`sanitizeMarkdownLinks` 仅放行 http/https，其余降级为纯文本；搜索卡片点击跳转前再次校验 `link.startsWith("http")`
- 来源：UX-001 问题 8 修复（ADR-021；guardrail TKN-UX001-GUARDRAIL-001 F-01）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-interface-007: 第三方 Compose 库的 API 版本差异须以实编译验证而非记忆假设

- 类别：interface / testing
- 规则：引入第三方 Compose 库（如 Markdown 渲染器）时，不可凭记忆/文档假设其 API 参数名与行为，必须**先编译验证**确认实际签名。同一库不同版本 API 可能显著不同（如 `Markdown(markdown=...)` vs `Markdown(content=...)`、`UrlHandler` vs `ReferenceLinkHandler`），记忆假设会导致编译失败或功能缺失。正确做法：读取库版本实际 API（解 aar/jar 检查类签名或先写最小编译用例）后再接入；对不存在的 API 采用"内容净化纯函数"等不依赖库内部的稳健方案。
- 反例：凭记忆写 `Markdown(markdown = content, uriHandler = ...)`——0.15.0 实际参数是 `content` 且无 `UrlHandler`，编译失败
- 正例：先编译验证确认 `Markdown(content = ...)`，用 `sanitizeMarkdownLinks` 纯函数做链接净化（不依赖库内部 handler API）
- 来源：UX-001 问题 3/8 修复（ADR-021；multiplatform-markdown-renderer 0.15.0 API 实测）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-interface-008: 第三方 Compose 库升级必须校验运行期 ABI 兼容（编译期通过 ≠ 运行期可用）

- 类别：interface / testing
- 规则：升级第三方 Compose 库时，**编译期通过不代表运行期可用**。库基于较高 Compose BOM 编译时，其字节码会引用宿主没有的新 ABI（如 `Composer.startReplaceGroup` 方法、`TextLinkStyles` 类），导致运行期 `NoSuchMethodError` / `ClassNotFoundException`（编译 tip 无报错，运行必崩）。升级前必须校验 ABI 兼容：解包库 AAR 的 `classes.jar`，二进制扫描宿主 Compose 版本不存在的符号；或直接安装运行验证。确定兼容版本后，在 `libs.versions.toml` 注释中记录约束与升级前提（如"0.28.0 起需 Compose ≥1.7"），防止未来盲目升级回归崩溃。
- 反例：把 markdown-renderer 从 0.15.0 升到 0.37.0 仅验证编译通过即交付——运行期 `ClassNotFoundException: TextLinkStyles`（0.31+ 引用 Compose 1.7 类）
- 正例：逐版本下载 AAR 解包扫描 `TextLinkStyles`/`startReplaceGroup` 字符串，确认 0.26.0 无引用（兼容 Compose 1.6.8）、0.28.0 起有引用，锁定 0.26.0 并在 toml 注释记录约束
- 来源：UX-001 问题 1 二次修复（ADR-022；markdown-renderer 0.28+ 与 Compose 1.6.8 ABI 不兼容崩溃实测）
- 添加日期：2026-08-15
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-009: 流式协议解析必须保留结构字符，空白过滤用 isNullOrEmpty 而非 isNullOrBlank

- 类别：interface
- 规则：流式协议（如 SSE/OpenAI chat delta）解析增量内容时，**不得用 `isNullOrBlank()` 过滤文本 delta**——`isBlank()` 会把纯换行 `"\n"`（以及纯空格）误判为"空白"丢弃。换行是 markdown 结构字符（标题/列表/表格依赖行首符号），丢失后流式文本粘连成单行、块级解析失效、用户看到裸符号。正确做法：仅过滤 `null` 与空串（`isNullOrEmpty()`），保留空格/换行等结构字符。同理适用于任何"拼接后交给下游结构化解析"的增量流。
- 反例：`if (!content.isNullOrBlank()) events.add(StreamEvent.Delta(content))` —— 流式输出中单独成 chunk 的 `"\n"` 被丢弃，markdown 标题/表格无法解析（ADR-022 问题 1 根因）
- 正例：`if (!content.isNullOrEmpty()) events.add(StreamEvent.Delta(content))` —— 保留换行，markdown 块级解析正常（14 个独立节点分层渲染验证）
- 来源：UX-001 问题 1 二次修复（ADR-022；chunkToEvents isNullOrBlank 丢弃换行实测）
- 添加日期：2026-08-15
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-010: 拼接外部标识符进协议字段前必须校验合法字符集

- 类别：interface
- 规则：把外部用户可控标识符（如 MCP server 名、Skill 名）拼接进协议字段（如 OpenAI tool name、JSON key）前，必须校验/规范化字符集。协议只允许特定字符（OpenAI tool name 仅 `[a-zA-Z0-9_-]`），直接拼接含空格/中文/特殊字符的标识符会导致：请求被 API 拒绝（400 invalid_request_error）或本地过滤后功能不可见。正确做法：拼接前规范化（非法字符替换为 `_`），且**构造与反查两侧使用同一规范化函数**（保证能从协议字段反解回原始标识符）。
- 反例：`"mcp_${server.name}__${tool}"` 直接拼接——`Sequential Thinking`（空格）、`跨 App 调用`（中文）生成非法工具名，LLM 感知不到该工具 / 请求 400
- 正例：`"mcp_${toMcpNamespace(server.name)}__${tool}"`，`toMcpNamespace` 将非 `[a-zA-Z0-9]` 替换为 `_`；`selectMcpServer` 反查时对每个 server.name 同样规范化后匹配
- 来源：UX-001 问题 5/6 二次修复（ADR-022；Sequential Thinking 不可用 + 400 工具重名根因）
- 添加日期：2026-08-15
- 适用场景：dev / bugfix
- 状态：active

### ops

（暂无规则，待累积）

### testing

#### BR-testing-001: 测试替身模拟第三方组件时必须复现原组件关键语义

- 类别：testing
- 规则：为第三方组件（如 DataStore、Room 等）创建测试替身（Fake/Mock）时，必须复现原组件的关键语义（如原子性、串行化、错误传播）。仅模拟接口方法而不复现语义会导致测试通过但生产环境失败。对于 DataStore，其 `updateData` 保证原子串行化语义；测试替身若用简单字段赋值（非 `MutableStateFlow` 原子更新）会在并发测试中产生假阳性。
- 反例：`class FakeDataStore { var data: Preferences = emptyPreferences() }` —— 无原子性保证，并发测试假阳性
- 正例：`class FakeDataStore : DataStore<Preferences> { private val state = MutableStateFlow(initial); override val data = state; override suspend fun updateData(transform) { state.value = transform(state.value) } }` —— 用 `MutableStateFlow` 保证原子性
- 来源：US-003 API Key 加密存储审查（TKN-PRISM-GUARDRAIL-005，G-04 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-testing-002: 集成测试「先启动资源再断言」必须在 try 块内或辅助函数自清理

- 类别：testing
- 规则：集成测试中任何「先启动资源（如起嵌入式服务器）再断言」的辅助函数，必须置于 `try` 块内，或辅助函数自身对失败路径自清理，确保资源获取失败时不留泄漏。若启动成功后取端口/句柄的后续步骤抛异常，已启动的资源仍须被清理。
- 反例：`val port = startMcpServer()` 位于 `try` 之外 —— 若 `startMcpServer()` 内部 `resolvedConnectors()` 取端口失败，`finally { stopServers() }` 不会执行，服务器泄漏
- 正例：`val port = try { startMcpServer() } catch (e: Exception) { stopServers(); throw e }`，或 `startMcpServer()` 内部 `try/catch` 兜底 stop；清理函数对每个 teardown 逐项容错并最终 `clear()`
- 来源：US-008 MCP 集成测试审查（TKN-MCP-CLIENT-GUARDRAIL-005，Q1/Q3）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-testing-003: 真实服务器集成测试的 HttpClient 必须与生产逐项对齐

- 类别：testing
- 规则：起真实服务器的集成测试，其 HttpClient 配置必须与生产（如 `PrismApplication.httpClient`）逐项对齐（engine、插件、`expectSuccess` 等），避免测试环境与生产行为漂移。仅对齐部分配置（如只 `install(SSE)` 而省略 `expectSuccess`）可能导致测试通过但生产行为不一致。
- 反例：测试仅 `HttpClient(OkHttp) { install(SSE) }`，而生产含 `expectSuccess = true` —— 非 2xx 行为在测试与生产间漂移
- 正例：`HttpClient(OkHttp) { install(SSE); expectSuccess = true }`，并用注释说明「与生产 PrismApplication.httpClient 逐项对齐」
- 来源：US-008 MCP 集成测试审查（TKN-MCP-CLIENT-GUARDRAIL-005，Q2）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-testing-004: 新模块设计应考虑纯 JVM 可测性，构造器避免访问 Android Context stub API

- 类别：testing
- 规则：新模块（如 Repository / Registry / Manager）设计时，构造器**禁止**访问 Android `Context` 的 stub API（如 `context.filesDir` / `context.assets` / `context.getSharedPreferences`），因为这些 API 在纯 JVM 单元测试中抛 "not mocked" RuntimeException，阻断测试构造。必须满足以下之一：(1) 将 Context 依赖推迟到方法内部（如 `scanAndSync()` 内构造 `File(context.filesDir, ...)`），构造器仅存储 Context 引用；(2) 将纯逻辑提取到 `companion object` 标记 `internal`，不依赖实例状态/Context，可在纯 JVM 测试中直接验证；(3) 通过构造器参数注入路径/目录（`File` 或 `String`）而非 Context。此外，含 `android.util.Log` 调用的纯 JVM 测试必须在 `app/build.gradle.kts` 配置 `testOptions.unitTests.isReturnDefaultValues = true`，让 Log 等 stub 静态方法返回默认值（0/null）而非抛异常。**目标**：核心业务逻辑（去重/同步/合并/过滤）应有纯 JVM 单元测试覆盖，不依赖 Robolectric/Instrumented test。
- 反例 1：`class SkillRegistry(context: Context, ...) { private val userDir = File(context.filesDir, "skills/user") }` —— 构造器访问 `filesDir`，纯 JVM 测试构造实例即抛 Stub 异常
- 反例 2：`class Foo(context: Context) { fun scan() { Log.w(TAG, "x"); ... } }` + 测试未配 `isReturnDefaultValues` —— Log.w 抛 "not mocked" RuntimeException
- 正例 1：`class SkillRegistry(context: Context, ...) { suspend fun scanAndSync() { val userDir = File(context.filesDir, "skills/user"); ... } }` —— filesDir 推迟到方法内
- 正例 2：`companion object { internal fun dedupByPriority(entries: List<Entry>): List<Entry> = ... }` —— 纯函数提取到 companion，测试直接 `SkillRegistry.dedupByPriority(...)`
- 来源：M4 Phase B 第三轮审查（TKN-M4-PHASEB-GUARDRAIL-003，主 Agent Q2 自我反思 + ac-verifier TKN-M4-PHASEB-ACCEPTANCE-002 受限通过根因：SkillRegistryTest 缺失）
- 添加日期：2026-08-09
- 适用场景：dev
- 状态：active（ac-verifier TKN-M4-PHASEB-ACCEPTANCE-003 §7 确认转 active，2026-08-09。规则可执行 + 非重复 + SkillRegistry 重构后实现符合全部 4 条要求 + 39 测试验证规则精神）

#### BR-testing-005: Compose 状态持有可变列表时禁止原地改值

- 类别：testing
- 规则：将可变列表持有于 Compose 状态时，禁止对 `mutableStateOf(list)` 中的列表**原地改值**（如 `xs[i] = v`、`xs.add(...)`），因为 `mutableStateOf` 只侦测引用变化，原地改值不触发重组，导致 UI 不回显/丢输入。必须重建新列表，或使用 `mutableStateListOf`（其内部为快照列表，原地改值亦触发重组），与删除/新增行为保持一致。
- 反例：`var xs by remember { mutableStateOf(mutableListOf(...)) }; xs[i] = v` —— 界面不更新
- 正例：`val xs = remember { mutableStateListOf<Pair<String,String>>() }; xs[i] = v` —— 原地改值触发重组
- 来源：US-007 自定义 headers 编辑器审查（TKN-US007-GUARDRAIL-001，Q2）
- 添加日期：2026-08-06
- 适用场景：dev
- 状态：active

#### BR-testing-008: OpenAI 工具 schema 合法性须有结构断言测试，防 Provider 400 复发

- 类别：testing
- 规则：凡是构建 OpenAI 兼容工具定义（`buildToolDefinitions()` / `buildToolDefinition()`）的模块，测试必须包含**结构断言**：`parameters` 为 `type:object`、`properties` 存在、任何数组属性（`sheets`/`questions`/`options` 等）的 value 是 `{"type":"array","items":{...}}` 结构（而非裸数组字面量）。根因：数组属性 schema 非法时，Provider（DeepSeek/OpenAI）对**所有请求**返回 400 `Invalid schema for function 'xxx'`，常规单元测试（测 execute 生成文件）无法发现，只有 schema 结构断言能捕获。参见 [BR-interface-016]。
- 反例：`DocumentLocalToolExecutorTest` 仅测 `parseMarkdownBlocks`/`sanitizeFilename`/`execute` 生成文件，未断言 `buildToolDefinitions()` 的 schema 结构 —— document__create_xlsx sheets 裸 JsonArray 未被测试发现，真机全请求 400 视觉不可用
- 正例：`buildToolDefinitions()` 测试断言 `parameters["type"]=="object"`、`properties["sheets"]["type"]=="array"`、`properties["sheets"]["items"] != null`；全库 `to JsonArray(` 扫描确认仅 enum/required 合法用法
- 来源：UXR8 真机测试 Bug1（TKN-UXR8-FIX-ACVERIFY-001，ac-verifier 建议 #1 schema 结构断言回归锚点）
- 添加日期：2026-08-17
- 适用场景：dev / bugfix
- 状态：active（guardrail TKN-UXR8-FIX-GUARDRAIL-001/002 + ac-verifier TKN-UXR8-FIX-ACVERIFY-001 确认，2026-08-17 转 active）

#### BR-testing-009: Compose 可点选状态的 MutableSet 必须用不可变 Set + 值替换，禁止原地改值

- 类别：testing
- 规则：`mutableStateMapOf<Int, MutableSet<String>>()` 中 MutableSet 作为 map value 时，其 `.add`/`.remove`/`.clear` 等原地改值操作**不触发 Compose 重组**（MutableSet 不是 Compose 快照类型，Snapshot mutation 检测依赖委托对象被替换引用）。必须改为 `mutableStateMapOf<Int, Set<String>>()`，每次修改通过**替换整个 value 引用**触发重组（如 `selected[key] = newSet`）。`mutableStateListOf` 快照列表可原地改值；`mutableStateMapOf` 的 value 修改必须靠引用替换。
- 反例：`val selected = mutableStateMapOf<Int, MutableSet<String>>(); selected[0]?.add("A")` —— 点击后选中态 UI 不更新，用户感知"无法点击"
- 正例：`val selected = mutableStateMapOf<Int, Set<String>>(); selected[0] = (selected[0] ?: emptySet()) + "A"` —— 替换 value 引用，快照识别变化触发重组
- 来源：UXR8-R3 Bug3 AskUserSheet 选中态修复（guardrail TKN-UXR8-R3-GUARDRAIL-001/002 + ac-verifier TKN-UXR8-R3-ACCEPTANCE-001 确认）
- 添加日期：2026-08-17
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-017: 关键词过滤必须整句归一化精确匹配，禁止前缀匹配

- 类别：interface
- 规则：实现启发式关键词过滤（如跳过 RAG 检索、跳过工具调用等）时，必须使用**整句归一化精确匹配**（先 lower-case + 剥离标点空白归约，再判断归约结果是否属于跳过集合）。**禁止前缀匹配**（`startsWith`）——前缀匹配会错误拦截"你好，帮我查一下xxx"（含真实查询内容）、"谢谢，请总结一下…"（含明确检索意图）等**含查询内容的**长句，造成漏检/功能异常。归约函数必须为纯函数且可测。
- 反例：`RAG_SKIP_KEYWORDS.any { queryText.lowercase().startsWith(it) }` —— "你好，帮我查一下知识库"因前缀"你好"被拦截，检索请求被静默跳过
- 正例：`normalizeRagText(queryText) !in RAG_SKIP_PHRASES` —— 归约后整句精确匹配，"你好帮我查一下知识库"不在集合内 → 照常检索
- 来源：UXR8-R3 Bug1 RAG 预判误伤（guardrail H-1 TKN-UXR8-R3-GUARDRAIL-001 + ac-verifier TKN-UXR8-R3-ACCEPTANCE-001 确认）
- 添加日期：2026-08-17
- 适用场景：dev / bugfix
- 状态：active

#### BR-error-handling-017: isTyping 守卫期间的消息/提示不得静默丢弃，必须入队或给用户可见反馈

- 类别：error-handling
- 规则：`sendMessage` 的 `isTyping` 守卫（防并发 AI 回路状态撕裂）期间，**带图消息**和**系统提示**（如编码失败提示）不得静默丢弃——用户感知为"上传了但没反应"或"点了几次没效果"。必须：
  (1) 带图消息入 FIFO 队列（`ArrayDeque`），AI 回复完成后由 `finally` 自动逐条发送；
  (2) 系统提示（如编码失败）入独立提示队列，回复完成后优先显示；
  (3) 队列加大小上限（如 8 张），超限提示用户而非静默丢弃；
  (4) 纯文本消息保持原守卫行为（用户可稍后重发，避免无限排队）。
- 反例：`if (_isTyping.value) return` —— 带图消息被静默丢弃，无气泡/无提示/LLM 收不到，用户感知"图片无法上传"
- 正例：`if (_isTyping.value) { if (!imageUrl.isNullOrBlank()) { pendingImageQueue.addLast(trimmed to imageUrl) }; return }` —— 暂存队列 + finally flush
- 来源：UXR8-R3 Bug2 图片无法上传修复（guardrail TKN-UXR8-R3-GUARDRAIL-001/002 + ac-verifier TKN-UXR8-R3-ACCEPTANCE-001 确认）
- 添加日期：2026-08-17
- 适用场景：dev / bugfix
- 状态：active

#### BR-error-handling-018: 暂存队列 flush 处理完提示后不得 return，必须继续处理队列主体

- 类别：error-handling
- 规则：当暂存队列同时含有「一次性系统提示」与「待处理主体」（如图片/消息）时，`flush` 方法在处理完提示后**不得提前 return**——提示写入不会触发新一轮回调/finally 来驱动下一次 flush，若 return 则主体（图片等）永久滞留，表现为"溢出提示出现了但图片 0 张送达"。正确的 flush 语义：处理提示后仍须继续消费并处理一条主体（保持逐条串行：每次最多处理一条主体，多条由后续轮次 finally 接力）。
- 反例：`if (notice != null) { appendSystemNotice(notice); return }` —— 溢出提示 + 多图同时入队时图片 0 送达（UXR9 回归测试实际发现）
- 正例：`if (notice != null) { appendSystemNotice(notice) }; val pending = queue.removeFirstOrNull() ?: return; sendMessage(...)` —— 提示与一条主体在同一 flush 内先后处理
- 来源：UXR9 Bug3 图片上传回归（image queue overflow 测试 0 送达，主 Agent 定位）
- 添加日期：2026-08-18
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-018: Kotlin override 一律不得声明默认参数值（默认值只在接口/基类声明）

- 类别：interface
- 规则：Kotlin 规则——**无论基类是否声明默认值，override 函数一律不得再声明默认参数值**（编译报 `An overriding function is not allowed to specify default values for its parameters`）。默认值只能在接口/基类声明一次，override 只写参数类型。若具体类型调用方需要省略参数（接口默认值经接口类型调用才生效，经具体类型调用不生效），须在具体类中增加一个**非 override 的单参便捷重载**委托到主函数（`fun encode(text: String) = encode(text, 512)`）。
- 反例：`interface T { fun e(s: String, n: Int = 512) }` + `class C : T { override fun e(s: String, n: Int = 512) }` → 编译错误；或去掉接口默认值后各 override 自带默认 → 同一错误
- 正例：接口声明默认值 `fun e(s: String, n: Int = 512)`；override 不声明默认值 `override fun e(s: String, n: Int)`；具体类型需省参时加非 override 便捷重载
- 来源：UXR9 US-901 多语言 tokenizer 抽象（BertWordPieceTokenizer/UnigramTokenizer 编译失败，主 Agent 修正）
- 添加日期：2026-08-18
- 适用场景：dev
- 状态：active

#### BR-ops-003: Edit 报告成功后必须用 git diff 验证磁盘持久化（防写竞争回滚）

- 类别：ops
- 规则：本会话多次出现「Edit 工具报告成功（回显含修改内容）但磁盘文件随后被回滚到旧内容」，直至编译报 Unresolved reference 才发现。为防此类静默丢失：对**关键文件**（多轮编辑过的文件）执行 Edit 后，必须用 `git diff <file> | Select-String <关键词>` 或 Read 立即验证目标改动已落盘；若发现回滚，改用 **Write 全量重写**（已证可靠）而非重复 Edit。同文件多处修改务必按 BR-ops-002 串行执行。
- 反例：Edit 回显显示 `PrismError` 已替换，但 git diff 显示磁盘仍为 `PrismRed`，编译 `Unresolved reference 'PrismRed'`（UXR9 实际发生 3 次）
- 正例：Edit 后立即 `git diff` 验证关键词存在；确认回滚后用 Write 整文件重写并再次 git diff 验证
- 来源：UXR9 开发期（ConversationScreen.kt / TestDocumentFactory.kt / CrossSessionMemoryManager.kt 多次 Edit 回滚）
- 添加日期：2026-08-18
- 适用场景：dev
- 状态：active

#### BR-ops-004: 用 PowerShell 修改源码文件前必须统一 CRLF→LF 或按行数组拼接，且多行替换后按字节级校验

- 类别：ops
- 规则：在 Windows PowerShell 中用 `ReadAllText`/`String.Replace` 对源码做**多行字符串替换**时，目标文件可能是 CRLF（git autocrlf）而脚本构造的块为 LF，`Replace` 静默不命中（返回 0 替换却"看似成功"），导致改了却未落盘直到编译失败。可靠做法：(1) 替换前 `.Replace("`r`n","`n")` 归一化再写回（整文件转 LF，Kotlin/git 可接受）；(2) 或按 `ReadAllLines` 行数组 + `List.RemoveRange`/`Insert` 拼接后 `WriteAllLines`（绕过 here-string 的引号/行尾坑），但对大文件需确认 `ReadAllLines` 长度与 `Get-Content` 一致（不一致说明编码/行尾异常，禁止直接覆盖）；(3) 写回后必须**字节级**用 `Get-Content -Encoding UTF8` 复验目标内容已变化（BR-ops-003）。禁止用 `@'...'@` here-string 构造含 `>`/`（`/中文/`$i` 的多行块传给 `Replace`——终端会分页展开引号导致内容与文件不符。
- 反例：`$content.Replace($oldBlock, $newBlock)`（$oldBlock 为 LF here-string，文件为 CRLF）→ 0 次替换，文件未变，编译报 Unresolved reference；或 `ReadAllLines`（738 行）与 `Get-Content`（597 行）长度不一致仍直接覆盖 → 丢行/错乱
- 正例：`$content=$content.Replace("`r`n","`n"); $content=$content.Replace($old,$new)`（先归一化行尾再替换再写回）+ WriteAllLines 前校验行数 / 写回后 UTF8 复验关键词存在
- 来源：v1 真机二次修复（2026-08-19）CrossSessionMemoryManager.kt 多行函数替换多次不命中（CRLF vs LF）+ ReadAllLines/Get-Content 行数不一致；ConversationScreen.kt/sanitize 正则多行替换（BR-ops-003 会话）
- 添加日期：2026-08-19
- 适用场景：dev
- 状态：active

#### BR-error-handling-019: RAG 嵌入模型必须匹配检索语种，英文模型对中文语义区分度差

- 类别：error-handling
- 规则：端侧嵌入模型（embedding）必须与知识库实际语种匹配。**英文 BERT 词表模型（如 all-MiniLM-L6-v2 uncased）对中文语义区分度极差**——无关中文片段余弦相似度普遍 0.4~0.7，任何单一阈值都无法干净分隔相关/无关，表现为「无关资料必被注入引用来源」的结构性 RAG 污染。治本方案是换多语言模型（如 paraphrase-multilingual-MiniLM-L12-v2 + 对应 tokenizer）并**用真实模型实测句对相似度分布校准阈值**（相关 0.58+/无关 ≤0.32 → 阈值取分隔区）。禁止仅靠调阈值缓解英文模型的中文 RAG 污染（多次证明无效）。
- 反例：英文 MiniLM 阈值 0.5 拦不住无关中文片段（0.4~0.7），多轮修复无效
- 正例：多语言 MiniLM qint8 + Unigram tokenizer，实测分布后阈值 0.5，相关/无关干净分隔
- 来源：UXR9 Bug1 RAG 无关资料注入（多轮未愈老 Bug，换模型根治，ChineseSimilarityDiagnosticTest 实测校准）
- 添加日期：2026-08-18
- 适用场景：dev / bugfix
- 状态：active

### docs

（暂无规则，待累积）

### build

#### BR-build-001: AGP 与 Gradle 版本必须匹配

- 类别：build
- 规则：声明 AGP 版本时，必须同步核实并配置满足最低要求的 Gradle Wrapper 版本。AGP 版本与最低 Gradle 版本对应关系见 [Android 官方文档](https://developer.android.com/build/releases/gradle-plugin)。修改任一版本时必须交叉验证兼容性。
- 反例：AGP 8.13.0 + Gradle 8.11.1（不满足最低 8.13，构建必失败）
- 正例：AGP 8.13.0 + Gradle 8.13（满足最低要求）
- 来源：US-001 M0 脚手架审查（TKN-PRISM-GUARDRAIL-001，G-01 阻断级发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-002: Windows 环境生成的 shell 脚本提交前必须设置可执行权限

- 类别：build
- 规则：在 Windows 环境（`core.filemode=false`）下生成的 Unix shell 脚本（如 `gradlew`、`mvnw`），提交到 git 前必须通过 `git update-index --chmod=+x <file>` 设置可执行权限，或创建 `.gitattributes` 文件确保跨平台权限与行结束符正确。否则 CI/CD 在 Linux 上执行时会报 Permission denied。
- 反例：在 Windows 上 `git add gradlew` 后直接 commit，未设置 `+x` 权限，Linux CI 执行 `./gradlew` 报 Permission denied。
- 正例：创建 `.gitattributes`（含 `gradlew text eol=lf`）+ `git update-index --chmod=+x gradlew` + commit。
- 来源：US-001 M0 脚手架第二轮审查（TKN-PRISM-GUARDRAIL-002，G-09 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-003: 第三方 Maven 镜像应使用 content 过滤限定包来源

- 类别：build
- 规则：配置第三方 Maven 镜像时，应尽可能使用 `content { includeGroupByRegex(...) }` 或 `excludeGroupByRegex(...)` 过滤，限定镜像只提供特定包名前缀的依赖。特别是 public/central 类聚合镜像应排除 AndroidX 组（`com.android.*` / `androidx.*`），确保这些依赖只从 google 镜像获取，降低交叉投毒风险。
- 反例：`maven { url = uri("https://maven.aliyun.com/repository/public") }`（无 content 过滤，任何包都可能从此镜像拉取）
- 正例：`maven { url = uri("https://maven.aliyun.com/repository/public"); content { excludeGroupByRegex("com\\.android.*"); excludeGroupByRegex("androidx.*") } }`
- 来源：US-001 M0 第三轮审查（TKN-PRISM-GUARDRAIL-003，G-10 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-004: ObjectBox JNI 本地库文件必须加入 .gitignore

- 类别：build
- 规则：ObjectBox Gradle 插件在运行 JVM 测试时会在 `app/` 目录下复制平台特定 JNI 本地库文件（如 `objectbox-jni-windows-x64.dll`、`objectbox-jni-linux-x64.so`、`objectbox-jni-macos-x64.dylib`）。这些文件是运行时产物，平台特定且体积大（~2MB+），必须在 `.gitignore` 中显式排除，禁止提交到版本控制。
- 反例：安装 ObjectBox 后运行测试，`app/objectbox-jni-windows-x64.dll` 出现为 untracked 文件，.gitignore 未排除，`git add -A` 导致 2.18MB 二进制文件入库
- 正例：`.gitignore` 追加 `app/objectbox-jni-windows-x64.dll` 等精确路径排除规则
- 来源：US-002 ObjectBox 集成审查（TKN-PRISM-GUARDRAIL-004，G-01 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-005: ObjectBox schema 模型文件必须提交到版本控制

- 类别：build
- 规则：ObjectBox 插件生成的 `app/objectbox-models/default.json` 是数据库 schema 模型文件，文件内含唯一 ID 映射，ObjectBox 官方明确要求 "KEEP THIS FILE! Check it into a version control system (VCS) like git."。提交代码时必须显式 `git add` 此文件，确保跨开发者/CI 的 schema 一致性。
- 反例：ObjectBox 集成后 `default.json` 为 untracked，提交时遗漏，导致其他开发者/CI 构建时 schema ID 不一致
- 正例：提交清单包含 `app/objectbox-models/default.json`，确保入库
- 来源：US-002 ObjectBox 集成审查（TKN-PRISM-GUARDRAIL-004，G-03 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

### animation

#### BR-animation-001: spring 欠阻尼动画值传入非负约束修饰符前必须 coerceIn 钳制

- 类别：animation
- 规则：`animateDpAsState` / `animateFloatAsState` 配合 `spring(dampingRatio < 1.0)`（欠阻尼）时，动画值会产生过冲（overshoot），短暂超出目标值范围。若该动画值传入 `Modifier.padding()` / `Modifier.size()` / `Modifier.width()` / `Modifier.height()` 等有非负约束的修饰符，过冲产生的负值会触发 `IllegalArgumentException: Padding must be non-negative`（或类似异常）导致 App 闪退。必须用 `coerceIn(min, max)` 或 `coerceAtLeast(0.dp)` 钳制动画值到安全范围后再传入非负约束修饰符。过冲量计算公式：`overshoot = exp(-πζ/sqrt(1-ζ²))`，ζ 为阻尼比（DampingRatioMediumBouncy = 0.5，过冲约 16.3%）。
- 反例：`val offset by animateDpAsState(targetValue = if (checked) 18.dp else 2.dp, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)); Box(Modifier.padding(start = offset))` —— checked true→false 时 offset 过冲到约 -0.6dp，padding 抛 IllegalArgumentException 闪退
- 正例：`Box(Modifier.padding(start = offset.coerceIn(0.dp, 18.dp)))` —— 钳制到非负范围，保留 spring 物理视觉，过冲部分截断不影响整体动画效果
- 来源：PrismSwitch padding 负值崩溃修复（TKN-BUGFIX-PRISMSWITCH-001，B3 致命，6 处开关页面闪退，logcat 3 次崩溃 PID 2859/2919/2975/3028）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active（guardrail TKN-BUGFIX-PRISMSWITCH-001 通过 + ac-verifier TKN-BUGFIX-PRISMSWITCH-002 通过，2026-08-12）

### ui

#### BR-ui-001: 占位 UI 的开关默认值必须为关闭态，避免误导用户以为功能已启用

- 类别：ui
- 规则：尚未实现的功能若在 UI 中保留占位开关（如生物识别解锁、未来特性预览），开关默认值必须为 `false`（关闭态），且副标题或说明文案必须明确标注"即将支持"/"未启用"等字样。禁止将占位开关默认值设为 `true`，否则用户会误以为功能已启用（如误以为 App 已有生物识别保护），构成虚假安全承诺。占位开关的 state 应使用 `remember { mutableStateOf(false) }`（本地状态，重启重置），不应使用 DataStore 持久化（持久化意味着功能已实现）。
- 反例：`var biometric by remember { mutableStateOf(true) }` + 副标题"可选二次解锁" —— 用户以为生物识别已启用，实际是纯 UI 占位无任何保护
- 正例：`var biometric by remember { mutableStateOf(false) }` + 副标题"即将支持 · 可选二次解锁" —— 明确告知用户功能未实现，默认关闭不误导
- 来源：PrismSwitch 崩溃修复中发现的生物识别占位误导（TKN-BUGFIX-PRISMSWITCH-001，code-archaeologist 考古报告任务 4）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active（guardrail TKN-BUGFIX-PRISMSWITCH-001 通过 + ac-verifier TKN-BUGFIX-PRISMSWITCH-002 通过，2026-08-12）

#### BR-ui-002: 底部弹层容器必须支持滚动 + 限制最大高度，防止内容超长时按钮被裁剪

- 类别：ui
- 规则：底部弹层（BottomSheet）容器必须同时满足：(1) **限制最大高度**——宿主容器（如 `PrismSheetHost`）须用 `heightIn(max = 可用高度 * 0.9f)` 限制 sheet 最大高度，**可用高度必须按 IME 双模式自适应判定**（UXR8 Bug3 OBS-2 终版，ADR-028）：Android 存在两种互斥键盘处理机制——**resize 模式**（`adjustResize` window resize 生效，ComposeView 约束已被系统压缩，此时 `WindowInsets.ime` 仍报完整键盘高度，若再 `imePadding()` 会双重扣除导致弹层塌缩）与 **insets 模式**（decorFitsSystemWindows 完全生效，window 不压缩，须 `imePadding()` 单一来源平移）。判定式：`BoxWithConstraints` 读父级约束 `parentMax`，`parentMaxPx < screenPx − imePx/2` → resize 模式（不加 imePadding，maxSheet = parentMax×0.9）；否则 insets 模式（imePadding + maxSheet = (parentMax−ime)×0.9）。无限约束时保守假设 resize 模式。(2) **内容区可滚动**——sheet 内部内容区（`PrismSheet` 的 content Column）须用 `weight(1f, fill = false)` + `verticalScroll(rememberScrollState())`，当内容超出最大高度时自动滚动，`fill = false` 确保内容少时不强制填满；(3) **系统 UI 适配**——`navigationBarsPadding()` 适配导航栏（两模式均实际扣除，insets 自适应无重复风险）。缺少任一层都会导致内容超长时底部按钮（如"保存配置"）被裁剪到屏幕外不可见/不可点击，用户误以为功能失效。
- 反例：`PrismSheet` content 用 `Column(padding(...)) { content() }` 无 verticalScroll + `PrismSheetHost` sheet Box 无 heightIn 限制 —— ProviderEditSheet 内容约 700dp 超出屏幕，"保存配置"按钮被裁剪到屏幕外，用户只看到"激活"和"删除"按钮，误以为无法保存
- 正例：`PrismSheetHost` 用 `BoxWithConstraints(navigationBarsPadding)` 读 parentMax → 双模式判定 `imeAppliedByParent` → `Box(.then(if (imeAppliedByParent) Modifier else Modifier.imePadding()).heightIn(max = maxSheetHeight))` + `PrismSheet` content Column 加 `.weight(1f, fill = false).verticalScroll(rememberScrollState())` —— 内容超长时可滚动，键盘弹出时弹层按实际可用空间限高（模拟器实测 resize 模式 maxSheet=331.85dp 弹层完整呈现，修复前双重扣除塌缩至 85dp），所有按钮可见可点击
- 来源：DEF-001 Provider 配置保存功能双 Bug 修复（TKN-DEF001-ROOTCAUSE-002，考古报告 §2 假设 1-1 主根因：PrismSheet 无滚动支持 + PrismSheetHost 未限制高度）；2026-08-16 UXR8 Bug3 + OBS-1 修正（TKN-UXR8B1-ACCEPTANCE-001）：高度公式改按可用高度计算、修饰符顺序 padding 在前；2026-08-16 OBS-2 终版（TRAE-debugger 约束探针，docs/reports/2026-08-16-uxr8-b1-bug3-obs2-debug.md）：发现 adjustResize window resize 与 imePadding 双重扣除，改双模式自适应判定
- 添加日期：2026-08-12（2026-08-16 两轮修正）
- 适用场景：dev
- 状态：active（guardrail TKN-DEF001-GUARDRAIL-001 确认非重复 + ac-verifier TKN-DEF001-ACCEPTANCE-001 验证通过，2026-08-12 转 active；guardrail TKN-UXR8B1-GUARDRAIL-003 强制同步修正 2026-08-16；OBS-2 双模式终版同日修正）

#### BR-ui-003: 底部弹层关键操作按钮须放在固定 footer 区域，不参与滚动

- 类别：ui
- 规则：底部弹层（BottomSheet）中的关键操作按钮（如"保存配置"、"删除"等）须放在固定 footer 区域（不参与 verticalScroll），而非放在可滚动 content 末尾。即使 BR-ui-002 的滚动机制已生效，按钮在 content 末尾仍需滚动才能可见，违反"关键操作始终可见"原则。footer 区域须在 content Column 之下独立渲染，不参与 `weight(1f, fill = false).verticalScroll()`，确保任何内容长度下按钮始终固定在 sheet 底部可见可点击。
- 反例：`PrismSheet(title="编辑") { PrismField(...); ...; PrismButton(text="保存配置", onClick={...}) }` —— 保存按钮在 content 末尾，内容超长时需滚动才能可见
- 正例：`PrismSheet(title="编辑", footer={ PrismButton(text="保存配置", onClick={...}) }) { PrismField(...); ... }` —— 保存按钮在 footer 固定底部，始终可见
- 来源：DEF-001 Provider 配置保存功能双 Bug 修复（TKN-DEF001-GUARDRAIL-001，BR-ui-002 滚动机制补充：滚动虽解决裁剪但按钮仍需滚动可见，footer 固定才是根本方案）
- 添加日期：2026-08-12
- 适用场景：dev
- 状态：active

#### BR-security-007: 第三方/外部不可信内容回灌 LLM 前必须做长度截断 + 控制字符过滤 + 边界标记

- 类别：security
- 规则：任何第三方/外部来源的内容（如网页搜索结果、外部 API 响应）回灌 LLM 上下文前，必须：(1) 长度截断（防 token 溢出）；(2) 过滤控制字符/孤立代理项/超范围码点（防 NUL/控制字符注入）；(3) 加「不可信内容」边界标记（降低 prompt 注入影响）。工具 description 也应声明内容来源不可信。
- 反例：搜索结果无前缀直接回灌 LLM —— 第三方网页内容可引导 LLM 执行恶意指令（prompt 注入）
- 正例：`buildString { append("【网络搜索外部内容，未经验证，仅作参考】\n"); ... }` + `take(200)` 截断 + 数字实体控制字符过滤
- 来源：问题 8 联网搜索（TKN-P8-GUARDRAIL-001 S-2，WebSearchLocalToolExecutor）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-error-handling-011: 工具入参校验必须同时处理 null 与 blank，禁止仅判 null

- 类别：error-handling
- 规则：LLM 工具入参校验必须同时处理 null 与 blank（`isBlank()`/`isNotEmpty()`），不能仅判 null。空串/空白串会绕过校验发出无效请求或返回误导性结果。
- 反例：`arguments["query"]?.toString()?.trim() ?: return "缺少参数"` —— 空串/全空白串被放行
- 正例：`arguments["query"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return "缺少参数"`
- 来源：问题 8 联网搜索（TKN-P8-GUARDRAIL-001 M-2，WebSearchLocalToolExecutor.execute）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-build-006: 临时性构建/调试改动（如临时 ABI、临时开关）禁止随功能提交入库

- 类别：build
- 规则：临时性构建/调试改动（如为模拟器临时加 x86_64 ABI、临时开关）禁止随功能提交入库，提交前必须还原或拆分独立 commit。带"测试后恢复"注释的临时改动会污染工程规范（ADR-017 4.8 明确生产 ABI 范围）。
- 反例：`abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") // 临时加入 x86_64（测试后恢复）` 并入功能提交
- 正例：提交前还原为 `listOf("arm64-v8a", "armeabi-v7a")`；模拟器调试用独立分支/独立 commit
- 来源：问题 1-7 审查（TKN-P17-GUARDRAIL-001 M-1，app/build.gradle.kts 临时 x86_64）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-error-handling-012: 正则 replace 回调使用 groupValues[i] 前必须确认正则含捕获组

- 类别：error-handling
- 规则：`Regex.replace(input) { match -> match.groupValues[i] }` 中，若正则不含捕获组 i，`groupValues[i]` 必抛 `IndexOutOfBoundsException`。使用前必须确认正则含捕获组（用 `Regex("""...(...)...""")`），或改用 `match.value`（整体匹配，与捕获组数量无关）。修复引入的正则必须立即补单测锁定。
- 反例：`CODE_BLOCK_PATTERN = Regex("""```[\s\S]*?```""")`（无捕获组）+ `m.groupValues[1]` —— 含代码块的回复闪退（B3 致命）
- 正例：无捕获组正则用 `m.value`；行内代码正则含捕获组 `Regex("""`([^`\n]+)`""")` 时用 `m.groupValues[1]`
- 来源：问题 2 修复（TKN-P17-GUARDRAIL-002 B-2，ConversationScreen.stripMarkdownSymbols）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-interface-006: 接口新增方法时路由/门面类必须同步覆写转发，禁止继承默认空实现

- 类别：interface
- 规则：接口新增方法（尤其带默认空实现）时，所有实现类——尤其是路由/门面类（Dispatcher/Facade）——必须显式覆写并按需转发到下游，禁止依赖接口默认实现。否则生产链路通过门面类调用时静默返回默认空结果，底层实现成为不可达代码，功能"看似修复实则未修复"。
- 反例：`McpToolProvider` 新增 `describeTools`（默认 emptyList）后，`McpToolProviderDispatcher` 未覆写 —— 生产链路 `dispatcher.describeTools` 恒空，MCP 工具无法注入 LLM（Bug-3 实际未修复，guardrail 才查出）
- 正例：Dispatcher 显式覆写 `describeTools` 并按 serverType 转发到 local/remote
- 来源：问题 3 修复（TKN-P17-GUARDRAIL-001 B-1，McpToolProviderDispatcher）
- 添加日期：2026-08-14
- 适用场景：dev
- 状态：active

#### BR-interface-011: 携带 tool_calls 的 assistant 消息必须回传 reasoning_content（DeepSeek 协议约束）

- 类别：interface
- 规则：DeepSeek 思考模式下，携带 `tools` 参数的请求，后续所有轮次的 assistant 消息（含 tool_calls 回放占位）必须回传 `reasoning_content` 字段，否则 API 返回 400 "The `reasoning_content` in the thinking mode must be passed back to the API"。`MessageBody` 必须包含 `reasoning_content` 字段，`ChatMessage.toMessageBody()` 的 ASSISTANT 分支必须从 `thinkingChain` 填充该字段，`SkillExecutor.buildAssistantToolCallMessage` 构造的占位消息必须携带 `thinkingChain`。无思考的端点（`thinkingChain` 为空/null）不输出该字段（`explicitNulls=false` 省略），零影响。
- 反例：`MessageBody` 仅有 `role/content/tool_call_id/tool_calls` 四字段 → 工具回路第 2 轮必 400（B3 致命，深度思考开启 + 联网搜索/GitHub/Sequential Thinking 全部触发）
- 正例：`MessageBody` 新增 `@SerialName("reasoning_content") reasoningContent: String? = null`；`toMessageBody()` ASSISTANT 分支传 `reasoningContent = thinkingChain`；`executeLoop` 累积 `roundReasoning` 传入 `buildAssistantToolCallMessage`
- 来源：UXR4 问题 1/4/6 修复（TKN-UXR4-ARCHAEOLOGY-001 + DeepSeek 官方 thinking_mode 文档 + GitHub openclaw#71037 同类根因，2026-08-15）
- 添加日期：2026-08-15
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-012: 会话持久化仅在有新消息（脏标记）时写库，避免"只读打开"刷新 updatedAt

- 类别：interface
- 规则：会话持久化（`persistSession`）必须引入脏标记机制：仅当有新消息（sendMessage/编辑重发）时置位，回答完成（Done/Error）落库后清位；`loadSession` 加载历史会话后清位。`persistSession` 检查脏标记，无标记时跳过写库。避免"只读打开历史会话再退出"刷新 updatedAt（会话被错误顶到"刚刚"），确保 `updatedAt = 最后消息结束时刻`。
- 反例：`persistSession` 无条件写库（`onCleared`/`startNewConversation`/`loadSession` 均调用），用户打开历史会话查看后退出 → `updatedAt` 被刷新为"现在" → 列表错误置顶
- 正例：`messagesDirty` 脏标记 + `persistSession` 无标记时 `return`；`loadSession` 清位；回答完成（Done/Error）落库并清位；工具回路 `executeWithToolLoop` finally 落库
- 来源：UXR4 问题 8/9 修复（ADR-024 子决策 D，2026-08-15）
- 添加日期：2026-08-15
- 适用场景：dev
- 状态：active

#### BR-ui-004: 工具执行阶段 activeTool/isTyping 应由回路结束统一清除，而非 Done 事件即清除

- 类别：ui
- 规则：工具调用回路中，`activeTool` 和 `isTyping` 的生命周期应从"Done 事件即清除"改为"工具回路（executeLoop）结束统一清除"。具体：`ToolCallStart` 置位 → `ToolCallComplete` 保持（+ 置 isTyping=true）→ 回路结束（`executeWithToolLoop` finally）统一清除。非工具回路（executePlainStream）保持"Done 清除"原行为。避免工具执行阶段（联网搜索/MCP 调用耗时数秒）UI 呈空白（指示一闪而过）。
- 反例：`handleStreamEvent(Done) { _isTyping=false; _activeTool=null }` → Done 紧跟 ToolCallComplete 到达，工具尚未执行但指示已清除，执行期用户看到空白
- 正例：`toolLoopActive` 标志位区分工具回路与非工具回路；`Done` 时检查 `toolLoopActive`，仅 false 时清除；`executeWithToolLoop` finally 统一复位
- 来源：UXR4 问题 7/10 修复（ADR-024 子决策 C，2026-08-15）
- 添加日期：2026-08-15
- 适用场景：dev
- 状态：active

#### BR-ui-005: padding 类修饰符与约束类修饰符组合时必须推演约束传递顺序，padding 须在约束类之前

- 类别：ui
- 规则：同一 Modifier 链中同时使用 padding 类（`imePadding`/`navigationBarsPadding`/`padding`）与约束类（`heightIn`/`widthIn`/`requiredHeight`）修饰符时，必须按"**外→内传递约束**"语义显式推演最终约束，且**顺序必须是 padding 在前、约束类在后**。原因：约束类的 max/min 作用于**其后（内侧）**收到的坐标系——若约束类在前，其阈值按外侧全坐标系列出，随后 padding 会在该阈值内**再扣一次**对应尺寸（双重扣除）；padding 在前则先把坐标系平移/收缩到目标区域，约束类再按已收缩坐标系收紧，语义与阈值公式一致。**但顺序正确不等于总量正确**（OBS-2 教训）：`adjustResize` window resize 已在 View 层压缩约束时，`WindowInsets.ime` 仍报完整键盘高度，链内 `imePadding()` 会与外层 resize 双重扣除——IME 适配必须先做双模式判定（见 BR-ui-002），不能仅凭链内顺序推演断言无重复。UI 布局类修复的静态审查必须包含此推演（不能凭"职责正交"直觉断言无冲突），涉及 IME 时须附约束探针实测证据。
- 反例 1：`.heightIn(max = (screenHeight - imeHeight - navBarHeight) * 0.9f).imePadding().navigationBarsPadding()` —— heightIn 的 max 已按扣除 IME 后的可用空间计算，但 imePadding/navBarsPadding 在该 max 内再扣 331+48dp，弹层被压至 ~65dp 窄带（TKN-UXR8B1-ACCEPTANCE-001 OBS-1 像素实测 180px）
- 反例 2：`.imePadding().navigationBarsPadding().heightIn(max = ...)`（顺序正确）但设备处于 resize 模式（window 已被 adjustResize 压缩至 1146px）——链内 imePadding 再扣 912px，弹层塌缩至 234px（OBS-2 约束探针实测）
- 正例：`BoxWithConstraints(navigationBarsPadding)` 读父级实际约束 → 判定 `imeAppliedByParent`（resize 模式 true）→ `Box(.then(if (imeAppliedByParent) Modifier else Modifier.imePadding()).heightIn(max = maxSheetHeight))` —— 顺序正确且总量正确，两模式均无双重扣除
- 来源：UXR8 批次1 OBS-1 修复（guardrail TKN-UXR8B1-GUARDRAIL-003 LOW-1 规则提议；实证：ac-verifier TKN-UXR8B1-ACCEPTANCE-001 像素级测量 + guardrail r3 独立约束传递链推演复核）；2026-08-16 OBS-2 补充（TRAE-debugger 约束探针：顺序正确仍双重扣除，须双模式判定，docs/reports/2026-08-16-uxr8-b1-bug3-obs2-debug.md）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active（guardrail TKN-UXR8B1-GUARDRAIL-003 确认非重复，与 BR-ui-002 正例同批修正避免内部矛盾；OBS-2 双模式判定补充同日更新）

#### BR-interface-013: 对协议必需字段做长度截断前，必须验证回放路径的协议完整性

- 类别：interface
- 规则：对协议必需的字段（如 DeepSeek `reasoning_content`）做长度截断（`.take(N)`）前，必须验证该字段的**回放路径**（工具回路第 2 轮回传 assistant 消息）是否会被截断值破坏协议完整性。若截断作用在协议回传副本上，须用真实端点验证截断值不被拒绝；必要时采用"协议副本完整 + 持久化副本裁剪"分层（回传用完整链、落库用裁剪链）。
- 反例：`buildAssistantToolCallMessage` 对 `reasoningContent` 直接 `.take(2000)` 截断，且该截断后的值经 `toMessageBody` 回传 DeepSeek —— 若 DeepSeek 对 reasoning_content 做语义/字节级校验，截断可能重新触发 B3 400 "must be passed back"
- 正例：回传副本保留完整 thinkingChain（`toMessageBody` 用内存完整链），仅持久化层（`persistSession`）剥离/裁剪；或截断后经真实 API 验证 400 不触发
- 来源：guardrail R2-NEW-1（TKN-UXR4-GUARDRAIL-R2，Q1 截断 + S1 剥离对协议回放的影响）
- 添加日期：2026-08-15
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-014: 按时间戳排序的 UI 列表必须附加 id 稳定 tie-break

- 类别：interface / testing
- 规则：按 `System.currentTimeMillis()` 时间戳倒序/正序排序的 UI 列表（如会话历史"最新在前"），必须附加实体 id 作为次级排序键（tie-break），如 `sortedWith(compareByDescending<Session> { it.updatedAt }.thenByDescending { it.id })`。原因：毫秒级时间戳在快速连续操作（用户快速创建/切换）或测试环境（虚拟时间推进极快）下**同毫秒真实存在**；Kotlin 稳定排序对相同键保持物理序列（ObjectBox 按 id 升序），导致"最新创建的实体反而排在后面"。次级键方向必须与主键语义一致（倒序列表用 id 倒序），保证"同毫秒时后创建者在前"。
- 反例：`box.all.sortedByDescending { it.updatedAt }` —— 同毫秒创建的两个会话，旧会话（小 id）物理序在前被稳定排序保留 → 新会话不在列表最前，ConversationViewModelSessionPersistenceTest 间歇性失败
- 正例：`box.all.sortedWith(compareByDescending<Session> { it.updatedAt }.thenByDescending { it.id })` —— 同毫秒时新会话（大 id）在前，排序确定且语义正确
- 来源：UXR8 批次1 guardrail 复审规则提议（TKN-UXR8B1-GUARDRAIL-002；SessionRepository.refreshFlows flaky 实证：ConversationViewModelSessionPersistenceTest 全量回归 1810 中真实触发 1 次）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active（guardrail TKN-UXR8B1-GUARDRAIL-002 确认非重复 + 全量回归复跑 1810 全绿实证）

#### BR-testing-005: 防御性修复必须双面断言（保护生效 + 副作用未发生）

- 类别：testing
- 规则：防御性修复（防并发/截断/剥离）必须同时断言"保护生效"与"副作用未发生"两面，避免单面断言漏检。例如 S1 隐私剥离：既断言 JSON 中思考链被剥离（保护生效），又断言内存 thinkingChain 保留（副作用未发生——协议回传不受影响）。若修复有主副作用路径，双面都要覆盖。
- 反例：S1 disabled 用例仅 `assertNotNull(aiMsg)` + 断言 JSON 不含思考内容，未断言内存 thinkingChain 非空 —— 若未来 strip 意外改写内存，测试仍通过
- 正例：断言 JSON 剥离 + `assertEquals("思考内容", aiMsg.thinkingChain)` 内存保留，两面闭环
- 来源：guardrail R2-NEW-2（TKN-UXR4-GUARDRAIL-R2，S1 测试缺口）
- 添加日期：2026-08-15
- 适用场景：testing
- 状态：active

#### BR-error-handling-013: 状态守卫把 UI 瑕疵升级为功能锁死前，须确认所有退出路径复位该状态

- 类别：error-handling
- 规则：添加状态守卫（如 `sendMessage` 的 `if (_isTyping.value) return`）时，守卫会把"状态未复位"从纯 UI 瑕疵升级为**功能锁死**（卡死期间所有操作被静默丢弃）。添加前必须穷举该状态的所有退出路径并确认均有复位（try-finally 兜底），否则新增守卫会放大未复位风险。
- 反例：Q5 守卫 `sendMessage` isTyping 拦截，但 `executePlainStream` 无 try-finally 复位 isTyping —— 若未来 Provider 直接抛异常而非发射 Error，卡死即永久屏蔽用户发送
- 正例：守卫配套确认 Done/Error/finally 三路径均复位 isTyping；`executePlainStream` 补 finally 兜底
- 来源：guardrail R2-NEW-4（TKN-UXR4-GUARDRAIL-R2，Q5 防御纵深）
- 添加日期：2026-08-15
- 适用场景：dev
- 状态：active

#### BR-error-handling-014: 工具结果失败文案禁止含诱导 LLM 重试的措辞

- 类别：error-handling
- 规则：工具执行器（如 web_search）返回的失败/空结果文案禁止含"请稍后重试""请重新搜索"等措辞——在 LLM 工具回路上下文中，这些措辞会诱导 LLM 反复以同义 query 重试同一工具直至 maxRounds 硬终止，用户得不到答案。失败文案应中性（如"搜索失败：联网搜索暂不可用，请基于已有信息回答"），并前置可识别前缀（如「搜索失败」）供上层 `isFailureResult` 识别触发熔断。
- 反例：`"联网搜索失败：网络错误或服务不可用，请稍后重试"` —— LLM 读到"请稍后重试"持续调用 web_search，10 轮后硬报"循环达上限"
- 正例：`"搜索失败：联网搜索暂不可用，请基于已有信息回答"` —— 中性，且前置「搜索失败」被 isFailureResult 识别触发重复工具熔断（MAX_CONSECUTIVE_TOOL_FAILURES=2）
- 来源：UXR6 问题 1 根因（TKN-UXR6-ARCHAEOLOGY-001，失败文案诱导重试 + 无重复工具熔断）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active

#### BR-interface-005: 缓存键必须包含实体唯一性签名

- 类别：interface
- 规则：任何按实体缓存的结果（如 MCP 工具定义缓存），缓存键不得只用易重复字段（如 `server.name`），必须包含能区分实体的唯一性签名（如 `name@baseUrl`），否则同名不同配置的实体在 `getOrPut` 时键冲突静默遮蔽（返回错误缓存）。
- 反例：`mcpToolsCache.getOrPut(server.name) { ... }` —— 同名不同 baseUrl 的 server 缓存冲突，返回错误工具定义
- 正例：`mcpToolsCache.getOrPut("${server.name}@${server.baseUrl}") { ... }` + enabled 集合签名（`name@baseUrl` join）做整体失效
- 来源：UXR6 guardrail Medium-1（TKN-UXR6-GUARDRAIL-001，主 Agent 盲区确认）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active

#### BR-testing-006: 循环/状态机核心逻辑须用「真实执行 + fake 依赖」的循环级测试覆盖

- 类别：testing
- 规则：工具回路、状态机等**核心循环逻辑**（如 SkillExecutor.executeLoop 的熔断、轮次推进）不能只用「覆写方法返回 canned」的外层测试，必须新增**真实执行 + fake 依赖**的循环级集成测试（真实 executeLoop + fake ChatStreamProvider/McpToolProvider 驱动多轮），直接断言循环行为（轮数、tools 变化、事件序列、终止条件）。覆写 stub 测试无法发现循环内部的逻辑缺陷（如熔断不触发、边界误发错误）。
- 反例：`ConversationViewModelUxR6Test` 早期只用 FakeSkillExecutor 覆写 executeLoop（验证外层 streamingIds），未测真实循环的熔断逻辑 —— guardrail Medium-2 指出循环逻辑无单测
- 正例：`executeLoop circuit breaker empties tools...` —— 真实 SkillExecutor + CircuitBreakerChatProvider（前 2 轮 ToolCallComplete → 第 3 轮纯文本），断言轮数=3、第 3 轮 tools 空、systemPrompt 含提示、无 maxRounds Error
- 来源：UXR6 guardrail Medium-2（TKN-UXR6-GUARDRAIL-001）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active

#### BR-error-handling-015: 解析 LLM 生成的 JSON 字符串字段必须显式拒绝 JsonNull 与 "null" 字面量

- 类别：error-handling
- 规则：从 LLM 生成的 JSON 参数（如 `ToolCallRef.arguments`）中提取字符串字段时，禁止直接 `jsonPrimitive.content`——`JsonNull.content` 返回字面量字符串 `"null"`，会把"字段为 null"误当成合法值，产生假数据（如假引用 "null"）。必须显式判断 `raw is JsonNull` 返回 null，且 trim 后拒绝 `"null"`/空串。对象/数组等容器经 `jsonPrimitive` 会抛异常，须有 catch 兜底为 null（fail-closed，不崩溃）。
- 反例：`obj["documentTitle"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }` —— `{"documentTitle": null}` 时返回字面量 `"null"`，UI 出现名为 "null" 的假引用
- 正例：`if (raw == null || raw is JsonNull) return null; raw.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() && it != "null" }`（catch 兜底 null）
- 来源：ac-verifier DEF-001（TKN-UXR7R2-ACCEPTANCE-001，引用池假引用 "null"）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active

#### BR-error-handling-016: 日志记录用户输入/外部数据必须截断，禁止输出完整原文

- 类别：error-handling
- 规则：日志（Log.d/w/i）中记录任何用户输入或外部数据（搜索关键词、工具参数、URL 等）时，必须截断到合理长度（如 `take(120)`），禁止输出完整原文。用户输入可能含 PII（姓名/手机号/私密问题），完整输出到 logcat 构成 CWE-532 敏感信息泄露面。RCA 所需的主体信息（前 N 字符）足以定位问题。新增日志点必须主动套用截断常量，不能只截断初始实现处。
- 反例：`Log.w(TAG, "query=$query")` 输出完整搜索词；某日志点截断而另一处 `core=$term` 未截断——审计发现遗漏
- 正例：`Log.w(TAG, "query=${query.take(LOG_QUERY_MAX_LEN)}")` + companion 定义 `LOG_QUERY_MAX_LEN = 120`，全文件所有用户输入日志点统一套用
- 来源：guardrail LOW-03 / ac-verifier DEF-002（TKN-UXR7R2-ACCEPTANCE-001）
- 添加日期：2026-08-16
- 适用场景：dev / bugfix
- 状态：active

#### BR-interface-015: 用户可重复添加的自然语言派生 key 发生冲突时必须生成唯一 key，禁止静默覆盖

- 类别：interface
- 规则：同一类别多条用户显式偏好并存时（如 L3 画像"我喜欢简洁的回复"与"正式场合用正式语气"都派生 `tone`），冲突 key 必须追加序号（`_2`/`_3`…）生成唯一 key 落库，禁止同 key upsert 静默覆盖旧值（用户显式输入被视为不可丢弃意图）。同 key 同 value 视为幂等（提示"已存在"），同 key 异 value 才走序号追加。派生 key 追加后缀后仍须 ≤ 字段长度上限（长 base 先截断再拼接，纵深防御）。顺带约束：带序号的 key 生成需可被纯函数测试（BR-testing-004）。
- 反例：`saveProfile` 直接 `setExplicitPreference(key, value)` —— "简洁"与"正式"连续添加时第二条覆盖第一条，用户第二条意图静默丢失
- 正例：`nextAvailableKey(base, occupied)` 先查 base 可用直接返回；冲突时 `_2` 起递增探测直至唯一（上限防无限循环），长 base 先 `take(MAX - len(suffix))` 截断；同 key 同值提前幂等 return
- 来源：UXR8 批次2 guardrail G-01（TKN-UXR8-B2-GUARDRAIL-001，L3 画像静默覆盖）
- 添加日期：2026-08-16
- 适用场景：dev
- 状态：active

#### BR-interface-016: OpenAI 工具 schema 的数组属性必须为 type:array + items 对象结构，禁止裸 JsonArray 字面量

- 类别：interface
- 规则：构建 OpenAI 兼容工具定义（ToolDefinition.parameters）时，任何数组属性（如 `sheets`、`questions`、`options`）的 JSON Schema 必须是「`type: "array"` + `items: <元素结构>`」的 JsonObject 结构。**禁止**直接把 Kotlin 的 `JsonArray`（数组字面量）作为属性的 value——那会产生非法 JSON Schema（`{"sheets": [...]}` 而非 `{"sheets": {"type":"array","items":{...}}}`），主流 Provider（DeepSeek/OpenAI 等）会对**整个请求**返回 400 `Invalid schema for function 'xxx'`，导致所有功能（含与本工具无关的图片消息、老/新会话）全部不可用。构建后用 schema 结构断言测试固化。
- 反例：`"sheets" to JsonArray(listOf(JsonObject(mapOf("type" to ...))))` —— DeepSeek 对全部请求返回 400 Invalid schema，视觉功能完全不可用（UXR8 真机实测）
- 正例：`"sheets" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to ...))))` —— 合法 JSON Schema，Provider 正常接受
- 来源：UXR8 真机测试 Bug1（document__create_xlsx schema 无效导致全请求 400，TKN-UXR8-FIX-GUARDRAIL-001）
- 添加日期：2026-08-17
- 适用场景：dev / bugfix
- 状态：active（guardrail TKN-UXR8-FIX-GUARDRAIL-001/002 确认修复正确 + ac-verifier TKN-UXR8-FIX-ACVERIFY-001 验收通过，2026-08-17 转 active）

#### BR-ops-002: 同一文件的多个修改必须串行单条 Edit，禁止同批并行 Edit 同文件

- 类别：ops
- 规则：对**同一文件**施加多处修改时，必须在不同消息中逐条串行执行 Edit（每次基于最新已写入内容）。禁止在同一条消息内并行发起多个针对同一文件的 Edit——并行 Edit 各自基于同一旧快照计算，写入时互相覆盖，最终磁盘上只保留最后一个 Edit 的改动，其余改动静默丢失（表现为"Edit 报告成功但内容未生效"，直到编译报 Unresolved reference 才发现）。不同文件的多处修改可并行；同一文件必须串行。
- 反例：一条消息内同时 Edit 同一文件的「import 块」与「函数体」→ 编译报 `Unresolved reference 'mutableStateMapOf'` / `'pendingAskUser'`，其中一个 Edit 的改动丢失（UXR8 批次3 实际踩坑）
- 正例：同一文件的 import 修改与状态声明修改分两条消息依次执行，每条 Edit 后文件即含最新内容；或在单条消息内对同一文件仅发一个 Edit
- 来源：UXR8 批次3 开发期编译失败（ConversationScreen.kt 并行 Edit 写竞争，修复后全量 1902 用例 0 失败）
- 添加日期：2026-08-17
- 适用场景：dev
- 状态：active（guardrail TKN-UXR8-B3-GUARDRAIL-001 §3 专项核查点5 确认非重复可执行 + 复审 TKN-UXR8-B3-GUARDRAIL-002 确认可转 active，2026-08-17 转 active）

#### BR-performance-002: 串行子请求必须在发起前检查剩余时间预算，防止总超时丢弃已成功结果

- 类别：performance
- 规则：被外层 `withTimeout`（如 SkillExecutor 默认 30s）包裹的工具执行器内部发起**串行子请求**（搜索降级重试 / 多查询合并变体）时，每次发起新请求前必须检查剩余预算（判据 `已耗时 + 单请求最坏超时 ≤ 总预算 − 安全缓冲`），不足则跳过新请求并返回已完成部分。原因：无预算感知时子请求耗时之和可贴满/超出外层总超时，外层取消会**整体丢弃已成功结果**。时间常量与上游（client 超时配置 / 外层 withTimeout）必须一致，且用单测断言常量对齐（防漂移）。
- 反例：主查询 + 降级重试 ≤3 + 合并变体 ≤2 每次 10s 无预算检查 —— 串行最长 40s > 30s 外层超时，主查询已成功结果被取消丢弃
- 正例：`hasRequestBudget(elapsed)` 纯函数判据 + 每个 `fetchSearch` 前检查，不足 `break` 保留已有结果；单测断言 `TOTAL_TOOL_BUDGET_MS == SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS`
- 来源：UXR8 批次2 guardrail G-03（TKN-UXR8-B2-GUARDRAIL-001，搜索子请求拖穿总超时）
- 添加日期：2026-08-16
- 适用场景：dev / bugfix
- 状态：active

#### BR-testing-010: 算法与外部参考实现对齐必须用真实参考输出作 golden data 锁定，禁止仅注释声明

- 类别：testing / interface
- 规则：任何声称与外部参考实现对齐的核心算法（如 Unigram Viterbi 分词与 HuggingFace `tokenizers` Rust 对齐），**必须**用真实参考实现（官方库/参考工具）生成的输出作为 golden data 写入测试并逐断言锁定，禁止仅在 KDoc/注释里写"15/15 样本匹配"而无任何测试。同时参数默认值必须与参考默认一致（如 `maxInputCharsPerSegment` 应为 HF Unigram 默认 100 而非自定 64），不一致会静默偏离训练时行为（长无空格 CJK 段退化为逐字符）且无测试可拦截。golden data 生成脚本须入库（可复现），生成器环境版本记录在生成文件头。
- 反例：`UnigramTokenizer` 的 KDoc 声明"15/15 样本与 transformers 一致"但 `app/src/test` 零 UnigramTokenizer 单测，且 `maxInputCharsPerSegment=64` 与参考默认 100 不一致——guardrail Q-HIGH-1 判定"静默降低核心功能质量且零测试锁定"
- 正例：`tools/gen_unigram_reference.py`（HF `tokenizers` 0.22.2 Rust）生成 8 样本参考表 → `UnigramTokenizerReference.kt`（含 88 字符 CJK 边界样本）→ `UnigramTokenizerTest` 逐样本断言 `tokenizeIds` 输出完全一致；`maxInputCharsPerSegment` 校准为 100
- 来源：UXR9 US-901 guardrail Q-HIGH-1（TKN-UXR9-GUARDRAIL-002，UnigramTokenizer 对齐无测试锁定 + 段长上限与参考不一致）
- 添加日期：2026-08-18
- 适用场景：dev / bugfix
- 状态：active

#### BR-concurrency-007: 重资源 by lazy 初始化必须在启动期 IO 线程预热，禁止主线程首次触发

- 类别：concurrency / performance
- 规则：`by lazy` 初始化为重资源（大模型读取、大 JSON 解析、DB 构建）时，若该 lazy 会被 UI 层（ViewModel Factory、Composable）在主线程首次访问，必须在 `Application.onCreate` 用 `appScope(IO)` 预触发（`runCatching { lazyRef }`，失败不缓存、后续按需重试），将加载移出主线程。lazy 默认 SYNCHRONIZED 线程安全，并发访问安全。禁止依赖"首次访问时后台线程恰好加载"的偶然性。
- 反例：`PrismApplication.embedder by lazy { 读 113MB ONNX + 解析 9MB tokenizer.json + 250k 词条 HashMap }` 由 `ConversationViewModel.Factory`（主线程）首次触发——慢设备首开聊天页卡顿/ANR（guardrail Q-HIGH-2）
- 正例：`onCreate` 内 `appScope.launch { runCatching { embedder }.onFailure { Log.e(...) } }` 预热，加载移出主线程；lazy 失败不缓存，后续访问（含主线程）重试优雅降级
- 来源：UXR9 US-901 guardrail Q-HIGH-2（TKN-UXR9-GUARDRAIL-002，embedder 主线程 lazy 初始化 ANR 风险）
- 添加日期：2026-08-18
- 适用场景：dev / bugfix
- 状态：active

#### BR-search-001: 无空格中文整句搜索须先剥疑问/泛化后缀提取实体前缀再检索

- 类别：interface / search
- 规则：Bing 等服务端对无空格/标点的连续中文句（如"梧州一中是什么学校"）按`[\u4e00-\u9fff]{2,}`正则会被视为**一个** CJK run，关键词解析退化；若把整句当核心词，条目过滤/相关性判定都要求字面整句命中 → 只返回"大概相关"。须先 `stripTrailingQuerySuffix`（最长后缀优先，覆盖 是什么学校/怎么/如何/… ）剥成前置实体候选（"梧州一中"），实体候选进入核心词列表参与主查询命中与短整词降级重试。
- 反例：整句"梧州一中是什么学校"作为唯一核心词 → 检索命中率低、参考来源间接
- 正例：核心词含实体"梧州一中" → 直接命中学校官方/介绍页
- 来源：v1 批次5 Issue 2（ADR-038 子决策 B）
- 添加日期：2026-08-19
- 适用场景：bugfix
- 状态：active

#### BR-vision-001: 视觉旁路 Provider 激活须同步授权并清熔断；重激活视为"期望启用"信号

- 类别：security / vision
- 规则：把 Provider 标记为「视觉旁路」（`isVisionFallback`）本身即用户"图片外发到该端点"的明确意图 → 保存时必须同步 `setConsent(true)` + `setAutoBypassEnabled(true)`。云端旁路经"连续失败 N 次自动熔断"停用后，若不在用户修复/重激活时 `resetFailures()`，Cloud 会永久不触发只剩 OCR；因此重激活保存时**额外清熔断计数**，让修复后的 Cloud 可重试。授权仍由用户 UI 显式动作为前提，非代码静默外发。
- 反例：用户激活视觉模型后仍只见 OCR（熔断卡死未清 / consent 默认 false 恒不过）
- 正例：标记 isVisionFallback 保存 → consent+auto+resetFailures 俱到，Cloud 可重试
- 来源：v1 批次5 Issue 3（ADR-038 子决策 C；旧 Issue 3 已授 consent，本次补熔断恢复）
- 添加日期：2026-08-19
- 适用场景：bugfix / security
- 状态：active

#### BR-security-008: 后台高危确认用仅宿主可见广播 + 单槽通知，PendingIntent 须组件显式 + FLAG_IMMUTABLE

- 类别：security
- 规则：后台用户确认（如手机操控发送/删除/拨号）不得用系统级悬浮窗权限（SYSTEM_ALERT_WINDOW 高风险），改用高优先级通知 + 操作按钮，Receiver 必须 `android:exported=false`、`PendingIntent` 必须**显式组件** + `FLAG_IMMUTABLE`（防外部篡改 extra/越权触发）。确认语义单槽时，发新通知前 `cancel` 旧通知（`activeAskId` 维护），杜绝"残留旧按钮误批新高危确认"。锁屏 `VISIBILITY_PRIVATE` 不泄详情；点通知本体默认拒绝（fail-closed）。answers 消费须白名单映射（固定 允许/取消 → 固定中文），未知动作忽略，禁止任意字符串注入。
- 反例：exported=true 广播 / 隐式 PendingIntent / 不撤旧通知 / 通知正文透传任意 LLM 输入给 sendMessage
- 正例：`ConfirmActionReceiver` exported=false + 显式 component PendingIntent + 发新先撤旧 + 白名单映射
- 来源：v1 批次5 Issue 5（ADR-038 子决策 E；guardrail TKN-V1B5-GUARDRAIL-001）
- 添加日期：2026-08-19
- 适用场景：security / bugfix
- 状态：active

#### BR-interface-019: Android 13+ 需要通知权限的功能须在入口生命周期请求，拒绝不阻塞主流程

- 类别：interface
- 规则：targetSdk ≥ 33 且功能依赖系统通知（后台确认、下载完成提示等）时，必须在入口 Activity `onCreate` 对 API33+ 做 `POST_NOTIFICATIONS` 运行时请求（`SDK>=TIRAMISU && !granted` 才请求），并同时在 `AndroidManifest` 声明该权限。请求失败/拒绝不得阻塞主流程（相关功能降级：提问卡片仍可用）。不要在每个功能点重复请求。
- 反例：只声明权限不运行时请求 → Android 13+ 通知静默不显示、后台确认不可见
- 正例：MainActivity.onCreate 一次性运行时请求 + Manifest 声明；拒绝可重进设置授予
- 来源：v1 批次5 Issue 5（ADR-038 子决策 E）
- 添加日期：2026-08-19
- 适用场景：bugfix
- 状态：active

#### BR-network-001: Android targetSdk>28 Fetch 任意公网 http 前必须升级 https（明文拦截非反爬）

- 类别：network / security
- 规则：Android 9+（targetSdk≥28）依赖 `network_security_config`，默认拦截公网**明文 http**（仅放行声明的 localhost）。任何抓取/检索类工具对公网 `http://` URL 直接请求会抛 `UnknownServiceException: CLEARTEXT communication ... not permitted`——这**不是反爬失败**。修复不得依赖"加请求头/指纹"（对明文拦截无效），应在应用层把公网 http **升级为 https** 再请求（同 host，过自身 SSRF 校验），且**不**全局放宽 `usesCleartextTraffic`（安全边界）。失败日志须经脱敏（剥 query/fragment/**userinfo** 凭证，CWE-532）以便区分明文/DNS/握手各层。
- 反例：对 `http://` 请求只加 UA/Sec-CH-UA/Referer → 仍 `UnknownServiceException`，LLM 误判反爬反复重试
- 正例：`fetchUrl` 先 `http://`→`https://` 再 `isPublicHttpUrl` 复检；`sanitizeUrlForLog` 剥 userinfo 再落日志
- 来源：v1 批次6 Issue 1（ADR-039 子决策 A；真机日志 `fetch failed: UnknownServiceException`）
- 添加日期：2026-08-19
- 适用场景：bugfix / dev
- 状态：active

#### BR-search-002: 中文实体的"中学/大学/学校/公司"等是实义词，禁止当查询后缀剥除；中文实体搜索用 HTML SERP 优于 RSS

- 类别：interface / search
- 规则1（后缀）：`stripTrailingQuerySuffix` 的后缀表只能放**疑问/泛化**后缀（是什么/怎么/如何/呢/吗…），**不得**放实体词（中学/大学/学校/公司/医院/功能/详情…）——否则"梧州市第一中学"被误剥成"梧州市第一"，毁了整词匹配。校名/机构名必须保留完整实体。
- 规则2（源）：Bing `format=rss` 对中文实体（尤其长校名）排名坍缩（连精确校名也返回市级百科、参考来源"大概相关"）；**优先解析 Bing HTML SERP**（`li.b_algo` 提取 title/真实 href/snippet，`u=a1<base64url>` 解码 ck 跳转直链），命中率显著更高。HTML 解析结果属不可信外部内容，仅回灌 LLM 文本，禁止进入抓取/Intent/WebView sink。
- 反例：RSS 返回市级、参考来源与内容无直接联系；后缀误剥出半截实体
- 正例：HTML SERP + 完整实体，校名 query 直接命中学校官网
- 来源：v1 批次6 Issue 2（ADR-039 子决策 B；真机日志 `search query=… first=梧州市…百科`）
- 添加日期：2026-08-19
- 适用场景：bugfix / dev
- 状态：active

#### BR-search-003: 单引擎命中不佳时须多引擎回退（Bing→Baidu），回退仍须经过相关性过滤 + 请求预算/电源感知

- 类别：interface / search / observability
- 规则：中文专有名词/长实体在单一搜索源（如 Bing）排名坍缩时，主查询+核心词重试（多候选）全不相关或空结果后，**必须触发兜底引擎（如 Baidu HTML SERP）再试一次**（query 主查询 → 首次不中再逐核心词短整词），避免"参考来源只有大概相关 / 剩下的全无关"。兜底引擎同样复用条目级相关性过滤（`parseBaiduHtml` → `filterRelevantItems`）与请求预算检查（`hasRequestBudget`，任一条 hit 即停），满足"预算感知 + 幂等 + 智能停止"。Baidu `link?url=` 跳转/`c-abstract` 摘要布局与 Bing 结构不同，解析必须独立实现并配 golden 测试，不得复用 Bing 的 `li.b_algo` 正则。
- 反例：Bing 对"梧州一中"只返回市级百科，直接返回"大概相关+无关混合集"
- 正例：Bing 不相关 → Baidu 兜底命中"梧州市第一中学_百度百科"等权威条目，参考来源直接相关
- 来源：v1 批次7 Issue 1（ADR-040 子决策 A；真机多次修复后 Bing 仍无法命中中文实体）
- 添加日期：2026-08-19
- 适用场景：bugfix / dev
- 状态：active

#### BR-vision-002: 云端视觉/外部降级链路失败不得静默吞，须留可观测日志；无专用视觉 Provider 时回退主 Provider 作描述端点

- 类别：interface / vision / observability
- 规则：任何"外部能力→本地兜底"降级链（如视觉旁路 Cloud→OCR），Cloud 侧**不得用 try/catch 吞失败**——必须记录 `provider + 异常`（不落 key/完整用户文本）和入参归属（`dedicated=`/`cloudConfig=`），否则真机永远无法判断是"没进 Cloud"还是"Cloud 失败"，问题无法闭环。Provider 解析应 `findVisionFallback() ?: activeProvider`——未配置专用视觉 Provider 时以主 Provider 尝试；外发仍受 consent 闸门（未授权不进云），不构成新增隐私面。
- 反例：cloudDescriber 失败返回 null → 默默落 OCR，无任何日志，多轮无法定位
- 正例：`cloud bypass ok/failed provider=… err=…` + `vision bypass: dedicated=… cloudConfig=…` 两条日志可现场 RCA
- 来源：v1 批次6 Issue 3（ADR-039 子决策 C；真机日志只有 `OCR process succeeded`）
- 添加日期：2026-08-19
- 适用场景：bugfix
- 状态：active

#### BR-vision-003: 视觉旁路专用 Provider 可跳过熔断，但**不得**因"已激活/专用"绕过用户显式撤销的图片外发授权（consent 隐私铁门）

- 类别：security / vision / privacy
- 规则：让"激活视觉模型即可用 Cloud"而做的专用 Provider 例外（`resolve(isDedicated=true)`）只能**跳过熔断**（连续失败不再锁死 Cloud，避免"激活了却永远只 OCR"），**必须仍尊重两项硬信号——`autoBypass` 开关 + 用户显式撤销授权（`isConsentGiven()`）**。consent 授予链路已由 `SettingsViewModel.saveProvider` 在把 Provider 标记为 `isVisionFallback` 时自动 `setConsent(true)`，因此正常激活路径 consent 恒为 true；校验 consent 正是为了拦截"用户到设置页关闭图片外发授权后，专用 Provider 仍把图片外发"的隐私回归（ADR-035 隐私铁门）。任何想绕开 consent 的"配置即授权/激活即授权"推理都是隐私红线违规。
- 反例：`cloudAllowed = if (isDedicated) auto else (visionConfig!=null && auto && consent && failures<MAX)`——专用分支漏掉 `consent`，用户设置页关闭授权后图片仍外发（guardrail TKN-SEARCH-VISION-ROUND5-001 B-1 阻断项）
- 正例：`cloudAllowed = if (isDedicated) auto && config.isConsentGiven() else visionConfig!=null && auto && consent && failures<MAX`，并配单测"专用 Provider + 授权撤销 → 落到 OCR，不得调云端"
- 来源：guardrail-accepted review（TKN-SEARCH-VISION-ROUND5-001 B-1/A-No.1，v1 批次7 ADR-040）
- 添加日期：2026-08-19
- 适用场景：dev / bugfix
- 状态：active

#### BR-network-002: 按状态码做诊断分支的 HTTP 客户端必须 expectSuccess=false，且测试 client 与生产配置一致（防测试-生产漂移）

- 类别：error-handling / testing / network
- 规则：若实现中写了 `resp.status.value !in 200..299` 之类的**状态码分支**（用于区分 401 Key 无效 / 429 限流 / 5xx 服务端错误等可诊断文案），则该请求的 HttpClient 必须 `expectSuccess = false`——否则非 2xx 在 `client.get` 抛 `ClientRequestException`，状态码分支成为**不可达死代码**，错误被吞成泛化文案。且**测试用 MockEngine 必须与生产 client 的 expectSuccess 配置一致**（测试默认 expectSuccess=false 会掩盖生产 expectSuccess=true 的行为漂移）。此为 ADR-032 R2（Fetch）与 v1 批次8（热榜）两度复现的根因。
- 反例：生产 `searchHttpClient { expectSuccess = true }` 复用给需要区分状态码的热榜工具；测试 MockEngine（默认 false）断言 500→null 通过，生产 401 被吞成 `ServerResponseException`
- 正例：需状态码诊断的工具用独立 `expectSuccess = false` client + 独立超时；补一条"用生产同款 client 配置跑 MockEngine"的契约测试验证状态码分支真实可达
- 来源：guardrail TKN-V1B8-MCP-ENHANCE-001 M-1（v1 批次8 US-002，与 ADR-032 R2 同款）
- 添加日期：2026-08-19
- 适用场景：dev / bugfix / testing
- 状态：active

#### BR-vision-004: 端侧 OCR/视觉坐标必须与「执行坐标空间」一致，降采样后必须按比例还原到屏幕空间

- 类别：vision / interface
- 规则：任何「截图 → 视觉/OCR 处理 → 返回坐标给执行层」的链路，**执行坐标空间**（如 Accessibility bounds / tap 手势）与**处理坐标空间**（如降采样后位图像素）不一致时，返回坐标必须按 `scaleX=screenW/bitmapW, scaleY=screenH/bitmapH` 还原。截图降采样（最长边 ≤1024px）后直接在降采样位图上跑 OCR/目标检测返回坐标、而 tap 在全屏空间执行，会产生整体缩放错位（真机约 2.3 倍），表现为"识别到了但点不到/点错位"。坐标还原逻辑应抽为纯函数（如 `scaledOcrElement`）可单测。
- 反例：`captureScreenshot` 降采样到 461×1024，`extractElements` 直接返回 OCR 的 461×1024 空间坐标，`tap(x,y)` 在 1080×2400 执行 → 全部点错位（v1 批次11 A 致命根因）
- 正例：captureScreenshot 降采样前记录原始尺寸；extractElements 传 `screenWidth/Height`，坐标经纯函数 `scaledOcrElement` 按比例还原到屏幕空间后再回灌 LLM/执行 tap
- 来源：v1 批次11 A 修复（真机"OCR 无法告诉 LLM 点击位置"根因链 #1，prd-v1-b11-phone §6.13.1）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / vision
- 状态：active

#### BR-security-008: 「按描述解析目标」类动作的敏感拦截必须用命中目标的真实文本，查询词/坐标仅作解析入口

- 类别：security
- 规则：对 tap/long_press 等按「LLM 描述/文本锚点」解析目标的动作，**敏感/高危拦截判断必须用命中目标（节点聚合文本 / OCR 行文本）的真实文本**，LLM 查询词与原始坐标只用于解析定位，不得作为敏感判断的唯一依据。典型绕过：`tap(text="确认")` 命中「确认支付」按钮——查询词"确认"不敏感但目标真实文本"确认支付"含支付词 → 用查询词判断会击穿支付类硬拦截。同理，坐标吸附（把点击坐标吸附到最近可点击节点中心）后也须确保吸附目标不落到敏感节点（吸附候选按真实文本预过滤）。另：查询词本身含敏感词也应作为附加防御直接拦截。
- 反例：`tap(text="确认")` 命中「确认支付」→ `isSensitiveTargetText("确认")=false` → 支付硬拦截/人工确认双绕过（guardrail TKN-V1B11-GUARDRAIL-001 H-1 / 002 R-1）
- 正例：`effectiveTargetText = 命中真实文本 ?: nodeTextOf(nodeId) ?: nodeTextAt(x,y) ?: 查询词`，四条路径（纯 text / node_id+text 双传 / 纯 node_id / 纯坐标）都用真实文本判定；snapToClickableCenter 按聚合文本过滤敏感候选；配红线单测"查询词不敏感但真实文本敏感 → 必须拦截"
- 来源：guardrail TKN-V1B11-GUARDRAIL-001/002/003（H-1 + R-1，v1 批次11 文本锚点敏感拦截绕过）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / security
- 状态：active

#### BR-security-009: 「文本型工具调用」解析执行必须复用既有工具安全链 + 结果回灌前剥离工具块

- 类别：security
- 规则：为不产生原生 tool_calls 的模型（glm-4.6v-flash 等）新增**文本型 `<tool_call>` 解析**时，解析出的工具调用**必须复用既有 executeToolCall 安全链**（用户确认门 / phone_control 敏感拦截），不得绕过；执行结果以 user 消息回灌前**必须 stripTextToolCalls**——fetch/搜索注入的 `<tool_call>` 块若留在结果里会跨轮被再次解析放大；文本路径 continue 前必须补重复工具熔断检查（与原生路径一致），否则只能靠 maxRounds 硬顶。
- 反例：文本工具调用直接执行不走确认门；工具结果原样注入历史导致注入块跨轮再解析（guardrail TKN-V1B12-GUARDRAIL-001 P1/P3）
- 正例：TextToolCallParser.parse → executeToolCall（确认+安全链）→ 结果 stripTextToolCalls 后【工具执行结果】user 回灌 → 熔断检查 → continue
- 来源：guardrail TKN-V1B12-GUARDRAIL-001/002（v1 批次12 glm 文本工具调用）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / security
- 状态：active

#### BR-security-010: 包名/别名纠正映射不得扩大功能面——纠正后包名必须仍过敏感黑名单（双重判定）

- 类别：security
- 规则：为 launch_app 新增**包名/别名纠正映射**（应用名/错包名 → 正确包名）时，映射的**正确包名必须仍落在金融敏感黑名单内**（否则"招商银行"中文名经映射解析成 cmb.pb、而 cmb.pb 不在黑名单 → prompt 注入启动银行 App 绕过硬拦截）；敏感判定须对**原始输入与纠正后包名双重**检查。映射"宁缺毋错"——不确定的包名不收录，错误映射比无映射更糟。金融黑名单须用**真实包名**（真机实证），不用 Activity 名。
- 反例：映射"招商银行"→cmb.pb 但黑名单只有 com.cmbchina.ccd.pluto.cmbActivity（Activity 名非包名）→ 注入可启动招商银行无拦截（guardrail TKN-V1B12-GUARDRAIL-001 P0 阻断）
- 正例：黑名单补 cmb.pb/com.chinamworld.bocmbci/com.chinamworld.main 等真实包名；runLaunchAction `if (isSensitivePackage(rawPkg) || isSensitivePackage(pkg))` 双重拦截；配红线单测（金融映射解析后必命中黑名单）
- 来源：guardrail TKN-V1B12-GUARDRAIL-001 P0（v1 批次12 PhoneControlPackageMap + 金融黑名单绕过）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / security
- 状态：active

#### BR-interface-019: 工具结果/会话历史禁止内嵌大 base64 图片文本——多模态模型走 image_url 注入，文本模型用文本摘要

- 类别：interface / performance
- 规则：任何工具（尤其手机操控 `screenshot`）的**结果文本/会话历史中禁止内嵌全量 base64 data URL**（截图 ~200-400KB）——① 持久化进会话 JSON 使历史膨胀至数 MB，重开历史渲染 400KB 单行阻塞主线程 >5s → **ANR 崩溃**（真机闪退）；② 该 base64 作为文本喂回模型纯属上下文膨胀（多模态模型要的是**图片**不是 base64 文本）。正确做法：多模态模型（supportsVision）截图以 **image_url 注入会话**（模型看真图）、base64 从持久化消息剥离；纯文本模型返回 OCR 文字+坐标摘要。
- 反例：`runScreenshot` 返回 `"截图成功（data URL）：$dataUrl$screenText"` → 400KB base64 进历史 → 重开对话 ANR 崩溃 + 每次截图上下文膨胀拖慢响应（v1 批次13 A 根因，真机 ANR 证据 prism_20260821_054307.log）
- 正例：视觉模型返回 `【手机截图图片】+dataUrl` 标记，SkillExecutor 提取标记后以 user 消息 image_url 注入、base64 从持久化剥离；纯文本模型返回 OCR 文字+坐标（条目上限防膨胀）
- 来源：v1 批次13 A/B（真机 ANR 崩溃 + 多模态图片注入，prd-v1-b11 §6.15）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / interface
- 状态：active

#### BR-vision-005: 视觉截图图片注入必须标记 transientImage 并持久化剥离；视觉不支持 400 必须降级重试而非中断

- 类别：vision / interface / error-handling
- 规则：多模态模型截图以 image_url 注入会话的 user 消息必须标记 `transientImage=true`，并由 `ChatMessageSerializer.encodeList` 在持久化时剥离 `imageUrl`（base64 仅用于当前会话 LLM 请求，不落历史——防会话 JSON 膨胀 + 切纯文本模型后历史每轮 400）。当工具回路收到 400 `visionUnsupported`（模型名含视觉字样但端点不支持 image_url）时，**不得让任务中断**：剥离已注入的瞬态截图图片 + 经 `LocalToolExecutor.onVisionUnsupported()` 通知手机操控截图降级 OCR/UI 树 + `rounds--` 重试本轮（模型以文本模式继续）。降级信号须经 Composite 门面转发给全部 delegate，接口用默认空实现向后兼容。
- 反例：image_url 注入未标 transientImage → 400KB base64 落历史 → 重开对话 ANR（v1 批次13 F1，真机 ANR 证据 prism_20260821_054307.log）；视觉 400 直接转发错误结束回路 → 拼多多打开后后续任务中断
- 正例：SkillExecutor 原生/文本两条路径注入均置 `transientImage=true`；ChatMessageSerializer 剥离；visionUnsupported → 剥离图片 + `onVisionUnsupported()`（截图转 OCR）+ 重试本轮；配单测「400 后重试 + base64 不出现在持久化 JSON」
- 来源：v1 批次13 A/B/F1/D16c（guardrail TKN-V1B13-GUARDRAIL-001 F1 + 多模态降级链）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / vision
- 状态：active

#### BR-security-011: 截图等图片外发属隐私面——supportsVision 显式设置后不得被模型名自动检测覆盖

- 类别：security / privacy / vision
- 规则：手机操控截图内容会发送到 LLM 端点，属敏感数据外发。视觉能力判定若为「显式标记 > 自动检测」，则**用户显式设置过**（`supportsVisionSet=true`，设置页触碰开关）后运行时/保存逻辑必须尊重用户值——显式关闭 `supportsVision=false` 绝不能被「按模型名自动检测」重新开启（防截图静默外发）。旧配置 `supportsVisionSet=false`（默认）才允许按模型名自动检测兜底（开箱即用）。自动启用（模型名命中视觉模式）须同时落 `supportsVisionSet=true`，避免被后续保存误覆盖。
- 反例：`visionCapableProvider = active.supportsVision || detectVisionSupport(model)` → 用户在设置页显式关闭视觉，模型名命中视觉模式仍被自动开启 → 截图静默外发（隐私回归）
- 正例：`supportsVisionSet ? supportsVision : detectVisionSupport(model)`；SettingsScreen 触碰开关置 `supportsVisionTouched=true`（保存时写入 supportsVisionSet）；saveProvider 仅在未设置时自动启用；配单测「显式关闭 + 视觉模型名 → 保存后仍 false」
- 来源：v1 批次13 B/D16b（视觉能力开箱即用 vs 隐私显式关闭的平衡设计）
- 添加日期：2026-08-21
- 适用场景：dev / bugfix / security
- 状态：active

#### BR-interface-021: 视觉能力开关保存时按模型名自动启用（开箱即用），但仅当用户未显式设置过

- 类别：interface / vision / ux
- 规则：为视觉模型（glm-4.6v-flash 等，模型名命中 [ProviderConfig.detectVisionSupport]）新增的「支持视觉」开关若默认为关，用户配置视觉模型后会继续走 OCR（能力未被利用）且不知该开此开关——违反开箱即用。保存 Provider 时应自动启用 `supportsVision=true` 并落 `supportsVisionSet=true`（仅当 `!supportsVisionSet`）。误判（模型名带视觉字样但端点不支持图片）由 400 降级链（BR-vision-005）自愈，用户仍可在设置页显式关闭。
- 反例：supportsVision 默认 false 且无自动提示 → 用户配 glm-4.6v-flash 仍走 OCR 文本，多模态能力闲置
- 正例：`SettingsViewModel.saveProvider` 未显式设置时 `detectVisionSupport(firstModel)` → `supportsVision=true + supportsVisionSet=true`；配单测「glm-4.6v-flash 保存后自动启用 / deepseek-chat 不启用 / 显式关闭不覆盖」
- 来源：v1 批次13 B/D16b（视觉能力开箱即用，prd-v1-b11 §6.15）
- 添加日期：2026-08-21
- 适用场景：dev / ux / vision
- 状态：active

#### BR-ops-005: 常驻前台服务/长驻资源必须绑定「任务活跃期」而非「能力开关期」，并以空闲超时自动释放

- 类别：ops / architecture / battery
- 规则：前台服务（FGS）等常驻型系统资源，其启动条件不得绑定在「能力开关状态」（如无障碍服务已启用、某功能已配置），必须绑定在「任务活跃期」——由任务的实际工作单元（如工具调用入口）首次触发启动并刷新活跃时间戳，空闲超时后自动释放；服务重启策略用 START_NOT_STICKY（任务期服务被杀不复活，下次任务重新拉起），避免「杀不死」循环。同时：能力开关期常驻会与 Manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 用途声明名实不符（Play 政策风险）；订阅系统高频事件（如无障碍 TYPE_WINDOW_CONTENT_CHANGED）前必须确认处理器真的消费它，否则是纯 binder IPC 开销拖慢整机。
- 反例：`onServiceConnected() { KeepAliveService.start(this) }` + `START_STICKY` + 订阅 typeWindowContentChanged 但处理器只认 STATE_CHANGED —— 真机 dumpsys 实证：服务常驻 1d8h10m、通知 ONGOING|NO_CLEAR 不间断、被杀自动重启，用户不使用软件也弹窗+整机卡顿
- 正例：`PhoneControlSessionManager.onPhoneToolInvoked()` 由工具执行入口每次调用（首个调用 startForegroundService + 排定 idle 检查，锁内单次取值判空防 TOCTOU；满 IDLE_TIMEOUT_MS 自动 stopService）；START_NOT_STICKY；事件订阅收窄为实际消费的类型
- 来源：v1 批次14 保活 Bug 修复（TKN-V1B14-KEEPALIVE-BUG-001，ADR-041；guardrail TKN-V1B14-GUARDRAIL-001 两轮 + ac-verifier TKN-V1B14-ACCEPTANCE-001 7/7 PASS）
- 添加日期：2026-08-23
- 适用场景：dev / bugfix
- 状态：active

#### BR-network-003: WebView 渲染抓取必须对主框架导航与终态 URL 做公网 https 校验（与直抓逐跳 SSRF 校验对齐）

- 类别：security / network / webview
- 规则：任何 WebView 渲染抓取路径，除初始 URL 的 SSRF 校验外，必须：① `shouldOverrideUrlLoading` 拦截指向非公网 https 的主框架导航（页内 JS `location`/meta refresh/链接跳转）；② `onPageFinished` 对终态 `view.url` 复验（服务端 302 不触发前者）。校验函数**禁止 DNS 解析**（回调在主线程，InetAddress 解析触发 NetworkOnMainThreadException），只做字符串级私网 IP 字面量/localhost 判定；「公网 DNS 名解析到内网」（rebinding）为既有已知局限，须与直抓路径同口径记录。否则内网响应体会经 outerHTML 提取回灌 LLM 上下文并随下轮请求外发云端端点。
- 反例：WebViewClient 只覆盖 onPageFinished 且对任意 finishedUrl 一律 complete(true) → 攻击页 JS 跳 `http://127.0.0.1:11434`（命中 network_security_config localhost 明文放行）→ 本机服务响应被提取回灌（guardrail TKN-V1B15-GUARDRAIL-001 M-1）
- 正例：shouldOverrideUrlLoading 拦截非公网 https 主框架导航 + onPageFinished 终态 `isFinalUrlAllowed` 复验（https scheme + userinfo 剥离 + localhost/.localhost/私网 IPv4 字面量/::1 拦截，无 DNS）+ 红线单测
- 来源：v1 批次15 US-1506 WebView 渲染降级（guardrail TKN-V1B15-GUARDRAIL-001 M-1 修复）
- 添加日期：2026-09-02
- 适用场景：dev / security / webview
- 状态：active

#### BR-network-004: 用户自建明文 http 服务端点必须提供系统明文策略拦截的可诊断分支与绕行方案文档

- 类别：network / ops / error-handling
- 规则：`network_security_config` 仅放行 localhost/127.0.0.1 明文 http（不放宽为安全基线）。凡新增「用户自填 http 端点」功能（SearXNG 引擎、局域网自建 MCP 模板等），必须：① 捕获 `UnknownServiceException`（CLEARTEXT not permitted）输出专属可诊断日志/文案，与「端点填错/DNS 失败」区分（防用户按教程配置后功能静默永不可用）；② 配套文档给出绕行路径：`adb reverse tcp:<端口> tcp:<端口>`（端点填 `http://127.0.0.1:<端口>`，落既有放行域）或 https 反向代理或 VPN+https。**不得为便利而放宽 network_security_config 放行任意明文**。
- 反例：SearXNG 引擎 `catch (Exception)` 统一静默降级 → 用户按 runbook 填局域网 http 端点后 OkHttp 抛 UnknownServiceException → 引擎永远不可用且日志仅 `failed (ClientRequestException)` 类无法定位（guardrail TKN-V1B15-GUARDRAIL-001 M-2）
- 正例：fetchViaSearxng 独立 catch UnknownServiceException 输出 `blocked by cleartext policy` + runbook 第 6 节三种解法（adb reverse/https 反代/Tailscale）+ 故障排查表对应行
- 来源：v1 批次15 US-1507 SearXNG + US-1508 局域网 MCP 模板（guardrail TKN-V1B15-GUARDRAIL-001 M-2 修复；同机制先例 ADR-039 批次6 Issue 1）
- 添加日期：2026-09-02
- 适用场景：dev / bugfix / network
- 状态：active

## 审计记录

| 日期 | 审计人 | 结果 | 备注 |
|---|---|---|---|
| 2026-08-02 | 主 Agent | 初始建立 | 试点期，无规则，待首期编码后累积 |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-001 | US-001 M0 审查发现 AGP/Gradle 版本不匹配（G-01 阻断级） |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-002 | US-001 M0 第二轮复审发现 gradlew 缺少 git 可执行权限（G-09） |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-003 | US-001 M0 第三轮审查发现镜像缺少 content 过滤（G-10） |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-004/005 + BR-security-001 | US-002 ObjectBox 审查发现 JNI DLL 未忽略（G-01）、schema 文件需提交（G-03）、FloatArray equals 缺陷（G-02） |
| 2026-08-02 | ac-verifier | 验收通过，无新规则 | US-002 ObjectBox 验收（TKN-PRISM-ACCEPTANCE-001）：18 测试通过，性能基线已建立，AC-003 因无模拟器受限通过 |
| 2026-08-02 | guardrail-enforcer | 新增 BR-security-002 + BR-testing-001 | US-003 API Key 审查（TKN-PRISM-GUARDRAIL-005）：StrongBox 异常捕获过窄（G-01）、测试替身语义缺失（G-04） |
| 2026-08-02 | ac-verifier | 验收通过，无新规则 | US-003 API Key 验收（TKN-PRISM-ACCEPTANCE-002）：30 测试通过（14 基础 + 16 边界），性能基线已建立，AC-001 因无模拟器受限通过，G-01 已修复确认 |
| 2026-08-02 | guardrail-enforcer | 提议 BR-concurrency-001 + BR-data-001 | US-004 ProviderConfig 审查（TKN-PRISM-GUARDRAIL-006）：32 测试通过，编译通过，无阻断级漏洞，结论通过。G-01 setActive 原子性（高风险）、G-02 StringListConverter 换行转义（中风险）为强建议修复项 |
| 2026-08-02 | 主 Agent | 确认 BR-concurrency-001 + BR-data-001 | 修复 G-01（setActive 用 runInTx 事务）+ G-02（StringListConverter 单次扫描转义/反转义），新增 3 边界测试，35 测试通过 |
| 2026-08-02 | guardrail-enforcer | 复审通过，无新规则 | US-004 复审（TKN-PRISM-GUARDRAIL-006）：G-01/G-02 修复正确，报告追加 8.6 节独立复审确认 |
| 2026-08-02 | ac-verifier | 验收通过，无新规则 | US-004 ProviderConfig 验收（TKN-PRISM-ACCEPTANCE-003）：52 测试通过（35 基础 + 17 边缘），性能基线已建立，AC-3 真实设备持久化因无模拟器受限通过，G-01/G-02 已修复确认 |
| 2026-08-06 | guardrail-enforcer | 新增 BR-error-handling-004 + BR-ui-001 + BR-security-003 | US-007 Provider 切换审查（TKN-US007-GUARDRAIL-001）：Q2 headers 编辑器原地改值（MEDIUM）、Q3 catch 无日志（LOW）、Q4 apiKeyRef 时间戳碰撞（LOW）、Q5/S1 CRLF 纵深防御（LOW） |
| 2026-08-06 | guardrail-enforcer | 复审通过，无新规则 | US-007 复审（TKN-US007-GUARDRAIL-002）：Q2（mutableStateListOf）/Q4（UUID）/Q5（CRLF 校验）修复正确，单激活不变式保持，可进入 ac-verifier |
| 2026-08-06 | ac-verifier | 验收通过，无新规则 | US-007 Provider 切换验收（TKN-US007-ACCEPTANCE-001）：prd.json 五条 AC 满足，SSE 首字延迟 p99 +1.7% 无回退，安全通过，回归 157 用例 0 失败 |
| 2026-08-06 | guardrail-enforcer | 新增 BR-testing-002 + BR-testing-003 | US-008 MCP 集成测试审查（TKN-MCP-CLIENT-GUARDRAIL-005）：Q1「先启动资源再断言」在 try 外、Q2 测试 HttpClient 未逐项对齐生产、Q3 stopServers 未逐项容错 |
| 2026-08-06 | ac-verifier | 验收通过，无新规则 | US-008 MCP Client 复验（TKN-MCP-CLIENT-AC-002）：DEF-001/GAP-001/GAP-002 三项闭合，AC-1~AC-5 全部通过，lintDebug BUILD SUCCESSFUL，回归 214 用例 0 失败 |
| 2026-08-07 | guardrail-enforcer | 提议 BR-concurrency-002 + BR-error-handling-005 | US-014 嵌入引擎审查（TKN-US014-EMBEDDING-001）：372 测试通过、Typecheck 通过，无阻断级安全漏洞（无注入/密钥/RCE），但 G-01 并发 use-after-close 竞态违反接口线程安全契约定为阻断；G-02/G-03/G-04 高危、G-05/G-06/G-07 中危。主 Agent 须修复后重新提交审查 |
| 2026-08-07 | guardrail-enforcer | 复审通过，BR 规则验证修复有效 | US-014 R2 复审（TKN-US014-EMBEDDING-002）：G-01~G-15 修复项正确，未引入新阻断/高危缺陷，建议 BR-concurrency-002 / BR-error-handling-005 转 active |
| 2026-08-07 | ac-verifier | 验收通过，BR 规则转 active | US-014 验收（TKN-US014-EMBEDDING-AC-001）：5/5 AC 通过，29 嵌入测试 + 4 perf 基准通过，全量 379 测试 0 失败。确认 G-01（2 并发测试）/ G-02（先置 null 再 close）修复有效，BR-concurrency-002 / BR-error-handling-005 状态 proposed → active |
| 2026-08-07 | guardrail-enforcer | 提议 BR-error-handling-006 | US-016 摄入管线审查（TKN-US016-GUARDRAIL-001）：无阻断级安全漏洞，协程取消语义正确（CancellationException 不被吞），但 M1 require 在 use{} 前导致 InputStream 资源泄漏（中高危，须修复后重新审查）。结论有条件通过。M2 Failed(throwable) 信息泄露（中危）、Q3 catch 缺注释（低危，违反 BR-error-handling-004）、Q6 测试未覆盖取消/写入失败 |
| 2026-08-07 | guardrail-enforcer | 复审通过，BR-error-handling-006 待 ac-verifier 确认 | US-016 摄入管线复审（TKN-US016-GUARDRAIL-002）：M1 修复有效（require 失败前先 close input，测试验证 closed==true），M2 KDoc 安全约定到位，Q3 catch 注释符合 BR-error-handling-004，Q6 协程取消测试覆盖。新增 catch(IllegalArgumentException) 设计合理（与 CancellationException 互不继承，不破坏取消语义）。无回归，24 测试通过。结论通过，可进入 ac-verifier。BR-error-handling-006 proposed→待 ac-verifier 确认转 active |
| 2026-08-07 | guardrail-enforcer | 提议 BR-concurrency-004 | US-018 知识库管理 UI 审查（TKN-US018-GUARDRAIL-001）：32 测试通过、Typecheck 通过，无阻断级安全漏洞，但 G-01 StateFlow 非原子 RMW 并发 lost update（中危）+ G-02~G-05 中危须修复。结论有条件通过 |
| 2026-08-07 | guardrail-enforcer | 复审通过，BR-concurrency-004 待 ac-verifier 确认 | US-018 R2 复审（TKN-US018-GUARDRAIL-002）：G-01~G-05 修复有效（_uiState.update 原子 CAS / Failed logger.log / catch 不静默吞 / createLibrary+deleteLibrary 统一 try-catch），无回归，0 阻断/0 高危/0 中危，仅 R2-1 低危建议。建议 BR-concurrency-004 proposed→待 ac-verifier 确认转 active |
| 2026-08-07 | ac-verifier | 验收通过，BR-concurrency-004 转 active | US-018 知识库管理 UI 验收（TKN-US018-AC-001）：5/5 AC 通过，35 单元测试 + 524 全量回归 0 失败，R2-1 日志措辞 simpleName 修复有效。确认 G-01（_uiState.update CAS）修复有效，BR-concurrency-004 proposed → active |
| 2026-08-07 | guardrail-enforcer | 提议 BR-error-handling-007 + BR-interface-004 | US-019 RAG 对话集成审查 round 1（TKN-US019-RAG-GUARDRAIL-001）：1 HIGH（G-01 runCatching 吞 CancellationException）+ 4 MEDIUM（G-02 embed 失败静默 / G-03 simpleName 暴露 / G-04 SpecificLibrary 无校验 / G-05 正向测试缺失）。结论有条件通过 |
| 2026-08-07 | guardrail-enforcer | 复审通过，BR 规则待 ac-verifier 确认 | US-019 RAG 对话集成审查 round 2（TKN-US019-RAG-GUARDRAIL-002）：G-01~G-05 修复有效（显式 try-catch 重抛 CancellationException / RagBuildResult sealed 三态 / Log.w 替代 simpleName appendDelta / SpecificLibrary init 校验 / 4 新测试），无新增阻断/高危/中危，3 LOW 建议（R2-1/R2-2/R2-3）不阻断。建议 BR-error-handling-007 / BR-interface-004 转 active |
| 2026-08-07 | ac-verifier | 验收通过，BR-error-handling-007 + BR-interface-004 转 active | US-019 RAG 对话集成验收（TKN-US019-RAG-ACCEPTANCE-001）：5/6 AC 完全通过（AC-1/3/4/5/6），AC-2 数据层通过但 UI 入口为已知 GAP（不阻断，延后至后续 US）。57 单元测试 + 519 全量回归 0 失败。确认 G-01（CancellationException 重抛）/ G-02（RagBuildResult sealed）/ G-04（SpecificLibrary init 校验）修复有效，BR-error-handling-007 / BR-interface-004 proposed → active |
| 2026-08-09 | functional-validation-auditor | M3 里程碑审计同步 BR-006 状态 | M3 里程碑交付审计（TKN-M3-MILESTONE-AUDIT-001）：BR-error-handling-006 在 US-016 acceptance 已确认转 active，但 behavioral-rules.md 状态字段仍为 proposed（审计 §C.1 偏差 M3-004）。本次同步状态字段 proposed → active，与 US-016 acceptance 报告一致 |
| 2026-08-09 | guardrail-enforcer | 提议 BR-naming-001 | M4 Phase A 基础层审查（TKN-M4-PHASEA-GUARDRAIL-001）：556 测试通过、Typecheck 通过，无阻断级安全漏洞（无注入/密钥/RCE）。Role.TOOL 静默映射 bug 已由主 Agent 自查修复（if-else → when 穷尽 + Fail Fast）。G-01 中危（tools 静默忽略，强建议加 Log.w）、G-02~G-05 低危建议项。结论通过，可进入 ac-verifier。BR-naming-001 proposed→待 ac-verifier 确认转 active |
| 2026-08-09 | ac-verifier | 验收通过，BR-naming-001 转 active | M4 Phase A 基础层验收（TKN-M4-PHASEA-ACCEPTANCE-001）：US-020 6/6 AC 通过，US-023 5/6 通过 + AC-4 有条件通过（Fail Fast 裁定为 ADR-014 5.6 分阶段决策，Phase C US-024 补完整 TOOL→"tool" 映射）。SkillRepositoryTest 12 测试 + 全量 556 回归 0 失败。G-01 Log.w 修复有效。BR-naming-001 proposed → active（规则验证通过，未触发反例模式） |
| 2026-08-09 | ac-verifier | 验收受限通过，BR-security-004 转 active | M4 Phase B 验收（TKN-M4-PHASEB-ACCEPTANCE-002）：US-021 5/5 AC 通过，US-022 5/6 通过 + AC-5 受限通过（SkillRegistryTest.kt 不存在，受限根因为 Android Context 构造期依赖 + 无 Robolectric/Mockito 测试基础设施，附 3 项 Phase C 强制条件）。SkillManifestParserTest 33 测试 + 全量 589 回归 0 失败（独立核实）。性能基线：parse 典型 1KB p50<1ms。安全：YAML 注入 4 项 + 敏感信息泄露 6 项全部通过。BR-security-004 proposed → active（规则文本已修订纠正 3 处事实错误 + 实现符合正例 + 4 测试验证防护有效） |
| 2026-08-10 | guardrail-enforcer | 提议 BR-security-001 补充条款 | M5 Phase A 数据层审查（TKN-M5-PHASEA-GUARDRAIL-001）：55 测试通过、编译通过，无阻断级安全漏洞（无注入/密钥/RCE/命令执行）。HNSW #1209 规避有效（Box.remove 路径确认）。BR-security-001/BR-concurrency-001/003/BR-testing-004 全部合规。L-01 低危（equals null embedding 边界缺陷）+ L-02~L-05 低危建议。结论通过，可进入 ac-verifier。提议 BR-security-001 补充条款（nullable 数组字段 equals 覆盖须用 nullable 扩展函数）待 ac-verifier 确认转 active |
| 2026-08-10 | ac-verifier | 验收通过，BR-security-001-amendment 转 active | M5 Phase A 验收（TKN-M5-PHASEA-ACCEPTANCE-001）：US-030 5/5 AC 通过（AC-1 Converter 偏离判定合理）+ US-031 5/5 AC 通过，59 专项测试 + 971 全量回归 0 失败。性能基线建立（searchByVector p50=62us / save p50=1311us / getBySession p50=92us）。安全检查 10 项全部通过。L-01 修复验证有效（4 边界测试：双 null 相等 / 双非 null 内容相等 / 单 null 不等 / 双非 null 内容不等）。BR-security-001-amendment proposed → active |
| 2026-08-11 | guardrail-enforcer | 提议 BR-security-005 | M5 Phase B 审查（TKN-M5-PHASEB-GUARDRAIL-001）：49 测试通过、编译通过，无阻断级安全漏洞。M-1/M-2 中危（setWindowSize 缺上界校验 + processMessages 缺 coerceIn 上界防御）+ L-1/L-2/L-3 低危建议。结论通过。主 Agent 已修复 M-1/M-2（require + coerceIn 双层防御 + 5 边界测试）。提议 BR-security-005（可配置数值参数双重强制合法范围）待 ac-verifier 确认转 active。提议的 BR-error-handling-008（后台任务降级须 Log.w）经主 Agent 判定为 BR-error-handling-004 的具体应用场景，不单独新增规则，L-1 修复将作为 BR-error-handling-004 的违反修复处理 |
| 2026-08-11 | ac-verifier | 验收通过，BR-security-005 转 active | M5 Phase B 验收（TKN-M5-PHASEB-ACCEPTANCE-001）：US-032 6/6 AC 全部通过，49 主 Agent 测试 + 12 ac-verifier 补充测试（parseCompletionResponse 10 + buildRequestBody stream=false 2）= 61 专项测试全部通过，1035 全量回归 0 失败。性能基线建立（processMessages p50=10.6μs / parseCompletionResponse p50=27μs / truncateMessages p50=2.4-9.0μs）。安全检查全部通过（敏感信息泄露 0 + BR-security-005 双层防御验证 + 注入防护 0）。M-1/M-2 修复验证有效（5 边界测试）。BR-security-005 proposed → active |
| 2026-08-11 | guardrail-enforcer | 无新规则，M-2 强制修复条件 | M5 Phase C Round 2 复审（TKN-M5-PHASEC-GUARDRAIL-001）：31 测试通过、编译通过，无阻断级安全漏洞（无注入/密钥/RCE/命令执行）。修复验证：主 Agent 声称修复 4 项，3 项验证通过（TAG 常量 / maxMemories coerceIn / FakeEmbedder stage），1 项部分修复——catch 块日志仅 saveSessionMemories 添加 Log.w（M-1 已修复 ✅），retrieveRelevantMemories 遗漏（M-2 未修复 ⚠️）。M-2 违反 BR-error-handling-004（active），为强制修复条件。L-2/L-3/L-4 低危建议。结论通过（附 M-2 强制修复条件）。无新规则提议（M-2 为 BR-error-handling-004 已有规则违反）。建议主 Agent 加强逐 catch 块核对粒度 |
| 2026-08-11 | guardrail-enforcer | 无新规则，结论通过 | M5 Phase C Round 3 复审（TKN-M5-PHASEC-GUARDRAIL-001）：34 测试通过（原 31 + L-3 补充 3）、编译通过，无阻断/高危/中风险安全漏洞。M-2 修复验证通过：retrieveRelevantMemories catch 块 L158 添加 Log.w，全文件 Log. 调用命中 2 处（L118 + L158），BR-error-handling-004 合规。L-3 补充测试验证通过：3 个边缘场景测试断言经手动追踪确认正确。所有声称修复项（FIX-1~4 + L-3）全部验证通过。结论通过（无强制修复条件），可进入 ac-verifier。无新规则提议。建议主 Agent 后续对每个 catch(Exception) 块逐一检查日志记录 |
| 2026-08-11 | ac-verifier | 验收通过，无新规则 | M5 Phase C 验收（TKN-M5-PHASEC-ACCEPTANCE-001）：US-033 6/6 AC 全部通过。34 主 Agent 测试 + 24 ac-verifier 补充测试 = 58 专项测试全部通过，1105 全量回归 0 失败。性能基线建立（saveSessionMemories 1-pair p50=834us / 10-pair p50=15450us / retrieveRelevantMemories 100-records p50=152us / formatMemoriesAsContext p50=8us）。安全检查全部通过（硬编码密钥 0 / 注入 0 / 敏感信息泄露 0 / CancellationException 重抛 2 处正确）。BR-error-handling-004/007 + BR-security-005 + BR-testing-004 全部合规。无新规则提议 |
| 2026-08-11 | guardrail-enforcer | 无新规则，结论通过 | M5 Phase D 审查（TKN-M5-PHASED-GUARDRAIL-001）：43 测试通过、编译通过，无阻断/高危/中危安全漏洞。3 个 catch 块均有 Log.w（BR-error-handling-004 合规），2 处 CancellationException 正确重抛（BR-error-handling-007 合规）。5 个低危建议：L-01（if-else→when，BR-naming-001 低危违规）、L-02（key/value 长度上限）、L-03（换行符编码）、L-04（测试注释）、L-05（JSON 大小限制）。结论通过，可进入 ac-verifier。无新规则提议 |
| 2026-08-11 | ac-verifier | 验收通过，无新规则 | M5 Phase D 验收（TKN-M5-PHASED-ACCEPTANCE-001）：US-034 7/7 AC 全部通过。43 主 Agent 测试 + 37 ac-verifier 补充测试 = 80 专项测试全部通过，1185 全量回归 0 失败。性能基线建立（setExplicitPreference p50=907us / parsePreferencesJson p50=50us / formatProfilesAsContext p50=33us / extractImplicitPreferences p50=2377us）。安全检查全部通过（JSON 注入 / 敏感信息泄露 / BR 合规）。无新规则提议 |
| 2026-08-11 | guardrail-enforcer | 提议 BR-concurrency-005，结论有条件通过 | M6 Phase C 审查（TKN-M6-PHASEC-GUARDRAIL-001）：编译通过，136 测试中 135 通过（1 stale test 失败）。无阻断级安全漏洞（无注入/密钥/RCE/命令执行）。M-1 中危（AppLauncherBridge 超时从 30s 改为 35s 方向错误，35s > 30s 导致 SkillExecutor 仍先超时，KDoc "保证 bridge 先超时" 事实性错误）+ M-2 中危（stale test 需更新）+ L-1 低危（KDoc 引用 "30s" 过时）。提议 BR-concurrency-005（嵌套 withTimeout 超时层级必须内层短于外层）待主 Agent 确认 + ac-verifier 验证后转 active。结论有条件通过 |
| 2026-08-11 | guardrail-enforcer | BR-concurrency-005 确认合规，结论通过 | M6 Phase C 第二轮审查（TKN-M6-PHASEC-GUARDRAIL-002）：M-1 修复正确（25s < 30s，BR-concurrency-005 合规），M-2 测试更新正确（断言反映修复后行为 + 新增端到端验证测试），M-1 验证测试 BUILD SUCCESSFUL。L-1 残留（类级 KDoc L28 + 方法级 KDoc L68 仍写 "默认 30s"，主 Agent 声称修复 3 处但实际只修 companion object KDoc 1 处）为低危文档问题，不阻断。二次自检报告 §2.4/§4.4/R-PC-4 中旧值 35s 残留。结论通过（附注 L-1-R/L-2-R 建议在后续提交中修正） |
| 2026-08-11 | ac-verifier | 验收通过，BR-concurrency-005 转 active | M6 Phase C 验收（TKN-M6-PHASEC-ACCEPTANCE-001）：US-006 10/10 AC 全部通过（AC-C-1 到 AC-C-10）。cross-app + skill 专项 378 测试（含 ac-verifier 补充 6 用例 M6PhaseCAcceptanceTest）全部通过（1 跳过性能基准），全量 1380 回归 0 失败。安全检查全部通过（URI 注入防护 / 日志脱敏 / 错误脱敏 CWE-209 / 用户确认 / Android 11+ 包可见性合规 / 协程取消安全 BR-error-handling-007）。性能变异为环境因素（JVM warmup/GC，被测函数均未在 Phase C 修改）。DEF-01（B2 严重）+ M-1（B1 一般）均已修复确认。BR-concurrency-005 proposed → active（M-1 修复正确 25s < 30s + 端到端测试验证 bridge 先超时返回语义化文案 + pending 清理 + ac-verifier 补充测试断言超时层级关系）。1 低危文档残留 DOC-01（ConversationViewModel KDoc "35s"→"25s"） |
| 2026-08-12 | 主 Agent | 新增 BR-animation-001 + BR-ui-001 | PrismSwitch padding 负值崩溃修复（TKN-BUGFIX-PRISMSWITCH-001/002）：用户报告 B3 致命崩溃（配置 Provider/MCP/Skills 时多次闪退）。logcat 捕获根因 `IllegalArgumentException: Padding must be non-negative` at PrismSwitch.kt:59，spring DampingRatioMediumBouncy (ζ=0.5) 欠阻尼过冲 16.3% 导致 offset 最低值约 -0.6dp。修复：`.padding(start = offset.coerceIn(0.dp, 18.dp))`。同时发现生物识别占位默认值 `true` 误导用户，改为 `false` + "即将支持" 标注。guardrail 通过 + ac-verifier 8/8 AC 通过（1497 回归 0 失败，6 处开关 69 次切换 0 崩溃，Golden Master 崩溃 3→0）。新增 DEF-001（B1 一般，Provider 保存后重启副标题未更新，留待用户验证确认） |
| 2026-08-12 | 主 Agent | 提议 BR-ui-002 + BR-naming-002 | DEF-001 Provider 配置保存功能双 Bug 修复（TKN-DEF001-ROOTCAUSE-002）：用户报告"保存按钮没有保存二字"+"无法保存"。code-archaeologist 故障定位根因：PrismSheet 无 verticalScroll + PrismSheetHost 未限制最大高度，导致 ProviderEditSheet 内容约 700dp 超出屏幕，"保存配置"按钮被裁剪不可见/不可点击。修复：PrismSheetHost 加 `heightIn(max=screenHeight*0.9f)` + `imePadding` + `navigationBarsPadding`；PrismSheet content 加 `weight(1f, fill=false)` + `verticalScroll`；重命名 `var enabled` 为 `activateAfterSave`。提议 BR-ui-002（弹层容器滚动+限高）+ BR-naming-002（变量名避免与组件参数同名）待 guardrail + ac-verifier 确认转 active |
| 2026-08-14 | 主 Agent | 新增 BR-security-007 + BR-error-handling-011/012 + BR-build-006 + BR-interface-006 | 问题 8（深度思考+联网搜索）与问题 1-7（真机测试 7 问题）修复闭环（TKN-P8 系列 / TKN-P17 系列）。问题 8：深度思考（thinking/reasoning_effort 参数 + ReasoningDelta）+ 联网搜索（Bing RSS 零配置 WebSearchLocalToolExecutor + Composite），guardrail 两轮通过 + ac-verifier 14/14 通过。问题 1-7：新对话/输出清洗/MCP 工具/知识库闪退/skills/键盘遮挡/跨 App 双弹窗，guardrail 四轮（阻断 B-1 Dispatcher describeTools 静默失效 + B-2 正则捕获组越界闪退 → 修复）+ ac-verifier 8/8 通过。全量 1583 用例 0 失败。5 条新规则：BR-security-007（外部内容回灌 LLM 边界标记）、BR-error-handling-011（入参 null+blank 双校验）、BR-error-handling-012（正则捕获组越界防御）、BR-build-006（临时构建改动禁止入库）、BR-interface-006（接口新方法路由类必须覆写转发） |
| 2026-08-15 | 主 Agent | 新增 BR-interface-008/009/010 | UX 二次反馈 10 问题修复闭环（TKN-UXR2-*，ADR-022）。根因修复：markdown 库 0.28+ 与 Compose 1.6.8 ABI 不兼容（0.26.0 为最高兼容版本，逐版本 AAR 字节码扫描实证）；chunkToEvents isNullOrBlank 丢弃纯换行 delta 导致 markdown 粘连；MCP 工具名直接拼接原始 server 名（空格/中文）生成非法名被过滤 + 400 工具重名。模拟机端到端验证：markdown 14 独立节点分层渲染、无裸符号、零崩溃；开关 toggleable 双向翻转；键盘 IME 正确贴合。3 条新规则：BR-interface-008（第三方 Compose 库升级须校验运行期 ABI）、BR-interface-009（流式解析保留结构字符，isNullOrEmpty 而非 isNullOrBlank）、BR-interface-010（外部标识符拼协议字段前须校验合法字符集） |
| 2026-08-16 | guardrail-enforcer + ac-verifier | 新增 BR-error-handling-015/016 | UXR7-R2 三问题修复闭环（TKN-UXR7R2-*，ADR-027 修订）。根因判定：首轮修复代码未进入真机 APK（APK 构建 01:37 早于源码 04:42-04:58，dex 字符串验证无新函数）——"多次修复依然存在"的直接根因是交付链断裂。网络调研 + 深度推理修正三处方案缺陷：搜索多候选核心词短整词降级重试（Bing OOV 分词坍缩，SearXNG #4964 同机制）、markdown 表格支持无分隔行紧凑表格（0.26.0 无表格组件）、引用池工具调用参数反向映射 + 成功读取过滤。guardrail 三轮（MED-01 假引用→R2 通过，DEF-001/002→R3 通过）+ ac-verifier 两轮（13 AC 全 PASS，全量回归 1792 用例 0 失败）。2 条新规则：BR-error-handling-015（LLM JSON 字段须显式拒绝 JsonNull/"null" 字面量防假引用）、BR-error-handling-016（日志记录用户输入必须截断防 CWE-532） |
| 2026-08-16 | guardrail-enforcer + ac-verifier | 新增 BR-interface-015 + BR-performance-002 | UXR8 批次2 优化闭环（TKN-UXR8-B2-GUARDRAIL-001/002 + ACCEPTANCE-001，ADR-029）。O1-O5 五项优化 + 6 项 guardrail 修复（G-01 画像静默覆盖 / G-03 搜索预算 / G-04 sheet 上限 / G-05 skill 工具名 / G-07 测试缺口 / G-09 公式注入）+ G2-01~04 即时闭环。guardrail 复审 PASS-with-notes（6 项全 FIXED）+ ac-verifier 17/17 AC PASS + 全量 1873 用例 0 失败 + 模拟器验证 O1/O2/O3/O4 UI 全部通过。2 条新规则：BR-interface-015（派生 key 冲突须生成唯一 key 防静默覆盖）、BR-performance-002（串行子请求须预算感知防总超时丢结果） |
| 2026-08-17 | guardrail-enforcer + ac-verifier | 新增 BR-ops-002 | UXR8 批次3 新功能闭环（TKN-UXR8-B3-GUARDRAIL-001/002 + ACCEPTANCE-001，ADR-030）。N1 用户规则文件 / N2 LLM 反问（Phase1+2）/ N3 文本模型视觉（方案 A）。guardrail 首轮有条件通过（4 MEDIUM + 2 可修 LOW 全修复：Q-MED-1 userRules runCatching 违规 / Q-MED-2 末轮 ask_user 误报循环上限 / Q-MED-3 历史图片污染 400 归因 / Q-MED-4 大图 OOM，Q-LOW-1 解析失败静默 / Q-LOW-2 占位孤儿）→ 复审通过。ac-verifier 3/3 AC PASS + 全量 1942 用例 0 失败 + lintDebug 0 errors。1 条新规则：BR-ops-002（同一文件多处修改必须串行单条 Edit，禁止同批并行 Edit 同文件——开发期实际踩坑：并行 Edit 写竞争导致改动丢失编译失败） |
| 2026-08-17 | 主 Agent | 新增 BR-interface-016 + BR-testing-008 | 真机测试 Bug 修复闭环（TKN-UXR8-FIX-GUARDRAIL-001/002 + ACCEPTANCE-001）。根因 1：document__create_xlsx 工具 `sheets` 参数 schema 为裸 JsonArray 字面量（非法 JSON Schema）→ DeepSeek 对**全部请求**（含图片请求、新/老会话）返回 400 Invalid schema，视觉功能完全不可用。修复为合法 `type:array + items:object` 结构。根因 2：Skill displayName 取 description 首行导致显示长句而非原名 → 改为优先取 SKILL.md body 首个 `# 一级标题`。根因 3：Skill 详情 body/systemPrompt 硬截断 500/200 字符 → 改为 ExpandableText 预览+展开全文。根因 4：Skills 缺失删除功能 → 新增 deleteSkill（isHidden 标记持久化 + 执行记录级联清理 + 非内置删磁盘目录），同时移除 5 个废弃内置 Skill（code-reviewer/meeting-notes/rewriter/summarizer/translator），scanAndSync 对 assets 已消失的内置 Skill 删除 DB 行（PURGE_BUILTIN）、对 isHidden 条目保留行（KEEP_HIDDEN）、对用户/远程文件缺失标记未安装（MARK_UNINSTALLED）。guardrail 两轮通过 + ac-verifier 6/6 AC PASS + 全量 1967 用例 0 失败 + 模拟器验证（5 内置 Skill 正确 purge、无崩溃）。2 条新规则：BR-interface-016（OpenAI 工具 schema 的 array 属性必须为 type:array + items 对象结构，禁止裸 JsonArray 字面量）、BR-testing-008（工具 schema 合法性须有结构断言测试防 400 复发） |

| 2026-08-19 | 主 Agent | 新增 BR-ops-004 | v1 真机二次修复（ADR-037）开发期踩坑：CrossSessionMemoryManager.kt 多行函数替换多次不命中（CRLF vs LF，PowerShell String.Replace 静默 0 替换）+ ReadAllLines/Get-Content 行数不一致。新增 BR-ops-004（改源码前统一 CRLF→LF 或按行数组拼接 + 字节级复验，禁止 here-string 多行块直传 Replace） |
| 2026-08-19 | guardrail-enforcer | 通过，A/C 建议采纳 | v1 真机修复审查（TKN-V1FIX-GUARDRAIL-001）：0 阻断，无注入/硬编码密钥/SSRF 回归/CWE-209，隐私授权边界完好（视觉旁路 OCR 本地兜底 + 云端仍受授权闸门）。采纳质量建议：ATOM_KEYWORDS 剔除淡化的非自我指涉词（防 L2 噪声）、CHALLENGE_MARKERS 分级（弱特征需正文极短才判定避免误伤正常长文）。全量回归通过 |
| 2026-08-19 | guardrail-enforcer | 有条件通过，B-1 阻断项已修复；新增 BR-search-003 + BR-vision-003 | v1 批次7（ADR-040）审查（TKN-SEARCH-VISION-ROUND5-001）：搜索 Bing→Baidu 多引擎回退 + 视觉专用 Provider 跳过熔断。**B-1 阻断项**（MEDIUM 隐私回归）：专用 Provider 分支漏 `isConsentGiven()`，用户设置页撤销授权后图片仍外发。修复为专用分支 `auto && config.isConsentGiven()`（只跳过熔断、仍守 consent 铁门）+ 补单测"专用 Provider + 授权撤销→OCR"。A-No.2/3/4/5（专用限流退避 / Baidu 跳转解码 / 简称↔全称放宽 / 主模型=视觉未打标授权引导）列为后续迭代已知限制。新增规则：BR-search-003（多引擎回退须复核相关性+预算）、BR-vision-003（专用 Provider 可跳过熔断但不得绕过 consent 铁门）。全量回归 2270 用例 0 失败 |
| 2026-08-19 | guardrail-enforcer | 有条件通过，M-1/M-2/M-3 已修复；新增 BR-network-002 | v1 批次8（PRD MCP/API 增强）审查（TKN-V1B8-MCP-ENHANCE-001）：US-001 博查模板 + US-002 今日热榜本地工具 + US-003 Jina Reader 抓取增强 + US-004 海外模板标注+移除 Brave。安全无阻断（SSRF/Key/日志脱敏/外部内容边界均合格）。**M-1**（Medium）：热榜复用 expectSuccess=true 的 searchHttpClient，状态码分支成死代码（ADR-032 R2 同款漂移）→ 独立 expectSuccess=false + 5s client + 状态码诊断文案；**M-2**（Medium）：Jina 失败无降级 → 改为降级直抓；**M-3**：Bocha 握手测试缺失 → 补嵌入式 Streamable HTTP Server 握手 + tools/list + callTool 测试。新增规则：BR-network-002（状态码诊断分支须 expectSuccess=false + 测试 client 与生产一致）。全量回归 2319 用例 0 失败 + APK 构建成功 |
| 2026-08-21 | 主 Agent | 新增 BR-vision-005 + BR-security-011 + BR-interface-021 | v1 批次13（ADR-041，真机 ANR 崩溃 + 多模态 + 提速 + E 强化）：A 崩溃根治（runScreenshot 不再内嵌 base64——视觉模型返回图片标记 / 纯文本模型返回 OCR 文本+坐标）/ B 多模态（supportsVision + 设置页开关 + 运行时自动检测（supportsVisionSet 隐私铁门）+ SkillExecutor image_url 注入（transientImage 持久化剥离）+ 400 visionUnsupported 降级链（剥离图片 + onVisionUnsupported 截图转 OCR/UI 树 + 重试本轮））/ C 提速（OCR 40/图标 15 条目上限 + 上下文去 base64）/ D（E 强化）type 接入 before/after + 软提示纠偏引导。全量回归 2464 功能用例 0 失败（唯一失败为 pdfbox-android glyphlist 资源在纯 JVM 基准不可用的既有环境限制，非本批次回归；guardrail M-2/M-3/L-3 修复后 2466 用例仅剩既有性能抖动复跑通过）+ lint 0 errors + APK 构建成功。新增 3 规则：BR-vision-005（transientImage 持久化剥离 + 400 降级重试）、BR-security-011（supportsVision 显式设置不被自动检测覆盖，隐私）、BR-interface-021（视觉开关按模型名自动启用开箱即用） |
| 2026-08-23 | 主 Agent + guardrail-enforcer + ac-verifier | 新增 BR-ops-005 | v1 批次14 保活 Bug 修复（ADR-041，真机「不用软件也弹窗+卡顿」）：根因为批次11 F2 把保活 FGS 绑在「无障碍启用期」而非「任务期」（dumpsys 实证常驻 1d8h10m + START_STICKY 重启循环）。修复：PhoneControlSessionManager 任务期动态保活状态机（首个 phone_control__* 工具调用启动/空闲 120s 停止/onDestroy reset）+ START_NOT_STICKY + FGS 后台启动拒绝可诊断降级 + 无障碍事件订阅收窄 typeWindowStateChanged+300ms（卡顿根治）。guardrail 两轮（M-1 TOCTOU 锁内单取值修复 / M-2 429 退避真空豁免记录）通过 + ac-verifier 7/7 PASS + 全量回归 2475 用例 0 失败 + lint 0 errors + 真机预验证（覆盖安装重绑后 KeepAlive 不再运行、id=2001 常驻通知消失）。新增规则：BR-ops-005（常驻资源绑定任务活跃期而非能力开关期 + 空闲超时释放 + 高频事件订阅须有消费者） |
