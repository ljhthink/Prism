#!/bin/bash
# 容器内：检查/初始化 NapCat 的 OneBot 配置（HTTP 3000 + WS 3001 + token）
set -x
CFG_DIR=/root/.config/QQ/napcat/config
ls -la "$CFG_DIR" 2>/dev/null | head -12
echo "--- 所有 onebot 配置 ---"
find /root/.config -name 'onebot11*.json' 2>/dev/null | head -5
find /root -maxdepth 4 -name 'onebot11*.json' 2>/dev/null | head -5

echo "--- webui.json 位置 ---"
find /root -maxdepth 5 -name 'webui.json' 2>/dev/null | head -5
