# 性能基线：M7 TierManager.initialize（首版）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，依 CLAUDE.md 第十一节 4。
> 由 ac-verifier 子 Agent 生成，作为 M7 设备适配层 TierManager.initialize 性能回退检查的首版基线。

| 项目 | 内容 |
|---|---|
| 基线版本 | M7 / US-040 首版 |
| 记录日期 | 2026-08-11 |
| 测试设备 | Windows 开发机（纯 JVM 测试，非 Android 模拟器；JDK 17） |
| 测试方法 | `./gradlew.bat testDebugUnitTest --tests "io.prism.tier.TierManagerPerfBaselineTest" --rerun-tasks`，从测试 XML `system-out` 采集 `PERF_BASELINE` 行 |
| 测试类 | [TierManagerPerfBaselineTest.kt](../../../app/src/test/java/io/prism/tier/TierManagerPerfBaselineTest.kt) `perf_baseline_tier_manager_initialize` |
| 测试耗时 | ~1m 15s（含编译；单测试方法 3 配置 × 预热5 + 计时50） |

## 1. 测试范围与局限

**测量内容**：`TierManager.initialize()` 在不同覆盖场景下的延迟（RAM 映射 + 覆盖读取 + resolveTier 逻辑开销）。

**关键局限**：

- 使用 `FakePreferenceDataStore`（纯内存），**不反映真实 Android DataStore I/O 延迟**（ADR-017 4.4 预期真实 DataStore 首次读取 <50ms）。本基线仅测量 initialize() 纯逻辑开销。
- 使用 fake `TierDetector`（直接返回内存值），不反映 `ActivityManager.MemoryInfo` 调用开销（API 16+，<1ms）。
- 纯 JVM 测试（非 Android 设备），**生产基线需在 Android 设备补测**。
- 不含 `by lazy` embedder 加载（~200ms，仅在首次访问时发生，非 initialize 范围）。

## 2. 关键指标

### TierManager.initialize 延迟（FakePreferenceDataStore，纯内存）

| 场景 | iters | min | p50 | p95 | p99 | max | 吞吐（init/s，基于 p50） | 失败数 |
|---|---|---|---|---|---|---|---|---|
| AUTO 无覆盖 | 50 | 51us | 65us | 185us | 293us | 293us | 15384.6 | 0 |
| FULL 覆盖 | 50 | 50us | 61us | 157us | 241us | 241us | 16393.4 | 0 |
| CHAT_ONLY 覆盖 | 50 | 44us | 53us | 135us | 704us | 704us | 18867.9 | 0 |

**原始采集行**（测试 `system-out`）：

```text
PERF_BASELINE|scenario=AUTO_no_override|iters=50|min=51us|p50=65us|p95=185us|p99=293us|max=293us|throughput=15384.6_init_per_s|failures=0
PERF_BASELINE|scenario=FULL_override|iters=50|min=50us|p50=61us|p95=157us|p99=241us|max=241us|throughput=16393.4_init_per_s|failures=0
PERF_BASELINE|scenario=CHAT_ONLY_override|iters=50|min=44us|p50=53us|p95=135us|p99=704us|max=704us|throughput=18867.9_init_per_s|failures=0
```

### 资源占用

| 指标 | 基线值 | 备注 |
|---|---|---|
| 内存占用 | 未采集（JVM 测试，无 Android Profiler） | 受限，待模拟器/真机补充 |
| CPU 占用 | 未采集 | 同上 |
| ANR 风险 | initialize() p50=53-65us（纯逻辑），真实 DataStore I/O 预期 <50ms | ADR-017 风险清单 R2：runBlocking 阻塞主线程，预期 <50ms 可接受 |

## 3. 分析

- **initialize 逻辑开销极低**：三场景 p50 均在 53-65us 级别（<0.1ms），远低于 ADR-017 预期的 <50ms（含 DataStore I/O）。
- **三场景延迟接近**：AUTO/FULL/CHAT_ONLY 覆盖场景的 p50 差异 <12us，说明 resolveTier 逻辑（valueOf + when）开销可忽略。
- **p99 偶发尖刺**：CHAT_ONLY 场景 p99=704us（可能为 GC 暂停），但仍在 1ms 以内，不影响 onCreate（<5s ANR 阈值）。
- **错误率 0**：所有迭代无失败（failures=0），H-01 修复确保异常路径降级 AUTO 不崩溃。
- **生产预估**：真实 Android 设备 initialize() = 逻辑开销（~65us）+ DataStore I/O（~50ms）≈ 50ms，远低于 5s ANR 阈值。

## 4. 回退门禁

- 本基线为**首版**，无前序基线可对比，故**不执行回退判定**（首次建立）。
- 后续若修改 TierManager.initialize 逻辑，须重跑本测试方法，对比 p50/p95/p99：
  - 性能下降 >50%：标记失败
  - 性能下降 >20%：标记警告，PR 需说明原因

## 5. 不适用项

| 指标 | 原因 |
|---|---|
| 冷启动/热启动时间 | 非应用启动测试（仅测量 initialize 单函数） |
| 内存峰值（4GB<1.2GB / 6GB<1.8GB） | 需 Android 真机 Profiler，JVM 测试无法测量 |
| buildRagPlan 短路延迟 | M-02 短路为同步 return（<1us），无需独立基线 |

## 6. 复现方式

```bash
./gradlew.bat testDebugUnitTest --tests "io.prism.tier.TierManagerPerfBaselineTest" --rerun-tasks
# 从 app/build/test-results/testDebugUnitTest/TEST-io.prism.tier.TierManagerPerfBaselineTest.xml 的 system-out 节点采集 PERF_BASELINE 行
```
