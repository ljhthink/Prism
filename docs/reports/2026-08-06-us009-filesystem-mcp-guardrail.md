# 安全与质量审计报告：US-009 内置 Filesystem MCP Server

> 本文含两轮审查：TKN-US009-GUARDRAIL-001（首轮，结论：有条件通过）与
> TKN-US009-GUARDRAIL-002（复审查，结论：通过）。复审查结论见文末「第 9 节」。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US009-GUARDRAIL-001 |
| 审计日期 | 2026-08-06 |
| 关联 ADR | [ADR-006](../../docs/decisions/ADR-006-filesystem-mcp-server.md) |
| 关联代码变更 | 新增 `io.prism.fs.*`（7 文件）+ `io.prism.network`（InProcessTransport / LocalMcpToolProvider / McpToolProviderDispatcher）+ 修改 `PrismApplication` / `CapabilitiesViewModel` / `CapabilitiesScreen` / `app/build.gradle.kts` |
| 主 Agent 自问 | ① 进程内握手时序 + UiConfirmationGate 无 replay 时确认请求丢失 → confirm 挂起？② removeFilesystemRoot/deleteServer 未释放持久化 URI 授权；Dispatcher 引入后 DI 与路由一致性 |

---

## 0. 结论（先读）

> **结论：有条件通过（Conditional Pass）—— 需主 Agent 修复下列「高风险/必改」项并重新提交审查后，方可进入 ac-verifier 阶段。**

本次变更**未发现阻断级（可主动利用的远程）漏洞**：无 SQL/命令/代码注入、无 eval/system、无硬编码密钥、无 CWE-209 信息泄露、无 XSS / 路径越权主动利用链。确认门禁缺省拒绝、输出编码、baseUrl/header CRLF 纵深防御均符合项目既有安全基线。

但存在 **1 个高危安全卫生问题（S1：撤销根目录未释放持久化授权）、2 个高危并发/功能缺陷（C1：SafFileAccess 根注册表线程不安全；C2：UiConfirmationGate 确认挂起与并发请求丢失）**，以及若干中低危项。其中 C2 为**潜在缺陷**——当前 `callTool` 尚未接入聊天流（仅经 Capabilities 测试连接走 `listTools`），一旦聊天接入文件工具调用即触发。以下问题必须修复并回退至编码阶段重新走 guardrail 闭环（CLAUDE.md 第七节 7.2）。

审计范围：10 个新增/修改主干文件 + 5 个测试文件，共审计 15 文件、约 30 个函数/方法、发现 9 项问题。

---

## 1. 代码质量审查（TRAE-code-review）

### 1.1 Karpathy Guidelines 符合性

- **命名**：`roots`/`resolveRoot`/`execute`/`renderEntry` 等命名清晰自解释，注释完备（ADR 引用、CWE 标注）。符合。
- **设计**：`FileSystemAccess` 接口抽象 + `SafFileAccess` 生产实现 + `InMemoryFileAccess` 测试 fake，职责单一、可测性良好；确认门禁置于 Server 处理器入口（而非客户端），与传输解耦，符合 ADR-006 5.4 决策。符合。
- **错误处理**：`execute` 捕获 `CancellationException` 重新抛出（结构化并发 CR-01），异常统一降级为通用文案（CWE-209）。符合。
- **测试**：fs 模块 23 用例（FilesystemMcpServer 11 / RootStore 6 / UiConfirmationGate 2 / Dispatcher 4）+ InMemoryFileAccess，覆盖工具注册、确认拒绝路径、参数缺失降级、持久化读写/覆盖/删除/损坏容错、路由分发。充分。

### 1.2 逻辑 / 性能 / 可维护性问题

见第 3 节 OWASP/CWE 表与第 4 节详细发现。核心问题集中在**并发正确性**（C1、C2、C3）与**安全卫生**（S1），详见后文。

### 1.3 跨模块影响识别

- 接口变更：`McpToolProvider` 新增 `LocalMcpToolProvider` / `McpToolProviderDispatcher` 实现；`FileSystemAccess` 新接口；`PrismApplication` 新增懒单例与方法；`CapabilitiesViewModel.Factory` 注入点改 `app.mcpToolProviderDispatcher`；`SafFileAccess` 构造器改 `Context`。均已在影响自检中声明，范围闭合，无遗漏调用方。
- 路由一致性核实：`PrismApplication` 将 `localMcpToolProvider` 与 `mcpClientManager`（实现 `McpToolProvider`）注入 `McpToolProviderDispatcher`，`serverType==LOCAL → local`、否则 `remote`，与 `McpServerConfig.serverType` 判定一致。**Dispatcher DI 与路由一致性正确。**

### 1.4 测试充分性评估

- **覆盖充分**：确认门禁缺省拒绝路径（`callTool read_file rejected by gate returns error`）、参数缺失降级（`missing path degrades to error`）、写入持久化在同一共享内存文件系统（`write_file persists content`）均有断言。
- **缺口**：① 无 `UiConfirmationGate` 并发多请求用例（当前仅单请求），未覆盖「后到请求覆盖先到、先到 deferred 挂死」场景（对应 C2）；② 无 `SafFileAccess` 路径越权（`..`/空段）边界用例（对应 S2）；③ 无 `removeFilesystemRoot` 释放授权断言（对应 S1）；④ 无 `registerFilesystemRoot` 与 `onCreate` 异步加载竞态用例（对应 C3）。建议修复后补充。

---

## 2. 安全漏洞扫描（TRAE-security-review）

### 2.1 输入与边界审计

- 工具参数经 `arg`/`argInt`/`argList` 提取，均以 `as? JsonPrimitive`/`as? JsonArray` 安全类型断言，缺失参数返回 `isError`，无类型崩溃。通过。
- `search_files.limit` 经 `toIntOrNull()` 兜底 `DEFAULT_SEARCH_LIMIT` + `coerceAtLeast(1)`，无负数/越界。通过（但无上限，见 C6）。
- **路径越权模型**：逻辑路径首段被严格解析为「根目录注册表」键（`resolveRoot`），未授权根目录任何路径均解析为 null → 抛错。`..`/空段依赖 SAF `findFile` fail-closed（无同名子项即 null）。**主动越权不可达**，但缺少显式段校验（纵深防御，S2）。

### 2.2 执行安全审计

- **注入类**：无 SQL 拼接、无 `system()`/`exec()` 命令执行、无 `eval`/`Function`。本地 Server 仅经进程内 `InProcessTransport` 桥接，不暴露网络端口。通过。
- **最小权限**：⚠️ **S1（高危）**——`removeFilesystemRoot` 仅从 `SafFileAccess.roots` 与 DataStore 移除逻辑映射，**未调用 `contentResolver.releasePersistableUriPermission`**。撤销的授权目录在系统级残留读写持久化授权（跨重启）。违反最小权限原则。
- **输出编码**：确认对话框经 Compose `Text` 渲染（默认转义，无 HTML 注入），无 XSS。`renderResult` 仅提取 `TextContent` 文本。通过（C7 下条目为展示隐私提示）。
- **CWE-209**：`execute` / `renderResult` / `LocalMcpToolProvider` 均不向调用方暴露内部异常细节（`e.message`/堆栈）。通过。

### 2.3 密钥与配置安全

- 无硬编码密钥/令牌/内部地址。API Key 走既定 `ApiKeyRepository`（Keystore 加密）。通过。
- `.gitignore` 已含 `.env`/`.env.*.local`/`keystore`/`*.jks`/运行时产物。通过。
- DataStore 键：`prism_api_keys` 与 `prism_filesystem_roots` 分离，授权根目录 URI 不落明文密钥。通过。

### 2.4 依赖与供应链风险

- `mcp-kotlin-sdk-server` 0.12.0 由 `testImplementation` 提升为 `implementation`（生产依赖，S3）；新增 `androidx.documentfile` 1.0.1。版本均固定（`libs.versions.toml` ref）。建议在 CI 加入依赖漏洞扫描（Android 侧可用 `dependency-check`/`ossindex`）并人工确认 mcp 0.12.0 无已知 CVE。

---

## 3. OWASP / CWE 发现汇总

| 编号 | 等级 | 类别 | 位置（相对路径） | 修复建议 |
|---|---|---|---|---|
| S1 | 高危 | 最小权限 / CWE-270（权限残留） | `app/src/main/java/io/prism/PrismApplication.kt:109-113`、`app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt:712` | 移除根目录前先取回其 URI，调用 `contentResolver.releasePersistableUriPermission(uri, READ or WRITE)`，再移除逻辑根并持久化 |
| C1 | 高危 | 并发数据竞争 | `app/src/main/java/io/prism/fs/SafFileAccess.kt:36-57,63,110,134-153` | `roots` 改为线程安全结构（如 `ConcurrentHashMap` 或 `MutableStateFlow<Map<String,Uri>>` 作为唯一事实源），消除主线程写 / IO 线程读竞争 |
| C2 | 高危（潜在） | 并发挂起 / 请求丢失 | `app/src/main/java/io/prism/fs/UiConfirmationGate.kt:36-52`、`app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt:627-656` | ① 确认宿主提升为全局（如 `MainActivity`/`NavHost` 级），勿仅挂于 CapabilitiesScreen；② UI 侧改为队列逐条确认，避免单 `pending` 覆盖导致先到 deferred 永不 resolve；③ 为 `confirm` 加超时/取消兜底，`MutableSharedFlow` 增加 `onBufferOverflow` 策略 |
| C3 | 中危 | 初始化竞态 | `app/src/main/java/io/prism/PrismApplication.kt:94-107,144-150` | `onCreate` 异步加载与 `registerFilesystemRoot` 去重加锁/串行化，或加载完成后合并，避免同名根被旧持久化 URI 覆盖 |
| S2 | 中危 | 路径遍历（纵深防御） | `app/src/main/java/io/prism/fs/SafFileAccess.kt:134-150` | 显式校验路径段，拒绝空段 / `"."` / `".."`，不依赖 SAF `findFile` 的 fail-closed 行为 |
| C4 | 低危 | 错误处理（BR-error-handling-004） | `app/src/main/java/io/prism/network/LocalMcpToolProvider.kt:41-43,65-68` | catch 兜底处补充结构化日志（不含密钥/路径），注明异常被归一化 |
| C5 | 低危 | 输入健壮性 | `app/src/main/java/io/prism/PrismApplication.kt:95-103` | 注册根目录名清洗（拒绝 `/` 及控制字符），避免破坏路径首段解析 |
| C6 | 低危 | 资源上限 | `app/src/main/java/io/prism/fs/SafFileAccess.kt:95-103` | `search_files.limit` 增加上限（如 100），避免超量结果渲染 |
| C7 | 低危 | 敏感展示 | `app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt:640-645` | 确认对话框对 `write_file.content` 截断/摘要展示，避免大段敏感内容预览 |

---

## 4. 详细发现（含证据与修复方向）

### S1（高危｜最小权限）：撤销根目录未释放持久化 URI 授权

**证据**：`removeFilesystemRoot` 仅执行 `safFileAccess.removeRoot(name)` + 异步 `filesystemRootStore.removeRoot(name)`。授权时（`CapabilitiesScreen` `FilesystemAuthorizationSection`）调用了 `takePersistableUriPermission(uri, READ or WRITE)`，但撤销路径**无对称的 `releasePersistableUriPermission`**。

**影响**：用户撤销授权目录后，应用在系统级仍持有对该目录的读写持久化授权（跨重启、跨 app 生命周期）。虽当前因逻辑根已移除、工具无法解析到该 URI 而不能直接经工具越权访问，但其**违背用户意图与最小权限原则**，构成权限残留（CWE-270）。若未来逻辑根复用同名，可能意外重新指向旧授权目录。

**修复方向**：`removeFilesystemRoot` 需先自 `SafFileAccess` 取回该名称对应的 URI，调用 `context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)`，再移除逻辑根与持久化条目。注意 `SafFileAccess` 需暴露「按名取 URI」能力。

### C1（高危｜并发）：`SafFileAccess.roots` 非线程安全

**证据**：`roots` 为普通 `LinkedHashMap`。写路径：`addRoot`/`removeRoot` 由 UI 主线程（`FilesystemAuthorizationSection` 回调）与 `onCreate` appScope（`Dispatchers.IO`）调用；读路径：`resolveRoot`/`resolveFile`/`writeFile` 在 `Dispatchers.IO` 的 `withContext` 内执行；`_rootsFlow.value = roots.keys.toList()` 会迭代 `roots`。

**影响**：主线程写与 IO 线程读并发，可能触发 `ConcurrentModificationException`（迭代时被修改）或读到不一致的根集合（新增根在列表化/解析时不可见），导致 `read_file`/`write_file` 偶发失败或崩溃。这正是主 Agent 自问「SAF writeFile 路径解析是否存在竞态」的根因。

**修复方向**：将 `roots` 收敛为单一线程安全事实源，例如 `MutableStateFlow<Map<String, Uri>>`（原子快照），`addRoot`/`removeRoot` 通过 `update { }` 原子更新，读路径读取 `.value` 快照；或改用 `ConcurrentHashMap` + 同步列表化。

### C2（高危｜潜在）：UiConfirmationGate 确认挂起与并发请求丢失

**证据**：`UiConfirmationGate` 用 `MutableSharedFlow(extraBufferCapacity = 16)`（无 replay、无 `onBufferOverflow` 策略）。`confirm` 中 `_requests.emit(...)` 后 `deferred.await()`。UI 宿主 `ToolConfirmationHost` **仅组合于 `CapabilitiesScreen`**，且持单一 `pending` 状态。

**两个子问题**：

1. **无收集者挂起**：当前 `McpToolProviderDispatcher` 仅注入 `CapabilitiesViewModel`，`callTool` 尚未接入聊天流；但一旦聊天调用文件工具，`CapabilitiesScreen` 未显示时 `requests` 无收集者——前 16 次 emit 静默入缓冲，`deferred.await()` 永不返回 → 工具调用**永久阻塞**。缓冲满后 `emit` 挂起。
2. **并发请求丢失**：即使宿主激活，`collectAsState(initial=null)` 单值状态在多个确认请求并发到达时，后到覆盖先到，**先到的 `deferred` 永不 resolve → 挂死**。

**影响**：核心 US-009 功能（AI 经聊天调用文件工具）接通后必然触发；当前为潜在缺陷。确认门禁「缺省拒绝」保证了安全方向（挂起不执行），但功能性不可接受。

**修复方向**：① 将 `ToolConfirmationHost` 提升为全局宿主（如 `MainActivity`/顶层 `NavHost`），保证任意屏皆有收集者；② UI 侧改为请求队列逐条确认，或为每个 `PendingConfirm` 独立对话框；③ 为 `confirm` 增加超时/取消兜底（超时按拒绝处理），`MutableSharedFlow` 明确 `onBufferOverflow` 策略。

### C3（中危｜初始化竞态）：`onCreate` 异步加载与注册去重竞态

**证据**：`onCreate` 中 `appScope.launch { loadRoots().forEach { safFileAccess.addRoot(...) } }` 异步执行；`registerFilesystemRoot` 去重仅依据当前 `safFileAccess.rootsFlow.value`。若用户快速注册与持久化根同名目录，异步加载可能在去重后又 `addRoot` 覆盖同名键，使新授权被旧持久化 URI 顶替。

**修复方向**：加载与注册串行化（如经同一 `Mutex`/`actor`），或加载完成后以「已加载集合」为准做合并去重；加载前先标记 `rootsLoaded`，注册时等待加载完成。

### S2（中危｜纵深防御）：路径段未显式校验

**证据**：`resolveFile`/`writeFile` 对路径 `split('/')` 后逐段 `findFile`，未显式拒绝空段 / `"."` / `".."`，依赖 SAF 无同名子项即 fail-closed。

**影响**：当前不可达主动越权（根目录键控 + SAF 行为），但属纵深防御缺口。建议工具层或 `SafFileAccess` 对每段做白名单校验（非空、非 `.`/`..`），并补充单测。

### C4 / C5 / C6 / C7（低危）

- **C4**：`LocalMcpToolProvider` 三处 `catch (e: Exception)` 静默降级，无结构化日志，违反 BR-error-handling-004（catch 兜底须记录）。
- **C5**：`registerFilesystemRoot` 目录显示名未清洗，含 `/` 或控制字符会破坏首段解析。
- **C6**：`search_files.limit` 无上限。
- **C7**：确认对话框直接展示工具参数（含 `write_file.content`），大段敏感内容会在预览中完整展示。

---

## 5. 保护机制验证

- **确认门禁缺省拒绝**：`FilesystemMcpServer.execute` 在 `confirm=false` 时返回 `isError=true`，不执行文件操作。已验证（测试 `rejected by gate`）。✅
- **CWE-209 信息泄露防护**：`execute`/`renderResult`/`LocalMcpToolProvider`/`McpClientManager` 均不暴露内部异常。✅
- **CRLF 纵深防御**：`McpClientManager.isValidBaseUrl`/`resolveHeaders` 连接层独立校验（既有基线，本次未回退）。✅
- **输出编码**：Compose `Text` 默认转义，无 XSS。✅
- **路径越权主动隔离**：根目录注册表模型 + 首段键控。✅（S2 为纵深防御补强）
- **Dispatcher 路由一致性**：`serverType==LOCAL→local`，DI 注入正确。✅

---

## 6. 结论

- [x] 通过（可进入测试阶段）
- [ ] 阻断（存在严重缺陷或高危漏洞，回退编码阶段）
- [x] **有条件通过** —— 需修复以下项后重新提交审查（回退至编码阶段，从 guardrail 重新开始闭环）：

| 优先级 | 编号 | 问题 | 严重度 |
|---|---|---|---|
| 必改 | S1 | 撤销根目录未释放 `releasePersistableUriPermission`，权限残留 | 高危 |
| 必改 | C1 | `SafFileAccess.roots` 线程不安全（主线程写 / IO 读竞争） | 高危 |
| 必改* | C2 | 确认门禁宿主未全局挂载 + 并发请求丢失 → `confirm` 挂死（聊天接入前须修复） | 高危（潜在） |
| 建议 | C3 / S2 | onCreate 加载竞态 / 路径段显式校验 | 中危 |
| 建议 | C4 / C5 / C6 / C7 | 日志 / 名称清洗 / limit 上限 / 参数展示摘要 | 低危 |

> *C2 为潜在缺陷：当前变更未将本地文件工具接入聊天流，故不构成阻断；但属「接通即触发」的必改项，须在聊天对接前闭合。

**修复后必须重新提交 guardrail-enforcer 审查，通过后方可启动 ac-verifier。**

---

## 7. 规则提议（accepted review → behavioral-rules）

以下 accepted review comment 建议追加至 `docs/behavioral-rules.md`（需主 Agent / guardrail 确认非重复且可执行）：

- **BR-security-004**（类别：security）：`takePersistableUriPermission` 授予的持久化 URI 授权，在对应资源被用户撤销/删除时必须对称调用 `releasePersistableUriPermission` 释放，避免权限残留（CWE-270）。反例：撤销根目录仅移除应用内逻辑映射而保留系统级授权。正例：移除映射前先 `releasePersistableUriPermission(uri, READ or WRITE)`。
- **BR-concurrency-002**（类别：concurrency）：进程内 MCP / 跨线程访问的共享可变容器（如文件夹注册表 `LinkedHashMap`）必须使用线程安全结构（`ConcurrentHashMap` 或 `MutableStateFlow` 原子快照），禁止主线程写 / IO 线程读裸容器。
- **BR-concurrency-003**（类别：concurrency）：`MutableSharedFlow` 承载「一次一请求、须等待响应的确认协议」时，必须保证 ① 有且仅有一个全局收集宿主（勿仅挂于子屏）；② 并发请求不被单值状态覆盖；③ `confirm` 有超时/取消兜底，避免 `await()` 永久挂起。

---

## 8. 自动化建议（CI/CD）

将以下检查集成到 `.github/workflows`（供主 Agent / 开发者参考）：

```yaml
# 依赖漏洞扫描（Android 侧）
- name: Dependency vulnerability scan
  run: ./gradlew dependencyCheckAnalyze  # 或集成 ossindex / deps.dev

# 静态安全扫描（Semgrep 规则示例）
- name: Semgrep persistable-uri-permission
  uses: returntocorp/semgrep-action@v2
  with:
    rules: |
      rules:
        - id: release-persistable-permission-on-revoke
          pattern: |
            takePersistableUriPermission(...)
          fix: ensure symmetric releasePersistableUriPermission on removal
```

---

## 9. 复审查结论（TKN-US009-GUARDRAIL-002）

> **结论：通过（Pass）—— 首轮「有条件通过」所列必改项（S1/C1/C2/C3/S2/C4-C7）已全部闭合，可进入 ac-verifier 阶段。**

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US009-GUARDRAIL-002 |
| 审计日期 | 2026-08-06 |
| 关联 ADR | [ADR-006](../../docs/decisions/ADR-006-filesystem-mcp-server.md) 5.8 节 |
| 复核范围 | S1 / C1 / C2 / C3 / S2 / C4 / C5 / C6 / C7 共 9 项修复 + 新增 2 测试用例 |
| 主 Agent 自问 | 修复是否真正消除首轮根因（而非仅压制症状）；并发路径是否仍存在挂死/覆盖；持久化授权是否对称释放 |

### 9.1 逐项复核结果

| 编号 | 首轮问题 | 修复落实情况 | 判定 |
|---|---|---|---|
| S1 | 撤销根目录未释放持久化授权 | `removeFilesystemRoot` 先经 `SafFileAccess.uriFor(name)` 取回 URI，`contentResolver.releasePersistableUriPermission(uri, READ or WRITE)` 后移除逻辑根与持久化（`PrismApplication.kt:124-140`）；`uriFor` 新增（`SafFileAccess.kt:65`）。释放包 `runCatching`，失败不阻断移除，方向安全。 | ✅ 闭合 |
| C1 | `SafFileAccess.roots` 线程不安全 | 改为 `MutableStateFlow<Map<String,Uri>>` 原子快照（`SafFileAccess.kt:44`）；`addRoot`/`removeRoot` 经 `update {}`（L55-62）；读路径读 `.value`（L65,68,124,144）；`rootsFlow` 暴露 `asStateFlow()`。消除主线程写 / IO 读竞争。 | ✅ 闭合 |
| C2 | 确认宿主未全局 + 并发请求丢失 + confirm 挂起 | ① `ToolConfirmationHost()` 提升至 `PrismApp` NavHost 外层（`PrismApp.kt:113`），任意 Tab 皆有收集者；② UI 改为 FIFO `mutableStateListOf` 队列逐条确认（`queue.remove` + `respond`，L128-164）；③ `confirm` 用 `withTimeoutOrNull(30_000)` 超时按拒绝返回 false（`UiConfirmationGate.kt:52`），`MutableSharedFlow(extraBufferCapacity=16, onBufferOverflow=DROP_OLDEST)`（L36-39）。 | ✅ 闭合 |
| C3 | `onCreate` 加载竞态 | `onCreate` 持久化根加载、`registerFilesystemRoot`、`removeFilesystemRoot` 均经同一 `rootsMutex` 串行化（`PrismApplication.kt:100,126,173`）。 | ✅ 闭合 |
| S2 | 路径段未显式校验 | `SafFileAccess.isSafeSegment` 逐段校验（非空、非 `.`/`..`、不含 `/`，L167-168），`resolveFile`（L157）与 `writeFile`（L121）均调用。 | ✅ 闭合 |
| C4 | catch 无日志 | `LocalMcpToolProvider` 两处 catch 补 `Log.w(LOG_TAG, …)`（L42-45,68-71），不含异常细节（CWE-209）。 | ✅ 闭合 |
| C5 | 根名未清洗 | `registerFilesystemRoot` 拒绝 `/` 与控制字符，回退 "root"（`PrismApplication.kt:101-103`）。 | ✅ 闭合 |
| C6 | `search_files.limit` 无上限 | `take(limit.coerceIn(1, MAX_SEARCH_LIMIT))`，`MAX_SEARCH_LIMIT=100`（`SafFileAccess.kt:109,213`）。 | ✅ 闭合 |
| C7 | 参数展示未截断 | `renderArguments` 长字符串截断 80 字符（`PrismApp.kt:168-175`，`MAX_ARG_DISPLAY=80` L177）。 | ✅ 闭合 |

### 9.2 新增测试核验

- `UiConfirmationGateTest` 由 2 增到 4 用例：`concurrent confirms resolve independently by id`（3 并发请求全部入列、逆序响应、均返回 true，验证 id 独立映射无覆盖）与 `confirm times out and returns false without response`（`advanceTimeBy(30s+1)` 后返回 false，不永久挂起）。均与原绑定 2 用例（允许/拒绝）一致。fs 模块 23+2=**25 用例**，与主 Agent 声明一致。

### 9.3 复审查发现的新问题

- **无阻断级 / 新高危问题。** 所有修复均消除首轮对应根因，未引入新的可主动利用漏洞（无注入、无硬编码密钥、无 CWE-209 泄露、无路径越权主动链）。
- **低危观察（记录不阻断，建议改进）**：`confirm` 超时后 `pending` 映射中的 `CompletableDeferred` 条目需待 UI `respond` 才清除；若对话框被弃置（如 Activity 重建而宿主已接收集），条目短暂滞留。因 30s 超时按拒绝兜底、方向安全，且 UI 逐条出队必 `respond` 清除，实践上受控，不构成缺陷。**不阻断。**
- **流程项（非代码）**：首轮「第 7 节」提出的 BR-security-004 / BR-concurrency-002 / BR-concurrency-003 三条规则**尚未写入 `docs/behavioral-rules.md`**（Grep 无匹配）。此为规则沉淀步骤，不阻塞本次代码闭合，但主 Agent 应在进入 ac-verifier 前或本轮闭环内补录，避免规则断层。

### 9.4 保护机制复核

- 确认门禁缺省拒绝：`confirm=false → isError`，不执行文件操作。✅（未回退）
- CWE-209：`LocalMcpToolProvider`、`FilesystemMcpServer.execute`、`renderResult` 均不暴露内部异常。✅
- 路径越权主动隔离：根目录注册表 + 首段键控 + `isSafeSegment` 逐段白名单。✅（S2 已补强）
- 输出编码：Compose `Text` 默认转义，无 XSS。✅
- 权限对称释放：`takePersistableUriPermission` ↔ `releasePersistableUriPermission` 对称。✅（S1 已闭合）

### 9.5 结论

- [x] **通过（Pass）** —— 首轮必改项与建议项全部闭合，**可进入 ac-verifier 阶段**。
- [ ] 阻断（回退编码阶段）
- [ ] 有条件通过（尚有未闭合项）

> 复审查确认：无阻断级或新高危漏洞，9 项修复均消除对应根因，fs 模块 25 用例通过。
> 低危观察与 behavioral-rules 规则补录为后续优化项，不阻塞本轮闭合。

---
