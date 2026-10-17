# -*- coding: utf-8 -*-
"""只读 onnx 元数据（不触碰 sherpa，避免原生崩溃吞掉信息）。"""
import json
import os

import onnx

D = os.path.join(r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
                 "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene")
out = {}
for f in ["model.onnx", "model.int8.onnx", "model-opt.onnx"]:
    p = os.path.join(D, f)
    if not os.path.isfile(p):
        out[f] = None
        continue
    m = onnx.load(p)
    out[f] = {
        "ir_version": m.ir_version,
        "opset": [i.version for i in m.opset_import],
        "metadata": {kv.key: kv.value[:200] for kv in m.metadata_props},
        "inputs": [f"{i.name}:{i.type.tensor_type.elem_type}"
                   f"[{','.join(str(d.dim_value or d.dim_param) for d in i.type.tensor_type.shape.dim)}]"
                   for i in m.graph.input],
        "outputs": [f"{o.name}:{o.type.tensor_type.elem_type}"
                    f"[{','.join(str(d.dim_value or d.dim_param) for d in o.type.tensor_type.shape.dim)}]"
                    for o in m.graph.output],
    }

with open(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis\vits-onnx-meta.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print("meta done")
