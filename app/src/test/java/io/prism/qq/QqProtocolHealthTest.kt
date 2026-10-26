package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QqProtocolHealth]：WS 连接态 ≠ QQ 在线态（2026-10-26；PRD US-3125）。 */
class QqProtocolHealthTest {

    private class FakeApi(
        private val responses: Map<String, String>
    ) : io.prism.qq.onebot.OneBotApi {
        override val state = kotlinx.coroutines.flow.MutableStateFlow<
            io.prism.qq.onebot.OneBotClient.State>(io.prism.qq.onebot.OneBotClient.State.Disconnected)
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<JsonObject>()
        override val connectedSelfId: Long = 0L
        override suspend fun call(
            action: String,
            paramsJson: String
        ): io.prism.qq.onebot.OneBotModels.Response {
            val body = responses[action] ?: return io.prism.qq.onebot.OneBotModels.Response(
                false, -1, null, "not found"
            )
            return io.prism.qq.onebot.OneBotModels.parseResponse(body)
        }
    }

    @Test
    fun `online true 判为在线`() = runBlocking {
        val api = FakeApi(
            mapOf(
                "get_status" to """{"status":"ok","retcode":0,"data":{"online":true,"good":true}}""",
                "get_login_info" to """{"status":"ok","retcode":0,"data":{"user_id":3904755943,"nickname":"Cyrene"}}""",
                "get_version_info" to """{"status":"ok","retcode":0,"data":{"app_version":"4.18.41"}}"""
            )
        )
        val status = QqProtocolHealth(api) { 1L }.probe()
        assertEquals(QqProtocolHealth.State.ONLINE, status.state)
        assertEquals(3904755943L, status.selfId)
        assertEquals("Cyrene", status.nickname)
        assertEquals("4.18.41", status.appVersion)
    }

    @Test
    fun `online false 判为 QQ 离线`() = runBlocking {
        val api = FakeApi(
            mapOf("get_status" to """{"status":"ok","retcode":0,"data":{"online":false,"good":true}}""")
        )
        val status = QqProtocolHealth(api).probe()
        assertEquals(QqProtocolHealth.State.QQ_OFFLINE, status.state)
        assertTrue(status.detail.contains("重新登录"))
    }

    @Test
    fun `协议端无响应判为不可达`() = runBlocking {
        val api = FakeApi(emptyMap())
        assertEquals(QqProtocolHealth.State.UNREACHABLE, QqProtocolHealth(api).probe().state)
    }

    @Test
    fun `登录信息缺失时仍判在线`() = runBlocking {
        val api = FakeApi(
            mapOf("get_status" to """{"status":"ok","retcode":0,"data":{"online":true}}""")
        )
        val status = QqProtocolHealth(api).probe()
        assertEquals(QqProtocolHealth.State.ONLINE, status.state)
        assertEquals(0L, status.selfId)
    }
}

/** [io.prism.qq.onebot.OneBotModels.parseMediaItems]：真机事件里的媒体元数据。 */
class OneBotMediaParseTest {

    private val selfId = 900L

    @Test
    fun `解析真机图片事件（普通图片）`() {
        val event = Json.parseToJsonElement(
            """
            {"post_type":"message","message_type":"private","self_id":3904755943,"user_id":2802250097,
             "message_id":68,"time":1793020369,
             "message":[{"type":"image","data":{"summary":"","file":"F65A.jpg","sub_type":0,
               "url":"https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=x",
               "file_size":"153444"}}]}
            """.trimIndent()
        ).jsonObject
        val parsed = io.prism.qq.onebot.OneBotModels.parsePrivateMessage(event, 3904755943L, 2802250097L)
        assertEquals(1, parsed!!.media.size)
        val media = parsed.media.single()
        assertEquals(QqMediaKind.IMAGE, media.kind)
        assertEquals("F65A.jpg", media.fileName)
        assertEquals(153444L, media.fileSize)
        assertTrue(media.url.orEmpty().startsWith("https://multimedia.nt.qq.com.cn/"))
        assertEquals("[图片]", parsed.text)
    }

    @Test
    fun `sub_type 1 判为表情包`() {
        val event = Json.parseToJsonElement(
            """
            {"post_type":"message","message_type":"private","self_id":3904755943,"user_id":2802250097,
             "message_id":66,"message":[{"type":"image","data":{"summary":"[动画表情]",
               "file":"FBA4.jpg","sub_type":1,"url":"https://multimedia.nt.qq.com.cn/download?x=1"}}]}
            """.trimIndent()
        ).jsonObject
        val parsed = io.prism.qq.onebot.OneBotModels.parsePrivateMessage(event, 3904755943L, 2802250097L)
        assertEquals(QqMediaKind.STICKER, parsed!!.media.single().kind)
        assertEquals("[表情包]", parsed.text)
    }

    @Test
    fun `纯表情事件解析为 FACE 且无下载地址`() {
        val event = Json.parseToJsonElement(
            """
            {"post_type":"message","message_type":"private","self_id":900,"user_id":1,"message_id":1,
             "message":[{"type":"face","data":{"id":"14"}}]}
            """.trimIndent()
        ).jsonObject
        val parsed = io.prism.qq.onebot.OneBotModels.parsePrivateMessage(event, selfId, 1L)
        val media = parsed!!.media.single()
        assertEquals(QqMediaKind.FACE, media.kind)
        assertNull(media.url)
        assertEquals("[表情]", parsed.text)
    }

    @Test
    fun `纯文本事件没有媒体`() {
        val event = Json.parseToJsonElement(
            """
            {"post_type":"message","message_type":"private","self_id":900,"user_id":1,"message_id":1,
             "message":[{"type":"text","data":{"text":"在吗"}}]}
            """.trimIndent()
        ).jsonObject
        val parsed = io.prism.qq.onebot.OneBotModels.parsePrivateMessage(event, selfId, 1L)
        assertTrue(parsed!!.media.isEmpty())
    }

    @Test
    fun `历史消息同样带媒体引用`() {
        val data = Json.parseToJsonElement(
            """
            {"messages":[{"message_id":1,"sender":{"user_id":900},"time":10,
              "message":[{"type":"image","data":{"file":"a.jpg","sub_type":0,
                "url":"https://multimedia.nt.qq.com.cn/download?a=1","file_size":"1000"}}]}]}
            """.trimIndent()
        )
        val list = io.prism.qq.onebot.OneBotModels.parseHistory(data, selfId = 900)
        assertEquals(1, list.size)
        assertEquals(QqMediaKind.IMAGE, list.single().media.single().kind)
    }
}
