# -*- coding: utf-8 -*-
"""ZipVoice（端侧克隆）PC 侧 RTF 归因对照：num_steps / 参考音频长度 / 线程数。

用法：C:\\Users\\ljh\\.conda\\envs\\vits38\\python.exe voice-clone-kit\\tools\\bench-zipvoice.py
输出：每个臂的 elapsed / audio 秒 / RTF（比值可外推到端侧，绝对值不可）。
"""
import json
import os
import time
import wave

import numpy as np
import sherpa_onnx

ROOT = r"D:\s0611\code\Prism"
PK = os.path.join(ROOT, "voice-clone-kit", "build-out", "zipvoice", "voicepack", "cyrene")
M = os.path.join(PK, "model")

TEXT = "今天的星空特别亮，要不要一起去看看？"

REFS = {
    "ref_03": ("以爱为始的故事，也一定以爱为终♪", 3.67),
    "ref_06": ("举手，我也会「魔术技巧」！看好咯，别眨眼…锵锵——送你一朵盛开的花♪", 7.95),
}


def read_wav(p):
    with wave.open(p, "rb") as w:
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    return sr, a


def build(num_threads):
    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = num_threads
    cfg.model.debug = False
    cfg.model.zipvoice = sherpa_onnx.OfflineTtsZipvoiceModelConfig(
        encoder=os.path.join(M, "encoder.onnx"),
        decoder=os.path.join(M, "decoder.onnx"),
        vocoder=os.path.join(M, "vocos_24khz.onnx"),
        tokens=os.path.join(M, "tokens.txt"),
        lexicon=os.path.join(M, "lexicon.txt"),
        data_dir=os.path.join(M, "espeak-ng-data"),
        feat_scale=0.1,
        t_shift=0.5,
        target_rms=0.1,
        guidance_scale=4.0,
    )
    cfg.silence_scale = 0.5
    return sherpa_onnx.OfflineTts(cfg)


def run(tts, ref_key, num_steps, speed=0.9, repeat=1):
    rtext, _ = REFS[ref_key]
    sr_ref, ra = read_wav(os.path.join(PK, "refs", ref_key + ".wav"))
    g = sherpa_onnx.GenerationConfig()
    g.reference_audio = ra
    g.reference_sample_rate = sr_ref
    g.reference_text = rtext
    g.num_steps = num_steps
    g.speed = speed
    g.silence_scale = 0.5
    best = None
    for _ in range(repeat):
        t0 = time.time()
        audio = tts.generate(TEXT, g)
        el = time.time() - t0
        asec = len(audio.samples) / audio.sample_rate
        rtf = el / asec if asec > 0 else -1
        if best is None or el < best[0]:
            best = (el, asec, rtf, float(np.max(np.abs(audio.samples))))
    return {"elapsed_s": round(best[0], 2), "audio_s": round(best[1], 2),
            "rtf": round(best[2], 3), "peak": round(best[3], 3)}


arms = [
    ("A_cur(steps8,ref7.95,thr2)", 8, "ref_06", 2),
    ("B_steps4", 4, "ref_06", 2),
    ("C_steps4+ref3.67", 4, "ref_03", 2),
    ("D_steps4+ref3.67+thr4", 4, "ref_03", 4),
    ("E_steps6+ref3.67+thr4", 6, "ref_03", 4),
]

out = {"text": TEXT, "text_chars": len(TEXT)}
made = {}
for name, steps, ref, thr in arms:
    try:
        if thr not in made:
            made[thr] = build(thr)
        out[name] = run(made[thr], ref, steps, repeat=1)
    except Exception as e:  # noqa: BLE001
        out[name] = {"error": "%s: %s" % (type(e).__name__, e)}

with open(os.path.join(ROOT, "tmp", "voice", "lvc-analysis", "zipvoice-bench.json"), "w", encoding="utf-8") as fh:
    json.dump(out, fh, ensure_ascii=False, indent=1)
print(json.dumps(out, ensure_ascii=False, indent=1))
