package io.prism.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 批次22 修复轮五（2026-09-07，同音字优化）：用户语音热词文件名（filesDir 相对路径，
 * 每行一个热词）。主源集常量——设置页编辑与 sherpa 条件源集解码偏置共用（sherpa 源集
 * 引用主源集方向合法；反向会把条件源集常量硬编码进主源集、破坏"无 AAR 可编译"降级契约）。
 */
const val VOICE_HOTWORDS_FILE = "voice_hotwords.txt"

/**
 * 批次20（VC-002，ADR-044）：语音识别引擎接口 —— 语音子系统的可替换识别层。
 *
 * **实现**：
 * - [SystemAsrEngine]：系统 [SpeechRecognizer]（多数国产 ROM 走厂商引擎，P1 主链路开箱即用）；
 * - SherpaAsrEngine（P1.5，ADR-044 主选）：sherpa-onnx 端侧流式中文识别——依赖官方 AAR
 *   与模型下载，作为独立小批次接入（AsrEngineFactory 按"模型就绪"切换，接口零改动）。
 *
 * **P1 交互形态**（用户批准）：按住说话 → 松开 → 整段识别 → 文本回 UI 可确认。
 */
interface AsrEngine {
    /** 引擎是否可用（系统无识别引擎 / sherpa 模型未就绪 → false，调用方降级或提示）。 */
    val isAvailable: Boolean

    /**
     * 批次22（SZ-202，ADR-047）：是否为**流式常驻引擎**（sherpa——常驻 AudioRecord 循环 +
     * endpoint 自动断句）。true 时 [VoiceSessionController] 启用免按压连续对话（通话开始即
     * 持续聆听，endpoint 断句自动提交，播报结束自动回聆听）；false（系统引擎）保持按住说话。
     */
    val isStreaming: Boolean get() = false

    /** 开始聆听（P1 整段模式；error/partial 经 [Listener] 回调）。 */
    fun startListening(listener: Listener)

    /** 停止聆听并输出最终结果（松开按钮）。 */
    fun stopListening()

    /** 取消本次识别（丢弃）。 */
    fun cancel()

    /**
     * 批次22（SZ-202）：清空已采集/解码中的识别缓冲（流式引擎回聆听前清 TTS 回声残留，
     * 防"AI 自己的话被当成用户输入"误提交；非流式引擎 no-op）。
     */
    fun resetCapture() {}

    /** 释放资源（SpeechRecognizer.destroy 等）。 */
    fun release()

    /** 识别回调（主线程）。 */
    interface Listener {
        /** 音量等级（0..1，UI 波形动画用）。 */
        fun onVolume(level: Float)

        /** 增量部分结果（系统引擎多数也回 partial）。 */
        fun onPartial(text: String)

        /** 最终识别结果（松开后）。 */
        fun onFinal(text: String)

        /** 识别失败（引擎不可用/网络/无匹配/取消）。 */
        fun onError(message: String)
    }
}

/**
 * 批次20（VC-002，ADR-044）：系统 [SpeechRecognizer] 实现（零依赖、厂商离线能力视 ROM）。
 *
 * 线程：[SpeechRecognizer] 要求主线程创建/调用——[VoiceSessionController] 负责在主线程编排。
 */
class SystemAsrEngine(private val context: Context) : AsrEngine {

    private var recognizer: SpeechRecognizer? = null

    /**
     * 批次21 真机修复 R3（RCA 问题2 续）：**引擎层连续错误熔断**——真机 MIUI 实证
     * RECORD_AUDIO 已授予但引擎层（AppOps/ROM 限制）仍连环报错（断连/权限交替，每 150-350ms
     * 一条，4 秒 13 条穿透外层防抖——每次报错都来自引擎内部新一轮识别尝试）。本计数在
     * **引擎内**跨轮累计：连续 ≥[ERROR_CIRCUIT_LIMIT] 次错误后熔断——后续 startListening
     * 不再创建 recognizer，仅回单条汇总提示；识别成功路径（partial/final）清零复位。
     */
    private var consecutiveErrors = 0

    /** 熔断阈值：连续错误达到该次数后停止创建 recognizer。 */
    private val ERROR_CIRCUIT_LIMIT = 3

    /**
     * 批次21 真机修复 R3（联网取证：flutter-speech-recognition issue——"Error 9 resolved
     * by granting mic permission to the *engine* app"）：**ERROR_INSUFFICIENT_PERMISSIONS
     * 指的是识别引擎进程没有麦克风权限**，不是调用方（调用方 RECORD_AUDIO 已授予）。
     * 处理：query 所有 RecognitionService，**优先选择引擎自身已授权 RECORD_AUDIO 的服务**
     * （MIUI 场景：小爱引擎无麦克风权限时，若有其它可用引擎则自动切换；全无授权则选第一个
     * 并在 code=9 文案中给出引擎名引导）。
     *
     * @return 目标引擎组件；无可用服务返回 null（沿用默认创建路径）
     */
    private fun resolveServiceComponent(): android.content.ComponentName? {
        val services = runCatching {
            context.packageManager.queryIntentServices(
                Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0
            )
        }.getOrDefault(emptyList())
        val candidates = services.mapNotNull { ri ->
            val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
            val name = ri.serviceInfo.name ?: return@mapNotNull null
            val engineMicGranted = runCatching {
                context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, pkg) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            Triple(ComponentName(pkg, name), engineMicGranted, ri.serviceInfo.enabled)
        }.filter { it.third }
        // 已授权麦克风的引擎优先；同序保持系统优先级（queryIntentServices 默认序）
        return candidates.sortedByDescending { it.second }.firstOrNull()?.first
    }

    /** 当前选中的引擎组件（code=9 动态文案用）。 */
    private var resolvedEngine: android.content.ComponentName? = null

    /**
     * 批次22（SZ-101，ADR-047）：暴露当前识别引擎包名——供通话启动时经 Shizuku
     * `pm grant` 自动给引擎授麦克风权限（code=9 根因修复）。
     */
    fun currentEnginePackage(): String? = resolvedEngine?.packageName

    /** 解析引擎（供修复动作在引擎尚未创建时预查询；结果不写入 resolvedEngine）。 */
    fun peekEnginePackage(): String? = resolveServiceComponent()?.packageName

    /**
     * 批次21 真机修复 R3：code=9 动态提示——点出**引擎应用名**（真机场景是引擎没麦克风
     * 权限，而非调用方；引导用户去开引擎 App 的权限）。
     */
    private fun permissionHint(): String {
        val pkg = resolvedEngine?.packageName
        val engineLabel = runCatching {
            pkg?.let { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)) }
        }.getOrNull() ?: pkg ?: "语音引擎"
        return "语音引擎（$engineLabel）缺少麦克风权限——请在系统设置→应用管理→$engineLabel→权限 中开启「麦克风/录音」"
    }

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun startListening(listener: AsrEngine.Listener) {
        // 批次20 真机修复 R2（RCA 问题2 续）：**RECORD_AUDIO 权限前置检查**——真机实证权限
        // 缺失时 SpeechRecognizer 连环回调 onError（权限/断连/客户端错误每秒多条，UI 刷屏），
        // 必须在创建 recognizer 前拦截；同时保留乐观尝试（isRecognitionAvailable 受包可见性
        // 影响可能误报 false，MIUI 实证）。
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "startListening 拦截：RECORD_AUDIO 未授予")
            listener.onError("缺少麦克风权限（请在系统设置或通话页重新授权）")
            return
        }
        // 批次21 真机修复 R3：连续错误熔断——ROM/引擎层限制导致的必然失败不再反复尝试
        if (consecutiveErrors >= ERROR_CIRCUIT_LIMIT) {
            Log.w(TAG, "startListening 熔断：连续错误 $consecutiveErrors 次")
            listener.onError("语音识别连续失败（可能被系统录音限制），请挂断后到系统设置检查 Prism 的麦克风权限再重进")
            return
        }
        // 乐观尝试：仍创建并 startListening，无引擎/异常时走 onError（createSpeechRecognizer 本身不抛）。
        // 批次21 真机修复 R3：显式选择"引擎自身已授权麦克风"的 RecognitionService（见
        // resolveServiceComponent KDoc——默认选择可能命中无麦克风权限的引擎，真机 MIUI 实证）。
        cancel()
        val rec = try {
            val component = resolveServiceComponent()
            resolvedEngine = component
            val r = if (component != null) {
                SpeechRecognizer.createSpeechRecognizer(context, component)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            r.also { recognizer = it }
        } catch (e: Exception) {
            Log.w(TAG, "createSpeechRecognizer 失败：${e::class.simpleName}")
            listener.onError("本机语音识别服务不可用（未安装/未启用识别引擎）")
            return
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) {
                // RMS 典型 -2..10 dB，归一到 0..1（波形动画）
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                listener.onVolume(level)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    consecutiveErrors = 0  // 识别链路恢复：清零熔断计数
                    listener.onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    onError(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    consecutiveErrors = 0  // 成功路径清零
                    listener.onFinal(text)
                }
            }

            override fun onError(error: Int) {
                consecutiveErrors++
                Log.w(TAG, "ASR onError code=$error（连续 $consecutiveErrors）")
                // 批次21 真机修复 R3：code=9 用引擎动态文案（引擎名+引擎侧权限引导）
                listener.onError(
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) permissionHint()
                    else describeError(error)
                )
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            // 允许部分结果回传（UI 实时回显）
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        rec.startListening(intent)
    }

    override fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        runCatching {
            recognizer?.destroy()
        }
        recognizer = null
    }

    override fun release() {
        cancel()
    }

    /** 错误码 → 可读文案（供 LLM/用户理解）。批次20 真机修复 R2：补 code=10/11/12（MIUI 常见）。 */
    internal fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到内容（可能环境太吵或语速过快）"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
        SpeechRecognizer.ERROR_NETWORK -> "识别引擎网络异常（厂商引擎多走云端）"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别引擎网络超时"
        // 批次21 真机修复 R3：RECORD_AUDIO 已授予但引擎仍报无权限 = ROM 层（MIUI 安全中心/
        // AppOps）录音限制——文案引导到系统应用权限页而非运行时弹窗
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "录音权限被系统限制——请在系统设置→应用管理→Prism→权限管理 中开启「录音/麦克风」"
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎忙，请稍候重试"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误"
        // Android 12+ 新增错误码（真机 MIUI 实证 code=11 连环出现）
        11 -> "识别服务断连（引擎异常或被系统回收），请重试"
        10 -> "识别请求过于频繁，请稍候重试"
        12 -> "该语言不被当前识别引擎支持"
        else -> "识别失败（code=$error）"
    }

    companion object {
        private const val TAG = "SystemAsrEngine"
    }
}
