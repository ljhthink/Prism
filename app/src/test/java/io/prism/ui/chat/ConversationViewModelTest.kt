package io.prism.ui.chat

import io.prism.ui.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ConversationViewModel 单元测试 —— 验证发送逻辑（US-005 AC-4）。
 *
 * 覆盖：
 * - 发送消息追加用户消息 + 占位 AI 回复
 * - 空白输入被忽略
 * - 连续发送消息 id 递增
 *
 * [ConversationViewModel.sendMessage] 在 [androidx.lifecycle.viewModelScope] 中
 * `delay(1400)` 后追加 AI 回复，故须设置 Main 为测试调度器并在 [runTest] 中推进虚拟时钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage appends user and assistant messages`() = runTest(mainDispatcher) {
        val vm = ConversationViewModel()

        vm.sendMessage("你好")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("你好", messages[0].content)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertTrue("AI 回复不应为空", messages[1].content.isNotEmpty())
    }

    @Test
    fun `sendMessage trims whitespace`() = runTest(mainDispatcher) {
        val vm = ConversationViewModel()

        vm.sendMessage("  你好  ")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals("你好", messages[0].content)
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest(mainDispatcher) {
        val vm = ConversationViewModel()

        vm.sendMessage("   ")
        vm.sendMessage("")
        advanceUntilIdle()

        assertEquals(0, vm.messages.value.size)
    }

    @Test
    fun `consecutive sends assign increasing ids`() = runTest(mainDispatcher) {
        val vm = ConversationViewModel()

        vm.sendMessage("a")
        vm.sendMessage("b")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(4, messages.size)
        // id 递增：0,1,2,3
        assertEquals(listOf(0L, 1L, 2L, 3L), messages.map { it.id })
    }
}