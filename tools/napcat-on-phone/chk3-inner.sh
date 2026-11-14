#!/bin/bash
# 全面检查 + 修正配置目录大小写问题
echo "== 1) 当前登录状态 =="
grep -aiE 'KickedOffLine|账号状态|登录成功|online|已上线' /tmp/qq-final.log 2>/dev/null | tail -6

echo "== 2) 目录结构（大小写）=="
echo "--- 小写 napcat ---"
ls -la /root/.config/QQ/napcat/ 2>/dev/null
echo "--- 大写 NapCat ---"
ls -la /root/.config/QQ/NapCat/ 2>/dev/null
echo "--- NapCat/data ---"
ls -la /root/.config/QQ/NapCat/data/ 2>/dev/null | head -8

echo "== 3) 所有 onebot 配置 =="
find /root/.config -name 'onebot11*' 2>/dev/null

echo "== 4) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '3000|3001|6099'

echo "== 5) OneBot 适配器日志 =="
grep -aiE 'AdapterManager|OneBot11|http|server|listen' /tmp/qq-final.log 2>/dev/null | tail -10

echo "== 6) 最后 12 行 =="
tail -12 /tmp/qq-final.log 2>/dev/null | tr -d '\033' | sed 's/\[[0-9;]*m//g'
echo "CHK3_DONE"
