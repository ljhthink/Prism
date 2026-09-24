package io.prism.pet.asset

import android.util.Log
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetAppearanceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 批次24（ADR-051 D4）：桌宠形象资产安装器 —— `.petpack` 安装 / 单图安装 / 列举 / 删除。
 *
 * **存储布局**：每个形象一个目录 `filesDir/pets/<sanitized-id>/`，目录内是**原样**的形象资产
 * （`pet.json` + 模型/图片文件树）。删除 = 整目录递归删除。
 *
 * **安装是"先验证后落位"的两阶段流程**（对齐 [io.prism.voice.rolepack.RolePackLoader] 的
 * "先解析校验再落盘"与 rules/07 §19.4 的 fail-fast）：
 * 1. 解压到 `baseDir/.staging-<uuid>/`（期间执行全部安全检查）；
 * 2. 校验 `pet.json` 与**资产引用完整性**（`model3.json` 里点名的每个文件都必须真实存在）；
 * 3. 校验通过才 `renameTo` 到最终目录。任何一步失败 → 删除 staging 目录并抛
 *   [PetPackException]，**目标目录不产生半成品**。
 *
 * **安全边界**（ADR-051 风险表）：
 * - 路径净化（[PetPackLimits.sanitizeRelativePath]）**先于**任何文件创建；
 * - `canonicalPath` 逃逸校验作为第二道防线（与 [io.prism.skill.SkillDownloader.safeNewFile] 同模式）；
 * - 条目数 / 单文件体积 / 总体积三重上限；
 * - 扩展名白名单——`.petpack` **不是**"往应用私有目录投递任意文件"的通道。
 *
 * **可测性**：[baseDir] 与 [ioDispatcher] 均为构造参数（生产传
 * `File(context.filesDir, "pets")` 与 [Dispatchers.IO]）。ioDispatcher 可注入是**必须**的——
 * 项目已有教训：`RolePackLoader` 硬编码 `Dispatchers.IO` 会让 `runTest` 的 `advanceUntilIdle()`
 * 提前返回、被测链路静默不执行（见 `ConversationViewModelRolePackWiringTest`）。
 */
class PetPackInstaller(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * 列举已安装形象（按显示名排序）。
     *
     * 单个形象目录解析失败（被外部改动 / `pet.json` 损坏）→ **跳过该目录**（列表页可用性优先，
     * 损坏项可由用户删除后重导）。
     */
    suspend fun list(): List<PetAppearance> = withContext(ioDispatcher) {
        appearanceDirs()
            .mapNotNull { dir ->
                runCatching { readInstalled(dir) }
                    .onFailure { Log.w(TAG, "skip malformed pet appearance dir=${dir.name}: ${it::class.simpleName}") }
                    .getOrNull()
            }
            .sortedBy { it.name }
    }

    /** 按 id 读取单个已安装形象；不存在或损坏返回 null。 */
    suspend fun find(id: String?): PetAppearance? = withContext(ioDispatcher) {
        val safe = id?.let { PetPackLimits.sanitizeAppearanceId(it) } ?: return@withContext null
        val dir = File(baseDir, safe)
        if (!dir.isDirectory) return@withContext null
        runCatching { readInstalled(dir) }
            .onFailure { Log.w(TAG, "pet appearance unreadable id=$safe: ${it::class.simpleName}") }
            .getOrNull()
    }

    /**
     * 安装 `.petpack`（ZIP）。
     *
     * @param source 资产流（调用方负责关闭；SAF `contentResolver.openInputStream` 的结果）
     * @return 安装完成的形象描述
     * @throws PetPackException 非 ZIP / 缺 `pet.json` / 清单畸形 / 引用文件缺失 / 超限 / 路径逃逸
     */
    suspend fun installPack(source: InputStream): PetAppearance = withContext(ioDispatcher) {
        val staging = newStagingDir()
        try {
            extractSafely(source, staging)
            val manifestFile = File(staging, PetPackParser.MANIFEST_NAME)
            if (!manifestFile.isFile) {
                throw PetPackException("形象包缺少 ${PetPackParser.MANIFEST_NAME}")
            }
            val manifest = PetPackParser.parse(manifestFile.readText())
            // 顺序有意为之：先用**原始**引用校验完整性（引用还是磁盘上的真实文件名），
            // 再把引用改写成 URL 安全形式（%23 / %E5%8A%A8…）——改写后引用不再等于文件名。
            verifyReferences(staging, manifest)
            urlEncodeModelReferences(staging, manifest)
            commit(staging, PetPackLimits.sanitizeAppearanceId(manifest.id))
            val appearance = PetPackParser.toAppearance(manifest, PetPackLimits.sanitizeAppearanceId(manifest.id))
            Log.i(
                TAG,
                "petpack installed: id=${appearance.id} kind=${appearance.kind} " +
                    "actions=${appearance.actions.size} idle=${appearance.idleAliases.size}"
            )
            appearance
        } catch (e: PetPackException) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw PetPackException("形象包安装失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    /**
     * 安装单张图片形象（用户直接选一张 PNG/JPG/WebP，不走 `.petpack`）。
     *
     * **为什么需要这条通道**：需求要求"用户自行上传角色形象"，而多数用户手上只有一张立绘
     * （ADR-051 D3）。该通道不引入任何额外的信任面——图片经同一套扩展名白名单与体积上限校验。
     *
     * @param displayName 显示名（取 SAF 文件名去掉扩展名）
     * @param extension 源文件扩展名（如 `png`），用于决定落盘文件名
     */
    suspend fun installImage(
        source: InputStream,
        displayName: String,
        extension: String
    ): PetAppearance = withContext(ioDispatcher) {
        val ext = extension.trim().lowercase()
        if (ext !in IMAGE_EXTENSIONS) {
            throw PetPackException("不支持的图片格式：.$ext（仅支持 png / jpg / jpeg / webp）")
        }
        val id = PetPackLimits.sanitizeAppearanceId(displayName)
        val staging = newStagingDir()
        try {
            val fileName = "portrait.$ext"
            val target = File(staging, fileName)
            writeLimited(source, target)
            val manifest = PetPackParser.singleImageManifest(id, displayName, fileName)
            verifyReferences(staging, manifest)
            // 清单必须落盘：list()/find() 以 pet.json 作为"这是一个形象"的判据，
            // 缺它会让目录被当作损坏项跳过（"导入成功但列表为空"的缺陷根因）
            File(staging, PetPackParser.MANIFEST_NAME)
                .writeText(PetPackParser.toManifestJson(manifest))
            commit(staging, id)
            Log.i(TAG, "pet image installed: id=$id ext=$ext")
            PetPackParser.toAppearance(manifest, id)
        } catch (e: PetPackException) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw PetPackException("图片导入失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    /**
     * 删除形象目录。
     *
     * @return 是否真的删除了目录（不存在返回 false）。**选中态的清理由调用方负责**
     *   （选中态存在 [io.prism.pet.model.PetConfig] 里，本类不依赖配置层，避免双向依赖）。
     */
    suspend fun remove(id: String): Boolean = withContext(ioDispatcher) {
        val safe = PetPackLimits.sanitizeAppearanceId(id)
        val dir = File(baseDir, safe)
        if (!dir.isDirectory) return@withContext false
        val removed = dir.deleteRecursively()
        Log.i(TAG, "pet appearance removed: id=$safe ok=$removed")
        removed
    }

    // ==================== 内部实现 ====================

    /** 已安装形象目录（过滤 staging 与隐藏目录）。 */
    private fun appearanceDirs(): List<File> =
        baseDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?: emptyList()

    private fun newStagingDir(): File {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw PetPackException("无法创建形象目录")
        }
        val dir = File(baseDir, ".staging-${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw PetPackException("无法创建导入暂存目录")
        return dir
    }

    /** 读取已安装目录 → 形象描述。`pet.json` 缺失/损坏即视为损坏项。 */
    private fun readInstalled(dir: File): PetAppearance {
        val manifestFile = File(dir, PetPackParser.MANIFEST_NAME)
        if (!manifestFile.isFile) throw IOException("missing manifest")
        val manifest = PetPackParser.parse(manifestFile.readText())
        return PetPackParser.toAppearance(manifest, dir.name)
    }

    /**
     * 安全解压：路径净化 + canonicalPath 二次校验 + 三重上限 + 扩展名白名单。
     *
     * 目录条目跳过（不信任 zip 内的目录结构，父目录按需创建）。
     */
    private fun extractSafely(source: InputStream, destDir: File) {
        var total = 0L
        var count = 0
        ZipInputStream(source.buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.isDirectory) {
                    entry = zis.nextEntry
                    continue
                }
                count++
                if (count > PetPackLimits.MAX_ENTRIES) {
                    throw PetPackException("形象包条目数超过上限（${PetPackLimits.MAX_ENTRIES}）")
                }
                val safePath = PetPackLimits.sanitizeRelativePath(entry.name)
                    ?: throw PetPackException("形象包含不合法路径条目，已拒绝导入")
                if (!PetPackLimits.extensionAllowed(safePath)) {
                    throw PetPackException("形象包含不支持的文件类型：${safePath.substringAfterLast('/')}")
                }
                val size = entry.size
                if (size > PetPackLimits.MAX_FILE_BYTES) {
                    throw PetPackException("形象包内单个文件超过上限（64MB）")
                }

                val target = safeNewFile(destDir, safePath)
                target.parentFile?.mkdirs()
                // 单文件上限**按实际写入字节**判定，不只信 zip 头声明的 size
                //（guardrail M-1：条目头可以把 size 写成 -1 或任意小值，
                //  而 deflate 流实际解出几十上百 MB —— 那是 zip bomb 的经典手法）。
                var entryBytes = 0L
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = zis.read(buffer)
                    while (read > 0) {
                        entryBytes += read
                        total += read
                        if (entryBytes > PetPackLimits.MAX_FILE_BYTES) {
                            throw PetPackException("形象包内单个文件超过上限（64MB）")
                        }
                        if (total > PetPackLimits.MAX_TOTAL_BYTES) {
                            throw PetPackException("形象包解压体积超过上限（256MB）")
                        }
                        out.write(buffer, 0, read)
                        read = zis.read(buffer)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (count == 0) throw PetPackException("形象包为空")
    }

    /** 单文件流式写入 + 体积上限（单图导入通道用）。 */
    private fun writeLimited(source: InputStream, target: File) {
        var total = 0L
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read = source.read(buffer)
            while (read > 0) {
                total += read
                if (total > PetPackLimits.MAX_FILE_BYTES) {
                    throw PetPackException("图片超过体积上限（64MB）")
                }
                out.write(buffer, 0, read)
                read = source.read(buffer)
            }
        }
        if (total == 0L) throw PetPackException("图片内容为空")
    }

    /**
     * zip slip 防护第二道防线（与 [io.prism.skill.SkillDownloader.safeNewFile] 同模式）。
     *
     * 第一道防线是 [PetPackLimits.sanitizeRelativePath]（白名单式字符与段校验）；
     * 本方法用真实文件系统的 canonicalPath 兜底已知/未知的编码变体。
     */
    @Throws(IOException::class)
    private fun safeNewFile(targetDir: File, entryName: String): File {
        val targetFile = File(targetDir, entryName)
        val canonicalTarget = targetFile.canonicalPath
        val canonicalDir = targetDir.canonicalPath + File.separator
        if (!canonicalTarget.startsWith(canonicalDir)) {
            throw PetPackException("形象包含越权路径条目，已拒绝导入")
        }
        return targetFile
    }

    /**
     * 资产引用完整性校验（PRD US-2402 AC-4）。
     *
     * **为什么必须做**：Cubism 资产是一棵**引用树**（`model3.json` → moc3/纹理/physics/motions/expressions）。
     * 缺一个文件时，渲染层的失败信息是 WebGL/JS 的底层异常（对用户不可读），
     * 而**导入期**我们手上有完整信息可以给出精确的"缺哪个文件"。把可读性做在导入期，是廉价的。
     */
    private fun verifyReferences(dir: File, manifest: PetManifest) {
        when (manifest.kind) {
            PetAppearanceKind.IMAGE -> {
                manifest.images.forEach { rel ->
                    if (!File(dir, rel).isFile) throw PetPackException("形象包缺少图片文件：$rel")
                }
            }
            PetAppearanceKind.LIVE2D -> {
                val modelRel = manifest.model
                    ?: throw PetPackException("live2d 形象缺少 model 声明")
                val modelFile = File(dir, modelRel)
                if (!modelFile.isFile) throw PetPackException("形象包缺少模型文件：$modelRel")
                val missing = PetPackParser.collectModelReferences(modelFile.readText())
                    .filter { !File(dir, it).isFile }
                if (missing.isNotEmpty()) {
                    throw PetPackException("形象包缺少模型引用的文件：${missing.first()}")
                }
            }
        }
    }

    /**
     * 把 `model3.json` 的文件引用改写为 URL 安全形式（见 [PetPackParser.encodeModelReferences]）。
     *
     * **失败不阻断安装**：改写不了只是"某些含特殊字符的 motion 取不到"，
     * 让形象整体装不上是更差的结果；改写函数自身在解析失败时也会原样返回。
     */
    private fun urlEncodeModelReferences(dir: File, manifest: PetManifest) {
        if (manifest.kind != PetAppearanceKind.LIVE2D) return
        val rel = manifest.model ?: return
        val modelFile = File(dir, rel)
        if (!modelFile.isFile) return
        runCatching {
            val encoded = PetPackParser.encodeModelReferences(modelFile.readText())
            if (encoded != modelFile.readText()) modelFile.writeText(encoded)
        }.onFailure { Log.w(TAG, "model reference url-encode skipped: ${it::class.simpleName}") }
    }

    /** staging 目录 → 最终目录（同 id 重复导入 = 覆盖更新，幂等）。 */
    private fun commit(staging: File, id: String) {
        val target = File(baseDir, id)
        if (target.exists() && !target.deleteRecursively()) {
            throw PetPackException("无法覆盖已存在的形象目录")
        }
        if (!staging.renameTo(target)) {
            // renameTo 在跨卷/被占用时可能失败：退化为"复制后删除"，保证语义一致
            staging.copyRecursively(target, overwrite = true)
            if (!target.isDirectory) throw PetPackException("形象目录落位失败")
            staging.deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "PetPackInstaller"

        /** 解压/写入缓冲大小（64KB，与项目既有下载链一致量级）。 */
        private const val BUFFER_SIZE = 64 * 1024

        /** 单图通道允许的图片扩展名。 */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
