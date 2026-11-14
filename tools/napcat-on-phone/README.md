# 手机本机模式：NapCat 装在手机上（免 Google 账号、免电脑、免组网）

> 适用：Prism 与 NapCat 跑在**同一台 Android 手机**上，Prism 连 `127.0.0.1:3000`。
> 推荐把两者都装在**一台闲置旧手机**上（插电常驻、与主号设备隔离）。
>
> 相关方案：[2026-10-27-qq-local-protocol-on-phone-plan.md](../../docs/reports/2026-10-27-qq-local-protocol-on-phone-plan.md)

## 为什么这条路最省事

| | 本机模式 | 前版 Tailscale | 公网隧道 |
| --- | --- | --- | --- |
| 网络配置 | **零**（回环地址） | 两台设备装客户端 + 登录 | 域名/VPS + 反代 |
| 第三方账号 | **不需要**（APK 直下） | 需要（Google 等） | 需要（邮箱/域名） |
| 电脑 | **不需要** | 需要 | 需要 |
| 出门可用 | **可以**（回环不切网） | 可以 | 可以 |
| 安全面 | **不放宽任何白名单** | 需放行 100.64/10 | token 过公网 |

## 前置

- Android 手机（本项目实测机型：23113RKC6C / **Android 16 / HyperOS V816**）；
- 存储 ≥ 3GB（Debian 容器 + Linux QQ 约 1~2GB）；
- 内存 ≥ 4GB（Linux QQ 常驻约 300–600MB）；
- **不需要** Google 账号：ZeroTermux 从 GitHub Releases 直接装 APK。

## 一、装 ZeroTermux

1. 打开 <https://github.com/hanxinhao000/ZeroTermux/releases>，下载最新 `.apk` 并安装
   （若下载慢，用 `https://mirror.ghproxy.com/` 前缀加速）。
2. 首次打开允许通知/存储权限。

## 二、装 NapCat（两条路，优先 A）

### A. 一键脚本（推荐先试）

在 ZeroTermux 终端里执行：

```bash
bash -c "$(curl -L https://github.com/NapNeko/NapCat-Termux/raw/main/onekey.sh)"
```

下载慢时换镜像：

```bash
bash -c "$(curl -L https://mirror.ghproxy.com/https://github.com/NapNeko/NapCat-Termux/raw/main/onekey.sh)"
```

脚本会：装 Debian bookworm(proot) → `apt` 装 Linux QQ 依赖 → 装 **Linux QQ 3.2.12 arm64** →
下载**最新 NapCat Shell** → 注入启动钩子。

> ⚠️ 已知风险：该脚本最后更新于 **2024-08**，钉的是 QQ 3.2.12，而当前 Linux QQ 已到 3.2.32。
> 若启动报错，走 B。

### B. 恢复包（脚本失败时的备选）

1. 到 <https://github.com/NapNeko/NapCat-Termux/releases> 下载 ZeroTermux 恢复包；
2. 放到手机 `内部存储/xinhao/data/`（或 `/sdcard/xinhao/data`）；
3. ZeroTermux 里按音量上键 →「备份/恢复」→ 选恢复包 → 起个容器名 → 恢复（几分钟）；
4. 音量上键 →「容器切换」→ 选该容器 → 立即重启。

### C. 手动兜底（A/B 都失败时）

```bash
# 进入容器后
cd /root
# 1) 装最新 Linux QQ（arm64 deb，版本号按 https://github.com/Rodert/qq-versions/releases 取最新的）
curl -O https://dldir1.qq.com/qqfile/qq/QQNT/Linux/QQ_3.2.32_260730_arm64_01.deb
apt-get update && apt-get install -y ./QQ_3.2.32_260730_arm64_01.deb || \
  dpkg -i --force-depends QQ_3.2.32_260730_arm64_01.deb
# 2) 装最新 NapCat Shell
version=$(curl -s https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest | jq -r .tag_name)
curl -L "https://github.com/NapNeko/NapCatQQ/releases/download/$version/NapCat.Shell.zip" -o napcat.zip
unzip -o napcat.zip -d NapCat.linux.arm64 && chmod +x NapCat.linux.arm64/napcat.sh
# 3) 注入启动钩子（与官方脚本一致）
echo "(async () => {await import('file:///root/NapCat.linux.arm64/napcat.mjs');})();" \
  > /opt/QQ/resources/app/app_launcher/index.js
```

## 三、启动

```bash
bash bookworm-arm64.sh          # 进容器（若已在容器内可跳过）
cd NapCat.linux.arm64 && ./napcat.sh
```

正常情况下会打印：

```
[NapCat] [Core] NapCat.Core Version: 4.18.x
[NapCat] [WebUi] WebUi User Panel Url: http://127.0.0.1:6099/webui?token=xxxxxxxx
```

## 四、M0 冒烟（**先别登录 QQ**）

在手机浏览器打开：

```
http://127.0.0.1:6099/webui
```

能看到 **WebUI 登录页 / 二维码页** 即视为"栈可用"。

判断要点：

- **不要**用 `127.0.0.1:3000` 判断失败 —— NapCat 未登录 QQ 时，OneBot 的 3000/3001 **不会启动**，这是正常的；
- 容器是否进去：`uname -a` 以 `Android` 结尾 = 还在 Termux；以 `GNU/Linux` 结尾 = 已进 bookworm；
- WebUI token 在容器内 `/root/NapCat.linux.arm64/config/webui.json`。

## 五、登录小号（用手机自己完成，不碰电脑）

WebUI 支持两种登录，都在手机浏览器里完成：

1. **密码登录**（推荐）：WebUI → QQ 登录 → 账号密码；可能要求验证码/新设备验证，按提示走；
2. **扫码登录**：WebUI 显示二维码，用**已登录小号的 QQ**（另一台设备的 QQ，或本机 QQ 的"应用双开"）扫码。

> 注意：本机模式下小号与主号可能处于同一设备/IP，NapCat 官方[安全建议](https://napneko.github.io/other/security)
> 劝阻这一组合。若你介意，用**闲置旧手机**装 Prism+NapCat，主号留在主机上，就是隔离的。

登录成功后，`http://127.0.0.1:3000/get_status` 才会返回：

```json
{"status":"ok","retcode":0,"data":{"online":true,"good":true}}
```

## 六、保活（HyperOS 必做，否则会被清理）

> **为什么这一节最重要**：`Termux → proot → Debian → Linux QQ → NapCat` 是**单链**结构，
> `proot` 依附 Termux 进程，Termux 一被杀整条链必然一起死。
> 2026-10-31 实测：Termux 被 `AutoPowerKill` 杀死后，协议端**静默离线 19 小时 42 分**无人知晓。
> 完整复盘见 [交接文档](../../docs/reports/2026-11-01-qq-onphone-keepalive-handover.md)。

### 6.1 必须完成的系统设置（逐条核对"判据"列，不要只凭印象）

| # | 操作 | 路径 | 判据（怎么确认真的生效） |
| --- | --- | --- | --- |
| 1 | Termux 省电策略 → **无限制** | 设置 → 应用设置 → 应用管理 → ZeroTermux → 省电策略 | 该页显示"无限制"，**不是**"智能省电"/"省电" |
| 2 | 允许**自启动** | 同上页 / 安全中心 → 自启动管理 | 开关为开 |
| 3 | 允许**后台活动** | 同上页 | 开关为开 |
| 4 | **锁定任务卡片** | 多任务界面 → 按住 ZeroTermux 卡片 → 下拉或点锁图标 | 卡片上出现**锁标记**（防上滑清除） |
| 5 | 加入清理白名单 | 安全中心 → 优化 → 清理白名单 → 加入 **ZeroTermux 与 Prism** | 两个应用都在列表内 |
| 6 | 允许后台弹出界面/后台运行 | 安全中心 → 应用权限 | 均为允许 |

### 6.2 Termux 内（**每次应用被杀/重启后都要重跑**）

```bash
termux-wake-lock
```

> 应用进程一死，wake-lock 即失效；重新进 Termux 必须再执行一次。

### 6.3 可验证的自查命令（在 Termux 里跑）

```bash
# ① 是否已在电池优化白名单（输出里应能看到 termux）
dumpsys deviceidle whitelist | grep -i termux

# ② Termux 是否持有唤醒锁（有输出即为持有）
dumpsys power | grep -i 'termux'

# ③ 协议端是否在跑（三条命令任一为空即说明已死）
ps -A | grep -E 'Xvfb|^qq$' | head -3
```

### 6.4 三条禁令

- **不要**在最近任务里上滑 ZeroTermux（`SwipeUpClean` 立即杀）；
- **不要**点安全中心的"一键加速 / 清理"（`OneKeyClean` 会连 Prism 一起杀，告警也一并失效）；
- **不要**在协议端那个 Termux 会话里输入 `exit` 或按 CTRL+C。

### 6.5 已知上限（务必知情）

- HyperOS 可能**重置**上述省电设置（2026-10-31 已发生一次"设了却没生效"），需定期自查；
- **Prism 被杀时任何告警都发不出去**，因此"协议端死了"只能由用户自己发现——
  这正是 M2 告警（ADR-069 AC-9）尚未实施、必须补上的原因。

## 七、在 Prism 里配置

设置 → QQ 小号助手 → 协议端连接：

- 协议端地址填 **`127.0.0.1`**（App 会自动成 `http://127.0.0.1:3000` / `ws://127.0.0.1:3001`）；
- token 填 NapCat 的 `access_token`（WebUI → 网络配置里可看到/修改）；
- 点「连接诊断」应显示 `已连接（127.0.0.1:3000）`。

## 八、常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `mkdir '/root'` no such file | 容器没恢复好：重新执行「容器切换」并重启，或重跑 onekey 脚本 |
| `napcat.sh` 起来就退出 | QQ 版本与 NapCat 不匹配：按 §二.C 换成最新 Linux QQ |
| WebUI 打不开 | NapCat 没起 / 端口被占：`ps aux \| grep napcat`，`netstat -tlnp \| grep 6099` |
| 3000 一直连不上但 6099 正常 | **正常**：还没登录 QQ。先登录再试 |
| 用一阵子突然全断 | Termux 被系统清理（`AutoPowerKill`/`SwipeUpClean`/`OneKeyClean`）：见 §六，重跑 `termux-wake-lock` 与启动脚本，并逐条核对 §6.1 判据 |
| 断开很久才发现 | 这是已知缺口：M2 失联告警未实施（ADR-069 AC-9）。Prism 只显示状态、不推送 |
| 扫描二维码提示失效 | 二维码有时效，回 WebUI 刷新一次再扫 |

## 九、卸载/回滚

- NapCat/Termux 侧：ZeroTermux 里删除容器（或在系统里卸载 ZeroTermux），Linux QQ 数据随之清除；
- Prism 侧：把协议端地址改回 PC 的 IP（或关闭 QQ 小号助手）即回到旧形态；
- 不影响主号 QQ 与 Prism 其它功能。

## 十、环境差异与自愈脚本（2026-11-02，ADR-071）

**PRoot 镜像里没有 `ss`，也没有 `netstat`** —— 旧版 `run2-inner.sh` 用
`(ss -tln || netstat -tln) | grep -c ':3000'` 探活，计数**恒为 0**，于是 Prism 侧
「已在运行就不重启」的防自踢守卫永不生效。现在改为读内核表（并校验连接状态列）：

```bash
grep -E ':0BB8[[:space:]]+[0-9A-Fa-f:]+[[:space:]]+(0A|01)[[:space:]]' /proc/net/tcp
```

状态文件格式升级为：

```text
ts=1793626043 tick=724 qq=5 onebot3000=1 state=online restarts=0
```

`state` 取值 `starting | need_qr | online | qq_dead`；**`starting` / `need_qr` 期间 Prism 不会自动重启**
（等待扫码时 3000 本来就不监听，重启会把正在扫的二维码作废并导致登录态再次失效）。

脚本已加**单实例锁**（`flock /tmp/qq-run2.lock`，无 flock 时退化为 pid 文件）：重复执行
`RUN_COMMAND` 不会再拉起第二条 QQ 链互踢账号。`run2.sh` 入口会先执行 `termux-wake-lock`。
另外加了**启动超时**：`starting` 持续 180 秒不前进即杀掉重来（防"进程活着但卡死"两边都不救）。

> 核对是否真的生效（P0 教训：修好的脚本可能没进 APK）：
> `adb shell wc -l /data/local/tmp/run2-inner.sh` 应为 **199 行**左右，或看状态文件里是否有 `state=` 字段。