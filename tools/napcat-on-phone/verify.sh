#!/system/bin/sh
# 协议端健康检查（在手机 shell 或 adb shell 里跑）
#
# 用法：
#   adb -s <设备号> shell sh /data/local/tmp/verify.sh
#   adb -s <设备号> shell sh /data/local/tmp/verify.sh <token>
#
# token 取值：NapCat 生效配置里的 HTTP token
#   /opt/QQ/resources/app/config/onebot11_<QQ号>.json → network.httpServers[0].token
# 注意：HTTP 与 WS 的 token 是两个独立字段，必须一致（WebUI 保存才触发热重载）。
#
# 判据（三项全绿才算协议端健康）：
#   1. 3000/3001/6099 三个端口都在 LISTEN
#   2. get_status 返回 online:true
#   3. WS 握手返回 101 且首帧是 meta_event.lifecycle.connect
#
# 若 get_status 返回 {"message":"token verify failed!"} → token 不对，不是协议端没起来。

T="${1:-QQgiGKiNyi29k5.T}"

echo "=== ports ==="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '3000|3001|6099'

echo "=== get_status ==="
curl -s -m 8 -X POST http://127.0.0.1:3000/get_status \
  -H "Authorization: Bearer $T" -d '{}'
echo

echo "=== login_info ==="
curl -s -m 8 -X POST http://127.0.0.1:3000/get_login_info \
  -H "Authorization: Bearer $T" -d '{}'
echo

echo "=== ws handshake (first frame) ==="
curl -s -i -m 6 -o - \
  -H "Authorization: Bearer $T" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  http://127.0.0.1:3001/ 2>&1 | tail -3
echo
echo "VERIFY_DONE"
