#!/data/data/com.termux/files/usr/bin/bash
# 一键：杀掉所有旧的 qr-sync / run-forever / final 脚本 + 容器内 QQ/Xvfb，
# 然后导出 NapCat 日志。
#
# 为什么要杀：多实例抢同一 QQ 账号会互踢（check2 日志实证 [KickedOffLine]）。
# 本脚本在 Termux 侧 + 容器侧双向清理，确保只剩一个实例。
#
# 用法：bash /data/local/tmp/logdump.sh
# 停止：CTRL+C

echo "===== 导出日志（不乱杀进程） ====="

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || { echo "!! 容器不存在"; exit 1; }

echo "===== 单实例启动 ====="
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
  --bind="$ROOTFS/etc/proc/.loadavg:/proc/loadavg" \
  --bind=/sys --bind=/proc/self/fd/2:/dev/stderr \
  --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/0:/dev/stdin \
  --bind=/proc/self/fd:/dev/fd --bind=/proc \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/data/com.termux/files/usr/tmp:/tmp \
  --bind=/dev --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/bash /mnt/host/tokchk-inner.sh 2>&1 | tee /sdcard/tokchk.txt
