package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-055：**清单声明 → 引擎实现类**的路由验收（ac-verifier TKN-LVC-015 放行条件 C1/C2）。
 *
 * ## 为什么必须有这个类
 *
 * 两轮 guardrail + 一轮验收都指出同一件事：`LocalCloneEngineFactory.synthesizerClassFor()` 与
 * "清单 → store → factory"这条链**零断言**——只有各自的单元分支测试。
 * 于是一个真实的错配（例如 VITS 包被路由到 ZipVoice 合成器类）**不会被任何用例抓住**：
 * store 测的是"包能不能解析"，factory 测不到，反射解析要到运行期才发生。
 *
 * ## 证据级别限定
 *
 * 本类钉的是**类名字符串与分流决策**（源码/常量级），**不是**"反射真的能创建出实例"——
 * 后者需要 AAR 与 Android 运行时（`src/sherpa` 条件源集在 JVM 单测里不参与编译），
 * 属真机验收项。故本类**只覆盖"链路上最容易断、且能在 JVM 里钉住"的那一环**。
 */
class LocalCloneEngineRoutingTest {

    @Test
    fun `引擎路由 - ZipVoice 包必须解析到 ZipVoice 实现类`() {
        assertEquals(
            "io.prism.voice.local.SherpaZipVoiceSynthesizer",
            LocalCloneEngineFactory.synthesizerClassFor(LocalTtsKind.ZIPVOICE)
        )
    }

    @Test
    fun `引擎路由 - VITS 包必须解析到 VITS 实现类`() {
        assertEquals(
            "io.prism.voice.local.SherpaVitsSynthesizer",
            LocalCloneEngineFactory.synthesizerClassFor(LocalTtsKind.VITS)
        )
    }

    @Test
    fun `引擎路由 - 两种引擎不得解析到同一个类（防复制粘贴式错配）`() {
        assertTrue(
            "ZipVoice 与 VITS 必须是不同的实现类",
            LocalCloneEngineFactory.synthesizerClassFor(LocalTtsKind.ZIPVOICE) !=
                LocalCloneEngineFactory.synthesizerClassFor(LocalTtsKind.VITS)
        )
    }

    /**
     * **端到端路由（可自动化部分）**：从 `voicepack.json` 解析出包，再据此选实现类。
     *
     * 覆盖 ac-verifier 条件 C2 中"store → factory"这一段（最后一段"factory → 反射实例"只能真机验证）。
     */
    @Test
    fun `端到端路由 - 清单声明 tts=vits 时最终选到 VITS 实现类`() {
        val root = File(System.getProperty("java.io.tmpdir"), "lvc-routing-${System.nanoTime()}")
        val packDir = File(root, "cyrene")
        try {
            packDir.mkdirs()
            File(packDir, "model").mkdirs()
            listOf("model/model.fixed.onnx", "model/lexicon.txt", "model/tokens.txt").forEach {
                File(packDir, it).writeBytes(ByteArray(8) { 1 })
            }
            File(packDir, LocalVoicePackStore.MANIFEST_NAME).writeBytes(
                """
                {
                  "schema": "${LocalVoicePackStore.SCHEMA}",
                  "voiceId": "cyrene",
                  "tts": "vits",
                  "model": { "model": "model/model.fixed.onnx", "lexicon": "model/lexicon.txt",
                             "tokens": "model/tokens.txt" },
                  "references": []
                }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )

            val status = LocalVoicePackStore(listOf(root)).resolve("cyrene")
            assertTrue("应 Ready，实际 $status", status is LocalVoiceStatus.Ready)
            val pack = (status as LocalVoiceStatus.Ready).pack

            assertEquals(
                "清单声明 vits ⇒ 必须路由到 VITS 实现类",
                "io.prism.voice.local.SherpaVitsSynthesizer",
                LocalCloneEngineFactory.synthesizerClassFor(pack.tts)
            )
            assertTrue("VITS 包允许空参考集", pack.references.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
