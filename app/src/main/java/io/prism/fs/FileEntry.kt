package io.prism.fs

/**
 * 文件系统条目 —— 描述目录中的一个文件或子目录（ADR-006 5.3）。
 *
 * 由 [FileSystemAccess] 的具体实现（SAF / 内存 fake）产出，供
 * [io.prism.fs.FilesystemMcpServer] 的 list_directory / directory_tree / get_file_info 工具渲染。
 *
 * @param uri 该条目的访问标识（SAF 实现为已解析的 SAF URI；内存实现为逻辑路径）
 * @param name 条目名称（不含路径）
 * @param isDirectory 是否为目录
 * @param size 文件大小（字节）；目录为 0
 */
data class FileEntry(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)