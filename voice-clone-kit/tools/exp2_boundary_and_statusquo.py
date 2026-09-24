# -*- coding: utf-8 -*-
"""LVC-2 实验二：分块边界不连续性 + 「现状（外部切段+串行）」的首声延迟与间隙

E5 分块边界不连续性：相邻块 tail/head 的样本跳变幅度（判定是否需要在块间加淡入淡出）
E6 现状仿真：第一段 16 字、后续 40 字，串行「合成→播完→合成」→ 首声延迟与每个间隙
"""
import json
import os
import time
import wave

import numpy as np
import sherpa_onnx

ROOT = r"D:\s0611\code\Prism\voice-clone-kit"
MODEL = os.path.join(ROOT, "build-out", "zipvoice", "local-model", "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia")
VOCODER = os.path.join(ROOT, "build-out", "zipvoice", "local-model", "vocos_24khz.onnx")
PROMPT_WAV = os.path.join(ROOT, "assets", "minimax", "cyrene-clone-prompt.wav")
PROMPT_TXT = os.path.join(ROOT, "assets", "transcripts", "cyrene-clone-prompt-transcript.txt")
OUT = os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis", "exp2_boundary_and_statusquo.json")

LONG_TEXT = (
    "欢迎回来，我一直在等你呢。今天的星空特别亮，要不要一起去看看？"
    "我知道你最近很忙，可是偶尔也要记得休息一下哦。"
    "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。"
    "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。"
)


def read_prompt():
    with wave.open(PROMPT_WAV, "rb") as w:
        raw = w.readframes(w.getnframes())
        sr = w.getframerate()
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    with open(PROMPT_TXT, "rb") as f:
        txt = f.read().decode("utf-8").strip()
    return samples, sr, txt


def make_cfg(ps, sr, pt, max_char, num_steps=8):
    c = sherpa_onnx.GenerationConfig()
    c.silence_scale = 1.0
    c.speed = 0.9
    c.reference_audio = [float(x) for x in ps.tolist()]
    c.reference_sample_rate = sr
    c.reference_text = pt
    c.num_steps = num_steps
    c.extra = {
        "max_char_in_sentence": str(max_char), "min_char_in_sentence": "10",
        "guidance_scale": "3.0", "feat_scale": "0.1", "t_shift": "0.5",
        "target_rms": "0.1", "speed": "0.9",
    }
    return c


def segment_for_synthesis(text, max_chars, first_max_chars):
    """与 Kotlin 侧 LocalCloneTextSegmenter 同规则（单测已钉住 join == text）。"""
    if max_chars <= 0 or len(text) <= max_chars:
        return [text]
    out, sb, last_clause = [], [], -1
    limit = first_max_chars if 1 <= first_max_chars <= max_chars else max_chars
    sent = "。！？!?."
    clause = "，；：,;:"
    for ch in text:
        sb.append(ch)
        if ch in clause:
            last_clause = len(sb)
        at_hard = len(sb) >= limit
        at_sent = ch in sent and len(sb) >= limit // 2
        if at_sent or at_hard:
            if at_sent:
                cut = len(sb)
            elif 1 <= last_clause < len(sb):
                cut = last_clause
            else:
                cut = len(sb)
            if cut > 0 and cut < len(sb) and 0xD800 <= ord(sb[cut - 1]) <= 0xDBFF:
                cut -= 1
            if cut <= 0:
                cut = len(sb)
            out.append("".join(sb[:cut]))
            sb = sb[cut:]
            last_clause = -1
            limit = max_chars
    if sb:
        if out and len(sb) <= 2 and all(c in sent or c in clause for c in sb):
            out[-1] += "".join(sb)
        else:
            out.append("".join(sb))
    return out


def main():
    ps, sr, pt = read_prompt()
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            zipvoice=sherpa_onnx.OfflineTtsZipvoiceModelConfig(
                tokens=os.path.join(MODEL, "tokens.txt"),
                encoder=os.path.join(MODEL, "encoder.int8.onnx"),
                decoder=os.path.join(MODEL, "decoder.int8.onnx"),
                vocoder=VOCODER,
                data_dir=os.path.join(MODEL, "espeak-ng-data"),
                lexicon=os.path.join(MODEL, "lexicon.txt"),
            ),
            num_threads=4, debug=False)))
    out = {"sample_rate": int(tts.sample_rate)}

    # ---- E5 分块边界不连续性 ----
    chunks = []
    def cb(samples, progress):
        chunks.append(np.array(samples, dtype=np.float32))
        return 1
    tts.generate(LONG_TEXT, make_cfg(ps, sr, pt, 200), cb)
    bnd = []
    for i in range(len(chunks) - 1):
        a, b = chunks[i], chunks[i + 1]
        bnd.append(dict(
            idx=i,
            tail_last=float(a[-1]), head_first=float(b[0]),
            step=abs(float(b[0]) - float(a[-1])),
            tail_10ms_rms=float(np.sqrt(np.mean(a[-240:].astype(np.float64) ** 2))),
            head_10ms_rms=float(np.sqrt(np.mean(b[:240].astype(np.float64) ** 2))),
        ))
    out["E5_boundary"] = dict(
        n_chunks=len(chunks),
        max_step=max(x["step"] for x in bnd) if bnd else None,
        boundaries=bnd,
        full_max_abs=float(np.max(np.abs(np.concatenate(chunks)))),
    )

    # ---- E6 现状仿真：16 字首段 + 40 字后续段，串行 ----
    segs = segment_for_synthesis(LONG_TEXT, 40, 16)
    sim = []
    for s in segs:
        t0 = time.time()
        nchunks = [0]
        def cb2(samples, progress):
            nchunks[0] += 1
            return 1
        a = tts.generate(s, make_cfg(ps, sr, pt, 200), cb2)
        synth_ms = (time.time() - t0) * 1000
        audio_sec = len(a.samples) / a.sample_rate
        sim.append(dict(seg=s, chars=len(s), synth_ms=round(synth_ms, 1),
                        audio_sec=round(audio_sec, 3), n_chunks=nchunks[0],
                        rtf=round(synth_ms / 1000.0 / audio_sec, 3)))
    out["E6_statusquo"] = dict(
        segments=sim,
        segments_text="".join(x["seg"] for x in sim) == LONG_TEXT,
        ttfc_ms=sim[0]["synth_ms"],
        total_ms=round(sum(x["synth_ms"] for x in sim) + sum(x["audio_sec"] for x in sim) * 1000, 1),
        gap_after_first_sec=round(sim[1]["synth_ms"] / 1000.0, 2),
        total_silence_vs_streaming=round(
            sum(x["synth_ms"] for x in sim[1:]) / 1000.0, 2),
    )

    # ---- E7 流式（原生分块）首声/连续性仿真 ----
    t0 = time.time()
    marks = []
    def cb3(samples, progress):
        marks.append(dict(t=round((time.time() - t0) * 1000, 1),
                          audio_sec=round(len(samples) / tts.sample_rate, 3)))
        return 1
    tts.generate(LONG_TEXT, make_cfg(ps, sr, pt, 200), cb3)
    # 逐块 RTF 与「播放是否会饥饿」判定
    prev_t, prev_a = 0.0, 0.0
    rows = []
    for m in marks:
        prod = (m["t"] - prev_t) / 1000.0
        cons = m["audio_sec"] - prev_a
        rows.append(dict(produced_sec=round(prod, 2), audio_sec=round(cons, 2),
                         margin_sec=round(cons - prod, 2), ok=cons >= prod))
        prev_t, prev_a = m["t"], m["audio_sec"]
    total_audio = marks[-1]["audio_sec"] if marks else 0.0
    total_prod = marks[-1]["t"] / 1000.0 if marks else 0.0
    out["E7_streaming"] = dict(
        ttfc_ms=marks[0]["t"] if marks else None,
        first_chunk_audio_sec=marks[0]["audio_sec"] if marks else None,
        total_audio_sec=round(total_audio, 2),
        total_prod_sec=round(total_prod, 2),
        overall_rtf=round(total_prod / total_audio, 3) if total_audio else None,
        underrun_margin_sec=round(total_audio - total_prod, 2),
        per_chunk=rows,
    )

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("OK")


if __name__ == "__main__":
    main()
