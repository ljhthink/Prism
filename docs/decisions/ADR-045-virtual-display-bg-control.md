# ADR-045: 手机操控后台化（虚拟屏 + 跨屏无障碍为主路线，Shizuku 手势增强在后）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-05 |
| 决策者 | 用户（调研报告第 13 节第 6~10 项批准） |
| 关联文档 | PRD docs/prd-v1-b20-voice-bg-control.md；ADR-036（手机操控）；ADR-041（任务期保活） |
| 上游调研 | docs/reports/2026-09-05-voice-call-and-background-control-research.md 第 6~11 节 |
| 风险等级 | P1（扩展 phone_control 工具协议与无障碍服务执行路径，敏感拦截存在回归风险） |

## 背景（Context）

现有手机操控（ADR-036）全部作用于主屏前台活动窗口：`currentRoot` 三级兜底、`takeScreenshot` 截主屏、`dispatchGesture` 主屏手势。LLM 执行任务期间用户无法使用手机。业界（Operator-on-Android、ShadowAuto、Canvas、掘金副屏实战）已验证「虚拟屏 + 后台自动化」形态可行，但各项目分别依赖 root / shell 提权 / Shizuku。

关键 API 事实（调研报告第 7 节核验）：`getWindowsOnAllDisplays()`（API 30+）可跨屏读 UI 树；节点动作（ACTION_CLICK/SET_TEXT/SCROLL）可作用于任意 display；`dispatchGesture` 无 displayId 参数仅默认屏；向虚拟屏注入触摸必须 shell 级权限；后台启动 Activity 持 SYSTEM_ALERT_WINDOW 豁免。

## 决策（Decision）

**路线 A（纯无障碍 + 自建 VirtualDisplay）为默认后台模式：App 自建虚拟屏（ImageReader Surface 免授权取帧）+ `setLaunchDisplayId` 启动目标 App + `getWindowsOnAllDisplays()` 跨屏读取 UI 树 + 节点动作执行 + 悬浮窗权限豁免后台启动；坐标手势在后台模式返回固定错误文案（不注入）。Shizuku 手势增强列为 M2 可选，root 路线不做。前台模式完整保留，`mode` 参数默认 foreground 保证协议向后兼容。**

一句理由：A 路线零额外提权开箱即用，覆盖 launch/节点点击/输入/滚动等多数场景；手势缺口以显式能力边界 + M2 Shizuku 增强补齐，不引入隐性失败。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| root + app_process（Operator-on-Android / ShadowAuto 模式） | 能力最全：真手势注入任意屏、UiAutomation、WMS 截图 | 用户群不可假设 root；供应链与安全审查负担 |
| Shizuku 为主路线 | 免 root 手势全量能力（Canvas 实证） | 要求用户先完成 USB/无线调试授权 Shizuku，门槛高；作为 M2 增强而非默认 |
| 容器/沙箱（VirtualApp/BlackBox 类） | 单 App 内运行目标应用 | 维护成本极高、兼容性脆弱、微信等大厂 App 对抗激烈 |
| App 内嵌自己 UI 的伪操控（Intent 自动化等） | 无需多屏 | 能力远弱于现有无障碍路线，非真实操控 |

## 后果（Consequences）

- 正面后果：API 30+ 设备开箱即用后台执行；虚拟屏 ImageReader 帧免 MediaProjection 授权与 Android 14 每 session 重授权问题；坐标零换算（虚拟屏与主屏同分辨率/DPI）；敏感拦截四层复用，安全模型不扩面。
- 负面后果 / 代价：坐标手势在后台不可用（能力边界明示）；微信等无节点 App 后台降级为 OCR 观察 + 受限操作；`getWindowsOnAllDisplays` 在部分 ROM 的暴露一致性需真机清单验证；虚拟屏常驻内存（约 2 帧缓冲 < 80MB）。
- 需要同步更新的文档或代码：`PhoneControlLocalToolExecutor`（schema + 分派 + guidance）、`PhoneControlAccessibilityService`（跨屏根节点与动作路由）、新包 `io.prism.phonecontrol.bg`、`AndroidManifest.xml`（SYSTEM_ALERT_WINDOW）、`docs/decisions/README.md` 索引、AGENTS.md 批次记录。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 后台启动 Activity 被系统拦截 | 高 | SYSTEM_ALERT_WINDOW 豁免引导 + 启动失败重试一次后返回明确错误；后台模式未授权时返回引导文案 |
| 部分 ROM 无障碍不暴露虚拟屏窗口 | 高 | `rootForDisplay` 失败即返回诊断文案，可一键切前台模式重试；真机验证清单（MIUI 优先）纳入 acceptance |
| 敏感操作绕过（后台路径未走拦截） | 高 | BG-002 单测强制：金融黑名单/敏感文本/高危三态在后台路径同样命中；guardrail 专项回归 |
| MIUI 回收虚拟屏任务 | 中 | 沿用 ADR-041 任务期 FGS 保活 + WakeLock + 完成通知兜底 |
| 工具协议变更破坏现有调用 | 中 | mode 可选默认 foreground；全量现有单测回归为验收项 |

## 参考

- 上游调研报告第 6~11 节（Operator-on-Android v2.0、ShadowAuto、Canvas、掘金副屏实战、官方后台启动限制文档等一手来源）
- https://github.com/xjyzs/Operator-on-Android ；https://github.com/ParthG2209/Canvas ；https://juejin.cn/post/7636571009870757897
