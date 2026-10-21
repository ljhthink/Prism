package io.prism.qq

import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联系人与目标 QQ 号绑定规则测试（guardrail M-11 / M-18）。
 */
class QqConfigBindingTest {

    @Test
    fun `联系人变化时必须清除绑定的目标 QQ 号`() {
        assertTrue(QqConfigRepository.shouldClearTargetId("阿主", "小李"))
        assertTrue(QqConfigRepository.shouldClearTargetId("阿主", ""))
    }

    @Test
    fun `联系人未变化时保留目标 QQ 号`() {
        assertFalse(QqConfigRepository.shouldClearTargetId("阿主", "阿主"))
    }

    @Test
    fun `联系人变更后目标 QQ 号落盘即被清除（DataStore 往返）`() = runBlocking {
        // guardrail L-30：绑定规则不只测纯函数，还需验证真实 repository 的落盘效果
        val repo = QqConfigRepository(FakePreferenceDataStore())
        repo.setTargetContact("阿主")
        repo.setOneBotConnection(
            baseUrl = "http://192.168.1.10:3000",
            wsUrl = "ws://192.168.1.10:3001",
            token = "",
            selfId = 0L,
            targetUserId = 10001L
        )
        assertEquals(10001L, repo.get().oneBotTargetUserId)

        repo.setTargetContact("小李")

        assertEquals("联系人变更后绑定目标应被清除", 0L, repo.get().oneBotTargetUserId)
        assertEquals("小李", repo.get().targetContact)
    }

    @Test
    fun `联系人不变时目标 QQ 号保留`() = runBlocking {
        val repo = QqConfigRepository(FakePreferenceDataStore())
        repo.setTargetContact("阿主")
        repo.setOneBotConnection("", "", "", 0L, 10001L)

        repo.setTargetContact("阿主")

        assertEquals(10001L, repo.get().oneBotTargetUserId)
    }
}
