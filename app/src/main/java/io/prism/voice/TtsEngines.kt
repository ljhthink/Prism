package io.prism.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 批次20（VC-003，ADR-044）：语音合成引擎接口 —— AI 回复播报层。
 *
 * P1 用 [SystemTtsEngine]（系统 TextToSpeech，零依赖）；sherpa VITS 中文音色为后续增强
 * （接口零改动替换）。[UtteranceProgressListener] 播完回调驱动状态机回到空闲。
 */
interface TtsEngine {
    /** 引擎是否可用（初始化成功；无 TTS 引擎设备 → false，调用方静默降级为仅文字）。 */
    val isAvailable: Boolean

    /** 播报文本（队列尾追加）；完成/失败经 [Listener] 回调。 */
    fun speak(text: String, listener: Listener)

    /** 立即停止播报并清空队列（打断/挂断）。 */
    fun stop()

    /** 释放资源。 */
    fun release()

    /** 播报回调。 */
    interface Listener {
        fun onDone(utteranceId: String)

        fun onError(utteranceId: String, message: String)

        /** 播报开始（状态机 → speaking）。 */
        fun onStart(utteranceId: String)
    }
}

/**
 * 批次20（VC-003，ADR-044）：系统 TextToSpeech 实现。
 *
 * **线程**：TextToSpeech 需在主线程创建（构造）——由 [VoiceSessionController] 编排；
 * init 异步完成（[isAvailable] 在 onInit 前为 false，播报请求会被排队丢弃并回调错误）。
 */
class SystemTtsEngine(context: Context) : TtsEngine {

    /** utterance id 生成器（进度回调关联）。 */
    private val idCounter = AtomicLong(0)

    @Volatile
    private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        onTtsInit(status)
    }

    /** TTS 异步初始化完成回调（回调时 [tts] 属性已完成初始化）。 */
    private fun onTtsInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // 中文优先（识别与播报同语言；未命中中文回退系统默认语言）
            runCatching { tts.setLanguage(Locale.SIMPLIFIED_CHINESE) }
            Log.i(TAG, "TTS 引擎就绪（中文）")
        } else {
            Log.w(TAG, "TTS 初始化失败 status=$status（本机无 TTS 引擎，降级为仅文字）")
        }
    }

    override val isAvailable: Boolean
        get() = ready

    override fun speak(text: String, listener: TtsEngine.Listener) {
        if (!ready) {
            listener.onError("no-init", "TTS 引擎未就绪（本机可能无语音合成引擎）")
            return
        }
        val utteranceId = "prism-tts-${idCounter.incrementAndGet()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let(listener::onStart)
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(listener::onDone)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { listener.onError(it, "播报失败") }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { listener.onError(it, "播报失败（code=$errorCode）") }
            }
        })
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            listener.onError(utteranceId, "播报请求被拒绝（code=$result）")
        }
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun release() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        private const val TAG = "SystemTtsEngine"
    }
}
