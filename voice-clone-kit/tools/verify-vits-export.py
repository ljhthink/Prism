# -*- coding: utf-8 -*-
"""TKN-LVC-014：导出产物的 PC 侧最小闭环（用**本机 sherpa_onnx** 直接加载微调后的 VITS）

目的：在动 Android 之前，先证明「训练 → 导出」的产物**真的能被 sherpa-onnx 加载并合成中文**，
并给出 RTF（这是 B 路线最关键、也最难事后补的证据）。

用法（base 环境，本机 sherpa_onnx 1.13.8）：
    python voice-clone-kit/tools/verify-vits-export.py
"""
import json
import os
import time
import wave

import numpy as np
import sherpa_onnx

OUT_DIR = os.path.join(
    r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
    "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene")
WAV_OUT = os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis", "vits-cyrene-sample.wav")
TEXTS = [
    "你好，我是昔涟。",
    "欢迎回来，我一直在等你呢。",
    "今天的星空特别亮，要不要一起去看看？",
]


def main():
    res = {"dir": OUT_DIR, "exists": os.path.isdir(OUT_DIR)}
    for f in ["model.onnx", "model.int8.onnx", "lexicon.txt", "tokens.txt"]:
        p = os.path.join(OUT_DIR, f)
        res[f] = round(os.path.getsize(p) / 1e6, 1) if os.path.isfile(p) else None

    rule_fsts = ",".join(
        os.path.join(OUT_DIR, f) for f in
        ["date.fst", "number.fst", "phone.fst", "new_heteronym.fst"]
        if os.path.isfile(os.path.join(OUT_DIR, f)))

    def build(model_file):
        cfg = sherpa_onnx.OfflineTtsConfig()
        cfg.model = sherpa_onnx.OfflineTtsModelConfig()
        cfg.model.num_threads = 4
        cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
            model=os.path.join(OUT_DIR, model_file),
            lexicon=os.path.join(OUT_DIR, "lexicon.txt"),
            tokens=os.path.join(OUT_DIR, "tokens.txt"),
            dict_dir=os.path.join(OUT_DIR, "dict"),
        )
        cfg.rule_fsts = rule_fsts
        return sherpa_onnx.OfflineTts(cfg)

    for variant in ["model.fixed.onnx", "model.int8.fixed.onnx"]:
        try:
            t0 = time.time()
            tts = build(variant)
            load_s = time.time() - t0
            sr = int(tts.sample_rate)
            rows = []
            full = []
            for t in TEXTS:
                t1 = time.time()
                a = tts.generate(t, sid=0, speed=1.0)
                el = time.time() - t1
                n = len(a.samples)
                full.extend(list(a.samples))
                rows.append(dict(text=t, elapsed_s=round(el, 2), audio_s=round(n / sr, 2),
                                 rtf=round(el / (n / sr), 2) if n else None))
            res[variant] = dict(load_s=round(load_s, 2), sample_rate=sr, utts=rows)
            if variant == "model.onnx":
                arr = np.array(full, dtype=np.float32)
                peak = float(np.max(np.abs(arr))) if arr.size else 0.0
                with wave.open(WAV_OUT, "wb") as w:
                    w.setnchannels(1)
                    w.setsampwidth(2)
                    w.setframerate(sr)
                    w.writeframes((np.clip(arr, -1, 1) * 32767).astype("<i2").tobytes())
                res["sample_wav"] = dict(path=WAV_OUT, seconds=round(len(full) / sr, 2),
                                         peak=round(peak, 4))
        except Exception as e:
            res[variant] = dict(error=type(e).__name__, detail=str(e)[:400])

    with open(os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis", "vits-export-verify.json"),
              "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    print("OK")


if __name__ == "__main__":
    main()
