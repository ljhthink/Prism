# 性能基线：QQ 定时 / 帧健康整改（TKN-QQ-FIX-001，2026-10-25 建立初版）

> 由 `ac-verifier` 在验收阶段建立。项目此前**无**本组热路径的性能基线，
> 故本文为**初版基线**（无"改动前"对比数据，不能声称"无回退"，只能作为后续对比锚点）。

## 1. 覆盖范围（本次改动涉及的热路径）

| 路径 | 为什么是热路径 | 测量方式 |
| --- | --- | --- |
| `QqTimeOfDay.parse/format` | 设置页每次输入、LLM 工具每次调用、调度每次重算都会走 | JVM 微基准（`QqTimeOfDayPerfBaselineTest`） |
| `QqTimeOfDay.isValidWindow` | 每次计划/窗口校验 | 同上 |
| `FrameEncodePlan.steps` | 每次隐藏面截图编码前生成一次（新抽出的纯函数） | 同上 |
| `VirtualDisplayManager.latestFrameWithMeta` | 每次截图多一次 Binder `hiddenFrameInfo` 往返 | 真机连续取帧（`frame` 广播，含 adb 往返开销） |
| `ShizukuShell.encodeHiddenFrame` | JPEG 编码 + 降级循环（在 shell 特权进程里） | 由取帧整体耗时间接体现 |
| 连续取帧内存 | 位图回收是否正确（G-r3-02） | `dumpsys meminfo io.prism` PSS 前后差值 |

环境：Windows 11 / JDK（Gradle daemon 4 GiB）/ 真机 23113RKC6C（Android 16）+ Shizuku 用户服务（shell uid）。

## 2. JVM 微基准结果（2026-10-25 21:19，`--no-build-cache`）

命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.prism.qq.QqTimeOfDayPerfBaselineTest" --no-build-cache
```

| 被测函数 | n | p50 | p95 | p99 | mean |
| --- | --- | --- | --- | --- | --- |
| `QqTimeOfDay.parse` | 20000 | 500 ns | 600 ns | 800 ns | 539.2 ns |
| `QqTimeOfDay.isValidWindow` | 20000 | 0 ns | 100 ns | 100 ns | — |
| `FrameEncodePlan.steps`（720×1560，JPEG，4 步） | 5000 | 100 ns | 100 ns | 200 ns | — |

**判定**：三者均在**纳秒级**，相对一次 Binder 往返（毫秒级）可忽略。
`parse` 的正则 `^(\\d{1,2})(?::(\\d{1,2}))?$` 无回溯风险（全定长/可选组），
不存在"输入放大即指数退化"的攻击面（补充用例 `QqFixAcceptanceSupplementTest` 覆盖了
`-1` / `7:00:00` / 中文 / 三位数小时等非法输入）。

## 3. 真机取帧耗时（含 adb 往返，作趋势锚点）

条件：隐藏执行面 `displayId=16`（720×1560），QQ 已渲染（user=999），连续 10 次 `frame` 广播。

| 指标 | 值 |
| --- | --- |
| 采样（ms） | 119, 120, 115, 125, 130, 111, 116, 135, 141, 131 |
| p50 | 120 ms |
| p95 / max | 141 ms |
| min | 111 ms |

说明：该值 = `adb shell am broadcast` 进程启动 + 广播派发 + `latestFrameWithMeta` +
`hiddenFrameInfo` Binder 往返 + PNG/JPEG 回传 + 日志落盘。**其中 adb 侧约占 60–90ms**
（同条件下纯广播 `status` 亦在 60–90ms 量级），故设备侧取帧实测量级为 **30–60ms**。
首次取帧在空/重建执行面下为 `frame=null`（无帧，非超时）。

**对照**：改动前 `latestFrameWithMeta` 只有一次 `capture` 往返；本版多一次
`hiddenFrameInfo`（拿 `capturedAtMs`）。以 30–60ms 总量看，多出的单次往返为**数十毫秒级**，
在"一次截图 + OCR + LLM"（秒级）链路中不构成瓶颈；但对**Watch 2fps 预览**属于新增开销，
若后续 Watch 出现掉帧，应先测这条往返（登记为观察项，非本次缺陷）。

## 4. 连续取帧内存（位图回收有效性）

| 时点 | TOTAL PSS | TOTAL RSS |
| --- | --- | --- |
| 取帧前（执行面已就绪） | 378 500 KB | 234 400 KB |
| 连续 20 次 `frame` 后（+2s 静置） | 381 716 KB | 237 860 KB |
| 差值 | **+3 216 KB（+0.85%）** | +3 460 KB |

**判定**：20 次取帧（每次 720×1560 RGBA ≈ 4.5MB，累计约 90MB 位图流量）后 PSS 仅 +3.2MB，
无单调累积，与 `FrameBitmapRecycler.recycleAll` 的 `IdentityHashMap` 去重回收一致；
未观察到"每帧泄漏一张位图"（若泄漏，20 次应 ≥90MB）。

## 5. 计划时刻偏差（US-3115 AC-5，≤60s）

| 场景 | 计划时刻 | 实际执行（日志时间） | 偏差 |
| --- | --- | --- | --- |
| 亮屏、ONEBOT 通道 | 21:14:43.150 | 21:14:43.204 唤醒 → 21:14:45.761 派发成功 | 唤醒 0.05s；端到端 2.6s |
| 熄屏、ACCESSIBILITY 通道 | 21:16:28.029 | 21:16:28.107 唤醒 → 21:16:28.773 点亮屏幕 → 21:16:40.210 结果 | 唤醒 0.08s；端到端 12.2s |

**判定**：唤醒误差 <0.1s，端到端远低于 60s 门禁。

## 6. 复现方式（后续对比用）

```powershell
# JVM 微基准
.\gradlew.bat :app:testDebugUnitTest --tests "io.prism.qq.QqTimeOfDayPerfBaselineTest" --no-build-cache

# 真机取帧（需先 create + launch user=999，否则 frame=null）
adb shell am broadcast -p io.prism -a io.prism.DEBUG_HIDDEN_VD --es action create
adb shell am broadcast -p io.prism -a io.prism.DEBUG_HIDDEN_VD --es action launch --es pkg com.tencent.mobileqq --ei user 999
1..10 | % { Measure-Command { adb shell am broadcast -p io.prism -a io.prism.DEBUG_HIDDEN_VD --es action frame } }
adb shell dumpsys meminfo io.prism | Select-String 'TOTAL PSS'
```

## 7. 限制

- **无改动前基线**：无法判断相对 r1/r2 的增减，只能作为后续锚点。
- 取帧耗时含 adb 开销，非纯设备侧；设备侧数值为估算区间（30–60ms），未做 systrace。
- 未测 `awaitFreshFrame` 最坏 1.5s 等待路径（需构造"轻推后仍无新帧"的场景）；
  该路径为**失败兜底**，不影响正常路径吞吐。
