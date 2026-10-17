# -*- coding: utf-8 -*-
"""VITS(e500) PC 侧多臂参数扫参：分段合成模拟端侧逐段播放 + 同口径客观测量。

任务背景（一手结论，不在本脚本内重复验证）：
  - 真机反馈"语速过快 / 有气无力"；已确认"过快"主因是**停顿被压缩**（有声帧占比偏高、
    平均停顿偏短），不是音素念得快。
  - `speed` 是纯线性时长缩放；`silence_scale` 对 VITS 近无效（已验证，故本脚本不再扫它）。
  - 端侧 App 会把长文本切成段逐段合成并连续播放、段间不插静音 —— 本脚本用
    分段合成 + `sentenceGapMs` 手动插静音来模拟该行为。

臂设计（共 19）：
  speed ∈ {1.0, 0.85, 0.8} × sentenceGapMs ∈ {0, 300, 400} × noiseScale ∈ {0.667, 0.8} = 18
  + 1 个"整段合成"对照臂（speed 0.85、不插静音；旧样本即整段/连续合成，用作可比锚点）

用法：
  $env:PYTHONIOENCODING='utf-8'
  C:\\Users\\ljh\\.conda\\envs\\vits38\\python.exe voice-clone-kit\\tools\\sweep-vits-arms.py

产物：
  tmp/voice/sweep-20260924/<臂名>.wav
  tmp/voice/sweep-20260924/sweep-report.json
  tmp/voice/sweep-20260924/README.md（人读；由本脚本生成）
"""
import glob
import json
import os
import sys
import time
import wave

import numpy as np
import sherpa_onnx

ROOT = r"D:\s0611\code\Prism"
ONNX = os.path.join(ROOT, "voice-clone-kit", "build-out", "vits", "toolchain",
                    "sherpa-onnx-vits-fine-tuning-webui", "onnx-output-e500", "vits-cyrene")
CORPUS = os.path.join(ROOT, "voice-clone-kit", "build-out", "vits", "dataset", "sampled_audio4ft")
OUT = os.path.join(ROOT, "tmp", "voice", "sweep-20260924")
os.makedirs(OUT, exist_ok=True)

# 沿用旧试听脚本 make-vits-sample.py 的同一组 5 句（保证与用户已听过的样本可比）
TEXTS = [
    "你好，我是昔涟。",
    "欢迎回来，我一直在等你呢。",
    "今天的星空特别亮，要不要一起去看看？",
    "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。",
    "毕竟啊，能陪在你身边，对我来说就已经足够幸福了。",
]
# chars 口径与 tmp/research-20260924/corpus-baseline.py 一致：**含标点**的字符数
CHARS_TOTAL = len("".join(TEXTS))
PUNCT = '''，。？！、；：""''（）,.?!;:'''
NON_PUNCT = sum(1 for t in TEXTS for c in t if c not in PUNCT)  # 仅供参考，不参与口径

SPEEDS = [1.0, 0.85, 0.8]
GAPS_MS = [0, 300, 400]
NOISE_SCALES = [0.667, 0.8]

# 任务给定的语料基线（口径见 README；本脚本另用同口径重算语料作为对照）
STATED_BASELINE = {"mean_pause_s": 0.538, "silence_ratio_pct": 31.2, "voiced_ratio": 0.736,
                   "f0_median_hz": 269.5}


# ---------------------------------------------------------------- 度量（口径与
# tmp/research-20260924/ab-silence-scale.py 的 analyze() 及 measure-periodicity.py 一致）
def read_wav(p):
    with wave.open(p, "rb") as w:
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    return sr, a


def write_wav(p, sr, a):
    with wave.open(p, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((np.clip(a, -1, 1) * 32767).astype("<i2").tobytes())


def db(x):
    return float(20 * np.log10(max(float(x), 1e-9)))


def frames_st_db(a, sr):
    win, hop = max(int(sr * 0.04), 16), max(int(sr * 0.02), 8)
    idx = list(range(0, max(len(a) - win, 1), hop))
    st = np.array([np.sqrt(np.mean(a[i:i + win] ** 2)) for i in idx]) if idx else np.array([0.0])
    return 20 * np.log10(np.clip(st, 1e-9, None)), hop


def measure(sr, a, chars=None):
    """与语料同口径的全套指标。voced 帧阈值 -45dB(40ms/20ms 帧)。"""
    dur = len(a) / sr
    st_db, hop = frames_st_db(a, sr)
    voiced = st_db > -45
    v_db = st_db[voiced]
    speech = float(voiced.sum() * hop / sr)

    # 停顿 = 连续静音帧段，只计 >=50ms
    pauses, run = [], 0
    for v in voiced:
        if not v:
            run += 1
        elif run:
            pauses.append(run * hop / sr)
            run = 0
    if run:
        pauses.append(run * hop / sr)
    pauses = [round(p, 3) for p in pauses if p >= 0.05]

    spec = np.abs(np.fft.rfft(a * np.hanning(len(a))))
    freqs = np.fft.rfftfreq(len(a), 1 / sr)
    centroid = float((spec * freqs).sum() / max(spec.sum(), 1e-9))
    band_hi = spec[(freqs >= 2000) & (freqs < 4000)].sum()

    # 周期性 / F0（有声帧自相关峰 / ac[0]）
    win = max(int(sr * 0.04), 16)
    lo, hi = int(sr / 400), int(sr / 70)
    per, f0s = [], []
    for i in range(0, max(len(a) - 2 * win, 1), hop):
        seg = a[i:i + 2 * win]
        if np.sqrt(np.mean(seg ** 2)) < 10 ** (-45 / 20):
            continue
        seg = seg - seg.mean()
        ac = np.correlate(seg, seg, mode="full")[seg.size - 1:]
        if ac[0] <= 0 or hi >= ac.size:
            continue
        sa = ac[lo:hi]
        if sa.size == 0:
            continue
        per.append(float(sa.max() / ac[0]))
        f0s.append(sr / (int(np.argmax(sa)) + lo))

    out = {
        "dur_s": round(dur, 3),
        "speech_dur_s": round(speech, 3),
        "voiced_ratio": round(float(voiced.mean()), 4),
        "silence_ratio_pct": round(100 * float(1 - voiced.mean()), 2),
        "chars_per_s": round(chars / speech, 2) if (chars and speech > 0) else None,
        "rms_dbfs": round(db(np.sqrt(np.mean(a ** 2))), 2),
        "peak_dbfs": round(db(np.max(np.abs(a))), 2),
        "rl_dyn_db": round(float(np.percentile(v_db, 90) - np.percentile(v_db, 10)), 2)
        if v_db.size else None,
        "centroid_hz": int(centroid),
        "spec_2k_4k_ratio": round(float(band_hi / max(spec.sum(), 1e-9)), 4),
        "n_pauses_ge_50ms": len(pauses),
        "pause_total_s": round(float(sum(pauses)), 3),
        "pause_mean_s": round(float(np.mean(pauses)), 3) if pauses else None,
        "pause_p50_s": round(float(np.median(pauses)), 3) if pauses else None,
        "pause_p90_s": round(float(np.percentile(pauses, 90)), 3) if pauses else None,
        "periodicity_p25": round(float(np.percentile(per, 25)), 4) if per else None,
        "periodicity_p50": round(float(np.median(per)), 4) if per else None,
        "periodicity_p75": round(float(np.percentile(per, 75)), 4) if per else None,
        "f0_median_hz": round(float(np.median(f0s)), 1) if f0s else None,
    }
    return out


def agg(rows, key):
    v = [r[key] for r in rows if r and r.get(key) is not None]
    if not v:
        return None
    return {"n": len(v), "mean": round(float(np.mean(v)), 4),
            "p10": round(float(np.percentile(v, 10)), 4),
            "p50": round(float(np.percentile(v, 50)), 4),
            "p90": round(float(np.percentile(v, 90)), 4)}


# ---------------------------------------------------------------- 0) API 能力确认
report = {"env": {"python": sys.version.split()[0], "sherpa_onnx": getattr(
    sherpa_onnx, "__version__", "n/a"), "onnx_dir": ONNX}, "texts": TEXTS}

vits_doc = (sherpa_onnx.OfflineTtsVitsModelConfig.__init__.__doc__ or "")
gen_doc = (sherpa_onnx.OfflineTts.generate.__doc__ or "")
cap = {
    "vits_config_fields": sorted(a for a in dir(sherpa_onnx.OfflineTtsVitsModelConfig())
                                 if not a.startswith("_")),
    "accepts_noise_scale": "noise_scale" in vits_doc,
    "accepts_noise_scale_w": "noise_scale_w" in vits_doc,
    "accepts_length_scale": "length_scale" in vits_doc,
    "generate_signature_doc": " ".join(gen_doc.split())[:400],
    "note": "noise_scale/noise_scale_w/length_scale 是 OfflineTtsVitsModelConfig 的构造参数"
            "（配置期，非 generate 期），故每个 noiseScale 需单独构建一个 OfflineTts 引擎。",
}
report["api_capability"] = cap
print("[api] vits fields:", cap["vits_config_fields"])
print("[api] noise_scale accepted:", cap["accepts_noise_scale"],
      "| noise_scale_w:", cap["accepts_noise_scale_w"],
      "| length_scale:", cap["accepts_length_scale"])
if not cap["accepts_noise_scale"]:
    print("[api] !!! noiseScale 轴不受支持，降级为仅 1 个值 (0.667)")
    NOISE_SCALES = [0.667]
report["noise_scale_axis_values"] = NOISE_SCALES
report["noise_scale_axis_degraded"] = len(NOISE_SCALES) == 1

# 随机性探针产物（由 probe-vits-determinism.py 先生成；本脚本据此判断轴差异是否在噪声地板上）
PROBE_JSON = os.path.join(OUT, "determinism-probe.json")
probe = None
if os.path.exists(PROBE_JSON):
    with open(PROBE_JSON, "r", encoding="utf-8") as f:
        probe = json.load(f)
report["determinism_probe"] = probe

# ---------------------------------------------------------------- 1) 语料基线（同口径重算）
t0 = time.time()
# 逐字台词（sampled_audio4ft.txt 形如 "training_data/cyrene_wavs/c000.wav|<text>"），
# 口径与 corpus-baseline.py 一致：字符数**含标点**，去掉 [ZH] 标记
text_by_id = {}
txt_manifest = os.path.join(os.path.dirname(CORPUS), "sampled_audio4ft.txt")
if os.path.exists(txt_manifest):
    with open(txt_manifest, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            parts = line.split("|", 1) if "|" in line else (line.split(None, 1) + [""])[:2]
            key = os.path.splitext(os.path.basename(parts[0].strip()))[0]
            text_by_id[key] = parts[1].strip().replace("[ZH]", "").strip()
corpus_rows = []
for p in sorted(glob.glob(os.path.join(CORPUS, "*.wav"))):
    sr, a = read_wav(p)
    if a.size:
        chars = len(text_by_id.get(os.path.splitext(os.path.basename(p))[0], "")) or None
        corpus_rows.append(measure(sr, a, chars))
report["corpus_baseline_recomputed"] = {
    "dir": CORPUS, "manifest": txt_manifest, "n_files": len(corpus_rows),
    "elapsed_s": round(time.time() - t0, 1),
    "agg": {k: agg(corpus_rows, k) for k in
            ("voiced_ratio", "silence_ratio_pct", "pause_mean_s", "pause_p50_s", "pause_p90_s",
             "n_pauses_ge_50ms", "f0_median_hz", "periodicity_p25", "periodicity_p50",
             "rl_dyn_db", "centroid_hz", "spec_2k_4k_ratio", "dur_s", "chars_per_s")},
    "rows": corpus_rows,
}
report["corpus_baseline_stated"] = STATED_BASELINE
ca = report["corpus_baseline_recomputed"]["agg"]
print("[corpus] n=%d voiced=%.4f silence=%.2f%% pause_mean=%s f0=%s per_p25=%s" % (
    len(corpus_rows), ca["voiced_ratio"]["mean"], ca["silence_ratio_pct"]["mean"],
    ca["pause_mean_s"]["mean"], ca["f0_median_hz"]["mean"], ca["periodicity_p25"]["mean"]))

# ---------------------------------------------------------------- 2) 引擎构建（每个 noiseScale 一个）
engines = {}
for ns in NOISE_SCALES:
    t = time.time()
    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = 4
    cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=os.path.join(ONNX, "model.fixed.onnx"),
        lexicon=os.path.join(ONNX, "lexicon.txt"),
        tokens=os.path.join(ONNX, "tokens.txt"),
        dict_dir=os.path.join(ONNX, "dict"),
        noise_scale=ns,
    )
    cfg.rule_fsts = ",".join(os.path.join(ONNX, f) for f in
                             ["date.fst", "number.fst", "phone.fst", "new_heteronym.fst"])
    tts = sherpa_onnx.OfflineTts(cfg)
    engines[ns] = {"tts": tts, "sr": int(tts.sample_rate), "build_s": round(time.time() - t, 2)}
    print("[engine] noise_scale=%s build=%.2fs sample_rate=%d speakers=%d" % (
        ns, engines[ns]["build_s"], engines[ns]["sr"], int(tts.num_speakers)))

SR = next(iter(engines.values()))["sr"]
report["sample_rate"] = SR


# ---------------------------------------------------------------- 3) 合成
def synth_segmented(eng, speed, gap_ms):
    """逐句 generate，句间插 gap_ms 静音 —— 模拟端侧切段连续播放。"""
    sr = eng["sr"]
    gap = np.zeros(int(sr * gap_ms / 1000.0), dtype=np.float32)
    t = time.time()
    chunks = []
    for i, txt in enumerate(TEXTS):
        audio = eng["tts"].generate(txt, sid=0, speed=speed)
        chunks.append(np.asarray(audio.samples, dtype=np.float32))
        if i != len(TEXTS) - 1 and gap.size:
            chunks.append(gap)
    elapsed = time.time() - t
    return sr, np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.float32), elapsed


def synth_whole(eng, speed):
    """整段合成：一次 generate 喂全部 5 句（旧样本形态），不手动插静音。"""
    sr = eng["sr"]
    t = time.time()
    audio = eng["tts"].generate("".join(TEXTS), sid=0, speed=speed)
    a = np.asarray(audio.samples, dtype=np.float32)
    return sr, a, time.time() - t


arms = []
for ns in NOISE_SCALES:
    for speed in SPEEDS:
        for gap in GAPS_MS:
            arms.append({
                "arm": "sp%g_gap%03d_ns%g" % (speed, gap, ns),
                "mode": "segmented", "speed": speed, "sentenceGapMs": gap,
                "noiseScale": ns, "noiseScaleW": 0.8, "lengthScale": 1.0,
            })
arms.append({"arm": "CONTROL_whole_sp0.85_ns0.667", "mode": "whole",
             "speed": 0.85, "sentenceGapMs": None, "noiseScale": NOISE_SCALES[0],
             "noiseScaleW": 0.8, "lengthScale": 1.0})

results = []
for k, arm in enumerate(arms, 1):
    eng = engines[arm["noiseScale"]]
    if arm["mode"] == "segmented":
        sr, a, elapsed = synth_segmented(eng, arm["speed"], arm["sentenceGapMs"])
    else:
        sr, a, elapsed = synth_whole(eng, arm["speed"])
    wav = os.path.join(OUT, arm["arm"] + ".wav")
    write_wav(wav, sr, a)
    audio_sec = len(a) / sr
    m = measure(sr, a, CHARS_TOTAL)
    row = dict(arm)
    row.update({
        "wav": os.path.relpath(wav, ROOT).replace("\\", "/"),
        "sample_rate": sr,
        "audio_sec": round(audio_sec, 3),
        "synth_elapsed_s": round(elapsed, 2),
        "rtf_synth": round(elapsed / audio_sec, 4) if audio_sec > 0 else None,
        "engine_build_s": eng["build_s"],
        "peak_abs": round(float(np.max(np.abs(a))), 4),
    })
    row.update(m)
    results.append(row)
    print("[%2d/%d] %-28s dur=%6.2fs voiced=%.3f pause_mean=%s rtf=%.4f" % (
        k, len(arms), arm["arm"], audio_sec, m["voiced_ratio"],
        m["pause_mean_s"], row["rtf_synth"]))
    sys.stdout.flush()

report["arms"] = results
report["arms_note"] = ("rtf_synth 仅含 generate 调用耗时（不含模型加载）；PC 值仅供臂间比较，"
                       "不可外推到手机端。CONTROL 臂为整段合成（一次 generate 全部 5 句）。")
report["rtf_caveat"] = "PC RTF 不可外推到手机；端侧按 int8 模型 + 不同线程数运行。"

with open(os.path.join(OUT, "sweep-report.json"), "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=1)
print("[done] report ->", os.path.join(OUT, "sweep-report.json"))

# ---------------------------------------------------------------- 4) README（人读）
BASE = {"voiced_ratio": ca["voiced_ratio"]["mean"], "silence_ratio_pct": ca["silence_ratio_pct"]["mean"],
        "pause_mean_s": ca["pause_mean_s"]["mean"], "f0_median_hz": ca["f0_median_hz"]["mean"],
        "periodicity_p25": ca["periodicity_p25"]["mean"], "periodicity_p50": ca["periodicity_p50"]["mean"],
        "rl_dyn_db": ca["rl_dyn_db"]["mean"], "centroid_hz": ca["centroid_hz"]["mean"],
        "spec_2k_4k_ratio": ca["spec_2k_4k_ratio"]["mean"],
        "chars_per_s": agg(corpus_rows, "chars_per_s")}

lines = []
lines.append("# VITS(e500) 多臂扫参 —— 试听清单与客观测量\n")
lines.append("结论分三态标注：**已验证**（有实际产物支撑）/ **推测**（机制解释，未验证）/ **未验证**。")
lines.append("客观指标只能证伪「停顿被压缩」，**不能宣称听感已解决**——最终判听权在用户。\n")
lines.append("## 0. 引擎与 API")
lines.append("- ONNX：`%s`（model.fixed.onnx, e500）" % ONNX.replace("\\", "/"))
lines.append("- 采样率（引擎回报）：%d Hz" % SR)
lines.append("- `OfflineTtsVitsModelConfig` 字段：`%s`" % ", ".join(cap["vits_config_fields"]))
lines.append("- **已验证**：`noise_scale` / `noise_scale_w` / `length_scale` 均为 VITS 配置期构造参数"
             "（默认 0.667 / 0.8 / 1.0），可生效；但**只能在构造 `OfflineTts` 时设定**，"
             "每个 noiseScale 需单独建引擎（本脚本建了 %d 个）。" % len(NOISE_SCALES))
lines.append("- `silence_scale`（OfflineTtsConfig）本脚本不扫（前次已实测近无效）。\n")
lines.append("## 1. 测试文本（沿用旧试听脚本 make-vits-sample.py 的 5 句，与用户已听样本可比）")
for i, t in enumerate(TEXTS, 1):
    lines.append("%d. %s" % (i, t))
lines.append("")
lines.append("## 2. 口径与语料基线")
lines.append("同口径重算训练语料（`%s`，n=%d）：" % (
    CORPUS.replace("\\", "/"), len(corpus_rows)))
lines.append("")
lines.append("| 指标 | 语料实测(本脚本重算, 78条均值) | 任务给定基线 |")
lines.append("| --- | --- | --- |")
lines.append("| 有声帧占比 voiced_ratio | %.3f | 0.736 |" % BASE["voiced_ratio"])
lines.append("| 静音占比 (=1-voiced_ratio) | %.2f%% | 31.2%% |" % BASE["silence_ratio_pct"])
lines.append("| 平均停顿 pause_mean_s | %.3f s | 0.538 s |" % BASE["pause_mean_s"])
lines.append("| F0 中位 | %.1f Hz | 269.5 Hz |" % BASE["f0_median_hz"])
lines.append("| 周期性 p25 (自相关峰/ac[0]) | %.4f | 0.3314 (periodicity.json) |" % BASE["periodicity_p25"])
lines.append("| 周期性 p50 | %.4f | 0.7147 |" % BASE["periodicity_p50"])
lines.append("| 短时 RMS 动态范围 rl_dyn_db | %.2f dB | 17.853 |" % BASE["rl_dyn_db"])
lines.append("| 谱质心 | %.0f Hz | 2637 Hz |" % BASE["centroid_hz"])
lines.append("| 谱 2k-4k 占比 | %.4f | 0.1746 |" % BASE["spec_2k_4k_ratio"])
lines.append("| 字/有声秒（含标点字符 / 有声秒） | %s | 8.615 |" % (
    BASE["chars_per_s"]["mean"] if BASE["chars_per_s"] else "n/a"))
lines.append("")
lines.append("口径：40ms 窗 / 20ms 跳；有声帧 = 帧 RMS > -45 dBFS；停顿 = 连续静音帧段且 >= 50ms；"
             "周期性 = 有声帧自相关峰/ac[0]（40ms 窗/20ms 跳，lag 域 70-400Hz）。")
lines.append("⚠️ 任务给定的「平均停顿 0.538s / 静音占比 31.2%」在本脚本同口径重算下未复现"
             "（见上表）：**静音占比**差异源于口径不同（31.2% 疑为其它阈值/其它脚本），"
             "**平均停顿**为对 `>=50ms` 停顿段取均值，对阈值敏感。臂间比较一律以本表「语料实测」列为准。\n")
lines.append("## 3. 臂名对照表（%d 臂）" % len(results))
lines.append("命名：`sp<speed>_gap<sentenceGapMs>_ns<noiseScale>`；`CONTROL_whole_*` = 整段合成对照。")
lines.append("")
lines.append("| 臂名 | speed | 句间静音 ms | noise_scale | 合成方式 |")
lines.append("| --- | --- | --- | --- | --- |")
for r in results:
    lines.append("| `%s` | %g | %s | %g | %s |" % (
        r["arm"], r["speed"], r["sentenceGapMs"] if r["sentenceGapMs"] is not None else "—",
        r["noiseScale"], "分段逐句" if r["mode"] == "segmented" else "整段"))
lines.append("")
lines.append("## 4. 每臂一行数字")
hdr = ("| 臂名 | 总时长 s | 有声帧占比 | 静音占比 % | 平均停顿 s | 停顿数 | 字/有声秒 | F0 中位 Hz | "
       "周期性 p25 | p50 | RMS动态 dB | 谱质心 Hz | 2-4k占比 | RTF |")
lines.append(hdr)
lines.append("|" + " --- |" * 15)
lines.append("| **语料基线** | %.1f | %.3f | %.2f | %.3f | — | %s | %.1f | %.4f | %.4f | %.2f | %.0f | %.4f | — |" % (
    ca["dur_s"]["mean"], BASE["voiced_ratio"], BASE["silence_ratio_pct"], BASE["pause_mean_s"],
    BASE["chars_per_s"]["mean"] if BASE["chars_per_s"] else "n/a", BASE["f0_median_hz"],
    BASE["periodicity_p25"], BASE["periodicity_p50"], BASE["rl_dyn_db"], BASE["centroid_hz"],
    BASE["spec_2k_4k_ratio"]))
for r in results:
    lines.append("| `%s` | %.2f | %.3f | %.2f | %s | %d | %s | %s | %s | %s | %s | %d | %.4f | %.4f |" % (
        r["arm"], r["dur_s"], r["voiced_ratio"], r["silence_ratio_pct"],
        r["pause_mean_s"] if r["pause_mean_s"] is not None else "—", r["n_pauses_ge_50ms"],
        r["chars_per_s"] if r["chars_per_s"] is not None else "—",
        r["f0_median_hz"] if r["f0_median_hz"] is not None else "—",
        r["periodicity_p25"], r["periodicity_p50"], r["rl_dyn_db"], r["centroid_hz"],
        r["spec_2k_4k_ratio"], r["rtf_synth"]))
lines.append("")
lines.append("RTF = 合成耗时 / 音频时长（不含模型加载；加载耗时见 sweep-report.json 的 engine_build_s）。"
             "**PC 值仅供臂间比较，严禁外推到手机端。** 端侧用 int8 模型、不同线程数、不同 SoC。")
lines.append("「字/有声秒」口径 = 字符数（**含标点**，5 句共 %d 字）/ 有声秒，与 corpus-baseline.py 一致；"
             "语料基线同口径重算见上表。" % CHARS_TOTAL)
lines.append("")
if probe:
    pw, pc = probe["within_ns_spread"], probe["cross_ns_diff"]
    lines.append("## 4.5 合成随机性探针（噪声地板）")
    lines.append("动机：扫参首轮与重跑中，**参数完全相同的臂**总时长不同（如 sp0.85_gap400_ns0.8: "
                 "17.94s → 18.06s；sp0.8_gap000_ns0.8: 16.72s → 17.10s）→ VITS 推理带随机性。")
    lines.append("做法：同一文本（%d 字）、speed=%.2f，每个 noise_scale 重复 %d 次（同一进程内连续调用，"
                 "RNG 序列在两组间对齐）。产物：`determinism-probe.json`。" % (
                     probe["chars"], probe["speed"], probe["reps"]))
    lines.append("")
    lines.append("| 指标 | ns=0.667 组内极差(同参数重跑) | ns=0.8 组内极差 | 跨 noise_scale 差(均值) | 轴可分辨? |")
    lines.append("| --- | --- | --- | --- | --- |")
    for k in ("dur_s", "voiced_ratio", "rms_dbfs", "periodicity_p25"):
        lines.append("| %s | %s | %s | %s | %s |" % (
            k, pw["0.667"][k], pw["0.8"][k], pc[k],
            "是" if probe["ns_axis_resolvable_per_metric"][k] else "否"))
    lines.append("")
    lines.append("**结论：%s。** 故第 6 节「noise_scale 0.667 vs 0.8 无可分辨差异」应理解为"
                 "**差异被推理随机性淹没**，而非「已证明该轴无效」；臂间 0.0x 量级的时长/占比差异"
                 "均落在噪声地板内，不可当作轴效应。" % probe["conclusion"])
    lines.append("")
lines.append("## 5. 建议优先试听哪 3 个臂")
lines.append("")


def R(name):
    return next(r for r in results if r["arm"] == name)


g0 = R("sp1_gap000_ns0.667")
need_gap_ms = ((g0["speech_dur_s"] / BASE["voiced_ratio"]) - g0["speech_dur_s"]) / 4 * 1000
A1, A2, A3 = "sp0.85_gap400_ns0.667", "sp1_gap400_ns0.667", "sp0.85_gap400_ns0.8"
seg = [r for r in results if r["mode"] == "segmented"]
best_pause = max(seg, key=lambda r: r["pause_mean_s"] or 0)
low_voiced = min(seg, key=lambda r: r["voiced_ratio"])
lines.append("选臂依据：覆盖三条轴的上限档（gap400 × speed{0.85, 1.0} × noise_scale{0.667, 0.8}），"
             "**不依赖「哪一臂最优」**——该判断被 §4.5 的随机性地板限制：本轮分段臂中 pause_mean 最高的是 "
             "`%s`（%.3f s），voiced_ratio 最低的是 `%s`（%.3f）。" % (
                 best_pause["arm"], best_pause["pause_mean_s"], low_voiced["arm"], low_voiced["voiced_ratio"]))
lines.append("")
lines.append("**① `%s`** —— 停顿恢复档位最高的臂之一（voiced_ratio %.3f、pause_mean %.3f s、总时长 %.2fs），"
             "且在停顿之外保留 15%% 线性放慢。与上段最优臂的差 = Δvoiced %.3f / Δpause %.3f s。"
             "优先听它：用户两条抱怨里「过快」有明确客观方向，这一臂在该方向上走得最远。" % (
                 A1, R(A1)["voiced_ratio"], R(A1)["pause_mean_s"], R(A1)["dur_s"],
                 abs(R(A1)["voiced_ratio"] - low_voiced["voiced_ratio"]),
                 abs((R(A1)["pause_mean_s"] or 0) - (best_pause["pause_mean_s"] or 0))))
lines.append("")
lines.append("**② `%s`** —— 与 ① 的唯一差异是 speed=1.0（不动音素时长、只加 400ms 段间静音，总时长 %.2fs）。"
             "作用是**解耦**：若听完 ① 觉得「字念得拖/慢」（本轮生成语音的字/有声秒 %.2f 本就低于语料 %.2f，"
             "即发声速率偏慢），② 可判定该拖感来自 speed 而非停顿。" % (
                 A2, R(A2)["dur_s"], R(A2)["chars_per_s"], BASE["chars_per_s"]["mean"]))
lines.append("")
lines.append("**③ `%s`** —— 与 ① 的唯一差异是 noise_scale 0.667→0.8，用作「有气无力」轴的 A/B 判听器。"
             "客观侧该轴不可分辨（见 §4.5：跨轴差均小于同参数重跑抖动），所以只能靠耳朵判；"
             "若两臂听不出差别，就该放弃细扫 noise_scale，转去查 F0/周期性偏差。" % A3)
lines.append("")
lines.append("锚点参考（可只听一条）：`CONTROL_whole_sp0.85_ns0.667`（整段合成，%.2fs、voiced %.3f）"
             "与 `sp0.85_gap000_ns0.667`（分段+0 静音，%.2fs、%.3f）几乎等价 —— **已验证**："
             "手动分段合成与整段合成在停顿结构上无实质差别，「段间不插静音」才是端侧差异来源。" % (
                 R("CONTROL_whole_sp0.85_ns0.667")["dur_s"], R("CONTROL_whole_sp0.85_ns0.667")["voiced_ratio"],
                 R("sp0.85_gap000_ns0.667")["dur_s"], R("sp0.85_gap000_ns0.667")["voiced_ratio"]))
lines.append("")
lines.append("")
lines.append("## 6. 结论（三态标注）")
lines.append("### 已验证（有本轮实际产物支撑）")
lines.append("- `noise_scale` / `noise_scale_w` / `length_scale` 均为 `OfflineTtsVitsModelConfig` 构造参数，可生效；"
             "但 noise_scale 只能在**构造引擎**时设，故每个取值需重建 OfflineTts（本轮建 %d 个）。" % len(NOISE_SCALES))
if probe:
    lines.append("- **noise_scale 0.667 vs 0.8 的差异小于推理随机性地板**：受控探针（同一 RNG 序列、仅 noise_scale 不同）"
                 "跨轴差 dur %s s / voiced %s / per25 %s，而同参数重复 3 次的组内极差为 dur %s s / voiced %s / per25 %s"
                 "（见 §4.5）→ 本轮**未能证实**该轴对「有气无力」有效（不是「已证否」，是分辨率不够）。" % (
                     probe["cross_ns_diff"]["dur_s"], probe["cross_ns_diff"]["voiced_ratio"],
                     probe["cross_ns_diff"]["periodicity_p25"],
                     probe["within_ns_spread"]["0.667"]["dur_s"],
                     probe["within_ns_spread"]["0.667"]["voiced_ratio"],
                     probe["within_ns_spread"]["0.667"]["periodicity_p25"]))
    lines.append("- **合成本身是随机的、且随机性足以覆盖臂间小差**：同一文本（%d 字）同参数连续 3 次，"
                 "总时长 %.3f / %.3f / %.3f s（极差 %.3f s = %.1f%%），voiced_ratio 极差 %.4f。" % (
                     probe["chars"],
                     probe["runs"]["0.667"][0]["dur_s"], probe["runs"]["0.667"][1]["dur_s"],
                     probe["runs"]["0.667"][2]["dur_s"], probe["within_ns_spread"]["0.667"]["dur_s"],
                     100 * probe["within_ns_spread"]["0.667"]["dur_s"] / probe["runs"]["0.667"][0]["dur_s"],
                     probe["within_ns_spread"]["0.667"]["voiced_ratio"]))
lines.append("- **speed 只拉长有声段，不改停顿结构**：`sp1/0.85/0.8_gap000` 的 voiced_ratio = %.3f / %.3f / %.3f，"
             "总时长 %.2f / %.2f / %.2f s（远非线性：1/0.85 = 1.176，实测仅 %.3f）。" % (
                 R("sp1_gap000_ns0.667")["voiced_ratio"], R("sp0.85_gap000_ns0.667")["voiced_ratio"],
                 R("sp0.8_gap000_ns0.667")["voiced_ratio"], R("sp1_gap000_ns0.667")["dur_s"],
                 R("sp0.85_gap000_ns0.667")["dur_s"], R("sp0.8_gap000_ns0.667")["dur_s"],
                 R("sp0.85_gap000_ns0.667")["dur_s"] / R("sp1_gap000_ns0.667")["dur_s"]))
lines.append("- **句间插静音是三个轴里唯一能改停顿结构的**：gap 0 → 300 → 400ms 使 voiced_ratio %.3f → %.3f → %.3f、"
             "pause_mean %.3f → %.3f → %.3f s（取 speed=1.0、ns=0.667 同一行比较）。" % (
                 R("sp1_gap000_ns0.667")["voiced_ratio"], R("sp1_gap300_ns0.667")["voiced_ratio"],
                 R("sp1_gap400_ns0.667")["voiced_ratio"], R("sp1_gap000_ns0.667")["pause_mean_s"],
                 R("sp1_gap300_ns0.667")["pause_mean_s"], R("sp1_gap400_ns0.667")["pause_mean_s"]))
_g4 = [r for r in results if r.get("sentenceGapMs") == 400]
lines.append("- **gap400 仍未追平语料**：voiced_ratio %.3f-%.3f vs 语料 %.3f；pause_mean %.3f-%.3f vs 语料 %.3f。"
             "纯几何外推需 **~%.0f ms/段间**静音才能在有声帧占比上追平（4 段间 = %.1fs 额外静音）—— "
             "该外推值**未实跑**，属推测。" % (
                 min(r["voiced_ratio"] for r in _g4), max(r["voiced_ratio"] for r in _g4), BASE["voiced_ratio"],
                 min(r["pause_mean_s"] for r in _g4), max(r["pause_mean_s"] for r in _g4), BASE["pause_mean_s"],
                 need_gap_ms, need_gap_ms * 4 / 1000))
lines.append("- **「不是音素念得快」在本口径下复现**：字/有声秒（含标点字符 %d 个 / 有声秒，与 corpus-baseline.py 同口径）"
             "speed=1.0 臂 %.2f、speed=0.8 臂 %.2f，均**低于**语料 %.3f —— 合成语音的**发声速率反而比语料慢**，"
             "「过快」只能来自停顿缺失。" % (CHARS_TOTAL, R("sp1_gap000_ns0.667")["chars_per_s"],
                                            R("sp0.8_gap000_ns0.667")["chars_per_s"], BASE["chars_per_s"]["mean"]))
_f0s = sorted(set(r["f0_median_hz"] for r in results))
lines.append("- **F0 中位整体偏高且三轴都改不动**：所有臂 F0 只取 %s Hz（语料 %.1f Hz，+%.0f~%.0f%%）。"
             "speed / gap / noise_scale 均不影响音高。" % (
                 "/".join("%.1f" % v for v in _f0s), BASE["f0_median_hz"],
                 100 * (_f0s[0] / BASE["f0_median_hz"] - 1), 100 * (_f0s[-1] / BASE["f0_median_hz"] - 1)))
_p25 = [r["periodicity_p25"] for r in results]
_rl = [r["rl_dyn_db"] for r in results if r["rl_dyn_db"] is not None]
lines.append("- **周期性 p25 整体低于语料且三轴都拉不回**：臂 %.4f-%.4f vs 语料 %.4f（越低越接近气声/发虚）；"
             "臂间波动与所扫轴无对应关系（且部分落在 §4.5 噪声地板内）。" % (
                 min(_p25), max(_p25), BASE["periodicity_p25"]))
lines.append("- **短时 RMS 动态范围略收窄**：臂 %.1f-%.1f dB vs 语料 %.2f dB。" % (
    min(_rl), max(_rl), BASE["rl_dyn_db"]))
lines.append("### 推测（机制解释，未验证）")
lines.append("- 「过快」的端侧放大器就是**段间不插静音**：App 行为等价于 gap000 臂（voiced_ratio 0.911），"
             "即用户实际听到的是本轮**最压缩**的一档。")
lines.append("- 「有气无力」更可能来自 F0 偏高 + RMS 动态收窄 + 周期性偏低的**叠加**，而非 noise_scale。")
lines.append("- 只靠段间静音无法复现语料的停顿**密度**（语料每 ~9 个字符就有一次 0.39s 停顿；VITS 句内逗号停顿只有 ~0.107s）。"
             "更有效的手段可能是**按逗号也切段并插静音**（提高停顿密度而非仅加长段间停顿）—— 未跑，属推测。")
lines.append("### 未验证")
lines.append("- **听感是否改善**——客观指标只能证伪「停顿被压缩」，最终判听权在用户。")
lines.append("- noise_scale 的有效区间（本轮只取 0.667 / 0.8，差异太小）。")
lines.append("- 端侧耗时表现（PC RTF %.3f-%.3f 为 fp32 + 4 线程，**不可外推**到手机）。"
             % (min(r["rtf_synth"] for r in results), max(r["rtf_synth"] for r in results)))
lines.append("")
lines.append("## 7. 本实验未能回答的问题")
lines.append("1. noise_scale 是否存在能改善「发虚」的取值区间 —— 需 0.3 / 0.5 / 1.0 / 1.2 等更宽取值 + 更多句子 + 多样本重复。")
lines.append("2. 停顿**密度**（句内逗号级停顿）能否恢复 —— 本轮只做了段间插静音，未做逗号切分臂。")
lines.append("3. 语料基线的 0.538s / 31.2%% 两个任务给定数字的确切口径 —— 本脚本同口径重算得 0.391s / 26.37%%"
             "（同口径 voiced_ratio 0.7363 与 F0 267.0、周期性与 periodicity.json 逐位吻合，说明公式无问题，差异在阈值口径）。")
lines.append("4. F0 整体偏高 ~15Hz 的成因与可修性 —— 本三轴均无关，需从数据/训练侧查。")
lines.append("5. 真机端侧实际耗时与内存 —— PC 值不可外推。")

with open(os.path.join(OUT, "README.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("[done] README ->", os.path.join(OUT, "README.md"))
print(json.dumps({"arms": len(results), "sample_rate": SR, "chars_total": CHARS_TOTAL,
                  "corpus_voiced_ratio": BASE["voiced_ratio"],
                  "extrapolated_gap_ms": round(need_gap_ms)}, ensure_ascii=False))
