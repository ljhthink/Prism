package io.prism.qq.onebot

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OneBot 11 薄客户端（ADR-064；PRD US-3110 / US-3111）。
 *
 * **为什么自写而不引入 Bot 框架**：AstrBot/LangBot/MaiBot 都是「接入 + LLM + 人设 + 插件」
 * 的完整平台，与 Prism 的 LLM/记忆/人设职责重叠，且含 AGPL/GPL 许可；现成 Kotlin 库
 * 维护状态亦不足以作为 P0 依赖。OneBot 11 本质是 JSON 事件 + `action/echo`，自写成本更低。
 *
 * **鉴权**：`access_token` 必填；为空时拒绝连接/调用（裸端点等于任何人可冒充小号）。
 * 仅允许本机/私网地址（严格 IP 字面量判定，见 [OneBotModels.isPrivateEndpoint]）。
 *
 * **离线语义**（US-3110 AC-5/AC-6）：重连期间调用方**不发送、不堆积补发**；
 * 离线是通道故障而非账号故障，不应触发账号熔断。
 *
 * **可测性**（guardrail L-07）：[httpClient] 与 [logger] 可注入——JVM 测试无需 Android。
 */
/**
 * OneBot 能力接口（guardrail L-07）：便于 [io.prism.qq.channel.OneBotQqChannel] 在 JVM 测试中
 * 注入假实现（解析/误投递防护用例），避免依赖真实 WebSocket。
 */
interface OneBotApi {
    val state: StateFlow<OneBotClient.State>
    val events: SharedFlow<JsonObject>
    val connectedSelfId: Long

    suspend fun call(action: String, paramsJson: String): OneBotModels.Response
}

class OneBotClient(
    private val connectionProvider: suspend () -> OneBotConnection,
    httpClient: HttpClient? = null,
    private val logger: (String) -> Unit = { Log.w(TAG, it) }
) : OneBotApi {

    /** 连接参数（每次重连前重新读取，使设置页改配置即时生效）。 */
    data class OneBotConnection(
        val baseUrl: String,
        val wsUrl: String,
        val token: String,
        val selfId: Long
    ) {
        /** 防 token 随日志/崩溃上报泄漏（guardrail L-03）。 */
        override fun toString(): String =
            "OneBotConnection(baseUrl=$baseUrl, wsUrl=$wsUrl, token=${if (token.isBlank()) "<empty>" else "***"}, selfId=$selfId)"
    }

    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(val selfId: Long) : State
        data class Failed(val reason: String) : State
    }

    private val http: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 12_000
            socketTimeoutMillis = 30_000
        }
        engine {
            config {
                // 固定私网端点无需跟随重定向（guardrail L-05）
                followRedirects(false)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Disconnected)
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    /** 当前生效的 self_id（连接成功时更新；供事件过滤）。 */
    @Volatile
    override var connectedSelfId: Long = 0L
        private set

    private var droppedEventCount: Long = 0L
    private var lastDropLogAtMs: Long = 0L

    /**
     * 最近一次收到**任意帧**的时刻（含心跳元事件）。
     *
     * 用于识别**半开连接**：TCP 已死但本端未收到 FIN 时，`incoming` 会一直挂起、不抛异常，
     * 表现为"显示已连接但永远收不到消息"。真机（2026-10-26）在 WiFi 切换后出现过该形态。
     */
    @Volatile
    private var lastFrameAtMs: Long = 0L

    /** 网络变化时立即打断退避等待（由系统 `NetworkCallback` 触发）。 */
    private val reconnectSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 请求**立即重连**（网络恢复/切换时调用）。
     *
     * 原实现失败后最长要等 30s 退避才重试；用户在设置页切 WiFi/热点后往往在 30s 内
     * 就判定"还是连不上"。本方法让退避等待可被网络事件打断，并把退避重置为初始值。
     */
    fun nudgeReconnect() {
        reconnectSignal.tryEmit(Unit)
    }

    /** 启动连接循环（幂等；与 [stop] 串行化）。 */
    suspend fun start() = lifecycleMutex.withLock {
        if (job?.isActive == true) return@withLock
        job = scope.launch { runLoop() }
    }

    /** 停止并等待连接协程退出（配置切到无障碍通道/连接参数变更时调用）。 */
    suspend fun stop() = lifecycleMutex.withLock {
        job?.cancelAndJoinSafely()
        job = null
        connectedSelfId = 0L
        _state.value = State.Disconnected
    }

    private suspend fun Job.cancelAndJoinSafely() {
        cancel()
        runCatching { join() }
    }

    /** 彻底释放底层资源（进程退出时调用；一般不调用，客户端随进程存活）。 */
    fun close() {
        job?.cancel()
        job = null
        scope.cancel()
        http.close()
    }

    private suspend fun runLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        var lastConfigLogAtMs = 0L
        while (currentCoroutineContext().isActive) {
            val cfg = readConnection()
            if (cfg == null || cfg.token.isBlank()) {
                _state.value = State.Failed("未配置 access_token（协议端必须鉴权）")
                // 2026-10-25：配置缺失**必须可观测**——原实现只改状态、零日志，真机上表现为
                // "协议端像在工作但永远收不到消息"，无法定位。限频 60s，且不输出任何凭据。
                val now = System.currentTimeMillis()
                if (now - lastConfigLogAtMs >= 60_000L) {
                    lastConfigLogAtMs = now
                    logger("协议端未就绪：access_token 为空（请在设置 → QQ 小号助手 → 协议端连接中配置）")
                }
                delay(RETRY_AFTER_INVALID_CONFIG_MS)
                continue
            }
            if (!OneBotModels.isPrivateEndpoint(cfg.baseUrl) ||
                !OneBotModels.isPrivateEndpoint(cfg.wsUrl)
            ) {
                _state.value = State.Failed(
                    "协议端地址必须是本机/私网 IPv4 字面量（安全限制）；如需公网请使用 HTTPS 反向代理"
                )
                val now = System.currentTimeMillis()
                if (now - lastConfigLogAtMs >= 60_000L) {
                    lastConfigLogAtMs = now
                    logger("协议端未就绪：地址不是本机/私网 IPv4 字面量（安全限制）")
                }
                delay(RETRY_AFTER_INVALID_CONFIG_MS)
                continue
            }
            _state.value = State.Connecting
            var handshakeFailure: String? = null
            var idleTimeout = false
            try {
                http.webSocket(
                    urlString = cfg.wsUrl,
                    request = { header(HttpHeaders.Authorization, "Bearer ${cfg.token}") }
                ) {
                    // M-10：self_id 未配置时用 get_login_info 自动获取，避免事件过滤退化
                    val selfId = if (cfg.selfId > 0) cfg.selfId else fetchSelfId()
                    if (selfId <= 0) {
                        handshakeFailure = "未配置 self_id，且无法从协议端获取登录信息"
                        return@webSocket
                    }
                    connectedSelfId = selfId
                    _state.value = State.Connected(selfId)
                    backoffMs = INITIAL_BACKOFF_MS
                    lastFrameAtMs = System.currentTimeMillis()
                    logger("OneBot WS 已连接（${describeEndpoint(cfg.wsUrl)}）")
                    // 半开连接看门狗：任何帧（含心跳）都会刷新 lastFrameAtMs；
                    // 超过 IDLE_TIMEOUT_MS 无帧即主动关闭，让外层重连（而不是永久挂起）。
                    val watchdog = launch {
                        while (isActive) {
                            delay(WATCHDOG_INTERVAL_MS)
                            val idleMs = System.currentTimeMillis() - lastFrameAtMs
                            if (idleMs >= IDLE_TIMEOUT_MS) {
                                idleTimeout = true
                                logger("连接空闲 ${idleMs / 1000}s（无任何帧），判定为半开连接，主动重连")
                                runCatching {
                                    close(CloseReason(CloseReason.Codes.GOING_AWAY, "idle"))
                                }
                                break
                            }
                        }
                    }
                    for (frame in incoming) {
                        lastFrameAtMs = System.currentTimeMillis()
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        if (text.length > MAX_FRAME_CHARS) {
                            logger("丢弃超长事件帧（${text.length} 字符）")
                            continue
                        }
                        val json = runCatching { Json.parseToJsonElement(text).jsonObject }
                            .getOrNull() ?: continue
                        if (!_events.tryEmit(json)) {
                            droppedEventCount++
                            val now = System.currentTimeMillis()
                            if (now - lastDropLogAtMs > DROP_LOG_INTERVAL_MS) {
                                lastDropLogAtMs = now
                                logger("事件缓冲已满，累计丢弃 $droppedEventCount 条")
                            }
                        }
                    }
                    watchdog.cancel()
                }
                if (currentCoroutineContext().isActive) {
                    // guardrail L-13：握手失败原因不得被"对端关闭"覆盖
                    _state.value = State.Failed(
                        handshakeFailure ?: if (idleTimeout) {
                            "连接空闲超时（已自动重连）"
                        } else {
                            "连接已被对端关闭"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记录异常类型与短消息；异常 message 可能含 URL（不含 token 头）。
                // 2026-10-26：必须带目标 host:port——原日志只有"无法连接"，真机上无法区分
                // "地址是回环/跨网段/端口错/防火墙"（见 ADR-066）。
                logger("OneBot WS 断开（${describeEndpoint(cfg.wsUrl)}）：${describeFailure(e)}")
            }
            if (!currentCoroutineContext().isActive) return
            connectedSelfId = 0L
            // 网络变化可打断退避并重置（否则用户切换网络后仍要等最长 30s）
            val nudged = withTimeoutOrNull(backoffMs) { reconnectSignal.first() } != null
            if (nudged) {
                backoffMs = INITIAL_BACKOFF_MS
                logger("网络变化：立即重连")
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** `ws://host:port/...` → `host:port`（日志用，不含 token/路径）。 */
    private fun describeEndpoint(rawUrl: String): String = runCatching {
        val uri = java.net.URI(rawUrl)
        "${uri.host}:${if (uri.port > 0) uri.port else 80}"
    }.getOrDefault("未知地址")

    /** 把常见异常映射为**可执行**的可读原因（guardrail H-02 / M-06）。 */
    private fun describeFailure(e: Exception): String {
        val name = e::class.simpleName ?: "Exception"
        val message = e.message.orEmpty()
        return when {
            message.contains("CLEARTEXT", ignoreCase = true) ->
                "系统禁止明文流量：请使用 https/wss 反向代理，或在网络安全配置中放行私网明文"
            message.contains("401", ignoreCase = true) || message.contains("403", ignoreCase = true) ->
                "鉴权失败（access_token 可能不正确）"
            message.contains("timeout", ignoreCase = true) -> "连接超时（地址不可达或协议端未启动）"
            name.contains("UnknownHost", ignoreCase = true) -> "地址无法解析（请检查 IP/端口）"
            name.contains("Connect", ignoreCase = true) -> "无法连接（协议端未启动/端口错误）"
            else -> "$name：${message.take(80)}"
        }
    }

    /** 调 `get_login_info` 获取真实 self_id（M-10）；失败返回 0。 */
    private suspend fun fetchSelfId(): Long {
        val response = call("get_login_info", "{}")
        if (!response.ok) return 0L
        return response.data?.jsonObject?.get("user_id")?.jsonPrimitive?.longOrNull ?: 0L
    }

    /**
     * 调用一个 OneBot action（HTTP）。
     *
     * @param action 如 `send_private_msg` / `get_friend_msg_history`
     * @param paramsJson 已序列化的参数对象
     */
    override suspend fun call(action: String, paramsJson: String): OneBotModels.Response {
        val cfg = readConnection()
            ?: return OneBotModels.Response(false, -1, null, "协议端未配置")
        if (cfg.token.isBlank()) {
            return OneBotModels.Response(false, -1, null, "未配置 access_token（协议端必须鉴权）")
        }
        if (!OneBotModels.isPrivateEndpoint(cfg.baseUrl)) {
            return OneBotModels.Response(
                false, -1, null,
                "协议端地址必须是本机/私网 IPv4 字面量（安全限制）"
            )
        }
        val url = "${cfg.baseUrl.trimEnd('/')}/$action"
        return try {
            val body = http.post(url) {
                header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
                contentType(ContentType.Application.Json)
                setBody(paramsJson)
            }.bodyAsText()
            OneBotModels.parseResponse(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("OneBot action=$action 失败：${describeFailure(e)}")
            OneBotModels.Response(false, -1, null, describeFailure(e))
        }
    }

    /** 读取连接参数（可空），**不吞取消异常**（guardrail L-12）。 */
    private suspend fun readConnection(): OneBotConnection? = try {
        connectionProvider()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "OneBotClient"

        /** 指数退避：1s → 2s → 4s → … → 30s 封顶。 */
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** 配置非法时的重试间隔（不参与退避，避免刷屏）。 */
        private const val RETRY_AFTER_INVALID_CONFIG_MS = 30_000L

        /** 单帧字符上限（约 1MB UTF-8 上界；超长视为异常报文）。 */
        private const val MAX_FRAME_CHARS = 1_000_000

        /** 丢事件日志的最小间隔（防刷屏）。 */
        private const val DROP_LOG_INTERVAL_MS = 60_000L

        /** 半开连接看门狗检查间隔。 */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /**
         * 无任何帧的最长容忍时间。
         *
         * NapCat 默认每 5s 发一次 `meta_event` 心跳，90s 无帧（≈18 个心跳周期）可确定链路已死。
         */
        private const val IDLE_TIMEOUT_MS = 90_000L
    }
}
