#!/data/data/com.termux/files/usr/bin/bash
# M0 v10：启动 NapCat（Xvfb + QQ），脚本本身不留场，由外层轮询端口。
# v9 已确认：main=file:///root/NapCat/loadNapCat.js、napcat.mjs、node_modules 齐备。
#
# 用法：bash /data/local/tmp/m0-v10.sh
# 停止：bash /data/local/tmp/m0-stop.sh（待提供）

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

echo "===== M0 v10 (boot) $(date) =====" >> "$LOG"
mark "RUNNING_V10"

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || { say "容器不存在"; mark "FAIL_NO_CONTAINER"; exit 1; }

say "启动容器内 Xvfb + QQ（nohup 脱离）"

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
  /bin/bash /mnt/host/boot-inner.sh >>"$LOG" 2>&1
RC=$?
say "启动脚本返回 RC=$RC"

# 外层轮询（Termux 侧）：最多 120 秒
say "轮询 6099/3000（最多 120s）"
saw=0
for i in $(seq 1 24); do
  sleep 5
  P=$(for f in /proc/net/tcp /proc/net/tcp6; do [ -f "$f" ] && awk 'NR>1 && $4=="0A" {n=split($2,a,":"); print a[n]}' "$f"; done 2>/dev/null | sort -u | tr '\n' ' ')
  H=$(echo "$P" | tr ' ' '\n' | while read x; do [ -n "$x" ] && printf "%d " "0x$x"; done)
  say "[$((i*5))s] listening=[$H]"
  case " $H " in
    *" 6099 "*) saw=1; say "WEBUI_UP_AFTER=$((i*5))s"; break ;;
  esac
done

if [ "$saw" = "1" ]; then
  mark "OK_WEBUI_LISTENING"
else
  mark "FAIL_NO_WEBUI"
fi

echo ""
echo "================ M0 v10 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
echo "--- 端口 ---"
tail -6 "$LOG"
echo "--- QQ 启动日志尾部 ---"
grep -A20 'napcat\|NapCat\|WebUi' "$LOG" 2>/dev/null | tail -20
echo "============================================"
