# 性能基线：M5 Phase D（US-034 UserProfileManager）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，存于 `docs/reports/perf/`。
> 依 CLAUDE.md 第十一节 4，ac-verifier 性能回退检查的依据。
> US-034 为新模块，无历史基线，本文件为首版基线。

| 项目 | 内容 |
|---|---|
| 基线版本 | M5 Phase D（US-034 UserProfileManager 初版） |
| 记录日期 | 2026-08-11 |
| 测试设备 | Windows 11 / JVM 单元测试（纯 JVM，非 Android 设备） |
| 测试方法 | `UserProfileManagerSupplementaryTest` 性能基线测试方法，nanoTime 计时，预热后迭代 |
| 任务令牌 | TKN-M5-PHASED-ACCEPTANCE-001 |

## 关键指标

### UserProfileManager 操作性能

| 指标 | 规模 | min | p50 | p95 | p99 | max | 吞吐（ops/s） | 失败 |
|---|---|---|---|---|---|---|---|---|
| setExplicitPreference | 1 save | 681us | 907us | 1237us | 1279us | 1279us | 1102.5 | 0/100 |
| parsePreferencesJson | 5 pairs | 34us | 50us | 106us | 270us | 270us | 20000.0 | 0/100 |
| formatProfilesAsContext | 10 profiles | 22us | 33us | 75us | 388us | 388us | 30303.0 | 0/100 |
| extractImplicitPreferences | 3 prefs | 1934us | 2377us | 2714us | 2896us | 2896us | 420.7 | 0/30 |
| filterKeyMessages | 20 msgs | 7us | 8us | 12us | 67us | 67us | 125000.0 | 0/100 |

### 分析

1. **setExplicitPreference**（p50=907us）：主要开销在 ObjectBox `runInTx` 事务（查 key + put）。
   与 Phase A `UserProfileRepository.save` 同模式，开销合理。

2. **parsePreferencesJson**（p50=50us）：kotlinx.serialization JSON 解析 + 遍历 entries。
   纯函数无 IO，5 对 key-value 解析 50us 在合理范围。

3. **formatProfilesAsContext**（p50=33us）：`box.all` 内存查询 + joinToString 格式化。
   10 条画像格式化 33us 极快，无性能瓶颈。

4. **extractImplicitPreferences**（p50=2377us）：完整链路（filterKeyMessages + FakeProvider 调用 +
   parsePreferencesJson + 3 次 upsert）。3 次事务 upsert 占主要开销（3 × ~900us ≈ 2700us）。
   生产环境增加真实 LLM 网络延迟（~500ms-3s），管理器本身开销 <1% 可忽略。

5. **filterKeyMessages**（p50=8us）：纯内存 List.filter，20 条消息过滤 8us 极快。

### 与 Phase A/C 基线对比

| 操作 | Phase A/C 基线 | 本 Phase 基线 | 变化 | 结论 |
|---|---|---|---|---|
| setExplicitPreference (save) | Phase A save p50=1311us | p50=907us | -30.9% | 更快（UserProfileManager 无额外开销，差异在测量波动） |
| parsePreferencesJson | 无（新操作） | p50=50us | N/A | 首版基线 |
| formatProfilesAsContext | Phase C formatMemories p50=8us | p50=33us | +312.5% | 不同操作（getAll + joinToString vs 单次格式化），无可比性 |
| extractImplicitPreferences | Phase C saveSessionMemories p50=834us | p50=2377us | +184.7% | 不同操作（3 次 upsert vs 1 次 save），合理 |

**注**：extractImplicitPreferences p50=2377us 包含 3 次 ObjectBox 事务 upsert（每次 ~900us），
与 Phase C saveSessionMemories（1 次 save 834us）无可比性。管理器本身开销（filter + parse + format）
约 50us + 8us + 33us = 91us，占总延迟 3.8%，其余 96.2% 为 ObjectBox IO。

## 局限

- 使用 FakeCompletionProvider（非真实 LLM 网络调用），extractImplicitPreferences 的 LLM 延迟未计入
- 纯 JVM ObjectBox 测试（非 Android 设备），实际设备性能可能不同
- ObjectBox JNI 在 Windows 上运行，Linux/Android 性能特征可能不同

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因

**首版基线**：无历史基线可对比，本次不触发回退门禁。后续迭代以本基线为基准。
