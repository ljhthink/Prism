package io.prism.hotlist

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.prism.skill.SkillExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 批次9（US-904）验收补充：热榜失败文案被 [SkillExecutor.isFailureResult] 识别。
 *
 * 根因（考古 H2）：未配置 Key 引导文案不是失败标记 → LLM 认为"未配置"是临时状态而反复
 * 调用同一工具直至熔断。修复后所有失败/引导文案前置 `错误：` 前缀，纳入失败识别。
 * 本测试断言热榜各类失败结果均被 [SkillExecutor.isFailureResult] 识别（熔断生效）。
 */
class V1Batch9HotlistFailureMarkTest {

    private fun executor(key: String?) = HotListLocalToolExecutor(
        HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        apiKeyProvider = { key }
    )

    @Test
    fun `unconfigured key guidance is prefixed with error mark and recognized as failure`() = runBlocking {
        val result = executor(key = null).execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("引导文案应以前缀「错误：」开头", result.startsWith("错误："))
        assertTrue("未配 Key 引导应纳入 isFailureResult（避免 LLM 反复重试）", SkillExecutor.isFailureResult(result))
    }

    @Test
    fun `http 401 unauthorized message is recognized as failure`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        val e = HotListLocalToolExecutor(HttpClient(engine), apiKeyProvider = { "test-key" })
        val result = e.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("401 文案应包含失败标记语义", result.contains("失败"))
        assertTrue("401 文案应纳入 isFailureResult", SkillExecutor.isFailureResult(result))
    }

    @Test
    fun `http 429 rate limit message is recognized as failure`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.TooManyRequests) }
        val e = HotListLocalToolExecutor(HttpClient(engine), apiKeyProvider = { "test-key" })
        val result = e.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("429 文案应提示限流", result.contains("限流"))
        assertTrue("429 文案应纳入 isFailureResult", SkillExecutor.isFailureResult(result))
    }

    @Test
    fun `network failure message is recognized as failure`() = runBlocking {
        val engine = MockEngine { throw java.io.IOException("connection reset") }
        val e = HotListLocalToolExecutor(HttpClient(engine), apiKeyProvider = { "test-key" })
        val result = e.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("网络失败文案应包含失败语义", result.contains("失败"))
        assertTrue("网络失败文案应纳入 isFailureResult", SkillExecutor.isFailureResult(result))
    }

    @Test
    fun `normal hotlist result is not recognized as failure`() = runBlocking {
        // 成功返回的热榜结果不应被误判为失败（防误熔断）
        val nodes = """{"error":false,"status":200,"data":[
            {"hashid":"KqndgxeLl9","name":"微博","display":"热搜榜","domain":"weibo.com"}]}"""
        val board = """{"error":false,"status":200,"data":{
            "hashid":"KqndgxeLl9","name":"微博","display":"热搜榜",
            "items":[{"extra":"100 万","url":"https://s.weibo.com/q?a","title":"热点"}]}}"""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/nodes")) respond(nodes, HttpStatusCode.OK)
            else respond(board, HttpStatusCode.OK)
        }
        val e = HotListLocalToolExecutor(HttpClient(engine), apiKeyProvider = { "test-key" })
        val result = e.execute("hotlist__get", mapOf("platform" to "微博"))
        assertTrue("成功结果应含热榜内容", result.contains("热点"))
        assertFalse("成功结果不应被误判失败", SkillExecutor.isFailureResult(result))
    }
}
