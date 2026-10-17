package io.prism.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 批次23-A（ADR-048，A4）：云端语音引擎连通性自检（设置页「语音引擎」区按钮）。
 *
 * **实现方式**：独立轻连接探测（不复用引擎实例、不触碰 cloud/ 已有公开行为）——
 * - ASR：DashScope WS 握手（`Authorization: Bearer <Key>`）→ 发 run-task → 等待
 *   `task-started`（复用 [io.prism.voice.cloud.AliCloudAsrEngine] 公开纯函数
 *   buildRunTaskCommand/extractEvent/parseTaskFailed 拼帧与解析）；
 * - TTS：MiniMax T2A v2 WS 握手 → 发 task_start → 等待 `task_started`（复用
 *   [io.prism.voice.cloud.buildTaskStartEvent] / [io.prism.voice.cloud.parseServerEvent]）。
 *
 * **探测预算**：3s（对齐两引擎 connectTimeout/启动探测口径，ADR-048 决策 3）；结果
 * null = 自检通过，非 null = 失败原因（可读中文，UI 直接展示）。探测成功后立即 cancel
 * 连接（不发 finish/task_finish——服务端对未推流的短命任务按超时回收，不计费）。
 *
 * **Key 安全**：Key/GroupId 仅进请求头/URL，不落日志（CWE-532 口径，与引擎同规则）。
 *
 * **线程**：阻塞等待（CountDownLatch），调用方须在 IO 线程执行（SettingsViewModel
 * withContext(Dispatchers.IO)）。
 */
object CloudVoiceSelfCheck {

    /** 探测预算（ms；ADR-048 决策 3 的 3s 口径）。 */
    private const val PROBE_TIMEOUT_MS = 3_000L

    /** 阿里云百炼实时识别 WS 服务地址（与 AliCloudAsrEngine 私有常量同源，官方文档 2026-09 版原样含尾斜杠）。 */
    private const val DASHSCOPE_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"

    /**
     * MiniMax T2A v2 WS 端点——CN 站（与 [io.prism.voice.cloud.MiniMaxTtsEngine] 私有常量
     * WS_URL 同源同步，BUG-20260908-001 一并修复；国际站账号为 api.minimax.io）。
     */
    private const val MINIMAX_WS_URL = "wss://api.minimaxi.com/ws/v1/t2a_v2"

    /** 独立探测客户端（不触碰引擎共享 CLIENT；连接与读均 3s 预算）。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 云端 ASR 连通性自检：WS 握手 + run-task → task-started（3s 预算）。
     *
     * @param dashScopeKey DashScope API Key 明文（调用方经 ApiKeyRepository 解密读取）
     * @return null = 自检通过；非 null = 失败原因（UI 展示文案）
     */
    fun checkAliCloudAsr(dashScopeKey: String): String? {
        if (dashScopeKey.isBlank()) return "未配置 DashScope API Key"
        val failure = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val request = Request.Builder()
            .url(DASHSCOPE_WS_URL)
            .header("Authorization", "Bearer $dashScopeKey")  // Key 不落日志（CWE-532）
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    io.prism.voice.cloud.AliCloudAsrEngine.buildRunTaskCommand(UUID.randomUUID().toString())
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (io.prism.voice.cloud.AliCloudAsrEngine.extractEvent(text)) {
                    "task-started" -> latch.countDown()  // failure 保持 null = 通过
                    "task-failed" -> {
                        val (code, message) = io.prism.voice.cloud.AliCloudAsrEngine.parseTaskFailed(text)
                            ?: ("未知" to "服务端未提供失败详情")
                        failure.compareAndSet(null, "握手失败（$code：$message）——请检查 Key 是否有效")
                        latch.countDown()
                    }
                    else -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.compareAndSet(null, "连接失败（${t::class.simpleName}）——请检查网络与 DashScope Key")
                latch.countDown()
            }
        })
        val timedOut = !latch.await(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
        runCatching { ws.cancel() }
        return when {
            failure.get() != null -> failure.get()!!
            timedOut -> "连接超时（${PROBE_TIMEOUT_MS / 1000}s 内未收到 task-started）——请检查网络"
            else -> null
        }
    }

    /**
     * 云端 TTS 连通性自检：WS 握手 + task_start → task_started（3s 预算）。
     *
     * @param apiKey MiniMax API Key 明文
     * @param groupId MiniMax 账号 GroupId 明文
     * @return null = 自检通过；非 null = 失败原因（UI 展示文案）
     */
    fun checkMiniMaxTts(apiKey: String, groupId: String): String? {
        if (apiKey.isBlank() || groupId.isBlank()) return "未配置 MiniMax API Key 或 GroupId"
        val failure = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val request = Request.Builder()
            .url("$MINIMAX_WS_URL?GroupId=${URLEncoder.encode(groupId, "UTF-8")}")
            .header("Authorization", "Bearer $apiKey")  // Key 不落日志（CWE-532）
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    io.prism.voice.cloud.buildTaskStartEvent(
                        io.prism.voice.cloud.MiniMaxTtsConfig(apiKey = apiKey, groupId = groupId)
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = io.prism.voice.cloud.parseServerEvent(text)) {
                    is io.prism.voice.cloud.MiniMaxEvent.TaskStarted -> latch.countDown()  // 通过
                    is io.prism.voice.cloud.MiniMaxEvent.TaskFailed -> {
                        failure.compareAndSet(null, "握手失败（${event.message}）——请检查 Key/GroupId 是否有效")
                        latch.countDown()
                    }
                    else -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // MiniMax 在 HTTP 层完成鉴权：握手被拒（http=200+JSON 错误体）通常是 Key 与
                // 端点站点不匹配或 Key/GroupId 无效——文案给出该线索；不输出请求头防 Key 泄露
                val http = response?.code
                failure.compareAndSet(
                    null,
                    "连接失败（${t::class.simpleName}，http=${http ?: "-"}）" +
                        "——请检查网络、Key/GroupId 是否有效，以及 Key 是否为国内站（platform.minimaxi.com）Key"
                )
                latch.countDown()
            }
        })
        val timedOut = !latch.await(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
        runCatching { ws.cancel() }
        return when {
            failure.get() != null -> failure.get()!!
            timedOut -> "连接超时（${PROBE_TIMEOUT_MS / 1000}s 内未收到 task_started）——请检查网络"
            else -> null
        }
    }
}
