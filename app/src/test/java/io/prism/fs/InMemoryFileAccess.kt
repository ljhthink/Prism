package io.prism.fs

import io.prism.fs.FileEntry
import io.prism.fs.FileSystemAccess
import java.io.IOException

/**
 * 内存版 [FileSystemAccess] 测试 fake（ADR-006 5.3）。
 *
 * 以内存树存储文件系统，逻辑路径即树中的节点键（首段为根目录名，如 `notes/readme.md`）。
 * 用于在 JVM 单测中验证 [FilesystemMcpServer] 的工具处理器逻辑，弥补 [SafFileAccess]
 * 依赖 Android [android.content.ContentResolver] 无法在 JVM 单测中实例化的问题。
 *
 * **语义对齐**：与 [SafFileAccess] 保持一致——目录可缺省创建中间路径、search 仅匹配直接子项、
 * 目录 size 为 0、文件 size 为内容字节数。
 */
class InMemoryFileAccess : FileSystemAccess {

    private class Node(
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        var content: String = "",
        val children: MutableMap<String, Node> = linkedMapOf()
    )

    /** 虚拟根节点：其直接子节点即「授权根目录」。 */
    private val vRoot = Node(path = "", name = "", isDirectory = true)

    /** 创建目录（含中间目录），顺带注册根目录。 */
    fun addDirectory(path: String): InMemoryFileAccess {
        ensureDir(path)
        return this
    }

    /** 创建文件（缺省中间目录），并写入内容。 */
    fun addFile(path: String, content: String): InMemoryFileAccess {
        val segments = segments(path)
        if (segments.isEmpty()) return this
        val name = segments.last()
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = if (parentPath.isEmpty()) vRoot else ensureDir(parentPath)
        val fullPath = segments.joinToString("/")
        parent.children[name] = Node(fullPath, name, isDirectory = false, content = content)
        return this
    }

    /** 确保某目录存在（含中间目录），返回其节点。 */
    private fun ensureDir(path: String): Node {
        var cur = vRoot
        var curPath = ""
        for (seg in segments(path)) {
            curPath = if (curPath.isEmpty()) seg else "$curPath/$seg"
            cur = cur.children[seg]?.takeIf { it.isDirectory }
                ?: cur.children.getOrPut(seg) { Node(curPath, seg, isDirectory = true) }
        }
        return cur
    }

    /** 将逻辑路径切为若干段（忽略空段与首尾斜杠）。 */
    private fun segments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotBlank() }

    /** 按逻辑路径解析节点；不存在返回 null。 */
    private fun resolve(path: String): Node? {
        var cur = vRoot
        for (seg in segments(path)) {
            cur = cur.children[seg] ?: return null
        }
        return cur
    }

    override suspend fun listAllowedDirectories(): List<String> =
        vRoot.children.values.filter { it.isDirectory }.map { it.name }

    override suspend fun hasAuthorizedRoots(): Boolean =
        listAllowedDirectories().isNotEmpty()

    override suspend fun readFile(path: String): String {
        val node = resolve(path) ?: throw IOException("文件不存在：$path")
        if (node.isDirectory) throw IOException("不是文件：$path")
        return node.content
    }

    override suspend fun readMultipleFiles(paths: List<String>): Map<String, String> =
        paths.associateWith { path ->
            try {
                readFile(path)
            } catch (e: Exception) {
                "<读取失败>"
            }
        }

    override suspend fun listDirectory(path: String): List<FileEntry> {
        val node = resolve(path) ?: throw IOException("目录不存在：$path")
        if (!node.isDirectory) throw IOException("不是目录：$path")
        return node.children.values.map { toEntry(it) }
    }

    override suspend fun directoryTree(path: String): List<FileEntry> {
        val node = resolve(path) ?: throw IOException("目录不存在：$path")
        val result = mutableListOf<FileEntry>()

        fun walk(n: Node) {
            result.add(toEntry(n))
            n.children.values.forEach { walk(it) }
        }

        walk(node)
        return result
    }

    override suspend fun searchFiles(path: String, query: String, limit: Int): List<String> {
        val node = resolve(path) ?: throw IOException("目录不存在：$path")
        if (!node.isDirectory) throw IOException("不是目录：$path")
        return node.children.values
            .filter { !it.isDirectory && it.name.contains(query, ignoreCase = true) }
            .take(limit.coerceAtLeast(1))
            .map { it.name }
    }

    override suspend fun getFileInfo(path: String): FileEntry {
        val node = resolve(path) ?: throw IOException("路径不存在：$path")
        return toEntry(node)
    }

    override suspend fun writeFile(path: String, content: String): Boolean {
        val parts = segments(path)
        if (parts.isEmpty()) return false
        val name = parts.last()
        // 与 SafFileAccess 对齐：目标父目录缺省则逐级创建。
        var parent = vRoot
        var curPath = ""
        for (seg in parts.dropLast(1)) {
            curPath = if (curPath.isEmpty()) seg else "$curPath/$seg"
            val next = parent.children[seg]
            parent = if (next != null && next.isDirectory) next
            else parent.children.getOrPut(seg) { Node(curPath, seg, isDirectory = true) }
        }
        val existing = parent.children[name]
        if (existing?.isDirectory == true) return false
        val fullPath = parts.joinToString("/")
        parent.children[name] = existing?.apply { this.content = content }
            ?: Node(fullPath, name, isDirectory = false, content = content)
        return true
    }

    private fun toEntry(node: Node): FileEntry = FileEntry(
        uri = node.path,
        name = node.name,
        isDirectory = node.isDirectory,
        size = if (node.isDirectory) 0L else node.content.encodeToByteArray().size.toLong()
    )
}