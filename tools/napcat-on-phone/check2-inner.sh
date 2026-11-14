#!/bin/sh
# 检查 NapCat 登录状态 + OneBot 配置加载
Q=/opt/QQ/resources/app
echo "== 1) 登录状态（日志关键行） =="
grep -aiE 'login|登录|online|头像|账号|success|失败' /tmp/qq-run.log 2>/dev/null | tail -12

echo "== 2) OneBot 配置是否存在 =="
ls -la /root/.config/QQ/napcat/config/ 2>/dev/null | head -10

echo "== 3) 配置内容 =="
cat /root/.config/QQ/napcat/config/onebot11_3904755943.json 2>/dev/null | head -30

echo "== 4) NapCat 是否报告 onebot 启动 =="
grep -aiE 'onebot|http server|websocket|3000|3001|network' /tmp/qq-run.log 2>/dev/null | tail -12

echo "== 5) 日志最后 15 行 =="
tail -15 /tmp/qq-run.log 2>/dev/null | tr -d '\033' | sed 's/\[[0-9;]*m//g'

echo "CHECK2_DONE"
