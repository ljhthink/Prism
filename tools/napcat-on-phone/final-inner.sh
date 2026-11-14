#!/bin/bash
# 容器内：单实例启动 NapCat（禁止自动重启，避免自我踢下线）
#
# 根因（check2 日志实证）：
#   12:55:00 登录成功 → 12:55:15 [KickedOffLine] 账号已在另一台终端登录
#   OneBot11 配置其实加载成功了，是"多实例抢同一账号"把登录踢掉了。
#   自动重启循环在登录瞬间误判 QQ 已死 → 拉起第二个实例 → 互踢。
#
# 因此本脚本：彻底清理 → 只起一个 → 常驻但**不重启** → 每 8s 同步二维码

echo "== 1) 彻底清理所有实例 =="
pkill -9 -f 'opt/QQ/qq' 2>/dev/null
pkill -9 -f 'Xvfb :99' 2>/dev/null
sleep 3
echo "剩余 qq: $(pgrep -fc 'opt/QQ/qq')  剩余 Xvfb: $(pgrep -fc 'Xvfb :99')"
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
mkdir -p /tmp/xdg && chmod 700 /tmp/xdg

echo "== 2) 启动 Xvfb（单实例） =="
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb2.log 2>&1 &
sleep 4
echo "xvfb_pid=$(pgrep -f 'Xvfb :99' | head -1)"

echo "== 3) 启动 QQ（单实例，不重启） =="
cd /opt/QQ
rm -f /tmp/qq-final.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-final.log 2>&1 &

echo "qq_pid=$(pgrep -f 'opt/QQ/qq' | head -1)"

echo "== 4) 常驻：同步二维码 + 只观察不重启 =="
echo "=== 请在本终端等待，二维码会同步到 /sdcard/qrcode.png ==="
n=0
while true; do
  sleep 8
  n=$((n+1))
  Q=/opt/QQ/resources/app/cache/qrcode.png
  if [ -f "$Q" ]; then
    cp "$Q" /sdcard/qrcode.png 2>/dev/null
  fi
  # 每 30 秒报一次状态（不重启，避免踢号）
  if [ $((n % 4)) -eq 0 ]; then
    QN=$(pgrep -fc 'opt/QQ/qq')
    OB=$( (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -c ':3000' )
    echo "[$((n*8))s] qq_proc=$QN onebot3000=$OB qr=$(ls -l /sdcard/qrcode.png 2>/dev/null | awk '{print $5}')"
  fi
done
