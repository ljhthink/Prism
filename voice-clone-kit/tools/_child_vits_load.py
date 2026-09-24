# -*- coding: utf-8 -*-
"""矩阵子进程：尝试构造 OfflineTts，成功则合成一句并写结果。

崩溃即崩溃（父进程据 returncode 判定），故这里不做任何兜底。
"""
import argparse
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--dict-dir", action="store_true")
    ap.add_argument("--rule-fsts", action="store_true")
    a = ap.parse_args()

    import sherpa_onnx
    print("imported sherpa_onnx", flush=True)

    cfg = sherpa_onnx.OfflineTtsConfig()
    cfg.model = sherpa_onnx.OfflineTtsModelConfig()
    cfg.model.num_threads = 2
    kwargs = dict(
        model=os.path.join(a.dir, a.model),
        lexicon=os.path.join(a.dir, "lexicon.txt"),
        tokens=os.path.join(a.dir, "tokens.txt"),
    )
    if a.dict_dir:
        kwargs["dict_dir"] = os.path.join(a.dir, "dict")
    cfg.model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(**kwargs)
    if a.rule_fsts:
        cfg.rule_fsts = ",".join(
            os.path.join(a.dir, f) for f in
            ["date.fst", "number.fst", "phone.fst", "new_heteronym.fst"])
    print("cfg built, constructing OfflineTts ...", flush=True)
    tts = sherpa_onnx.OfflineTts(cfg)
    print("CONSTRUCTED sample_rate=%d" % tts.sample_rate, flush=True)
    au = tts.generate("你好，我是昔涟。", sid=0, speed=1.0)
    print("SYNTH_OK samples=%d sr=%d" % (len(au.samples), au.sample_rate), flush=True)


if __name__ == "__main__":
    main()
