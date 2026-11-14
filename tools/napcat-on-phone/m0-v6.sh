#!/data/data/com.termux/files/usr/bin/bash
# M0 v6：只做最后一步——把 QQ 的 main 指向 NapCat 的 loadNapCat.js。
# （v4 已确认：/root/NapCat 三个关键文件齐备，且自带 node_modules）
#
# v4 失败原因：内嵌 `node -e` 的引号被 shell 解析吃掉 → unexpected EOF。
# v5 改为执行独立 JS 文件，彻底避开引号问题。
#
# 用法：bash /data/local/tmp/m0-v6.sh

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

echo "===== M0 v6 $(date) =====" >> "$LOG"
mark "RUNNING_V6"

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || { say "容器不存在"; mark "FAIL_NO_CONTAINER"; exit 1; }

say "进入容器：用独立 JS 改写 QQ main（避开 shell 引号）"

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
  /bin/bash /mnt/host/m0-v6-inner.sh >>"$LOG" 2>&1
RC=$?

if [ $RC -eq 0 ] && grep -q "OK_ALL_DONE" "$LOG"; then
  say "注入完成 ✅"
  mark "OK_READY_TO_START"
else
  say "失败（RC=$RC）"
  mark "FAIL_V6_RC$RC"
fi

echo ""
echo "================ M0 v6 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
tail -16 "$LOG"
echo "============================================"
