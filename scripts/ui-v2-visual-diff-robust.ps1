$Adb = "C:\Users\ljh\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:PYTHONIOENCODING = 'utf-8'
function Shot([string]$n) {
    & $Adb shell screencap -p /sdcard/_p.png | Out-Null
    & $Adb pull /sdcard/_p.png "tmp-ui/shots/$n.png" 2>$null | Out-Null
    Write-Output "shot $n"
}
function TapText([string]$needle) {
    & $Adb shell uiautomator dump /sdcard/_u.xml 2>$null | Out-Null
    & $Adb pull /sdcard/_u.xml tmp-ui/ui.xml 2>$null | Out-Null
    $line = python tmp-ui/dump.py | Select-String -SimpleMatch $needle | Select-Object -First 1
    if (-not $line) { Write-Output "MISS $needle"; return }
    if ($line.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $Adb shell input tap $x $y | Out-Null
        Start-Sleep -Milliseconds 900
        Write-Output "tap $needle @$x,$y"
    }
}
& $Adb shell am force-stop io.prism | Out-Null
Start-Sleep -Milliseconds 400
& $Adb shell am start -n io.prism/.MainActivity | Out-Null
Start-Sleep -Seconds 3
& $Adb shell input tap 949 2250 | Out-Null
Start-Sleep -Milliseconds 900
TapText "QQ 小号"
Start-Sleep -Milliseconds 2200
Shot "qq"
# 定时子页
& $Adb shell input swipe 540 1900 540 800 200 | Out-Null
Start-Sleep -Milliseconds 500
TapText "定时与主动消息"; Shot "qq-schedule"
& $Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 700
& $Adb shell input swipe 540 900 540 1900 200 | Out-Null; Start-Sleep -Milliseconds 400
& $Adb shell input swipe 540 1900 540 800 200 | Out-Null; Start-Sleep -Milliseconds 500
TapText "自动回复"; Shot "qq-reply"
& $Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 700
& $Adb shell input swipe 540 900 540 1900 200 | Out-Null; Start-Sleep -Milliseconds 400
& $Adb shell input swipe 540 1900 540 800 200 | Out-Null; Start-Sleep -Milliseconds 500
TapText "表情库"; Shot "qq-stickers"
& $Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 500
& $Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 700
# 语音
TapText "语音与形象"; Start-Sleep -Milliseconds 900; Shot "voice"
& $Adb shell input swipe 540 1900 540 700 200 | Out-Null; Start-Sleep -Milliseconds 500
& $Adb shell input swipe 540 1900 540 700 200 | Out-Null; Start-Sleep -Milliseconds 500
& $Adb shell input swipe 540 1900 540 700 200 | Out-Null; Start-Sleep -Milliseconds 500
Shot "voice-3"
TapText "角色包"; Start-Sleep -Milliseconds 900; Shot "persona"
& $Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 500
& $Adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 700
TapText "网络、设备与关于"; Start-Sleep -Milliseconds 900; Shot "system"
TapText "搜索引擎增强"; Start-Sleep -Milliseconds 900; Shot "search-sheet"
Write-Output "done"
