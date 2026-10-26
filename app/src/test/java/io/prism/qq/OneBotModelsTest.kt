package io.prism.qq

import io.prism.qq.onebot.OneBotModels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OneBotModels] 纯函数单元测试（ADR-064；US-3110 / US-3111 / US-3112）。
 *
 * 覆盖：响应解析、请求构建、私聊事件过滤（self_id/目标/媒体）、历史解析、好友解析、私网判定。
 */
class OneBotModelsTest {

    @Test
    fun `解析成功响应`() {
        val response = OneBotModels.parseResponse("""{"status":"ok","retcode":0,"data":{"message_id":7}}""")
        assertTrue(response.ok)
        assertEquals(0, response.retcode)
    }

    @Test
    fun `解析失败响应与非法 JSON`() {
        val failed = OneBotModels.parseResponse("""{"status":"failed","retcode":1404,"message":"token 无效"}""")
        assertFalse(failed.ok)
        assertEquals(1404, failed.retcode)
        assertEquals("token 无效", failed.message)

        val invalid = OneBotModels.parseResponse("not json")
        assertFalse(invalid.ok)
        assertEquals(-1, invalid.retcode)
    }

    @Test
    fun `请求体包含必需字段`() {
        val send = OneBotModels.buildSendPrivateMsg(10001, "晚上好")
        assertTrue(send.contains("\"user_id\":10001"))
        assertTrue(send.contains("晚上好"))

        val history = OneBotModels.buildGetFriendMsgHistory(10001, 12)
        assertTrue(history.contains("\"count\":12"))

        val nick = OneBotModels.buildSetLongNick("今天也在认真生活")
        assertTrue(nick.contains("longNick"))
    }

    @Test
    fun `语音请求体使用 record 消息段与 base64 负载（US-3113）`() {
        val body = OneBotModels.buildSendPrivateVoiceMsg(10001, "base64://QUJD")
        assertTrue(body.contains("\"user_id\":10001"))
        assertTrue(body.contains("\"type\":\"record\""))
        assertTrue(body.contains("\"file\":\"base64://QUJD\""))
    }

    @Test
    fun `私聊事件匹配 self_id 与目标`() {
        val event = Json.parseToJsonElement(
            """{"post_type":"message","message_type":"private","self_id":900,
               "user_id":10001,"message_id":42,"time":1792400000,
               "message":[{"type":"text","data":{"text":"在吗"}}]}"""
        ).jsonObject
        val parsed = OneBotModels.parsePrivateMessage(event, expectedSelfId = 900, targetUserId = 10001)
        assertEquals("在吗", parsed?.text)
        assertEquals("42", parsed?.messageId)
        assertTrue(parsed!!.incoming)
        assertEquals(1792400000_000L, parsed.timestampMs)
    }

    @Test
    fun `私聊事件在 self_id 不匹配时丢弃`() {
        val event = Json.parseToJsonElement(
            """{"post_type":"message","message_type":"private","self_id":900,
               "user_id":10001,"message_id":1,"raw_message":"hi"}"""
        ).jsonObject
        assertNull(OneBotModels.parsePrivateMessage(event, expectedSelfId = 901, targetUserId = 10001))
    }

    @Test
    fun `私聊事件在非目标发送者时丢弃`() {
        val event = Json.parseToJsonElement(
            """{"post_type":"message","message_type":"private","self_id":900,
               "user_id":20002,"message_id":1,"raw_message":"hi"}"""
        ).jsonObject
        assertNull(OneBotModels.parsePrivateMessage(event, expectedSelfId = 900, targetUserId = 10001))
    }

    @Test
    fun `非私聊或非消息事件丢弃`() {
        val group = Json.parseToJsonElement(
            """{"post_type":"message","message_type":"group","self_id":900,"user_id":1,"raw_message":"hi"}"""
        ).jsonObject
        assertNull(OneBotModels.parsePrivateMessage(group, 900, 1))

        val notice = Json.parseToJsonElement("""{"post_type":"notice","self_id":900}""").jsonObject
        assertNull(OneBotModels.parsePrivateMessage(notice, 900, 1))
    }

    @Test
    fun `媒体消息（无文本）不进入回复链路，且不回退 raw_message`() {
        // 真实 OneBot 事件必带 raw_message（含 CQ 码）；不得把它当正文回复（guardrail M-01）
        val event = Json.parseToJsonElement(
            """{"post_type":"message","message_type":"private","self_id":900,"user_id":10001,
               "message_id":1,"raw_message":"[CQ:image,file=a.jpg]",
               "message":[{"type":"image","data":{"file":"a.jpg"}}]}"""
        ).jsonObject
        assertNull(OneBotModels.parsePrivateMessage(event, 900, 10001))
    }

    @Test
    fun `纯字符串消息含 CQ 码时视为媒体`() {
        assertEquals(null, OneBotModels.extractText(Json.parseToJsonElement("\"[CQ:face,id=1]\"")))
        assertEquals("你好", OneBotModels.extractText(Json.parseToJsonElement("\"你好\"")))
    }

    @Test
    fun `self_id 未配置 0 时拒绝事件（fail-closed）`() {
        val event = Json.parseToJsonElement(
            """{"post_type":"message","message_type":"private","self_id":900,
               "user_id":10001,"message_id":1,"raw_message":"hi"}"""
        ).jsonObject
        assertNull(OneBotModels.parsePrivateMessage(event, expectedSelfId = 0, targetUserId = 10001))
    }

    @Test
    fun `message_id 规范化为安全字符并截断 64`() {
        assertEquals("abc-123_X", OneBotModels.normalizeMessageId(" abc-123_X "))
        assertEquals("a".repeat(64), OneBotModels.normalizeMessageId("a".repeat(200)))
        assertNull(OneBotModels.normalizeMessageId("   "))
        assertNull(OneBotModels.normalizeMessageId("!!!$$$"))
    }

    @Test
    fun `历史解析兼容 messages 数组与直接数组`() {
        val nested = Json.parseToJsonElement(
            """{"messages":[
                {"message_id":1,"sender":{"user_id":10001},"time":10,"message":[{"type":"text","data":{"text":"你好"}}]},
                {"message_id":2,"sender":{"user_id":900},"time":11,"raw_message":"在的"}
            ]}"""
        )
        val list = OneBotModels.parseHistory(nested, selfId = 900)
        assertEquals(2, list.size)
        assertTrue(list[0].incoming)
        assertFalse(list[1].incoming)

        val direct = Json.parseToJsonElement(
            """[{"message_id":3,"user_id":900,"time":12,"raw_message":"嗯"}]"""
        )
        assertEquals(1, OneBotModels.parseHistory(direct, selfId = 900).size)
    }

    @Test
    fun `历史解析跳过纯媒体消息`() {
        val data = Json.parseToJsonElement(
            """{"messages":[{"message_id":1,"user_id":900,"time":10,"message":[{"type":"face","data":{"id":"1"}}]}]}"""
        )
        assertTrue(OneBotModels.parseHistory(data, selfId = 900).isEmpty())
    }

    @Test
    fun `好友列表按备注或昵称解析`() {
        val data = Json.parseToJsonElement(
            """[{"user_id":10001,"remark":"小号主人","nickname":"阿主"},
                {"user_id":10002,"remark":"","nickname":"别人"}]"""
        )
        assertEquals(10001L, OneBotModels.resolveUserIdByContact(data, "小号主人"))
        assertEquals(10001L, OneBotModels.resolveUserIdByContact(data, "阿主"))
        assertNull(OneBotModels.resolveUserIdByContact(data, "不存在"))
    }

    @Test
    fun `私网端点判定`() {
        assertTrue(OneBotModels.isPrivateEndpoint("http://127.0.0.1:3000"))
        assertTrue(OneBotModels.isPrivateEndpoint("ws://localhost:3001"))
        assertTrue(OneBotModels.isPrivateEndpoint("http://192.168.1.10:3000"))
        assertTrue(OneBotModels.isPrivateEndpoint("http://10.0.0.5:3000"))
        assertTrue(OneBotModels.isPrivateEndpoint("http://172.16.0.1:3000"))
        assertTrue(OneBotModels.isPrivateEndpoint("http://172.31.255.1:3000"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://172.32.0.1:3000"))
        assertFalse(OneBotModels.isPrivateEndpoint("https://api.example.com"))
        assertFalse(OneBotModels.isPrivateEndpoint("not a url"))
    }

    @Test
    fun `私网判定拒绝域名前缀伪造（guardrail H-01）`() {
        assertFalse(OneBotModels.isPrivateEndpoint("http://10.0.0.5.evil.example"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://192.168.1.10.attacker.tld"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://172.16.0.1.attacker.tld"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://127.0.0.1.evil.com"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://localhost."))
        assertFalse(OneBotModels.isPrivateEndpoint("http://127.0.0.1@evil.com"))
        assertFalse(OneBotModels.isPrivateEndpoint("ws://[::ffff:127.0.0.1]"))
        assertFalse(OneBotModels.isPrivateEndpoint("ftp://127.0.0.1"))
        // 边界：合法私网与越界
        assertTrue(OneBotModels.isPrivateEndpoint("ws://172.31.255.255:3001"))
        assertFalse(OneBotModels.isPrivateEndpoint("ws://172.32.0.0:3001"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://256.1.1.1"))
        assertFalse(OneBotModels.isPrivateEndpoint("http://1.2.3.4"))
    }
}
