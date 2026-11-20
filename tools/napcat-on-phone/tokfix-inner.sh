#!/bin/bash
# 把 WS token 统一成 HTTP token，交给 NapCat 热重载后立即验证
#
# 2026-11-18（远程提交前安全清理）：**token 不得硬编码在仓库里**。
# 原版把真机 HTTP/WS token 直接写在脚本第 4~5 行，随仓库公开即等于公开凭据
# （即使是 loopback 服务，也违反 rules/07 §19 密钥不入库）。
# 现在改为从环境变量读取，由使用者在 Termux 里显式传入：
#   PRISM_HTTP_TOK='<http token>' PRISM_WS_TOK='<ws token>' bash tokfix-inner.sh
CFG=/opt/QQ/resources/app/config/onebot11_3904755943.json
HTTP_TOK="${PRISM_HTTP_TOK:?需要设置环境变量 PRISM_HTTP_TOK（NapCat HTTP token）}"
WS_TOK="${PRISM_WS_TOK:?需要设置环境变量 PRISM_WS_TOK（NapCat 当前 WS token）}"

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
