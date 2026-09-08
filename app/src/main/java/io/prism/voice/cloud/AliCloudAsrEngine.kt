package io.prism.voice.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.prism.voice.AsrEngine
import io.prism.voice.AudioInputController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.Buffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批次23-A：一句识别结果。
 *
 * @param text 识别文本（payload.output.sentence.text）
 * @param isSentenceEnd true=句尾（映射 [AsrEngine.Listener.onFinal]，服务端语义断句自动提交）；
 *   false=中间结果（映射 [AsrEngine.Listener.onPartial]）
 */
data class AsrSentence(val text: String, val isSentenceEnd: Boolean)

/**
 * 批次23-A（ADR-048）：阿里云百炼实时 ASR 引擎（Paraformer-realtime-v2，纯 WebSocket）。
 *
 * **协议**（官方文档 help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-
 * api-reference，2026-09-02 版）：
 * - 连接 `wss://dashscope.aliyuncs.com/api-ws/v1/inference/`（尾斜杠为官方示例原样），握手头
 *   `Authorization: Bearer <DashScope Key>`——Key 由调用方经 [AsrEngineFactory] 传入
 *   （ApiKeyRepository 加密存储，键名 [KEY_REF]=cloud_asr_dashscope，不硬编码不落日志）；
 * - 时序：`run-task`（JSON text 帧）→ 收 `task-started` 后才可推二进制 PCM 帧（16k/16bit/mono
 *   小端，~100ms/帧，官方推荐粒度）→ `finish-task` → 服务端补发尾句 + `task-finished`；
 * - `result-generated` → `payload.output.sentence{text, sentence_end, heartbeat}`（字段名以
 *   官方协议文档为准）：非句尾 → [AsrEngine.Listener.onPartial]、句尾 → [AsrEngine.Listener.onFinal]、
 *   heartbeat=true 为心跳包跳过；
 * - `task-failed` → `header.error_code/error_message` 映射可读中文文案 → [AsrEngine.Listener.onError]；
 *   WS 连接层异常同走 onError（v2.2 双轨降级链的"探测失败回端侧"由后续手选引擎批次接线，
 *   本批次先提供可诊断回调）。
 *
 * **流式常驻形态**（[isStreaming]=true，与 SherpaAsrEngine 同语义）：通话开始即持续聆听，
 * 服务端语义断句（sentence_end=true）自动提交整句；[startListening] 幂等（已在聆听直接返回）。
 *
 * **音频源**：复用主源集 [AudioInputController]（16k/16bit/mono + VOICE_COMMUNICATION +
 * AcousticEchoCanceler 硬件 AEC——与 sherpa 引擎同源）；其 20ms 采集块聚合为 100ms
 * （1600 样本/3200 字节）推流。
 *
 * **线程模型**：WS 读在 OkHttp 回调线程；音频写在 Dispatchers.IO（AudioInputController 循环），
 * 推流即在音频回调线程执行（OkHttp WebSocket.send 线程安全）；**Listener 回调全部经主线程
 * Handler 派发**（AsrEngine 主线程契约——onFinal 触发 sendMessage/状态机要求主线程）。
 *
 * **[resetCapture] 语义**：回聆听前丢弃未发送的音频残段（防 TTS 回声尾被推流）；已推送部分
 * 依赖前置硬件 AEC + 服务端语义断句的静音门控兜底——服务端无"重置"动作，不整任务重连
 * （免按压连续对话每轮重建 WS 会引入 ~300ms 重连延迟，代价不成比例）。
 *
 * **Key 安全**：明文仅内存驻留，不落盘不落日志（CWE-532 口径）；识别文本（用户口述 PII）
 * 同样不进 logcat——日志仅记长度/事件名（sherpa 同款规则）。
 */
class AliCloudAsrEngine(
    private val context: Context,
    /** DashScope API Key 明文（调用方解密后传入）；null/blank = Key 未配置（引擎不可用）。 */
    private val dashScopeKey: String?,
) : AsrEngine {

    private val running = AtomicBoolean(false)

    /** WS 会话（@Volatile——音频回调线程读、主线程写）。 */
    @Volatile
    private var webSocket: WebSocket? = null

    /** 收到 task-started 后才可推音频（协议时序硬约束）。 */
    @Volatile
    private var taskStarted = false

    /** finish-task 已发送（此后不得再推音频帧）。 */
    @Volatile
    private var finishRequested = false

    /** 当前任务 ID（run-task/finish-task 复用同一 UUID）。 */
    @Volatile
    private var taskId: String? = null

    /** 当前聆听会话的回调（startListening 写、WS/音频线程读）。 */
    @Volatile
    private var listener: AsrEngine.Listener? = null

    private val audio = AudioInputController(context)
    private val main = Handler(Looper.getMainLooper())

    /** 推流聚合锁（音频回调线程写、主线程 resetCapture 清零）。 */
    private val accLock = Any()
    private val acc = ShortArray(CHUNK_SAMPLES)  // 100ms @16k
    private var accFill = 0

    override val isStreaming: Boolean get() = true

    /**
     * Key 就绪即可用（网络态省略——连接失败经 onError 可诊断回调，ADR-048 降级链由
     * 后续手选引擎批次消费）。
     */
    override val isAvailable: Boolean
        get() = !dashScopeKey.isNullOrBlank()

    override fun startListening(listener: AsrEngine.Listener) {
        if (running.get()) return  // 流式常驻：已在聆听则幂等（免按压模式每轮复用）
        if (dashScopeKey.isNullOrBlank()) {
            listener.onError("云端语音识别未配置 DashScope Key（请先在设置中配置）")
            return
        }
        if (!audio.hasPermission()) {
            listener.onError("录音初始化失败（请检查麦克风权限）")
            return
        }
        this.listener = listener
        running.set(true)
        taskStarted = false
        finishRequested = false
        synchronized(accLock) { accFill = 0 }
        val id = UUID.randomUUID().toString()
        taskId = id
        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $dashScopeKey")  // Key 不进日志（CWE-532）
            .build()
        webSocket = CLIENT.newWebSocket(request, WsListener())
        Log.i(TAG, "云端识别连接中（模型=$MODEL）")
    }

    override fun stopListening() {
        // 协议时序：finish-task 后不得再推音频帧 → 先停采集再发指令；收 task-finished 收口
        //（onMessage finishFlow → closeInternal）。流式主链路由服务端 sentence_end 自动断句，
        // 本方法为接口兼容 + 显式收尾路径。
        if (!running.get() || finishRequested) return
        finishRequested = true
        audio.stop()
        val id = taskId
        val ws = webSocket
        if (id != null && ws != null) {
            ws.send(buildFinishTaskCommand(id))
            Log.i(TAG, "finish-task 已发送（等待尾句 + task-finished）")
        } else {
            closeInternal()
        }
    }

    override fun cancel() {
        running.set(false)   // 在途 result-generated 全部丢弃（result-generated 分支 guard）
        finishRequested = true
        audio.stop()
        runCatching { webSocket?.cancel() }  // 丢弃语义：立即断开，不发 close 帧
        webSocket = null
    }

    override fun resetCapture() {
        synchronized(accLock) { accFill = 0 }
    }

    override fun release() {
        cancel()
    }

    // ==================== 音频采集 → 推流 ====================

    /** task-started 后启动采集（AudioInputController 16k/16bit/mono + AEC，主源集同源）。 */
    private fun startAudioCapture() {
        val ok = audio.start(::onAudioChunk)
        if (!ok) {
            Log.w(TAG, "采集启动失败（权限/占用）")
            val l = listener
            main.post { l?.onError("录音初始化失败（请检查麦克风权限或被其它应用占用）") }
            closeInternal()
        }
    }

    /**
     * 20ms 采集块 → 聚合 100ms（1600 样本）→ LE PCM16 编码推流 + RMS 音量回调（每 100ms
     * 一次，×6 增益对齐 sherpa 波形/barge-in 刻度）。任何一步在 taskStarted/finishRequested
     * 不满足时丢弃音频（协议时序 + 收尾后不得推流）。
     */
    private fun onAudioChunk(chunk: ShortArray) {
        if (!taskStarted || finishRequested) return
        var payload: ByteString? = null
        var level = -1f
        synchronized(accLock) {
            var src = 0
            while (src < chunk.size) {
                val n = minOf(chunk.size - src, CHUNK_SAMPLES - accFill)
                System.arraycopy(chunk, src, acc, accFill, n)
                accFill += n
                src += n
                if (accFill == CHUNK_SAMPLES) {
                    var sum = 0.0
                    for (s in acc) {
                        val v = s / 32768f
                        sum += (v * v).toDouble()
                    }
                    level = (kotlin.math.sqrt(sum / CHUNK_SAMPLES).toFloat() * 6f).coerceAtMost(1f)
                    payload = encodePcm16le(acc)
                    accFill = 0
                }
            }
        }
        payload?.let { bytes ->
            webSocket?.send(bytes)
            val l = listener
            main.post { l?.onVolume(level) }
        }
    }

    /** 16bit PCM 小端编码（Android AudioRecord 原生 LE；DashScope "pcm"=16bit 小端交错）。 */
    private fun encodePcm16le(samples: ShortArray): ByteString {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return Buffer().write(out).readByteString()
    }

    // ==================== WS 生命周期 ====================

    private fun closeInternal() {
        running.set(false)
        audio.stop()
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
    }

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 连接即发 run-task（协议要求：收 task-started 前禁止推音频）
            val id = taskId ?: return
            webSocket.send(buildRunTaskCommand(id))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (extractEvent(text)) {
                "task-started" -> {
                    if (!running.get()) return  // cancel 竞态：任务已废弃，不再起采集
                    taskStarted = true
                    startAudioCapture()
                }
                "result-generated" -> {
                    val s = parseResultGenerated(text) ?: return
                    if (!running.get()) return  // cancel 后在途结果丢弃
                    if (s.text.isBlank()) return  // 空文本不提交（对齐 sherpa 非空门控）
                    val l = listener
                    main.post {
                        if (s.isSentenceEnd) l?.onFinal(s.text) else l?.onPartial(s.text)
                    }
                }
                "task-finished" -> {
                    Log.i(TAG, "task-finished（会话收口）")
                    closeInternal()
                }
                "task-failed" -> {
                    val failed = parseTaskFailed(text)
                    val (code, message) = failed ?: ("未知" to "服务端未提供失败详情")
                    Log.w(TAG, "task-failed code=$code（长度=${message.length}）")
                    val l = listener
                    main.post { l?.onError(mapTaskFailure(code, message)) }
                    closeInternal()
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (running.get()) {
                Log.w(TAG, "WS 异常：${t::class.simpleName}: ${t.message}")
                val l = listener
                main.post { l?.onError("云端识别连接异常（${t::class.simpleName}），请检查网络后重试") }
            }
            closeInternal()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)  // 完成关闭握手
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeInternal()
        }
    }

    /** task-failed 错误码 → 可读中文文案（服务端 message 原样透传做诊断信息）。 */
    private fun mapTaskFailure(code: String, message: String): String = when {
        code.contains("ApiKey", true) || code.contains("Token", true) ||
            code.contains("Authorization", true) || code.contains("AccessDenied", true) ->
            "云端识别鉴权失败（DashScope Key 无效或未开通语音识别服务）"
        code.contains("Throttling", true) || code.contains("Limit", true) ||
            message.contains("quota", true) ->
            "云端识别请求受限（限流或配额不足，请稍后重试）"
        else -> "云端识别失败（$code：$message）"
    }

    companion object {
        private const val TAG = "AliCloudAsrEngine"

        /** ApiKeyRepository 键名（批次23-A）：DashScope Key（云端 ASR）。 */
        const val KEY_REF = "cloud_asr_dashscope"

        /** 百炼实时识别 WebSocket 服务地址（官方文档 2026-09-02 版示例原样，含尾斜杠）。 */
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"

        /** 模型（ADR-048 决策 1：Paraformer-realtime-v2）。 */
        private const val MODEL = "paraformer-realtime-v2"

        /** 推流块长：100ms @16k16bit mono = 1600 样本 / 3200 字节（官方推荐 ~100ms 帧）。 */
        private const val CHUNK_SAMPLES = 1600

        /**
         * 共享 WS 客户端（OkHttpClient 线程安全、官方推荐跨会话复用，避免每通话新建连接池）。
         * 连接超时 3s（ADR-048 决策 3 的探测预算口径）；WS 长连接禁读超时；ping 保活
         * （服务端空闲 60s 主动断连）。
         */
        private val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ZERO)
                .pingInterval(Duration.ofSeconds(15))
                .build()
        }

        // ==================== 纯函数（JVM 单测覆盖，见 AliCloudAsrEngineTest） ====================

        /**
         * 拼装 run-task 指令 JSON（连接后首个 text 帧）。payload.model/parameters 字段名以
         * 官方协议文档为准（format=pcm + sample_rate=16000 为本引擎硬约束）。
         */
        fun buildRunTaskCommand(taskId: String): String = buildJsonObject {
            put("header", buildJsonObject {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "out")
            })
            put("payload", buildJsonObject {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", MODEL)
                put("parameters", buildJsonObject {
                    put("format", "pcm")
                    put("sample_rate", 16000)
                    put("volume", 50)
                    put("rate", 1)
                    put("word_confidence", false)
                })
                put("input_files", buildJsonArray { })
            })
        }.toString()

        /** 拼装 finish-task 指令 JSON（音频推送完毕后发送，payload.input 恒为空对象）。 */
        fun buildFinishTaskCommand(taskId: String): String = buildJsonObject {
            put("header", buildJsonObject {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "out")
            })
            put("payload", buildJsonObject {
                put("input", buildJsonObject { })
            })
        }.toString()

        /** 提取服务端事件名（header.event）；畸形输入返回 null（服务端帧不可信，逐层容错）。 */
        fun extractEvent(json: String): String? = runCatching {
            Json.parseToJsonElement(json)
                .jsonObject["header"]?.jsonObject?.get("event")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        /**
         * 解析 result-generated 帧 → [AsrSentence]。字段名以官方协议文档为准：
         * `payload.output.sentence{text, sentence_end, heartbeat}`。非 result-generated 事件、
         * 心跳包（heartbeat=true）、缺 text/sentence_end、任意结构畸形 → null（容错不崩溃）。
         */
        fun parseResultGenerated(json: String): AsrSentence? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val header = root["header"]?.jsonObject ?: return@runCatching null
            if (header["event"]?.jsonPrimitive?.contentOrNull != "result-generated") {
                return@runCatching null
            }
            val output = root["payload"]?.jsonObject?.get("output")?.jsonObject
                ?: return@runCatching null
            val sentence = output["sentence"]?.jsonObject ?: return@runCatching null
            if (sentence["heartbeat"]?.jsonPrimitive?.booleanOrNull == true) {
                return@runCatching null  // 心跳包跳过（官方文档：heartbeat=true 可跳过该结果）
            }
            // text 必须为 JSON 字符串（contentOrNull 对数字字面量仍返回其字符串表示，
            // 需显式 isString 校验拒绝协议外的非字符串 text）
            val textElement = sentence["text"] as? JsonPrimitive ?: return@runCatching null
            if (!textElement.isString) return@runCatching null
            val text = textElement.contentOrNull ?: return@runCatching null
            val sentenceEnd = sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull
                ?: return@runCatching null
            AsrSentence(text, sentenceEnd)
        }.getOrNull()

        /**
         * 解析 task-failed 帧 → (error_code, error_message)（官方文档：两者位于 header）。
         * 非 task-failed 事件/畸形 → null。
         */
        fun parseTaskFailed(json: String): Pair<String, String>? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val header = root["header"]?.jsonObject ?: return@runCatching null
            if (header["event"]?.jsonPrimitive?.contentOrNull != "task-failed") {
                return@runCatching null
            }
            val code = header["error_code"]?.jsonPrimitive?.contentOrNull ?: ""
            val message = header["error_message"]?.jsonPrimitive?.contentOrNull ?: ""
            code to message
        }.getOrNull()
    }
}
