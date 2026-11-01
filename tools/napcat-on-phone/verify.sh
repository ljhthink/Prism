#!/system/bin/sh
# 协议端健康检查（在手机 shell 或 adb shell 里跑）
#
# 用法：
#   adb -s <设备号> shell sh /data/local/tmp/verify.sh <token>
#   或经环境变量：adb shell "NAPCAT_TOKEN=xxx sh /data/local/tmp/verify.sh"
#
# **token 不再有硬编码默认值**（2026-11-01 安全审查 H4）：
# 本脚本会被打进 Prism 的 APK assets 并部署到 /data/local/tmp（该目录 0771、
# 文件 755 —— Termux 是另一个 uid，必须可读才能执行），任何本机 App 知道路径即可读。
# 因此凭据只能由调用方**运行时传入**，绝不落进仓库与安装包。
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
# 结果同时写 /sdcard/napcat-verify.txt（供 Prism 管家读取，闭环判据）。

T="${1:-${NAPCAT_TOKEN:-}}"
OUT=/sdcard/napcat-verify.txt
RESULT="PENDING"

echo "=== ports ==="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '3000|3001|6099'

if [ -z "$T" ]; then
  echo "!! 未传 token：跳过鉴权检查（用法：sh verify.sh <token>）"
  echo "VERIFY_SKIPPED_NO_TOKEN"
  echo "SKIP_NO_TOKEN" > "$OUT" 2>/dev/null
  exit 2
fi

echo "=== get_status ==="
STATUS=$(curl -s -m 8 -X POST http://127.0.0.1:3000/get_status \
  -H "Authorization: Bearer $T" -d '{}')
echo "$STATUS"

echo "=== login_info ==="
LOGIN=$(curl -s -m 8 -X POST http://127.0.0.1:3000/get_login_info \
  -H "Authorization: Bearer $T" -d '{}')
echo "$LOGIN"

echo "=== ws handshake (first frame) ==="
WS=$(curl -s -i -m 6 -o - \
  -H "Authorization: Bearer $T" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  http://127.0.0.1:3001/ 2>&1 | grep -E 'HTTP/|lifecycle' | head -3)
echo "$WS"

# 机器可读结论（只写判据词，不写 token / QQ 号 / 昵称）
case "$STATUS" in
  *'"online":true'*) ONLINE=1 ;;
  *) ONLINE=0 ;;
esac
case "$WS" in
  *101*|*lifecycle*) WSOk=1 ;;
  *) WSOk=0 ;;
esac
if [ "$ONLINE" = "1" ] && [ "$WSOk" = "1" ]; then
  RESULT="OK online=1 ws=1"
elif [ "$ONLINE" = "1" ]; then
  RESULT="PARTIAL online=1 ws=0"
else
  RESULT="FAIL online=0 ws=$WSOk"
fi

echo "$RESULT" > "$OUT" 2>/dev/null
echo "$RESULT"
echo "VERIFY_DONE"
