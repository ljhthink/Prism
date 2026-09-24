# 昔涟本地音色包构建（build-local-voicepack.ps1）
#
# 用途（PRD US-001 / AC0）：把 raw\ 里的官方语音素材 + WIKI 官方台词，构建成 ZipVoice 端侧克隆所需的
# **参考音频集 + 音色包清单**（voicepack.json）。模型文件不在此脚本内（由 install-local-voicepack.ps1
# 组装并推送到设备）——本脚本只产出小体积、可复算、可核对的部分。
#
# 为什么需要"台账"：ZipVoice 是**参考音频驱动**的零样本克隆，官方明确要求
# reference_text 与 reference_audio **逐字一致**（不一致音质明显退化）。因此台词必须来自官方来源，
# 且"缺台词/时长越界"必须**失败退出**而不是静默生成残包（fail-fast，防产出不可用的音色包）。
#
# 用法（在 voice-clone-kit\ 目录）：
#   powershell -ExecutionPolicy Bypass -File build-local-voicepack.ps1
#   powershell -ExecutionPolicy Bypass -File build-local-voicepack.ps1 -VoiceId cyrene -DryRun
#
# 依赖：ffmpeg / ffprobe（本机已确认可用）、cyrene-voice-lines.json（由 parse-wiki-vo-lines.ps1 产出）。
# 输出：
#   build-out\zipvoice\voicepack\<VoiceId>\refs\ref_XX.wav   参考音频（24kHz 单声道 16bit PCM；静音裁剪 + 响度归一）
#   build-out\zipvoice\voicepack\<VoiceId>\voicepack.json    音色包清单（模型相对路径 + 生成参数 + 参考集原文台词）
#
# ⚠️ 编码约定：voicepack.json 必须以 **UTF-8 无 BOM** 写出——Kotlin 侧 kotlinx-serialization
# 解析带 BOM 的字符串会失败（生产解析器已做 BOM 容错，但生成端仍按无 BOM 产出，避免依赖容错）。

param(
    [string]$VoiceId = "cyrene",
    [string]$PackName = "昔涟（本地离线音色）",
    [switch]$DryRun,
    # ---- 生成参数（写进清单 generation 段；可由角色包 voice.local.numSteps 覆盖 numSteps） ----
    # 语速：ZipVoice 的 speed 语义为"时长缩放"（<1 = 变慢、>1 = 变快）；官方对超短文本建议 0.3 拉长时长。
    [double]$Speed = 0.9,
    # 流匹配步数：distill 官方默认 8（下限 4）；步数越多越慢
    [int]$NumSteps = 8,
    # 生成后静音缩放：sherpa 默认 0.2 会把 >0.2s 停顿压到 20%（听感变快）→ 必须 1.0 = 不缩放。
    # ⚠ 除听感外它还决定【能否走流式播放】：LocalCloneTtsEngine 的流式门控要求 silenceScale == 1.0
    #   （ADR-054：非 1.0 时上游只在整段结束时做 ScaleSilence、流式路径会静默丢弃该后处理）
    #   ⇒ 设成 0.5 会让端侧静默退回「整段合成→整段播放」，段间出现长时间无声（BUG-20260924-003）。
    [double]$SilenceScale = 1.0,
    # 条件强度：distill 官方默认 3.0（不是 CFG，而是直接喂 decoder 的条件 embedding）；
    # sherpa-onnx 默认 1.0 → 条件不足会"扁平/机械"
    [double]$GuidanceScale = 4.0,
    # prompt mel 缩放：**必须贴官方训练值 0.1**（作者明确警告改 0.5 不可用、社区实测 0.2 变机械音）
    [double]$FeatScale = 0.1,
    # 时间步 schedule 形变（官方 0.5）
    [double]$TShift = 0.5,
    # prompt RMS 单向归一目标（官方 0.1；必须 > 0）
    [double]$TargetRms = 0.1,
    # 推理线程数：**不影响音色**，只影响速度（合法域 1..4，App 侧会钳制）。
    [int]$NumThreads = 2,
    # 合成看门狗超时（ms；合法域 1000..120000）。fp32/多步数会显著变慢，需相应放宽。
    [int]$TimeoutMs = 120000,
    # 参考音频目标采样率（源为 48kHz；**不降采样**可避免二次重采样损失）
    [int]$RefSampleRate = 48000
)

$ErrorActionPreference = "Stop"

$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rawDir = Join-Path $kitDir "assets\raw"
$linesJson = Join-Path $kitDir "assets\transcripts\cyrene-voice-lines.json"
$packDir = Join-Path $kitDir ("build-out\zipvoice\voicepack\" + $VoiceId)
$refsDir = Join-Path $packDir "refs"

# ==================== 参考集计划（人工选定，覆盖情绪/句长；时长对齐官方示例 6.1/6.8/8.8s 量级） ====================
# 选材标准：时长 3–9.5s（ZipVoice README 建议短 prompt；官方自带示例为 6.06/6.84/8.84s）、
# 官方台词文本可得、句长适中且有情绪差异。改此表即改参考集（可复算）。
$referencePlan = @(
    # TKN-LVC-005：**恢复旧长集**（用户主观判定"音色可以接受"），但**保留**新增的 prompt 预处理
    # （内部 >0.5s 停顿压到 0.2s + 尾补 200ms）——停顿问题由预处理解决，不必靠缩短 prompt。
    # 依据：① 用户实测反馈"缩到 2.3s 后音色过于尖锐、不如原来"；
    #      ② 本机客观度量显示短 prompt 会让生成 F0 漂高（2.28s→312Hz vs 6.00s→273Hz，角色典型 254Hz）。
    # 注：长度↔音色的单样本度量噪声大，故此处**以用户听感为准**，不再靠 proxy 挑素材。
    [pscustomobject]@{ Id = "ref_00"; Raw = "VO_fandom_00_问候.mp3";            Emotion = "亲切·问候" },
    [pscustomobject]@{ Id = "ref_01"; Raw = "VO_fandom_03_关于自己_神谕_一.mp3"; Emotion = "平静·叙述" },
    [pscustomobject]@{ Id = "ref_02"; Raw = "VO_fandom_08_烦恼.mp3";             Emotion = "俏皮·温柔" },
    [pscustomobject]@{ Id = "ref_03"; Raw = "VO_fandom_29_星魂激活.mp3";         Emotion = "明亮·短句" },
    [pscustomobject]@{ Id = "ref_04"; Raw = "VO_fandom_41_队伍编成_赛飞儿.mp3";  Emotion = "俏皮·亲密" },
    [pscustomobject]@{ Id = "ref_05"; Raw = "VO_fandom_31_角色满级.mp3";         Emotion = "温柔·期待" },
    [pscustomobject]@{ Id = "ref_06"; Raw = "VO_fandom_23_关于那刻夏.mp3";       Emotion = "活泼·兴奋" },
    [pscustomobject]@{ Id = "ref_07"; Raw = "VO_fandom_34_队伍编成_白厄.mp3";    Emotion = "温柔·感慨" }
)

# 时长门禁与选材标准（第 60 行注释）保持一致（guardrail TKN-LVC-003 F-4：此前门禁 2.5–10.0 宽于口径 3–9.5）
$MinRefSec = 3.0
$MaxRefSec = 9.5

# ==================== 前置检查 ====================
foreach ($tool in @("ffmpeg", "ffprobe")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Write-Error "缺少依赖：$tool 不在 PATH 中"; exit 1 }
}
if (-not (Test-Path $rawDir)) { Write-Error "未找到 raw\ 目录：$rawDir"; exit 1 }
if (-not (Test-Path $linesJson)) {
    Write-Host "缺少 $linesJson，先执行 parse-wiki-vo-lines.ps1"
    & powershell -ExecutionPolicy Bypass -File (Join-Path $kitDir "parse-wiki-vo-lines.ps1")
    if (-not (Test-Path $linesJson)) { Write-Error "台词库生成失败（$linesJson 不存在）"; exit 1 }
}

$lines = (Get-Content $linesJson -Raw -Encoding UTF8 | ConvertFrom-Json).lines
if (-not $lines -or $lines.Count -eq 0) { Write-Error "台词库为空——WIKI 页面结构可能已变更"; exit 1 }

# 台词索引：三种键形态都登记（文件名标签的标点替换方式历史上不一致，见 raw\ 命名）
$textByLabel = @{}
foreach ($l in $lines) {
    foreach ($key in @(
            $l.语音类型,
            ($l.语音类型 -replace '[•·]', '_'),
            (($l.语音类型 -replace '[•·]', '_') -replace '[【】]', '_'),
            (($l.语音类型 -replace '[•·]', '_') -replace '[【】（）()]', '_')
        )) {
        if (-not $textByLabel.ContainsKey($key)) { $textByLabel[$key] = $l.语音内容 }
    }
}

# ==================== 全素材体检（覆盖性校验：任一 raw 无台词即失败） ====================
Write-Host "== 素材体检（raw\ 全量探测 + 台词覆盖）=="
$allRaw = Get-ChildItem $rawDir -File | Where-Object { $_.Extension -match '\.(mp3|wav|ogg|m4a)$' } | Sort-Object Name
$missingText = @()
$probeRows = @()
foreach ($f in $allRaw) {
    $label = $f.BaseName -replace '^VO_fandom_\d+_', ''
    $dur = [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $f.FullName)
    $probe = ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate,channels -of json $f.FullName | ConvertFrom-Json
    $text = $null
    if ($textByLabel.ContainsKey($label)) { $text = $textByLabel[$label] }
    if ([string]::IsNullOrWhiteSpace($text)) { $missingText += $f.Name }
    $probeRows += [pscustomobject]@{
        File = $f.Name; Sec = [math]::Round($dur, 2)
        SR = $probe.streams[0].sample_rate; Ch = $probe.streams[0].channels
        HasText = (-not [string]::IsNullOrWhiteSpace($text))
    }
}
$probeRows | Measure-Object -Property Sec -Sum -Average | ForEach-Object {
    Write-Host ("  素材 {0} 条，总时长 {1:N2}s，平均 {2:N2}s" -f $_.Count, $_.Sum, $_.Average)
}
Write-Host ("  台词覆盖：{0}/{1}" -f ($probeRows | Where-Object { $_.HasText }).Count, $probeRows.Count)
if ($missingText.Count -gt 0) {
    Write-Error ("以下素材缺官方台词（ZipVoice 要求逐字一致，禁止生成残包）：`n  " + ($missingText -join "`n  "))
    exit 1
}

# 参考音频应全部为单声道（ZipVoice prompt 单说话人）；多声道仅告警
$multiChannel = $probeRows | Where-Object { [int]$_.Ch -ne 1 }
if ($multiChannel) { Write-Warning ("以下素材非单声道：{0}" -f (($multiChannel | ForEach-Object File) -join ", ")) }

# ==================== 构建参考集 ====================
Write-Host ""
Write-Host "== 构建参考集（${MinRefSec}s–${MaxRefSec}s）=="
$refs = @()
foreach ($p in $referencePlan) {
    $src = Join-Path $rawDir $p.Raw
    if (-not (Test-Path $src)) { Write-Error "参考计划中的素材不存在：$($p.Raw)"; exit 1 }
    $label = [System.IO.Path]::GetFileNameWithoutExtension($p.Raw) -replace '^VO_fandom_\d+_', ''
    if (-not $textByLabel.ContainsKey($label)) { Write-Error "参考素材缺台词：$($p.Raw)（标签 $label）"; exit 1 }
    $text = $textByLabel[$label]
    $dur = [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $src)
    if ($dur -lt $MinRefSec -or $dur -gt $MaxRefSec) {
        Write-Error ("参考素材 {0} 时长 {1:N2}s 越界（{2}–{3}s）——请改选素材" -f $p.Raw, $dur, $MinRefSec, $MaxRefSec)
        exit 1
    }
    $outName = "$($p.Id).wav"
    $outPath = Join-Path $refsDir $outName
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force -Path $refsDir | Out-Null
        # 一步步对齐上游的 prompt 预处理（sherpa **完全不做**，见 infer_zipvoice.py 的 remove_silence）：
        #   ① 首尾静音裁剪（保留 0.1s 呼吸感）
        #   ② **内部长静音压缩**：>0.5s 的停顿压到 0.2s —— 模型会模仿 prompt 的停顿结构，
        #      内部长停顿会让它在短句里产生 ~0.9s 的怪停顿（TKN-LVC-004 真机现象）
        #   ③ EBU R128 响度归一（TP=-1.5 留峰值余量，防削波）
        #   ④ **末尾补 200ms 静音**（上游 trail_sil=200；给模型一个"句末"边界线索）
        #   → 单声道 16bit PCM，保留源采样率（48kHz；ZipVoice 经 referenceSampleRate 自行处理）
        ffmpeg -y -hide_banner -loglevel error -i $src -af `
            "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.1,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.1,areverse,silenceremove=stop_periods=-1:stop_duration=0.5:stop_threshold=-40dB:stop_silence=0.2,loudnorm=I=-16:TP=-1.5:LRA=11,apad=pad_dur=0.2" `
            -ar $RefSampleRate -ac 1 -c:a pcm_s16le $outPath
        if ($LASTEXITCODE -ne 0) { Write-Error "参考音频处理失败：$($p.Raw)"; exit 1 }
    }
    $outDur = if ($DryRun) { $dur } else { [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $outPath) }
    Write-Host ("  {0}  {1,-34} {2,5:N2}s -> {3,5:N2}s  {4}" -f $p.Id, $p.Raw, $dur, $outDur, $p.Emotion)
    $refs += [pscustomobject]@{
        id = $p.Id
        audio = "refs/$outName"
        text = $text
        emotion = $p.Emotion
        durationMs = [int][math]::Round($outDur * 1000)
        source = $p.Raw
    }
}

# ==================== 写音色包清单 ====================
$manifest = [ordered]@{
    schema = "prism-localvoice/1"
    voiceId = $VoiceId
    name = $PackName
    engine = "zipvoice"
    license = "素材取自游戏官方语音，仅限个人自用，禁止分发/商用（见 voice-clone-kit/README.md）"
    model = [ordered]@{
        tokens = "model/tokens.txt"
        encoder = "model/encoder.onnx"
        decoder = "model/decoder.onnx"
        vocoder = "model/vocos_24khz.onnx"
        lexicon = "model/lexicon.txt"
        dataDir = "model/espeak-ng-data"
    }
    generation = [ordered]@{
        numSteps = $NumSteps
        speed = $Speed
        silenceScale = $SilenceScale
        guidanceScale = $GuidanceScale
        featScale = $FeatScale
        tShift = $TShift
        targetRms = $TargetRms
        minCharInSentence = 10
        maxCharInSentence = 200
        numThreads = $NumThreads
        timeoutMs = $TimeoutMs
    }
    references = $refs
}

if ($DryRun) {
    Write-Host ""
    Write-Host "== DryRun：不写盘。清单预览 =="
    $manifest | ConvertTo-Json -Depth 6
    exit 0
}

New-Item -ItemType Directory -Force -Path $packDir | Out-Null
$jsonPath = Join-Path $packDir "voicepack.json"
$jsonText = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($jsonPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host ("完成：{0} 条参考 -> {1}" -f $refs.Count, $refsDir)
Write-Host ("清单：{0}（UTF-8 无 BOM）" -f $jsonPath)
Write-Host ""
Write-Host "下一步：powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1  （组装模型并推送到设备）"
