# prepare-vits-toolchain.ps1 —— 取「中文 VITS 微调」工具链与底模（TKN-LVC-014 / 方案 §B.2 步骤 2）
#
# 产物（全部落在 voice-clone-kit\build-out\vits\toolchain\ 下；voice-clone-kit\ 整体被 .gitignore 排除）：
#   build-out\vits\toolchain\sherpa-onnx-vits-fine-tuning-webui\   ANONEKO 工具链（MIT）
#   build-out\vits\toolchain\...\pretrained_models\{G_0,D_0}.pth    底模（默认取 **中文单语 C 模型**）
#   build-out\vits\toolchain\...\configs\finetune_speaker.json      底模配套 config
#   build-out\vits\toolchain\prepare-log.txt                        逐步日志（可核验）
#
# 底模候选（依仓库内 `vits_fast_fine_tuning/LOCAL.md` 原文，按"中文优先 + 镜像优先"排序）：
#   C   中文单语 : https://huggingface.co/datasets/Plachta/sampled_audio4ft/resolve/main/VITS-Chinese/
#   CJE 中日英   : https://huggingface.co/spaces/Plachta/VITS-Umamusume-voice-synthesizer/resolve/main/
# 选 C 的理由：中文角色不需要日/英前端；路径在 datasets 仓库（hf-mirror 对 datasets 有镜像，
# spaces 无镜像 —— 本批实测 `hf-mirror.com/spaces/...` 返回 404）。
#
# ⚠️ 两个环境坑（本批实际踩到，勿改）：
#   1. 本脚本**必须带 UTF-8 BOM**（Windows PowerShell 5.1 在无 BOM 时按 ANSI/GBK 解析中文注释，
#      会报与真正语法无关的 `Missing closing '}'`）。用任何工具改写本文件后请重新加回 BOM。
#   2. `$ErrorActionPreference` **必须为 Continue**：`git`/`curl` 把进度写 stderr，
#      在 Stop 下会被当成终止性错误而在"其实已经成功"时中断脚本。
#
# 幂等：已存在的 git 仓库做 `git pull --ff-only`（失败仅告警，不中断——国内直连 GitHub 常被重置）；
#       已存在且体积达标的权重直接跳过。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File prepare-vits-toolchain.ps1

[CmdletBinding()]
param(
    [string]$RepoUrl = "https://github.com/ANONEKO777/sherpa-onnx-vits-fine-tuning-webui",
    # 权重体积下限（MB）；小于它视为下载不完整并换下一个源
    [int]$MinWeightMB = 50,
    # 代理基址候选（按顺序试；脚本自动拼上各候选的相对路径）
    [string[]]$BaseUrls = @(
        "https://hf-mirror.com",
        "https://huggingface.co"
    )
)

$ErrorActionPreference = "Continue"

$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Join-Path $kitDir "build-out\vits\toolchain"
$repo = Join-Path $root "sherpa-onnx-vits-fine-tuning-webui"
$logPath = Join-Path $root "prepare-log.txt"
New-Item -ItemType Directory -Force -Path $root | Out-Null
$log = New-Object System.Collections.Generic.List[string]

function Say([string]$m) {
    $line = ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m)
    Write-Host $line
    $log.Add($line)
    [System.IO.File]::WriteAllText($logPath, ($log -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))
}

foreach ($tool in @("git", "curl")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Write-Error "缺少依赖：$tool 不在 PATH 中"; exit 1 }
}

# ==================== 1) 取仓库 ====================
if (Test-Path (Join-Path $repo ".git")) {
    Say "仓库已存在，尝试 git pull --ff-only（失败仅告警）"
    git -C $repo pull --ff-only 2>&1 | ForEach-Object { Say "  git: $_" }
} else {
    Say "克隆 $RepoUrl"
    git clone --depth 1 $RepoUrl $repo 2>&1 | ForEach-Object { Say "  git: $_" }
}
if (-not (Test-Path (Join-Path $repo "export_vits_fast_fine_tuning_onnx.py"))) {
    Write-Error "仓库不可用：缺 export_vits_fast_fine_tuning_onnx.py"
    exit 1
}
Say "工具链就位：$(Join-Path $repo 'export_vits_fast_fine_tuning_onnx.py')"

# ==================== 2) 取底模 ====================
New-Item -ItemType Directory -Force -Path (Join-Path $repo "pretrained_models") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $repo "configs") | Out-Null

$targets = @(
    @{ Dest = "pretrained_models/G_0.pth"; MinMB = $MinWeightMB; Candidates = @(
            "datasets/Plachta/sampled_audio4ft/resolve/main/VITS-Chinese/G_0.pth",
            "spaces/Plachta/VITS-Umamusume-voice-synthesizer/resolve/main/pretrained_models/G_trilingual.pth"
        ) },
    @{ Dest = "pretrained_models/D_0.pth"; MinMB = $MinWeightMB; Candidates = @(
            "datasets/Plachta/sampled_audio4ft/resolve/main/VITS-Chinese/D_0.pth",
            "spaces/Plachta/VITS-Umamusume-voice-synthesizer/resolve/main/pretrained_models/D_trilingual.pth"
        ) },
    @{ Dest = "configs/finetune_speaker.json"; MinMB = 0; Candidates = @(
            "datasets/Plachta/sampled_audio4ft/resolve/main/VITS-Chinese/config.json",
            "spaces/Plachta/VITS-Umamusume-voice-synthesizer/resolve/main/configs/uma_trilingual.json"
        ) }
)

$failed = @()
foreach ($t in $targets) {
    $dest = Join-Path $repo $t.Dest
    if (Test-Path $dest) {
        $mb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
        if ($mb -ge $t.MinMB) { Say ("跳过（已存在 {0}MB）：{1}" -f $mb, $t.Dest); continue }
        Say ("已存在但体积异常（{0}MB），重新下载：{1}" -f $mb, $t.Dest)
        Remove-Item $dest -Force
    }
    $ok = $false
    foreach ($cand in $t.Candidates) {
        foreach ($base in $BaseUrls) {
            $url = "$base/$cand"
            Say "下载 $url"
            curl.exe -L --fail --silent --show-error --max-time 1200 -o $dest $url 2>&1 | ForEach-Object { Say "  curl: $_" }
            if (Test-Path $dest) {
                $mb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
                if ($mb -ge $t.MinMB) { Say ("  OK {0}MB" -f $mb); $ok = $true; break }
                Say ("  体积不足（{0}MB < {1}MB），换下一个源" -f $mb, $t.MinMB)
                Remove-Item $dest -Force
            } else {
                Say "  未产出文件，换下一个源"
            }
        }
        if ($ok) { break }
    }
    if (-not $ok) { $failed += $t.Dest }
}

if ($failed.Count -gt 0) {
    Write-Error ("以下底模在两个基址、全部候选都没拿到：`n  " + ($failed -join "`n  ") +
        "`n  处置：手动从 LOCAL.md 列出的 URL 下载后按 README 命名放好（G→G_0.pth / D→D_0.pth / config→finetune_speaker.json）")
    exit 1
}

Say "完成。下一步：conda run -n vits38 python -m pip install -r requirements.txt（工作目录 = $repo）"
