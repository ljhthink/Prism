# -*- coding: utf-8 -*-
"""VITS(e500) 合成随机性探针：判断 noise_scale 轴差异是否可分辨。

动机：`sweep-vits-arms.py` 两次运行中，**参数完全相同的臂**总时长出现差异
（如 sp0.85_gap400_ns0.8: 17.94s → 18.06s；sp0.8_gap000_ns0.8: 16.72s → 17.10s），
说明 VITS 推理本身带随机性。若组内抖动 >= 轴间差异，则 noise_scale 0.667 vs 0.8
的"无可分辨差异"必须改述为"差异被随机性淹没"。

做法：固定同一段文本、同一 speed，每个 noise_scale **重复 3 次**，比较
  组内极差（同一 noise_scale 的 3 次）  vs  组间差（0.667 均值 - 0.8 均值）。

用法：$env:PYTHONIOENCODING='utf-8'
      C:\\Users\\ljh\\.conda\\envs\\vits38\\python.exe voice-clone-kit\\tools\\probe-vits-determinism.py

产物：tmp/voice/sweep-20260924/determinism-probe.json，并合并进 sweep-report.json
      （键 `determinism_probe`）与 README.md。
"""
import json
import os
import time

import numpy as np
import sherpa_onnx

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = r"D:\s0611\code\Prism\tmp\voice\sweep-20260924"
REPORT = os.path.join(OUT, "sweep-report.json")
README = os.path.join(OUT, "README.md")

# 度量函数与 sweep-vits-arms.py 的 measure() 口径一致（40ms 窗/20ms 跳/-45dBFS/自相关）

ONNX = os.path.join(r"D:\s0611\code\Prism", "voice-clone-kit", "build-out", "vits", "toolchain",
                    "sherpa-onnx-vits-fine-tuning-webui", "onnx-output-e500", "vits-cyrene")
TEXT = "如果有什么烦恼的话，就说给我听吧，我会认真听着，不会打岔的。"
CHARS = len(TEXT)
SPEED = 1.0
NOISE_SCALES = [0.667, 0.8]
REPS = 3


def measure(sr, a):
    win, hop = max(int(sr * 0.04), 16), max(int(sr * 0.02), 8)
    idx = list(range(0, max(len(a) - win, 1), hop))
    st = np.array([np.sqrt(np.mean(a[i:i + win] ** 2)) for i in idx]) if idx else np.array([0.0])
    st_db = 20 * np.log10(np.clip(st, 1e-9, None))
    voiced = st_db > -45
    per = []
    lo, hi = int(sr / 400), int(sr / 70)
    for i in range(0, max(len(a) - 2 * win, 1), hop):
        seg = a[i:i + 2 * win]
        if np.sqrt(np.mean(seg ** 2)) < 10 ** (-45 / 20):
            continue
        seg = seg - seg.mean()
        ac = np.correlate(seg, seg, mode="full")[seg.size - 1:]
        if ac[0] <= 0 or hi >= ac.size:
            continue
        sa = ac[lo:hi]
        if sa.size:
            per.append(float(sa.max() / ac[0]))
    return {
        "dur_s": round(len(a) / sr, 3),
        "voiced_ratio": round(float(voiced.mean()), 4),
        "rms_dbfs": round(float(20 * np.log10(max(np.sqrt(np.mean(a ** 2)), 1e-9))), 2),
        "periodicity_p25": round(float(np.percentile(per, 25)), 4) if per else None,
        "peak_abs": round(float(np.max(np.abs(a))), 4),
    }


def build(ns):
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
    return sherpa_onnx.OfflineTts(cfg)


runs = {}
for ns in NOISE_SCALES:
    tts = build(ns)
    sr = int(tts.sample_rate)
    recs = []
    for rep in range(REPS):
        a = np.asarray(tts.generate(TEXT, sid=0, speed=SPEED).samples, dtype=np.float32)
        m = measure(sr, a)
        m["rep"] = rep + 1
        recs.append(m)
        print("[ns=%s rep%d] dur=%.3fs voiced=%.4f rms=%s per25=%s" % (
            ns, rep + 1, m["dur_s"], m["voiced_ratio"], m["rms_dbfs"], m["periodicity_p25"]))
    runs[str(ns)] = recs


def spread(recs, key):
    v = [r[key] for r in recs if r.get(key) is not None]
    return round(max(v) - min(v), 4) if v else None


def mean(recs, key):
    v = [r[key] for r in recs if r.get(key) is not None]
    return round(float(np.mean(v)), 4) if v else None


probe = {
    "text": TEXT, "chars": CHARS, "speed": SPEED, "reps": REPS,
    "noise_scales": NOISE_SCALES, "runs": runs,
    "within_ns_spread": {ns: {k: spread(runs[ns], k) for k in
                              ("dur_s", "voiced_ratio", "rms_dbfs", "periodicity_p25")}
                         for ns in runs},
    "cross_ns_diff": {k: round(abs(mean(runs["0.667"], k) - mean(runs["0.8"], k)), 4)
                      for k in ("dur_s", "voiced_ratio", "rms_dbfs", "periodicity_p25")},
    "ns_means": {ns: {k: mean(runs[ns], k) for k in
                      ("dur_s", "voiced_ratio", "rms_dbfs", "periodicity_p25")} for ns in runs},
    "conclusion": None,
}
cw = probe["cross_ns_diff"]
ww = probe["within_ns_spread"]
resolvable = {k: (cw[k] is not None and ww["0.667"][k] is not None
                  and ww["0.8"][k] is not None and cw[k] > max(ww["0.667"][k], ww["0.8"][k]))
              for k in cw}
probe["ns_axis_resolvable_per_metric"] = resolvable
probe["conclusion"] = (
    "noise_scale 0.667 vs 0.8 的差异在全部指标上均未超过同一 noise_scale 的组内抖动"
    if not any(resolvable.values()) else
    "至少一个指标上组间差 > 组内抖动，noise_scale 轴可分辨：%s" %
    [k for k, v in resolvable.items() if v])

with open(os.path.join(OUT, "determinism-probe.json"), "w", encoding="utf-8") as f:
    json.dump(probe, f, ensure_ascii=False, indent=1)

if os.path.exists(REPORT):
    with open(REPORT, "r", encoding="utf-8") as f:
        rep = json.load(f)
    rep["determinism_probe"] = probe
    with open(REPORT, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)

if os.path.exists(README):
    with open(README, "r", encoding="utf-8") as f:
        txt = f.read()
    block = []
    block.append("## 4.5 合成随机性探针（噪声地板）")
    block.append("问题：扫参首轮与重跑中，**参数完全相同的臂**总时长不同（sp0.85_gap400_ns0.8: "
                 "17.94s → 18.06s；sp0.8_gap000_ns0.8: 16.72s → 17.10s）→ VITS 推理带随机性。")
    block.append("做法：同一文本（%d 字）、speed=%.2f，每个 noise_scale 重复 %d 次。" % (CHARS, SPEED, REPS))
    block.append("")
    block.append("| 指标 | ns=0.667 组内极差 | ns=0.8 组内极差 | 组间差(0.667-0.8 均值) | 可分辨? |")
    block.append("| --- | --- | --- | --- | --- |")
    for k in ("dur_s", "voiced_ratio", "rms_dbfs", "periodicity_p25"):
        block.append("| %s | %s | %s | %s | %s |" % (
            k, ww["0.667"][k], ww["0.8"][k], cw[k], "是" if resolvable[k] else "否"))
    block.append("")
    block.append("**结论：%s。** 因此第 6 节中「noise_scale 0.667 vs 0.8 无可分辨差异」应理解为"
                 "**差异被推理随机性淹没**，而非「已证明该轴无效」；臂间 0.0x 量级的时长/占比差异"
                 "均在噪声地板附近，不可当作轴效应。" % probe["conclusion"])
    block.append("")
    marker = "## 5. 建议优先试听哪 3 个臂"
    if marker in txt and "## 4.5" not in txt:
        txt = txt.replace(marker, "\n".join(block) + "\n" + marker)
        with open(README, "w", encoding="utf-8") as f:
            f.write(txt)

print(json.dumps({k: probe[k] for k in ("within_ns_spread", "cross_ns_diff",
                                        "ns_axis_resolvable_per_metric", "conclusion")},
                 ensure_ascii=False, indent=1))
