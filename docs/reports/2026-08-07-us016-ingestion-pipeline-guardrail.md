# 代码安全与质量审计报告：US-016 摄入管线

> 从 `docs/templates/reports/guardrail-template.md` 复制新建，依 CLAUDE.md 第十节。
> 由 guardrail-enforcer 子 Agent 执行，对 US-016「实现摄入管线」代码变更执行代码质量审查 + 安全漏洞扫描。

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US016-GUARDRAIL-001 |
| 审计日期 | 2026-08-07 |
| 审计目标 | US-016 IngestionPipeline（解析→切片→嵌入→入库全链路编排） |
| 风险等级 | P2 跨模块（集成 4 个既有模块，新增 addChunk 接口扩展） |
| 上游产出 | ADR-009、考古报告 TKN-US016-ARCH-001、影响自检、IngestionPipelineTest.kt |
| 审查 Skill | TRAE-code-review + TRAE-security-review |
| 推理辅助 | sequential-thinking（验证协程取消语义与 InputStream 关闭路径） |
| 项目根 | d:\s0611\code\Prism |

---

## 1. 总体结论

**有条件通过（Conditional Pass）**

- **无阻断级安全漏洞**：无 SQL/命令/代码注入、无硬编码密钥、无 RCE、无认证绕过。
- **无阻断级质量缺陷**：协程取消语义正确（`CancellationException` 不会被吞，已用 sequential-thinking 验证）。
- **存在 1 项须修复的中高危问题**（M1：`InputStream` 资源泄漏）+ 2 项建议修复的中低危问题。
- 主 Agent 须修复 M1 后重新提交本 Agent 审查；M2/Q3/Q6 建议同步修复，可在修复 M1 时一并处理。

> 严格回答主 Agent 自问盲区：
>
> - **「CancellationException 是否会被吞」**：**否**。`catch (e: CancellationException) { throw e }` 位于 `catch (e: Exception)` 之前，Kotlin 异常顺序匹配保证取消异常被先捕获并重新抛出。详见 §3.1。
> - **「InputStream.use 是否所有路径关闭」**：**否**。`require(knowledgeBaseId >= 0)` 位于 `input.use {}` 之前，当 `knowledgeBaseId < 0` 时 `require` 抛 `IllegalArgumentException`，`input` 未被 `use {}` 包裹，不会被关闭——构成资源泄漏。其余路径（正常/解析失败/其他异常/协程取消）均通过 `use {}` 的 finally 关闭。详见 §3.2。

---

## 2. 检查范围摘要

| 维度 | 数据 |
| --- | --- |
| 审查文件数 | 5（4 新增 + 1 修改） |
| 审查函数/方法数 | 4（`ingest`、`defaultTitle`、`addChunk`、`IngestionResult.init`） |
| 审查测试用例数 | 20（`IngestionPipelineTest`） |
| 阻断级问题 | 0 |
| 须修复（中高危） | 1（M1） |
| 建议修复（中危） | 1（M2） |
| 建议修复（低危） | 2（Q3、Q5） |
| 测试覆盖建议 | 1（Q6） |
| 行为规则违反 | 1（BR-error-handling-004 轻微，Q3） |
| 行为规则通过 | BR-concurrency-001/002/003、BR-testing-001、BR-security-001、BR-error-handling-005（部分相关） |

### 审查文件清单

| 文件 | 类型 | 行数 |
| --- | --- | --- |
| [IngestionPipeline.kt](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) | 新增 | 191 |
| [IngestionEvent.kt](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt) | 新增 | 61 |
| [IngestionResult.kt](../../app/src/main/java/io/prism/ingestion/IngestionResult.kt) | 新增 | 45 |
| [IngestionPipelineTest.kt](../../app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt) | 新增 | 529 |
| [KnowledgeBaseRepository.kt](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)（addChunk 第 158-180 行） | 修改 | +23 |

---

## 3. 详细发现

### 3.1 [验证通过] 协程取消语义正确，CancellationException 不会被吞

**位置**：[IngestionPipeline.kt:170-179](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**代码**：

```kotlin
} catch (e: DocumentParseException) {
    emit(IngestionEvent.Failed(e))
} catch (e: CancellationException) {
    // Kotlin 协程铁律：CancellationException 必须重新抛出，不可吞（ADR-009 5.6）
    throw e
} catch (e: Exception) {
    // 其他不可恢复异常（如 ObjectBox 写入失败、OOM）→ 终止管线
    emit(IngestionEvent.Failed(e))
}
```

**验证结论（sequential-thinking 确认）**：

1. `kotlin.coroutines.cancellation.CancellationException`（import 于第 15 行）在 JVM 平台是 `java.util.concurrent.CancellationException` 的 typealias，继承链：`CancellationException → IllegalStateException → RuntimeException → Exception`，即 **CancellationException IS-A Exception**。
2. 若 `catch (e: Exception)` 在 `catch (e: CancellationException)` 之前，会吞掉取消异常。但代码中 `catch (e: CancellationException)` 在前并 `throw e`，Kotlin 异常顺序匹配保证取消异常被先捕获并重新抛出。
3. `kotlinx.coroutines.CancellationException`（自 Kotlin 1.5 起）与 `kotlin.coroutines.cancellation.CancellationException` 互为 typealias；`ensureActive()` 抛出的异常及 `JobCancellationException` 均继承自该类型，故 `catch (e: CancellationException)` 能捕获所有协程取消异常。
4. `flow {}` builder 内 `emit` 为挂起点，取消会在 `emit` 处抛出 `CancellationException`，同样被正确捕获重抛。

**结论**：设计正确，符合 Kotlin 协程铁律。**不构成问题**，记录为正向验证。

### 3.2 [须修复 M1·中高危] InputStream 资源泄漏——require 在 use{} 之前

**位置**：[IngestionPipeline.kt:91-103](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**问题代码**：

```kotlin
fun ingest(...): Flow<IngestionEvent> = flow {
    require(knowledgeBaseId >= 0) {                 // 第 91 行：在 use{} 之前
        "knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）"
    }
    val startMs = System.currentTimeMillis()
    emit(IngestionEvent.Started)
    try {
        val text = input.use { stream ->            // 第 100 行：use{} 只包裹 parse
            val parser = parserRegistry.parserFor(fileName)
            parser.parse(stream)
        }
        ...
```

**证据链（sequential-thinking 路径分析）**：

| 路径 | require | use{} 关闭 input | 结论 |
| --- | --- | --- | --- |
| (a) 正常 | 通过 | ✓ parse 后关闭 | 关闭 |
| (b) **require 失败（kbId<0）** | **抛 IllegalArgumentException** | **✗ 未进入 use{}** | **泄漏** |
| (c) parse 抛 DocumentParseException | 通过 | ✓ use finally 关闭 | 关闭 |
| (d) parse 抛其他异常 | 通过 | ✓ use finally 关闭 | 关闭 |
| (e) 协程取消（ensureActive） | 通过 | ✓ parse 阶段已关闭 | 关闭 |
| (f) embed/addChunk 抛异常 | 通过 | ✓ parse 阶段已关闭 | 关闭 |

**违规点**：

- KDoc（第 80 行）声明「**input 由本方法负责关闭**（use {}）」，但路径 (b) 违反此契约。
- 违反 BR-error-handling-005 精神（显式关闭资源的异常处理须保证状态置位/资源释放覆盖所有路径）。
- 测试 [ingest_negative_knowledge_base_id_throws_illegal_argument](../../app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt)（第 402-411 行）用 `ByteArrayInputStream`（`close()` 为 no-op），**未检测到此泄漏**。

**影响**：

- 触发条件：调用方传入负数 `knowledgeBaseId`（编程错误）。
- 生产环境中若 `input` 为 `FileInputStream` 或 Android SAF `ContentResolver` InputStream，`require` 失败导致文件描述符泄漏；多次触发可耗尽 FD 上限。
- 注意：`require` 位于 `flow {}` 冷流内部，仅在 `collect` 时执行——调用方可能在 `collect` 前已打开 InputStream，`require` 失败后调用方收到的 `IllegalArgumentException` 不携带「input 未关闭」的提示。

**修复建议**（两种方案任选其一）：

方案 A（推荐，最小改动——将参数校验移入 use{} 之后，并用 try-finally 兜底关闭）：

```kotlin
fun ingest(...): Flow<IngestionEvent> = flow {
    require(knowledgeBaseId >= 0) {
        "knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）"
    }
    emit(IngestionEvent.Started)
    try {
        val text = input.use { stream ->
            val parser = parserRegistry.parserFor(fileName)
            parser.parse(stream)
        }
        // ... 其余逻辑不变
    } catch (e: DocumentParseException) {
        emit(IngestionEvent.Failed(e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emit(IngestionEvent.Failed(e))
    } finally {
        // 兜底：require 之后的任何异常路径（理论上 use{} 已关闭，此处幂等保护）
        // 注意：require 在 finally 之前，若 require 失败仍不覆盖——见方案 B
    }
}
```

方案 B（彻底修复——require 也纳入资源保护范围，先校验再 use，但 require 失败时手动关闭）：

```kotlin
fun ingest(...): Flow<IngestionEvent> = flow {
    if (knowledgeBaseId < 0) {
        input.close()  // 校验失败前先关闭资源，履行 KDoc 契约
        throw IllegalArgumentException("knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）")
    }
    emit(IngestionEvent.Started)
    try {
        val text = input.use { stream ->
            val parser = parserRegistry.parserFor(fileName)
            parser.parse(stream)
        }
        // ... 其余逻辑不变
    } catch ...
}
```

> 推荐方案 B：显式履行「input 由本方法负责关闭」契约，覆盖 require 失败路径。需同步补充测试：用 `TrackedInputStream` 传入负数 `knowledgeBaseId`，断言 `trackedStream.closed == true`。

### 3.3 [建议修复 M2·中危] Failed(throwable) 直接封装原始异常，存在信息泄露风险

**位置**：[IngestionEvent.kt:60](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt)、[IngestionPipeline.kt:172,178](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**问题**：

```kotlin
data class Failed(val throwable: Throwable) : IngestionEvent()
```

`Failed` 事件直接封装原始 `Throwable`，调用方（US-018 UI）若直接显示 `throwable.message` 或 `throwable.stackTrace`，可能泄露内部细节：

- `DocumentParseException`：message = `"文档解析失败: $fileName"`（含文件名，用户提供的，风险低）；但其 `cause` 可能是 PDFBox/Apache POI 内部异常，含库版本、内部路径、Java 类名。
- ObjectBox 写入异常：可能含 DB 文件路径、SQL/查询细节。
- 其他 `RuntimeException`：堆栈含内部包结构 `io.prism.*`、ObjectBox native 路径。

**违规点**：违反 BR-error-handling-004「不得将内部异常细节（路径/堆栈）暴露给用户」精神。虽然该规则主要约束 catch 块内日志，但 `Failed(throwable)` 将原始异常传递给上层，增加了上层泄露的或然率（纵深防御应从生产者侧收敛）。

**缓解因素**：US-018 尚未实现，调用方如何消费 `Failed` 未知。当前是潜在风险而非已实现泄露。

**修复建议**：`Failed` 事件额外提供安全的 `errorMessage` 字段，`throwable` 保留供日志/诊断（但不建议直接渲染给用户）：

```kotlin
data class Failed(
    val throwable: Throwable,
    val errorMessage: String  // 安全的用户可见消息，不含路径/堆栈
) : IngestionEvent()

// IngestionPipeline 内：
catch (e: DocumentParseException) {
    emit(IngestionEvent.Failed(e, "文档解析失败，请检查文件格式是否受支持"))
}
catch (e: Exception) {
    // BR-error-handling-004：异常归一化处理，记录诊断但不暴露细节
    emit(IngestionEvent.Failed(e, "摄入失败，请重试或检查日志"))
}
```

### 3.4 [建议修复 Q3·低危] catch(Exception) 块缺注释，轻微违反 BR-error-handling-004

**位置**：[IngestionPipeline.kt:176-179](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**问题**：BR-error-handling-004 规定「若项目暂未引入结构化日志基建，应在该分支保留注释说明异常被归一化处理」。

```kotlin
} catch (e: Exception) {
    // 其他不可恢复异常（如 ObjectBox 写入失败、OOM）→ 终止管线
    emit(IngestionEvent.Failed(e))
}
```

当前注释说明了异常**去向**（终止管线），但未明确说明「异常已归一化为 Failed 事件，未输出结构化日志，诊断依赖 Failed.throwable」。`catch (e: EmbeddingException)` 块（第 136-143 行）的注释较完整（提及 BR-error-handling-004），但 `catch (e: Exception)` 块未对齐。

**修复建议**：补充注释明确归一化语义：

```kotlin
} catch (e: Exception) {
    // BR-error-handling-004：异常归一化为 Failed 事件传递给调用方，
    // 项目暂无结构化日志基建，诊断依赖 Failed.throwable（US-018 须做安全映射）
    emit(IngestionEvent.Failed(e))
}
```

### 3.5 [建议修复 Q5·低危] chunk title 重复摄入无去重，检索可能混淆

**位置**：[IngestionPipeline.kt:131](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**问题**：`title = "${documentTitle}#${index + 1}"`。同一文档重复摄入会产生相同 title（如 `doc#1` 重复）。ObjectBox 不强制 title 唯一，会产生重复 title 的 chunk。US-017 检索结果标注来源时可能混淆（无法区分是哪次摄入的 chunk）。

**影响**：低。当前无去重需求明确，但 US-017 检索体验受影响。

**修复建议**：在 ADR-009 或后续 US 中明确重复摄入策略（允许重复/去重/追加时间戳）。短期可保持现状，但需在 ADR-009 风险表补充该条。

### 3.6 [建议 Q6·测试覆盖] 未覆盖协程取消、ObjectBox 写入失败、chunk title 冲突

**位置**：[IngestionPipelineTest.kt](../../app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt)

**测试覆盖缺口**（主 Agent 自问盲区已提及）：

| 场景 | 是否覆盖 | 风险 |
| --- | --- | --- |
| 协程取消（collect 中途取消，验证 ensureActive 生效 + input 关闭） | ✗ 未覆盖 | 取消语义未经运行时验证，回归风险 |
| ObjectBox 写入失败（addChunk 抛异常，验证 emit Failed + 已入库 chunk 保留） | ✗ 未覆盖 | 写入失败路径未验证 |
| chunk title 冲突/重复摄入 | ✗ 未覆盖 | 去重策略未定义 |
| M1 修复后：负数 kbId 时 input 关闭 | ✗ 未覆盖 | 验证 M1 修复 |

**修复建议**：补充以下测试（建议由 ac-verifier 在验收阶段强制要求，或主 Agent 修复 M1 时一并补充）：

```kotlin
@Test fun ingest_cancels_at_chunk_boundary_and_closes_input() = runBlocking {
    // 构造大文档，collect 中途取消，验证抛出 CancellationException + input 已关闭
}

@Test fun ingest_emits_failed_when_add_chunk_throws() = runBlocking {
    // 注入会抛异常的 repository（或用已关闭的 boxStore），验证 emit Failed
}

@Test fun ingest_closes_input_when_knowledge_base_id_negative() = runBlocking {
    // M1 修复验证：负数 kbId 时 input 仍被关闭
    val tracked = TrackedInputStream("内容".toByteArray())
    try { pipeline.ingest("doc.txt", tracked, -1L).toList(); fail() }
    catch (e: IllegalArgumentException) { assertTrue(tracked.closed) }
}
```

---

## 4. 安全扫描专项（TRAE-security-review）

### 4.1 输入与边界审计

| 审计项 | 结论 | 证据 |
| --- | --- | --- |
| 数值边界（knowledgeBaseId） | require 校验 ≥0，充分；但 require 失败导致 M1 资源泄漏 | [IngestionPipeline.kt:91](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) |
| 集合边界（chunks） | `forEachIndexed` 安全遍历，无越界；`index + 1` 不会溢出（Int 范围足够） | [IngestionPipeline.kt:127](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) |
| 状态机约束 | 无状态机（管线无状态，4 组件线程安全） | 类注释第 45 行 |
| 算术溢出 | `embedded`/`skipped` 为 Int 累加，chunk 数受限于内存，无溢出风险 | — |

### 4.2 执行安全审计（注入防护）

| 审计项 | 结论 | 证据 |
| --- | --- | --- |
| SQL/NoSQL 注入 | **无风险**。ObjectBox 使用 `Box.put()`（非查询拼接），`addChunk` 无用户输入进入查询构造 | [KnowledgeBaseRepository.kt:175-180](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) |
| OS 命令注入 | **无风险**。无 `Runtime.exec`/`ProcessBuilder` 调用 | — |
| 代码/表达式注入 | **无风险**。无 `eval`/反射执行用户输入 | — |
| 模板注入 | **不适用**。无模板引擎 | — |
| 路径遍历 | **低风险**。`defaultTitle` 从 fileName 提取，处理了 `/`/`\` 分隔符；title 仅存储为字符串，不用于路径构造 | [IngestionPipeline.kt:184-189](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) |

### 4.3 最小权限检查

| 审计项 | 结论 |
| --- | --- |
| 数据库账户权限 | ObjectBox 嵌入式，无独立账户；应用进程权限，无提权 |
| OS 服务账户 | Android 应用沙箱，无 root 运行 |
| 容器安全上下文 | 不适用（Android 应用，非容器化） |

### 4.4 输出编码与特殊字符处理

| 审计项 | 结论 | 证据 |
| --- | --- | --- |
| `ChunkSkipped.reason` | **可接受**。`reason = "嵌入失败: ${e.stage}"`，stage 是枚举名（MODEL_LOAD/TOKENIZER_INIT/INFERENCE/POOLING/UNLOAD），不含密钥/路径/PII，属诊断信息 | [IngestionPipeline.kt:139](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)、[EmbeddingException.kt:14-20](../../app/src/main/java/io/prism/embedding/EmbeddingException.kt) |
| `Failed(throwable)` | **中风险**（见 M2）。原始异常可能含内部路径/堆栈 | [IngestionEvent.kt:60](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt) |
| chunk title | **低风险**。fileName 特殊字符进入 title 存储，但 UI 用 Compose（非 WebView），XSS 风险低 | [IngestionPipeline.kt:131](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) |

### 4.5 密钥与配置安全

| 审计项 | 结论 |
| --- | --- |
| 硬编码密钥/密码/token | **无**。扫描全部变更文件，无硬编码敏感信息 |
| 环境变量/密钥服务 | 不适用（端侧应用，无服务端密钥） |
| .gitignore | 本次变更不涉及 .gitignore 修改；既有 BR-build-004 已覆盖 ObjectBox JNI 文件 |

### 4.6 依赖与供应链

本次变更**未修改** `build.gradle`/`package.json` 等依赖描述文件，无新增依赖。无供应链风险。

### 4.7 DoS 风险（记录但非阻断）

> 注：TRAE-security-review §8.1 将 DoS 列为排除项。此处作为质量建议记录，不作为安全漏洞阻断。

大文档 chunk 数无上限：`chunker.chunk(text)` 对超大文档可能产生成千上万个 chunk，逐条 embed（~100ms/次）+ put，可能导致长时间阻塞与内存压力。建议在 US-018 或后续迭代中加入文档大小/chunk 数上限预警。**当前不阻断**。

---

## 5. 保护机制验证

### 5.1 协程取消保护（ADR-009 5.6）

| 验证项 | 结论 |
| --- | --- |
| `coroutineContext.ensureActive()` 调用位置 | [IngestionPipeline.kt:129](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)，在 `forEachIndexed` 循环顶部、embed 之前。覆盖 chunk 边界取消检查。✓ |
| `CancellationException` 处理 | `catch (e: CancellationException) { throw e }`，重新抛出不吞。✓（§3.1 验证） |
| `embed` 持锁不可中断 | ADR-009 5.6 接受最坏 100ms 延迟。✓ |
| `addChunk` 后无 `ensureActive` | 取消后最多多写一个 chunk（addChunk 毫秒级），可接受。建议但非必须。 |

### 5.2 InputStream 生命周期（ADR-009 5.7）

| 验证项 | 结论 |
| --- | --- |
| `input.use {}` 包裹 parse | ✓，parse 后即关闭（比 ADR-009 5.7 描述的「包裹整个流程」更早释放，合理） |
| 所有正常/异常路径关闭 | ✗，require 失败路径泄漏（M1） |
| 协程取消时关闭 | ✓，parse 阶段已关闭；取消发生在循环阶段时 input 已关闭 |
| ADR-009 5.7 描述与实现不一致 | ADR 说「use {} 包裹 parse+chunk+embed+store」，实际只包裹 parse。实现更优（及早释放），但 ADR 描述需同步修正 |

### 5.3 事务边界（ADR-009 5.5）

| 验证项 | 结论 |
| --- | --- |
| chunk 级独立 `addChunk` | ✓，[KnowledgeBaseRepository.kt:175-180](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) |
| `addChunk` 校验 `knowledgeBaseId >= 0` | ✓，纵深防御（与管线入口双重校验） |
| 不强制文档级事务 | ✓，符合 ADR-009 5.5（嵌入昂贵不回滚） |
| BR-concurrency-001 适用性 | ADR-009 论证不适用（无业务不变式）。✓ 不违反 |

### 5.4 嵌入失败降级（ADR-009 5.4，AC-3）

| 验证项 | 结论 |
| --- | --- |
| catch `EmbeddingException` → embedding=null → 仍入库 | ✓，[IngestionPipeline.kt:134-152](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) |
| emit `ChunkSkipped` 提示 | ✓，AC-3 满足 |
| HNSW 自动排除 null embedding | 考古报告确认（KnowledgeChunkVectorSearchTest.kt:73-86 验证）✓ |
| 不 retry / 不 fail-fast | ✓，符合 ADR-009 5.4 |

### 5.5 IngestionResult.init 一致性校验

| 验证项 | 结论 |
| --- | --- |
| `embedded + skipped == total` 在 Completed 路径 | ✓ 每个 chunk 二选一，恒成立 |
| 空文档路径 | ✓ 0+0==0 |
| Failed 路径 | ✓ 不构造 IngestionResult |

---

## 6. 行为规则核对

| 规则 | 适用性 | 结论 |
| --- | --- | --- |
| BR-concurrency-001（多步骤 DB 变更须事务） | ADR-009 5.5 论证不适用（无业务不变式） | ✓ 不违反 |
| BR-concurrency-002（生命周期资源并发访问须覆盖 close） | 管线不持有 Embedder 生命周期，不在管线内 close | ✓ 不违反 |
| BR-concurrency-003（HNSW 实体批量删除禁用 Query.remove） | addChunk 是 put 操作，不涉及删除 | ✓ 不违反 |
| BR-error-handling-004（catch 须输出结构日志/注释） | catch(Exception) 块缺归一化注释 | ✗ 轻微违反（Q3） |
| BR-error-handling-005（显式关闭资源须保证状态置位） | use{} 关闭正确，但 require 失败路径泄漏（M1） | ⚠ 相关，M1 须修复 |
| BR-testing-001（测试替身须复现关键语义） | FakeEmbedder 返回 one-hot 向量（L2 范数=1，归一化），复现 embed 成功/失败语义 | ✓ 不违反 |
| BR-security-001（data class 含数组字段须覆盖 equals/hashCode） | IngestionResult/SkippedChunk 无数组字段 | ✓ 不违反 |

> BR-testing-001 深度核对（主 Agent 自问盲区）：FakeEmbedder 返回 one-hot 向量（仅一个位置 1.0f，其余 0），其 L2 范数 = 1.0，**恰好是归一化的**，与 OnnxEmbedder 返回 L2 归一化向量在「归一化」语义上一致。one-hot 向量间 cosine 相似度只有 0/1（无连续相似度），但摄入管线测试的关键语义是「embed 成功返回向量 / 失败抛 EmbeddingException」，非「向量检索质量」。FakeEmbedder 复现了关键语义，**不违反 BR-testing-001**。

---

## 7. 修复建议汇总

| 编号 | 等级 | 问题 | 修复方案 | 是否阻断 |
| --- | --- | --- | --- | --- |
| M1 | 中高危 | InputStream 资源泄漏（require 在 use{} 前） | 方案 B：require 失败前先 `input.close()`；补充测试 | **是（须修复后重新审查）** |
| M2 | 中危 | Failed(throwable) 信息泄露风险 | Failed 增加 `errorMessage` 安全字段 | 否（建议修复） |
| Q3 | 低危 | catch(Exception) 缺归一化注释 | 补充 BR-error-handling-004 注释 | 否（建议修复） |
| Q5 | 低危 | chunk title 重复摄入无去重 | ADR-009 风险表补充策略说明 | 否（建议） |
| Q6 | 建议 | 测试未覆盖取消/写入失败/title 冲突 | 补充 3 个测试用例 | 否（ac-verifier 阶段强制） |

### 修复后须重新走闭环

依 CLAUDE.md 7.2，主 Agent 修复 M1 后，**必须重新提交本 Agent 审查**（从 guardrail-enforcer 阶段重新开始），通过后方可启动 ac-verifier。M2/Q3 建议在修复 M1 时一并处理，避免二次返工。

---

## 8. 豁免

| 豁免项 | 依据 | 备注 |
| --- | --- | --- |
| DoS（大文档 chunk 数无上限） | TRAE-security-review §8.1 排除 DoS | 作为质量建议记录（§4.7），不阻断 |
| ADR-009 5.7 描述与实现不一致（use{} 范围） | 实现更优（及早释放），非缺陷 | 建议 ADR-009 同步修正描述 |
| chunk title 重复（Q5） | 当前无去重需求明确 | 建议后续 US 明确策略 |

---

## 9. 自动化建议（CI/CD 集成）

为防止同类问题回归，建议在 CI 中集成：

1. **资源泄漏检测**：对 Kotlin 代码引入 [Detekt](https://detekt.dev/) 自定义规则，检测 `use {}`/`close()` 与 `require`/`check` 的相对位置，警告「资源校验在资源保护块之前」模式。
2. **协程取消语义 Lint**：集成 [ktlint](https://pinterest.github.io/ktlint/) + 自定义规则，检测 `catch (e: Exception)` 前是否已单独 `catch (e: CancellationException) { throw e }`。
3. **信息泄露扫描**：对 `data class Xxx(val throwable: Throwable)` 模式标记审查项，提示增加安全 errorMessage 字段。
4. **测试覆盖门禁**：在 ac-verifier 阶段强制要求覆盖「协程取消」「写入失败」「资源关闭」三类场景，未覆盖则验收不通过。

---

## 10. 结论

US-016 摄入管线代码**整体设计质量高**：协程取消语义正确（CancellationException 不被吞）、嵌入失败降级符合 AC-3、事务边界合理、事件流模型清晰、测试覆盖较全（20 用例）。无阻断级安全漏洞。

但存在 1 项须修复的中高危资源泄漏（M1：require 在 use{} 前），主 Agent **必须修复后重新提交本 Agent 审查**。同时建议修复 M2（信息泄露）、Q3（注释）、Q6（测试覆盖）。

**结论：有条件通过。修复 M1 后重新审查，通过方可进入 ac-verifier。**
