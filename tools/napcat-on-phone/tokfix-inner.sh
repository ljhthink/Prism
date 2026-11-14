#!/bin/bash
# 把 WS token 统一成 HTTP token，交给 NapCat 热重载后立即验证
CFG=/opt/QQ/resources/app/config/onebot11_3904755943.json
HTTP_TOK="QQgiGKiNyi29k5.T"
WS_TOK="yoWIOqS~gXZ-4u.J"

echo "== 1) 备份 =="
[ -f "$CFG.bak" ] || cp "$CFG" "$CFG.bak"
echo "备份：$(ls -l "$CFG.bak" | awk '{print $5}') bytes"

echo "== 2) 把 WS 的 token 改为与 HTTP 一致 =="
# 用 python 不存在，用 sed 精确替换 WS 那个 token 字符串
sed -i "s|$WS_TOK|$HTTP_TOK|g" "$CFG"
echo "替换后 token 汇总："
grep -o '"token"[[:space:]]*:[[:space:]]*"[^"]*"' "$CFG"

echo "== 3) 等待热重载（10s）=="
sleep 10

echo "== 4) HTTP 自测 =="
curl -s -m 8 -X POST http://127.0.0.1:3000/get_login_info \
  -H "Authorization: Bearer $HTTP_TOK" -d '{}'
echo

echo "== 5) WS 握手 + 首帧测试（token=$HTTP_TOK）=="
curl -s -i -m 6 -o - \
  -H "Authorization: Bearer $HTTP_TOK" \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  http://127.0.0.1:3001/ 2>&1 | tail -4
echo
echo "TOKFIX_DONE"
