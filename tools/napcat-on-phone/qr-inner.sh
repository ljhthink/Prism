#!/bin/bash
echo "== 找二维码 =="
for f in /opt/QQ/resources/app/cache/qrcode.png /root/.config/QQ/cache/qrcode.png /root/qrcode.png; do
  if [ -f "$f" ]; then echo "FOUND $f ($(wc -c < $f) bytes)"; cp "$f" /mnt/host/qrcode.png; fi
done
ls -l /mnt/host/qrcode.png 2>/dev/null
echo "QR_DONE"