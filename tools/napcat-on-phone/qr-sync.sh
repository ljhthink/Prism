#!/data/data/com.termux/files/usr/bin/bash
# 持续同步：每 8 秒把容器内最新 qrcode.png 复制到 /sdcard，保证 PC 侧拿到的总是新鲜二维码。
#
# 背景：NapCat 约每 2 分钟自动刷新二维码，而"人工复制→PC→扫码"一轮可能就超时。
# 本脚本让 /sdcard/qrcode.png 始终跟随最新，PC 侧只需在有效期内拉取。
#
# 用法（新会话）：bash /data/local/tmp/qr-sync.sh
# 停止：CTRL+C

ROOTFS="$HOME/bookworm-arm64"
unset LD_PRELOAD
proot \
  --bind=/vendor --bind=/system \
  --bind=/data/data/com.termux/files/usr \
  --bind=/storage --bind=/storage/self/primary:/sdcard \
  --bind=/data/data/com.termux/files/home \
  --bind=/data/data/com.termux/cache \
  --bind=/data/local/tmp:/mnt/host \
  --bind="$ROOTFS/tmp:/dev/shm" \
  --bind="$ROOTFS/etc/proc/.version:/proc/version" \
  --bind="$ROOTFS/etc/proc/.stat:/proc/stat" \
  --bind="$ROOTFS/etc/proc/.uptime:/proc/uptime" \
  --bind=/sys --bind=/proc/self/fd/2:/dev/stderr \
  --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/0:/dev/stdin \
  --bind=/proc/self/fd:/dev/fd --bind=/proc \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/data/com.termux/files/usr/tmp:/tmp \
  --bind=/dev --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/sh -c '
Q=/opt/QQ/resources/app/cache/qrcode.png
n=0
while true; do
  n=$((n+1))
  if [ -f "$Q" ]; then
    cp "$Q" /sdcard/qrcode.png 2>/dev/null
    echo "[$n] synced $(wc -c < "$Q") bytes @ $(date +%H:%M:%S)"
  else
    echo "[$n] no qrcode yet @ $(date +%H:%M:%S)"
  fi
  # 顺带打印最新解码 URL（便于兜底）
  grep -h "txz.qq.com" /tmp/qq-run.log 2>/dev/null | tail -1
  sleep 8
done
'
