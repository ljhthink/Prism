# build-vits-dataset.ps1 —— 为「中文 VITS 微调」产出训练数据集（TKN-LVC-014 / 方案 §B.2 步骤 1）
#
# 产物（全部落在 voice-clone-kit\build-out\vits\dataset\ 下，**不入库、不入 APK**）：
#   sampled_audio4ft\cNNN.wav        44.1kHz 单声道 16bit PCM（VITS-fast-fine-tuning v2 的采样率）
#   sampled_audio4ft.txt             每行  `<RelPrefix>/cNNN.wav|<Speaker>|[ZH]<官方台词>[ZH]`
#   manifest.json                    cNNN → { 源文件, 官方台词, 时长 }（可复算/可追溯）
#   build-report.txt                 总时长/条数/门禁结果（供 归档 与 真机外的可核验证据）
#
# 为什么另起脚本而不是改 build-local-voicepack.ps1：
#   后者的产物是 **8 条 ZipVoice 提示音频 + voicepack.json**（推理期资产，带 prompt 专用
#   尾部补静音 apad=pad_dur=0.2）；本脚本的产物是 **全量 78 条训练语料**（训练期资产，
#   不做 prompt 补静音）。两者口径不同、消费方不同，合并会让任一侧的改动污染另一侧。
#
# 依赖：ffmpeg / ffprobe、cyrene-voice-lines.json（parse-wiki-vo-lines.ps1 产出）、raw\ 全量素材。
# 纪律：任一素材缺官方台词即**非零退出**（VITS 微调同样要求逐字一致的转写）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build-vits-dataset.ps1
#   powershell -ExecutionPolicy Bypass -File build-vits-dataset.ps1 -Speaker Cyrene -RelPrefix training_data/sampled_audio4ft

[CmdletBinding()]
param(
    [string]$OutDir = "",
    [string]$Speaker = "Cyrene",
    [int]$SampleRate = 44100,
    # 写入 sampled_audio4ft.txt 的相对路径前缀（要按训练仓库根目录调整）
    [string]$RelPrefix = "training_data/sampled_audio4ft",
    # 少于该时长（秒）即告警：VITS-fast-fine-tuning README 要求 >= 3 分钟
    [double]$MinTotalSec = 180.0
)

$ErrorActionPreference = "Stop"

$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $kitDir "build-out\vits\dataset" }
$rawDir = Join-Path $kitDir "assets\raw"
$linesJson = Join-Path $kitDir "assets\transcripts\cyrene-voice-lines.json"
$wavDir = Join-Path $OutDir "sampled_audio4ft"
$listPath = Join-Path $OutDir "sampled_audio4ft.txt"
$manifestPath = Join-Path $OutDir "manifest.json"
$reportPath = Join-Path $OutDir "build-report.txt"

# ==================== 前置检查 ====================
foreach ($tool in @("ffmpeg", "ffprobe")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Write-Error "缺少依赖：$tool 不在 PATH 中"; exit 1 }
}
if (-not (Test-Path $rawDir)) { Write-Error "未找到 raw\ 目录：$rawDir"; exit 1 }
if (-not (Test-Path $linesJson)) { Write-Error "未找到台词库：$linesJson（先执行 parse-wiki-vo-lines.ps1）"; exit 1 }

New-Item -ItemType Directory -Force -Path $wavDir | Out-Null

$lines = (Get-Content $linesJson -Raw -Encoding UTF8 | ConvertFrom-Json).lines
if (-not $lines -or $lines.Count -eq 0) { Write-Error "台词库为空——WIKI 页面结构可能已变更"; exit 1 }

# 台词索引：与 build-local-voicepack.ps1 同口径（文件名标签的标点替换方式历史上不一致）
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

# ==================== 逐条转码 ====================
# 清洗链与参考音频一致（去首尾静音 + 压缩过长内部停顿 + loudnorm），但**不加** prompt 专用的
# apad=pad_dur=0.2（那是给 ZipVoice 提示用的尾静音，训练语料不需要）。
$filter = "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.1," +
"areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.1,areverse," +
"silenceremove=stop_periods=-1:stop_duration=0.5:stop_threshold=-40dB:stop_silence=0.2," +
"loudnorm=I=-16:TP=-1.5:LRA=11"

$allRaw = Get-ChildItem $rawDir -File | Where-Object { $_.Extension -match '\.(mp3|wav|ogg|m4a)$' } | Sort-Object Name
$missing = @()
$manifest = @()
$listLines = New-Object System.Collections.Generic.List[string]
$report = New-Object System.Collections.Generic.List[string]
$totalSec = 0.0
$idx = 0

foreach ($f in $allRaw) {
    $label = $f.BaseName -replace '^VO_fandom_\d+_', ''
    $text = $null
    if ($textByLabel.ContainsKey($label)) { $text = $textByLabel[$label] }
    if ([string]::IsNullOrWhiteSpace($text)) { $missing += $f.Name; continue }
    # [ZH] ... [ZH] 是 VITS-fast-fine-tuning 的语言标记（cleaner 据此选 zh 前端）
    if ($text -match '\[ZH\]') { Write-Error "台词内含 [ZH] 标记，会破坏语言标记：$($f.Name)"; exit 1 }

    $name = "c{0:D3}.wav" -f $idx
    $outPath = Join-Path $wavDir $name
    ffmpeg -y -hide_banner -loglevel error -i $f.FullName -af $filter -ar $SampleRate -ac 1 -c:a pcm_s16le $outPath
    if ($LASTEXITCODE -ne 0) { Write-Error "转码失败：$($f.Name)"; exit 1 }

    $dur = [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $outPath)
    $totalSec += $dur
    $listLines.Add("$RelPrefix/$name|$Speaker|[ZH]$text[ZH]")
    $manifest += [pscustomobject]@{ id = $name; source = $f.Name; sec = [math]::Round($dur, 2); text = $text }
    $report.Add(("{0}  {1,6:N2}s  {2}" -f $name, $dur, $f.Name))
    $idx++
}

if ($missing.Count -gt 0) {
    Write-Error ("以下素材缺官方台词（逐字一致是硬要求，禁止产出残数据集）：`n  " + ($missing -join "`n  "))
    exit 1
}
if ($idx -eq 0) { Write-Error "没有产出任何训练样本"; exit 1 }

# ==================== 写出产物 ====================
# 全部 UTF-8 **无 BOM**：VITS-fast-fine-tuning 的 data_utils 以 utf-8 逐行读，BOM 会让首行的
# 标签匹配失败（表现为"只丢第一条"这类难查现象）。
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($listPath, ($listLines -join "`n") + "`n", $utf8NoBom)
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 4), $utf8NoBom)

$header = @(
    "== VITS 训练数据集构建报告 ==",
    ("素材来源目录 : {0}" -f $rawDir),
    ("台词库       : {0}" -f $linesJson),
    ("样本条数     : {0}" -f $idx),
    ("总时长       : {0:N2}s ({1:N2} 分钟)" -f $totalSec, ($totalSec / 60.0)),
    ("平均时长     : {0:N2}s" -f ($totalSec / $idx)),
    ("采样率/声道  : {0}Hz / mono / s16le" -f $SampleRate),
    ("说话人名     : {0}" -f $Speaker),
    ("清单         : {0}" -f $listPath),
    ("相对路径前缀 : {0}（须与训练仓库根目录一致，不一致时改 -RelPrefix）" -f $RelPrefix),
    "",
    "== 逐条 =="
) + $report
[System.IO.File]::WriteAllText($reportPath, ($header -join "`n") + "`n", $utf8NoBom)

Write-Host ("样本 {0} 条，总时长 {1:N2}s（{2:N2} 分钟）" -f $idx, $totalSec, ($totalSec / 60.0))
Write-Host ("清单：{0}" -f $listPath)
Write-Host ("报告：{0}" -f $reportPath)
if ($totalSec -lt $MinTotalSec) {
    Write-Warning ("总时长 {0:N2}s 低于门禁 {1:N2}s：VITS-fast-fine-tuning 要求 >= 3 分钟，可能训练不足" -f $totalSec, $MinTotalSec)
    exit 2
}
