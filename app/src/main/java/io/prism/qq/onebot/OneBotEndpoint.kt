package io.prism.qq.onebot

import io.prism.network.PrivateNetwork

/**
 * OneBot 协议端地址**归一化**（2026-10-26 真机复盘新增）。
 *
 * **为什么需要**：真机实证——用户把手机和 PC 切到同一热点后仍连不上，唯一原因是设置里存的
 * 仍是 `http://127.0.0.1:3000`（Android 上指手机自己）。同一份配置里 HTTP 与 WS 是两个输入框，
 * 用户很容易只改一个、或只填 IP 不填端口。本对象把"用户想表达的目标"统一成一对确定地址。
 *
 * **设计约束**：
 * - 只接受**本机/私网 IPv4 字面量**（复用 [PrivateNetwork.isPrivateHost]）——与 ADR-064 的
 *   鉴权边界一致，任何域名/公网 IP 一律拒绝（防止 token 被发往公网）；
 * - 纯函数、无 Android 依赖，便于 JVM 单测；
 * - 端口推导规则与 NapCat 默认部署一致：HTTP 3000 / WS 3001（`http` 端口 +1）。
 */
object OneBotEndpoint {

    /** 默认 HTTP 端口（NapCat 默认）。 */
    const val DEFAULT_HTTP_PORT: Int = 3000

    /** 默认 WS 端口（NapCat 默认）。 */
    const val DEFAULT_WS_PORT: Int = 3001

    /** 归一化结果：成功时 [error] 为 null。 */
    data class Result(
        val baseUrl: String,
        val wsUrl: String,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null

        /** 供 UI/日志展示的目标（不含凭据）。 */
        val displayTarget: String
            get() = runCatching { java.net.URI(baseUrl).let { "${it.host}:${it.port}" } }
                .getOrDefault(baseUrl)
    }

    /**
     * 把用户输入归一化为一对地址。
     *
     * 支持的写法（大小写不敏感，忽略首尾空白与末尾 `/`）：
     * - `192.168.1.20` → `http://192.168.1.20:3000` + `ws://192.168.1.20:3001`
     * - `192.168.1.20:3000` → 同上
     * - `http://192.168.1.20:8080` → HTTP 8080 + WS 8081
     * - `ws://192.168.1.20:3001` → HTTP 3000 + WS 3001（按 WS 端口回推 HTTP）
     * - `https://192.168.1.20` → `https://…:443` + `wss://…:443`（TLS 同端口，不做 +1）
     *
     * @param input 用户输入（设置页"协议端地址"单输入框）
     * @param fallbackHttpPort 未指定端口时使用的 HTTP 端口
     */
    fun normalize(
        input: String,
        fallbackHttpPort: Int = DEFAULT_HTTP_PORT
    ): Result {
        val raw = input.trim().removeSuffix("/")
        if (raw.isEmpty()) {
            return Result("", "", "协议端地址为空")
        }
        // 1) 拆分 scheme（缺省视为 http）
        val schemeEnd = raw.indexOf("://")
        val scheme: String
        val remainder: String
        if (schemeEnd >= 0) {
            scheme = raw.substring(0, schemeEnd).lowercase()
            remainder = raw.substring(schemeEnd + 3)
        } else {
            scheme = "http"
            remainder = raw
        }
        if (scheme !in SCHEMES) {
            return Result("", "", "不支持的协议：$scheme（仅支持 http/https/ws/wss）")
        }
        // 2) 拆 host 与端口（路径部分忽略——协议端 action 直接拼在根路径后）
        val authority = remainder.substringBefore('/')
        if (authority.isEmpty()) return Result("", "", "地址缺少主机部分")
        val portSep = authority.lastIndexOf(':')
        val host: String
        val explicitPort: Int?
        if (portSep >= 0) {
            host = authority.substring(0, portSep)
            val portText = authority.substring(portSep + 1)
            explicitPort = portText.toIntOrNull()
            if (explicitPort == null || explicitPort !in 1..65535) {
                return Result("", "", "端口非法：$portText（应为 1~65535）")
            }
        } else {
            host = authority
            explicitPort = null
        }
        if (host.isEmpty()) return Result("", "", "地址缺少主机部分")
        if (!PrivateNetwork.isPrivateHost(host)) {
            return Result(
                "", "",
                "仅支持本机/私网 IPv4 地址（如 192.168.x.x、10.x.x.x）：$host"
            )
        }
        // 3) TLS 与非 TLS 分开处理：TLS 同端口，明文按 NapCat 约定 +1
        val secure = scheme == "https" || scheme == "wss"
        val wsScheme = if (secure) "wss" else "ws"
        val httpScheme = if (secure) "https" else "http"

        val httpPort: Int
        val wsPort: Int
        when {
            explicitPort == null -> {
                httpPort = fallbackHttpPort
                wsPort = if (secure) fallbackHttpPort else nextPort(fallbackHttpPort)
            }
            secure -> {
                httpPort = explicitPort
                wsPort = explicitPort
            }
            scheme == "ws" || scheme == "wss" -> {
                // 用户显式给的是 WS 端口：HTTP 回推一个端口（不小于 1）
                wsPort = explicitPort
                httpPort = if (explicitPort > 1) explicitPort - 1 else explicitPort
            }
            else -> {
                httpPort = explicitPort
                wsPort = nextPort(explicitPort)
            }
        }
        return Result(
            baseUrl = "$httpScheme://$host:$httpPort",
            wsUrl = "$wsScheme://$host:$wsPort"
        )
    }

    /**
     * 是否为本机回环（用于设置页提示"这是手机自己，协议端在 PC 上要填 PC 的 IP"）。
     *
     * 真机实证：用户在 PC 上部署协议端却保留默认回环地址，是"网络已通仍连不上"的第一原因。
     */
    fun isLoopback(rawUrl: String): Boolean {
        val host = runCatching { java.net.URI(rawUrl.trim()).host?.lowercase() }.getOrNull()
            ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    private fun nextPort(port: Int): Int =
        if (port >= 65535) port else port + 1

    private val SCHEMES = setOf("http", "https", "ws", "wss")
}
