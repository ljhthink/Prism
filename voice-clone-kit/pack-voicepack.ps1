# pack-voicepack.ps1 —— 把已组装好的音色包目录打成**应用内可导入**的 `.voicepack` 归档
#   （TKN-LVC-016 / ADR-057）
#
# 产物：<KitDir>\build-out\zipvoice\dist\<VoiceId>.voicepack
#   —— 一个 zip，**根即包根**：voicepack.json + model/ + 可选 refs/
#   —— 用户把它传到手机，在 设置 → 语音引擎 → 播报引擎（TTS） → 「导入音色包」选它即可。
#
# 为什么不用 Compress-Archive：
#   本脚本用 System.IO.Compression 逐条 CreateEntry 并**强制以 '/' 作为路径分隔符**。
#   App 侧导入器（LocalVoicePackImporter.isSafeEntryName）为防 Windows 分隔符混淆，
#   会**直接拒绝任何含反斜杠的条目名**；若打包端产出 '\' 分隔的条目，归档会在手机上被
#   整体拒收（失败原因是"包内路径不合法"，对打包者非常不直观）。此处单点消除该风险。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File pack-voicepack.ps1                 # 打包 cyrene-vits
#   powershell -ExecutionPolicy Bypass -File pack-voicepack.ps1 -VoiceId cyrene # 打包 ZipVoice 包
#
# 前置：先跑 build-vits-voicepack.ps1（或 install-local-voicepack.ps1 的组装步骤）产出
#       build-out\zipvoice\voicepack\<VoiceId>\ 。

[CmdletBinding()]
param(
    [string]$VoiceId = "cyrene-vits"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 默认不加载压缩程序集（否则报 `Unable to find type [System.IO.Compression.ZipFile]`）；
# PowerShell 7 已内置，重复加载是 no-op。
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$packDir = Join-Path $kitDir ("build-out\zipvoice\voicepack\" + $VoiceId)

if (-not (Test-Path $packDir)) {
    throw "找不到音色包目录：$packDir（请先运行 build-vits-voicepack.ps1 组装）"
}
$manifestPath = Join-Path $packDir "voicepack.json"
if (-not (Test-Path $manifestPath)) {
    throw "音色包缺少清单：$manifestPath"
}

# 前置自检之一：清单声明的 voiceId 必须与目录名一致
#   （App 侧 LocalVoicePackStore.validate 会判"清单 voiceId 与目录名不一致"⇒ 导入失败，
#    在这里先失败能给出更直白的提示）
$manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.voiceId -ne $VoiceId) {
    throw "清单 voiceId（$($manifest.voiceId)）与目录名（$VoiceId）不一致：App 侧导入会直接失败"
}

$distDir = Join-Path $kitDir "build-out\zipvoice\dist"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$out = Join-Path $distDir ("$VoiceId.voicepack")
if (Test-Path $out) { Remove-Item $out -Force }

$files = Get-ChildItem -Recurse -File $packDir
$zip = [System.IO.Compression.ZipFile]::Open($out, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($packDir.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}

# ---- 产物自检（与 App 侧同口径的最小检查）----
$verify = [System.IO.Compression.ZipFile]::OpenRead($out)
try {
    $names = $verify.Entries | ForEach-Object { $_.FullName }
    # 逐条判定（不要写成管道里的复合布尔式：`-split` 产生的数组在 Where-Object 里恒为真，
    # 会把全部条目误判为非法——本脚本第一版就踩了这个坑）
    $bad = @()
    foreach ($n in $names) {
        if ($n -match '\\' -or $n -match ':' -or ($n.Split('/') -contains '..')) { $bad += $n }
    }
    $hasManifest = $names -contains "voicepack.json"
} finally {
    $verify.Dispose()
}

$sizeMB = (Get-Item $out).Length / 1MB
Write-Host ""
Write-Host "== 归档：$out =="
Write-Host ("  条目 {0} 条 / {1:N1} MB / 根清单 voicepack.json = {2}" -f $names.Count, $sizeMB, $hasManifest)
if (-not $hasManifest) { throw "归档根级缺少 voicepack.json（App 侧会判'清单缺少合法的 voiceId'）" }
if ($bad) { throw "归档含非法条目名（反斜杠/冒号/..）：$($bad -join ', ')" }
Write-Host "  路径分隔符检查通过（全部为 '/'）"
Write-Host ""
Write-Host "下一步：把该文件传到手机 → 设置 → 语音引擎 → 播报引擎（TTS） → 「导入音色包」。"
