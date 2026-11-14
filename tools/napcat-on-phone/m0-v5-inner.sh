#!/bin/bash
# 在 proot 容器内执行：改写 QQ main 指向 NapCat
echo "== 1) 检查 NapCat =="
for f in loadNapCat.js napcat.mjs qqnt.json; do
  if [ -f "/root/NapCat/$f" ]; then
    echo "  OK $f ($(wc -c < /root/NapCat/$f) bytes)"
  else
    echo "  MISSING $f"
  fi
done

echo "== 2) 找 QQ 的 package.json =="
TARGET=""
for f in /opt/QQ/resources/app/package.json /opt/QQ/resources/app/qqnt.json; do
  if [ -f "$f" ]; then TARGET="$f"; break; fi
done
if [ -z "$TARGET" ]; then
  echo "!! 未找到 QQ package.json"
  ls -1 /opt/QQ/resources/app/ 2>/dev/null | head -15
  exit 23
fi
echo "目标：$TARGET"
echo "原始 main："
grep -o '"main"[^,}]*' "$TARGET" | head -2

echo "== 3) 用独立 JS 改写（避开 shell 引号） =="
which node || echo "no node in PATH"
node /mnt/host/patch-main.mjs "$TARGET" /root/NapCat/loadNapCat.js || exit 24

echo "== 4) 校验 =="
grep -o '"main"[^,}]*' "$TARGET" | head -2
echo "备份：$(ls -1 "$TARGET.orig" 2>/dev/null)"

echo "== 5) 试着启动一次（10 秒超时，验证能否加载） =="
cd /root/NapCat
timeout 12 node loadNapCat.js > /tmp/boot.log 2>&1
BRC=$?
echo "启动返回码：$BRC（124=超时说明进程活着，属正常）"
echo "--- 启动日志前 20 行 ---"
head -20 /tmp/boot.log 2>/dev/null
echo "--- 是否监听 6099 ---"
(ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null) | grep -E '6099|3000' || echo "（本次短启动未必来得及监听）"

echo "OK_ALL_DONE"
