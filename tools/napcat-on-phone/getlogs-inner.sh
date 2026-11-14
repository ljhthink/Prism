#!/bin/bash
# 容器内：把所有可能的启动日志复制到 /mnt/host（= adb 的 /data/local/tmp），便于 PC 侧读取
OUT=/mnt/host
echo "== 1) QQ 启动日志 =="
if [ -f /tmp/qq-boot.log ]; then
  echo "size=$(wc -c < /tmp/qq-boot.log)"
  cp /tmp/qq-boot.log $OUT/qq-boot.log 2>/dev/null
  echo "--- 前 30 行 ---"
  head -30 /tmp/qq-boot.log
  echo "--- 后 40 行 ---"
  tail -40 /tmp/qq-boot.log
else
  echo "(无 /tmp/qq-boot.log)"
fi

echo "== 2) NapCat 自己的日志目录 =="
for d in /root/.config/QQ/napcat /root/NapCat/logs /root/.napcat /tmp/napcat; do
  [ -d "$d" ] && echo "DIR $d:" && ls -la "$d" | head -10
done

echo "== 3) 找所有 napcat 相关 log =="
find /root -maxdepth 4 -name '*.log' -newermt '-2 hours' 2>/dev/null | head -15

echo "== 4) Xvfb 日志 =="
tail -8 /tmp/xvfb.log 2>/dev/null || echo "(无)"

echo "== 5) QQ 与 NapCat 版本匹配检查 =="
echo "QQ 版本: $(dpkg -l | grep -i linuxqq | awk '{print $3}')"
echo "NapCat qqnt.json 期望: $(grep -o '"linuxVersion"[^,]*' /root/NapCat/qqnt.json 2>/dev/null)"
echo "NapCat package.json version: $(grep -o '"version"[^,]*' /root/NapCat/package.json 2>/dev/null | head -1)"

echo "== 6) 当前 main =="
grep -o '"main"[^,}]*' /opt/QQ/resources/app/package.json | head -1

echo "== 7) QQ 进程 =="
ps aux 2>/dev/null | grep -E 'opt/QQ/qq' | grep -v grep | head -5

echo "LOGS_COPIED"
