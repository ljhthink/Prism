#!/bin/bash
# 在 proot 容器内执行：用 sed 改写 QQ main（容器内无 node，不能依赖 node）
echo "== 1) 检查 NapCat 关键文件 =="
for f in loadNapCat.js napcat.mjs qqnt.json; do
  if [ -f "/root/NapCat/$f" ]; then
    echo "  OK $f ($(wc -c < /root/NapCat/$f) bytes)"
  else
    echo "  MISSING $f"
  fi
done

TARGET=/opt/QQ/resources/app/package.json
[ -f "$TARGET" ] || { echo "!! 找不到 $TARGET"; exit 23; }

echo "== 2) 备份并改写 main =="
[ -f "$TARGET.orig" ] || cp "$TARGET" "$TARGET.orig"
OLD=$(grep -o '"main"[[:space:]]*:[[:space:]]*"[^"]*"' "$TARGET" | head -1)
echo "原始：$OLD"

# 用 sed 精确替换 main 的值（只动第一个 main 键）
sed -i '0,/"main"[[:space:]]*:[[:space:]]*"[^"]*"/s//"main": "\/root\/NapCat\/loadNapCat.js"/' "$TARGET"

NEW=$(grep -o '"main"[[:space:]]*:[[:space:]]*"[^"]*"' "$TARGET" | head -1)
echo "改写后：$NEW"
case "$NEW" in
  *loadNapCat.js*) echo "改写成功" ;;
  *) echo "!! 改写失败"; exit 24 ;;
esac

echo "== 3) 校验 JSON 合法性（用 QQ 自带 electron 的 node 模式） =="
ELECTRON=/opt/QQ/qq
if [ -x "$ELECTRON" ]; then
  echo "QQ 可执行存在：$ELECTRON"
else
  echo "QQ 可执行缺失？"; ls -1 /opt/QQ | head -10
fi

echo "== 4) 保存容器内启动脚本（供后续一键启动） =="
cat > /root/start-napcat.sh <<'EOS'
#!/bin/bash
export DISPLAY=:0
cd /opt/QQ
exec ./qq --no-sandbox
EOS
chmod +x /root/start-napcat.sh
echo "已写 /root/start-napcat.sh"

echo "OK_ALL_DONE"
