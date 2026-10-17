package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * LVC-1（ADR-053 决策 4）：本地音色包定位/校验单测（PRD AC4）。
 *
 * 契约（`LocalVoicePackStore`）：
 * - 完整包 → [LocalVoiceStatus.Ready]（模型/参考文件全部解析为绝对路径）；
 * - 任一必需文件缺失/为空 / 参考集为空 / 清单畸形 / schema 不符 / **路径逃逸** → [LocalVoiceStatus.Incomplete]；
 * - 未安装 → [LocalVoiceStatus.NotInstalled]；
 * - **任何情况都不抛异常**（设置页可用性优先）。
 */
class LocalVoicePackStoreTest {

    private fun tempRoot(): File = Files.createTempDirectory("localvoice-test").toFile()

    private fun file(dir: File, rel: String, bytes: Int = 16): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes) { 1 })
        return f
    }

    /**
     * 构造一个音色包目录。
     *
     * @param tokensPath 可注入异常路径（测逃逸）
     * @param schema 可注入异常 schema
     * @param refs 参考条目 JSON 片段列表（空 = references 为空数组）
     * @param skip 需要"制造缺失"的模型键
     */
    private fun buildPack(
        root: File,
        id: String = "cyrene",
        schema: String = LocalVoicePackStore.SCHEMA,
        manifestVoiceId: String = id,
        tokensPath: String = "model/tokens.txt",
        refs: List<String> = listOf("""{"id":"ref_00","audio":"refs/ref_00.wav","text":"你好，世界","emotion":"平静"}"""),
        skip: Set<String> = emptySet(),
        emptyDataDir: Boolean = false,
        rawManifest: String? = null,
        bom: Boolean = false,
        refOverrideBytes: Int? = null
    ): File {
        val packDir = File(root, id)
        packDir.mkdirs()
        // 只在"安全相对路径"时真正创建 tokens——逃逸用例不应把文件写到临时目录之外
        val tokensSafe = !tokensPath.contains("..") && !tokensPath.startsWith("/") && !tokensPath.startsWith("\\")
        if ("tokens" !in skip && tokensSafe) file(packDir, tokensPath)
        if ("encoder" !in skip) file(packDir, "model/encoder.int8.onnx")
        if ("decoder" !in skip) file(packDir, "model/decoder.int8.onnx")
        if ("vocoder" !in skip) file(packDir, "model/vocos_24khz.onnx")
        if ("lexicon" !in skip) file(packDir, "model/lexicon.txt")
        if ("dataDir" !in skip) {
            val d = File(packDir, "model/espeak-ng-data")
            d.mkdirs()
            if (!emptyDataDir) File(d, "phondata").writeBytes(ByteArray(8) { 1 })
        }
        refs.forEachIndexed { i, _ ->
            file(packDir, "refs/ref_${i.toString().padStart(2, '0')}.wav", refOverrideBytes ?: 2048)
        }

        val json = rawManifest ?: """
            {
              "schema": "$schema",
              "voiceId": "$manifestVoiceId",
              "name": "测试音色",
              "engine": "zipvoice",
              "model": {
                "tokens": "$tokensPath",
                "encoder": "model/encoder.int8.onnx",
                "decoder": "model/decoder.int8.onnx",
                "vocoder": "model/vocos_24khz.onnx",
                "lexicon": "model/lexicon.txt",
                "dataDir": "model/espeak-ng-data"
              },
              "generation": {"numSteps": 4, "speed": 1.0, "minCharInSentence": 10, "timeoutMs": 15000},
              "references": [${refs.joinToString(",")}]
            }
        """.trimIndent()
        val bytes = (if (bom) "\uFEFF" else "") + json
        File(packDir, LocalVoicePackStore.MANIFEST_NAME).writeText(bytes)
        return packDir
    }

    private fun store(root: File) = LocalVoicePackStore(roots = listOf(root))

    @Test
    fun `完整音色包 - Ready 且路径与参数解析正确`() {
        val root = tempRoot()
        buildPack(root)
        val status = store(root).resolve("cyrene")
        assertTrue("期望 Ready，实际 $status", status is LocalVoiceStatus.Ready)
        val pack = (status as LocalVoiceStatus.Ready).pack
        assertEquals("cyrene", pack.voiceId)
        assertEquals(1, pack.references.size)
        val zip = pack.model as LocalModelFiles.ZipVoice
        assertTrue(zip.decoder.isFile)
        assertTrue(zip.vocoder.isFile)
        assertTrue(zip.dataDir.isDirectory)
        assertTrue(pack.references[0].audio.isFile)
        assertEquals("你好，世界", pack.references[0].text)
        assertEquals(4, pack.generation.numSteps)
        assertEquals(15_000L, pack.generation.timeoutMs)
        assertTrue("totalBytes 应统计到模型与参考音频", pack.totalBytes > 0)
    }

    @Test
    fun `未安装 - NotInstalled`() {
        val root = tempRoot()
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.NotInstalled)
    }

    @Test
    fun `voiceId 含路径分隔符 - 拒绝为 NotInstalled（防目录穿越）`() {
        val root = tempRoot()
        assertTrue(store(root).resolve("../etc") is LocalVoiceStatus.NotInstalled)
        assertTrue(store(root).resolve("a/b") is LocalVoiceStatus.NotInstalled)
        assertTrue(store(root).resolve("..") is LocalVoiceStatus.NotInstalled)
        assertTrue(store(root).resolve("") is LocalVoiceStatus.NotInstalled)
    }

    @Test
    fun `任一模型文件缺失 - Incomplete 且列出缺失项`() {
        for (key in listOf("tokens", "encoder", "decoder", "vocoder", "lexicon")) {
            val root = tempRoot()
            buildPack(root, skip = setOf(key))
            val status = store(root).resolve("cyrene")
            assertTrue("缺 $key 时应为 Incomplete，实际 $status", status is LocalVoiceStatus.Incomplete)
            assertTrue((status as LocalVoiceStatus.Incomplete).problems.isNotEmpty())
        }
    }

    @Test
    fun `音素数据目录缺失或为空 - Incomplete`() {
        val root1 = tempRoot()
        buildPack(root1, skip = setOf("dataDir"))
        assertTrue(store(root1).resolve("cyrene") is LocalVoiceStatus.Incomplete)

        val root2 = tempRoot()
        buildPack(root2, emptyDataDir = true)
        assertTrue(store(root2).resolve("cyrene") is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `参考集为空 - Incomplete`() {
        val root = tempRoot()
        buildPack(root, refs = emptyList())
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
        assertTrue((status as LocalVoiceStatus.Incomplete).problems.any { it.contains("参考音频集为空") })
    }

    @Test
    fun `参考音频文件缺失 - Incomplete`() {
        val root = tempRoot()
        buildPack(root) // 生成 refs/ref_00.wav
        File(root, "cyrene/refs/ref_00.wav").delete()
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `清单畸形 - Incomplete 且不抛异常`() {
        val root = tempRoot()
        buildPack(root, rawManifest = "{ this is not json ")
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `清单顶层非对象 - Incomplete`() {
        val root = tempRoot()
        buildPack(root, rawManifest = "[1,2,3]")
        assertTrue(store(root).resolve("cyrene") is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `schema 不符 - Incomplete`() {
        val root = tempRoot()
        buildPack(root, schema = "prism-localvoice/999")
        assertTrue(store(root).resolve("cyrene") is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `清单路径逃逸 - Incomplete（禁止上跳与绝对路径）`() {
        val root = tempRoot()
        buildPack(root, tokensPath = "../../../etc/passwd")
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
        assertTrue((status as LocalVoiceStatus.Incomplete).problems.any { it.contains("路径不合法") })

        val root2 = tempRoot()
        buildPack(root2, tokensPath = "/etc/passwd")
        assertTrue(store(root2).resolve("cyrene") is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `清单 voiceId 与目录名不一致 - Incomplete`() {
        val root = tempRoot()
        buildPack(root, manifestVoiceId = "someoneelse")
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
        assertTrue((status as LocalVoiceStatus.Incomplete).problems.any { it.contains("目录名不一致") })
    }

    @Test
    fun `BOM 清单可解析 - Ready（容错手工编辑）`() {
        val root = tempRoot()
        buildPack(root, bom = true)
        assertTrue(store(root).resolve("cyrene") is LocalVoiceStatus.Ready)
    }

    @Test
    fun `参考音频超大 - Incomplete（防一次性读入造成 OOM）`() {
        val root = tempRoot()
        buildPack(root, refOverrideBytes = (LocalVoicePackStore.MAX_REFERENCE_BYTES + 1).toInt())
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
        assertTrue((status as LocalVoiceStatus.Incomplete).problems.any { it.contains("过大") })
    }

    @Test
    fun `参考条目过多 - Incomplete（防畸形清单长循环）`() {
        val root = tempRoot()
        val many = (0..LocalVoicePackStore.MAX_REFERENCES).map {
            """{"id":"r$it","audio":"refs/ref_00.wav","text":"你好"}"""
        }
        buildPack(root, refs = many)
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Incomplete)
        assertTrue((status as LocalVoiceStatus.Incomplete).problems.any { it.contains("过多") })
    }

    @Test
    fun `多候选根目录 - 按序取首个命中`() {
        val empty = tempRoot()
        val real = tempRoot()
        buildPack(real)
        val status = LocalVoicePackStore(roots = listOf(empty, real)).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Ready)
    }

    @Test
    fun `候选根 - 取首个含清单者（残留同名目录不得遮蔽，guardrail F-11）`() {
        val shadow = tempRoot()
        File(shadow, "cyrene").mkdirs() // 残留目录：存在但无清单
        val real = tempRoot()
        buildPack(real)
        val status = LocalVoicePackStore(roots = listOf(shadow, real)).resolve("cyrene")
        assertTrue("残留目录不得遮蔽真实音色包，实际 $status", status is LocalVoiceStatus.Ready)
    }

    @Test
    fun `voiceId 白名单 - 盘符与超长与非法字符一律 NotInstalled（guardrail F-12）`() {
        val root = tempRoot()
        val store = store(root)
        assertTrue(store.resolve("C:cyrene") is LocalVoiceStatus.NotInstalled)
        assertTrue(store.resolve("...") is LocalVoiceStatus.NotInstalled)
        assertTrue(store.resolve("cy rene") is LocalVoiceStatus.NotInstalled)
        assertTrue(store.resolve("昔涟") is LocalVoiceStatus.NotInstalled)
        assertTrue(store.resolve("a".repeat(65)) is LocalVoiceStatus.NotInstalled)
        // 白名单内仍正常（大小写/连字符/下划线）
        buildPack(root, id = "cyrene_v2-x")
        assertTrue(store.resolve("cyrene_v2-x") is LocalVoiceStatus.Ready)
    }

    @Test
    fun `清单超大 - Incomplete 且不读入全文（guardrail F-5）`() {
        val root = tempRoot()
        val packDir = File(root, "cyrene")
        packDir.mkdirs()
        // 构造一个超过上限的清单（不解析、不建树即应被拦下）
        File(packDir, LocalVoicePackStore.MANIFEST_NAME)
            .writeText("{\"schema\":\"${LocalVoicePackStore.SCHEMA}\",\"pad\":\"" + "x".repeat(300 * 1024) + "\"}")
        val started = System.currentTimeMillis()
        val status = store(root).resolve("cyrene")
        val cost = System.currentTimeMillis() - started
        assertTrue("期望 Incomplete，实际 $status", status is LocalVoiceStatus.Incomplete)
        assertTrue((status as LocalVoiceStatus.Incomplete).problems.any { it.contains("过大") })
        assertTrue("应在读全文前拦下（耗时 ${cost}ms）", cost < 1_000)
    }

    @Test
    fun `generation 越界字段被钳制为默认值且仍可用（guardrail F-4）`() {
        val root = tempRoot()
        buildPack(
            root,
            rawManifest = """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "model": {
                    "tokens": "model/tokens.txt",
                    "encoder": "model/encoder.int8.onnx",
                    "decoder": "model/decoder.int8.onnx",
                    "vocoder": "model/vocos_24khz.onnx",
                    "lexicon": "model/lexicon.txt",
                    "dataDir": "model/espeak-ng-data"
                  },
                  "generation": {
                    "numSteps": 99999,
                    "speed": -1.0,
                    "minCharInSentence": 0,
                    "maxCharInSentence": -5,
                    "numThreads": 100000,
                    "timeoutMs": 9223372036854775807
                  },
                  "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
                }
            """.trimIndent()
        )
        val status = store(root).resolve("cyrene")
        assertTrue("钳制后应仍可用，实际 $status", status is LocalVoiceStatus.Ready)
        val gen = (status as LocalVoiceStatus.Ready).pack.generation
        assertEquals(LocalCloneConfig.DEFAULT_NUM_STEPS, gen.numSteps)
        assertEquals(LocalCloneConfig.DEFAULT_NUM_THREADS, gen.numThreads)
        assertEquals(LocalCloneConfig.DEFAULT_TIMEOUT_MS, gen.timeoutMs)
        assertEquals(LocalCloneConfig.DEFAULT_SPEED, gen.speed, 0.0001f)
        assertEquals(LocalCloneConfig.DEFAULT_MIN_CHARS, gen.minCharInSentence)
        assertEquals(LocalCloneConfig.DEFAULT_MAX_CHARS, gen.maxCharInSentence)
    }

    @Test
    fun `音质参数 - 清单可配、越界被钳制、且经 extra 送达 native（TKN-LVC-003）`() {
        val root = tempRoot()
        buildPack(
            root,
            rawManifest = """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "model": {
                    "tokens": "model/tokens.txt",
                    "encoder": "model/encoder.int8.onnx",
                    "decoder": "model/decoder.int8.onnx",
                    "vocoder": "model/vocos_24khz.onnx",
                    "lexicon": "model/lexicon.txt",
                    "dataDir": "model/espeak-ng-data"
                  },
                  "generation": {
                    "silenceScale": 0.0,
                    "guidanceScale": 99.0,
                    "featScale": 0.2,
                    "tShift": 2.0,
                    "targetRms": 0.0
                  },
                  "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
                }
            """.trimIndent()
        )
        val gen = (store(root).resolve("cyrene") as LocalVoiceStatus.Ready).pack.generation

        // 越界值回落官方默认（F-1/F-2：featScale 窄域 0.05~0.15、silenceScale 下限 0.01 —— 0.0 会回落到默认）
        assertEquals(LocalCloneConfig.DEFAULT_SILENCE_SCALE, gen.silenceScale, 1e-4f)
        assertEquals(LocalCloneConfig.DEFAULT_FEAT_SCALE, gen.featScale, 1e-4f)
        assertEquals(LocalCloneConfig.DEFAULT_GUIDANCE_SCALE, gen.guidanceScale, 1e-4f)
        assertEquals(LocalCloneConfig.DEFAULT_T_SHIFT, gen.tShift, 1e-4f)
        assertEquals(LocalCloneConfig.DEFAULT_TARGET_RMS, gen.targetRms, 1e-4f)

        // 必须真的经 extra 送达 native（C++ GetExtraFloat 优先读 extra）
        assertEquals("3.0", gen.extra["guidance_scale"])
        assertEquals("0.1", gen.extra["feat_scale"])
        assertEquals("0.5", gen.extra["t_shift"])
        assertEquals("0.1", gen.extra["target_rms"])
        assertEquals("10", gen.extra["min_char_in_sentence"])
        assertEquals("200", gen.extra["max_char_in_sentence"])
        // num_steps 走 GenerationConfig.numSteps 字段（上游默认值表达式读该字段），**不在 extra 里**
        assertFalse("num_steps 不应重复出现在 extra", gen.extra.containsKey("num_steps"))
    }

    @Test
    fun `featScale 只允许贴官方的窄域 - 越界即回落且不得放过`() {
        // 官方唯一正确值 0.1；作者警告 0.5 不可用、社区实测 0.2 变机械音 → 只留 0.05~0.15
        assertEquals(0.1f, LocalCloneConfig.DEFAULT_FEAT_SCALE, 1e-6f)
        assertEquals(LocalCloneConfig.DEFAULT_FEAT_SCALE, LocalCloneConfig(featScale = 0.2f).sanitized().featScale, 1e-4f)
        assertEquals(LocalCloneConfig.DEFAULT_FEAT_SCALE, LocalCloneConfig(featScale = 0.5f).sanitized().featScale, 1e-4f)
        assertEquals(0.05f, LocalCloneConfig(featScale = 0.05f).sanitized().featScale, 1e-4f)
        assertEquals(0.15f, LocalCloneConfig(featScale = 0.15f).sanitized().featScale, 1e-4f)
    }

    @Test
    fun `silenceScale 下限 0_01 - 0 与负数都回落默认而非静默失效（F-2）`() {
        // 上游 ScaleSilence 有 kMinSilenceScale=0.01 守卫，低于它会被拒绝并跳过（等同不缩放）
        assertEquals(1.0f, LocalCloneConfig.DEFAULT_SILENCE_SCALE, 1e-6f)
        assertEquals("0 应回落到默认 1.0（若留在域内会静默无效果）", 1.0f,
            LocalCloneConfig(silenceScale = 0.0f).sanitized().silenceScale, 1e-4f)
        assertEquals(1.0f, LocalCloneConfig(silenceScale = -1.0f).sanitized().silenceScale, 1e-4f)
        assertEquals(0.01f, LocalCloneConfig(silenceScale = 0.01f).sanitized().silenceScale, 1e-4f)
    }

    @Test
    fun `withRolePackOverride 只覆盖 numSteps 且越界或 null 不覆盖（guardrail F-7）`() {
        val base = LocalCloneConfig(numSteps = 8, speed = 0.9f)
        assertEquals("null 不覆盖（本次角色包 numSteps=null 即依赖此语义）",
            base, base.withRolePackOverride(null))
        assertEquals(4, base.withRolePackOverride(4).numSteps)
        assertEquals("其它字段不受影响", base.speed, base.withRolePackOverride(4).speed, 1e-6f)
        assertEquals("越界不覆盖", base, base.withRolePackOverride(0))
        assertEquals("越界不覆盖", base, base.withRolePackOverride(LocalCloneConfig.MAX_STEPS + 1))
    }

    @Test
    fun `静音缩放默认必须为 1_0 - 否则停顿被压缩致听感变快（TKN-LVC-003）`() {
        // sherpa 默认 0.2 会把 >0.2s 停顿压到 20%（`if (silence_scale != 1) ScaleSilence`）——
        // 机制上这是"语速过快、节奏不像角色"的**首要嫌疑**（证据级别：上游源码/默认值 = 一手；
        // 听感是否改善 = 待真机 A/B），故默认值必须钉住
        assertEquals(1.0f, LocalCloneConfig.DEFAULT_SILENCE_SCALE, 1e-6f)
        val root = tempRoot()
        buildPack(root)
        val gen = (store(root).resolve("cyrene") as LocalVoiceStatus.Ready).pack.generation
        assertEquals("清单未给该字段时也必须是不缩放", 1.0f, gen.silenceScale, 1e-6f)
    }

    @Test
    fun `generation 钳制必须保持 min 小于等于 max 且幂等（guardrail R2 N-1 C7）`() {
        // min 合法（800 ∈ 1..1000）而 max 越界：若 max 回落 DEFAULT_MAX_CHARS=200，
        // 就会产出 min(800) > max(200) 的非法组合并直达 native extra
        val raw = LocalCloneConfig(minCharInSentence = 800, maxCharInSentence = -5)
        val once = raw.sanitized()
        assertEquals(800, once.minCharInSentence)
        assertEquals(800, once.maxCharInSentence)
        assertTrue("min ≤ max 必须恒成立", once.minCharInSentence <= once.maxCharInSentence)
        assertEquals("钳制必须幂等", once, once.sanitized())
        assertEquals("重复钳制结果不随次数变化", once, raw.sanitized().sanitized())
    }

    @Test
    fun `generation 合法值不被改动（钳制不得误伤）`() {
        val root = tempRoot()
        buildPack(
            root,
            rawManifest = """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "model": {
                    "tokens": "model/tokens.txt",
                    "encoder": "model/encoder.int8.onnx",
                    "decoder": "model/decoder.int8.onnx",
                    "vocoder": "model/vocos_24khz.onnx",
                    "lexicon": "model/lexicon.txt",
                    "dataDir": "model/espeak-ng-data"
                  },
                  "generation": {
                    "numSteps": 8, "speed": 1.2, "minCharInSentence": 20,
                    "maxCharInSentence": 300, "numThreads": 3, "timeoutMs": 45000
                  },
                  "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
                }
            """.trimIndent()
        )
        val gen = (store(root).resolve("cyrene") as LocalVoiceStatus.Ready).pack.generation
        assertEquals(8, gen.numSteps)
        assertEquals(3, gen.numThreads)
        assertEquals(45_000L, gen.timeoutMs)
        assertEquals(1.2f, gen.speed, 0.0001f)
        assertEquals(20, gen.minCharInSentence)
        assertEquals(300, gen.maxCharInSentence)
    }

    @Test
    fun `generation sentenceGapMs 生效 - 段间停顿由清单声明（播放层，两引擎同源）`() {
        val root = tempRoot()
        buildPack(
            root,
            rawManifest = """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "engine": "zipvoice",
                  "model": {
                    "tokens": "model/tokens.txt",
                    "encoder": "model/encoder.int8.onnx",
                    "decoder": "model/decoder.int8.onnx",
                    "vocoder": "model/vocos_24khz.onnx",
                    "lexicon": "model/lexicon.txt",
                    "dataDir": "model/espeak-ng-data"
                  },
                  "generation": {"sentenceGapMs": 350},
                  "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
                }
            """.trimIndent()
        )
        val gen = (store(root).resolve("cyrene") as LocalVoiceStatus.Ready).pack.generation
        assertEquals("清单声明的段间停顿必须原样生效（不得被钳制误伤）", 350, gen.sentenceGapMs)
        assertEquals("合法域上限必须放行", LocalCloneConfig.MAX_SENTENCE_GAP_MS, 2_000)
        assertEquals("合法域下限必须放行", LocalCloneConfig.MIN_SENTENCE_GAP_MS, 0)
    }

    @Test
    fun `generation sentenceGapMs 越界回落默认 0 - 不钳到边界（脏数据不得改变听感基线）`() {
        // 与既有 coerceIn 语义**不同**：越界回落 DEFAULT_SENTENCE_GAP_MS=0（= 不插静音，等价改造前），
        // 而不是钳成 2000/0 的边界值——边界钳制会让脏数据静默改变听感基线
        val high = tempRoot()
        buildPack(high, rawManifest = gapManifest(99_999))
        val highGen = (store(high).resolve("cyrene") as LocalVoiceStatus.Ready).pack.generation
        assertEquals(
            "超上限必须回落默认 0（不得被钳成 2000ms）",
            LocalCloneConfig.DEFAULT_SENTENCE_GAP_MS,
            highGen.sentenceGapMs
        )
        assertEquals(0, highGen.sentenceGapMs)

        val low = tempRoot()
        buildPack(low, rawManifest = gapManifest(-5))
        val lowGen = (store(low).resolve("cyrene") as LocalVoiceStatus.Ready).pack.generation
        assertEquals("负数必须回落默认 0", LocalCloneConfig.DEFAULT_SENTENCE_GAP_MS, lowGen.sentenceGapMs)
    }

    /** 只含 `sentenceGapMs` 的清单原文（越界用例共用）。 */
    private fun gapManifest(gapMs: Int): String = """
        {
          "schema": "${LocalVoicePackStore.SCHEMA}",
          "voiceId": "cyrene",
          "engine": "zipvoice",
          "model": {
            "tokens": "model/tokens.txt",
            "encoder": "model/encoder.int8.onnx",
            "decoder": "model/decoder.int8.onnx",
            "vocoder": "model/vocos_24khz.onnx",
            "lexicon": "model/lexicon.txt",
            "dataDir": "model/espeak-ng-data"
          },
          "generation": {"sentenceGapMs": $gapMs},
          "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
        }
    """.trimIndent()

    @Test
    fun `generation 缺省兜底 - 使用 TKN-LVC-003 音质调优默认值`() {
        val root = tempRoot()
        buildPack(
            root,
            rawManifest = """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "model": {
                    "tokens": "model/tokens.txt",
                    "encoder": "model/encoder.int8.onnx",
                    "decoder": "model/decoder.int8.onnx",
                    "vocoder": "model/vocos_24khz.onnx",
                    "lexicon": "model/lexicon.txt",
                    "dataDir": "model/espeak-ng-data"
                  },
                  "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
                }
            """.trimIndent()
        )
        val status = store(root).resolve("cyrene")
        assertTrue(status is LocalVoiceStatus.Ready)
        val gen = (status as LocalVoiceStatus.Ready).pack.generation
        assertEquals(8, gen.numSteps)
        assertEquals(10, gen.minCharInSentence)
        assertEquals(200, gen.maxCharInSentence)
        assertEquals(60_000L, gen.timeoutMs)
        // TKN-LVC-003 音质调优默认值（缺省 = ZipVoice 官方训练/推理值）
        assertEquals(LocalCloneConfig.DEFAULT_SILENCE_SCALE, gen.silenceScale, 0.0001f)
        assertEquals(LocalCloneConfig.DEFAULT_GUIDANCE_SCALE, gen.guidanceScale, 0.0001f)
        assertEquals(LocalCloneConfig.DEFAULT_FEAT_SCALE, gen.featScale, 0.0001f)
        assertEquals(LocalCloneConfig.DEFAULT_T_SHIFT, gen.tShift, 0.0001f)
        assertEquals(LocalCloneConfig.DEFAULT_TARGET_RMS, gen.targetRms, 0.0001f)
    }

    // ==================== ADR-055：VITS 音色包 ====================

    @Test
    fun `ZipVoice 包缺 tts 字段时仍解析为 ZipVoice（向后兼容）`() {
        val root = tempRoot()
        buildPack(root)
        val pack = (store(root).resolve("cyrene") as LocalVoiceStatus.Ready).pack
        assertEquals(LocalTtsKind.ZIPVOICE, pack.tts)
        assertTrue("model 必须是 ZipVoice 变体", pack.model is LocalModelFiles.ZipVoice)
    }

    @Test
    fun `VITS 包 - 无参考音频也可用，且资产按引擎分流`() {
        val root = tempRoot()
        buildVitsPack(root)
        val status = store(root).resolve("cyrene")
        assertTrue("应 Ready，实际 $status", status is LocalVoiceStatus.Ready)
        val pack = (status as LocalVoiceStatus.Ready).pack
        assertEquals(LocalTtsKind.VITS, pack.tts)
        assertTrue("references 允许为空（音色在权重里）", pack.references.isEmpty())
        val vits = pack.model as LocalModelFiles.Vits
        assertTrue(vits.model.isFile)
        assertTrue(vits.lexicon.isFile)
        assertTrue(vits.tokens.isFile)
        assertTrue("dictDir 存在时须解析出来", vits.dictDir?.isDirectory == true)
        assertEquals("ruleFsts 应全部解析", 2, vits.ruleFsts.size)
        assertTrue("totalBytes 应统计到模型资产", pack.totalBytes > 0)
    }

    @Test
    fun `VITS 包 - dictDir 与 ruleFsts 缺失不阻断安装（可选资产）`() {
        val root = tempRoot()
        buildVitsPack(root, withDict = false, ruleFsts = emptyList())
        val status = store(root).resolve("cyrene")
        assertTrue("可选资产缺失不应判不可用，实际 $status", status is LocalVoiceStatus.Ready)
        val vits = (status as LocalVoiceStatus.Ready).pack.model as LocalModelFiles.Vits
        assertEquals("dictDir 缺失应回落 null", null, vits.dictDir)
        assertTrue("ruleFsts 缺失应回落空列表", vits.ruleFsts.isEmpty())
    }

    @Test
    fun `VITS 包 - 声明了 ruleFsts 但文件不存在时判不可用（N3：fail-fast，不静默丢规则）`() {
        val root = tempRoot()
        val packDir = buildVitsPack(root, ruleFsts = listOf("model/date.fst"))
        // 删掉其中一个已声明的文件 ⇒ 清单说要用它、文件却不在 ⇒ 判不可用
        assertTrue(File(packDir, "model/date.fst").delete())
        val status = store(root).resolve("cyrene")
        assertTrue("应 Incomplete（运行期静默丢规则更难查），实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `VITS 包 - 缺 model onnx 判不可用`() {
        val root = tempRoot()
        buildVitsPack(root, withModel = false)
        val status = store(root).resolve("cyrene")
        assertTrue("应 Incomplete，实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `tts 取值无法识别时判不可用（不得静默回落 ZipVoice）`() {
        // ⚠️ 本用例的夹具必须是**合法 ZipVoice 形状**（六资产齐全 + 参考音频非空）。
        // 否则"静默回落 ZipVoice"会因缺资产而照样判 Incomplete —— 用例就变成**空断言**
        // （guardrail TKN-LVC-015 变异 M1 实测：旧夹具下把 parse 改回 `?: ZIPVOICE` 仍 33 例全绿）。
        val root = tempRoot()
        buildPack(
            root,
            rawManifest = """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "tts": "vitz",
                  "model": {
                    "tokens": "model/tokens.txt",
                    "encoder": "model/encoder.int8.onnx",
                    "decoder": "model/decoder.int8.onnx",
                    "vocoder": "model/vocos_24khz.onnx",
                    "lexicon": "model/lexicon.txt",
                    "dataDir": "model/espeak-ng-data"
                  },
                  "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
                }
            """.trimIndent()
        )
        val status = store(root).resolve("cyrene")
        assertTrue("回落 ZipVoice 会把这个包判成 Ready —— 必须判 Incomplete，实际 $status",
            status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `VITS 包 - ruleFsts 超过上限判不可用（N2：清单可被改写，数组必须有上界）`() {
        val root = tempRoot()
        val many = (1..(LocalVoicePackStore.MAX_RULE_FSTS + 1)).map { "model/r$it.fst" }
        buildVitsPack(root, ruleFsts = many)
        val status = store(root).resolve("cyrene")
        assertTrue("应 Incomplete，实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `VITS 包 - ruleFsts 重复条目去重后不越界`() {
        val root = tempRoot()
        val dup = List(LocalVoicePackStore.MAX_RULE_FSTS * 2) { "model/date.fst" }
        buildVitsPack(root, ruleFsts = dup)
        val status = store(root).resolve("cyrene")
        assertTrue("同一条重复不应触发上限，实际 $status", status is LocalVoiceStatus.Ready)
        val vits = (status as LocalVoiceStatus.Ready).pack.model as LocalModelFiles.Vits
        assertEquals("去重后只应剩 1 条", 1, vits.ruleFsts.size)
    }

    @Test
    fun `清单 voiceId 超长或含非法字符时判不可用（N5：该值进日志与设置页）`() {
        val root = tempRoot()
        buildPack(root, rawManifest = """
            {
              "schema": "${LocalVoicePackStore.SCHEMA}",
              "voiceId": "${"a".repeat(200)}",
              "model": { "tokens": "model/tokens.txt", "encoder": "model/encoder.int8.onnx",
                "decoder": "model/decoder.int8.onnx", "vocoder": "model/vocos_24khz.onnx",
                "lexicon": "model/lexicon.txt", "dataDir": "model/espeak-ng-data" },
              "references": [{"audio": "refs/ref_00.wav", "text": "你好"}]
            }
        """.trimIndent())
        val status = store(root).resolve("cyrene")
        assertTrue("应 Incomplete，实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `VITS 包 - dictDir 逃逸路径判不可用（C8：与 ruleFsts 同口径，必须有用例钉住）`() {
        // guardrail R3 的变异 M-A1 实证：去掉该 problem 后**没有任何层级变红** ⇒ 此前是"源码改了但没钉住"。
        // 本用例即钉住"声明了 dictDir 但路径非法 ⇒ 判不可用"（而非静默当成未声明）。
        val root = tempRoot()
        buildVitsPack(root, dictDirOverride = "../evil")
        val status = store(root).resolve("cyrene")
        assertTrue("逃逸路径必须判不可用，实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `VITS 包 - dictDir 绝对路径判不可用`() {
        val root = tempRoot()
        buildVitsPack(root, dictDirOverride = "C:/windows/system32")
        val status = store(root).resolve("cyrene")
        assertTrue("绝对路径必须判不可用，实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    @Test
    fun `VITS 包 - dictDir 指向文件而非目录时判不可用`() {
        val root = tempRoot()
        // 先按正常路径建包，再把 dictDir 指向一个**文件**
        val packDir = buildVitsPack(root, withDict = false)
        file(packDir, "model/not-a-dir.txt")
        buildVitsPack(root, withDict = false, dictDirOverride = "model/not-a-dir.txt")
        val status = store(root).resolve("cyrene")
        assertTrue("指向文件必须判不可用，实际 $status", status is LocalVoiceStatus.Incomplete)
    }

    /** 造一个 VITS 音色包（`tts: "vits"`；参考音频默认不放）。 */
    private fun buildVitsPack(
        root: File,
        id: String = "cyrene",
        tts: String = "vits",
        withModel: Boolean = true,
        withDict: Boolean = true,
        dictDirOverride: String? = null,
        ruleFsts: List<String> = listOf("model/date.fst", "model/number.fst"),
        /** 注入 `generation` 段原文（TKN-LVC-016 的 VITS 旋钮测试用；null = 不输出该段）。 */
        generationJson: String? = null
    ): File {
        val packDir = File(root, id)
        packDir.mkdirs()
        file(packDir, "model/lexicon.txt")
        file(packDir, "model/tokens.txt")
        if (withModel) file(packDir, "model/model.fixed.onnx")
        if (withDict) {
            val d = File(packDir, "model/dict")
            d.mkdirs()
            File(d, "jieba.dict.utf8").writeBytes(ByteArray(8) { 1 })
        }
        ruleFsts.forEach { file(packDir, it) }
        val dictValue = dictDirOverride ?: if (withDict) "model/dict" else null
        val dictLine = if (dictValue != null) """"dictDir": "$dictValue",""" else ""
        val fstLine = if (ruleFsts.isEmpty()) "" else
            """"ruleFsts": [${ruleFsts.joinToString(",") { "\"$it\"" }}],"""
        val genLine = if (generationJson == null) "" else """"generation": $generationJson,"""
        val json = """
            {
              "schema": "${LocalVoicePackStore.SCHEMA}",
              "voiceId": "$id",
              "name": "VITS 音色",
              "tts": "$tts",
              "model": {
                "model": "model/model.fixed.onnx",
                "lexicon": "model/lexicon.txt",
                "tokens": "model/tokens.txt",
                $dictLine
                $fstLine
                "unused": true
              },
              $genLine
              "references": []
            }
        """.trimIndent()
        val manifest = File(packDir, LocalVoicePackStore.MANIFEST_NAME)
        manifest.writeBytes(json.toByteArray(Charsets.UTF_8))
        return packDir
    }

    // ==================== TKN-LVC-016（ADR-056）：VITS 生成旋钮 ====================

    private fun vitsPack(root: File, generation: String?): LocalVoicePack {
        buildVitsPack(root, generationJson = generation)
        val status = store(root).resolve("cyrene")
        assertTrue("期望 Ready，实际 $status", status is LocalVoiceStatus.Ready)
        return (status as LocalVoiceStatus.Ready).pack
    }

    @Test
    fun `VITS 旋钮 - 清单缺失时取官方默认（旧包行为逐字段不变）`() {
        val pack = vitsPack(tempRoot(), generation = null)
        assertEquals(VitsGeneration.DEFAULT_NOISE_SCALE, pack.vitsGeneration.noiseScale, 0f)
        assertEquals(VitsGeneration.DEFAULT_NOISE_SCALE_W, pack.vitsGeneration.noiseScaleW, 0f)
    }

    @Test
    fun `VITS 旋钮 - 清单声明时按声明生效`() {
        val pack = vitsPack(tempRoot(), generation = """{"speed": 0.8, "noiseScale": 0.5, "noiseScaleW": 0.6}""")
        assertEquals(0.8f, pack.generation.speed, 0.0001f)
        assertEquals(0.5f, pack.vitsGeneration.noiseScale, 0.0001f)
        assertEquals(0.6f, pack.vitsGeneration.noiseScaleW, 0.0001f)
    }

    @Test
    fun `VITS 旋钮 - 越界与 NaN 钳制为默认值且不判不可用`() {
        val pack = vitsPack(
            tempRoot(),
            generation = """{"noiseScale": 99.0, "noiseScaleW": -3.0}"""
        )
        assertEquals(VitsGeneration.DEFAULT_NOISE_SCALE, pack.vitsGeneration.noiseScale, 0f)
        assertEquals(VitsGeneration.DEFAULT_NOISE_SCALE_W, pack.vitsGeneration.noiseScaleW, 0f)
    }

    @Test
    fun `VITS 旋钮 - 0 是合法值（确定性推理，供 A-B 对拍用）`() {
        val pack = vitsPack(tempRoot(), generation = """{"noiseScale": 0, "noiseScaleW": 0}""")
        assertEquals(0f, pack.vitsGeneration.noiseScale, 0f)
        assertEquals(0f, pack.vitsGeneration.noiseScaleW, 0f)
    }

    @Test
    fun `VITS 旋钮 - sanitized 幂等（不改变合法值）`() {
        val g = VitsGeneration(noiseScale = 0.5f, noiseScaleW = 0.6f)
        assertEquals(g, g.sanitized())
        assertEquals(VitsGeneration(), VitsGeneration().sanitized())
    }
}
