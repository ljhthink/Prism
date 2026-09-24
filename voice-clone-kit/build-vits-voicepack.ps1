# build-vits-voicepack.ps1 —— 把微调 VITS 导出物组装成端侧**音色包**并（可选）推送到真机
#   （TKN-LVC-015 / ADR-055）
#
# 产物（voice-clone-kit\build-out\zipvoice\voicepack\<VoiceId>\）：
#   voicepack.json          清单（`tts: "vits"`；references 为空——VITS 音色在权重里）
#   model/model.onnx        **已修 comment 元数据**的那份（未修版本会让 sherpa native abort）
#   model/lexicon.txt / tokens.txt
#   model/dict/             可选（结巴词典）
#   model/*.fst             可选（date/number/phone/new_heteronym；≤ MAX_RULE_FSTS=8 条）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build-vits-voicepack.ps1                 # 只组装
#   powershell -ExecutionPolicy Bypass -File build-vits-voicepack.ps1 -Push           # 组装并推送到真机
#
# 设备侧落点：files/voicepack/<VoiceId>（**内部目录**；外部目录经 run-as 不可读，见 runbook §1）
# 切换方式：角色包 `cyrene.rolepack` 的 `voice.local.voiceId` 改成 <VoiceId> 后重新导入。

[CmdletBinding()]
param(
    [string]$VoiceId = "cyrene-vits",
    [string]$DisplayName = "昔涟（VITS 微调）",
    # 语速（sherpa `GenerationConfig.speed`；>1 加快、<1 变慢）。
    # TKN-LVC-016：0.8 是**用户裁决值**——该语料固有语速 6.16 字/秒（p90 7.36），
    # 微调产物实测 6.61 字/秒，用户判定"过快"；0.8 档实测降到 5.89 字/秒（落在语料 p10~均值之间）。
    [double]$Speed = 0.8,
    # 导出目录（相对 kit 根）。缺省 = epoch-100 的导出；TKN-LVC-016 续训产物在
    # `onnx-output-e500\vits-cyrene`，**必须显式指定**才能打包新权重——否则会打出旧模型，
    # 表现为"续训了但听不出变化"（正是本项目反复出现的"改了没效果"型缺陷）。
    [string]$OnnxSubDir = "onnx-output\vits-cyrene",
    [switch]$Push,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$onnxDir = Join-Path $kitDir ("build-out\vits\toolchain\sherpa-onnx-vits-fine-tuning-webui\" + $OnnxSubDir)
$packDir = Join-Path $kitDir ("build-out\zipvoice\voicepack\" + $VoiceId)
$modelDir = Join-Path $packDir "model"
$deviceStage = "/data/local/tmp/lvc-$VoiceId"
$deviceInternalRel = "files/voicepack/$VoiceId"

# ==================== 1. 资产体检 ====================
$modelSrc = Join-Path $onnxDir "model.fixed.onnx"
if (-not (Test-Path $modelSrc)) {
    Write-Error "缺少 $modelSrc —— 先跑 voice-clone-kit\tools\fix-vits-onnx-metadata.py（未修 comment 会让 native 直接 abort）"
    exit 1
}
$fsts = @("date.fst", "number.fst", "phone.fst", "new_heteronym.fst") |
    ForEach-Object { Join-Path $onnxDir $_ } | Where-Object { Test-Path $_ }
New-Item -ItemType Directory -Force -Path $modelDir | Out-Null

Write-Host "== 资产 =="
Copy-Item $modelSrc (Join-Path $modelDir "model.onnx") -Force
Copy-Item (Join-Path $onnxDir "lexicon.txt") (Join-Path $modelDir "lexicon.txt") -Force
Copy-Item (Join-Path $onnxDir "tokens.txt") (Join-Path $modelDir "tokens.txt") -Force
Write-Host ("  model      {0,8:N2} MB" -f ((Get-Item $modelSrc).Length / 1MB))
Write-Host ("  lexicon    {0,8:N2} MB" -f ((Get-Item (Join-Path $onnxDir "lexicon.txt")).Length / 1MB))

$dictSrc = Join-Path $onnxDir "dict"
$hasDict = Test-Path $dictSrc
if ($hasDict) {
    $dictDst = Join-Path $modelDir "dict"
    if (Test-Path $dictDst) { Remove-Item $dictDst -Recurse -Force }
    Copy-Item $dictSrc $dictDst -Recurse -Force
    $dictMB = (Get-ChildItem -Recurse $dictDst | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host ("  dict       {0,8:N2} MB" -f $dictMB)
}
foreach ($f in $fsts) {
    Copy-Item $f (Join-Path $modelDir (Split-Path -Leaf $f)) -Force
    Write-Host ("  fst        {0,8:N2} MB  {1}" -f ((Get-Item $f).Length / 1MB), (Split-Path -Leaf $f))
}

# ==================== 2. 写清单（UTF-8 **无 BOM**） ====================
$fstJson = ($fsts | ForEach-Object { '"model/' + (Split-Path -Leaf $_) + '"' }) -join ","
$dictLine = if ($hasDict) { '"dictDir": "model/dict",' } else { "" }
$manifest = @"
{
  "schema": "prism-localvoice/1",
  "voiceId": "$VoiceId",
  "name": "$DisplayName",
  "tts": "vits",
  "model": {
    "model": "model/model.onnx",
    "lexicon": "model/lexicon.txt",
    "tokens": "model/tokens.txt",
    $dictLine
    "ruleFsts": [$fstJson]
  },
  "generation": {
    "speed": $Speed
  },
  "references": []
}
"@
$manifestPath = Join-Path $packDir "voicepack.json"
[System.IO.File]::WriteAllText($manifestPath, $manifest, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ""
Write-Host "== 清单：$manifestPath（UTF-8 无 BOM） =="
Write-Host ("  tts=vits / references=0 / ruleFsts={0} 条 / dict={1}" -f $fsts.Count, $hasDict)

$totalMB = ((Get-ChildItem -Recurse $packDir | Measure-Object -Property Length -Sum).Sum) / 1MB
Write-Host ("  合计 {0:N1} MB" -f $totalMB)

if (-not $Push) {
    Write-Host ""
    Write-Host "未指定 -Push：仅组装完成。加 -Push 推送到设备。"
    exit 0
}

# ==================== 3. 推送（内部目录，与 install-local-voicepack.ps1 同法） ====================
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Write-Error "adb 不在 PATH 中"; exit 1 }
$adbArgs = @()
if ($Serial -ne "") { $adbArgs += @("-s", $Serial) }
$devices = (adb @adbArgs devices) -split "`n" | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) { Write-Error "未检测到已连接且已授权的设备"; exit 1 }

Write-Host ""
Write-Host "== 1/3 暂存到 $deviceStage =="
adb @adbArgs shell "rm -rf '$deviceStage'; mkdir -p '$deviceStage/model'"
if ($LASTEXITCODE -ne 0) { Write-Error "设备暂存目录创建失败"; exit 1 }
adb @adbArgs push (Join-Path $modelDir "model.onnx")  "$deviceStage/model/model.onnx"
adb @adbArgs push (Join-Path $modelDir "lexicon.txt") "$deviceStage/model/lexicon.txt"
adb @adbArgs push (Join-Path $modelDir "tokens.txt")  "$deviceStage/model/tokens.txt"
foreach ($f in $fsts) { adb @adbArgs push $f "$deviceStage/model/" }
if ($hasDict) { adb @adbArgs push (Join-Path $modelDir "dict") "$deviceStage/model/" }
adb @adbArgs push $manifestPath "$deviceStage/voicepack.json"
if ($LASTEXITCODE -ne 0) { Write-Error "推送失败（检查授权与剩余空间；本包约 $([math]::Round($totalMB))MB）"; exit 1 }

Write-Host ""
Write-Host "== 2/3 以应用 uid 复制进内部目录 =="
adb @adbArgs shell "chmod -R a+rX '$deviceStage'"
adb @adbArgs shell run-as io.prism mkdir -p files/voicepack
adb @adbArgs shell run-as io.prism rm -rf $deviceInternalRel
adb @adbArgs shell run-as io.prism cp -r $deviceStage $deviceInternalRel
if ($LASTEXITCODE -ne 0) { Write-Error "复制失败（应用未安装 / 非 debuggable 包？）"; exit 1 }
adb @adbArgs shell "rm -rf '$deviceStage'"

Write-Host ""
Write-Host "== 3/3 设备侧核对 =="
adb @adbArgs shell run-as io.prism ls $deviceInternalRel
adb @adbArgs shell run-as io.prism sha256sum $deviceInternalRel/voicepack.json
Write-Host ("  主机 voicepack.json = {0}" -f (Get-FileHash $manifestPath -Algorithm SHA256).Hash.ToLower())
Write-Host ""
Write-Host "完成。切换方式见本脚本头部注释（改角色包 voice.local.voiceId 后重新导入）。"
