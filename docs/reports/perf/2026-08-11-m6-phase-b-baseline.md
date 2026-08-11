# 性能基线：M6 Phase B AI 集成层

| 项目 | 内容 |
|---|---|
| 基线版本 | M6 Phase B（US-038，LocalToolExecutor + CrossAppLocalToolExecutor + SkillExecutor 扩展） |
| 记录日期 | 2026-08-11 |
| 测试设备 | JVM 单元测试（LAPTOP-PGE8BV0D，Windows 11，JDK 17） |
| 测试方法 | JUnit 5 + System.nanoTime() 计时，1000 次迭代（前 100 次预热），统计 p50/p95/p99/avg/吞吐 |
| 测试文件 | [M6PhaseBPerformanceBaselineTest.kt](../../../app/src/test/java/io/prism/crossapp/M6PhaseBPerformanceBaselineTest.kt) |
| 测试结果 | 6/6 通过，0 失败 |

## 测试环境说明

本基线在 JVM 环境下采集，不含 Android 运行时开销（如 IPC、PackageManager 查询、Activity 启动延迟）。
实际设备上的性能将受 Android Framework 调度影响，预计 p50 延迟增加 5-50 倍（取决于操作类型）。
基线用途：后续 Phase C/D 变更的性能回退对比基准。

## 关键指标

### 本地工具分发性能

| 指标 | 基线值 | p50 | p95 | p99 | avg | 吞吐（ops/s） |
|---|---|---|---|---|---|---|
| `handles()` 集合查找 | 100ns | 100ns | 101ns | 101ns | 85ns | 11,764,705 |
| `execute(open_app, 3 args)` 参数提取+分发（无协程） | 600ns | 600ns | 800ns | 12,300ns | 1,025ns | 975,609 |
| `executeToolCall` 本地工具分支（含 withTimeout + 确认门禁） | 70,600ns | 70.6us | 219us | 297.2us | 93.5us | 10,698 |

### URI 模板替换性能

| 指标 | 基线值 | p50 | p95 | p99 | avg | 吞吐（ops/s） |
|---|---|---|---|---|---|---|
| `resolveTemplates` 单占位符 + URLEncoder | 1,400ns | 1.4us | 2.0us | 3.7us | 1.62us | 617,283 |
| `resolveTemplates` 双占位符 + 中文 + URLEncoder | 4,900ns | 4.9us | 15.5us | 27.3us | 7.09us | 141,023 |

### 结果判定性能

| 指标 | 基线值 | p50 | p95 | p99 | avg | 吞吐（ops/s） |
|---|---|---|---|---|---|---|
| `isFailureResult` 前缀匹配 | 200ns | 200ns | 500ns | 699ns | 224ns | 4,464,285 |

## 原始测试输出

```
[M6-Perf] isFailureResult(): p50=200ns p95=500ns p99=699ns avg=224ns throughput=4464285ops/s
[M6-Perf] resolveTemplates(2 params + Chinese): p50=4900ns p95=15500ns p99=27299ns avg=7091ns throughput=141023ops/s
[M6-Perf] executeToolCall(local branch): p50=70600ns p95=219000ns p99=297200ns avg=93473ns throughput=10698ops/s
[M6-Perf] resolveTemplates(1 param): p50=1400ns p95=2000ns p99=3700ns avg=1620ns throughput=617283ops/s
[M6-Perf] handles(): p50=100ns p95=101ns p99=101ns avg=85ns throughput=11764705ops/s
[M6-Perf] execute(open_app, 3 args): p50=600ns p95=800ns p99=12300ns avg=1025ns throughput=975609ops/s
```

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因

## 分析与备注

1. **`handles()` 性能**：O(1) Set 查找，p50=100ns，满足 ADR-016 "handles 必须无副作用且快速（O(1) 查表）" 要求
2. **`resolveTemplates` 性能**：双占位符 + 中文 URL 编码 p99=27.3us，在可接受范围内。URLEncoder.encode 是主要开销来源
3. **`executeToolCall` 本地分支**：p50=70.6us，包含 withTimeout 协程创建 + 确认门禁调用 + 本地工具执行全链路。MCP 路径性能未纳入对比（依赖网络 I/O）
4. **`isFailureResult` 性能**：前缀匹配 p50=200ns，11 个 `startsWith` 短路求值，性能可接受。已知局限 L-1（误判风险）不影响性能
5. **真机预估**：实际设备上 `executeToolCall` p50 预计 1-5ms（含 ActivityResult IPC 往返），需 Phase C 真机验证
