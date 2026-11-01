#!/data/data/com.termux/files/usr/bin/bash
# 只做文件拷贝（不需要 proot）：容器 /tmp 已 bind 到 Termux 的 $PREFIX/tmp
L="$PREFIX/tmp/qq-final.log"
OUT=/sdcard/qq-final.log
if [ -f "$L" ]; then
  cp "$L" "$OUT"
  echo "copied $(wc -c < "$L") bytes -> $OUT"
else
  echo "NOT_FOUND: $L"
  ls -l "$PREFIX/tmp/" 2>/dev/null | head -10
fi
