# Runbook：中文 VITS 微调（昔涟音色）→ sherpa-onnx 端侧

> 关联：方案 `docs/reports/2026-09-20-voice-clone-streaming-and-vits-plan.md` §B（含 §B.4 一手核查结论）、
> 路线调研 `docs/reports/2026-09-20-voice-clone-vits-route-research.md`、ADR-054。
> 任务令牌：**TKN-LVC-014**。素材仅个人自用，产物**不外发、不入库、不入 APK**。
> ⚠️ 本 Runbook 的**命令依据是工具链仓库内的 `vits_fast_fine_tuning/LOCAL.md` 原文**（已 clone 到本机）；
> 凡本机**未实测**的步骤均标注 `[未实测]`。

## ⚠️ 已实测更正（2026-09-20，全链路已跑通，详见 `docs/reports/2026-09-20-vits-finetune-verification.md`）

本 Runbook 最初是**按上游文档写的计划**；实际执行后有 4 处必须更正（原文保留在下方，便于对照）：

| # | 原文 | 实测事实 |
| --- | --- | --- |
| 1 | 数据集 44.1kHz 单声道 | **必须 16 kHz**：`training_data/configs/{c,cj,cje}_finetune_speaker.json` 的 `sampling_rate` **都是 16000**，且 `data_utils.get_audio()` 用 `torchaudio.load()` **不做重采样** ⇒ 喂 44.1kHz 会得到错误频谱。**产物也就是 16 kHz**（低于 ZipVoice 的 24 kHz） |
| 2 | `python finetune_speaker_v2.py -m ./OUTPUT_MODEL --max_epochs N --save_every_epoch K` | **该 CLI 不可用**：`run_finetuning()` 需要 8 个位置参数，`main()` 只传了 1 个（`TypeError`）。且**没有 `--save_every_epoch` 这个参数**。⇒ 用 `voice-clone-kit/run-vits-finetune.py` |
| 3 | 训练启动即可跑 | 会在 `TCPStore` rendezvous 处 `DistNetworkError: Unknown error` ⇒ 需把 `MASTER_ADDR` 从 `localhost` 改成 `127.0.0.1`（补丁已打在 vendored `finetune_speaker_v2.py`，允许 `VITS_MASTER_ADDR/PORT` 覆盖） |
| 4 | 导出的 onnx 直接可用 | **不能直接用**：`comment` 元数据为空串 ⇒ sherpa-onnx `SHERPA_ONNX_READ_META_DATA_STR(comment,"comment")` 判为缺失 ⇒ **`SHERPA_ONNX_EXIT(-1)` 原生崩溃**。须先跑 `voice-clone-kit/tools/fix-vits-onnx-metadata.py` 把 `comment` 写成非空 ASCII（详见验证报告 §5） |
| 5 | （2026-09-25 新增）`--max-epochs N` 跑到第 N 轮 ⇒ 会得到 epoch N 的检查点 | **不成立**：检查点**只在 `global_step % train.eval_interval == 0` 时落盘**，且**循环结束后没有任何兜底保存**。实测依据：`vits_fast_fine_tuning/finetune_speaker_v2.py` 共 424 行，8 处 `utils.save_checkpoint(...)` **全部位于循环体内**（L313/316/320/323/326/328/340/342），`if global_step % hps.train.eval_interval == 0:`（L310）是唯一的落盘闸门；本机 `models/vits-cyrene/config.json` 的 `train.eval_interval = 25`（实测 9 step/epoch）。⇒ **每轮训练的最后 <25 step 必然丢失**。续训 500→1000 的实测后果：磁盘最新权重为 `G_latest.pth` 与 `G_8975.pth`，二者 **858 个 `model.*` 张量逐一 bit-identical、`iteration` 字段均为 998**（哈希不同、大小差 8124 B 发生在 model 张量之外）⇒ **epoch 999 / step 8990 的权重根本不存在**（train.log 打完 `====> Epoch: 1000` 即 exit 0，无检查点）。**影响与用法**：对 1000-epoch 的模型质量可忽略（mel 每 100 epoch 才动 0.26），但 **"训到第 N 轮" ≠ "有第 N 轮权重"**——引用检查点时须按 `实际 step = floor(总step / eval_interval) * eval_interval` 反推。**这是 vendored fork 的既有行为，未打补丁**；若要让末轮落盘，须在循环后补一次无条件 `save_checkpoint`（注意工具链在 `build-out/` 下、不入版本控制，补丁需同步记入本 Runbook 或 `voice-clone-kit/README.md` 的"重建后必做"清单，否则重新 clone 即丢失） |

实测数值（可复算）：**78 条 / 513.67s**；**100 epochs ≈ 35 分钟**；显存峰值 **7570MiB@batch16 / 6552MiB@batch8**；
导出 **fp32 RTF 0.10–0.11、int8 RTF 0.90–1.00**（**端上应选 fp32**，与本模型 int8 反而更慢）；
端侧资产合计 **≈132MB**。


## 0. 目标

把「端侧 ZipVoice 零样本克隆」的相似度从 **0.8079**（同人天花板 0.9104）往 **≥0.88** 提，
同时把真机 RTF 从 **3.67** 压到尽可能低（ZipVoice 真机 RTF>1 是"听一句等很久"的根因，见 ADR-054）。

**验收口径（沿用既有裁判，只换引擎）**：
- 客观：CAM++ 说话人嵌入余弦 **目标 ≥0.88**；
- 主观：真机听感（自然度、"机器感"）；
- 成本：RTF 与峰值内存（VITS 预期明显低于 ZipVoice）；
- 长句泛化：78 条均为短句，**长句是否过拟合必须真机验证**。

## 1. 已完成的前置（本机实测，2026-09-20）

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 训练数据 | ✅ 78 条 / **513.67s（8.56 分钟）** / 平均 6.59s / 台词覆盖 78/78 | `voice-clone-kit/build-vits-dataset.ps1` 实测退出码 0；产物 `build-out/vits/dataset/`（`sampled_audio4ft.txt`、`manifest.json`、`build-report.txt`） |
| Python 环境 | ✅ conda env `vits38` = Python **3.8.20** + `torch 2.4.1+cu124` + `torchaudio 2.4.1` + `CUDA=True`（RTX 4060 Laptop 8GB） | `voice-clone-kit/prepare-vits-toolchain.ps1` 配套命令 |
| 工具链仓库 | ✅ `ANONEKO777/sherpa-onnx-vits-fine-tuning-webui`（MIT）已 clone | `voice-clone-kit/build-out/vits/toolchain/sherpa-onnx-vits-fine-tuning-webui/` |
| 底模（中文单语 C） | ✅ `G_0.pth` 151.7MB / `D_0.pth` 178.4MB / `configs/finetune_speaker.json`（经 **hf-mirror** 取得；HF 直连被重置、`hf-mirror.com/spaces/` 404，故走 **datasets** 路径） | `build-out/vits/toolchain/prepare-log.txt` |
| 训练依赖 | ⏳ `pip install -r requirements.txt`（含 `demucs`/`openai-whisper`/`gradio`/`onnx==1.11.0`）与 `monotonic_align` 编译 | 见 §7 风险 |

## 2. 训练（命令级）

工作目录一律为 `voice-clone-kit/build-out/vits/toolchain/sherpa-onnx-vits-fine-tuning-webui`。

### 2.1 放数据（**跳过 Whisper 转写**——我们已有逐字官方台词）

LOCAL.md 的原流程要求先 `short_audio_transcribe.py --whisper_size large` 生成
`short_character_anno.txt`；**本机 8GB 显存不满足其 12GB 建议**，而我们的转写来自 WIKI 官方台词、
且 `build-vits-dataset.ps1` 已产出**同格式**（`路径|说话人|[ZH]文本[ZH]`）的清单 ⇒ **直接复用，免去转写步骤**：

```powershell
# ① 生成语料（**必须 16 kHz**，见顶部更正表 #1）
cd voice-clone-kit
powershell -ExecutionPolicy Bypass -File build-vits-dataset.ps1 -SampleRate 16000 -RelPrefix "training_data/cyrene_wavs"
# ② 放入训练仓库约定的相对路径（清单里写的就是这个前缀）
$repo = "build-out/vits/toolchain/sherpa-onnx-vits-fine-tuning-webui"
New-Item -ItemType Directory -Force -Path "$repo/training_data/cyrene_wavs" | Out-Null
Copy-Item build-out/vits/dataset/sampled_audio4ft/*.wav "$repo/training_data/cyrene_wavs/" -Force
# ③ 清单原样作为短音频标注（格式与 LOCAL.md 的 short_character_anno.txt 一致，UTF-8 无 BOM）
Copy-Item build-out/vits/dataset/sampled_audio4ft.txt "$repo/training_data/short_character_anno.txt" -Force
```

> ⚠️ **不要**把语料放进 `training_data/sampled_audio4ft/`：那是仓库自带的**辅助数据集**（1092 条 `N.wav`，
> 供 `--add_auxiliary_data True` 使用），混放会让"辅助数据"与"角色数据"边界不清。

> 若改用 `raw_audio/`（长音频路径）则**必须**走 Whisper 转写，本批不用。

### 2.2 文本/特征预处理

```powershell
conda run -n vits38 python preprocess_v2.py --languages C
# 若要用 Plachta 的辅助数据集（多说话人底料，可提升泛化）：
#   conda run -n vits38 python preprocess_v2.py --add_auxiliary_data True --languages C
#   辅助数据 = https://huggingface.co/datasets/Plachta/sampled_audio4ft/resolve/main/sampled_audio4ft_v2.zip
```

预处理会把我们的说话人追加进 `configs/finetune_speaker.json` 的 `speakers` 列表（`[未实测]`：
若未自动追加，需手工在 `speakers` 里加 `"Cyrene"`，并把 `data.speaker` 设为它）。

### 2.3 训练（**已实测可跑**）

```powershell
# 首轮冒烟（先 2 epochs 确认显存与产物；同时开另一个终端采 nvidia-smi 峰值）
python ..\..\run-vits-finetune.py --max-epochs 2 --batch-size 8
# 正式训练（LOCAL.md 原文："Empirically, 100 or more is recommended"）——实测 ≈35 分钟
python ..\..\run-vits-finetune.py --max-epochs 100 --batch-size 8
# 续训：--continue-training（需 ./models/vits-cyrene/ 下已有 G_latest/D_latest.pth）
python ..\..\run-vits-finetune.py --max-epochs 100 --batch-size 8 --continue-training
```

> ### ⚠️ `--max-epochs` 对训练轮数是 **no-op**（2026-09-23 一手实测，TKN-LVC-016）
>
> `finetune_speaker_v2.py` 的循环上界取自 **config JSON**：
>
> ```python
> for epoch in range(epoch_str, hps.train.epochs + 1):   # ← 上界 = hps.train.epochs
>     ...
>     if epoch > hps.max_epochs:                          # ← --max-epochs 唯一的消费点
>         exit()
> ```
>
> ⇒ `train.epochs = 100` 时，无论 `--max-epochs` 给多少，都会在 epoch 100 **干净退出（exit 0）**，
> 看起来像"参数没生效"。**改轮数要改 `training_data/configs/<cfg>.json` 的 `train.epochs`**，
> 并让 `--max-epochs` 与之保持一致。本仓 epoch 100→500 即如此操作。
>
> 续训启动后**必须实看日志**确认：(a) 加载的是 `G_latest.pth`，(b) 轮次越过了旧停止点。
>
> 另：检查点里 `optimizer` 字段为 `None`（该脚本设计上不落盘 Adam 状态，续训用新鲜优化器）；
> `G_latest.pth` 与同轮 `G_<n>.pth` 的**字节哈希可能不同（torch 容器层差异）**，
> 判断"是不是同一个训练状态"要看**逐张量哈希 + `iteration` 字段**，不要只看文件哈希/体积。

> ❌ **不要**按上方"原文"用 `finetune_speaker_v2.py`（签名不匹配，见顶部更正表 #2）。
> 建议先把 `training_data/configs/modified_finetune_speaker.json` 的 `train.eval_interval`
> 从 100 调到 **25**，否则 12 step/epoch 的语料要 ~9 个 epoch 才落一次检查点。

显存峰值：
```powershell
nvidia-smi --query-gpu=memory.used,memory.total --format=csv -l 5
```

### 2.4 原文（**保留供对照，勿照抄**）

```powershell
# 首轮冒烟（先只跑少量 epoch，确认显存与产物；同时开另一个终端采 nvidia-smi 峰值）
conda run -n vits38 python finetune_speaker_v2.py -m ./OUTPUT_MODEL --max_epochs 2 --drop_speaker_embed True --save_every_epoch 1
# 正式训练（LOCAL.md 原文："Empirically, 100 or more is recommended"）
conda run -n vits38 python finetune_speaker_v2.py -m ./OUTPUT_MODEL --max_epochs 100 --drop_speaker_embed True --save_every_epoch 10
# 续训：--cont True（需 ./OUTPUT_MODEL/ 下已有 G_latest.pth / D_latest.pth）
```

显存峰值：
```powershell
nvidia-smi --query-gpu=memory.used,memory.total --format=csv -l 5
```

## 3. 导出为 sherpa-onnx 可用产物

```powershell
$py = "C:\Users\ljh\.conda\envs\vits38\python.exe"
& $py export_vits_fast_fine_tuning_onnx.py `
  --config ./models/vits-cyrene/config.json `
  --checkpoint ./models/vits-cyrene/G_latest.pth `
  --output_dir ./onnx-output --model_name vits-cyrene --language ZH
```

产出（README 原文）：`model.onnx`（实测 **115.5MB**）/ `model.int8.onnx`（**37.5MB**）/ `lexicon.txt`（2.5MB）/
`tokens.txt` / `date.fst` `number.fst` `phone.fst` `new_heteronym.fst` / `dict/`（13.7MB）。

### 3.1 ⚠️ 必须的收尾步骤：修 `comment` 元数据（**否则端上/PC 加载都会原生崩溃**）

```powershell
& $py ..\..\..\..\voice-clone-kit\tools\fix-vits-onnx-metadata.py     # 或用仓库内任意副本
# 产出 model.fixed.onnx / model.int8.fixed.onnx（不覆盖原文件，保留导出证据）
```

根因与证据见验证报告 §5：`comment` 为空串 ⇒ 上游 `SHERPA_ONNX_READ_META_DATA_STR(comment,"comment")`
（**唯一无 DEFAULT 的宏**，`offline-tts-vits-model.cc:197-198`）判为缺失 ⇒ `SHERPA_ONNX_EXIT(-1)`。
修复后隔离子进程实测 `rc=0 / CONSTRUCTED sample_rate=16000 / SYNTH_OK`。

**端侧字段映射（AAR 字节码实证）**：
- `OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir, dictDir, noiseScale, noiseScaleW, lengthScale)`
  —— **没有 fst 字段**；
- `*.fst` 必须走 **`OfflineTtsConfig.ttsRuleFsts`**（官方 CLI 是 `--tts-rule-fsts=date.fst,number.fst,...`）；
  只放 `VitsModelConfig` 会**静默丢规则**（数字/多音字读法出错）。
- **实测结论（本机 sherpa_onnx 1.13.8）**：带/不带 `dict_dir`、带/不带 `rule_fsts` 都能构造并合成；
  即**这两项都不是加载前提**，但按官方口径仍应接上（决定数字/多音字读法）。
- **端上选 fp32**：实测 fp32 RTF 0.10–0.11，int8 RTF 0.90–1.00（**int8 反而慢 9 倍**）。
- `sample_rate` 元数据 = **16000** ⇒ `LocalCloneSynthesizer.sampleRate()` 应回报 16000。

## 4. PC 侧最小闭环（**已执行并通过**）

```powershell
# 4.1 用本机基础环境（sherpa_onnx 1.13.8）直接加载 fp32 导出物并合成
python voice-clone-kit\tools\verify-vits-export.py      # 产出 vits-export-verify.json（含 RTF）
python voice-clone-kit\tools\make-vits-sample.py        # 产出可试听的 wav（fp32 / int8 各一段）
```

判据（**实测已满足**）：能加载、能出声、无掉字、`sample_rate=16000` 与导出元数据一致。
**未满足**：音色相似度客观打分（本机无 CAM++ 模型）⇒ 只能人工判听。

## 5. 端侧集成（**待做，需新任务令牌**）

1. 音色包布局扩展：与 ZipVoice **并存、按角色/引擎切换**（不删 ZipVoice 路径）；
2. 清单 `voicepack.json` 增加引擎类型字段（`ttsEngine: "vits" | "zipvoice"`）⇒ 连带
   `LocalVoicePackStore` 校验、`LocalCloneCacheKey`、`sanitized()` 与单测；
3. 新建 `SherpaVitsSynthesizer`（`src/sherpa` 条件源集，反射创建，同 ZipVoice 模式），
   实现既有 `LocalCloneSynthesizer`（含 `sampleRate()`）；
4. `ttsRuleFsts` 在 Kotlin 侧的字段名 `[未核实]`——按 AAR bytecode 确认后接入；
5. **流式播放（LVC-2）天然复用**：VITS 的 `OfflineTts` 同样走 `generateWithConfigAndCallback`，
   回调语义需**重新实测**（本批只核实了 ZipVoice 的 `offline-tts-zipvoice-impl.h`）。

## 6. 判定与验收

| 项 | 目标 | 手段 |
| --- | --- | --- |
| 音色相似度 | ≥0.88（当前 0.8079 / 天花板 0.9104） | CAM++ 说话人嵌入余弦，同一裁判只换引擎 |
| 自然度 | 主观：机器感消失 | 真机 A/B（与 ZipVoice、云端 MiniMax 三方对照） |
| RTF | 尽可能 <1 | 真机日志（`合成完成：elapsed=… rtf=…`）；官方一手参考：fanchen-C 在 RPi4B 4 线程 = 1.600 |
| 峰值内存 | 不高于 ZipVoice | `adb shell dumpsys meminfo io.prism` |
| 长句泛化 | 无"念到一半崩/变调" | 真机长文本播报 |

## 7. 风险与未核实项（**不得默认为已解决**）

1. **依赖安装可复现性**：`demucs` / `openai-whisper` / `gradio` / `pyopenjtalk-prebuilt` /
   `Cython==0.29.21` 在 Windows + py3.8 上能否装齐 `[未实测]`；`monotonic_align` 能否编译 `[未实测]`。
2. **8GB 显存是否够**：LOCAL.md 的 12GB 只说 Whisper 转写；训练本身无一手数值 `[未核实]`。
3. **训练时长**：Plachtaa README 称 20 分钟~2 小时（二手），本机实际 `[未核实]`。
4. **78 条短句是否过拟合**：无一手数据，须真机长句验证。
5. **Android 端 VITS RTF**：无一手数据（仅有 RPi4B 值）——**若落在 1.0~1.6，则达不到"实时"**，
   本路线的收益将低于预期；届时应考虑 `numSteps`/语速等其它手段，或接受"更自然但仍有停顿"。
6. **权重许可**：Plachtaa 仓库 Apache-2.0、ANONEKO MIT；**底模权重自身的许可未核实**，
   按"个人自用、不外发"使用。
7. **`tokens.txt` / `dataDir` / `ttsRuleFsts` 的用法**在非 fanchen-C 模型上未核实。
