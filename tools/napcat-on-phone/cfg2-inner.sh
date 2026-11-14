#!/bin/bash
# 检查 NapCat 登录状态与 OneBot 配置
echo "== 1) NapCat 进程内日志（最新） =="
find /root/.config/QQ -name '*.log' -newermt '-30 min' 2>/dev/null | head -10
ls -la /root/.config/QQ/napcat/ 2>/dev/null | head -10

echo "== 2) 所有 onebot 配置 =="
for f in $(find /root/.config -name 'onebot11*.json' 2>/dev/null | head -3); do
  echo "--- $f ---"
  cat "$f"
done

echo "== 3) webui.json =="
for f in $(find /root -maxdepth 6 -name 'webui.json' 2>/dev/null | head -3); do
  echo "--- $f ---"
  cat "$f" | head -8
done

echo "== 4) 容器内端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | head -12

echo "== 5) 容器内 curl 自测 =="
curl -s -o /dev/null -w 'webui=%{http_code}\n' -m 5 http://127.0.0.1:6099/webui/ 2>/dev/null
curl -s -o /dev/null -w 'onebot3000=%{http_code}\n' -m 5 http://127.0.0.1:3000/get_status 2>/dev/null

echo "== 6) 最近的 NapCat/QQ 日志行（从进程输出找） =="
ls -lt /root/.config/QQ/logs 2>/dev/null | head -5
find /root/.config/QQ -name '*.log' 2>/dev/null | head -8
echo "CFG2_DONE"
