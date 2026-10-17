# -*- coding: utf-8 -*-
"""TKN-LVC-014 补充实验：**微调 VITS 的生成回调语义**是否与 ZipVoice 一致（决定能否复用流式播放）。

为什么必须实测：A 面（流式播放，ADR-054）的机制依据来自 ZipVoice 的
`sherpa-onnx/csrc/offline-tts-zipvoice-impl.h`。VITS 走的是**另一个实现**
`offline-tts-vits-impl.h`，其回调载荷是"每个内部句子"还是"累积缓冲"**未核实**。
若为累积缓冲而按增量处理，会把音频**重复播放**（比不流式更糟）。

判据：`sum(chunk samples) == 无回调时的整段 samples` ⇒ 增量；`>` ⇒ 累积。
"""
import json
import os

import numpy as np
import sherpa_onnx

D = os.path.join(r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
                 "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene")
OUT = os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis", "vits-callback-semantics.json")

TEXT = "欢迎回来，我一直在等你呢。今天的星空特别亮，要不要一起去看看？我知道你最近很忙，可是偶尔也要记得休息一下哦。"


def build():
    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = 4
    cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=os.path.join(D, "model.fixed.onnx"),
        lexicon=os.path.join(D, "lexicon.txt"),
        tokens=os.path.join(D, "tokens.txt"),
        dict_dir=os.path.join(D, "dict"),
    )
    return sherpa_onnx.OfflineTts(cfg)


def main():
    tts = build()
    sr = int(tts.sample_rate)
    res = {"sample_rate": sr, "text_chars": len(TEXT)}

    base = tts.generate(TEXT, sid=0, speed=1.0)
    total = len(base.samples)
    res["no_callback_samples"] = total
    res["no_callback_sec"] = round(total / sr, 2)

    chunks = []

    def cb(samples, progress):
        chunks.append(int(len(samples)))
        return 1

    tts.generate(TEXT, sid=0, speed=1.0, callback=cb)
    s = sum(chunks)
    res["with_callback"] = {
        "n_chunks": len(chunks),
        "chunk_samples": chunks,
        "chunk_sec": [round(c / sr, 2) for c in chunks],
        "sum_samples": s,
        "sum_over_total": round(s / total, 4) if total else None,
        "incremental": s == total,
        "cumulative_like": s > total,
    }

    # 决定性的对照：**同一次调用内**比较「回调块之和」与「该次返回的整段长度」。
    # 上一次实验跨两次调用比较，会被 VITS 的随机性（noise_scale>0 ⇒ 每次长度略不同）污染，
    # 实测 sum/other-call-total = 1.0228 —— 不足以判定语义，故补这一条。
    chunks2 = []
    a2 = tts.generate(TEXT, sid=0, speed=1.0, callback=lambda smp, p: (chunks2.append(int(len(smp))), 1)[1])
    s2 = sum(chunks2)
    n2 = len(a2.samples)
    res["same_call_control"] = {
        "n_chunks": len(chunks2),
        "chunk_samples": chunks2,
        "sum_samples": s2,
        "returned_samples": n2,
        "ratio": round(s2 / n2, 4) if n2 else None,
        "incremental": s2 == n2,
    }

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    print(json.dumps(res, ensure_ascii=False))


if __name__ == "__main__":
    main()
