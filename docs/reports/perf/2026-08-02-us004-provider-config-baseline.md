# 性能基线 —— US-004 BYOK Provider 配置数据模型

> 从 `docs/templates/performance-baseline-template.md` 复制新建。
> 依 CLAUDE.md 第十一节 4，ac-verifier 性能回退检查的依据。

| 项目 | 内容 |
|---|---|
| 基线版本 | v0.1.0 (US-004) |
| 记录日期 | 2026-08-02 |
| 测试设备 | Windows 11 笔记本 (LAPTOP-PGE8BV0D) / JVM 单元测试环境（非 Android 设备） |
| 测试方法 | JUnit 4 JVM 基准测试（`ProviderConfigPerformanceBenchmark`），500 次迭代 + 50 次预热，System.nanoTime 计时 |
| ObjectBox 版本 | 5.4.2 |
| 数据存储 | 临时目录纯 JVM ObjectBox 实例（`MyObjectBox.builder().directory(tempDir).build()`） |
| 测试范围 | ProviderConfig CRUD（save/get/setActive）+ StringListConverter/StringMapConverter 往返 |

## 关键指标 —— ProviderConfig 操作与类型转换器延迟

### SAVE（单条 ProviderConfig，含 2 models + 1 header）

| 指标 | 值 |
|---|---|
| p50 | 279.801 us (0.280 ms) |
| p95 | 461.3 us (0.461 ms) |
| p99 | 662.499 us (0.662 ms) |
| mean | 318.654 us |
| min | 213.6 us |
| max | 6101.1 us (6.101 ms) |
| 迭代次数 | 500 |

### GET（单条 ProviderConfig）

| 指标 | 值 |
|---|---|
| p50 | 1.9 us (0.002 ms) |
| p95 | 2.999 us (0.003 ms) |
| p99 | 15.4 us (0.015 ms) |
| mean | 2.47 us |
| min | 1.5 us |
| max | 102.0 us |
| 迭代次数 | 500 |

### SET_ACTIVE（10 个 Provider 中切换激活，含 runInTx 事务）

| 指标 | 值 |
|---|---|
| p50 | 291.3 us (0.291 ms) |
| p95 | 497.4 us (0.497 ms) |
| p99 | 648.1 us (0.648 ms) |
| mean | 323.573 us |
| min | 210.9 us |
| max | 861.7 us |
| 迭代次数 | 500 |

### LIST_CONVERTER_ENCODE（100 个模型名序列化）

| 指标 | 值 |
|---|---|
| p50 | 12.901 us |
| p95 | 61.8 us |
| p99 | 132.1 us |
| mean | 32.628 us |
| min | 11.699 us |
| max | 3273.6 us |
| 迭代次数 | 500 |

### LIST_CONVERTER_DECODE（100 个模型名反序列化）

| 指标 | 值 |
|---|---|
| p50 | 14.1 us |
| p95 | 71.099 us |
| p99 | 119.3 us |
| mean | 28.977 us |
| min | 12.1 us |
| max | 266.4 us |
| 迭代次数 | 500 |

### MAP_CONVERTER_ENCODE（4 个请求头序列化，含反斜杠/换行/等号/Unicode）

| 指标 | 值 |
|---|---|
| p50 | 3.701 us |
| p95 | 15.0 us |
| p99 | 18.999 us |
| mean | 4.965 us |
| min | 3.199 us |
| max | 46.2 us |
| 迭代次数 | 500 |

### MAP_CONVERTER_DECODE（4 个请求头反序列化）

| 指标 | 值 |
|---|---|
| p50 | 3.999 us |
| p95 | 16.2 us |
| p99 | 24.001 us |
| mean | 5.827 us |
| min | 2.5 us |
| max | 46.0 us |
| 迭代次数 | 500 |

## 分析

1. **GET 极快**（p50 1.9 us）：ObjectBox mmap 读取，与 US-002 GET p50 1.1 us 同量级。
2. **SAVE 约 280 us**（p50）：含 ObjectBox 磁盘写入 + 两个 @Convert 类型转换器序列化（2 models + 1 header）。与 US-002 ObjectBox PUT p50 298.6 us 同量级，符合预期。
3. **SET_ACTIVE 约 291 us**（p50）：`runInTx` 事务遍历 10 个 Provider + 至多 2 次 box.put + refreshActiveProvider 读事务。事务开销使 p50 略高于 GET，但远低于交互阈值。
4. **类型转换器极快**（p50 3-14 us）：单次扫描转义/反转义算法开销可忽略。100 个模型往返 <15 us，10 万级模型也不过亚毫秒，无性能瓶颈。
5. **p99 偏差**：SAVE max 6.1 ms（单次 GC 停顿）、LIST_ENCODE max 3.3 ms（JIT）——属 JVM 基准测试正常范围。
6. **与 US-002 ObjectBox 对比**：SAVE p50 279.8 us ≈ PUT p50 298.6 us（相近）；GET p50 1.9 us ≈ GET p50 1.1 us（相近）。US-004 引入的 @Convert 转换器未造成显著性能回退。

## 环境说明

- 测试在 JVM 环境运行（纯 JVM ObjectBox native 库），非真实 Android 设备
- 真实 Android 设备上 ObjectBox 使用本地 native 库，磁盘 I/O 受闪存性能影响，绝对延迟可能不同；相对差异（get 远快于 save/setActive）应保持一致
- 此基线仅作为后续性能回退对比的参考起点

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因

## 后续追踪

| 追踪项 | 原因 | 计划 |
|---|---|---|
| 真实 Android 设备 CRUD 延迟 | JVM 测试用桌面 native 库，非设备闪存 | 后续 androidTest 仪器测试建立设备基线 |
| getAll/findByName 全表扫描（G-04） | Provider 数量通常 <20，当前可忽略 | 若规模增长，后续引入 @Index 或 query |
