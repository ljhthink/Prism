package io.prism.voice

/**
 * 批次23-A（ADR-048，A5）：引擎类别（降级决策入参——ASR 与 TTS 的错误文案口径不同）。
 */
enum class VoiceEngineKind { ASR, TTS }

/** 引擎启动探测失败文案的回退标记（MiniMaxTtsEngine PROBE_ERROR_MESSAGE 等自带）。 */
private const val FALLBACK_MARK = "已回退"

/** 网络类错误关键词（层③会话中断开的典型形态：WS 断连/超时/DNS 等）。 */
private val NETWORK_MARKS = listOf("连接失败", "连接异常", "网络", "超时", "断开")

/** 云端 ASR task-failed 未细分兜底文案前缀（AliCloudAsrEngine.mapTaskFailure else 分支）。 */
private const val ASR_TASK_FAILED_PREFIX = "云端识别失败"

/**
 * 批次23-A（ADR-048，A5）降级决策（纯函数，JVM 单测见 VoiceEngineFallbackTest）。
 *
 * 返回 true = 该错误应触发「本次通话自动切换端侧引擎重试当前动作」（层②启动探测 /
 * 层③会话中，VoiceSessionController.tryFallbackAsr/tryFallbackTts 消费）：
 * - 引擎启动探测失败文案自带 [FALLBACK_MARK]（"已回退"）标记——MiniMax TTS 3s 探测超时
 *   （"云端 TTS 连接失败（网络或 Key 问题），已回退"）；
 * - 网络类错误（[NETWORK_MARKS]）——云端 ASR WS 连接失败/会话中断开的典型形态；
 * - ASR 服务端 task-failed 未细分兜底文案（[ASR_TASK_FAILED_PREFIX]）——引擎当通不可用，
 *   回退端侧保通话。
 *
 * **不自动回退**（返回 false，错误文案原样上抛给状态机/UI）：鉴权失败（Key 无效——配置
 * 问题，回退掩盖不了且下次通话前用户可修）、Key 未配置（装配层已保证不发生）、限流/配额、
 * 麦克风权限类（端侧同样不可用）、MiniMax 合成任务失败（单句失败，本轮文字呈现即可）。
 *
 * @param engineKind 引擎类别（[VoiceEngineKind.ASR] / [VoiceEngineKind.TTS]）
 * @param errorMessage 引擎 onError 原始文案（不匹配任何回退特征 → false）
 */
fun shouldFallbackToOnDevice(engineKind: VoiceEngineKind, errorMessage: String): Boolean {
    val message = errorMessage.trim()
    if (message.isEmpty()) return false
    if (message.contains(FALLBACK_MARK)) return true
    if (NETWORK_MARKS.any { message.contains(it) }) return true
    if (engineKind == VoiceEngineKind.ASR && message.startsWith(ASR_TASK_FAILED_PREFIX)) return true
    return false
}
