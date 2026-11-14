#!/data/data/com.termux/files/usr/bin/bash
# M0 v7 冒烟：启动 NapCat（Linux QQ + 注入），验证 WebUI 6099 是否监听。
#
# 前提（已完成）：容器 / Linux QQ 3.2.32 / NapCat 4.18.42 / main 指向 loadNapCat.js
# **依然不登录 QQ**：这里只验证进程能起、WebUI 端口能开。
#
# 用法：bash /data/local/tmp/m0-v7.sh
# 停止：Ctrl+C（或另开会话跑 pkill）

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

echo "===== M0 v7 (boot smoke) $(date) =====" >> "$LOG" 2>/dev/null
echo "日志可写：$?"
mark "RUNNING_V7"

ROOTFS="$HOME/bookworm-arm64"

say "启动 NapCat（后台 90 秒观察窗口）"
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
  DISPLAY=:0 /bin/bash -lc '
echo "== 启动前检查 =="
grep -o "\"main\"[^,}]*" /opt/QQ/resources/app/package.json | head -1
ls -l /root/NapCat/loadNapCat.js

echo "== 启动 QQ（NapCat 随 main 注入自动加载） =="
cd /opt/QQ
# --no-sandbox 必需（proot 下无法用 setuid sandbox）；后台跑并把日志留下
./qq --no-sandbox --disable-gpu > /tmp/qq-boot.log 2>&1 &
BB=$!
echo "QQ pid=$BB"

# 观察 90 秒：6099 (WebUI) / 3000 (OneBot，需登录后才起)
for i in $(seq 1 18); do
  sleep 5
  P6099=$( (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -c 6099 )
  P3000=$( (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -c 3000 )
  ALIVE=$(kill -0 $BB 2>/dev/null && echo yes || echo no)
  echo "[$((i*5))s] qq_alive=$ALIVE listen6099=$P6099 listen3000=$P3000"
  if [ "$P6099" -gt 0 ]; then
    echo "WEBUI_UP_AFTER=$((i*5))s"
    break
  fi
done

echo "== 端口详情 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E "6099|3000|3001" || echo "(无相关监听)"
echo "== WebUI 配置 =="
cat /root/NapCat/config/webui.json 2>/dev/null | head -12 || echo "(尚未生成 webui.json)"
echo "== 启动日志（末尾 30 行） =="
tail -30 /tmp/qq-boot.log 2>/dev/null

echo "OK_SMOKE_DONE"
' >>"$LOG" 2>&1
RC=$?

if grep -q "WEBUI_UP_AFTER" "$LOG"; then
  say "WebUI 已监听 ✅"
  mark "OK_WEBUI_LISTENING"
elif grep -q "OK_SMOKE_DONE" "$LOG"; then
  say "进程已跑但 WebUI 未监听（见日志）"
  mark "FAIL_NO_WEBUI"
else
  say "失败（RC=$RC）"
  mark "FAIL_V7_RC$RC"
fi

echo ""
echo "================ M0 v7 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
echo "--- 关键行 ---"
grep -E 'qq_alive|WEBUI_UP|listen|Error|error|NapCat|WebUi' "$LOG" | tail -20
echo "============================================"
