# capture-prism-log.ps1 - Capture Prism real-device logs (MIUI-noise / process-restart resistant)
# Usage:
#   .\capture-prism-log.ps1                  # live capture filtered to io.prism's UID (recommended)
#   .\capture-prism-log.ps1 -Duration 600    # stop after N seconds
#   .\capture-prism-log.ps1 -Full            # dump ALL buffers (incl. system noise) as a snapshot
#   .\capture-prism-log.ps1 -OutDir D:\logs  # output directory
#
# Why filter by UID (fixes the two real-device pains):
#  1) MIUI components flood the in-memory ring buffer and evict our logs before they are pulled.
#     Fix: `--uid=<app_uid>` keeps ONLY io.prism's own lines (its GC/ART logs, our Log.* tags,
#          Java FATAL EXCEPTION, native libc lines all carry the same app UID) in scope.
#          No system noise -> buffer can never wrap on MIUI spam.
#  2) Filtering by PID breaks when the process restarts (PID changes).
#     Fix: UID is per-app and STABLE across restarts -> survives process death / relaunch.
#  3) Fatal/native crashes live in crash/system buffers -> use `-b main,crash,system`.
#  4) The on-device buffer is volatile -> stream to a PC file in real time so lines persist on arrival.
#
# Find app UID / check crashes manually:
#   adb shell pm list packages -U io.prism
#   adb logcat -d --uid=<uid> -b main,crash,system
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5 reads .ps1 without BOM as ANSI/GBK,
#       non-ASCII comments get mangled and can inject stray braces -> parse errors.

param(
    [int]$Duration = 0,            # capture seconds; 0 = forever until Ctrl+C
    [string]$OutDir = "$PSScriptRoot\..\..\app\build\prism-log", # log output dir
    [switch]$Full                  # snapshot ALL buffers instead of UID-filtered live capture
)

$ErrorActionPreference = "Continue"
$pkg = "io.prism"

# --- locate adb ---
$envPath = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools",
    "$env:ANDROID_HOME\platform-tools"
)
$adb = $null
foreach ($p in $envPath) { if (Test-Path "$p\adb.exe") { $adb = "$p\adb.exe"; break } }
if (-not $adb) { $which = Get-Command adb -ErrorAction SilentlyContinue; if ($which) { $adb = $which.Source } }
if (-not $adb) { Write-Error "adb not found; set ANDROID_HOME or add adb to PATH"; exit 1 }

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# --- device ---
$devices = & $adb devices 2>$null
$serial = $devices | Select-String "`tdevice$|device$" | ForEach-Object { ($_ -split "`t|\s+")[0] } | Select-Object -First 1
if (-not $serial) { Write-Error "no connected device"; exit 1 }
Write-Host "Device: $serial" -ForegroundColor Cyan

# --- resolve app UID (stable across process restarts) ---
$uid = $null
if (-not $Full) {
    $uidLine = (& $adb -s $serial shell pm list packages -U $pkg 2>$null) -join " "
    if ($uidLine -match "uid[:=](\d+)") { $uid = $matches[1] }
    if (-not $uid) {
        Write-Warning "Cannot resolve UID for $pkg ($uidLine); falling back to tag-filter broad capture."
    }
}

# --- enlarge buffers (best effort; helps $Full and leaves headroom) ---
Write-Host "Enlarge logcat buffer to 16M (best effort)..." -ForegroundColor Yellow
try { & $adb -s $serial logcat -G 16M 2>&1 | Out-Null } catch { Write-Host "  enlarge failed (ignored)" -ForegroundColor DarkGray }

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outFile = Join-Path $OutDir "prism_${stamp}.log"
Write-Host "Log file: $outFile" -ForegroundColor Green

if ($Full) {
    # FULL snapshot: dump all buffers then exit (system noise huge; snapshot only)
    $argsList = @("-s", $serial, "logcat", "-v", "threadtime", "-b", "main,crash,system", "-d")
    $lines = & $adb @argsList 2>&1
    $lines | Set-Content -Path $outFile -Encoding UTF8
    Write-Host "FULL snapshot: $($lines.Count) lines -> $outFile" -ForegroundColor Green
    exit 0
}

if ($uid) {
    # --- UID-filtered live capture (recommended) ---
    $argsList = [System.Collections.Generic.List[string]]::new()
    $argsList.Add("-s"); $argsList.Add($serial)
    $argsList.Add("logcat"); $argsList.Add("-v"); $argsList.Add("threadtime")
    $argsList.Add("-b"); $argsList.Add("main,crash,system")
    $argsList.Add("--uid=$uid")
    Write-Host "Mode: UID=$uid filtered (io.prism only + its crashes), PID-independent" -ForegroundColor Cyan
} else {
    # --- fallback: broad TAG filter (no UID available) ---
    $prismTags = @("PhoneControl","PhoneControlTool","WebSearchTool","LocalMcpToolProvider",
        "SkillExecutor","SkillRegistry","SkillDownloader","ToolCallListConverter","AskUserTool",
        "ConversationViewModel","ConversationScreen","OpenAICompatibleProvider","PrismApplication",
        "CrossSessionMemory","MemoryFts","UserProfileManager","MlKitOcr","KnowledgeBaseTool",
        "DocumentTool","CrossAppLauncher","AppAvailabilityChecker","AppLauncherBridge","SchemeRegistry",
        "ImageEncode","DocumentParse","MemoryMgmtViewModel","SkillsViewModel","PrismVision")
    $parts = @()
    foreach ($t in ($prismTags | Sort-Object -Unique)) { $parts += "$t`:V" }
    $parts += "AndroidRuntime:V"; $parts += "libc:V"; $parts += "*:S"
    $argsList = [System.Collections.Generic.List[string]]::new()
    $argsList.Add("-s"); $argsList.Add($serial)
    $argsList.Add("logcat"); $argsList.Add("-v"); $argsList.Add("threadtime")
    $argsList.Add("-b"); $argsList.Add("main,crash,system")
    foreach ($t in ($parts -join " ") -split " ") { if ($t -ne "") { $argsList.Add($t) } }
    Write-Host "Mode: broad TAG filter (UID unavailable)" -ForegroundColor Cyan
}

Write-Host "Capturing... (Ctrl+C to stop; run the manual test in another window now)" -ForegroundColor Cyan
if ($Duration -gt 0) {
    Write-Host "Will stop automatically after ${Duration}s." -ForegroundColor DarkCyan
    $job = Start-Job -ArgumentList $adb, $argsList, $outFile -ScriptBlock {
        param($a, $al, $out)
        & $a @al | Out-File -FilePath $out -Encoding UTF8 -Append
    }
    Start-Sleep -Seconds $Duration
    Stop-Job $job; Remove-Job $job -Force
} else {
    & $adb @argsList | Out-File -FilePath $outFile -Encoding UTF8 -Append
}
Write-Host "Capture finished. Log saved: $outFile" -ForegroundColor Green