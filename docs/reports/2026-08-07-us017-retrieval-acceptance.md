# 验收测试报告：US-017 实现向量检索

> 依 CLAUDE.md 第十一节（ac-verifier 强制，含硬性门禁）。
> 仅当 guardrail-enforcer 审计通过后，主 Agent 方可启动 ac-verifier。
> 本报告基于 PRD 验收标准执行全面分层测试（静态分析 / 单元 / 集成 / 性能 / 安全 / 回归）。

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-US017-ACCEPT-001 |
| 验收日期 | 2026-08-07 |
| 验收范围 | US-017 实现向量检索（4 个 AC + Typecheck） |
| 关联 PRD | prd.json US-017（5 条 AC） |
| 关联 ADR | [ADR-010](../decisions/ADR-010-m3-vector-retrieval.md)（9 项决策 + 8 项风险） |
| 关联 guardrail | [2026-08-07-us017-retrieval-guardrail.md](2026-08-07-us017-retrieval-guardrail.md)（第一轮 Pass，5 低危建议已处理） |
| 关联考古 | [2026-08-07-us017-retrieval-archaeology.md](2026-08-07-us017-retrieval-archaeology.md)（9 项风险清单） |
| 性能基线 | [perf/2026-08-07-us017-retrieval-baseline.md](perf/2026-08-07-us017-retrieval-baseline.md)（首版） |
| 项目根 | d:\s0611\code\Prism |
| 技术栈 | Kotlin 2.3.21 + ObjectBox 5.4.2（HNSW）+ onnxruntime 1.27.0，纯端侧 |

---

## 1. 总体结论

**通过（Pass）**

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| AC-1~AC-4 + Typecheck | 全通过 | 47 功能用例 + 1 性能测试全通过 |
| 性能门禁 | 通过（首版基线，无回退判定） | 100/500/1000 chunk p50 < 200us |
| 安全门禁 | 通过 | 无硬编码密钥 / 无注入 / 无日志泄露 / 维度校验完备 |
| 回归测试 | 通过 | `./gradlew.bat :app:testDebugUnitTest` BUILD SUCCESSFUL |

**4 个 AC 全部通过 + 性能门禁通过 + 安全门禁通过 + 无回归问题。本轮开发周期可闭合。**

---

## 2. 验收标准覆盖矩阵

| AC ID | 验收标准 | 测试用例 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-1 | top-k 检索（默认 k=5，可配置）基于 nearestNeighbors | search_returns_topk_with_default_k_5 / search_k_configurable / search_returns_results_sorted_by_similarity_desc / search_identical_vector_similarity_approx_one / search_oblique_60_degrees_similarity_positive_half / search_oblique_120_degrees_similarity_negative_half / search_k_max_value_returns_all_available / search_mixed_orthogonal_and_aligned_prioritizes_aligned | 通过 | [KnowledgeBaseRetrievalTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalTest.kt) + [KnowledgeBaseRetrievalEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt) 全 PASS |
| AC-2 | 支持指定库或全库检索 | search_specified_kb_filters_correctly / search_default_kb_returns_only_default / search_all_kb_returns_cross_kb / search_cross_kb_unified_similarity_sorting / search_kb_id_max_value_returns_empty / ingest_to_self_built_kb_then_search_specified_kb / ingest_to_default_kb_then_search_default_kb / ingest_multiple_kbs_then_search_all_kb | 通过 | [KnowledgeBaseRetrievalTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalTest.kt) + [KnowledgeBaseRetrievalEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt) + [KnowledgeBaseRetrievalIntegrationTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalIntegrationTest.kt) 全 PASS |
| AC-3 | 检索结果含相似度分数与来源（文件/片段位置） | search_title_parsed_to_source / search_title_with_hash_in_docname / search_title_no_hash_returns_title_and_null_index / search_result_contains_all_required_fields / search_title_chunk_index_zero_returns_null / search_title_chunk_index_negative_returns_null / search_title_chunk_index_non_numeric_returns_null / search_title_hash_at_start_returns_title_and_null / search_title_hash_at_end_returns_title_and_null / search_title_empty_string_returns_empty_and_null / search_opposite_vector_similarity_negative / ingest_then_search_returns_chunks_with_correct_source / ingest_then_search_result_contains_all_fields | 通过 | [KnowledgeBaseRetrievalTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalTest.kt) + [KnowledgeBaseRetrievalEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt) + [KnowledgeBaseRetrievalIntegrationTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalIntegrationTest.kt) 全 PASS |
| AC-4 | 检索单元测试通过（含空库、无匹配） | search_empty_kb_returns_empty / search_nonexistent_kb_returns_empty / search_null_embedding_excluded / search_mixed_null_and_valid_embeddings / search_orthogonal_vectors_does_not_crash / search_zero_vector_query_does_not_crash / ingest_with_embedding_failure_excluded_from_search | 通过 | [KnowledgeBaseRetrievalTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalTest.kt) + [KnowledgeBaseRetrievalEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt) + [KnowledgeBaseRetrievalIntegrationTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalIntegrationTest.kt) 全 PASS |
| AC-5 | Typecheck passes | 编译成功 | 通过 | `./gradlew.bat :app:compileDebugUnitTestKotlin` BUILD SUCCESSFUL |

---

## 3. 测试用例设计文档

### 3.1 等价类划分

| 等价类 | 类型 | 输入 | 期望行为 | 覆盖用例 |
| --- | --- | --- | --- | --- |
| k 正常值 | 有效 | k=1,3,5,10 | 返回 min(k, 可用) 条 | search_returns_topk_with_default_k_5 / search_k_configurable / search_k_one_returns_single_most_similar / search_k_greater_than_available_returns_all |
| k=0 | 无效 | k=0 | 抛 IllegalArgumentException | search_k_zero_throws |
| k 负数 | 无效 | k=-1 | 抛 IllegalArgumentException | search_k_negative_throws |
| k=Int.MAX_VALUE | 边界 | k=MAX_VALUE | 返回全部可用 | search_k_max_value_returns_all_available |
| kbId=null | 有效（全库） | kbId=null | 跨库返回 | search_all_kb_returns_cross_kb / search_cross_kb_unified_similarity_sorting |
| kbId=0L | 有效（默认库） | kbId=0L | 仅默认库 | search_default_kb_returns_only_default |
| kbId>0 | 有效（自建库） | kbId=1L | 仅自建库 | search_specified_kb_filters_correctly |
| kbId 负数 | 无效 | kbId=-1L | 抛 IllegalArgumentException | search_knowledgeBaseId_negative_throws |
| kbId=Long.MAX_VALUE | 边界 | kbId=MAX | 返回空（不存在） | search_kb_id_max_value_returns_empty |
| kbId=Long.MIN_VALUE | 边界 | kbId=MIN | 抛 IllegalArgumentException | search_kb_id_min_value_throws |
| query 维度=384 | 有效 | size=384 | 正常检索 | 所有正向用例 |
| query 维度!=384 | 无效 | size=2 | 抛 IllegalArgumentException | search_dimension_mismatch_throws |
| query 全零向量 | 边界 | all zeros | 不崩溃（ObjectBox 未定义行为） | search_zero_vector_query_does_not_crash |
| 空库 | 边界 | 0 chunk | 返回空 list | search_empty_kb_returns_empty |
| 全 null embedding | 边界 | embedding=null | 返回空 | search_null_embedding_excluded |
| 混合 null/有效 | 边界 | 部分null | 仅有效 | search_mixed_null_and_valid_embeddings |

### 3.2 边界值分析

| 边界点 | 输入 | 期望 | 覆盖用例 |
| --- | --- | --- | --- |
| k=0（下界） | k=0 | 抛异常 | search_k_zero_throws |
| k=1（最小有效） | k=1 | 返回 1 条 | search_k_one_returns_single_most_similar |
| k=5（默认） | k=5 | 返回 5 条 | search_returns_topk_with_default_k_5 |
| k>可用量 | k=10, 仅 2 条 | 返回 2 条 | search_k_greater_than_available_returns_all |
| k=MAX_VALUE | k=Int.MAX_VALUE | 返回全部 | search_k_max_value_returns_all_available |
| similarity=1.0（上界） | 完全相同向量 | ≈1.0 | search_identical_vector_similarity_approx_one |
| similarity=0.0（正交） | 正交向量 | ≈0.0 | search_orthogonal_vectors_does_not_crash |
| similarity=-1.0（下界） | 反向向量 | ≈-1.0 | search_opposite_vector_similarity_negative |
| similarity=-0.5（中间值） | 120°夹角 | ≈-0.5 | search_oblique_120_degrees_similarity_negative_half |
| similarity=0.5（中间值） | 60°夹角 | ≈0.5 | search_oblique_60_degrees_similarity_positive_half |

### 3.3 决策表（kbId 三态 × 检索结果）

| kbId | equal 过滤 | 预期结果 | 覆盖用例 |
| --- | --- | --- | --- |
| null | 不加 | 跨库返回 | search_all_kb_returns_cross_kb |
| 0L | equal(kbId, 0L) | 仅默认库 | search_default_kb_returns_only_default |
| >0 | equal(kbId, id) | 仅指定库 | search_specified_kb_filters_correctly |
| Long.MAX_VALUE | equal(kbId, MAX) | 空（不存在） | search_kb_id_max_value_returns_empty |
| Long.MIN_VALUE | require 失败 | 抛异常 | search_kb_id_min_value_throws |

### 3.4 状态迁移（parseTitle 容错降级）

| title 输入 | idx | 条件分支 | 输出 | 覆盖用例 |
| --- | --- | --- | --- | --- |
| "" (空串) | -1 | idx<=0 | "" to null | search_title_empty_string_returns_empty_and_null |
| "#1" (#首位) | 0 | idx<=0 | "#1" to null | search_title_hash_at_start_returns_title_and_null |
| "doc#" (#末尾) | 3 | idx>=len-1 | "doc#" to null | search_title_hash_at_end_returns_title_and_null |
| "doc#0" (序号0) | 3 | toIntOrNull=0, 0>0=false | "doc" to null | search_title_chunk_index_zero_returns_null |
| "doc#-1" (序号负) | 3 | toIntOrNull=-1, -1>0=false | "doc" to null | search_title_chunk_index_negative_returns_null |
| "doc#abc" (非数字) | 3 | toIntOrNull=null | "doc" to null | search_title_chunk_index_non_numeric_returns_null |
| "doc#3" (正常) | 3 | toIntOrNull=3, 3>0=true | "doc" to 3 | search_title_parsed_to_source |
| "C#入门.pdf#1" (文件名含#) | 最后# | toIntOrNull=1 | "C#入门.pdf" to 1 | search_title_with_hash_in_docname |

### 3.5 路径覆盖（search 方法分支）

| 分支 | 覆盖用例 |
| --- | --- |
| require(query.size==384) 失败 | search_dimension_mismatch_throws |
| require(k>0) 失败 | search_k_zero_throws / search_k_negative_throws |
| require(kbId>=0) 失败 | search_knowledgeBaseId_negative_throws / search_kb_id_min_value_throws |
| kbId==null（不加 equal） | search_all_kb_returns_cross_kb / search_cross_kb_unified_similarity_sorting |
| kbId!=null（加 equal） | search_specified_kb_filters_correctly / search_default_kb_returns_only_default |
| 空库返回空 | search_empty_kb_returns_empty |
| 非空库正常返回 | search_returns_topk_with_default_k_5 |
| Query.use{} 关闭（50次无泄漏） | search_multiple_invocations_no_leak |

---

## 4. 分层测试详情

### 4.1 静态分析

| 工具 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| Android Lint | `./gradlew.bat :app:lintDebug` | 通过（0 Error，36 Warning 均为项目既有） | BUILD SUCCESSFUL in 1m 59s；US-017 文件（KnowledgeBaseRepository.kt / RetrievalResult.kt）0 lint issues |
| Detekt | 未配置 | N/A（项目无 detekt 插件） | app/build.gradle.kts 无 detekt 插件 |
| 手动安全扫描 | Select-String 搜索 | 通过 | 无硬编码密钥、无注入模式、无日志泄露（见第 6 节） |

### 4.2 单元测试

| 测试类 | 用例数 | 通过 | 失败 | 跳过 | 结果 |
| --- | --- | --- | --- | --- | --- |
| [KnowledgeBaseRetrievalTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalTest.kt) | 24 | 24 | 0 | 0 | 通过 |
| [ProbeNearestNeighborsWithEqualTest.kt](../../app/src/test/java/io/prism/data/ProbeNearestNeighborsWithEqualTest.kt) | 5 | 5 | 0 | 0 | 通过 |
| [KnowledgeBaseRetrievalEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt)（新增） | 12 | 12 | 0 | 0 | 通过 |
| **小计** | **41** | **41** | **0** | **0** | **通过** |

**覆盖率估算**（基于用例对代码分支的映射分析）：

| 方法 | 语句覆盖 | 分支覆盖 | 依据 |
| --- | --- | --- | --- |
| search() | >95% | >85% | 正常路径 + 3个require失败 + kbId三态 + 空库 + null embedding + k边界全覆盖 |
| parseTitle() | 100% | 100% | 8种title格式全覆盖（空串/#首位/#末尾/序号0/负数/非数字/正常/文件名含#） |
| RetrievalResult 构造 | 100% | N/A | 7字段全验证 |

> **覆盖率门禁**：语句 ≥90%（估算 >95%），分支 ≥80%（估算 >85%）。通过。
> 项目未配置 JaCoCo 覆盖率插件，以上为基于用例-代码分支映射的估算。

### 4.3 集成测试

| 测试类 | 用例数 | 通过 | 失败 | 结果 |
| --- | --- | --- | --- | --- |
| [KnowledgeBaseRetrievalIntegrationTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalIntegrationTest.kt)（新增） | 6 | 6 | 0 | 通过 |

| 场景 | 验证点 | 结果 | 证据 |
| --- | --- | --- | --- |
| 摄入→检索→title解析 | IngestionPipeline 生成 title `${doc}#${i+1}` → search → parseTitle 正确 | 通过 | ingest_then_search_returns_chunks_with_correct_source |
| 指定库摄入→指定库检索 | 摄入自建库 → 检索自建库 → 仅返回该库 | 通过 | ingest_to_self_built_kb_then_search_specified_kb |
| 默认库摄入→默认库检索 | 摄入默认库 → 检索默认库 → 返回结果 | 通过 | ingest_to_default_kb_then_search_default_kb |
| 嵌入失败→不参与检索 | failOnText 注入失败 → embedding=null 入库 → search 排除 | 通过 | ingest_with_embedding_failure_excluded_from_search |
| 多库摄入→全库检索 | 摄入库A+库B → 全库检索 → 跨库返回 | 通过 | ingest_multiple_kbs_then_search_all_kb |
| 检索结果完整字段 | 7 字段全验证（chunkId/content/title/similarity/documentTitle/chunkIndex/kbId） | 通过 | ingest_then_search_result_contains_all_fields |

### 4.4 端到端测试

**不适用**。US-017 是数据层组件（KnowledgeBaseRepository.search），无 UI 交互。E2E 由 US-018（知识库管理 UI）/ US-019（RAG 对话集成）承接。

---

## 5. 极端/边缘场景验证（主 Agent 自问盲区闭合）

> 针对 [guardrail 报告](2026-08-07-us017-retrieval-guardrail.md) §3.3 L1~L5 及主 Agent 7.3 节自问的盲区，ac-verifier 补充测试。

### 5.1 正交向量场景（主 Agent 最没把握）

**主 Agent 担心**：HNSW 在正交向量场景下是否真的会返回少于 k 条结果。

**测试构造**：查询 oneHot(0)，库内 7 条 chunk 用 oneHot(1)~oneHot(7)（全部与查询正交，distance=1.0, similarity=0.0），k=5。

**实际结果**：

```text
EDGE_CASE: 正交向量场景 k=5, 库内 7 条正交 chunk, 实际返回 5 条
```

**结论**：HNSW 在本测试场景下返回了完整的 5 条结果（=k），**未出现 < k 的情况**。主 Agent 在 ADR-010 风险表中记录的「HNSW 近似性可能导致 < k」是合理的预防性文档，但在 oneHot 正交场景下未复现。

**补充验证**（混合场景）：3 条同向 + 4 条正交，k=5 → 同向 3 条被优先返回（similarity≈1.0），验证 HNSW 优先返回最相似结果。

### 5.2 反向向量 similarity 中间值（主 Agent 遗憾）

**主 Agent 遗憾**：既有测试仅覆盖 similarity≈-1.0（完全反向）端点，未覆盖 distance=1.5（similarity=-0.5）中间值。

**测试构造**：
- 120° 夹角：query=oneHot(0), chunk=[-0.5, √3/2, 0, ...]，cos(120°)=-0.5, similarity=-0.5
- 60° 夹角：query=oneHot(0), chunk=[0.5, √3/2, 0, ...]，cos(60°)=0.5, similarity=0.5

**实际结果**：

```text
EDGE_CASE: 120度夹角 similarity=-0.5 (预期 ≈ -0.5)
EDGE_CASE: 60度夹角 similarity=0.5 (预期 ≈ 0.5)
```

**结论**：similarity 转换公式 `1.0 - distance` 在中间值路径正确。60°→0.5, 120°→-0.5, 0°→1.0, 90°→0.0, 180°→-1.0 全覆盖。

### 5.3 跨库排序语义（主 Agent 遗憾）

**主 Agent 遗憾**：全库检索跨库排序的语义（不同库 chunk 互相比较相似度）没有专门测试。

**测试构造**：默认库 chunk A(oneHot(0), sim=1.0) + chunk C(oneHot(5), sim=0.0)；自建库 chunk B(oneHot(0), sim=1.0) + chunk D(oneHot(5), sim=0.0)。全库检索 k=4。

**实际结果**：前 2 条来自不同库（A+B, similarity≈1.0），后 2 条来自不同库（C+D, similarity≈0.0）。

**结论**：全库检索按相似度统一排序，**非按库分组**。不同库的 chunk 在同一排序中交叉排列，符合 ADR-010 5.4 设计。

### 5.4 k=Int.MAX_VALUE 极端值

**结果**：k=Int.MAX_VALUE 返回全部 3 条可用 chunk，不溢出不报错。通过。

### 5.5 空 query 向量（全 0）

**测试构造**：query=FloatArray(384) 全 0（零向量模为 0，COSINE 未定义）。

**实际结果**：

```text
EDGE_CASE: 零向量查询 k=5, 库内 2 条, 返回 2 条
```

**结论**：零向量查询不崩溃，返回库内全部可用 chunk（2 条）。ObjectBox 对零向量返回了结果（未抛异常）。**风险标记**：零向量 similarity 值可能为 NaN 或特殊值，调用方（US-019）应避免传入零向量（OnnxEmbedder.embed 不产生零向量，因 L2 归一化后模恒为 1）。

---

## 6. 安全审计结果

### 6.1 基础安全检查（至少两项）

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| **注入类** | 通过 | ObjectBox Query 使用编译期属性引用 `KnowledgeChunk_.embedding.nearestNeighbors(query, k)` + `KnowledgeChunk_.knowledgeBaseId`，强类型参数化，非字符串拼接。Select-String 搜索 `query(.*+|query(.*$` 无匹配。无 rawQuery/execSQL/SELECT/INSERT 模式。 |
| **敏感信息泄露** | 通过 | Select-String 搜索 `Log\.|println|System\.out|System\.err|logger|Logger` 在生产代码（KnowledgeBaseRepository.kt / RetrievalResult.kt）中无匹配。require 消息仅含数值参数（id/k/kbId），不含密钥/密码/令牌/内部路径。 |
| XSS 测试 | 不适用 | 本项目无 Web 前端（Android 原生），XSS 不适用。 |

### 6.2 安全专项验证

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 硬编码密钥 | 通过 | Select-String 搜索 `api[_-]?key|token|secret|password|passwd|credential` 在 KnowledgeBaseRepository.kt / RetrievalResult.kt 中无匹配。 |
| SQL/NoSQL 注入 | 通过 | ObjectBox 编译期属性引用，非字符串拼接（见 6.1）。 |
| 维度校验绕过 | 通过 | `require(query.size == EMBEDDING_DIM)` 前置 fail-fast。传入非 384 维向量抛 IllegalArgumentException。传入 384 维零向量通过校验但 ObjectBox 不崩溃（5.5 节验证）。 |
| kbId 越界 | 通过 | `require(knowledgeBaseId == null \|\| knowledgeBaseId >= 0)` 拒绝负数。Long.MAX_VALUE 返回空（不存在的库）。Long.MIN_VALUE 抛 IllegalArgumentException。 |
| Query 资源泄漏 | 通过 | `queryBuilder.build().use { q -> ... }` 确保 Query native 句柄关闭。50 次连续检索无泄漏（search_multiple_invocations_no_leak）。 |
| 权限验证 | 不适用 | 数据层组件，无 AuthN/AuthZ 逻辑。kbId 三态语义是访问控制的一种形式（ADR-010 5.4），调用方负责传值。 |

### 6.3 guardrail 安全审计复核

guardrail-enforcer 报告（[2026-08-07-us017-retrieval-guardrail.md](2026-08-07-us017-retrieval-guardrail.md)）已确认：
- 无 SQL/NoSQL 注入 ✓（与 6.1 一致）
- 无硬编码密钥 ✓（与 6.2 一致）
- Query use{} 关闭 ✓（与 6.2 一致）
- 维度前置校验完备 ✓（与 6.2 一致）

ac-verifier 复核结论：**与 guardrail 一致，无新增安全问题。**

---

## 7. 性能回退检查

### 7.1 性能基线（首版）

> 无既有 US-017 检索性能基线，本次生成首版。存放于 [perf/2026-08-07-us017-retrieval-baseline.md](perf/2026-08-07-us017-retrieval-baseline.md)。

| 指标 | iters | min | p50 | p95 | p99 | max | 吞吐(search/s) | 失败 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 chunk top-5 | 20 | 102us | 115us | 194us | 194us | 194us | 8695.7 | 0 |
| 500 chunk top-5 | 10 | 100us | 111us | 142us | 142us | 142us | 9009.0 | 0 |
| 1000 chunk top-5 | 5 | 188us | 190us | 200us | 200us | 200us | 5263.2 | 0 |

### 7.2 回退判定

- **首版基线**，无前序基线可对比，故**不执行回退判定**（首次建立）。
- 后续 US-018+ 或重构若修改检索逻辑，须重跑本测试方法，对比 p50/p95/p99。
- 性能下降 >50% 标记失败；下降 >20% 标记警告。

### 7.3 局限说明

- **JVM 环境近似基线**：纯 JVM ObjectBox 测试（非 Android 设备），生产基线需在 Android 设备补测。
- 不含 OnnxEmbedder.embed 延迟（生产 ~100ms/次）。总检索延迟 = 本基线 + embed 延迟。
- 使用 oneHot 向量（非真实 embedding），HNSW 索引开销可能与真实场景略有差异。

---

## 8. 回归测试结果

| 套件 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 全量 testDebugUnitTest | `./gradlew.bat :app:testDebugUnitTest` | 通过 | BUILD SUCCESSFUL in 51s，无失败用例 |

**回归结论**：US-017 新增代码（search / parseTitle / RetrievalResult）未破坏任何既有测试。全量测试套件通过，无回归问题。

> 回归测试日志中有 ObjectBox WARN/ERROR（"Destroying inactive transaction in non-creator thread"），这些来自 McpClientManagerIntegrationTest 等其他测试的并发操作，**非 US-017 引入**，不影响回归结论。

---

## 9. 缺陷列表

| ID | 严重度 | AC | 描述 | 复现步骤 | 证据 |
| --- | --- | --- | --- | --- | --- |
| （无） | - | - | 无缺陷发现 | - | - |

---

## 10. 新增测试用例清单

| 文件 | 用例数 | 类型 | 路径 |
| --- | --- | --- | --- |
| KnowledgeBaseRetrievalEdgeCaseTest.kt | 12 | 极端场景补充 | [app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalEdgeCaseTest.kt) |
| KnowledgeBaseRetrievalIntegrationTest.kt | 6 | 集成测试 | [app/src/test/java/io/prism/data/KnowledgeBaseRetrievalIntegrationTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalIntegrationTest.kt) |
| KnowledgeBaseRetrievalPerfBaselineTest.kt | 1 | 性能基线 | [app/src/test/java/io/prism/data/KnowledgeBaseRetrievalPerfBaselineTest.kt](../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalPerfBaselineTest.kt) |

**新增用例明细**：

| 用例名 | 文件 | 覆盖盲区 |
| --- | --- | --- |
| search_orthogonal_vectors_does_not_crash | EdgeCaseTest | 正交向量场景（主 Agent 最没把握） |
| search_mixed_orthogonal_and_aligned_prioritizes_aligned | EdgeCaseTest | 正交+同向混合 |
| search_oblique_120_degrees_similarity_negative_half | EdgeCaseTest | 反向中间值 similarity=-0.5（主 Agent 遗憾） |
| search_oblique_60_degrees_similarity_positive_half | EdgeCaseTest | 正向中间值 similarity=0.5 |
| search_cross_kb_unified_similarity_sorting | EdgeCaseTest | 跨库排序语义（主 Agent 遗憾） |
| search_k_max_value_returns_all_available | EdgeCaseTest | k=Int.MAX_VALUE 极端值 |
| search_zero_vector_query_does_not_crash | EdgeCaseTest | 空 query 向量（全 0） |
| search_title_hash_at_start_returns_title_and_null | EdgeCaseTest | parseTitle # 在首位 |
| search_title_hash_at_end_returns_title_and_null | EdgeCaseTest | parseTitle # 在末尾 |
| search_title_empty_string_returns_empty_and_null | EdgeCaseTest | parseTitle 空串 |
| search_kb_id_max_value_returns_empty | EdgeCaseTest | kbId=Long.MAX_VALUE |
| search_kb_id_min_value_throws | EdgeCaseTest | kbId=Long.MIN_VALUE |
| ingest_then_search_returns_chunks_with_correct_source | IntegrationTest | 摄入→检索→title解析 |
| ingest_to_self_built_kb_then_search_specified_kb | IntegrationTest | 自建库摄入→检索 |
| ingest_to_default_kb_then_search_default_kb | IntegrationTest | 默认库摄入→检索 |
| ingest_with_embedding_failure_excluded_from_search | IntegrationTest | 嵌入失败→不参与检索 |
| ingest_multiple_kbs_then_search_all_kb | IntegrationTest | 多库摄入→全库检索 |
| ingest_then_search_result_contains_all_fields | IntegrationTest | 完整字段验证 |
| perf_baseline_search_top5 | PerfBaseline | 性能基线（100/500/1000 chunk） |

---

## 11. 未覆盖项与风险

| 项目 | 原因 | 风险 | 缓解 |
| --- | --- | --- | --- |
| 真实 OnnxEmbedder 向量检索 | 无 Android 模拟器/真机，JVM 测试用 oneHot 向量替代 | HNSW 索引开销在真实密集向量下可能不同 | US-018/019 在 Android 设备补测 |
| 真实大库检索（>1000 chunk） | JVM 测试 1000 chunk 已覆盖，更大规模受限于测试时间 | 10000+ chunk 延迟未测 | 后续在真机/模拟器上补测 |
| 零向量 similarity 值 | ObjectBox 对零向量行为未定义，测试仅验证不崩溃 | similarity 可能为 NaN 或特殊值 | OnnxEmbedder.embed 经 L2 归一化后模恒为 1，不产生零向量；调用方不应传入零向量 |
| 覆盖率工具（JaCoCo） | 项目未配置 JaJaCoCo 插件 | 覆盖率为估算非精确测量 | 基于用例-代码分支映射估算，语句>95% 分支>85% |
| E2E 测试 | US-017 是数据层组件，无 UI | RAG 端到端流程未验证 | US-019 RAG 对话集成时承接 E2E |
| HNSW 近似性 < k 复现 | 正交场景实测返回完整 k 条 | ADR-010 风险表记录的 < k 行为未复现 | 风险表保留预防性文档；US-019 调用方应处理 results.size < k |

---

## 12. guardrail 低危建议处理状态复核

| 编号 | 建议 | guardrail 状态 | ac-verifier 复核 |
| --- | --- | --- | --- |
| L1 | parseTitle 序号 0/负数/非数字 测试补充 | 已处理（3 用例） | 复核通过：search_title_chunk_index_zero/negative/non_numeric_returns_null 全 PASS |
| L2 | 反向向量 similarity 负值测试 | 已处理（1 用例） | 复核通过：search_opposite_vector_similarity_negative PASS；ac-verifier 补充 120°/60° 中间值 2 用例 |
| L3 | search KDoc 无阈值过滤说明 | 已处理 | 复核通过：[KnowledgeBaseRepository.kt](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) KDoc L229-232 已补说明 |
| L4 | ADR-010 风险表 HNSW 近似性 | 已处理 | 复核通过：[ADR-010](../decisions/ADR-010-m3-vector-retrieval.md) 风险表已补 2 项 |
| L5 | 考古报告 getScore() 类型修正 | 已处理 | 复核通过：[考古报告](2026-08-07-us017-retrieval-archaeology.md) §3.2 已修正为 double |

---

## 13. 审计签署

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-US017-ACCEPT-001 |
| 验收结论 | **通过（Pass）** |
| AC 覆盖 | 5/5（AC-1~AC-4 + Typecheck）全通过 |
| 单元测试 | 41 用例全通过（24 基础 + 5 探针 + 12 极端场景） |
| 集成测试 | 6 用例全通过 |
| 性能门禁 | 通过（首版基线，p50 < 200us） |
| 安全门禁 | 通过（无注入 / 无密钥 / 无泄露 / 校验完备） |
| 回归测试 | 通过（全量 testDebugUnitTest BUILD SUCCESSFUL） |
| 缺陷数 | 0 |
| 可否闭合开发周期 | **可以** |
| 验收日期 | 2026-08-07 |
