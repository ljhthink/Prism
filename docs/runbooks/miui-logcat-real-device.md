# runbook：MIUI 真机 Prism 日志捕获（抗噪音 / 抗重启）

> 目的：手动测试时可靠保存 Prism 的 `io.prism` 关键日志与崩溃堆栈，解决 MIUI 系统噪音冲掉缓冲、
> PID 过滤在进程重启后失效两个历史痛点。

## 快速开始（日常用）

在**第二个终端**窗口运行（先用本脚本，再在手机上操作 App 复现）：

```powershell
# 方式一：推荐 —— 只捕获 io.prism 自身 UID 的日志（含崩溃），无 MIUI 噪音，实时写 PC 文件
.\capture-prism-log.ps1

# 方式二：限时捕获 N 秒后自动停止
.\capture-prism-log.ps1 -Duration 600

# 方式三：FULL 快照（一次性 dump 全部缓冲，含系统噪音；用于怀疑系统层问题）
.\capture-prism-log.ps1 -Full

# 指定输出目录
.\capture-prism-log.ps1 -OutDir D:\logs
```

输出：`app\build\prism-log\prism_<时间戳>.log`（Ctrl+C 停止，文件已实时写入）。

## 关键设计（为什么这样能扛住 MIUI）

| 历史痛点 | 本方案对策 |
|---|---|
| MIUI 组件高频刷屏 → 内存环形缓冲回绕，App 日志被冲掉 | 用 `logcat --uid=<app_uid>` **只放行 io.prism 自己的行**。缓冲里不再有系统噪音，**永不回绕**；外加 `logcat -G 16M` 放大缓冲留余量 |
| 按 PID（`--pid`）过滤，进程重启 PID 变化即失效 | 改用 **UID**：`io.prism` 的 UID（如 10378）**每次安装恒定、跨重启不变**，天然扛进程 kill/重启 |
| 崩溃分属 main/crash/system 缓冲 | `-b main,crash,system` 三缓冲全捞；`io.prism` 的 Java `FATAL EXCEPTION`、native `libc` 崩溃都带同类 UID，`--uid` 一并捕获 |
| logcat 内存缓冲易失 | **实时管道写 PC 文件**（`| Out-File -Append`），到了一行持久一行 |

### 手工对照命令

```powershell
# 查 App UID
adb shell pm list packages -U io.prism        # -> package:io.prism uid:10378

# dump 只含本 App UID 的日志（带线程时间戳，三缓冲）
adb logcat -d --uid=10378 -v threadtime -b main,crash,system

# 只看崩溃（Java FATAL EXCEPTION / native)
adb logcat -d -b crash -s "AndroidRuntime:V" "libc:V" "*:S"
```

## 排查崩溃

1. 遇到崩溃/ANR 后 `Ctrl+C` 停脚本。
2. 打开 `prism_*.log`，按崩溃关键行检索：
   - Java 崩：`FATAL EXCEPTION`（其下方第一行 `Process: io.prism, PID:` + 堆栈）
   - 原生崩：`FATAL`
   - ANR/卡顿：`MIUIScout App` / `ANR in io.prism`
3. 若需把日志贴回对话，直接给 `prism_*.log` 文件路径或关键段落即可。

## 注意事项

- 脚本须保持 **ASCII-only 注释**：Windows PowerShell 5 无 BOM 读 .ps1 会按 ANSI/GBK 解码，
  中文注释被乱码后可能混入无关 `}`/引号导致解析失败（本脚本已因此踩过坑）。
- 默认不 `logcat -c` 清缓冲，避免误删已复现场景的旧日志；如需干净会话，可在手机设置里先清或脚本前手动 `adb logcat -c -b all`。
- `--uid` 需要 debuggable 应用（本 debug APK 满足）或可读 logcat；release 若受限，回退到脚本内置的 TAG 宽匹配路径。