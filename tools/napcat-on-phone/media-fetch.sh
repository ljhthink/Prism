#!/data/data/com.termux/files/usr/bin/bash
# 协议端**本地图片导出**（2026-11-15；ADR-075 / PRD US-3134）
#
# ## 为什么需要它
#
# 真机取证：NapCat 的 rkey 三条通道全挂
#   `[Rkey] 所有服务均已禁用, 图片使用FallBack机制`
# → 图片 URL 用**硬编码占位 rkey** → 所有 `gchat.qpic.cn` 链接必然 `HTTP 400`。
# 但图片字节通常在协议端容器里（NapCat 收消息时会落盘）：
#   `/root/.config/QQ/nt_qq_*/nt_data/{Emoji/emoji-recv,Pic}/<年-月>/Ori/<md5>.jpg`
# 本脚本把文件"捞"到共享存储，让 Prism 能读到（Prism 无 root，读不了 termux 私有目录）。
#
# ## 真机踩过的坑（勿回退）
#
# 1. **Termux 只能写 `/sdcard`**：写 `Android/data/io.prism/files` 会被
#    Android 11+ 跨应用限制拒绝（实测 DENIED）。产物落 /sdcard，由 Shizuku 中转。
# 2. **`env -i` 会清空环境**：proot 内拿不到外层 `$APPDIR`（曾导致写到 `/`）。
#    因此路径一律用**位置参数**传入。
# 3. **NapCat 给出 file 名 ≠ 字节已落盘**：刚收到消息时 `find` 会 not_found，
#    QQ 还在后台下载。必须**重试等待**（见 WAIT_SECONDS）。
# 4. 扩展名大小写：事件里是大写 md5，落盘常为小写，必须 `-iname`。
#
# ## 契约
#
# 入参：$1 = 图片文件名（如 523119BEA8C6C0749FD09A0359C0E1F2.jpg）
# 输出：/sdcard/prism-media.bin（原始字节）+ /sdcard/prism-media.txt（回执）
# 退出：0=成功；非 0=失败（回执带可读 reason）

set -u

NAME="${1:-}"
APPDIR=/sdcard
RESULT=$APPDIR/prism-media.txt
OUT=$APPDIR/prism-media.bin
DEBUG=$APPDIR/prism-media-debug.txt

# 等落盘的**绝对截止秒数**（不是轮数）。
#
# guardrail 第二轮 P1：上一版用"轮数"，而每轮含 2 次 `find -maxdepth 10` + sleep 1，
# 实测单轮 1.4~4s → 10 轮最长可达 40s，**超过桥接层 15s** 预算。
# 于是桥接放弃后本脚本仍在跑，变成孤儿 proot 并继续写全局共享路径（串图低频复发）。
# 现在改为绝对截止：无论 find 多慢，总时长封顶在此值，且明显小于桥接预算。
WAIT_SECONDS=8

fail() {
  TS=$(date +%s)
  printf 'ok=0 ts=%s size=0 reason=%s\n' "$TS" "$1" > "$RESULT" 2>/dev/null
  echo "prism-media: FAIL $1" >&2
  exit 1
}

# ---- 参数校验（安全边界，不可放宽；与 Kotlin 侧 sanitizeName 双端一致）----
case "$NAME" in
  *[!A-Za-z0-9_.-]*) fail "illegal_chars" ;;
  *..*) fail "path_traversal" ;;
  -*) fail "illegal_leading_dash" ;;
esac
EXT=$(printf '%s' "$NAME" | sed 's/.*\.//' | tr 'A-Z' 'a-z')
case "$EXT" in
  jpg|jpeg|png|gif|webp|bmp) : ;;
  *) fail "illegal_name" ;;
esac

ROOTFS="$HOME/bookworm-arm64"
[ -d "$ROOTFS/root" ] || fail "container_missing"

rm -f "$OUT" "$RESULT" "$DEBUG" 2>/dev/null

unset LD_PRELOAD

# proot 参数与 run2.sh 保持一致；只做 find + cp，不碰 QQ/NapCat 进程。
# 末尾 `prism "$NAME" "$APPDIR"` 是位置参数（见坑 #2）。
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
    NAME="$1"
    APPDIR="$2"
    STEM="${NAME%.*}"
    SRC=""
    i=0
    LIMIT="$3"
    END=$(( $(date +%s) + LIMIT ))
    while [ "$(date +%s)" -lt "$END" ]; do
      SRC=$(find /root/.config/QQ -maxdepth 10 -type f \
              \( -name "$NAME" -o -iname "$NAME" \) 2>/dev/null | head -1)
      if [ -z "$SRC" ]; then
        SRC=$(find /root/.config/QQ -maxdepth 10 -type f \
                -iname "$STEM.*" 2>/dev/null | head -1)
      fi
      if [ -n "$SRC" ]; then break; fi
      i=$((i + 1))
      sleep 1
    done
    echo "WAITED=${i}次 剩余=$((END - $(date +%s)))s"
    if [ -z "$SRC" ]; then
      echo "NOT_FOUND name=$NAME"
      echo "--- nt_data ---"
      ls -la /root/.config/QQ/nt_qq_*/nt_data 2>&1 | head -20
      echo "--- emoji-recv Ori ---"
      ls -la /root/.config/QQ/nt_qq_*/nt_data/Emoji/emoji-recv/*/Ori 2>&1 | head -12
      echo "--- Pic Ori ---"
      ls -la /root/.config/QQ/nt_qq_*/nt_data/Pic/*/Ori 2>&1 | head -12
      echo "--- jpg count under /root ---"
      find /root -maxdepth 12 -type f -iname "*.jpg" 2>/dev/null | wc -l
      exit 2
    fi
    echo "SRC=$SRC"
    mkdir -p "$APPDIR" 2>/dev/null
    cp "$SRC" "$APPDIR/prism-media.bin" 2>&1 || exit 3
    echo "COPIED"
  ' prism "$NAME" "$APPDIR" "$WAIT_SECONDS" 2>&1
)
RC=$?

# 诊断输出只在**失败**时落盘（含 QQ 目录名/账号哈希，成功即清，不留残留）
case "$PROOT_OUT" in
  *NOT_FOUND*) printf '%s\n' "$PROOT_OUT" > "$DEBUG" 2>/dev/null ;;
  *) rm -f "$DEBUG" 2>/dev/null ;;
esac

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
