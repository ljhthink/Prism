# -*- coding: utf-8 -*-
"""生成「微调后 VITS」的试听样本（fp32 与 int8 各一段），供人工判听音色/自然度。

用法：python voice-clone-kit/tools/make-vits-sample.py [<标签>] [<onnx 目录>]

  <标签>     输出文件后缀（默认 `e100`），产出 `vits-cyrene-sample-<标签>-{fp32,int8}.wav`
  <onnx 目录> 缺省为 epoch-100 的 `onnx-output/vits-cyrene`

  ⚠️ 标签与目录都必须显式给出（TKN-LVC-016）：本脚本原先用固定文件名，
  续训后的样本会**直接覆盖 epoch-100 基线**，A/B 就无据可依了。
"""
import os
import sys
import wave

import numpy as np
import sherpa_onnx

TAG = sys.argv[1] if len(sys.argv) > 1 else "e100"
D = (os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else
     os.path.join(r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
                  "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene"))
OUTDIR = r"D:\s0611\code\Prism\tmp\voice\lvc-analysis"

TEXTS = [
    "你好，我是昔涟。",
    "欢迎回来，我一直在等你呢。",
    "今天的星空特别亮，要不要一起去看看？",
    "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。",
    "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。",
]


def synth(model_file, out_wav):
    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = 4
    cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=os.path.join(D, model_file),
        lexicon=os.path.join(D, "lexicon.txt"),
        tokens=os.path.join(D, "tokens.txt"),
        dict_dir=os.path.join(D, "dict"),
    )
    cfg.rule_fsts = ",".join(os.path.join(D, f) for f in
                             ["date.fst", "number.fst", "phone.fst", "new_heteronym.fst"])
    tts = sherpa_onnx.OfflineTts(cfg)
    sr = int(tts.sample_rate)
    buf = []
    for t in TEXTS:
        a = tts.generate(t, sid=0, speed=1.0)
        buf.extend(list(a.samples))
        buf.extend([0.0] * int(sr * 0.25))  # 句间 250ms 便于判听
    arr = np.clip(np.array(buf, dtype=np.float32), -1, 1)
    with wave.open(out_wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((arr * 32767).astype("<i2").tobytes())
    return {"wav": out_wav, "sample_rate": sr, "seconds": round(len(arr) / sr, 2),
            "peak": round(float(np.max(np.abs(arr))), 4)}


res = {"tag": TAG, "dir": D}
for model, mtag in [("model.fixed.onnx", "fp32"), ("model.int8.fixed.onnx", "int8")]:
    try:
        res[mtag] = synth(model, os.path.join(OUTDIR, "vits-cyrene-sample-%s-%s.wav" % (TAG, mtag)))
    except Exception as e:
        res[mtag] = {"error": f"{type(e).__name__}: {e}"}

import json
with open(os.path.join(OUTDIR, "vits-sample-info-%s.json" % TAG), "w", encoding="utf-8") as f:
    json.dump(res, f, ensure_ascii=False, indent=2)
print(json.dumps(res, ensure_ascii=False))
