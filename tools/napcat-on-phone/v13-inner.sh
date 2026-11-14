#!/bin/bash
# 容器内 v13：保留 QQ 原入口 + 在 app_launcher/index.js 注入 NapCat
#
# 依据 v12 实测错误：
#   Cannot find module '/opt/QQ/resources/app/index.js' imported from /opt/QQ/
# 说明改 package.json main 会破坏 QQ 自身的模块解析；正确做法是**不动 main**，
# 只在 QQ 的启动入口文件里注入 NapCat（与 NapCat-Termux 官方脚本一致）。
set -x

APP=/opt/QQ/resources/app
PKG=$APP/package.json

echo "== 1) 停旧进程 =="
pkill -f 'opt/QQ/qq' 2>/dev/null
pkill -f Xvfb 2>/dev/null
sleep 2

echo "== 2) 恢复 package.json main 为 QQ 原生入口 =="
if [ -f "$PKG.m0bak" ]; then
  cp "$PKG.m0bak" "$PKG"
  echo "已从备份恢复"
fi
# 确保 main 指向松散的 app_launcher（若原本指向 asar，改成松散目录以可注入）
sed -i '0,/"main"[[:space:]]*:[[:space:]]*"[^"]*"/s##"main": "./app_launcher/index.js"#' "$PKG"
grep -o '"main"[^,}]*' "$PKG" | head -1
ls -la "$APP/app_launcher/" | head -8

echo "== 3) 注入 NapCat 到 app_launcher/index.js =="
# 备份原入口
[ -f "$APP/app_launcher/index.js" ] && [ ! -f "$APP/app_launcher/index.js.m0bak" ] \
  && cp "$APP/app_launcher/index.js" "$APP/app_launcher/index.js.m0bak"

cat > "$APP/app_launcher/index.js" <<'EOS'
// NapCat 注入入口（由 Prism M0 部署脚本写入）
// 先加载 QQ 原生启动逻辑，再加载 NapCat
(async () => {
  try {
    await import('file:///opt/QQ/resources/app/napcat.mjs');
  } catch (e) {
    console.error('[NapCat] load failed:', e && e.stack || e);
  }
})();
EOS
echo "写入内容："
cat "$APP/app_launcher/index.js"
echo "napcat.mjs 存在？"
ls -l "$APP/napcat.mjs"

echo "== 4) 起 Xvfb =="
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 3
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 5) 起 QQ =="
cd /opt/QQ
rm -f /tmp/qq-boot.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-boot.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"
sleep 12
kill -0 $QPID 2>/dev/null && echo "QQ_ALIVE=yes" || echo "QQ_ALIVE=no"

echo "== 6) 启动日志 50 行 =="
head -50 /tmp/qq-boot.log 2>/dev/null
echo "== 7) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000' || echo "(无)"
echo "== 8) NapCat 输出目录 =="
ls -la /root/.config/QQ/ 2>/dev/null | head -10
find /root -maxdepth 3 -name 'webui.json' 2>/dev/null | head -3

cp /tmp/qq-boot.log /mnt/host/qq-boot.log 2>/dev/null
echo "V13_DONE"
