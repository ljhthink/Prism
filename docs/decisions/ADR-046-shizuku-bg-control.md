# ADR-046: Shizuku 后台化完整化选型（shell 身份 trusted 能力，替代纯 App untrusted 限制）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-05 |
| 决策者 | 用户（AskUserQuestion 路线决策，三选一选 Shizuku；费用确认免费后拍板） |
| 关联文档 | PRD docs/prd-v1-b21-shizuku-bg-control.md；ADR-045（虚拟屏后台化） |
| 上游调研 | docs/reports/2026-09-05-batch20-realdevice-issues-rca.md（R2 章节：untrusted display 硬限制取证） |
| 风险等级 | P1（引入 shell 提权能力，需严格白名单与敏感拦截前置） |

## 背景（Context）

批次20 M1 真机实证：Android 10+ 的 untrusted virtual display 不允许承载第三方 App 的 Activity（`startActivity(setLaunchDisplayId)` 抛 SecurityException；`VIRTUAL_DISPLAY_FLAG_TRUSTED` 为 @SystemApi 不可加；官方预检 API `isActivityStartAllowedOnDisplay` 为 @hide 不可编译）。纯 App 的"后台启动其他 App 到自建虚拟屏"被平台硬限制封死——业界三个开源实现（Operator-on-Android=root、ShadowAuto=shell、Canvas=Shizuku）全部依赖提权，印证此为唯一路径族。

用户在三个选项（Shizuku / Android 14+ VDM / 接受降级）中确认**两条路线均免费**后选择 **Shizuku**。

## 决策（Decision）

**引入 Shizuku（`dev.rikka.shizuku:api` + `provider`，免费开源 Apache-2.0）作为后台化完整能力的提权通道：经 `Shizuku.bindUserService` 绑定自定义 `ShizukuUserService`（shell 身份进程，AIDL `IShizukuUserService.exec(String[])`）执行受控白名单命令——`am start --display <id>`（shell 持 START_ANY_ACTIVITY，绕过 untrusted 限制）、`input --display <id> tap/swipe/keyevent`（后台手势全量解锁）、`am force-stop`（干净回收）；命令仅由代码拼装（无用户自由文本入 shell），敏感拦截四层前置不变；Shizuku 未就绪时完整回退批次20 行为（节点操作 + 诚实降级）。**

**实施修正**：原计划用 `Shizuku.newProcess` 轻量方案，实测 13.1.5 中该方法为 private 不可访问——改用官方推荐的 UserService AIDL 绑定方案（能力等价，实现多一个 AIDL 接口 + 绑定管理）。

一句理由：免费、免 root、社区成熟（Canvas/Operator 同款）、与项目既有 USB+adb 工作流天然契合，且降级兼容保证无 Shizuku 用户零损失。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| Android 14+ VirtualDeviceManager | 官方公开 API、免提权 | 实现重（AssociationRequest 设备关联 + 输入/传感器桥接自研）；仅覆盖 API 34+ 设备；本轮用户设备验证成本高 |
| 接受降级（bg 失败自动前台续行） | 零投入 | 无法满足用户"真正后台化"的核心诉求 |
| root 直连 | 能力最强 | 用户群不可假设 root |
| UserService AIDL（Shizuku 高级用法） | 进程常驻、能力更细 | 实现复杂度高于 `newProcess` 命令方案；本轮诉求仅三条命令 |

## 后果（Consequences）

- 正面后果：后台模式从"仅节点操作"升级为**全能力**（launch/坐标手势/swipe/long_press/double_tap/back/home 全部可用）；`am force-stop` 回收比 killBackgroundProcesses 干净；Shizuku 未就绪用户零损失（完整回退）。
- 负面后果 / 代价：新增第三方依赖（shizuku-api/provider）；用户需安装 Shizuku 并激活（USB 或无线调试）；shell 提权能力需以白名单拼装 + 敏感拦截前置严格约束（guardrail 专项）。
- 需要同步更新的文档或代码：`app/build.gradle.kts`、`AndroidManifest.xml`（ShizukuProvider）、新类 `ShizukuShell`、`VirtualDisplayManager.launchApp` 双路、`PhoneControlLocalToolExecutor` 手势分支、设置页/引导文案、`docs/decisions/README.md`、AGENTS.md。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| shell 提权滥用（注入面） | 高 | 命令仅代码拼装（包名经敏感拦截+坐标为 int，无自由文本）；用户显式授权；guardrail 专项审查 |
| Shizuku 未激活的用户流失 | 中 | 三态分级引导（未装→下载链接；未激活→无线调试说明；未授权→requestPermission） |
| `input --display` ROM 差异 | 中 | 失败返回错误文案 + 节点路径兜底；不做静默重试 |
| Shizuku 运行中权限被撤销 | 低 | exec 失败返回错误文案 + 三态重检测，不崩溃 |

> **勘误（2026-09-07 修复轮八）**：本文所述 `input --display` 选项有误——AOSP `input` 官方用法仅 **`-d DISPLAY_ID`** 短选项（见 InputShellCommand onShowUsage），传 `--display` 时 ROM 打印 "Unknown command: --display" 且 exit=0（注入从未执行却报成功）。已全部改用 `-d`，回归锁见 ShizukuShellTest（am start --display 不受影响，`am` 语法不同）。

## 参考

- https://github.com/RikkaApps/Shizuku-API（Apache-2.0；ShizukuProvider/Shizuku.newProcess/requestPermission 流程）
- https://github.com/RikkaApps/Shizuku（下载与激活文档）
- 上游 RCA：批次20 真机修复轮 R2 章节（untrusted display 限制双实证）
