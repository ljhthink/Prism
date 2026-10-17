package io.prism.voice.cloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 批次23-A（ADR-048）：MiniMax T2A v2 云端 TTS 配置。
 *
 * **ApiKey 存储**（复用 [io.prism.security.ApiKeyRepository]，本地加密不落明文）：
 * 按仓库既有"一键一值"模式拆为两个条目（apiKey 与 groupId 生命周期不同，
 * groupId 明文度低但与 Key 绑定改绑账号，不宜拼复合串）：
 * - [KEY_API_KEY] = `"cloud_tts_minimax"` → MiniMax API Key（Bearer 鉴权）
 * - [KEY_GROUP_ID] = `"cloud_tts_minimax_group"` → MiniMax 账号 GroupId（URL 查询参数）
 *
 * 上层装配批次经 `readApiKeyOnce(KEY_API_KEY)` / `readApiKeyOnce(KEY_GROUP_ID)`
 * 读取后构造本配置（本批次不触碰设置页与降级链装配——ADR-048 双轨降级链为上层职责）。
 */
data class MiniMaxTtsConfig(
    /** MiniMax API Key（Bearer 鉴权；只经 [io.prism.security.ApiKeyRepository] 读写，绝不硬编码/落日志）。 */
    val apiKey: String,
    /** MiniMax 账号 GroupId（WS URL 查询参数）。 */
    val groupId: String,
    /** 音色 id（官方系统音色，默认男声；后续批次 RolePack 框架可按角色覆盖）。 */
    val voiceId: String = DEFAULT_VOICE_ID,
    /** 语速 0.5~2.0，默认 1.0。 */
    val speed: Float = 1.0f,
    /** 合成模型（官方 T2A v2 当前模型枚举，见 DEFAULT_MODEL 注释）。 */
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        /** ApiKeyRepository 条目键：MiniMax TTS API Key。 */
        const val KEY_API_KEY = "cloud_tts_minimax"

        /** ApiKeyRepository 条目键：MiniMax GroupId（与 Key 分条存储，同"一键一值"模式）。 */
        const val KEY_GROUP_ID = "cloud_tts_minimax_group"

        /** 官方默认系统音色（青涩青年男声，官方 T2A v2 文档示例音色；RolePack 批次可覆盖）。 */
        const val DEFAULT_VOICE_ID = "male-qn-qingse"

        /**
         * 合成模型（2026-09 官方文档核对 platform.minimax.io T2A v2 模型枚举：
         * speech-2.8-hd / speech-2.8-turbo / speech-2.6-hd / speech-2.6-turbo /
         * speech-02-hd / speech-02-turbo / speech-01-*）。批次说明中的
         * "speech-2.5-hd-preview" 已不在官方枚举，按文档以 2.8-hd 旗舰为默认；
         * 若真机验证需要更低延迟可换 speech-2.6-turbo（配置项可覆盖）。
         */
        const val DEFAULT_MODEL = "speech-2.8-hd"
    }
}

/**
 * 批次23-A（ADR-048）：T2A v2 服务端事件（解析结果的封闭模型，纯 JVM 可单测）。
 *
 * 官方协议服务端事件：`connected_success` / `task_started` / `task_continued` /
 * `assistant_response`（文本回显）/ `data`（hex 音频分片）/ `task_finished` / `task_failed`。
 */
sealed class MiniMaxEvent {
    /** 任务已就绪（引擎于此时发 task_continue/task_finish 并回调 onStart）。 */
    data object TaskStarted : MiniMaxEvent()

    /** hex 音频分片（已解码为 PCM 16bit 24kHz mono 字节）。 */
    data class AudioChunk(val bytes: ByteArray) : MiniMaxEvent()

    /** 任务完成（本句播完）。 */
    data object TaskFinished : MiniMaxEvent()

    /** 任务失败（task_failed 事件或任意事件携带 base_resp.status_code != 0）。 */
    data class TaskFailed(val message: String) : MiniMaxEvent()

    /** 信息性事件（connected_success/assistant_response/无音频的 task_continued ACK 等）——无操作。 */
    data object Unknown : MiniMaxEvent()
}

/**
 * 批次23-A（ADR-048）：构造 task_start 事件（官方 T2A v2 WS 协议，2026-09 文档核对）。
 *
 * **协议说明**（官方文档与批次说明的差异已按官方文档修正）：
 * - 现行官方协议 task_start **不含 task_id**（旧版协议字段，已弃用）；
 * - `voice_setting`：voice_id 必填，speed/vol/pitch 可选；
 * - `audio_setting`：本引擎请求 **PCM 24kHz 单声道**（无 bitrate——仅压缩格式需要），
 *   与 [MiniMaxTtsEngine] 的 AudioTrack 参数严格一致（服务端按请求格式回包）。
 */
fun buildTaskStartEvent(config: MiniMaxTtsConfig): String = buildJsonObject {
    put("event", "task_start")
    put("model", config.model)
    put("voice_setting", buildJsonObject {
        put("voice_id", config.voiceId)
        put("speed", config.speed)
        put("vol", 1.0)
        put("pitch", 0)
    })
    put("audio_setting", buildJsonObject {
        put("sample_rate", MiniMaxTtsEngine.SAMPLE_RATE)
        put("format", "pcm")
        put("channel", 1)
    })
}.toString()

/**
 * 批次23-A（ADR-048）：构造 task_continue 事件（文本为**顶层字段**——现行官方协议
 * 无 task_id/input 包装；须在收到 task_started 后发送）。
 */
fun buildTaskContinueEvent(text: String): String = buildJsonObject {
    put("event", "task_continue")
    put("text", text)
}.toString()

/** 批次23-A（ADR-048）：构造 task_finish 事件（服务端等待队列内合成完成后关连接）。 */
fun buildTaskFinishEvent(): String = buildJsonObject {
    put("event", "task_finish")
}.toString()

/**
 * 批次23-A（ADR-048）：hex 音频分片解码（官方 data 事件 `data.audio` 为 hex 编码 PCM）。
 *
 * 容错：奇数长度 / 非法字符（0-9a-fA-F 之外）→ 返回 null（调用方映射 TaskFailed）；
 * 空串 → 空数组（协议允许的无音频分片，调用方跳过）。
 */
fun decodeHexAudio(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * 批次23-A（ADR-048）：解析服务端 WS 帧（纯函数，kotlinx.serialization，JVM 可单测）。
 *
 * 规则：
 * - `base_resp.status_code != 0` → [MiniMaxEvent.TaskFailed]（协议级失败，优先于 event 判定——
 *   官方所有服务端事件均可携带 base_resp，如 task_started 即失败的场景）；
 * - event=task_started / task_finished → 对应事件；
 * - event=task_failed（无 base_resp 详情时兜底）→ TaskFailed；
 * - event=data → `data.audio` hex 解码 → [MiniMaxEvent.AudioChunk]（缺字段/解码失败 → TaskFailed）；
 * - event=task_continued → **CN 站音频载体帧**（官方 task_continue 返回体：event="task_continued"
 *   + `data.audio` hex 分片；`data` 可为 null——纯 ACK 帧无音频按信息性处理，
 *   BUG-20260909-001 服务端侧根因：CN 站与国际站 `data` 事件名不同）；
 * - 其余（connected_success/assistant_response/未知）→ [MiniMaxEvent.Unknown]；
 * - 非法 JSON / 非对象帧 → TaskFailed（服务端契约已破坏，按失败处理）。
 */
fun parseServerEvent(json: String): MiniMaxEvent {
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull()
        ?: return MiniMaxEvent.TaskFailed("服务端帧非合法 JSON")
    val obj = root as? JsonObject ?: return MiniMaxEvent.TaskFailed("服务端帧非 JSON 对象")
    val base = obj["base_resp"] as? JsonObject
    if (base != null) {
        val code = (base["status_code"] as? JsonPrimitive)?.intOrNull ?: 0
        if (code != 0) {
            val msg = (base["status_msg"] as? JsonPrimitive)?.contentOrNull ?: "未知错误"
            return MiniMaxEvent.TaskFailed("MiniMax 错误（code=$code）：$msg")
        }
    }
    return when ((obj["event"] as? JsonPrimitive)?.contentOrNull) {
        "task_started" -> MiniMaxEvent.TaskStarted
        "task_finished" -> MiniMaxEvent.TaskFinished
        "task_failed" -> MiniMaxEvent.TaskFailed("MiniMax 合成任务失败")
        "data" -> {
            val hex = audioHex(obj)
                ?: return MiniMaxEvent.TaskFailed("data 事件缺少 audio 分片")
            decodeHexAudio(hex)?.let { MiniMaxEvent.AudioChunk(it) }
                ?: MiniMaxEvent.TaskFailed("音频分片 hex 解码失败")
        }
        "task_continued" -> {
            // CN 站音频帧（BUG-20260909-001：原实现按国际站文档视为纯信息性事件，
            // 数百帧音频被静默丢弃导致零音频静音）；data 缺失/null/空串 = 纯 ACK 帧 → Unknown
            // （guardrail TKN-VOICE-BUGFIX3-002 M-1：data 分支空串保持旧行为 AudioChunk(空)，
            // 与 decodeHexAudio KDoc"空串=协议允许的无音频分片"契约一致，两分支口径有意区分）
            val hex = audioHex(obj)?.takeIf { it.isNotEmpty() } ?: return MiniMaxEvent.Unknown
            decodeHexAudio(hex)?.let { MiniMaxEvent.AudioChunk(it) }
                ?: MiniMaxEvent.TaskFailed("音频分片 hex 解码失败")
        }
        else -> MiniMaxEvent.Unknown
    }
}

/** 提取帧内 `data.audio` hex 载荷（data 非对象或字段缺失返回 null；空串原样返回——语义由分支决定）。 */
private fun audioHex(obj: JsonObject): String? =
    (obj["data"] as? JsonObject)?.get("audio")?.let { (it as? JsonPrimitive)?.contentOrNull }

/**
 * 批次23-A（ADR-048）：MiniMax T2A v2 云端 TTS 引擎（[TtsEngine] 实现零改动）。
 *
 * **协议**（官方 T2A v2 WebSocket，2026-09 文档核对；CN 站为 api.minimaxi.com——本引擎
 * 采用，国际站账号为 api.minimax.io，见 WS_URL 注释）：
 * `wss://api.minimaxi.com/ws/v1/t2a_v2?GroupId=<groupId>`，
 * header `Authorization: Bearer <apiKey>`。单句会话流：
 * 连接 → 发 task_start → 收 task_started → 发 task_continue(text) + task_finish →
 * 收音频分片（CN 站 event=task_continued / 国际站 event=data，data.audio hex 边收边播）→
 * 收 task_finished → 零帧判定（finishOrGrace）→
 * 排空缓冲 → onDone（零帧按失败降级端侧，BUG-20260909-001）。
 * （批次说明中的 task_id/input.text 为旧版协议字段，现行官方协议已弃用——每次 speak
 * 建一条 WS，会话复用为后续优化。）
 *
 * **音频输出**：[AudioTrack]（PCM 16bit 24kHz mono，MODE_STREAM），分片到达即
 * 非阻塞写入边收边播（缓冲满时让出线程轮询，stop 翻转状态即退出）。**打断语义**
 * 与 [io.prism.voice.SystemTtsEngine.stop] 对齐：stop() 立即 pause+flush+release 静音、
 * 关 WS，并回调 **onDone**（非 onError）——被打断是正常终止（VoiceSessionController
 * 挂断/语义打断路径由状态机自转，onDone 异步派发不与状态机写入重入）。
 *
 * **线程模型**：speak 可从主线程调用（WS 连接异步）；网络/音频/回调各自独立线程，
 * Listener 回调统一经单线程执行器串行派发（不占主线程，语义与系统 TTS binder 线程
 * 回调一致）。启动探测 3s：task_started 未按时到达（连不上/握手失败/Key 无效）→
 * onError"云端 TTS 连接失败（网络或 Key 问题），已回退"——由上层双轨降级链消费
 * （ADR-048 决策 3）。
 *
 * [isAvailable] = apiKey/groupId/voiceId 均已配置（Key 未配置时调用方不应选中本引擎；
 * 即便误调 speak 也走 onError 降级）。
 */
class MiniMaxTtsEngine(context: Context, private val config: MiniMaxTtsConfig) : io.prism.voice.TtsEngine {

    override val isAvailable: Boolean
        get() = config.apiKey.isNotBlank() && config.groupId.isNotBlank() && config.voiceId.isNotBlank()

    /** utterance id 生成器（同 SystemTtsEngine 的 AtomicLong 模式）。 */
    private val idCounter = AtomicLong(0)

    /** 在途会话（单句单会话；新 speak 替换旧会话——旧句立即静音并回调 onDone 终结）。 */
    private var session: Session? = null
    private val sessionLock = Any()

    /** WS 客户端（连接/握手 3s 上限对齐启动探测；读不设超时——合成间隙无流量属正常）。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Listener 回调串行派发线程（onDone/onError 恰好一次；release 后静默丢弃）。 */
    private val callbacks = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prism-cloud-tts-cb").apply { isDaemon = true }
    }

    /** 启动探测调度器（3s 内 task_started 未到 → 失败降级）。 */
    private val probe = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "prism-cloud-tts-probe").apply { isDaemon = true }
    }

    override fun speak(text: String, listener: io.prism.voice.TtsEngine.Listener) {
        if (!isAvailable) {
            listener.onError("no-config", "云端 TTS 未配置（缺少 API Key 或 GroupId）")
            return
        }
        val utteranceId = "prism-cloud-tts-${idCounter.incrementAndGet()}"
        val s = Session(utteranceId, text, listener)
        synchronized(sessionLock) {
            // 替换在途会话：旧句立即静音并按正常终止收尾（对齐系统引擎队列语义的
            // "不悬挂回调"要求；当前消费方由状态机守卫不会并发 speak，此为防御）
            session?.interrupt()
            session = s
        }
        val request = Request.Builder()
            .url("${WS_URL}?GroupId=${URLEncoder.encode(config.groupId, "UTF-8")}")
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 连接建立即发 task_start（connected_success 为服务端确认帧，无需等待）
                webSocket.send(buildTaskStartEvent(config))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                s.onServerFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // 协议防御：MiniMax 官方为文本帧（hex in JSON），但若服务端以二进制帧回送，
                // 必须显式接收并落日志（空实现会静默丢帧——BUG-20260909-001 H3 假设之一）。
                Log.w(TAG, "收到非预期二进制帧（len=${bytes.size}），当前实现仅支持文本帧")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 记录 HTTP 状态码辅助定位（200=服务端拒绝升级：Key 与端点站点不匹配或 Key/GroupId
                // 无效）；不输出响应体与请求头，防 Key 泄露（CWE-532）
                Log.w(TAG, "云端 TTS 连接失败：${t.message}（http=${response?.code}）")
                s.onTransportFailure()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // task_finished 后服务端关连接属正常；未到终态的关闭视为传输失败
                s.onTransportFailure()
            }
        })
        s.attach(ws)
        s.scheduleProbe(probe)
    }

    override fun stop() {
        val s = synchronized(sessionLock) { session } ?: return
        s.interrupt()
    }

    override fun release() {
        stop()
        probe.shutdownNow()
        callbacks.shutdown()  // 优雅关闭：排空已入队的终态回调后退出（daemon 线程不阻塞进程）
    }

    /**
     * 单句合成会话：状态机 + 音轨 + WS 句柄。
     * 状态流转：CONNECTING →(task_started) PLAYING →(task_finished) DRAINING → FINISHED；
     * 任意态 →(stop/替换) INTERRUPTED / →(失败) FAILED。
     */
    private inner class Session(
        val utteranceId: String,
        val text: String,
        val listener: io.prism.voice.TtsEngine.Listener,
    ) {
        /** 音轨/状态闩（非阻塞 write 与 stop 的 pause/flush 互斥，杜绝 use-after-release）。 */
        private val gate = Any()

        /** 终态只进不出的准入闩（onDone/onError 恰好一次）。 */
        private val terminal = AtomicBoolean(false)

        /** onStart 恰好一次（data 分片先于 task_started 到达的协议外兜底也会触发就绪）。 */
        private val started = AtomicBoolean(false)

        @Volatile
        private var state = STATE_CONNECTING

        @Volatile
        private var ws: WebSocket? = null

        @Volatile
        private var probeFuture: ScheduledFuture<*>? = null

        /** 音轨（gate 内创建/写/释放；惰性创建——首个分片到达才占资源）。 */
        private var track: AudioTrack? = null

        /** 已写入音轨的帧总数（16bit mono：2 字节/帧；排空时长估算用）。 */
        private val frames = AtomicLong(0)

        fun attach(webSocket: WebSocket) {
            ws = webSocket
        }

        fun scheduleProbe(scheduler: java.util.concurrent.ScheduledExecutorService) {
            probeFuture = scheduler.schedule({ onProbeTimeout() }, PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        /** 启动探测超时：仅 CONNECTING 态生效（就绪后合成时长不设限）。 */
        private fun onProbeTimeout() {
            if (state == STATE_CONNECTING) {
                Log.w(TAG, "云端 TTS 启动探测超时（${PROBE_TIMEOUT_MS}ms 未收到 task_started）")
                fail(PROBE_ERROR_MESSAGE)
            }
        }

        /**
         * 传输失败（连不上/握手失败/中途断开）：仅 CONNECTING/PLAYING 态回调错误。
         * DRAINING 期间的断连是**正常收尾**（官方协议：task_finished 后服务端关连接），
         * 不得打断排空——否则正常播完会被误报 onError 并掐断尾音。
         */
        fun onTransportFailure() {
            cancelProbe()
            if (state == STATE_CONNECTING || state == STATE_PLAYING) {
                fail(PROBE_ERROR_MESSAGE)
            }
        }

        /** 用户打断（stop/挂断/新句替换）：立即静音 + 正常终止（onDone 非 onError）。 */
        fun interrupt() {
            if (!terminal.compareAndSet(false, true)) return  // 已终结（幂等）
            synchronized(gate) {
                state = STATE_INTERRUPTED
                releaseTrack(pauseFirst = true)
            }
            cancelProbe()
            runCatching { ws?.cancel() }
            post { listener.onDone(utteranceId) }
        }

        private fun fail(message: String) {
            if (terminal.compareAndSet(false, true)) {
                synchronized(gate) {
                    state = STATE_FAILED
                    releaseTrack(pauseFirst = true)
                }
                cancelProbe()
                runCatching { ws?.cancel() }
                post { listener.onError(utteranceId, message) }
            }
        }

        /** 服务端帧分发（OkHttp 读线程）。帧事件留痕（事件名/音频字节数，不含内容）——
         *  BUG-20260909-001 插桩：定位"握手成功但零音频"类问题。 */
        fun onServerFrame(frame: String) {
            when (val ev = parseServerEvent(frame)) {
                is MiniMaxEvent.TaskStarted -> {
                    Log.d(TAG, "server frame: task_started（model=${config.model} voice=${config.voiceId}）")
                    cancelProbe()
                    becomeReady()
                    sendContinueAndFinish()
                }
                is MiniMaxEvent.AudioChunk -> {
                    // 事件名不写死：CN 站经 task_continued / 国际站经 data 到达（guardrail L-3）
                    Log.d(TAG, "server frame: audio chunk bytes=${ev.bytes.size}")
                    if (ev.bytes.isNotEmpty()) writeChunk(ev.bytes)
                }
                is MiniMaxEvent.TaskFinished -> {
                    Log.d(TAG, "server frame: task_finished（已收音频帧=${frames.get()}）")
                    synchronized(gate) { if (state == STATE_PLAYING) state = STATE_DRAINING }
                    Thread({ finishOrGrace() }, "prism-cloud-tts-drain").apply {
                        isDaemon = true
                        start()
                    }
                }
                is MiniMaxEvent.TaskFailed -> {
                    Log.w(TAG, "server frame: task_failed（${ev.message}）")
                    fail(ev.message)
                }
                is MiniMaxEvent.Unknown -> {
                    val name = UNKNOWN_EVENT_NAME_REGEX.find(frame)?.groupValues?.get(1) ?: "?"
                    Log.d(TAG, "server frame: other event=$name len=${frame.length}")
                }
            }
        }

        /** 就绪（task_started 或首片兜底）：状态推进 + onStart 恰好一次。 */
        private fun becomeReady() {
            if (started.compareAndSet(false, true)) {
                synchronized(gate) { if (state == STATE_CONNECTING) state = STATE_PLAYING }
                post { runCatching { listener.onStart(utteranceId) } }
            }
        }

        /** 就绪后发文本与收尾指令（官方流程：task_started 后才可发送）。 */
        private fun sendContinueAndFinish() {
            val socket = ws
            if (socket == null) {
                fail(PROBE_ERROR_MESSAGE)
                return
            }
            runCatching {
                socket.send(buildTaskContinueEvent(text))
                socket.send(buildTaskFinishEvent())
            }.onFailure {
                Log.w(TAG, "task_continue 发送失败：${it.message}")
                fail(PROBE_ERROR_MESSAGE)
            }
        }

        /** 非阻塞写分片：缓冲满时轮询让出（stop 翻转状态即退出，无阻塞悬挂）。
         *  DRAINING 态仍接收写入——容忍服务端 task_finished 后尾部分片补达
         *  （BUG-20260909-001：原实现静默丢弃，会造成"收到音频却零帧"的静音）。 */
        private fun writeChunk(bytes: ByteArray) {
            if (state == STATE_CONNECTING) becomeReady()  // 协议外兜底：分片先于 task_started
            var offset = 0
            while (offset < bytes.size) {
                if (state != STATE_PLAYING && state != STATE_DRAINING) return  // 已打断/失败：丢弃剩余分片
                val t = synchronized(gate) {
                    if (state != STATE_PLAYING && state != STATE_DRAINING) return
                    track ?: createTrack().also {
                        runCatching { it.play() }
                        track = it
                    }
                }
                val written = runCatching {
                    t.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                }.getOrDefault(-1)
                if (written < 0) {
                    fail("音频写入失败（AudioTrack error=$written）")
                    return
                }
                if (written == 0) {
                    Thread.sleep(WRITE_RETRY_MS)  // 音轨缓冲满：等待消费
                } else {
                    offset += written
                    frames.addAndGet((written / BYTES_PER_FRAME).toLong())
                }
            }
        }

        /**
         * task_finished 收尾（BUG-20260909-001 核心修复）：先零帧判定，再排空回调 onDone。
         *
         * **零帧=成功缺陷**：原实现 task_finished 一律走 drainAndFinish → onDone，而上层
         * 降级链仅在 onError 时回退端侧——服务端正常收尾但未推送任何音频分片（音色无效被
         * 空完成 / 参数空响应等）会导致整轮静音且不降级。现处理：
         * 宽限 [FINISH_GRACE_MS] 等待尾部分片补达（DRAINING 态仍接收写入），
         * 仍零帧 → fail（文案含"已回退"标记，命中 VoiceEngineFallback.shouldFallbackToOnDevice
         * 自动切端侧，恢复有声音）。
         */
        private fun finishOrGrace() {
            if (frames.get() == 0L) {
                runCatching { Thread.sleep(FINISH_GRACE_MS) }
            }
            if (frames.get() == 0L) {
                Log.w(TAG, "task_finished 但零音频帧（可能音色无效/服务端空完成），按失败降级")
                fail(ZERO_AUDIO_ERROR_MESSAGE)
                return
            }
            drainAndFinish()
        }

        /** task_finished 收尾：等待音轨缓冲排空（残留帧时长 + 余量）后释放并回调 onDone。 */
        private fun drainAndFinish() {
            runCatching {
                var head = 0L
                synchronized(gate) {
                    val t = track
                    if (t != null) head = runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(0L)
                }
                val bufferedFrames = (frames.get() - head).coerceAtLeast(0L)
                val drainMs = bufferedFrames * 1000L / SAMPLE_RATE + DRAIN_MARGIN_MS
                Log.d(TAG, "播报收尾：排空缓冲 ${drainMs}ms（残留帧=$bufferedFrames）")
                Thread.sleep(drainMs)
            }
            if (terminal.compareAndSet(false, true)) {
                synchronized(gate) { state = STATE_FINISHED }
                post { listener.onDone(utteranceId) }
            }
        }

        /** 创建音轨（PCM 16bit 24kHz mono，流式；缓冲 ≥1s 平滑网络抖动）。 */
        private fun createTrack(): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * BYTES_PER_FRAME)  // ≥1s
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }

        /** 释放音轨（pause+flush 立即静音，随后 release；全程 runCatching 幂等）。 */
        private fun releaseTrack(pauseFirst: Boolean) {
            val t = track
            track = null
            if (t != null) {
                runCatching {
                    if (pauseFirst) {
                        t.pause()
                        t.flush()
                    }
                }
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }

        private fun cancelProbe() {
            probeFuture?.cancel(false)
        }

        /** 终态回调派发（串行线程；回调异常隔离，release 后静默丢弃）。 */
        private fun post(block: () -> Unit) {
            runCatching { callbacks.execute { runCatching(block) } }
        }
    }

    companion object {
        private const val TAG = "MiniMaxTtsEngine"

        /**
         * 官方 T2A v2 WS 端点——**CN 站**（platform.minimaxi.com 账号，与克隆音色同站）。
         * 国际站账号为 `wss://api.minimax.io/ws/v1/t2a_v2`，CN/国际 Key 不互通；
         * 端点与 Key 站点不匹配时服务端在握手层返回 HTTP 200+JSON 错误体（base_resp），
         * OkHttp 报 ProtocolException「Expected HTTP 101 response but was '200 OK'」。
         * （BUG-20260908-001 根因修复：原硬编码国际站，CN Key 握手必被拒。）
         */
        private const val WS_URL = "wss://api.minimaxi.com/ws/v1/t2a_v2"

        /** 输出采样率（audio_setting 与 AudioTrack 严格一致；24kHz 语音质量/流量平衡）。 */
        const val SAMPLE_RATE = 24_000

        /** 16bit mono → 2 字节/帧。 */
        private const val BYTES_PER_FRAME = 2

        /** 启动探测超时（ADR-048 决策 3：3s 探测失败自动回端侧——由上层降级链消费）。 */
        private const val PROBE_TIMEOUT_MS = 3_000L

        /** 启动探测/传输失败统一错误文案（上层降级链识别回退；不泄露 Key 等细节）。 */
        private const val PROBE_ERROR_MESSAGE = "云端 TTS 连接失败（网络或 Key 问题），已回退"

        /** 零音频失败文案（含"已回退"降级标记——命中 VoiceEngineFallback 自动切端侧）。 */
        private const val ZERO_AUDIO_ERROR_MESSAGE = "云端 TTS 未返回音频，已回退"

        /** task_finished 后零帧宽限窗口（ms）：容忍尾部分片乱序补达。 */
        private const val FINISH_GRACE_MS = 600L

        /** 未知事件名提取（诊断用；仅匹配 event 字段名，不输出帧内容）。 */
        private val UNKNOWN_EVENT_NAME_REGEX = Regex("\"event\"\\s*:\\s*\"([^\"]+)\"")

        /** 音轨缓冲满时的写入重试间隔（ms）。 */
        private const val WRITE_RETRY_MS = 8L

        /** 播报收尾排空余量（ms）。 */
        private const val DRAIN_MARGIN_MS = 120L

        // 会话状态（Int 常量避免 JDK8 目标下 enum 开销；INTERRUPTED 及以后为终态）
        private const val STATE_CONNECTING = 0
        private const val STATE_PLAYING = 1
        private const val STATE_DRAINING = 2
        private const val STATE_INTERRUPTED = 3
        private const val STATE_FINISHED = 4
        private const val STATE_FAILED = 5
    }
}
