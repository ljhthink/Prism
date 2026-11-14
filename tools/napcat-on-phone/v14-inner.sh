#!/bin/bash
# 容器内 v14：基线测试 —— 完全恢复 QQ 原状（无 NapCat 注入），确认 QQ 本身能启动
set -x

APP=/opt/QQ/resources/app
PKG=$APP/package.json

echo "== 1) 停进程 =="
pkill -f 'opt/QQ/qq' 2>/dev/null
pkill -f Xvfb 2>/dev/null
sleep 2

echo "== 2) 恢复 QQ 原始文件 =="
if [ -f "$PKG.m0bak" ]; then cp "$PKG.m0bak" "$PKG"; echo "package.json 已恢复"; fi
grep -o '"main"[^,}]*' "$PKG" | head -1

if [ -f "$APP/app_launcher/index.js.m0bak" ]; then
  cp "$APP/app_launcher/index.js.m0bak" "$APP/app_launcher/index.js"
  echo "app_launcher/index.js 已恢复"
fi
echo "当前 app_launcher/index.js 前 3 行："
head -3 "$APP/app_launcher/index.js" 2>/dev/null

echo "== 3) 起 Xvfb =="
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 3
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 4) 起纯 QQ =="
cd /opt/QQ
rm -f /tmp/qq-plain.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-plain.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"
sleep 15
kill -0 $QPID 2>/dev/null && echo "PLAIN_QQ_ALIVE=yes" || echo "PLAIN_QQ_ALIVE=no"

echo "== 5) 纯 QQ 日志（30 行） =="
head -30 /tmp/qq-plain.log 2>/dev/null
echo "--- 末尾 10 行 ---"
tail -10 /tmp/qq-plain.log 2>/dev/null
echo "== 6) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000' || echo "(无)"
echo "== 7) QQ 是否生成了配置 =="
ls -la /root/.config/QQ/ 2>/dev/null | head -8

cp /tmp/qq-plain.log /mnt/host/qq-plain.log 2>/dev/null
echo "V14_DONE"
