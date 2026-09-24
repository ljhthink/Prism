package io.prism.voice.local

import io.prism.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LVC-1（ADR-053 决策 3/7）：本地音色引擎行为与降级单测（PRD AC5）。
 *
 * 覆盖的硬契约（考古报告「不可破坏的不变量」 + guardrail R1 修复项）：
 * - 回调 `onStart/onDone/onError` **恰好一次**；停止/被取代/已释放的请求**不回调**；
 * - 合成失败/超时/零音频 → `onError` 且文案含"已回退"（降级链触发标记）；
 * - `release()` 幂等、有界等待、超时跳过 native 释放（F-8）；
 * - `isAvailable` 不随模型加载状态变化（否则通话链会静默跳过整轮播报）；
 * - **单飞**：同一合成器不得被并发进入（F-1）；
 * - **首因取消**：被 stop/取代的请求不得被随后触发的看门狗改写成"超时"（F-3）；
 * - **播放入口探针**：停止早于播放开始时不得出声（F-2）；
 * - 引擎**不抛异常**（`speak` 在无异常处理器的协程内被调用）。
 *
 * 测试纪律：事件列表用 `CopyOnWriteArrayList`（跨线程写、主线程读），等待用 [awaitUntil]
 * 有界轮询而非裸 `Thread.sleep`（guardrail F-10）。
 */
class LocalCloneTtsEngineTest {

    // ==================== 测试替身 ====================

    private class RecListener : TtsEngine.Listener {
        val events = CopyOnWriteArrayList<String>()

        override fun onStart(utteranceId: String) {
            events += "start"
        }

        override fun onDone(utteranceId: String) {
            events += "done"
        }

        override fun onError(utteranceId: String, message: String) {
            events += "error:$message"
        }
    }

    /** 有界轮询等待（替代裸 sleep）。 */
    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** 明确的"窗口期"等待（用于断言"此后不应再发生变化"）；不是轮询条件，故保留固定时长。 */
    private fun settleWindow(ms: Long) {
        Thread.sleep(ms)
    }

    /**
     * 假合成器。
     *
     * @param failure 每次调用抛出该异常
     * @param blockUntilCancelled 忽略...不，此处语义为：**尊重**取消探针地阻塞
     * @param ignoreProbe 阻塞且**无视**取消探针（模拟"native 无法及时中断"）
     * @param blockMs 阻塞时长（ignoreProbe=false 时生效，用于单飞/取代用例）
     * @param onEnter 进入时回调
     * @param audioProvider 产出（默认 2 样本 @24kHz）
     */
    private class FakeSynth(
        private val failure: Throwable? = null,
        private val blockUntilCancelled: Boolean = false,
        private val ignoreProbe: Boolean = false,
        private val ignoreProbeMs: Long = 0L,
        private val blockMs: Long = 0L,
        private val onEnter: (() -> Unit)? = null,
        private val audioProvider: () -> LocalCloneAudio = { LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000) }
    ) : LocalCloneSynthesizer {
        val synthCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        /** ADR-055：VITS 路径下引擎必须把 null 参考传进来（音色在权重里）。 */
        @Volatile
        var lastReferenceWasNull: Boolean = false
            private set

        override fun synthesize(
            text: String,
            reference: LocalReference?,
            config: LocalCloneConfig,
            isCancelled: () -> Boolean,
            onChunk: (FloatArray) -> Unit
        ): LocalCloneAudio {
            synthCalls.incrementAndGet()
            lastReferenceWasNull = reference == null
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            try {
                onEnter?.invoke()
                if (ignoreProbeMs > 0L) {
                    // 有界地忽略探针：合成必然返回，从而能观测"合成之后"的首因判定
                    val deadline = System.currentTimeMillis() + ignoreProbeMs
                    while (System.currentTimeMillis() < deadline) Thread.sleep(5)
                    return LocalCloneAudio(FloatArray(0), 0)
                }
                if (ignoreProbe) {
                    val deadline = System.currentTimeMillis() + 8_000
                    while (System.currentTimeMillis() < deadline) Thread.sleep(5)
                    return LocalCloneAudio(FloatArray(0), 0)
                }
                if (blockUntilCancelled) {
                    val deadline = System.currentTimeMillis() + 8_000
                    while (!isCancelled() && System.currentTimeMillis() < deadline) Thread.sleep(2)
                    return LocalCloneAudio(FloatArray(0), 0)
                }
                if (blockMs > 0) {
                    val deadline = System.currentTimeMillis() + blockMs
                    while (System.currentTimeMillis() < deadline && !isCancelled()) Thread.sleep(2)
                    // 阻塞期间即使被取消也返回有效音频：用于验证"取消后不得播放/不得回调"
                    return audioProvider()
                }
                failure?.let { throw it }
                return audioProvider()
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun release() {
            releaseCalls.incrementAndGet()
        }
    }

    /** 假播放器：**尊重入口探针**（与生产实现同语义），记录调用次数。 */
    private class FakePlayer(private val blockUntilStoppedMs: Long = 0L) : LocalClonePlayer {
        val playCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)

        @Volatile
        var lastSampleRate = 0

        @Volatile
        var lastSamples = 0

        /** 累计写入样本数（段间停顿用例：断言"各段之和 + (段数-1)×gap"）。 */
        val playedTotalSamples = AtomicInteger(0)

        /** 每次 `play` 的样本数（顺序敏感：末次必须是末段音频，不是静音）。 */
        val playedSizes = CopyOnWriteArrayList<Int>()

        private val stopSignal = CountDownLatch(1)

        override fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean) {
            if (isCancelled()) return // 入口探针：停止早于播放开始 → 不出声
            playCalls.incrementAndGet()
            lastSampleRate = sampleRate
            lastSamples = samples.size
            playedTotalSamples.addAndGet(samples.size)
            playedSizes += samples.size
            if (blockUntilStoppedMs > 0) {
                stopSignal.await(blockUntilStoppedMs, TimeUnit.MILLISECONDS)
            }
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            while (stopSignal.count > 0) stopSignal.countDown()
        }

        override fun release() {
            stop()
            releaseCalls.incrementAndGet()
        }
    }

    private fun pack(
        refs: List<LocalReference> = listOf(defaultRef()),
        tts: LocalTtsKind = LocalTtsKind.ZIPVOICE,
        model: LocalModelFiles = LocalModelFiles.ZipVoice(
            File("tokens.txt"), File("encoder.onnx"), File("decoder.onnx"),
            File("vocos.onnx"), File("lexicon.txt"), File("espeak-ng-data")
        )
    ) = LocalVoicePack(
        voiceId = "cyrene",
        name = "测试音色",
        root = File("."),
        tts = tts,
        model = model,
        generation = LocalCloneConfig(),
        references = refs,
        totalBytes = 0L
    )

    private fun defaultRef() = LocalReference(
        id = "ref_00",
        audio = File("ref_00.wav"),
        text = "你好，世界",
        emotion = "平静"
    )

    /** 同步执行（Unconfined）：请求在 `speak()` 返回前跑完，断言确定性最高。 */
    private fun syncEngine(
        synth: LocalCloneSynthesizer,
        player: LocalClonePlayer,
        config: LocalCloneConfig = LocalCloneConfig(),
        refs: List<LocalReference> = listOf(defaultRef())
    ): LocalCloneTtsEngine = LocalCloneTtsEngine(
        pack = pack(refs),
        config = config,
        synthesizerFactory = { synth },
        playerFactory = { player },
        ioDispatcher = Dispatchers.Unconfined,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    /** 真实并发（多线程 IO）：用于单飞/取消/看门狗等需要真实并发的用例。 */
    private fun concurrentEngine(
        synth: LocalCloneSynthesizer,
        player: LocalClonePlayer,
        config: LocalCloneConfig = LocalCloneConfig(),
        synthFactory: (() -> LocalCloneSynthesizer)? = null,
        disposeTimeoutMs: Long = LocalCloneTtsEngine.DEFAULT_DISPOSE_TIMEOUT_MS,
        nowMs: () -> Long = { System.currentTimeMillis() }
    ): LocalCloneTtsEngine = LocalCloneTtsEngine(
        pack = pack(),
        config = config,
        synthesizerFactory = { synthFactory?.invoke() ?: synth },
        playerFactory = { player },
        ioDispatcher = Dispatchers.IO,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        disposeTimeoutMs = disposeTimeoutMs,
        // 测试默认注入真实时钟（`SystemClock.elapsedRealtime()` 在 JVM 单测恒 0 ⇒ 看门狗永不触发 ⇒ 空断言）
        nowMs = nowMs
    )

    // ==================== 正常路径 ====================

    @Test
    fun `就绪合成 - onStart 与 onDone 各一次且用引擎回报采样率播放`() = runBlocking {
        val synth = FakeSynth()
        val player = FakePlayer()
        val engine = syncEngine(synth, player)
        val listener = RecListener()

        engine.speak("你好，世界", listener)

        assertEquals(listOf("start", "done"), listener.events.toList())
        assertEquals(1, synth.synthCalls.get())
        assertEquals(1, player.playCalls.get())
        assertEquals(24_000, player.lastSampleRate)
        assertEquals(2, player.lastSamples)
        engine.release()
    }

    @Test
    fun `可用性 - 参考集非空且未释放时为 true`() = runBlocking {
        val engine = syncEngine(FakeSynth(), FakePlayer())
        assertTrue(engine.isAvailable)
        engine.release()
        assertFalse("释放后必须报不可用", engine.isAvailable)
    }

    @Test
    fun `可用性 - 参考集为空时为 false`() = runBlocking {
        val engine = syncEngine(FakeSynth(), FakePlayer(), refs = emptyList())
        assertFalse(engine.isAvailable)
        engine.release()
    }

    // ==================== ADR-055：VITS 包的可用性必须与 ZipVoice 分流 ====================

    @Test
    fun `可用性 - VITS 包无参考音频时仍必须可用（N1：否则整链静默不出声）`() = runBlocking {
        val synth = FakeSynth()
        val player = FakePlayer()
        val engine = vitsEngine(synth, player)
        assertTrue("VITS 的音色在权重里，空参考集必须仍可用", engine.isAvailable)
        engine.release()
    }

    @Test
    fun `VITS 包 - speak 不得因缺参考音频而回退（且把 null 参考传给合成器）`() = runBlocking {
        val synth = FakeSynth()
        val engine = vitsEngine(synth, FakePlayer())
        val listener = RecListener()

        engine.speak("你好，世界", listener)

        assertEquals("应正常播完，实际 ${listener.events}", listOf("start", "done"), listener.events.toList())
        assertEquals(1, synth.synthCalls.get())
        assertTrue("VITS 必须收到 null 参考（音色在权重里）", synth.lastReferenceWasNull)
        engine.release()
    }

    private fun vitsEngine(synth: LocalCloneSynthesizer, player: LocalClonePlayer): LocalCloneTtsEngine =
        LocalCloneTtsEngine(
            pack = pack(refs = emptyList(), tts = LocalTtsKind.VITS, model = LocalModelFiles.Vits(
                File("model.onnx"), File("lexicon.txt"), File("tokens.txt"), null, emptyList()
            )),
            config = LocalCloneConfig(),
            synthesizerFactory = { synth },
            playerFactory = { player },
            ioDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    // ==================== 失败与降级 ====================

    @Test
    fun `合成抛异常 - 恰好一次 onError 且含已回退标记（无 onStart onDone）`() = runBlocking {
        val engine = syncEngine(FakeSynth(failure = IllegalStateException("boom")), FakePlayer())
        val listener = RecListener()

        engine.speak("你好，世界", listener)

        assertEquals(1, listener.events.size)
        assertTrue("实际=${listener.events}", listener.events[0].startsWith("error:"))
        assertTrue("降级标记缺失：${listener.events[0]}", listener.events[0].contains("已回退"))
        engine.release()
    }

    @Test
    fun `合成器构建失败 - onError 含模型加载失败且不再重试`() = runBlocking {
        val factoryCalls = AtomicInteger(0)
        val engine = LocalCloneTtsEngine(
            pack = pack(),
            config = LocalCloneConfig(),
            synthesizerFactory = {
                factoryCalls.incrementAndGet()
                throw NoClassDefFoundError("sherpa")
            },
            playerFactory = { FakePlayer() },
            ioDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        val first = RecListener()
        engine.speak("你好", first)
        assertTrue("实际=${first.events}", first.events.single().contains("模型加载失败"))
        assertTrue(first.events.single().contains("已回退"))

        val second = RecListener()
        engine.speak("再见", second)
        assertTrue(second.events.single().startsWith("error:"))
        // 失败被记住：不再重复付加载代价
        assertEquals(1, factoryCalls.get())
        engine.release()
    }

    @Test
    fun `零音频 - onError 含已回退（不得静默当作成功）`() = runBlocking {
        val emptySynth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ) = LocalCloneAudio(FloatArray(0), 0)

            override fun release() = Unit
        }
        val engine = syncEngine(emptySynth, FakePlayer())
        val listener = RecListener()

        engine.speak("你好", listener)

        assertEquals(1, listener.events.size)
        assertTrue(listener.events[0].contains("未产出音频"))
        assertTrue(listener.events[0].contains("已回退"))
        engine.release()
    }

    @Test
    fun `空文本 - onError 且不触发合成`() = runBlocking {
        val synth = FakeSynth()
        val engine = syncEngine(synth, FakePlayer())
        val listener = RecListener()

        engine.speak("♪「」   ", listener)

        assertEquals(1, listener.events.size)
        assertTrue(listener.events[0].startsWith("error:"))
        assertEquals(0, synth.synthCalls.get())
        engine.release()
    }

    @Test
    fun `无可用参考音频 - onError 且不触发合成`() = runBlocking {
        val synth = FakeSynth()
        val engine = syncEngine(synth, FakePlayer(), refs = emptyList())
        val listener = RecListener()

        engine.speak("你好", listener)

        assertEquals(1, listener.events.size)
        assertTrue(listener.events[0].contains("无可用参考音频"))
        assertEquals(0, synth.synthCalls.get())
        engine.release()
    }

    // ==================== 资源释放 ====================

    @Test
    fun `release - 释放合成器与播放器各一次且幂等`() = runBlocking {
        val synth = FakeSynth()
        val player = FakePlayer()
        val engine = syncEngine(synth, player)

        engine.speak("你好", RecListener())
        engine.release()
        engine.release() // 幂等：不得二次释放 native 资源

        assertEquals(1, synth.releaseCalls.get())
        assertEquals(1, player.releaseCalls.get())
    }

    @Test
    fun `release 后 speak - onError 且含已回退标记（通话链不得静默结束本轮）`() = runBlocking {
        val engine = syncEngine(FakeSynth(), FakePlayer())
        engine.release()
        val listener = RecListener()

        engine.speak("你好", listener)

        assertEquals(1, listener.events.size)
        assertTrue(listener.events[0].startsWith("error:"))
        // guardrail F-7：缺"已回退"时 VoiceSessionController.tryFallbackTts 会拒绝回退
        assertTrue("实际=${listener.events[0]}", listener.events[0].contains("已回退"))
    }

    @Test
    fun `release 有界等待超时 - 跳过 native 释放（不并发释放换取不崩溃）`() {
        val entered = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val releaseCalls = AtomicInteger(0)
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                entered.countDown()
                releaseLatch.await(5, TimeUnit.SECONDS) // 无视探针：模拟不可中断的 native 调用
                return LocalCloneAudio(FloatArray(0), 0)
            }

            override fun release() {
                releaseCalls.incrementAndGet()
            }
        }
        val engine = concurrentEngine(synth, FakePlayer(), disposeTimeoutMs = 200L)
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue("合成未进入", entered.await(5, TimeUnit.SECONDS))
        engine.release()

        // 独占权被在途 native 调用占住 → 200ms 后走"跳过释放"分支（否则并发 release 会崩）
        settleWindow(700)
        assertEquals("超时分支不得释放 native 资源", 0, releaseCalls.get())
        assertTrue("已释放/被取消的请求不得回调，实际=${listener.events}", listener.events.isEmpty())

        releaseLatch.countDown()
        settleWindow(300)
    }

    // ==================== 中断语义（真实并发） ====================

    @Test
    fun `stop - 作废在途请求（不回调且不进入播放）`() {
        val entered = CountDownLatch(1)
        val synth = FakeSynth(blockUntilCancelled = true, onEnter = { entered.countDown() })
        val player = FakePlayer()
        val engine = concurrentEngine(synth, player)
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue("合成未进入", entered.await(5, TimeUnit.SECONDS))
        engine.stop()

        assertTrue(
            "停止后不得回调（上层代际守卫已收敛状态）",
            awaitUntil(2_000) { synth.synthCalls.get() >= 1 }
        )
        settleWindow(400)
        assertEquals("停止的请求不得进入播放", 0, player.playCalls.get())
        assertTrue("停止后不得回调，实际=${listener.events}", listener.events.isEmpty())
        engine.release()
    }

    @Test
    fun `stop - 播放阶段打断（播放器收到 stop 且不再发 onDone）`() {
        val player = FakePlayer(blockUntilStoppedMs = 3_000)
        val engine = concurrentEngine(FakeSynth(), player)
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue("播放未进入", awaitUntil(2_000) { player.playCalls.get() >= 1 })
        assertEquals(listOf("start"), listener.events.toList())

        engine.stop()

        assertTrue("停止必须打断播放器", awaitUntil(2_000) { player.stopCalls.get() >= 1 })
        settleWindow(400) // 留出补发窗口
        assertEquals("停止后不得补发 onDone", listOf("start"), listener.events.toList())
        engine.release()
    }

    @Test
    fun `stop 早于播放开始 - 入口探针使其不出声（guardrail F-2）`() {
        val engineRef = AtomicReference<LocalCloneTtsEngine?>(null)
        val player = FakePlayer()
        val engine = concurrentEngine(FakeSynth(), player)
        engineRef.set(engine)
        val events = CopyOnWriteArrayList<String>()
        val listener = object : TtsEngine.Listener {
            override fun onStart(utteranceId: String) {
                events += "start"
                engineRef.get()?.stop() // 在 onStart 里停止：播放尚未开始
            }

            override fun onDone(utteranceId: String) {
                events += "done"
            }

            override fun onError(utteranceId: String, message: String) {
                events += "error:$message"
            }
        }

        engine.speak("你好，世界", listener)

        assertTrue("onStart 应已发出", awaitUntil(2_000) { events.contains("start") })
        settleWindow(400)
        assertEquals("停止早于播放开始 → 不得出声", 0, player.playCalls.get())
        assertEquals("停止后不得补发 onDone", listOf("start"), events.toList())
        engine.release()
    }

    @Test
    fun `新 speak 取代在途请求 - 旧请求不回调`() {
        // 语义变更（TKN-LVC-011 G-1）：超时**不再**取消合成，故本用例不再依赖"第二句被超时取消"。
        // 改为直接验证**取代语义**：第一次调用阻塞至探针变真（被取代）后返回；第二次立即返回有效音频。
        // 注意：`ensureSynthesizer()` 缓存实例 ⇒ 必须用**同一个合成器按调用序号分支**，不能换实例。
        val entered = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    while (!isCancelled()) Thread.sleep(2) // 被取代后探针变真 ⇒ 放弃
                    return LocalCloneAudio(FloatArray(0), 0)
                }
                return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000)
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 500L))
        val first = RecListener()
        val second = RecListener()

        engine.speak("第一句", first)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.speak("第二句", second)

        assertTrue("第二句应正常播完，实际=${second.events}", awaitUntil(5_000) { second.events.contains("done") })
        assertEquals(listOf("start", "done"), second.events.toList())
        assertTrue("被取代的请求不得回调，实际=${first.events}", first.events.isEmpty())
        engine.release()
    }

    @Test
    fun `合成分段持续推进时不得被看门狗误杀 - 防残音频（TKN-LVC-011）`() = runBlocking {
        // 真机缺陷：绝对时限到点 => 取消探针变真 => sherpa 回调返回 STOP => 丢弃后续分段，
        // 产出残音频而日志仍打"合成完成" => 用户听到"播报播到一半异常停止"。
        // PC 复现实证：第 2 段后 STOP => 19.02s 变 13.25s。
        // 新语义：只要仍在产出分段（onChunk 心跳），总耗时超过 timeoutMs 也不得取消。
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                repeat(6) {
                    if (isCancelled()) return LocalCloneAudio(FloatArray(0), 0) // 被误杀 => 残音频（空）
                    onChunk(floatArrayOf(0.1f)) // 心跳：本段有产出
                    // 心跳间隔 100ms = 阈值 300ms 的 1/3（guardrail G-6 要求余量 >=3x，防调度抖动误判）
                    Thread.sleep(100) // 6 x 100ms = 600ms，远超 timeoutMs=300ms
                }
                return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000)
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 300L))
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue("持续推进的合成必须正常播完（不得被判超时）", awaitUntil(5_000) { listener.events.contains("done") })
        assertEquals("不得回退系统引擎", listOf("start", "done"), listener.events.toList())
        engine.release()
    }
    @Test
    fun `已出声后失败不得触发降级重播 —— 应收敛为 onDone（TKN-LVC-012 N2）`() = runBlocking {
        // 护栏 N2（本轮新引入）：逐段播放后中途抛异常时，旧 catch 会发含"已回退"的 onError，
        // 上层据此**重播整段**（用户听到重复）。修复：已出声 ⇒ 收敛 onDone，异常只留日志。
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                if (calls.incrementAndGet() == 1) return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000)
                throw IllegalStateException("第二段失败")
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 5_000L))
        val listener = RecListener()

        engine.speak("今天天气很好，我们一起出去走走吧。等风停了，我就带你去看那边。", listener)
        assertTrue("应收敛为 onDone", awaitUntil(5_000) { listener.events.contains("done") })
        assertEquals("不得回调含已回退的错误（否则上层重播整段）", listOf("start", "done"), listener.events.toList())
        engine.release()
    }

    @Test
    fun `长文本必须切段合成并逐段播放 —— 单次无界合成会导致无声与被杀（TKN-LVC-012）`() = runBlocking {
        // 真机缺陷（BUG-20260920-006）：长文本被当作一次无界合成 ⇒ 合成期间完全没声音，
        // 且内存/耗时被推到系统容忍线外（真机日志：`Sending signal. PID: 24565 SIG: 9`，堆 217MB/221MB）。
        // 修复：按标点切段（每段 ≤40 字），逐段合成 + 逐段播放，第一段算完即出声。
        val synth = FakeSynth()
        val player = FakePlayer()
        val engine = concurrentEngine(synth, player, LocalCloneConfig(timeoutMs = 5_000L))
        val listener = RecListener()

        val longText = "今天天气很好，我们一起出去走走吧。等风停了，我就带你去看那边的云。" +
            "路上要是遇见卖糖葫芦的，你可别又走不动路呀。" +
            "我还想跟你说说，昨天夜里我梦见了一片很安静的湖。"
        engine.speak(longText, listener)

        assertTrue("必须播完", awaitUntil(8_000) { listener.events.contains("done") })
        assertEquals("onStart/onDone 各一次", listOf("start", "done"), listener.events.toList())
        assertTrue("长文本必须被切成多段合成（实际调用 ${synth.synthCalls.get()} 次）", synth.synthCalls.get() >= 2)
        assertEquals("每段都要播放（段数应与合成次数一致）", synth.synthCalls.get(), player.playCalls.get())
        engine.release()
    }

    /** 段间停顿用例共用的长文本（必被切成多段）。 */
    private val gapLongText = "今天天气很好，我们一起出去走走吧。等风停了，我就带你去看那边的云。" +
        "路上要是遇见卖糖葫芦的，你可别又走不动路呀。" +
        "我还想跟你说说，昨天夜里我梦见了一片很安静的湖。"

    @Test
    fun `段间停顿 - 相邻段之间插入静音且末段之后不插（sentenceGapMs）`() = runBlocking {
        // 真机"语速过快"的根因是停顿被压缩；段间停顿是**播放层**补偿（ZipVoice/VITS 同源）。
        // 契约（与生产逐字对齐）：连续模式下每段一次 `play`，段间再插一个**独立静音 piece**，
        // 而**最后一段之后不插**（否则尾音被拖长、onDone 延迟）。
        val synth = FakeSynth()
        val player = FakePlayer()
        val gapMs = 100
        val engine = concurrentEngine(
            synth, player,
            LocalCloneConfig(timeoutMs = 5_000L, sentenceGapMs = gapMs)
        )
        val listener = RecListener()
        engine.speak(gapLongText, listener)

        assertTrue("必须播完", awaitUntil(8_000) { listener.events.contains("done") })
        val segCount = synth.synthCalls.get()
        assertTrue("前提：长文本必须被切成多段（实际 $segCount）", segCount >= 2)
        val perSegment = 2 // FakeSynth 默认产出 2 样本
        // 期望值必须**按边界类型逐段算**（段边界 ≠ 句边界：句末满额、子句打折、硬切 0）
        val segments = segmentForSynthesis(gapLongText, DEFAULT_SEGMENT_CHARS, FIRST_SEGMENT_CHARS)
        val expectedGapSamples = segments.dropLast(1).sumOf {
            LocalCloneSegmentGap.silenceSamples(LocalCloneSegmentGap.gapMsFor(it, gapMs), 24_000)
        }
        assertTrue("前提：该文本必须至少有一个边界产生停顿（实际 $expectedGapSamples）", expectedGapSamples > 0)
        assertEquals(
            "写入总样本数 = 各段之和 + 各边界按类型判定的停顿（末段之后不得有多余静音）",
            segCount * perSegment + expectedGapSamples,
            player.playedTotalSamples.get()
        )
        assertEquals(
            "最后一块必须是末段音频而不是静音",
            perSegment,
            player.playedSizes.last()
        )
        engine.release()
    }

    @Test
    fun `段间停顿 - 逗号边界只插四成而不是满额（段边界不等于句边界）`() = runBlocking {
        // 切分规则（LocalCloneTextSegmenter）：句末标点优先 → 句内过长退到子句标点 → 仍无则硬切。
        // 在**逗号**边界插满额会让试听听到明显顿挫、并污染"定值"结论，故子句档必须打折。
        val synth = FakeSynth()
        val player = FakePlayer()
        val gapMs = 100
        // 逗号恰在第 16 字符（= FIRST_SEGMENT_CHARS，硬上限）：首段必以 "，" 结尾
        val textWithComma = "一二三四五六七八九十甲乙丙丁戊，" +
            "然后我们一起去山上看那远远的云还有风里的花香和鸟鸣"
        val segments = segmentForSynthesis(textWithComma, DEFAULT_SEGMENT_CHARS, FIRST_SEGMENT_CHARS)
        assertEquals("前提：必须恰好切成 2 段（实际 $segments）", 2, segments.size)
        assertTrue("前提：首段必须以逗号结尾（实际『${segments[0]}』）", segments[0].endsWith("，"))
        val clauseGapMs = LocalCloneSegmentGap.gapMsFor(segments[0], gapMs)
        val fullSamples = LocalCloneSegmentGap.silenceSamples(gapMs, 24_000)
        val clauseSamples = LocalCloneSegmentGap.silenceSamples(clauseGapMs, 24_000)
        assertEquals("前提：逗号档 = round(base×0.4)", 40, clauseGapMs)
        assertEquals("前提：逗号档样本数", 960, clauseSamples)
        assertTrue("前提：逗号档必须严格小于满额（$clauseSamples vs $fullSamples）", clauseSamples < fullSamples)

        val engine = concurrentEngine(
            synth, player,
            LocalCloneConfig(timeoutMs = 5_000L, sentenceGapMs = gapMs)
        )
        val listener = RecListener()
        engine.speak(textWithComma, listener)

        assertTrue("必须播完", awaitUntil(8_000) { listener.events.contains("done") })
        assertEquals("必须恰好 2 段", 2, synth.synthCalls.get())
        assertEquals(
            "逗号边界必须插四成（若插满额则总数会多出 ${fullSamples - clauseSamples} 样本）",
            2 * 2 + clauseSamples,
            player.playedTotalSamples.get()
        )
        assertEquals("静音片段的长度必须是四成档而不是满额", clauseSamples, player.playedSizes[1])
        engine.release()
    }

    @Test
    fun `段间停顿 - 无标点硬切文本一律不插（词中间不得出现停顿）`() = runBlocking {
        // 硬切可能切在词中间：此处插停顿 = "词中间凭空停顿"，比改动前更糟 ⇒ 必须 0
        val synth = FakeSynth()
        val player = FakePlayer()
        // 65 字、**无任何标点**（句末/子句标点都不含）⇒ 全部边界都是硬切
        val hardCutText = "春风吹过山岗带走最后一片雪".repeat(5)
        val segments = segmentForSynthesis(hardCutText, DEFAULT_SEGMENT_CHARS, FIRST_SEGMENT_CHARS)
        assertTrue("前提：必须切成多段（实际 ${segments.size}）", segments.size >= 2)
        segments.dropLast(1).forEach {
            assertEquals("前提：每个边界都必须是硬切档（0）", 0, LocalCloneSegmentGap.gapMsFor(it, 400))
        }

        val engine = concurrentEngine(
            synth, player,
            LocalCloneConfig(timeoutMs = 5_000L, sentenceGapMs = 400)
        )
        val listener = RecListener()
        engine.speak(hardCutText, listener)

        assertTrue("必须播完", awaitUntil(8_000) { listener.events.contains("done") })
        val segCount = synth.synthCalls.get()
        assertEquals("段间静音样本总数必须为 0", segCount * 2, player.playedTotalSamples.get())
        assertEquals("不得有任何额外的静音 play 调用", segCount, player.playCalls.get())
        engine.release()
    }

    @Test
    fun `段间停顿默认 0 - 写入样本数与改造前一致（回归钉子）`() = runBlocking {
        // 默认 0 必须与改造前**逐字节等价**：不插任何静音 piece，play 次数 == 段数。
        val synth = FakeSynth()
        val player = FakePlayer()
        val engine = concurrentEngine(
            synth, player,
            LocalCloneConfig(timeoutMs = 5_000L, sentenceGapMs = 0)
        )
        val listener = RecListener()
        engine.speak(gapLongText, listener)

        assertTrue("必须播完", awaitUntil(8_000) { listener.events.contains("done") })
        val segCount = synth.synthCalls.get()
        assertTrue("前提：长文本必须被切成多段（实际 $segCount）", segCount >= 2)
        assertEquals(
            "gap=0 时写入样本数必须恰为各段之和（没有额外静音）",
            segCount * 2,
            player.playedTotalSamples.get()
        )
        assertEquals("gap=0 时播放次数必须恰为段数", segCount, player.playCalls.get())
        engine.release()
    }

    @Test
    fun `看门狗无进展到点后 - 取消探针绝不得变真（G-1 真正守卫，C-1）`() = runBlocking {
        // ⚠️ 本用例**必须注入时基**：`isReturnDefaultValues = true` 使 `SystemClock.elapsedRealtime()`
        // 在 JVM 单测恒 0 ⇒ 不注入时看门狗停滞分支永不触发，用例会退化成空断言
        // （封口复审 M1 实证：把"超时接回探针"后 0 例变红）。
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val probeEverTrue = java.util.concurrent.atomic.AtomicBoolean(false)
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                clock.addAndGet(5_000) // 无心跳窗口已远超阈值（timeoutMs=300）
                val deadline = System.currentTimeMillis() + 800 // 活过看门狗若干轮轮询（250ms/轮）
                while (System.currentTimeMillis() < deadline) {
                    if (isCancelled()) {
                        probeEverTrue.set(true)
                        return LocalCloneAudio(FloatArray(0), 0) // 被中止 ⇒ 残音频
                    }
                    Thread.sleep(5)
                }
                return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000)
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(
            synth = synth,
            player = FakePlayer(),
            config = LocalCloneConfig(timeoutMs = 300L),
            nowMs = { clock.get() }
        )
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue("必须正常播完（超时不得中止合成）", awaitUntil(5_000) { listener.events.contains("done") })
        assertFalse(
            "看门狗到点后取消探针绝不能变真——否则 sherpa 丢弃其后全部分段 ⇒ 残音频（BUG-20260920-005）",
            probeEverTrue.get()
        )
        assertEquals(listOf("start", "done"), listener.events.toList())
        engine.release()
    }

    @Test
    fun `合成慢于 timeoutMs 且无心跳时 - 超时也不得取消合成（G-1 闭合）`() = runBlocking {
        // 真机缺陷（BUG-20260920-005）：超时经取消探针让 sherpa 丢弃其后全部分段 ⇒ 残音频。
        // 新语义：**超时绝不取消合成**（native 不可中断，取消只会丢数据），只告警。
        // 本用例：单段耗时 600ms > timeoutMs=300ms 且期间无心跳 ⇒ 仍必须完整播出。
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                Thread.sleep(600) // 远超 timeoutMs，且全程无 onChunk 心跳
                if (isCancelled()) return LocalCloneAudio(FloatArray(0), 0) // 被取消 => 残音频（空）
                return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000)
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 300L))
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue("超时不得把它判成失败", awaitUntil(5_000) { listener.events.contains("done") })
        assertEquals(listOf("start", "done"), listener.events.toList())
        engine.release()
    }

    @Test
    fun `用户 stop 后合成返回 - 不得补播也不得报错（放弃语义）`() = runBlocking {
        val entered = CountDownLatch(1)
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                entered.countDown()
                while (!isCancelled()) Thread.sleep(2) // 等到用户停止（探针只认放弃语义）
                return LocalCloneAudio(floatArrayOf(0.25f, -0.25f), 24_000)
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 300L))
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.stop()
        Thread.sleep(400)
        assertEquals("用户已停止，不得补播也不得报错", emptyList<String>(), listener.events.toList())
        engine.release()
    }

    @Test
    fun `合成挂死时不产生虚假超时 - 后续请求等锁超时才报错降级`() = runBlocking {
        // 真挂死的兜底是**等锁超时**（后续请求有界失败并降级），而不是丢弃已生成的音频。
        val entered = CountDownLatch(1)
        val hung = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                entered.countDown()
                Thread.sleep(30_000) // 模拟不可中断的 native 挂死
                return LocalCloneAudio(FloatArray(0), 0)
            }

            override fun release() = Unit
        }
        val engine = concurrentEngine(hung, FakePlayer(), LocalCloneConfig(timeoutMs = 200L))
        val first = RecListener()
        val second = RecListener()

        engine.speak("第一句", first)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.speak("第二句", second)

        assertTrue("后续请求等锁超时后必须报错以触发降级", awaitUntil(8_000) { second.events.isNotEmpty() })
        assertTrue("实际=${second.events}", second.events[0].contains("超时"))
        assertTrue(second.events[0].contains("已回退"))
        assertEquals("挂死请求自身不得产生虚假超时回调", emptyList<String>(), first.events.toList())
    }


    @Test
    fun `stop 后合成仍无视探针阻塞 - 不得回调 onError 超时（首因判定，guardrail F-3）`() {
        val entered = CountDownLatch(1)
        // 无视探针：模拟"native 无法被回调中断"——此时看门狗会照常到点
        val synth = FakeSynth(ignoreProbe = true, onEnter = { entered.countDown() })
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 250L))
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.stop() // 首因 = STOPPED

        // 等过看门狗时点（250ms）后再等一段，确认没有被改写成"超时"回调
        settleWindow(1_200)
        assertTrue(
            "被 stop 的请求不得收到 onError(超时)，实际=${listener.events}",
            listener.events.isEmpty()
        )
        engine.release()
    }

    @Test
    fun `首因判定 - 被 stop 的请求不得被随后触发的看门狗改写成超时回调（guardrail R2 N-4①）`() {
        val entered = CountDownLatch(1)
        // 有界地"无视探针"：合成会在 600ms 后返回，从而真正走到"合成后"的 when 判定
        // ——若首因是"最后写入胜出"，此时 cause 会被看门狗改写成 TIMEOUT 并发出错误回调
        val synth = FakeSynth(ignoreProbeMs = 600, onEnter = { entered.countDown() })
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 250L))
        val listener = RecListener()

        engine.speak("你好，世界", listener)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.stop() // 首因 = STOPPED（早于 250ms 的看门狗）

        settleWindow(1_500) // 覆盖"合成返回(600ms) + 看门狗(250ms) + 余量"
        assertTrue(
            "被 stop 的请求不得被改写成超时回调，实际=${listener.events}",
            listener.events.isEmpty()
        )
        engine.release()
    }

    @Test
    fun `取代后 - 旧请求零回调且新请求仍被正常处理（不因旧请求的看门狗被丢弃）`() {
        val callIndex = AtomicInteger(0)
        val secondEntered = CountDownLatch(1)
        val synth = object : LocalCloneSynthesizer {
            override fun synthesize(
                text: String,
                reference: LocalReference?,
                config: LocalCloneConfig,
                isCancelled: () -> Boolean,
                onChunk: (FloatArray) -> Unit
            ): LocalCloneAudio {
                val call = callIndex.incrementAndGet()
                if (call == 1) return LocalCloneAudio(floatArrayOf(0.1f), 24_000) // 预热：让播放器被创建
                secondEntered.countDown()
                val deadline = System.currentTimeMillis() + 700
                while (System.currentTimeMillis() < deadline) Thread.sleep(5) // 无视探针地阻塞
                return LocalCloneAudio(FloatArray(0), 0)
            }

            override fun release() = Unit
        }
        val player = FakePlayer()
        val engine = concurrentEngine(synth, player, LocalCloneConfig(timeoutMs = 250L))

        // 预热一轮：确保玩家实例存在、且第一句确实会在"独占权被占用"状态下被取代
        engine.speak("预热", RecListener())
        assertTrue("预热未播放", awaitUntil(2_000) { player.playCalls.get() >= 1 })

        val first = RecListener()
        val second = RecListener()
        engine.speak("第一句", first)
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        engine.speak("第二句", second) // 取代；第一句的看门狗随后会在"它已不在台"时触发

        // 行为观测（替代易抖动的 stop 计数）：第一句零回调；第二句仍被正常处理（有且仅有一次终态回调）
        assertTrue("第二句应被处理并给出终态回调", awaitUntil(4_000) { second.events.isNotEmpty() })
        settleWindow(400)
        assertEquals("第二句只允许一次终态回调，实际=${second.events}", 1, second.events.size)
        assertTrue(
            "取代后的第一句不得回调（尤其不得被陈旧看门狗改写成超时），实际=${first.events}",
            first.events.isEmpty()
        )
        engine.release()
    }

    @Test
    fun `并发 speak - 同一合成器不得被并发进入（单飞，guardrail F-1）`() {
        val synth = FakeSynth(blockMs = 200L)
        val engine = concurrentEngine(synth, FakePlayer(), LocalCloneConfig(timeoutMs = 5_000L))
        val first = RecListener()
        val second = RecListener()

        engine.speak("第一句", first)
        awaitUntil(1_000) { synth.synthCalls.get() >= 1 }
        engine.speak("第二句", second)

        assertTrue("两轮应都被处理", awaitUntil(4_000) { synth.synthCalls.get() >= 2 })
        assertEquals("不得并发进入 native 合成", 1, synth.maxConcurrent.get())
        engine.release()
    }
}
