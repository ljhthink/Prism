# 昔涟官方语音文本抽取（parse-wiki-vo-lines.ps1）
# 用途：从 biligame 星铁 WIKI「昔涟/语音」页 wikitext 抽取「语音类型 / 语音文件 / 语音内容」三元组，
#       产出 cyrene-voice-lines.json（本地参考库的权威台词来源，供 ZipVoice 参考音频转写使用）。
#
# 为什么要权威文本：ZipVoice（零样本克隆 TTS）要求 reference_audio 与 reference_text **逐字对齐**——
# 文本与音频不一致会直接导致克隆相似度退化（官方文档明确要求精确转写）。游戏官方台词是唯一可信来源。
#
# 用法（在 voice-clone-kit\ 目录）：
#   powershell -ExecutionPolicy Bypass -File parse-wiki-vo-lines.ps1
# 若无 cyrene-vo-wikitext.json 则自动联网抓取（biligame WIKI api.php，非 HTML，无需爬虫）。
# 依赖：curl.exe（Windows 10+ 自带）、PowerShell 5.1+。

$ErrorActionPreference = "Stop"
$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rawJson = Join-Path $kitDir "cyrene-vo-wikitext.json"
$outJson = Join-Path $kitDir "cyrene-voice-lines.json"

$apiUrl = "https://wiki.biligame.com/sr/api.php?action=parse&page=%E6%98%94%E6%B6%9F%2F%E8%AF%AD%E9%9F%B3&prop=wikitext&format=json&formatversion=2"

if (-not (Test-Path $rawJson)) {
    Write-Host "抓取 WIKI wikitext -> $rawJson"
    curl.exe -sS -o $rawJson $apiUrl
    if ($LASTEXITCODE -ne 0) { Write-Error "抓取失败：$apiUrl"; exit 1 }
}

$page = Get-Content $rawJson -Raw -Encoding UTF8 | ConvertFrom-Json
$wt = $page.parse.wikitext
if ([string]::IsNullOrWhiteSpace($wt)) { Write-Error "wikitext 为空——页面结构可能已变更"; exit 1 }

# {{角色语音 ... }} 块解析：类型 / 语音文件 / 中文语音内容
$blocks = [regex]::Matches($wt, '\{\{角色语音(?<body>[\s\S]*?)\n\}\}')
$lines = @()
foreach ($b in $blocks) {
    $body = $b.Groups['body'].Value
    $type = ([regex]::Match($body, '\|语音类型=(?<v>[^\r\n|]*)')).Groups['v'].Value.Trim()
    $file = ([regex]::Match($body, '\|语音文件=(?<v>[^\r\n|]*)')).Groups['v'].Value.Trim()
    $text = ([regex]::Match($body, '\|语音内容=(?<v>[^\r\n|]*)')).Groups['v'].Value.Trim()
    if ($type -eq "") { continue }
    $lines += [pscustomobject]@{
        语音类型 = $type
        语音文件 = $file
        语音内容 = $text
        有文本   = -not [string]::IsNullOrWhiteSpace($text)
    }
}

$payload = [pscustomobject]@{
    source   = "https://wiki.biligame.com/sr/昔涟/语音"
    fetchedAt = (Get-Date -Format "yyyy-MM-dd")
    total    = $lines.Count
    withText = ($lines | Where-Object { $_.有文本 }).Count
    lines    = $lines
}
$payload | ConvertTo-Json -Depth 5 | Set-Content -Path $outJson -Encoding UTF8

Write-Host ("抽取完成：{0} 条语音，其中带中文文本 {1} 条 -> {2}" -f $payload.total, $payload.withText, $outJson)
$lines | Where-Object { $_.有文本 } | Select-Object -First 8 | Format-Table -AutoSize | Out-String -Width 200
