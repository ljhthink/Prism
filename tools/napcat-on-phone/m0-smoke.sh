#!/data/data/com.termux/files/usr/bin/bash
# M0 可行性冒烟：在旧手机（Redmi M2012K10C / Android 13 / HyperOS）上验证
# ZeroTermux + Debian(proot) + Linux QQ + NapCat 能否跑起来。
#
# 特点：
#   - **不登录 QQ**（零账号风险，不产生任何风控暴露）
#   - 全自动、非交互；进度写 /sdcard/m0.log，结论写 /sdcard/m0.status
#   - 可断点续跑：已装好容器/已装好 NapCat 会跳过对应步骤
#
# 用法（Termux 里执行一行）：
#   bash /sdcard/m0.sh
#
# 判据（成功时 status 里是 OK）：
#   - 进得去 proot 容器（uname -a 以 GNU/Linux 结尾）
#   - NapCat 能启动并打印 WebUi User Panel Url
#   - 手机浏览器能打开 http://127.0.0.1:6099/webui

set -o pipefail
# 日志优先写 $HOME（Termux 一定有写权限），并尽力镜像到 /sdcard 便于 PC 侧读取。
LOG="$HOME/m0.log"
STATUS="$HOME/m0.status"
mkdir -p "$HOME" 2>/dev/null

MIRROR() {
  for d in /sdcard /storage/emulated/0; do
    [ -d "$d" ] && cp "$LOG" "$d/m0.log" 2>/dev/null
    [ -f "$STATUS" ] && [ -d "$d" ] && cp "$STATUS" "$d/m0.status" 2>/dev/null
  done
}
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; MIRROR; }
mark() {
  echo "$1" > "$STATUS"
  # 尽力镜像（无存储权限时静默失败，不影响主流程）
  for d in /sdcard /storage/emulated/0; do
    [ -d "$d" ] && cp "$STATUS" "$d/m0.status" 2>/dev/null
    [ -f "$LOG" ] && [ -d "$d" ] && cp "$LOG" "$d/m0.log" 2>/dev/null
  done
}

echo "===== M0 smoke $(date) =====" > "$LOG"
mark "RUNNING"

if uname -a | grep -q "Android"; then
  say "环境正确：在 Termux 内（未进容器）"
else
  say "错误：当前已在 proot 容器内，请退出容器（exit）后重跑"
  mark "FAIL_IN_CONTAINER"
  exit 1
fi

ARCH=$(uname -m | sed s/aarch64/arm64/ | sed s/x86_64/amd64/)
ROOTFS="bookworm-$ARCH"
say "架构=$ARCH 容器目录=$ROOTFS"

# ---------- 步骤 1：安装 Debian 容器 ----------
if [ -f "$ROOTFS/root/.bashrc" ]; then
  say "步骤1 跳过：容器已存在"
else
  say "步骤1 开始：安装 Debian bookworm（下载约 300MB，可能需要 10-20 分钟）"
  if ! command -v curl >/dev/null 2>&1; then
    say "缺少 curl，尝试 pkg install"
    pkg install -y curl tar xz-utils >>"$LOG" 2>&1 || true
  fi
  curl -L -o "${PREFIX:-$HOME}/tmp/debian.sh" https://mirror.ghproxy.com/https://github.com/NapNeko/NapCat-Termux/raw/main/debian.sh >>"$LOG" 2>&1 \
    || curl -L -o "${PREFIX:-$HOME}/tmp/debian.sh" https://github.com/NapNeko/NapCat-Termux/raw/main/debian.sh >>"$LOG" 2>&1
  if [ ! -s "${PREFIX:-$HOME}/tmp/debian.sh" ]; then
    say "错误：debian.sh 下载失败（网络/镜像问题）"
    mark "FAIL_DOWNLOAD_DEBIAN"
    exit 1
  fi
  bash "${PREFIX:-$HOME}/tmp/debian.sh" >>"$LOG" 2>&1
  if [ ! -f "$ROOTFS/root/.bashrc" ]; then
    say "错误：容器安装失败，详见 $LOG"
    mark "FAIL_CONTAINER"
    exit 1
  fi
  say "步骤1 完成：容器已就绪"
fi

# ---------- 步骤 2：容器内安装 Linux QQ + NapCat ----------
say "步骤2 开始：容器内安装 Linux QQ + NapCat（依赖较多，10-30 分钟）"
if [ ! -f "$ROOTFS/root/napcat.sh" ]; then
  curl -L -o "$ROOTFS/root/napcat.sh" https://mirror.ghproxy.com/https://github.com/NapNeko/NapCat-Termux/raw/main/napcat.sh >>"$LOG" 2>&1 \
    || curl -L -o "$ROOTFS/root/napcat.sh" https://github.com/NapNeko/NapCat-Termux/raw/main/napcat.sh >>"$LOG" 2>&1
fi
chmod +x "$ROOTFS/root/napcat.sh" 2>/dev/null

unset LD_PRELOAD
proot \
  --bind=/vendor --bind=/system \
  --bind=/data/data/com.termux/files/usr \
  --bind=/storage --bind=/storage/self/primary:/sdcard \
  --bind=/data/data/com.termux/files/home \
  --bind=/data/data/com.termux/cache \
  --bind=/data/dalvik-cache \
  --bind="$ROOTFS/tmp:/dev/shm" \
  --bind="$ROOTFS/etc/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap" \
  --bind="$ROOTFS/etc/proc/.vmstat:/proc/vmstat" \
  --bind="$ROOTFS/etc/proc/.version:/proc/version" \
  --bind="$ROOTFS/etc/proc/.uptime:/proc/uptime" \
  --bind="$ROOTFS/etc/proc/.stat:/proc/stat" \
  --bind="$ROOTFS/etc/proc/.loadavg:/proc/loadavg" \
  --bind="$ROOTFS/etc/proc/.bus/input/devices:/proc/bus/input/devices" \
  --bind="$ROOTFS/etc/proc/.modules:/proc/modules" \
  --bind=/sys --bind=/proc/self/fd/2:/dev/stderr \
  --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/0:/dev/stdin \
  --bind=/proc/self/fd:/dev/fd --bind=/proc \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/data/com.termux/files/usr/tmp:/tmp \
  --bind=/data/data/com.termux/files:"$ROOTFS/termux" \
  --bind=/dev --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink --kill-on-exit \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=zh_CN.UTF-8 TERM=xterm-256color \
  /bin/su -l root /root/napcat.sh >>"$LOG" 2>&1
RC=$?
say "步骤2 退出码=$RC（apt 装依赖时出现非零退出码不一定是失败，看下面的版本探测）"

# ---------- 步骤 3：探测 NapCat / WebUI 是否就绪 ----------
say "步骤3 探测 NapCat 与 WebUI"
PROBE=$(proot \
  --bind=/vendor --bind=/system --bind=/storage --bind=/proc --bind=/dev \
  --bind="$ROOTFS/etc/proc/.version:/proc/version" \
  --bind="$ROOTFS/etc/proc/.stat:/proc/stat" \
  --bind="$ROOTFS/etc/proc/.uptime:/proc/uptime" \
  --bind="$ROOTFS/etc/proc/.loadavg:/proc/loadavg" \
  --root-id --cwd=/root -L --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /bin/sh -lc '
    echo "--- napcat dir ---"
    ls -1 /root/NapCat.linux.arm64 2>/dev/null | head -20
    echo "--- qq version ---"
    (dpkg -l 2>/dev/null | grep -i linuxqq) || true
    echo "--- webui config ---"
    cat /root/NapCat.linux.arm64/config/webui.json 2>/dev/null | head -20
    echo "--- inject hook ---"
    head -c 120 /opt/QQ/resources/app/app_launcher/index.js 2>/dev/null
    echo
  ' 2>&1)
echo "$PROBE" | tee -a "$LOG"

if echo "$PROBE" | grep -q "NapCat.linux.arm64"; then
  say "结果：NapCat 已落盘"
  mark "OK_PENDING_WEBUI_CHECK"
else
  say "结果：未发现 NapCat 目录 → 需要走兜底（装最新 Linux QQ + 最新 NapCat）"
  mark "FAIL_NAPCAT_MISSING"
fi

say "===== 完成 ====="
echo ""
echo "================ M0 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
echo "日志：$LOG（已尝试镜像到 /sdcard/m0.log）"
echo "手工复核：浏览器打开 http://127.0.0.1:6099/webui 能否看到登录页"
echo "========================================"
echo ""
echo "把上面 STATUS 这一行发我即可（其余我可以用 adb 读日志）。"
