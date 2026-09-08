# 批次21：Shizuku 后台化完整化 · 产品需求文档（PRD）

> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。
> 上游：批次20 真机 RCA（docs/reports/2026-09-05-batch20-realdevice-issues-rca.md）+ 用户路线决策（AskUserQuestion：Shizuku 路线获批）。

| 项目 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-09-05 |
| 作者 | AI（依用户批准的 Shizuku 路线执行） |
| 关联文档 | ADR-046（Shizuku 选型）、ADR-045、PRD v1-b20、调研报告 |
| 风险等级 | P1（引入 Shizuku 提权能力，触及后台操控执行路径与安全边界） |

## 1. 背景

批次20 M1 实测确认：Android 10+ 的 untrusted virtual display 不允许承载第三方 App 的 Activity（`startActivity(setLaunchDisplayId)` 抛 SecurityException；`VIRTUAL_DISPLAY_FLAG_TRUSTED` 为 @SystemApi）。纯 App 无法把目标 App 启动到自建虚拟屏——**完整后台化必须提权**。用户已决策走 **Shizuku 路线**（免费开源、免 root、shell 身份，与项目 USB+adb 工作流契合）。

## 2. 目标与非目标

- **目标**：
  - Shizuku 可用时：后台模式**全能力**——`am start --display` 启动任意 App 到虚拟屏（shell 持 START_ANY_ACTIVITY，绕过 untrusted 限制）；`input --display tap/swipe/keyevent` 注入坐标手势与 back/home；`am force-stop` 干净回收。
  - Shizuku 不可用（未装/未激活/未授权）时：保持批次20 行为（节点操作 + 诚实降级文案），并提供分级引导。
- **非目标**：root 直连路线；UserService AIDL（用 `Shizuku.newProcess` 执行命令的轻量方案）；Shizuku 开机自启管理。

## 3. 用户故事与验收标准

### SZ-001: ShizukuShell 命令通道

- 作为执行器，我希望以 shell 身份执行受控命令，以便绕过 untrusted display 限制。
- 验收标准：
  - [ ] 新类 `io.prism.phonecontrol.bg.ShizukuShell`：`isInstalled()`（pingBinder）、`isGranted()`（checkSelfPermission==GRANTED）、`exec(vararg cmd): Boolean`（`Shizuku.newProcess` 执行 + 等待退出码；超时 5s）
  - [ ] **命令仅由代码拼装**（包名经敏感拦截/坐标为 int/无用户自由文本入 shell）——无命令注入面（PRD §4 安全）
  - [ ] 未安装/未激活/未授权三态可区分（供 UI 引导）
  - [ ] 单测：命令构造纯函数（launch/input/keyevent/force-stop 参数拼装）

### SZ-002: 后台 launch 双路

- 作为 LLM，我希望 chip 开启时 launch_app 在 Shizuku 就绪时真正后台启动，否则得到明确引导。
- 验收标准：
  - [ ] `VirtualDisplayManager.launchApp` 增加 Shizuku 分支：就绪 → `am start --display <id> -n <pkg>/<activity>`（组件取自 launcher intent）；未就绪 → 返回现状 untrusted 限制文案 + Shizuku 引导
  - [ ] 成功路径保留 settle + `backgroundWindowPackage` 校验；敏感拦截在分支之前同源生效

### SZ-003: 后台手势解锁

- 作为 LLM，我希望 Shizuku 就绪时后台模式支持坐标手势与 back/home。
- 验收标准：
  - [ ] 后台模式：swipe → `input --display <id> swipe`；坐标 tap（无节点锚点）→ `input --display <id> tap`；long_press → `input swipe x y x y 600`；back/home → `input keyevent --display <id> KEYCODE_BACK/KEYCODE_HOME`
  - [ ] double_tap 后台不做（shell 两次独立调用时序不可靠，PRD 实施修订：仅前台支持，后台由 LLM 改用两次 tap 或前台模式）
  - [ ] 敏感拦截（滑动起点文本/敏感目标）在 shell 注入路径**同源前置**；坐标吸附逻辑对 shell tap 同样适用
  - [ ] Shizuku 不可用时保持 `bgGestureBlocked` 受限文案（纵深防御保留）
  - [ ] 单测：手势命令拼装 + Shizuku 不可用回退文案

### SZ-004: 状态引导 UI

- 作为用户，我希望清楚知道 Shizuku 的安装/激活/授权状态与操作入口。
- 验收标准：
  - [ ] 设置页「后台操控（虚拟屏）」行 subtitle 追加 Shizuku 三态（未安装→附下载地址 https://shizuku.rikka.app/download/；已装未激活→提示 adb/无线激活；已激活未授权→点击触发 `Shizuku.requestPermission`）
  - [ ] chip 开启时若 Shizuku 未就绪：首次后台调用返回的文案包含引导（不静默失败）
  - [ ] Shizuku 授权请求结果经监听器刷新状态

### SZ-005: 回收与生命周期

- 验收标准：
  - [ ] `releaseDisplay` 在 Shizuku 就绪时用 `am force-stop` 替代 `killBackgroundProcesses`（幂等、失败不影响释放）
  - [ ] Shizuku 权限被撤销（运行中）→ `exec` 失败返回错误文案，不崩溃；下次调用重走三态检测

## 4. 非功能需求

- **安全**：shell 命令仅白名单拼装（am start/input/force-stop + 受控参数）；敏感拦截四层前置；Shizuku 权限由用户显式授予；无 QUERY_ALL_PACKAGES
- **性能**：shell 命令执行超时 5s 上限；失败不阻塞前台路径
- **兼容性**：Shizuku 要求 Android 6+；未装 Shizuku 的一切行为与批次20 完全一致（降级兼容）
- **可观测性**：exec 命令与退出码结构化日志（不含 PII）

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| Shizuku 未装/未激活的用户引导成本 | 中 | 三态分级文案 + 下载链接 + 无线调试激活说明 |
| `input --display` 各 ROM 行为差异 | 中 | Android 10+ 官方支持（调研实证）；失败文案 + 节点路径兜底 |
| shell 提权滥用面 | 高 | 白名单拼装 + 敏感拦截前置 + 用户显式授权（guardrail 专项） |
| MIUI 对 Shizuku 的激进行为 | 低 | pingBinder 探测 + 失败降级批次20 行为 |

## 6. 里程碑

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| M-SZ | Shizuku 就绪时：语音/文字派活 → 后台 launch 成功进入虚拟屏 → 坐标手势/swipe/back 均在虚拟屏生效 → 主屏全程自由使用 | P1 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联 |
|---|---|---|---|
| ShizukuShell 状态机 | 单测 | 三态判定 + 命令拼装正确 | SZ-001 |
| bg launch 双路 | 单测 + 真机 | Shizuku 就绪 → am start --display 成功；未就绪 → 引导文案 | SZ-002 |
| 手势解锁 | 单测 + 真机 | bg swipe/坐标 tap/back 经 input --display 生效 | SZ-003 |
| 引导 UI | 真机走查 | 三态文案与入口正确 | SZ-004 |
| 回收 | 单测 + 真机 | force-stop 替代 killBackgroundProcesses | SZ-005 |
| 回归 | 全量 | 2625+ 用例 0 失败 | 全部 |

## 8. 待确认事项

- [x] 路线已由用户决策（Shizuku）。无遗留开放问题。
