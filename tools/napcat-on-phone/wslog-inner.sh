#!/bin/bash
echo "== WS / 鉴权相关日志 =="
grep -aiE 'websocket|ws |auth|token|鉴权|连接|client' /tmp/qq-final.log 2>/dev/null | tail -20
echo
echo "== 最后 25 行 =="
tail -25 /tmp/qq-final.log 2>/dev/null | tr -d '\033' | sed 's/\[[0-9;]*m//g'
echo "WSDONE"
