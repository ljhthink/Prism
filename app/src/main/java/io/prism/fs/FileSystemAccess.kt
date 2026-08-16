package io.prism.fs

/**
 * 文件访问层抽象 —— 屏蔽 Storage Access Framework（SAF）与内存实现的差异（ADR-006 5.3）。
 *
 * SAF 依赖 Android [android.content.Context]/[android.content.ContentResolver]，JVM 单测不可用；
 * 故抽象此接口，使 [io.prism.fs.FilesystemMcpServer] 的工具处理器可独立测试。
 *
 * **路径模型**：所有方法以「逻辑路径」为参数（如 `notes/readme.md`），逻辑路径由各实现
 * 经「根目录注册表」解析：
 * - 生产实现 [SafFileAccess]：将逻辑路径首段映射到用户经 `ACTION_OPEN_DOCUMENT_TREE`
 *   授权的 SAF 树 URI，后续段在树内逐级导航。
 * - 测试实现 InMemoryFileAccess：以内存 Map 树存储，逻辑路径即内存键。
 *
 * **安全**：本接口只暴露授权根目录内的可见范围；单次操作是否放行由
 * [ToolConfirmationGate] 在工具处理器层决定（ADR-006 5.4）。
 */
interface FileSystemAccess {

    /** 列出当前授权可见的根目录名称（对应 `list_allowed_directories` 工具）。 */
    suspend fun listAllowedDirectories(): List<String>

    /** 是否已授权至少一个根目录（UXR3 问题 8，ADR-023；未授权时目录/文件工具应给出明确提示）。 */
    suspend fun hasAuthorizedRoots(): Boolean

    /** 读取单个文件内容（对应 `read_file` 工具）。 */
    suspend fun readFile(path: String): String

    /** 批量读取多个文件内容（对应 `read_multiple_files` 工具），返回 路径 → 内容。 */
    suspend fun readMultipleFiles(paths: List<String>): Map<String, String>

    /** 列出目录下的直接子条目（对应 `list_directory` 工具）。 */
    suspend fun listDirectory(path: String): List<FileEntry>

    /** 递归列出目录树（对应 `directory_tree` 工具）。 */
    suspend fun directoryTree(path: String): List<FileEntry>

    /** 在目录内按关键词搜索文件名（对应 `search_files` 工具），最多返回 [limit] 条。 */
    suspend fun searchFiles(path: String, query: String, limit: Int): List<String>

    /** 获取单个文件/目录的元信息（对应 `get_file_info` 工具）。 */
    suspend fun getFileInfo(path: String): FileEntry

    /** 写入文件（对应 `write_file` 工具）；返回是否成功。 */
    suspend fun writeFile(path: String, content: String): Boolean
}