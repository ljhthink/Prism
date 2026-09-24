# -*- coding: utf-8 -*-
"""TKN-LVC-016：epoch-100 与 epoch-500 的**客观对拍**（音色相似度之外的量级指标）。

为什么要单独写：`analyze-vits-quality.py` 的输入路径是写死的（指向
`voice-clone-kit/build-out/vits/listening/`），无法对拍两组新产物；而这次 A/B 的
核心问题恰恰是"续训到底有没有把'有气无力'改掉"。

指标口径与 `analyze-vits-quality.py` **逐字一致**（同一套 read_wav/analyze），
保证与 2026-09-20 的基线数字可比：

- `rms_dbfs`：整段响度 → 排除/坐实"音量小 = 有气无力"
- `speech_dur_s` + `chars_per_s`：去停顿后的实际语速（同文本同字数 ⇒ 可比语速）
- `rl_dyn_db`：90/10 分位动态范围 → "有气无力"的另一面（是否压扁成一条直线）
- `centroid_hz`：频谱重心 → 16kHz 带限下的明亮度

用法：python voice-clone-kit/tools/ab-vits-e100-vs-e500.py
"""
import json
import os
import sys
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
TEXTS = [
    "你好，我是昔涟。",
    "欢迎回来，我一直在等你呢。",
    "今天的星空特别亮，要不要一起去看看？",
    "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。",
    "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。",
]
TOTAL_CHARS = sum(len(t) for t in TEXTS)  # 含标点，与 make-vits-sample.py 的 5 句一致


def read_wav(p):
    with wave.open(p, "rb") as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        n = w.getnframes()
        data = w.readframes(n)
    a = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    return sr, a


def db(x):
    return round(float(20 * np.log10(max(float(x), 1e-9))), 1)


def analyze(path, chars=None, sr_out=16000):
    sr, a = read_wav(path)
    if a.size == 0:
        return {"file": os.path.basename(path), "error": "empty"}
    dur = len(a) / sr
    rms = np.sqrt(np.mean(a ** 2))
    peak = np.max(np.abs(a))
    win = max(int(sr * 0.04), 16)
    hop = max(int(sr * 0.02), 8)
    frames = [a[i:i + win] for i in range(0, max(len(a) - win, 1), hop)]
    st = np.array([np.sqrt(np.mean(f ** 2)) for f in frames]) if frames else np.array([0.0])
    st_db = 20 * np.log10(np.clip(st, 1e-9, None))
    voiced = st_db > -45
    v_db = st_db[voiced]
    spec = np.abs(np.fft.rfft(a * np.hanning(len(a))))
    freqs = np.fft.rfftfreq(len(a), 1 / sr)
    centroid = float((spec * freqs).sum() / max(spec.sum(), 1e-9))
    speech_dur = float(voiced.sum() * hop / sr)
    out = {
        "file": os.path.basename(path),
        "sr": sr,
        "dur_s": round(dur, 2),
        "rms_dbfs": db(rms),
        "peak_dbfs": db(peak),
        "centroid_hz": int(centroid),
        "voiced_ratio": round(float(voiced.mean()), 3),
        "speech_dur_s": round(speech_dur, 2),
        "rl_dyn_db": round(float(np.percentile(v_db, 90) - np.percentile(v_db, 10)), 1)
        if v_db.size else None,
    }
    if chars and speech_dur > 0:
        out["chars_per_s"] = round(chars / speech_dur, 2)
    # F0（自相关法，只统计有声帧）：直接对应"有气无力 / 音高漂移"
    f0 = []
    step = max(int(sr * 0.02), 1)
    for i in range(0, max(len(a) - 2 * step, 1), step):
        seg = a[i:i + 2 * step]
        seg = seg[seg.size // 2:] if False else seg
        if np.sqrt(np.mean(seg ** 2)) < 10 ** (-45 / 20):
            continue
        seg = seg - seg.mean()
        ac = np.correlate(seg, seg, mode="full")[seg.size - 1:]
        lo, hi = int(sr / 400), int(sr / 70)  # 70–400Hz
        if hi <= lo or hi >= ac.size:
            continue
        seg_ac = ac[lo:hi]
        if seg_ac.size == 0 or ac[0] <= 0:
            continue
        lag = int(np.argmax(seg_ac)) + lo
        if seg_ac.max() / ac[0] > 0.3:
            f0.append(sr / lag)
    if f0:
        out["f0_median_hz"] = round(float(np.median(f0)), 1)
        out["f0_p10_p90_hz"] = [round(float(np.percentile(f0, 10)), 1),
                                round(float(np.percentile(f0, 90)), 1)]
    return out


CANDIDATES = {
    "e100_fp32": "vits-cyrene-sample-fp32.wav",
    "e100_int8": "vits-cyrene-sample-int8.wav",
    "e500_fp32": "vits-cyrene-sample-e500-fp32.wav",
    "e500_int8": "vits-cyrene-sample-e500-int8.wav",
}

res = {"total_chars": TOTAL_CHARS, "note": "同一 5 句文本、同一 speed=1.0、同一 int8/fp32 变体"}
for tag, name in CANDIDATES.items():
    p = os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis\samples", name)
    res[tag] = analyze(p, TOTAL_CHARS) if os.path.exists(p) else {"file": name, "error": "missing"}

print(json.dumps(res, ensure_ascii=False, indent=1))
