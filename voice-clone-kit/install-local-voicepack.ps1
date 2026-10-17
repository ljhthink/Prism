# 昔涟本地音色包 → 设备侧载（install-local-voicepack.ps1）
#
# 用途：把 ZipVoice 端侧模型 + 参考音频集组装成设备上的「本地音色包」目录并推送。
#   **模型不进 APK、不进仓库**（ADR-053 决策 4）：单角色约 195MB，用 adb 侧载到
#   应用专属外部目录（`/sdcard/Android/data/io.prism/files/voicepack/<voiceId>/`）——
#   该目录无需运行时权限、随卸载清除、adb 可直接写入。
#
# 用法（在 voice-clone-kit\ 目录）：
#   powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1                 # 全自动（缺模型则下载，缺参考集则构建）
#   powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -Serial <sn>    # 指定设备
#   powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -Push           # 组装后推送到设备
#   powershell -ExecutionPolicy Bypass -File install-local-voicepack.ps1 -NoDownload     # 禁止联网（模型缺则报错退出）
#
# 依赖：curl.exe、tar.exe（Windows 10+ 自带）、adb（Android SDK platform-tools）。
# 国内直连 GitHub release 资源常被重置——本脚本默认走 ghfast.top 镜像前缀（失败会依次尝试备用镜像）。

param(
    [string]$VoiceId = "cyrene",
    [string]$Serial = "",
    [switch]$Push,
    [switch]$NoDownload,
    # 模型变体（音质↔体积/延迟）：
    #   int8 = distill-int8（104MB，默认；真机实测有金属感"电音"）
    #   fp32 = distill-fp32（456MB，**同一 distill 模型的非量化版**——音质首选）
    #   base = 非蒸馏基座（605MB；音质上限最高，但需更多步数、明显更慢）
    [ValidateSet("int8", "fp32", "base")]
    [string]$ModelVariant = "int8"
)

$ErrorActionPreference = "Stop"

$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$modelRoot = Join-Path $kitDir "build-out\zipvoice\local-model"

# 变体 → 官方资产名 / 解包目录名 / 权重文件名（规范化推送名见 §4，与清单无关，故换变体无需改清单）
switch ($ModelVariant) {
    "int8" {
        $asset = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
        $encoderSrcName = "encoder.int8.onnx"
        $decoderSrcName = "decoder.int8.onnx"
    }
    "fp32" {
        $asset = "sherpa-onnx-zipvoice-distill-fp32-zh-en-emilia"
        $encoderSrcName = "encoder.onnx"
        $decoderSrcName = "decoder.onnx"
    }
    default {
        $asset = "sherpa-onnx-zipvoice-zh-en-emilia"
        $encoderSrcName = "encoder.onnx"
        $decoderSrcName = "decoder.onnx"
    }
}
$zipvoiceDirName = $asset
$zipvoiceDir = Join-Path $modelRoot $zipvoiceDirName
$vocoderPath = Join-Path $modelRoot "vocos_24khz.onnx"
$packSrc = Join-Path $kitDir ("build-out\zipvoice\voicepack\" + $VoiceId)
# 有效载荷落**内部目录**（真机验证可读）；外部目录经 run-as 读取会 Permission denied，见 runbook §1
$deviceStage = "/data/local/tmp/lvc-$VoiceId"
$deviceInternalRel = "files/voicepack/$VoiceId"

$mirrors = @("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/", "")

function Get-WithMirror([string]$url, [string]$out) {
    if (Test-Path $out) { return $true }
    if ($NoDownload) { Write-Error "缺少 $out 且指定了 -NoDownload"; exit 1 }
    foreach ($m in $mirrors) {
        Write-Host ("  下载（{0}）..." -f ($(if ($m -eq "") { "直连" } else { $m })))
        curl.exe -L --connect-timeout 15 --max-time 1800 -sS -o $out "$m$url"
        if ((Test-Path $out) -and (Get-Item $out).Length -gt 1000000) { return $true }
        Remove-Item $out -Force -ErrorAction SilentlyContinue
    }
    return $false
}

# ==================== 1. 模型就位（下载 + 解包） ====================
New-Item -ItemType Directory -Force -Path $modelRoot | Out-Null

if (-not (Test-Path (Join-Path $zipvoiceDir $decoderSrcName))) {
    Write-Host ("== 模型（ZipVoice {0} zh-en）==" -f $ModelVariant)
    $tar = Join-Path $modelRoot "$ModelVariant.tar.bz2"
    $url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$asset.tar.bz2"
    if (-not (Get-WithMirror $url $tar)) { Write-Error "模型下载失败（三个镜像均不可用）：$url"; exit 1 }
    Write-Host "  解包 ..."
    tar -xjf $tar -C $modelRoot
    if ($LASTEXITCODE -ne 0) { Write-Error "解包失败：$tar"; exit 1 }
} else {
    Write-Host ("== 模型（{0}）：已就位 ==" -f $ModelVariant)
}

if (-not (Test-Path $vocoderPath)) {
    Write-Host "== 声码器（vocos_24khz）=="
    $url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx"
    if (-not (Get-WithMirror $url $vocoderPath)) { Write-Error "声码器下载失败：$url"; exit 1 }
} else {
    Write-Host "== 声码器：已就位 =="
}

# ==================== 2. 参考集就位（缺则构建） ====================
if (-not (Test-Path (Join-Path $packSrc "voicepack.json"))) {
    Write-Host "== 参考集缺失，执行 build-local-voicepack.ps1 =="
    & powershell -ExecutionPolicy Bypass -File (Join-Path $kitDir "build-local-voicepack.ps1") -VoiceId $VoiceId
    if (-not (Test-Path (Join-Path $packSrc "voicepack.json"))) { Write-Error "参考集构建失败"; exit 1 }
} else {
    Write-Host "== 参考集：已就位 =="
}

# ==================== 3. 组装视图（只读校验，不复制大文件） ====================
Write-Host ""
Write-Host "== 音色包构成 =="
$manifest = Get-Content (Join-Path $packSrc "voicepack.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$required = @(
    @{ Name = "tokens";  Src = (Join-Path $zipvoiceDir "tokens.txt") },
    @{ Name = "encoder"; Src = (Join-Path $zipvoiceDir $encoderSrcName) },
    @{ Name = "decoder"; Src = (Join-Path $zipvoiceDir $decoderSrcName) },
    @{ Name = "vocoder"; Src = $vocoderPath },
    @{ Name = "lexicon"; Src = (Join-Path $zipvoiceDir "lexicon.txt") }
)
foreach ($r in $required) {
    if (-not (Test-Path $r.Src)) { Write-Error ("模型文件缺失：{0} -> {1}" -f $r.Name, $r.Src); exit 1 }
    Write-Host ("  {0,-8} {1,10:N2} MB" -f $r.Name, ((Get-Item $r.Src).Length / 1MB))
}
$espeak = Join-Path $zipvoiceDir "espeak-ng-data"
if (-not (Test-Path $espeak)) { Write-Error "espeak-ng-data 缺失：$espeak"; exit 1 }
$espeakMB = (Get-ChildItem -Recurse $espeak | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("  {0,-8} {1,10:N2} MB" -f "espeak", $espeakMB)
$refsMB = (Get-ChildItem (Join-Path $packSrc "refs") | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("  {0,-8} {1,10:N2} MB（{2} 条参考）" -f "refs", $refsMB, $manifest.references.Count)
$totalMB = ($required | ForEach-Object { (Get-Item $_.Src).Length } | Measure-Object -Sum).Sum / 1MB + $espeakMB + $refsMB
Write-Host ("  合计约 {0:N1} MB（目标设备可用空间需留裕量）" -f $totalMB)

if (-not $Push) {
    Write-Host ""
    Write-Host ("未指定 -Push：仅校验与组装检查完成（变体={0}）。加 -Push 推送到设备：" -f $ModelVariant)
    Write-Host ("  内部目录目标 = {0}（暂存 {1}）" -f $deviceInternalRel, $deviceStage)
    exit 0
}

# ==================== 4. 推送到设备（内部目录；外部目录经 run-as 不可读，见 runbook §1） ====================
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Write-Error "adb 不在 PATH 中"; exit 1 }
$adbArgs = @()
if ($Serial -ne "") { $adbArgs += @("-s", $Serial) }
$devices = (adb @adbArgs devices) -split "`n" | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) { Write-Error "未检测到已连接且已授权的设备（adb devices 无 device 项）"; exit 1 }

Write-Host ""
Write-Host "== 1/3 暂存到 $deviceStage =="
adb @adbArgs shell "rm -rf '$deviceStage'; mkdir -p '$deviceStage/model' '$deviceStage/refs'"
if ($LASTEXITCODE -ne 0) { Write-Error "设备暂存目录创建失败"; exit 1 }

# 规范化推送名（恒为 model/encoder.onnx、model/decoder.onnx；与 voicepack.json 一致 ⇒ 换变体无需改清单）
adb @adbArgs push (Join-Path $zipvoiceDir "tokens.txt")      "$deviceStage/model/tokens.txt"
adb @adbArgs push (Join-Path $zipvoiceDir $encoderSrcName)   "$deviceStage/model/encoder.onnx"
adb @adbArgs push (Join-Path $zipvoiceDir $decoderSrcName)   "$deviceStage/model/decoder.onnx"
adb @adbArgs push (Join-Path $zipvoiceDir "lexicon.txt")     "$deviceStage/model/lexicon.txt"
adb @adbArgs push $vocoderPath                               "$deviceStage/model/vocos_24khz.onnx"
adb @adbArgs push $espeak                                    "$deviceStage/model/"
adb @adbArgs push (Join-Path $packSrc "refs")                "$deviceStage/"
adb @adbArgs push (Join-Path $packSrc "voicepack.json")      "$deviceStage/voicepack.json"
if ($LASTEXITCODE -ne 0) { Write-Error "推送失败（检查 adb 授权与剩余空间）"; exit 1 }

Write-Host ""
Write-Host "== 2/3 以应用 uid 复制进内部目录 =="
adb @adbArgs shell "chmod -R a+rX '$deviceStage'"
adb @adbArgs shell run-as io.prism mkdir -p files/voicepack
adb @adbArgs shell run-as io.prism rm -rf $deviceInternalRel
adb @adbArgs shell run-as io.prism cp -r $deviceStage $deviceInternalRel
if ($LASTEXITCODE -ne 0) { Write-Error "复制失败（应用未安装 / 非 debuggable 包？）"; exit 1 }
adb @adbArgs shell "rm -rf '$deviceStage'"

Write-Host ""
Write-Host "== 3/3 设备侧核对（应用 uid 视角） =="
adb @adbArgs shell run-as io.prism ls $deviceInternalRel
adb @adbArgs shell run-as io.prism sha256sum $deviceInternalRel/voicepack.json
Write-Host ("  主机 voicepack.json = {0}" -f (Get-FileHash (Join-Path $packSrc "voicepack.json") -Algorithm SHA256).Hash.ToLower())
Write-Host ""
Write-Host "完成。下一步：真机 App → 设置 → 语音引擎 → 播报引擎选「本地昔涟（离线）」→ 文本消息点播放试听。"
Write-Host "（App 也可先进入设置页查看安装状态：应显示「已安装（N 条参考）」）"
