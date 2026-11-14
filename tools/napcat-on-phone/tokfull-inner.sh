#!/bin/bash
echo "== 生效配置完整内容 =="
cat /opt/QQ/resources/app/config/onebot11_3904755943.json
echo
echo "== 各 token 汇总 =="
grep -o '"token"[[:space:]]*:[[:space:]]*"[^"]*"' /opt/QQ/resources/app/config/onebot11_3904755943.json
echo "TOKFULL_DONE"
