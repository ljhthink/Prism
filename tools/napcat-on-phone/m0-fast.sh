#!/data/data/com.termux/files/usr/bin/bash
# M0 快速版：把 apt 源换成国内镜像（清华 TUNA）后重装依赖。
#
# 背景：官方 napcat.sh 用 deb.debian.org，实测只有 ~30KB/s，装完要 ~3 小时。
# 本脚本改用 TUNA 镜像，通常 5~40 分钟可完成。
#
# 用法（ZeroTermux 里执行一行）：
#   bash /data/local/tmp/m0-fast.sh
#
# 会做的事（全部在 proot 容器内）：
#   1. 备份并替换 /etc/apt/sources.list 为清华镜像
#   2. apt-get update + 安装 Linux QQ 依赖
#   3. 安装 Linux QQ（arm64 deb，多版本回退）
#   4. 下载最新 NapCat Shell（ghproxy 加速）并注入启动钩子
#   5. 打印版本信息，写状态到 $HOME/m0.status
#
# **依然不登录 QQ**，零账号风险。

set -o pipefail
LOG="$HOME/m0.log"
STATUS="$HOME/m0.status"

MIRROR() {
  for d in /sdcard /storage/emulated/0; do
    [ -d "$d" ] && cp "$LOG" "$d/m0.log" 2>/dev/null
    [ -f "$STATUS" ] && [ -d "$d" ] && cp "$STATUS" "$d/m0.status" 2>/dev/null
  done
}
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; MIRROR; }
mark() { echo "$1" > "$STATUS"; MIRROR; }

echo "===== M0 fast $(date) =====" >> "$LOG"
mark "RUNNING_FAST"

ARCH=$(uname -m | sed s/aarch64/arm64/ | sed s/x86_64/amd64/)
ROOTFS="bookworm-$ARCH"
[ -f "$ROOTFS/root/.bashrc" ] || { say "错误：容器不存在，请先跑 m0.sh 完成步骤1"; mark "FAIL_NO_CONTAINER"; exit 1; }

# 停掉还在跑的慢速 apt（同属 termux uid，可直接杀）。
# 注意：Termux 默认没有 pkill，改用 /proc 扫描 + kill；失败也不阻断（不影响后续换源重装）。
say "清理旧的 apt 进程（卡住的慢速下载）"
KILLED=0
for d in /proc/[0-9]*; do
  pid=${d#/proc/}
  [ "$pid" = "$$" ] && continue
  cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$cmd" in
    *"apt-get install"*|*"apt/methods/http"*|*"apt/methods/https"*|*"dpkg -i"*|*"napcat.sh"*)
      kill -9 "$pid" 2>/dev/null && KILLED=$((KILLED+1))
      ;;
  esac
done
say "已清理 $KILLED 个进程"
sleep 3

say "进入容器：换镜像源 → 装依赖 → 装 Linux QQ → 装 NapCat"
unset LD_PRELOAD
proot \
  --bind=/vendor --bind=/system \
  --bind=/data/data/com.termux/files/usr \
  --bind=/storage --bind=/storage/self/primary:/sdcard \
  --bind=/data/data/com.termux/files/home \
  --bind=/data/data/com.termux/cache \
  --bind=/data/dalvik-cache \
  --bind="$ROOTFS/tmp:/dev/shm" \
  --bind="$ROOTFS/etc/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap" \
  --bind="$ROOTFS/etc/proc/.vmstat:/proc/vmstat" \
  --bind="$ROOTFS/etc/proc/.version:/proc/version" \
  --bind="$ROOTFS/etc/proc/.uptime:/proc/uptime" \
  --bind="$ROOTFS/etc/proc/.stat:/proc/stat" \
  --bind="$ROOTFS/etc/proc/.loadavg:/proc/loadavg" \
  --bind="$ROOTFS/etc/proc/.bus/input/devices:/proc/bus/input/devices" \
  --bind="$ROOTFS/etc/proc/.modules:/proc/modules" \
  --bind=/sys --bind=/proc/self/fd/2:/dev/stderr \
  --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/0:/dev/stdin \
  --bind=/proc/self/fd:/dev/fd --bind=/proc \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/data/com.termux/files/usr/tmp:/tmp \
  --bind=/data/data/com.termux/files:"$ROOTFS/termux" \
  --bind=/dev --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink --kill-on-exit \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  /bin/bash -lc '
set -e
export DEBIAN_FRONTEND=noninteractive

echo "== 1) 换清华镜像 =="
cp -n /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true
cat > /etc/apt/sources.list <<EOF
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm-updates main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian-security bookworm-security main contrib non-free non-free-firmware
EOF
cat /etc/apt/sources.list

echo "== 2) apt update =="
apt-get update -o Acquire::Retries=3

echo "== 3) 安装依赖 =="
apt-get install -y --no-install-recommends \
  libnss3 libnotify4 libsecret-1-0 libgbm1 libasound2 fonts-wqy-zenhei \
  gnutls-bin libglib2.0-dev libdbus-1-3 libgtk-3-0 libxss1 libxtst6 \
  libatspi2.0-0 libx11-xcb1 ffmpeg unzip jq curl xvfb || \
  (echo "→ 首次安装失败，尝试 -f 修复"; apt-get -f install -y)

echo "== 4) 安装 Linux QQ (arm64) =="
cd /root
installed=0
for v in QQ_3.2.32_260730_arm64_01.deb QQ_3.2.30_250630_arm64_01.deb QQ_3.2.12_240819_arm64_01.deb; do
  echo "尝试 $v"
  if curl -fL --retry 2 --max-time 600 -o qq.deb "https://dldir1.qq.com/qqfile/qq/QQNT/Linux/$v"; then
    if dpkg -i --force-depends qq.deb; then installed=1; echo "已安装 $v"; rm -f qq.deb; break; fi
  fi
  rm -f qq.deb
done
if [ "$installed" != "1" ]; then
  echo "!! Linux QQ 三版本均失败"
  exit 21
fi
ls -l /opt/QQ/ 2>/dev/null | head -5 || true

echo "== 5) 安装 NapCat Shell =="
version=$(curl -fsSL --max-time 60 https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest | jq -r .tag_name)
echo "最新版本：$version"
ok=0
for base in "https://mirror.ghproxy.com/https://github.com" "https://github.com"; do
  if curl -fL --retry 2 --max-time 900 -o NapCat.zip "$base/NapNeko/NapCatQQ/releases/download/$version/NapCat.Shell.zip"; then ok=1; break; fi
done
[ "$ok" = "1" ] || { echo "!! NapCat 下载失败"; exit 22; }
rm -rf NapCat.linux.arm64
unzip -q -o NapCat.zip -d NapCat.linux.arm64
chmod +x NapCat.linux.arm64/napcat.sh
rm -f NapCat.zip
echo "NapCat 文件数：$(ls -1 NapCat.linux.arm64 | wc -l)"

echo "== 6) 注入启动钩子 =="
echo "(async () => {await import(\"file:///root/NapCat.linux.arm64/napcat.mjs\");})();" \
  > /opt/QQ/resources/app/app_launcher/index.js
head -c 100 /opt/QQ/resources/app/app_launcher/index.js; echo

echo "== 7) 版本汇总 =="
dpkg -l 2>/dev/null | grep -i linuxqq | head -2 || true
node -v 2>/dev/null || true
echo "OK_ALL_DONE"
' >>"$LOG" 2>&1
RC=$?

if [ $RC -eq 0 ]; then
  say "步骤完成（RC=0）"
  if grep -q "OK_ALL_DONE" "$LOG"; then mark "OK_PENDING_WEBUI_CHECK"; else mark "FAIL_UNKNOWN"; fi
else
  say "失败（RC=$RC），见日志末尾"
  mark "FAIL_INSTALL_RC$RC"
fi

echo ""
echo "================ M0 (fast) 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
tail -25 "$LOG"
echo "================================================"
echo ""
echo "下一篇：浏览器打开 http://127.0.0.1:6099/webui 看能否出登录页"
