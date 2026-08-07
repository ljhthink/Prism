package io.prism.ingestion

import io.prism.data.KnowledgeBaseRepository
import io.prism.data.KnowledgeChunk
import io.prism.document.Chunker
import io.prism.document.DocumentParseException
import io.prism.document.DocumentParserRegistry
import io.prism.embedding.Embedder
import io.prism.embedding.EmbeddingException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 摄入管线 —— 串联 [DocumentParserRegistry] → [Chunker] → [Embedder] → [KnowledgeBaseRepository]，
 * 完成文档解析→切片→嵌入→入库全链路（US-016，ADR-009）。
 *
 * **架构**（ADR-009 5.1，仿 LlamaIndex IngestionPipeline transformations 链式）：
 * ```
 * InputStream ──parse──▶ text ──chunk──▶ List<String> ──embed──▶ KnowledgeChunk ──addChunk──▶ ObjectBox
 * ```
 *
 * **进度观察**（ADR-009 5.3，AC-2）：[ingest] 返回 `Flow<IngestionEvent>`，调用方 collect 即可实时观察。
 * chunk 边界 emit 事件，避免 OnnxEmbedder 锁竞争。
 *
 * **嵌入失败降级**（ADR-009 5.4，AC-3）：单 chunk 嵌入抛 [EmbeddingException] 时，
 * `embedding = null` 仍入库（HNSW 索引自动排除），emit [IngestionEvent.ChunkSkipped]，
 * 继续处理下一 chunk。**不 retry、不 fail-fast**。
 *
 * **致命错误**：[DocumentParseException]（无法解析→无文本）或其他不可恢复异常 → emit [IngestionEvent.Failed] 终止管线。
 *
 * **事务边界**（ADR-009 5.5）：chunk 级独立 [KnowledgeBaseRepository.addChunk]，不强制文档级事务。
 * 嵌入是昂贵操作，文档级事务中途失败会丢失已嵌入结果。
 *
 * **协程取消**（ADR-009 5.6）：`flow {}` 内每个 chunk 边界 `coroutineContext.ensureActive()` 检查取消。
 * OnnxEmbedder.embed 持锁不可中断（BR-concurrency-002），单次 ~100ms，最坏延迟可接受。
 * **禁止 catch CancellationException**（Kotlin 协程铁律），catch 块只捕获 [EmbeddingException]。
 *
 * **InputStream 生命周期**（ADR-009 5.7）：[ingest] 内 `input.use {}` 保证关闭，
 * 协程取消时 finally 也会关闭（close 非 suspend，可在 Cancelling 状态执行）。
 *
 * **线程安全**：本类无状态（4 个组件均线程安全），可安全跨协程复用。
 *
 * US-016 验收标准：
 * 1. IngestionPipeline：解析→切片→嵌入→写入指定库
 * 2. 摄入进度与错误可观察
 * 3. 嵌入为 null 的片段不建索引并提示
 * 4. 摄入管线集成测试通过
 * 5. Typecheck passes
 *
 * @param parserRegistry 文档解析器注册表（US-012）
 * @param chunker 文本切片器（US-013）
 * @param embedder 嵌入引擎（US-014）
 * @param repository 知识库仓库（US-015，提供 addChunk 写入）
 */
class IngestionPipeline(
    private val parserRegistry: DocumentParserRegistry,
    private val chunker: Chunker,
    private val embedder: Embedder,
    private val repository: KnowledgeBaseRepository
) {

    /**
     * 执行文档摄入：解析→切片→嵌入→写入指定库，返回事件流。
     *
     * **事件序列**（见 [IngestionEvent] KDoc）：
     * - happy path: `Started → Parsed → Chunked → ChunkEmbedded×N → Completed`
     * - 降级 path: `Started → Parsed → Chunked → (ChunkEmbedded | ChunkSkipped)×N → Completed`
     * - 致命 path: `Started → Failed`
     *
     * **空文档处理**：解析后文本为空或切片结果为空列表时，emit `Completed`（totalChunks=0），
     * 不视为错误（用户可能导入空文件，UI 据此提示「文档无有效内容」）。
     *
     * **chunk title 约定**：`${documentTitle}#${index + 1}`，便于 US-017 检索结果标注来源。
     *
     * @param fileName 文件名（含扩展名），用于解析器分发与错误溯源
     * @param input 文档输入流，**由本方法负责关闭**（use {}）
     * @param knowledgeBaseId 目标知识库 id（0L=默认库，>0=自建库，禁止负数）
     * @param documentTitle 文档标题（用于 chunk title 前缀），默认从 [fileName] 去扩展名
     * @return 事件流，调用方 collect 观察进度
     */
    fun ingest(
        fileName: String,
        input: InputStream,
        knowledgeBaseId: Long,
        documentTitle: String = defaultTitle(fileName)
    ): Flow<IngestionEvent> = flow {
        // 参数校验（M1 修复，TKN-US016-GUARDRAIL-001）：
        // require 必须在 emit(Started) 之前 fail-fast，但 input 关闭契约仍须履行。
        // 故 require 失败前先 close input（吞 close 异常避免掩盖 IllegalArgumentException）。
        if (knowledgeBaseId < 0) {
            try {
                input.close()
            } catch (_: Exception) {
                // 忽略 close 异常，避免掩盖 require 的 IllegalArgumentException
            }
            require(knowledgeBaseId >= 0) {
                "knowledgeBaseId 不能为负数（收到 $knowledgeBaseId）"
            }
        }

        val startMs = System.currentTimeMillis()
        emit(IngestionEvent.Started)

        try {
            // 1. 解析（DocumentParser.parse 契约：调用方负责关闭 InputStream → use {}）
            val text = input.use { stream ->
                val parser = parserRegistry.parserFor(fileName)
                parser.parse(stream)
            }
            emit(IngestionEvent.Parsed(text.length))

            // 2. 切片
            val chunks = chunker.chunk(text)
            emit(IngestionEvent.Chunked(chunks.size))

            // 3. 空文档：正常完成，不视为错误
            if (chunks.isEmpty()) {
                emit(IngestionEvent.Completed(IngestionResult(
                    totalChunks = 0,
                    embeddedChunks = 0,
                    skippedChunks = 0,
                    skippedDetails = emptyList(),
                    knowledgeBaseId = knowledgeBaseId,
                    documentTitle = documentTitle,
                    durationMs = System.currentTimeMillis() - startMs
                )))
                return@flow
            }

            // 4. 逐条嵌入 + 入库（chunk 级独立 put，ADR-009 5.5）
            val skipped = mutableListOf<SkippedChunk>()
            var embedded = 0
            chunks.forEachIndexed { index, chunkText ->
                // 协程取消检查（ADR-009 5.6）：embed 持锁不可中断，chunk 边界检查
                coroutineContext.ensureActive()

                val title = "${documentTitle}#${index + 1}"

                // 嵌入失败降级（ADR-009 5.4，AC-3）：catch EmbeddingException → null → 仍入库
                val embedding: FloatArray? = try {
                    embedder.embed(chunkText)
                } catch (e: EmbeddingException) {
                    // BR-error-handling-004：catch 须记录可诊断信息，不静默吞
                    // EmbeddingException 不含密钥/请求体，stage 枚举可安全暴露
                    val reason = "嵌入失败: ${e.stage}"
                    skipped.add(SkippedChunk(index, title, reason))
                    emit(IngestionEvent.ChunkSkipped(index, chunks.size, title, reason))
                    null
                }

                // 无论嵌入成功/失败都入库（失败时 embedding=null，HNSW 自动不建索引）
                val chunk = KnowledgeChunk(
                    title = title,
                    content = chunkText,
                    embedding = embedding,
                    knowledgeBaseId = knowledgeBaseId
                )
                repository.addChunk(chunk)

                if (embedding != null) {
                    embedded++
                    emit(IngestionEvent.ChunkEmbedded(index, chunks.size, title))
                }
            }

            // 5. 完成
            emit(IngestionEvent.Completed(IngestionResult(
                totalChunks = chunks.size,
                embeddedChunks = embedded,
                skippedChunks = skipped.size,
                skippedDetails = skipped,
                knowledgeBaseId = knowledgeBaseId,
                documentTitle = documentTitle,
                durationMs = System.currentTimeMillis() - startMs
            )))
        } catch (e: DocumentParseException) {
            // 致命错误：无法解析文档 → 无文本可处理 → 终止管线
            // Failed.throwable 仅供调用方日志/调试，UI 须展示通用安全文案（M2，不泄露内部路径/堆栈）
            emit(IngestionEvent.Failed(e))
        } catch (e: CancellationException) {
            // Kotlin 协程铁律：CancellationException 必须重新抛出，不可吞（ADR-009 5.6）
            // 此分支必须位于 catch(Exception) 之前，否则会被吞
            throw e
        } catch (e: IllegalArgumentException) {
            // 编程错误（如 repository.addChunk 内部 require 失败）：直接抛给调用方，不走 Failed 事件
            // input 已被 use {} 关闭，资源安全
            throw e
        } catch (e: Exception) {
            // 兜底归一化（BR-error-handling-004）：其他不可恢复异常（如 ObjectBox 写入失败、OOM）
            // Failed.throwable 仅供日志，UI 须展示通用安全文案（M2）
            emit(IngestionEvent.Failed(e))
        }
    }

    companion object {
        /** 从文件名提取默认文档标题（去扩展名）。 */
        private fun defaultTitle(fileName: String): String {
            val lastSep = fileName.lastIndexOfAny(charArrayOf('/', '\\'))
            val base = if (lastSep >= 0) fileName.substring(lastSep + 1) else fileName
            val dot = base.lastIndexOf('.')
            return if (dot > 0) base.substring(0, dot) else base
        }
    }
}
