#!/bin/bash
# 容器内：单实例常驻 NapCat，**使用快速登录**（-q <QQ号>），不扫码。
#
# 2026-11-02 稳定性改造（ADR-071 / PRD US-3129）：
#   1. **单实例锁**：重复 RUN_COMMAND 会拉起第二个 proot 链，两条 QQ 链互踢是
#      「登录态已失效 / 要反复扫码」的常见成因。这里用 flock 直接拒绝第二个实例。
#   2. **探活口径修正**：PRoot 镜像里没有 `ss`，也没有 `netstat`，旧实现
#      `(ss -tln || netstat -tln) | grep -c ':3000'` 恒为 0 —— 于是 Prism 侧
#      「已在运行就不重启」的防自踢守卫永不生效。改为读内核表 `/proc/net/tcp`
#      （3000 的十六进制是 `0BB8`）。
#   3. **状态机**：状态文件新增 `state=starting|need_qr|online|qq_dead`，
#      让 Prism 能区分「正在启动」「等扫码」「已在线」——扫码期间**不允许**自动重启，
#      否则重启会把用户正在扫的二维码作废。
#   4. **内层看门狗**：QQ 进程消失时用 `-q` 重拉（每小时最多 3 次，退避 1/5/15 分钟），
#      不必等 Prism 从外部整链重启。
#
# 依据 2026-10-31 真机日志：
#   13:17:31 NapCat 自动重启后打印「可用于快速登录 of QQ：1. <QQ号> <昵称>」
#   → 凭据已落盘，可用 -q 快速登录，无需重复扫码。
#
# 同时每轮把日志镜像到 /sdcard/napcat.log，便于 PC 侧直接读取定位。
#
# 2026-11-01（安全审查 H4）：QQ 号改为**可由环境变量覆盖**，不再只有硬编码一条路。
# QQ 号本身不是密钥（好友可见），但它会随 APK assets 下发到 /data/local/tmp，
# 属"本机其它 App 可读"的信息面；环境变量可在不改脚本的前提下换号。

QQ="${PRISM_QQ_ID:-3904755943}"
if ! printf '%s' "$QQ" | grep -qE '^[0-9]{5,12}$'; then
  echo "!! QQ 号非法：$QQ" >&2
  exit 1
fi
LOG=/tmp/qq-run2.log
STATUS=/sdcard/napcat-status.txt
LOCK=/tmp/qq-run2.lock

# ---- 0) 单实例锁（ADR-071）：拿不到锁说明已有实例在跑，直接退出，绝不 pkill ----
# 优先 flock（util-linux，bookworm 默认有）；没有则退回 pid 文件判活。
mkdir -p /tmp 2>/dev/null
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK" 2>/dev/null || exit 0
  if ! flock -n 9 2>/dev/null; then
    echo "已有 run2-inner 实例在运行，本实例退出（防重复链互踢）" >&2
    exit 0
  fi
else
  if [ -f "$LOCK" ]; then
    old=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
      echo "已有 run2-inner 实例在运行（pid=$old），本实例退出" >&2
      exit 0
    fi
  fi
  echo $$ > "$LOCK" 2>/dev/null
fi

state="starting"
state_since=$(date +%s)
restarts=0
restart_window_start=$(date +%s)
next_restart_at=0

# 3000 是否在监听：多路判据（2026-11-14 真机实证：只查 /proc 会假阴性）
#
# 为什么不能只 grep `/proc/net/tcp`：真机 21:09 实测——宿主侧 `/proc/net/tcp` 明明有
# `00000000:0BB8 ... 0A`（LISTEN），但**容器内**同一条正则返回 0；而 NapCat 的
# `get_status` 一切正常（HTTP 200 / online=true）。探活假阴性会让状态机永远停在
# `starting`，Prism 侧据此判"没起来"并反复重启——正是要根除的自踢循环。
#
# 因此按"内核表（tcp / tcp6）→ HTTP 探测（任何状态码都证明服务活着，403 也算）"逐级降级。
port3000_line() {
  grep -hE ':0BB8[[:space:]]+[0-9A-Fa-f:]+[[:space:]]+(0A|01)[[:space:]]' \
    /proc/net/tcp /proc/net/tcp6 2>/dev/null | head -1
}

port3000_listening() {
  [ -n "$(port3000_line)" ] && return 0
  # 二级：HTTP 探测。NapCat 未带 token 时返回 403 —— 403 恰恰证明服务在监听。
  command -v curl >/dev/null 2>&1 || return 1
  local code
  code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' \
    http://127.0.0.1:3000/get_status 2>/dev/null)
  case "$code" in
    000|"") return 1 ;;
    *) return 0 ;;
  esac
}

# 6000? 不检测；扫码态由日志判据给出
qr_pending() {
  [ -s "$LOG" ] || return 1
  # 最近一次登录相关行是「请扫描/二维码」而不是「登录成功」
  local last
  last=$(grep -aS '请扫描下面的二维码\|二维码已保存\|登录成功\|已上线\|快速登录错误\|KickedOffLine' "$LOG" 2>/dev/null | tail -1)
  case "$last" in
    *请扫描*|*二维码已保存*) return 0 ;;
    *) return 1 ;;
  esac
}

qq_alive() {
  pgrep -f 'opt/QQ/qq' >/dev/null 2>&1
}

start_qq() {
  pkill -9 -f 'Xvfb :99' 2>/dev/null
  sleep 1
  rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
  mkdir -p /tmp/xdg && chmod 700 /tmp/xdg
  Xvfb :99 -screen 0 720x1280x24 -nolisten tcp > /tmp/xvfb2.log 2>&1 &
  sleep 4
  cd /opt/QQ || return 1
  env DISPLAY=:99 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg \
      ./qq --no-sandbox --disable-gpu --disable-dev-shm-usage \
           --disable-software-rasterizer --ozone-platform=x11 \
           -q "$QQ" > "$LOG" 2>&1 &
  sleep 2
}

# ---- 1) 首次启动：清理旧实例后拉起 ----
echo "== 1) 清理旧实例 =="
pkill -9 -f 'opt/QQ/qq' 2>/dev/null
pkill -9 -f 'Xvfb :99' 2>/dev/null
sleep 3
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null
echo "残留 qq=$(pgrep -fc 'opt/QQ/qq' 2>/dev/null || echo 0)"

echo "== 2) 启动（Xvfb + 快速登录） =="
start_qq
echo "已启动，等待登录与 OneBot 就绪…"

n=0
while true; do
  sleep 5
  n=$((n+1))
  cp "$LOG" /sdcard/napcat.log 2>/dev/null

  # ---- 3) 状态机 ----
  QN=$(pgrep -fc 'opt/QQ/qq' 2>/dev/null || echo 0)
  P3000=0
  port3000_listening && P3000=1
  prev_state="$state"
  if ! qq_alive; then
    state="qq_dead"
  elif port3000_listening; then
    state="online"
  elif qr_pending; then
    state="need_qr"
  else
    state="starting"
  fi
  # 状态迁移即重置计时（guardrail R2-05：不能复用 start_qq 的 started_at——
  # 已在线后短暂抖动回 starting 会被误判"启动超时"，直接杀掉健康进程并烧掉重启预算）
  [ "$state" != "$prev_state" ] && state_since=$(date +%s)

  # ---- 3b) 启动超时（guardrail R-08）----
  # `starting` 持续 180s 不前进 = 进程活着但挂死（如 QQ 卡在启动画面）。
  # 若只等 qq_dead，脚本与 Prism 侧都会认为「在跑」，两边都不救。
  NOW=$(date +%s)
  if [ "$state" = "starting" ] && [ $((NOW - state_since)) -gt 180 ]; then
    echo "!! 启动超时（$((NOW - state_since))s 未就绪），杀掉进程重来" >> /sdcard/napcat.log 2>/dev/null
    pkill -9 -f 'opt/QQ/qq' 2>/dev/null
    state="qq_dead"
    state_since=$NOW
  fi

  # ---- 4) 内层看门狗：仅 qq_dead 且退避到点才重拉；need_qr 期间绝不重启 ----
  if [ "$state" = "online" ]; then
    restarts=0
    restart_window_start=$NOW
    next_restart_at=0
  fi
  if [ "$state" = "qq_dead" ] && [ "$NOW" -ge "$next_restart_at" ]; then
    if [ $((NOW - restart_window_start)) -gt 3600 ]; then
      restarts=0
      restart_window_start=$NOW
    fi
    if [ "$restarts" -lt 3 ]; then
      restarts=$((restarts+1))
      case "$restarts" in
        1) wait_s=60 ;;
        2) wait_s=300 ;;
        *) wait_s=900 ;;
      esac
      next_restart_at=$((NOW + wait_s))
      echo "!! QQ 进程不存在，第 $restarts 次重拉（下次最早 ${wait_s}s 后）" >> /sdcard/napcat.log 2>/dev/null
      start_qq
      state_since=$(date +%s)
    fi
  fi

  # 状态文件：每 10 秒一行，全部字段都带 ts（Prism 判 stale）
  if [ $((n % 2)) -eq 0 ]; then
    TS=$(date +%s)
    {
      echo "ts=$TS tick=$n qq=$QN onebot3000=$P3000 state=$state since=$state_since restarts=$restarts tcp=$(port3000_line | wc -l)"
      # guardrail L：状态文件落在 /sdcard（同组可读），日志行里的 QQ 号/昵称必须打码
      grep -aS 'KickedOffLine\|账号状态\|登录成功\|已上线\|快速登录\|Server Started' "$LOG" 2>/dev/null \
        | tail -3 | sed 's/[0-9]\{5,\}/*****/g'
    } > "$STATUS.tmp" 2>/dev/null && mv "$STATUS.tmp" "$STATUS" 2>/dev/null
  fi

  # 每轮把当前 WebUI 扫码地址单独落一份（2026-11-01 审查 H-2）：
  # 审查 N-4：**先写临时文件、非空再 mv**——避免无匹配时写空文件。
  if [ $((n % 4)) -eq 0 ]; then
    if grep -aS 'WebUi User Panel Url' "$LOG" 2>/dev/null | tail -1 > /sdcard/.napcat-webui.tmp 2>/dev/null \
       && [ -s /sdcard/.napcat-webui.tmp ]; then
      mv /sdcard/.napcat-webui.tmp /sdcard/napcat-webui.txt 2>/dev/null
    else
      rm -f /sdcard/.napcat-webui.tmp 2>/dev/null
    fi
  fi

  # 就绪即打印一次
  if [ "$P3000" -gt 0 ] && [ $((n % 12)) -eq 0 ]; then
    echo "[$((n*5))s] READY qq=$QN onebot=1 state=$state"
  fi
done
