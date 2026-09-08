package io.prism.voice.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次23-A（ADR-048）：[AliCloudAsrEngine] 纯函数 JVM 单测（无 Android 依赖）。
 *
 * 覆盖：
 * - run-task / finish-task 指令 JSON 断言（协议字段完整性——format=pcm/sample_rate=16000/
 *   model=paraformer-realtime-v2 为本引擎硬约束）；
 * - result-generated 解析（句尾/非句尾/心跳包跳过/畸形输入容错）——字段名以官方协议文档为准
 *   （help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-api-reference，
 *   2026-09-02 版）：text/sentence_end/heartbeat 均位于 payload.output.sentence 内；
 * - task-failed 解析（header.error_code/error_message 提取）。
 */
class AliCloudAsrEngineTest {

    // 官方文档 result-generated 示例原样（句尾：sentence_end=true + usage.duration）
    private val finalSentenceJson = """
        {"header":{"task_id":"2bf83b9a-baeb-4fda-8d9a-xxxxxxxxxxxx","event":"result-generated","attributes":{}},
         "payload":{"output":{"sentence":{"begin_time":170,"end_time":920,"text":"好，我知道了",
         "heartbeat":false,"sentence_end":true,
         "words":[{"begin_time":170,"end_time":295,"text":"好","punctuation":"，"}]}},"usage":{"duration":3}}}
    """.trimIndent()

    // 非句尾：sentence_end=false + usage=null
    private val partialSentenceJson = """
        {"header":{"task_id":"2bf83b9a-baeb-4fda-8d9a-xxxxxxxxxxxx","event":"result-generated","attributes":{}},
         "payload":{"output":{"sentence":{"sentence_id":2,"begin_time":23560,"end_time":null,"text":"那你们几",
         "channel_id":0,"sentence_end":false,"words":[]}},"usage":null}}
    """.trimIndent()

    // ==================== buildRunTaskCommand ====================

    @Test
    fun `buildRunTaskCommand - 协议字段完整（run-task 流式出向 pcm 16000 paraformer-realtime-v2）`() {
        val json = Json.parseToJsonElement(
            AliCloudAsrEngine.buildRunTaskCommand("task-abc-123")
        ).jsonObject

        val header = json.getValue("header").jsonObject
        assertEquals("run-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("task-abc-123", header.getValue("task_id").jsonPrimitive.content)
        assertEquals("out", header.getValue("streaming").jsonPrimitive.content)

        val payload = json.getValue("payload").jsonObject
        assertEquals("audio", payload.getValue("task_group").jsonPrimitive.content)
        assertEquals("asr", payload.getValue("task").jsonPrimitive.content)
        assertEquals("recognition", payload.getValue("function").jsonPrimitive.content)
        assertEquals("paraformer-realtime-v2", payload.getValue("model").jsonPrimitive.content)

        val params = payload.getValue("parameters").jsonObject
        assertEquals("pcm", params.getValue("format").jsonPrimitive.content)
        assertEquals(16000, params.getValue("sample_rate").jsonPrimitive.int)

        assertTrue(payload.getValue("input_files").jsonArray.isEmpty())
    }

    // ==================== buildFinishTaskCommand ====================

    @Test
    fun `buildFinishTaskCommand - action=finish-task 且 payload-input 为空对象`() {
        val json = Json.parseToJsonElement(
            AliCloudAsrEngine.buildFinishTaskCommand("task-xyz-9")
        ).jsonObject

        val header = json.getValue("header").jsonObject
        assertEquals("finish-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("task-xyz-9", header.getValue("task_id").jsonPrimitive.content)
        assertEquals("out", header.getValue("streaming").jsonPrimitive.content)

        val payload = json.getValue("payload").jsonObject
        assertTrue(payload.getValue("input").jsonObject.isEmpty())
    }

    // ==================== parseResultGenerated ====================

    @Test
    fun `parseResultGenerated - 句尾（sentence_end=true）映射 onFinal 语义`() {
        val s = AliCloudAsrEngine.parseResultGenerated(finalSentenceJson)
        assertEquals("好，我知道了", s?.text)
        assertEquals(true, s?.isSentenceEnd)
    }

    @Test
    fun `parseResultGenerated - 非句尾（sentence_end=false）映射 onPartial 语义`() {
        val s = AliCloudAsrEngine.parseResultGenerated(partialSentenceJson)
        assertEquals("那你们几", s?.text)
        assertEquals(false, s?.isSentenceEnd)
    }

    @Test
    fun `parseResultGenerated - 心跳包（heartbeat=true）跳过返回 null`() {
        val heartbeatJson = """
            {"header":{"task_id":"t","event":"result-generated"},"payload":{"output":{"sentence":{
            "begin_time":0,"end_time":null,"text":"","heartbeat":true,"sentence_end":false,"words":[]}}}}
        """.trimIndent()
        assertNull(AliCloudAsrEngine.parseResultGenerated(heartbeatJson))
    }

    @Test
    fun `parseResultGenerated - 畸形输入容错返回 null 不崩溃`() {
        val malformed = listOf(
            "not-json",
            "",
            "  ",
            """{"header":{}}""",  // 缺 event
            """{"header":{"event":"task-started"},"payload":{}}""",  // 非 result-generated 事件
            """{"header":{"event":"result-generated"},"payload":{}}""",  // 缺 output
            """{"header":{"event":"result-generated"},"payload":{"output":{}}}""",  // 缺 sentence
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_end":true}}}}""",  // 缺 text
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"x"}}}}""",  // 缺 sentence_end
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":123,"sentence_end":true}}}}""",  // text 类型错误
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"x","sentence_end":"yes"}}}}""",  // sentence_end 类型错误
            """{"header":"broken","payload":{"output":{"sentence":{}}}}""",  // header 结构错误
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":"broken"}}}""",  // sentence 非对象
        )
        for (bad in malformed) {
            assertNull("畸形输入应返回 null：$bad", AliCloudAsrEngine.parseResultGenerated(bad))
        }
    }

    @Test
    fun `extractEvent - 提取事件名且畸形输入返回 null`() {
        assertEquals(
            "result-generated",
            AliCloudAsrEngine.extractEvent(finalSentenceJson)
        )
        assertEquals("task-started", AliCloudAsrEngine.extractEvent("""{"header":{"event":"task-started"}}"""))
        assertNull(AliCloudAsrEngine.extractEvent("not-json"))
        assertNull(AliCloudAsrEngine.extractEvent("""{"payload":{}}"""))
    }

    // ==================== parseTaskFailed ====================

    @Test
    fun `parseTaskFailed - 提取 error_code 与 error_message`() {
        val json = """
            {"header":{"task_id":"t","event":"task-failed","error_code":"InvalidApiKey",
            "error_message":"Invalid API-key provided."},"payload":{}}
        """.trimIndent()
        val failed = AliCloudAsrEngine.parseTaskFailed(json)
        assertEquals("InvalidApiKey", failed?.first)
        assertTrue(failed?.second?.contains("API-key") == true)
    }

    @Test
    fun `parseTaskFailed - 非 task-failed 事件与畸形输入返回 null`() {
        assertNull(AliCloudAsrEngine.parseTaskFailed(finalSentenceJson))
        assertNull(AliCloudAsrEngine.parseTaskFailed("not-json"))
        assertNull(AliCloudAsrEngine.parseTaskFailed("""{"header":{"event":"task-failed"}"""))  // 截断 JSON
    }
}
