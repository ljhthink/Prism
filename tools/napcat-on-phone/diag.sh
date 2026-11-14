#!/data/data/com.termux/files/usr/bin/bash
# 检查 NapCat 解压结果与 QQ 安装是否可用（诊断用）
LOG="$HOME/m0.log"
ROOTFS="$HOME/bookworm-arm64"
M() {
  for d in /sdcard /storage/emulated/0; do
    [ -d "$d" ] && cp "$LOG" "$d/m0.log" 2>/dev/null
  done
}

{
echo "===== diagnose $(date) ====="
echo "--- 顶层目录 ---"
ls -la "$ROOTFS/root/NapCat.linux.arm64/" | head -30
echo "--- 查找 napcat.sh / napcat.mjs ---"
find "$ROOTFS/root/NapCat.linux.arm64/" -maxdepth 3 -name 'napcat*' 2>/dev/null | head -10
echo "--- QQ 可执行 ---"
ls -l "$ROOTFS/opt/QQ/qq" 2>/dev/null || ls -l "$ROOTFS/opt/QQ/" 2>/dev/null | head -6
echo "--- 钩子 ---"
cat "$ROOTFS/opt/QQ/resources/app/app_launcher/index.js" 2>/dev/null
echo
} >> "$LOG" 2>&1
M
echo "诊断已写入日志"
