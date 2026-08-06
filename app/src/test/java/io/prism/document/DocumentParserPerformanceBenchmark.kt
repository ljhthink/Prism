package io.prism.document

import org.junit.Assume
import org.junit.Test

/**
 * DocumentParser 解析性能基准测试（ac-verifier 补充，US-012 性能基线）。
 *
 * 生成 PDF / DOCX / XLSX 样例，测量 parse 耗时的 p50/p95/p99 初版基线。
 * 默认跳过；手动运行获取基线数据：
 *   .\gradlew.bat testDebugUnitTest --tests "*.DocumentParserPerformanceBenchmark" -PignorePerformanceTests=false
 *
 * 说明：本基准在 JVM 上测量小样例解析耗时，反映 PDFBox/POI 库加载与解析开销，
 * 供后续迭代（US-016 大文件上限落地后）做性能回退对比。大文件内存峰值不在此覆盖。
 */
class DocumentParserPerformanceBenchmark {

    companion object {
        private const val ITERATIONS = 200
        private const val WARMUP = 20
    }

    @Test
    fun pdf_parse_latency_benchmark() {
        assumePerformance()
        val parser = DocumentParserRegistry().parserFor("bench.pdf")
        // 构造 200 句文本的样例（模拟中小体积文档）；PDFBox showText 不支持换行符，故以空格拼接为单行
        val largeText = (1..200).map { "The quick brown fox jumps over the lazy dog line $it" }.joinToString(" ") { it }

        repeat(WARMUP) { parser.parse(TestDocumentFactory.pdfByteStream(largeText)) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val input = TestDocumentFactory.pdfByteStream(largeText)
            val start = System.nanoTime()
            parser.parse(input)
            latencies[i] = System.nanoTime() - start
        }
        printStats("PDF parse", latencies)
    }

    @Test
    fun docx_parse_latency_benchmark() {
        assumePerformance()
        val parser = DocumentParserRegistry().parserFor("bench.docx")
        val paragraphs = (1..200).map { "Paragraph content number $it with some RAG text." }

        repeat(WARMUP) { parser.parse(TestDocumentFactory.docxByteStream(paragraphs)) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val input = TestDocumentFactory.docxByteStream(paragraphs)
            val start = System.nanoTime()
            parser.parse(input)
            latencies[i] = System.nanoTime() - start
        }
        printStats("DOCX parse", latencies)
    }

    @Test
    fun xlsx_parse_latency_benchmark() {
        assumePerformance()
        val parser = DocumentParserRegistry().parserFor("bench.xlsx")
        val rows = (1..200).map { r ->
            listOf<Any>("Name$r", r, r * 1.5, (r % 2 == 0), "notes-$r")
        }

        repeat(WARMUP) { parser.parse(TestDocumentFactory.xlsxByteStream("Bench", rows)) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val input = TestDocumentFactory.xlsxByteStream("Bench", rows)
            val start = System.nanoTime()
            parser.parse(input)
            latencies[i] = System.nanoTime() - start
        }
        printStats("XLSX parse", latencies)
    }

    @Test
    fun md_parse_latency_benchmark() {
        assumePerformance()
        val parser = DocumentParserRegistry().parserFor("bench.md")
        val md = (1..500).map { "# Section $it\nSome **bold** text and [a link](https://example.com/$it)." }.joinToString("\n")

        repeat(WARMUP) { parser.parse("$md\n".byteInputStream()) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            parser.parse("$md\n".byteInputStream())
            latencies[i] = System.nanoTime() - start
        }
        printStats("MD parse", latencies)
    }

    private fun assumePerformance() {
        Assume.assumeTrue(
            "性能基准默认跳过；运行: testDebugUnitTest ... -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )
    }

    private fun printStats(label: String, latencies: LongArray) {
        val sorted = latencies.sortedArray()
        val n = sorted.size
        val p50 = sorted[n * 50 / 100]
        val p95 = sorted[(n * 95 + 99) / 100 - 1]
        val p99 = sorted[(n * 99 + 99) / 100 - 1]
        val mean = latencies.average().toLong()
        val min = sorted.first()
        val max = sorted.last()

        println()
        println("===== $label =====")
        println("Iterations: $n")
        println("  p50: ${p50 / 1_000.0} us")
        println("  p95: ${p95 / 1_000.0} us")
        println("  p99: ${p99 / 1_000.0} us")
        println("  mean: ${mean / 1_000.0} us")
        println("  min:  ${min / 1_000.0} us")
        println("  max:  ${max / 1_000.0} us")
    }
}