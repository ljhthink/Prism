# 昔涟克隆素材重建脚本（rebuild-clone-audio.ps1）
# 用途：raw\ 目录放入新增的昔涟语音（ogg/wav/mp3 均可）后，一键产出符合
#       MiniMax 克隆要求的 cyrene-clone-main.mp3：
#       - 首尾静音裁剪（-45dB 阈值，保留 0.15s 呼吸感）
#       - 响度归一（EBU R128：I=-16 LUFS，防各条音量不一致拉低克隆质量）
#       - 统一转码 44.1kHz 单声道 128kbps mp3（≤20MB 远裕）
#       - 拼接为单文件并输出总时长报告（目标 60–300s；>300s 时脚本会告警）
# 用法：把新语音放进 raw\ 后，在 voice-clone-kit\ 目录执行：
#       powershell -ExecutionPolicy Bypass -File rebuild-clone-audio.ps1
# 依赖：ffmpeg / ffprobe（已确认本机可用）

$ErrorActionPreference = "Stop"
$kitDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rawDir = Join-Path $kitDir "raw"
$outMain = Join-Path $kitDir "cyrene-clone-main.mp3"
$tmpDir = Join-Path $kitDir "tmp-processed"

if (-not (Test-Path $rawDir)) { Write-Error "未找到 raw\ 目录"; exit 1 }
$inputs = Get-ChildItem $rawDir -File | Where-Object { $_.Extension -match '\.(ogg|wav|mp3|m4a)$' } | Sort-Object Name
if (-not $inputs) { Write-Error "raw\ 目录无音频文件"; exit 1 }

# 克隆质量筛选（BR-ops-013 素材最佳实践）：战斗喊招/技能音效段不适合克隆——按文件名模式剔除
$excludePattern = '回合开始|忆灵技|回复生命|发现敌方|受击|战斗开始|重回战斗|\(2x\)|（2x）'
$excluded = @($inputs | Where-Object { $_.Name -match $excludePattern })
if ($excluded.Count -gt 0) {
    Write-Host ("剔除战斗/重复变体 {0} 条：{1}" -f $excluded.Count, (($excluded | ForEach-Object Name) -join ' | '))
    $inputs = @($inputs | Where-Object { $_.Name -notmatch $excludePattern })
}

New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$processed = @()
$chain = ""
$i = 0
# $k = **已入选**段的序号（0 起）。concat 滤镜里的 `[k:a]` 必须与 `-i` 实参列表的**位置**一致，
# 而 `-i` 只按 $processed（入选段）生成 ⇒ 这里**不能**用 $i-1：$i 每个输入都自增，
# 第 49 行跳过超限段 / 第 38 行 break 之后，$i-1 会指向一个**从未作为 -i 传入**的编号，
# ffmpeg 会因引用不存在的输入而整体失败（既有缺陷，本批顺手修正并登记）。
$k = 0
$acc = 0.0
$TARGET_MAX_S = 295.0   # MiniMax 上限 300s，留 5s 余量；贪心：按清单顺序累计，超限即止

foreach ($f in $inputs) {
    if ($acc -ge $TARGET_MAX_S) { Write-Host "已达时长上限 ${TARGET_MAX_S}s，剩余素材跳过"; break }
    $i++
    $out = Join-Path $tmpDir ("seg_{0:D2}.mp3" -f $i)
    Write-Host "处理 $($f.Name) ..."
    # 首尾静音裁剪（areverse 技巧实现尾部裁剪）→ 响度归一 → 44.1kHz mono
    ffmpeg -y -hide_banner -loglevel error -i $f.FullName -af `
      "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.15,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.15,areverse,loudnorm=I=-16:TP=-1.5:LRA=11" `
      -ar 44100 -ac 1 -b:a 128k $out
    if ($LASTEXITCODE -ne 0) { Write-Error "处理失败：$($f.Name)"; exit 1 }
    $dur = [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $out)
    # 贪心：整段超限则丢弃该段继续尝试更短的（避免浪费）
    if (($acc + $dur) -gt $TARGET_MAX_S) { Write-Host ("  → {0:N1}s 超限跳过" -f $dur); Remove-Item $out -Force; continue }
    Write-Host ("  → {0:N1}s ✓" -f $dur)
    $acc += $dur
    $processed += $out
    $chain += "[$($k):a]"
    $k++
}
Write-Host ("入选 {0} 段，累计 {1:N1}s" -f $processed.Count, $acc)

$total = $acc
# 拼接（concat filter 一次性重编码，避免 mp3 时间戳问题）
$n = $processed.Count
# 用**数组实参**调用，不用 `Invoke-Expression`（独立审查 L-4，CWE-78 模式）：
# 原实现把素材文件名（`raw\` 下的任意本地文件名）拼进命令行字符串，再交给 Invoke-Expression 执行
# ⇒ 名字里含 `;` / `$()` / 反引号等字符时会被当**代码**执行。
# 数组调用下每个元素都是一个独立实参，名字里的任何字符都不会被解析为语法。
$concatFilter = "$chain`concat=n=$n`:v=0`:a=1"
$ffArgs = @('-y', '-hide_banner', '-loglevel', 'error')
foreach ($f in $processed) { $ffArgs += @('-i', $f) }
$ffArgs += @('-filter_complex', $concatFilter, '-b:a', '128k', $outMain)
& ffmpeg @ffArgs
if ($LASTEXITCODE -ne 0) { Write-Error "拼接失败"; exit 1 }

$total = [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $outMain)
$sizeMB = [math]::Round((Get-Item $outMain).Length / 1MB, 2)
Write-Host ""
Write-Host ("完成：{0}（{1:N1}s，{2}MB，来自 {3} 条素材）" -f $outMain, $total, $sizeMB, $n)
if ($total -lt 60) { Write-Warning "总时长不足 60s——相似度提升有限，建议继续补充 raw\ 素材至 60–120s" }
if ($total -gt 300) { Write-Warning "总时长超 300s（MiniMax 上限 5 分钟）——请删减 raw\ 中低质量片段后重跑" }

# 清理临时分段
Remove-Item $tmpDir -Recurse -Force
Write-Host "下一步：platform.minimaxi.com 重新克隆（或用官方抽卡工具批量出 6 候选试听筛选），"
Write-Host "把新 voice_id 填入 cyrene.rolepack 的 voice.minimax.cloneVoiceId，再到设置页重新导入角色包。"
