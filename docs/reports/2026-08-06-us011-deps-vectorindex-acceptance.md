# 验收测试报告：US-011 依赖落地 + KnowledgeChunk 向量索引

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-US011-AC-001 |
| 验收日期 | 2026-08-06 |
| 关联 PRD | prd.json US-011（5 条验收标准，AC1-AC5） |
| 关联 ADR | ADR-007-m3-rag-tech-stack.md（5.1/5.2/5.3 依赖落地与向量索引） |
| guardrail 报告 | docs/reports/2026-08-06-us011-deps-vectorindex-guardrail.md（TKN-US011-GUARDRAIL-001，结论通过） |
| 技术栈 | Android Compose、Kotlin 2.3.21、ObjectBox 5.4.2（HNSW 向量搜索）、onnxruntime-android 1.27.0、poi-ooxml 5.5.1、JUnit4 |
| 验证范围 | gradle/libs.versions.toml / app/build.gradle.kts / KnowledgeChunk.kt + KnowledgeChunkVectorSearchTest.kt + 新增 KnowledgeChunkVectorSearchEdgeCaseTest.kt |

## 1. 总体结论

## ✅ 通过（Pass）

**US-011 全部 5 条验收标准（AC1-AC5）均验证通过，无阻断缺陷，无回归。**

- 配置证据：`libs.versions.toml` 精确包含 onnxruntime 1.27.0 与 poi-ooxml 5.5.1；`app/build.gradle.kts` 引入两依赖并启用 `multiDexEnabled`；`KnowledgeChunk.embedding` 标注 `@HnswIndex(dimensions=384, distanceType=COSINE)`。
- 单元测试：向量近邻检索 11 用例全部通过（既有 4 + ac-verifier 补充边界 7）。
- 静态分析：`:app:compileDebugKotlin` BUILD SUCCESSFUL（exit 0）；`:app:lintDebug` 0 errors。
- 回归测试：全量 274 用例，0 failures / 0 errors（15 个为跳过的性能基准）。
- 边界发现：ObjectBox 5.4.2 对维度不匹配的查询返回 COSINE 距离上界哨兵值 2.0（非抛异常/空），已记录风险（见 §8）。

---

## 2. 验收标准覆盖矩阵

| AC ID | 验收标准 | 验证方法 | 结果 | 证据位置 |
|---|---|---|---|---|
| AC-1 | libs.versions.toml 新增 onnxruntime-android 1.27.0 与 poi-ooxml 5.5.1 | 静态核对 libs.versions.toml | ✅ 通过 | `gradle/libs.versions.toml:20-21`（onnxruntime="1.27.0"、poiOoxml="5.5.1"）；`:53-54`（onnxruntime-android、poi-ooxml 依赖别名） |
| AC-2 | app/build.gradle.kts 引入依赖并启用 multiDexEnabled | 静态核对 app/build.gradle.kts | ✅ 通过 | `app/build.gradle.kts:22`（multiDexEnabled=true，注释关联 ADR-007 5.3）；`:97-98`（implementation(libs.onnxruntime.android)、implementation(libs.poi.ooxml)） |
| AC-3 | KnowledgeChunk.embedding 加 @HnswIndex(dimensions=384, distanceType=COSINE) 注解 | 静态核对 KnowledgeChunk.kt | ✅ 通过 | `app/src/main/java/io/prism/data/KnowledgeChunk.kt:27-28`（`@HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)`） |
| AC-4 | nearestNeighbors 近邻查询单元测试通过（含 embedding=null 不参与检索） | 运行向量检索测试 | ✅ 通过 | `KnowledgeChunkVectorSearchTest`（4 用例）+ `KnowledgeChunkVectorSearchEdgeCaseTest`（7 用例，ac-verifier 新增）全部通过；测试 XML `build/test-results/testDebugUnitTest/TEST-io.prism.data.KnowledgeChunkVectorSearchTest.xml` 与 EdgeCase 对应 XML 均 0 failures/0 errors |
| AC-5 | Typecheck passes | 编译 + lint | ✅ 通过 | `.\gradlew.bat :app:compileDebugKotlin --offline` BUILD SUCCESSFUL（exit 0）；`:app:lintDebug` 0 errors / 33 warnings |

---

## 3. 分层测试

### 3.1 静态分析

| 检查项 | 命令 | 结果 | 证据 |
|---|---|---|---|
| 编译 Typecheck | `.\gradlew.bat :app:compileDebugKotlin --offline` | ✅ 通过 | `BUILD SUCCESSFUL in 2s`（exit 0），19 个 task up-to-date |
| Android Lint | `.\gradlew.bat :app:lintDebug --offline` | ✅ 通过 | `app/build/reports/lint-results-debug.txt`：0 errors / 33 warnings。新增 3 warnings 全部来自 `poi-ooxml-5.5.1.jar` 内部 `TrustAllX509TrustManager`（第三方 jar，非本项目代码），其余 30 条为既有告警，US-011 业务变更文件（KnowledgeChunk.kt）无告警 |

### 3.2 单元测试

本迭代涉及向量检索单元测试，分两组：

| 套件 | 用例数 | 通过 | 失败 | 结果 |
|---|---|---|---|---|
| KnowledgeChunkVectorSearchTest（主 Agent 既有） | 4 | 4 | 0 | ✅ 通过 |
| KnowledgeChunkVectorSearchEdgeCaseTest（ac-verifier 新增） | 7 | 7 | 0 | ✅ 通过 |

既有 4 用例逐一核对（AC-4 直接证据）：

| 用例 | 覆盖 AC | 验证内容 |
|---|---|---|
| `nearestNeighbors_returns_topk_by_similarity` | AC-4 | top-k 按相似度返回正确片段 |
| `nearestNeighbors_embedding_null_excluded` | AC-4 | embedding=null 不参与近邻检索 |
| `nearestNeighbors_empty_box_returns_empty` | AC-4 | 空库返回空结果 |
| `nearestNeighbors_k_honors_limit` | AC-4 | k 上限生效 |

### 3.3 集成测试

本迭代为「依赖落地 + 索引声明」阶段，无新增跨模块接口。向量索引的写入与检索契约经 `MyObjectBox` 纯 JVM 临时目录构建验证（与既有 KnowledgeChunkCrudTest/EdgeCaseTest 同模式）。`@HnswIndex` 注解参数与 COSINE 分数语义已经 guardrail 反编译 ObjectBox 5.4.2 真实 API 核对全部正确（见 guardrail 报告第三节）。

### 3.4 E2E 测试

未执行真实设备 E2E（本环境无 Android 模拟器）。`@HnswIndex` 在真机上的原生实现待发布前真机冒烟验证（见 §8）。核心近邻检索语义已由单元测试全覆盖。

---

## 4. 边界 / 极端场景（ac-verifier 补充）

| 场景 | 用例 | 分析 | 结论 |
|---|---|---|---|
| top-k 完整排序 | `nearestNeighbors_full_ordering_all_topk` | 4 个方向各异向量，query 逐维递减使各方向 COSINE 距离严格可区分，断言完整单调递增序（回应 guardrail L-02） | ✅ 通过 |
| 全同向量（退化） | `nearestNeighbors_all_identical_vectors` | 3 个完全相同的向量 + 相同查询（COSINE 距离=0），断言分数彼此相等（容差） | ✅ 通过 |
| 大量向量 top-k | `nearestNeighbors_large_dataset_topk` | 1000 条向量，块0 与查询同向（距离0），其余正交换向，断言最近邻为块0 且分数=0、完整单调非递减序 | ✅ 通过 |
| k 超量 | `nearestNeighbors_k_greater_than_available_returns_all` | 仅 2 条，k=5，断言返回全部可用而非崩溃 | ✅ 通过 |
| k=1 边界 | `nearestNeighbors_k_one_returns_single` | k=1 返回最近的单条 | ✅ 通过 |
| 纯 null embedding（极端） | `nearestNeighbors_only_null_embeddings_returns_empty` | 库中仅 null 记录，断言返回空（与 AC-4 null 排除互补） | ✅ 通过 |
| 维度不匹配（异常路径） | `nearestNeighbors_dimension_mismatch_rejected` | 查询向量 2 维 vs 索引 384 维。**实测发现**：ObjectBox 5.4.2 不抛异常、不返回空，而是返回记录并赋予 COSINE 距离上界哨兵值 2.0（最不相似），调用方可据此识别无效匹配 | ✅ 通过（行为已文档化，风险见 §8） |

> 说明：边界测试设计过程中，`large_dataset` 初版因 `oneHot(i)` 下标越界（i>383）暴露测试自身缺陷、`full_ordering` 初版因正交向量产生并列距离 1.0 暴露断言缺陷，均已修正测试构造。这些是测试用例设计问题，非业务代码缺陷。

---

## 5. 性能回退检查

| 指标 | 基线 | 实测 | 结果 |
|---|---|---|---|
| nearestNeighbors 近邻检索 | 无基线 | 本迭代为「依赖落地 + 索引声明」阶段，无切片/嵌入写入方，检索调用方在 US-017 才落地；`large_dataset_topk`（1000 条）用例执行通过（EdgeCase 套件 6.5s 含全部 7 用例） | ✅ 可接受（无基线，依任务约定不强制定量对比） |

**说明**：项目性能基线（`docs/reports/perf/`）覆盖 ObjectBox CRUD/APIKey/ProviderConfig，无向量检索基线。建议在 US-017 向量检索模块落地时建立 nearestNeighbors 基线。

---

## 6. 安全检查

| 检查项 | 结果 | 证据 |
|---|---|---|
| 无硬编码密钥/令牌 | ✅ 通过 | 本迭代仅依赖声明与实体注解；`384` 为模型维度常量非敏感信息；guardrail 已审计无任何密钥/口令/令牌 |
| 注入类（SQL/命令/代码） | ✅ 不适用 | 无原始 SQL（ObjectBox 实体存储）、无命令执行、无动态求值 |
| XSS | ✅ 不适用 | Compose 原生渲染，无 HTML 注入面 |
| 依赖 License 合规 | ✅ 通过 | onnxruntime MIT、poi-ooxml Apache 2.0，ADR-007 5.2/5.3 已确认 |
| 供应链风险 | ✅ 本期可控 | 版本固定（1.27.0 / 5.5.1），无模糊版本范围 |
| 新增 lint 告警（poi jar 内部 TLS） | ✅ 低风险 | `poi-ooxml-5.5.1.jar` 内部 `TrustAllX509TrustManager` 3 条 warning，属第三方 jar 内部实现；本期仅声明未使用，无调用路径；US-012 使用 poi 时须纳入 guardrail（呼应 guardrail R-01） |

---

## 7. 回归测试

| 套件 | 总用例 | 通过 | 失败/错误 | 结果 |
|---|---|---|---|---|
| 全量 testDebugUnitTest（25 类） | 274 | 274 | 0 / 0 | ✅ 通过 |

（含 15 个跳过的性能基准用例，非失败；259 个实际执行用例全部通过。相比 US-010 的 263 用例，新增 11 个 = 既有向量检索 4 用例 + ac-verifier 边界 7 用例，均属 US-011 新增。）

---

## 8. 未覆盖项与风险

| 项目 | 原因 | 风险 | 处置建议 |
|---|---|---|---|
| ObjectBox 维度不匹配返回哨兵值 2.0 | 实测 ObjectBox 5.4.2 行为：查询向量维度 != 索引维度时，不抛异常、不返回空，而是返回距离上界 2.0 的记录 | 低：当前 US-011 固定 384 维，调用方恒用 384 维查询，不会触发；若未来嵌入维度变化需警惕调用方拿到退化结果 | 在 US-017 检索模块实现时，对 query 维度做显式前置校验（==384），避免依赖 ObjectBox 哨兵值 |
| poi-ooxml TrustAllX509TrustManager lint 告警 | 第三方 jar 内部实现，非本项目代码 | 低：本期未使用该依赖，无调用路径；US-012 启用 DOCX/XLSX 解析时暴露 | US-012 guardrail 时复核 poi 网络/解析面，对照 OWASP XXE 防护（呼应 guardrail R-01） |
| 真实设备 HNSW 原生实现 | 无 Android 模拟器 | 低：JVM 纯内存验证通过，真机 ObjectBox 原生向量实现待验证 | 发布前真机冒烟验证 nearestNeighbors |
| 向量检索性能基线 | 无检索调用方，属 US-017 | 低 | US-017 落地时建立 nearestNeighbors 基准 |

---

## 9. 结论

| 结论 | 是否 |
|---|---|
| 通过 | ✅ |
| 不通过（回退至 guardrail-enforcer 阶段） | 否 |

**US-011「依赖落地 + KnowledgeChunk 向量索引」验收通过。** 5 条验收标准全部满足：依赖版本与配置精确命中（AC-1/2）、HNSW 注解参数正确（AC-3）、近邻检索 11 用例通过含 null 排除（AC-4）、Typecheck 与 lint 通过（AC-5）。全量 274 用例回归无失败，无阻断缺陷。遗留 4 项可推迟的低风险项（维度前置校验建议、poi 使用复审、真机冒烟、性能基线）不阻断本轮交付。
