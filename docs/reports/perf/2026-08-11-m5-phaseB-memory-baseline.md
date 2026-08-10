# 性能基线：M5 Phase B SlidingWindowMemoryManager（首版）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，依 CLAUDE.md 第十一节 4。
> 由 ac-verifier 子 Agent 生成，作为 M5 Phase B SlidingWindowMemoryManager 性能回退检查的首版基线。

| 项目 | 内容 |
|---|---|
| 基线版本 | M5 Phase B / US-032 首版 |
| 记录日期 | 2026-08-11 |
| 测试设备 | Windows 开发机（纯 JVM 测试，非 Android 模拟器；JDK 17） |
| 测试方法 | `./gradlew.bat testDebugUnitTest --tests "io.prism.memory.M5PhaseBPerfBaselineTest" --rerun-tasks -i`，从 `-i` 输出采集 `PERF_BASELINE` 行 |
| 测试类 | [M5PhaseBPerfBaselineTest.kt](../../../app/src/test/java/io/prism/memory/M5PhaseBPerfBaselineTest.kt) |
| 测试耗时 | ~63s（7 测试方法，含 500-1000 次迭代计时） |
| 任务令牌 | TKN-M5-PHASEB-ACCEPTANCE-001 |
| 执行 Agent | ac-verifier |

## 1. 测试范围与局限

**测量内容**：
- `SlidingWindowMemoryManager.truncateMessages` 不同消息数量下的截断延迟
- `OpenAICompatibleProvider.parseCompletionResponse` 典型/异常 LLM 响应的解析延迟
- `SlidingWindowMemoryManager.processMessages` 无摘要路径（messages.size <= N）延迟
- `SlidingWindowMemoryManager.processMessages` 摘要路径（FakeProvider，测量滑动窗口逻辑开销）
- `SlidingWindowMemoryManager.processMessages` 截断降级路径延迟

**关键局限**：
- 摘要路径使用 FakeProvider（即时返回，不测量真实网络延迟 ~100-500ms）
- DataStore 使用 FakePreferenceDataStore（内存操作，不测量磁盘 I/O）
- 纯 JVM 测试（非 Android 设备），生产基线需在 Android 设备补测
- 不含 `ConversationSummarizer.summarize` 的 LLM 网络调用延迟（生产场景瓶颈）

## 2. 关键指标

### 截断降级 —— truncateMessages

| 消息数 | iters | min | p50 | p95 | p99 | max | 吞吐（ops/s） |
|---|---|---|---|---|---|---|---|
| 10 messages | 1000 | 2,099ns | 2,400ns | 12,399ns | 21,500ns | 37,900ns | 416,666.7 |
| 50 messages | 1000 | 8,099ns | 9,001ns | 46,700ns | 102,800ns | 3,309,900ns | 111,098.8 |

**分析**：截断延迟与消息数呈线性关系（10→50 消息，p50 从 2.4μs 增至 9.0μs，约 3.75x）。50 条消息截断 p50=9μs，远低于用户感知阈值。p99 尖峰（102.8μs）由 JVM GC 导致。

### JSON 解析 —— parseCompletionResponse

| 场景 | iters | min | p50 | p95 | p99 | max | 吞吐（ops/s） |
|---|---|---|---|---|---|---|---|
| 典型 LLM 响应 | 1000 | 14,199ns | 27,000ns | 116,600ns | 304,000ns | 35,255,600ns | 37,037.0 |
| 无效 JSON（HTML 错误页） | 1000 | 6,299ns | 8,401ns | 16,001ns | 49,700ns | 23,601,200ns | 119,033.4 |

**分析**：典型 LLM 响应解析 p50=27μs（kotlinx-serialization 反序列化 + firstOrNull + takeIf 链）。无效 JSON 快速失败 p50=8.4μs（异常捕获后立即返回 null）。两者均远低于用户感知阈值。p99/max 尖峰由 JIT 编译/GC 导致。

### 滑动窗口管理 —— processMessages

| 路径 | iters | min | p50 | p95 | p99 | max | 吞吐（ops/s） |
|---|---|---|---|---|---|---|---|
| 无摘要路径（size <= N） | 500 | 8,700ns | 10,601ns | 36,500ns | 141,601ns | 7,164,800ns | 94,330.7 |
| 摘要路径（FakeProvider） | 500 | 6,400ns | 6,799ns | 11,900ns | 21,400ns | 89,300ns | 147,080.5 |
| 截断降级路径 | 500 | 10,300ns | 12,700ns | 27,300ns | 253,200ns | 1,229,000ns | 78,740.2 |

**分析**：
- 无摘要路径 p50=10.6μs（DataStore 读 + coerceIn + 比较 + 列表返回）
- 摘要路径 p50=6.8μs（FakeProvider 即时返回，仅测量滑动窗口分割逻辑开销）
- 截断降级路径 p50=12.7μs（DataStore 读 + summarize(null) + truncateMessages(15msgs)）
- 三条路径延迟均在亚毫秒级，相对于 LLM 网络调用（~100-500ms）可忽略

### 与 M5 Phase A 基线对比

| 操作 | Phase A p50 | Phase B p50 | 来源 |
|---|---|---|---|
| MemoryRepository.searchByVector top-3 | 62μs | N/A | Phase A 基线 |
| MemoryRepository.save | 1,311μs | N/A | Phase A 基线 |
| SlidingWindowMemoryManager.processMessages (no-summary) | N/A | 10.6μs | Phase B 基线（本表） |
| OpenAICompatibleProvider.parseCompletionResponse | N/A | 27.0μs | Phase B 基线（本表） |

**分析**：Phase B 新增操作均为纯内存计算（无 ObjectBox I/O、无网络），延迟比 Phase A 的 ObjectBox 操作低 1-2 个数量级。滑动窗口管理本身不是性能瓶颈。

## 3. 分析

- **所有操作亚毫秒级**：最慢的 `parseCompletionResponse_typical` p50=27μs，远低于用户感知阈值（16ms）。滑动窗口管理对对话延迟的贡献可忽略。
- **生产预估**：真实场景 `processMessages` 摘要路径 = 滑动窗口逻辑(~10μs) + LLM 网络调用(~200ms) ≈ 200.01ms。滑动窗口逻辑占比 < 0.005%，LLM 网络调用是绝对瓶颈。
- **截断降级高效**：`truncateMessages` 50 条消息 p50=9μs，即使最坏情况（N=1，全部消息需截断）也仅微秒级。
- **错误率 0**：所有操作无失败。
- **线性扩展**：`truncateMessages` 延迟与消息数呈线性关系，50 条消息仍在微秒级，无性能隐患。

## 4. 回退门禁

- 本基线为**首版**，无前序基线可对比，故**不执行回退判定**（首次建立）。
- 后续 Phase（US-035 ConversationViewModel 上下文注入集成）或重构若修改滑动窗口/截断/解析逻辑，须重跑本测试方法，对比 p50/p95/p99：
  - 性能下降 >50%：标记失败
  - 性能下降 >20%：标记警告，PR 需说明原因

## 5. 不适用项

| 指标 | 原因 |
|---|---|
| LLM 网络调用延迟 | 无法在单元测试中测量（FakeProvider 即时返回），需 Android 设备 + 真实 API |
| DataStore 磁盘 I/O 延迟 | FakePreferenceDataStore 为内存操作，需 Android 设备测量真实 DataStore |
| Android UI 帧率影响 | 非 Phase B 范围（US-035 ConversationViewModel 集成后评估） |

## 6. 复现方式

```bash
./gradlew.bat testDebugUnitTest --tests "io.prism.memory.M5PhaseBPerfBaselineTest" --rerun-tasks -i
# 从 -i 输出采集 PERF_BASELINE 行
```
