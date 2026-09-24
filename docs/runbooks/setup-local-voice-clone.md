# Runbook：端侧本地音色克隆（本地昔涟音色）侧载与启用

> 关联：ADR-053（本地音色克隆决策）、PRD `docs/prd-local-voice-clone.md`、调研 `docs/reports/2026-09-19-local-voice-clone-feasibility.md`。
> 目标：**完全离线、零 API 费用**地用昔涟音色播报（文本播放按钮 + 语音通话）。
> 端侧运行时复用项目已集成的 sherpa-onnx AAR（v1.13.7，含 `OfflineTts` + ZipVoice 支持，**无新增依赖**）。

## 0. 为什么模型要"单独侧载"

单角色音色包约 **197MB**（ZipVoice int8 distill + vocos 声码器 + 音素数据 + 参考音频）。
进 APK 会让安装包不可接受，且违反 ADR-048 已确立的"角色资产不入 APK/assets/公开仓库"边界。
因此：**模型与参考音频由桌面脚本侧载到设备**，App 只在运行期读取并校验。

## 1. 目录布局（设备侧）

```
/sdcard/Android/data/io.prism/files/voicepack/cyrene/
├── voicepack.json          清单（模型相对路径 + 生成参数 + 参考集官方台词）
├── model/
│   ├── tokens.txt          文本词表
│   ├── encoder.int8.onnx   ZipVoice 编码器（5.3MB）
│   ├── decoder.int8.onnx   ZipVoice 解码器（118.9MB）
│   ├── vocos_24khz.onnx    声码器（51.7MB，**不在模型包内**，官方单独发布）
│   ├── lexicon.txt         汉字→拼音词典
│   └── espeak-ng-data/     音素数据（17.2MB）
└── refs/
    └── ref_XX.wav          参考音频（24kHz 单声道 16bit PCM，共 8 条）
```

> 该目录为**应用专属外部目录**：无需运行时权限、`adb` 可直接写入、卸载应用即清除。

> ⚠️ **2026-09-20 真机实测修正（重要）**：首次真机部署时发现——`adb push` 到外部目录后，
> **从应用 uid 视角（`adb shell run-as io.prism`）读取该目录会 `Permission denied`**（上传目录
> 属主为 `shell:ext_data_rw`、模式 `770`）。外部目录能否被 App 自身读取**未经证实**，而音色包
> 校验是"取首个含清单的根"，**部分可读会让它停在不可用的外部根、不再回退内部根**。
>
> 因此真机采用**已验证可读的内部目录**作为生效根（`defaultRoots` 的第二个根，无需改代码）：
>
> ```powershell
> # ① 先推到 /data/local/tmp（世界可读），再以应用 uid 复制进内部目录
> adb push <清单/模型/参考> /data/local/tmp/lvc/cyrene/...
> adb shell chmod -R a+rX /data/local/tmp/lvc
> adb shell run-as io.prism mkdir -p files/voicepack
> adb shell run-as io.prism cp -r /data/local/tmp/lvc/cyrene files/voicepack/cyrene
> adb shell run-as io.prism sha256sum files/voicepack/cyrene/voicepack.json   # 与主机核对
> ```
>
> 判据：`adb shell run-as io.prism ls files/voicepack/cyrene` 能列出 `model/ refs/ voicepack.json`。
> 外部目录那条路**待单独验证**（若验证可读，`-Push` 脚本与本节可恢复原方案）。

## 2. 一次性准备（在 `voice-clone-kit\` 目录）

依赖：`ffmpeg`/`ffprobe`、`curl`、`tar`、`adb`（Android SDK platform-tools）。

```powershell
# ① 抽官方台词（biligame WIKI「昔涟/语音」→ cyrene-voice-lines.json）
powershell -ExecutionPolicy Bypass -File parse-wiki-vo-lines.ps1

# ② 体检素材 + 构建参考集与清单（产出 voicepack\cyrene\{voicepack.json, refs\}）
powershell -ExecutionPolicy Bypass -File build-local-voicepack.ps1
```

①+② 是**可复算**的：脚本会逐条探测 `raw\` 全部素材并比对官方台词，**任一素材缺台词即非零退出**
（ZipVoice 要求参考文本与参考音频逐字一致，残包会直接拉低相似度）。

## 3. 下载模型 + 推送到设备

```powershell
# 校验构成（缺模型则自动下载；国内直连 GitHub release 常被重置，脚本默认走镜像前缀）
powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1

# 实际推送（可选 -Serial <设备序列号> 指定设备）
powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -Push
```

模型来源（脚本内置，含镜像兜底）：

| 文件 | 官方地址 |
|---|---|
| ZipVoice int8 distill zh-en | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2` |
| vocos_24khz 声码器 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx` |

## 3.5 应用内导入音色包（TKN-LVC-016 / ADR-057）—— **不需要 USB**

> 适用：任何已组装好的 `voicepack\<VoiceId>\`。**这是普通用户路径**；第 3 节的 `-Push` 是
> 开发/调试路径（可一次推多个包、可直接覆盖，不经过用户确认）。

```powershell
# 组装（VITS 包为例；ZipVoice 包用 build-local-voicepack.ps1）
powershell -ExecutionPolicy Bypass -File build-vits-voicepack.ps1
# 打成手机可导入的单文件归档 → dist\<VoiceId>.voicepack
powershell -ExecutionPolicy Bypass -File pack-voicepack.ps1 -VoiceId cyrene-vits
```

把 `dist\cyrene-vits.voicepack` 传到手机（微信/网盘/`adb push /sdcard/Download/` 均可），然后：

**设置 → 语音引擎 → 展开 → 播报引擎（TTS） → 「导入音色包」→ 文件选择器里选中该 `.voicepack`。**

导入器行为（与 ADR-057 逐条对应）：

| 情形 | 行为 |
|---|---|
| 首次导入 | 解压到 `<filesDir>/voicepack/.import-<uuid>/<voiceId>/` 校验通过后整体改名落位；**不留临时目录** |
| 同名已存在 | **不静默覆盖**：弹确认框；确认后才替换（旧包先改名为 `.bak-<uuid>`，替换成功才删除） |
| 覆盖过程中失败 | 旧包**原样回滚**（不会出现"新旧同时丢失"） |
| 不是音色包 / 缺清单 / 清单非法 | 拒绝并给出中文原因；**不落盘任何内容** |
| 包内有 `..`、绝对路径、反斜杠、盘符 | 拒绝（zip-slip 两道闸：条目名判定 + canonical 路径比对） |
| 超过上限（条目 2000 / 单条目 600MB / 总量 1.5GB / 压缩比 200） | 拒绝 |
| 可用空间 < 解压后总量 + 200MB 预留 | 拒绝 |
| 导入成功后列表 | 立即出现该音色包；可在设置页逐条删除（删除不影响角色包） |

打包端注意：`pack-voicepack.ps1` **强制以 `/` 作为 zip 内路径分隔符**——App 侧会直接拒绝含
反斜杠的条目名，若用 `Compress-Archive` 等工具产包，请先确认条目名用的是 `/`。

## 4. 在 App 中启用

1. 确认已导入并选中昔涟角色包（`cyrene.rolepack` 的 `voice.local.voiceId = "cyrene"`）；
2. 设置 → 「语音引擎」→ 展开 → 播报引擎（TTS）选 **「本地昔涟（离线）」**
   （未就绪时该项置灰，描述行会写明原因：未安装 / 不完整（列出缺项）/ 检测中）；
3. 回聊天：点 AI 回复的 ▶ 播放按钮 → 本地合成出声；
4. 语音通话同样使用该引擎（通话装配读同一份引擎选择）。
5. **播报方式**（同页，仅本地引擎可见）：
   - `连续播报`（默认）：上一段还在播时，下一段已在合成 —— 长回复不再"一句一句等"；
   - `整段播报`：全部合成完再一次性播出（段间**绝对**无缝），代价是首声更晚；超过 200 字自动回落连续；
   - `合成档位`：`质量优先`（默认，= 改造前基线）/ `均衡`（6 步 3 线程）/ `速度优先`（4 步 4 线程）。
     档位**只改步数与线程数**，不改参考音频（短参考会让音高漂移，见 8.3）。

## 5. 排障

| 现象 | 排查 |
|---|---|
| 设置页该项置灰，描述"未安装" | 目录或清单不存在：确认 `voicepack/cyrene/voicepack.json` 已在设备上；换角色包后需重进设置页刷新 |
| 描述"音色包不完整：X 缺失" | 按缺项重跑 `install-local-voicepack.ps1 -Push`；`voicepack.json` 的 `voiceId` 必须与目录名一致 |
| 播放无声音且日志有"文本播报失败…已回退"（降级链触发标记） | 已自动降级系统播报；查 `LocalClone` tag 日志（形如"本地音色合成失败：<异常名>"，不含"已回退"字样，属内部诊断行）；必要时调 `voicepack.json` 的 `generation.timeoutMs`（合法域 1s–120s，越界会被主动钳制为默认 60s（清单上限 120000ms）） |
| 通话里没声音 | 本地引擎失败会经既有降级链切系统播报（`tryFallbackTts`）；确认设备有系统 TTS 引擎 |
| 想调 RTF↔质量 | 改角色包 `voice.local.numSteps`（默认 4；越小越快、质量略降）后重新导入角色包 || 播放无声音 + **进程崩溃**（`SIGABRT`） | 见下方 §5.1 崩溃指纹；根因与处置见 `docs/reports/2026-09-20-local-voice-clone-crash-rca.md`（生成回调必须是具名类 `LocalCloneSampleCallback`，写成 lambda 必崩） |
| 正式包（release）里本地音色"总是系统音" | 检查 `app/proguard-rules.pro` 是否仍保有 `io.prism.voice.local.SherpaZipVoiceSynthesizer` 与 `LocalCloneSampleCallback` 的 keep（缺 keep 会被 R8 改名/删除 → 静默回退，不崩） |

### 5.1 崩溃指纹（2026-09-20 真机实证，保留原样便于检索）
```
F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 6623 (DefaultDispatch), pid 32269 (io.prism)
Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception
  java.lang.NoSuchMethodError: no non-static method
  "Lio/prism/voice/local/SherpaZipVoiceSynthesizer$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"'
```

> 判据：出现 `$$ExternalSyntheticLambda0` ⇒ 生成回调被写成了 lambda。修法见 RCA 报告（改用
> `LocalCloneSampleCallback` 具名类），并**不要**删掉调用前那道 `hasJniCompatibleInvoke()` 预检——
> 它把"签名不符"从不可捕获的 native abort 降级为可回退的失败。

## 6. 真机手动验收清单（PRD AC6）

- [ ] 设置页能正确显示"已安装（8 条参考 / N MB）"；
- [ ] 文本播放按钮出昔涟音色；记录 **首字延迟** 与听感（与云端 MiniMax 做 A/B）；
- [ ] 语音通话全离线出昔涟音色；说话打断、挂断均正常；
- [ ] 记录 **RTF**（日志 `合成完成：elapsed=… audio=… rtf=…`）与内存峰值；
- [ ] 拔网/开飞行模式后仍能出声（证明完全离线）。

> RTF 判定口径：RTF < 0.8 适合通话；≥ 0.8 建议通话保留云端/系统，本地仅用于文本播放
> （调优手段见 PRD §8 待确认事项）。

### 6.1 流式播放（LVC-2 / ADR-054）专项验收 —— **待用户真机执行**

> 改动内容：合成回调的每个内部句子分块**立即写入一条常开音轨**（不再"整段算完才播"，
> 合成与播放重叠）。**自动化已验证的部分**：局部 **104** 用例全绿（10 类；含门控/空块/取消/收敛语义），
> 全量 213 类 / 3074 用例 / 0 失败（guardrail 四轮 + ac-verifier 独立复跑均吻合），
> PC 实测静音 33.07s → 0、端到端 82.9s → 36.9s（见测量报告）。
> **未验证的部分**：真机首声、真机静音结构、`AudioTrack` 常开写入的行为、打断/通话链路。

| # | 待测项 | 怎么测 | 判据 |
| --- | --- | --- | --- |
| S1 | **首声延迟** | 点一条 ≥60 字 AI 回复的 ▶，记"点击 → 第一声" | 应 ≈ 首句合成耗时，**不再等整段**；与改造前对照 |
| S2 | **静音结构** | 听同一段并数"每句之间的静音" | 应比改造前**明显缩短**；**不预期为 0**（真机 RTF 3.67 > 1，供数慢于实时） |
| S3 | **是否出现"念到一半就没了"** | 播一段长文本到底 | 全文须播完；若中途停止，抓 `LocalClone` tag 日志（`stream-write-failed` / `输出栈在流式播放中失效` / `该段合成被中止`） |
| S4 | **打断/停止** | 播报中点停止 / 发新消息 / 挂断 | 立即静音，**不得**出现"停止后又冒出后半段" |
| S5 | **通话链路** | 语音通话里让角色说长句；说话打断 | 与改造前一致，无重复播报、无卡死 |
| S6 | **underrun 观测** | 播报后看日志 | 允许出现 `本次播放发生 N 次 underrun`（供数不足的客观指标）；若 N 很大且伴可闻断音，属**已知**（RTF>1），不是本次回归 |
| S7 | **不回归** | 短句（<16 字）播报、设置页切换引擎、降级到系统播报 | 全部与改造前一致 |

抓日志：
```powershell
adb logcat -c ; adb logcat -s "LocalClone" "LocalClone/ZipVoice"
```

> ⚠️ 若 S3 失败（长文本被静默截断），这属于**必须回报的缺陷**——请把 `本地音色` 相关日志整段
> 贴回，重点看是否出现 `输出栈在流式播放中失效`（该分支按"已出声 ⇒ 收敛为 onDone"处理，
> 不会触发上层重播；若日志里同时出现 `已回退`，说明走的是另一条分支）。

### 6.3 VITS 音色包（ADR-055 / TKN-LVC-015）专项验收 —— **待用户真机执行**

> 引擎已实现并经 4 轮 guardrail + 1 轮 ac-verifier 验证**自动化可证明面**（214 类 / 3093 用例 / 0 失败）。
> **未验证**：正式包 minify、真机侧载与播放、`factory → Class.forName → speak` 这一段、
> 以及下面 V1–V5 的五项运行时未知。

**音色包最小布局**（侧载到 `<音色包根>/<voiceId>/`，与 ZipVoice 包并存）：

```json
// voicepack.json（UTF-8 无 BOM；schema 仍是 prism-localvoice/1）
{
  "schema": "prism-localvoice/1",
  "voiceId": "cyrene-vits",
  "name": "昔涟（VITS 微调）",
  "tts": "vits",
  "model": {
    "model": "model/model.fixed.onnx",     // ⚠️ 必须是修过 comment 元数据的那份
    "lexicon": "model/lexicon.txt",
    "tokens": "model/tokens.txt",
    "dictDir": "model/dict",                // 可选
    "ruleFsts": ["model/date.fst","model/number.fst","model/phone.fst","model/new_heteronym.fst"]  // 可选，≤8 条
  },
  "references": []                          // VITS 允许为空
}
```

资产来源：`voice-clone-kit/vits-toolchain/sherpa-onnx-vits-fine-tuning-webui/onnx-output/vits-cyrene/`
（`model.fixed.onnx` = 用 `fix-vits-onnx-metadata.py` 修过 `comment` 的那份）。

| # | 待测项 | 怎么测 | 判据 / 说明 |
| --- | --- | --- | --- |
| V1 | **能否出声**（最基本） | 侧载后播一句 | 若失败，抓日志：出现 `LocalClone` 的 `本地合成不可用（…tts=vits）` 或 `engine-mismatch`/`prompt-missing` ⇒ 说明走的是"可回退失败"而非崩溃，把日志贴回 |
| V2 | **`ruleFsts` 是否真生效** | 播含数字/多音字的句子（如「今天是 2026 年 9 月 20 日」） | 数字读对 ⇒ 生傚；读成逐字 ⇒ 该字段对 VITS 未生效（**ADR-055 风险 3 的运行时未知项**） |
| V3 | **`GenerationConfig` 是否够用** | 上面任一句正常出声 | 若出现变调/极速/极慢 ⇒ 需补字段（ADR-055 自问第 1 条） |
| V4 | **RTF 与内存** | 播 ≥60 字；看日志 `合成完成：elapsed=… rtf=… sr=16000` | PC fp32 实测 RTF **0.10–0.11**；真机若 <1，则"段间静音"问题**有望真正消除**（这是换 VITS 的最大收益） |
| V5 | **16 kHz 音质是否可接受** | 与 ZipVoice（24 kHz）A/B 听同一段文本 | 若明显发闷 ⇒ 需换 22.05/24 kHz 底模重训（**新决策**，非本批范围） |
| V6 | **不回归** | 切回 ZipVoice 包播报、短句、降级系统播报 | 与改造前一致（**`tts` 缺省即 ZipVoice**，旧包无需改动） |

抓日志：
```powershell
adb logcat -c ; adb logcat -s "LocalClone" "LocalClone/ZipVoice" "LocalClone/Vits"
```

> 启动时 VITS 包若不可用，设置页会显示具体 problems（路径缺失/非法/条目过多等）；**先把设置页文案贴回**
> 比直接看日志更快定位。

### 6.4 已登记的已知覆盖边界（ac-verifier 放行条件 C1/C2，**显式接受、不修**）

| 编号 | 内容 | 为何接受 | 真机是否可替代观察 |
| --- | --- | --- | --- |
| C1 | `release()` **在途**时"不再出声"无用例（现有用例只覆盖"release 后新发起 speak"） | 该行为由 `current?.cancelBy(RELEASED)` → 取消探针 → 回调停止 → `close()` 释放音轨这条链保证；补测需在途并发，收益低（属覆盖缺口，非缺陷） | 是——S4（打断/停止）与 S7（切引擎/退出通话）会经过同一段释放链 |
| C2 | AC-A7 的 9 个场景中 8 个有用例，**仅 release 在途**缺 | 同上（同一根因） | 同上 |
| C4 | **无性能基线**，未做性能回退对比（`perf/baselines/` 不存在，`docs/reports/perf/` 无 LVC 条目） | 本项目既有约定；本改动不引入新的热路径调用（仅改变写入时机） | RTF 由 S1/S6 的日志给出 |
| C5 | 分块边界"无爆音"的依据是 **PC 波形分析**（最大跳变 0.0186 ≈ −34.6dB），**非真机可闻性** | 证据分级如实标注；不为此背书 | 是——S2/S3 的听测即为该证据的补强 |



### 6.5 TKN-LVC-016（播报管线双模 / 合成档位 / 应用内导入）专项验收 —— **待用户真机执行**

> 前置：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（本批产物 2026-09-23 20:15 构建）；
> `adb logcat -c` 后只保留
> `adb logcat -s "LocalClone" "LocalClone/Vits" "LocalVoicePack"`
> 每轮播报会打一行 `播报汇总：mode=… segments=… played=… samples=… rate=… synthMs=… firstMs=… totalMs=… rtf=…`，
> T1/T2/T4 的对比直接看这行即可（`firstMs` = 首声延迟，`synthMs` = 合成总耗时，`rtf` = total/synth）。

| 编号 | 操作 | 期望 | 关键证据 |
| --- | --- | --- | --- |
| T1 | 本地引擎 + `连续播报`（默认），播一条**长回复（≥3 段）** | 首声后**连续出声**；段间听不到"一次合成"的静音 | 日志出现 `连续播报：预取流水线`；`RTF`/分段日志中第 2 段的合成起始早于第 1 段结束 |
| T2 | 同一条回复切到 `整段播报` | 首声**明显更晚**（等全部合成），但**完全无缝** | 日志出现 `整段播报：收齐 N 段后一次播出`；只有一次播放 |
| T3 | `整段播报` 下播一条 **>200 字**回复 | 自动回落连续（首声变早） | 日志出现 `文本超过 200 字，整段播报回落连续` |
| T4 | 切 `速度优先` 档再播同一条 | 首声更快；**音色不应明显变差**（档位只改步数/线程） | 日志 `steps=4 threads=4 tier=SPEED`；缓存键变化 → 引擎重建 |
| T5 | 用 `pack-voicepack.ps1` 产出 `cyrene-vits.voicepack`（112MB）导入 | 列表出现该包；**无"半包"残留** | `adb shell run-as io.prism ls files/voicepack` 无 `.import-*` |
| T6 | **再次导入同一个包** | 弹确认框；选"取消"后旧包**逐字节不变** | 导入日志 `已存在同名音色包` |
| T7 | 再次导入并**确认覆盖** | 替换成功，列表仍是一个；`.bak-*` 被清理 | 无 `.bak-*` 残留 |
| T8 | 导入一个**改坏的包**（把 `voicepack.json` 删掉重打包） | 中文错误提示；`files/voicepack` 下无新增目录 | 提示"清单缺少合法的 voiceId" |
| T9 | 导入后选中 VITS 播报 | 语速比改造前**明显更慢**（speed 1.0→0.8，约 5.9 字/秒） | 清单 `generation.speed = 0.8` 被读入（可对比同一句话时长） |
| T11 | 听 `vits-listening-samples/vits-cyrene-sample-{fp32,e500-fp32}.wav` | 判断续训（epoch 500）是否真的缓解了"有气无力" | **主观判听**；客观对拍只显示小幅变化（动态范围 +0.5dB、频谱重心 +215Hz、F0 摆动收窄），**不足以代替听感** |

> **T11 是 Q4（续训 400–600 epoch）的验收项**：epoch-500 权重已打进音色包，
> 但"是否值得替换"必须由你听完两组样本定论。若判定 500 反而不如 100，
> 用 `build-vits-voicepack.ps1`（缺省 `-OnnxSubDir` 即 epoch-100 目录）重建即可回退。
| T10 | 删除刚导入的音色包 | 列表消失；`files/voicepack/<id>` 被删除 | — |


## 7. 版权与合规

素材取自游戏《崩坏：星穹铁道》公开语音（miHoYo / HoYoverse 版权所有）。**仅限个人自用**：禁止分发、二次上传、商用。音色包与 `voice-clone-kit/` 均被 `.gitignore` 排除，
不入公开仓库、不入 APK。沿用 `voice-clone-kit/README.md` 既有口径。

## 8. 音质 / 韵律调优（A/B 指南，TKN-LVC-003）

> 依据：`docs/reports/2026-09-20-local-voice-clone-quality-research.md`（一手来源调研）。
> **重要**：调参后**无需重启 App**（引擎缓存键已覆盖全部生成参数 + 模型资产身份，改任一项自动重建）。

### 8.1 三个最容易踩的默认值

| 参数 | sherpa 默认 | 我们必须显式给的值 | 为什么 |
| --- | --- | --- | --- |
| `guidanceScale` | 1.0 | **3.0** | ZipVoice 官方对 **distill** 模型默认 3.0；它不是 CFG，而是喂 decoder 的条件 embedding（训练采样 [1,3]）→ 1.0 会"扁平/机械" |
| `silenceScale` | 0.2 | **1.0** | 上游 `if (silence_scale != 1) ScaleSilence(...)` 会把 **>0.2s 停顿压到 20%** → 听感"语速过快" |
| `numSteps` | 5（示例 4） | **8** | distill 官方默认 8（下限 4） |

`featScale` 必须**贴官方 0.1**（作者明确警告改 0.5 不可用、社区实测 0.2 即"变机械音"）→
代码里只允许 0.05–0.15 的窄域，越界自动回落。

> ⚠️ **流式播放的开关是 `silenceScale`（ADR-054 决策 3）**：`silenceScale != 1.0` 时引擎**自动退回**
> 「整段合成 → 整段播放」（因为上游只在 `silence_scale != 1` 时对**整段**做 `ScaleSilence`，
> 流式块绕过它 ⇒ 不退回就会静默换掉音频）。
> ⇒ **A/B `silenceScale` 时，"播放变慢/出现段间静音"是预期副作用，不是回归**。若只想调停顿又想保住流式，
> 目前只能二选一（后续可在引擎内自行实现等效的逐块静音缩放，属未实现项）。

### 8.2 逐项 A/B 的推荐顺序（一次只动一个变量）

```powershell
cd D:\s0611\code\Prism\voice-clone-kit

# ① 换 fp32 模型（去 int8 量化；官方称量化"会造成一定质量退化"）——最可能改善"电音"
powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -ModelVariant fp32 -Push -Serial <sn>

# ② 更多步数（质量上限更高，但更慢）——先看 RTF 再决定
powershell -ExecutionPolicy Bypass -File build-local-voicepack.ps1 -NumSteps 16
powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -ModelVariant fp32 -Push -Serial <sn>

# ③ 语速/韵律：官方公式是 输出时长 ∝ 参考音频"帧/字"比 ÷ speed
#    ⇒ 参考音频的语速才是主控；speed 只作微调（<1 变慢）
powershell -ExecutionPolicy Bypass -File build-local-voicepack.ps1 -Speed 0.8
powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -ModelVariant fp32 -Push -Serial <sn>

# ④ 换更短(<3s)/更慢的参考音频：需改 build-local-voicepack.ps1 的 $Plan 后重建
#    ⚠️ 换/改**参考集**（含本臂）后必须先强杀 App 进程：
#       adb shell am force-stop io.prism
#    原因：缓存键含"参考集条数"但不含参考内容身份，同条数改动会让引擎继续用旧的 reference_text
#    （ZipVoice 要求参考音频与文本**逐字一致**，不一致会明显劣化 → 会污染这一臂的结论）。
#    改 generation 段（11 个参数）或换模型文件**都不需要**强杀（缓存键已覆盖）。
```

单次播报后抓日志（含 RTF）：

```powershell
adb logcat -c ; adb logcat -s "LocalClone/ZipVoice" "LocalClone"
```

### 8.3 参考音频（prompt）的官方最佳实践

> ⚠️ **TKN-LVC-004（2026-09-20）实测修正**：模型会**模仿 prompt 内部的停顿结构**。旧参考集
> （3.5–8.6s，内部含 0.5–1.3s 长停顿）会让模型在 11 字短句里塞进 **0.93s** 停顿 → 听感"停顿位置不对"。
> 换成 **2.28–2.95s + 内部停顿压到 ≤0.2s + 末尾补 200ms** 后，同句停顿降到 **0.24s** 且落点最贴近逗号，
> 同时 RTF 1.62 → 1.13。详见 `docs/reports/2026-09-20-local-voice-clone-prosody-similarity-research.md`。
> **不要**用 `max_char_in_sentence` 强制在逗号分段（实测会产生**两个**停顿且 RTF 2.36）。

| 项 | 要求 | 现状（2026-09-20） |
| --- | --- | --- |
| 时长 | 官方建议 **<3s**（过长会变慢且质量退化） | **2.28–2.95s** ✓ |
| 内部静音 | 压缩 >0.5s 的长停顿（模型会模仿它） | 已压到 0.2s，实测 ≤0.70s ✓ |
| 末尾 | 补 200ms 静音（上游 `trail_sil=200`） | 已补 ✓ |
| 采样率 | 可给原采样率，但 `referenceSampleRate` 必须等于真实值 | 48kHz（原采样） |
| 响度 | 官方无 loudnorm 步骤；`target_rms` 是**单向**提升 | 保留 `loudnorm I=-16:TP=-1.5`（实测无削波） |
| 文本 | 必须与音频**逐字一致** | 78/78 官方台词覆盖 ✓ |
| 去 BGM/混响 | 官方不做；若源带 BGM/混响则相似度受损 | **未做**（源为游戏语音 mp3） |


