#!/data/data/com.termux/files/usr/bin/bash
# 把容器内 NapCat 的登录二维码复制到 /sdcard，供 PC 侧直接取用。
# 用法：bash /data/local/tmp/qr-get.sh
ROOTFS="$HOME/bookworm-arm64"
unset LD_PRELOAD
proot \
  --bind=/vendor --bind=/system \
  --bind=/data/data/com.termux/files/usr \
  --bind=/storage --bind=/storage/self/primary:/sdcard \
  --bind=/data/data/com.termux/files/home \
  --bind=/data/data/com.termux/cache \
  --bind=/data/dalvik-cache \
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
for f in /opt/QQ/resources/app/cache/qrcode.png /root/.config/QQ/cache/qrcode.png; do
  if [ -f "$f" ]; then
    cp "$f" /mnt/host/qrcode.png
    echo "COPIED $f ($(wc -c < "$f") bytes)"
  fi
done
ls -l /mnt/host/qrcode.png 2>/dev/null || echo "NO_QR_FILE"
'
