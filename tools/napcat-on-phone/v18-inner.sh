#!/bin/bash
# v18：完整重启 Xvfb + NapCat（v17 只重启了 QQ，Xvfb 已死 → QQ 起不来）
# 用 setsid + nohup 让进程脱离 proot 会话，避免 proot 退出时被杀
set -x

echo "== 1) 清理 =="
pkill -9 -f 'opt/QQ/qq' 2>/dev/null
pkill -9 -f 'Xvfb :99' 2>/dev/null
sleep 2
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
mkdir -p /tmp/xdg && chmod 700 /tmp/xdg

echo "== 2) 启动 Xvfb（setsid 脱离） =="
setsid Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 < /dev/null &
sleep 4
XPID=$(pgrep -f 'Xvfb :99' | head -1)
echo "xvfb_pid=$XPID"
ls -l /tmp/.X11-unix/ 2>/dev/null

echo "== 3) 确认配置已就绪 =="
CFG=/root/.config/QQ/napcat/config/onebot11_3904755943.json
if [ -f "$CFG" ]; then
  echo "onebot 配置存在："
  grep -E '"(port|token|enable)"' "$CFG" | head -8
else
  echo "!! 配置缺失"
fi

echo "== 4) 启动 QQ（setsid + nohup） =="
cd /opt/QQ
rm -f /tmp/qq-v18.log
setsid env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-v18.log 2>&1 < /dev/null &
sleep 20

echo "== 5) 进程 =="
pgrep -fa 'opt/QQ/qq' | head -3
QN=$(pgrep -fc 'opt/QQ/qq')
echo "qq_proc_count=$QN"

echo "== 6) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000|3001' || echo "(无)"

echo "== 7) OneBot 自测 =="
curl -s -m 6 -H "Authorization: Bearer PrismLocal2026" \
  -X POST http://127.0.0.1:3000/get_status -d '{}' 2>/dev/null
echo
curl -s -m 6 -H "Authorization: Bearer PrismLocal2026" \
  -X POST http://127.0.0.1:3000/get_login_info -d '{}' 2>/dev/null
echo

echo "== 8) NapCat 日志 =="
grep -iE 'napcat|onebot|webui|3000|3001|login|登录|online' /tmp/qq-v18.log 2>/dev/null | head -20
echo "== 9) 日志尾部 =="
tail -12 /tmp/qq-v18.log 2>/dev/null

cp /tmp/qq-v18.log /mnt/host/qq-v18.log 2>/dev/null
echo "V18_DONE"
