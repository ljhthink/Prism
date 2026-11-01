#!/bin/bash
# 容器内：单实例常驻启动 NapCat，**使用快速登录**（-q <QQ号>），不扫码。
#
# 依据 2026-10-31 真机日志：
#   13:17:31 NapCat 自动重启后打印「可用于快速登录 of QQ：1. <QQ号> <昵称>」
#   → 凭据已落盘，可用 -q 快速登录，无需重复扫码。
#
# 同时每轮把日志镜像到 /sdcard/napcat.log，便于 PC 侧直接读取定位。
#
# 2026-11-01（安全审查 H4）：QQ 号改为**可由环境变量覆盖**，不再只有硬编码一条路。
# QQ 号本身不是密钥（好友可见），但它会随 APK assets 下发到 /data/local/tmp，
# 属"本机其它 App 可读"的信息面；环境变量可在不改脚本的前提下换号。

QQ="${PRISM_QQ_ID:-3904755943}"
if ! printf '%s' "$QQ" | grep -qE '^[0-9]{5,12}$'; then
  echo "!! QQ 号非法：$QQ" >&2
  exit 1
fi
LOG=/tmp/qq-run2.log

echo "== 1) 清理旧实例 =="
pkill -9 -f 'opt/QQ/qq' 2>/dev/null
pkill -9 -f 'Xvfb :99' 2>/dev/null
sleep 3
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
mkdir -p /tmp/xdg && chmod 700 /tmp/xdg
echo "残留 qq=$(pgrep -fc 'opt/QQ/qq' 2>/dev/null || echo 0)"

echo "== 2) Xvfb =="
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb2.log 2>&1 &
sleep 4
echo "xvfb=$(pgrep -f 'Xvfb :99' | head -1)"

echo "== 3) 快速登录启动 =="
cd /opt/QQ
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         -q "$QQ" > "$LOG" 2>&1 &

echo "已启动，等待登录与 OneBot 就绪…"
# 2026-11-01（审查 C1）：进程被 SIGKILL 时没有任何 trap 能触发，状态文件会永久停在
# 最后一行的 `onebot3000=1`。因此**每行状态都带 epoch 时间戳 ts=**，由 Prism 侧判 stale。
# 这是"存活判据"唯一可靠的代理信号。
n=0
while true; do
  sleep 5
  n=$((n+1))
  # 镜像日志（供 PC 侧读取）
  cp "$LOG" /sdcard/napcat.log 2>/dev/null

  P3000=$( (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -c ':3000' )
  QN=$(pgrep -fc 'opt/QQ/qq' 2>/dev/null || echo 0)
  TS=$(date +%s)
  # 每 10 秒写一行状态（含 ts）
  if [ $((n % 2)) -eq 0 ]; then
    {
      echo "ts=$TS tick=$n qq=$QN onebot3000=$P3000"
      grep -aS 'KickedOffLine\|账号状态\|登录成功\|已上线\|快速登录\|Server Started' "$LOG" 2>/dev/null | tail -3
    } > /sdcard/napcat-status.txt
  fi
  # 每轮把当前 WebUI 扫码地址单独落一份（2026-11-01 审查 H-2）：
  # WebUI URL 只在 NapCat 启动时打印一次，日志持续增长后会滑出尾部窗口，
  # 单独文件 + tail -1 保证"扫码登录"永远拿得到最新一条。
  #
  # 审查 N-4：**先写临时文件、非空再 mv**——`grep | tail -1 > file` 在无匹配时会写空文件，
  # 而 NapCat 重启瞬间日志被截断，会造成已登录后"扫码地址突然变空"。
  if [ $((n % 4)) -eq 0 ]; then
    if grep -aS 'WebUi User Panel Url' "$LOG" 2>/dev/null | tail -1 > /sdcard/.napcat-webui.tmp 2>/dev/null \
       && [ -s /sdcard/.napcat-webui.tmp ]; then
      mv /sdcard/.napcat-webui.tmp /sdcard/napcat-webui.txt 2>/dev/null
    else
      rm -f /sdcard/.napcat-webui.tmp 2>/dev/null
    fi
  fi
  # 就绪即打印一次
  if [ "$P3000" -gt 0 ] && [ $((n % 12)) -eq 0 ]; then
    echo "[$((n*5))s] READY qq=$QN onebot=1"
  fi
done
