# -*- coding: utf-8 -*-
"""诊断：ANONEKO 导出的 VITS onnx 在 sherpa-onnx 上的元数据/加载兼容性。

逐步执行并把每一步结果写盘（避免控制台编码与管道问题吞掉信息）。
"""
import json
import os
import sys
import traceback

OUT = r"D:\s0611\code\Prism\tmp\voice\lvc-analysis\vits-compat-diag.json"
D = os.path.join(r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
                 "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene")
res = {}


def step(name, fn):
    try:
        res[name] = fn()
    except Exception as e:
        res[name] = {"error": type(e).__name__, "detail": str(e)[:500],
                     "tb": traceback.format_exc()[-800:]}


def read_meta():
    import onnx
    out = {}
    for f in ["model.onnx", "model.int8.onnx"]:
        p = os.path.join(D, f)
        m = onnx.load(p)
        out[f] = {kv.key: kv.value[:120] for kv in m.metadata_props}
    return out


def probe_sherpa():
    import sherpa_onnx
    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = 4
    cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=os.path.join(D, "model.onnx"),
        lexicon=os.path.join(D, "lexicon.txt"),
        tokens=os.path.join(D, "tokens.txt"),
        dict_dir=os.path.join(D, "dict"),
    )
    tts = sherpa_onnx.OfflineTts(cfg)
    return {"constructed": True, "sample_rate": int(tts.sample_rate)}


step("metadata", read_meta)
step("sherpa_construct", probe_sherpa)
step("sherpa_construct_no_dict", lambda: None)

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(res, f, ensure_ascii=False, indent=2)

with open(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis\vits-compat-diag.done", "w") as f:
    f.write("done")
