#!/bin/bash
# 容器内：按 NapCat Shell 官方标准方式部署 —— 解压进 QQ 的 app 目录，用相对路径 main
#
# 依据实测：Electron 的 main 字段**不支持 file:// URI**，会拼成
#   /opt/QQ/resources/app/file:/root/NapCat/loadNapCat.js
# 官方 NapCat.Shell.zip 自带 qqnt.json 里就是 "main": "./loadNapCat.js"，
# 前提是这些文件位于 app 目录内。因此正确做法是「合并解压到 app 目录」。
set -x

APP=/opt/QQ/resources/app
PKG=$APP/package.json

echo "== 1) 停掉旧 QQ =="
pkill -f 'opt/QQ/qq' 2>/dev/null
sleep 2

echo "== 2) 把 NapCat 合并进 app 目录 =="
cp -r /root/NapCat/. "$APP"/ 2>/dev/null
echo "拷贝结果检查："
for f in loadNapCat.js napcat.mjs node_modules static; do
  if [ -e "$APP/$f" ]; then echo "  OK $f"; else echo "  MISSING $f"; fi
done

echo "== 3) 设置 main 为相对路径 =="
[ -f "$PKG.m0bak" ] || cp "$PKG" "$PKG.m0bak"
sed -i '0,/"main"[[:space:]]*:[[:space:]]*"[^"]*"/s##"main": "./loadNapCat.js"#' "$PKG"
grep -o '"main"[^,}]*' "$PKG" | head -1

echo "== 4) 起 Xvfb =="
pkill -f Xvfb 2>/dev/null
sleep 1
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 3
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 5) 起 QQ（显式 DISPLAY） =="
cd /opt/QQ
rm -f /tmp/qq-boot.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-boot.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"

sleep 10
kill -0 $QPID 2>/dev/null && echo "QQ_ALIVE=yes" || echo "QQ_ALIVE=no"
echo "== 6) 启动日志（40 行） =="
head -40 /tmp/qq-boot.log 2>/dev/null
echo "== 7) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000' || echo "(无 6099/3000)"
echo "== 8) NapCat 配置/日志 =="
ls -la /root/.config/QQ/napcat 2>/dev/null | head -8
find / -maxdepth 5 -name 'webui.json' -newermt '-1 hours' 2>/dev/null | head -5
echo "COPY_LOGS"
cp /tmp/qq-boot.log /mnt/host/qq-boot.log 2>/dev/null
cp /tmp/xvfb.log /mnt/host/xvfb.log 2>/dev/null
echo "V12_DONE"
