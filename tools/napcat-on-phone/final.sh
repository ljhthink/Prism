#!/data/data/com.termux/files/usr/bin/bash
# 一键：杀掉所有旧的 qr-sync / run-forever / final 脚本 + 容器内 QQ/Xvfb，
# 然后单实例启动 NapCat。
#
# 为什么要杀：多实例抢同一 QQ 账号会互踢（check2 日志实证 [KickedOffLine]）。
# 本脚本在 Termux 侧 + 容器侧双向清理，确保只剩一个实例。
#
# 用法：bash /data/local/tmp/final.sh
# 停止：CTRL+C

SELF=$(basename "$0")
echo "===== 清理旧脚本 ====="

# 1) 杀掉所有同类 Termux 侧脚本（排除自己）
for d in /proc/[0-9]*; do
  pid=${d#/proc/}
  [ "$pid" = "$$" ] && continue
  [ "$pid" = "$PPID" ] && continue
  cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$cmd" in
    *qr-sync*|*run-forever*|*final.sh*|*qr-sdcard*|*m0-v*.sh*|*boot2-inner*|*v1[0-9]-inner*)
      echo "  kill ${pid}: ${cmd:0:70}"
      kill -9 "$pid" 2>/dev/null
      ;;
  esac
done

# 2) 杀掉容器内所有 QQ 与 Xvfb（跨 proot 实例）
for d in /proc/[0-9]*; do
  pid=${d#/proc/}
  [ "$pid" = "$$" ] && continue
  cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$cmd" in
    *opt/QQ/qq*|*Xvfb*)
      kill -9 "$pid" 2>/dev/null
      ;;
  esac
done
sleep 3

echo "剩余 qq=$(pgrep -fc 'opt/QQ/qq' 2>/dev/null || echo 0) xvfb=$(pgrep -fc Xvfb 2>/dev/null || echo 0)"

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
  /bin/bash /mnt/host/final-inner.sh
