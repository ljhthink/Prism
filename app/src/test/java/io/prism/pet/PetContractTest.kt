package io.prism.pet

import io.prism.pet.asset.PetPackInstaller
import io.prism.pet.model.PetActionCatalog
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetActionRef
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetConfig
import io.prism.pet.overlay.PetAssetRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 批次24（ADR-051）：**跨层契约**与**真实资产**的端到端测试。
 *
 * 本文件针对的是 R1 审查暴露的那类缺陷：**单层看都对、跨层接不上**。
 * 具体案例（H-6）：`pet-renderer.js` 加了 `index < 0` 的随机分支、
 * `build-cyrene-petpack.js` 也写入了 `index: -1`，但中间的 `PetPackParser`
 * 用 `coerceAtLeast(0)` 把哨兵截成了 0 —— 三处单独看都"改了"，链路却不成立，
 * 而当时的 83 个用例**一个都没覆盖这条链**。
 *
 * 因此这里做两件事：
 * 1. 用**手工构造**的形象跑完整链路（不依赖任何外部文件，CI 可跑）；
 * 2. 用**真实 `cyrene.petpack`** 跑同一链路（存在时执行，不存在时 `assumeTrue` 跳过，
 *    并在跳过时明确它**不构成证据**）。
 */
class PetContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== 1. 工具 schema 结构断言（BR-testing-008） ====================

    /**
     * `pet__play_action` 的 JSON Schema 必须是**结构合法**的，而不只是"字符串里含关键字"。
     *
     * 为什么单列一条：项目有过真实事故——`document__create_xlsx` 的 `sheets` 参数写成
     * 裸 `JsonArray` 字面量（非法 JSON Schema），DeepSeek 对**全部请求**返回
     * `400 Invalid schema`，导致整个模型端点不可用（BR-interface-016 / BR-testing-008）。
     * 工具 schema 出错是**全站级**故障，不是局部故障。
     */
    @Test
    fun `工具 schema - 结构合法且枚举非空`() {
        val params = json.parseToJsonElement(
            PetLocalToolExecutor.buildToolDefinition().function.parameters.toString()
        ).jsonObject

        assertEquals("object", params["type"]!!.jsonPrimitive.content)

        val properties = params["properties"]!!.jsonObject
        val action = properties["action"]!!.jsonObject
        assertEquals("string", action["type"]!!.jsonPrimitive.content)

        val enum = action["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("枚举不得为空", enum.isNotEmpty())
        assertEquals("枚举必须与内置别名全集一致", PetActionCatalog.BUILTIN_ALIASES, enum)
        assertTrue("枚举值不得含空白", enum.none { it != it.trim() || it.isEmpty() })

        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("action"), required)
        // required 里出现的键必须真的在 properties 中声明（否则模型无法构造合法参数）
        assertTrue(required.all { properties.containsKey(it) })
        assertEquals("false", params["additionalProperties"]!!.jsonPrimitive.content)
    }

    @Test
    fun `工具 schema - 描述包含负向约束`() {
        // 只写正向触发会让模型在纯信息性回答上也乱调动作（工具描述须给负向条件）
        val description = PetLocalToolExecutor.buildToolDefinition().function.description
        assertTrue(description.contains("不要"))
        assertTrue(description.contains("桌宠未开启"))
    }

    // ==================== 2. 跨层契约：负下标哨兵全链路 ====================

    /**
     * H-6 的通用护栏：`pet.json` 的 `index:-1` 必须一路走到渲染描述符里仍是 -1。
     *
     * 链路：`pet.json` 文本 → [io.prism.pet.asset.PetPackParser] → [PetAppearance]
     * → [PetDescriptorCodec] → 渲染器 JSON。
     * 任何一环做"善意的钳制"都会让"组内随机"退化成"固定第 0 个动作"。
     */
    @Test
    fun `跨层 - 负下标哨兵穿过解析器与描述符仍是负值`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("prism-pet-contract").toFile()
        try {
            val installer = PetPackInstaller(dir, Dispatchers.Unconfined)
            val pack = zipOf(
                "pet.json" to """
                    {"schema":"prism-petpack/1","id":"c","name":"契约形象","kind":"live2d",
                     "model":"M.model3.json",
                     "actions":{
                       "idle":{"kind":"motion","target":"Tick3","index":-1},
                       "happy":"开心眼"},
                     "idle":["idle"],"tap":"happy"}
                """.trimIndent(),
                "M.model3.json" to """{"FileReferences":{"Moc":"model.moc3","Textures":[],"Expressions":[]}}""",
                "model.moc3" to "MOC3"
            )
            val appearance = installer.installPack(pack)

            // 第 1 跳：解析器不得钳制负值
            assertEquals(
                "解析器把负下标哨兵改了 → 组内随机将退化为固定动作",
                PetActionRef.RANDOM_MOTION_INDEX,
                appearance.actions["idle"]!!.index
            )
            // 第 2 跳：描述符不得钳制负值
            val descriptor = json.parseToJsonElement(
                PetDescriptorCodec.build(appearance, PetConfig())
            ).jsonObject
            val idle = descriptor["actions"]!!.jsonObject["idle"]!!.jsonObject
            assertEquals(
                "描述符把负下标哨兵改了 → 渲染层的随机分支永远走不到",
                PetActionRef.RANDOM_MOTION_INDEX,
                idle["index"]!!.jsonPrimitive.content.toInt()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 含 `#` 的资产在"安装 → 引用改写 → 路由解析"三跳后仍能命中真实文件。 */
    @Test
    fun `跨层 - 含井号的资产名可被路由命中`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("prism-pet-contract-hash").toFile()
        try {
            val installer = PetPackInstaller(dir, Dispatchers.Unconfined)
            val appearance = installer.installPack(
                zipOf(
                    "pet.json" to """
                        {"schema":"prism-petpack/1","id":"c","name":"契约","kind":"live2d",
                         "model":"Cyrene.model3.json"}
                    """.trimIndent(),
                    "Cyrene.model3.json" to """
                        {"FileReferences":{"Moc":"model.moc3","Textures":["t.png"],
                         "Motions":{"动作#6":[{"Name":"动作回正","File":"motions/动作#6_0.motion3.json"}]}}}
                    """.trimIndent(),
                    "model.moc3" to "MOC3",
                    "t.png" to "PNG",
                    "motions/动作#6_0.motion3.json" to "{}"
                )
            )

            // 安装后引用必须已被改写成百分号编码（否则 URL 解析会把 # 当 fragment 吃掉）
            val rewritten = File(dir, "c/Cyrene.model3.json").readText()
            assertTrue(
                "引用未 URL 化，含 # 的 motion 会在真实请求中 404",
                rewritten.contains("motions/%E5%8A%A8%E4%BD%9C%236_0.motion3.json")
            )

            // 路由侧：把描述符里的 URL 路径喂回虚拟路由，必须命中真实文件
            val url = PetDescriptorCodec.build(appearance, PetConfig())
                .let { json.parseToJsonElement(it).jsonObject }
                .let { it["modelUrl"]!!.jsonPrimitive.content }
            assertNotNull(PetAssetRoutes.resolvePetFile(dir, url))

            val motionUrl = "/pets/c/motions/%E5%8A%A8%E4%BD%9C%236_0.motion3.json"
            val resolved = PetAssetRoutes.resolvePetFile(dir, motionUrl)
            assertNotNull("含 # 的 motion 经 URL 编码后必须能被路由命中", resolved)
            assertEquals("动作#6_0.motion3.json", resolved!!.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ==================== 3. 真实资产端到端（存在时才跑） ====================

    /**
     * 用**真实 `cyrene.petpack`** 跑完整链路。
     *
     * **跳过语义**：包不在（CI / 新克隆）时 `assumeTrue` 跳过。此时本用例
     * **不构成任何证据** —— 报告里必须如实标注"未执行"，不得当作通过。
     * 之所以仍要写它：这是唯一能在交付前发现"资产与解析器实际不匹配"的手段
     * （H-6 正是资产与解析器不匹配，而当时无人发现）。
     */
    @Test
    fun `真实资产 - cyrene_petpack 全链路自洽`() = runBlocking<Unit> {
        val pack = locateRealPetPack()
        assumeTrue(
            "cyrene.petpack 不存在（属用户私有资产，不入库），本用例未执行、**不构成任何证据**",
            pack != null
        )

        val dir = Files.createTempDirectory("prism-pet-contract-real").toFile()
        try {
            val installer = PetPackInstaller(dir, Dispatchers.Unconfined)
            val appearance = pack!!.inputStream().use { installer.installPack(it) }

            assertEquals("cyrene", appearance.id)
            assertEquals("昔涟", appearance.name)
            assertTrue("动作别名应有 17 个，实际 ${appearance.actions.size}", appearance.actions.size >= 15)
            assertEquals("命中区应 9/9 覆盖", 9, appearance.hitActions.size)

            // 昔涟包的 idle 就是负下标哨兵——这是 H-6 的原始触发点
            assertEquals(
                "cyrene.petpack 的 idle 必须是组内随机（index=-1）",
                PetActionRef.RANDOM_MOTION_INDEX,
                appearance.actions["idle"]!!.index
            )
            assertEquals(PetActionKind.MOTION, appearance.actions["idle"]!!.kind)
            assertEquals("Tick3", appearance.actions["idle"]!!.target)

            // 每个命中区指向的别名都必须能在动作表里解析（否则点击静默无反应）
            appearance.hitActions.forEach { (area, alias) ->
                assertNotNull(
                    "命中区「$area」指向了不存在的别名「$alias」→ 点击该部位将无反应",
                    PetActionCatalog.resolve(appearance, alias)
                )
            }
            // 待机别名同样必须可解析
            assertTrue(
                "待机别名集合为空 → 开了待机却什么都不做",
                PetActionCatalog.idleAliases(appearance).isNotEmpty()
            )

            // 模型引用已 URL 化，且每个引用文件真实存在、可被虚拟路由命中
            val modelFile = File(dir, "cyrene/${appearance.model}")
            assertTrue(modelFile.isFile)
            val refs = io.prism.pet.asset.PetPackParser.collectModelReferences(modelFile.readText())
            assertTrue("模型引用不应为空", refs.isNotEmpty())
            refs.forEach { ref ->
                val url = "/pets/cyrene/$ref"
                assertNotNull("路由无法解析模型引用：$ref", PetAssetRoutes.resolvePetFile(dir, url))
            }
            // 含 # 的那 4 个 motion 必须在内（它们是最容易被 URL 解析吃掉的）
            assertTrue(
                "未发现被 URL 化的含 # motion 引用",
                refs.any { it.contains("%23") || it.contains("%E5%8A%A8%E4%BD%9C") }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ==================== 工具 ====================

    /**
     * 定位仓库根目录下的 `cyrene.petpack`。
     *
     * **为什么不直接用 `user.dir`**：Gradle 跑单元测试时 `user.dir` 是**模块目录**（`app/`），
     * 不是仓库根 —— 早期实现因此**静默跳过**了本用例（`assumeTrue` 判 false 即 skip），
     * 而这恰恰是本文件最想跑的一条（它是 H-6 那类"资产与解析器不匹配"的唯一护栏）。
     * 这里沿目录树向上找，允许显式用系统属性 `prism.petpack` 覆盖。
     */
    private fun locateRealPetPack(): File? {
        System.getProperty("prism.petpack")?.let { p ->
            val f = File(p)
            if (f.isFile) return f
        }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val candidate = dir?.let { File(it, "cyrene.petpack") }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    private fun zipOf(vararg entries: Pair<String, String>): java.io.ByteArrayInputStream {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return java.io.ByteArrayInputStream(out.toByteArray())
    }
}
