package io.prism.fs

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.prism.fs.FileEntry
import io.prism.fs.FileSystemAccess
import io.prism.fs.ToolConfirmationGate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 内置 Filesystem MCP Server（ADR-006 5.5）—— 承载 8 个文件工具，供进程内 Client 调用。
 *
 * **单例 Server**：工具仅注册一次；每个工具处理器先经 [ToolConfirmationGate.confirm] 确认，
 * 再调用 [FileSystemAccess] 执行，返回 [CallToolResult]（失败时 `isError=true`）。
 *
 * **工具集**（对齐 MCP Filesystem Server 子集，SAF 可映射）：
 * 只读：read_file / read_multiple_files / list_directory / directory_tree /
 *       search_files / get_file_info / list_allowed_directories
 * 写：write_file
 *
 * **安全**：确认门禁置于每个处理器入口（ADR-006 5.4），缺省拒绝（confirm=false 返回 isError）。
 * 错误结果不泄露内部堆栈/异常细节（CWE-209，对齐 McpClientManager）。
 */
class FilesystemMcpServer(
    private val fileSystemAccess: FileSystemAccess,
    private val confirmationGate: ToolConfirmationGate
) {

    /** 已注册全部工具的 MCP Server 单例。 */
    val server: Server = Server(
        serverInfo = Implementation(SERVER_NAME, SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    ) {
        addTool("read_file", "读取授权目录内单个文件内容", stringSchema("path")) { request ->
            val path = arg(request, "path") ?: return@addTool missingParam("read_file", "path")
            execute("read_file", request) { fileSystemAccess.readFile(path) }
        }

        addTool(
            "read_multiple_files",
            "批量读取多个文件内容",
            ToolSchema(
                schema = "object",
                properties = buildJsonObject {
                    put("paths", buildJsonObject { put("type", "array") })
                },
                required = listOf("paths")
            )
        ) { request ->
            val paths = argList(request, "paths")
            if (paths.isNullOrEmpty()) return@addTool missingParam("read_multiple_files", "paths")
            execute("read_multiple_files", request) {
                renderMap(fileSystemAccess.readMultipleFiles(paths))
            }
        }

        addTool("list_directory", "列出目录下直接子条目", stringSchema("path")) { request ->
            val path = arg(request, "path") ?: return@addTool missingParam("list_directory", "path")
            execute("list_directory", request) {
                renderEntries(fileSystemAccess.listDirectory(path))
            }
        }

        addTool("directory_tree", "递归列出目录树", stringSchema("path")) { request ->
            val path = arg(request, "path") ?: return@addTool missingParam("directory_tree", "path")
            execute("directory_tree", request) {
                renderEntries(fileSystemAccess.directoryTree(path))
            }
        }

        addTool(
            "search_files",
            "在目录内按关键词搜索文件名",
            ToolSchema(
                schema = "object",
                properties = buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("query", buildJsonObject { put("type", "string") })
                    put("limit", buildJsonObject { put("type", "integer") })
                },
                required = listOf("path", "query")
            )
        ) { request ->
            val path = arg(request, "path") ?: return@addTool missingParam("search_files", "path")
            val query = arg(request, "query") ?: return@addTool missingParam("search_files", "query")
            val limit = argInt(request, "limit") ?: DEFAULT_SEARCH_LIMIT
            execute("search_files", request) {
                fileSystemAccess.searchFiles(path, query, limit).joinToString("\n")
            }
        }

        addTool("get_file_info", "获取文件或目录元信息", stringSchema("path")) { request ->
            val path = arg(request, "path") ?: return@addTool missingParam("get_file_info", "path")
            execute("get_file_info", request) {
                renderEntry(fileSystemAccess.getFileInfo(path))
            }
        }

        addTool("list_allowed_directories", "列出当前授权可见的根目录") { request ->
            execute("list_allowed_directories", request) {
                fileSystemAccess.listAllowedDirectories().joinToString("\n")
            }
        }

        addTool(
            "write_file",
            "写入文件（需用户确认）",
            ToolSchema(
                schema = "object",
                properties = buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("content", buildJsonObject { put("type", "string") })
                },
                required = listOf("path", "content")
            )
        ) { request ->
            val path = arg(request, "path") ?: return@addTool missingParam("write_file", "path")
            val content = arg(request, "content") ?: return@addTool missingParam("write_file", "content")
            execute("write_file", request) {
                val ok = fileSystemAccess.writeFile(path, content)
                if (ok) "写入成功" else "写入失败"
            }
        }
    }

    /**
     * 统一执行入口：先经确认门禁，再执行文件操作。
     *
     * @param toolName 工具名（用于确认与错误文案）
     * @param request 调用请求
     * @param block 文件操作（返回成功文本）
     * @return 成功 [CallToolResult]；被拒绝或执行异常时返回 `isError=true`
     */
    private suspend fun execute(
        toolName: String,
        request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
        block: suspend () -> String
    ): CallToolResult {
        val allowed = confirmationGate.confirm(toolName, request.params.arguments?.toFlatMap() ?: emptyMap())
        if (!allowed) {
            return CallToolResult(
                content = listOf(TextContent("用户拒绝了 $toolName 调用")),
                isError = true
            )
        }
        return try {
            CallToolResult(content = listOf(TextContent(block())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CWE-209：不向工具结果暴露内部异常细节（e.message 可能含路径/堆栈）。
            CallToolResult(content = listOf(TextContent("工具执行出错")), isError = true)
        }
    }

    private fun missingParam(toolName: String, key: String): CallToolResult =
        CallToolResult(
            content = listOf(TextContent("缺少参数：$key")),
            isError = true
        )

    private fun renderEntries(entries: List<FileEntry>): String =
        entries.joinToString("\n") { renderEntry(it) }

    private fun renderEntry(entry: FileEntry): String {
        val kind = if (entry.isDirectory) "dir" else "file"
        return "(name=${entry.name}, kind=$kind, size=${entry.size})"
    }

    private fun renderMap(results: Map<String, String>): String =
        results.entries.joinToString("\n") { (k, v) -> "$k:\n$v" }

    private companion object {
        const val SERVER_NAME = "prism-filesystem"
        const val SERVER_VERSION = "1.0.0"
        const val DEFAULT_SEARCH_LIMIT = 10
    }
}

/** 依据字符串属性构建 [ToolSchema]（name/description/required 对齐 MCP filesystem）。 */
private fun stringSchema(vararg props: String): ToolSchema = ToolSchema(
    schema = "object",
    properties = buildJsonObject {
        props.forEach { put(it, buildJsonObject { put("type", "string") }) }
    },
    required = props.toList()
)

/** 从调用请求参数中提取字符串参数。 */
private fun arg(
    request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
    key: String
): String? = (request.params.arguments?.get(key) as? JsonPrimitive)?.content

/** 从调用请求参数中提取整数参数。 */
private fun argInt(
    request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
    key: String
): Int? = (request.params.arguments?.get(key) as? JsonPrimitive)?.let { it.content.toIntOrNull() }

/** 从调用请求参数中提取字符串列表参数。 */
private fun argList(
    request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
    key: String
): List<String>? {
    val el = request.params.arguments?.get(key) ?: return null
    return (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
}

/** 将 [JsonObject] 展平为 [Map] 供确认门禁展示（JsonElement 视为 Any）。 */
private fun JsonObject.toFlatMap(): Map<String, Any?> = mapValues { it.value as Any? }