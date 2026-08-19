package io.prism.phonecontrol

import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [PhoneControlAskUserNotifier] 通知链单元测试（v1 批次5 Issue 5，ac-verifier P5，ADR-038）。
 *
 * **application 指定**：`application = android.app.Application::class` 避免 Robolectric 按
 * AndroidManifest 加载 [io.prism.PrismApplication]（ObjectBox native 在 Windows JVM 不可用）。
 *
 * 覆盖：
 * - `request` 发出一条高优先级通知（NotificationManager 可见）
 * - `resolve(allow/deny)` 把答案推送到 `answers` 流（VM 消费回灌工具回路的接缝）
 * - 发新通知先撤旧通知（guardrail LOW-1：防陈旧通知按钮误批新高危确认）
 * - `resolve` 撤销对应通知
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneControlAskUserNotifierTest {

    private fun notifier(): PhoneControlAskUserNotifier = PhoneControlAskUserNotifier(
        RuntimeEnvironment.getApplication()
    )

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifCount(ctx: Context): Int = nm(ctx).activeNotifications.size

    @Test
    fun `request posts a notification and resolve pushes answer to flow`() = runBlocking {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val nb = notifier()
        val askId = nb.request("检测到敏感操作：发送「测试」，是否允许继续？")
        // 通知已发出
        assertEquals("应恰好有一条通知", 1, notifCount(ctx))
        val shadow = Shadows.shadowOf(nm(ctx))
        assertNotNull(
            "通知 id 应映射到 NOTIFICATION_ID_BASE+askId",
            shadow.getNotification(PhoneControlAskUserNotifier.NOTIFICATION_ID_BASE + askId.toInt())
        )
        // 作答推送到 answers 流（ConversationViewModel 消费此流回灌工具回路）
        val deferred = CompletableDeferred<PhoneControlAskUserNotifier.Answer>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { nb.answers.collect { deferred.complete(it) } }
        nb.resolve(askId, PhoneControlAskUserNotifier.ACTION_ALLOW)
        val answer = deferred.await()
        assertEquals(askId, answer.askId)
        assertEquals(PhoneControlAskUserNotifier.ACTION_ALLOW, answer.action)
        job.cancel()
    }

    @Test
    fun `resolve cancels the notified notification`() = runBlocking {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val nb = notifier()
        val askId = nb.request("需要人工接管")
        nb.resolve(askId, PhoneControlAskUserNotifier.ACTION_DENY)
        assertEquals("作答后通知应被撤销", 0, notifCount(ctx))
    }

    @Test
    fun `new request cancels previous stale notification`() = runBlocking {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val nb = notifier()
        val first = nb.request("第一次确认")
        val second = nb.request("第二次确认（理想应覆盖第一次）")
        val shadow = Shadows.shadowOf(nm(ctx))
        assertNull(
            "旧确认通知应被撤掉，防陈旧按钮误批新高危确认",
            shadow.getNotification(PhoneControlAskUserNotifier.NOTIFICATION_ID_BASE + first.toInt())
        )
        assertNotNull(
            "新确认通知应存在",
            shadow.getNotification(PhoneControlAskUserNotifier.NOTIFICATION_ID_BASE + second.toInt())
        )
    }
}