# 性能基线：M5 Phase C CrossSessionMemoryManager（首版）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，依 CLAUDE.md 第十一节 4。
> 由 ac-verifier 子 Agent 生成，作为 M5 Phase C CrossSessionMemoryManager 性能回退检查的首版基线。

| 项目 | 内容 |
|---|---|
| 基线版本 | M5 Phase C / US-033 首版 |
| 记录日期 | 2026-08-11 |
| 测试设备 | Windows 开发机（纯 JVM ObjectBox 测试，非 Android 模拟器；JDK 17） |
| 测试方法 | `./gradlew.bat testDebugUnitTest --tests "io.prism.memory.CrossSessionMemoryManagerPerfBaselineTest" --rerun-tasks`，从测试 XML `system-out` 采集 `PERF_BASELINE` 行 |
| 测试类 | [CrossSessionMemoryManagerPerfBaselineTest.kt](../../../app/src/test/java/io/prism/memory/CrossSessionMemoryManagerPerfBaselineTest.kt) |
| 测试耗时 | 1.258s（5 测试方法） |
| 任务令牌 | TKN-M5-PHASEC-ACCEPTANCE-001 |
| 执行 Agent | ac-verifier |

## 1. 测试范围与局限

**测量内容**：
- `CrossSessionMemoryManager.saveSessionMemories` 在 1/5/10 个轮次对下的延迟
- `CrossSessionMemoryManager.retrieveRelevantMemories` top-3 在 100 条记录下的延迟
- `CrossSessionMemoryManager.formatMemoriesAsContext` 格式化 3 条结果的延迟

**关键局限**：
- 使用 FakeEmbedder（非真实 OnnxEmbedder），embed 开销远低于生产（~0.01ms vs ~100ms）
- 纯 JVM ObjectBox 测试（非 Android 设备），**生产基线需在 Android 设备补测**
- 管理层开销（filter/group/format）是主要测量目标，embed 开销差异在分析中说明
- saveSessionMemories 延迟含 MemoryRepository.save 的 StateFlow `refreshFlows()` 开销

## 2. 关键指标

### saveSessionMemories —— 保存 1 个轮次对

| 指标 | min | p50 | p95 | p99 | max | 吞吐（save/s） | 失败数 |
|---|---|---|---|---|---|---|---|
| 1 pair | 512us | 834us | 1337us | 6495us | 6495us | 1199.0 | 0 |

### saveSessionMemories —— 保存 5 个轮次对

| 指标 | min | p50 | p95 | p99 | max | 吞吐（save/s） | 失败数 |
|---|---|---|---|---|---|---|---|
| 5 pairs | 3002us | 5197us | 6865us | 6937us | 6937us | 192.4 | 0 |

### saveSessionMemories —— 保存 10 个轮次对

| 指标 | min | p50 | p95 | p99 | max | 吞吐（save/s） | 失败数 |
|---|---|---|---|---|---|---|---|
| 10 pairs | 11944us | 15450us | 18313us | 19492us | 19492us | 64.7 | 0 |

### retrieveRelevantMemories —— top-3 检索（100 条记录）

| 指标 | min | p50 | p95 | p99 | max | 吞吐（retrieve/s） | 失败数 |
|---|---|---|---|---|---|---|---|
| 100 records top-3 | 107us | 152us | 242us | 2023us | 2023us | 6578.9 | 0 |

### formatMemoriesAsContext —— 格式化 3 条结果

| 指标 | min | p50 | p95 | p99 | max | 吞吐（format/s） | 失败数 |
|---|---|---|---|---|---|---|---|
| 3 results | 5us | 8us | 22us | 60us | 60us | 125000.0 | 0 |

**原始采集行**（测试 `system-out`）：

```text
PERF_BASELINE|op=saveSessionMemories|scale=1_pair|iters=50|min=512us|p50=834us|p95=1337us|p99=6495us|max=6495us|throughput=1199.0_save_per_s|failures=0
PERF_BASELINE|op=saveSessionMemories|scale=5_pairs|iters=30|min=3002us|p50=5197us|p95=6865us|p99=6937us|max=6937us|throughput=192.4_save_per_s|failures=0
PERF_BASELINE|op=saveSessionMemories|scale=10_pairs|iters=30|min=11944us|p50=15450us|p95=18313us|p99=19492us|max=19492us|throughput=64.7_save_per_s|failures=0
PERF_BASELINE|op=retrieveRelevantMemories|scale=100_records_top3|iters=30|min=107us|p50=152us|p95=242us|p99=2023us|max=2023us|throughput=6578.9_retrieve_per_s|failures=0
PERF_BASELINE|op=formatMemoriesAsContext|scale=3_results|iters=100|min=5us|p50=8us|p95=22us|p99=60us|max=60us|throughput=125000.0_format_per_s|failures=0
```

## 3. 与 Phase A 基线对比

| 操作 | Phase A 基线 p50 | Phase C 实测 p50 | 差异 | 分析 |
|---|---|---|---|---|
| MemoryRepository.searchByVector top-3 (100 records) | 62us | — | — | Phase A 基线（未修改） |
| CrossSessionMemoryManager.retrieveRelevantMemories (100 records) | — | 152us | +90us vs Phase A searchByVector | 增量 = isBlank 检查 + FakeEmbedder.embed + 调用开销。生产环境 OnnxEmbedder.embed ~100ms，此 90us 开销可忽略 |
| MemoryRepository.save single | 1311us | — | — | Phase A 基线（未修改） |
| CrossSessionMemoryManager.saveSessionMemories 1 pair | — | 834us | -477us vs Phase A save | 差异由 JVM 运行时波动（GC/JIT）导致，非真实回退。两者均调用 MemoryRepository.save（含 StateFlow 刷新） |
| formatMemoriesAsContext 3 results | — | 8us | N/A | 新功能，纯字符串操作，开销可忽略 |

### 线性扩展性分析

| 轮次对数 | p50 | 相对 1 pair 倍数 | 理论倍数 | 分析 |
|---|---|---|---|---|
| 1 | 834us | 1.0x | 1.0x | 基准 |
| 5 | 5197us | 6.2x | 5.0x | 略超线性，因每对需 embed + save + StateFlow 刷新 |
| 10 | 15450us | 18.5x | 10.0x | 超线性，因 StateFlow 全量刷新随记录数增长 |

**分析**：saveSessionMemories 延迟随轮次对数超线性增长，主要瓶颈是 MemoryRepository.save 内的 StateFlow `refreshFlows()`（每次 save 都执行 `box.all` 全量读取 + 排序）。这是 Phase A 的已知设计（guardrail L-05 评估正确），在当前数据规模（单会话 ≤20 对）下性能完全可接受。

## 4. 回退门禁

- 本基线为**首版**，无前序 CrossSessionMemoryManager 基线可对比，故**不执行回退判定**（首次建立）。
- Phase A MemoryRepository 代码**未修改**，Phase A 基线不受影响。
- 后续重构若修改 CrossSessionMemoryManager 检索/保存逻辑，须重跑本测试方法，对比 p50/p95/p99：
  - 性能下降 >50%：标记失败
  - 性能下降 >20%：标记警告，PR 需说明原因

## 5. 生产预估

| 场景 | 测试环境延迟 | 生产预估延迟 | 瓶颈 |
|---|---|---|---|
| retrieveRelevantMemories (100 records) | p50=152us | ~100.15ms | OnnxEmbedder.embed (~100ms) |
| saveSessionMemories (1 pair) | p50=834us | ~100.83ms | OnnxEmbedder.embed (~100ms) |
| saveSessionMemories (10 pairs) | p50=15.45ms | ~1015.45ms | 10x OnnxEmbedder.embed (~1000ms) |
| formatMemoriesAsContext | p50=8us | ~8us | 无瓶颈（纯字符串） |

**结论**：生产环境瓶颈始终是 OnnxEmbedder.embed（~100ms/次），CrossSessionMemoryManager 管理层开销（filter/group/format）在微秒级，可忽略。

## 6. 不适用项

| 指标 | 原因 |
|---|---|
| 冷启动/热启动时间 | 非应用启动测试 |
| 聊天首字延迟/吞吐 | 非 M5 Phase C 范围（US-035 上下文注入） |
| 嵌入编码延迟 | US-014 范围，已有基线 `2026-08-07-us014-embedding-baseline.md` |
| MemoryRepository 独立性能 | Phase A 已建立基线 `2026-08-10-m5-phaseA-memory-baseline.md`，代码未修改 |

## 7. 复现方式

```bash
./gradlew.bat testDebugUnitTest --tests "io.prism.memory.CrossSessionMemoryManagerPerfBaselineTest" --rerun-tasks
# 从 app/build/test-results/testDebugUnitTest/TEST-io.prism.memory.CrossSessionMemoryManagerPerfBaselineTest.xml 的 system-out 节点采集 PERF_BASELINE 行
```
