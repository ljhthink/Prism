package io.prism.qq.onebot

/**
 * 本机网络信息（诊断用）。
 *
 * @param ipv4 当前活动接口的 IPv4（无网络时为 null）
 * @param prefixLength 该接口前缀长度（如 24）
 * @param gateway 默认网关
 */
data class QqLocalNetwork(
    val ipv4: String?,
    val prefixLength: Int?,
    val gateway: String?
)

/**
 * 协议端连接**分阶段诊断**的纯逻辑（2026-10-26 真机复盘新增）。
 *
 * **为什么必须分阶段**：真机实证的失败原因不止一种，而原实现只有一句
 * 「无法连接（协议端未启动/端口错误）」，把所有情况混为一谈：
 *
 * | 真实现象 | 真实原因 | 原提示 |
 * | --- | --- | --- |
 * | 地址仍是 `127.0.0.1` | 连的是手机自己 | 「无法连接」 |
 * | 同 SSID 但 ARP `INCOMPLETE` | 无线客户端隔离 / 跨 VLAN | 「无法连接」 |
 * | 端口未监听 | 协议端没起来 | 「无法连接」 |
 * | token 不匹配 | 鉴权失败 | 正常（这一类有区分） |
 *
 * 本对象只做**判定**（纯函数，可在 JVM 单测）；真正的探测由
 * [io.prism.qq.QqConnectionDiagnostics] 注入，避免测试依赖网络。
 */
object QqDiagnosticsRules {

    /** 诊断阶段。 */
    enum class Stage { ENDPOINT, SUBNET, TCP, AUTH, WS }

    /** 阶段结果。 */
    enum class Outcome {
        /** 通过。 */
        PASS,

        /** 可继续，但需要注意（例如不同网段仍可能被路由）。 */
        WARN,

        /** 失败，流程终止。 */
        FAIL
    }

    /** 总体结论（供 UI 显示与建议映射）。 */
    enum class Verdict {
        CONNECTED,
        INVALID_ENDPOINT,
        LOOPBACK_ENDPOINT,
        SUBNET_MISMATCH,
        ISOLATED,
        PORT_CLOSED,
        FIREWALL_TIMEOUT,
        AUTH_FAILED,
        WS_FAILED,
        UNKNOWN
    }

    /** 单步结果。 */
    data class Step(
        val stage: Stage,
        val outcome: Outcome,
        val detail: String
    )

    /** 完整报告。 */
    data class Report(
        val verdict: Verdict,
        val steps: List<Step>,
        /** 归一化后的 HTTP 地址（诊断失败时可能为空）。 */
        val baseUrl: String = "",
        val wsUrl: String = "",
        /** 目标 host:port（脱敏无须，IP 非凭据）。 */
        val target: String = "",
        val atMs: Long = 0L
    ) {
        val connected: Boolean get() = verdict == Verdict.CONNECTED

        /** 汇总成一行可读结论（写入设置页「最近一次诊断」）。 */
        fun summary(): String {
            val failed = steps.firstOrNull { it.outcome == Outcome.FAIL }
            return when (verdict) {
                Verdict.CONNECTED -> "已连接（${target}）"
                Verdict.INVALID_ENDPOINT -> "地址配置有误：${failed?.detail.orEmpty()}"
                Verdict.LOOPBACK_ENDPOINT ->
                    "地址是手机自身的回环地址；协议端在 PC 上时应填 PC 的局域网 IP"
                Verdict.SUBNET_MISMATCH -> "手机与协议端不在同一网段：${failed?.detail.orEmpty()}"
                Verdict.ISOLATED ->
                    "同网段但目标不可达（ARP 失败）——通常是无线客户端隔离/跨 VLAN，代码无法绕过"
                Verdict.PORT_CLOSED -> "主机可达但端口未监听：${failed?.detail.orEmpty()}"
                Verdict.FIREWALL_TIMEOUT ->
                    "连接超时（被防火墙丢弃或目标不在线）：${failed?.detail.orEmpty()}"
                Verdict.AUTH_FAILED -> "鉴权失败：${failed?.detail.orEmpty()}"
                Verdict.WS_FAILED -> "HTTP 可用但 WebSocket 握手失败：${failed?.detail.orEmpty()}"
                Verdict.UNKNOWN -> "未通过：${failed?.detail.orEmpty()}"
            }
        }
    }

    /** IPv4 字面量 → 无符号 32 位（非法返回 null）。 */
    fun ipv4ToLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        parts.forEach { part ->
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || part.isEmpty() || part.length > 3) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    /** 两个 IPv4 是否在同一子网。 */
    fun sameSubnet(a: String, prefixLength: Int, b: String): Boolean {
        val x = ipv4ToLong(a) ?: return false
        val y = ipv4ToLong(b) ?: return false
        if (prefixLength !in 0..32) return false
        if (prefixLength == 0) return true
        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return (x and mask) == (y and mask)
    }

    /**
     * TCP 阶段失败分类。
     *
     * 判定顺序很重要：`NoRouteToHost` 与「同网段」组合才指向隔离；单独出现时可能是路由问题。
     */
    fun classifyTcpFailure(exception: Throwable, sameSubnet: Boolean): Pair<Verdict, String> {
        val message = exception.message.orEmpty()
        val name = exception::class.simpleName.orEmpty()
        return when {
            // 主机可达，端口没开：协议端未启动 / 端口填错
            message.contains("ECONNREFUSED", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true) ->
                Verdict.PORT_CLOSED to "端口未监听（协议端未启动或端口填错）"

            // 同网段却路由不可达：二层隔离/跨 VLAN 的典型表现
            message.contains("EHOSTUNREACH", ignoreCase = true) ||
                message.contains("No route to host", ignoreCase = true) ||
                name.contains("NoRouteToHost", ignoreCase = true) ->
                if (sameSubnet) {
                    Verdict.ISOLATED to
                        "同网段但目标不可达（ARP 不通，疑似无线客户端隔离/跨 VLAN）"
                } else {
                    Verdict.SUBNET_MISMATCH to "目标路由不可达（跨网段且无路由）"
                }

            // 超时：防火墙丢包、目标离线、或热点侧禁互访
            message.contains("ETIMEDOUT", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ||
                name.contains("Timeout", ignoreCase = true) ->
                Verdict.FIREWALL_TIMEOUT to "连接超时（被防火墙丢弃或目标不在线）"

            name.contains("UnknownHost", ignoreCase = true) ->
                Verdict.INVALID_ENDPOINT to "地址无法解析"

            else -> Verdict.UNKNOWN to "$name：${message.take(80)}"
        }
    }

    /** 基于已完成阶段的步骤推导总体结论（首个 FAIL 决定结论）。 */
    fun verdictOf(steps: List<Step>): Verdict {
        val failed = steps.firstOrNull { it.outcome == Outcome.FAIL } ?: return Verdict.CONNECTED
        return when (failed.stage) {
            Stage.ENDPOINT -> if (failed.detail.contains("回环")) {
                Verdict.LOOPBACK_ENDPOINT
            } else {
                Verdict.INVALID_ENDPOINT
            }
            Stage.SUBNET -> Verdict.SUBNET_MISMATCH
            Stage.TCP -> when {
                failed.detail.contains("隔离") -> Verdict.ISOLATED
                failed.detail.contains("端口未监听") -> Verdict.PORT_CLOSED
                failed.detail.contains("超时") -> Verdict.FIREWALL_TIMEOUT
                else -> Verdict.UNKNOWN
            }
            Stage.AUTH -> Verdict.AUTH_FAILED
            Stage.WS -> Verdict.WS_FAILED
        }
    }

    /**
     * 拓扑建议（真机实证：同 SSID 同网段仍 ARP 不通时，代码无法解决，必须换拓扑）。
     */
    fun topologyAdvice(verdict: Verdict): String? = when (verdict) {
        Verdict.LOOPBACK_ENDPOINT -> "协议端在 PC 上：把地址改成 PC 的局域网 IP（设置页会显示实际目标）"
        Verdict.ISOLATED, Verdict.SUBNET_MISMATCH ->
            "换拓扑：①手机开热点、PC 连热点（推荐）②家用路由器同一 LAN ③协议端改跑在手机 Termux"
        Verdict.PORT_CLOSED -> "在 PC 上确认 NapCat 已启动、HTTP/WS 端口与填写一致"
        Verdict.FIREWALL_TIMEOUT -> "检查 PC 防火墙是否放行 3000/3001，或换网络拓扑"
        Verdict.AUTH_FAILED -> "核对 access_token（NapCat WebUI → 网络配置）"
        Verdict.WS_FAILED -> "确认 WS 服务已启用（NapCat 需单独开启 WebSocket 服务）"
        else -> null
    }
}
