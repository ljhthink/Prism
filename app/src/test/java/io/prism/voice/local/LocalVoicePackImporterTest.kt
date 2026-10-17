package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TKN-LVC-016（ADR-057）：`.voicepack` 应用内导入单测（PRD AC-5）。
 *
 * 本类是本项目**唯一**一条"把不可信归档写进应用私有目录"路径的守卫清单，故逐条覆盖：
 * - 正常导入（成功 / 落点 / 临时目录清理）
 * - Zip-Slip 四种形态（`..` / 绝对路径 / 反斜杠 / 盘符）
 * - 资源上限（条目数 / 单条目 / 总量 / 压缩比）
 * - 空间预检
 * - 覆盖语义（默认拒绝 + 显式覆盖 + 覆盖失败回滚）
 * - 失败不留半包（`.import-*`、`.bak-*` 全部清干净）
 * - 校验复用（缺模型文件 ⇒ 失败，而不是"导入了但不可用"）
 */
class LocalVoicePackImporterTest {

    private fun tempRoot(name: String = "voicepack-import"): File =
        Files.createTempDirectory(name).toFile()

    /** 写一个 zip；[entries] 允许注入任意条目名（含恶意路径）。 */
    private fun zip(
        dir: File,
        name: String = "pack.voicepack",
        entries: List<Pair<String, ByteArray>>
    ): File {
        val out = File(dir, name)
        ZipOutputStream(out.outputStream()).use { zos ->
            entries.forEach { (entryName, bytes) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out
    }

    private fun manifest(
        voiceId: String = "cyrene-vits",
        tts: String = "vits",
        name: String = "测试音色"
    ): ByteArray = """
        {
          "schema": "${LocalVoicePackStore.SCHEMA}",
          "voiceId": "$voiceId",
          "name": "$name",
          "tts": "$tts",
          "model": {
            "model": "model/model.onnx",
            "lexicon": "model/lexicon.txt",
            "tokens": "model/tokens.txt"
          },
          "generation": {"speed": 0.8},
          "references": []
        }
    """.trimIndent().toByteArray()

    /** 一个能通过 [LocalVoicePackStore] 全部校验的最小 VITS 包。 */
    private fun validEntries(voiceId: String = "cyrene-vits"): List<Pair<String, ByteArray>> = listOf(
        "voicepack.json" to manifest(voiceId = voiceId),
        "model/model.onnx" to ByteArray(64) { 1 },
        "model/lexicon.txt" to ByteArray(16) { 1 },
        "model/tokens.txt" to ByteArray(16) { 1 }
    )

    private fun importer(
        targetRoot: File,
        store: LocalVoicePackStore? = null,
        limits: LocalVoicePackImporter.Limits = LocalVoicePackImporter.Limits(),
        free: Long = Long.MAX_VALUE
    ) = LocalVoicePackImporter(
        store = store ?: LocalVoicePackStore(roots = listOf(targetRoot)),
        targetRoot = targetRoot,
        uuid = { "fixed" },
        usableSpace = { free },
        limits = limits
    )

    // ==================== 正常路径 ====================

    @Test
    fun `合法归档 - 导入成功且落在 targetRoot 下且不留临时目录`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries())

        val result = importer(root).importBlocking(src, overwrite = false)

        assertTrue("期望成功，实际 $result", result is LocalVoicePackImporter.Result.Success)
        result as LocalVoicePackImporter.Result.Success
        assertEquals("cyrene-vits", result.voiceId)
        assertEquals("测试音色", result.name)
        assertFalse(result.replaced)
        assertTrue("包目录必须存在", File(root, "cyrene-vits/voicepack.json").isFile)
        assertTrue("模型文件必须落盘", File(root, "cyrene-vits/model/model.onnx").isFile)
        // 幂等性检查的第一条：临时目录不许残留（残留会被下次同名冲突撞上）
        assertFalse(
            "不得残留 .import-* 目录",
            root.listFiles().orEmpty().any { it.name.startsWith(LocalVoicePackImporter.TMP_PREFIX) }
        )
    }

    @Test
    fun `导入后 store 能把它判为就绪（落点与校验口径一致）`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries())
        importer(root).importBlocking(src, overwrite = false)

        val status = LocalVoicePackStore(roots = listOf(root)).resolve("cyrene-vits")
        assertTrue("导入的包必须能被 store 认出来，实际 $status", status is LocalVoiceStatus.Ready)
    }

    @Test
    fun `peekVoiceId 只读清单不改磁盘`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries(voiceId = "cyrene-x"))

        assertEquals("cyrene-x", importer(root).peekVoiceId(src))
        assertEquals("peek 不得落盘", emptyList<String>(), root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `非 zip 文件 - 失败且不留痕`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val bad = File(dir, "bad.voicepack").apply { writeBytes(ByteArray(128) { 7 }) }

        val result = importer(root).importBlocking(bad, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertEquals(
            LocalVoicePackImporter.MSG_NOT_A_PACK,
            (result as LocalVoicePackImporter.Result.Failure).reason
        )
        assertTrue("失败不得留下任何子目录", root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `空文件 - 失败`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val empty = File(dir, "empty.voicepack").apply { writeBytes(ByteArray(0)) }
        assertTrue(importer(root).importBlocking(empty, overwrite = false) is LocalVoicePackImporter.Result.Failure)
    }

    // ==================== Zip-Slip（安全核心） ====================

    @Test
    fun `zip-slip - 上级目录逃逸必须被拒绝且不写出任何文件`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val escapeTarget = File(dir, "PWNED.txt")
        val src = zip(
            dir,
            entries = validEntries() + ("../PWNED.txt" to "pwned".toByteArray())
        )

        val result = importer(root).importBlocking(src, overwrite = false)

        assertTrue("含 .. 条目必须整体失败，实际 $result", result is LocalVoicePackImporter.Result.Failure)
        assertFalse("逃逸文件绝不能被写出", escapeTarget.exists())
        assertTrue("失败不得留下临时目录", root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `扫描清理 - 只删 import 与 bak 前缀，正式包目录不受影响（F-4）`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        File(root, "${LocalVoicePackImporter.TMP_PREFIX}abc/model").mkdirs()
        File(root, "${LocalVoicePackImporter.BAK_PREFIX}xyz/model").mkdirs()
        File(root, "cyrene-vits").mkdirs()
        File(root, "cyrene-vits/voicepack.json").writeText("{}")
        // 相似但不带前缀的目录必须保留（前缀判定不能写成"包含"）
        File(root, "import-keep").mkdirs()
        File(root, "bak-keep").mkdirs()

        val removed = importer(root).sweepStale()

        assertEquals("只应清理两个前缀目录", 2, removed)
        assertFalse(File(root, "${LocalVoicePackImporter.TMP_PREFIX}abc").exists())
        assertFalse(File(root, "${LocalVoicePackImporter.BAK_PREFIX}xyz").exists())
        assertTrue("正式包不得被清扫掉", File(root, "cyrene-vits/voicepack.json").isFile)
        assertTrue(File(root, "import-keep").isDirectory)
        assertTrue(File(root, "bak-keep").isDirectory)
    }

    @Test
    fun `有界读取 - 超出上限返回 null 而非截断（F-3：声明尺寸不可信）`() {
        val ok = ByteArray(1024)
        assertEquals(
            "恰好等于上限必须成功",
            1024,
            LocalVoicePackImporter.readBounded(ok.inputStream(), 1024L)!!.size
        )
        assertNull(
            "超过上限必须判失败（截断会让'归档在撒谎'伪装成'JSON 解析失败'）",
            LocalVoicePackImporter.readBounded(ByteArray(1025).inputStream(), 1024L)
        )
        // 分块边界：远超上限的流也必须被挡住，而不是先读完再判
        assertNull(
            "远超上限同样必须被挡住",
            LocalVoicePackImporter.readBounded(ByteArray(4 * 1024 * 1024).inputStream(), 1024L)
        )
    }

    @Test
    fun `zip-slip - 绝对路径与反斜杠与盘符条目都必须被拒绝`() {
        listOf("/abs.txt", "\\abs.txt", "..\\win.txt", "C:/drive.txt", "a\\b.txt").forEach { evil ->
            val dir = tempRoot()
            val root = File(dir, "voicepack").apply { mkdirs() }
            val src = zip(dir, entries = validEntries() + (evil to "x".toByteArray()))

            val result = importer(root).importBlocking(src, overwrite = false)

            assertTrue("条目「$evil」必须被拒绝，实际 $result", result is LocalVoicePackImporter.Result.Failure)
            assertTrue("条目「$evil」失败后不得留下临时目录", root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `zip-slip - 深层隐藏的逃逸段也必须被拒绝`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries() + ("model/../../escape.txt" to "x".toByteArray()))

        assertTrue(importer(root).importBlocking(src, overwrite = false) is LocalVoicePackImporter.Result.Failure)
        assertFalse(File(dir, "escape.txt").exists())
    }

    // ==================== 资源上限 ====================

    @Test
    fun `条目数超上限必须被拒绝`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        // 用合法包 + 若干多余小文件逼近上限（只调小 maxEntries，其余上限保持默认）
        val extra = (1..5).map { "model/extra_$it.txt" to ByteArray(4) }
        val src = zip(dir, entries = validEntries() + extra)

        val result = importer(root, limits = LocalVoicePackImporter.Limits(maxEntries = 4))
            .importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue((result as LocalVoicePackImporter.Result.Failure).reason.contains("条目过多"))
    }

    @Test
    fun `单条目超上限必须被拒绝`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries())

        val result = importer(root, limits = LocalVoicePackImporter.Limits(maxEntryBytes = 32))
            .importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue((result as LocalVoicePackImporter.Result.Failure).reason.contains("单个文件过大"))
    }

    @Test
    fun `解压总量超上限必须被拒绝`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries())

        val result = importer(root, limits = LocalVoicePackImporter.Limits(maxTotalBytes = 32))
            .importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue((result as LocalVoicePackImporter.Result.Failure).reason.contains("解压总量超上限"))
    }

    @Test
    fun `压缩比异常（解压炸弹）必须被拒绝`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        // 1MB 全零 → 压缩后极小，压缩比远超阈值
        val src = zip(dir, entries = validEntries() + ("model/bomb.bin" to ByteArray(1 shl 20)))

        val result = importer(root, limits = LocalVoicePackImporter.Limits(maxCompressionRatio = 5))
            .importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue((result as LocalVoicePackImporter.Result.Failure).reason.contains("压缩比异常"))
    }

    @Test
    fun `空间不足必须被拒绝且不写出任何内容`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries())

        val result = importer(root, free = 0L).importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue((result as LocalVoicePackImporter.Result.Failure).reason.contains("存储空间不足"))
        assertTrue("空间不足时不得留下临时目录", root.listFiles().orEmpty().isEmpty())
    }

    // ==================== 校验与失败清理 ====================

    @Test
    fun `缺清单 - 失败`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries().filterNot { it.first == "voicepack.json" })

        val result = importer(root).importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue("失败不得留下临时目录", root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `清单 voiceId 非法 - 失败（防用清单值拼出越界目录名）`() {
        listOf("../evil", "a/b", "", "x".repeat(65)).forEach { badId ->
            val dir = tempRoot()
            val root = File(dir, "voicepack").apply { mkdirs() }
            val src = zip(dir, entries = validEntries(voiceId = badId))

            val result = importer(root).importBlocking(src, overwrite = false)

            assertTrue("voiceId=「$badId」必须失败，实际 $result", result is LocalVoicePackImporter.Result.Failure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `模型文件缺失 - 失败（不得产出不可用包）`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries().filterNot { it.first == "model/model.onnx" })

        val result = importer(root).importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertFalse("不可用包绝不能出现在落点", File(root, "cyrene-vits").exists())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `畸形清单 JSON - 失败`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(
            dir,
            entries = listOf("voicepack.json" to "{ this is not json".toByteArray())
        )

        val result = importer(root).importBlocking(src, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    // ==================== 覆盖语义 ====================

    @Test
    fun `同名已存在且未确认覆盖 - 失败且旧包逐字节不变`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val first = zip(dir, "first.voicepack", entries = validEntries())
        assertTrue(
            importer(root).importBlocking(first, overwrite = false) is LocalVoicePackImporter.Result.Success
        )
        val oldBytes = File(root, "cyrene-vits/model/model.onnx").readBytes()

        val second = zip(
            dir,
            "second.voicepack",
            entries = listOf(
                "voicepack.json" to manifest(name = "新名字"),
                "model/model.onnx" to ByteArray(64) { 9 },
                "model/lexicon.txt" to ByteArray(16) { 9 },
                "model/tokens.txt" to ByteArray(16) { 9 }
            )
        )
        val result = importer(root).importBlocking(second, overwrite = false)

        assertTrue(result is LocalVoicePackImporter.Result.Failure)
        assertTrue((result as LocalVoicePackImporter.Result.Failure).reason.contains("已存在同名音色包"))
        assertArrayEquals("旧包内容不得被改动", oldBytes, File(root, "cyrene-vits/model/model.onnx").readBytes())
        assertTrue("不得残留临时/备份目录", root.listFiles().orEmpty().none { it.name.startsWith(".") })
    }

    @Test
    fun `显式覆盖 - 新包生效且旧包与备份目录全部清理`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        importer(root).importBlocking(
            zip(dir, "first.voicepack", entries = validEntries()),
            overwrite = false
        )
        val second = zip(
            dir,
            "second.voicepack",
            entries = listOf(
                "voicepack.json" to manifest(name = "新名字"),
                "model/model.onnx" to ByteArray(64) { 9 },
                "model/lexicon.txt" to ByteArray(16) { 9 },
                "model/tokens.txt" to ByteArray(16) { 9 }
            )
        )

        val result = importer(root).importBlocking(second, overwrite = true)

        assertTrue("期望成功，实际 $result", result is LocalVoicePackImporter.Result.Success)
        assertTrue((result as LocalVoicePackImporter.Result.Success).replaced)
        assertEquals(9.toByte(), File(root, "cyrene-vits/model/model.onnx").readBytes()[0])
        val leftovers = root.listFiles().orEmpty().map { it.name }
        assertEquals("落点下有且只有包目录，不得有 .bak-*/.import-*", listOf("cyrene-vits"), leftovers)
    }

    @Test
    fun `覆盖后包仍能被 store 判为就绪`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        importer(root).importBlocking(zip(dir, "a.voicepack", entries = validEntries()), false)
        importer(root).importBlocking(zip(dir, "b.voicepack", entries = validEntries()), true)

        val status = LocalVoicePackStore(roots = listOf(root)).resolve("cyrene-vits")
        assertTrue("覆盖后必须仍是 Ready，实际 $status", status is LocalVoiceStatus.Ready)
    }

    // ==================== 校验器复用（同一口径） ====================

    @Test
    fun `declaredVoiceId 与 resolveAtDirectory 使用同一个 voiceId 口径`() {
        val dir = tempRoot()
        val root = File(dir, "voicepack").apply { mkdirs() }
        val src = zip(dir, entries = validEntries(voiceId = "cyrene-vits"))
        importer(root).importBlocking(src, overwrite = false)

        val store = LocalVoicePackStore(roots = listOf(root))
        assertEquals("cyrene-vits", store.declaredVoiceId(File(root, "cyrene-vits")))
        assertNull("目录不存在时不得猜出一个 id", store.declaredVoiceId(File(root, "nope")))
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(message, expected, actual)
    }
}
