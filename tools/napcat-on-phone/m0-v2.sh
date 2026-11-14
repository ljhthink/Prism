#!/data/data/com.termux/files/usr/bin/bash
# M0 续跑版：依赖已装好（TUNA 镜像），现在只需装 Linux QQ + NapCat。
#
# 背景（实测）：
#   - 官方脚本的 deb.debian.org 只有 ~30KB/s → 换清华 TUNA 后依赖 3 分钟装完 ✅
#   - 我猜的 QQ deb 文件名（260730/250630/240819）全部 404 → 必须从 GitHub 归档
#     (Rodert/qq-versions) 动态取真实文件名，且经 gh-proxy 下载（直连 GitHub 超时）
#
# 用法（ZeroTermux 里执行一行）：
#   bash /data/local/tmp/m0-v2.sh
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

echo "===== M0 v2 $(date) =====" >> "$LOG"
mark "RUNNING_V2"

ARCH=$(uname -m | sed s/aarch64/arm64/ | sed s/x86_64/amd64/)
ROOTFS="bookworm-$ARCH"
[ -f "$ROOTFS/root/.bashrc" ] || { say "错误：容器不存在，请先跑 m0.sh"; mark "FAIL_NO_CONTAINER"; exit 1; }

say "进入容器：装 Linux QQ（gh-proxy + 断点续传）→ 装 NapCat → 注入钩子"
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
export DEBIAN_FRONTEND=noninteractive
GH=https://gh-proxy.com

echo "== 0) 依赖自检 =="
if dpkg -l 2>/dev/null | grep -q "^ii  libgtk-3-0"; then
  echo "依赖已安装，跳过"
else
  echo "依赖缺失，补装（TUNA 源）"
  apt-get install -y --no-install-recommends libnss3 libnotify4 libsecret-1-0 libgbm1 \
    libasound2 fonts-wqy-zenhei gnutls-bin libglib2.0-dev libdbus-1-3 libgtk-3-0 \
    libxss1 libxtst6 libatspi2.0-0 libx11-xcb1 ffmpeg unzip jq curl xvfb
fi

echo "== 1) 安装 Linux QQ =="
cd /root
# 已装则跳过
if dpkg -l 2>/dev/null | grep -qi linuxqq; then
  echo "Linux QQ 已安装：$(dpkg -l | grep -i linuxqq | awk "{print \$3}")"
else
  # 从 GitHub 归档仓库动态取真实文件名（官方 dldir1 已 404）
  REL=$(curl -fsSL --max-time 60 https://api.github.com/repos/Rodert/qq-versions/releases/latest)
  TAG=$(echo "$REL" | jq -r .tag_name)
  DEB=$(echo "$REL" | jq -r ".assets[].name" | grep -E "arm64.*\.deb$" | head -1)
  echo "release=$TAG deb=$DEB"
  [ -n "$DEB" ] || { echo "!! 未找到 arm64 deb"; exit 21; }
  URL="$GH/https://github.com/Rodert/qq-versions/releases/download/$TAG/$DEB"
  echo "下载：$URL"
  # 断点续传：最多重试 6 次，每次 300s，失败不清空已有分片
  for i in 1 2 3 4 5 6; do
    curl -fL -C - --retry 2 --retry-delay 3 --max-time 300 -o qq.deb "$URL" && break
    echo "  第 $i 次中断，已下载 $(du -m qq.deb 2>/dev/null | cut -f1)MB，重试…"
    sleep 3
  done
  ls -l qq.deb
  echo "== 2) dpkg 安装 =="
  dpkg -i --force-depends qq.deb || apt-get -f install -y || true
  if dpkg -l 2>/dev/null | grep -qi linuxqq; then rm -f qq.deb; else echo "!! QQ 安装失败"; exit 22; fi
fi
ls -d /opt/QQ >/dev/null 2>&1 && echo "QQ 目录存在：$(ls -1 /opt/QQ | head -3 | tr "\n" " ")"

echo "== 3) 安装 NapCat Shell =="
if [ -x /root/NapCat.linux.arm64/napcat.sh ]; then
  echo "NapCat 已存在，跳过下载"
else
  REL2=$(curl -fsSL --max-time 60 https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest)
  NV=$(echo "$REL2" | jq -r .tag_name)
  echo "NapCat 最新版：$NV"
  ok=0
  for i in 1 2 3; do
    if curl -fL -C - --max-time 300 -o NapCat.zip "$GH/https://github.com/NapNeko/NapCatQQ/releases/download/$NV/NapCat.Shell.zip"; then ok=1; break; fi
    echo "  重试 $i"; sleep 3
  done
  [ "$ok" = "1" ] || { echo "!! NapCat 下载失败"; exit 23; }
  rm -rf NapCat.linux.arm64
  unzip -q -o NapCat.zip -d NapCat.linux.arm64
  chmod +x NapCat.linux.arm64/napcat.sh
  rm -f NapCat.zip
fi
echo "NapCat 文件数：$(ls -1 /root/NapCat.linux.arm64 | wc -l)"

echo "== 4) 注入启动钩子 =="
echo "(async () => {await import(\"file:///root/NapCat.linux.arm64/napcat.mjs\");})();" \
  > /opt/QQ/resources/app/app_launcher/index.js
head -c 110 /opt/QQ/resources/app/app_launcher/index.js; echo

echo "== 5) 汇总 =="
dpkg -l 2>/dev/null | grep -i linuxqq | awk "{print \"linuxqq:\", \$3}"
ls -1 /root/NapCat.linux.arm64/napcat.sh
echo "OK_ALL_DONE"
' >>"$LOG" 2>&1
RC=$?

if [ $RC -eq 0 ] && grep -q "OK_ALL_DONE" "$LOG"; then
  say "安装完成"
  mark "OK_PENDING_WEBUI_CHECK"
else
  say "失败（RC=$RC）"
  mark "FAIL_V2_RC$RC"
fi

echo ""
echo "================ M0 v2 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
tail -18 "$LOG"
echo "============================================"
echo "下一步：浏览器开 http://127.0.0.1:6099/webui 看能否出登录页"
