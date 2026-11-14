#!/data/data/com.termux/files/usr/bin/bash
# M0 v3（离线安装版）：不再从手机网络下载任何东西。
#
# 为什么：实测手机经 gh-proxy 只有 ~138KB/s（196MB 要 24 分钟，且多次中断），
# 而 PC 有 11.8MB/s。因此改由 PC 下载 + adb 推送（28MB/s），手机只做安装。
#
# 前置（已由 PC 侧完成）：
#   /data/local/tmp/QQ_arm64.deb      196MB  Linux QQ 3.2.32 arm64
#   /data/local/tmp/NapCat.Shell.zip  34MB   NapCat 4.18.42
#
# 用法（ZeroTermux 里执行一行）：
#   bash /data/local/tmp/m0-v3.sh
#
# **依然不登录 QQ**，零账号风险。

set -o pipefail
LOG="$HOME/m0.log"
STATUS="$HOME/m0.status"

MIRROR() {
  for d in /sdcard /storage/emulated/0; do
    [ -d "$d" ] && cp "$LOG" "$d/m0.log" 2>/dev/null
    [ -f "$STATUS" ] && [ -d "$d" ] && cp "$STATUS" "$d/m0.status" 2>/dev/null
  done
}
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; MIRROR; }
mark() { echo "$1" > "$STATUS"; MIRROR; }

echo "===== M0 v3 (offline) $(date) =====" >> "$LOG"
mark "RUNNING_V3"

ARCH=$(uname -m | sed s/aarch64/arm64/ | sed s/x86_64/amd64/)
ROOTFS="bookworm-$ARCH"
[ -f "$ROOTFS/root/.bashrc" ] || { say "错误：容器不存在"; mark "FAIL_NO_CONTAINER"; exit 1; }
[ -f /data/local/tmp/QQ_arm64.deb ] || { say "错误：缺少 QQ_arm64.deb（请让 PC 侧重新 push）"; mark "FAIL_NO_DEB"; exit 1; }
[ -f /data/local/tmp/NapCat.Shell.zip ] || { say "错误：缺少 NapCat.Shell.zip"; mark "FAIL_NO_ZIP"; exit 1; }

QMB=$(( $(wc -c < /data/local/tmp/QQ_arm64.deb) / 1024 / 1024 ))
say "本地包就绪：QQ ${QMB}MB + NapCat zip"
say "进入容器安装（不联网，纯本地解包 + dpkg）"

unset LD_PRELOAD
proot \
  --bind=/vendor --bind=/system \
  --bind=/data/data/com.termux/files/usr \
  --bind=/storage --bind=/storage/self/primary:/sdcard \
  --bind=/data/data/com.termux/files/home \
  --bind=/data/data/com.termux/cache \
  --bind=/data/dalvik-cache \
  --bind=/data/local/tmp:/mnt/host \
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
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/bash -lc '
export DEBIAN_FRONTEND=noninteractive

echo "== 0) 依赖自检 =="
if dpkg -l 2>/dev/null | grep -q "^ii  libgtk-3-0"; then
  echo "依赖已就绪（上一轮已装）"
else
  echo "依赖缺失 → 用清华源补装"
  cp -n /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
  cat > /etc/apt/sources.list <<EOF
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm-updates main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian-security bookworm-security main contrib non-free non-free-firmware
EOF
  apt-get update -o Acquire::Retries=3
  apt-get install -y --no-install-recommends libnss3 libnotify4 libsecret-1-0 libgbm1 \
    libasound2 fonts-wqy-zenhei gnutls-bin libglib2.0-dev libdbus-1-3 libgtk-3-0 \
    libxss1 libxtst6 libatspi2.0-0 libx11-xcb1 ffmpeg unzip jq curl xvfb
fi

echo "== 1) 安装 Linux QQ（本地 deb） =="
if dpkg -l 2>/dev/null | grep -qi linuxqq; then
  echo "已安装：$(dpkg -l | grep -i linuxqq | awk "{print \$3}")"
else
  rm -f /root/qq.deb
  cp /mnt/host/QQ_arm64.deb /root/qq.deb || { echo "!! 复制 deb 失败"; exit 21; }
  ls -l /root/qq.deb
  dpkg -i --force-depends /root/qq.deb || apt-get -f install -y || true
  if dpkg -l 2>/dev/null | grep -qi linuxqq; then
    echo "QQ 安装成功"; rm -f /root/qq.deb
  else
    echo "!! QQ 安装失败"; exit 22
  fi
fi
echo "QQ 目录：$(ls -1 /opt/QQ 2>/dev/null | head -3 | tr "\n" " ")"

echo "== 2) 安装 NapCat Shell（本地 zip） =="
if [ -x /root/NapCat.linux.arm64/napcat.sh ]; then
  echo "NapCat 已存在"
else
  rm -rf /root/NapCat.linux.arm64 /root/NapCat.zip
  cp /mnt/host/NapCat.Shell.zip /root/NapCat.zip || { echo "!! 复制 zip 失败"; exit 23; }
  unzip -q -o /root/NapCat.zip -d /root/NapCat.linux.arm64 || { echo "!! 解压失败"; exit 24; }
  chmod +x /root/NapCat.linux.arm64/napcat.sh
  rm -f /root/NapCat.zip
fi
echo "NapCat 文件数：$(ls -1 /root/NapCat.linux.arm64 | wc -l)"

echo "== 3) 注入启动钩子 =="
echo "(async () => {await import(\"file:///root/NapCat.linux.arm64/napcat.mjs\");})();" \
  > /opt/QQ/resources/app/app_launcher/index.js
head -c 110 /opt/QQ/resources/app/app_launcher/index.js; echo

echo "== 4) 汇总 =="
dpkg -l 2>/dev/null | grep -i linuxqq | awk "{print \"linuxqq:\", \$3}"
test -x /root/NapCat.linux.arm64/napcat.sh && echo "napcat.sh: 可执行"
echo "OK_ALL_DONE"
' >>"$LOG" 2>&1
RC=$?

if [ $RC -eq 0 ] && grep -q "OK_ALL_DONE" "$LOG"; then
  say "安装完成 ✅"
  mark "OK_PENDING_WEBUI_CHECK"
else
  say "失败（RC=$RC）"
  mark "FAIL_V3_RC$RC"
fi

echo ""
echo "================ M0 v3 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
tail -14 "$LOG"
echo "============================================"
