#!/data/data/com.termux/files/usr/bin/bash
# 协议端**本地图片导出**（2026-11-15 新增；ADR-075 / PRD US-3134）
#
# ## 为什么需要它
#
# 真机取证：NapCat 的 rkey 三条通道全挂
#   `[Rkey] 所有服务均已禁用, 图片使用FallBack机制`
# → 图片 URL 用的是**硬编码占位 rkey** → 所有 `gchat.qpic.cn` 链接必然 `HTTP 400`。
# 但 **图片字节其实已经存在协议端容器里**——NapCat 收消息时就落盘了：
#   `/root/.config/QQ/nt_qq_*/nt_data/{Emoji/emoji-recv,nt_data/Pic}/<年-月>/Ori/<md5>.jpg`
# 本脚本负责把这层文件"捞"到共享存储，让 Prism 能读到（Prism 无 root，读不了 termux 私有目录）。
#
# ## 契约
#
# 入参：$1 = 图片文件名（形如 `523119BEA8C6C0749FD09A0359C0E1F2.jpg`，**只允许安全字符**）
# 输出：$APPDIR/prism-media.bin（原始字节）+ $APPDIR/prism-media.txt（`ok=1|0 ts=… size=…`）
#       $APPDIR 即 Prism 的 app-specific 外部目录（见下方"为什么改"）
# 退出：0=成功导出；非 0=失败（Prism 侧读 ok=0 得到可读原因）
#
# ## 安全
#
# - 参数**严格校验**（防命令注入 + 防路径穿越）：只接受 `[A-Za-z0-9_.-]{1,80}` 且必须以图片扩展名结尾；
# - 用 `find -name` 精确匹配文件名，不接受调用方传目录；
# - 只在容器内 `cp` 到 `/sdcard`（proot 已 bind 共享存储），不删改任何协议端文件。

set -u

NAME="${1:-}"
# 2026-11-15（真机取证后定案）：产物必须落在 **Prism 自己**的外部目录里。
#
# 为什么改：原方案写 `/sdcard/prism-media.bin`，但真机实测 Prism **读不了**
# （`exists=true canRead=false`）——文件由 root 创建为 `660 root:everybody`，
# 而 Prism 没有 MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE。
# 试过用 Shizuku（shell uid）中转 `cp` 到 /data/local/tmp：**同样失败**
# （`exec 失败：cp exit=1`，因为 Shizuku user service 缺 `sdcard_rw` 组）。
#
# 正解：Termux 持有 `MANAGE_EXTERNAL_STORAGE=allow`（真机 appops 已确认），
# 因此它可以写 **Prism 的 app-specific 外部目录**；而该目录属主是 Prism 自己
# （`u0_a165:sdcard_rw`），Prism 读它**不需要任何权限**。
# 2026-11-15 真机实测结论（最终定案）：
#   WRITABLE /sdcard            ← Termux 可写（有 MANAGE_EXTERNAL_STORAGE）
#   DENIED   /sdcard/Android/data/io.prism/files  ← Android 11+ 禁止跨应用写 app 目录
# 而 Prism 无存储权限、读不了 /sdcard → 由 Shizuku(shell) 中转 `cp` 到 /data/local/tmp
# （两者均已真机实测：Shizuku 读 /sdcard ✓、Prism 读 /data/local/tmp ✓）。
APPDIR=/sdcard
RESULT=$APPDIR/prism-media.txt
OUT=$APPDIR/prism-media.bin

fail() {
  TS=$(date +%s)
  printf 'ok=0 ts=%s size=0 reason=%s\n' "$TS" "$1" > "$RESULT" 2>/dev/null
  echo "prism-media: FAIL $1" >&2
  exit 1
}

# ---- 参数校验（安全边界，不可放宽）----
case "$NAME" in
  *[!A-Za-z0-9_.-]*) fail "illegal_chars" ;;
  *..*) fail "path_traversal" ;;
  # 前导 `-` 会被 find/cp 当成选项（单测 `-rf.jpg` 抓到过这个洞）
  -*) fail "illegal_leading_dash" ;;
esac
# 2026-11-15（guardrail R-04）：扩展名必须**大小写不敏感**。
# 旧写死小写 → `ABC.JPG` 被 Kotlin 侧放行（它已 lowercase 比较）却被这里拒绝，
# 本地回退 100% 失败。两端必须一致：都以 lower(ext) 判定。
EXT=$(printf '%s' "$NAME" | sed 's/.*\.//' | tr 'A-Z' 'a-z')
case "$EXT" in
  jpg|jpeg|png|gif|webp|bmp) : ;;
  *) fail "illegal_name" ;;
esac
printf '%s' "$NAME" | grep -qE '^[A-Za-z0-9_-]{1,74}\.[A-Za-z0-9]{2,5}$' \
  || fail "illegal_name"

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || fail "container_missing"

# 输出目录可能尚不存在（用户从未打开过相册等）——按需创建
mkdir -p "$APPDIR" 2>/dev/null
# 清掉旧产物，避免 Prism 读到上一次的字节
rm -f "$OUT" "$RESULT" 2>/dev/null

unset LD_PRELOAD
# proot 参数与 run2.sh 保持一致；只做 find + cp，不碰 QQ/NapCat 进程
#
# 2026-11-15（真机排障）：`find` 的搜索根与深度必须足够。QQ 的实际路径形如
#   /root/.config/QQ/nt_qq_<32位哈希>/nt_data/Emoji/emoji-recv/<年-月>/Ori/<md5>.jpg
# 从 `/root/.config/QQ` 起算约 7 层；留到 10 层更稳（多账号/不同版本目录结构不同）。
# stdout 全部收进 PROOT_OUT，失败时写进回执 —— 否则排障时"看不到脚本内部发生了什么"。
PROOT_OUT=$(
proot \
  --bind=/vendor --bind=/system \
  --bind=/data/data/com.termux/files/usr \
  --bind=/storage --bind=/storage/self/primary:/sdcard \
  --bind=/data/data/com.termux/files/home \
  --bind=/data/data/com.termux/cache \
  --bind=/dev --bind=/proc \
  --root-id --cwd=/root -L \
  --kernel-release=6.2.1-perf --sysvipc --link2symlink \
  --rootfs="$ROOTFS/" /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /bin/sh -c '
    # 注意：`env -i` 已清空环境变量，外层的 $APPDIR **不会**传进来
    # （真机踩过：内部 $APPDIR 为空 → cp 写到了 /prism-media.bin）。
    # 因此路径一律走位置参数传入。
    NAME="$1"
    APPDIR="$2"
    # 大小写不敏感匹配：QQ 常把小写 md5 落盘，而事件里给的是大写
    SRC=$(find /root/.config/QQ -maxdepth 10 -type f \
            \( -name "$NAME" -o -iname "$NAME" \) 2>/dev/null | head -1)
    if [ -z "$SRC" ]; then
      # 兜底：按"去掉扩展名的主体"模糊找（应对扩展名被改写：.jpg ↔ .png ↔ .gif）
      STEM="${NAME%.*}"
      SRC=$(find /root/.config/QQ -maxdepth 10 -type f -iname "$STEM.*" 2>/dev/null | head -1)
    fi
    if [ -z "$SRC" ]; then
      QQDIRS=$(ls -d /root/.config/QQ 2>/dev/null | wc -l)
      CNT=$(find /root/.config/QQ -maxdepth 10 -type f -iname "*.jpg" 2>/dev/null | wc -l)
      echo "NOT_FOUND qqdir=$QQDIRS jpg_total=$CNT home=$HOME"
      exit 2
    fi
    echo "SRC=$SRC"
    mkdir -p "$APPDIR" 2>/dev/null
    cp "$SRC" "$APPDIR/prism-media.bin" 2>&1 || exit 3
    echo "COPIED"
  ' prism "$NAME" "$APPDIR" 2>&1
)
RC=$?
# 把 proot 的内部输出落盘（诊断用；不含图片内容）
printf '%s\n' "$PROOT_OUT" > "$APPDIR/prism-media-debug.txt" 2>/dev/null

TS=$(date +%s)
if [ "$RC" -eq 0 ] && [ -s "$OUT" ]; then
  SIZE=$(wc -c < "$OUT" 2>/dev/null | tr -d ' ')
  printf 'ok=1 ts=%s size=%s reason=\n' "$TS" "$SIZE" > "$RESULT" 2>/dev/null
  echo "prism-media: OK size=$SIZE"
  exit 0
fi

case "$RC" in
  2) fail "not_found_in_container" ;;
  3) fail "copy_failed" ;;
  *) fail "proot_error_$RC" ;;
esac