#!/bin/bash
# 容器内：启动 Xvfb + QQ，**显式**把 DISPLAY 传给 QQ（不经 export/nohup 链路）
# 依据 d2 诊断：Xvfb 起来了（/tmp/.X11-unix/X99 存在），但 QQ 报 "Missing X server or $DISPLAY"
# 说明 DISPLAY 没传进去。这里用 env 前缀直传，并用 --ozone-platform=x11 明确后端。
set -x

echo "== 1) 清理 =="
pkill -f 'opt/QQ/qq' 2>/dev/null
pkill -f Xvfb 2>/dev/null
sleep 1
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null

echo "== 2) Xvfb =="
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
XPID=$!
echo "xvfb_pid=$XPID"
sleep 3
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 3) 验证 X 可用（用 QQ 自带 electron 起一个最小窗口测试） =="
# 用 xset 会缺失，改用 QQ 启动本身的输出来判断

echo "== 4) 启动 QQ（env DISPLAY=:99 直传） =="
cd /opt/QQ
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         --user-data-dir=/root/.config/QQ-m0 \
         > /tmp/qq-boot.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"

sleep 8
if kill -0 $QPID 2>/dev/null; then
  echo "QQ_ALIVE_AFTER_8S=yes"
else
  echo "QQ_ALIVE_AFTER_8S=no"
fi
echo "== 5) 启动日志（前 40 行） =="
head -40 /tmp/qq-boot.log 2>/dev/null
echo "== 6) 日志末尾 =="
tail -20 /tmp/qq-boot.log 2>/dev/null
echo "BOOT2_DONE"
