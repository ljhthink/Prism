package io.prism.qq

import android.util.Log
import io.prism.voice.local.LocalCloneAudio
import io.prism.voice.local.ExportableTtsEngine
import io.prism.voice.local.LocalCloneConfig
import io.prism.voice.local.LocalCloneEngineFactory
import io.prism.voice.local.LocalVoicePack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 语音合成的最小抽象（供工具执行器注入；测试用假实现）。
 */
interface QqVoiceSource {
    /** @return PCM 样本与采样率；未配置音色包/合成失败时为 null */
    suspend fun synthesize(text: String): LocalCloneAudio?
}

/**
 * QQ 语音消息的 TTS 合成入口（PRD US-3113）。
 *
 * **复用既有音色链路**：与文本播报共用 `LocalCloneTtsEngine`（Prism 音色包 / ZipVoice / VITS），
 * 只把产物从「播放」改为「PCM 样本」——音色因此与 Prism 播报一致（US-3113 AC-3）。
 *
 * **缓存与释放**（guardrail M-06/C5）：[Selection.signature] 覆盖全部生效输入，签名变化重建；
 * 引擎在**空闲 [idleReleaseMs] 后自动释放**，且音色包不可用时立即释放——避免与文本播报引擎
 * 各自常驻一份 118/195MB 模型造成 OOM。
 *
 * **失败语义**：任何失败返回 null（调用方降级为文本，US-3113 AC-4），不抛异常
 * （`CancellationException` 除外）。
 *
 * @param selectionProvider 解析当前生效音色包与生成参数（生产由 PrismApplication 装配；
 *   返回 null 表示未选择/包不可用）
 * @param engineFactory 引擎工厂（测试注入假实现；生产 [LocalCloneEngineFactory]）
 * @param idleReleaseMs 空闲多久后释放原生模型（默认 10 分钟）
 */
class QqVoiceSynthesizer(
    private val selectionProvider: suspend () -> Selection?,
    private val engineFactory: (LocalVoicePack, LocalCloneConfig) -> ExportableTtsEngine? =
        { pack, config -> LocalCloneEngineFactory.create(pack, config) },
    private val idleReleaseMs: Long = DEFAULT_IDLE_RELEASE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : QqVoiceSource {

    /** 一次合成所需的全部生效输入；[signature] 用于缓存失效判定。 */
    data class Selection(
        val pack: LocalVoicePack,
        val config: LocalCloneConfig,
        val signature: String
    )

    private val mutex = Mutex()
    private var engine: ExportableTtsEngine? = null
    private var engineSignature: String? = null
    private var idleJob: Job? = null

    override suspend fun synthesize(text: String): LocalCloneAudio? {
        if (text.isBlank()) return null
        return try {
            mutex.withLock {
                idleJob?.cancel()
                idleJob = null
                try {
                    val selection = selectionProvider()
                    if (selection == null) {
                        // 音色包被删除/未选择：立即释放，避免旧模型常驻
                        releaseLocked()
                        return@withLock null
                    }
                    val active = engine
                    val ready = if (active != null && engineSignature == selection.signature) {
                        active
                    } else {
                        releaseLocked()
                        engineFactory(selection.pack, selection.config)?.also {
                            engine = it
                            engineSignature = selection.signature
                        }
                    } ?: return@withLock null
                    ready.synthesizeForExport(text)
                } finally {
                    // guardrail M-10：**成功/失败/异常都要**重新起算空闲释放，
                    // 否则合成抛错后引擎无计时器常驻。
                    if (engine != null) scheduleIdleRelease()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "QQ 语音合成失败：${e::class.simpleName}")
            null
        }
    }

    /** 释放底层 native 资源（幂等；测试/音色包删除时可显式调用）。 */
    suspend fun release() {
        idleJob?.cancel()
        idleJob = null
        mutex.withLock { releaseLocked() }
    }

    /** 非阻塞释放（内存压力回调使用：不等待在途合成，异步排队释放）。 */
    fun releaseAsync() {
        idleJob?.cancel()
        idleJob = null
        scope.launch { mutex.withLock { releaseLocked() } }
    }

    private fun releaseLocked() {
        runCatching { engine?.release() }
        engine = null
        engineSignature = null
    }

    /** 空闲计时：到期后在锁内释放（与在途合成互斥）。 */
    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleReleaseMs)
            mutex.withLock {
                releaseLocked()
                idleJob = null
            }
        }
    }

    private companion object {
        const val TAG = "QqVoice"

        /** 空闲释放窗口：远大于语音消息的典型间隔，避免每次重载 118MB 级模型。 */
        const val DEFAULT_IDLE_RELEASE_MS = 10 * 60 * 1000L
    }
}
