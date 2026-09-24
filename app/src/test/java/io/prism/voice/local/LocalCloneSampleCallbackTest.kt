package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * LVC-1 真机崩溃修复（2026-09-20）的机制级护栏。
 *
 * **背景**：sherpa-onnx JNI 按回调对象的**运行时类**硬编码查找
 * `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")`。Kotlin lambda 经 invokedynamic/
 * D8 脱糖只暴露擦除签名 `invoke(Ljava/lang/Object;)Ljava/lang/Object;` → 查找失败 →
 * `NoSuchMethodError` + `NewFloatArray` → **SIGABRT 崩进程**（真机 logcat 实证）。
 *
 * 本测试用**与 JNI 同构的反射查找**钉住该契约：一旦有人把回调改回 lambda、或改回原语返回的
 * `fun interface`，[jni 同构查找必须命中] 会变红。
 */
class LocalCloneSampleCallbackTest {

    @Test
    fun `JNI 同构查找必须命中且返回装箱 Integer`() {
        val callback = LocalCloneSampleCallback(isCancelled = { false })

        // 与 offline-tts.cc::CallCallback 等价的查找动作
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)

        assertEquals("JNI 硬编码要求的返回类型是装箱 Integer", java.lang.Integer::class.java, method.returnType)
        assertTrue("必须是实例方法", !Modifier.isStatic(method.modifiers))
        assertEquals("描述符必须与 JNI 硬编码一致", "([F)Ljava/lang/Integer;", descriptorOf(method))
    }

    @Test
    fun `预检与 JNI 查找同构 - 对具名类通过`() {
        assertTrue(LocalCloneSampleCallback.hasJniCompatibleInvoke(LocalCloneSampleCallback(isCancelled = { false })))
    }

    @Test
    fun `预检可识别不兼容形态 - lambda 缺 JNI 要求的描述符`() {
        // lambda 的运行时类没有 invoke([F)Ljava/lang/Integer;（这是真机崩溃的形态）
        val lambda: (FloatArray) -> Int = { 1 }
        assertFalse(
            "若此断言失败，说明 lambda 已能提供 JNI 描述符（需重新评估修复结论）",
            LocalCloneSampleCallback.hasJniCompatibleInvoke(lambda)
        )
    }

    @Test
    fun `回调语义 - 未取消继续 已取消中止`() {
        assertEquals(1, LocalCloneSampleCallback(isCancelled = { false }).invoke(FloatArray(16)))
        assertEquals(0, LocalCloneSampleCallback(isCancelled = { true }).invoke(FloatArray(16)))
        assertEquals(
            LocalCloneSampleCallback.CONTINUE_GENERATION,
            LocalCloneSampleCallback(isCancelled = { false }).invoke(FloatArray(0))
        )
        assertEquals(LocalCloneSampleCallback.STOP_GENERATION, LocalCloneSampleCallback(isCancelled = { true }).invoke(FloatArray(0)))
    }

    @Test
    fun `取消探针每次调用都重新求值（不得缓存）`() {
        var cancelled = false
        val callback = LocalCloneSampleCallback(isCancelled = { cancelled })
        assertEquals(1, callback.invoke(FloatArray(1)))
        cancelled = true
        assertEquals("探针必须在每次回调时重新求值", 0, callback.invoke(FloatArray(1)))
    }

    /** JVM 方法描述符（参数 + 返回值），用于与 JNI 字符串逐字比对。 */
    private fun descriptorOf(method: java.lang.reflect.Method): String {
        val params = method.parameterTypes.joinToString("") { descriptorOf(it) }
        return "($params)${descriptorOf(method.returnType)}"
    }

    private fun descriptorOf(type: Class<*>): String = when (type) {
        java.lang.Integer::class.java -> "Ljava/lang/Integer;"
        java.lang.Void.TYPE -> "V"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Integer.TYPE -> "I"
        FloatArray::class.java -> "[F"
        else -> "L${type.name.replace('.', '/')};"
    }

    // ==================== 调用点守卫（TKN-LVC-002 guardrail 第 3 点） ====================

    /**
     * **调用点**守卫：`SherpaZipVoiceSynthesizer` 必须把 [LocalCloneSampleCallback] 的**实例**传给
     * `generateWithConfigAndCallback`，不得就地写 lambda。
     *
     * 存在的理由：guardrail 实测证明——把调用点改回 lambda 后**全部 3029 个用例仍然全绿**
     * （类级契约测试钉不住调用点）。本用例对**源码文本**做结构断言，正是为补这个缺口：
     * 改动一旦发生，它会变红。
     *
     * **证据级别限定**：本用例断言的是源码结构（不是运行时行为）；运行时行为由
     * [JNI 同构查找必须命中且返回装箱 Integer] + 生产预检 `hasJniCompatibleInvoke` 双重把守。
     * **不依赖运行目录**：从 CWD 逐级向上找 `settings.gradle.kts` 定位仓库根（rules/04 §11.3）。
     */
    @Test
    fun `调用点不得把 lambda 传给 sherpa 生成回调`() {
        val source = zipVoiceSynthesizerSource()

        assertTrue(
            "必须构造具名类实例：`val callback = LocalCloneSampleCallback(isCancelled, ...)`",
            source.contains("val callback = LocalCloneSampleCallback(isCancelled)")
        )
        assertTrue(
            "必须把该实例交给 native 回调位：`callback = callback`",
            source.contains("callback = callback")
        )
        // guardrail TKN-LVC-011 G-6：不能只断言"某个字符串出现过"（挪行即可绕过），
        // 而要钉住**同一构造表达式**：具名类实例 + 心跳计数 + 心跳外传必须在同一个 lambda 内。
        assertTrue(
            "必须在同一表达式中把心跳接到回调上（chunkCount++ 与 onChunk 必须同属该 lambda）",
            Regex(
                "LocalCloneSampleCallback\\(isCancelled\\)\\s*\\{\\s*chunk\\s*->\\s*" +
                    "chunkCount\\.incrementAndGet\\(\\);\\s*onChunk\\(chunk\\)\\s*\\}"
            ).containsMatchIn(source)
        )
        assertFalse(
            "禁止就地写 lambda（真机 SIGABRT 的形态）",
            Regex("callback\\s*=\\s*\\{").containsMatchIn(source)
        )
        assertTrue(
            "调用 native 前必须做同构预检（fail-closed，防不可捕获的 native abort）",
            source.contains("hasJniCompatibleInvoke")
        )
    }

    private fun zipVoiceSynthesizerSource(): String {
        val relativePath = "app/src/sherpa/kotlin/io/prism/voice/local/SherpaZipVoiceSynthesizer.kt"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("未能从 ${System.getProperty("user.dir")} 向上定位 $relativePath")
    }
}
