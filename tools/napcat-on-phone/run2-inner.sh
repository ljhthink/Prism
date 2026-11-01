#!/bin/bash
# 容器内：单实例常驻启动 NapCat，**使用快速登录**（-q 3904755943），不扫码。
#
# 依据 2026-10-31 真机日志：
#   13:17:31 NapCat 自动重启后打印「可用于快速登录 of QQ：1. 3904755943 Cyrene」
#   → 凭据已落盘，可用 -q 快速登录，无需重复扫码。
#
# 同时每轮把日志镜像到 /sdcard/napcat.log，便于 PC 侧直接读取定位。

QQ=3904755943
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
n=0
while true; do
  sleep 5
  n=$((n+1))
  # 镜像日志（供 PC 侧读取）
  cp "$LOG" /sdcard/napcat.log 2>/dev/null

  P3000=$( (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -c ':3000' )
  QN=$(pgrep -fc 'opt/QQ/qq' 2>/dev/null || echo 0)
  # 每 10 秒写一行状态
  if [ $((n % 2)) -eq 0 ]; then
    {
      echo "tick=$n qq=$QN onebot3000=$P3000"
      grep -aS 'KickedOffLine\|账号状态\|登录成功\|已上线\|快速登录\|Server Started' "$LOG" 2>/dev/null | tail -3
    } > /sdcard/napcat-status.txt
  fi
  # 就绪即打印一次
  if [ "$P3000" -gt 0 ] && [ $((n % 12)) -eq 0 ]; then
    echo "[$((n*5))s] READY qq=$QN onebot=1"
  fi
done
