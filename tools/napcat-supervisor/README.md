# NapCat 持久化监管（Prism 配套工具）

> 目的：**只要这台电脑不关机，QQ 小号就一直在线与 Prism 对话**。
> 本工具只操作本机的 NapCat 容器与其 WebUI 配置，与 Prism App 无代码耦合；不修改 QQ 客户端，不内置/分发第三方协议端。

## 它解决什么

真机与容器日志取证（2026-10-26）：

| 现象 | 证据 | 后果 |
| --- | --- | --- |
| 自动登录账号为空 | `GetQuickLoginQQ` 返回 `""` | 掉线/重启后必须扫码 |
| 账号态每 ~2 小时失效 | `Login Error,ErrType:1 ErrCode:3`（11:20/13:20/15:20/17:20） | 长时间不可用 |
| Docker 不自启 | `com.docker.service` = Manual/Stopped | 重启电脑即失联 |

本工具补上三件事：**自动登录账号**、**掉线自愈**、**开机自启**。

## 文件

| 文件 | 作用 |
| --- | --- |
| `install.ps1` | 一次性安装：写 `autoLoginAccount`、固化快登、Docker 服务改自启、创建登录时计划任务 |
| `supervisor.ps1` | 周期健康检查与自愈（离线→重启 NapCat 触发快登→必要时重启容器） |
| `status.ps1` | 一键查看当前状态（容器 / QQ 在线 / 快登列表 / 最近恢复记录） |
| `lib.ps1` | 公共函数（读配置、WebUI 登录、OneBot 调用、日志） |

## 安装

```powershell
# 以当前用户身份（不需要管理员，但 Docker 服务启动类型变更需要管理员）
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Qq 3904755943

# 需要管理员时：
Start-Process powershell -Verb RunAs -ArgumentList '-ExecutionPolicy Bypass -File ".\install.ps1" -Qq 3904755943'
```

安装脚本会做且仅做下面这些改动（全部可回滚）：

1. `D:\s0611\napcat\data\config\webui.json` 增加/更新 `"autoLoginAccount": "<QQ>"`；
2. 调用 NapCat WebUI `POST /api/QQLogin/SetQuickLogin {uin}`（失败只告警，不阻断）；
3. 把 `com.docker.service` 启动类型改为 **Automatic** 并启动；
4. 创建计划任务 `Prism-NapCat-Supervisor`（**登录时**触发，不用 SYSTEM，避免 Docker Desktop 会话问题），每 2 分钟运行一次 `supervisor.ps1`；
5. 日志写入 `D:\s0611\napcat\logs\supervisor-YYYYMMDD.log`。

## 状态

```powershell
powershell -ExecutionPolicy Bypass -File .\status.ps1
```

输出示例：

```
容器：Up 2 hours
QQ：在线（3904755943 Cyrene）  NapCat 4.18.41
快登列表：3904755943
自动登录：3904755943
最近恢复：2026-10-26 21:40:12  RestartNapCat 成功，88s 后在线
```

## 卸载

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Uninstall
```

卸载会删除计划任务、把 `com.docker.service` 改回 **Manual**、从 `webui.json` 移除 `autoLoginAccount`（**不动** QQ 登录态与数据目录）。

## 自愈策略（supervisor.ps1）

```
每 2 分钟：
  get_status → online?
  ├─ 是 → 记录一次（每小时最多写 1 行心跳），结束
  └─ 否 → 计数 +1
         ├─ 本小时已重启 ≥3 次 → 只告警，不再重启（防死循环）
         ├─ 先调 WebUI RestartNapCat（会触发快登，通常 30~120s 恢复）
         └─ 120s 后仍离线 → docker restart napcat
```

## 安全

- 只读本机 `webui.json` 取 token，**不落日志、不进命令行**（HTTP 头里带）。
- WebUI 端口保持 `127.0.0.1:6099` 绑定，不对外暴露。
- 不修改 QQ 的账号密码，不做自动化登录（只复用 NapCat 自带的"快速登录"）。
- 脚本只操作系统计划任务与 Docker 服务启动类型，可一键卸载。

## 已知限制

1. QQ 侧强制下线（改密/风控/异地登录）**必须人工扫码**，脚本无法绕过——只会更快地把"需要扫码"暴露给你。
2. 电脑关机/睡眠期间无法保活。若需 7×24，把 NapCat 部署到常开主机（NAS/云主机）。
3. NapCat 升级可能改变 WebUI 路径（`/api/QQLogin/*`）；脚本已做版本探测，失败时输出原始响应供排查。
