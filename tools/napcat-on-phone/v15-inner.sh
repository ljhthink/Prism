#!/bin/bash
# 容器内 v15：真正还原 QQ 原状（用 .orig 备份），做干净的基线测试
#
# 背景：v12 的 `cp -r /root/NapCat/. "$APP"/` 覆盖了 QQ 的 package.json，
# 导致 v14 的"纯 QQ"其实是 NapCat 的入口 → 同样的 index.js 错误。
# 这里用 v9 时保存的 .orig 恢复真原状。
set -x

APP=/opt/QQ/resources/app
PKG=$APP/package.json

echo "== 1) 停进程 =="
pkill -f 'opt/QQ/qq' 2>/dev/null
pkill -f Xvfb 2>/dev/null
sleep 2

echo "== 2) 查看可用备份 =="
ls -l "$PKG.orig" "$PKG.m0bak" "$APP/app_launcher/index.js.m0bak" 2>/dev/null
echo "--- .orig 的 main ---"
grep -o '"main"[^,}]*' "$PKG.orig" 2>/dev/null | head -1
echo "--- .m0bak 的 main ---"
grep -o '"main"[^,}]*' "$PKG.m0bak" 2>/dev/null | head -1

echo "== 3) 用 .orig 恢复 QQ 原始 package.json =="
if [ -f "$PKG.orig" ]; then
  cp "$PKG.orig" "$PKG"
  echo "已恢复 .orig"
else
  echo "!! 无 .orig，改用写入已知原始值"
  sed -i '0,/"main"[[:space:]]*:[[:space:]]*"[^"]*"/s##"main": "./application.asar/app_launcher/index.js"#' "$PKG"
fi
grep -o '"main"[^,}]*' "$PKG" | head -1

echo "== 4) 恢复 app_launcher/index.js =="
if [ -f "$APP/app_launcher/index.js.m0bak" ]; then
  cp "$APP/app_launcher/index.js.m0bak" "$APP/app_launcher/index.js"
  echo "已恢复 index.js"
fi
head -3 "$APP/app_launcher/index.js" 2>/dev/null

echo "== 5) 移除我们拷进去的 NapCat 文件（保留 /root/NapCat 原处） =="
for f in loadNapCat.js napcat.mjs node_modules static worker native launcher.bat \
         launcher-elevate.ps1 launcher-user.bat launcher-win10.bat launcher-win10-user.bat \
         qqnt.json; do
  [ -e "$APP/$f" ] && rm -rf "$APP/$f" && echo "  removed $f"
done
echo "app 目录现在："
ls -1 "$APP" | head -12

echo "== 6) 起 Xvfb =="
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 3
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 7) 起纯 QQ（真基线） =="
cd /opt/QQ
rm -f /tmp/qq-base.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-base.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"
sleep 15
kill -0 $QPID 2>/dev/null && echo "BASE_QQ_ALIVE=yes" || echo "BASE_QQ_ALIVE=no"

echo "== 8) 基线日志（前 25 行） =="
head -25 /tmp/qq-base.log 2>/dev/null
echo "== 9) 有无 JS 错误 =="
grep -c 'Uncaught Exception' /tmp/qq-base.log 2>/dev/null || echo 0
grep -A3 'Uncaught Exception' /tmp/qq-base.log 2>/dev/null | head -8

cp /tmp/qq-base.log /mnt/host/qq-base.log 2>/dev/null
echo "V15_DONE"
