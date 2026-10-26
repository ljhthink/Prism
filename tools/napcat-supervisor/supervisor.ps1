# NapCat 自愈监管：QQ 离线 → RestartNapCat（触发快速登录）→ 必要时重启容器
#
# 设计约束（来自真机取证）：
#   1. 账号态会周期性失效（ErrCode 3），NapCat 自带"快速登录"可在重启 Worker 后恢复；
#   2. 但 webui.json 的 autoLoginAccount 为空时，快登不会被触发 → 必须先由 install.ps1 写入；
#   3. 不能无限重启（会加重风控）→ 每小时最多 3 次，超限只告警；
#   4. 脚本必须能在"没有任何 token / 容器没起"的情况下安静退出，不能弹窗、不能阻塞计划任务。
#
# 用法：powershell -ExecutionPolicy Bypass -File supervisor.ps1 [-Root D:\s0611\napcat] [-Quiet]

param(
    [string]$Root = 'D:\s0611\napcat',
    [switch]$Quiet
)

. (Join-Path $PSScriptRoot 'lib.ps1')

$paths = Get-NapCatPaths -Root $Root
$state = Read-SupervisorState -StateFile $paths.StateFile
$hourKey = Get-Date -Format 'yyyyMMddHH'

# 跨小时 → 重置重启配额
if ($state.HourKey -ne $hourKey) {
    $state = [pscustomobject]@{
        HourKey = $hourKey; Restarts = 0
        LastOnlineAt = $state.LastOnlineAt; LastRecoverAt = $state.LastRecoverAt
    }
}

$oneBot = Get-OneBotBaseUrl -OneBotConfigPath $paths.OneBotConfig
if ($null -eq $oneBot) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message "未找到 OneBot HTTP 配置，跳过（$($paths.OneBotConfig)）"
    exit 0
}

$online = Test-QqOnline -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token
if ($null -eq $online) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message '协议端不可达（HTTP 无响应），尝试启动容器'
    $null = docker start napcat 2>&1
    Start-Sleep -Seconds 20
    $online = Test-QqOnline -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token
}

if ($online -eq $true) {
    $state.LastOnlineAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    Save-SupervisorState -StateFile $paths.StateFile -State $state
    if (-not $Quiet) { Write-SupervisorLog -LogDir $paths.LogDir -Message 'QQ 在线，无需处理' }
    exit 0
}

# ===== 离线：尝试自愈 =====
Write-SupervisorLog -LogDir $paths.LogDir -Message "检测到 QQ 离线（本小时已重启 $($state.Restarts) 次）"

if ($state.Restarts -ge 3) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message `
        '本小时重启已达 3 次上限，停止自动重启（可能需要在 PC 上手动扫码登录）'
    exit 0
}

$port = Get-NapCatHttpPort -WebUiConfigPath $paths.WebUiConfig
$token = Get-NapCatToken -WebUiConfigPath $paths.WebUiConfig
$cred = Get-WebUiCredential -Port $port -Token $token -CacheFile $paths.CredFile

if ($null -ne $cred) {
    # 优先：RestartNapCat（重启 Worker → 触发快速登录）
    $resp = Invoke-WebUiApi -Port $port -Credential $cred -Path 'QQLogin/RestartNapCat'
    if ($null -ne $resp -and $resp.code -eq 0) {
        Write-SupervisorLog -LogDir $paths.LogDir -Message 'RestartNapCat 已发起，等待快登…'
    } else {
        Write-SupervisorLog -LogDir $paths.LogDir -Message 'RestartNapCat 调用失败，改为重启容器'
    }
} else {
    Write-SupervisorLog -LogDir $paths.LogDir -Message 'WebUI 登录失败（token 可能已变更），直接重启容器'
}

# 等待恢复（最多 120s）
$recovered = $false
for ($i = 1; $i -le 12; $i++) {
    Start-Sleep -Seconds 10
    if ((Test-QqOnline -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token) -eq $true) {
        $recovered = $true
        break
    }
}

if (-not $recovered) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message '仍未恢复，重启容器 napcat'
    $null = docker restart napcat 2>&1
    for ($i = 1; $i -le 12; $i++) {
        Start-Sleep -Seconds 10
        if ((Test-QqOnline -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token) -eq $true) {
            $recovered = $true
            break
        }
    }
}

$state.Restarts = $state.Restarts + 1
if ($recovered) {
    $state.LastRecoverAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    $state.LastOnlineAt = $state.LastRecoverAt
    Write-SupervisorLog -LogDir $paths.LogDir -Message '已恢复在线'
} else {
    Write-SupervisorLog -LogDir $paths.LogDir -Message '本轮未能恢复（可能需要人工扫码）'
}
Save-SupervisorState -StateFile $paths.StateFile -State $state
