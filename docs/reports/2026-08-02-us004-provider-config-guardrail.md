# 安全与质量审计报告 —— US-004 定义 BYOK Provider 配置数据模型

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-PRISM-GUARDRAIL-006 |
| 审计日期 | 2026-08-02 |
| 关联 ADR | [ADR-001 Prism 技术栈与架构选型](../decisions/ADR-001-prism-tech-stack.md) |
| 关联代码变更 | 6 个新增源码 + 1 个新增测试 + `.gitignore`/`default.json` 修改 |
| 复用考古 | [US-002 ObjectBox 集成考古](2026-08-02-us002-objectbox-archaeology.md) |
| 行为规则自检 | [docs/behavioral-rules.md](../behavioral-rules.md) BR-build-003/004/005、BR-security-001/002、BR-testing-001、BR-interface-001 |
| 风险等级 | P1 常规（单个模块内部逻辑，不改接口/契约/依赖） |
| 审查 Skill | TRAE-code-review（代码质量）+ 手动结构化安全扫描（TRAE-security-review 当前会话不可用，由 guardrail-enforcer 依据 OWASP Top 10 / CWE 专业知识执行） |
| 推理辅助 | sequential-thinking MCP（8 步多步推理，覆盖转义边界/原子性/createdAt/注入/密钥/输入验证） |

---

## 0. 执行摘要

US-004 在 `io.prism.data` 包内新增 ProviderConfig 实体、CRUD 仓库、5 种预设模板及两个 ObjectBox 类型转换器。代码结构清晰、测试覆盖良好（32 测试全通过）、无阻断级安全漏洞（无 SQL 注入、无硬编码密钥、无命令/代码注入、apiKeyRef 仅存引用不存明文）。

**核心结论**：**通过**（可进入测试阶段），附带 2 项强建议（G-01 原子性、G-02 换行转义）与若干中低风险改进项。

**验证结果**：

- 编译检查（`compileDebugKotlin` + `compileDebugUnitTestKotlin`）：通过
- 单元测试（`ProviderConfigRepositoryTest`）：32 测试，0 失败，0 错误，0 跳过
- 验收标准覆盖：AC-1（字段）✓ / AC-2（5 种预设）✓ / AC-3（持久化）✓ / AC-4（CRUD）✓ / AC-5（Typecheck）✓

---

## 1. 代码质量审查（TRAE-code-review）

### 1.1 作者意图推断

本次变更意图：为 BYOK 多端点架构定义 Provider 配置数据模型，含实体定义、持久化仓库、预设模板与类型转换器，为后续 US-005/006/007（聊天核心）提供数据基础。StringMapConverter 的单次扫描反转义是对已知链式 replace 转义 bug 的修复。

### 1.2 变更概览（Mermaid）

```mermaid
flowchart LR
    subgraph 新增["US-004 新增数据层"]
        PC[ProviderConfig.kt<br/>@Entity 数据类]
        PCR[ProviderConfigRepository.kt<br/>CRUD + 激活管理]
        PP[ProviderPresets.kt<br/>5 种预设模板]
        SLC[StringListConverter.kt<br/>List↔String]
        SMC[StringMapConverter.kt<br/>Map↔String 已修复转义]
    end
    subgraph 持久化["ObjectBox 持久化"]
        DJ[default.json<br/>schema id=2]
        AKR[ApiKeyRepository<br/>apiKeyRef 引用加密 Key]
    end
    PC -->|@Convert| SLC
    PC -->|@Convert| SMC
    PCR -->|boxFor/put/get| PC
    PP -->|createFromPreset| PCR
    PC -.->|apiKeyRef 引用| AKR
    style PC fill:#c8e6c9,color:#1a5e20
    style SMC fill:#fff3e0,color:#e65100
    style AKR fill:#bbdefb,color:#0d47a1
```

### 1.3 Karpathy Guidelines 符合性

| 准则 | 评估 | 证据 |
|---|---|---|
| **Simplicity First**（最小代码解决问题） | 符合 | 数据类字段直观，仓库方法无过度抽象，无投机性"灵活性" |
| **Surgical Changes**（仅改动必要部分） | 符合 | 纯新增文件，未修改现有 KnowledgeChunk 或其他模块；`.gitignore`/`default.json` 修改是必要且克制的 |
| **Think Before Coding**（显式假设、表面困惑） | 符合 | StringMapConverter 的 KDoc 详细记录了转义规则与链式 replace bug 的根因，体现了对边界条件的深入思考 |
| **Goal-Driven Execution**（可验证成功标准） | 符合 | 32 个测试对应 5 条验收标准，覆盖 CRUD/预设/激活/类型转换往返 |

### 1.4 逻辑错误 / 性能隐患 / 可维护性

#### G-01【高风险】setActive / clearActive 缺乏事务原子性

- **位置**：[ProviderConfigRepository.kt:89-100](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L89-L100)（setActive）、[ProviderConfigRepository.kt:105-113](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L105-L113)（clearActive）
- **问题**：`setActive` 遍历 `box.all` 逐个 `box.put` 修改 `isActive`。若遍历中途抛出异常（磁盘满、IO 错误、ObjectBox 内部错误），可能留下多个 `isActive = true` 的 Provider，违反"同一时间仅一个激活"不变式。`clearActive` 有同样问题。
- **影响**：数据一致性破坏。用户可能看到多个"激活"Provider，后续业务逻辑依赖唯一激活 Provider 时行为不可预测。
- **修复建议**：使用 ObjectBox 事务 `boxStore.runInTx { ... }` 包装循环体，保证全成功或全回滚：

```kotlin
fun setActive(id: Long) {
    boxStore.runInTx {
        box.all.forEach { config ->
            if (config.id == id && !config.isActive) {
                config.isActive = true
                box.put(config)
            } else if (config.id != id && config.isActive) {
                config.isActive = false
                box.put(config)
            }
        }
    }
    refreshActiveProvider()
}
```

> 注：主 Agent 在自问中已识别此问题。当前为数据一致性风险而非安全漏洞，故不阻断，但强烈建议在进入 ac-verifier 前修复。

#### G-02【中风险】StringListConverter 未对换行符转义

- **位置**：[StringListConverter.kt:15-20](../../app/src/main/java/io/prism/data/StringListConverter.kt#L15-L20)
- **问题**：序列化用 `\n` 分隔，但模型名若含换行符（如 `"model\nA"`），序列化为 `"model\nA\nmodelB"`，反序列化 `split("\n")` 得到 3 个元素而非 2 个——数据损坏。与 StringMapConverter 对换行符做转义（`\n` → `\\n`）的设计不一致。
- **影响**：用户输入含换行的模型名时静默数据损坏。虽然 UI 单行输入框通常不允许换行，但违反零信任原则——外部输入应被认为可能含任意字符。
- **修复建议**：与 StringMapConverter 一致地对换行符转义，或在 `convertToDatabaseValue` 中校验元素不含 `\n` 并抛出 `IllegalArgumentException`：

```kotlin
override fun convertToDatabaseValue(entityProperty: List<String>): String =
    entityProperty.joinToString("\n") { it.replace("\\", "\\\\").replace("\n", "\\n") }

override fun convertToEntityProperty(databaseValue: String): List<String> =
    if (databaseValue.isEmpty()) emptyList()
    else databaseValue.split("\n").map { it.replace("\\n", "\n").replace("\\\\", "\\") }
```

#### G-03【中风险】ProviderConfig 输入验证缺失

- **位置**：[ProviderConfig.kt:33-44](../../app/src/main/java/io/prism/data/ProviderConfig.kt#L33-L44)、[ProviderConfigRepository.kt:44](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L44)
- **问题**：`name`/`baseUrl`/`apiKeyRef` 无任何校验（空字符串、长度上限、格式）。`save()` 直接 `box.put` 不验证。用户可能保存 `name = ""` 或 `baseUrl = ""`。
- **影响**：违反零信任原则。空 baseUrl 后续用于 HTTP 请求会引发异常；空 name 在 UI 列表中无法区分。
- **修复建议**：在 `save()` 中添加 `require(name.isNotBlank())` 等前置条件校验，或提供独立 `validate()` 方法供 UI 层调用。

#### G-04【低风险】getAll / findByName 全表扫描性能

- **位置**：[ProviderConfigRepository.kt:59](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L59)、[ProviderConfigRepository.kt:67-68](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L67-L68)
- **问题**：`getAll()` 加载全部到内存再 `sortedBy`；`findByName` 全表扫描。
- **评估**：Provider 数量通常 <20，性能影响可忽略。后续若规模增长可考虑 ObjectBox `@Index` 或 query。
- **建议**：暂不处理，记录为技术债。

#### G-05【低风险】activeProviderFlow 一致性窗口

- **位置**：[ProviderConfigRepository.kt:135-137](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L135-L137)
- **问题**：`activeProviderFlow` 仅在仓库方法内 `refreshActiveProvider()` 更新。若有外部代码直接通过 `BoxStore` 修改 `isActive`，Flow 不会同步。
- **评估**：当前无外部直接访问 box 的路径（`box` 为 private），风险低。后续 US 引入 DI 时需注意封装。

### 1.5 跨模块影响识别

- 纯新增，未修改现有 KnowledgeChunk 实体或任何已有模块。
- 新增类当前无外部调用方（US-005/006/007 后续依赖）。
- `default.json` 新增 ProviderConfig schema（id=2），未修改 KnowledgeChunk schema（id=1），向后兼容。
- 无 BREAKING CHANGE。自检结论正确。

### 1.6 测试框架与基础用例充分性

| 维度 | 评估 |
|---|---|
| **AC 覆盖** | AC-1（字段默认值）✓ / AC-2（5 种预设 + 唯一 apiKeyRef + 有效 baseUrl + 非空 models）✓ / AC-3（save→get 往返）✓ / AC-4（save/get/getAll/remove/removeAll/findByName）✓ |
| **类型转换往返** | models（含 dash/dots/slashes）、headers（含等号/换行/反斜杠+e/反斜杠+n/混合转义序列）覆盖良好 |
| **激活机制** | setActive 标记/切换取消、clearActive、Flow 反映状态，覆盖完整 |
| **缺失用例** | (1) `setActive(不存在的 id)` 行为未测；(2) StringListConverter 换行符边界未测；(3) 空字符串 name/baseUrl 未测（因代码无校验）；(4) 多个 Provider 同时 isActive 的异常场景未测（因代码无事务） |
| **结论** | 测试充分性良好，建议 ac-verifier 补充上述缺失用例 |

---

## 2. 安全漏洞扫描（手动结构化安全扫描）

> TRAE-security-review skill 在当前会话不可用。guardrail-enforcer 依据系统提示赋予的"代码安全护栏"专业职责，按 OWASP Top 10 / CWE 框架手动执行全部审计项。

### 2.1 输入与边界审计

#### 数值与类型边界

| 输入参数 | 来源 | 范围校验 | 结论 |
|---|---|---|---|
| `id: Long` | ObjectBox 自增 | id=0 新建，>0 更新；`get(id)` 不存在返回 null | 安全 |
| `createdAt: Long` | `System.currentTimeMillis()` | Long 类型，2099 年前不溢出 | 安全 |
| `isActive: Boolean` | 仓库方法管理 | 无外部直接设置路径（box 为 private） | 安全 |

#### 集合与缓冲边界

- **StringMapConverter**：逐字符 `escape` + 单次扫描 `unescape`，无缓冲区溢出风险。`StringBuilder` 自动扩容。深度验证（sequential-thinking 8 步推理）确认转义逻辑对所有边界正确：
  - value 以 `\` 结尾 ✓
  - value 仅含 `\` ✓
  - value 含字面 `\e`/`\n`（非转义）✓
  - key 含 `=`/换行 ✓（转义后不与分隔符冲突）
  - 空 key 往返 ✓（语义问题，非安全漏洞）
  - 超长 value ✓（无长度上限，受堆内存限制）
- **StringListConverter**：换行符未转义，见 G-02。
- **空值处理**：空 List/Map 序列化为空字符串，反序列化返回 emptyList/emptyMap。正确。

#### 业务状态机约束

- `isActive` 状态机：`setActive` 保证"仅一个激活"不变式，但缺事务原子性（G-01）。异常路径可能破坏不变式。
- 无直接修改 public 字段绕过状态检查的路径（`box` 为 private，`isActive` 通过仓库方法管理）。

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

#### 注入防护

| 注入类型 | 审查结论 |
|---|---|
| **SQL/NoSQL 注入** | **安全**。ObjectBox 是对象数据库，无 SQL 查询。`findByName` 用 `box.all.find { it.name == name }` 精确匹配，无字符串拼接 |
| **OS 命令注入** | **安全**。无 `system()`/`exec()` 调用 |
| **代码/表达式注入** | **安全**。无 `eval()`/`Function()`/动态加载 |
| **模板引擎注入** | **不适用**。无模板引擎 |
| **HTTP 头注入** | 当前仅存储 headers，不做 HTTP 请求。后续 US 使用 headers 构建 HTTP 请求时需校验 header 值不含 CRLF（CWE-113） |

#### 最小权限

- ObjectBox 本地存储，无数据库账户/OS 服务账户权限问题。
- 无不必要的权限请求（无读写 `/etc/passwd` 等系统文件）。
- 非容器化部署，无 security context 审查项。

#### 输出编码

- 当前 US 仅存储数据，无 HTML/JS/CSS/URL 输出上下文。
- `baseUrl` 后续用于 HTTP 请求时需校验 scheme（http/https），防止 `file://` 等 scheme（CWE-939）。当前 presets 均用 http/https，但 ProviderConfig 不校验 baseUrl 格式（见 G-03）。

### 2.3 密钥与配置安全

| 审查项 | 结论 | 证据 |
|---|---|---|
| **apiKeyRef 不存明文** | **安全** | `apiKeyRef` 存储引用标识符（如 "openai"），明文 Key 由 `ApiKeyRepository` 经 Tink AEAD 加密存入 DataStore。见 [ApiKeyRepository.kt](../../app/src/main/java/io/prism/security/ApiKeyRepository.kt) |
| **无硬编码密钥** | **安全** | 扫描全部新增文件，无 API Key/密码/token/内部 IP |
| **headers 潜在明文存储** | **中风险** | headers 字段明文存入 ObjectBox。若用户在 headers 中配置 `Authorization: Bearer <token>`，则 token 明文存储。当前预设 headers 均为 emptyMap()，但 UI 层若允许 headers 存敏感数据则有 CWE-312 风险。建议：文档/UI 约束 headers 不存敏感信息，敏感凭证统一走 apiKeyRef 引用机制 |
| **.gitignore 排除 .bak** | **正确** | 新增 `app/objectbox-models/*.bak` 排除规则，符合 BR-build-004 精神。`default.json` 仍入库，符合 BR-build-005 |
| **default.json 提交** | **正确** | schema id=2 新增，KnowledgeChunk schema 未变，向后兼容 |

### 2.4 依赖与供应链风险

- **无新增依赖**：US-004 仅使用 US-002 已引入的 ObjectBox 5.4.2，无 `libs.versions.toml`/`build.gradle.kts` 依赖变更。
- **无锁文件变更**。
- **无已知 CVE**：ObjectBox 5.4.2 已在 US-002 审查中确认无已知高危 CVE。
- **结论**：无供应链风险。

---

## 3. OWASP / CWE 发现汇总

| 编号 | 等级 | CWE | 位置 | 描述 | 修复建议 |
|---|---|---|---|---|---|
| G-01 | 高风险 | CWE-754（不当异常条件处理）/ CWE-362（竞态条件） | [ProviderConfigRepository.kt:89-100](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L89-L100) | setActive/clearActive 逐个 put 无事务，异常时破坏"仅一个激活"不变式 | 用 `boxStore.runInTx { }` 包装循环体 |
| G-02 | 中风险 | CWE-20（输入验证不当） | [StringListConverter.kt:15-20](../../app/src/main/java/io/prism/data/StringListConverter.kt#L15-L20) | 换行符未转义，模型名含换行时数据损坏 | 对换行符转义或校验输入不含换行 |
| G-03 | 中风险 | CWE-20（输入验证不当） | [ProviderConfig.kt:33-44](../../app/src/main/java/io/prism/data/ProviderConfig.kt#L33-L44) | name/baseUrl/apiKeyRef 无空值/格式/长度校验 | save() 添加 require 前置条件 |
| G-04 | 中风险 | CWE-312（敏感信息明文存储） | [ProviderConfig.kt:40-41](../../app/src/main/java/io/prism/data/ProviderConfig.kt#L40-L41) | headers 明文存入 ObjectBox，可能含敏感凭证 | UI/文档约束 headers 不存敏感信息 |
| G-05 | 低风险 | — | [StringMapConverter.kt:27-34](../../app/src/main/java/io/prism/data/StringMapConverter.kt#L27-L34) | 空 key 可往返但语义可能非预期 | 可选：序列化时过滤空 key |
| G-06 | 低风险 | — | [ProviderConfig.kt:43](../../app/src/main/java/io/prism/data/ProviderConfig.kt#L43) | createdAt 默认值在构造时立即求值 | 可选：改用 0L 默认值让 ObjectBox 填充 |
| G-07 | 低风险 | CWE-939（URL 授权范围不当） | [ProviderConfig.kt:36](../../app/src/main/java/io/prism/data/ProviderConfig.kt#L36) | baseUrl 无 scheme 校验 | 后续 US 使用时校验 http/https |

---

## 4. 行为规则自检

对照 `docs/behavioral-rules.md` 现有规则逐条检查本次变更是否违反：

| 规则 ID | 规则摘要 | 本次变更符合性 |
|---|---|---|
| BR-build-003 | Maven 镜像 content 过滤 | 不涉及（无依赖变更） |
| BR-build-004 | ObjectBox JNI 库加入 .gitignore | 符合（无新增 JNI 文件） |
| BR-build-005 | ObjectBox schema 文件必须提交 | **符合**（default.json 已纳入变更，含 ProviderConfig schema） |
| BR-security-001 | data class 含数组字段必须覆盖 equals/hashCode | **符合**（ProviderConfig 无数组字段，使用 List/Map，data class 自动 equals 基于内容比较） |
| BR-security-002 | Keystore StrongBox 捕获通用异常 | 不涉及（US-004 无 Keystore 操作） |
| BR-testing-001 | 测试替身复现原组件语义 | 不涉及（US-004 测试用真实 ObjectBox 实例，非替身） |
| BR-interface-001 | UI 设计需用户审核 | 不涉及（US-004 纯数据模型，无 UI） |

**结论**：本次变更未违反任何现有行为规则。

---

## 5. 结论

- [x] **通过**（可进入测试阶段）
- [ ] 阻断（存在严重缺陷或高危漏洞，回退编码阶段）

### 判定依据

1. **无阻断级安全漏洞**：无 SQL 注入、无硬编码密钥、无命令/代码注入、apiKeyRef 仅存引用不存明文。OWASP Top 10 全项通过。
2. **编译通过 + 32 测试全通过**，AC-1~AC-5 全覆盖。
3. **无阻断级质量缺陷**：代码符合 Karpathy Guidelines，结构清晰，无逻辑错误。
4. **G-01 高风险**为数据一致性问题（非安全漏洞），触发需异常场景（磁盘满/IO 错误），主 Agent 已识别。判定为"通过"但**强建议**在 ac-verifier 前修复。

### 强建议（进入 ac-verifier 前处理）

| 编号 | 建议 | 优先级 |
|---|---|---|
| G-01 | setActive/clearActive 用 `runInTx` 保证原子性 | 强建议 |
| G-02 | StringListConverter 对换行符转义 | 强建议 |

### 可后续优化项

| 编号 | 建议 | 优先级 |
|---|---|---|
| G-03 | save() 添加输入校验 | 建议（可在 UI 层处理） |
| G-04 | headers 敏感信息约束 | 建议（后续 US-005 UI 设计时处理） |
| G-05/G-06/G-07 | 低风险优化 | 可选 |

---

## 6. 规则提议（accepted review → behavioral-rules）

### 提议 BR-concurrency-001：多步骤数据库状态变更必须使用事务保证原子性

- **类别**：concurrency
- **规则**：当一个数据库操作方法需要修改多条记录以维护业务不变式（如"同一时间仅一个激活"、"唯一默认值"等）时，必须使用数据库事务（如 ObjectBox `runInTx`、Room `@Transaction`）将所有修改包装为原子操作。逐条 put/update 在异常场景下可能破坏不变式，留下不一致状态。
- **反例**：`fun setActive(id: Long) { box.all.forEach { if (it.id == id) { it.isActive = true; box.put(it) } else if (it.isActive) { it.isActive = false; box.put(it) } } }` —— 遍历中途异常留下多个 isActive=true
- **正例**：`fun setActive(id: Long) { boxStore.runInTx { box.all.forEach { ... box.put(it) } }; refresh() }` —— 事务保证全成功或全回滚
- **来源**：US-004 ProviderConfig 审查（TKN-PRISM-GUARDRAIL-006，G-01 发现）
- **添加日期**：2026-08-02
- **适用场景**：dev
- **状态**：active（主 Agent 已确认 2026-08-02，修复同步落地）

### 提议 BR-data-001：自定义序列化转换器必须对所有分隔符与特殊字符做转义

- **类别**：security
- **规则**：为 ORM 类型转换器（如 ObjectBox `PropertyConverter`）实现自定义序列化时，必须对所用分隔符（换行、等号、逗号等）和转义字符（反斜杠）做完整转义。若序列化格式用某字符做分隔符，该字符在数据中出现时必须转义，否则会导致数据损坏（元素数量变化/键值错位）。同一项目内多个转换器应保持一致的转义策略。
- **反例**：`StringListConverter` 用 `\n` 分隔但不对元素中的 `\n` 转义 —— 模型名含换行时 `split("\n")` 产生多余元素
- **正例**：`StringMapConverter` 对 `\`/`\n`/`=` 全部转义，单次扫描反转义 —— 无歧义
- **来源**：US-004 ProviderConfig 审查（TKN-PRISM-GUARDRAIL-006，G-02 发现）
- **添加日期**：2026-08-02
- **适用场景**：dev
- **状态**：active（主 Agent 已确认 2026-08-02，修复同步落地）

---

## 7. 自动化建议（CI/CD 集成）

| 检查项 | 工具 | 集成方式 |
|---|---|---|
| Kotlin 静态分析 | detekt | GitHub Action 中 `./gradlew detekt`，规则含 `RequireNotNull`/`ReturnCount`/`ComplexMethod` |
| 安全扫描 | Semgrep | 配置 `p/kotlin` 规则集，检测硬编码密钥/不安全反序列化 |
| 依赖漏洞 | Dependabot + npm-audit 等效（Gradle 用 `dependency-check`） | `.github/dependabot.yml` 已配置；CI 中 `./gradlew dependencyCheckAnalyze` |
| 测试覆盖率 | JaCoCo + kover | CI 中 `./gradlew koverVerify`，语句 ≥90% / 分支 ≥80% |

---

## 8. 复审记录（G-01 / G-02 修复确认）

| 项目 | 内容 |
|---|---|
| 复审轮次 | 第 2 轮（修复后快速复审） |
| 复审日期 | 2026-08-02 |
| 任务令牌 | TKN-PRISM-GUARDRAIL-006（不变） |
| 触发原因 | 主 Agent 完成 G-01（setActive 事务原子性）+ G-02（StringListConverter 换行转义）修复 |
| 推理辅助 | sequential-thinking MCP 3 步验证（runInTx 安全性 / 转义往返 / 综合结论） |

### 8.1 G-01 修复验证：setActive/clearActive 事务原子性

**修复文件**：[ProviderConfigRepository.kt](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt)

**修复内容**：

- 构造函数改为 `private val boxStore: BoxStore`（保留引用用于事务）
- `setActive`（第 93-106 行）：循环体用 `boxStore.runInTx { ... }` 包装，`refreshActiveProvider()` 在事务外调用
- `clearActive`（第 114-124 行）：同样用 `runInTx` 包装

**验证结论**：**修复正确**

| 验证点 | 结论 | 依据 |
|---|---|---|
| 事务原子性 | 正确 | `runInTx` 保证全成功或全回滚，异常时不留下多个 isActive=true |
| 事务内 box.all/box.put 安全性 | 安全 | ObjectBox 事务内 `box.all` 返回事务可见快照，`box.put` 在提交时持久化 |
| refreshActiveProvider 位置 | 正确 | 在 `runInTx` 块外调用，读取已提交状态；若在事务内调用可能读到未提交修改 |
| boxStore 改 private 影响 | 无影响 | 构造函数参数类型不变（BoxStore），仅加 `private val` 存为字段；setActive/clearActive 签名不变 |
| 二次自检"无外部变更" | 确认正确 | 无接口/契约/依赖变更，无 BREAKING CHANGE |

### 8.2 G-02 修复验证：StringListConverter 换行符转义

**修复文件**：[StringListConverter.kt](../../app/src/main/java/io/prism/data/StringListConverter.kt)

**修复内容**：

- 新增 `escape` 方法：逐字符扫描，`\` → `\\`，换行符 → `\n`（与 StringMapConverter 一致）
- 新增 `unescape` 方法：单次扫描反转义（与 StringMapConverter 一致逻辑，无 `=` 处理因 List 不需要）
- `convertToDatabaseValue`：`joinToString("\n") { escape(it) }`
- `convertToEntityProperty`：`split("\n").map { unescape(it) }`（转义后的 `\n` 是 `\`+`n` 两字符，不会被字面换行符 split 误分割）

**验证结论**：**修复正确**

3 个新增测试用例往返验证（sequential-thinking 逐步推演）：

| 测试用例 | 输入（Kotlin 字面量） | escape 后 | split 后 | unescape 后 | 往返 |
|---|---|---|---|---|---|
| `models_with_newline_round_trip` | `["model\nwith\nnewline", "normal-model"]`（含字面换行符） | `["model\\nwith\\nnewline", "normal-model"]` | 2 元素 | `["model\nwith\nnewline", "normal-model"]` | 正确 |
| `models_with_backslash_round_trip` | `["model\\with\\backslash", "normal"]`（含字面反斜杠） | `["model\\\\with\\\\backslash", "normal"]` | 2 元素 | `["model\\with\\backslash", "normal"]` | 正确 |
| `models_with_backslash_followed_by_n_round_trip` | `["model\\n", "path\\to\\model"]`（反斜杠+n 非换行） | `["model\\\\n", "path\\\\to\\\\model"]` | 2 元素 | `["model\\n", "path\\to\\model"]` | 正确 |

### 8.3 观察项（非阻断）

**StringListConverter 向后兼容性**：转义修复对含反斜杠的"旧数据"有理论兼容性风险——旧数据中未转义的反斜杠+n 会被新 unescape 错误还原为换行符。但 US-004 是全新 schema（id=2），数据库中无旧数据；且模型名通常不含反斜杠/换行。**当前无实际影响**，仅在未来数据迁移场景需注意。

### 8.4 测试与编译验证

| 验证项 | 结果 |
|---|---|
| 编译（`compileDebugKotlin` + `compileDebugUnitTestKotlin`） | 通过 |
| `ProviderConfigRepositoryTest` | 35 测试，0 失败，0 错误，0 跳过（原 32 + 新增 3） |
| 行为规则状态 | BR-concurrency-001 / BR-data-001 均已更新为 active |

### 8.5 复审结论

- [x] **复审通过**（G-01 / G-02 修复均正确，无新问题引入，可进入 ac-verifier 阶段）
- [ ] 复审阻断（修复不正确或引入新问题，回退编码阶段）

**判定依据**：

1. G-01 事务原子性修复正确，`runInTx` 包装保证不变式，`refreshActiveProvider` 事务外调用位置正确。
2. G-02 转义修复正确，与 StringMapConverter 策略一致，3 个新增边界测试全通过。
3. 35 测试全通过，编译通过。
4. 无新问题引入（boxStore private 不影响调用方；转义对全新 schema 无兼容性风险）。
5. 剩余 G-03~G-07 为中低风险可后续优化项，不阻断进入 ac-verifier。

### 8.6 guardrail-enforcer 独立复审确认

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-PRISM-GUARDRAIL-006（独立复审） |

本子 Agent 在收到任务后，独立读取全部相关文件完成证据采集，未依赖第 8 节既有结论，逐项复核如下：

#### G-01 独立验证（runInTx 事务）

- [ProviderConfigRepository.kt:93-106](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L93-L106)：`setActive` 循环体整体包入 `boxStore.runInTx`，`refreshActiveProvider()` 在事务外调用（读取已提交状态，正确）。
- [ProviderConfigRepository.kt:114-124](../../app/src/main/java/io/prism/data/ProviderConfigRepository.kt#L114-L124)：`clearActive` 同样事务包装。
- `runInTx(Runnable)` 为 ObjectBox 5.4.2 核心 API，Kotlin lambda 经 SAM 转换成立（编译+运行通过，35 测试证实）。`box.put` 在事务内不产生嵌套事务。异常时事务回滚，维持"仅一个激活"不变式。
- 调用方扫描：全库仅测试文件 [ProviderConfigRepositoryTest.kt:36](../../app/src/test/java/io/prism/data/ProviderConfigRepositoryTest.kt#L36) 构造实例并传 `BoxStore`，构造函数改 `private val boxStore` 不影响调用方，签名未变。

#### G-02 独立验证（单次扫描反转义）

对 [StringListConverter.kt](../../app/src/main/java/io/prism/data/StringListConverter.kt) 的 escape/unescape 逐字符推演，覆盖任务要求的全部边界：

| 边界 | 输入 | escape | unescape | 结果 |
|---|---|---|---|---|
| 反斜杠结尾 | `"a\\"` | `"a\\\\"` | `"a\\"` | 正确 |
| 仅反斜杠 | `"\\"` | `"\\\\"` | `"\\"` | 正确 |
| 反斜杠+n（非换行） | `"a\\nb"` | `"a\\\\nb"` | `"a\\nb"` | 正确 |
| 字面换行符 | `"a\nb"` | `"a\\nb"` | `"a\nb"` | 正确 |
| 混合多元素+分隔 | `["a\nb","c\\d","e\\nf"]` | 各元素 escape 后以字面 `\n` 连接 | 按字面 `\n` split 后逐元素 unescape | 正确 |

`convertToEntityProperty` 按字面换行符（0x0A）split，转义后的 `\n` 是 `\`+`n` 两字符不含 0x0A，无歧义。unescape 对"`\` 后跟其他字符"及"结尾单 `\`"均做容错保留，不会越界（`i+1 < s.length` 守卫）。

#### 测试与行为规则

- 3 个新增边界测试（[ProviderConfigRepositoryTest.kt:312-340](../../app/src/test/java/io/prism/data/ProviderConfigRepositoryTest.kt#L312-L340)）存在且覆盖换行/反斜杠/反斜杠+n，与我的推演结果一致。
- [behavioral-rules.md:49](../../behavioral-rules.md#L49)（BR-data-001）与 [behavioral-rules.md:62](../../behavioral-rules.md#L62)（BR-concurrency-001）状态均为 `active`，符合任务声明。

**独立复审结论**：与第 8 节既有记录一致——**通过**，G-01/G-02 修复正确，未引入新问题，可进入 ac-verifier 阶段。
