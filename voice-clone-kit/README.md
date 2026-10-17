# voice-clone-kit —— 昔涟本地克隆音色资产包

> **本文件是该 kit 的唯一入口。** 逐条资产台账见 [`docs/ASSETS.md`](docs/ASSETS.md)。
> 最后整理：2026-09-24（资产整理批次，承接 commit `4011f13`）。
> ⚠️ 整个 `voice-clone-kit/` 被 `.gitignore` 排除（个人克隆音色素材，guardrail TKN-VOICE-BUGFIX-002 D1）——**它不在版本控制里，删了就没了**。
> 本次重写前的原 README（MiniMax 平台克隆操作步骤）已整文件保留为 [`docs/legacy-readme-minimax-clone.md`](docs/legacy-readme-minimax-clone.md)。

## 1. 这是什么

把「昔涟」的声音做成**端侧可离线运行**的 TTS 音色，产出两种后端可消费的音色包：

| 后端 | 产物 | 端侧形态 |
| --- | --- | --- |
| **ZipVoice**（零样本克隆，24 kHz） | `build-out/zipvoice/voicepack/cyrene/` + `build-out/zipvoice/dist/cyrene-vits.voicepack` | 应用内导入 `.voicepack` 归档（ADR-057） |
| **VITS**（微调，16 kHz） | `build-out/zipvoice/voicepack/cyrene-vits/` | 权重内含音色，`references` 为空 |

## 2. 目录布局（2026-09-24 起）

```
voice-clone-kit/
├── assets/                      # 原始素材：不可再生，禁止删除
│   ├── raw/                     #   78 文件 / 22.9 MB  官方全量 VO 素材
│   ├── raw-legacy/              #    4 文件 /  1.5 MB  早期批次素材
│   ├── transcripts/             #    3 文件 / 98.5 KB  官方台词文本与克隆提示词转写
│   └── minimax/                 #    2 文件 /  5.2 MB  MiniMax 克隆产物（mp3 + 提示音频）
├── build-out/                   # 构建产物：可再生（训练检查点除外），但重建代价高
│   ├── vits/
│   │   ├── dataset/             #   81 文件 / 16.5 MB  VITS 训练语料
│   │   ├── listening/           #    5 文件 /  2.1 MB  试听样本 + 导出验证 JSON
│   │   └── toolchain/           # 1439 文件 /  6.5 GB  ANONEKO 工具链 + Plachta 底模 + 训练输出
│   └── zipvoice/
│       ├── dist/                #    1 文件 / 117.7 MB cyrene-vits.voicepack
│       ├── local-model/         #  729 文件 /  1.3 GB  sherpa-onnx ZipVoice 模型（可重新下载）
│       └── voicepack/           #  387 文件 / 711.8 MB 已组装音色包（cyrene / cyrene-vits）
├── tools/                       # 可复算脚本（原 .tmp-cyrene/ 的 15 个 .py，2026-09-24 迁入）
├── docs/
│   ├── ASSETS.md                # 资产台账
│   └── legacy-readme-minimax-clone.md   # 重写前的原 README
├── *.ps1                        # 构建/组装/侧载流水线（见 §5）
├── run-vits-finetune.py         # VITS 续训驱动
└── README.md                    # 本文件
```

## 3. 许可与来源

| 资产 | 来源 | 许可 |
| --- | --- | --- |
| `build-out/vits/toolchain/sherpa-onnx-vits-fine-tuning-webui/` | [ANONEKO777/sherpa-onnx-vits-fine-tuning-webui](https://github.com/ANONEKO777/sherpa-onnx-vits-fine-tuning-webui) | **MIT**（仓库内含 `LICENSE`） |
| VITS 底模 `G_0.pth` / `D_0.pth` | [Plachta/sampled_audio4ft](https://huggingface.co/datasets/Plachta/sampled_audio4ft) → `VITS-Chinese/`（**中文单语 C 模型**） | 见上游仓库；仅本地训练使用，不再分发 |
| `build-out/zipvoice/local-model/` | sherpa-onnx ZipVoice distill int8/fp32（zh-en-emilia） | 见上游 sherpa-onnx 发布页 |
| `assets/raw/`、`assets/raw-legacy/`、`assets/transcripts/` | 昔涟官方语音素材（B站 Wiki / 官方台词表） | **用户私有素材**，不入库、不再分发 |
| `assets/minimax/` | MiniMax 声音克隆产物 | 同「用户私有素材」 |

选中文单语 C 底模的理由（出自 `prepare-vits-toolchain.ps1` 注释）：中文角色不需要日/英前端；且路径在 HF `datasets` 仓库下 —— hf-mirror 对 `datasets` 有镜像、对 `spaces` **无**镜像（实测 `hf-mirror.com/spaces/...` 返回 404）。

## 4. 复算链路

```
assets/raw/  (78 条官方 VO)
    │
    ├─(1)─ build-vits-dataset.ps1 ──────────────► build-out/vits/dataset/
    │        44.1 kHz 单声道 16 bit + 逐字官方台词
    │        （依赖 assets/transcripts/cyrene-voice-lines.json）
    │
    ├─(2)─ prepare-vits-toolchain.ps1 ─────────► build-out/vits/toolchain/
    │        ANONEKO 工具链 + Plachta 中文底模 + finetune_speaker.json
    │
    ├─(3)─ run-vits-finetune.py ───────────────► build-out/vits/toolchain/.../models/vits-cyrene/
    │        需 CUDA；REPO_ROOT 按候选路径自解析（新旧位置都能跑）
    │
    ├─(4)─ 导出 ONNX ──────────────────────────► .../onnx-output/vits-cyrene/ 与 onnx-output-e500/vits-cyrene/
    │
    ├─(5)─ tools/fix-vits-onnx-metadata.py ────► .../model.fixed.onnx（不覆盖原文件）
    │        ⚠️ 必做：comment 为空会让 sherpa native 直接 abort（SHERPA_ONNX_EXIT(-1)）
    │
    ├─(6)─ tools/verify-vits-export.py ────────► build-out/vits/listening/vits-export-verify.json
    │        PC 侧最小闭环：真的能被 sherpa-onnx 加载并合成中文 + RTF
    │
    ├─(7)─ tools/make-vits-sample.py ──────────► 试听样本（fp32/int8）
    │        ⚠️ 必须显式给 <标签> 与 <onnx 目录>，否则会覆盖 epoch-100 基线
    │
    └─(8)─ build-vits-voicepack.ps1 [-Push] ──► build-out/zipvoice/voicepack/cyrene-vits/
             组装音色包（voicepack.json + model/）并可选推送真机
             pack-voicepack.ps1 再打成 .voicepack 归档 → build-out/zipvoice/dist/
```

ZipVoice 路线（零样本克隆，无需训练）：

```
assets/minimax/cyrene-clone-prompt.wav + assets/transcripts/cyrene-clone-prompt-transcript.txt
    └─ build-local-voicepack.ps1 ─► build-out/zipvoice/voicepack/cyrene/{model,refs,voicepack.json}
         └─ install-local-voicepack.ps1 [-Push] ─► 设备 files/voicepack/<voiceId>/
              └─ 应用内导入（ADR-057）或侧载
```

## 5. 脚本清单

| 脚本 | 作用 | 产物 |
| --- | --- | --- |
| `build-vits-dataset.ps1` | 官方 VO → VITS 训练语料 | `build-out/vits/dataset/`（`sampled_audio4ft/`、`manifest.json`、`build-report.txt`） |
| `prepare-vits-toolchain.ps1` | 取工具链与底模 | `build-out/vits/toolchain/` |
| `run-vits-finetune.py` | 微调/续训驱动 | `.../models/vits-cyrene/` |
| `build-local-voicepack.ps1` | ZipVoice 参考集 + 清单 | `build-out/zipvoice/voicepack/cyrene/` |
| `build-vits-voicepack.ps1` | VITS 导出物 → 音色包（可 `-Push`） | `build-out/zipvoice/voicepack/cyrene-vits/` |
| `install-local-voicepack.ps1` | 侧载到真机内部目录 | 设备 `files/voicepack/` |
| `pack-voicepack.ps1` | 音色包 → `.voicepack` 归档 | `build-out/zipvoice/dist/` |
| `parse-wiki-vo-lines.ps1` | 官方台词表 → JSON | `assets/transcripts/cyrene-voice-lines.json` |
| `rebuild-clone-audio.ps1` | 重建克隆提示音频 | `assets/minimax/` |
| `tools/*.py` | 分析/导出/验证/参数扫描，逐条见 `docs/ASSETS.md` §4 | 输出统一写 `tmp/voice/lvc-analysis/` |

### ⚠️ 5.1 已知不一致（2026-09-24 整理后遗留，未修）

上表"产物"列是**重写后的正确位置**，但下列 `.ps1` 脚本内部的**路径常量仍是整理前的旧位置**，直接运行会把产物写到已不存在的目录（如 `<kit>/vits-dataset/`）：

`prepare-vits-toolchain.ps1`（L42）、`build-vits-dataset.ps1`（L3, L35）、`build-vits-voicepack.ps1`（L4, L37, L38, L46）、`install-local-voicepack.ps1`（L33, L56）、`build-local-voicepack.ps1`（L15, L17, L18, L55, L56）、`pack-voicepack.ps1`（L4）。

修法：映射表已固化在 `tmp/tools/fix-kit-paths.ps1`（本批用它修好了 `tools/*.py`），扩展 `.ps1` 部分即可。
**改 `prepare-vits-toolchain.ps1` 时必须写回 UTF-8 BOM**，否则 Windows PowerShell 5.1 会按 GBK 解析中文注释并报假的 `Missing closing '}'`。
另：该脚本要求 `$ErrorActionPreference` 保持 `Continue`，否则 `git`/`curl` 写 stderr 会被当成终止性错误。

## 6. 当前状态

| 项 | 状态 |
| --- | --- |
| VITS epoch-100 基线 | 已导出并验证可被 sherpa-onnx 加载合成中文（`build-out/vits/listening/vits-export-verify.json`） |
| VITS epoch-500（TKN-LVC-016 续训） | 已产出 `onnx-output-e500/vits-cyrene/`；A/B 对拍脚本 `tools/ab-vits-e100-vs-e500.py` |
| VITS 续训（500 → **1000 epoch**） | ✅ **已完成**（2026-09-25 03:16，exit 0；跑到 epoch 1000 / step 8990）。逐 step 损失在 `.../models/vits-cyrene/train.log`（**不是** stdout 重定向的那个 log），字段序 `[disc, gen, fm, mel, dur, kl, step, lr]` ⇒ **mel 是下标 3**。mel：e100 17.2211 → e500~600 均值 11.9734 → e900~999 均值 10.6968（n=90）|
| ⚠️ 检查点目录 | `build-out/vits/toolchain/.../models/vits-cyrene/` 是**唯一训练事实来源**（`G_latest.pth` 已更新为 e1000 权重，哈希 `FB4FA7DF…`，与 e500 备份 `309A0CAB…` 不同而同尺寸）——**不要移动或改写该目录** |
| 试听样本 | 现位于 **`build-out/vits/listening/`**：`vits-cyrene-sample-{fp32,int8}.wav`（epoch-100 基线）与 `...-e500-{fp32,int8}.wav`（16 kHz，5 句）。<br>⚠️ **路径已变更**：2026-09-24 整理前是 `voice-clone-kit/vits-listening-samples/`。**历史报告（ADR-055、批次验收报告、proposal、progress）仍沿用旧路径**，看到旧路径请按此表换算——这些一次性报告按 rules/05 §12.6 作历史记录保留，不逐一改写 |
| ZipVoice RTF | PC 侧归因脚本 `tools/bench-zipvoice.py`（绝对值不可外推到端侧，比值可） |
| 脚本可运行性 | ✅ **18 个 kit `.py` 全部 `py_compile` 通过**（含 `tools/sweep-vits-arms.py`，已实跑产出 19 臂样本）；8 个 `.ps1` 全部 `ParseErrors=0` 且带 UTF-8 BOM。~~`sweep-vits-arms.py:329` SyntaxError~~ 该行**已不成立**（2026-09-25 实测），此行原记录为过期信息，已更正 |

## 7. 哪些目录可安全删除

| 可删 | 前提 |
| --- | --- |
| `build-out/zipvoice/local-model/`（1.3 GB） | 可重新下载；`install-local-voicepack.ps1` 会在缺失时自动下载 |
| `build-out/zipvoice/dist/`（117.7 MB） | `pack-voicepack.ps1` 可重新打包 |
| `build-out/zipvoice/voicepack/`（711.8 MB） | 脚本可从模型 + 素材重新组装 |
| `build-out/vits/dataset/`（16.5 MB） | `build-vits-dataset.ps1` 可重建（需 `assets/raw/` 完整） |
| `build-out/vits/listening/`（2.1 MB） | 重新跑 `verify-vits-export.py` / `make-vits-sample.py` 即得 |
| `__pycache__/`、`tools/__pycache__/` | 字节码缓存 |

**不可删**：

- `assets/raw/`、`assets/raw-legacy/`、`assets/transcripts/`、`assets/minimax/` —— 原始素材，来源页面可能已下架。
- `build-out/vits/toolchain/.../models/vits-cyrene/*.pth` —— **训练检查点不可再生**（重训成本数百 GPU 小时）；且当前可能有进程正在写入。
- `build-out/vits/toolchain/.../pretrained_models/{G_0,D_0}.pth` —— 上游底模，重下需联网。

## 8. 相关文档

- [`docs/ASSETS.md`](docs/ASSETS.md) —— 逐条资产台账（路径/体积/来源/许可/可再生/复算命令）
- [`docs/legacy-readme-minimax-clone.md`](docs/legacy-readme-minimax-clone.md) —— MiniMax 平台克隆操作步骤（原 README）
- [`../docs/runbooks/finetune-vits-zh-cyrene.md`](../docs/runbooks/finetune-vits-zh-cyrene.md) —— 中文 VITS 微调 runbook
- [`../docs/runbooks/setup-local-voice-clone.md`](../docs/runbooks/setup-local-voice-clone.md) —— 本地克隆环境搭建
- [`../docs/decisions/ADR-055-ondevice-vits-voicepack.md`](../docs/decisions/ADR-055-ondevice-vits-voicepack.md)、[ADR-057](../docs/decisions/ADR-057-voicepack-inapp-import.md)
- [`../tmp/README.md`](../tmp/README.md) —— 一次性产物的落点约定
