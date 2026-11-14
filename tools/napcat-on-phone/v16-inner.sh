#!/bin/bash
# v16：用 NapCat 的 qqnt.json 作为 QQ 的 package.json（这才是 shell 的正确部署方式）
#
# 前几轮的错误：把 NapCat zip 里**开发用的 package.json**（name=napcat、带 scripts）
# 拷进了 /opt/QQ/resources/app/，覆盖了 QQ 自身配置 → 报
#   Cannot find module '/opt/QQ/resources/app/index.js'
# 正确做法：zip 里的 qqnt.json 才是 shell 入口配置（name=qq-chat, main=./loadNapCat.js），
# 把它改 platform=linux / eleArch=arm64 后作为 package.json。
set -x

APP=/opt/QQ/resources/app
PKG=$APP/package.json
NAP=/root/NapCat

echo "== 1) 停进程 =="
pkill -f 'opt/QQ/qq' 2>/dev/null
pkill -f Xvfb 2>/dev/null
sleep 2

echo "== 2) 从 .orig 恢复 QQ 原始 package.json（留作备用） =="
[ -f "$PKG.orig" ] && cp "$PKG.orig" "$PKG" && echo restored

echo "== 3) 把 NapCat 文件合并进 app 目录（不含开发 package.json） =="
cd "$NAP" || exit 1
for f in loadNapCat.js napcat.mjs node_modules static worker native; do
  if [ -e "$NAP/$f" ]; then
    rm -rf "$APP/$f"
    cp -r "$NAP/$f" "$APP/$f" && echo "  copied $f"
  fi
done

echo "== 4) 用 qqnt.json 生成 app/package.json（platform=linux, eleArch=arm64） =="
sed -e 's/"platform": *"win32"/"platform": "linux"/' \
    -e 's/"eleArch": *"x64"/"eleArch": "arm64"/' \
    "$NAP/qqnt.json" > "$PKG"
echo "--- 生成结果关键字段 ---"
grep -E '"(name|main|platform|eleArch|buildVersion|isPureShell)"' "$PKG"

echo "== 5) 确认 QQ 原始 asar 仍在（NapCat 要从它加载 QQ 代码） =="
ls -l "$APP/application.asar" 2>/dev/null || echo "!! application.asar 缺失"
ls -1 "$APP" | head -14

echo "== 6) 起 Xvfb =="
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 3
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 7) 起 QQ（应加载 NapCat） =="
cd /opt/QQ
rm -f /tmp/qq-nc.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-nc.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"
sleep 15
kill -0 $QPID 2>/dev/null && echo "QQ_ALIVE=yes" || echo "QQ_ALIVE=no"

echo "== 8) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000' || echo "(无)"
echo "== 9) NapCat 关键字 =="
grep -iE 'napcat|webui|onebot|6099' /tmp/qq-nc.log 2>/dev/null | head -15
echo "== 10) JS 错误 =="
grep -A4 'Uncaught Exception' /tmp/qq-nc.log 2>/dev/null | head -10 || echo "(无)"
echo "== 11) 日志前 20 行 =="
head -20 /tmp/qq-nc.log 2>/dev/null

cp /tmp/qq-nc.log /mnt/host/qq-nc.log 2>/dev/null
echo "V16_DONE"
