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
