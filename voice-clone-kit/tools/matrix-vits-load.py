# -*- coding: utf-8 -*-
"""TKN-LVC-014：sherpa-onnx 加载导出 VITS 的**隔离矩阵**测（每个变体一个子进程，崩溃不影响其余）。

背景：直接在主进程里构造 `OfflineTts` 会**原生崩溃**（exit=-1，无 Python traceback），
只能靠 subprocess 隔离逐变体定位到底是哪个配置项触发。

用法：python voice-clone-kit/tools/matrix-vits-load.py
"""
import json
import os
import subprocess
import sys

D = os.path.join(r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
                 "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene")
PY = sys.executable
CHILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_child_vits_load.py")
OUT = os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis", "vits-load-matrix.json")

VARIANTS = [
    ("model.onnx", []),
    ("model.onnx", ["--dict-dir"]),
    ("model.onnx", ["--rule-fsts"]),
    ("model.onnx", ["--dict-dir", "--rule-fsts"]),
    ("model.int8.onnx", []),
    ("model-opt.onnx", []),
]

results = []
for model, extra in VARIANTS:
    p = os.path.join(D, model)
    if not os.path.isfile(p):
        results.append({"model": model, "opts": extra, "status": "skip-missing"})
        continue
    cmd = [PY, CHILD, "--dir", D, "--model", model] + extra
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    results.append({
        "model": model,
        "opts": extra,
        "returncode": r.returncode,
        "stdout_tail": (r.stdout or "")[-400:],
        "stderr_tail": (r.stderr or "")[-700:],
    })

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print("matrix done")
