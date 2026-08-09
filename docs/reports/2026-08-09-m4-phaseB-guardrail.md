# 安全与质量审计报告：M4 Phase B（SKILL.md 解析器 + SkillRegistry + 内置 Skill）

> 从 `docs/templates/reports/guardrail-template.md` 复制新建，依 CLAUDE.md 第十节。
> 本报告由 guardrail-enforcer 子 Agent 生成，覆盖 M4 Phase B 全部代码变更的代码质量审查与安全漏洞扫描。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-M4-PHASEB-GUARDRAIL-001 |
| 审计日期 | 2026-08-09 |
| 关联 ADR | [ADR-013](../decisions/ADR-013-m4-skills-system-architecture.md) 5.2 / 5.3 |
| 关联代码变更 | `SkillManifestParser.kt` / `SkillRegistry.kt` / `SkillParseException.kt` / `PrismApplication.kt` / `SkillManifestParserTest.kt` / 5 个内置 `SKILL.md` / `libs.versions.toml` / `build.gradle.kts` |
| 关联影响自检 | [2026-08-09-m4-phaseB-impact-selfcheck.md](2026-08-09-m4-phaseB-impact-selfcheck.md) |
| 关联行为规则 | [behavioral-rules.md](../behavioral-rules.md) BR-error-handling-007 / BR-naming-001 |
| 风险等级 | P2 跨模块（新增第三方依赖 snakeyaml-engine-kmp + PrismApplication 启动初始化扩展） |

---

## 0. 审查范围与方法论

### 0.1 审查文件清单

| # | 文件 | 类型 | 行数 |
|---|---|---|---|
| 1 | [SkillManifestParser.kt](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt) | 新增 | 264 |
| 2 | [SkillRegistry.kt](../../app/src/main/java/io/prism/skill/SkillRegistry.kt) | 新增 | 290 |
| 3 | [SkillParseException.kt](../../app/src/main/java/io/prism/skill/SkillParseException.kt) | 新增 | 22 |
| 4 | [PrismApplication.kt](../../app/src/main/java/io/prism/PrismApplication.kt) | 修改 | +20 行 |
| 5 | [SkillManifestParserTest.kt](../../app/src/test/java/io/prism/skill/SkillManifestParserTest.kt) | 新增 | 523 |
| 6 | [libs.versions.toml](../../gradle/libs.versions.toml) | 修改 | +4 行 |
| 7 | [build.gradle.kts](../../app/build.gradle.kts) | 修改 | +2 行 |
| 8 | 5 个内置 SKILL.md（translator/code-reviewer/meeting-notes/summarizer/rewriter） | 新增 | ~350 |
| 9 | [ADR-013](../decisions/ADR-013-m4-skills-system-architecture.md) | 修改 | 5.2 节同步修订 |

### 0.2 审查方法

- **TRAE-code-review**：Karpathy Guidelines 符合性、逻辑正确性、性能隐患、可维护性、跨模块影响、测试充分性
- **TRAE-security-review**：OWASP Top 10 / CWE 分类、输入边界审计、执行安全审计、密钥与配置安全、依赖与供应链风险
- **行为规则核对**：逐条对照 `behavioral-rules.md` 中 active 规则（BR-error-handling-007 / BR-naming-001 等 24 条）
- **网络调研**：snakeyaml-engine-kmp 4.0.1 安全配置（`LoadSettings` 默认值、CVE 记录、`StandardConstructor` 沙箱化验证）

### 0.3 作者意图推断

**意图**：实现 M4 Phase B 的 SKILL.md 解析器（YAML frontmatter 分离 + snakeyaml-engine-kmp 解析 + 字段校验）与 Skill 注册中心（加载源扫描 + 优先级去重 + SkillConfig 表同步 + StateFlow 暴露），并在 PrismApplication.onCreate 中容错触发扫描。5 个内置 Skill 验证纯 prompt 注入与 tool_calling 两条路径。

---

## 1. 代码质量审查（TRAE-code-review）

### 1.1 Karpathy Guidelines 符合性

| 原则 | 评估 | 证据 |
|---|---|---|
| **外科手术式修改** | 符合 | PrismApplication 仅新增 `skillRegistry` lazy 属性 + onCreate 中 8 行扫描触发块，未触碰既有逻辑 |
| **显式假设** | 符合 | KDoc 明确标注 `Load` 非线程安全、每次创建新实例；`dedupByPriority` 标注优先级映射 |
| **可验证成功标准** | 符合 | 29 单元测试覆盖正向/边界/错误场景；585 全量回归 0 失败 |
| **不过度设计** | 符合 | `mapToManifest` 扩展函数 <50 行，类型容错简洁；未引入额外抽象层 |
| **错误处理** | 基本符合 | fail-fast 校验 + runCatching 隔离；但 G-01 违反 BR-error-handling-007（详见 1.3） |
| **命名达意** | 符合 | `splitFrontmatter` / `dedupByPriority` / `syncToRepository` / `mergeWithPersistedState` 语义清晰 |

### 1.2 逻辑正确性逐项验证

#### 1.2.1 splitFrontmatter 首行定位逻辑

[SkillManifestParser.kt:86-113](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L86-L113)

```kotlin
for (i in lines.indices) {
    val trimmed = lines[i].trim()
    if (trimmed.isEmpty()) continue
    if (trimmed == "---") {
        startIdx = i
    }
    break
}
```

**验证结论**：逻辑正确。循环跳过空白行（`continue`），定位第一个非空行：若为 `---` 则设置 `startIdx` 后 `break`；若非 `---` 则直接 `break`（`startIdx` 保持 -1，后续返回 null）。控制流等价于"仅检查第一个非空行是否为 `---`"。

测试 `parse frontmatter with leading blank lines before fence succeeds` 验证了前导空白行场景。

#### 1.2.2 toJsonElement 递归类型映射

[SkillManifestParser.kt:184-206](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L184-L206)

**验证结论**：类型映射覆盖完整（null/String/Boolean/Int/Long/Double/Float/Number/List/Map + else 兜底）。但**递归无深度限制**——若 YAML 含循环引用（通过别名/锚点 `&a [*a]`），`toJsonElement` 会无限递归导致 `StackOverflowError`。

snakeyaml-engine-kmp 4.0.1 的 `LoadSettings` 默认配置：
- `maxAliasesForCollections = 50`（限制别名展开总数，防 billion laughs）
- `nestingDepthLimit = 50`（限制 YAML 解析器嵌套深度，防深度嵌套栈溢出）
- `allowRecursiveKeys = true`（**默认允许递归键**，可创建循环引用的 Java 对象）

`nestingDepthLimit` 限制的是 YAML **解析阶段**的嵌套深度，不限制解析后 Java 对象图的遍历深度。循环引用 `Map A → Map A` 的 YAML 嵌套深度为 1，通过 `nestingDepthLimit` 检查，但 `toJsonElement` 递归遍历会无限。

**当前阶段风险**：低。SKILL.md 来源为内置 assets（APK 内置，不可修改）和 filesDir/skills/user（用户自建，自伤自害）。Phase C 远程下载未实现。

**Phase C 风险**：中。远程下载的 SKILL.md 可含恶意循环引用，导致应用崩溃。

详见 G-02。

#### 1.2.3 validate 字段校验

[SkillManifestParser.kt:152-171](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L152-L171)

**验证结论**：校验规则正确。
- `name`：slug 正则 `^[a-z0-9-]{1,64}$`（OpenClaw 规范）
- `description`：非空 + ≤160 字符
- `maxRounds`：1..50 范围
- `tools[].name`：非空 + 标识符正则 `^[a-zA-Z_][a-zA-Z0-9_]*$`

但 `validate` 抛出 `IllegalArgumentException`（`require` 语义），而 `mapToManifest` 抛出 `SkillParseException`。错误类型不一致（详见 G-05）。

#### 1.2.4 mapToManifest 类型容错

[SkillManifestParser.kt:120-251](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L120-L251)

**验证结论**：类型容错设计良好。
- `getString`：返回非空字符串或 null（`takeIf { it.isNotBlank() }`）
- `getBoolean`：支持 Boolean / String("true") / null(默认值)
- `getInt`：支持 Int / Long / Number / String / null(默认值)
- `getStringList`：支持 List / String(单值容错) / null
- `getToolList`：支持 List / null，逐项校验 mapping + name

#### 1.2.5 scanBuiltin assets.list 失败处理

[SkillRegistry.kt:121-143](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L121-L143)

**验证结论**：容错正确。`runCatching { assets.list() }.getOrNull()?.filter { ... } ?: return emptyList()`。单个 Skill 读取失败用 `continue` 跳过（在 inline `getOrElse` lambda 中，Kotlin 2.3.21 编译通过）。

但 `scanBuiltin` 的 `getOrElse` lambda 内使用 `continue`，与 `scanDirectory` 的 `return@getOrElse null ?: continue` 写法不一致（详见 G-03）。

#### 1.2.6 scanDirectory File.listFiles null 处理

[SkillRegistry.kt:150-176](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L150-L176)

**验证结论**：null 处理正确。前置 `if (!dir.exists() || !dir.isDirectory) return emptyList()` + `listFiles { ... } ?: return emptyList()`。存在 TOCTOU 竞态（检查后目录可能被删除），但在 Android 应用中可接受（filesDir 为应用私有目录，运行时不会被外部删除）。

#### 1.2.7 dedupByPriority 的 maxByOrNull...!! NPE 风险

[SkillRegistry.kt:212-223](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L212-L223)

```kotlin
.mapValues { (_, group) -> group.maxByOrNull { priority[it.config.source] ?: 0 }!! }
```

**验证结论**：`!!` 安全。`groupBy` 保证每个 group 至少含 1 个元素，`maxByOrNull` 不会返回 null。但使用 `!!` 违反 Kotlin 最佳实践，应加注释说明安全理由（详见 G-04）。

#### 1.2.8 syncToRepository 三分支策略

[SkillRegistry.kt:233-266](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L233-L266)

**验证结论**：策略正确。
- 新增：`existingConfig == null` → 创建 `SkillConfig(isEnabled=false)`
- 更新：`existingConfig != null` → `existingConfig.copy(...)` 保留 `isEnabled`（未在 copy 参数中覆盖）
- 标记缺失：表里有但扫描未发现 → `setInstalled(false)`

`existingConfig.copy(...)` 保留了 `isEnabled` / `dependsOnMcpServers` / `createdAt`，`updatedAt` 在 `save()` 中刷新。逻辑正确。

非原子操作（未包装在 `runInTx` 事务中），但启动扫描仅执行一次，崩溃后下次启动重新扫描。当前阶段可接受。

#### 1.2.9 mergeWithPersistedState 继承 isEnabled

[SkillRegistry.kt:274-285](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L274-L285)

**验证结论**：正确。从 `skillRepository.getAll()` 读取持久化状态，用 `stored`（含持久化的 id + isEnabled）替换 `entry.config`。未持久化的异常情况保持原样（config.id=0, isEnabled=false），注释已说明。

#### 1.2.10 PrismApplication.onCreate scanAndSync 容错

[PrismApplication.kt:260-267](../../app/src/main/java/io/prism/PrismApplication.kt#L260-L267)

```kotlin
appScope.launch {
    runCatching { skillRegistry.scanAndSync() }
        .onFailure { e ->
            android.util.Log.e("PrismApplication", "Skill scanAndSync failed", e)
        }
}
```

**验证结论**：容错结构正确（失败仅 Log.e，不阻断启动）。但 `runCatching` 包裹 suspend 函数 `scanAndSync()`，会捕获 `CancellationException`，违反 **BR-error-handling-007**（详见 G-01）。

### 1.3 跨模块影响识别

| 影响面 | 识别状态 | 处理 |
|---|---|---|
| 新增依赖 snakeyaml-engine-kmp 4.0.1 | 已识别 | libs.versions.toml + build.gradle.kts 已更新 |
| PrismApplication 启动流程扩展 | 已识别 | IO 协程 + runCatching 容错，不阻塞 UI |
| SkillRepository 新增消费方 | 已识别 | syncToRepository / mergeWithPersistedState 调用 Phase A 已验证 API |
| ADR-013 5.2 同步修订 | 已完成 | API 示意 + 版本号已更新 |
| ADR-013 5.3 SkillEntry 定义偏差 | **未同步** | 详见 G-06 |

### 1.4 测试框架与基础用例充分性

| 维度 | 评估 |
|---|---|
| 测试数量 | 29 单元测试，覆盖正向/边界/错误 |
| 等价类覆盖 | valid frontmatter / missing frontmatter / malformed YAML / 校验失败 / 类型映射 |
| 边界值覆盖 | name 长度 1/64/65；description 长度 0/160/161；max-rounds 1/50/100 |
| 真实样本验证 | translator / meeting-notes 内置 SKILL.md 样本 |
| **缺失场景** | 循环引用 YAML / 超大 YAML / 多行 description displayName 截取 / SkillRegistry 集成测试 |

**建议补充测试**（不阻断）：
1. `toJsonElement` 递归深度限制测试（若添加深度限制后）
2. `splitFrontmatter` Windows 换行符 `\r\n` 兼容性测试
3. `SkillRegistry.scanAndSync` 集成测试（Mock assets + filesDir）

---

## 2. 安全漏洞扫描（TRAE-security-review）

### 2.1 输入与边界审计

#### 2.1.1 YAML 反序列化攻击（CWE-502）

**分析**：

snakeyaml-engine-kmp 4.0.1 底层依赖 snakeyaml-engine 3.x，使用 `StandardConstructor`（默认），**仅构造标准 YAML 类型**（Map/List/String/Int/Long/Double/Float/Boolean），**不支持 `!!java/object` 等自定义标签**，不会通过反射构造任意 Java 类。

与 SnakeYAML 1.x（`org.yaml:snakeyaml` 1.x）的 `Constructor` 不同，snakeyaml-engine 从设计上移除了不安全的反序列化功能。SnakeYAML 1.x 的 CVE-2022-1471（RCE via `!!javax.script.ScriptEngineManager`）**不适用于 snakeyaml-engine**。

**结论**：**无 CWE-502 RCE 风险**。`StandardConstructor` 沙箱化有效。

#### 2.1.2 YAML Billion Laughs 攻击

**分析**：

`LoadSettings` 默认 `maxAliasesForCollections = 50`，限制别名展开总数。攻击者构造的 billion laughs 载荷在别名展开超过 50 时会被 Composer 拒绝并抛出异常。

**结论**：**已缓解**。默认配置足以防止 billion laughs 攻击。

#### 2.1.3 YAML 循环引用（StackOverflowError）

**分析**：

`LoadSettings` 默认 `allowRecursiveKeys = true`，允许 YAML 中的循环别名（如 `&a [*a]`）。解析后创建循环引用的 Java 对象（如 `Map A` 包含自身引用）。`toJsonElement` 递归遍历此类结构会无限递归，导致 `StackOverflowError`。

`nestingDepthLimit = 50` 限制的是 YAML **解析阶段**的嵌套深度，不限制解析后 Java 对象图的遍历深度。循环引用 `Map A → Map A` 的 YAML 嵌套深度为 1，通过 `nestingDepthLimit` 检查，但 `toJsonElement` 递归遍历会无限。

**当前阶段风险评估**：
- 内置 assets（APK 内置，不可修改）：**安全**
- filesDir/skills/user（用户自建）：用户自伤自害，**低风险**
- filesDir/skills/remote（远程下载）：Phase B 未实现，**当前无风险**

**Phase C 风险**：远程下载的 SKILL.md 可含恶意循环引用，导致应用崩溃。**Phase C 前必须修复**。

**安全审查裁定**：StackOverflowError 属于 DoS/可用性攻击，按 TRAE-security-review §8.1 Hard Exclusions 排除。但从代码质量角度标记为 G-02（中危建议）。

#### 2.1.4 路径遍历

**分析**：

`SkillRegistry.scanDirectory` 扫描 `filesDir/skills/user/` 和 `filesDir/skills/remote/`：
- `skillDirs` 来自 `dir.listFiles { f -> f.isDirectory }`，不直接拼接用户输入
- `SKILL.md` 是固定文件名，不存在路径拼接

`SkillRegistry.scanBuiltin` 扫描 `assets/skills/builtin/`：
- `dirName` 来自 `context.assets.list(builtinAssetsRoot)`，是 assets 目录列表，非用户输入
- `skillMdPath = "$builtinAssetsRoot/$dirName/SKILL.md"` 拼接的 `dirName` 来自系统 API

**结论**：**无路径遍历风险**。不涉及用户可控输入的路径拼接。

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

#### 2.2.1 注入防护

| 注入类型 | 状态 | 分析 |
|---|---|---|
| SQL 注入 | 不适用 | 项目使用 ObjectBox（NoSQL），无 SQL 查询拼接 |
| 命令注入 | 不适用 | 无 `Runtime.exec()` / `ProcessBuilder` 调用 |
| 代码注入 | 不适用 | 无 `eval()` / `ScriptEngine` 调用 |
| 模板注入 | 不适用 | 无模板引擎使用 |
| YAML 注入 | 已防护 | `StandardConstructor` 沙箱化，不构造任意类 |

#### 2.2.2 最小权限

- `SkillRegistry` 通过 `context.assets` 和 `context.filesDir` 访问文件，均为应用私有目录，不涉及高权限操作
- `PrismApplication` 未以 root 运行（Android 应用沙箱）
- 无容器化安全上下文（Android 原生应用）

**结论**：**符合最小权限原则**。

#### 2.2.3 输出编码

- `toJsonElement` 将原生 YAML 类型映射为 `JsonElement`，使用 kotlinx.serialization 标准库方法（`JsonPrimitive` / `JsonArray` / `JsonObject`），无字符串拼接 JSON
- `Log.w` / `Log.e` 输出目录名和异常消息，不含 HTML/JS 上下文

**结论**：**输出编码安全**。

### 2.3 密钥与配置安全

#### 2.3.1 硬编码密钥扫描

扫描全部修改文件，未发现硬编码的 API Key / password / token / secret。

#### 2.3.2 日志脱敏

| 日志位置 | 输出内容 | 敏感性 |
|---|---|---|
| `SkillRegistry.scanBuiltin` L130 | `Builtin skill '$dirName' SKILL.md read failed: ${e.message}` | 非敏感（目录名 + 异常消息） |
| `SkillRegistry.scanDirectory` L158 | `Skill dir '${skillDir.name}' missing SKILL.md, skip` | 非敏感（目录名） |
| `SkillRegistry.scanDirectory` L163 | `Skill '${skillDir.name}' SKILL.md read failed: ${e.message}` | 非敏感 |
| `SkillRegistry.parseToEntry` L191 | `Skill parse failed for '$skillDir': ${e.message}` | 非敏感（路径 + 异常消息） |
| `SkillRegistry.syncToRepository` L263 | `Skill '$name' no longer found, marked isInstalled=false` | 非敏感（slug 名） |
| `PrismApplication.onCreate` L265 | `Log.e("PrismApplication", "Skill scanAndSync failed", e)` | 非敏感（异常堆栈，无密钥/PII） |

**结论**：**日志脱敏合规**。未输出密钥、密码、令牌、完整 SQL、信用卡号或 PII。文件路径为应用私有目录（`/data/user/0/io.prism/files/...`），在 Android 上非高敏感。

#### 2.3.3 .gitignore 检查

本次变更未新增需忽略的文件。`assets/skills/builtin/` 下的 SKILL.md 是需要提交的资源文件。

### 2.4 依赖与供应链风险

#### 2.4.1 snakeyaml-engine-kmp 4.0.1 CVE 检查

| 维度 | 结果 |
|---|---|
| 包名 | `it.krzeminski:snakeyaml-engine-kmp` |
| 版本 | 4.0.1 |
| 底层引擎 | snakeyaml-engine 3.x（`org.snakeyaml:snakeyaml-engine`） |
| 已知 CVE | **无**。snakeyaml-engine 2.x/3.x 不受 SnakeYAML 1.x 的 CVE-2022-1471 影响（设计上移除了不安全反序列化） |
| 活跃维护 | 最后提交 2026-08-08（krzema12/snakeyaml-engine-kmp） |
| License | Apache 2.0（与项目兼容） |
| 传递依赖 | `org.snakeyaml:snakeyaml-engine`（纯 JVM，无 Native 库，不与既有依赖冲突） |
| 方法数 | ~300 方法（远低于 64K dex 上限，已启用 multidex） |

**结论**：**无已知 CVE**，依赖安全。建议在 CI 中集成 `dependencyCheck` 或 Dependabot 持续监控。

#### 2.4.2 供应链建议

```
建议执行：./gradlew dependencyCheckAnalyze
或配置 .github/dependabot.yml 监控 snakeyaml-engine-kmp 版本更新
```

---

## 3. OWASP / CWE 发现

| 编号 | 等级 | 类别 | 位置 | 描述 | 阻断 |
|---|---|---|---|---|---|
| G-01 | 中危 | BR-error-handling-007 违规 | [PrismApplication.kt:263](../../app/src/main/java/io/prism/PrismApplication.kt#L263) | `runCatching { skillRegistry.scanAndSync() }` 在协程中包裹 suspend 函数，吞 CancellationException | 否 |
| G-02 | 中危 | CWE-674（不受控递归） | [SkillManifestParser.kt:184-206](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L184-L206) | `toJsonElement` 递归无深度限制，YAML 循环引用 → StackOverflowError | 否 |
| G-03 | 低危 | 代码一致性 | [SkillRegistry.kt:129-132](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L129-L132) | `scanBuiltin` 中 `getOrElse` lambda 内 `continue`，与 `scanDirectory` 的 `return@getOrElse null ?: continue` 写法不一致 | 否 |
| G-04 | 低危 | 代码可维护性 | [SkillRegistry.kt:220](../../app/src/main/java/io/prism/skill/SkillRegistry.kt#L220) | `maxByOrNull { ... }!!` 使用 `!!`，缺少安全理由注释 | 否 |
| G-05 | 低危 | 错误处理一致性 | [SkillManifestParser.kt:152-171](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L152-L171) | `validate` 抛 `IllegalArgumentException`，`mapToManifest` 抛 `SkillParseException`，错误类型不一致 | 否 |
| G-06 | 低危 | 文档一致性 | [ADR-013 5.3](../decisions/ADR-013-m4-skills-system-architecture.md) | ADR-013 5.3 中 `SkillEntry` 有 `body` 字段，实际实现无（合并到 `manifest.body`），ADR 未同步修订 5.3 | 否 |
| G-07 | 低危 | 纵深防御 | [SkillManifestParser.kt:59](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L59) | `LoadSettings()` 使用默认配置，未显式设置 `setAllowRecursiveKeys(false)` / `setMaxAliasesForCollections(50)` | 否 |

### 3.1 G-01 详细分析

**位置**：[PrismApplication.kt:262-267](../../app/src/main/java/io/prism/PrismApplication.kt#L262-L267)

```kotlin
appScope.launch {
    runCatching { skillRegistry.scanAndSync() }
        .onFailure { e ->
            android.util.Log.e("PrismApplication", "Skill scanAndSync failed", e)
        }
}
```

**问题**：`runCatching { }` 捕获所有 `Throwable`（含 `CancellationException`），破坏结构化并发的取消传播。违反 **BR-error-handling-007**（active 状态）。

**实际影响评估**：
- `appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` —— SupervisorJob 不会被取消（除非进程终止）
- `scanAndSync()` 内部 `withContext(ioDispatcher)` 检查取消状态时抛出的 `CancellationException` 会被 `runCatching` 捕获
- 但 appScope 生命周期与进程相同，协程取消的实际概率为 0
- 与 US-019 的 viewModelScope 场景不同（ViewModel 销毁时取消概率高）

**严重度判定**：中危（而非高危）。理由：违反 active 行为规则，但实际影响为 0（appScope 不会被取消）。不构成阻断。

**修复建议**：
```kotlin
appScope.launch {
    try {
        skillRegistry.scanAndSync()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("PrismApplication", "Skill scanAndSync failed", e)
    }
}
```

### 3.2 G-02 详细分析

**位置**：[SkillManifestParser.kt:184-206](../../app/src/main/java/io/prism/skill/SkillManifestParser.kt#L184-L206)

**问题**：`toJsonElement` 是递归函数，处理 `List<*>` 和 `Map<*, *>` 时递归调用自身。若 YAML frontmatter 含循环引用（通过别名/锚点 `&a [*a]`），snakeyaml-engine 解析后创建循环引用的 Java 对象，`toJsonElement` 递归遍历会无限递归 → `StackOverflowError`。

**snakeyaml-engine-kmp 4.0.1 `LoadSettings` 默认配置**（经网络调研确认）：
- `maxAliasesForCollections = 50`：限制别名展开总数（防 billion laughs）
- `nestingDepthLimit = 50`：限制 YAML 解析器嵌套深度（防深度嵌套栈溢出）
- `allowRecursiveKeys = true`：**默认允许递归键**（可创建循环引用）

`nestingDepthLimit` 限制 YAML 解析阶段的嵌套深度，不限制解析后 Java 对象图的遍历深度。循环引用 `Map A → Map A` 的 YAML 嵌套深度为 1，通过检查。

**当前阶段风险**：低（SKILL.md 来源安全）。**Phase C 前必须修复**。

**修复建议**（二选一）：

方案 A：禁用递归键
```kotlin
val settings = LoadSettings.builder()
    .setAllowRecursiveKeys(false)
    .build()
Load(settings).loadOne(frontmatterText)
```

方案 B：添加递归深度限制
```kotlin
internal fun toJsonElement(value: Any?, depth: Int = 0, maxDepth: Int = 50): JsonElement {
    require(depth < maxDepth) { "YAML nesting depth exceeds $maxDepth" }
    return when (value) {
        // ... 同现有逻辑，递归调用时传 depth + 1
        is List<*> -> buildJsonArray {
            value.forEach { add(toJsonElement(it, depth + 1, maxDepth)) }
        }
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) ->
                require(k is String) { "..." }
                put(k, toJsonElement(v, depth + 1, maxDepth))
            }
        }
        // ...
    }
}
```

**推荐方案 A**（更简洁，从源头禁止循环引用）。

### 3.3 G-03 ~ G-07 汇总

| 编号 | 修复建议 |
|---|---|
| G-03 | 将 `scanBuiltin` 的 `getOrElse { e -> ... continue }` 改为 `getOrElse { e -> ...; null } ?: continue`，与 `scanDirectory` 统一 |
| G-04 | 在 `maxByOrNull { ... }!!` 处加注释：`// groupBy 保证 group 非空，!! 安全` |
| G-05 | 将 `validate` 中的 `require` 改为手动 `if + throw SkillParseException`，或在 `parse` 中用 `try-catch(IllegalArgumentException)` 包装 `validate` 并转为 `SkillParseException` |
| G-06 | 同步修订 ADR-013 5.3 的 `SkillEntry` 定义，移除 `body` 字段（已合并到 `manifest.body`） |
| G-07 | 显式设置 `LoadSettings.builder().setAllowRecursiveKeys(false).setMaxAliasesForCollections(50).build()`（与 G-02 方案 A 合并修复） |

---

## 4. 行为规则核对

| 规则 ID | 状态 | 核对结果 |
|---|---|---|
| BR-naming-001 | 未触发 | `SkillSource` 为 String 常量（Phase A 设计），非 enum，不适用 when 穷尽规则 |
| BR-error-handling-003 | 符合 | 无错误文案映射场景 |
| BR-error-handling-004 | 符合 | `parseToEntry` 的 `runCatching { parse(content) }.getOrElse` 有 Log.w 记录 |
| BR-error-handling-005 | 不适用 | 无显式 close 资源场景 |
| BR-error-handling-006 | 不适用 | `assets.open().use {}` 在 use 块内读取，无 require 前置 |
| **BR-error-handling-007** | **违反** | **G-01**：`PrismApplication.onCreate` 中 `runCatching { scanAndSync() }` 吞 CancellationException |
| BR-security-001 | 不适用 | 无数组字段 |
| BR-security-002 | 不适用 | 无 Keystore 操作 |
| BR-data-001 | 不适用 | 未新增自定义序列化转换器 |
| BR-security-003 | 不适用 | 无用户可配置 HTTP header |
| BR-concurrency-001 | 符合 | `syncToRepository` 多步操作未用事务，但启动扫描仅一次，崩溃后重扫。当前可接受 |
| BR-concurrency-002 | 不适用 | 无生命周期资源并发访问 |
| BR-concurrency-003 | 不适用 | 无 HNSW 索引删除 |
| BR-concurrency-004 | 符合 | `_skills.value = mergeWithPersistedState(deduped)` 是单次赋值（非 read-modify-write），StateFlow.value setter 原子 |

---

## 5. 结论

- [x] **通过**（可进入测试阶段）
- [ ] 阻断（存在严重缺陷或高危漏洞，回退编码阶段）

### 5.1 结论依据

1. **无阻断级安全漏洞**：YAML 反序列化无 RCE 风险（`StandardConstructor` 沙箱化）；无注入风险；无路径遍历；无硬编码密钥；无已知 CVE
2. **无高危安全漏洞**：所有安全审查项通过或被 Hard Exclusions 排除
3. **7 项发现均为中危/低危**，不构成阻断条件
4. **编译与回归通过**：585 测试 0 失败 0 错误 25 跳过
5. **G-01（BR-error-handling-007 违规）**：虽违反 active 行为规则，但 appScope 不会被取消，实际影响为 0。标记中危，强烈建议修复，不阻断进入测试

### 5.2 修复优先级

| 优先级 | 编号 | 修复时机 |
|---|---|---|
| 强烈建议 | G-01 | Phase C 前必须修复（保持规则合规性） |
| 强烈建议 | G-02 + G-07 | **Phase C 远程下载实现前必须修复**（合并修复：`LoadSettings.builder().setAllowRecursiveKeys(false).build()`） |
| 建议 | G-03 | 随手修复（代码一致性） |
| 建议 | G-04 | 随手修复（加注释） |
| 建议 | G-05 | 可延后（不影响功能） |
| 建议 | G-06 | 随手修复（ADR 同步） |

---

## 6. 规则提议（accepted review → behavioral-rules）

### 6.1 BR-security-004（提议）：YAML 解析必须显式配置 LoadSettings 安全参数

- **类别**：security
- **规则**：使用 snakeyaml-engine-kmp `Load` 解析 YAML 时，必须通过 `LoadSettings.builder()` 显式设置安全参数，至少包含 `setAllowRecursiveKeys(false)`（禁止循环引用）和 `setMaxAliasesForCollections(50)`（限制别名展开）。禁止使用无参 `LoadSettings()` 构造（依赖默认值），因为默认 `allowRecursiveKeys=true` 允许循环引用，下游递归遍历（如 `toJsonElement`）会导致 `StackOverflowError`。对于来自不可信源（远程下载）的 YAML，此配置为强制项。
- **反例**：`Load(LoadSettings()).loadOne(frontmatterText)` —— 默认 `allowRecursiveKeys=true`，循环引用 YAML 导致下游递归无限
- **正例**：`Load(LoadSettings.builder().setAllowRecursiveKeys(false).setMaxAliasesForCollections(50).build()).loadOne(frontmatterText)`
- **来源**：M4 Phase B 审查（TKN-M4-PHASEB-GUARDRAIL-001，G-02/G-07 中危发现）
- **添加日期**：2026-08-09
- **适用场景**：dev
- **状态**：proposed（待主 Agent 修复 G-02 后 ac-verifier 确认转 active）

---

## 7. 修复建议代码示例

### 7.1 G-01 + G-02 + G-07 合并修复

```kotlin
// SkillManifestParser.kt —— G-02 + G-07 修复
fun parse(content: String): ParseResult {
    val (frontmatterText, body) = splitFrontmatter(content)
        ?: throw SkillParseException("Missing YAML frontmatter (expected ---...--- fence)")

    // G-07 修复：显式配置安全参数，禁用递归键
    val settings = LoadSettings.builder()
        .setAllowRecursiveKeys(false)
        .setMaxAliasesForCollections(50)
        .build()
    val data: Any? = try {
        Load(settings).loadOne(frontmatterText)
    } catch (e: Exception) {
        throw SkillParseException("YAML frontmatter parse failed: ${e.message}", e)
    }
    // ... 后续逻辑不变
}

// PrismApplication.kt —— G-01 修复
appScope.launch {
    try {
        skillRegistry.scanAndSync()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("PrismApplication", "Skill scanAndSync failed", e)
    }
}
```

### 7.2 G-03 修复

```kotlin
// scanBuiltin 中统一写法
val content = runCatching { context.assets.open(skillMdPath).use { it.readBytes().decodeToString() } }
    .getOrElse { e ->
        android.util.Log.w(TAG, "Builtin skill '$dirName' SKILL.md read failed: ${e.message}")
        null
    } ?: continue
```

---

## 8. 自动化建议（CI/CD 集成）

### 8.1 依赖漏洞扫描

```yaml
# .github/workflows/security.yml
- name: Dependency Check
  run: ./gradlew dependencyCheckAnalyze
  # 或使用 Dependabot（已在 .github/dependabot.yml 配置）
```

### 8.2 Semgrep 规则建议

```yaml
# semgrep-rules/skills-yaml-security.yml
rules:
  - id: snakeyaml-loadsettings-explicit-config
    pattern: Load(LoadSettings()).loadOne(...)
    message: >
      LoadSettings() 使用默认配置，allowRecursiveKeys 默认为 true，
      允许循环引用。应显式设置 setAllowRecursiveKeys(false)。
    severity: WARNING
    languages: [kotlin]

  - id: coroutine-runcatching-swallows-cancellation
    pattern: appScope.launch { runCatching { $SUSPEND_FN() } }
    message: >
      协程中 runCatching 会吞 CancellationException，破坏取消传播。
      应改用 try-catch 并显式重抛 CancellationException。
    severity: WARNING
    languages: [kotlin]
```

---

## 9. 主 Agent 自问回应（基于审查发现）

### 9.1 "眼下最没有把握的事情是什么？"

主 Agent 对 `Load` 线程安全的设计判断是正确的——每次 `parse()` 创建新 `Load` 实例，无共享状态，可并发调用。`StateFlow.value` 是原子读，`enabledSkills()` 无需额外同步。

但主 Agent **未意识到** `LoadSettings` 默认 `allowRecursiveKeys=true` 的风险（G-02/G-07）。`toJsonElement` 递归遍历解析后的 Java 对象图时，若 YAML 含循环引用，会无限递归。`nestingDepthLimit=50` 限制的是 YAML 解析阶段的嵌套深度，不限制解析后对象图的遍历深度。这一区别是审查中发现的关键盲区。

### 9.2 "最大的遗憾 / 没有意识到什么？"

1. **displayName 取值策略**：主 Agent 自查已识别（影响自检 §8），用 `description.lineSequence().firstOrNull()?.take(60) ?: manifest.name` 作为 displayName。审查确认：当前实现可接受（给用户有意义的展示名），5 个内置 Skill 的 description 均为单行 ≤60 字符，截取无信息损失。建议在 ADR-013 5.1 备注 displayName 派生策略（G-06 范围内）。

2. **scanAndSync 的「新增 Skill 默认 isEnabled=false」策略**：主 Agent 判断正确。符合产品意图（Skill 应由用户主动启用，类似 MCP Server 的 isEnabled 默认 false），避免未授权 Skill 自动注入 system prompt。审查确认无需修改。

3. **未意识到的盲区**：`PrismApplication.onCreate` 中 `runCatching { scanAndSync() }` 违反 BR-error-handling-007（G-01）。虽然 appScope 不会被取消（实际影响为 0），但规则合规性要求修复。主 Agent 在影响自检中未识别此违规。

---

## 10. 保护机制验证

| 保护机制 | 配置状态 | 验证结果 |
|---|---|---|
| YAML `StandardConstructor` 沙箱化 | 默认启用 | 有效（不构造任意 Java 类） |
| `maxAliasesForCollections = 50` | 默认值 | 有效（防 billion laughs） |
| `nestingDepthLimit = 50` | 默认值 | 有效（防深度嵌套栈溢出） |
| `allowRecursiveKeys` | **默认 true（未显式配置）** | **无效**（G-07：应设为 false） |
| Android 应用沙箱 | 系统级 | 有效（不以 root 运行） |
| multidex | `multiDexEnabled = true` | 有效（snakeyaml-engine-kmp ~300 方法） |

---

## 豁免声明

无豁免项。所有发现均已记录，无安全策略明确接受的风险。
