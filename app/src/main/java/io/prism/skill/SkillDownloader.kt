package io.prism.skill

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 远程 Skill 下载器（US-028，ADR-013 5.6）。
 *
 * **职责**：从 HTTPS URL 下载远程 Skill（`.skill.md` 单文件或 `.zip` 打包），
 * 经多层安全校验后安装到 `filesDir/skills/remote/{slug}/`，供 [SkillRegistry] 扫描加载。
 *
 * **安全策略**（ADR-013 5.6，用户决策「标准校验」）：
 *
 * | 层级 | 校验 | 实现 |
 * | --- | --- | --- |
 * | 1. URL | 协议白名单（仅 https）+ 主机名非空 + 路径扩展名 | [Companion.validateUrl] |
 * | 2. HTTP 响应 | 状态码 2xx + Content-Length ≤ [MAX_CONTENT_SIZE] + Content-Type 白名单 | [downloadToTmp] |
 * | 3. 流式计数 | 实时累计已读字节，超限即抛 | [downloadToTmp] |
 * | 4. 内容校验 | `.zip` 解压 + zip slip 防护 + 总解压大小限制；`.skill.md`/`.md` 直接读取 | [validateContent] |
 * | 5. YAML 沙箱 | [SkillManifestParser.parse]（BR-security-004，禁任意类构造 + 递归深度限制） | [download] |
 * | 6. slug 校验 | manifest.name 必须匹配 `^[a-z0-9-]{1,64}$`（由 Parser 保证） | [SkillManifestParser] |
 * | 7. 原子安装 | 下载到临时目录，校验通过后 renameTo 最终目录（backup-then-swap 模式） | [download] |
 * | 8. 超时防护 | 下载请求 30s 超时（[HttpTimeout] 插件，安装在专用 downloadHttpClient） | [downloadToTmp] |
 * | 9. 重定向降级防护 | Ktor `HttpRedirect` 插件默认 `allowHttpsDowngrade=false` 拦截 https→http 降级 + 响应级 URL 协议二次校验（纵深防御，P2-03，CWE-918） | [downloadToTmp] |
 *
 * **失败处理**：任一层级校验失败返回 [DownloadResult.Fail]，自动清理临时目录，
 * 不污染 `filesDir/skills/remote/`。错误信息经 [sanitizeMessage] 脱敏（CWE-209），
 * 不泄露内部路径/堆栈给 UI。
 *
 * **可测性**（BR-testing-004）：
 * - 构造器不依赖 Android [android.content.Context]，`remoteSkillsDir` 由调用方注入
 * - 纯逻辑提取到 [companion object] 标记 `internal`：
 *   - [Companion.validateUrl]：URL 校验
 *   - [Companion.safeNewFile]：zip slip 防护
 *   - [Companion.validateContentType]：Content-Type 校验
 *   - [Companion.validateContentSize]：Content-Length / 流式计数校验
 *   - [Companion.sanitizeMessage]：错误信息脱敏
 * - 集成测试用 [HttpClient] 配合 ktor-client-mock 注入 fake engine
 *
 * @paramHttpClient Ktor HttpClient（复用 [io.prism.PrismApplication.httpClient]，OkHttp engine + SSE）
 * @param ioDispatcher IO 协程调度器（可注入便于测试）
 */
class SkillDownloader(
    private val httpClient: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * 下载并安装远程 Skill。
     *
     * **流程**（ADR-013 5.6）：
     * 1. URL 校验（[Companion.validateUrl]）
     * 2. 创建临时目录 `remoteSkillsDir/.tmp_{timestamp}/`
     * 3. HTTP 流式下载到临时文件（[downloadToTmp]）
     * 4. 内容校验（[validateContent]）：`.zip` 解压 + zip slip 防护 / `.skill.md` 直接读取
     * 5. YAML 沙箱解析（[SkillManifestParser.parse]）
     * 6. 原子重命名临时目录 → `remoteSkillsDir/{manifest.name}/`
     *
     * **失败清理**：任一步骤失败均清理临时目录，返回 [DownloadResult.Fail]。
     *
     * @param url 远程 Skill URL（必须 https，扩展名 .skill.md / .zip / .md）
     * @param remoteSkillsDir 安装根目录（`filesDir/skills/remote/`，由调用方注入）
     * @return [DownloadResult.Success]（含 slug + manifest + skillDir）或 [DownloadResult.Fail]（含脱敏 message）
     */
    suspend fun download(url: String, remoteSkillsDir: File): DownloadResult = withContext(ioDispatcher) {
        // 1. URL 校验
        val urlValidation = Companion.validateUrl(url)
        if (urlValidation !is UrlValidationResult.Valid) {
            return@withContext DownloadResult.Fail(
                (urlValidation as UrlValidationResult.Invalid).message
            )
        }

        // 2. 准备安装根目录 + 临时目录
        if (!remoteSkillsDir.exists()) remoteSkillsDir.mkdirs()
        val tmpDir = File(remoteSkillsDir, ".tmp_${System.currentTimeMillis()}")
        if (!tmpDir.mkdirs()) {
            return@withContext DownloadResult.Fail("临时目录创建失败")
        }

        try {
            // 3. HTTP 下载到临时文件
            val downloadedFile = try {
                downloadToTmp(urlValidation.url, tmpDir)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download failed: ${Companion.sanitizeUrlForLog(url)}", e)
                return@withContext DownloadResult.Fail(
                    "下载失败: ${Companion.sanitizeMessage(e.message) ?: "未知错误"}"
                )
            }

            // 4. 内容校验（解压 or 直接读取）→ 定位 SKILL.md
            val skillMdFile = try {
                validateContent(downloadedFile, tmpDir, urlValidation.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "content validation failed", e)
                return@withContext DownloadResult.Fail(
                    "内容校验失败: ${Companion.sanitizeMessage(e.message) ?: "未知错误"}"
                )
            }

            // 5. YAML 沙箱解析
            val parseResult = try {
                SkillManifestParser.parse(skillMdFile.readText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SkillParseException) {
                android.util.Log.w(TAG, "SKILL.md parse failed", e)
                return@withContext DownloadResult.Fail(
                    "SKILL.md 解析失败: ${Companion.sanitizeMessage(e.message) ?: "未知错误"}"
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "SKILL.md read failed", e)
                return@withContext DownloadResult.Fail(
                    "SKILL.md 读取失败: ${Companion.sanitizeMessage(e.message) ?: "未知错误"}"
                )
            }

            // 6. 原子安装：backup-then-swap 模式（防 rename 失败导致旧 Skill 丢失，P2-02 修复）
            val slug = parseResult.manifest.name
            val finalDir = File(remoteSkillsDir, slug)
            val backupDir = File(remoteSkillsDir, ".bak_${slug}_${System.currentTimeMillis()}")
            // 同名 Skill 已存在：先重命名为备份（而非直接删除），rename 失败可回滚
            if (finalDir.exists()) {
                if (!finalDir.renameTo(backupDir)) {
                    return@withContext DownloadResult.Fail("旧版本备份失败，请重试")
                }
            }
            // 临时目录 → 最终目录
            if (!tmpDir.renameTo(finalDir)) {
                // rename 失败：回滚（恢复备份）
                // R2-02 修复：与 P3-02 保持一致，使用 try-catch(Exception) 而非 runCatching
                if (backupDir.exists()) {
                    try {
                        backupDir.renameTo(finalDir)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "backup rollback failed: ${backupDir.name}", e)
                    }
                }
                return@withContext DownloadResult.Fail("安装目录创建失败，请重试")
            }
            // 成功后删除备份
            if (backupDir.exists()) {
                try {
                    backupDir.deleteRecursively()
                } catch (e: Exception) {
                    // 备份清理失败不影响安装结果（孤儿备份目录可由后续扫描清理），仅记录日志
                    android.util.Log.w(TAG, "backup cleanup failed: ${backupDir.name}", e)
                }
            }

            android.util.Log.i(
                TAG,
                "remote skill installed: slug=$slug, dir=${finalDir.name}"
            )
            DownloadResult.Success(slug, parseResult.manifest, finalDir)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：协程取消必须重抛
        } catch (e: Exception) {
            // 兜底（不应到达，但防御性处理）
            android.util.Log.e(TAG, "unexpected install failure", e)
            DownloadResult.Fail("安装失败: ${Companion.sanitizeMessage(e.message) ?: "未知错误"}")
        } finally {
            // 临时目录若仍存在（失败路径或 renameTo 后残留），清理
            // P3-02 修复：catch Exception 而非 runCatching（避免吞 Throwable，BR-error-handling-007 精神）
            if (tmpDir.exists()) {
                try {
                    tmpDir.deleteRecursively()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "tmpDir cleanup failed: ${tmpDir.name}", e)
                }
            }
        }
    }

    /**
     * HTTP 流式下载到临时文件（[Companion.MAX_CONTENT_SIZE] 限制）。
     *
     * **校验层级**：
     * 1. 最终 URL 协议校验（P2-03 修复，CWE-918）：拒绝重定向降级到非 https
     * 2. HTTP 状态码 2xx
     * 3. Content-Length（若存在）≤ [Companion.MAX_CONTENT_SIZE]
     * 4. Content-Type 白名单（[Companion.validateContentType]）
     * 5. 流式计数实时累计，超限即抛 IOException
     *
     * **依赖**：httpClient 必须安装 [HttpTimeout] 插件（由 [io.prism.PrismApplication.downloadHttpClient] 保证）。
     *
     * @param url 已校验的 URL
     * @param tmpDir 临时目录
     * @return 下载的临时文件（命名 `download.tmp`）
     */
    private suspend fun downloadToTmp(url: URL, tmpDir: File): File {
        val tmpFile = File(tmpDir, "download.tmp")

        httpClient.prepareGet(url.toString()) {
            timeout {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
        }.execute { response ->
            // 0. 最终 URL 协议校验（P2-03 纵深防御，CWE-918）
            //    主防护层：Ktor HttpRedirect 插件默认 allowHttpsDowngrade=false，拦截 https→http
            //    降级重定向（降级请求不会发出，返回 3xx 响应）。
            //    此处为纵深防御兜底：校验最终响应 URL 协议，防 httpClient 配置变更或插件卸载。
            val finalProtocol = response.call.request.url.protocol.name
            if (finalProtocol != "https") {
                throw IOException("重定向降级到非 https 协议 ($finalProtocol)")
            }

            // 1. 状态码校验（纵深防御：httpClient.expectSuccess=true 已拦截非 2xx，
            //    此处兜底确保即使 expectSuccess 配置变更也能正确拒绝）
            if (!response.status.isSuccess()) {
                throw IOException("HTTP ${response.status.value}")
            }

            // 2. Content-Length 校验（响应头声明的大小）
            val declaredLength = response.contentLength()
            Companion.validateContentSize(declaredLength, 0L)

            // 3. Content-Type 校验
            val contentType = response.contentType()
            Companion.validateContentType(contentType)

            // 4. 流式下载 + 实时计数（防 Content-Length 缺失或被篡改）
            val channel: ByteReadChannel = response.bodyAsChannel()
            var totalRead = 0L
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    totalRead += read
                    Companion.validateContentSize(declaredLength, totalRead)
                    output.write(buffer, 0, read)
                }
            }
            if (totalRead == 0L) {
                throw IOException("下载内容为空")
            }
        }
        return tmpFile
    }

    /**
     * 内容校验：根据 URL 扩展名决定处理方式，定位 SKILL.md 文件。
     *
     * - `.zip`：解压到临时目录 + zip slip 防护 + 校验含 SKILL.md
     * - `.skill.md` / `.md`：直接作为 SKILL.md（重命名）
     *
     * @param downloadedFile 下载的临时文件
     * @param tmpDir 临时目录（zip 解压目标）
     * @param url 原始 URL（用于判断扩展名）
     * @return 定位到的 SKILL.md 文件
     * @throws IOException 校验失败（zip slip / 无 SKILL.md / 解压大小超限）
     */
    private fun validateContent(downloadedFile: File, tmpDir: File, url: URL): File {
        val path = url.path.lowercase()
        return when {
            path.endsWith(".zip") -> {
                // 删除原始 zip（解压后不再需要）
                extractZipSafely(downloadedFile, tmpDir)
                downloadedFile.delete()
                findSkillMd(tmpDir)
                    ?: throw IOException("ZIP 包内未找到 SKILL.md")
            }
            path.endsWith(".skill.md") || path.endsWith(".md") -> {
                // 重命名下载文件为 SKILL.md
                val skillMd = File(tmpDir, "SKILL.md")
                if (!downloadedFile.renameTo(skillMd)) {
                    throw IOException("SKILL.md 文件创建失败")
                }
                skillMd
            }
            else -> {
                // URL 无明确扩展名（目录形式）：尝试作为 SKILL.md 直接读取
                val skillMd = File(tmpDir, "SKILL.md")
                if (!downloadedFile.renameTo(skillMd)) {
                    throw IOException("SKILL.md 文件创建失败")
                }
                skillMd
            }
        }
    }

    /**
     * 安全解压 ZIP 到目标目录（zip slip 防护 + 总大小限制 + 条目数限制）。
     *
     * **zip slip 防护**（CWE-22，Android 官方推荐）：
     * 对每个 ZipEntry，用 [Companion.safeNewFile] 校验 canonicalPath 不逃逸目标目录。
     *
     * **zip bomb 防护**（CWE-400，双维度）：
     * 1. 累计解压总大小，超过 [Companion.MAX_EXTRACTED_SIZE] 抛 IOException
     * 2. 条目数计数，超过 [Companion.MAX_ENTRY_COUNT] 抛 IOException（防大量空文件耗 inode）
     *
     * @param zipFile ZIP 文件
     * @param destDir 解压目标目录
     * @throws IOException zip slip 检测 / 解压超限 / 条目数超限 / ZIP 格式错误
     */
    private fun extractZipSafely(zipFile: File, destDir: File) {
        var totalExtracted = 0L
        var entryCount = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                // 条目数限制（防 zip bomb 大量小文件，CWE-400）
                entryCount++
                if (entryCount > Companion.MAX_ENTRY_COUNT) {
                    throw IOException("ZIP 条目数超过限制 (${Companion.MAX_ENTRY_COUNT})")
                }

                // 跳过目录条目（仅创建目录，不写内容；nextEntry 已隐式关闭当前 entry）
                if (entry.isDirectory) {
                    val dirFile = Companion.safeNewFile(destDir, entry.name)
                    dirFile.mkdirs()
                    entry = zis.nextEntry
                    continue
                }

                val targetFile = Companion.safeNewFile(destDir, entry.name)
                // 确保父目录存在
                targetFile.parentFile?.mkdirs()

                // 流式写入 + 累计大小（防 zip bomb 解压膨胀）
                targetFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var read = zis.read(buffer)
                    while (read > 0) {
                        totalExtracted += read
                        if (totalExtracted > Companion.MAX_EXTRACTED_SIZE) {
                            throw IOException("解压总大小超过限制 (${Companion.MAX_EXTRACTED_SIZE} bytes)")
                        }
                        output.write(buffer, 0, read)
                        read = zis.read(buffer)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 在目录中递归查找 SKILL.md（不区分大小写，支持 ZIP 根目录或一级子目录）。
     *
     * @param dir 搜索根目录
     * @return 找到的 SKILL.md 文件，未找到返回 null
     */
    private fun findSkillMd(dir: File): File? {
        // 优先根目录
        val rootSkillMd = dir.listFiles()?.find {
            it.isFile && it.name.equals("SKILL.md", ignoreCase = true)
        }
        if (rootSkillMd != null) return rootSkillMd

        // 一级子目录（ZIP 内常见结构：{skill-name}/SKILL.md）
        val subDirs = dir.listFiles { f -> f.isDirectory } ?: return null
        for (subDir in subDirs) {
            val subSkillMd = subDir.listFiles()?.find {
                it.isFile && it.name.equals("SKILL.md", ignoreCase = true)
            }
            if (subSkillMd != null) return subSkillMd
        }
        return null
    }

    companion object {
        private const val TAG = "SkillDownloader"

        /** 下载超时（30s，对齐 ADR-013 5.6）。 */
        internal const val DOWNLOAD_TIMEOUT_MS = 30_000L

        /** 连接超时（10s）。 */
        internal const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * 单次下载内容大小上限（10MB，ADR-013 5.6）。
         *
         * SKILL.md 通常 <100KB，10MB 覆盖含资源（图片/示例文件）的 ZIP 包。
         */
        internal const val MAX_CONTENT_SIZE = 10L * 1024 * 1024

        /**
         * ZIP 解压后总大小上限（50MB，防 zip bomb）。
         *
         * 压缩比通常 2-10x，10MB zip 解压最多 ~100MB，50MB 为合理上限。
         */
        internal const val MAX_EXTRACTED_SIZE = 50L * 1024 * 1024

        /**
         * ZIP 条目数上限（1000，防 zip bomb 大量空文件，CWE-400）。
         *
         * 10MB ZIP 最多可包含约 10 万个空文件（每条目最小开销 ~100 字节），
         * 在 IO 线程上逐一创建大量空文件会消耗 inode 和时间。
         * 1000 条目足够覆盖含资源（图片/示例文件）的 Skill ZIP 包。
         */
        internal const val MAX_ENTRY_COUNT = 1000

        /** 流式下载/解压缓冲区大小（8KB）。 */
        internal const val DOWNLOAD_BUFFER_SIZE = 8 * 1024

        /**
         * 错误信息截断长度（CWE-209 信息泄露纵深防御，对齐 SkillExecutor MAX_ERROR_MESSAGE_LEN）。
         */
        internal const val MAX_ERROR_MESSAGE_LEN = 200

        /**
         * 文件路径正则（脱敏）：匹配以 `/` 或 `\` 开头的路径片段，替换为 `<path>`。
         */
        private val pathPattern = Regex("""[/\\][^\s"'<>]+""")

        /**
         * 校验 URL（纯函数，BR-testing-004）。
         *
         * **校验项**：
         * 1. URL 格式合法（可解析）
         * 2. 协议为 https（拒绝 http/file/ftp）
         * 3. 主机名非空
         * 4. 路径扩展名为 .skill.md / .zip / .md（或无路径/目录形式，按 SKILL.md 处理）
         *
         * @param url 原始 URL 字符串
         * @return [UrlValidationResult.Valid]（含解析后的 [URL]）或 [UrlValidationResult.Invalid]
         */
        internal fun validateUrl(url: String): UrlValidationResult {
            if (url.isBlank()) {
                return UrlValidationResult.Invalid("URL 不能为空")
            }
            val parsed = try {
                URL(url)
            } catch (e: Exception) {
                return UrlValidationResult.Invalid("URL 格式错误")
            }
            if (parsed.protocol.lowercase() != "https") {
                return UrlValidationResult.Invalid("仅支持 https 协议（当前: ${parsed.protocol}）")
            }
            if (parsed.host.isBlank()) {
                return UrlValidationResult.Invalid("URL 主机名不能为空")
            }
            val path = parsed.path.lowercase()
            val hasValidExtension = VALID_EXTENSIONS.any { path.endsWith(it) }
            // 无路径 / 目录形式 / 无扩展名：允许，按 SKILL.md 直接下载处理
            val isDirectoryLike = path.isBlank() || path.endsWith("/") || !path.contains(".")
            if (!hasValidExtension && !isDirectoryLike) {
                return UrlValidationResult.Invalid(
                    "URL 路径必须以 ${VALID_EXTENSIONS.joinToString(" / ")} 结尾，或为目录形式"
                )
            }
            return UrlValidationResult.Valid(parsed)
        }

        /**
         * 允许的 URL 路径扩展名。
         */
        private val VALID_EXTENSIONS = listOf(".skill.md", ".zip", ".md")

        /**
         * 校验 Content-Type（纯函数，BR-testing-004）。
         *
         * 允许的 Content-Type：
         * - `text/markdown`（.skill.md / .md）
         * - `application/zip`（.zip）
         * - `application/octet-stream`（通用二进制，部分 CDN 使用）
         * - `text/plain`（部分服务器对 .md 返回 text/plain）
         * - null（不强制要求，部分服务器不返回 Content-Type）
         *
         * 拒绝：可执行类（application/x-msdownload、application/x-executable、text/html 等）
         *
         * @param contentType Ktor [ContentType]，可为 null
         * @throws IOException Content-Type 不在白名单
         */
        internal fun validateContentType(contentType: ContentType?) {
            if (contentType == null) return // 不强制要求
            val mime = contentType.toString().lowercase()
            val isValid = ALLOWED_CONTENT_TYPES.any { allowed ->
                mime == allowed || mime.startsWith("$allowed;")
            }
            if (!isValid) {
                throw IOException("不支持的 Content-Type: $mime")
            }
        }

        /**
         * 允许的 Content-Type MIME 前缀。
         */
        private val ALLOWED_CONTENT_TYPES = listOf(
            "text/markdown",
            "text/plain",
            "application/zip",
            "application/octet-stream",
            "application/x-zip-compressed"
        )

        /**
         * 校验内容大小（纯函数，BR-testing-004）。
         *
         * @param declaredLength Content-Length 头声明的总大小（null 表示未声明）
         * @param currentRead 当前已读字节数（流式计数）
         * @throws IOException 超过 [MAX_CONTENT_SIZE]
         */
        internal fun validateContentSize(declaredLength: Long?, currentRead: Long) {
            if (declaredLength != null && declaredLength > MAX_CONTENT_SIZE) {
                throw IOException("Content-Length 超过限制 ($declaredLength > $MAX_CONTENT_SIZE)")
            }
            if (currentRead > MAX_CONTENT_SIZE) {
                throw IOException("下载内容超过大小限制 ($currentRead > $MAX_CONTENT_SIZE)")
            }
        }

        /**
         * zip slip 防护（纯函数，CWE-22，Android 官方推荐模式）。
         *
         * 校验 ZipEntry 解压后的 canonicalPath 不逃逸目标目录。
         *
         * **攻击场景**：恶意 ZIP 含 `../../../etc/passwd` 条目，naive 解压会写入目标目录之外。
         *
         * **跨平台一致性**：
         * - 显式拒绝以 `/` 或 `\` 开头的条目名（ZIP 标准用正斜杠，绝对路径条目应在所有平台被拦截；
         *   Windows 上 `File(targetDir, "/etc/passwd")` 会将 `/etc/passwd` 视为相对路径，
         *   仅靠 canonicalPath 校验无法拦截，故显式检查前缀）
         * - canonicalPath 校验作为第二道防线，拦截 `../` 路径遍历
         *
         * @param targetDir 解压目标目录
         * @param entryName ZipEntry 名称（可能含路径分隔符 / `../`）
         * @return 校验通过的目标 [File]
         * @throws IOException 绝对路径条目 / canonicalPath 逃逸目标目录（zip slip 检测命中）
         */
        @Throws(IOException::class)
        internal fun safeNewFile(targetDir: File, entryName: String): File {
            // 第一道防线：拒绝绝对路径条目（跨平台一致，ZIP 标准用正斜杠）
            if (entryName.startsWith("/") || entryName.startsWith("\\")) {
                throw IOException("zip slip 防护：条目 '$entryName' 为绝对路径")
            }
            // 第二道防线：canonicalPath 不逃逸目标目录（拦截 ../ 路径遍历）
            val targetFile = File(targetDir, entryName)
            val canonicalTarget = targetFile.canonicalPath
            val canonicalDir = targetDir.canonicalPath + File.separator
            if (!canonicalTarget.startsWith(canonicalDir)) {
                throw IOException("zip slip 防护：条目 '$entryName' 试图逃逸目标目录")
            }
            return targetFile
        }

        /**
         * 错误信息脱敏（纯函数，CWE-209，对齐 [SkillExecutor.sanitizeErrorMessage]）。
         *
         * 1. null → null（调用方回退通用文案）
         * 2. 长度截断（≤ [MAX_ERROR_MESSAGE_LEN]）
         * 3. 路径脱敏（`/xxx/yyy` → `<path>`）
         *
         * @param raw 原始 message
         * @return 脱敏后的 message，或 null
         */
        internal fun sanitizeMessage(raw: String?): String? {
            if (raw == null) return null
            val truncated = if (raw.length > MAX_ERROR_MESSAGE_LEN) {
                raw.take(MAX_ERROR_MESSAGE_LEN) + "..."
            } else {
                raw
            }
            return pathPattern.replace(truncated, "<path>")
        }

        /**
         * URL 日志脱敏（P3-05 修复，CWE-209）。
         *
         * 移除 URL 中的 userInfo（`user:pass@`）部分，防凭据泄露到 logcat。
         * 含凭据时仅保留 `protocol://host`；无凭据时保留 `protocol://host/path`（path 截断防过长）。
         *
         * @param url 原始 URL 字符串
         * @return 脱敏后的 URL 字符串（可安全输出到日志）
         */
        internal fun sanitizeUrlForLog(url: String): String {
            return try {
                val parsed = URL(url)
                if (parsed.userInfo != null) {
                    // 含凭据：仅记录 protocol://host，不泄露 path/query（可能含 presigned 签名）
                    "${parsed.protocol}://${parsed.host}"
                } else {
                    // 无凭据：记录 protocol://host/path（截断防过长）
                    "${parsed.protocol}://${parsed.host}${parsed.path.take(50)}".take(100)
                }
            } catch (e: Exception) {
                "<invalid-url>"
            }
        }
    }
}

/**
 * 下载结果（sealed，ADR-013 5.6）。
 *
 * - [Success]：安装成功，含 slug + manifest + skillDir
 * - [Fail]：安装失败，含脱敏后的 message（不泄露内部路径/堆栈）
 */
sealed interface DownloadResult {
    /**
     * @param slug Skill slug（manifest.name，已校验 `^[a-z0-9-]{1,64}$`）
     * @param manifest 解析后的 SkillManifest
     * @param skillDir 安装目录（`remoteSkillsDir/{slug}/`，含 SKILL.md + 资源）
     */
    data class Success(
        val slug: String,
        val manifest: SkillManifest,
        val skillDir: File
    ) : DownloadResult

    /**
     * @param message 脱敏后的错误信息（CWE-209，可展示给用户）
     */
    data class Fail(val message: String) : DownloadResult
}

/**
 * URL 校验结果（[SkillDownloader.validateUrl] 返回值）。
 *
 * 提升为顶层 sealed interface（原嵌套在 companion object 内部，外部测试无法访问）。
 *
 * - [Valid]：URL 合法，含解析后的 [URL]
 * - [Invalid]：URL 不合法，含面向用户的错误信息
 */
sealed interface UrlValidationResult {
    data class Valid(val url: URL) : UrlValidationResult
    data class Invalid(val message: String) : UrlValidationResult
}
