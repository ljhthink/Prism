# ADR-041: v1 手机操控保活策略修订（「无障碍启用期常驻」→「任务期动态保活」）

> 落实 v1（v1.0.0）真机用户报告：不使用 Prism 时仍持续弹出「Prism 正在操控手机」常驻通知、
> 后台不间断运行导致整机卡顿。经真机 dumpsys 取证 + 网络调研，定位为批次11 F2 保活策略的
> 实现偏差，按 CLAUDE.md 第二十一节 Bug 修复闭环处置。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-23 |
| 决策者 | 主 Agent + 用户确认（120s 空闲超时；不加设置开关） |
| 关联文档 | [ADR-036](ADR-036-v1-phone-control.md)、[docs/reports/2026-08-23-keepalive-bug-debug.md](../reports/2026-08-23-keepalive-bug-debug.md) |
| 风险等级 | P2（跨模块：服务生命周期语义 + 无障碍事件订阅 + 工具执行器行为） |

## 背景（Context）

批次11 F2 为对抗 MIUI/HyperOS 激进回收（操控任务中 Prism 进程被杀 → `get_ui_state` 失效 /
SSE 流断），引入 `PhoneControlKeepAliveService` 前台服务。设计注释宣称「D2 决策：操控期间才
启用」，但实现绑定在 `onServiceConnected()`——**无障碍开启即常驻**。

真机取证（小米 HyperOS V816 / Android 16，2026-08-23）：

- `dumpsys activity services io.prism`：KeepAlive 服务 `isForeground=true` 已连续运行
  **1d8h10m**；约 1h49m 前被系统回收后经 `START_STICKY` 自动重启（restartTime 证据）；
- `dumpsys notification`：id=2001 通知 `ONGOING_EVENT|NO_CLEAR` 不间断展示；
- 进程永驻 BFGS / FOREGROUND_SERVICE 级优先级。

叠加因素：`phone_control_accessibility.xml` 订阅了全系统最高频的 `typeWindowContentChanged`
事件与 `typeWindowsChanged`，而处理器仅消费 `TYPE_WINDOW_STATE_CHANGED`——高频订阅是
无障碍服务拖慢整机的经典根因（XDA《Android's Accessibility Lag》对 LastPass 的分析；
官方缓解路径即收窄事件类型 + 加大 notificationTimeout）。

网络调研（Android 官方文档）：Android 12+ 禁止后台启动 FGS，豁免场景含「应用刚从可见状态
离开」——操控任务由用户在 Prism 界面发起后数秒内进入首个工具调用，该窗口内启动合法可行；
超窗被拒时抛 `ForegroundServiceStartNotAllowedException`，须容错降级而非中断任务。

## 决策（Decision）

### 子决策 A：保活生命周期绑定「任务期」而非「能力开关期」

- 新增 `PhoneControlSessionManager`（进程级单例状态机）：
  - `onPhoneToolInvoked()` 由 `PhoneControlLocalToolExecutor.execute()` 入口每次调用
    （覆盖全部 `phone_control__*` 工具）；首个调用启动前台服务并排定空闲检查，后续调用仅
    刷新时间戳（幂等，synchronized 保护并发）；
  - 距最近一次工具调用满 **120s**（用户确认值；LLM 多轮思考间隔通常 <60s 取双倍余量）
    自动停止前台服务；
  - `PhoneControlAccessibilityService.onDestroy` 时 `reset()`，保证下次连接后的首个调用
    重新走 start 分支。
- 移除 `onServiceConnected()` 的无条件启动（根因消除点）；`onDestroy` 的 stop 保留兜底。
- **功能完整性论证**：任务进行中（工具调用间隔 <120s）前台服务始终在线，批次10/11 修复的
  「跨 App 操控进程不被杀」目标不受影响；闲置期零占用、无常驻通知。

### 子决策 B：`START_STICKY` → `START_NOT_STICKY`

任务期服务被系统回收后不自动重启，下次工具调用由 SessionManager 重新拉起——消除真机证据
中的「杀不死」重启循环。

### 子决策 C：FGS 后台启动受限的可诊断降级

`PhoneControlKeepAliveService.start()` catch 分支区分 `ForegroundServiceStartNotAllowedException`
（Android 12+ 后台启动限制）：降级为无保活继续任务（等同批次11 之前行为），日志单独标注
便于真机定位；其他异常维持原通用文案。

### 子决策 D：无障碍事件订阅瘦身（卡顿根治点）

- `accessibilityEventTypes` 收窄为 `typeWindowStateChanged`（处理器唯一消费的类型；
  UI 树读取走 `rootInActiveWindow` 实时查询，不依赖事件流，功能零损失）；
- `notificationTimeout` 100→300ms（缓存维护无需实时性）。

## 结果（Consequences）

- 新增 `PhoneControlSessionManagerTest` 6 用例（首启幂等 / 空闲停止 / 刷新重排 / 未连接跳过 /
  跨会话重启 / 并发单次启动），全部通过。
- 全量回归与 guardrail/ac-verifier 闭环见对应报告。
- 用户侧可感知变化：开启无障碍但未发起操控任务时不再出现常驻通知；任务期间通知照常出现
  （语义正确：「执行 LLM 操控手机任务中」）；任务静置约 2 分钟后自动消失。

## 已知限制（后续迭代）

- 极端场景：首个工具调用前用户已切离 Prism 且超过系统豁免窗口，FGS 启动被 Android 12+
  拒绝 → 该次任务降级为无保活运行（进程仍可能被 MIUI 回收）。概率低（LLM 首轮响应通常在
  可见窗口内），后续可评估「发送消息时预启动」策略。
- **429 长退避保活真空**（guardrail M-2）：kimi/glm 限流退避链最长约 180s（批次11 U2 已知
  限制）> 空闲阈值 120s，期间无 `phone_control__*` 调用 → 保活提前停止，恰在需要防护的
  场景出现真空；下一次工具调用会自愈拉起，但被杀若发生在窗口内任务即中断。本期接受该
  权衡（用户确认 120s 阈值），后续迭代评估「SkillExecutor 工具回路每轮开始追加会话刷新」
  （guardrail 建议 (a)）。
- 空闲阈值 120s 内服务仍驻留（通知仍在），属预期行为；如需更激进可在设置页暴露（本期不加，
  KISS）。
- 提示注入资源滥用面（guardrail L-3）：恶意页面可诱导高频 `wait` 类调用维持会话存活至上限，
  已有轮次熔断封顶、无数据外发增量，记录接受。
- 陈旧空闲检查链卫生问题（guardrail L-1）：reset/停止不 removeCallbacks，快速断开-重连循环
  残留的检查链已论证自终止且不会误停活跃新会话，后续持有 handler 引用清理。
