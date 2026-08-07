# 性能基线：US-017 向量检索（首版）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，依 CLAUDE.md 第十一节 4。
> 由 ac-verifier 子 Agent 生成，作为 US-017 向量检索性能回退检查的首版基线。

| 项目 | 内容 |
|---|---|
| 基线版本 | M3 / US-017 首版 |
| 记录日期 | 2026-08-07 |
| 测试设备 | Windows 开发机（纯 JVM ObjectBox 测试，非 Android 模拟器；JDK 17） |
| 测试方法 | `./gradlew.bat testDebugUnitTest --tests "io.prism.data.KnowledgeBaseRetrievalPerfBaselineTest" --rerun-tasks`，从测试 XML `system-out` 采集 `PERF_BASELINE` 行 |
| 测试类 | [KnowledgeBaseRetrievalPerfBaselineTest.kt](../../../app/src/test/java/io/prism/data/KnowledgeBaseRetrievalPerfBaselineTest.kt) `perf_baseline_search_top5` |
| 测试耗时 | 11.341s（单测试方法，含 3 配置 × 插入+预热+计时） |

## 1. 测试范围与局限

**测量内容**：`KnowledgeBaseRepository.search(query, k=5)` 在不同 chunk 数量下的 HNSW 检索延迟。

**关键局限**：
- 使用 oneHot 向量（非真实 OnnxEmbedder 向量），HNSW 索引开销可能与真实场景略有差异（oneHot 向量极度稀疏，真实 embedding 更密集）。
- 纯 JVM ObjectBox 测试（非 Android 设备），**生产基线需在 Android 设备补测**。
- 不含 Embedder.embed 延迟（生产 ~100ms/次，BR-concurrency-002 持锁）。总检索延迟 = 本基线 + embed 延迟。
- 查询向量固定为 oneHot(0)，库内 chunk 用 oneHot(i % 384)，部分 chunk 与查询同向（similarity=1.0），部分正交（similarity=0.0）。

## 2. 关键指标

### RAG 性能 —— 向量检索 top-5（oneHot 向量，纯 HNSW+DB 层）

| 指标 | iters | min | p50 | p95 | p99 | max | 吞吐（search/s，基于 p50） | 失败数 |
|---|---|---|---|---|---|---|---|---|
| 100 chunk top-5 | 20 | 102us | 115us | 194us | 194us | 194us | 8695.7 | 0 |
| 500 chunk top-5 | 10 | 100us | 111us | 142us | 142us | 142us | 9009.0 | 0 |
| 1000 chunk top-5 | 5 | 188us | 190us | 200us | 200us | 200us | 5263.2 | 0 |

**原始采集行**（测试 `system-out`）：

```text
PERF_BASELINE|chunks=100|iters=20|min=102us|p50=115us|p95=194us|p99=194us|max=194us|throughput=8695.7_search_per_s|failures=0
PERF_BASELINE|chunks=500|iters=10|min=100us|p50=111us|p95=142us|p99=142us|max=142us|throughput=9009.0_search_per_s|failures=0
PERF_BASELINE|chunks=1000|iters=5|min=188us|p50=190us|p95=200us|p99=200us|max=200us|throughput=5263.2_search_per_s|failures=0
```

### 资源占用

| 指标 | 基线值 | 备注 |
|---|---|---|
| 内存占用 | 未采集（JVM 测试，无 Android Profiler） | 受限，待模拟器/真机补充 |
| CPU 占用 | 未采集 | 同上 |
| 存储（知识库） | 每 chunk ≈ content+384×4B 向量 ≈ 数百字节 | HNSW 索引额外开销 |

## 3. 分析

- **检索延迟极低**：100/500 chunk 的 p50 均在 ~110us 级别（<0.2ms），1000 chunk p50 跳至 ~190us。HNSW 检索复杂度近似 O(log N)，在 1000 chunk 以下延迟可忽略。
- **100→500 chunk 延迟几乎不增长**：p50 从 115us→111us（甚至略降，可能因 JVM JIT 优化）。表明 HNSW 在小规模数据下检索开销几乎不受 chunk 数影响。
- **1000 chunk 延迟显著增长**：p50 从 111us→190us（+71%），p95 从 142us→200us（+41%）。HNSW 索引图在 ~1000 节点时遍历深度增加。
- **错误率 0**：所有迭代无检索失败（failures=0）。
- **生产预估**：真实 OnnxEmbedder 场景，单次检索 = embed(~100ms) + search(~0.2ms) ≈ 100.2ms。检索本身不是瓶颈，embed 才是瓶颈（BR-concurrency-002 持锁串行化）。

## 4. 回退门禁

- 本基线为**首版**，无前序基线可对比，故**不执行回退判定**（首次建立）。
- 后续 US-018+ 或重构若修改检索逻辑，须重跑本测试方法，对比 p50/p95/p99：
  - 性能下降 >50%：标记失败
  - 性能下降 >20%：标记警告，PR 需说明原因

## 5. 不适用项

| 指标 | 原因 |
|---|---|
| 冷启动/热启动时间 | 非应用启动测试 |
| 聊天首字延迟/吞吐 | 非 US-017 范围（US-019 RAG 集成） |
| 嵌入编码延迟 | US-014 范围，已有基线 `2026-08-07-us014-embedding-baseline.md` |
| MCP Tool 调用延迟 | 不适用 |

## 6. 复现方式

```bash
./gradlew.bat testDebugUnitTest --tests "io.prism.data.KnowledgeBaseRetrievalPerfBaselineTest" --rerun-tasks
# 从 app/build/test-results/testDebugUnitTest/TEST-io.prism.data.KnowledgeBaseRetrievalPerfBaselineTest.xml 的 system-out 节点采集 PERF_BASELINE 行
```
