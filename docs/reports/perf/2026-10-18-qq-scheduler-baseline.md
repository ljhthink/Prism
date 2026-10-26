# QQ 随机定时主动消息调度子系统 · 性能基线（初版）

| 项目 | 内容 |
| --- | --- |
| 报告路径 | docs/reports/perf/2026-10-18-qq-scheduler-baseline.md |
| 执行 Agent | ac-verifier |
| 任务令牌 | 未分配（主 Agent 未提供 TKN） |
| 日期 | 2026-10-18 |
| 关联 | ADR-063 · PRD `docs/prd-qq-burner-account.md`（US-3108） · 验收报告 `docs/reports/2026-10-18-qq-scheduler-acceptance.md` |
| 基线类型 | **初版**（本批首次建立；`docs/reports/perf/` 下此前无 QQ 相关基线） |

> 本文件位于 `docs/reports/perf/`（该子目录提交远程；`docs/reports/*.md` 一次性报告不推送，子目录不受影响）。

---

## 1. 测量对象与环境

| 对象 | 说明 |
| --- | --- |
| `QqSchedulePlanner.nextTriggerAt` | 纯 JVM O(1)：时区换算（`Instant.atZone`）+ 窗口计算 + 一次随机采样 |
| `QqThrottle.decide` | 纯 JVM O(1)：熔断/间隔/计数 三类短路判定 + `todayKey` |

- 环境：Windows（powershell5），Gradle 8.13，JVM 测试运行时（`testDebugUnitTest`）。
- 方法：JUnit 临时探针（`QqPerfProbeTest`），预热 50,000 次后，按 **每批 1000 次** 计时取批均值，
  对批均值序列取分位；采集后探针文件已删除（不入交付集）。
- 复现方式：临时探针同法（每批 1000 次，planner 3000 批 / decide 5000 批）。

## 2. 实测结果

| 指标 | 迭代数 | p50 | p95 | 备注 |
| --- | --- | --- | --- | --- |
| `nextTriggerAt` 单次 | 3,000,000 | **85 ns** | **168 ns** | 含时区换算与分配，量级为百纳秒 |
| `decide` 单次 | 5,000,000 | **48 ns** | **75 ns** | 稳态短路判定，量级为几十纳秒 |

## 3. 门禁判定

- PRD §4 非功能目标为「单次后台发送端到端 ≤10 s」「触发偏差 ≤±5 min」，均为**链路级/真机级**指标，
  不适用于本次纯函数计时。
- 本次两函数为 **O(1) 纯逻辑**，p95 均 < 0.2 µs，**不构成任何性能风险**；心跳循环每 ≤15 min 才
  计算一次、节流判定每次派发一次，合计开销可忽略。
- 结论：**通过（初版基线建立）**，无历史基线的回退比对项。

## 4. 只能真机测量、当前未验证的项（显式登记）

| 指标 | 目标 | 当前状态 |
| --- | --- | --- |
| 定时触发偏差 | ≤ ±5 min | **未验证**（须真机 + Doze/ROM PoC，见 ADR-063 M2 闸门） |
| 单次后台发送端到端时延 | ≤ 10 s | **未验证**（发送链路 `QqProactiveGateway.sender` 未装配，待 M0） |
| 前台服务常驻电量/通知体验 | 无明显异常 | **未验证**（须真机 batterystats） |
| 闹钟唤醒被杀进程后能否前台化 | 成功前台化 | **未验证**（Android 12+/厂商 ROM；ADR-063 明列推测项） |
