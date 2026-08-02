# 性能基线 —— US-003 API Key 加密存储

> 从 `docs/templates/performance-baseline-template.md` 复制新建。
> 依 CLAUDE.md 第十一节 4，ac-verifier 性能回退检查的依据。

| 项目 | 内容 |
|---|---|
| 基线版本 | v0.1.0 (US-003) |
| 记录日期 | 2026-08-02 |
| 测试设备 | Windows 11 笔记本 (LAPTOP-PGE8BV0D) / JVM 单元测试环境（非 Android 设备） |
| 测试方法 | JUnit 4 JVM 基准测试（`ApiKeyPerformanceBenchmark`），500 次迭代 + 50 次预热，System.nanoTime 计时 |
| Tink 版本 | 1.15.0（tink-android） |
| 加密算法 | AES-256-GCM（PredefinedAeadParameters.AES256_GCM） |
| 测试替身 | RecordingCryptoService（纯 JVM Tink AEAD，密钥在内存中生成） |
| 数据存储 | FakePreferenceDataStore（内存版 MutableStateFlow，无文件 I/O） |

## 关键指标 —— API Key 加密存储延迟

### ENCRYPT（加密操作）

| 指标 | 值 |
|---|---|
| p50 | 9.1 us (0.009 ms) |
| p95 | 39.0 us (0.039 ms) |
| p99 | 140.3 us (0.140 ms) |
| mean | 14.988 us |
| min | 8.6 us |
| max | 267.4 us |
| p99/p50 比值 | 15.4x |
| 迭代次数 | 500 |

### DECRYPT（解密操作）

| 指标 | 值 |
|---|---|
| p50 | 7.1 us (0.007 ms) |
| p95 | 24.9 us (0.025 ms) |
| p99 | 57.6 us (0.058 ms) |
| mean | 10.263 us |
| min | 3.9 us |
| max | 87.1 us |
| p99/p50 比值 | 8.1x |
| 迭代次数 | 500 |

### SAVE_API_KEY（加密 + DataStore 写入）

| 指标 | 值 |
|---|---|
| p50 | 88.2 us (0.088 ms) |
| p95 | 228.5 us (0.229 ms) |
| p99 | 399.8 us (0.400 ms) |
| mean | 122.671 us |
| min | 53.3 us |
| max | 5213.6 us (5.214 ms) |
| p99/p50 比值 | 4.5x |
| 迭代次数 | 500 |

### READ_API_KEY（DataStore 读取 + 解密）

| 指标 | 值 |
|---|---|
| p50 | 40.0 us (0.040 ms) |
| p95 | 208.8 us (0.209 ms) |
| p99 | 330.3 us (0.330 ms) |
| mean | 63.992 us |
| min | 14.0 us |
| max | 488.3 us (0.488 ms) |
| p99/p50 比值 | 8.3x |
| 迭代次数 | 500 |

## 分析

1. **ENCRYPT/DECRYPT 极快**（p50 7-9 us）：Tink AEAD AES-256-GCM 在 JVM 环境下性能优异。解密略快于加密（p50 7.1 vs 9.1 us），因解密无需生成随机 IV。
2. **SAVE_API_KEY 约 88 us**（p50）：encrypt (9 us) + DataStore edit (79 us)。FakePreferenceDataStore 的 MutableStateFlow 原子更新带来约 79 us 开销。真实 DataStore（文件 I/O）预计 p50 在 1-5 ms 范围。
3. **READ_API_KEY 约 40 us**（p50）：DataStore flow.first() (33 us) + decrypt (7 us)。Flow 读取开销占主要部分。
4. **p99 偏差**：ENCRYPT p99/p50 = 15.4x（偶发 GC + JIT 编译抖动）；SAVE_API_KEY max 5.2 ms 为单次 GC 停顿。属 JVM 基准测试正常范围。
5. **与 US-002 ObjectBox 对比**：
   - ObjectBox PUT p50 298.6 us vs SAVE_API_KEY p50 88.2 us —— 加密存储更快（FakePreferenceDataStore 内存操作 vs ObjectBox 磁盘写入）。
   - ObjectBox GET p50 1.1 us vs READ_API_KEY p50 40.0 us —— ObjectBox mmap 读取更快；READ_API_KEY 包含 Flow + 解密开销。
   - 注意：两者测试替身不同（FakePreferenceDataStore 内存 vs ObjectBox tempDir 磁盘），直接对比不完全公平。

## 环境说明

- 测试在 JVM 环境运行（RecordingCryptoService + FakePreferenceDataStore），非真实 Android 设备
- 生产环境使用 KeystoreCryptoService（Android Keystore 硬件加密），加密/解密通过 binder IPC 调用 Keystore HAL，预计延迟更高（50-300 ms 首次密钥生成，单次加解密 1-10 ms）
- 真实 DataStore（文件持久化）的 I/O 延迟未测试（使用内存替身）
- 此基线仅作为后续性能回退对比的参考起点

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因

## 后续追踪

| 追踪项 | 原因 | 计划 |
|---|---|---|
| 真实 Android Keystore 加密延迟 | JVM 测试用内存 AEAD，非硬件 Keystore | 后续 androidTest 仪器测试建立设备基线 |
| 真实 DataStore 文件 I/O 延迟 | FakePreferenceDataStore 内存替身无 I/O | 后续 androidTest 仪器测试覆盖 |
| cryptoService lazy 初始化延迟 | 首次访问触发 Keystore 主密钥生成 | 后续设备测试测量 Application.onCreate 耗时 |
