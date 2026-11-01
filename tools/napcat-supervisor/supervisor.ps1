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

# ===== 2026-10-31 起：PC 协议端已停用，改用「手机本机模式」（ADR-069）=====
# 原因：NapCat 与 Prism 都装在旧手机里，通过 127.0.0.1 通信，不再需要 PC 上的容器。
# 若本监管继续每 2 分钟拉起容器，会与手机 NapCat **抢同一 QQ 账号**，
# 表现为反复 `[KickedOffLine] 您的账号已在另一台终端登录`（真机实测两次）。
# 因此这里直接静默退出：不再启动、不再重连。
#
# 恢复 PC 模式：把下面这行改成 $false（并确保手机侧 final.sh 已 CTRL+C 停止）。
$PcProtocolRetired = $true
if ($PcProtocolRetired) {
    if (-not $Quiet) {
        Write-Output 'NapCat supervisor skipped: PC protocol retired (on-phone mode, ADR-069)'
    }
    exit 0
}

. (Join-Path $PSScriptRoot 'lib.ps1')

$paths = Get-NapCatPaths -Root $Root
$state = Read-SupervisorState -StateFile $paths.StateFile
$hourKey = Get-Date -Format 'yyyyMMddHH'

# 跨小时 → 重置重启配额（Last* 字段必须原样带过去，否则冷却判定永远读到空值）
if ($state.HourKey -ne $hourKey) {
    $state = [pscustomobject]@{
        HourKey       = $hourKey
        Restarts      = 0
        LastOnlineAt  = $state.LastOnlineAt
        LastRecoverAt = $state.LastRecoverAt
        LastRestartAt = $state.LastRestartAt
    }
}

# ===== 重启冷却（**必须最先判定**）=====
# 2026-10-26 实测教训：短时间连续重启会让 QQ 直接下发 KickedOffLine
# （"登录已失效"），随后快速登录返回"用户身份已失效"，只能人工扫码。
# 因此重启是**慢动作**：任意两次重启之间至少间隔 10 分钟。
# 放在最前面是因为"协议端不可达"分支也会拉起容器——同样算一次重启。
$lastRestartAt = $null
if (-not [string]::IsNullOrWhiteSpace($state.LastRestartAt)) {
    try { $lastRestartAt = [datetime]$state.LastRestartAt } catch { }
}
if ($null -ne $lastRestartAt) {
    $sinceMin = ((Get-Date) - $lastRestartAt).TotalMinutes
    if ($sinceMin -ge 0 -and $sinceMin -lt 10) {
        Write-SupervisorLog -LogDir $paths.LogDir -Message `
            ("冷却中：距上次重启仅 {0:N0} 分钟（下限 10 分钟），本轮不动作" -f $sinceMin)
        exit 0
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
    $state.Restarts = $state.Restarts + 1
    $state | Add-Member -NotePropertyName LastRestartAt -NotePropertyValue ((Get-Date).ToString('yyyy-MM-dd HH:mm:ss')) -Force
    Save-SupervisorState -StateFile $paths.StateFile -State $state
    Start-Sleep -Seconds 20
    $online = Test-QqOnline -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token
}

if ($online -eq $true) {
    # 心跳可验证性：-Quiet 下不刷屏，但**每小时至少留一行**，
    # 否则"任务到底有没有在跑"在日志里无法回答（status.ps1 只能靠猜）。
    $prevHour = ''
    if (-not [string]::IsNullOrWhiteSpace($state.LastOnlineAt)) {
        try { $prevHour = ([datetime]$state.LastOnlineAt).ToString('yyyyMMddHH') } catch { }
    }
    $state.LastOnlineAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    Save-SupervisorState -StateFile $paths.StateFile -State $state
    if ((-not $Quiet) -or ($prevHour -ne $hourKey)) {
        Write-SupervisorLog -LogDir $paths.LogDir -Message 'QQ 在线，无需处理'
    }
    exit 0
}

# ===== 离线：尝试自愈 =====
Write-SupervisorLog -LogDir $paths.LogDir -Message "检测到 QQ 离线（本小时已重启 $($state.Restarts) 次）"

if ($state.Restarts -ge 3) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message `
        '本小时重启已达 3 次上限，停止自动重启（可能需要在 PC 上手动扫码登录）'
    exit 0
}

# 2026-10-26 实测教训：**短时间连续重启会让 QQ 判定"登录已失效"**——
# 21:50 与 21:51 两次重启后，21:55:59 QQ 直接下发 KickedOffLine，
# 随后快速登录返回"用户身份已失效，为保证账号安全，请你重新登录"（必须人工扫码）。
# 因此加**最小重启间隔**（10 分钟）：重启是有代价的动作，不是重试手段。
$lastRestartAt = $null
if (-not [string]::IsNullOrWhiteSpace($state.LastRestartAt)) {
    try { $lastRestartAt = [datetime]$state.LastRestartAt } catch { }
}
if ($null -ne $lastRestartAt) {
    $sinceMin = ((Get-Date) - $lastRestartAt).TotalMinutes
    if ($sinceMin -ge 0 -and $sinceMin -lt 10) {
        Write-SupervisorLog -LogDir $paths.LogDir -Message `
            ("距上次重启仅 {0:N0} 分钟（下限 10 分钟），本轮不动作，避免触发 QQ 登录失效" -f $sinceMin)
        exit 0
    }
}

# 若上一次重启后仍未恢复，且已经等待足够久 → 基本可判定"身份失效，需人工扫码"，
# 继续重启只会加重风控，改为只告警。
if ($state.Restarts -ge 2 -and -not [string]::IsNullOrWhiteSpace($state.LastRestartAt)) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message `
        '连续 2 次重启仍未恢复，疑似「登录身份已失效」——请在 http://127.0.0.1:6099/webui 扫码登录（本小时不再自动重启）'
    exit 0
}

$port = Get-NapCatHttpPort -WebUiConfigPath $paths.WebUiConfig
$token = Get-NapCatToken -WebUiConfigPath $paths.WebUiConfig
$cred = Get-WebUiCredential -Port $port -Token $token -CacheFile $paths.CredFile

# 2026-10-26 实测：被 QQ 踢下线后**快登会话会被清空**（踢前有 3904755943，重扫码后为空），
# 此时重启必然弹二维码 ⇒ 免扫码失效。先查凭据：没有就只告警、不重启
# （重启只会让"需要扫码"更晚被发现，还加重风控）。
$quickLogin = Test-QuickLoginAvailable -Port $port -Credential $cred
if ($quickLogin -eq $false) {
    Write-SupervisorLog -LogDir $paths.LogDir -Message `
        'QQ 离线且无快登凭据（曾被踢下线）——重启无用，请打开 http://127.0.0.1:6099/webui 扫码登录'
    exit 0
}

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
$state | Add-Member -NotePropertyName LastRestartAt -NotePropertyValue ((Get-Date).ToString('yyyy-MM-dd HH:mm:ss')) -Force
if ($recovered) {
    $state.LastRecoverAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    $state.LastOnlineAt = $state.LastRecoverAt
    Write-SupervisorLog -LogDir $paths.LogDir -Message '已恢复在线'
} else {
    Write-SupervisorLog -LogDir $paths.LogDir -Message '本轮未能恢复（可能需要人工扫码）'
}
Save-SupervisorState -StateFile $paths.StateFile -State $state
