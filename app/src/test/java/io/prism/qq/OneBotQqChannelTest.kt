package io.prism.qq

import io.prism.qq.channel.OneBotQqChannel
import io.prism.qq.onebot.OneBotApi
import io.prism.qq.onebot.OneBotClient
import io.prism.qq.onebot.OneBotModels
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OneBotQqChannel] 解析与误投递防护测试（guardrail B-01 / M-11 / M-14 / M-16）。
 *
 * 用假 [OneBotApi] 注入，纯 JVM，不触网。
 */
class OneBotQqChannelTest {

    /** 好友列表固定为：10001=阿主（备注），10002=小李小李（昵称重名 2 个）。 */
    private class FakeApi(
        private val friendListJson: String = """[
            {"user_id":10001,"remark":"阿主","nickname":"主人"},
            {"user_id":10004,"remark":"小李","nickname":"小李子"},
            {"user_id":10002,"remark":"","nickname":"同名"},
            {"user_id":10003,"remark":"","nickname":"同名"}
        ]""",
        private val sendResponseJson: String =
            """{"status":"ok","retcode":0,"data":{"message_id":1}}"""
    ) : OneBotApi {
        var lastSendUserId: Long = 0
        var lastParamsJson: String = ""
        var sendCount: Int = 0
        var historyJson: String = """{"messages":[]}"""

        override val state: StateFlow<OneBotClient.State> =
            MutableStateFlow<OneBotClient.State>(OneBotClient.State.Connected(900))
        override val events: SharedFlow<JsonObject> = MutableSharedFlow<JsonObject>()
        override val connectedSelfId: Long = 900

        override suspend fun call(action: String, paramsJson: String): OneBotModels.Response {
            return when (action) {
                "get_friend_list" ->
                    OneBotModels.parseResponse("""{"status":"ok","retcode":0,"data":$friendListJson}""")
                "send_private_msg" -> {
                    sendCount++
                    lastParamsJson = paramsJson
                    lastSendUserId = Json.parseToJsonElement(paramsJson).jsonObject["user_id"]!!
                        .jsonPrimitive.content.toLong()
                    OneBotModels.parseResponse(sendResponseJson)
                }
                "get_friend_msg_history" ->
                    OneBotModels.parseResponse("""{"status":"ok","retcode":0,"data":$historyJson}""")
                "set_self_longnick" ->
                    OneBotModels.parseResponse("""{"status":"ok","retcode":0,"data":null}""")
                else -> OneBotModels.parseResponse("""{"status":"failed","retcode":1404,"message":"unknown"}""")
            }
        }
    }

    private fun channel(api: FakeApi, cfg: QqConfig) =
        OneBotQqChannel(api) { cfg }

    @Test
    fun `两个备注名联系人各发各的（缓存不串号，M-18）`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))
        ch.sendText("阿主", "hi")
        assertEquals(10001L, api.lastSendUserId)
        // 第二个联系人同样走缓存/好友列表解析路径（不是纯数字捷径）
        ch.sendText("小李", "hi")
        assertEquals(10004L, api.lastSendUserId)
    }

    @Test
    fun `语音以 record 消息段发送（US-3113）`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))

        val result = ch.sendVoice("阿主", floatArrayOf(0.1f, -0.1f), 16_000)

        assertTrue(result.startsWith("已发送语音"))
        assertEquals(1, api.sendCount)
        assertEquals(10001L, api.lastSendUserId)
        assertTrue("应为 record 消息段", api.lastParamsJson.contains("\"type\":\"record\""))
        assertTrue("负载应为 base64://", api.lastParamsJson.contains("base64://"))
        // WAV 头（RIFF）经 Base64 后应可还原
        val b64 = api.lastParamsJson.substringAfter("base64://").substringBefore("\"")
        val bytes = java.util.Base64.getDecoder().decode(b64)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `语音协议端明确拒绝时返回可降级前缀（retcode-0）`() = runBlocking {
        val api = FakeApi(
            sendResponseJson = """{"status":"failed","retcode":1200,"message":"转码失败"}"""
        )
        val ch = channel(api, QqConfig(targetContact = "阿主"))

        val result = ch.sendVoice("阿主", floatArrayOf(0.1f), 16_000)

        assertTrue(result.startsWith("错误：协议端语音发送失败"))
        assertTrue(result.contains("转码失败"))
    }

    @Test
    fun `语音传输异常时返回未确认（retcode-1，不误导降级）`() = runBlocking {
        val api = FakeApi(
            sendResponseJson = """{"status":"failed","retcode":-1,"message":"连接超时"}"""
        )
        val ch = channel(api, QqConfig(targetContact = "阿主"))

        val result = ch.sendVoice("阿主", floatArrayOf(0.1f), 16_000)

        assertTrue(result.startsWith("⚠️ 语音发送未确认"))
        assertTrue(result.contains("请勿自动重试"))
    }

    @Test
    fun `空音频不发送语音`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))
        val result = ch.sendVoice("阿主", FloatArray(0), 16_000)
        assertTrue(result.startsWith("错误"))
        assertEquals(0, api.sendCount)
    }

    @Test
    fun `未解析到的联系人拒绝发送（不误投递）`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))
        val result = ch.sendText("不存在的人", "hi")
        assertTrue(result.startsWith("错误"))
        assertEquals(0, api.sendCount)
    }

    @Test
    fun `配置的目标 QQ 号只对配置联系人生效（M-11）`() = runBlocking {
        val api = FakeApi()
        val cfg = QqConfig(targetContact = "阿主", oneBotTargetUserId = 10001)
        val ch = channel(api, cfg)

        // 配置联系人 → 使用配置的目标 QQ 号
        ch.sendText("阿主", "hi")
        assertEquals(10001L, api.lastSendUserId)

        // 其它联系人 → 不得复用配置目标；纯数字仅当在好友列表内才允许（R-01/M-07）
        ch.sendText("10003", "hi")
        assertEquals("好友列表内的数字 QQ 号应允许", 10003L, api.lastSendUserId)

        val rejected = ch.sendText("99999", "hi")
        assertTrue("不在好友列表的数字应拒绝", rejected.startsWith("错误"))
        assertEquals("不得向陌生人发第二条", 2, api.sendCount)
    }

    @Test
    fun `数字 contact 不在好友列表时拒绝且零请求（R-01）`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))

        val result = ch.sendText("123456789", "hi")

        assertTrue(result.startsWith("错误"))
        assertEquals("不得向任意数字 QQ 号发送", 0, api.sendCount)
    }

    @Test
    fun `昵称重名时不解析（M-16）`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))
        val result = ch.sendText("同名", "hi")
        assertTrue(result.startsWith("错误"))
        assertEquals(0, api.sendCount)
    }

    @Test
    fun `历史为空时回落到空列表（三级降级不崩溃）`() = runBlocking {
        val api = FakeApi()
        val ch = channel(api, QqConfig(targetContact = "阿主"))
        assertEquals(0, ch.recentMessages("阿主", 12).size)
    }
}
