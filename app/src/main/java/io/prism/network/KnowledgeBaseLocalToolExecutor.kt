package io.prism.network

import android.util.Log
import io.prism.data.KnowledgeBaseRepository
import io.prism.embedding.Embedder
import io.prism.skill.LocalToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 知识库本地工具执行器（UXR4 问题 2/3，ADR-024 子决策 B）—— 实现 [LocalToolExecutor] 接口。
 *
 * **背景**：考古确认知识库**没有任何面向 LLM 的 MCP/工具接口**（仅 RAG 自动注入），LLM 感知不到
 * 知识库能力，才会把"知识库里有什么"误路由到 Filesystem 工具（问题 2）；RAG 单次 top-k + 阈值 0.5
 * 双重收窄导致"只见第一篇"（问题 3）。本执行器为知识库提供**主动查询**工具，LLM 可枚举 / 检索 /
 * 读取知识库（对齐 karpathy-LLM.md 的 index + 主动查询思想，见考古报告 §5 建议）。
 *
 * **工具命名空间**（ADR-024）：`knowledge_base__` 前缀，与 `cross_app__` / `web_search__` 平行。
 * - [TOOL_SEARCH]（`knowledge_base__search`）：向量语义检索知识库（query → embed → top-k）
 * - [TOOL_LIST_DOCUMENTS]（`knowledge_base__list_documents`）：枚举全部文档标题（类似 wiki index）
 * - [TOOL_GET_DOCUMENT_CONTENT]（`knowledge_base__get_document_content`）：获取指定文档全文
 *
 * **实现**：
 * - 依赖 [Embedder]（向量化查询）+ [KnowledgeBaseRepository]（检索/枚举/读取）
 * - 检索复用既有 `search(query, k, knowledgeBaseId)` 链路，与 RAG 相同的向量语义
 * - `knowledgeBaseId` 参数：`0`=默认库，`-1`/缺省=全库（对齐 RAG AllLibraries 语义）
 *
 * **安全边界**（对齐 WebSearchLocalToolExecutor / MCP 工具）：
 * - 结果文本回灌 LLM 前加「知识库内容」边界标记（知识库为用户自上传资料，非外部不可信内容）
 * - 单条片段 / 全文截断防 token 溢出
 * - 不向 LLM 暴露内部路径 / 堆栈（CWE-209）
 * - CancellationException 重抛（BR-error-handling-007）
 *
 * **降级策略**：embed 失败 / 检索失败 / 参数缺失均返回描述性文案（不抛异常），
 * 由 SkillExecutor 回灌 LLM 决定降级。
 *
 * **可测性**（BR-testing-004）：依赖注入解耦，测试可注入 fake Embedder + fake 仓库纯 JVM 验证。
 *
 * @param embedder 端侧嵌入引擎（向量化查询，FULL/STANDARD 档为 OnnxEmbedder，MINIMAL/CHAT_ONLY 为 NullEmbedder）
 * @param knowledgeBaseRepository 知识库分库仓库（search / listDocuments / getDocumentContent）
 */
class KnowledgeBaseLocalToolExecutor(
    private val embedder: Embedder,
    private val knowledgeBaseRepository: KnowledgeBaseRepository
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName in HANDLED_TOOLS

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String =
        withContext(Dispatchers.IO) {
            when (toolName) {
                TOOL_SEARCH -> executeSearch(arguments)
                TOOL_LIST_DOCUMENTS -> executeListDocuments(arguments)
                TOOL_GET_DOCUMENT_CONTENT -> executeGetDocumentContent(arguments)
                else -> "未知知识库工具: $toolName"
            }
        }

    /**
     * 执行 `knowledge_base__search`：向量语义检索知识库。
     *
     * **参数**：
     * - `query`（必需）：查询文本
     * - `knowledgeBaseId`（可选）：`0`=默认库，`-1`/缺省=全库，`>0`=指定自建库
     * - `topK`（可选，1..10，默认 5）：返回片段数
     *
     * **返回**：按相似度降序的片段列表（含来源与相似度），供 LLM 作为回答事实依据。
     */
    private suspend fun executeSearch(args: Map<String, Any?>): String {
        val query = args["query"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return "缺少必需参数 query"
        val kbId = parseKbId(args["knowledgeBaseId"])
        val topK = (args["topK"] as? Number)?.toInt()?.coerceIn(MIN_TOP_K, MAX_TOP_K) ?: DEFAULT_TOP_K

        return try {
            // embed（BR-concurrency-002：OnnxEmbedder 内部持锁，与 RAG 路径串行）
            val vector = embedder.embed(query)
            // 全库检索（kbId=null）；默认库（0L）与自建库（>0）精确过滤
            val results = if (kbId == null) {
                knowledgeBaseRepository.search(vector, k = topK)
            } else {
                knowledgeBaseRepository.search(vector, k = topK, knowledgeBaseId = kbId)
            }
            if (results.isEmpty()) return "知识库中未找到与「$query」相关的片段"
            buildString {
                append("【知识库内容，来源为已上传的个人资料】\n")
                results.forEachIndexed { i, r ->
                    append("[来源${i + 1}] 文件=${r.documentTitle}")
                    r.chunkIndex?.let { append(" 片段=$it") }
                    append(" 相似度=${(r.similarity * 100).toInt() / 100.0}\n")
                    append(r.content.take(SNIPPET_MAX_LEN))
                    append("\n\n")
                }
                append("【END 知识库内容】")
            }.trimEnd()
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            // CWE-209：不暴露内部异常细节；记录 simpleName 供排查
            Log.w(LOG_TAG, "knowledge_base search failed: ${e::class.simpleName}")
            "知识库检索失败：${if (e is IllegalStateException) e.message?.take(MAX_ERR_LEN) ?: "嵌入模型不可用" else "请检查知识库状态"}"
        }
    }

    /**
     * 执行 `knowledge_base__list_documents`：枚举知识库全部文档标题。
     *
     * **参数**：
     * - `knowledgeBaseId`（可选）：`0`=默认库，`-1`/缺省=全库，`>0`=指定自建库
     *
     * **返回**：文档标题列表（供 LLM 回答"知识库里有什么"、规划后续检索）。
     */
    private suspend fun executeListDocuments(args: Map<String, Any?>): String {
        val kbId = parseKbId(args["knowledgeBaseId"])
        return try {
            val docs = if (kbId == null) {
                // 全库：聚合默认库 + 全部自建库
                val all = mutableListOf<String>()
                all.addAll(knowledgeBaseRepository.listDocuments(KnowledgeBaseRepository.DEFAULT_KB_ID))
                knowledgeBaseRepository.getAll().forEach { kb ->
                    all.addAll(knowledgeBaseRepository.listDocuments(kb.id))
                }
                all.distinct()
            } else {
                knowledgeBaseRepository.listDocuments(kbId)
            }
            if (docs.isEmpty()) return "知识库暂无资料，请先在「知识库」页导入文档"
            // Q3（guardrail TKN-UXR4-GUARDRAIL-001）：大文档库枚举结果截断，防 token 溢出
            val limited = docs.take(MAX_DOC_LIST_LEN)
            buildString {
                append("【知识库文档列表（共 ${docs.size} 篇）】\n")
                limited.forEachIndexed { i, doc -> append("${i + 1}. $doc\n") }
                if (docs.size > limited.size) append("…（其余 ${docs.size - limited.size} 篇省略，可指定 knowledgeBaseId 查询）")
                append("【END】")
            }.trimEnd()
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            Log.w(LOG_TAG, "knowledge_base list_documents failed: ${e::class.simpleName}")
            "知识库文档枚举失败"
        }
    }

    /**
     * 执行 `knowledge_base__get_document_content`：获取指定文档全文。
     *
     * **参数**：
     * - `documentTitle`（必需）：文档标题（来自 [TOOL_LIST_DOCUMENTS] 枚举结果）
     * - `knowledgeBaseId`（可选）：`0`=默认库，`-1`/缺省=全库（跨库查找首个匹配），`>0`=指定自建库
     *
     * **返回**：文档全文（分块按序号拼接，截断防 token 溢出）。
     */
    private suspend fun executeGetDocumentContent(args: Map<String, Any?>): String {
        val title = args["documentTitle"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return "缺少必需参数 documentTitle"
        val kbId = parseKbId(args["knowledgeBaseId"])
        return try {
            val content = if (kbId == null) {
                // 全库：跨库查找首个匹配文档
                val candidates = listOf(KnowledgeBaseRepository.DEFAULT_KB_ID) +
                    knowledgeBaseRepository.getAll().map { it.id }
                candidates.firstNotNullOfOrNull { kb ->
                    knowledgeBaseRepository.getDocumentContent(kb, title).takeIf { it.isNotBlank() }
                } ?: return "知识库中未找到文档「$title」"
            } else {
                knowledgeBaseRepository.getDocumentContent(kbId, title)
                    .takeIf { it.isNotBlank() }
                    ?: return "知识库中未找到文档「$title」"
            }
            buildString {
                append("【知识库文档：$title】\n")
                append(content.take(DOCUMENT_MAX_LEN))
                append("\n【END】")
            }.trimEnd()
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            Log.w(LOG_TAG, "knowledge_base get_document_content failed: ${e::class.simpleName}")
            "知识库文档读取失败"
        }
    }

    /**
     * 解析 `knowledgeBaseId` 参数：`null`/`-1` → 全库（null），`0` → 默认库，`>0` → 自建库。
     *
     * @return `null` 表示全库检索；`0` 表示默认库；`>0` 表示指定自建库
     */
    private fun parseKbId(raw: Any?): Long? = when {
        raw == null -> null // 缺省 → 全库
        else -> (raw as? Number)?.toLong()?.takeIf { it >= 0 } // -1 或非法 → null（全库）
    }

    companion object {
        /** 知识库工具命名空间前缀（ADR-024）。 */
        const val NAMESPACE_PREFIX = "knowledge_base__"

        /** 语义检索工具名。 */
        const val TOOL_SEARCH = "${NAMESPACE_PREFIX}search"

        /** 枚举文档工具名。 */
        const val TOOL_LIST_DOCUMENTS = "${NAMESPACE_PREFIX}list_documents"

        /** 获取文档全文工具名。 */
        const val TOOL_GET_DOCUMENT_CONTENT = "${NAMESPACE_PREFIX}get_document_content"

        /** 本执行器处理的所有工具名集合（O(1) 查表）。 */
        private val HANDLED_TOOLS = setOf(TOOL_SEARCH, TOOL_LIST_DOCUMENTS, TOOL_GET_DOCUMENT_CONTENT)

        /** 检索默认返回片段数。 */
        private const val DEFAULT_TOP_K = 5

        /** 检索 top-k 下限。 */
        private const val MIN_TOP_K = 1

        /** 检索 top-k 上限（防 token 溢出）。 */
        private const val MAX_TOP_K = 10

        /** 单条片段最大长度（字符，防 token 溢出）。 */
        private const val SNIPPET_MAX_LEN = 500

        /** 文档全文最大长度（字符，防 token 溢出）。 */
        private const val DOCUMENT_MAX_LEN = 5000

        /** Q3（guardrail TKN-UXR4-GUARDRAIL-001）：list_documents 返回文档数上限（防 token 溢出）。 */
        private const val MAX_DOC_LIST_LEN = 100

        /** 错误信息截断长度。 */
        private const val MAX_ERR_LEN = 100

        private const val LOG_TAG = "KnowledgeBaseTool"

        /**
         * 构建知识库工具的 [ToolDefinition] 列表（供 ConversationViewModel.buildTools 合并）。
         *
         * **工具描述**：明确与 Filesystem 的区分（本工具访问 Prism 知识库而非本地文件夹），
         * 并给出使用引导（先 list_documents 枚举，再按需 search / get_document_content）。
         */
        fun buildToolDefinitions(): List<ToolDefinition> = listOf(
            buildSearchToolDefinition(),
            buildListDocumentsToolDefinition(),
            buildGetDocumentContentToolDefinition()
        )

        /** 构建 `knowledge_base__search` 工具定义。 */
        private fun buildSearchToolDefinition(): ToolDefinition {
            val parameters = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "query" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("查询文本（简洁、明确，描述用户想从知识库获取的信息）")
                    )),
                    "knowledgeBaseId" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("知识库 id（可选）：0=默认库，-1 或省略=全库，>0=指定自建库"),
                        "minimum" to JsonPrimitive(-1)
                    )),
                    "topK" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("返回片段数（1-10，默认 5）"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    ))
                )),
                "required" to JsonArray(listOf(JsonPrimitive("query"))),
                "additionalProperties" to JsonPrimitive(false)
            ))
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_SEARCH,
                    description = "在 Prism 个人知识库中做语义检索（向量相似度），返回与查询最相关的片段及来源。" +
                        "当用户询问知识库中的内容、需要依据已上传资料回答、或确认知识库是否包含某信息时调用。" +
                        "注意：本工具访问 Prism 知识库（用户上传的个人资料），**不是** Filesystem 本地文件夹。",
                    parameters = parameters
                )
            )
        }

        /** 构建 `knowledge_base__list_documents` 工具定义。 */
        private fun buildListDocumentsToolDefinition(): ToolDefinition {
            val parameters = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "knowledgeBaseId" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("知识库 id（可选）：0=默认库，-1 或省略=全库，>0=指定自建库"),
                        "minimum" to JsonPrimitive(-1)
                    ))
                )),
                "additionalProperties" to JsonPrimitive(false)
            ))
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_LIST_DOCUMENTS,
                    description = "枚举 Prism 个人知识库中已保存的全部文档标题。当用户询问「知识库里有什么/保存了哪些资料」" +
                        "时调用，先获得文档清单，再按需用 knowledge_base__search 或 knowledge_base__get_document_content 深入。" +
                        "注意：本工具访问 Prism 知识库，**不是** Filesystem 本地文件夹。",
                    parameters = parameters
                )
            )
        }

        /** 构建 `knowledge_base__get_document_content` 工具定义。 */
        private fun buildGetDocumentContentToolDefinition(): ToolDefinition {
            val parameters = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "documentTitle" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("文档标题（来自 knowledge_base__list_documents 的枚举结果）")
                    )),
                    "knowledgeBaseId" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("知识库 id（可选）：0=默认库，-1 或省略=全库，>0=指定自建库"),
                        "minimum" to JsonPrimitive(-1)
                    ))
                )),
                "required" to JsonArray(listOf(JsonPrimitive("documentTitle"))),
                "additionalProperties" to JsonPrimitive(false)
            ))
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_GET_DOCUMENT_CONTENT,
                    description = "获取 Prism 个人知识库中指定文档的完整内容（按分块拼接）。当用户需要了解某篇已上传资料的" +
                        "完整内容、或 search 结果片段不足时调用。文档标题来自 knowledge_base__list_documents。",
                    parameters = parameters
                )
            )
        }
    }
}
