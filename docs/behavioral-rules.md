# Prism 行为规则累积（Behavioral Rules）

> 本文件是 CLAUDE.md 的动态累积层（第二十三节）。从 Bug 修复、PR 审查、运维 postmortem 中提炼可执行规则。
> 初始结构从 `docs/templates/behavioral-rules-template.md` 复制。试点期顺向累积，不回溯历史。

## 规则分类

### naming

（暂无规则，待累积）

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

#### BR-ui-001: Compose 状态持有可变列表时禁止原地改值

- 类别：testing
- 规则：将可变列表持有于 Compose 状态时，禁止对 `mutableStateOf(list)` 中的列表**原地改值**（如 `xs[i] = v`、`xs.add(...)`），因为 `mutableStateOf` 只侦测引用变化，原地改值不触发重组，导致 UI 不回显/丢输入。必须重建新列表，或使用 `mutableStateListOf`（其内部为快照列表，原地改值亦触发重组），与删除/新增行为保持一致。
- 反例：`var xs by remember { mutableStateOf(mutableListOf(...)) }; xs[i] = v` —— 界面不更新
- 正例：`val xs = remember { mutableStateListOf<Pair<String,String>>() }; xs[i] = v` —— 原地改值触发重组
- 来源：US-007 自定义 headers 编辑器审查（TKN-US007-GUARDRAIL-001，Q2）
- 添加日期：2026-08-06
- 适用场景：dev
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
