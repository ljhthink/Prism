package io.prism.skill

import org.junit.Assume
import org.junit.Test

/**
 * SkillManifestParser 解析性能基准（ac-verifier 补充，M4 Phase B 初版基线）。
 *
 * 测量不同规模 SKILL.md frontmatter 解析耗时 p50/p95/p99，
 * 验证 parse() 在典型规模（~1KB）与较大规模（~10KB metadata）下的延迟可接受性。
 *
 * 默认跳过；手动运行获取基线数据：
 *   .\gradlew.bat testDebugUnitTest --tests "*.SkillManifestParserPerformanceBenchmark" -PignorePerformanceTests=false
 */
class SkillManifestParserPerformanceBenchmark {

    @Test
    fun parse_latency_benchmark() {
        Assume.assumeTrue(
            "性能基准默认跳过；运行: ... -PignorePerformanceTests=false",
            System.getProperty("prism.runPerformanceTests") == "true"
        )

        // 场景 1：典型内置 Skill（~1KB frontmatter，translator 风格）
        val typicalSkill = buildSkillMd(
            name = "translator",
            description = "中英互译翻译助手，支持术语表与语境保持",
            metadataLines = listOf(
                "version: 1.0.0",
                "user-invocable: true",
                "homepage: https://github.com/prism/skills/builtin/translator",
                "max-rounds: 3"
            ),
            bodySize = 2000
        )
        benchmark("parse(typical ~1KB skill)", typicalSkill)

        // 场景 2：带 tools 声明（meeting-notes 风格，~2KB frontmatter）
        val withTools = buildSkillMdWithTools()
        benchmark("parse(skill with tools ~2KB)", withTools)

        // 场景 3：大 metadata（10 个工具声明，~10KB frontmatter，压力测试）
        val largeSkill = buildLargeSkillMd(toolCount = 10)
        benchmark("parse(large 10-tool ~10KB)", largeSkill)
    }

    private fun benchmark(label: String, content: String) {
        // 预热
        repeat(WARMUP) { SkillManifestParser.parse(content) }

        val latencies = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            SkillManifestParser.parse(content)
            latencies[i] = System.nanoTime() - start
        }
        printStats(label, latencies, content.length)
    }

    private fun buildSkillMd(
        name: String,
        description: String,
        metadataLines: List<String>,
        bodySize: Int
    ): String {
        val body = "## 正文\n".repeat(bodySize / 10)
        return buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            metadataLines.forEach { appendLine(it) }
            appendLine("---")
            append(body)
        }
    }

    private fun buildSkillMdWithTools(): String {
        return """
            ---
            name: meeting-notes
            description: 会议纪要提取助手，从转录文本生成结构化纪要含决议与行动项
            version: 1.0.0
            user-invocable: true
            max-rounds: 5
            tools:
              - name: read_file
                description: 读取本地会议转录文件
                parameters:
                  type: object
                  properties:
                    path:
                      type: string
                      description: 会议转录文件的相对路径
                  required:
                    - path
                  additionalProperties: false
            ---

            # 会议纪要提取助手

            ## 能力
            从会议转录文本提取结构化纪要。
        """.trimIndent()
    }

    private fun buildLargeSkillMd(toolCount: Int): String {
        return buildString {
            appendLine("---")
            appendLine("name: large-tool-skill")
            appendLine("description: 压力测试用大 Skill 声明多个工具")
            appendLine("version: 1.0.0")
            appendLine("max-rounds: 20")
            appendLine("tools:")
            for (i in 1..toolCount) {
                appendLine("  - name: tool_$i")
                appendLine("    description: 工具 $i 的描述说明")
                appendLine("    parameters:")
                appendLine("      type: object")
                appendLine("      properties:")
                appendLine("        param1:")
                appendLine("          type: string")
                appendLine("          description: 参数一")
                appendLine("        param2:")
                appendLine("          type: integer")
                appendLine("          description: 参数二")
                appendLine("      required:")
                appendLine("        - param1")
                appendLine("      additionalProperties: false")
            }
            appendLine("---")
            appendLine("# 大 Skill 正文")
        }
    }

    private fun printStats(label: String, latencies: LongArray, contentSize: Int) {
        val sorted = latencies.sortedArray()
        val n = sorted.size
        val p50 = sorted[n * 50 / 100]
        val p95 = sorted[(n * 95 + 99) / 100 - 1]
        val p99 = sorted[(n * 99 + 99) / 100 - 1]
        val mean = latencies.average().toLong()
        val min = sorted.first()
        val max = sorted.last()

        println()
        println("===== $label (content=${contentSize} chars) =====")
        println("Iterations: $n")
        println("  p50: ${p50 / 1_000.0} us")
        println("  p95: ${p95 / 1_000.0} us")
        println("  p99: ${p99 / 1_000.0} us")
        println("  mean: ${mean / 1_000.0} us")
        println("  min:  ${min / 1_000.0} us")
        println("  max:  ${max / 1_000.0} us")
    }

    companion object {
        private const val ITERATIONS = 100
        private const val WARMUP = 20
    }
}
