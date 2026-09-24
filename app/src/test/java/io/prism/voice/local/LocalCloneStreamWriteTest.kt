package io.prism.voice.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LVC-2 流式写入准入判定（guardrail 第二轮 R2-N2 的可测出口）。
 *
 * ## 为什么单独有这个用例类
 *
 * 生产实现 [AudioTrackLocalClonePlayer.StreamSession] 是 **private inner class**、构造需 `AudioTrack`
 * ⇒ JVM 单测无法实例化；写在它内部的"空块 = 无操作"判定**无法被任何用例钉住**（guardrail R2-N2
 * 判定为未验证残留）。把判定抽成纯函数 [streamWriteAccepted] 后，该语义即可被直接钉住——
 * 这也正是本轮整改的目的：**可验证优先于"看起来更内聚"**。
 */
class LocalCloneStreamWriteTest {

    @Test
    fun `空块必须可接受（无操作）——不得与会话作废混为一谈`() {
        // 会话已失效时空块仍返回 true：调用方据此只"跳过本块"，而不是判定输出栈永久失效
        assertTrue("空块 + 会话有效 ⇒ 接受（无操作）", streamWriteAccepted(0, sessionValid = true))
        assertTrue("空块 + 会话已失效 ⇒ 仍接受（本块无音频可丢，不构成新失效）", streamWriteAccepted(0, sessionValid = false))
    }

    @Test
    fun `非空块必须以会话有效性为准`() {
        assertTrue("会话有效 ⇒ 接受", streamWriteAccepted(480, sessionValid = true))
        assertFalse("会话失效 ⇒ 拒绝（调用方须停止写入）", streamWriteAccepted(480, sessionValid = false))
    }

    @Test
    fun `负样本数按非空处理（不因异常入参把作废会话判成有效）`() {
        assertFalse(streamWriteAccepted(-1, sessionValid = false))
    }

    // ==================== 调用点守卫（guardrail R3 放行条件 C-1） ====================

    /**
     * **调用点**守卫：生产 `StreamSession.write` 必须把**真实的会话有效性表达式**交给
     * [streamWriteAccepted]，不得传常量。
     *
     * 存在的理由：guardrail R3 实测证明——把调用点第二参硬写成 `true`（等价于删掉
     * `!closed && ownerEpoch == epoch && !isCancelled()`）后，**`io.prism.voice.local.*` 103 个用例
     * 全绿**（纯函数级用例钉不住调用点接线）。本用例对**源码文本**做结构断言，正是为补这个缺口。
     *
     * **证据级别限定**：断言的是源码结构（不是运行时行为）；运行时行为由
     * `StreamSession.write` 的 epoch/closed/cancelled 判定 + 上层的取消用例共同把守。
     */
    @Test
    fun `调用点必须把真实会话有效性交给纯函数，不得传常量`() {
        val source = engineSource()

        assertTrue(
            "必须把 `!closed && ownerEpoch == epoch && !isCancelled()` 作为第二参传入",
            Regex(
                "streamWriteAccepted\\(\\s*samples\\.size\\s*,\\s*" +
                    "!closed\\s*&&\\s*ownerEpoch\\s*==\\s*epoch\\s*&&\\s*!isCancelled\\(\\)\\s*\\)"
            ).containsMatchIn(source)
        )
        assertFalse(
            "禁止把第二参写成常量（true/false）——那会绕过会话失效判定（guardrail R3 变异 B 的形态）",
            Regex("streamWriteAccepted\\([^)]*,\\s*(true|false)\\s*\\)").containsMatchIn(source)
        )
    }

    private fun engineSource(): String {
        val relativePath = "app/src/main/java/io/prism/voice/local/LocalCloneTtsEngine.kt"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("未能从 ${System.getProperty("user.dir")} 向上定位 $relativePath")
    }
}
