# -*- coding: utf-8 -*-
"""TKN-LVC-014：修复导出 ONNX 的元数据，使 sherpa-onnx 可加载。

## 现象（一手）

用本机 `sherpa_onnx` 1.13.8 构造 `OfflineTts` 时**原生崩溃**（exit=-1，无 Python traceback），
最后一条日志为：

```
sherpa-onnx/csrc/offline-tts-vits-model.cc:Init:198 'comment' does not exist in the metadata
```

## 根因（上游源码，v1.13.8）

`offline-tts-vits-model.cc:197-198`：

```cpp
std::string comment;
SHERPA_ONNX_READ_META_DATA_STR(comment, "comment");   // ← 无 DEFAULT 的宏：缺失即 SHERPA_ONNX_EXIT(-1)
```

而同文件的其它键用的是 `SHERPA_ONNX_READ_META_DATA_WITH_DEFAULT`（缺失不致命）。
即：**`comment` 是唯一必须存在、且缺失就会 abort 的键**。

ANONEKO 导出脚本确实写了 `comment`，但值为**空串**——实测仍然被判定为"不存在"
（很可能是 onnx 元数据里空值条目在 onnxruntime 侧查找失败，或写出时被跳过）。

## 处置

重新写元数据：把 `comment` 置为**非空 ASCII**，并顺手把导出的乱码 `punctuation` 归一为
sherpa 期望的半角标点集合；其余键原样保留。产出 `model.fixed.onnx` / `model.int8.fixed.onnx`，
**不覆盖原文件**（原文件哈希仍是导出证据）。

用法：python voice-clone-kit/tools/fix-vits-onnx-metadata.py [<onnx 目录>]

  <onnx 目录> 缺省为 epoch-100 的 `onnx-output/vits-cyrene`；TKN-LVC-016 续训导出物在
  `onnx-output-e500/vits-cyrene`，必须显式传入 —— 否则会把**新权重**的修复写回旧目录，
  污染"epoch 100 基线"的证据面。
"""
import json
import os
import sys

import onnx

_DEFAULT = os.path.join(r"D:\s0611\code\Prism\voice-clone-kit\build-out\vits\toolchain",
                       "sherpa-onnx-vits-fine-tuning-webui", "onnx-output", "vits-cyrene")
D = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else _DEFAULT
COMMENT = "vits-cyrene finetuned by TKN-LVC-014 (sherpa-onnx compatible)"
PUNCT = ", . ! ? ; : - ~"
_TAG = os.path.basename(os.path.dirname(D)) or "default"
OUT = os.path.join(r"D:\s0611\code\Prism\tmp\voice\lvc-analysis", f"vits-onnx-fix-{_TAG}.json")

res = {"before": {}, "after": {}, "errors": {}}

for name in ["model.onnx", "model.int8.onnx"]:
    src = os.path.join(D, name)
    dst = os.path.join(D, name.replace(".onnx", ".fixed.onnx"))
    try:
        m = onnx.load(src)
        res["before"][name] = {kv.key: repr(kv.value)[:80] for kv in m.metadata_props}

        have = {kv.key for kv in m.metadata_props}
        def setkv(k, v):
            for kv in m.metadata_props:
                if kv.key == k:
                    kv.value = v
                    return
            m.metadata_props.add(key=k, value=v)

        setkv("comment", COMMENT)
        setkv("punctuation", PUNCT)
        if "model_type" not in have:
            setkv("model_type", "vits")

        onnx.save(m, dst)
        res["after"][name] = {"written": dst, "size_mb": round(os.path.getsize(dst) / 1e6, 1),
                             "metadata": {kv.key: repr(kv.value)[:80] for kv in m.metadata_props}}
    except Exception as e:
        res["errors"][name] = f"{type(e).__name__}: {e}"

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(res, f, ensure_ascii=False, indent=2)
print("fix done")
