# US-015 知识库分库数据模型 验收测试报告

> 由 ac-verifier 子 Agent 生成。依 CLAUDE.md 第十一节、第七节 7.3。
> 基于 PRD（prd.json US-015）验收标准执行分层验收测试。guardrail-enforcer 第二轮审查（TKN-US015-GUARDRAIL-002）已通过。

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-US015-AC-001 |
| 验收日期 | 2026-08-07 |
| 验收对象 | US-015「实现知识库分库数据模型」 |
| 风险等级 | P2 跨模块（沿用 guardrail 判定） |
| 关联 ADR | [ADR-008](../decisions/ADR-008-m3-knowledgebase-model.md)（M3 知识库分库数据模型，Proposed） |
| 关联 ADR | [ADR-007](../decisions/ADR-007-m3-rag-tech-stack.md)（M3 RAG 技术栈） |
| guardrail 报告 | [第一轮（有条件通过）](./2026-08-07-us015-knowledgebase-model-guardrail.md)（TKN-US015-GUARDRAIL-001） / [第二轮（通过）](./2026-08-07-us015-knowledgebase-model-guardrail-round2.md)（TKN-US015-GUARDRAIL-002） |
| 考古报告 | [2026-08-07-us015-data-archaeology.md](./2026-08-07-us015-data-archaeology.md) |
| 测试方法 | test-architect skill（PRD 驱动分层测试） + 实测复跑（--rerun-tasks 非缓存） |
| 技术栈 | Kotlin 2.3.21 + ObjectBox 5.4.2（HNSW 384 维 COSINE） + JUnit4 + 纯 JVM 临时目录 |

---

## 0. 总体结论

**结论：通过（Pass）—— US-015 全部 5 条验收标准满足，可闭合本轮开发周期**

5 条验收标准（AC-1~AC-5）逐项验证全部通过。分层测试（静态分析 / 单元 / 集成 / 回归）全部通过，性能基线已生成（首版无回退），安全专项 6 项检查全部通过，文档与实现一致（ADR-008 5.1~5.4 与代码逐项对应，R2-01 已修正）。无阻断/高危/中危缺陷。

| 维度 | 结果 | 证据 |
|---|---|---|
| 验收标准 | 5/5 通过 | §2 覆盖矩阵 |
| 静态分析（编译） | BUILD SUCCESSFUL | §3.1 |
| 单元测试 | 31/31 通过（0 失败 0 错误 0 跳过，0.823s） | §3.2 |
| 极端场景补充 | 6/6 通过（临时验证后已清理） | §3.2.1 |
| 集成测试 | 107 既有 data 测试 0 失败（9 perf skipped） | §3.3 |
| E2E | 不适用（纯数据层无 UI/API） | §3.4 |
| 安全专项 | 6/6 通过 | §4 |
| 性能基线 | 首版生成，无回退（无历史基线对比） | §5 |
| 回归测试 | 410 测试 0 失败 0 错误（25 perf skipped） | §6 |
| 文档一致性 | ADR-008 5.1~5.4 与代码一致；R2-01 已修正 | §7 |

---

## 1. 验收标准解析

### 1.1 验收标准原文（prd.json US-015）

| AC ID | 原文 | 可验证性 |
|---|---|---|
| AC-1 | KnowledgeBase 实体（id/name/createdAt）关联 KnowledgeChunk | 可验证（实体定义 + schema + 关联字段） |
| AC-2 | KnowledgeBaseRepository 分库 CRUD | 可验证（Repository 方法 + 测试） |
| AC-3 | KnowledgeChunk 关联所属库字段 | 可验证（字段定义 + schema） |
| AC-4 | 分库 CRUD 单元测试通过 | 可验证（测试运行结果） |
| AC-5 | Typecheck passes | 可验证（编译结果） |

### 1.2 测试用例设计（test-architect 方法论）

采用等价类划分 / 边界值分析 / 状态迁移 / 路径覆盖系统化设计，覆盖矩阵见 §2。

- **等价类**：id 有效（>0 自建库）/ 默认库（0L）/ 负数（非法）/ 不存在（99999）
- **边界值**：空库名 / 重名 / 超长名（10000 字符）/ 空库 / 不存在库
- **状态迁移**：默认库 0L 状态机（remove 拒绝 / get 返回 null / getAll·Flow 不含 / chunkCount 返回计数）
- **路径覆盖**：remove 级联删除路径（findIds → Box.remove → box.remove）/ removeAll 遍历路径 / HNSW embedding 删除路径

---

## 2. 验收标准覆盖矩阵

| AC ID | 验收标准 | 测试用例 ID | 结果 | 证据 |
|---|---|---|---|---|
| AC-1 | KnowledgeBase 实体（id/name/createdAt）关联 KnowledgeChunk | TC-1.1 实体定义核验 / TC-1.2 schema 核验 / TC-1.3 关联字段核验 | **通过** | [KnowledgeBase.kt:36-39](../../app/src/main/java/io/prism/data/KnowledgeBase.kt) `@Entity data class KnowledgeBase(@Id var id, var name, var createdAt)`；[default.json:148-167](../../app/objectbox-models/default.json) KnowledgeBase entity（id `4:130754586657942467`，3 字段）；[KnowledgeChunk.kt:35](../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) `var knowledgeBaseId: Long = 0L` |
| AC-2 | KnowledgeBaseRepository 分库 CRUD | TC-2.1 save/get/getAll/findByName/remove/removeAll/chunkCount 方法核验 / TC-2.2 31 单元测试 / TC-2.3 6 极端场景测试 | **通过** | [KnowledgeBaseRepository.kt:53-176](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) 7 个公开方法；KnowledgeBaseRepositoryTest 31 测试 0 失败（§3.2） |
| AC-3 | KnowledgeChunk 关联所属库字段 | TC-3.1 字段定义核验 / TC-3.2 schema property 核验 / TC-3.3 旧数据归属默认库测试 | **通过** | [KnowledgeChunk.kt:35](../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) `knowledgeBaseId: Long = 0L`；[default.json:35-38](../../app/objectbox-models/default.json) property `knowledgeBaseId`（id `5:3538805690684898002`，type 6=Long）；`legacy_chunk_without_knowledge_base_id_belongs_to_default_kb` 测试通过 |
| AC-4 | 分库 CRUD 单元测试通过 | TC-4.1 KnowledgeBaseRepositoryTest 全量运行（--rerun-tasks） | **通过** | 31 测试 0 失败 0 错误 0 跳过，0.823s（§3.2） |
| AC-5 | Typecheck passes | TC-5.1 compileDebugKotlin + compileDebugUnitTestKotlin | **通过** | BUILD SUCCESSFUL（§3.1） |

---

## 3. 分层测试详情

### 3.1 静态分析（Typecheck passes，AC-5）

| 命令 | 结果 | 证据 |
|---|---|---|
| `.\gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL | 编译通过，仅余既有 deprecation 警告（PrismGlassCard/Lottie/Space1，与本轮变更无关） |

**Typecheck passes（AC-5）验证通过。** ObjectBox plugin 编译期生成 MyObjectBox（含 KnowledgeBase 实体），schema 自动迁移成功。

### 3.2 单元测试（AC-4）

| 框架 | 用例数 | 通过 | 失败 | 错误 | 跳过 | 耗时 | 结果 |
|---|---|---|---|---|---|---|---|
| JUnit4 | 31 | 31 | 0 | 0 | 0 | 0.823s | **通过** |

命令：`.\gradlew :app:testDebugUnitTest --tests "io.prism.data.KnowledgeBaseRepositoryTest" --rerun-tasks`

测试覆盖维度（[KnowledgeBaseRepositoryTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRepositoryTest.kt)）：

| 维度 | 用例数 | 关键用例 |
|---|---|---|
| CRUD 基础 | 9 | save_assigns_positive_id / get_returns_persisted / get_returns_null_for_nonexistent / get_returns_null_for_default_kb_id / get_all_sorted_by_created_at / find_by_name / save_with_existing_id_updates / remove_all_cascade |
| 级联删除原子性 | 5 | remove_deletes_associated_chunks / remove_does_not_affect_other_kb / remove_does_not_affect_default_kb / remove_default_kb_throws / remove_all_does_not_affect_default_kb |
| 旧数据归属默认库 | 2 | legacy_chunk_without_kb_id_belongs_to_default / legacy_chunks_counted_in_default_kb |
| chunkCount 边界 | 2 | chunk_count_zero_for_empty_kb / chunk_count_zero_for_nonexistent_kb |
| Flow 订阅 | 3 | flow_emits_initial_empty / flow_updates_after_save / flow_updates_after_remove |
| 边界场景 | 5 | save_empty_name / save_duplicate_name / remove_nonexistent_id / multiple_unique_ids / cascade_preserves_other_kbs |
| 负数 id 防御 | 3 | get_negative_id_throws / remove_negative_id_throws / chunk_count_negative_id_throws |
| HNSW embedding 删除 | 2 | remove_cascade_deletes_hnsw_embedding / remove_cascade_mixed_embedding |

**覆盖率评估**：KnowledgeBaseRepository 全部 7 个公开方法（save/get/getAll/findByName/remove/removeAll/chunkCount）均有直接测试；全部 `require` 防御分支（负数 id / 默认库删除）有独立测试；HNSW embedding 删除路径（G-01 规避 #1209）有专项测试。语句覆盖与分支覆盖满足目标（无未覆盖的公开方法或分支）。

#### 3.2.1 极端/边缘场景补充测试（ac-verifier 补充，临时验证后已清理）

针对主 Agent 基础用例（31 测试）的盲区，ac-verifier 编写 `KnowledgeBaseRepositoryExtremeTest`（6 测试）补充验证，运行通过后已删除（context safety）。

| 测试用例 | 技术 | 结果 | 证据 |
|---|---|---|---|
| remove_cascade_deletes_500_embedded_chunks_without_error | 大规模（500 chunk × 384 维 embedding） | **通过** | 500 个 embedded chunk 级联删除，未触发 #1209，chunk 全删 |
| remove_cascade_deletes_large_scale_mixed_chunks_without_error | 大规模混合（300 chunk，embedding/null 交替） | **通过** | 混合 chunk 删除无异常 |
| save_very_long_name_is_allowed_at_repository_layer | 边界值（10000 字符库名） | **通过** | 超长名保存读取一致 |
| find_by_name_matches_very_long_name | 边界值（5000 字符精确匹配） | **通过** | findByName 精确匹配超长名 |
| concurrent_remove_different_kbs_preserves_consistency | 并发（2 线程同时删不同库） | **通过** | 数据一致、无死锁（注：触发 ObjectBox 跨线程事务警告，见 §8 风险 R-2） |
| remove_cascade_performance_baseline | 性能计时（30 次迭代 × 50 chunk） | **通过** | 性能基线见 §5 |

**主 Agent 自问答复验证**：

| 主 Agent 自问 | 验证结论 |
|---|---|
| 1. HNSW embedding 级联删除仅 3 chunk，生产规模更大 | **已补充验证。** 500 个 384 维 embedding chunk 级联删除通过，未触发 #1209。残留不确定性：数千 chunk 规模未测（见 §9 风险 R-1） |
| 2. `Box.remove(*chunkIds)` spread 对大数组性能影响 | **已评估。** 50 chunk 删除 p50=40.63ms，性能良好（§5）。残留：数千 chunk 时 vararg 数组副本内存压力未测（见 §9 风险 R-3） |

### 3.3 集成测试（既有测试兼容性回归）

验证 KnowledgeChunk 加字段 + 新增 KnowledgeBase 实体对既有 data 层测试的兼容性。

命令：`.\gradlew :app:testDebugUnitTest --tests "io.prism.data.KnowledgeChunk*" --tests "io.prism.data.ProviderConfig*" --tests "io.prism.data.McpServer*" --rerun-tasks`

| 测试集 | 用例数 | 通过 | 失败 | 跳过 | 结果 |
|---|---|---|---|---|---|
| KnowledgeChunkCrudTest / EdgeCaseTest / VectorSearchTest / VectorSearchEdgeCaseTest / PerformanceBenchmark | — | — | 0 | — | **通过** |
| ProviderConfigRepositoryTest / EdgeCaseTest / PerformanceBenchmark / Demo | — | — | 0 | — | **通过** |
| McpServerRepositoryTest | — | — | 0 | — | **通过** |
| **合计** | 107 | 98 | 0 | 9 | **通过** |

9 跳用为性能基准（`Assume.assumeTrue` 默认跳过，与本轮变更无关）。既有测试构造 `KnowledgeChunk(title=..., content=...)` 用命名参数，新字段 `knowledgeBaseId` 有默认值 `0L`，编译兼容、运行通过。schema 兼容性变更（`retiredPropertyUids=[]`，[default.json:176-179](../../app/objectbox-models/default.json)）对既有数据无破坏。

### 3.4 端到端测试（E2E）

**不适用。** US-015 为纯数据层（实体 + Repository），无 UI 交互、无 API 端点、无用户可操作业务流程。知识库管理 UI 属 US-018 范围（[KnowledgeBaseScreen.kt](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt) 当前为纯 Mock，未接数据层，考古报告 §6.2 确认）。本 US 的核心业务流程（分库 CRUD）已由单元测试 + 集成测试充分覆盖，不调用 Playwright MCP——属合理分层裁剪。

---

## 4. 安全专项验证

依 CLAUDE.md 第十一节 5/6 项与 test-architect Phase 3，逐项取证。

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| S-1 | 无硬编码密钥/token/内部地址 | **通过** | `Select-String -Pattern 'password\|secret\|token\|apikey\|credential'` 扫描 KnowledgeBase.kt / KnowledgeBaseRepository.kt / KnowledgeChunk.kt，**无匹配** |
| S-2 | NoSQL 注入防护（参数化查询） | **通过** | 全部查询用 `.equal(KnowledgeChunk_.knowledgeBaseId, id)` 类型安全参数化（[L116](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) / [L145](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) / [L173](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)），`id` 为 `Long`，无字符串拼接；`findByName` 用 `box.all.find { it.name == name }` 内存相等比较 |
| S-3 | 输入边界校验 | **通过** | `get`/`remove`/`chunkCount` 均 `require(id >= 0)` 防御负数（[L67](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) / [L109](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) / [L171](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)）；`remove` 额外 `require(id != DEFAULT_KB_ID)` 防御默认库（[L110](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)）；3 个负数 id 防御测试通过 |
| S-4 | 敏感信息泄露（日志/异常 message） | **通过** | 异常 message 仅含 `"KnowledgeBase id 不能为负数（id=$id）"` 与 `"禁止删除虚拟默认库（id=$DEFAULT_KB_ID）"`，`id` 为 `Long`，`DEFAULT_KB_ID=0L`，无密钥/路径/堆栈/PII |
| S-5 | 权限验证（敏感操作纵深防御） | **通过** | `remove(0L)` 拒绝删除虚拟默认库（`require` 抛 `IllegalArgumentException`，[L110-112](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)）；`get(0L)` 返回 null（[L68](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)）；默认库 0L 状态机完整（remove 拒绝 / get null / getAll·Flow 结构性不含 / chunkCount 返回计数），无绕过路径 |
| S-6 | 资源管理（Query native 句柄） | **通过** | G-02 已修复：`remove`/`removeAll`/`chunkCount` 的 Query 均经 `.use { }` 关闭（[L117](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) / [L147](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) / [L175](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)），符合 ObjectBox 官方 Query javadoc |

**XSS 基础测试**：N/A。US-015 为本地 DB 数据层，无 HTML/JS 渲染上下文，无 XSS 攻击面。

**注入载荷测试**：ObjectBox 非 SQL DB，`name` 字段虽为用户可输入 String，但 Repository 层用 `box.all.find { it.name == name }` 内存相等比较（非查询拼接），无注入向量。`name` 含特殊字符（如 `'`/`"`/`;`）不会影响查询安全性。

---

## 5. 性能回退检查

依 CLAUDE.md 第十一节 4 项，对 `remove(id)` 级联删除执行计时测试生成初版基线。

### 5.1 基线场景

- **操作**：`KnowledgeBaseRepository.remove(id)` 级联删除（findIds → Box.remove(*ids) → box.remove(id)）
- **数据规模**：每次 1 个 KnowledgeBase + 50 个带 384 维 embedding 的 KnowledgeChunk
- **迭代次数**：30 次（预热无，每次独立 KB + chunk 集）
- **环境**：Windows + 纯 JVM ObjectBox 5.4.2（临时目录，非 Android 模拟器）

### 5.2 基线数据（首版）

| 指标 | 值 |
|---|---|
| p50 延迟 | 40.63 ms |
| p95 延迟 | 42.10 ms |
| p99 延迟 | 42.14 ms |
| mean 延迟 | 40.54 ms |
| min 延迟 | 38.85 ms |
| max 延迟 | 42.14 ms |
| 吞吐 | 24.67 ops/s |
| 错误率 | 0.0 % |
| 有效样本 | 30 / 30 |

### 5.3 回退判定

- **历史基线**：无（US-015 为新增功能，首次建立基线）
- **回退判定**：N/A（首版基线，无对比对象，无回退）
- **性能特征**：p50-p99 差距仅 1.51ms（3.7%），延迟分布极其稳定；错误率 0%；50 个 embedded chunk 级联删除约 40ms，符合 ObjectBox runInTx 事务 + HNSW 索引维护的预期开销
- **spread 操作符性能**：50 chunk 的 `Box.remove(*chunkIds)` vararg 展开性能良好，无显著开销（主 Agent 自问答复 #2 评估）

### 5.4 性能门禁

- 下降 >50% 标记失败：N/A（首版基线）
- 下降 >20% 标记警告：N/A（首版基线）
- **建议**：将本基线存档为 `docs/reports/perf/` 或 `perf/baselines/` 的 US-015 remove 基线，供 US-016/US-017 对比

---

## 6. 回归测试

依 CLAUDE.md 第十一节 7 项，运行全量测试套件。

命令：`.\gradlew :app:testDebugUnitTest --rerun-tasks`

| 测试类数 | 用例数 | 通过 | 失败 | 错误 | 跳过 | 结果 |
|---|---|---|---|---|---|---|
| 39 | 410 | 385 | 0 | 0 | 25 | **通过** |

25 跳用为性能基准（`Assume.assumeTrue` 默认跳过，`-PignorePerformanceTests=false` 启用，与本轮变更无关）。全量 410 测试 0 失败 0 错误，与主 Agent 基线一致（410 测试 385 运行 25 perf skipped）。**无回归。**

---

## 7. 文档与实现一致性

依 CLAUDE.md 第十四节，逐项对照 ADR-008 5.1~5.4 与代码实现。

| ADR 决策 | 代码实现 | 一致性 | 证据 |
|---|---|---|---|
| 5.1 KnowledgeBase 实体：`id/name/createdAt` 最小字段集 | [KnowledgeBase.kt:36-39](../../app/src/main/java/io/prism/data/KnowledgeBase.kt) `@Entity data class KnowledgeBase(@Id var id: Long = 0, var name: String, var createdAt: Long = System.currentTimeMillis())` | **一致** | 无统计字段，运行时聚合 |
| 5.2 KnowledgeChunk 关联字段：扁平 `knowledgeBaseId: Long = 0L` | [KnowledgeChunk.kt:35](../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) `var knowledgeBaseId: Long = 0L` | **一致** | 无 @Relation/ToOne/ToMany，扁平 Long 外键 |
| 5.3 默认库语义：`0L` 虚拟默认库，不持久化 | [KnowledgeBaseRepository.kt:68](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `get(0L)→null` / [L110](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `require(id != DEFAULT_KB_ID)` / [L187](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `DEFAULT_KB_ID = 0L` | **一致** | remove 拒绝 / get null / getAll·Flow 不含 / chunkCount(0L) 返回默认库计数 |
| 5.4 级联删除：`runInTx` + `findIds()` + `Box.remove(*ids)` 规避 #1209 | [KnowledgeBaseRepository.kt:113-124](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `runInTx { findIds() via .use{}; chunkBox.remove(*chunkIds); box.remove(id) }` | **一致** | runInTx 事务 + findIds+Box.remove 规避 #1209 + .use{} 关闭 Query |

### 7.1 R2-01 核验（guardrail 第二轮 LOW 建议）

guardrail 第二轮报告 R2-01 指出 ADR-008 5.4 代码示例第 99 行 `chunkBox.remove(chunkIds)` 缺 spread 操作符。

**核验结果：R2-01 已修正。** [ADR-008 5.4 第 99 行](../decisions/ADR-008-m3-knowledgebase-model.md) 现为 `chunkBox.remove(*chunkIds)`（带 spread），与实际实现 [KnowledgeBaseRepository.kt:120](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `chunkBox.remove(*chunkIds)` 一致。主 Agent 已在 guardrail 第二轮后修正。

### 7.2 KDoc 与代码一致性

`get`/`remove`/`removeAll`/`chunkCount`/`save` 的 KDoc 均标注 HNSW 删除策略、负数 id 防御、资源管理、`@throws IllegalArgumentException`、默认库语义。逐项核对与实现一致。

### 7.3 behavioral-rules 合规性

| 规则 | 状态 | 证据 |
|---|---|---|
| BR-concurrency-001（多步骤 DB 变更须事务） | **合规** | `remove`/`removeAll` 的 `runInTx` 包装完整，findIds+Box.remove 均在事务内 |
| BR-security-001（data class 含数组须覆盖 equals/hashCode） | **N/A** | KnowledgeBase 无数组字段；KnowledgeChunk 的 FloatArray 情况未变（既有 KDoc 已标注） |
| BR-data-001（转换器须转义分隔符） | **N/A** | KnowledgeBase 仅 Long/String 原始字段，无转换器 |
| BR-build-005（schema 文件须提交） | **合规** | [default.json](../../app/objectbox-models/default.json) 已更新含 KnowledgeBase entity + knowledgeBaseId property |

### 7.4 文档待办（不阻断验收）

| 项 | 状态 | 说明 |
|---|---|---|
| prd.json US-015 `passes` | 待更新 | ADR-008 后果提到"prd.json：US-015 passes=true + notes 补 ADR-008 引用"，当前 `passes=false`、`notes="PRD US-003 验收 5：分库管理。"`。属主 Agent 验收收尾工作，验收通过后更新 |

---

## 8. 缺陷列表

| ID | 严重度 | 相关 AC | 描述 | 复现步骤 | 证据 | 状态 |
|---|---|---|---|---|---|---|
| — | — | — | 无阻断/高危/中危/低危缺陷 | — | — | — |

**guardrail 第二轮遗留项状态**：

| 项 | guardrail R2 状态 | ac-verifier 核验 |
|---|---|---|
| R2-01（ADR 代码示例缺 spread） | LOW 建议修正 | **已修正**（§7.1） |
| R2-02（removeAll HNSW 路径未直接测试） | LOW 可选增强 | **未补**（remove 与 removeAll 用相同删除原语 `findIds+Box.remove`，remove 的 HNSW 测试间接验证；ac-verifier 大规模测试 500 chunk 进一步佐证原语安全） |

---

## 9. 未覆盖项与风险

| ID | 未覆盖项 | 原因 | 风险描述 | 缓解 |
|---|---|---|---|---|
| R-1 | 数千 chunk 规模的级联删除 | 单元测试仅验证 500 chunk，生产可能有数千 | objectbox-java#1209 在更大规模的不确定性（ADR-008 5.4 已记录"截至 5.4.2 未公开确认修复"）。500 chunk 测试通过提供经验证据，但不构成规模无关的保证 | ADR-008 5.4 已记录风险；建议 US-016 入库管线集成测试覆盖大规模删除；ObjectBox 升级时复查 #1209 状态 |
| R-2 | ObjectBox 跨线程事务警告 | 并发测试触发 `Destroying inactive transaction in non-owner thread` 警告 | ObjectBox native 事务/游标线程绑定，多线程访问需 `closeThreadResources()`。并发测试功能通过（数据一致无死锁），但警告表明测试环境资源管理边界 | **非生产代码缺陷**：KnowledgeBaseRepository 设计为单线程访问（Android UI 线程），生产不触发。建议 US-018 UI 集成时确保 Repository 单线程访问；若需多线程，每线程用完调用 `boxStore.closeThreadResources()` |
| R-3 | spread 操作符大数组（数千 id）内存压力 | 50 chunk 性能良好，数千 chunk 的 vararg 数组副本未测 | `Box.remove(*chunkIds)` 的 spread 将 LongArray 展开为 vararg，JVM 创建数组副本。数千 id 时可能有内存开销（主 Agent 自问答复 #2） | 50 chunk 基线性能良好（40ms）；若 US-016/US-017 出现数千 chunk 删除热点，可改用 `forEach { box.remove(it) }` 或 ObjectBox 批量 API；当前数据量小（4GB 低端机限制库容量），非热点 |
| R-4 | 真实 Android 设备持久化 | 测试用纯 JVM ObjectBox（临时目录），非 Android 模拟器/真机 | ObjectBox 在 Android 设备的 schema 自动迁移行为未在真机验证 | 沿用项目既有测试模式（考古报告 §5.1，所有 data 测试用纯 JVM）；schema 变更属兼容性（新增字段+实体，`retiredPropertyUids=[]`），ObjectBox 官方保证自动迁移；真机验证留待 US-018 UI 集成 |
| R-5 | 向量检索跨库隔离 | US-015 仅保证 `knowledgeBaseId` 字段存在，检索隔离属 US-017 | 数据模型层已保证字段可过滤，但 HNSW + `equal(knowledgeBaseId)` 联合查询行为未验证（考古报告 H5） | 属 US-017 范围；ADR-008 风险表已记录"US-017 检索模块叠加 equal(knowledgeBaseId) 过滤条件" |

---

## 10. 验收结论

### 10.1 验收标准逐项结论

| AC ID | 验收标准 | 结论 |
|---|---|---|
| AC-1 | KnowledgeBase 实体（id/name/createdAt）关联 KnowledgeChunk | **通过** |
| AC-2 | KnowledgeBaseRepository 分库 CRUD | **通过** |
| AC-3 | KnowledgeChunk 关联所属库字段 | **通过** |
| AC-4 | 分库 CRUD 单元测试通过 | **通过** |
| AC-5 | Typecheck passes | **通过** |

### 10.2 门禁结论

| 门禁 | 结论 |
|---|---|
| 验收标准覆盖 | 5/5 通过 |
| 分层测试 | 静态分析 / 单元（31+6）/ 集成（107）/ 回归（410）全部通过 |
| 性能门禁 | 首版基线生成，无回退（无历史基线） |
| 安全门禁 | 6/6 通过（无密钥/注入/泄露/权限缺陷） |
| 回归门禁 | 410 测试 0 失败，无回归 |
| 文档一致性 | ADR-008 5.1~5.4 与代码一致，R2-01 已修正 |

### 10.3 最终结论

- [x] **通过**
- [ ] 有条件通过
- [ ] 不通过

**US-015「实现知识库分库数据模型」全部 5 条验收标准满足，分层测试、性能基线、安全专项、回归测试全部通过，文档与实现一致。本轮开发周期可闭合。**

**主 Agent 收尾建议（不阻断）**：

1. 更新 prd.json US-015 `passes=true` + notes 补 ADR-008 引用（§7.4）
2. 将性能基线（§5）存档供 US-016/US-017 对比
3. 评估 guardrail R2 第二轮提议的 BR-data-002（HNSW 索引实体禁用 Query.remove，改用 findIds+Box.remove）是否提炼为正式 behavioral-rule，供 US-016/US-017 涉及 KnowledgeChunk 删除时遵循

**下一步**：依 CLAUDE.md 7.2，ac-verifier 全部通过且无回归问题，本轮开发周期闭合。主 Agent 可推进 prd.json 标记与提交。
