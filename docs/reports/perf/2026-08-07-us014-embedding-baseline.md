# US-014 端侧嵌入引擎 性能基线（初版）

> 由 ac-verifier 子 Agent 生成。依 CLAUDE.md 第十一节 4 性能回退检查。
> 模板：[performance-baseline-template.md](../../templates/performance-baseline-template.md)

| 项目 | 内容 |
|---|---|
| 基线版本 | US-014 实现（prd.json passes=false → 待主 Agent 翻 true） |
| 记录日期 | 2026-08-07 |
| 测试设备 | JVM 开发机（Windows x64，hostname LAPTOP-PGE8BV0D，非 Android 真机） |
| 测试方法 | `OnnxEmbedderPerformanceBenchmark` JUnit（`-PignorePerformanceTests=false` 启用），warmup=10 + iterations=100（模型加载 30），`System.nanoTime()` 计时 |
| 任务令牌 | TKN-US014-EMBEDDING-AC-001 |
| 代码路径 | `app/src/test/java/io/prism/embedding/OnnxEmbedderPerformanceBenchmark.kt` |

## 测试环境说明

- **运行环境**：JVM 测试用 onnxruntime（桌面原生库，x86），非 Android ARM64 真机
- **绝对延迟差异**：x86 桌面原生库延迟低于 Android ARM64 真机；本基线作为**初版回退检测基准**，后续真机数据待 US-018/019 验收时补充
- **模型**：`app/src/main/assets/models/model_qint8_arm64.onnx`（23MB INT8 量化，all-MiniLM-L6-v2）
- **线程配置**：`setInterOpNumThreads(1)` + `setIntraOpNumThreads(1)`（ADR-007 5.2 内存约束，4GB 低端机友好）

## RAG 性能

### 嵌入编码延迟

| 指标 | 基线值 | p50 | p95 | p99 | n |
|---|---|---|---|---|---|
| 短文本 embed（"hello world"） | 1 ms | 1 ms | 1 ms | 2 ms | 100 |
| 长文本 embed（~440 chars，10 句 repeat） | 12 ms | 12 ms | 13 ms | 14 ms | 100 |
| 批量 embed（4 条文本） | 5 ms | 5 ms | 7 ms | 10 ms | 100 |
| 模型加载 + 首次 embed | 121 ms | 121 ms | 140 ms | 154 ms | 30 |

### 吞吐

| 指标 | 基线值 |
|---|---|
| 批量 embed 吞吐（4 条/批） | 683.7 docs/s |

### 延迟分布特性

| 指标 | p99/p50 比 | 评估 |
|---|---|---|
| 短文本 | 1.9x | 稳定（绝对值低，毫秒级抖动放大比） |
| 长文本 | 1.2x | 稳定 |
| 批量 | 1.9x | 稳定 |
| 模型加载 | 1.3x | 稳定 |

## 资源占用

| 指标 | 基线值 | 备注 |
|---|---|---|
| 模型内存常驻 | ~23 MB | INT8 量化模型字节数组（`modelBytes`） |
| session 内存 | 未直接测量 | onnxruntime native 内存，真机待测 |
| 闲置卸载阈值 | 5 min | `checkAndUnload(maxIdleMs=300_000)` |

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因

### 后续回退检测建议

- 每次涉及 `OnnxEmbedder` / `BertWordPieceTokenizer` 的 PR，运行 `OnnxEmbedderPerformanceBenchmark` 对比本基线
- 关注 p99 延迟（短文本 >3ms / 长文本 >21ms / 模型加载 >231ms 即触发 >50% 失败门禁）
- 真机基线待 US-018/019 验收时建立（Android Profiler 测量 native 内存与真机延迟）
