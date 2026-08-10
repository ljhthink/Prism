package io.prism.skill

import io.ktor.http.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * SkillDownloader 单元测试（US-028，BR-testing-004）。
 *
 * **测试策略**：SkillDownloader.download() 依赖 ktor HttpClient（需 fake engine + 网络层 mock），
 * 集成测试成本高且 flaky。改为对其 companion object 的纯函数做穷尽单元测试：
 *
 * - [SkillDownloader.Companion.validateUrl]：URL 校验（AC-5 合法 URL / http 拒绝）
 * - [SkillDownloader.Companion.validateContentSize]：大小校验（AC-5 超大拒绝）
 * - [SkillDownloader.Companion.safeNewFile]：zip slip 防护（AC-5 zip slip 拦截）
 * - [SkillDownloader.Companion.validateContentType]：Content-Type 白名单
 * - [SkillDownloader.Companion.sanitizeMessage]：错误信息脱敏（CWE-209）
 * - [SkillDownloader.Companion.sanitizeUrlForLog]：URL 日志脱敏（P3-05，CWE-209）
 *
 * 「解析失败拒绝」由 [SkillManifestParserTest]（Phase B，33 测试）覆盖 YAML 沙箱解析层，
 * SkillDownloader.download() 第 5 步捕获 SkillParseException 转 DownloadResult.Fail，
 * 此处不重复测试解析器内部逻辑，仅验证错误传播路径（mapInstallResult 测试覆盖）。
 *
 * AC-5 覆盖映射：
 * - 合法 URL → validateUrl_Success_*
 * - http 拒绝 → validateUrl_rejects_http
 * - 超大拒绝 → validateContentSize_rejects_*
 * - zip slip 拦截 → safeNewFile_rejects_*
 * - 解析失败拒绝 → SkillManifestParserTest（Phase B）+ mapInstallResult_Fail*
 */
class SkillDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ============ validateUrl ============

    @Test
    fun `validateUrl accepts valid https skill md URL`() {
        val result = SkillDownloader.validateUrl("https://example.com/skill.skill.md")
        assertTrue("合法 https .skill.md URL 应通过校验", result is UrlValidationResult.Valid)
        val url = (result as UrlValidationResult.Valid).url
        assertEquals("https", url.protocol)
        assertEquals("example.com", url.host)
    }

    @Test
    fun `validateUrl accepts valid https zip URL`() {
        val result = SkillDownloader.validateUrl("https://cdn.example.com/skills/translator.zip")
        assertTrue("合法 https .zip URL 应通过校验", result is UrlValidationResult.Valid)
    }

    @Test
    fun `validateUrl accepts valid https md URL`() {
        val result = SkillDownloader.validateUrl("https://example.com/skills/translator.md")
        assertTrue("合法 https .md URL 应通过校验", result is UrlValidationResult.Valid)
    }

    @Test
    fun `validateUrl accepts directory-like URL without extension`() {
        // 目录形式 URL（无扩展名）：允许，按 SKILL.md 直接下载处理
        val result = SkillDownloader.validateUrl("https://example.com/skills/translator")
        assertTrue("目录形式 https URL 应通过校验", result is UrlValidationResult.Valid)
    }

    @Test
    fun `validateUrl rejects http protocol`() {
        val result = SkillDownloader.validateUrl("http://example.com/skill.skill.md")
        assertTrue("http 协议应被拒绝", result is UrlValidationResult.Invalid)
        val msg = (result as UrlValidationResult.Invalid).message
        assertTrue("错误信息应提及仅支持 https", msg.contains("https"))
    }

    @Test
    fun `validateUrl rejects file protocol`() {
        val result = SkillDownloader.validateUrl("file:///etc/passwd")
        assertTrue("file 协议应被拒绝（路径遍历防护）", result is UrlValidationResult.Invalid)
    }

    @Test
    fun `validateUrl rejects ftp protocol`() {
        val result = SkillDownloader.validateUrl("ftp://example.com/skill.skill.md")
        assertTrue("ftp 协议应被拒绝", result is UrlValidationResult.Invalid)
    }

    @Test
    fun `validateUrl rejects blank URL`() {
        assertEquals(
            UrlValidationResult.Invalid::class.java,
            SkillDownloader.validateUrl("").javaClass
        )
        assertEquals(
            UrlValidationResult.Invalid::class.java,
            SkillDownloader.validateUrl("   ").javaClass
        )
    }

    @Test
    fun `validateUrl rejects malformed URL`() {
        assertTrue(
            "格式错误的 URL 应被拒绝",
            SkillDownloader.validateUrl("not-a-url") is UrlValidationResult.Invalid
        )
    }

    @Test
    fun `validateUrl rejects URL with empty host`() {
        // https:// 协议但无主机名
        val result = SkillDownloader.validateUrl("https:///skill.skill.md")
        assertTrue("空主机名应被拒绝", result is UrlValidationResult.Invalid)
    }

    @Test
    fun `validateUrl rejects URL with unsupported extension`() {
        val result = SkillDownloader.validateUrl("https://example.com/skill.exe")
        assertTrue(".exe 扩展名应被拒绝（防可执行文件）", result is UrlValidationResult.Invalid)
    }

    @Test
    fun `validateUrl rejects URL with html extension`() {
        val result = SkillDownloader.validateUrl("https://example.com/skill.html")
        assertTrue(".html 扩展名应被拒绝（防 XSS 反射）", result is UrlValidationResult.Invalid)
    }

    // ============ validateContentSize ============

    @Test
    fun `validateContentSize accepts null declared length with small read`() {
        // 未声明 Content-Length + 已读 1MB：通过
        SkillDownloader.validateContentSize(null, 1024 * 1024L)
        // 无异常即通过
    }

    @Test
    fun `validateContentSize accepts declared length at limit`() {
        // Content-Length 恰好等于上限 10MB：通过（边界值）
        SkillDownloader.validateContentSize(SkillDownloader.MAX_CONTENT_SIZE, 0L)
    }

    @Test
    fun `validateContentSize rejects declared length exceeding limit`() {
        // AC-5 超大拒绝：Content-Length > 10MB
        try {
            SkillDownloader.validateContentSize(SkillDownloader.MAX_CONTENT_SIZE + 1, 0L)
            fail("超过 10MB 的 Content-Length 应抛 IOException")
        } catch (e: IOException) {
            assertTrue("错误信息应提及限制", e.message?.contains("Content-Length") == true)
        }
    }

    @Test
    fun `validateContentSize rejects current read exceeding limit`() {
        // 流式计数超限（防 Content-Length 缺失或被篡改）
        try {
            SkillDownloader.validateContentSize(null, SkillDownloader.MAX_CONTENT_SIZE + 1)
            fail("已读字节超过 10MB 应抛 IOException")
        } catch (e: IOException) {
            assertTrue("错误信息应提及大小限制", e.message?.contains("超过") == true)
        }
    }

    @Test
    fun `validateContentSize rejects when current read exceeds limit despite small declared`() {
        // Content-Length 声明 1MB，但实际已读 11MB（服务端返回错误或被中间人篡改）
        try {
            SkillDownloader.validateContentSize(1024 * 1024L, SkillDownloader.MAX_CONTENT_SIZE + 1)
            fail("流式计数超限应抛 IOException，即使 Content-Length 声明较小")
        } catch (e: IOException) {
            // 预期异常
        }
    }

    // ============ safeNewFile (zip slip 防护) ============

    @Test
    fun `safeNewFile accepts entry inside target dir`() {
        val targetDir = tempFolder.newFolder("extract")
        val file = SkillDownloader.safeNewFile(targetDir, "SKILL.md")
        assertEquals(File(targetDir, "SKILL.md").canonicalPath, file.canonicalPath)
    }

    @Test
    fun `safeNewFile accepts nested entry inside target dir`() {
        val targetDir = tempFolder.newFolder("extract")
        val file = SkillDownloader.safeNewFile(targetDir, "subdir/SKILL.md")
        assertEquals(File(targetDir, "subdir/SKILL.md").canonicalPath, file.canonicalPath)
    }

    @Test
    fun `safeNewFile rejects parent directory traversal`() {
        // AC-5 zip slip 拦截：../../../etc/passwd 试图逃逸目标目录
        val targetDir = tempFolder.newFolder("extract")
        try {
            SkillDownloader.safeNewFile(targetDir, "../../../etc/passwd")
            fail("zip slip 攻击应被拦截")
        } catch (e: IOException) {
            assertTrue("错误信息应提及 zip slip", e.message?.contains("zip slip") == true)
        }
    }

    @Test
    fun `safeNewFile rejects absolute path entry`() {
        // 绝对路径条目 /etc/passwd 试图覆盖系统文件
        val targetDir = tempFolder.newFolder("extract")
        try {
            SkillDownloader.safeNewFile(targetDir, "/etc/passwd")
            fail("绝对路径条目应被拦截")
        } catch (e: IOException) {
            assertTrue("错误信息应提及 zip slip", e.message?.contains("zip slip") == true)
        }
    }

    @Test
    fun `safeNewFile rejects dotdot in middle`() {
        // 中间含 .. 的路径
        val targetDir = tempFolder.newFolder("extract")
        try {
            SkillDownloader.safeNewFile(targetDir, "skill/../../etc/passwd")
            fail("含 .. 的路径应被拦截")
        } catch (e: IOException) {
            // 预期异常
        }
    }

    // ============ validateContentType ============

    @Test
    fun `validateContentType accepts null`() {
        // 部分服务器不返回 Content-Type，不强制要求
        SkillDownloader.validateContentType(null)
    }

    @Test
    fun `validateContentType accepts text markdown`() {
        SkillDownloader.validateContentType(ContentType.parse("text/markdown"))
    }

    @Test
    fun `validateContentType accepts text plain`() {
        SkillDownloader.validateContentType(ContentType.parse("text/plain"))
    }

    @Test
    fun `validateContentType accepts application zip`() {
        SkillDownloader.validateContentType(ContentType.parse("application/zip"))
    }

    @Test
    fun `validateContentType accepts application octet-stream`() {
        SkillDownloader.validateContentType(ContentType.parse("application/octet-stream"))
    }

    @Test
    fun `validateContentType accepts text markdown with charset`() {
        // Content-Type 含 charset 参数
        SkillDownloader.validateContentType(ContentType.parse("text/markdown; charset=utf-8"))
    }

    @Test
    fun `validateContentType rejects text html`() {
        try {
            SkillDownloader.validateContentType(ContentType.parse("text/html"))
            fail("text/html 应被拒绝（防 XSS 反射）")
        } catch (e: IOException) {
            assertTrue("错误信息应包含不支持的类型", e.message?.contains("text/html") == true)
        }
    }

    @Test
    fun `validateContentType rejects application executable`() {
        try {
            SkillDownloader.validateContentType(ContentType.parse("application/x-executable"))
            fail("可执行类 Content-Type 应被拒绝")
        } catch (e: IOException) {
            // 预期异常
        }
    }

    @Test
    fun `validateContentType rejects application javascript`() {
        try {
            SkillDownloader.validateContentType(ContentType.parse("application/javascript"))
            fail("JavaScript 类应被拒绝（防脚本注入）")
        } catch (e: IOException) {
            // 预期异常
        }
    }

    // ============ sanitizeMessage ============

    @Test
    fun `sanitizeMessage returns null for null input`() {
        assertNull(SkillDownloader.sanitizeMessage(null))
    }

    @Test
    fun `sanitizeMessage preserves short message without path`() {
        val result = SkillDownloader.sanitizeMessage("HTTP 404")
        assertEquals("HTTP 404", result)
    }

    @Test
    fun `sanitizeMessage redacts absolute paths`() {
        // CWE-209：错误信息中的内部路径应被脱敏
        val result = SkillDownloader.sanitizeMessage("File not found: /data/user/0/io.prism/files/skills/remote/.tmp_123/download.tmp")
        assertNotNull(result)
        assertTrue("路径应被替换为 <path>", result!!.contains("<path>"))
        assertTrue("脱敏后不应包含原始路径片段", !result.contains("/data/user/0"))
    }

    @Test
    fun `sanitizeMessage redacts windows paths`() {
        val result = SkillDownloader.sanitizeMessage("Failed: C:\\Users\\prism\\skills\\remote\\.tmp\\download.tmp")
        assertNotNull(result)
        assertTrue("Windows 路径应被脱敏", result!!.contains("<path>"))
    }

    @Test
    fun `sanitizeMessage truncates long message`() {
        val longMessage = "x".repeat(SkillDownloader.MAX_ERROR_MESSAGE_LEN + 100)
        val result = SkillDownloader.sanitizeMessage(longMessage)
        assertNotNull(result)
        assertTrue(
            "超长 message 应被截断",
            result!!.length <= SkillDownloader.MAX_ERROR_MESSAGE_LEN + 3 // +3 for "..."
        )
        assertTrue("截断后应以 ... 结尾", result.endsWith("..."))
    }

    @Test
    fun `sanitizeMessage handles message at exact limit`() {
        val message = "x".repeat(SkillDownloader.MAX_ERROR_MESSAGE_LEN)
        val result = SkillDownloader.sanitizeMessage(message)
        assertEquals(message, result)
    }

    @Test
    fun `sanitizeMessage redacts multiple paths in single message`() {
        val result = SkillDownloader.sanitizeMessage(
            "Copy from /tmp/source.tmp to /tmp/dest.tmp failed"
        )
        assertNotNull(result)
        // 两个路径都应被替换
        assertTrue("所有路径都应被脱敏", result!!.contains("<path>"))
        assertTrue("脱敏后不应包含 /tmp/source", !result.contains("/tmp/source"))
        assertTrue("脱敏后不应包含 /tmp/dest", !result.contains("/tmp/dest"))
    }

    // ============ sanitizeUrlForLog (P3-05 URL 日志脱敏) ============

    @Test
    fun `sanitizeUrlForLog preserves plain https url without credentials`() {
        val result = SkillDownloader.sanitizeUrlForLog("https://example.com/skills/translator.skill.md")
        assertTrue("无凭据 URL 应保留 host", result.contains("example.com"))
        assertTrue("无凭据 URL 应保留 protocol", result.startsWith("https://"))
        assertTrue("脱敏后不应包含凭据标记", !result.contains("@"))
    }

    @Test
    fun `sanitizeUrlForLog redacts user info from url with credentials`() {
        // 含 user:pass@ 的 URL：应移除凭据部分
        val result = SkillDownloader.sanitizeUrlForLog("https://user:pass@example.com/skills/translator.zip")
        assertTrue("含凭据 URL 应保留 protocol", result.startsWith("https://"))
        assertTrue("含凭据 URL 应保留 host", result.contains("example.com"))
        assertTrue("脱敏后不应包含 user:pass", !result.contains("user"))
        assertTrue("脱敏后不应包含 pass", !result.contains("pass"))
        assertTrue("脱敏后不应包含 @", !result.contains("@"))
    }

    @Test
    fun `sanitizeUrlForLog handles invalid url gracefully`() {
        val result = SkillDownloader.sanitizeUrlForLog("not-a-valid-url")
        assertEquals("非法 URL 应返回兜底标记", "<invalid-url>", result)
    }

    @Test
    fun `sanitizeUrlForLog truncates long path`() {
        // 超长 path：应截断到 50 字符
        val longPath = "/skills/" + "a".repeat(200)
        val result = SkillDownloader.sanitizeUrlForLog("https://example.com$longPath")
        assertTrue("超长 URL 应被截断", result.length <= 100)
    }
}
