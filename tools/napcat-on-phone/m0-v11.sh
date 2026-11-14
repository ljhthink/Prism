#!/data/data/com.termux/files/usr/bin/bash
# M0 v9：只修 main 路径（不启动任何 GUI，避免挂起）。
# v7/v8 已确认：QQ 进程能起，但 NapCat 未加载 —— 因为 main 仍是相对路径。
# 本脚本把 main 改成 file:// URI 并校验，然后**单独**再用 v10 启动。
#
# 用法：bash /data/local/tmp/m0-v11.sh

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

echo "===== M0 v9 (fix main only) $(date) =====" >> "$LOG"
mark "RUNNING_V9"

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || { say "容器不存在"; mark "FAIL_NO_CONTAINER"; exit 1; }

say "进入容器：只改 main（不启动 GUI）"

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
  /bin/bash /mnt/host/boot2-inner.sh >>"$LOG" 2>&1
RC=$?

if grep -q "PATH_PATCH_DONE" "$LOG"; then
  if grep -q 'file:///root/NapCat/loadNapCat.js' "$LOG"; then
    say "main 已改为 file:// URI ✅"
    mark "OK_MAIN_PATCHED"
  else
    say "改写未生效（见日志）"
    mark "FAIL_PATCH_NOT_APPLIED"
  fi
else
  say "失败（RC=$RC）"
  mark "FAIL_V9_RC$RC"
fi

echo ""
echo "================ M0 v9 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
echo "--- 最后 18 行 ---"
tail -18 "$LOG"
echo "============================================"
