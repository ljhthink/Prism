"""比对 G_latest.pth 与 G_<n>.pth 的真实内容身份（TKN-LVC-016 续训收尾用）。

背景：两个文件的字节哈希与**体积**都不同（158,896,284 vs 158,888,160），
但日志显示它们都在 "iteration 498" 这一轮里写出。必须查清"哪个才是 epoch 500 的权重"，
否则会把**回退了一版**的模型导出成音色包（表现为"训完反而变差了"）。
"""
import hashlib
import json
import sys

import torch

paths = sys.argv[1:]
result = {}
for p in paths:
    ck = torch.load(p, map_location="cpu")
    sd = ck.get("model", {})
    h = hashlib.sha256()
    for k in sorted(sd.keys()):
        t = sd[k]
        h.update(k.encode("utf-8"))
        h.update(str(tuple(t.shape)).encode("utf-8"))
        h.update(str(t.dtype).encode("utf-8"))
        h.update(t.detach().to(torch.float32).cpu().numpy().tobytes())
    opt = ck.get("optimizer")
    nopt = sum(len(v.get("state", {})) for v in opt["param_groups"]) if opt else -1
    result[p] = {
        "iteration": ck.get("iteration"),
        "learning_rate": ck.get("learning_rate"),
        "n_model_tensors": len(sd),
        "model_sha256": h.hexdigest()[:32],
        "n_optimizer_states": nopt,
        "optimizer_keys": sorted(opt.keys()) if opt else None,
        "n_param_groups": len(opt["param_groups"]) if opt else 0,
    }
    del ck
    del sd

print(json.dumps(result, indent=2, ensure_ascii=False, default=str))
