# 跨网络访问 NapCat（Tailscale 组网）

> 目标：**离开家里的/热点的局域网后，手机依然能连上 PC 上的 NapCat**，从而保留看图、发表情、发语音、随机定时。
> 本文对应 ADR-068（2026-10-27 用户裁定：选 Tailscale、允许 `100.64/10`、不做公网 wss）。

## 为什么需要它

真机实证（2026-10-27）：

- 手机切到蜂窝网（`rmnet_data2 10.244.60.226/30`，WiFi 已关闭），
- App 里仍保存着上次热点的 `10.92.122.126:3000` → WS/HTTP 全部超时 → `UNREACHABLE`，
- 通道自动降级到**无障碍**，而无障碍**结构上**看不了图、发不了表情/语音。

即：**不是媒体功能坏了，是协议端根本连不上**。要让"出门也能用"，必须有一条跨网络可达的路径。

## 一次性配置（约 10 分钟）

### 1. PC 侧

1. 安装 Tailscale：<https://tailscale.com/download>（Windows），登录你的账号。
2. 记录本机 Tailscale IP（形如 `100.x.y.z`）：

   ```powershell
   tailscale ip -4
   # 或
   Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like '100.*' }
   ```

3. 放行 NapCat 端口（**仅 Tailscale 网卡**，不暴露到公网）：

   ```powershell
   New-NetFirewallRule -DisplayName "NapCat OneBot (Tailscale)" -Direction Inbound `
     -Protocol TCP -LocalPort 3000,3001 -Action Allow `
     -InterfaceAlias "Tailscale" -Profile Any
   ```

   > 若 `-InterfaceAlias` 报错（虚拟网卡名不同），先 `Get-NetAdapter | Select Name,InterfaceDescription`
   > 找到 Tailscale 网卡名再填；实在不行用 `-RemoteAddress 100.64.0.0/10` 限定来源网段。

4. 确认监听正常（NapCat 已监听全部接口，无需改配置）：

   ```powershell
   Get-NetTCPConnection -State Listen -LocalPort 3000,3001 | Select LocalAddress,LocalPort
   # 期望看到 ::  （表示全部接口）
   ```

### 2. 手机侧

1. 安装 Tailscale（Google Play / App Store / APK），**登录同一个账号**。
2. 打开 VPN 开关，确认能 ping 通 PC 的 `100.x.y.z`：

   ```powershell
   # PC 上反向验证手机在线
   tailscale status
   ```

### 3. App 内填地址

设置 → QQ 小号助手 → 协议端连接 → **协议端地址**：

```
100.x.y.z
```

保存后 App 会自动推导 `http://100.x.y.z:3000` 与 `ws://100.x.y.z:3001`。
点「连接诊断」，期望一路通过（ENDPOINT → SUBNET → TCP → AUTH → WS）。

## 验证清单

| 检查 | 期望 |
| --- | --- |
| `tailscale status`（PC） | 手机显示 `active`，有 100.x 地址 |
| 手机上用浏览器开 `http://100.x.y.z:3000/get_status` | 返回 JSON（可能提示鉴权失败，但说明端口通） |
| App「连接诊断」 | `已连接（100.x.y.z:3000）` |
| 设置页「协议端连接」副标题 | `QQ 在线：…` 且**不显示**"仅文本可用" |
| 关掉 WiFi 只用蜂窝 | 仍显示在线（这就是本次要的效果） |

## 常见问题

**Q：诊断说"该地址不在当前网络内（疑似上次局域网/热点的地址）"？**
A：说明 App 里还是旧局域网的 IP。改成 `100.x.y.z`，或在同一 WiFi 下改回局域网 IP。

**Q：诊断说"无线客户端隔离/跨 VLAN"？**
A：校园网/公共 WiFi 常禁止终端互访。此时即使同网段也不通——用 Tailscale 反而能绕过。

**Q：Tailscale 会不会把 NapCat 暴露到公网？**
A：不会。Tailscale 是点对点加密组网，只有你账号下的设备能访问；`100.64/10` 也不是公网可路由网段。
App 侧仍然**只接受 IP 字面量**，不接受域名，也不会对公网地址发 token。

**Q：手机 Tailscale 掉线怎么办？**
A：会退回"协议端不可达"，App 自动降级为无障碍文本并**明确标注"当前仅文本可用"**（看不了图、发不了表情/语音）。

**Q：能不能不用 Tailscale，直接公网开放？**
A：本次按你的裁定**不做**（会把 token 暴露在公网）。若以后要，需另开 ADR 并强制 wss + 显式开关。

## 相关

- [NapCat 网络基础](https://doc.napneko.icu/onebot/network)（HTTP/WS 的 server/client 两种角色）
- [Tailscale 保留地址](https://tailscale.com/docs/reference/reserved-ip-addresses)（`100.64/10` 属 CGNAT）
- 方案：[2026-10-27-qq-crossnetwork-and-schedule-fix-plan.md](../../docs/reports/2026-10-27-qq-crossnetwork-and-schedule-fix-plan.md)
