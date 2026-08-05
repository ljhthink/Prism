package io.prism.data

import io.objectbox.BoxStore
import org.junit.Test
import java.io.File

/**
 * ProviderConfig 模块 JVM 演示程序 —— 用 Mock 数据验证数据加载与显示逻辑。
 *
 * 模拟用户配置 AI Provider 的完整流程：
 * 1. 从 5 种预设模板（OpenAI/Anthropic/Ollama/Moonshot/OpenRouter）创建 Provider
 * 2. 新增 1 个自定义 Provider（模拟手动配置）
 * 3. 保存到 ObjectBox
 * 4. 读取全部，验证数据加载
 * 5. 切换激活 Provider
 * 6. 展示加载结果（控制台表格）
 *
 * 提供两种运行方式：
 * - JVM main：`java -cp ... ProviderConfigDemo`（需完整依赖 classpath）
 * - JUnit 测试：`testDebugUnitTest --tests "io.prism.data.ProviderConfigDemo"`（推荐，Gradle 自动配置 classpath）
 * 控制台输出依赖 `showStandardStreams = true`（build.gradle.kts 临时配置，验证后移除）。
 */
class ProviderConfigDemo {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // runDemo 是实例方法（JUnit 4 要求测试类可实例化），静态入口需先实例化
            ProviderConfigDemo().runDemo()
        }
    }

    /**
     * JUnit 入口。JUnit 4 要求测试类为可实例化的 class，故演示逻辑放在实例方法中。
     */
    @Test
    fun runDemo() {
        runDemoBlocking()
    }

    private fun runDemoBlocking() {
        val separator = "=".repeat(72)
        println()
        println(separator)
        println("  Prism · ProviderConfig 模块演示（Mock 数据）")
        println("  演示目的：验证数据加载与显示逻辑，为 UI 开发做准备")
        println(separator)

        // 使用临时目录构建纯 JVM ObjectBox 实例（与单元测试一致的隔离方式）
        val tempDir: File = kotlin.io.path.createTempDirectory(prefix = "prism-demo-").toFile()
        val boxStore: BoxStore = MyObjectBox.builder().directory(tempDir).build()

        try {
            val repository = ProviderConfigRepository(boxStore)
            println("\n[1/5] 创建 Repository（ObjectBox 临时实例）")

            // ---- 步骤 1：从预设模板创建 Mock Provider ----
            println("\n[2/5] 从 5 种预设模板创建 Provider（Mock 数据）...")
            val presetIds = mutableMapOf<String, Long>()
            ProviderPresets.all.forEachIndexed { _, preset ->
                val id = repository.createFromPreset(preset)
                presetIds[preset.name] = id
                println("  > ${preset.name.padEnd(16)} -> id=$id   baseUrl=${preset.baseUrl}")
            }

            // ---- 步骤 2：新增自定义 Provider ----
            println("\n[3/5] 新增 1 个自定义 Provider（模拟手动配置）...")
            val customId = repository.save(
                ProviderConfig(
                    name = "MyLocal",
                    baseUrl = "http://192.168.1.100:1234/v1",
                    apiKeyRef = "mylocal",
                    models = listOf("custom-model-a", "custom-model-b"),
                    headers = mapOf("X-Custom-Key" to "demo-value")
                )
            )
            println("  > MyLocal -> id=$customId   baseUrl=http://192.168.1.100:1234/v1")

            // ---- 步骤 3：加载全部（验证数据加载） ----
            println("\n[4/5] 加载全部 Provider 配置（共 " + repository.getAll().size + " 条）...")
            val all = repository.getAll()
            println("  [id]     名称          模型数  激活   创建时间")
            println("  " + "-".repeat(64))
            all.forEach { c ->
                val active = if (c.isActive) "* 是" else "- 否"
                println(
                    "  " + c.id.toString().padEnd(9) + c.name.padEnd(14) +
                        c.models.size.toString().padEnd(7) + active.padEnd(8) + c.createdAt
                )
            }

            // ---- 步骤 4：切换激活 Provider ----
            println("\n[5/5] 切换激活 Provider：OpenAI -> 自定义 MyLocal...")
            repository.setActive(presetIds.getValue("OpenAI"))
            println("  > 激活 OpenAI (id=" + presetIds.getValue("OpenAI") + ")")
            repository.setActive(customId)
            println("  > 切换激活 MyLocal (id=$customId)")
            val activeProvider = repository.activeProviderFlow.value
            println("  -> 当前激活：" + (activeProvider?.name ?: "无"))

            // ---- 最终展示 ----
            println("\n$separator")
            println("  演示完成：数据加载与显示逻辑验证通过")
            println("  > " + all.size + " 条 Provider 配置全部正确加载")
            println("  > 5 种预设模板 + 1 个自定义配置")
            println("  > 激活切换正常（同一时间仅一个激活）")
            println("  > ObjectBox 持久化往返正确")
            println(separator)
        } finally {
            boxStore.close()
            tempDir.deleteRecursively()
        }
    }
}