# 代码安全与质量审计报告（第二轮）：US-016 摄入管线

> 依 CLAUDE.md 第十节 + 7.2 闭环回退规则。主 Agent 修复第一轮 M1（阻断）+ M2/Q3/Q6 后重新提交审查。
> 本轮聚焦：M1 修复有效性、新增 catch(IllegalArgumentException) 对取消语义的影响、回归检查、BR-error-handling-006 状态。

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-US016-GUARDRAIL-002 |
| 审计日期 | 2026-08-07 |
| 审计轮次 | 第二轮（复审） |
| 上一轮报告 | [2026-08-07-us016-ingestion-pipeline-guardrail.md](2026-08-07-us016-ingestion-pipeline-guardrail.md)（TKN-US016-GUARDRAIL-001，结论「有条件通过」） |
| 审查 Skill | TRAE-code-review + TRAE-security-review（框架沿用第一轮，本轮基于修复后源码逐行复核） |
| 推理辅助 | sequential-thinking（验证新增 catch(IllegalArgumentException) 与取消语义隔离 + M1 路径分析 + Q6 取消测试逻辑） |
| 项目根 | d:\s0611\code\Prism |

---

## 1. 总体结论

**通过（Pass）**

- **M1（InputStream 资源泄漏）修复有效**：`require` 失败前先 `input.close()`，履行 KDoc 关闭契约；新增测试验证负数 `kbId` 时 `input` 已关闭。资源泄漏路径闭合。
- **新增 catch(IllegalArgumentException) 设计合理**：位于 `catch(CancellationException)` 之后、`catch(Exception)` 之前，与 `CancellationException` 互不继承（均直接继承 `RuntimeException`），不破坏取消语义。编程错误直接抛给调用方、不走 `Failed` 事件，是合理的职责分离。
- **M2/Q3/Q6 处理到位**：`Failed` KDoc 安全约定明确；catch 注释符合 BR-error-handling-004；协程取消测试覆盖 chunk 边界停止行为。
- **无回归**：现有 22 测试 + 新增 2 测试 = 24 测试，主 Agent 报告全部通过；回归路径逐项复核无破坏。
- **无新引入的阻断/高危缺陷**。

**可进入 ac-verifier 测试阶段。**

> BR-error-handling-006 状态建议：M1 修复有效，`proposed` → 待 ac-verifier 确认后转 `active`（与 BR-concurrency-002/BR-error-handling-005 既有模式一致）。

---

## 2. 第一轮发现项修复复核

### 2.1 [M1·已修复·中高危→闭合] InputStream 资源泄漏

**修复位置**：[IngestionPipeline.kt:91-103](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**修复代码**：

```kotlin
if (knowledgeBaseId < 0) {
    try {
        input.close()
    } catch (_: Exception) {
        // 忽略 close 异常，避免掩盖 require 的 IllegalArgumentException
    }
    require(knowledgeBaseId >= 0) {
        "knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）"
    }
}
```

**路径复核（sequential-thinking 确认）**：

| 路径 | 修复前 | 修复后 |
| --- | --- | --- |
| (a) kbId ≥ 0 正常 | use{} 关闭 ✓ | use{} 关闭 ✓（不变） |
| (b) **kbId < 0** | **require 抛异常，input 未关闭 ✗** | **先 close input（吞 close 异常），再 require 抛异常，input 已关闭 ✓** |
| (c) parse 失败 | use{} 关闭 ✓ | use{} 关闭 ✓（不变） |
| (d) 其他异常 | use{} 关闭 ✓ | use{} 关闭 ✓（不变） |
| (e) 协程取消 | parse 阶段已关闭 ✓ | parse 阶段已关闭 ✓（不变） |

**关键验证点**：

1. `require` 位于 `try` 块（第 108 行）**之前**，故 `require` 抛出的 `IllegalArgumentException` 不被任何 `catch` 捕获，直接抛给调用方——正确的 fail-fast 参数校验。
2. `close` 异常用 `catch (_: Exception)` 吞掉，避免掩盖后续 `require` 的 `IllegalArgumentException`——符合「不掩盖原始异常」原则。
3. `if (knowledgeBaseId < 0)` + `require(knowledgeBaseId >= 0)` 略冗余（require 在 if 内必然失败），但保持了标准异常消息格式，可接受。

**测试验证**：[IngestionPipelineTest.kt:417-427](../../app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt) `ingest_negative_knowledge_base_id_still_closes_input_stream`

- 用 `TrackedInputStream` 传入 `-1L`，断言 `trackedStream.closed == true` + 抛 `IllegalArgumentException`。✓

**结论**：M1 修复有效，资源泄漏闭合。符合 BR-error-handling-006（proposed）。

### 2.2 [M2·已处理·中危→可接受] Failed(throwable) 信息泄露

**修复位置**：[IngestionEvent.kt:59-68](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt)、[IngestionPipeline.kt:182,194](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**修复方式**：KDoc 安全约定（非结构变更）

- `Failed.throwable` KDoc 明确标注「仅供日志/调试，禁止直接展示 message/堆栈给用户」
- 引用 BR-error-handling-003（保留业务语义区分）
- catch 块注释同步标注 M2 安全约定

**评估**：

- 第一轮建议增加 `errorMessage` 安全字段，主 Agent 选择 KDoc 约束替代。
- KDoc 约束 + BR-error-handling-003 引用提供了明确的 UI 层映射指导，**可接受**。
- 缺点：依赖 US-018 自觉遵守，无编译期强制。
- **建议**：ac-verifier 验收阶段应确认 US-018 UI 层对 `Failed.throwable` 做安全映射（不直接渲染 message/堆栈）；若 US-018 未实现，应在 US-018 任务中明确此约束。

**结论**：M2 处理到位，不阻断。后续由 ac-verifier / US-018 跟进。

### 2.3 [Q3·已处理·低危→闭合] catch 注释

**修复位置**：[IngestionPipeline.kt:180-196](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**修复内容**：4 个 catch 块均补充归一化策略注释：

- `catch (DocumentParseException)`：致命错误 + M2 安全约定 ✓
- `catch (CancellationException)`：Kotlin 协程铁律 + 必须在 `catch(Exception)` 之前 ✓
- `catch (IllegalArgumentException)`：编程错误直接抛 + input 已关闭 ✓
- `catch (Exception)`：兜底归一化 + BR-error-handling-004 + M2 ✓

**结论**：Q3 闭合，符合 BR-error-handling-004。

### 2.4 [Q6·已处理·建议→闭合] 协程取消测试

**修复位置**：[IngestionPipelineTest.kt:433-459](../../app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt) `ingest_cancellation_stops_processing_at_chunk_boundary`

**测试逻辑复核（sequential-thinking 确认）**：

1. 构造 5 段文档，每段独立成块。
2. `collect` 内第 1 个 `ChunkEmbedded` 后抛 `CancellationException("测试取消")`。
3. 关键时序：`repository.addChunk(chunk)`（第 162 行）在 `emit(ChunkEmbedded)`（第 166 行）**之前**，故第 1 个 chunk 已入库。
4. `collect` lambda 抛 `CancellationException` 后，`flow {}` 内部下一个 `emit`/`ensureActive` 抛 `CancellationException`，被 `catch (CancellationException) { throw e }` 重新抛出，管线终止。
5. 断言：仅 1 个 `ChunkEmbedded`、无 `Completed`、仅 1 chunk 入库。✓

**评估**：

- 用 `collect` 内抛 `CancellationException` 模拟协程取消，等效于取消语义（`CancellationException` 是协程取消信号）。
- 覆盖了 chunk 边界停止行为，验证 `ensureActive` 与 `catch(CancellationException){throw e}` 协同。
- 未额外断言 `input` 关闭，但取消发生在 parse 阶段之后（`input` 已被 `use{}` 关闭），无需断言。

**结论**：Q6 闭合，协程取消路径已有运行时验证。

---

## 3. 新增变更审查：catch(IllegalArgumentException)

**位置**：[IngestionPipeline.kt:188-191](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

**代码**：

```kotlin
} catch (e: IllegalArgumentException) {
    // 编程错误（如 repository.addChunk 内部 require 失败）：直接抛给调用方，不走 Failed 事件
    // input 已被 use {} 关闭，资源安全
    throw e
}
```

### 3.1 catch 顺序安全性（sequential-thinking 验证）

修复后完整 catch 链：

```
1. catch (DocumentParseException)     → emit Failed
2. catch (CancellationException)      → throw e（重新抛出）
3. catch (IllegalArgumentException)   → throw e（重新抛出）
4. catch (Exception)                  → emit Failed
```

**继承链分析**：

- `CancellationException` → `IllegalStateException` → `RuntimeException` → `Exception`
- `IllegalArgumentException` → `RuntimeException` → `Exception`
- `DocumentParseException` → `RuntimeException` → `Exception`

**关键结论**：

- `CancellationException` 与 `IllegalArgumentException` 互不继承（均直接继承 `RuntimeException` 的不同子类），`catch(CancellationException)` 不会捕获 `IllegalArgumentException`，反之亦然。**取消语义不受影响**。✓
- `catch(IllegalArgumentException)` 位于 `catch(Exception)` 之前，保证 `IllegalArgumentException` 被重新抛出而非落入 `catch(Exception)` emit `Failed`。✓
- `DocumentParseException` 不是 `IllegalArgumentException` 子类，`catch(DocumentParseException)` 在前优先捕获解析异常。✓
- `parserFor` 不支持格式时抛 `DocumentParseException(fileName, IllegalArgumentException(...))`——`DocumentParseException` 本身不是 `IllegalArgumentException`，由 `catch(DocumentParseException)` 捕获，不会误入 `catch(IllegalArgumentException)`。✓

### 3.2 行为变更影响

| 异常来源 | 修复前 | 修复后 | 影响 |
| --- | --- | --- | --- |
| 入口 require（kbId<0） | try 外抛出，不进 catch | try 外抛出，不进 catch（M1 修复后位置不变） | 无变化 |
| parserFor 不支持格式 | catch(DocumentParseException) emit Failed | catch(DocumentParseException) emit Failed | 无变化 |
| addChunk 内 require 失败 | catch(Exception) emit Failed | catch(IllegalArgumentException) throw e | **行为变更**：编程错误直接抛，不走 Failed |
| parse 内部 IllegalArgumentException | catch(Exception) emit Failed | catch(IllegalArgumentException) throw e | **行为变更**：直接抛给调用方 |

**评估**：行为变更合理——编程错误（`IllegalArgumentException`）不应伪装为可恢复的运行时失败（`Failed` 事件），直接抛给调用方更符合 fail-fast 原则。`DocumentParseException` 仍是业务异常走 `Failed`，区分清晰。

**潜在边界**：若 `parse` 内部因损坏文件抛出未包装的 `IllegalArgumentException`（而非 `DocumentParseException`），将被直接抛出而非 `emit Failed`。但考古报告确认 `DocumentParser` 契约统一抛 `DocumentParseException`，此边界概率低且主 Agent 注释已说明设计意图。**可接受**。

**结论**：新增 `catch(IllegalArgumentException)` 设计合理，不破坏取消语义，无阻断问题。

---

## 4. 回归检查

### 4.1 现有测试影响分析

| 测试 | 受影响？ | 说明 |
| --- | --- | --- |
| happy_path / 降级 / 空文档 / 入库 | 否 | 不涉及 IllegalArgumentException 路径 |
| 解析失败（unknown.xyz） | 否 | DocumentParseException 走 catch(DocumentParseException) |
| InputStream 关闭（3 测试） | 否 | use{} 不变 |
| 负数 kbId 抛异常 | 否 | require 在 try 外抛出，不进 catch |
| 负数 kbId 仍关闭 input（新增） | — | M1 验证测试 |
| 取消停止处理（新增） | — | Q6 取消测试 |
| 多文档复用 embedder | 否 | 不涉及异常路径 |

**结论**：无回归。主 Agent 报告 24 测试全部通过。

### 4.2 KDoc 一致性（低风险，不阻断）

- [IngestionPipeline.kt:42-43](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) 类 KDoc 仍说「`input.use {}` 保证关闭」，未提及 M1 修复的 `if` 块 `close()` 路径。
- [IngestionPipeline.kt:80](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) `@param input` 说「由本方法负责关闭（use {}）」，括号说明略不全。
- **建议**：后续同步更新类 KDoc 与 `@param` 说明，补充「参数校验失败前亦 close」。不阻断本轮通过。

---

## 5. 行为规则状态

| 规则 | 第一轮状态 | 本轮状态 | 依据 |
| --- | --- | --- | --- |
| BR-error-handling-006（参数校验须在资源保护块内或之前先释放资源） | proposed | **proposed → 待 ac-verifier 确认转 active** | M1 修复有效（§2.1），测试验证通过 |
| BR-error-handling-004（catch 须输出结构日志/注释） | 轻微违反（Q3） | **通过** | catch 注释已补充（§2.3） |
| BR-error-handling-005（显式关闭资源须保证状态置位） | 相关（M1） | **通过** | M1 修复覆盖 require 失败路径 |
| BR-concurrency-001/002/003 | 不违反 | 不违反 | 无变化 |
| BR-testing-001（测试替身须复现关键语义） | 不违反 | 不违反 | 无变化 |
| BR-security-001 | 不违反 | 不违反 | 无变化 |

---

## 6. 安全扫描复核

本轮无新增安全面（M1 是资源管理修复，M2 是文档约束，Q3/Q6 是注释/测试）。第一轮安全扫描结论不变：

| 审计项 | 结论 |
| --- | --- |
| SQL/NoSQL 注入 | 无风险（ObjectBox Box.put，非查询拼接） |
| OS 命令注入 | 无风险 |
| 代码/表达式注入 | 无风险 |
| 路径遍历 | 低风险（title 仅存储，不用于路径构造） |
| 硬编码密钥 | 无 |
| 信息泄露（Failed.throwable） | 中风险→可接受（M2 KDoc 约束，US-018 跟进） |
| ChunkSkipped.reason | 可接受（stage 枚举，无敏感信息） |
| 资源泄漏（InputStream） | **已修复**（M1 闭合） |

**结论**：无新增安全漏洞，M1 资源泄漏已闭合。

---

## 7. 结论与下一步

### 结论：通过

US-016 摄入管线第一轮发现的 M1（中高危资源泄漏）已有效修复，M2/Q3/Q6 处理到位，新增 `catch(IllegalArgumentException)` 设计合理且不破坏取消语义，无回归，无新增阻断/高危缺陷。

**可进入 ac-verifier 测试阶段。**

### 主 Agent 下一步

1. 启动 `ac-verifier` 子 Agent（CLAUDE.md 第十一节），提供：
   - 本轮全部代码变更及上下文
   - PRD US-016 验收标准（AC-1~AC-5）
   - 本报告路径 + 第一轮报告路径
   - ADR-009
   - IngestionPipelineTest.kt 测试框架
2. ac-verifier 须重点验证：
   - AC-1~AC-5 分层测试
   - 协程取消路径（Q6 测试）运行时确认
   - 性能基线（首版，涉及 embed ~100ms/chunk）
   - **BR-error-handling-006 转 active 确认**（M1 修复 + 测试验证）
   - US-018 未实现前提下，`Failed.throwable` 安全映射的后续约束记录
3. 若 ac-verifier 全部通过且无回归，本轮开发周期闭合。

### 遗留项（不阻断，后续跟进）

| 编号 | 内容 | 跟进方 |
| --- | --- | --- |
| L1 | 类 KDoc / @param input 关闭说明未含 M1 if 块路径 | 主 Agent 后续微调 |
| L2 | Failed.throwable 安全映射由 US-018 实现 | US-018 任务约束 |
| L3 | chunk title 重复摄入策略（Q5） | ADR-009 风险表或后续 US |
| L4 | ObjectBox 写入失败测试（addChunk 抛异常路径） | ac-verifier 阶段补充 |
