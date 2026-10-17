package io.prism.pet

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetActionRef
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetAppearanceKind
import io.prism.pet.model.PetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 批次24（ADR-051）：渲染描述符编译器与配置仓库单测 —— 覆盖 guardrail M-6 指出的盲区。
 *
 * 这两块此前**零覆盖**：描述符编译器挂在持有 `Context` 的门面上（已抽成纯 object
 * [PetDescriptorCodec]），配置仓库没有被测（DataStore 可用 `PreferenceDataStoreFactory`
 * 在纯 JVM 上跑，项目既有 `RolePackLoaderTest` 即此模式）。
 */
class PetDescriptorCodecTest {

    private val cyrene = PetAppearance(
        id = "cyrene",
        name = "昔涟",
        kind = PetAppearanceKind.LIVE2D,
        model = "Cyrene.model3.json",
        scale = 1.25f,
        actions = mapOf(
            "idle" to PetActionRef(PetActionKind.MOTION, "Tick3", PetActionRef.RANDOM_MOTION_INDEX),
            "happy" to PetActionRef(PetActionKind.EXPRESSION, "开心眼"),
            "wave" to PetActionRef(PetActionKind.MOTION, "动作#6", 2, "我可爱吧~", "星星眼")
        ),
        hitActions = mapOf("左侧秋千" to "wave"),
        idleAliases = listOf("idle"),
        tapAlias = "happy",
        defaultExpression = "表情回正",
        attribution = "原模型作者：是依七哒"
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    @Test
    fun `描述符 - 模型路径被百分号编码以承载井号`() {
        val obj = parse(PetDescriptorCodec.build(cyrene, PetConfig()))
        assertEquals("live2d", obj["kind"]!!.jsonPrimitive.content)
        assertEquals("/pets/cyrene/Cyrene.model3.json", obj["modelUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `描述符 - 含井号与中文的图片路径被编码`() {
        val imageAppearance = cyrene.copy(
            kind = PetAppearanceKind.IMAGE,
            model = null,
            images = listOf("子目录/动作#6_0.png")
        )
        val obj = parse(PetDescriptorCodec.build(imageAppearance, PetConfig()))
        assertEquals(
            "/pets/cyrene/%E5%AD%90%E7%9B%AE%E5%BD%95/%E5%8A%A8%E4%BD%9C%236_0.png",
            obj["imageUrls"]!!.jsonArray[0].jsonPrimitive.content
        )
    }

    @Test
    fun `描述符 - 负下标哨兵原样下发`() {
        val obj = parse(PetDescriptorCodec.build(cyrene, PetConfig()))
        val idle = obj["actions"]!!.jsonObject["idle"]!!.jsonObject
        assertEquals("motion", idle["kind"]!!.jsonPrimitive.content)
        assertEquals("Tick3", idle["target"]!!.jsonPrimitive.content)
        assertEquals(PetActionRef.RANDOM_MOTION_INDEX, idle["index"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `描述符 - 回退表情下发`() {
        val wave = parse(PetDescriptorCodec.build(cyrene, PetConfig()))
            .let { it["actions"]!!.jsonObject["wave"]!!.jsonObject }
        assertEquals("星星眼", wave["fallbackExpression"]!!.jsonPrimitive.content)
        assertEquals("我可爱吧~", wave["name"]!!.jsonPrimitive.content)
        assertEquals(2, wave["index"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `描述符 - 缩放为形象缩放乘用户缩放`() {
        val obj = parse(PetDescriptorCodec.build(cyrene, PetConfig(scale = 0.8f)))
        assertEquals(1.0, obj["scale"]!!.jsonPrimitive.content.toDouble(), 0.0001)
    }

    @Test
    fun `描述符 - 非法用户配置被钳制后才下发`() {
        val obj = parse(PetDescriptorCodec.build(cyrene, PetConfig(scale = -5f, expressionResetSeconds = 999)))
        // 最终缩放 = 形象缩放(1.25) × 用户缩放（已被钳到 MIN_SCALE=0.5）= 0.625
        assertEquals(
            1.25 * PetConfig.MIN_SCALE.toDouble(),
            obj["scale"]!!.jsonPrimitive.content.toDouble(),
            0.0001
        )
        assertEquals(PetConfig.MAX_EXPRESSION_RESET_SECONDS, obj["expressionResetSeconds"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `描述符 - 待机别名经过可解析过滤`() {
        val broken = cyrene.copy(idleAliases = listOf("idle", "不存在", "happy"))
        val obj = parse(PetDescriptorCodec.build(broken, PetConfig()))
        val idle = obj["idleAliases"]!!.jsonArray.map { it.jsonPrimitive.content }
        // "不存在" 未在 actions 中声明 → 被过滤；idle / happy 均声明了 → 保留且保持原序
        assertEquals(listOf("idle", "happy"), idle)
    }

    @Test
    fun `描述符 - 无形象时给出最小描述符而非失败`() {
        val obj = parse(PetDescriptorCodec.build(null, PetConfig()))
        assertEquals("image", obj["kind"]!!.jsonPrimitive.content)
        assertTrue(obj["imageUrls"]!!.jsonArray.isEmpty())
        assertNull(obj["modelUrl"])
    }

    @Test
    fun `描述符 - 高亮配置透传`() {
        val obj = parse(
            PetDescriptorCodec.build(
                cyrene,
                PetConfig(reduceMotion = true, lowPowerMode = true, expressionResetSeconds = 5)
            )
        )
        assertEquals(true, obj["reduceMotion"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, obj["lowPower"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(5, obj["expressionResetSeconds"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `描述符 - 命中区映射下发`() {
        val obj = parse(PetDescriptorCodec.build(cyrene, PetConfig()))
        assertEquals("wave", obj["hitActions"]!!.jsonObject["左侧秋千"]!!.jsonPrimitive.content)
    }

    @Test
    fun `petUrl - 组合前缀与编码`() {
        assertEquals("/pets/a/b.png", PetDescriptorCodec.petUrl("a", "b.png"))
        assertEquals("/pets/%E6%98%94%E6%B6%9F/%23.png", PetDescriptorCodec.petUrl("昔涟", "#.png"))
    }
}

/**
 * [PetConfigRepository] 单测（纯 JVM：DataStore 用 `PreferenceDataStoreFactory` 落临时文件）。
 */
class PetConfigRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: PetConfigRepository

    private fun newRepo(scope: CoroutineScope): PetConfigRepository {
        val file = File(dir, "pet-${System.nanoTime()}.preferences_pb")
        return PetConfigRepository(
            PreferenceDataStoreFactory.create(scope = scope) { file }
        )
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("prism-pet-config").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `默认值 - 桌宠出厂关闭`() = runBlocking<Unit> {
        repo = newRepo(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val config = repo.get()
        assertFalse("桌宠必须默认关闭（ADR-051 D7）", config.enabled)
        assertNull(config.appearanceId)
        assertEquals(1.0f, config.scale, 0.0001f)
        assertEquals(1.0f, config.opacity, 0.0001f)
        assertTrue(config.idleEnabled)
        assertFalse(config.reduceMotion)
    }

    @Test
    fun `写入读取 - round-trip`() = runBlocking<Unit> {
        repo = newRepo(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.setEnabled(true)
        repo.setAppearanceId("cyrene")
        repo.setScale(1.2f)
        repo.setOpacity(0.75f)
        repo.setPosition(0.25f, 0.5f)
        repo.setIdleEnabled(false)
        repo.setReduceMotion(true)
        repo.setBubbleEnabled(false)
        repo.setLowPowerMode(true)
        repo.setExpressionResetSeconds(5)

        val c = repo.get()
        assertTrue(c.enabled)
        assertEquals("cyrene", c.appearanceId)
        assertEquals(1.2f, c.scale, 0.0001f)
        assertEquals(0.75f, c.opacity, 0.0001f)
        assertEquals(0.25f, c.posX, 0.0001f)
        assertEquals(0.5f, c.posY, 0.0001f)
        assertFalse(c.idleEnabled)
        assertTrue(c.reduceMotion)
        assertFalse(c.bubbleEnabled)
        assertTrue(c.lowPowerMode)
        assertEquals(5, c.expressionResetSeconds)
    }

    @Test
    fun `写侧钳制 - 越界值不落盘为非法值`() = runBlocking<Unit> {
        repo = newRepo(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.setScale(99f)
        repo.setOpacity(-1f)
        repo.setPosition(5f, -5f)
        repo.setExpressionResetSeconds(999)

        val c = repo.get()
        assertEquals(PetConfig.MAX_SCALE, c.scale, 0.0001f)
        assertEquals(PetConfig.MIN_OPACITY, c.opacity, 0.0001f)
        assertEquals(1f, c.posX, 0.0001f)
        assertEquals(0f, c.posY, 0.0001f)
        assertEquals(PetConfig.MAX_EXPRESSION_RESET_SECONDS, c.expressionResetSeconds)
    }

    @Test
    fun `写侧钳制 - NaN 不落盘`() = runBlocking<Unit> {
        repo = newRepo(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.setScale(Float.NaN)
        repo.setOpacity(Float.NaN)
        val c = repo.get()
        assertFalse(c.scale.isNaN())
        assertFalse(c.opacity.isNaN())
    }

    @Test
    fun `清除形象 - 空值与 null 等价`() = runBlocking<Unit> {
        repo = newRepo(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.setAppearanceId("cyrene")
        assertNotNull(repo.get().appearanceId)
        repo.setAppearanceId(null)
        assertNull(repo.get().appearanceId)
        repo.setAppearanceId("cyrene")
        repo.setAppearanceId("   ")
        assertNull("空白 id 应被清除而非落盘", repo.get().appearanceId)
    }
}
