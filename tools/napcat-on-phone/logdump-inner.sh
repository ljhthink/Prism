#!/bin/sh
# 导出 NapCat 运行日志关键行到 /sdcard
L=/tmp/qq-final.log
[ -f "$L" ] || L=/tmp/qq-run.log
echo "===== 日志文件：$L（$(wc -c < "$L" 2>/dev/null) bytes） ====="

echo "--- 登录/踢号 ---"
grep -aiE 'KickedOffLine|下线|登录|login|online|离线|扫码|二维码' "$L" 2>/dev/null | tail -12

echo "--- OneBot 网络 ---"
grep -aiE 'onebot|http server|websocket|3000|3001|network|Adapter' "$L" 2>/dev/null | tail -10

echo "--- 最后 20 行 ---"
tail -20 "$L" 2>/dev/null | tr -d '\033' | sed 's/\[[0-9;]*m//g'

echo "--- 端口 ---"
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E ':3000|:3001|:6099'
echo "LOGDONE"
