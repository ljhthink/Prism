package io.prism.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 批次20（VC-004，ADR-044）：麦克风采集控制器 —— 16kHz/16bit/单声道 PCM。
 *
 * **主链路关系**：P1 系统 [SystemAsrEngine]（SpeechRecognizer 自带录音）不经过本类；
 * 本类是 sherpa-onnx 端侧识别（P1.5，ADR-044 主选落地批）的音频源——16kHz 单声道 PCM
 * 正是 sherpa 流式识别的输入格式。
 *
 * **AEC**（调研 webrtc.ventures 实战）：TTS 外放场景下必须开启硬件回声消除，否则
 * "AI 听见自己"形成反馈环——`VOICE_COMMUNICATION` 音源 + [android.media.audiofx.AcousticEchoCanceler]。
 */
class AudioInputController(private val context: Context) {

    /** 采样率（sherpa / Gemini Live 通用 16kHz）。 */
    val sampleRate: Int = 16_000

    /** 单次读帧 PCM 样本数（20ms @16kHz）。 */
    val chunkSamples: Int = sampleRate / 50

    private val recording = AtomicReference<AudioRecord?>(null)
    private var loopJob: Job? = null

    /** RECORD_AUDIO 是否已授予（未授予时 start 直接失败）。 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 开始采集（16kHz/16bit/mono，VOICE_COMMUNICATION + AEC）。
     *
     * @param onChunk PCM 块回调（采集线程；P1.5 喂给 sherpa 流式识别）
     * @return 是否成功启动（权限缺失/初始化失败返回 false）
     */
    fun start(onChunk: (ShortArray) -> Unit): Boolean {
        // 批次22 修复轮七（lint MissingPermission）：权限检查内联在 start 内（lint 数据流
        // 可识别）；hasPermission() 保留供外部调用方预判
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start 失败：RECORD_AUDIO 未授予")
            return false
        }
        if (recording.get() != null) return true // 幂等
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "start 失败：minBufferSize=$minBuf")
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, chunkSamples * 2 * 4)
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord 创建失败：${e::class.simpleName}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.w(TAG, "start 失败：AudioRecord 未初始化（被其它应用占用？）")
            return false
        }
        // 硬件 AEC（存在即开启；防 TTS 外放回采——P2 打断的基线）
        runCatching {
            android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
        }
        recording.set(record)
        record.startRecording()
        loopJob = CoroutineScope(Dispatchers.IO).launch {
            val rec = recording.get() ?: return@launch
            val buf = ShortArray(chunkSamples)
            while (isActive && recording.get() === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf.copyOf(n))
            }
        }
        Log.i(TAG, "采集已启动（16kHz/16bit/mono + AEC）")
        return true
    }

    /** 停止采集并释放（幂等）。 */
    fun stop() {
        val rec = recording.getAndSet(null) ?: return
        loopJob?.cancel()
        loopJob = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        Log.i(TAG, "采集已停止")
    }

    companion object {
        private const val TAG = "AudioInput"
    }
}
