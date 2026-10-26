# NapCat 持久化安装/卸载（一次性）
#
# 做的事（全部可回滚）：
#   1. webui.json 写入 autoLoginAccount=<QQ>（NapCat 重启时据此触发快速登录）
#   2. 调 WebUI SetQuickLogin 固化
#   3. com.docker.service 启动类型改 Automatic 并启动（管理员权限需要）
#   4. 创建计划任务 Prism-NapCat-Supervisor（用户登录时启动，每 2 分钟巡检一次）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Qq 3904755943
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall

param(
    [string]$Root = 'D:\s0611\napcat',
    [string]$Qq = '',
    [switch]$Uninstall,
    [switch]$SkipDockerAutostart
)

. (Join-Path $PSScriptRoot 'lib.ps1')

$TASK_NAME = 'Prism-NapCat-Supervisor'
$paths = Get-NapCatPaths -Root $Root
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

# ==================== 卸载 ====================
if ($Uninstall) {
    Write-Host '== 卸载 NapCat 持久化 =='
    schtasks /Delete /TN $TASK_NAME /F 2>$null | Out-Null
    Write-SupervisorLog -LogDir $paths.LogDir -Message '已删除计划任务'

    if ($isAdmin) {
        try {
            Set-Service -Name 'com.docker.service' -StartupType Manual
            Write-Host 'Docker 服务已改回 Manual'
        } catch { Write-Warning "改回 Docker 启动类型失败：$($_.Exception.Message)" }
    } else {
        Write-Warning '非管理员：跳过 Docker 启动类型还原（可手动在 services.msc 改回 Manual）'
    }

    $cfg = Read-WebUiConfig -Path $paths.WebUiConfig
    if ($null -ne $cfg) {
        $cfg.PSObject.Properties.Remove('autoLoginAccount')
        Write-WebUiConfig -Path $paths.WebUiConfig -Config $cfg
        Write-Host '已从 webui.json 移除 autoLoginAccount'
    }
    Write-Host '== 卸载完成（QQ 登录态与数据目录未改动）=='
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Qq)) {
    Write-Error '缺少 -Qq <小号QQ号>。示例：install.ps1 -Qq 3904755943'
    exit 1
}

Write-Host "== 安装 NapCat 持久化（QQ=$Qq）=="

# ---------- 1. webui.json ----------
if (-not (Test-Path $paths.WebUiConfig)) {
    Write-Error "找不到 $($paths.WebUiConfig)，请先确认 NapCat 容器已启动过并生成配置"
    exit 1
}
$cfg = Read-WebUiConfig -Path $paths.WebUiConfig
$cfg | Add-Member -NotePropertyName 'autoLoginAccount' -NotePropertyValue $Qq -Force
Write-WebUiConfig -Path $paths.WebUiConfig -Config $cfg
Write-Host '[1/4] webui.json 已写入 autoLoginAccount'

# ---------- 2. SetQuickLogin ----------
$port = Get-NapCatHttpPort -WebUiConfigPath $paths.WebUiConfig
$token = Get-NapCatToken -WebUiConfigPath $paths.WebUiConfig
$cred = Get-WebUiCredential -Port $port -Token $token -CacheFile $paths.CredFile
if ($null -eq $cred) {
    Write-Warning '[2/4] WebUI 登录失败（容器可能未运行）；跳过 SetQuickLogin，仅依赖配置项'
} else {
    # 2026-10-26 实测：账号在线时 SetQuickLogin 会返回 "QQ Is Logined"（该接口用于写入快登凭据）。
    # 真正决定"重启 Worker 时自动登录哪个号"的是 SetQuickLoginQQ（= autoLoginAccount）。
    $auto1 = Invoke-WebUiApi -Port $port -Credential $cred -Path 'QQLogin/SetQuickLoginQQ' -Payload @{ uin = $Qq }
    if ($null -ne $auto1 -and $auto1.code -eq 0) {
        Write-Host '[2/4] 自动登录账号已固化（autoLoginAccount）'
    } else {
        Write-Warning "[2/4] SetQuickLoginQQ 失败：$($auto1 | ConvertTo-Json -Compress -Depth 3)"
    }
    # 顺带尝试写入快登凭据；账号在线时失败属正常，仅提示不阻断
    $resp = Invoke-WebUiApi -Port $port -Credential $cred -Path 'QQLogin/SetQuickLogin' -Payload @{ uin = $Qq }
    if ($null -ne $resp -and $resp.code -ne 0) {
        Write-Host ("      快登凭据未写入（{0}；账号在线时属正常）" -f $resp.message)
    }
    $auto = Invoke-WebUiApi -Port $port -Credential $cred -Path 'QQLogin/GetQuickLoginQQ'
    if ($null -ne $auto) { Write-Host ("      当前自动登录账号：'{0}'" -f $auto.data) }
}

# ---------- 3. Docker 服务自启 ----------
if ($SkipDockerAutostart) {
    Write-Host '[3/4] 跳过 Docker 自启（按要求）'
} elseif (-not $isAdmin) {
    Write-Warning '[3/4] 非管理员：跳过 Docker 启动类型修改。请用管理员重跑，或手动把 com.docker.service 设为自动'
} else {
    try {
        Set-Service -Name 'com.docker.service' -StartupType Automatic
        Start-Service -Name 'com.docker.service' -ErrorAction SilentlyContinue
        Write-Host '[3/4] Docker 服务已设为自动并尝试启动'
    } catch {
        Write-Warning "[3/4] Docker 服务设置失败：$($_.Exception.Message)"
    }
}

# ---------- 4. 计划任务 ----------
$action = "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\supervisor.ps1`" -Root `"$Root`" -Quiet"
# 用 cmd 重定向吞掉"任务不存在"的正常噪声（PS 5.1 下 2>$null 仍会因
# $ErrorActionPreference='Stop' 变成终止性错误）
cmd /c "schtasks /Delete /TN $TASK_NAME /F >nul 2>&1" | Out-Null
cmd /c "schtasks /Create /TN $TASK_NAME /TR ""$action"" /SC MINUTE /MO 2 /RL LIMITED /F >nul 2>&1" | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host '[4/4] 计划任务已创建（每 2 分钟巡检；仅在用户登录后运行）'
} else {
    Write-Warning '[4/4] 计划任务创建失败，请检查任务计划程序权限'
}

Write-Host ''
Write-Host "完成。查看状态：powershell -ExecutionPolicy Bypass -File `"$PSScriptRoot\status.ps1`""
Write-Host '卸载： install.ps1 -Uninstall'
