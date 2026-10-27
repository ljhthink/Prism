package io.prism.qq.onebot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Random

/**
 * 协议端连接**分阶段诊断**（2026-10-26 真机复盘新增；PRD US-3120）。
 *
 * 顺序：地址归一化 → 本机网段对照 → TCP → HTTP 鉴权 → WS 握手。
 * 每一步都产出可读结论，**首个失败即终止**并给出可执行建议。
 *
 * **真机依据**（2026-10-26）：
 * - 手机与 PC 同 SSID、同 `/18` 网段，但网关 ARP `REACHABLE`、目标 ARP `INCOMPLETE`，
 *   双向 ping 全丢 → 无线客户端隔离 / 跨 VLAN，**应用层无法绕过**，必须换拓扑；
 * - 同一时刻 App 内保存的仍是 `127.0.0.1` → 即使网络放通也连的是手机自己。
 *
 * 因此本诊断把这两类原因分开呈现，不再统一显示「无法连接」。
 */
class QqConnectionDiagnostics(
    private val localNetwork: suspend () -> QqLocalNetwork,
    private val tcpProbe: suspend (host: String, port: Int, timeoutMs: Int) -> Unit,
    private val httpLoginProbe: suspend (baseUrl: String, token: String) -> HttpProbeResult,
    private val wsProbe: suspend (wsUrl: String, token: String) -> WsProbeResult = ::rawWsHandshake,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** HTTP `get_login_info` 探测结果。 */
    data class HttpProbeResult(
        val reachable: Boolean,
        val httpStatus: Int = 0,
        val ok: Boolean = false,
        val selfId: Long = 0L,
        val nickname: String = "",
        val detail: String = ""
    )

    /** WS 握手探测结果。 */
    data class WsProbeResult(val ok: Boolean, val httpStatus: Int = 0, val detail: String = "")

    /**
     * 执行诊断（**不修改任何配置、不发送消息**）。
     *
     * @param rawInput 用户输入的地址（可以只是 IP 或 IP:端口）
     * @param token 访问令牌（为空时跳过鉴权阶段并给出提示）
     */
    suspend fun run(rawInput: String, token: String): QqDiagnosticsRules.Report =
        withContext(Dispatchers.IO) {
            val steps = mutableListOf<QqDiagnosticsRules.Step>()
            val normalized = OneBotEndpoint.normalize(rawInput)
            if (!normalized.ok) {
                steps += fail(
                    QqDiagnosticsRules.Stage.ENDPOINT,
                    normalized.error ?: "地址非法"
                )
                return@withContext finish(steps, normalized)
            }
            steps += pass(
                QqDiagnosticsRules.Stage.ENDPOINT,
                "HTTP ${normalized.baseUrl} · WS ${normalized.wsUrl}"
            )
            // 回环地址：即使可达也只连到手机自己——直接判失败并给指引
            if (OneBotEndpoint.isLoopback(normalized.baseUrl)) {
                steps += fail(
                    QqDiagnosticsRules.Stage.SUBNET,
                    "回环地址（手机自身），不可能是 PC 上的协议端"
                )
                return@withContext finish(steps, normalized)
            }

            val host = runCatching { java.net.URI(normalized.baseUrl).host }.getOrNull().orEmpty()
            val httpPort = runCatching { java.net.URI(normalized.baseUrl).port }
                .getOrDefault(OneBotEndpoint.DEFAULT_HTTP_PORT)

            // 2) 网段对照
            val local = runCatching { localNetwork() }.getOrNull()
            val sameSubnet = local?.ipv4 != null && local.prefixLength != null &&
                QqDiagnosticsRules.sameSubnet(local.ipv4, local.prefixLength, host)
            val subnetDetail = buildString {
                append("本机 ")
                append(local?.ipv4 ?: "未知")
                if (local?.prefixLength != null) append("/${local.prefixLength}")
                append(" · 网关 ")
                append(local?.gateway ?: "未知")
                append(" · 目标 ")
                append(host)
                append(if (sameSubnet) "（同网段）" else "（不同网段）")
            }
            steps += if (sameSubnet) {
                pass(QqDiagnosticsRules.Stage.SUBNET, subnetDetail)
            } else {
                warn(QqDiagnosticsRules.Stage.SUBNET, subnetDetail + "，跨网段需路由可达")
            }

            // 3) TCP
            try {
                tcpProbe(host, httpPort, TCP_TIMEOUT_MS)
                steps += pass(QqDiagnosticsRules.Stage.TCP, "$host:$httpPort 可达")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                var (verdict, detail) = QqDiagnosticsRules.classifyTcpFailure(e, sameSubnet)
                // 2026-10-27（ADR-068）：超时/不可达 + 地址不在当前网络内 → 判为"旧局域网地址"。
                // 真机实证：离开热点后地址仍是 10.92.122.126，只报"连接超时"会把人引去查防火墙。
                val timeoutLike = verdict == QqDiagnosticsRules.Verdict.FIREWALL_TIMEOUT ||
                    verdict == QqDiagnosticsRules.Verdict.UNKNOWN
                if (timeoutLike && QqDiagnosticsRules.isStaleLanAddress(
                        sameSubnet = sameSubnet,
                        cellOnly = QqDiagnosticsRules.isCellOnly(local?.interfaceName),
                        tcpFailedWithTimeout = true
                    )
                ) {
                    verdict = QqDiagnosticsRules.Verdict.STALE_LAN_ADDRESS
                    detail = "该地址不在当前网络内（本机无对应网段接口）"
                }
                steps += fail(QqDiagnosticsRules.Stage.TCP, detail)
                return@withContext finish(steps, normalized, verdictOverride = verdict)
            }

            // 4) HTTP 鉴权
            if (token.isBlank()) {
                steps += fail(QqDiagnosticsRules.Stage.AUTH, "未配置 access_token（协议端必须鉴权）")
                return@withContext finish(steps, normalized)
            }
            val http = try {
                httpLoginProbe(normalized.baseUrl, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HttpProbeResult(reachable = false, detail = e::class.simpleName.orEmpty())
            }
            when {
                !http.reachable -> {
                    steps += fail(
                        QqDiagnosticsRules.Stage.AUTH,
                        "HTTP 不可达：${http.detail.ifBlank { "无响应" }}"
                    )
                    return@withContext finish(steps, normalized)
                }
                http.httpStatus == 401 || http.httpStatus == 403 -> {
                    steps += fail(
                        QqDiagnosticsRules.Stage.AUTH,
                        "鉴权被拒（HTTP ${http.httpStatus}），access_token 不匹配"
                    )
                    return@withContext finish(steps, normalized)
                }
                !http.ok -> {
                    steps += fail(
                        QqDiagnosticsRules.Stage.AUTH,
                        "接口返回异常：${http.detail.ifBlank { "retcode 非 0" }}"
                    )
                    return@withContext finish(steps, normalized)
                }
                else -> steps += pass(
                    QqDiagnosticsRules.Stage.AUTH,
                    "登录信息 OK：${http.nickname.ifBlank { "未知昵称" }}" +
                        if (http.selfId > 0) "（self_id=${http.selfId}）" else ""
                )
            }

            // 5) WS 握手
            val ws = try {
                wsProbe(normalized.wsUrl, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WsProbeResult(ok = false, detail = e::class.simpleName.orEmpty())
            }
            if (ws.ok) {
                steps += pass(QqDiagnosticsRules.Stage.WS, "WebSocket 握手成功")
            } else {
                steps += fail(
                    QqDiagnosticsRules.Stage.WS,
                    if (ws.httpStatus == 401 || ws.httpStatus == 403) {
                        "WS 鉴权被拒（HTTP ${ws.httpStatus}）"
                    } else {
                        "WS 握手失败：${ws.detail.ifBlank { "无响应" }}"
                    }
                )
            }
            finish(steps, normalized)
        }

    private fun finish(
        steps: List<QqDiagnosticsRules.Step>,
        normalized: OneBotEndpoint.Result,
        verdictOverride: QqDiagnosticsRules.Verdict? = null
    ): QqDiagnosticsRules.Report {
        val verdict = verdictOverride ?: QqDiagnosticsRules.verdictOf(steps)
        return QqDiagnosticsRules.Report(
            verdict = verdict,
            steps = steps,
            baseUrl = normalized.baseUrl,
            wsUrl = normalized.wsUrl,
            target = normalized.displayTarget,
            atMs = clock()
        )
    }

    private fun pass(stage: QqDiagnosticsRules.Stage, detail: String) =
        QqDiagnosticsRules.Step(stage, QqDiagnosticsRules.Outcome.PASS, detail)

    private fun warn(stage: QqDiagnosticsRules.Stage, detail: String) =
        QqDiagnosticsRules.Step(stage, QqDiagnosticsRules.Outcome.WARN, detail)

    private fun fail(stage: QqDiagnosticsRules.Stage, detail: String) =
        QqDiagnosticsRules.Step(stage, QqDiagnosticsRules.Outcome.FAIL, detail)

    companion object {
        private const val TCP_TIMEOUT_MS = 3_000
        private const val IO_TIMEOUT_MS = 4_000

        /**
         * 生产用实例：本机网络信息来自 [ConnectivityManager]，其余用裸 Socket/HTTP 探测。
         *
         * 不注入 Ktor 客户端——诊断需要在"配置尚未保存"时也能跑，且需要拿到底层
         * TCP/HTTP 的**具体失败类型**（ECONNREFUSED / EHOSTUNREACH / timeout），
         * 这些信息在 Ktor 异常里会被统一包装。
         */
        fun create(context: Context): QqConnectionDiagnostics {
            val appContext = context.applicationContext
            return QqConnectionDiagnostics(
                localNetwork = { readLocalNetwork(appContext) },
                tcpProbe = { host, port, timeoutMs ->
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                    }
                },
                httpLoginProbe = { baseUrl, token -> probeHttpLogin(baseUrl, token) }
            )
        }

        private fun readLocalNetwork(context: Context): QqLocalNetwork {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return QqLocalNetwork(null, null, null)
            val network = cm.activeNetwork ?: return QqLocalNetwork(null, null, null)
            val lp: LinkProperties = cm.getLinkProperties(network)
                ?: return QqLocalNetwork(null, null, null)
            // 只取 IPv4 与默认网关：IPv6-only / 无 IPv4 时返回 null，由上层按"未知"处理
            val ipv4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            val gateway = lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
            return QqLocalNetwork(
                ipv4 = ipv4?.address?.hostAddress,
                prefixLength = ipv4?.prefixLength,
                gateway = gateway,
                // 2026-10-27：接口名用于识别"只剩蜂窝网"（rmnet*），
                // 从而把"超时"正确归因为旧局域网地址而非防火墙
                interfaceName = lp.interfaceName
            )
        }

        private fun probeHttpLogin(baseUrl: String, token: String): HttpProbeResult {
            val url = java.net.URL("${baseUrl.trimEnd('/')}/get_login_info")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                instanceFollowRedirects = false
            }
            return try {
                conn.outputStream.use { it.write("{}".toByteArray()) }
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val response = OneBotModels.parseResponse(body)
                HttpProbeResult(
                    reachable = true,
                    httpStatus = status,
                    ok = response.ok,
                    selfId = response.data
                        ?.let { data ->
                            runCatching {
                                data.jsonObject["user_id"]?.jsonPrimitive?.longOrNull ?: 0L
                            }.getOrDefault(0L)
                        } ?: 0L,
                    nickname = runCatching {
                        response.data?.jsonObject?.get("nickname")
                            ?.jsonPrimitive?.contentOrNull.orEmpty()
                    }.getOrDefault(""),
                    detail = response.message ?: if (status !in 200..299) "HTTP $status" else ""
                )
            } finally {
                runCatching { conn.disconnect() }
            }
        }

        /** 裸 Socket 完成 WebSocket 握手（只读状态行，随后立即关闭）。 */
        private fun rawWsHandshake(wsUrl: String, token: String): WsProbeResult {
            val uri = java.net.URI(wsUrl)
            val host = uri.host ?: return WsProbeResult(false, detail = "WS 地址缺少主机")
            val port = if (uri.port > 0) uri.port else 80
            val key = Base64.getEncoder().encodeToString(
                ByteArray(16).also { Random().nextBytes(it) }
            )
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), IO_TIMEOUT_MS)
                    socket.soTimeout = IO_TIMEOUT_MS
                    val request = buildString {
                        append("GET / HTTP/1.1\r\n")
                        append("Host: $host:$port\r\n")
                        append("Authorization: Bearer $token\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $key\r\n")
                        append("Sec-WebSocket-Version: 13\r\n\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(request.toByteArray())
                        flush()
                    }
                    val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val status = statusLine.split(' ')
                        .getOrNull(1)?.toIntOrNull() ?: 0
                    if (status == 101) {
                        WsProbeResult(true, status, "101 Switching Protocols")
                    } else {
                        WsProbeResult(false, status, statusLine.take(80))
                    }
                }
            } catch (e: Exception) {
                WsProbeResult(false, detail = e::class.simpleName.orEmpty() + "：" +
                    e.message.orEmpty().take(60))
            }
        }
    }
}
