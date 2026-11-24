"""UI v2 四区「设计稿 | 真机」并排图生成器（2026-11-21）。

只拼图，不判定通过——判定权交给用户看图（上一轮失败的根因是主 Agent 自己判"对齐"）。

用法：
    python scripts/ui-v2-side-by-side.py
输入：
    design/prism-ui-v1/screenshots/<design>.png   （设计稿 780x1688 基准）
    tmp-ui/shots/<device>.png                     （真机 1080x2400）
输出：
    design/prism-ui-v1/diff/<pair>.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.join(ROOT, "design", "prism-ui-v1", "screenshots")
SHOTS = os.path.join(ROOT, "tmp-ui", "shots")
OUT = os.path.join(ROOT, "design", "prism-ui-v1", "diff")

# (输出名, 设计稿文件, 真机文件, 标题)
PAIRS = [
    ("01-phone", "settings-phone.png", "phone.png", "手机操控"),
    ("02-qq", "settings-qq.png", "qq.png", "QQ 小号 · 主页面"),
    ("03-qq-schedule", "settings-qq-schedule.png", "qq-schedule.png", "QQ · 定时与主动消息"),
    ("04-qq-reply", "settings-qq-reply.png", "qq-reply.png", "QQ · 自动回复"),
    ("05-qq-stickers", "settings-qq-stickers.png", "qq-stickers.png", "QQ · 表情库"),
    ("06-voice", "settings-voice.png", "voice.png", "语音与形象（上半）"),
    ("07-voice-2", "settings-voice.png", "voice-3.png", "语音与形象（下半）"),
    ("08-system", "settings-system.png", "system.png", "网络、设备与关于"),
    ("09-search-sheet", "settings-system-sheet-search.png", "search-sheet.png", "搜索引擎增强（弹层）"),
    ("10-persona", "settings-persona.png", "persona.png", "角色与桌宠"),
]

TARGET_H = 1400          # 统一高度，避免一侧被缩得看不清
GAP = 24
LABEL_H = 56


def load(path):
    if not os.path.exists(path):
        return None
    return Image.open(path).convert("RGB")


def fit(img, height):
    w, h = img.size
    return img.resize((int(w * height / h), height), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 28)
    except Exception:
        font = ImageFont.load_default()

    made, skipped = [], []
    for name, design_file, device_file, title in PAIRS:
        d = load(os.path.join(DESIGN, design_file))
        p = load(os.path.join(SHOTS, device_file))
        if d is None or p is None:
            skipped.append((name, design_file if d is None else device_file))
            continue
        d, p = fit(d, TARGET_H), fit(p, TARGET_H)
        canvas = Image.new(
            "RGB",
            (d.width + p.width + GAP * 3, TARGET_H + LABEL_H + GAP * 2),
            (24, 25, 23),
        )
        draw = ImageDraw.Draw(canvas)
        draw.text((GAP, GAP // 2), f"{title}    左：设计稿    右：真机", fill=(230, 232, 225), font=font)
        canvas.paste(d, (GAP, LABEL_H + GAP))
        canvas.paste(p, (GAP * 2 + d.width, LABEL_H + GAP))
        out = os.path.join(OUT, f"{name}.png")
        canvas.save(out)
        made.append(name)

    print("generated:", len(made))
    for m in made:
        print("  ", m)
    if skipped:
        print("skipped (missing source):")
        for s in skipped:
            print("  ", s[0], "->", s[1])


if __name__ == "__main__":
    main()
