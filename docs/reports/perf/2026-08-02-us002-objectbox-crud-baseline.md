# 性能基线 —— US-002 ObjectBox CRUD

> 从 `docs/templates/performance-baseline-template.md` 复制新建。
> 依 CLAUDE.md 第十一节 4，ac-verifier 性能回退检查的依据。

| 项目 | 内容 |
|---|---|
| 基线版本 | v0.1.0 (US-002) |
| 记录日期 | 2026-08-02 |
| 测试设备 | Windows 11 笔记本 (LAPTOP-PGE8BV0D) / JVM 单元测试环境（非 Android 设备） |
| 测试方法 | JUnit 4 JVM 基准测试（`KnowledgeChunkPerformanceBenchmark`），500 次迭代 + 50 次预热，System.nanoTime 计时 |
| ObjectBox 版本 | 5.4.2 |
| 数据模型 | KnowledgeChunk(id, title, content, embedding) |

## 关键指标 —— ObjectBox CRUD 延迟

### 单条 PUT（插入）

| 指标 | 值 |
|---|---|
| p50 | 298.6 us (0.299 ms) |
| p95 | 443.1 us (0.443 ms) |
| p99 | 599.4 us (0.599 ms) |
| mean | 319.6 us |
| min | 235.9 us |
| max | 643.8 us |
| 迭代次数 | 500 |

### 单条 GET（查询）

| 指标 | 值 |
|---|---|
| p50 | 1.1 us (0.001 ms) |
| p95 | 1.7 us (0.002 ms) |
| p99 | 9.4 us (0.009 ms) |
| mean | 1.5 us |
| min | 0.9 us |
| max | 44.9 us |
| 迭代次数 | 500 |

### 单条 REMOVE（删除）

| 指标 | 值 |
|---|---|
| p50 | 330.3 us (0.330 ms) |
| p95 | 464.1 us (0.464 ms) |
| p99 | 547.0 us (0.547 ms) |
| mean | 344.3 us |
| min | 246.9 us |
| max | 575.5 us |
| 迭代次数 | 500 |

### 批量 PUT（1000 条插入）

| 指标 | 值 |
|---|---|
| p50 | 336,751 us (336.8 ms) |
| p95 | 443,252 us (443.3 ms) |
| p99 | 443,252 us (443.3 ms) |
| mean | 344,112 us |
| min | 316,496 us |
| max | 443,252 us |
| 迭代次数 | 10 |
| 吞吐 | ~2,972 ops/s（1000 条 / 336.8 ms） |

## 分析

1. **GET 极快**（p50 1.1 us）：ObjectBox 使用内存映射（mmap）读取，无需磁盘 I/O，适合高频查询场景。
2. **PUT/REMOVE 约 300-600 us**：涉及磁盘写入与事务提交，延迟在可接受范围。单条写入吞吐约 3.1K ops/s。
3. **批量 PUT 1000 条约 337 ms**：吞吐约 2.97K ops/s，与单条 PUT 吞吐接近，说明 ObjectBox 未对批量操作做特殊优化（每条单独 put）。后续可使用 `box.put(list)` 批量 API 优化。
4. **p99 偏差**：PUT p99 (599 us) / p50 (299 us) 比值约 2.0x，GET p99 (9.4 us) / p50 (1.1 us) 比值约 8.5x，存在偶发 GC/系统调度抖动，属正常范围。

## 环境说明

- 测试在 JVM 环境运行（`BoxStore.directory(tempDir)`），非真实 Android 设备
- Android 设备上的实际性能可能因存储 I/O 速度（eMMC/UFS）差异而不同
- 此基线仅作为后续性能回退对比的参考起点

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因
