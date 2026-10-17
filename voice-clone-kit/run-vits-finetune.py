# -*- coding: utf-8 -*-
"""TKN-LVC-014：VITS 微调驱动脚本（绕过工具链的 CLI 缺口）

## 为什么需要这个脚本

工具链仓库 `vits_fast_fine_tuning/LOCAL.md` 记录的 CLI 用法
`python finetune_speaker_v2.py -m ./OUTPUT_MODEL --max_epochs N` 在该 fork 里**是坏的**：

```python
def main():                      # finetune_speaker_v2.py:415
    args = get_args()
    run_finetuning(args)         # ← run_finetuning 需要 8 个位置参数

TypeError: run_finetuning() missing 7 required positional arguments:
  'model_name', 'max_epochs', 'continue_training', 'drop_speaker_embed',
  'train_with_pretrained_model', 'preserved_models', and 'batch_size'
```

即该 fork 只维护了 **WebUI 调用路径**（`ui/finetuning.py:84` 直接按位置传参），CLI 路径未随签名更新。
本脚本按 `ui/finetuning.py` 的方式调用 `run_finetuning(...)`，从而可在无 GUI 的 CI/会话里复算。

## 用法（工作目录 = 工具链仓库根）

```powershell
& C:\\Users\\ljh\\.conda\\envs\\vits38\\python.exe ..\\..\\run-vits-finetune.py --max-epochs 2
```

## 注意

- 必须 `torch.cuda.is_available()`；CPU 训练被 `run_finetuning` 断言拒绝。
- 日志里的 `torio ... FFmpeg extension is not available` **是噪声不是致命错误**（实测
  `torchaudio.list_audio_backends() == ['soundfile']` 且 `torchaudio.load()` 正常），
  真正读过日志才不会被它带偏。
"""
import argparse
import os
import sys

# REPO_ROOT 解析：2026-09-24 资产整理把工具链从 `<kit>/vits-toolchain/` 挪到了
# `<kit>/build-out/vits/toolchain/`，本脚本自身也可能从 `<kit>/` 挪到 `<kit>/pipeline/`。
# 按候选列表逐个探测，取第一个真实存在的目录 —— 因此**新旧两种位置都能跑**。
_HERE = os.path.dirname(os.path.abspath(__file__))
_TOOLCHAIN_TAIL = "sherpa-onnx-vits-fine-tuning-webui"
_REPO_CANDIDATES = [
    # 本脚本在 <kit>/pipeline/ 时（迁移后位置）
    os.path.join(os.path.dirname(_HERE), "build-out", "vits", "toolchain", _TOOLCHAIN_TAIL),
    # 本脚本在 <kit>/ 时（当前位置）
    os.path.join(_HERE, "build-out", "vits", "toolchain", _TOOLCHAIN_TAIL),
    # 整理前的旧布局回落
    os.path.join(_HERE, "vits-toolchain", _TOOLCHAIN_TAIL),
    os.path.join(os.path.dirname(_HERE), "vits-toolchain", _TOOLCHAIN_TAIL),
]
REPO_ROOT = next((p for p in _REPO_CANDIDATES if os.path.isdir(p)), _REPO_CANDIDATES[0])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--config", default="training_data/configs/modified_finetune_speaker.json")
    p.add_argument("--model-name", default="models/vits-cyrene")
    p.add_argument("--max-epochs", type=int, default=100)
    p.add_argument("--continue-training", action="store_true")
    p.add_argument("--drop-speaker-embed", action="store_true", default=True)
    p.add_argument("--train-with-pretrained-model", action="store_true", default=True)
    p.add_argument("--preserved-models", type=int, default=4)
    p.add_argument("--batch-size", type=int, default=16)
    a = p.parse_args()

    repo = os.environ.get("VITS_REPO_ROOT", REPO_ROOT)
    os.chdir(repo)
    sys.path.insert(0, repo)

    from vits_fast_fine_tuning.finetune_speaker_v2 import run_finetuning

    run_finetuning(
        a.config,
        a.model_name,
        a.max_epochs,
        a.continue_training,
        a.drop_speaker_embed,
        a.train_with_pretrained_model,
        a.preserved_models,
        a.batch_size,
    )


if __name__ == "__main__":
    main()
