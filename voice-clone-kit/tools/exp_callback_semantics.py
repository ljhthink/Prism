# -*- coding: utf-8 -*-
"""LVC-2 调研实验：ZipVoice 生成回调的分块语义与首块延迟（PC 侧，sherpa-onnx python）

目的（对应方案 A 的机制依据核实）：
  E1 回调是否按「内部句子」逐块回调？块是**增量新音频**还是**累积音频**？
  E2 首块延迟（TTFC）占全部合成时间的比例，随 max_char_in_sentence 的变化？
  E3 与当前端实现（先切 16 字首段 + 40 字后续段）相比，纯流式的首声延迟对比。

输出：JSON（UTF-8），供 read 工具查阅。
"""
import json
import os
import sys
import time
import wave

import numpy as np
import sherpa_onnx

ROOT = r"D:\s0611\code\Prism\voice-clone-kit"
MODEL = os.path.join(ROOT, "build-out", "zipvoice", "local-model", "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia")
MODEL_FP32 = os.path.join(ROOT, "build-out", "zipvoice", "local-model", "sherpa-onnx-zipvoice-distill-fp32-zh-en-emilia")
VOCODER = os.path.join(ROOT, "build-out", "zipvoice", "local-model", "vocos_24khz.onnx")
PROMPT_WAV = os.path.join(ROOT, "assets", "minimax", "cyrene-clone-prompt.wav")
PROMPT_TXT = os.path.join(ROOT, "assets", "transcripts", "cyrene-clone-prompt-transcript.txt")

LONG_TEXT = (
    "欢迎回来，我一直在等你呢。今天的星空特别亮，要不要一起去看看？"
    "我知道你最近很忙，可是偶尔也要记得休息一下哦。"
    "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。"
    "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。"
)

out = {}


def read_prompt():
    with wave.open(PROMPT_WAV, "rb") as w:
        n = w.getnframes()
        sr = w.getframerate()
        raw = w.readframes(n)
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if w.getnchannels() if False else False:
        pass
    with open(PROMPT_TXT, "rb") as f:
        txt = f.read().decode("utf-8").strip()
    return samples, sr, txt


def build_tts(model_dir, num_threads=4):
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            zipvoice=sherpa_onnx.OfflineTtsZipvoiceModelConfig(
                tokens=os.path.join(model_dir, "tokens.txt"),
                encoder=os.path.join(model_dir, "encoder.int8.onnx"),
                decoder=os.path.join(model_dir, "decoder.int8.onnx"),
                vocoder=VOCODER,
                data_dir=os.path.join(model_dir, "espeak-ng-data"),
                lexicon=os.path.join(model_dir, "lexicon.txt"),
            ),
            num_threads=num_threads,
            debug=False,
        )
    )
    return sherpa_onnx.OfflineTts(cfg)


def make_cfg(prompt_samples, prompt_sr, prompt_txt, max_char, num_steps=8, guidance=3.0):
    c = sherpa_onnx.GenerationConfig()
    c.silence_scale = 1.0
    c.speed = 0.9
    c.reference_audio = [float(x) for x in prompt_samples.tolist()]
    c.reference_sample_rate = prompt_sr
    c.reference_text = prompt_txt
    c.num_steps = num_steps
    c.extra = {
        "max_char_in_sentence": str(max_char),
        "min_char_in_sentence": "10",
        "guidance_scale": str(guidance),
        "feat_scale": "0.1",
        "t_shift": "0.5",
        "target_rms": "0.1",
        "speed": "0.9",
    }
    return c


def n_of(samples):
    return int(samples.shape[0]) if hasattr(samples, "shape") else len(samples)


def probe(tts, prompt_samples, prompt_sr, prompt_txt, text, max_char, num_steps=8, guidance=3.0):
    chunks = []
    t0 = time.time()
    progress_seen = []

    def cb(samples, progress):
        chunks.append(
            dict(
                idx=len(chunks),
                n=n_of(samples),
                progress=float(progress),
                wall_ms=round((time.time() - t0) * 1000, 1),
                head=[float(x) for x in np.asarray(samples[:3]).tolist()],
                tail=[float(x) for x in np.asarray(samples[-3:]).tolist()],
            )
        )
        progress_seen.append(float(progress))
        return 1

    gen_cfg = make_cfg(prompt_samples, prompt_sr, prompt_txt, max_char, num_steps, guidance)
    audio = tts.generate(text, gen_cfg, cb)
    total_ms = (time.time() - t0) * 1000
    total_n = n_of(audio.samples)
    sum_chunks = sum(c["n"] for c in chunks)
    return dict(
        max_char_in_sentence=max_char,
        num_steps=num_steps,
        text_chars=len(text),
        n_chunks=len(chunks),
        total_samples=total_n,
        total_audio_sec=round(total_n / audio.sample_rate, 3),
        sum_chunk_samples=sum_chunks,
        chunks_are_incremental=(sum_chunks == total_n),
        sum_over_total_ratio=round(sum_chunks / total_n, 4) if total_n else None,
        total_ms=round(total_ms, 1),
        ttfc_ms=chunks[0]["wall_ms"] if chunks else None,
        rtf=round(total_ms / 1000.0 / (total_n / audio.sample_rate), 3) if total_n else None,
        sample_rate=audio.sample_rate,
        chunks=chunks,
    )


def main():
    prompt_samples, prompt_sr, prompt_txt = read_prompt()
    out["prompt"] = dict(sr=prompt_sr, n=int(prompt_samples.shape[0]),
                         sec=round(prompt_samples.shape[0] / prompt_sr, 3), text=prompt_txt)
    out["sherpa_onnx_version"] = getattr(sherpa_onnx, "__version__", "unknown")

    tts = build_tts(MODEL)
    out["sample_rate"] = int(tts.sample_rate)

    # 基线：无回调（整段一次性返回）
    t0 = time.time()
    base = tts.generate(LONG_TEXT, make_cfg(prompt_samples, prompt_sr, prompt_txt, 200))
    base_ms = (time.time() - t0) * 1000
    out["baseline_no_callback"] = dict(
        chars=len(LONG_TEXT), samples=n_of(base.samples),
        audio_sec=round(n_of(base.samples) / base.sample_rate, 3),
        total_ms=round(base_ms, 1),
        rtf=round(base_ms / 1000.0 / (n_of(base.samples) / base.sample_rate), 3),
    )

    out["probes"] = []
    for mc in [200, 40, 24, 16, 12]:
        out["probes"].append(probe(tts, prompt_samples, prompt_sr, prompt_txt, LONG_TEXT, mc))

    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "exp_callback_semantics.json"),
              "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("OK")


if __name__ == "__main__":
    main()
