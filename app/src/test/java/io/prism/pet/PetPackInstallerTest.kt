package io.prism.pet

import io.prism.pet.asset.PetPackException
import io.prism.pet.asset.PetPackInstaller
import io.prism.pet.model.PetAppearanceKind
import io.prism.pet.overlay.PetAssetRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 批次24（ADR-051 D4 / PRD US-2402）：[PetPackInstaller] 安装、引用校验与**安全边界**单测。
 *
 * 全部在临时目录上跑（不依赖 Robolectric）；`android.util.Log` 由测试配置的
 * `isReturnDefaultValues = true` 兜底。
 */
class PetPackInstallerTest {

    private lateinit var baseDir: File
    private lateinit var installer: PetPackInstaller

    private val model3Json = """
        {
          "Version": 3,
          "FileReferences": {
            "Moc": "model.moc3",
            "Textures": ["texture_0.png"],
            "Physics": "model.physics3.json",
            "Motions": { "动作#6": [ { "Name": "动作回正", "File": "motions/动作#6_0.motion3.json" } ] },
            "Expressions": [ { "Name": "开心眼", "File": "expressions/6.exp3.json" } ]
          },
          "Groups": [],
          "HitAreas": [ { "Name": "左侧秋千", "Id": "ArtMesh11" } ]
        }
    """.trimIndent()

    private val fullPack = linkedMapOf(
        "pet.json" to """
            {
              "schema": "prism-petpack/1",
              "id": "cyrene",
              "name": "昔涟",
              "kind": "live2d",
              "model": "Cyrene.model3.json",
              "actions": { "happy": "开心眼", "wave": "动作#6#0" },
              "hitActions": { "左侧秋千": "wave" },
              "idle": ["happy"],
              "tap": "wave"
            }
        """.trimIndent(),
        "Cyrene.model3.json" to model3Json,
        "model.moc3" to "MOC3-BINARY",
        "texture_0.png" to "PNG-BINARY",
        "model.physics3.json" to "{}",
        "motions/动作#6_0.motion3.json" to "{}",
        "expressions/6.exp3.json" to "{}"
    )

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("prism-pet-installer").toFile()
        installer = PetPackInstaller(baseDir, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    // ==================== 正常路径 ====================

    @Test
    fun `安装 - 完整包落盘并可读回`() = runBlocking<Unit> {
        val appearance = installer.installPack(zip(fullPack))

        assertEquals("cyrene", appearance.id)
        assertEquals("昔涟", appearance.name)
        assertEquals(PetAppearanceKind.LIVE2D, appearance.kind)
        assertEquals(2, appearance.actions.size)
        assertEquals("wave", appearance.hitActions["左侧秋千"])

        val dir = File(baseDir, "cyrene")
        assertTrue(File(dir, "pet.json").isFile)
        assertTrue(File(dir, "model.moc3").isFile)
        assertTrue(File(dir, "motions/动作#6_0.motion3.json").isFile)

        val listed = installer.list()
        assertEquals(1, listed.size)
        assertNotNull(installer.find("cyrene"))
        // 落盘后模型引用已被 URL 化（# → %23）
        assertTrue(File(dir, "Cyrene.model3.json").readText().contains("%E5%8A%A8%E4%BD%9C%236_0.motion3.json"))
    }

    @Test
    fun `安装 - 同 id 重复导入幂等覆盖`() = runBlocking<Unit> {
        installer.installPack(zip(fullPack))
        val again = installer.installPack(zip(fullPack))
        assertEquals("cyrene", again.id)
        assertEquals(1, installer.list().size)
    }

    @Test
    fun `安装 - staging 目录不残留`() = runBlocking<Unit> {
        installer.installPack(zip(fullPack))
        val leftovers = baseDir.listFiles()?.filter { it.name.startsWith(".staging-") }.orEmpty()
        assertTrue("暂存目录应被清理: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `安装 - 单图通道`() = runBlocking<Unit> {
        val appearance = installer.installImage(ByteArrayInputStream("PNG".toByteArray()), "我的桌宠", "png")
        assertEquals(PetAppearanceKind.IMAGE, appearance.kind)
        assertEquals("我的桌宠", appearance.name)
        assertEquals(1, installer.list().size)
        assertTrue(File(baseDir, "我的桌宠/portrait.png").isFile)
    }

    @Test
    fun `删除 - 移除整个形象目录`() = runBlocking<Unit> {
        installer.installPack(zip(fullPack))
        assertTrue(installer.remove("cyrene"))
        assertFalse(File(baseDir, "cyrene").exists())
        assertFalse(installer.remove("cyrene"))
        assertTrue(installer.list().isEmpty())
    }

    // ==================== 引用完整性 ====================

    @Test
    fun `引用校验 - 缺纹理拒绝且不落盘`() {
        val broken = LinkedHashMap(fullPack).apply { remove("texture_0.png") }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(broken)) } }
        assertFalse("失败导入不得留下目录", File(baseDir, "cyrene").exists())
    }

    @Test
    fun `引用校验 - 缺 motion 文件拒绝`() {
        val broken = LinkedHashMap(fullPack).apply { remove("motions/动作#6_0.motion3.json") }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(broken)) } }
    }

    @Test
    fun `引用校验 - 缺 pet json 拒绝`() {
        val broken = LinkedHashMap(fullPack).apply { remove("pet.json") }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(broken)) } }
    }

    // ==================== 安全边界 ====================

    /** 核心安全回归：zip slip 条目必须被拒绝，且**不得在目标目录之外写任何文件**。 */
    @Test
    fun `安全 - zip slip 条目被拒绝且无越界写入`() {
        val outside = File(baseDir.parentFile, "prism-pet-escape-${System.nanoTime()}.txt")
        val evil = LinkedHashMap(fullPack).apply {
            put("../${outside.name}", "pwned")
        }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(evil)) } }
        assertFalse("越界文件不得被创建", outside.exists())
    }

    @Test
    fun `安全 - 绝对路径条目被拒绝`() {
        val evil = LinkedHashMap(fullPack).apply { put("/tmp/prism-evil.txt", "pwned") }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(evil)) } }
    }

    @Test
    fun `安全 - 扩展名白名单外条目被拒绝`() {
        val evil = LinkedHashMap(fullPack).apply { put("payload.dex", "x") }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(evil)) } }
    }

    @Test
    fun `安全 - 条目数超限被拒绝`() {
        val evil = LinkedHashMap<String, String>()
        evil["pet.json"] = fullPack["pet.json"]!!
        repeat(600) { evil["f$it.png"] = "x" }
        assertThrows(PetPackException::class.java) { runBlocking { installer.installPack(zip(evil)) } }
    }

    @Test
    fun `安全 - 空包被拒绝`() {
        assertThrows(PetPackException::class.java) {
            runBlocking { installer.installPack(ByteArrayInputStream(ByteArray(0))) }
        }
    }

    @Test
    fun `安全 - 非 zip 内容被拒绝`() {
        assertThrows(PetPackException::class.java) {
            runBlocking { installer.installPack(ByteArrayInputStream("这不是一个 zip".toByteArray())) }
        }
    }

    @Test
    fun `安全 - 单图通道扩展名白名单`() {
        assertThrows(PetPackException::class.java) {
            runBlocking { installer.installImage(ByteArrayInputStream("x".toByteArray()), "evil", "dex") }
        }
    }

    @Test
    fun `安全 - 单图通道空内容被拒绝`() {
        assertThrows(PetPackException::class.java) {
            runBlocking { installer.installImage(ByteArrayInputStream(ByteArray(0)), "empty", "png") }
        }
    }

    // ==================== 损坏项隔离 ====================

    @Test
    fun `列举 - 单个损坏形象被跳过而非整表失败`() = runBlocking<Unit> {
        installer.installPack(zip(fullPack))
        // 人为破坏一个形象目录（模拟外部篡改）
        val brokenDir = File(baseDir, "broken").apply { mkdirs() }
        File(brokenDir, "pet.json").writeText("{ not json")
        val listed = installer.list()
        assertEquals(1, listed.size)
        assertEquals("cyrene", listed.first().id)
        assertNull(installer.find("broken"))
    }

    // ==================== 体积上限（guardrail M-1 / M-6） ====================

    /**
     * 单文件上限必须**按实际写入字节**判定，不能只信 zip 头声明的 size。
     *
     * 本用例手工构造 ZIP：本地头（local file header）里把 `uncompressedSize` 谎报成 16 字节，
     * 实际 deflate 解出 80MB 全零数据。旧实现只读 `entry.size`（由头部字段而来），
     * 会放行 —— 这是 zip bomb 的经典手法。
     */
    @Test
    fun `安全 - 伪造 zip 头尺寸的超大条目仍被拒绝`() {
        val bytes = forgedSizeZip(
            entryName = "pet.json",
            declaredSize = 16,
            payload = ByteArray(80 * 1024 * 1024) // 80MB 全零，deflate 后极小
        )
        val e = assertThrows(PetPackException::class.java) {
            runBlocking { installer.installPack(ByteArrayInputStream(bytes)) }
        }
        assertTrue("应命中单文件或总体积上限：${e.message}", e.message!!.contains("超过上限"))
        assertTrue(baseDir.listFiles().orEmpty().none { it.name.startsWith(".staging-") })
    }

    @Test
    fun `安全 - 正常小包不受体积上限影响`() = runBlocking<Unit> {
        assertEquals("cyrene", installer.installPack(zip(fullPack)).id)
    }

    // ==================== 符号链接（guardrail M-6） ====================

    /**
     * 符号链接逃逸：`filesDir/pets/<id>/link.png` 若是指向根目录之外的软链，
     * `resolvePetFile` 的 `canonicalPath` 校验必须拒绝。
     *
     * Windows 上创建符号链接需要特权，用 `assumeTrue` 在无权限环境下**跳过**
     * （而不是静默通过——那样会让"测试通过"变成假信号）。
     */
    @Test
    fun `安全 - 符号链接逃逸被 canonicalPath 校验拒绝`() {
        val outside = File(baseDir.parentFile, "prism-pet-outside-${System.nanoTime()}.png")
        outside.writeText("secret")
        val link = File(baseDir, "linked.png")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("当前环境不支持创建符号链接，跳过", created)

        assertNull("指向根目录之外的符号链接不得被读取", PetAssetRoutes.resolvePetFile(baseDir, "/pets/linked.png"))
        assertNull(outside.delete())
    }

    // ==================== 工具 ====================

    /**
     * 手工构造 ZIP：本地头声明一个**假的** uncompressed size，而 deflate 流实际更大。
     *
     * 用于验证"按实际写入字节判定"的防护（guardrail M-1）。不设 bit 3（data descriptor），
     * 因此 `ZipInputStream` 会采信头部字段 —— 正是攻击面所在。
     */
    private fun forgedSizeZip(entryName: String, declaredSize: Long, payload: ByteArray): ByteArray {
        val nameBytes = entryName.toByteArray(Charsets.UTF_8)
        // nowrap = true：ZIP 用的是**裸 deflate**，不带 zlib 头（默认构造会加 zlib 包装，
        // 结果 ZipInputStream 报 "invalid stored block lengths"）
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED, true)
        deflater.setInput(payload)
        deflater.finish()
        val compressed = ByteArray(payload.size / 2 + 1024)
        val compressedLen = deflater.deflate(compressed)
        deflater.end()
        val deflated = compressed.copyOf(compressedLen)

        val out = ByteArrayOutputStream()
        val header = java.nio.ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x04034b50)
        header.putShort(20)          // version needed
        header.putShort(0)           // flags（无 data descriptor → 头部尺寸被采信）
        header.putShort(8)           // deflate
        header.putShort(0)           // mod time
        header.putShort(0)           // mod date
        header.putInt(0)             // crc32（流式读取不校验直到结束）
        header.putInt(deflated.size) // compressed size
        header.putInt(declaredSize.toInt()) // ← 谎报的 uncompressed size
        header.putShort(nameBytes.size.toShort())
        header.putShort(0)
        out.write(header.array())
        out.write(nameBytes)
        out.write(deflated)
        // 中央目录 + EOCD（ZipInputStream 顺序读取时并不需要，但补全以便结构完整）
        val cdStart = out.size()
        val cd = java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cd.putInt(0x02014b50)
        cd.putShort(20); cd.putShort(20); cd.putShort(0); cd.putShort(8)
        cd.putShort(0); cd.putShort(0); cd.putInt(0)
        cd.putInt(deflated.size); cd.putInt(declaredSize.toInt())
        cd.putShort(nameBytes.size.toShort())
        cd.putShort(0); cd.putShort(0); cd.putShort(0); cd.putShort(0); cd.putInt(0); cd.putInt(0)
        out.write(cd.array())
        out.write(nameBytes)
        val eocd = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054b50); eocd.putShort(0); eocd.putShort(0)
        eocd.putShort(1); eocd.putShort(1)
        eocd.putInt(cd.array().size + nameBytes.size)
        eocd.putInt(cdStart)
        eocd.putShort(0)
        out.write(eocd.array())
        return out.toByteArray()
    }

    private fun zip(entries: Map<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }
}
