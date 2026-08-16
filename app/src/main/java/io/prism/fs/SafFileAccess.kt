package io.prism.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.prism.fs.FileEntry
import io.prism.fs.FileSystemAccess
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 基于 Storage Access Framework（SAF）的生产文件访问实现（ADR-006 5.3）。
 *
 * 用户经 `ACTION_OPEN_DOCUMENT_TREE` 选择目录并持久化授权后，通过 [addRoot] 将逻辑目录名
 * 映射到 SAF 树 URI。所有工具参数使用逻辑路径（如 `notes/readme.md`），此处将首段解析为树，
 * 后续段在树内逐级 [DocumentFile.findFile] 导航。
 *
 * **线程安全（BR-concurrency-002）**：[roots] 以 [MutableStateFlow] 原子快照作为唯一事实源，
 * [addRoot]/[removeRoot] 经 [MutableStateFlow.update] 原子更新，读路径读取 `.value` 快照，
 * 消除主线程写 / IO 线程读的裸容器竞争。
 *
 * **路径防御（S2）**：[resolveFile] 逐段校验，显式拒绝空段 / `.` / `..`，不依赖 SAF `findFile`
 * 的 fail-closed 行为（纵深防御）。
 *
 * **可测性**：本类依赖 Android [ContentResolver]，JVM 单测不可用；工具逻辑由 [FileSystemAccess]
 * 接口 + 内存 fake 覆盖，本类保持薄封装，真机验证。
 *
 * **线程**：IO 操作统一切到 [Dispatchers.IO]，避免阻塞主线程。
 */
class SafFileAccess(
    private val context: Context
) : FileSystemAccess {

    private val contentResolver: ContentResolver get() = context.contentResolver

    /** 逻辑目录名 → SAF 树 URI（用户授权后注入）。原子快照，唯一事实源（C1）。 */
    private val roots = MutableStateFlow<Map<String, Uri>>(emptyMap())

    /** 当前已授权根目录名列表（响应式，供 UI 展示与刷新）。 */
    val rootsFlow: StateFlow<Map<String, Uri>> = roots.asStateFlow()

    /**
     * 注册一个授权根目录。
     *
     * @param name 逻辑目录名（工具参数首段）
     * @param treeUri SAF 树 URI（`ACTION_OPEN_DOCUMENT_TREE` 返回值）
     */
    fun addRoot(name: String, treeUri: Uri) {
        roots.update { it + (name to treeUri) }
    }

    /** 移除一个授权根目录。 */
    fun removeRoot(name: String) {
        roots.update { it - name }
    }

    /** 取回指定根目录对应的 SAF 树 URI（供撤销时释放持久化授权，S1）。 */
    fun uriFor(name: String): Uri? = roots.value[name]

    override suspend fun listAllowedDirectories(): List<String> = withContext(Dispatchers.IO) {
        roots.value.keys.toList()
    }

    /** 是否已授权至少一个根目录（UXR3 问题 8，ADR-023）。 */
    override suspend fun hasAuthorizedRoots(): Boolean = withContext(Dispatchers.IO) {
        roots.value.isNotEmpty()
    }

    override suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val file = resolveFile(path) ?: throw IOException("文件不存在：$path")
        if (file.isFile) {
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("无法读取文件：$path")
        } else {
            throw IOException("不是文件：$path")
        }
    }

    override suspend fun readMultipleFiles(paths: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            paths.associateWith { path ->
                try {
                    readFile(path)
                } catch (e: Exception) {
                    "<读取失败>"
                }
            }
        }

    override suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = resolveFile(path) ?: throw IOException("目录不存在：$path")
        if (!dir.isDirectory) throw IOException("不是目录：$path")
        toEntries(dir)
    }

    override suspend fun directoryTree(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val root = resolveRootFor(path) ?: throw IOException("路径不在授权目录内：$path")
        collectTree(root, path)
    }

    override suspend fun searchFiles(path: String, query: String, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            val dir = resolveFile(path) ?: throw IOException("目录不存在：$path")
            if (!dir.isDirectory) throw IOException("不是目录：$path")
            dir.listFiles()
                .filter { !it.isDirectory && it.name?.contains(query, ignoreCase = true) == true }
                .take(limit.coerceIn(1, MAX_SEARCH_LIMIT))
                .mapNotNull { it.name }
        }

    override suspend fun getFileInfo(path: String): FileEntry = withContext(Dispatchers.IO) {
        val file = resolveFile(path) ?: throw IOException("路径不存在：$path")
        toEntry(file)
    }

    override suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = path.trimStart('/')
        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { !isSafeSegment(it) }) return@withContext false
        val rootName = segments.first()
        val rel = segments.drop(1)
        val root = roots.value[rootName] ?: return@withContext false
        var dir = DocumentFile.fromTreeUri(context, root) ?: return@withContext false
        // 定位到目标父目录（逐级查找，不存在则创建）
        for (seg in rel.dropLast(1)) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(seg)
                ?: return@withContext false
        }
        val fileName = rel.lastOrNull() ?: return@withContext false
        val target = dir.findFile(fileName) ?: dir.createFile("application/octet-stream", fileName)
            ?: return@withContext false
        contentResolver.openOutputStream(target.uri, "w")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: return@withContext false
        true
    }

    /** 将逻辑路径首段解析为树 URI，并返回树 DocumentFile；根不存在返回 null。 */
    private fun resolveRoot(path: String): DocumentFile? {
        val rootName = path.trimStart('/').substringBefore('/')
        val uri = roots.value[rootName] ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    /** 解析逻辑路径到具体文件/目录；返回 null 表示路径不存在、根未授权或含非法段（S2）。 */
    private fun resolveFile(path: String): DocumentFile? {
        val trimmed = path.trimStart('/')
        val rootName = trimmed.substringBefore('/')
        val root = resolveRoot(trimmed) ?: return null
        val rel = trimmed.substringAfter('/')
        if (rel.isEmpty()) return root
        var current = root
        for (seg in rel.split('/')) {
            if (!isSafeSegment(seg)) return null
            current = current.findFile(seg) ?: return null
        }
        return current
    }

    /** 解析逻辑路径对应的根 DocumentFile（供 directory_tree 遍历）。 */
    private fun resolveRootFor(path: String): DocumentFile? = resolveRoot(path)

    /** 路径段白名单校验：非空、非 `.` / `..`（S2，纵深防御）。 */
    private fun isSafeSegment(seg: String): Boolean =
        seg.isNotEmpty() && seg != "." && seg != ".." && !seg.contains('/')

    private fun toEntries(dir: DocumentFile): List<FileEntry> =
        dir.listFiles().map { toEntry(it) }

    private fun toEntry(file: DocumentFile): FileEntry {
        val size = querySize(file.uri)
        return FileEntry(
            uri = file.uri.toString(),
            name = file.name ?: "",
            isDirectory = file.isDirectory,
            size = size
        )
    }

    private fun collectTree(node: DocumentFile, logicalPath: String): List<FileEntry> {
        val result = mutableListOf(toEntry(node))
        node.listFiles().forEach { child ->
            val childPath = if (logicalPath.isEmpty()) child.name.orEmpty() else "$logicalPath/${child.name}"
            if (child.isDirectory) {
                result += collectTree(child, childPath)
            } else {
                result += toEntry(child)
            }
        }
        return result
    }

    /** 查询文件大小（OpenableColumns.SIZE），不可用时回退 0。 */
    private fun querySize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private companion object {
        const val MAX_SEARCH_LIMIT = 100
    }
}