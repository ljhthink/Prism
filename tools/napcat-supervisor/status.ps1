# NapCat 状态速查
#
# 用法：powershell -ExecutionPolicy Bypass -File status.ps1 [-Root D:\s0611\napcat]

param([string]$Root = 'D:\s0611\napcat')

. (Join-Path $PSScriptRoot 'lib.ps1')

$paths = Get-NapCatPaths -Root $Root

Write-Host '==== NapCat 状态 ===='

# 容器
try {
    $ps = docker ps --filter 'name=^napcat$' --format '{{.Status}}' 2>&1
    if ([string]::IsNullOrWhiteSpace($ps)) { Write-Host '容器：未运行' }
    else { Write-Host "容器：$ps" }
} catch { Write-Host '容器：docker 不可用' }

# QQ 在线
$oneBot = Get-OneBotBaseUrl -OneBotConfigPath $paths.OneBotConfig
if ($null -eq $oneBot) {
    Write-Host "QQ：无法读取 OneBot 配置（$($paths.OneBotConfig)）"
} else {
    $status = Invoke-OneBot -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token -Action 'get_status'
    $login = Invoke-OneBot -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token -Action 'get_login_info'
    $ver = Invoke-OneBot -BaseUrl $oneBot.BaseUrl -Token $oneBot.Token -Action 'get_version_info'
    if ($null -eq $status -or $status.retcode -ne 0) {
        Write-Host 'QQ：协议端不可达'
    } elseif (-not $status.data.online) {
        Write-Host 'QQ：离线（需要重新登录）'
    } else {
        $nick = $login.data.nickname
        $uid = $login.data.user_id
        $v = $ver.data.app_version
        Write-Host "QQ：在线（$uid $nick） NapCat $v"
    }
}

# WebUI：快登列表 / 自动登录
$port = Get-NapCatHttpPort -WebUiConfigPath $paths.WebUiConfig
$token = Get-NapCatToken -WebUiConfigPath $paths.WebUiConfig
$cred = Get-WebUiCredential -Port $port -Token $token -CacheFile $paths.CredFile
if ($null -eq $cred) {
    Write-Host 'WebUI：登录失败（token 变更或容器未运行）'
} else {
    $list = Invoke-WebUiApi -Port $port -Credential $cred -Path 'QQLogin/GetQuickLoginList'
    $auto = Invoke-WebUiApi -Port $port -Credential $cred -Path 'QQLogin/GetQuickLoginQQ'
    if ($null -ne $list) { Write-Host ("快登列表：{0}" -f ([string]($list.data -join ","))) }
    if ($null -ne $auto) {
        if ([string]::IsNullOrWhiteSpace([string]$auto.data)) {
            Write-Host '自动登录：未设置（掉线后需扫码；运行 install.ps1 可修复）'
        } else {
            Write-Host ("自动登录：{0}" -f ([string]$auto.data))
        }
    }
}

# 计划任务
$task = schtasks /Query /TN 'Prism-NapCat-Supervisor' /FO LIST 2>$null
if ($LASTEXITCODE -eq 0) {
    $next = ($task | Select-String -Pattern '下次运行时间|Next Run Time' | Select-Object -First 1).Line
    Write-Host "计划任务：已安装  $next"
} else {
    Write-Host '计划任务：未安装'
}

# Docker 服务启动类型
try {
    $svc = Get-Service -Name 'com.docker.service' -ErrorAction Stop
    Write-Host ("Docker 服务：{0} / {1}" -f $svc.Status, $svc.StartType)
} catch { Write-Host 'Docker 服务：未安装' }

# 最近日志
$log = Get-ChildItem $paths.LogDir -Filter 'supervisor-*.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -ne $log) {
    Write-Host ''
    Write-Host "==== 最近日志（$($log.Name)）===="
    Get-Content $log.FullName -Tail 15
} else {
    Write-Host ''
    Write-Host '暂无监管日志（计划任务尚未运行过）'
}
