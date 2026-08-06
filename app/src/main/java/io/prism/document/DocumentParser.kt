package io.prism.document

import java.io.InputStream

/**
 * 文档解析器接口 —— 将各类文档解析为纯文本，供 RAG 摄入管线切片/嵌入。
 *
 * US-012 验收标准 1：DocumentParser 接口 + 按格式分发实现。
 *
 * 实现约定：
 * - 输入为 [InputStream]（Android SAF 经 ContentResolver 打开），不依赖 Android Context，
 *   便于 JVM 单元测试直接构造输入流。
 * - 输出为规范化纯文本（去除图片/超链接/格式标记），不保留二进制或富文本结构。
 * - 解析失败应抛出可识别的 [DocumentParseException]，由调用方决定降级策略。
 *
 * @see <a href="../docs/decisions/ADR-007-m3-rag-tech-stack.md">ADR-007 5.3</a>
 */
fun interface DocumentParser {
    /**
     * 将文档输入流解析为纯文本。
     *
     * @param input 文档二进制/文本输入流，由调用方负责关闭
     * @return 提取的纯文本（可能为空字符串）
     * @throws DocumentParseException 解析失败时抛出
     */
    fun parse(input: InputStream): String
}