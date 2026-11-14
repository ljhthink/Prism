#!/bin/bash
# 在 proot 容器内：修正 main 路径为 file:// URIs + 启动 Xvfb + 启动 QQ
echo "== 1) 修正 main 为 file:// 绝对 URI =="
TARGET=/opt/QQ/resources/app/package.json
[ -f "$TARGET.orig" ] || cp "$TARGET" "$TARGET.orig"

# Electron 会把 main 当相对路径拼到 app 目录，必须用 file://
ENDPOINT='file:///root/NapCat/loadNapCat.js'
sed -i "0,/\"main\"[[:space:]]*:[[:space:]]*\"[^\"]*\"/s##\"main\": \"$ENDPOINT\"#" "$TARGET"
grep -o '"main"[^,}]*' "$TARGET" | head -1

# loadNapCat.js 内部用 __dirname 拼 napcat.mjs，需确保它是绝对可寻址的
echo "loadNapCat.js 内容确认："
cat /root/NapCat/loadNapCat.js

echo "== 2) 启动 Xvfb（虚拟显示 :99） =="
pkill -f Xvfb 2>/dev/null
rm -f /tmp/.X99-lock
Xvfb :99 -screen 0 1080x2400x24 -nolisten tcp > /tmp/xvfb.log 2>&1 &
XPID=$!
sleep 3
if kill -0 $XPID 2>/dev/null; then
  echo "Xvfb 已启动 pid=$XPID"
  DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && echo "X 显示可用" || echo "（xdpyinfo 不可用，跳过校验）"
else
  echo "!! Xvfb 启动失败："
  tail -5 /tmp/xvfb.log
  # 退路：Linux QQ 支持 --headless? 试 ozone platform headless
fi
export DISPLAY=:99

echo "== 3) 启动 QQ（含 NapCat） =="
cd /opt/QQ
rm -f /tmp/qq-boot.log
./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
     --disable-software-rasterizer > /tmp/qq-boot.log 2>&1 &
QPID=$!
echo "QQ pid=$QPID display=$DISPLAY"

for i in $(seq 1 18); do
  sleep 5
  ALIVE=$(kill -0 $QPID 2>/dev/null && echo yes || echo no)
  P6099=$( (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -c 6099 )
  echo "[$((i*5))s] qq_alive=$ALIVE listen6099=$P6099"
  if [ "$P6099" -gt 0 ]; then echo "WEBUI_UP_AFTER=$((i*5))s"; break; fi
  if [ "$ALIVE" = "no" ]; then echo "QQ 已退出，停止观察"; break; fi
done

echo "== 4) 端口 =="
(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -E '6099|3000|3001' || echo "(无)"
echo "== 5) webui.json =="
cat /root/NapCat/config/webui.json 2>/dev/null | head -14 || echo "(未生成)"
echo "== 6) 启动日志尾部 =="
tail -25 /tmp/qq-boot.log 2>/dev/null
echo "OK_SMOKE_DONE"
