# Runbook：自建 SearXNG 并接入 Prism 搜索增强

> 关联：v1 批次15（PRD `docs/prd-search-fetch-enhancement.md` US-1507）。
> SearXNG 是开源自托管元搜索引擎（AGPL-3.0）。Prism 只通过 HTTP JSON 接口调用你部署的服务，
> **不将 SearXNG 链接/打包进分发产物**。本教程覆盖 Docker Compose 部署、开启 JSON 输出、
> 大陆可达引擎配置、以及在 Prism 设置页填端点的完整流程。

## 1. 为什么需要「搜索增强」

Prism 零配置搜索走 Bing/Baidu HTML SERP（无 Key 免费），但服务端分词/消歧不可控，
对冷门中文实体命中率有限。自建 SearXNG 后可聚合多引擎结果并拿回部分控制权：

- 一个端点聚合 bing/baidu/sogou/360/chinaso 等多个大陆可达引擎；
- 返回结构化 JSON（`results[][title/url/content]`），比 HTML SERP 正则解析稳定；
- 数据不经过第三方云（只有你自己与上游搜索引擎之间的请求）。

## 2. Docker Compose 部署（官方镜像）

新建目录 `searxng/`，放入以下两个文件：

`docker-compose.yml`：

```yaml
version: "3.9"

services:
  searxng:
    image: searxng/searxng:latest
    container_name: searxng
    ports:
      - "8080:8080"          # 左侧宿主机端口可自行调整
    volumes:
      - ./searxng:/etc/searxng   # settings.yml 挂载进容器
    environment:
      - SEARXNG_BASE_URL=http://<你的局域网IP>:8080/
    restart: unless-stopped
```

`searxng/settings.yml`（首次可先 `docker run --rm searxng/searxng:latest` 拿默认模板再改）：

```yaml
use_default_settings: true

server:
  secret_key: "请生成一串随机长字符串"   # 必填：openssl rand -hex 32
  limiter: false          # 家庭内网/单用户场景建议关闭请求限流器（默认 true 会拦高频请求）
  image_proxy: false

search:
  safe_search: 0
  autocomplete: ""
  default_lang: "zh-CN"
  formats:
    - html
    - json                # ★ 必须手动加这一行（见第 3 节）

engines:
  - name: bing
    engine: bing
    disabled: false
  - name: baidu
    engine: baidu
    disabled: false
  - name: sogou
    engine: sogou
    disabled: false
  - name: 360search
    engine: 360search
    disabled: false
  - name: chinaso       # 中国搜索（官方）
    engine: chinaso
    disabled: false
```

启动：

```bash
docker compose up -d
curl "http://127.0.0.1:8080/search?q=test&format=json"   # 应返回 JSON
```

## 3. 关键：必须开启 `json` 输出格式（否则 403）

**官方文档核验**：SearXNG 出于防滥用考虑，`settings.yml` 的 `search.formats` **默认只允许
`html`**；未添加 `- json` 时，任何 `format=json` 请求都会被拒绝（HTTP **403 Forbidden**），
Prism 搜索侧会表现为「SearXNG 引擎 http 403 → 静默降级 Bing/Baidu」。

因此必须在 `searxng/settings.yml` 中：

```yaml
search:
  formats:
    - html
    - json
```

改完 `docker compose restart searxng` 生效。若 Prism 仍拿不到结果，先用浏览器/curl
直接请求 `http://<host>:8080/search?q=test&format=json` 验证返回 200 JSON。

## 4. 大陆可达引擎建议

家庭/大陆网络环境下，海外引擎（google、duckduckgo、brave 等）大概率超时或被阻断，
拖慢整体聚合耗时（SearXNG 等待所有启用引擎返回）。建议：

- **启用**：`bing`、`baidu`、`sogou`、`360search`、`chinaso`（均大陆直连可用）；
- **禁用**：google / duckduckgo / qwant / startpage 等海外引擎（`disabled: true` 或保持默认禁用）；
- 在 `http://<host>:8080/config`（或管理页）可核对各引擎实际可用性。

## 5. 在 Prism 中接入

1. 打开 Prism → **设置 → 搜索增强 → 搜索引擎增强**；
2. 在「SearXNG 端点」填写 `http://<主机IP>:8080`（Prism 会自动追加 `/search`；
   若你填的地址已以 `/search` 结尾则原样使用）；
3. 若你在 SearXNG 前面加了反向代理 Basic Auth，填写「SearXNG 用户名 / 密码」，否则留空；
4. 保存后无需重启：下一次联网搜索即按 **博查 → 智谱 → SearXNG → Tavily → Bing/Baidu**
   的优先级尝试（首个成功即返回）。

## 6. 部署位置：家宽优于云服务器 IP

- **家庭宽带 / 内网部署（推荐）**：上游搜索引擎对家宽 IP 的风控宽容，baidu/sogou 等触发
  验证码的概率低；数据全程不出局域网。
- **云服务器部署（不推荐裸用）**：数据中心 IP 段是搜索引擎风控重点，高频聚合请求很快
  触发验证码/封禁，SearXNG 返回空结果或 429。若必须用云服务器，请降低调用频率并配合
  `limiter` 与反代限速。
- 局域网地址（如 `http://192.168.x.x:8080`）Prism 允许配置（用户显式配置口径），
  请确保手机与 SearXNG 主机在同一局域网/VPN 内。
- ⚠️ **Android 明文策略限制（M-2，guardrail TKN-V1B15-GUARDRAIL-001）**：App 网络安全
  配置仅放行 `localhost/127.0.0.1` 明文 http，**局域网 `http://<IP>` 端点会被系统拦截**
  （CLEARTEXT not permitted，日志特征 `searxng search blocked by cleartext policy`）。
  三种解法（任选其一）：
  1. **USB adb reverse（免配置证书，推荐）**：手机连电脑后执行
     `adb reverse tcp:8080 tcp:8080`，Prism 端点填 `http://127.0.0.1:8080`（落在既有
     localhost 放行域，流量经 USB 转发到电脑的 SearXNG）；
  2. **https 反向代理**：给 SearXNG 套 Caddy/Nginx 自签或 Let's Encrypt 证书，端点填
     `https://<主机名>`（配合第 5 节 Basic Auth 一并实现）；
  3. **Tailscale/VPN + MagicDNS https**（进阶）。

## 7. 故障排查

| 现象 | 原因与处理 |
|---|---|
| Prism 提示 searxng http 403 | `settings.yml` 未加 `search.formats: [- json]`（见第 3 节） |
| logcat 出现 `blocked by cleartext policy` | 局域网明文 http 被系统拦截（见第 6 节解法 1/2/3） |
| 请求超时 | 启用了海外引擎；按第 4 节禁用后重启容器 |
| 返回 JSON 但 Prism 无结果 | 全部上游引擎被风控（常见于云 IP）；换家宽部署或降低频率 |
| Basic Auth 401 | 核对反代凭据与 Prism 设置页用户名/密码是否一致 |
| 端点不可达 | 手机与主机不在同一网络；或防火墙未放行 8080 端口 |

## 8. 安全提示

- `server.secret_key` 必须设置且不外泄；
- 不要把 SearXNG 直接暴露公网（如需远程使用，建议 VPN/Tailscale 或至少加反代 Basic Auth）；
- Prism 侧 API Key 走 Keystore 加密存储、不落日志；SearXNG Basic Auth 凭据仅在内存与
  请求头中出现，不会输出到 logcat。
