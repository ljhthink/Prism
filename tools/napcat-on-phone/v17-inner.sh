#!/bin/bash
# v17：写入 OneBot HTTP(3000) + WS(3001) 配置并重启 NapCat
#
# NapCat 的 onebot11 配置路径：/root/.config/QQ/napcat/config/onebot11_<QQ>.json
# 需要：httpServers[0] = 3000 + token；websocketServers[0] = 3001 + token
set -x

QQ_ID=3904755943
TOKEN=PrismLocal2026
CFG_DIR=/root/.config/QQ/napcat/config

echo "== 1) 找/建配置目录 =="
mkdir -p "$CFG_DIR"
ls -la "$CFG_DIR" 2>/dev/null | head -10

echo "== 2) 看看已有的 onebot 配置 =="
find /root/.config -name 'onebot11*.json' 2>/dev/null | head -5
for f in $(find /root/.config -name 'onebot11*.json' 2>/dev/null | head -2); do
  echo "--- $f ---"; cat "$f"
done

echo "== 3) 写配置 =="
TARGET="$CFG_DIR/onebot11_${QQ_ID}.json"
[ -f "$TARGET" ] && cp "$TARGET" "$TARGET.bak"
cat > "$TARGET" <<EOF
{
  "network": {
    "httpServers": [
      {
        "enable": true,
        "name": "prism-http",
        "host": "0.0.0.0",
        "port": 3000,
        "enableCors": true,
        "enableWebsocket": false,
        "messagePostFormat": "array",
        "token": "$TOKEN",
        "debug": false
      }
    ],
    "httpSseServers": [],
    "httpClients": [],
    "websocketServers": [
      {
        "enable": true,
        "name": "prism-ws",
        "host": "0.0.0.0",
        "port": 3001,
        "reportSelfMessage": false,
        "enableForcePushEvent": true,
        "messagePostFormat": "array",
        "token": "$TOKEN",
        "debug": false,
        "heartInterval": 30000
      }
    ],
    "websocketClients": [],
    "plugins": []
  },
  "musicSignUrl": "",
  "enableLocalFile2Url": false,
  "parseMultMsg": false
}
EOF
echo "已写入 $TARGET"
cat "$TARGET" | head -20

echo "== 4) 同时更新 webui.json 的 token（便于你登录 WebUI） =="
WJ=$(find /root/.config -name 'webui.json' 2>/dev/null | head -1)
if [ -n "$WJ" ]; then
  echo "webui 配置：$WJ"
  cat "$WJ" | head -8
  # 保持原 token，仅打印出来
  grep -o '"token"[^,}]*' "$WJ" | head -1
fi

echo "== 5) 重启 NapCat（杀 QQ 进程，重新起） =="
pkill -f 'opt/QQ/qq' 2>/dev/null
sleep 3
cd /opt/QQ
rm -f /tmp/qq-nc2.log
env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
    ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
         --disable-software-rasterizer --ozone-platform=x11 \
         > /tmp/qq-nc2.log 2>&1 &
QPID=$!
echo "qq_pid=$QPID"
sleep 20
kill -0 $QPID 2>/dev/null && echo "QQ_ALIVE=yes" || echo "QQ_ALIVE=no"

echo "== 6) 端口检查 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000|3001' || echo "(无)"
echo "== 7) OneBot 自测 =="
curl -s -m 6 -H "Authorization: Bearer $TOKEN" \
  -X POST http://127.0.0.1:3000/get_status -d '{}' 2>/dev/null
echo
echo "== 8) NapCat 日志关键字 =="
grep -iE 'napcat|onebot|webui|3000|3001|login|登录' /tmp/qq-nc2.log 2>/dev/null | head -20

cp /tmp/qq-nc2.log /mnt/host/qq-nc2.log 2>/dev/null
echo "V17_DONE"
