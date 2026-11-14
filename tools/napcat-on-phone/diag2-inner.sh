#!/bin/bash
# 容器内：查看上次 QQ 启动的崩溃原因 + 清理残留
echo "== 1) QQ 启动日志 =="
if [ -f /tmp/qq-boot.log ]; then
  echo "字节数：$(wc -c < /tmp/qq-boot.log)"
  echo "--- 最后 40 行 ---"
  tail -40 /tmp/qq-boot.log
else
  echo "(无 /tmp/qq-boot.log)"
fi

echo "== 2) Xvfb 日志 =="
tail -10 /tmp/xvfb.log 2>/dev/null || echo "(无)"

echo "== 3) 关键检查 =="
echo "DISPLAY 变量在容器内无效（每次新 shell），需启动时传"
ls -l /tmp/.X11-unix/ 2>/dev/null || echo "(无 X socket)"
pgrep -f Xvfb >/dev/null && echo "Xvfb 仍在运行: $(pgrep -f Xvfb | tr '\n' ' ')" || echo "Xvfb 已退出"

echo "== 4) QQ 相关依赖是否齐 =="
ldd /opt/QQ/qq 2>/dev/null | grep -i "not found" | head -10 || echo "ldd 无缺失（或不可用）"

echo "DIAG2_DONE"
