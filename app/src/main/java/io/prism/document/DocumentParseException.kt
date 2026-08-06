package io.prism.document

/**
 * 文档解析异常 —— 当文档无法解析为纯文本时抛出。
 *
 * 携带来源文件名与底层原因，供摄入管线记录错误并降级（US-018 摄入失败提示）。
 * 不携带原始栈路径或敏感信息（BR-security-001）。
 *
 * @param fileName 触发解析的文档名（用于错误溯源与日志）
 * @param cause 底层异常（如 PDFBox/POI 解析失败）
 */
class DocumentParseException(
    val fileName: String,
    cause: Throwable? = null
) : RuntimeException("文档解析失败: $fileName", cause)