# 性能基线：角色体验保真修复（ADR-049 / TASK-20260912-001）

> 从 `docs/templates/performance-baseline-template.md` 复制新建，存于 `docs/reports/perf/`。
> 依 CLAUDE.md 第十一节 4，ac-verifier 性能回退检查的依据。
> **本文件为角色包装配链三个关键纯函数的首版基线**（`docs/reports/perf/` 既有 12 份基线均不覆盖）。

| 项目 | 内容 |
|---|---|
| 基线版本 | 角色体验保真修复 v6（ADR-049 D3/D4/D6） |
| 记录日期 | 2026-09-12 |
| 测试设备 | Windows 11 / JVM 单元测试（纯 JVM，非 Android 设备；JDK 17.0.17） |
| 测试方法 | `AcVerifierPerfBaselineTest`，`System.nanoTime` 计时，iterations=2000 / warmup=300 |
| 任务令牌 | TKN-ROLEPACK6-ACCEPTANCE-001 |
| 输入数据 | **真实资产** `cyrene.rolepack` v6.0.0：persona 14,963 字 + 通话段 1,620 字 + worldbook 40 条 / 20,750 字 / 3 恒定 |

## 关键指标

> 单位：微秒（us）。"规模"列给出该次调用的实际输入规模。

| 指标 | 规模 | min | p50 | p95 | p99 | max | 吞吐（ops/s） |
|---|---|---|---|---|---|---|---|
| `RolePackWorldbook.select`（空扫描 → 仅恒定） | 40 条 / 20,750 字 | 0.9 | 1.0 | 1.1 | 1.3 | 14.7 | 1,007,049 |
| `RolePackWorldbook.select`（高命中扫描） | 40 条；scan 281 字；212 keys | 1078.1 | 1177.7 | 2252.3 | 2283.4 | 2853.9 | 673 |
| `RolePackWorldbook.toSystemPromptSection`（隔离：仅渲染） | 入选 8 条 | 2.3 | 4.3 | 8.9 | 15.6 | 1853.5 | 168,829 |
| `toPersonaSystemPromptSection(voiceCall=false)` | persona 14,963 字 | 8.0 | 15.7 | 51.6 | 75.3 | 2016.3 | 41,879 |
| `toPersonaSystemPromptSection(voiceCall=true)` | persona + 通话段 1,620 字 | 8.2 | 12.9 | 21.9 | 37.0 | 1923.9 | 61,449 |
| `mergeSystemPrompt`（出厂态，无角色包） | DEFAULT_PERSONA + 6 技能 | 1.1 | 2.7 | 6.0 | 7.7 | 150.4 | 293,496 |
| `mergeSystemPrompt`（角色全层，隔离：预取层） | persona + worldbook + tail | 10.0 | 16.3 | 32.7 | 64.2 | 1936.6 | 48,233 |
| **单轮完整装配链（worldbook→persona→merge）** | 端到端纯函数链 | 1110.1 | **2234.4** | 2315.3 | 2455.0 | 3907.9 | 543 |

### systemPrompt 体积

| 状态 | 字符数 |
|---|---|
| 出厂最小态（仅 `DEFAULT_PERSONA`） | 408 |
| 出厂全层态（RAG + L1 + L2 + L3 + 用户规则 + 手机 + 热榜 + TODO + 6 技能） | 651 |
| **角色全层态（本轮扩展后）** | **20,665** |
| ├ 其中 persona（14,963 + 通话段 1,620，v5 已在注入） | 16,583 |
| ├ 其中 worldbook 区块（入选 8 条） | 3,932（预算上限 6,000） |
| └ 其中 `ROLE_STYLE_TAIL` | 106 |
| **本轮净新增层（worldbook + styleTail）** | **4,038（占角色态 +24.3%）** |

> **口径校正（回答 AC10 的"persona 11.5k→15.0k"）**：实测 `persona.systemPrompt` = 14,963 字、
> `callSystemPrompt` = 1,620 字。v5 时通话段**并进** systemPrompt，故 v5 注入量与 v6 的 persona
> 总量**同为 16,583 字**——本轮 persona 体量**没有增长**，只是按轮次拆成两段。真正的体量增长
> 只有 worldbook + styleTail 这 4,038 字。另：AC10 提到的"persona 11.5k→15.0k"若指 systemPrompt
> 单段，则 11,500 应为 v5 中**去掉通话段**后的近似值（16,583 − 1,620 ≈ 14,963，与 v5 原始
> systemPrompt 单段值存在版本差异）——以本表实测为准。

## 分析

1. **空扫描 select = 1.0us**：`matches()` 对空 `scanText` 首行短路（`if (scanText.isEmpty()) return false`），
   恒定条目路径不做任何字符串匹配 → **未命中关键词的轮次几乎零开销**（这是常态）。
2. **高命中 select = 1,178us**：开销**不在** `select` 的算法，而在 `String.contains(other, ignoreCase=true)`
   这一 JDK 原语本身。**已证伪**：隔离测量同一原语（212 个 key × 281 字扫描文本、不做条目遍历与分配）
   p50 = **1,353us**，与 `select` 的 1,178us **同量级（比值 0.87×）** → `select` 无隐藏放大，
   成本 = `keys 数 × contains`。属可接受开销（相对网络 LLM 首字延迟 500ms–3s 为 0.24%）。
3. **单轮完整链 p50 = 2,234us（2.2ms）**：即角色轮次每轮**纯函数装配总成本**。
   相对真实首字延迟（500ms–3s）占比 **0.07%–0.45%** → **不构成首字延迟回退**。
   p99 = 2,455us，尾部稳定（无长尾抖动）。
4. **`mergeSystemPrompt` 角色全层 = 16.3us**：相比出厂态 2.7us 增加 13.6us，纯为字符串拼接
   体量增加（651 → 20,665 字）所致，绝对量可忽略。
5. **`toPersonaSystemPromptSection` = 16us**：1.6 万字 `trim()` + `buildString` 拼接，量级合理。
   `max` 出现 2ms 级离群值（GC/JIT 抖动），p50/p95 稳定。

### 测量方法学修正（本轮自查，需登记）

首轮测量得到 `select` 高命中 **794us→1,068us** 与 `mergeSystemPrompt` 角色全层 **1,472us** 的虚高值。
根因是**测试夹具**使用 Kotlin `+=` 拼接扫描文本：`String.plus` 逐次分配新串，在长串上退化为
O(n²)，把夹具开销计入了被测函数；且该被污染的 `hitScan` 又被两条用例复用。改用
`buildString { append(...) }` 预构造后，`mergeSystemPrompt` 由 1,472us 降至 **16.3us（−98.9%）**，
证明原值**全部是夹具伪影**。本表数据取自修正后的测量。

## 回退门禁

- 性能下降 >50%：标记失败
- 性能下降 >20%：标记警告，PR 需说明原因

**本轮判定**：三个函数**均无既有基线** → 无从做"下降百分比"对比；以绝对量级门禁替代
（已固化在 `AcVerifierPerfBaselineTest` 断言中）：
① 单轮完整链 p95 < 5ms；② 高命中 `select` p95 < 5ms；③ 仅渲染 p95 < 200us；
④ `select.p50 < 20 × 隔离原语.p50 + 200us`（防未识别的算法放大）；⑤ worldbook 区块 ≤ `MAX_CHARS` + 400。
**结论：通过，无回退**；首版基线数值如上表，供后续轮次对比。

## 局限

- 纯 JVM（非 Android 设备）：未计入 ART 上的字符串原语差异与低端机 CPU 差异；真机首字延迟
  另见 `docs/reports/2026-09-12-rolepack-v6-realdevice-checklist.md`。
- 未测量网络与 LLM 推理耗时（本轮变更不触及该链路）。
- 端到端链覆盖"纯函数装配"，**不含** `RolePackLoader.effectiveRolePack()` 的 116 KB JSON
  解析与文件 IO（属 IO 边界，见 guardrail R2 的 P3-C 未处置项）。
