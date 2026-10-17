# `voice-clone-kit` 资产台账

> 逐条记录 `voice-clone-kit/` 下资产的路径、体积、来源、许可、是否可再生、复算命令。
> 生成于 2026-09-24（资产整理批次）。体积为 `Get-ChildItem -Recurse | Measure-Object Length -Sum` 实测值。
> ⚠️ 整个 `voice-clone-kit/` 被 `.gitignore` 排除 —— 本台账是**唯一**的资产清册，删资产前请先读本文件。

## 1. 台账

| 路径 | 体积 | 来源 | 许可 | 可再生? | 复算命令 |
| --- | --- | --- | --- | --- | --- |
| `assets/raw/` (78 文件) | 22,885,032 B | 昔涟官方 VO 素材（B站 Wiki / 官方语音页） | 用户私有，仅本地 | ❌ **不可再生**（来源页可能下架） | — |
| `assets/raw-legacy/` (4 文件) | 1,479,764 B | 早期批次抓取的素材 | 用户私有，仅本地 | ❌ **不可再生** | — |
| `assets/transcripts/cyrene-vo-wikitext.json` | 见下 | 官方 Wiki 语音页原文（wikitext） | 用户私有 | ❌ **不可再生**（页面快照） | — |
| `assets/transcripts/cyrene-voice-lines.json` | 见下 | 由 wikitext 解析出的逐字官方台词 | 用户私有 | ⚠️ 可从 `cyrene-vo-wikitext.json` 重解析 | `powershell -File parse-wiki-vo-lines.ps1` |
| `assets/transcripts/cyrene-clone-prompt-transcript.txt` | 见下 | 克隆提示音频的逐字转写 | 用户私有 | ❌ 需人工听写 | `powershell -File rebuild-clone-audio.ps1`（音频，非转写） |
| `assets/transcripts/` 小计 (3 文件) | 98,535 B | — | — | — | — |
| `assets/minimax/cyrene-clone-main.mp3` | 见下 | MiniMax 声音克隆输出 | 用户私有，不再分发 | ❌ **不可再生**（需 MiniMax 账号 + 原始素材） | — |
| `assets/minimax/cyrene-clone-prompt.wav` | 见下 | 从 mp3 裁出的提示音频 | 用户私有 | ⚠️ 可从 mp3 重裁 | `powershell -File rebuild-clone-audio.ps1` |
| `assets/minimax/` 小计 (2 文件) | 5,219,616 B | — | — | — | — |
| `build-out/vits/dataset/` (81 文件) | 16,477,341 B | `assets/raw/` + `assets/transcripts/cyrene-voice-lines.json` | 派生 | ✅ 可再生 | `powershell -File build-vits-dataset.ps1` |
| `build-out/vits/listening/` (5 文件) | 2,120,972 B | 微调后 VITS 的合成样本 + 导出验证 JSON | 派生 | ✅ 可再生 | `python tools/verify-vits-export.py`; `python tools/make-vits-sample.py <标签> <onnx目录>` |
| `build-out/vits/toolchain/` (1439 文件) | 6,497,616,983 B | 见下 §2 细分 | — | 部分 | — |
| `build-out/zipvoice/dist/cyrene-vits.voicepack` (1 文件) | 117,658,124 B | `build-out/zipvoice/voicepack/cyrene-vits/` 打包 | 派生 | ✅ 可再生 | `powershell -File pack-voicepack.ps1` |
| `build-out/zipvoice/local-model/` (729 文件) | 1,308,046,521 B | sherpa-onnx ZipVoice distill int8/fp32 (zh-en-emilia) | 见上游 sherpa-onnx 发布页 | ✅ 可重新下载 | `powershell -File install-local-voicepack.ps1`（缺失时自动下载） |
| `build-out/zipvoice/voicepack/cyrene/` | 见下 | ZipVoice 零样本克隆音色包（model + refs + voicepack.json） | 派生 | ✅ 可再生 | `powershell -File build-local-voicepack.ps1` |
| `build-out/zipvoice/voicepack/cyrene-vits/` | 见下 | VITS 微调音色包（model + voicepack.json，references 为空） | 派生 | ✅ 可再生 | `powershell -File build-vits-voicepack.ps1` |
| `build-out/zipvoice/voicepack/` 小计 (387 文件) | 711,801,500 B | — | — | — | — |
| `tools/` (17 个 .py + `__pycache__`) | 146,752 B | 原 `.tmp-cyrene/` 的 15 个可复用脚本（2026-09-24 迁入）+ 2 个既有脚本 | 项目内 | ✅ | — |
| `*.ps1`（9 个）+ `run-vits-finetune.py` | — | 项目内流水线脚本 | 项目内 | ✅ | — |

## 2. `build-out/vits/toolchain/` 细分（含不可再生项）

路径前缀：`build-out/vits/toolchain/sherpa-onnx-vits-fine-tuning-webui/`

| 子路径 | 来源 | 许可 | 可再生? |
| --- | --- | --- | --- |
| 工具链本体（`*.py`、`webui.py`、`espeak-ng.exe` 等） | [ANONEKO777/sherpa-onnx-vits-fine-tuning-webui](https://github.com/ANONEKO777/sherpa-onnx-vits-fine-tuning-webui) | **MIT**（仓库含 `LICENSE`） | ✅ `git clone` |
| `pretrained_models/{G_0,D_0}.pth` | [Plachta/sampled_audio4ft](https://huggingface.co/datasets/Plachta/sampled_audio4ft) → `VITS-Chinese/` | 见上游（仅本地训练用，不再分发） | ✅ 需联网（hf-mirror 对 `datasets` 有镜像） |
| `configs/finetune_speaker.json` | 同上（底模配套 config） | 同上游 | ✅ |
| **`models/vits-cyrene/*.pth`**（`G_4475.pth` 158,888,160 B；`D_4475.pth` 187,021,601 B；`G_latest.pth` 158,896,284 B；`D_latest.pth` 187,027,527 B 等） | **本地训练产出** | 派生 | ❌ **不可再生**（重训成本数百 GPU 小时） |
| `models/vits-cyrene/train.log`、`events.out.tfevents.*` | 训练日志与 TensorBoard 事件 | 派生 | ❌ 随训练过程消失 |
| `onnx-output/vits-cyrene/` | epoch-100 导出 | 派生 | ⚠️ 可从检查点重导出 |
| `onnx-output-e500/vits-cyrene/` | epoch-500 导出（TKN-LVC-016 续训） | 派生 | ⚠️ 同上 |
| `prepare-log.txt` | 工具链准备日志 | 派生 | ✅ |
| `training_data/` | 训练数据挂载点（指向 `build-out/vits/dataset/`） | 派生 | ✅ |

> ⚠️ **`models/vits-cyrene/` 当前可能有 VITS 续训进程正在写入。不要移动、不要改写该目录下的任何文件。**

## 3. 不可再生资产汇总（禁止删除）

1. `assets/raw/`（22.9 MB，78 文件）
2. `assets/raw-legacy/`（1.5 MB，4 文件）
3. `assets/transcripts/cyrene-vo-wikitext.json`（页面快照）
4. `assets/transcripts/cyrene-clone-prompt-transcript.txt`（人工听写）
5. `assets/minimax/cyrene-clone-main.mp3`（MiniMax 克隆输出）
6. `build-out/vits/toolchain/.../models/vits-cyrene/*.pth`（训练检查点）
7. `build-out/vits/toolchain/.../models/vits-cyrene/train.log`、`events.out.tfevents.*`（训练过程证据）

## 4. `tools/*.py` 复算命令对应表

所有脚本的输出统一写 `tmp/voice/lvc-analysis/`（2026-09-24 起；迁移前为 `.tmp-cyrene/`）。

| 脚本 | 用途 | 复算命令（解释器按需） |
| --- | --- | --- |
| `_child_vits_load.py` | 矩阵子进程（被 `matrix-vits-load.py` 调用） | 不单独运行 |
| `analyze-vits-quality.py` | 试听样本 vs 训练语料的客观指标对比 | `python tools/analyze-vits-quality.py` |
| `ab-vits-e100-vs-e500.py` | epoch-100 vs epoch-500 客观对拍 | `python tools/ab-vits-e100-vs-e500.py` |
| `bench-zipvoice.py` | ZipVoice PC 侧 RTF 归因 | `python tools/bench-zipvoice.py` |
| `compare-vits-checkpoints.py` | 比对 `G_latest.pth` 与 `G_<n>.pth` 内容身份 | `python tools/compare-vits-checkpoints.py <pth> <pth>` |
| `diag-vits-compat.py` | sherpa 加载兼容性逐步诊断 | `python tools/diag-vits-compat.py` |
| `dump-vits-onnx-meta.py` | 只读 ONNX 元数据（不触碰 sherpa） | `python tools/dump-vits-onnx-meta.py` |
| `exp-vits-callback.py` | VITS 生成回调语义（增量 vs 累积） | `python tools/exp-vits-callback.py` |
| `exp_callback_semantics.py` | ZipVoice 回调分块语义与首块延迟 | `python tools/exp_callback_semantics.py` |
| `exp2_boundary_and_statusquo.py` | 分块边界不连续性 + 现状仿真 | `python tools/exp2_boundary_and_statusquo.py` |
| `fix-vits-onnx-metadata.py` | 修 `comment` 元数据与标点（不覆盖原文件） | `python tools/fix-vits-onnx-metadata.py [<onnx 目录>]` |
| `make-vits-sample.py` | 生成试听样本 | `python tools/make-vits-sample.py <标签> <onnx 目录>` |
| `matrix-vits-load.py` | 加载变体隔离矩阵测 | `python tools/matrix-vits-load.py` |
| `sweep-vits-arms.py` | VITS 参数臂扫描 | ⚠️ 当前 `SyntaxError`（L329 未转义引号），**不可运行** |
| `tune-vits-params.py` | 语速/F0 根因量化 + 参数扫描 | `python tools/tune-vits-params.py` |
| `verify-vits-export.py` | PC 侧导出物最小闭环 + RTF | `python tools/verify-vits-export.py` |

`<onnx 目录>` 缺省为 epoch-100 的 `build-out/vits/toolchain/sherpa-onnx-vits-fine-tuning-webui/onnx-output/vits-cyrene`；TKN-LVC-016 续训产物在 `onnx-output-e500/vits-cyrene`，**必须显式传入** —— 否则会把新权重的修复写回旧目录，污染「epoch 100 基线」的证据面。
