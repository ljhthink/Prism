package io.prism.pet.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 批次24（ADR-051 风险表）：[PetAssetRoutes] 虚拟资源路由单测。
 *
 * 这是"用户资产目录被 WebView 读取"的**唯一入口**，因此白名单范围与路径校验必须钉死：
 * 只暴露 `pets/` 之下的普通文件，目录、越权路径、畸形编码一律拒绝。
 */
class PetAssetRoutesTest {

    private lateinit var petsDir: File

    @Before
    fun setUp() {
        petsDir = Files.createTempDirectory("prism-pet-routes").toFile()
        File(petsDir, "cyrene/motions").mkdirs()
        File(petsDir, "cyrene/Cyrene.model3.json").writeText("{}")
        File(petsDir, "cyrene/motions/动作#6_0.motion3.json").writeText("{}")
        File(petsDir, "secret.txt").writeText("should not be reachable from a sub-scope guess")
    }

    @After
    fun tearDown() {
        petsDir.deleteRecursively()
    }

    // ==================== assets ====================

    @Test
    fun `assets 路由 - 前缀匹配与净化`() {
        assertEquals("index.html", PetAssetRoutes.toAssetPath("/assets/pet/index.html"))
        assertEquals("vendor/pixi.min.js", PetAssetRoutes.toAssetPath("/assets/pet/vendor/pixi.min.js"))
        assertNull(PetAssetRoutes.toAssetPath("/pets/cyrene/x.json"))
        assertNull(PetAssetRoutes.toAssetPath("/assets/pet/../secret"))
        assertNull(PetAssetRoutes.toAssetPath("/assets/pet/"))
    }

    // ==================== 形象资产 ====================

    @Test
    fun `形象路由 - 直接命中`() {
        assertNotNull(PetAssetRoutes.resolvePetFile(petsDir, "/pets/cyrene/Cyrene.model3.json"))
    }

    /** 回归：含 `#` 的文件名必须经 `%23` 编码后仍能命中（安装期已改写引用，这里守住读取侧）。 */
    @Test
    fun `形象路由 - 百分号编码的井号文件名命中`() {
        val file = PetAssetRoutes.resolvePetFile(
            petsDir,
            "/pets/cyrene/motions/%E5%8A%A8%E4%BD%9C%236_0.motion3.json"
        )
        assertNotNull(file)
        assertEquals("动作#6_0.motion3.json", file!!.name)
    }

    @Test
    fun `形象路由 - 拒绝越权与非法请求`() {
        val rejected = listOf(
            "/pets/../secret.txt",
            "/pets/cyrene/../../secret.txt",
            "/pets//secret.txt",
            "/pets/secret.txt/",
            "/pets/%2e%2e/secret.txt",
            "/pets/cyrene/%2e%2e/%2e%2e/secret.txt",
            "/pets/cyrene/%ZZ",
            "/pets/cyrene/%2",
            "/assets/pet/index.html",
            "/pets/cyrene/motions"
        )
        rejected.forEach { path ->
            assertNull("应拒绝: $path", PetAssetRoutes.resolvePetFile(petsDir, path))
        }
    }

    @Test
    fun `形象路由 - 目录不被当作文件返回`() {
        assertNull(PetAssetRoutes.resolvePetFile(petsDir, "/pets/cyrene"))
        assertNull(PetAssetRoutes.resolvePetFile(petsDir, "/pets/cyrene/motions"))
    }

    // ==================== MIME ====================

    @Test
    fun `MIME - 关键类型必须准确`() {
        assertEquals("application/json", PetAssetRoutes.mimeOf("a/Cyrene.model3.json"))
        assertEquals("image/png", PetAssetRoutes.mimeOf("a/TEXTURE_0.PNG"))
        assertEquals("application/octet-stream", PetAssetRoutes.mimeOf("a/model.moc3"))
        assertEquals("text/html", PetAssetRoutes.mimeOf("index.html"))
        assertEquals("application/javascript", PetAssetRoutes.mimeOf("vendor/pixi.min.js"))
        assertEquals("application/octet-stream", PetAssetRoutes.mimeOf("a/unknown.bin"))
    }

    // ==================== 百分号解码 ====================

    @Test
    fun `解码 - 不把加号当空格`() {
        assertEquals("a+b.png", PetAssetRoutes.percentDecode("a+b.png"))
        assertEquals("a b.png", PetAssetRoutes.percentDecode("a%20b.png"))
        assertEquals("动作#6_0.motion3.json", PetAssetRoutes.percentDecode("%E5%8A%A8%E4%BD%9C%236_0.motion3.json"))
    }

    @Test
    fun `解码 - 畸形编码返回 null 而不抛异常`() {
        assertNull(PetAssetRoutes.percentDecode("%"))
        assertNull(PetAssetRoutes.percentDecode("%2"))
        assertNull(PetAssetRoutes.percentDecode("%GG"))
    }

    @Test
    fun `解码 - 无百分号时原样返回`() {
        assertEquals("plain/path.json", PetAssetRoutes.percentDecode("plain/path.json"))
    }

    @Test
    fun `路由前缀常量 - 与文档一致`() {
        assertEquals("/assets/pet/", PetAssetRoutes.ASSETS_PREFIX)
        assertEquals("/pets/", PetAssetRoutes.PETS_PREFIX)
        assertTrue(PetAssetRoutes.mimeOf("x.json").isNotEmpty())
    }
}
