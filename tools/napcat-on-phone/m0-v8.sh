#!/data/data/com.termux/files/usr/bin/bash
# M0 v8：修正 main 为 file:// + 启动 Xvfb + 启动 QQ，验证 WebUI 6099。
#
# 依据 v7 实测的三个明确问题：
#   1. Electron 把 main 当相对路径 → 必须写 file:///root/NapCat/loadNapCat.js
#   2. "Missing X server or $DISPLAY" → 需要 Xvfb（容器内已装）
#   3. inotify/dbus 警告 → 用 --disable-dev-shm-usage 等参数降低影响
#
# 用法：bash /data/local/tmp/m0-v8.sh
# 依然不登录 QQ。

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

echo "===== M0 v8 $(date) =====" >> "$LOG"
mark "RUNNING_V8"

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || { say "容器不存在"; mark "FAIL_NO_CONTAINER"; exit 1; }

say "进入容器：修 main(file://) + Xvfb + 启动 QQ"

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
  --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/bash /mnt/host/m0-v8-inner.sh >>"$LOG" 2>&1
RC=$?

if grep -q "WEBUI_UP_AFTER" "$LOG"; then
  say "WebUI 已监听 ✅"
  mark "OK_WEBUI_LISTENING"
elif grep -q "OK_SMOKE_DONE" "$LOG"; then
  say "进程跑过但 WebUI 未监听"
  mark "FAIL_NO_WEBUI"
else
  say "失败（RC=$RC）"
  mark "FAIL_V8_RC$RC"
fi

echo ""
echo "================ M0 v8 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
echo "--- 关键行 ---"
grep -E 'qq_alive|WEBUI_UP|listen|main|Error|Segmentation' "$LOG" | tail -18
echo "============================================"
