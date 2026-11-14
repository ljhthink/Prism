#!/data/data/com.termux/files/usr/bin/bash
# run-forever：常驻启动 NapCat（Xvfb + QQ），脚本不退出以保持 proot 存活。
#
# 为什么必须常驻：proot 一旦退出，其挂载命名空间消失，容器内所有进程被内核回收。
# 因此"后台脱离"在 proot 下不可行 —— 必须让这个脚本一直运行。
#
# 用法（ZeroTermux）：
#   termux-wake-lock
#   bash /data/local/tmp/run-forever.sh
# 之后把 Termux 切到后台即可（不要按 CTRL+C、不要 exit）。
# 停止：CTRL+C

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
  --bind="$ROOTFS/etc/proc/.loadavg:/proc/loadavg" \
  --bind="/sys" --bind=/proc/self/fd/2:/dev/stderr \
  --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/0:/dev/stdin \
  --bind=/proc/self/fd:/dev/fd --bind=/proc \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/data/com.termux/files/usr/tmp:/tmp \
  --bind=/dev --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/bash /mnt/host/run-forever-inner.sh
