#!/bin/bash
# 容器内：极简启动（Xvfb + QQ），用 nohup 脱离，立刻返回
# 目标：起完立即让脚本结束，由外层用 adb 轮询端口
echo "== 1) 清理旧进程 =="
pkill -f 'opt/QQ/qq' 2>/dev/null
pkill -f Xvfb 2>/dev/null
sleep 1
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null

echo "== 2) 起 Xvfb :99 =="
nohup Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 2
echo "Xvfb pid=$(pgrep -f 'Xvfb :99' | head -1)"
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 3) 起 QQ（nohup 脱离，脚本不等待） =="
cd /opt/QQ
export DISPLAY=:99
nohup ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
      --disable-software-rasterizer --no-zygote \
      > /tmp/qq-boot.log 2>&1 &
sleep 3
echo "QQ pid=$(pgrep -f 'opt/QQ/qq' | head -3 | tr '\n' ' ')"

echo "== 4) 立即返回，由外层轮询 =="
echo "BOOT_LAUNCHED"
