#!/bin/bash
# 容器内：只做最关键的两件事（不启动 Xvfb、不启动 QQ，避免挂起）
#   1) 把 QQ main 改成 file:///root/NapCat/loadNapCat.js
#   2) 校验 loadNapCat.js 能找到 napcat.mjs
echo "== A) 当前 main =="
grep -o '"main"[^,}]*' /opt/QQ/resources/app/package.json | head -2

echo "== B) 改写为 file:// URI =="
T=/opt/QQ/resources/app/package.json
[ -f "$T.orig" ] || cp "$T" "$T.orig"
sed -i '0,/"main"[[:space:]]*:[[:space:]]*"[^"]*"/s##"main": "file:///root/NapCat/loadNapCat.js"#' "$T"
grep -o '"main"[^,}]*' "$T" | head -2

echo "== C) loadNapCat.js 内容 =="
cat /root/NapCat/loadNapCat.js
echo "== D) napcat.mjs 是否就位 =="
ls -l /root/NapCat/napcat.mjs

echo "== E) node_modules 是否存在 =="
ls -d /root/NapCat/node_modules 2>/dev/null && echo "有 node_modules"

echo "PATH_PATCH_DONE"
