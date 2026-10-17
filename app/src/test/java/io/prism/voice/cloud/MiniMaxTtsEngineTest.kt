package io.prism.voice.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次23-A（ADR-048）：MiniMaxTtsEngine 协议纯函数 JVM 单测。
 *
 * 覆盖：
 * - [buildTaskStartEvent]/[buildTaskContinueEvent]/[buildTaskFinishEvent]：官方 T2A v2 WS
 *   协议字段形状（含**安全断言**——请求体绝不泄露 apiKey）；
 * - [decodeHexAudio]：hex 音频分片解码（正常/大小写混合/奇数长度/非法字符容错）；
 * - [parseServerEvent]：服务端事件解析（task_started/task_finished/data 分片/task_failed/
 *   base_resp 错误码优先/信息性事件/非法帧容错）。
 *
 * 引擎的 WS 流式播报/AudioTrack 行为依赖真机（同 VoiceSubsystemsTest 的边界约定）。
 */
class MiniMaxTtsEngineTest {

    // ==================== buildTaskStartEvent ====================

    @Test
    fun `buildTaskStartEvent - 默认配置字段符合官方 T2A v2 WS 协议`() {
        val json = buildTaskStartEvent(MiniMaxTtsConfig(apiKey = "k-secret", groupId = "g-123"))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("task_start", obj["event"]!!.jsonPrimitive.content)
        assertEquals("speech-2.8-hd", obj["model"]!!.jsonPrimitive.content)
        val voice = obj["voice_setting"]!!.jsonObject
        assertEquals("male-qn-qingse", voice["voice_id"]!!.jsonPrimitive.content)
        assertEquals(1.0, voice["speed"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
        assertEquals(1.0, voice["vol"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
        assertEquals(0, voice["pitch"]!!.jsonPrimitive.intOrNull)
        val audio = obj["audio_setting"]!!.jsonObject
        assertEquals(24000, audio["sample_rate"]!!.jsonPrimitive.intOrNull)
        assertEquals("pcm", audio["format"]!!.jsonPrimitive.content)
        assertEquals(1, audio["channel"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun `buildTaskStartEvent - 请求体不泄露 apiKey 与 groupId 之外无冗余字段`() {
        val json = buildTaskStartEvent(MiniMaxTtsConfig(apiKey = "k-secret", groupId = "g-123"))
        // 安全：鉴权走 HTTP header（Authorization: Bearer），请求体不得出现 Key 明文
        assertFalse("请求体泄露 apiKey", json.contains("k-secret"))
        // 现行官方协议无 task_id（旧版字段已弃用）
        assertFalse("请求体含已弃用 task_id", json.contains("task_id"))
    }

    @Test
    fun `buildTaskStartEvent - 自定义音色语速模型透传`() {
        val config = MiniMaxTtsConfig(
            apiKey = "k", groupId = "g",
            voiceId = "voice-custom", speed = 1.3f, model = "speech-2.6-turbo"
        )
        val obj = Json.parseToJsonElement(buildTaskStartEvent(config)).jsonObject
        assertEquals("speech-2.6-turbo", obj["model"]!!.jsonPrimitive.content)
        val voice = obj["voice_setting"]!!.jsonObject
        assertEquals("voice-custom", voice["voice_id"]!!.jsonPrimitive.content)
        assertEquals(1.3, voice["speed"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
    }

    // ==================== buildTaskContinueEvent / buildTaskFinishEvent ====================

    @Test
    fun `buildTaskContinueEvent - 文本为顶层字段（现行官方协议无 input 包装）`() {
        val obj = Json.parseToJsonElement(buildTaskContinueEvent("你好，Prism")).jsonObject
        assertEquals("task_continue", obj["event"]!!.jsonPrimitive.content)
        assertEquals("你好，Prism", obj["text"]!!.jsonPrimitive.content)
        assertNull("task_continue 不得含已弃用 input 包装", obj["input"])
        assertNull(obj["task_id"])
    }

    @Test
    fun `buildTaskFinishEvent - 仅含 event 字段`() {
        val obj = Json.parseToJsonElement(buildTaskFinishEvent()).jsonObject
        assertEquals("task_finish", obj["event"]!!.jsonPrimitive.content)
        assertEquals(1, obj.size)
    }

    // ==================== decodeHexAudio ====================

    @Test
    fun `decodeHexAudio - 正常解码往返`() {
        val bytes = decodeHexAudio("00ff807f")
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte(), 0x80.toByte(), 0x7f), bytes)
    }

    @Test
    fun `decodeHexAudio - 大小写混合等价`() {
        assertArrayEquals(decodeHexAudio("deadbeef"), decodeHexAudio("DEADBEef"))
        assertArrayEquals(decodeHexAudio("A5"), decodeHexAudio("a5"))
    }

    @Test
    fun `decodeHexAudio - 空串返回空数组（协议允许的无音频分片）`() {
        val bytes = decodeHexAudio("")
        assertNotNull(bytes)
        assertEquals(0, bytes!!.size)
    }

    @Test
    fun `decodeHexAudio - 奇数长度容错返回 null`() {
        assertNull(decodeHexAudio("abc"))
        assertNull(decodeHexAudio("0"))
    }

    @Test
    fun `decodeHexAudio - 非法字符容错返回 null`() {
        assertNull(decodeHexAudio("0g"))
        assertNull(decodeHexAudio("xyz1"))
        assertNull(decodeHexAudio("0 0"))
    }

    // ==================== parseServerEvent ====================

    @Test
    fun `parseServerEvent - task_started 且 base_resp 零错误码`() {
        val frame = """{"event":"task_started","base_resp":{"status_code":0,"status_msg":"success"}}"""
        assertEquals(MiniMaxEvent.TaskStarted, parseServerEvent(frame))
    }

    @Test
    fun `parseServerEvent - task_finished`() {
        val frame = """{"event":"task_finished","base_resp":{"status_code":0}}"""
        assertEquals(MiniMaxEvent.TaskFinished, parseServerEvent(frame))
    }

    @Test
    fun `parseServerEvent - data 事件音频分片解码为 PCM 字节`() {
        val frame = """{"event":"data","data":{"audio":"00ff"},"is_final":false}"""
        val ev = parseServerEvent(frame)
        assertTrue(ev is MiniMaxEvent.AudioChunk)
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte()), (ev as MiniMaxEvent.AudioChunk).bytes)
    }

    @Test
    fun `parseServerEvent - data 事件缺 audio 分片映射失败`() {
        val ev = parseServerEvent("""{"event":"data","data":{}}""")
        assertTrue(ev is MiniMaxEvent.TaskFailed)
    }

    @Test
    fun `parseServerEvent - data 事件非法 hex 容错映射失败`() {
        val ev = parseServerEvent("""{"event":"data","data":{"audio":"0zz"}}""")
        assertTrue(ev is MiniMaxEvent.TaskFailed)
    }

    @Test
    fun `parseServerEvent - task_failed 携带错误码与原因`() {
        val frame =
            """{"event":"task_failed","base_resp":{"status_code":1004,"status_msg":"invalid api key"}}"""
        val ev = parseServerEvent(frame)
        assertTrue(ev is MiniMaxEvent.TaskFailed)
        val msg = (ev as MiniMaxEvent.TaskFailed).message
        assertTrue("错误信息应含错误码：$msg", msg.contains("1004"))
        assertTrue("错误信息应含原因：$msg", msg.contains("invalid api key"))
    }

    @Test
    fun `parseServerEvent - base_resp 非零错误码优先于 event 类型`() {
        // 官方协议：任意事件都可能携带失败 base_resp（如 task_started 即失败）
        val frame =
            """{"event":"task_started","base_resp":{"status_code":1004,"status_msg":"bad key"}}"""
        assertTrue(parseServerEvent(frame) is MiniMaxEvent.TaskFailed)
    }

    @Test
    fun `parseServerEvent - 信息性事件映射 Unknown（不误报失败）`() {
        // assistant_response（文本回显）正常到达，映射 Unknown 而非 TaskFailed——
        // 否则每句播报都会被误判失败
        assertEquals(MiniMaxEvent.Unknown, parseServerEvent("""{"event":"assistant_response","text":"你好"}"""))
        assertEquals(
            MiniMaxEvent.Unknown,
            parseServerEvent("""{"event":"connected_success","base_resp":{"status_code":0}}""")
        )
        assertEquals(MiniMaxEvent.Unknown, parseServerEvent("""{"event":"task_continued"}"""))
        assertEquals(MiniMaxEvent.Unknown, parseServerEvent("""{"event":"future_new_event"}"""))
    }

    @Test
    fun `parseServerEvent - task_continued 携带音频解码为 AudioChunk（CN 站音频帧）`() {
        // BUG-20260909-001 服务端根因：CN 站音频帧 event=task_continued（官方 task_continue
        // 返回体），data.audio 为 hex PCM——原实现映射 Unknown 静默丢弃导致零音频静音
        val hex = "0000" // 2 字节静音 PCM（16bit）
        val event = parseServerEvent("""{"event":"task_continued","data":{"audio":"$hex"}}""")
        assertTrue(event is MiniMaxEvent.AudioChunk)
        assertEquals(2, (event as MiniMaxEvent.AudioChunk).bytes.size)
    }

    @Test
    fun `parseServerEvent - task_continued data 为 null 或缺 audio 视为 ACK 帧`() {
        // 官方文档：task_continue 返回体的 data 可能为 null——纯 ACK 帧无音频按信息性处理
        assertEquals(MiniMaxEvent.Unknown, parseServerEvent("""{"event":"task_continued","data":null}"""))
        assertEquals(MiniMaxEvent.Unknown, parseServerEvent("""{"event":"task_continued","data":{}}"""))
    }

    @Test
    fun `parseServerEvent - task_continued 空串 audio 视为纯 ACK 帧`() {
        // AC1 边界钉死：task_continued 的 data.audio=""（空字符串载荷）与 data:null/缺字段
        // 同判——纯 ACK 帧无音频按信息性处理（takeIf isNotEmpty 收敛 Unknown）
        assertEquals(
            MiniMaxEvent.Unknown,
            parseServerEvent("""{"event":"task_continued","data":{"audio":""}}""")
        )
    }

    @Test
    fun `parseServerEvent - task_continued 非法 hex 映射失败`() {
        // 携带音频字段但内容非法 = 服务端契约破坏，按失败处理（与 data 事件口径一致）
        assertTrue(
            parseServerEvent("""{"event":"task_continued","data":{"audio":"zzzz"}}""") is MiniMaxEvent.TaskFailed
        )
    }

    @Test
    fun `parseServerEvent - data 分支空串 audio 保持旧行为 AudioChunk 空数组`() {
        // M-1 Golden Master（guardrail TKN-VOICE-BUGFIX3-002）：data 事件 data.audio=""
        // 旧行为 = AudioChunk(空数组)（与 decodeHexAudio"空串=协议允许的无音频分片"契约
        // 一致；引擎 onServerFrame 对空 chunk 跳过写入，等效旧行为"静默跳过"）
        val ev = parseServerEvent("""{"event":"data","data":{"audio":""}}""")
        assertTrue(ev is MiniMaxEvent.AudioChunk)
        assertEquals(0, (ev as MiniMaxEvent.AudioChunk).bytes.size)
    }

    @Test
    fun `parseServerEvent - 非法帧容错映射失败`() {
        assertTrue(parseServerEvent("not-json{") is MiniMaxEvent.TaskFailed)
        assertTrue(parseServerEvent("[1,2,3]") is MiniMaxEvent.TaskFailed)
    }
}
