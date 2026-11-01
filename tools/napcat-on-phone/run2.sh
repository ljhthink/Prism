#!/data/data/com.termux/files/usr/bin/bash
# 快速登录版常驻启动（不扫码）。用法：bash /data/local/tmp/run2.sh ；停止：CTRL+C
#
# 与 final.sh 的差别：
#   1. 用 -q <QQ号> 快速登录（凭据已保存，见 2026-10-31 日志；号来自 run2-inner.sh，
#      可用环境变量 PRISM_QQ_ID 覆盖）
#   2. 每 5 秒把 NapCat 日志镜像到 /sdcard/napcat.log（PC 侧可直接读，无需用户代跑）
#   3. 每 10 秒写 /sdcard/napcat-status.txt（含是否被踢、3000 是否监听）

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || { echo "!! 容器不存在：$ROOTFS"; exit 1; }

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
  --bind="$ROOTFS/etc/proc/.loadavg:/proc/loadavg" \
  --bind=/sys --bind=/proc/self/fd/2:/dev/stderr \
  --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/0:/dev/stdin \
  --bind=/proc/self/fd:/dev/fd --bind=/proc \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/data/com.termux/files/usr/tmp:/tmp \
  --bind=/dev --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/bash /mnt/host/run2-inner.sh
