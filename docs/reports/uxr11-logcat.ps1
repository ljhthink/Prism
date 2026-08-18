# uxr11-logcat.ps1 —— Prism UXR11 真机日志捕获脚本（解决 PID 过滤失效问题）
#
# 用法（PowerShell）：
#   .\docs\reports\uxr11-logcat.ps1 start            # 清空并开始全量连续捕获（自动选真机）
#   .\docs\reports\uxr11-logcat.ps1 start -Serial 65ed68d
#   .\docs\reports\uxr11-logcat.ps1 stop             # 停止捕获并打印关键摘要
#   .\docs\reports\uxr11-logcat.ps1 analyze          # 不停止，直接分析已捕获文件
#
# 为什么不用 `adb logcat --pid=<pid>`：
#   1. 应用崩溃/被杀 → PID 消失 → 该 PID 的过滤结果为空，看不到 FATAL 崩溃栈
#   2. 应用重启 → 新 PID → 旧过滤失效
#   3. `-d` dump + pid 在事后分析时同样无效
#   本脚本改为【全量连续捕获到文件】：
#   - 无 PID 过滤（应用崩溃/重启都完整落盘）
#   - 多缓冲 -b main -b crash -b system（崩溃日志在 crash 缓冲永不被冲掉）
#   - -v threadtime 保留时间戳+线程，便于崩溃前后对齐
#   - Start-Process 重定向写原始字节（避免 PowerShell `>` 产生 UTF-16 乱码）
#   事后用 grep 按包名/tag/崩溃标记过滤，任何 PID 变化都不影响分析。

param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "analyze")]
    [string]$Action = "start",

    [string]$Serial = $null,

    # 输出目录
    [string]$OutDir = "docs\reports\logs"
)

$pidFile = "app\build\uxr11_logcat_pid.txt"
$logMarker = "app\build\uxr11_logcat_path.txt"

function Get-RealDeviceSerial {
    $devices = adb devices | Select-String -Pattern "^\S+\s+device$"
    foreach ($line in $devices) {
        $serial = ($line -split "\s+")[0]
        if ($serial -notmatch "^emulator-") { return $serial }
    }
    # 无真机则退回第一个 device（含模拟器）
    if ($devices) { return (($devices[0] -split "\s+")[0]) }
    throw "未检测到已连接的设备（adb devices 为空）"
}

if (-not $Serial) { $Serial = Get-RealDeviceSerial }
Write-Host "目标设备: $Serial" -ForegroundColor Cyan

switch ($Action) {
    "start" {
        # 若已有捕获在运行则提示
        if (Test-Path $pidFile) {
            $oldPid = [int](Get-Content $pidFile)
            if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
                Write-Host "已有捕获正在运行 (pid=$oldPid)。先执行 stop 再 start。" -ForegroundColor Yellow
                exit 1
            }
        }
        New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
        $logFile = Join-Path $OutDir "uxr11-realdevice-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"
        Write-Host "清空 logcat ..." -ForegroundColor Cyan
        adb -s $Serial logcat -c
        Write-Host "启动全量捕获（无 PID 过滤，含 main/crash/system 缓冲）..." -ForegroundColor Cyan
        $p = Start-Process -FilePath "adb" -ArgumentList "-s", $Serial, "logcat", "-v", "threadtime", "-b", "main", "-b", "crash", "-b", "system" `
            -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -PassThru -NoNewWindow
        $p.Id | Out-File $pidFile
        $logFile | Out-File $logMarker
        Write-Host "捕获已开始:" -ForegroundColor Green
        Write-Host "  PID : $($p.Id)"
        Write-Host "  文件: $logFile"
        Write-Host "现在进行手动测试。测试结束后运行: .\docs\reports\uxr11-logcat.ps1 stop" -ForegroundColor Yellow
    }
    "stop" {
        if (-not (Test-Path $pidFile)) { Write-Host "没有正在运行的捕获。" -ForegroundColor Yellow; exit 1 }
        $pidVal = [int](Get-Content $pidFile)
        $logFile = Get-Content $logMarker
        if (Get-Process -Id $pidVal -ErrorAction SilentlyContinue) {
            Stop-Process -Id $pidVal -Force
            Write-Host "已停止捕获 (pid=$pidVal)" -ForegroundColor Green
        } else {
            Write-Host "捕获进程已不在运行（可能已被结束）。" -ForegroundColor Yellow
        }
        Remove-Item $pidFile, $logMarker -ErrorAction SilentlyContinue
        if (Test-Path $logFile) {
            $size = (Get-Item $logFile).Length
            Write-Host "日志文件: $logFile ($([Math]::Round($size/1KB)) KB)" -ForegroundColor Green
            # 打印关键标记（崩溃 / Prism 相关 / 429 重试 / L2 记忆 / Fetch）
            Write-Host "===== 崩溃标记 =====" -ForegroundColor Magenta
            Select-String -Path $logFile -Pattern "FATAL EXCEPTION|AndroidRuntime|Process: io.prism|FATAL" | Select-Object -Last 5
            Write-Host "===== Prism 相关（最近 40 行）=====" -ForegroundColor Magenta
            Select-String -Path $logFile -Pattern "io\.prism|SkillExecutor|LocalMcpToolProvider|ConversationViewModel|CrossSessionMemoryManager|OpenAIProvider|WebSearch" | Select-Object -Last 40
        }
    }
    "analyze" {
        $logFile = if (Test-Path $logMarker) { Get-Content $logMarker } else { Get-ChildItem $OutDir -Filter "uxr11-realdevice-*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName }
        if (-not $logFile -or -not (Test-Path $logFile)) { Write-Host "未找到日志文件。" -ForegroundColor Yellow; exit 1 }
        Write-Host "分析文件: $logFile" -ForegroundColor Cyan
        Write-Host "===== 崩溃标记 =====" -ForegroundColor Magenta
        Select-String -Path $logFile -Pattern "FATAL EXCEPTION|Process: io.prism|FATAL" | Select-Object -Last 5
        Write-Host "===== Prism 相关 =====" -ForegroundColor Magenta
        Select-String -Path $logFile -Pattern "io\.prism|SkillExecutor|LocalMcpToolProvider|ConversationViewModel|CrossSessionMemoryManager|OpenAIProvider|WebSearch" | Select-Object -Last 60
    }
}
