package io.prism.network

/**
 * 私网/回环地址判定（ADR-064 明文策略的运行时补偿）。
 *
 * **背景**：Android `NetworkSecurityConfig` 无法表达网段，为连接用户自建的 LAN 协议端，
 * `base-config` 全局允许了明文。为不使既有模块（Provider / SearXNG / MCP / 抓取）
 * 的"远端可控 URL + 明文"风险回归，统一用本对象做**严格 IP 字面量**校验：
 * 只允许回环与私网 IPv4，任何域名/公网 IP 一律拒绝。
 */
object PrivateNetwork {

    private val SCHEMES = setOf("http", "https", "ws", "wss")

    /** URL 是否为本机/私网端点（scheme 白名单 + 严格 IP 字面量）。 */
    fun isPrivateEndpoint(rawUrl: String): Boolean {
        val uri = runCatching { java.net.URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in SCHEMES) return false
        val host = uri.host?.lowercase() ?: return false
        return isPrivateHost(host)
    }

    /** host（不含 scheme/端口）是否为本机/私网。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "127.0.0.1") return true
        val bare = h.removePrefix("[").removeSuffix("]")
        if (bare == "::1") return true
        // guardrail L-31：**拒绝一切主机名**（含单标签，如 `nas`/`ollama`）——单标签名可能经
        // DNS 搜索域解析到公网，与 ADR-064「拒绝任何域名、仅接受 IP 字面量」不一致。
        val parts = bare.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull() ?: return false
        }
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }

    /**
     * 明文 URL 是否允许（**仅 http 受限**；https/wss 不受影响）。
     *
     * @return false 表示"远端 http，应拒绝"——防 API Key / 抓取内容走明文公网
     */
    fun isCleartextAllowed(rawUrl: String): Boolean {
        val scheme = runCatching { java.net.URI(rawUrl.trim()).scheme?.lowercase() }.getOrNull()
            ?: return false
        return when (scheme) {
            "https", "wss" -> true
            "http", "ws" -> isPrivateEndpoint(rawUrl)
            else -> false
        }
    }
}
