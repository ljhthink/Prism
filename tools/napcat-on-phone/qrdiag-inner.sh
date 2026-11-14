#!/bin/bash
# 容器内：导出 QQ/NapCat 运行日志 + 查找二维码文件，复制到 /mnt/host（=adb 可读）
echo "== 1) /tmp 下的日志 =="
ls -la /tmp/*.log 2>/dev/null | head -10

echo "== 2) 拷贝 qq-run.log =="
for f in /tmp/qq-run.log /tmp/qq-v18.log /tmp/qq-nc2.log; do
  [ -f "$f" ] && cp "$f" /mnt/host/$(basename $f) && echo "copied $f ($(wc -c < "$f") bytes)"
done

echo "== 3) 找二维码文件 =="
find /opt/QQ /root -name 'qrcode*' -o -name '*qrcode*' 2>/dev/null | head -10

echo "== 4) 二维码解码 URL（从日志提取） =="
grep -h 'txz.qq.com\|二维码解码URL\|qrcode' /tmp/qq-*.log 2>/dev/null | tail -6

echo "== 5) 进程与端口 =="
pgrep -fa 'opt/QQ/qq' | head -3
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000|3001'

echo "== 6) 日志尾部 20 行 =="
tail -20 /tmp/qq-run.log 2>/dev/null

echo "QRDIAG_DONE"
