# NapCat 监管公共函数（由 supervisor.ps1 / install.ps1 / status.ps1 共用）
# 约定：
#   - 不输出/不记录 token 明文；HTTP 头里带 hash 或 Bearer；
#   - 所有网络调用有超时，失败返回 $null 而非抛异常（监管循环不能因一次失败退出）。
#
# 注意：本文件必须 UTF-8 **带 BOM** 保存（PS 5.1 否则按 GBK 解码中文注释 → 假语法错误）。

$ErrorActionPreference = 'Stop'

function Get-NapCatPaths {
    param([string]$Root = 'D:\s0611\napcat')
    [pscustomobject]@{
        Root         = $Root
        ComposeFile  = Join-Path $Root 'compose.yaml'
        ConfigDir    = Join-Path $Root 'data\config'
        WebUiConfig  = Join-Path $Root 'data\config\webui.json'
        OneBotConfig = Join-Path $Root 'data\config\onebot11.json'
        LogDir       = Join-Path $Root 'logs'
        StateFile    = Join-Path $Root 'logs\supervisor-state.json'
        CredFile     = Join-Path $Root 'logs\webui-credential.json'
    }
}

function Read-WebUiConfig {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    try { return Get-Content $Path -Raw -Encoding UTF8 | ConvertFrom-Json } catch { return $null }
}

function Write-WebUiConfig {
    param([string]$Path, [object]$Config)
    $json = $Config | ConvertTo-Json -Depth 8
    # NapCat 侧是 Node 读 JSON，必须 UTF-8 **无 BOM**
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Get-NapCatToken {
    param([string]$WebUiConfigPath)
    $cfg = Read-WebUiConfig -Path $WebUiConfigPath
    if ($null -eq $cfg) { return $null }
    if ([string]::IsNullOrWhiteSpace($cfg.token)) { return $null }
    return [string]$cfg.token
}

function Get-NapCatHttpPort {
    param([string]$WebUiConfigPath)
    $cfg = Read-WebUiConfig -Path $WebUiConfigPath
    if ($null -ne $cfg -and $cfg.port) { return [int]$cfg.port }
    return 6099
}

function Get-Sha256Hex {
    param([string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))
        return ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally { $sha.Dispose() }
}

# WebUI 登录 -> 返回 Credential（失败 $null）
#
# 2026-10-26 实测：webui.json 的 loginRate=3（每分钟最多 3 次登录）。巡检任务 +
# install/status 手工调用很容易撞上 "login rate limit"，表现为"登录失败"。
# 因此这里做**凭据缓存**（NapCat 的 Credential 有效期 1 小时）：
#   - 缓存文件 logs\webui-credential.json，含 Credential 与获得时刻；
#   - 50 分钟内直接复用；复用前用一次轻量读接口验证（避免容器重启后失效）。
function Get-WebUiCredential {
    param([int]$Port, [string]$Token, [string]$CacheFile)
    if ([string]::IsNullOrWhiteSpace($Token)) { return $null }

    if (-not [string]::IsNullOrWhiteSpace($CacheFile) -and (Test-Path $CacheFile)) {
        try {
            $cached = Get-Content $CacheFile -Raw -Encoding UTF8 | ConvertFrom-Json
            $ageMin = ((Get-Date) - [datetime]$cached.At).TotalMinutes
            if ($ageMin -ge 0 -and $ageMin -lt 50 -and -not [string]::IsNullOrWhiteSpace($cached.Credential)) {
                $probe = Invoke-WebUiApi -Port $Port -Credential $cached.Credential -Path 'QQLogin/GetQuickLoginList'
                if ($null -ne $probe -and $probe.code -eq 0) { return [string]$cached.Credential }
            }
        } catch { }
    }

    try {
        $hash = Get-Sha256Hex "$Token.napcat"
        $body = @{ hash = $hash } | ConvertTo-Json
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/auth/login" `
            -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 10
        if ($resp.code -ne 0) {
            # 把服务端原因带出去（"login rate limit" 与 "token is invalid" 语义完全不同）
            Write-Verbose ("WebUI 登录失败：{0}" -f $resp.message)
            return $null
        }
        $cred = [string]$resp.data.Credential
        if (-not [string]::IsNullOrWhiteSpace($CacheFile)) {
            try {
                $dir = Split-Path $CacheFile -Parent
                if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
                $payload = @{ Credential = $cred; At = (Get-Date).ToString('o') } | ConvertTo-Json
                [System.IO.File]::WriteAllText($CacheFile, $payload, [System.Text.UTF8Encoding]::new($false))
            } catch { }
        }
        return $cred
    } catch {
        return $null
    }
}

function Invoke-WebUiApi {
    param([int]$Port, [string]$Credential, [string]$Path, [hashtable]$Payload = @{})
    if ([string]::IsNullOrWhiteSpace($Credential)) { return $null }
    try {
        return Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/$Path" -Method Post `
            -Headers @{ Authorization = "Bearer $Credential" } `
            -ContentType 'application/json' -Body ($Payload | ConvertTo-Json -Compress) -TimeoutSec 15
    } catch {
        return $null
    }
}

function Get-OneBotBaseUrl {
    param([string]$OneBotConfigPath)
    if (-not (Test-Path $OneBotConfigPath)) { return $null }
    try {
        $cfg = Get-Content $OneBotConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $srv = $cfg.network.httpServers | Where-Object { $_.enable -eq $true } | Select-Object -First 1
        if ($null -eq $srv) { return $null }
        return [pscustomobject]@{
            BaseUrl = "http://127.0.0.1:$([int]$srv.port)"
            Token   = [string]$srv.token
        }
    } catch { return $null }
}

# OneBot 调用（返回原始响应对象，失败 $null）
function Invoke-OneBot {
    param([string]$BaseUrl, [string]$Token, [string]$Action, [hashtable]$Payload = @{})
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return $null }
    try {
        $headers = @{}
        if (-not [string]::IsNullOrWhiteSpace($Token)) { $headers['Authorization'] = "Bearer $Token" }
        return Invoke-RestMethod -Uri "$BaseUrl/$Action" -Method Post -Headers $headers `
            -ContentType 'application/json' -Body ($Payload | ConvertTo-Json -Compress) -TimeoutSec 10
    } catch { return $null }
}

# 返回 $true=在线 / $false=账号离线 / $null=协议端不可达（三态必须区分，否则"离线"会被当成"没启动"反复重启）
function Test-QqOnline {
    param([string]$BaseUrl, [string]$Token)
    $r = Invoke-OneBot -BaseUrl $BaseUrl -Token $Token -Action 'get_status'
    if ($null -eq $r -or $r.retcode -ne 0) { return $null }
    return [bool]$r.data.online
}

function Write-SupervisorLog {
    param([string]$LogDir, [string]$Message)
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $file = Join-Path $LogDir ("supervisor-{0}.log" -f (Get-Date -Format 'yyyyMMdd'))
    $line = "{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $file -Value $line -Encoding UTF8
    Write-Host $line
    # 只保留最近 14 天
    Get-ChildItem $LogDir -Filter 'supervisor-*.log' |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

function Read-SupervisorState {
    param([string]$StateFile)
    # 字段必须与 supervisor.ps1 读写的一致；漏一个字段会让"缺失即跳过"的保护失效
    # （2026-10-26 实测：漏了 LastRestartAt ⇒ 10 分钟重启冷却形同虚设）。
    $empty = [pscustomobject]@{
        HourKey       = ''
        Restarts      = 0
        LastOnlineAt  = ''
        LastRecoverAt = ''
        LastRestartAt = ''
    }
    if (-not (Test-Path $StateFile)) { return $empty }
    try {
        $s = Get-Content $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        return [pscustomobject]@{
            HourKey       = [string]$s.HourKey
            Restarts      = [int]$s.Restarts
            LastOnlineAt  = [string]$s.LastOnlineAt
            LastRecoverAt = [string]$s.LastRecoverAt
            LastRestartAt = [string]$s.LastRestartAt
        }
    } catch {
        return $empty
    }
}

function Save-SupervisorState {
    param([string]$StateFile, [object]$State)
    $dir = Split-Path $StateFile -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($StateFile, ($State | ConvertTo-Json -Depth 5),
        [System.Text.UTF8Encoding]::new($false))
}

# 快登凭据是否可用（2026-10-26 实测新增）
#
# 背景：`autoLoginAccount` 只决定"登录哪个号"，真正免扫码靠 QQ 客户端保存的**快登会话**
# （`GetQuickLoginList`）。被 QQ 踢下线（KickedOffLine / 身份失效）后这份会话会被清空
# （实测：踢下线前列表有 3904755943，踢下线并重新扫码后列表变空）。
# 此时再重启容器只会弹二维码 —— 白折腾且加重风控，所以要先查再决定要不要重启。
#
# @return $true=有快登凭据（重启免扫码）；$false=无凭据（重启也必须扫码）；$null=查询失败
function Test-QuickLoginAvailable {
    param([int]$Port, [string]$Credential)
    if ([string]::IsNullOrWhiteSpace($Credential)) { return $null }
    $resp = Invoke-WebUiApi -Port $Port -Credential $Credential -Path 'QQLogin/GetQuickLoginList'
    if ($null -eq $resp -or $resp.code -ne 0) { return $null }
    $list = @($resp.data) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    return ($list.Count -gt 0)
}
