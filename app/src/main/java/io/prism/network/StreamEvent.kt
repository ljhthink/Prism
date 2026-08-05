package io.prism.network

/**
 * OpenAI 兼容 chat/completions 流式响应的解码结果（ADR-004 4.2）。
 *
 * 流式 SSE 被解码为一串 [StreamEvent]，由 [Flow] 暴露给调用方：
 * - [Delta]：单个增量 token（`choices[0].delta.content`）
 * - [Done]：收到 `[DONE]` 终止信号
 * - [Error]：网络 / 协议 / 鉴权错误
 *
 * 密封类保证调用方用 `when` 穷尽分支，避免遗漏（Karpathy Guidelines）。
 */
sealed class StreamEvent {
    /** 增量 token 内容（可能为空白，需自行 trim 判断）。 */
    data class Delta(val content: String) : StreamEvent()

    /** 流式结束（`[DONE]` 或正常完成）。 */
    data object Done : StreamEvent()

    /** 错误（端点不可达 / 超时 / 401 / 流中断 / 解析失败）。 */
    data class Error(val message: String) : StreamEvent()
}