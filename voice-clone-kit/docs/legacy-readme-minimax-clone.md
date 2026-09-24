# 昔涟音色克隆就绪音频包

供 MiniMax 平台音色克隆使用。角色：昔涟（《崩坏：星穹铁道》，中文 CV：宴宁，少女音明亮温柔）。

## 包内文件

| 文件 | 用途 | 规格 |
|---|---|---|
| `cyrene-clone-main.mp3` | **主音频**（克隆必传） | 29.89s，44.1kHz 单声道，128kbps，约 468KB |
| `cyrene-clone-prompt.wav` | 可选 prompt 切片（提升相似度） | 5.90s，44.1kHz 单声道 16bit PCM，约 508KB |
| `cyrene-clone-prompt-transcript.txt` | prompt 切片的逐字转写（与切片精确对应） | 纯文本一行 |
| `raw\` | 原始素材 ogg（备份，勿上传平台） | 4 条游戏内语音原文件 |

- 主音频内容：「初次见面」（完整句）+「道别」（截至「约好了，」处静音边界），总长 29.89s，满足 MiniMax 10s–5min、≤20MB 的要求。
- prompt 切片内容：「初次见面」首句「这是命运的邂逅吗，还是…久别重逢呢？」，<8s，配转写文本可获得更好克隆效果。

## 一、MiniMax 平台克隆步骤

1. 打开 [platform.minimaxi.com](https://platform.minimaxi.com) 并登录（首次使用需实名认证并开通语音服务）。
2. 进入「语音大模型」→「声音复刻 / 音色克隆」（不同版本入口名称略有差异）。
3. 上传主音频：选择本目录的 `cyrene-clone-main.mp3`。
4. （推荐，可选）填入 prompt 切片：上传 `cyrene-clone-prompt.wav`，并在转写文本框粘贴 `cyrene-clone-prompt-transcript.txt` 的内容（打开文件全选复制即可）。转写与音频逐字对应，可显著提升克隆相似度与韵律自然度。
5. 提交克隆任务，等待训练完成；若平台支持「抽卡」（多次生成候选音色试听对比），可多试听几版挑最像的。
6. 按平台要求**在 7 天内用克隆音色完成一次语音合成（试听/生成均可）以激活音色**，超期未激活会被回收。
7. 激活后在音色列表复制该克隆音色的 `voice_id`（形如 `clone_xxxxxxxx`）。

> 提示：克隆时留意平台的降噪/首尾静音检测提示；本包音频已做过转码（44.1kHz 单声道 mp3）与静音边界截断，一般可直接通过校验。

## 二、拿到 voice_id 后的接入方法

1. 用文本编辑器打开 `D:\s0611\code\Prism\cyrene.rolepack`（JSON 格式）。
2. 找到 `voice.minimax.cloneVoiceId` 字段，当前值为 `null`，将其替换为你在平台上复制的 voice_id：

```json
"voice": {
  "minimax": {
    "voiceId": "female-shaonv",
    "speed": 1.0,
    "emotion": "happy",
    "cloneVoiceId": "这里填你的 voice_id"
  }
}
```

3. 保存文件后交回开发，重新导入设备即可生效。导入后设备语音将使用克隆音色而非默认的 `female-shaonv`。

## 二·B、素材扩充与重新克隆（提升相似度，2026-09-10 增补）

> 背景：当前 29.89s 素材克隆出的音色「有些许不像」。经调研 MiniMax 官方文档与抽卡工具说明，
> 结论：**有必要扩充素材，但建议目标 60–120s（而非拉满 300s）**；且比时长更大的杠杆是
> **官方「抽卡工具」批量出 6 个候选试听筛选**（单次克隆质量波动是官方已知现象）。

### 为什么 60–120s 是甜点区

- 官方仅约束 10s–5min（下限是能不能用）；**相似度取决于素材的音素覆盖、情绪多样性与干净度**，
  30s 往往只覆盖单一情绪与少数音素；60–120s 通常显著改善，300s 拉满收益递减且更容易
  混入带 BGM/音效/嘈杂的片段（游戏内语音常带环境音，脏片段会直接拉低克隆质量）。
- **质量优先于时长**：宁要 60s 全干净的纯人声，不要 300s 混着 BGM 与音效的素材。

### 选材建议（星铁昔涟素材来源）

- 优先收集**无 BGM、无音效、单人清唱说话**的片段：角色互动语音（委托/闲谈）、剧情纯对话；
  避免战斗喊招、带背景音乐的 PV 段。
- 情绪覆盖面拉宽：平静陈述、开心、俏皮、轻柔各占一些——克隆模型学的是「这个人的声学全域」。
- 每条素材首尾静音干净（本包脚本会自动裁剪）。

### 素材重建工作流

1. 把新收集的语音文件（ogg/wav/mp3/m4a 均可）放进 `raw\` 目录（与现有语音并列）。
2. 在本目录执行 `powershell -ExecutionPolicy Bypass -File rebuild-clone-audio.ps1`：
   自动剔除战斗喊招/重复变体 → 首尾静音裁剪 → 响度归一（EBU R128，防各条音量不一）→
   统一 44.1kHz 单声道 → **按清单顺序贪心拼接至 295s 上限**（优先互动/关于类日常语气，
   战斗短句靠后自然被截断）→ 产出新的 `cyrene-clone-main.mp3` 并报告总时长。
3. 用新主音频重新克隆；prompt 切片（<8s + 逐字转写）保持既有 `cyrene-clone-prompt.wav` 可复用，
   也可换一条更「日常语气」的切片再对比。

### 抽卡工具工作流（官方，强烈推荐）

平台「一次克隆一个结果」的相似度波动是官方已知现象——官方提供
[Voice ID Lucky Draw 抽卡工具](https://solution.minimaxi.com/voice-id-lucky-draw/)：
上传一段长音频，自动切分并发起 **6 次复刻（3 次 Zero-Shot + 3 次 One-Shot）**，用统一文案
生成试听音频横向对比，挑最像的再激活。**未激活的候选不产生 9.9 元复刻费**（仅试听的
T2A 字符计费）。流程：填 API Key + Group ID → 选/编试听文案 → 上传长音频自动切分
（clone_audio >10s、prompt_audio <8s）→ 校对 prompt 转写 → 批量克隆 → 试听筛选 → 激活。
选出的 `voice_id` 同样走「二、拿到 voice_id 后的接入方法」。

### 换新 voice_id 的生效方式

更新 `cyrene.rolepack` 的 `voice.minimax.cloneVoiceId` 后，到设置页「角色包」**重新导入**
该文件（导入即自动选中生效）。

## 三、版权提示

- 本包音频素材取自游戏《崩坏：星穹铁道》公开资料库语音（米哈游 miHoYo / HoYoverse 版权所有，中文配音：宴宁）。
- **仅限个人自用**（自用设备的语音体验），**禁止分发、二次上传、商用**，禁止用于冒充真人或违反法律法规的用途。
- 由此素材克隆产生的音色，请勿对外声称与官方或配音演员有关联。使用产生的合规风险由使用者自行承担。

## 四、VITS 微调数据与工具链（TKN-LVC-014，2026-09-20 新增）

> 目的：把「端侧 ZipVoice 零样本克隆」这条路线的**音色相似度与自然度**再提一档。
> 路线依据与一手核查结论见 `docs/reports/2026-09-20-voice-clone-vits-route-research.md`；
> 执行方案见 `docs/reports/2026-09-20-voice-clone-streaming-and-vits-plan.md` §B。
> **本节的产物全部落在 `voice-clone-kit\` 内（整目录被 `.gitignore` 排除），不入库、不入 APK。**

### 4.1 两个脚本

| 脚本 | 作用 | 产物 |
| --- | --- | --- |
| `build-vits-dataset.ps1` | 把 `raw\` 全量素材转成 VITS 训练语料（44.1kHz 单声道 16bit + 逐字官方台词） | `vits-dataset/sampled_audio4ft/cNNN.wav`、`sampled_audio4ft.txt`、`manifest.json`、`build-report.txt` |
| `prepare-vits-toolchain.ps1` | 取训练/导出工具链（ANONEKO MIT 仓库）与 Plachta uma_trilingual 底模 | `vits-toolchain/sherpa-onnx-vits-fine-tuning-webui`、`pretrained_models/{G_0,D_0}.pth`、`configs/finetune_speaker.json` |

```powershell
cd voice-clone-kit
powershell -ExecutionPolicy Bypass -File build-vits-dataset.ps1
powershell -ExecutionPolicy Bypass -File prepare-vits-toolchain.ps1
```

**实测（2026-09-20）**：数据集 **78 条 / 总时长 513.67s（8.56 分钟）/ 平均 6.59s**，台词覆盖
78/78，退出码 0（门禁 ≥3 分钟 —— Plachtaa 仓库 README 原文要求，已满足）。

### 4.2 环境（已在本机验证可复现）

```powershell
conda create -n vits38 -y python=3.8
conda run -n vits38 python -m pip install torch==2.4.1 torchaudio==2.4.1 --index-url https://download.pytorch.org/whl/cu124
```

**实测**：`PYVER=3.8.20 / TORCHVER=2.4.1+cu124 / CUDA=True`（RTX 4060 Laptop 8GB）。
与工具链 `requirements.txt` 钉的 `torch==2.4.1+cu124` 一致。

### 4.3 两个易踩的坑（本批实测）

1. **PowerShell 脚本必须写 UTF-8 BOM**。本目录的 `.ps1` 一律以 `powershell -File` 执行
   （Windows PowerShell 5.1），**无 BOM 时会按 ANSI/GBK 解析**，中文注释里的字节会被误认成引号或花括号
   ⇒ `Missing closing '}'` 之类**与真正语法无关**的解析错误（本批实际踩到）。
2. **训练清单与文本文件必须 UTF-8 无 BOM**。训练侧按 `utf-8` 逐行读，BOM 会让首行语言标记
   `[ZH]` 匹配失败（表现为"只丢第一条"这类难查现象）。两者要求**相反**，不要互相套用。

### 4.4 全链路已跑通（2026-09-20，TKN-LVC-014）

证据：`docs/reports/2026-09-20-vits-finetune-verification.md`；Runbook：`docs/runbooks/finetune-vits-zh-cyrene.md`。

| 阶段 | 结果 |
| --- | --- |
| 语料 | 78 条 / 513.67s / **16 kHz**（清单 + wav 在 `vits-dataset/`） |
| 训练 | **100 epochs ≈ 35 分钟**，显存峰值 7570MiB@batch16 · 6552MiB@batch8 |
| 导出 | `onnx-output/vits-cyrene/`：fp32 115.5MB / int8 37.5MB / lexicon / tokens / 4×fst / `dict`（端侧 ≈132MB） |
| PC 侧合成 | **fp32 RTF 0.10–0.11**、int8 0.90–1.00 ⇒ **端上选 fp32** |
| 试听 | `vits-listening-samples/vits-cyrene-sample-{fp32,int8}.wav`（16 kHz，5 句） |

**三个必须知道的坑**：① 导出的 onnx **不能直接加载**——`comment` 元数据为空串会让 sherpa-onnx
`SHERPA_ONNX_EXIT(-1)`（唯一无 DEFAULT 的元数据宏）；用 `.tmp-cyrene/fix-vits-onnx-metadata.py` 补非空值；
② 工具链三个底模配置的 `sampling_rate` **都是 16000** 且 `data_utils` 不重采样 ⇒ 语料必须 16 kHz，
**产物也就是 16 kHz**（低于 ZipVoice 的 24 kHz，取舍须人工判听）；
③ `LOCAL.md` 的 `finetune_speaker_v2.py` CLI 在本 fork **不可用**（签名不匹配）⇒ 用 `run-vits-finetune.py`。


