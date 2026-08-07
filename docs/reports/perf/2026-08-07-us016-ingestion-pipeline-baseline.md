# 性能基线：US-016 摄入管线（首版）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，依 CLAUDE.md 第十一节 4。
> 由 ac-verifier 子 Agent 生成，作为 US-016 摄入管线性能回退检查的首版基线。

| 项目 | 内容 |
|---|---|
| 基线版本 | M3 / US-016 首版 |
| 记录日期 | 2026-08-07 |
| 测试设备 | Windows 开发机（纯 JVM ObjectBox 测试，非 Android 模拟器；JDK 17） |
| 测试方法 | `./gradlew.bat testDebugUnitTest --tests "*perf_baseline*" --rerun-tasks`，从测试 XML `system-out` 采集 `PERF_BASELINE` 行 |
| 测试类 | [IngestionPipelineTest.kt](../../../app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt) `perf_baseline_ingestion_pipeline_orchestration_and_objectbox_write` |
| 测试耗时 | 4.926s（单测试方法） |

## 1. 测试范围与局限

**测量内容**：管线编排开销（parse→chunk→embed→addChunk 链路）+ ObjectBox 写入（含 HNSW 索引重建）。

**关键局限**：
- 使用 `FakeEmbedder`（返回 384 维 one-hot 向量，微秒级），**无法测量真实 OnnxEmbedder ONNX 推理延迟**（生产 ~100ms/chunk，BR-concurrency-002 持锁）。
- 真实端侧集成（真 ONNX + 真文件解析 + Android SAF InputStream）因无模拟器受限，与 US-002~015 同模式跳过。
- 故本基线**仅反映管线 + DB 层开销**，生产环境总延迟 = 本基线 + N×100ms（ONNX 推理）。

## 2. 关键指标

### RAG 性能 —— 摄入管线（FakeEmbedder，纯管线+DB 层）

| 指标 | iters | min | p50 | p95 | p99 | max | 吞吐（chunk/s，基于 p50） | 失败数 |
|---|---|---|---|---|---|---|---|---|
| 10 chunk 摄入 | 20 | 5ms | 8ms | 30ms | 30ms | 30ms | 1250.0 | 0 |
| 50 chunk 摄入 | 10 | 56ms | 127ms | 220ms | 220ms | 220ms | 393.7 | 0 |
| 100 chunk 摄入 | 5 | 499ms | 641ms | 735ms | 735ms | 735ms | 156.0 | 0 |

**原始采集行**（测试 `system-out`）：

```text
PERF_BASELINE|chunks=10|iters=20|min=5ms|p50=8ms|p95=30ms|p99=30ms|max=30ms|throughput=1250.0_chunk_per_s|failures=0
PERF_BASELINE|chunks=50|iters=10|min=56ms|p50=127ms|p95=220ms|p99=220ms|max=220ms|throughput=393.7_chunk_per_s|failures=0
PERF_BASELINE|chunks=100|iters=5|min=499ms|p50=641ms|p95=735ms|p99=735ms|max=735ms|throughput=156.0_chunk_per_s|failures=0
```

### 资源占用

| 指标 | 基线值 | 备注 |
|---|---|---|
| 内存占用 | 未采集（JVM 测试，无 Android Profiler） | 受限，待模拟器/真机补充 |
| CPU 占用 | 未采集 | 同上 |
| 存储（知识库） | 每 chunk ≈ content+384×4B 向量 ≈ 数百字节 | HNSW 索引额外开销 |

## 3. 分析

- **延迟随 chunk 数非线性增长**：10→50→100 chunk，p50 从 8ms→127ms→641ms（每 chunk 约 0.8ms→2.5ms→6.4ms）。原因：HNSW 索引随 chunk 数增大，`Box.put` 重建索引开销增加（ObjectBox HNSW 插入是 O(log N) 级别但常数较大）。
- **错误率 0**：所有迭代无摄入失败（failures=0）。
- **吞吐随规模下降**：1250→393.7→156 chunk/s，符合 HNSW 索引开销预期。
- **生产预估**：真实 OnnxEmbedder 场景，100 chunk 文档 ≈ 641ms（管线+DB）+ 100×100ms（ONNX）≈ 10.6s。

## 4. 回退门禁

- 本基线为**首版**，无前序基线可对比，故**不执行回退判定**（首次建立）。
- 后续 US-018+ 或重构若修改摄入管线，须重跑本测试方法，对比 p50/p95/p99：
  - 性能下降 >50%：标记失败
  - 性能下降 >20%：标记警告，PR 需说明原因

## 5. 不适用项

| 指标 | 原因 |
|---|---|
| 冷启动/热启动时间 | 非应用启动测试 |
| 聊天首字延迟/吞吐 | 非 US-016 范围 |
| 向量检索延迟（top-5） | US-017 范围，已在 US-015 KnowledgeChunkVectorSearchTest 覆盖 |
| MCP Tool 调用延迟 | 不适用 |

## 6. 复现方式

```bash
./gradlew.bat testDebugUnitTest --tests "io.prism.ingestion.IngestionPipelineTest.perf_baseline*" --rerun-tasks
# 从 app/build/test-results/testDebugUnitTest/TEST-io.prism.ingestion.IngestionPipelineTest.xml 的 system-out 节点采集 PERF_BASELINE 行
```
