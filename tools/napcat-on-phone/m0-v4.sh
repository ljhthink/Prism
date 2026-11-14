#!/data/data/com.termux/files/usr/bin/bash
# M0 v4：修正 NapCat 注入方式（新版 Shell 结构变更）。
#
# 关键发现：NapCat 4.18.x 的 Linux 包已不再有 napcat.sh（那是 2024 年老结构），
# 入口是 loadNapCat.js + napcat.mjs，通过 **覆盖 QQ 的 qqnt.json（main 指向 loadNapCat.js）** 注入。
# 因此 v3 的 `chmod napcat.sh` 必然失败（文件不存在），但 QQ 本身已装好（3.2.32）。
#
# 用法（ZeroTermux）：
#   bash /data/local/tmp/m0-v4.sh
#
# 依然不登录 QQ，零账号风险。

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

echo "===== M0 v4 $(date) =====" >> "$LOG"
mark "RUNNING_V4"

ARCH=$(uname -m | sed s/aarch64/arm64/ | sed s/x86_64/amd64/)
ROOTFS="bookworm-$ARCH"
[ -f "$ROOTFS/root/.bashrc" ] || { say "容器不存在"; mark "FAIL_NO_CONTAINER"; exit 1; }

say "修正 NapCat 注入：解压到 /root/NapCat + 覆盖 QQ qqnt.json"

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
echo "== 1) 清理旧目录并重新解压到 /root/NapCat =="
rm -rf /root/NapCat /root/NapCat.linux.arm64 /root/NapCat.zip
cp /mnt/host/NapCat.Shell.zip /root/NapCat.zip || { echo "!! 复制失败"; exit 21; }
mkdir -p /root/NapCat
unzip -q -o /root/NapCat.zip -d /root/NapCat || { echo "!! 解压失败"; exit 22; }
rm -f /root/NapCat.zip
echo "顶层文件："
ls -1 /root/NapCat | head -20
echo "关键文件检查："
for f in loadNapCat.js napcat.mjs qqnt.json; do
  if [ -f "/root/NapCat/$f" ]; then echo "  OK $f ($(wc -c < /root/NapCat/$f) bytes)"; else echo "  MISSING $f"; fi
done

echo "== 2) 安装依赖（napcat.mjs 需要 express/ws，若已打包则跳过） =="
if [ -d /root/NapCat/node_modules ]; then
  echo "已自带 node_modules"
else
  echo "无 node_modules —— 但 napcat.mjs 已 bundle 依赖，通常无需安装"
fi

echo "== 3) 覆盖 QQ 的 qqnt.json（main -> loadNapCat.js） =="
QQJSON=/opt/QQ/resources/app/package.json
QQJSON2=/opt/QQ/resources/app/qqnt.json
ls -l /opt/QQ/resources/app/ 2>/dev/null | head -8
TARGET=""
for f in "$QQJSON2" "$QQJSON"; do
  [ -f "$f" ] && TARGET="$f" && break
done
if [ -z "$TARGET" ]; then
  echo "!! 未找到 QQ 的 package.json/qqnt.json"
  ls -1 /opt/QQ/resources/app/ 2>/dev/null | head -20
  exit 23
fi
echo "目标：$TARGET"
cp -n "$TARGET" "$TARGET.orig" 2>/dev/null || true
# 用 python/node 改写 main 字段（不引入 jq 依赖风险）
if command -v node >/dev/null 2>&1; then
  node -e "
const fs=require('fs');
const p=process.argv[1];
const j=JSON.parse(fs.readFileSync(p,'utf8'));
j.main='/root/NapCat/loadNapCat.js';
fs.writeFileSync(p, JSON.stringify(j,null,2));
console.log('改写后 main =', j.main);
" "$TARGET"
else
  echo "无 node，用 sed 兜底"
  sed -i "s#\"main\"[[:space:]]*:[[:space:]]*\"[^\"]*\"#\"main\": \"/root/NapCat/loadNapCat.js\"#" "$TARGET"
  grep -o "\"main\"[^,]*" "$TARGET" | head -2
fi
echo "--- 校验 ---"
grep -o "\"main\"[^,]*" "$TARGET" | head -2

echo "== 4) 汇总 =="
echo "napcat 入口：$(ls -l /root/NapCat/loadNapCat.js 2>/dev/null | awk "{print \$5\" bytes\"}")"
echo "qqnt main：$(grep -o "\"main\"[^,]*" "$TARGET" | head -1)"
echo "OK_ALL_DONE"
' >>"$LOG" 2>&1
RC=$?

if [ $RC -eq 0 ] && grep -q "OK_ALL_DONE" "$LOG"; then
  say "注入配置完成 ✅"
  mark "OK_READY_TO_START"
else
  say "失败（RC=$RC）"
  mark "FAIL_V4_RC$RC"
fi

echo ""
echo "================ M0 v4 结果 ================"
echo "STATUS: $(cat "$STATUS" 2>/dev/null)"
tail -22 "$LOG"
echo "============================================"
