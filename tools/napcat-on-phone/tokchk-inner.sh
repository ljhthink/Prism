#!/bin/bash
echo "== 所有 onebot11 配置 =="
find /root/.config /opt/QQ -name 'onebot11*' 2>/dev/null

echo "== 内容 =="
for f in $(find /root/.config /opt/QQ -name 'onebot11*' 2>/dev/null); do
  echo "--- $f ---"
  cat "$f"
  echo
done
echo "TOKCHK_DONE"
