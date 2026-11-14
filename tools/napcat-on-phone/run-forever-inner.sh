#!/bin/bash
# 容器内：常驻运行 Xvfb + QQ(NapCat)，脚本不退出 → proot 不退出 → 进程不被回收
#
# 关键教训：之前用 setsid/nohup 让 QQ 脱离，但 **proot 一退出，挂载命名空间消失，
# 子进程随之被杀**。正确做法是让这个脚本**一直挂着**（标准 NapCat-Termux 用法：
# 终端留着，Termux 切后台 + wake-lock）。
set -x

echo "== 1) 清理旧进程 =="
pkill -9 -f 'opt/QQ/qq' 2>/dev/null
pkill -9 -f 'Xvfb :99' 2>/dev/null
sleep 2
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
mkdir -p /tmp/xdg && chmod 700 /tmp/xdg

echo "== 2) Xvfb =="
Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
sleep 4
echo "xvfb=$(pgrep -f 'Xvfb :99' | head -1)"

echo "== 3) 配置检查 =="
CFG=/root/.config/QQ/napcat/config/onebot11_3904755943.json
[ -f "$CFG" ] && grep -E '"(port|token)"' "$CFG" | head -6 || echo "!! 无 onebot 配置"

echo "== 4) 启动 QQ（前台子进程，随本脚本存活） =="
cd /opt/QQ
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         >> /tmp/qq-run.log 2>&1 &

# 等 NapCat 起来并生二维码
for i in $(seq 1 20); do
  sleep 3
  if [ -f /opt/QQ/resources/app/cache/qrcode.png ]; then
    cp /opt/QQ/resources/app/cache/qrcode.png /mnt/host/qrcode.png 2>/dev/null
    echo "QR_COPIED_AT=$((i*3))s"
    break
  fi
done
ls -l /mnt/host/qrcode.png 2>/dev/null

echo "== 5) 二维码已就绪，进入常驻 =="
echo "NAP CAT_RUNNING (不要关闭此终端；按 CTRL+C 停止)"

# 常驻：每 60 秒刷新二维码 & 检查存活，脚本不退出
tick=0
while true; do
  sleep 60
  tick=$((tick+1))
  if ! pgrep -f 'opt/QQ/qq' >/dev/null; then
    echo "[$tick] QQ 已退出，重新拉起"
    cd /opt/QQ
    env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
        ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
             --disable-software-rasterizer --ozone-platform=x11 \
             >> /tmp/qq-run.log 2>&1 &
  fi
  # 持续镜像最新二维码（登录前有效）
  [ -f /opt/QQ/resources/app/cache/qrcode.png ] && \
    cp /opt/QQ/resources/app/cache/qrcode.png /mnt/host/qrcode.png 2>/dev/null
  [ $((tick % 5)) -eq 0 ] && echo "[$tick min] alive qq=$(pgrep -fc 'opt/QQ/qq')"
done
