# -*- coding: utf-8 -*-
"""VITS「语速过快 / 有气无力」根因量化 + 参数扫描。

做三件事：
 1. 训练语料本身的实际语速（字/秒）与 F0 统计 —— 判定"语速快"是语料固有还是推理参数导致；
 2. 合成样本 vs 语料的 F0（中位/音域）对比 —— 判定"有气无力"是否= 音高下移 + 音域收窄；
 3. speed / noise_scale_w 扫描 —— 给出可落地的参数取值。

用法：C:\\Users\\ljh\\.conda\\envs\\vits38\\python.exe voice-clone-kit\\tools\\tune-vits-params.py
"""
import glob
import json
import os
import re
import wave

import numpy as np
import sherpa_onnx

ROOT = r"D:\s0611\code\Prism"
KIT = os.path.join(ROOT, "voice-clone-kit")
TOOL = os.path.join(KIT, "build-out", "vits", "toolchain", "sherpa-onnx-vits-fine-tuning-webui",
                    "onnx-output", "vits-cyrene")
OUT = os.path.join(ROOT, "tmp", "voice", "lvc-analysis", "vits-tune")
os.makedirs(OUT, exist_ok=True)

SENT = ["你好，我是昔涟。", "欢迎回来，我一直在等你呢。", "今天的星空特别亮，要不要一起去看看？",
        "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。",
        "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。"]


def read_wav(p):
    with wave.open(p, "rb") as w:
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    return sr, a


def write_wav(p, sr, a):
    a = np.clip(a, -1, 1)
    with wave.open(p, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((a * 32767).astype("<i2").tobytes())


def f0_stats(a, sr, fmin=70.0, fmax=450.0):
    """自相关 F0（40ms 窗 / 10ms 跳）。返回中位与 p5/p95（Hz 与半音跨度）。"""
    win = int(sr * 0.04)
    hop = int(sr * 0.01)
    lo, hi = int(sr / fmax), int(sr / fmin)
    vals = []
    for i in range(0, max(len(a) - win, 1), hop):
        f = a[i:i + win]
        if len(f) < win:
            break
        if np.sqrt(np.mean(f ** 2)) < 0.01:
            continue
        f = f - f.mean()
        ac = np.correlate(f, f, mode="full")[win - 1:]
        if ac[0] <= 0:
            continue
        seg = ac[lo:hi]
        if seg.size == 0:
            continue
        lag = int(np.argmax(seg)) + lo
        if ac[lag] / ac[0] < 0.35:
            continue
        vals.append(sr / lag)
    if len(vals) < 10:
        return {"n": len(vals)}
    v = np.array(vals)
    p5, p50, p95 = np.percentile(v, [5, 50, 95])
    return {"n": len(v), "f0_med": round(float(p50), 1), "f0_p5": round(float(p5), 1),
            "f0_p95": round(float(p95), 1),
            "f0_range_st": round(float(12 * np.log2(p95 / p5)), 2)}


def activity(a, sr):
    win, hop = int(sr * 0.04), int(sr * 0.02)
    st = np.array([np.sqrt(np.mean(a[i:i + win] ** 2))
                   for i in range(0, max(len(a) - win, 1), hop)])
    voiced = st > 0.0056
    return float(voiced.sum() * hop / sr)


res = {}

# ---------- 1. 训练语料固有语速 ----------
man = os.path.join(KIT, "build-out", "vits", "dataset", "sampled_audio4ft.txt")
corpus = []
with open(man, encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("|")
        if len(parts) < 3:
            continue
        base = os.path.basename(parts[0])
        text = re.sub(r"\[ZH\]", "", parts[2]).strip()
        p = os.path.join(KIT, "build-out", "vits", "dataset", "sampled_audio4ft", base)
        if not os.path.exists(p):
            continue
        sr, a = read_wav(p)
        sp = activity(a, sr)
        cps = len(text) / sp if sp > 0 else 0.0
        corpus.append({"file": base, "chars": len(text), "speech_s": round(sp, 2),
                       "cps": round(cps, 2), **f0_stats(a, sr)})

cps_all = np.array([c["cps"] for c in corpus])
f0m_all = np.array([c["f0_med"] for c in corpus if "f0_med" in c])
f0r_all = np.array([c["f0_range_st"] for c in corpus if "f0_range_st" in c])
res["corpus"] = {
    "files": len(corpus),
    "cps_mean": round(float(cps_all.mean()), 2),
    "cps_p10": round(float(np.percentile(cps_all, 10)), 2),
    "cps_p90": round(float(np.percentile(cps_all, 90)), 2),
    "f0_med_mean": round(float(f0m_all.mean()), 1),
    "f0_range_st_mean": round(float(f0r_all.mean()), 2),
    "sample": corpus[:3],
}

# ---------- 2. 现役合成样本 ----------
for tag in ["fp32", "int8"]:
    p = os.path.join(KIT, "build-out", "vits", "listening", "vits-cyrene-sample-%s.wav" % tag)
    if not os.path.exists(p):
        continue
    sr, a = read_wav(p)
    sp = activity(a, sr)
    res["current_" + tag] = {"speech_s": round(sp, 2),
                             "cps": round(sum(len(s) for s in SENT) / sp, 2),
                             **f0_stats(a, sr)}


# ---------- 3. 参数扫描 ----------
def make_tts(model_file, speed=1.0, noise_scale=0.667, noise_scale_w=0.8):
    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = 4
    cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=os.path.join(TOOL, model_file),
        lexicon=os.path.join(TOOL, "lexicon.txt"),
        tokens=os.path.join(TOOL, "tokens.txt"),
        dict_dir=os.path.join(TOOL, "dict"),
        noise_scale=noise_scale,
        noise_scale_w=noise_scale_w,
        length_scale=1.0,
    )
    cfg.rule_fsts = ",".join(os.path.join(TOOL, f) for f in
                             ["date.fst", "number.fst", "phone.fst", "new_heteronym.fst"])
    return sherpa_onnx.OfflineTts(cfg)


sweep = []
for speed in [1.0, 0.9, 0.85, 0.8]:
    try:
        tts = make_tts("model.fixed.onnx", speed=speed)
        sr = int(tts.sample_rate)
        buf = []
        for t in SENT:
            a = tts.generate(t, sid=0, speed=speed)
            buf.extend(list(a.samples))
            buf.extend([0.0] * int(sr * 0.25))
        arr = np.array(buf, dtype=np.float32)
        sp = activity(arr, sr)
        wav = os.path.join(OUT, "speed-%.2f.wav" % speed)
        write_wav(wav, sr, arr)
        sweep.append({"speed": speed, "wav": wav,
                      "cps": round(sum(len(s) for s in SENT) / sp, 2),
                      **f0_stats(arr, sr)})
    except Exception as e:  # noqa: BLE001
        sweep.append({"speed": speed, "error": "%s: %s" % (type(e).__name__, e)})
res["speed_sweep"] = sweep

_out = os.path.join(ROOT, "tmp", "voice", "lvc-analysis", "vits-tune-result.json")
with open(_out, "w", encoding="utf-8") as fh:
    json.dump(res, fh, ensure_ascii=False, indent=1)
print(json.dumps({k: v for k, v in res.items() if k != "corpus"}, ensure_ascii=False, indent=1))
print("corpus_summary=", json.dumps({k: v for k, v in res["corpus"].items() if k != "sample"},
                                    ensure_ascii=False))
