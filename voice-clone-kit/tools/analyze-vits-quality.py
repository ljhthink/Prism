# -*- coding: utf-8 -*-
"""客观量化「有气无力 / 语速过快」：对合成样本与训练语料做同一套无参度量。

用法：C:\\Users\\ljh\\.conda\\envs\\vits38\\python.exe voice-clone-kit\\tools\\analyze-vits-quality.py
输出：JSON（RL-DYN = p90-p10 的短时 RMS 动态范围 dB，越小越"平/无力"；
      SPS = 字/秒（需外部给字数））。
"""
import glob
import json
import os
import wave

import numpy as np

ROOT = r"D:\s0611\code\Prism"


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


def analyze(path, chars=None):
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
    # 语音活动帧（>-45dBFS）
    voiced = st_db > -45
    v_db = st_db[voiced]
    spec = np.abs(np.fft.rfft(a * np.hanning(len(a))))
    freqs = np.fft.rfftfreq(len(a), 1 / sr)
    centroid = float((spec * freqs).sum() / max(spec.sum(), 1e-9))
    # 去停顿后的有效时长（语音帧数 × hop / sr）
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
    return out


SENT = ["你好，我是昔涟。", "欢迎回来，我一直在等你呢。", "今天的星空特别亮，要不要一起去看看？",
        "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。",
        "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。"]
TOTAL_CHARS = sum(len(s) for s in SENT)  # 含标点

res = {"sample_chars": TOTAL_CHARS, "sample_text_note": "5 句合计字符数（含标点）"}
for f in ["vits-cyrene-sample-fp32.wav",
          "vits-cyrene-sample-int8.wav"]:
    p = os.path.join(ROOT, "voice-clone-kit", "build-out", "vits", "listening", f)
    if os.path.exists(p):
        res[os.path.basename(p)] = analyze(p, TOTAL_CHARS)

# 训练语料（16kHz，与 VITS 输出同采样率）——取前 6 条做对照
ds = sorted(glob.glob(os.path.join(ROOT, "voice-clone-kit", "build-out", "vits", "dataset",
                                   "sampled_audio4ft", "*.wav")))[:6]
res["train_corpus"] = []
for p in ds:
    res["train_corpus"].append(analyze(p))

# ZipVoice 24kHz 参考音频（现役音色包的 prompt）
refs = sorted(glob.glob(os.path.join(ROOT, "voice-clone-kit", "build-out", "zipvoice", "voicepack", "cyrene",
                                     "refs", "*.wav")))[:4]
res["zipvoice_refs"] = [analyze(p) for p in refs if os.path.exists(p)]

print(json.dumps(res, ensure_ascii=False, indent=1))
