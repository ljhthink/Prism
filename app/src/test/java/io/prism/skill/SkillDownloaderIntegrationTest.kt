package io.prism.skill

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SkillDownloader MockEngine 集成测试（US-028，AC-1~AC-6，P3-06 补强）。
 *
 * **测试目标**：覆盖 [SkillDownloader.download] 主流程（HTTP 请求 → 流式下载 → 内容校验 →
 * YAML 解析 → 原子安装 → 错误传播），补强纯函数单元测试（42 测试）未覆盖的集成路径。
 *
 * **测试策略**（test-architect skill 集成测试方法）：
 * - 使用 [MockEngine] 注入 fake HTTP 响应，隔离真实网络依赖
 * - 使用 [TemporaryFolder] 创建临时安装目录，测试后自动清理
 * - 使用 [UnconfinedTestDispatcher] 确保协程在当前线程立即执行
 *
 * **覆盖矩阵**：
 *
 * | 场景分类 | 测试方法 | AC |
 * | --- | --- | --- |
 * | 成功路径 | download valid skill md / zip with dir entry / zip with resources / md URL / creates remote dir | AC-1,2,4 |
 * | HTTP 错误码 | 404 / 500 / 403 | AC-1,5 |
 * | Content-Type 拒绝 | text/html / application/javascript | AC-1,5 |
 * | Content-Type 允许 | octet-stream / text/plain / null | AC-1 |
 * | 大小超限 | Content-Length 超限 / 流式计数超限 | AC-1,5 |
 * | 空内容 | empty content | AC-2,5 |
 * | ZIP 安全校验 | zip slip / absolute path / no SKILL.md / too many entries | AC-2,5 |
 * | YAML 解析失败 | no frontmatter / missing name / invalid slug / malformed YAML | AC-3,5 |
 * | URL 校验 | http / blank / unsupported extension | AC-1,5 |
 * | 原子安装 | overwrite existing / cleanup on failure | AC-4 |
 *
 * **AC-5 覆盖映射**：
 * - 合法 URL → download valid skill md succeeds
 * - http 拒绝 → download rejects http URL at download layer
 * - 超大拒绝 → download rejects Content-Length exceeding 10MB / streaming content exceeding 10MB
 * - zip slip 拦截 → download rejects zip slip attack / absolute path entry
 * - 解析失败拒绝 → download rejects SKILL md without frontmatter / missing name / invalid slug / malformed YAML
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillDownloaderIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    /** 合法的 SKILL.md 内容（最小必填字段） */
    private val validSkillMd = """
        ---
        name: test-skill
        description: A test skill for integration testing
        ---
        # Test Skill Body
    """.trimIndent().toByteArray()

    // ============ 辅助函数 ============

    /**
     * 创建 SkillDownloader with MockEngine 注入 fake HTTP 响应。
     *
     * @param content 响应体字节数组
     * @param status HTTP 状态码（默认 200）
     * @param contentType Content-Type 头（默认 text/markdown）
     * @param contentLength Content-Length 头（null 表示不设置）
     * @param expectSuccess 是否启用 expectSuccess（默认 true，与生产环境一致）
     */
    private fun createDownloader(
        content: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: String = "text/markdown",
        contentLength: Long? = null,
        expectSuccess: Boolean = true
    ): SkillDownloader {
        val headerPairs = mutableListOf(HttpHeaders.ContentType to listOf(contentType))
        if (contentLength != null) {
            headerPairs.add(HttpHeaders.ContentLength to listOf(contentLength.toString()))
        }
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(content),
                status = status,
                headers = headersOf(*headerPairs.toTypedArray())
            )
        }
        val client = HttpClient(engine) {
            this.expectSuccess = expectSuccess
            install(HttpTimeout)
        }
        return SkillDownloader(client, testDispatcher)
    }

    /**
     * 创建 ZIP 字节数组。
     *
     * @param entries (entryName, content?) 列表，content 为 null 表示目录条目
     */
    private fun createZip(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                if (content != null) {
                    zos.write(content)
                }
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    // ================================================================
    // 成功路径 (AC-1, AC-2, AC-4, AC-5)
    // ================================================================

    @Test
    fun `download valid skill md succeeds and installs to remote dir`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals("test-skill", success.slug)
        assertEquals("A test skill for integration testing", success.manifest.description)
        assertTrue("skillDir should exist", success.skillDir.exists())
        assertTrue("SKILL.md should exist in skillDir", File(success.skillDir, "SKILL.md").exists())
        // 验证临时目录已被清理
        val tmpDirs = remoteDir.listFiles { f -> f.name.startsWith(".tmp_") }
        assertTrue("Temp dirs should be cleaned up", tmpDirs.isNullOrEmpty())
    }

    @Test
    fun `download valid zip with directory entry succeeds (P1-01 regression)`() = runTest(testDispatcher) {
        // P1-01 回归：构造含显式目录条目的标准 ZIP，验证 extractZipSafely 正确解压。
        // 首次审查 P1-01 bug：目录条目双重 nextEntry 跳过后续文件条目。
        val zipContent = createZip(
            "test-skill/" to null,
            "test-skill/SKILL.md" to validSkillMd
        )
        val downloader = createDownloader(zipContent, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals("test-skill", success.slug)
        assertEquals("A test skill for integration testing", success.manifest.description)
        // P1-01 核心验证：文件条目未被跳过（目录条目不再双重 nextEntry）
        // 注意：ZIP 含子目录时，findSkillMd 在一级子目录中找到 SKILL.md，
        // tmpDir 整体重命名为 finalDir 后，SKILL.md 在 skillDir/{subdir}/SKILL.md
        val skillMd = success.skillDir.walk().find { it.name.equals("SKILL.md", ignoreCase = true) }
        assertTrue("SKILL.md should exist somewhere in skillDir (P1-01: file entry not skipped)", skillMd != null)
        val skillMdContent = skillMd!!.readText()
        assertTrue("SKILL.md content should contain name", skillMdContent.contains("name: test-skill"))
    }

    @Test
    fun `download valid zip with root SKILL md succeeds`() = runTest(testDispatcher) {
        // ZIP 根目录直接包含 SKILL.md（无子目录）
        val zipContent = createZip("SKILL.md" to validSkillMd)
        val downloader = createDownloader(zipContent, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
    }

    @Test
    fun `download zip with extra resource files succeeds`() = runTest(testDispatcher) {
        // ZIP 包含 SKILL.md + 额外资源文件（验证完整解压）
        val zipContent = createZip(
            "test-skill/" to null,
            "test-skill/SKILL.md" to validSkillMd,
            "test-skill/resources/guide.md" to "# Guide\nStep by step".toByteArray(),
            "test-skill/templates/example.txt" to "template content".toByteArray()
        )
        val downloader = createDownloader(zipContent, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals("test-skill", success.slug)
        // 验证文件条目被完整解压（P1-01 回归：目录条目不再跳过后续文件）
        val skillMd = success.skillDir.walk().find { it.name.equals("SKILL.md", ignoreCase = true) }
        assertTrue("SKILL.md should exist", skillMd != null)
        val guide = success.skillDir.walk().find { it.name == "guide.md" }
        assertTrue("Resource file should exist", guide != null)
        val template = success.skillDir.walk().find { it.name == "example.txt" }
        assertTrue("Template file should exist", template != null)
    }

    @Test
    fun `download md URL succeeds`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd, contentType = "text/markdown")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/skills/test.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertTrue(File(success.skillDir, "SKILL.md").exists())
    }

    @Test
    fun `download creates remote dir if not exists`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd)
        val remoteDir = File(tempFolder.root, "nonexistent-remote")
        assertFalse("Remote dir should not exist initially", remoteDir.exists())

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
        assertTrue("Remote dir should be created", remoteDir.exists())
    }

    // ================================================================
    // Content-Type 允许场景 (AC-1)
    // ================================================================

    @Test
    fun `download accepts application octet-stream content type`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd, contentType = "application/octet-stream")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
    }

    @Test
    fun `download accepts text plain content type`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd, contentType = "text/plain")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
    }

    @Test
    fun `download accepts null content type (no header)`() = runTest(testDispatcher) {
        // 不设置 Content-Type 头（部分服务器不返回）
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(validSkillMd),
                status = HttpStatusCode.OK,
                headers = headersOf()
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(HttpTimeout)
        }
        val downloader = SkillDownloader(client, testDispatcher)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
    }

    @Test
    fun `download accepts content type with charset parameter`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd, contentType = "text/markdown; charset=utf-8")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)
    }

    // ================================================================
    // HTTP 错误码拒绝 (AC-1, AC-5)
    // ================================================================

    @Test
    fun `download rejects HTTP 404`() = runTest(testDispatcher) {
        val downloader = createDownloader(
            "Not Found".toByteArray(),
            status = HttpStatusCode.NotFound
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        assertTrue("Message should mention download failure", fail.message.contains("下载失败"))
    }

    @Test
    fun `download rejects HTTP 500`() = runTest(testDispatcher) {
        val downloader = createDownloader(
            "Internal Server Error".toByteArray(),
            status = HttpStatusCode.InternalServerError
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects HTTP 403`() = runTest(testDispatcher) {
        val downloader = createDownloader(
            "Forbidden".toByteArray(),
            status = HttpStatusCode.Forbidden
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // Content-Type 拒绝 (AC-1, AC-5)
    // ================================================================

    @Test
    fun `download rejects text html content type`() = runTest(testDispatcher) {
        val downloader = createDownloader(
            "<html><body>XSS</body></html>".toByteArray(),
            contentType = "text/html"
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects application javascript content type`() = runTest(testDispatcher) {
        val downloader = createDownloader(
            "alert(1)".toByteArray(),
            contentType = "application/javascript"
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects application x-executable content type`() = runTest(testDispatcher) {
        val downloader = createDownloader(
            ByteArray(4) { 0x7f },
            contentType = "application/x-executable"
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // 大小超限拒绝 (AC-1, AC-5)
    // ================================================================

    @Test
    fun `download rejects Content-Length exceeding 10MB`() = runTest(testDispatcher) {
        // Content-Length 声明 > 10MB，实际内容很小（downloadToTmp 第 2 步校验拦截）
        val downloader = createDownloader(
            "small content".toByteArray(),
            contentLength = SkillDownloader.MAX_CONTENT_SIZE + 1
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        assertTrue("Message should mention download failure", fail.message.contains("下载失败"))
    }

    @Test
    fun `download rejects streaming content exceeding 10MB`() = runTest(testDispatcher) {
        // 无 Content-Length 声明，实际内容 > 10MB（downloadToTmp 流式计数拦截）
        val largeContent = ByteArray(SkillDownloader.MAX_CONTENT_SIZE.toInt() + 1024) { 'x'.code.toByte() }
        val downloader = createDownloader(largeContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // 空内容拒绝 (AC-2, AC-5)
    // ================================================================

    @Test
    fun `download rejects empty content`() = runTest(testDispatcher) {
        val downloader = createDownloader(ByteArray(0))
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // ZIP 安全校验 (AC-2, AC-5)
    // ================================================================

    @Test
    fun `download rejects zip slip attack with parent traversal`() = runTest(testDispatcher) {
        // 恶意 ZIP：包含 ../../../etc/passwd 条目（CWE-22）
        val maliciousZip = createZip(
            "../../../etc/passwd" to "malicious".toByteArray(),
            "SKILL.md" to validSkillMd
        )
        val downloader = createDownloader(maliciousZip, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        assertTrue("Message should mention content validation failure", fail.message.contains("内容校验失败"))
        // 验证临时目录已清理
        val tmpDirs = remoteDir.listFiles { f -> f.name.startsWith(".tmp_") }
        assertTrue("Temp dirs should be cleaned up after failure", tmpDirs.isNullOrEmpty())
    }

    @Test
    fun `download rejects zip slip attack with absolute path entry`() = runTest(testDispatcher) {
        // 绝对路径条目：/etc/passwd
        val maliciousZip = createZip(
            "/etc/passwd" to "malicious".toByteArray(),
            "SKILL.md" to validSkillMd
        )
        val downloader = createDownloader(maliciousZip, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects zip slip with dotdot in middle`() = runTest(testDispatcher) {
        // 中间含 .. 的路径：skill/../../etc/passwd
        val maliciousZip = createZip(
            "skill/../../etc/passwd" to "malicious".toByteArray(),
            "SKILL.md" to validSkillMd
        )
        val downloader = createDownloader(maliciousZip, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects zip without SKILL md`() = runTest(testDispatcher) {
        val zipContent = createZip(
            "README.md" to "readme content".toByteArray(),
            "config.yaml" to "key: value".toByteArray()
        )
        val downloader = createDownloader(zipContent, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects zip with too many entries (zip bomb protection)`() = runTest(testDispatcher) {
        // 构造 > 1000 条目的 ZIP（CWE-400 zip bomb 防护）
        val entries = mutableListOf<Pair<String, ByteArray?>>()
        for (i in 1..1001) {
            entries.add("file_$i.txt" to "x".toByteArray())
        }
        entries.add("SKILL.md" to validSkillMd)
        val zipContent = createZip(*entries.toTypedArray())
        val downloader = createDownloader(zipContent, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // YAML 解析失败拒绝 (AC-3, AC-5)
    // ================================================================

    @Test
    fun `download rejects SKILL md without frontmatter`() = runTest(testDispatcher) {
        val invalidContent = "This is just plain text without YAML frontmatter".toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        assertTrue(
            "Message should mention parse or read failure",
            fail.message.contains("解析失败") || fail.message.contains("读取失败")
        )
    }

    @Test
    fun `download rejects SKILL md with missing required name field`() = runTest(testDispatcher) {
        val invalidContent = """
            ---
            description: Missing name field
            ---
            Body
        """.trimIndent().toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects SKILL md with missing required description field`() = runTest(testDispatcher) {
        val invalidContent = """
            ---
            name: test-skill
            ---
            Body
        """.trimIndent().toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects SKILL md with invalid name slug`() = runTest(testDispatcher) {
        // name 包含大写字母和空格，不符合 slug 正则 ^[a-z0-9-]{1,64}$
        val invalidContent = """
            ---
            name: Invalid Name!
            description: Invalid slug
            ---
            Body
        """.trimIndent().toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects SKILL md with malformed YAML`() = runTest(testDispatcher) {
        val invalidContent = """
            ---
            name: [unclosed bracket
            description: test
            ---
            Body
        """.trimIndent().toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // URL 校验（download 层）(AC-1, AC-5)
    // ================================================================

    @Test
    fun `download rejects http URL at download layer`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("http://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        assertTrue("Message should mention https", fail.message.contains("https"))
    }

    @Test
    fun `download rejects blank URL at download layer`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects unsupported extension URL`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.exe", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    @Test
    fun `download rejects file protocol URL`() = runTest(testDispatcher) {
        val downloader = createDownloader(validSkillMd)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("file:///etc/passwd", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
    }

    // ================================================================
    // 原子安装（覆盖安装 + 清理）(AC-4)
    // ================================================================

    @Test
    fun `download overwrites existing skill with same slug`() = runTest(testDispatcher) {
        val remoteDir = tempFolder.newFolder("remote")

        // 第一次安装
        val downloader1 = createDownloader(validSkillMd)
        val result1 = downloader1.download("https://example.com/test-skill.skill.md", remoteDir)
        assertTrue("First install should succeed", result1 is DownloadResult.Success)
        val skillDir = (result1 as DownloadResult.Success).skillDir
        val originalContent = File(skillDir, "SKILL.md").readText()
        assertTrue("Original should have 'test-skill'", originalContent.contains("test-skill"))

        // 第二次安装同名 Skill（不同内容）
        val updatedContent = """
            ---
            name: test-skill
            description: Updated description
            ---
            # Updated body
        """.trimIndent().toByteArray()
        val downloader2 = createDownloader(updatedContent)
        val result2 = downloader2.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Second install should succeed", result2 is DownloadResult.Success)
        val success2 = result2 as DownloadResult.Success
        assertEquals("test-skill", success2.slug)
        // 验证内容已更新
        val newContent = File(success2.skillDir, "SKILL.md").readText()
        assertTrue("Content should be updated", newContent.contains("Updated description"))
        // 验证旧备份已清理
        val bakDirs = remoteDir.listFiles { f -> f.name.startsWith(".bak_") }
        assertTrue("Backup dirs should be cleaned up", bakDirs.isNullOrEmpty())
    }

    @Test
    fun `download cleans up temp dir on content validation failure`() = runTest(testDispatcher) {
        val invalidContent = "no frontmatter".toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val tmpDirs = remoteDir.listFiles { f -> f.name.startsWith(".tmp_") }
        assertTrue("Temp dirs should be cleaned up", tmpDirs.isNullOrEmpty())
    }

    @Test
    fun `download cleans up temp dir on zip slip failure`() = runTest(testDispatcher) {
        val maliciousZip = createZip("../../../etc/passwd" to "malicious".toByteArray())
        val downloader = createDownloader(maliciousZip, contentType = "application/zip")
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.zip", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val tmpDirs = remoteDir.listFiles { f -> f.name.startsWith(".tmp_") }
        assertTrue("Temp dirs should be cleaned up after zip slip", tmpDirs.isNullOrEmpty())
    }

    @Test
    fun `download cleans up temp dir on YAML parse failure`() = runTest(testDispatcher) {
        val invalidContent = """
            ---
            name: [unclosed
            ---
            Body
        """.trimIndent().toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val tmpDirs = remoteDir.listFiles { f -> f.name.startsWith(".tmp_") }
        assertTrue("Temp dirs should be cleaned up after parse failure", tmpDirs.isNullOrEmpty())
    }

    // ================================================================
    // 错误信息脱敏验证 (CWE-209, AC-5)
    // ================================================================

    @Test
    fun `download failure message does not leak internal file paths`() = runTest(testDispatcher) {
        // 触发内容校验失败，验证错误信息不泄露内部路径
        val invalidContent = "no frontmatter here".toByteArray()
        val downloader = createDownloader(invalidContent)
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download("https://example.com/test-skill.skill.md", remoteDir)

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        // 错误信息不应包含 .tmp_ 路径或 download.tmp
        assertFalse("Message should not leak temp dir path: ${fail.message}",
            fail.message.contains(".tmp_") || fail.message.contains("download.tmp"))
    }

    @Test
    fun `download failure message does not leak URL credentials`() = runTest(testDispatcher) {
        // 使用含凭据的 URL，验证错误信息不泄露凭据
        val downloader = createDownloader(
            "Not Found".toByteArray(),
            status = HttpStatusCode.NotFound
        )
        val remoteDir = tempFolder.newFolder("remote")

        val result = downloader.download(
            "https://user:secret@example.com/test-skill.skill.md",
            remoteDir
        )

        assertTrue("Expected Fail, got $result", result is DownloadResult.Fail)
        val fail = result as DownloadResult.Fail
        assertFalse("Message should not contain credentials: ${fail.message}",
            fail.message.contains("secret") || fail.message.contains("user:secret"))
    }
}
